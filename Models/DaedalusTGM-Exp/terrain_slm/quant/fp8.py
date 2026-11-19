"""FP8 fake quantisation for the EXP-2 networks (plan §1, P6): e4m3 weights per output channel and activations per
tensor, applied to every conv in a block of code by patching torch.nn.functional.conv2d (MPConv and nn.Conv2d both
call it). Used for post-training-quantisation measurements and as the student's forward in QAD (straight-through:
the rounding passes gradients unchanged).

    with fp8_convs(act="dynamic"):           # quantise every conv's weight and input in here
        v = model(x, t, cond)

FP8 e4m3 holds +-448 with 3 mantissa bits. MP layers keep activations near unit scale and weights unit-norm per
channel, which is what makes FP8 plausible here (v4's unbounded activations moved PTQ samples 28-42 m RMS).
"""
from __future__ import annotations

import contextlib

import torch
import torch.nn.functional as F

E4M3_MAX = 448.0
_ORIG_CONV2D = F.conv2d


def _q(x: torch.Tensor, scale: torch.Tensor) -> torch.Tensor:
    """Round to e4m3 at `scale` (broadcastable), straight-through for gradients."""
    y = (x.float() / scale).clamp(-E4M3_MAX, E4M3_MAX).to(torch.float8_e4m3fn).float() * scale
    return x + (y.to(x.dtype) - x).detach()


def quant_weight(w: torch.Tensor) -> torch.Tensor:
    amax = w.detach().abs().flatten(1).amax(1).clamp_min(1e-8).view(-1, *([1] * (w.ndim - 1)))
    return _q(w, amax / E4M3_MAX)


def quant_act(x: torch.Tensor, fixed_scale: float | None = None) -> torch.Tensor:
    if fixed_scale is not None:
        return _q(x, torch.tensor(fixed_scale, device=x.device))
    amax = x.detach().abs().amax().clamp_min(1e-8)
    return _q(x, amax / E4M3_MAX)


@contextlib.contextmanager
def fp8_convs(act: str = "dynamic", fixed_scale: float = 1.0, skip_in_channels: tuple = ()):
    """Quantise weight + input of every F.conv2d call inside the block. act: "dynamic" (per-tensor amax), "fixed"
    (one scale for every tensor: MP nets keep activations near unit scale), or "none" (weights only).
    `skip_in_channels`: convs whose weight has one of these input widths stay full precision (the stem, the output)."""
    def conv2d(x, w, *a, **k):
        if w.shape[1] in skip_in_channels:
            return _ORIG_CONV2D(x, w, *a, **k)
        wq = quant_weight(w)
        xq = x if act == "none" else quant_act(x, fixed_scale if act == "fixed" else None)
        return _ORIG_CONV2D(xq, wq, *a, **k)

    F.conv2d = conv2d
    try:
        yield
    finally:
        F.conv2d = _ORIG_CONV2D
