"""Surface-detail node (EXP-2 agent L, 2026-11-19): what lies inside a block, learnt from 1 m lidar DTMs.

From what the tile builder has at tile time - the 60 m ground heights and the local climate (temperature, annual
precipitation) - it predicts, per 60 m block and per 15 m sub-block (4 x 4 per 60 m block), the sub-block surface
fields of `data/surface_labels.py`:

  rock_exposure  share of the ground that is bare rock wall / outcrop face (1 m slope >= 45 deg)
  scree          share that is rough talus / block field (28-45 deg, rough at 1-5 m)
  smooth         share that is smooth fine-soil ground (< 20 deg, not rough)
  roughness      RMS of the < ~10 m relief (m)
  boulder_density  boulders (1.6-6.8 m) per 100 m^2
  boulder_size   typical boulder diameter (m) where boulders occur

Pure stride-1 3x3 convolutions (receptive-field radius RF = 6 samples = 360 m), so a column's output never depends on
the window it was computed in: the tile builder runs it over tile + margin and crops (no tile-edge seams).
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

FIELDS = ("rock_exposure", "scree", "smooth", "roughness", "boulder_density", "boulder_size")
N_FIELDS = len(FIELDS)
FRACTIONS = 3            # the first three are shares (sigmoid)
N_IN = 9
PIXEL_M = 60.0
SUB = 4                  # 15 m sub-blocks per 60 m block and axis
RF = 6                   # receptive-field radius (samples): 5x5 input features (2) + stem and 3 trunk 3x3 convs (4)


@dataclass
class SurfaceNetConfig:
    width: int = 64
    depth: int = 3        # 3x3 convs after the 3x3 stem
    arch: str = "surface"

    def to_dict(self):
        return asdict(self)


class SurfaceNet(nn.Module):
    def __init__(self, cfg: SurfaceNetConfig = SurfaceNetConfig()):
        super().__init__()
        self.cfg = cfg
        w = cfg.width
        self.stem = nn.Conv2d(N_IN, w, 3, padding=1)
        self.trunk = nn.ModuleList(nn.Conv2d(w, w, 3, padding=1) for _ in range(cfg.depth))
        self.head60 = nn.Conv2d(w, N_FIELDS, 1)
        self.head15 = nn.Conv2d(w, N_FIELDS * SUB * SUB, 1)

    def forward(self, x):
        """x (B, N_IN, H, W) -> raw outputs (B, F, H, W) at 60 m and (B, F, 4H, 4W) at 15 m."""
        h = F.silu(self.stem(x))
        for c in self.trunk:
            h = h + F.silu(c(h))
        return self.head60(h), F.pixel_shuffle(self.head15(h), SUB)


def activate(raw: torch.Tensor) -> torch.Tensor:
    """Raw head outputs -> physical fields (channel order FIELDS)."""
    frac = torch.sigmoid(raw[:, :FRACTIONS])
    rough = F.softplus(raw[:, 3:4]) * 0.25
    dens = F.softplus(raw[:, 4:5]) * 0.5
    size = 1.6 + F.softplus(raw[:, 5:6]) * 2.0
    return torch.cat([frac, rough, dens, size], 1)


def build_input(z60: torch.Tensor, temp: torch.Tensor, precip: torch.Tensor) -> torch.Tensor:
    """(B, H, W) 60 m ground (m), local mean annual temperature (degC), annual precipitation (mm) -> (B, N_IN, H, W).
    Replicate padding at the window edge (callers crop RF + 1 samples, or train with a loss margin)."""
    z = z60[:, None]
    zp = F.pad(z, (1, 1, 1, 1), mode="replicate")
    gx = 0.5 * (zp[..., 1:-1, 2:] - zp[..., 1:-1, :-2]) / PIXEL_M
    gy = 0.5 * (zp[..., 2:, 1:-1] - zp[..., :-2, 1:-1]) / PIXEL_M
    slope = torch.sqrt(gx * gx + gy * gy)
    mx = F.max_pool2d(zp, 3, 1)
    mn = -F.max_pool2d(-zp, 3, 1)
    mean3 = F.avg_pool2d(zp, 3, 1)
    rel = z - F.avg_pool2d(F.pad(z, (2, 2, 2, 2), mode="replicate"), 5, 1)
    return torch.cat([
        rel / 30.0, gx.clamp(-3, 3), gy.clamp(-3, 3), slope.clamp(0, 3), (mx - mn) / 100.0, (mean3 - z) / 10.0,
        z / 2000.0, temp[:, None] / 15.0, torch.log1p(precip.clamp_min(0.0)[:, None] / 500.0),
    ], 1)


@torch.no_grad()
def predict(model: SurfaceNet, z60: torch.Tensor, temp: torch.Tensor, precip: torch.Tensor):
    """(H, W) tensors -> ((F, H, W) at 60 m, (F, 4H, 4W) at 15 m) physical fields for the whole window."""
    x = build_input(z60[None].float(), temp[None].float(), precip[None].float())
    r60, r15 = model(x)
    return activate(r60)[0], activate(r15)[0]


def load(path, device) -> SurfaceNet:
    ck = torch.load(path, map_location="cpu", weights_only=False)
    m = SurfaceNet(SurfaceNetConfig(**{k: v for k, v in ck["config"].items() if k in SurfaceNetConfig.__dataclass_fields__}))
    m.load_state_dict(ck["model"])
    return m.to(device).eval()


def count_params(m: nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())
