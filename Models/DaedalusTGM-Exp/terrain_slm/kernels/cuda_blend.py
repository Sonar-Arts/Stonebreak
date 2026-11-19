"""The generator's window cross-fades (overlap-add of canonical windows) as one kernel launch (csrc/blend.cu).

    out = overlap_add((C, H, W), items, fade, device)       # items: [(win (C, EH, EW), a0, b0), ...] in loop order

is bit-identical to the torch loop every cross-fade in world/generator*.py used to run --

    out = torch.zeros(C, H, W)
    for win, a0, b0 in items:                                # only the part of each window inside out
        out[:, r0:r1, s0:s1] += win[:, r0 - a0 : r1 - a0, s0 - b0 : s1 - b0] * fade[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0]

-- (fp32 product rounded, then fp32 add, in list order; csrc/blend.cu), without its 2-3 launches and Python slicing
per window (a 448-cell planner blend fetched ~250 windows: ~750 launches). `torch_overlap_add` is that loop: the
fallback (CPU, kernels off) and the reference the tests pin the kernel against.
"""
from __future__ import annotations

import ctypes
import os

import torch

from terrain_slm.kernels import native

MAXW = 64


class _Args(ctypes.Structure):
    _fields_ = [("out", ctypes.c_void_p), ("fade", ctypes.c_void_p), ("win", ctypes.c_void_p * MAXW),
                ("C", ctypes.c_int), ("H", ctypes.c_int), ("W", ctypes.c_int), ("EH", ctypes.c_int),
                ("EW", ctypes.c_int), ("n", ctypes.c_int), ("accumulate", ctypes.c_int),
                ("a0", ctypes.c_int * MAXW), ("b0", ctypes.c_int * MAXW)]


def torch_overlap_add(shape: tuple, items, fade: torch.Tensor, device) -> torch.Tensor:
    c, h, w = shape
    out = torch.zeros(c, h, w, device=device)
    eh, ew = fade.shape
    for win, a0, b0 in items:
        r0, r1 = max(0, a0), min(h, a0 + eh)
        s0, s1 = max(0, b0), min(w, b0 + ew)
        if r0 >= r1 or s0 >= s1:
            continue
        out[:, r0:r1, s0:s1] += (win.reshape(c, eh, ew)[:, r0 - a0 : r1 - a0, s0 - b0 : s1 - b0]
                                 * fade[r0 - a0 : r1 - a0, s0 - b0 : s1 - b0])
    return out


ENABLED = os.getenv("TGM_FAST_BLEND", "1").strip().lower() not in ("0", "false", "off", "no")   # A/B: 0 = torch loop


def _usable(device) -> bool:
    return ENABLED and torch.device(device).type == "cuda" and native.cuda_available(device)


def overlap_add(shape: tuple, items, fade: torch.Tensor, device) -> torch.Tensor:
    """(C, H, W) fp32: the cross-fade of `items` [(window (C, EH, EW) or (EH, EW) when C == 1, a0, b0), ...] (window
    origins in out's coordinates, in the order the windows are summed) with weights `fade` (EH, EW)."""
    if not _usable(device):
        return torch_overlap_add(shape, items, fade, device)
    c, h, w = shape
    eh, ew = fade.shape
    live = []
    for win, a0, b0 in items:
        if max(0, a0) >= min(h, a0 + eh) or max(0, b0) >= min(w, b0 + ew):
            continue                                       # no overlap: the loop skips it too
        assert win.dtype == torch.float32 and win.is_contiguous() and win.numel() == c * eh * ew, \
            (win.dtype, tuple(win.shape), (c, eh, ew))
        live.append((win, a0, b0))
    out = torch.empty(c, h, w, device=device) if live else torch.zeros(c, h, w, device=device)
    if not live:
        return out
    fade = fade.contiguous()
    dev = out.device
    mod = native.cuda_module("blend", ["blend_kernel"], dev)
    total = c * h * w
    grid = ((total + 255) // 256,)
    with torch.cuda.device(dev):         # the module's context must be current (another GPU may be the current one)
        _launch(mod, out, fade, live, c, h, w, eh, ew, grid)
    return out


def _launch(mod, out, fade, live, c, h, w, eh, ew, grid) -> None:
    for start in range(0, len(live), MAXW):
        part = live[start : start + MAXW]
        a = _Args()
        a.out, a.fade = out.data_ptr(), fade.data_ptr()
        a.C, a.H, a.W, a.EH, a.EW = c, h, w, eh, ew
        a.n, a.accumulate = len(part), int(start > 0)
        for k, (win, a0, b0) in enumerate(part):
            a.win[k] = win.data_ptr()
            a.a0[k], a.b0[k] = a0, b0
        mod.launch("blend_kernel", grid, (256,), a)
