"""Hydrology sidecar: coarse height + climate (per cell) -> drainage (per cell).

The planner predicted rivers from its *trend* input, so nothing tied them to the valleys
that the relief sampler and planner actually produced. This model reads the final coarse
height instead, so drainage follows the real ground. Drainage is a deterministic function of
that ground, so plain regression suits it (unlike the descriptors, whose regression averages).

Outputs per cell (same units as the planner's, so rivers.py is unchanged):
  * log flow accumulation, log1p(precip-weighted upslope cells) / planner.LOGACC_SCALE,
    on the 3x3-dilated field the planner used,
  * river logit (dilated accumulation >= planner.RIVER_MIN_UPSLOPE_CELLS),
  * D8 flow direction logits (8 neighbours + terminal), for the water simulation.

A conv UNet (4 levels, receptive field ~100 cells = 24 km) at cell resolution: precise to
the cell, and tiled like the relief sampler (canonical windows, cross-faded).
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.models import planner as P

# Conditioning channels.
C_HEIGHT, C_SLOPE_X, C_SLOPE_Y, C_T0, C_TSEASON, C_PRECIP, C_PCV = range(7)
N_IN = 7
OUT_LOGACC, OUT_RIVER = 0, 1
OUT_D8 = slice(2, 11)
N_OUT = 11
SLOPE_SCALE_M = 100.0  # metres per cell -> ~unit


@dataclass
class HydroConfig:
    channels: tuple = (16, 24, 40, 56)
    blocks: int = 2

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class _Res(nn.Module):
    def __init__(self, c: int):
        super().__init__()
        self.conv1 = nn.Conv2d(c, c, 3, padding=1)
        self.conv2 = nn.Conv2d(c, c, 3, padding=1)
        nn.init.zeros_(self.conv2.weight)
        nn.init.zeros_(self.conv2.bias)

    def forward(self, x):
        return x + self.conv2(F.silu(self.conv1(F.silu(x))))


class Hydro(nn.Module):
    def __init__(self, cfg: HydroConfig = HydroConfig()):
        super().__init__()
        self.cfg = cfg
        ch = cfg.channels
        self.stem = nn.Conv2d(N_IN, ch[0], 3, padding=1)
        self.enc = nn.ModuleList(nn.Sequential(*[_Res(c) for _ in range(cfg.blocks)]) for c in ch[:-1])
        self.down = nn.ModuleList(nn.Conv2d(ch[i], ch[i + 1], 3, stride=2, padding=1) for i in range(len(ch) - 1))
        self.mid = nn.Sequential(*[_Res(ch[-1]) for _ in range(cfg.blocks)])
        self.up = nn.ModuleList(nn.Conv2d(ch[i + 1], ch[i], 3, padding=1) for i in range(len(ch) - 1))
        self.merge = nn.ModuleList(nn.Conv2d(2 * c, c, 3, padding=1) for c in ch[:-1])
        self.dec = nn.ModuleList(nn.Sequential(*[_Res(c) for _ in range(cfg.blocks)]) for c in ch[:-1])
        self.out = nn.Conv2d(ch[0], N_OUT, 3, padding=1)

    @property
    def multiple(self) -> int:
        return 2 ** (len(self.cfg.channels) - 1)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """(B, N_IN, H, W) -> (B, N_OUT, H, W); H, W multiples of `multiple`."""
        h = self.stem(x)
        skips = []
        for enc, down in zip(self.enc, self.down):
            h = enc(h)
            skips.append(h)
            h = down(F.silu(h))
        h = self.mid(h)
        for i in reversed(range(len(self.up))):
            h = self.up[i](F.interpolate(F.silu(h), scale_factor=2, mode="nearest"))
            h = self.dec[i](self.merge[i](torch.cat([h, skips[i]], dim=1)))
        return self.out(F.silu(h))


def build_input(height_m: torch.Tensor, climate: dict | torch.Tensor) -> torch.Tensor:
    """(B,1,H,W) metres + climate (dict of physical (B,1,H,W) fields, or normalised (B,4,H,W))
    -> (B, N_IN, H, W)."""
    if isinstance(climate, dict):
        climate = torch.cat([P.climate_input(n, climate[n]) for n in P.CLIMATE_NAMES], dim=1)
    p = F.pad(height_m, (1, 1, 1, 1), mode="replicate")
    gx = (p[..., 1:-1, 2:] - p[..., 1:-1, :-2]) * 0.5 / SLOPE_SCALE_M
    gy = (p[..., 2:, 1:-1] - p[..., :-2, 1:-1]) * 0.5 / SLOPE_SCALE_M
    return torch.cat([height_m / P.HEIGHT_SCALE_M, gx, gy, climate], dim=1)
