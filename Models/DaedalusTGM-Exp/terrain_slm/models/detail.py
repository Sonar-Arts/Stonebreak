"""Detail sampler: coarse cells -> block-resolution (60 m) terrain, sampled.

The v3 stack draws everything finer than a 240 m cell from the synth (isotropic noise per
descriptor band) and a small-receptive-field refiner, so 1-4 km valley networks never form
("orange peel") and lowlands with gentle controls come out flat. This model replaces
synth + refiner for heights. It is a conditional flow-matching UNet on 60 m samples -- the
game's block size, so nothing it draws is averaged away -- with a ~24 km receptive field,
trained on real DEMs from ~24 regions:

    x1 = asinh((dem60 - base60) / ASINH_M)       base60 = detail_base(degraded coarse cells)

The asinh keeps a 2 m plains ripple and a 1 km Alpine valley in one numeric range without a
scale field, so the model decides how hilly ground is from its conditioning (trend, slope,
wildness, climate, optional descriptors) instead of inheriting a regressed amplitude.

Conditioning (N_COND channels, all upsampled from cells except the base and its slope):
base height, base slope (2), descriptors (8), wildness, climate (4), drainage log flow and
river probability (2), presence flags for descriptors / wildness / climate / drainage (4).
Drainage conditioning is what ties valleys to rivers: training sees the real (dilated) flow
accumulation, inference the hydro sidecar's.

Convolution-only, no normalisation layers, channel counts multiples of 32 (native-kernel
rules); the generator runs canonical windows and cross-fades them.
"""
from __future__ import annotations

import math
from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P
from terrain_slm.models.refiner import timestep_embedding

DS = 2                       # native 30 m pixels per sample
SAMPLES_PER_CELL = D.CELL_PX // DS   # 4
SAMPLE_M = 60.0
ASINH_M = 10.0               # target compression knee (m)
BASE_SIGMA = 6.0             # samples: low-pass on the upsampled cell surface (like coarse_surface)
SLOPE_SCALE_M = 30.0         # metres per sample -> ~unit on steep ground

(C_BASE, C_SLOPE_X, C_SLOPE_Y) = range(3)
C_DESC = slice(3, 11)
C_WILD = 11
C_CLIMATE = slice(12, 16)
C_LOGACC, C_RIVER = 16, 17
C_HAS_DESC, C_HAS_WILD, C_HAS_CLIMATE, C_HAS_FLOW = 18, 19, 20, 21
N_COND = 22


@dataclass
class DetailConfig:
    channels: tuple = (96, 192, 288, 384, 512)
    blocks: int = 2
    temb: int = 128
    dropout: float = 0.1

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class ResBlock(nn.Module):
    """Pre-activation residual block, FiLM time conditioning, zero-initialised output."""

    def __init__(self, c: int, temb: int, dropout: float):
        super().__init__()
        self.conv1 = nn.Conv2d(c, c, 3, padding=1)
        self.film = nn.Linear(temb, 2 * c)
        self.drop = nn.Dropout(dropout)
        self.conv2 = nn.Conv2d(c, c, 3, padding=1)
        for m in (self.conv2, self.film):
            nn.init.zeros_(m.weight)
            nn.init.zeros_(m.bias)

    def forward(self, x, e):
        h = self.conv1(F.silu(x))
        scale, shift = self.film(e)[:, :, None, None].chunk(2, dim=1)
        h = h * (1 + scale) + shift
        return x + self.conv2(self.drop(F.silu(h)))


