"""Refiner: conditional flow-matching UNet that adds learned pixel detail.

Predicts the velocity field for x_t = (1 - t) * noise + t * r_n, where r_n is the
residual of real terrain over the smooth coarse surface, divided by a local scale field
s(x) derived from the descriptors (so every terrain type is ~unit variance).

Conditioning per pixel (N_COND channels): slope of the coarse surface (2), the eight
descriptors (normalised), river probability (1). Convolution-only, no normalisation
layers, channel counts multiples of 16 (the native-kernel design rules).
"""
from __future__ import annotations

import math
from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

N_COND = 11
SLOPE_SCALE = 10.0  # metres per pixel -> ~unit
MIN_SCALE_M = 0.5


@dataclass
class RefinerConfig:
    channels: tuple = (16, 32, 48)
    blocks: int = 2
    temb: int = 64

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


def timestep_embedding(t: torch.Tensor, dim: int) -> torch.Tensor:
    half = dim // 2
    freqs = torch.exp(-math.log(1000.0) * torch.arange(half, device=t.device, dtype=torch.float32) / half)
    a = t.float()[:, None] * 1000.0 * freqs[None]
    return torch.cat([torch.cos(a), torch.sin(a)], dim=1)


class ResBlock(nn.Module):
    def __init__(self, c: int, temb: int):
        super().__init__()
        self.conv1 = nn.Conv2d(c, c, 3, padding=1)
        self.film = nn.Linear(temb, 2 * c)
        self.conv2 = nn.Conv2d(c, c, 3, padding=1)
        nn.init.zeros_(self.conv2.weight)
        nn.init.zeros_(self.conv2.bias)
        nn.init.zeros_(self.film.weight)
        nn.init.zeros_(self.film.bias)

    def forward(self, x, e):
        h = self.conv1(F.silu(x))
        scale, shift = self.film(e)[:, :, None, None].chunk(2, dim=1)
        h = h * (1 + scale) + shift
        return x + self.conv2(F.silu(h))


class Refiner(nn.Module):
    def __init__(self, cfg: RefinerConfig = RefinerConfig()):
        super().__init__()
        self.cfg = cfg
        c0, c1, c2 = cfg.channels
        te = cfg.temb * 2
        self.temb = nn.Sequential(nn.Linear(cfg.temb, te), nn.SiLU(), nn.Linear(te, te))
        self.stem = nn.Conv2d(1 + N_COND, c0, 3, padding=1)
        self.enc0 = nn.ModuleList(ResBlock(c0, te) for _ in range(cfg.blocks))
        self.down0 = nn.Conv2d(c0, c1, 3, stride=2, padding=1)
        self.enc1 = nn.ModuleList(ResBlock(c1, te) for _ in range(cfg.blocks))
        self.down1 = nn.Conv2d(c1, c2, 3, stride=2, padding=1)
        self.mid = nn.ModuleList(ResBlock(c2, te) for _ in range(cfg.blocks))
        self.up1 = nn.Conv2d(c2, c1, 3, padding=1)
        self.merge1 = nn.Conv2d(2 * c1, c1, 3, padding=1)
        self.dec1 = nn.ModuleList(ResBlock(c1, te) for _ in range(cfg.blocks))
        self.up0 = nn.Conv2d(c1, c0, 3, padding=1)
        self.merge0 = nn.Conv2d(2 * c0, c0, 3, padding=1)
        self.dec0 = nn.ModuleList(ResBlock(c0, te) for _ in range(cfg.blocks))
        self.out = nn.Conv2d(c0, 1, 3, padding=1)
        nn.init.zeros_(self.out.weight)
        nn.init.zeros_(self.out.bias)

    def forward(self, x_t: torch.Tensor, t: torch.Tensor, cond: torch.Tensor) -> torch.Tensor:
        """x_t (B,1,H,W), t (B,), cond (B,N_COND,H,W) -> velocity (B,1,H,W); H, W divisible by 4."""
        e = self.temb(timestep_embedding(t, self.cfg.temb))
        h0 = self.stem(torch.cat([x_t, cond], dim=1))
        for b in self.enc0:
            h0 = b(h0, e)
        h1 = self.down0(F.silu(h0))
        for b in self.enc1:
            h1 = b(h1, e)
        h2 = self.down1(F.silu(h1))
        for b in self.mid:
            h2 = b(h2, e)
        u1 = self.up1(F.interpolate(F.silu(h2), scale_factor=2, mode="nearest"))
        u1 = self.merge1(torch.cat([u1, h1], dim=1))
        for b in self.dec1:
            u1 = b(u1, e)
        u0 = self.up0(F.interpolate(F.silu(u1), scale_factor=2, mode="nearest"))
        u0 = self.merge0(torch.cat([u0, h0], dim=1))
        for b in self.dec0:
            u0 = b(u0, e)
        return self.out(F.silu(u0))


# ----------------------------------------------------------------------------- shared helpers
def scale_field(desc_px: torch.Tensor) -> torch.Tensor:
    """Local detail scale s(x) (B,1,H,W) from *raw* per-pixel descriptors (log amps 0..4:
    every band the residual over the coarse surface contains)."""
    amps = (torch.exp(desc_px[:, 0:5]) - 0.01).clamp_min(0.0)
    return torch.sqrt((amps**2).sum(dim=1, keepdim=True)).clamp_min(MIN_SCALE_M)


def slope(base: torch.Tensor) -> torch.Tensor:
    """Central-difference gradient of the coarse surface, (B,2,H,W), /SLOPE_SCALE."""
    gy = (F.pad(base, (0, 0, 1, 1), mode="replicate")[..., 2:, :] - F.pad(base, (0, 0, 1, 1), mode="replicate")[..., :-2, :]) * 0.5
    gx = (F.pad(base, (1, 1, 0, 0), mode="replicate")[..., :, 2:] - F.pad(base, (1, 1, 0, 0), mode="replicate")[..., :, :-2]) * 0.5
    return torch.cat([gx, gy], dim=1) / SLOPE_SCALE


def build_cond(base: torch.Tensor, desc_px: torch.Tensor, river_px: torch.Tensor,
               desc_mean: torch.Tensor, desc_std: torch.Tensor) -> torch.Tensor:
    d = desc_px.clone()
    d[:, 5] = d[:, 5].clamp(-3, 3)
    d = (d - desc_mean.view(1, -1, 1, 1)) / desc_std.view(1, -1, 1, 1)
    return torch.cat([slope(base), d, river_px], dim=1)


@torch.no_grad()
def sample(model: "Refiner", cond: torch.Tensor, noise: torch.Tensor, steps: int = 8,
           x_start: torch.Tensor | None = None, t_start: float = 0.0) -> torch.Tensor:
    """Euler-integrate the flow from t_start to 1. With x_start (e.g. normalised synth),
    starts from (1 - t_start) * noise + t_start * x_start (SDEdit); t_start=0 ignores it."""
    x = noise if x_start is None or t_start <= 0 else (1 - t_start) * noise + t_start * x_start
    ts = torch.linspace(t_start, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((cond.shape[0],), float(ts[i]), device=cond.device)
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=cond.is_cuda):
            v = model(x, t, cond).float()
        x = x + (ts[i + 1] - ts[i]) * v
    return x
