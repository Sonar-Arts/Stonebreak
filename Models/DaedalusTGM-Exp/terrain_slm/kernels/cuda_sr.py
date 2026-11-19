"""SR node (models/sr.py, SR-30 at 1:2 / SR-15 at 1:1) on the native fp16 kernels: NativeDetail2Sampler run over a
Detail2-shaped view of the SR network.

SR is Detail2's topology on the same EDM2 magnitude-preserving layers (stem over [x_t, cond, ones], encoder blocks with
pixel norm, stride-2 down convs, nearest-up conv + 1x1 mp_cat merge, mid blocks, output conv with a learned gain,
multiplicative time embedding). The differences, all handled by the view:
  * embedding     mp_silu(emb(fourier(t))): one time, no MeanFlow h, no r
  * residual      mp_sum(x, y, t=0.3) without Detail2's +-256 clip (`clip_act = False`)
  * merge         mp_cat at t = 0.5;  no experts
Numbers: fp16 operands, fp32 accumulation and Euler state, like the detail sampler (the torch path samples under bf16
autocast); tests/test_cuda_sr.py measures both against fp32 on real windows.
"""
from __future__ import annotations

from types import SimpleNamespace

import torch

from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
from terrain_slm.models import sr as SRM
from terrain_slm.models.mp import mp_silu


class SRView:
    """The attributes NativeDetail2Sampler reads, taken from an `SR` (no copies: the same modules)."""

    detail2_like = True
    clip_act = False
    mean_flow = False

    def __init__(self, sr: SRM.SR):
        self.sr = sr
        self.cfg = SimpleNamespace(channels=tuple(sr.cfg.channels), res_balance=0.3, concat_balance=0.5)
        for name in ("stem", "enc", "down", "mid", "up", "merge", "dec", "out", "out_gain"):
            setattr(self, name, getattr(sr, name))
        self.adapters = {}

    @property
    def multiple(self) -> int:
        return self.sr.multiple

    def parameters(self):
        return self.sr.parameters()

    def embed(self, t: torch.Tensor, r=None) -> torch.Tensor:
        return mp_silu(self.sr.emb(self.sr.fourier(t)))


def supported(sr) -> bool:
    return isinstance(sr, SRM.SR) and NativeDetail2Sampler.supported(SRView(sr))


def native_sr_sampler(sr: SRM.SR, h: int, w: int, steps: int) -> NativeDetail2Sampler:
    """Sampler for one window shape: `__call__(cond, noise)` (graph replay) / `.eager(cond, noise)` -> (1, 1, H, W)
    fp32 encoded residual, like `models.sr.sample(sr, cond, noise, steps)`."""
    return NativeDetail2Sampler(SRView(sr), h, w, steps)

