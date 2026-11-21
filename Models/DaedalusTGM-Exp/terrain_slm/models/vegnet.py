"""Vegetation node (EXP-2, agent V, 2026-11-19): a small conv net that predicts per-60 m-block vegetation fields from
what the generator has at tile time - terrain (block-quantised metres), climate (7 channels incl. aridity / soil /
snow from the climate node, temperature lapsed to the regional elevation), and standing water.

Trained on real land (data/vegetation.py: ETH Global Canopy Height 2020 + ESA WorldCover 2021, CC BY 4.0) with
cropland / built-up / water pixels masked out of the loss, so it learns NATURAL vegetation patterns: forests that
thicken in valleys and thin toward the treeline, taller canopy on wet lowlands, sparse trees in dry savanna, gaps.

Outputs (OUTPUTS order, all 0..1 after sigmoid; heights / HEIGHT_SCALE m):
  cover     share of ground under canopy >= 5 m          (-> surface channel canopy_cover)
  hcan      mean height of the canopy pixels / 50 m       (-> canopy_height)
  p90       90th percentile canopy height / 50 m
  clearing  share of the 5x5 (300 m) window in open cells (cover < 0.25), natural cells only (-> clearing)
  tree, shrub, grass, bare, snow, wetland, moss  WorldCover shares of the natural ground (-> ground_*)

Receptive-field radius (input features + convolutions) 22 blocks at 60 m: inside TileBuilder's BLOCK_MARGIN (32)
at 1:4, so 1:4 tiles are request-independent. All norms are per pixel (ChannelNorm).
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.models.biomenet import ChannelNorm

PIXEL_M = 60.0
LAPSE_C_PER_M = 0.0065
HEIGHT_SCALE = 50.0
OUTPUTS = ("cover", "hcan", "p90", "clearing", "tree", "shrub", "grass", "bare", "snow", "wetland", "moss")
N_OUT = len(OUTPUTS)
N_IN = 16


@dataclass
class VegNetConfig:
    channels: tuple = (48, 96)

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


def _block(ci, co):
    return nn.Sequential(nn.Conv2d(ci, co, 3, padding=1), ChannelNorm(co), nn.SiLU(),
                         nn.Conv2d(co, co, 3, padding=1), ChannelNorm(co), nn.SiLU())


class VegNet(nn.Module):
    """Two-level U-net: e0 (r2) -> down (r3) -> e1 + mid at 1/2 (r11) -> up (r12) -> dec0 (r14)."""

    def __init__(self, cfg: VegNetConfig = VegNetConfig()):
        super().__init__()
        self.cfg = cfg
        c0, c1 = cfg.channels
        self.e0 = _block(N_IN, c0)
        self.d0 = nn.Conv2d(c0, c1, 3, stride=2, padding=1)
        self.e1 = _block(c1, c1)
        self.mid = _block(c1, c1)
        self.u0 = nn.Conv2d(c1 + c0, c0, 3, padding=1)
        self.dec0 = nn.Sequential(ChannelNorm(c0), nn.SiLU(), nn.Conv2d(c0, c0, 3, padding=1), ChannelNorm(c0), nn.SiLU())
        self.out = nn.Conv2d(c0, N_OUT, 1)

    def forward(self, x):
        h0 = self.e0(x)
        h1 = self.mid(self.e1(F.silu(self.d0(h0))))
        u = self.u0(torch.cat([F.interpolate(h1, size=h0.shape[-2:], mode="nearest"), h0], 1))
        return self.out(self.dec0(u))


def _box(x: torch.Tensor, k: int) -> torch.Tensor:
    r = k // 2
    return F.avg_pool2d(F.pad(x[None, None], (r, r, r, r), mode="replicate"), k, stride=1)[0, 0]


def build_input(elev_m: torch.Tensor, climate: torch.Tensor, regional_m: torch.Tensor, water: torch.Tensor) -> torch.Tensor:
    """(H, W) mid-block metres, (7, H, W) climate (temperature lapsed to `regional_m`, the generator's units),
    (H, W) regional elevation (m), (H, W) standing water 0/1 -> (1, N_IN, H, W)."""
    e = elev_m.float()
    # Shape features from a 5x5-smoothed surface: on block-quantised heights the raw 3x3 slope / curvature spike at
    # every block step, and the node drew vegetation bands along the contours (veg_r0b maps, 2026-11-19).
    es = _box(e, 5)
    p = F.pad(es[None, None], (1, 1, 1, 1), mode="replicate")[0, 0]
    gx = 0.5 * (p[1:-1, 2:] - p[1:-1, :-2])
    gy = 0.5 * (p[2:, 1:-1] - p[:-2, 1:-1])
    slope = torch.sqrt(gx * gx + gy * gy) / PIXEL_M
    curv = (p[1:-1, 2:] + p[1:-1, :-2] + p[2:, 1:-1] + p[:-2, 1:-1] - 4 * es) / PIXEL_M
    w = water.float()
    t, ts, pr, cv = climate[0], climate[1], climate[2].clamp_min(0.0), climate[3]
    t_local = t - LAPSE_C_PER_M * (e.clamp_min(0.0) - regional_m.clamp_min(0.0))
    land = ((e > 0) & (w < 0.5)).float()
    return torch.stack([
        e.clamp_min(0.0) / 1000.0, slope.clamp(0, 3), (curv * 10).clamp(-3, 3),
        ((es - _box(e, 9)) / 50.0).clamp(-4, 4), ((es - _box(e, 17)) / 150.0).clamp(-4, 4),
        t / 10.0, t_local / 10.0, ts / 500.0, torch.log1p(pr) / 7.0, cv / 50.0,
        torch.log(climate[4].clamp_min(0.0) + 0.01) / 3.0, climate[5] / 0.4,
        torch.log1p(climate[6].clamp_min(0.0) * 100.0) / 3.0,
        w, _box(w, 9), land,
    ])[None]


@torch.no_grad()
def predict(model: VegNet, elev_m, climate, regional_m, water) -> torch.Tensor:
    """(N_OUT, H, W) in 0..1 (heights / HEIGHT_SCALE) for the whole input frame (callers crop their margin)."""
    return torch.sigmoid(model(build_input(elev_m, climate, regional_m, water).float())[0])
