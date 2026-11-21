"""Magnitude-preserving layers (EDM2: Karras et al., "Analyzing and Improving the Training Dynamics of Diffusion
Models", CVPR 2024), for the DaedalusTGM-Exp-2 networks.

Why: v4's detail sampler blew up on in-game windows (x -> inf at t ~ 0) because nothing bounds its activations:
plain convs with biases, unbounded FiLM, residual sums that grow with depth. EDM2's recipe keeps every activation
at unit magnitude by construction, which also makes low-precision (FP8) scales easy to fit:

  MPConv      weights forced to unit norm per output channel (in place, every training step) and divided by
              sqrt(fan_in) in the forward pass: unit-variance in, unit-variance out. No bias.
  mp_silu     silu(x) / 0.596: SiLU of a unit Gaussian has std 0.596.
  mp_sum      (a (1 - t) + b t) / sqrt((1 - t)^2 + t^2): a residual or skip blend that keeps unit magnitude.
  mp_cat      concatenation that weights the two parts and keeps the total magnitude.
  MPFourier   fixed random Fourier features of a scalar (time), cos/sin scaled by sqrt(2).
  normalize   per-pixel (channel-vector) normalisation.

Everything here folds into ordinary conv weights + constants at export (the normalised weight is a constant once
trained, the 1/0.596 and the mp_sum coefficients scale neighbouring weights), so the native kernels can run it.
"""
from __future__ import annotations

import math

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

SILU_GAIN = 1.0 / 0.596


def normalize(x: torch.Tensor, dim=None, eps: float = 1e-4) -> torch.Tensor:
    """x scaled to unit RMS over `dim` (default: all but the first axis): the EDM2 'normalize'."""
    if dim is None:
        dim = list(range(1, x.ndim))
    norm = torch.linalg.vector_norm(x, dim=dim, keepdim=True, dtype=torch.float32)
    norm = torch.add(eps, norm, alpha=np.sqrt(norm.numel() / x.numel()))
    return x / norm.to(x.dtype)


def pixel_norm(x: torch.Tensor, eps: float = 1e-4) -> torch.Tensor:
    """Unit RMS over channels at every pixel."""
    return normalize(x, dim=1, eps=eps)


def mp_silu(x: torch.Tensor) -> torch.Tensor:
    return F.silu(x) * SILU_GAIN


def mp_sum(a: torch.Tensor, b: torch.Tensor, t: float = 0.5) -> torch.Tensor:
    return a.lerp(b.to(a.dtype), t) / math.sqrt((1 - t) ** 2 + t ** 2)


def mp_cat(a: torch.Tensor, b: torch.Tensor, dim: int = 1, t: float = 0.5) -> torch.Tensor:
    na, nb = a.shape[dim], b.shape[dim]
    c = math.sqrt((na + nb) / ((1 - t) ** 2 + t ** 2))
    wa = c / math.sqrt(na) * (1 - t)
    wb = c / math.sqrt(nb) * t
    return torch.cat([wa * a, wb * b], dim=dim)


class MPFourier(nn.Module):
    """cos/sin of a scalar at fixed random frequencies; unit magnitude."""

    def __init__(self, channels: int, bandwidth: float = 1.0, seed: int = 0):
        super().__init__()
        g = torch.Generator().manual_seed(seed)
        self.register_buffer("freqs", 2 * np.pi * torch.randn(channels, generator=g) * bandwidth)
        self.register_buffer("phases", 2 * np.pi * torch.rand(channels, generator=g))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        y = x.to(torch.float32)[:, None] * self.freqs[None].to(torch.float32) + self.phases[None].to(torch.float32)
        return (y.cos() * np.sqrt(2)).to(x.dtype)


class MPConv(nn.Module):
    """Conv (or linear when kernel == ()) with forced weight normalisation and no bias."""

    def __init__(self, cin: int, cout: int, kernel: tuple = (3, 3), stride: int = 1):
        super().__init__()
        self.cout, self.kernel, self.stride = cout, kernel, stride
        self.weight = nn.Parameter(torch.randn(cout, cin, *kernel))

    def effective_weight(self, gain: float | torch.Tensor = 1.0) -> torch.Tensor:
        """The weight the forward pass uses: unit norm per output channel, / sqrt(fan_in), * gain. What export
        folds into a plain conv."""
        w = self.weight.to(torch.float32)
        w = normalize(w)
        return w * (gain / np.sqrt(w[0].numel()))

    def forward(self, x: torch.Tensor, gain: float | torch.Tensor = 1.0) -> torch.Tensor:
        # Pure (no in-place weight update here, so torch.func transforms -- MeanFlow's JVP -- can run it);
        # training calls force_weight_norm_() once per step instead.
        w = self.weight.to(torch.float32)
        w = normalize(w) * (gain / np.sqrt(w[0].numel()))
        w = w.to(x.dtype)
        if w.ndim == 2:
            return x @ w.t()
        assert w.ndim == 4
        return F.conv2d(x, w, stride=self.stride, padding=w.shape[-1] // 2)


@torch.no_grad()
def force_weight_norm_(module: nn.Module) -> None:
    """EDM2's forced weight normalisation: every MPConv's stored weight back to unit norm per output channel.
    Call once per optimizer step (before the forward), so weights cannot grow and the effective learning rate
    stays put."""
    for m in module.modules():
        if isinstance(m, MPConv):
            m.weight.copy_(normalize(m.weight.to(torch.float32)).to(m.weight.dtype))
