"""The river pipeline's fixed-point loops on one kernel launch per step (csrc/river.cu), several steps per check.

`monotone_downstream` (river/geometry.py: WaterSurface, MonotoneTop) and the Centrelines hysteresis growth used to run
~30 (resp. 2) torch launches per step and a `torch.equal` -- a device sync -- after EVERY step, up to 128 / 64 steps.
Here a step is one launch that also flags whether anything changed, and the loop reads the flag every CHECK_EVERY
steps. Results are bit-identical: each step is the same arithmetic (torch.minimum's NaN rule, max-pool * weak), and a
step at the fixed point changes nothing, so running a few past convergence returns the same state; the step cap is
honoured exactly. tests/test_cuda_river.py pins both against the torch loops.
"""
from __future__ import annotations

import ctypes
import os

import torch

from terrain_slm.kernels import native

CHECK_EVERY = 8
ENABLED = os.getenv("TGM_FAST_RIVER", "1").strip().lower() not in ("0", "false", "off", "no")


class _Mono(ctypes.Structure):
    _fields_ = [("lv", ctypes.c_void_p), ("ups", ctypes.c_void_p), ("out", ctypes.c_void_p),
                ("changed", ctypes.c_void_p), ("H", ctypes.c_int), ("W", ctypes.c_int), ("step", ctypes.c_int)]


class _Grow(ctypes.Structure):
    _fields_ = [("grown", ctypes.c_void_p), ("weak", ctypes.c_void_p), ("out", ctypes.c_void_p),
                ("changed", ctypes.c_void_p), ("H", ctypes.c_int), ("W", ctypes.c_int), ("step", ctypes.c_int)]


def usable(t: torch.Tensor) -> bool:
    return ENABLED and t.is_cuda and native.cuda_available(t.device)


def _iterate(dev, state: torch.Tensor, steps: int, launch) -> torch.Tensor:
    """Run `launch(src, dst, changed, step)` up to `steps` times (ping-pong buffers), checking every CHECK_EVERY
    steps whether the last one changed anything; returns the final state."""
    if steps <= 0:
        return state
    a, b = state.contiguous(), torch.empty_like(state)
    changed = torch.zeros(steps, dtype=torch.int32, device=dev)
    s = 0
    with torch.cuda.device(dev):
        while s < steps:
            n = min(CHECK_EVERY, steps - s)
            for i in range(n):
                launch(a, b, changed, s + i)
                a, b = b, a
            s += n
            if not int(changed[s - 1]):          # the last step was at the fixed point: so is every later one
                break
    return a


def monotone_downstream(level: torch.Tensor, line: torch.Tensor, flow: torch.Tensor, iters: int) -> torch.Tensor:
    """river.geometry.monotone_downstream, natively (same contract, same numbers)."""
    from terrain_slm.river.geometry import _N8, _shift2
    inf = float("inf")
    lv = torch.where(line, level, torch.full_like(level, inf)).float()
    fl = torch.where(line, flow, torch.full_like(flow, inf))
    ups = torch.zeros(level.shape, dtype=torch.uint8, device=level.device)
    for k, (di, dj) in enumerate(_N8):
        ups |= ((_shift2(fl, di, dj, inf) < fl) & line).to(torch.uint8) * (1 << k)
    h, w = level.shape
    mod = native.cuda_module("river", ["mono_step", "grow_step"], level.device)
    grid = ((h * w + 255) // 256,)

    def launch(src, dst, changed, step):
        a = _Mono(src.data_ptr(), ups.data_ptr(), dst.data_ptr(), changed.data_ptr(), h, w, step)
        mod.launch("mono_step", grid, (256,), a)
    lv = _iterate(level.device, lv, iters, launch)
    return torch.where(line, lv, level)


def grow(grown: torch.Tensor, weak: torch.Tensor, steps: int) -> torch.Tensor:
    """The Centrelines loop `nxt = max_pool2d(grown, 3, 1, 1) * weak` until it stops changing (at most `steps`):
    (1, 1, H, W) 0/1 floats in and out."""
    h, w = grown.shape[-2:]
    weak = weak.contiguous()
    mod = native.cuda_module("river", ["mono_step", "grow_step"], grown.device)
    grid = ((h * w + 255) // 256,)

    def launch(src, dst, changed, step):
        a = _Grow(src.data_ptr(), weak.data_ptr(), dst.data_ptr(), changed.data_ptr(), h, w, step)
        mod.launch("grow_step", grid, (256,), a)
    return _iterate(grown.device, grown, steps, launch)