class Detail(nn.Module):
    def __init__(self, cfg: DetailConfig = DetailConfig()):
        super().__init__()
        self.cfg = cfg
        ch, te = cfg.channels, cfg.temb * 2
        rb = lambda c: ResBlock(c, te, cfg.dropout)
        self.temb = nn.Sequential(nn.Linear(cfg.temb, te), nn.SiLU(), nn.Linear(te, te))
        self.stem = nn.Conv2d(1 + N_COND, ch[0], 3, padding=1)
        self.enc = nn.ModuleList(nn.ModuleList(rb(c) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.down = nn.ModuleList(nn.Conv2d(ch[i], ch[i + 1], 3, stride=2, padding=1) for i in range(len(ch) - 1))
        self.mid = nn.ModuleList(rb(ch[-1]) for _ in range(2 * cfg.blocks))
        self.up = nn.ModuleList(nn.Conv2d(ch[i + 1], ch[i], 3, padding=1) for i in range(len(ch) - 1))
        self.merge = nn.ModuleList(nn.Conv2d(2 * c, c, 1) for c in ch[:-1])
        self.dec = nn.ModuleList(nn.ModuleList(rb(c) for _ in range(cfg.blocks)) for c in ch[:-1])
        self.out = nn.Conv2d(ch[0], 1, 3, padding=1)
        nn.init.zeros_(self.out.weight)
        nn.init.zeros_(self.out.bias)

    @property
    def multiple(self) -> int:
        return 2 ** (len(self.cfg.channels) - 1)

    def forward(self, x_t: torch.Tensor, t: torch.Tensor, cond: torch.Tensor) -> torch.Tensor:
        """x_t (B,1,H,W), t (B,), cond (B,N_COND,H,W) -> velocity; H, W multiples of `multiple`."""
        e = self.temb(timestep_embedding(t, self.cfg.temb))
        h = self.stem(torch.cat([x_t, cond], dim=1))
        skips = []
        for blocks, down in zip(self.enc, self.down):
            for b in blocks:
                h = b(h, e)
            skips.append(h)
            h = down(F.silu(h))
        for b in self.mid:
            h = b(h, e)
        for i in reversed(range(len(self.up))):
            h = self.up[i](F.interpolate(F.silu(h), scale_factor=2, mode="nearest"))
            h = self.merge[i](torch.cat([h, skips[i]], dim=1))
            for b in self.dec[i]:
                h = b(h, e)
        return self.out(F.silu(h))


# ----------------------------------------------------------------------------- shared helpers
def up_cells(x: torch.Tensor) -> torch.Tensor:
    """(B,C,hc,wc) per-cell field -> (B,C,hc*4,wc*4) samples, bilinear."""
    hc, wc = x.shape[-2:]
    return F.interpolate(x, size=(hc * SAMPLES_PER_CELL, wc * SAMPLES_PER_CELL), mode="bilinear", align_corners=False)


def detail_base(coarse_m: torch.Tensor) -> torch.Tensor:
    """Smooth sample surface from cell means (B,1,hc,wc) -> (B,1,4hc,4wc). Values within
    ~3 * BASE_SIGMA samples of the edge see reflected context: callers pass a margin."""
    return D.blur(up_cells(coarse_m), BASE_SIGMA)


def sample_slope(base: torch.Tensor) -> torch.Tensor:
    p = F.pad(base, (1, 1, 1, 1), mode="replicate")
    gx = (p[..., 1:-1, 2:] - p[..., 1:-1, :-2]) * 0.5
    gy = (p[..., 2:, 1:-1] - p[..., :-2, 1:-1]) * 0.5
    return torch.cat([gx, gy], dim=1) / SLOPE_SCALE_M


def encode(residual_m: torch.Tensor) -> torch.Tensor:
    return torch.asinh(residual_m / ASINH_M)


def decode(x: torch.Tensor) -> torch.Tensor:
    return ASINH_M * torch.sinh(x)


def build_cond(base: torch.Tensor, desc_n: torch.Tensor, wild_n: torch.Tensor, climate_n: torch.Tensor,
               logacc_n: torch.Tensor, river: torch.Tensor, has_desc: torch.Tensor, has_wild: torch.Tensor,
               has_climate: torch.Tensor, has_flow: torch.Tensor) -> torch.Tensor:
    """All spatial inputs already at sample resolution (B,C,H,W). `desc_n` normalised descriptors,
    `wild_n` planner-normalised wildness, `climate_n` P.climate_input fields, `logacc_n`
    log1p(acc) / P.LOGACC_SCALE, `river` probability; `has_*` (B,1,1,1) presence flags."""
    b, _, h, w = base.shape
    ones = torch.ones(b, 1, h, w, device=base.device, dtype=base.dtype)
    return torch.cat([
        base / P.HEIGHT_SCALE_M,
        sample_slope(base),
        desc_n * has_desc,
        wild_n * has_wild,
        climate_n * has_climate,
        logacc_n * has_flow,
        river * has_flow,
        ones * has_desc, ones * has_wild, ones * has_climate, ones * has_flow,
    ], dim=1)


@torch.no_grad()
def sample(model: Detail, cond: torch.Tensor, noise: torch.Tensor, steps: int = 16, t_start: float = 0.0) -> torch.Tensor:
    """Euler-integrate the flow from t_start to data (t=1); returns the encoded residual. `t_start` > 0
    starts at (1 - t_start) * noise -- the noise level x_t has there -- skipping the t ~ 0 regime that
    logit-normal training barely samples (see generator.DETAIL_T_START)."""
    x = (1.0 - t_start) * noise
    ts = torch.linspace(t_start, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((cond.shape[0],), float(ts[i]), device=cond.device)
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=cond.is_cuda):
            v = model(x, t, cond).float()
        x = x + (ts[i + 1] - ts[i]) * v
    return x


def count_params(m: nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())
