"""Planner: control maps (per cell) -> coarse heights, descriptors, rivers (per cell).

A small ViT over 4x4-cell patches with axial 2D RoPE (relative positions only, so any
window size works and tiling is translation-invariant). Residual branches end in
zero-initialised projections, so a freshly added block is the identity -- the property
depth growth (stacking/zero-init) relies on later up the ladder.

Channel layouts are defined here and shared with the dataset and the server.
"""
from __future__ import annotations

import math
from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

# --- inputs (per cell) ---------------------------------------------------------------
IN_TREND, IN_T0, IN_TSEASON, IN_PRECIP, IN_PCV, IN_RIVER, IN_WILD = range(7)
IN_HAS_TREND, IN_HAS_CLIMATE, IN_HAS_RIVER, IN_HAS_WILD = 7, 8, 9, 10
N_IN = 11
# "Wildness": a smooth roughness control, in the planner's log-amplitude units of band 3
# (the ~16-32 px band), normalised by these constants. Painted or procedural.
WILD_BAND = 3
WILD_NORM = (1.77, 1.34)  # (offset, scale) of log metres, over all training regions
# --- outputs (per cell) --------------------------------------------------------------
OUT_HEIGHT = 0
OUT_DESC = slice(1, 9)
OUT_RIVER = 9
OUT_LOGACC = 10  # log1p(upslope cells) / LOGACC_SCALE: river strength, grows downstream
N_OUT = 11
LOGACC_SCALE = 8.0
RIVER_MIN_UPSLOPE_CELLS = 434  # ~25 km^2 of drainage at 240 m cells

# Fixed normalisation of physical inputs (roughly unit scale over the training regions).
HEIGHT_SCALE_M = 1000.0
CLIMATE_NAMES = ("t0", "tseason", "precip", "pcv")


def climate_input(name: str, value):
    """Physical climate (WorldClim units) -> planner input channel, ~unit scale over the
    training regions (sahara .. borneo). Precipitation spans 20x, so it is log-scaled.
    Works on floats, numpy arrays and torch tensors alike."""
    if name == "t0":        # sea-level mean annual temperature, degC
        return (value - 15.0) / 8.0
    if name == "tseason":   # temperature seasonality, std*100
        return (value - 600.0) / 300.0
    if name == "precip":    # annual precipitation, mm
        import math
        try:
            import torch
            if isinstance(value, torch.Tensor):
                return (torch.log(value.clamp_min(10.0)) - math.log(800.0)) / 0.9
        except ImportError:
            pass
        import numpy as np
        return (np.log(np.maximum(value, 10.0)) - math.log(800.0)) / 0.9
    if name == "pcv":       # precipitation coefficient of variation, %
        return (value - 40.0) / 30.0
    raise KeyError(name)


@dataclass
class PlannerConfig:
    dim: int = 128
    depth: int = 2
    heads: int = 4
    mlp_ratio: int = 4
    patch: int = 4

    def to_dict(self):
        return asdict(self)


def _rope_tables(h: int, w: int, head_dim: int, device, base: float = 100.0):
    """Axial RoPE: first half of each head rotates with the row index, second with the column."""
    quarter = head_dim // 4
    freqs = base ** (-torch.arange(quarter, device=device, dtype=torch.float32) / quarter)
    rows = torch.arange(h, device=device, dtype=torch.float32)
    cols = torch.arange(w, device=device, dtype=torch.float32)
    ang_r = (rows[:, None] * freqs[None]).repeat_interleave(1, 0)  # (h, q)
    ang_c = cols[:, None] * freqs[None]  # (w, q)
    ang = torch.cat([ang_r[:, None, :].expand(h, w, quarter), ang_c[None, :, :].expand(h, w, quarter)], dim=-1)
    ang = ang.reshape(h * w, 2 * quarter)
    return torch.cos(ang), torch.sin(ang)


def _apply_rope(x: torch.Tensor, cos: torch.Tensor, sin: torch.Tensor) -> torch.Tensor:
    # x: (B, heads, N, head_dim); rotate pairs (even, odd).
    x1, x2 = x[..., 0::2], x[..., 1::2]
    return torch.stack([x1 * cos - x2 * sin, x1 * sin + x2 * cos], dim=-1).flatten(-2)


class Block(nn.Module):
    def __init__(self, dim: int, heads: int, mlp_ratio: int):
        super().__init__()
        self.heads = heads
        self.norm1 = nn.LayerNorm(dim)
        self.qkv = nn.Linear(dim, 3 * dim)
        self.proj = nn.Linear(dim, dim)
        self.norm2 = nn.LayerNorm(dim)
        self.fc1 = nn.Linear(dim, mlp_ratio * dim)
        self.fc2 = nn.Linear(mlp_ratio * dim, dim)
        for lin in (self.proj, self.fc2):  # identity at init
            nn.init.zeros_(lin.weight)
            nn.init.zeros_(lin.bias)

    def forward(self, x, cos, sin):
        b, n, d = x.shape
        q, k, v = self.qkv(self.norm1(x)).view(b, n, 3, self.heads, d // self.heads).permute(2, 0, 3, 1, 4)
        q, k = _apply_rope(q, cos, sin), _apply_rope(k, cos, sin)
        a = F.scaled_dot_product_attention(q, k, v).transpose(1, 2).reshape(b, n, d)
        x = x + self.proj(a)
        return x + self.fc2(F.gelu(self.fc1(self.norm2(x))))


class Planner(nn.Module):
    def __init__(self, cfg: PlannerConfig = PlannerConfig()):
        super().__init__()
        self.cfg = cfg
        p = cfg.patch
        self.embed = nn.Linear(N_IN * p * p, cfg.dim)
        self.blocks = nn.ModuleList(Block(cfg.dim, cfg.heads, cfg.mlp_ratio) for _ in range(cfg.depth))
        self.norm = nn.LayerNorm(cfg.dim)
        self.head = nn.Linear(cfg.dim, N_OUT * p * p)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """(B, N_IN, H, W) cells -> (B, N_OUT, H, W) cells; H, W multiples of the patch."""
        b, c, h, w = x.shape
        p = self.cfg.patch
        hp, wp = h // p, w // p
        tok = x.view(b, c, hp, p, wp, p).permute(0, 2, 4, 1, 3, 5).reshape(b, hp * wp, c * p * p)
        t = self.embed(tok)
        cos, sin = _rope_tables(hp, wp, self.cfg.dim // self.cfg.heads, x.device)
        for blk in self.blocks:
            t = blk(t, cos, sin)
        out = self.head(self.norm(t))
        return out.view(b, hp, wp, N_OUT, p, p).permute(0, 3, 1, 4, 2, 5).reshape(b, N_OUT, h, w)


def count_params(m: nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())
