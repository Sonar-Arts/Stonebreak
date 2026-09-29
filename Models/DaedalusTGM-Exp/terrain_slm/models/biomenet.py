"""Biome sidecar (v4): a small conv net that classifies block columns into biomes coherently.

The rule classifier (`biomes._classify_biome`) decides each column alone from its elevation, its
own slope (vegetation thresholds at slope ratio 0.62 and above) and climate, so on hilly terrain
1-2 block steps flip columns between biomes: speckle, small blobs and streaks along valley
floors. This net is trained on real DEMs (24 regions, quantised to blocks exactly as the tile
builder does) against the rule classifier's output cleaned by majority filters, so it learns
the same biome logic with spatially coherent regions. The classifier's own coordinate noise
fields are inputs, so region edges keep their natural wiggle.

Receptive-field radius <= 23 blocks: inside TileBuilder's BLOCK_MARGIN (24), so a tile's
biomes never depend on which request computed them.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm import biomes as B

CLASS_IDS = tuple(sorted(set(B._BIOME_ID.values())))   # biome id per output class
N_CLASSES = len(CLASS_IDS)
N_IN = 10   # elev, slope, 4 climate, 3 classifier noise fields, land flag
PIXEL_M = 60.0


@dataclass
class BiomeNetConfig:
    channels: tuple = (32, 64, 96)
    norm: bool = False   # per-pixel channel norm in every block: without it training collapsed to one class (b3)

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class ChannelNorm(nn.Module):
    """LayerNorm over channels at each pixel. Pointwise, so a column's output never depends on the window
    it was computed in (GroupNorm/BatchNorm statistics span the window and would put seams at tile edges)."""

    def __init__(self, c: int):
        super().__init__()
        self.weight = nn.Parameter(torch.ones(1, c, 1, 1))
        self.bias = nn.Parameter(torch.zeros(1, c, 1, 1))

    def forward(self, x):
        mu = x.mean(dim=1, keepdim=True)
        var = x.var(dim=1, keepdim=True, unbiased=False)
        return (x - mu) * torch.rsqrt(var + 1e-5) * self.weight + self.bias


def _block(ci, co, norm=False):
    if not norm:
        return nn.Sequential(nn.Conv2d(ci, co, 3, padding=1), nn.SiLU(), nn.Conv2d(co, co, 3, padding=1), nn.SiLU())
    return nn.Sequential(nn.Conv2d(ci, co, 3, padding=1), ChannelNorm(co), nn.SiLU(),
                         nn.Conv2d(co, co, 3, padding=1), ChannelNorm(co), nn.SiLU())


class BiomeNet(nn.Module):
    def __init__(self, cfg: BiomeNetConfig = BiomeNetConfig()):
        super().__init__()
        self.cfg = cfg
        c0, c1, c2 = cfg.channels
        nb = lambda a, b: _block(a, b, cfg.norm)
        self.e0 = nb(N_IN, c0)
        self.d0 = nn.Conv2d(c0, c1, 3, stride=2, padding=1)
        self.e1 = nb(c1, c1)
        self.d1 = nn.Conv2d(c1, c2, 3, stride=2, padding=1)
        self.mid = nb(c2, c2)
        self.u1 = nn.Conv2d(c2 + c1, c1, 3, padding=1)
        self.dec1 = nb(c1, c1)
        self.u0 = nn.Conv2d(c1 + c0, c0, 3, padding=1)
        self.dec0 = nb(c0, c0)
        self.out = nn.Conv2d(c0, N_CLASSES, 1)

    def forward(self, x):
        h0 = self.e0(x)
        h1 = self.e1(F.silu(self.d0(h0)))
        h2 = self.mid(F.silu(self.d1(h1)))
        u1 = self.dec1(F.silu(self.u1(torch.cat([F.interpolate(h2, size=h1.shape[-2:], mode="nearest"), h1], 1))))
        u0 = self.dec0(F.silu(self.u0(torch.cat([F.interpolate(u1, size=h0.shape[-2:], mode="nearest"), h0], 1))))
        return self.out(u0)


def classifier_noise(i0: int, j0: int, h: int, w: int) -> np.ndarray:
    """The rule classifier's coordinate noise fields at columns [i0, i0+h) x [j0, j0+w), combined
    exactly as it combines them: (3, h, w) = temperature, precipitation, snow."""
    x = np.arange(j0, j0 + w, dtype=np.float32)
    y = np.arange(i0, i0 + h, dtype=np.float32)
    xx, yy = np.meshgrid(x, y)
    coords = np.array([xx.ravel(), yy.ravel()], dtype=np.float32)
    g = lambda n: n.gen_from_coords(coords).astype(np.float32).reshape(h, w)
    temp = 0.4 * g(B._TEMP_NOISE) + 0.2 * g(B._TEMP_NOISE_FINE)
    precip = 0.2 * g(B._PRECIP_NOISE)
    snow = (3.0 * g(B._SNOW_NOISE) + 2.0 * g(B._SNOW_NOISE_FINE)) / 5.0
    return np.stack([temp, precip, snow])


def build_input(elev_m: torch.Tensor, climate: torch.Tensor, i0: int, j0: int) -> torch.Tensor:
    """(H, W) mid-block metres + (4, H, W) climate in the classifier's units -> (1, N_IN, H, W)."""
    h, w = elev_m.shape
    p = F.pad(elev_m[None, None], (1, 1, 1, 1), mode="replicate")[0, 0]
    gx = 0.5 * (p[1:-1, 2:] - p[1:-1, :-2])
    gy = 0.5 * (p[2:, 1:-1] - p[:-2, 1:-1])
    slope = torch.sqrt(gx * gx + gy * gy) / PIXEL_M
    noise = torch.from_numpy(classifier_noise(i0, j0, h, w)).to(elev_m.device)
    t, ts, pr, cv = climate[0], climate[1], climate[2].clamp_min(0.0), climate[3]
    return torch.stack([
        elev_m.clamp_min(0.0) / 1000.0, slope.clamp(0, 3), t / 10.0, ts / 500.0,
        torch.log1p(pr) / 7.0, cv / 50.0, noise[0], noise[1] * 5.0, noise[2], (elev_m > 0).float(),
    ])[None]


@torch.no_grad()
def classify(model: BiomeNet, elev_m: torch.Tensor, climate: torch.Tensor, i0: int, j0: int) -> torch.Tensor:
    """(H, W) int16 biome ids for the whole input (callers crop their margin)."""
    logits = model(build_input(elev_m, climate, i0, j0).float())
    ids = torch.tensor(CLASS_IDS, device=elev_m.device, dtype=torch.int16)
    return ids[logits[0].argmax(0)]


def majority(labels: torch.Tensor, size: int) -> torch.Tensor:
    """Majority (mode) filter of an (H, W) class-index map over size x size windows (reflect-padded).
    Index N_CLASSES (ids outside the land classes, e.g. ocean) is carried as its own class."""
    onehot = F.one_hot(labels.long(), N_CLASSES + 1).permute(2, 0, 1)[None].float()
    r = size // 2
    counts = F.avg_pool2d(F.pad(onehot, (r, r, r, r), mode="reflect"), size, stride=1)
    return counts[0].argmax(0)
