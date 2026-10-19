"""Descriptor sampler (MaskGIT): planner inputs -> SAMPLED terrain descriptors per cell.

The planner regresses its 8 descriptors, so where the inputs cannot pin texture down (which
dunes, which ridges, how rough this valley side) it predicts their mean: averaged, "orange
peel" terrain. This model samples instead. Each cell's descriptors are two tokens from a split
codebook (k-means): one of K_AMP for the five band amplitudes, one of K_SHAPE for ridge and
anisotropy (mean reconstruction error 0.11 in normalised units, vs 0.19 for one 1024-code
token). A ViT over 4x4-cell patches (the planner's trunk, axial RoPE) predicts masked tokens
from the unmasked ones plus the planner's own inputs; sampling unmasks them over a few passes
with seeded randomness.

Tiling (world/generator.py) uses a 4-phase checkerboard of windows with hard core ownership, so
there is no cross-fade to average samples away and no dependence on request order.
"""
from __future__ import annotations

import math
from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.models import planner as P

K_AMP, K_SHAPE = 512, 256
AMP = slice(0, 5)
SHAPE = slice(5, 8)
EMB_AMP, EMB_SHAPE = 16, 8


@dataclass
class DescGitConfig:
    dim: int = 160
    depth: int = 4
    heads: int = 4
    mlp_ratio: int = 4
    patch: int = 4
    head_dim_cell: int = 64

    def to_dict(self):
        return asdict(self)


class Codebook(nn.Module):
    """Split k-means codebook over normalised descriptors (buffers, saved with the model)."""

    def __init__(self, amp: torch.Tensor | None = None, shape: torch.Tensor | None = None):
        super().__init__()
        self.register_buffer("amp", amp if amp is not None else torch.zeros(K_AMP, 5))
        self.register_buffer("shape", shape if shape is not None else torch.zeros(K_SHAPE, 3))

    def encode(self, d: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """(B, 8, H, W) normalised descriptors -> (B, H, W) amp ids, (B, H, W) shape ids."""
        b, _, h, w = d.shape
        flat = d.permute(0, 2, 3, 1).reshape(-1, 8)
        a = torch.cdist(flat[:, AMP], self.amp).argmin(1)
        s = torch.cdist(flat[:, SHAPE], self.shape).argmin(1)
        return a.view(b, h, w), s.view(b, h, w)

    def decode(self, a: torch.Tensor, s: torch.Tensor) -> torch.Tensor:
        """ids -> (B, 8, H, W) normalised descriptors."""
        return torch.cat([self.amp[a], self.shape[s]], dim=-1).permute(0, 3, 1, 2)


def kmeans(x: torch.Tensor, k: int, iters: int = 40, seed: int = 0) -> torch.Tensor:
    g = torch.Generator(device=x.device).manual_seed(seed)
    c = x[torch.randperm(x.shape[0], device=x.device, generator=g)[:k]].clone()
    for _ in range(iters):
        a = torch.cdist(x, c).argmin(1)
        c = torch.zeros_like(c).index_add_(0, a, x) / torch.bincount(a, minlength=k).clamp_min(1)[:, None].float()
    return c


class DescGit(nn.Module):
    def __init__(self, cfg: DescGitConfig = DescGitConfig()):
        super().__init__()
        self.cfg = cfg
        p = cfg.patch
        self.codebook = Codebook()
        self.emb_amp = nn.Embedding(K_AMP + 1, EMB_AMP)      # last id = MASK
        self.emb_shape = nn.Embedding(K_SHAPE + 1, EMB_SHAPE)
        per_cell = P.N_IN + EMB_AMP + EMB_SHAPE
        self.embed = nn.Linear(per_cell * p * p, cfg.dim)
        self.blocks = nn.ModuleList(P.Block(cfg.dim, cfg.heads, cfg.mlp_ratio) for _ in range(cfg.depth))
        self.norm = nn.LayerNorm(cfg.dim)
        self.head = nn.Linear(cfg.dim, cfg.head_dim_cell * p * p)
        self.out_amp = nn.Linear(cfg.head_dim_cell, K_AMP)
        self.out_shape = nn.Linear(cfg.head_dim_cell, K_SHAPE)

    def forward(self, cond: torch.Tensor, a: torch.Tensor, s: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """cond (B, N_IN, H, W) planner inputs; a, s (B, H, W) ids with MASK = K -> logits
        (B, H, W, K_AMP), (B, H, W, K_SHAPE)."""
        b, _, h, w = cond.shape
        p = self.cfg.patch
        cell = torch.cat([cond, self.emb_amp(a).permute(0, 3, 1, 2), self.emb_shape(s).permute(0, 3, 1, 2)], dim=1)
        c = cell.shape[1]
        hp, wp = h // p, w // p
        tok = cell.view(b, c, hp, p, wp, p).permute(0, 2, 4, 1, 3, 5).reshape(b, hp * wp, c * p * p)
        t = self.embed(tok)
        cos, sin = P._rope_tables(hp, wp, self.cfg.dim // self.cfg.heads, cond.device)
        for blk in self.blocks:
            t = blk(t, cos, sin)
        feat = self.head(self.norm(t)).view(b, hp, wp, p, p, self.cfg.head_dim_cell)
        feat = F.gelu(feat.permute(0, 1, 3, 2, 4, 5).reshape(b, h, w, self.cfg.head_dim_cell))
        return self.out_amp(feat), self.out_shape(feat)


def hashed_uniform(keys: torch.Tensor, salt: int, k: int | None = None) -> torch.Tensor:
    """Uniform (0, 1) noise from int64 cell keys (B, H, W) and a salt, optionally one value per
    class (B, H, W, k). Coordinate-hashed, so it never depends on the request."""
    from terrain_slm.synth import noise as S
    base = S._hash32(keys ^ (salt * 0x9E3779B1 & S._MASK32))
    if k is not None:
        cls = torch.arange(k, device=keys.device, dtype=torch.int64) * 0x85EBCA77 & S._MASK32
        base = S._hash32(base[..., None] ^ cls)
    return (base.float() + 0.5) / 4294967296.0


def gumbel(u: torch.Tensor) -> torch.Tensor:
    return -torch.log(-torch.log(u.clamp(1e-7, 1 - 1e-7)))


@torch.no_grad()
def sample(model: DescGit, cond: torch.Tensor, a: torch.Tensor, s: torch.Tensor, known: torch.Tensor,
           keys: torch.Tensor, steps: int = 12, temperature: float = 1.0) -> tuple[torch.Tensor, torch.Tensor]:
    """MaskGIT decoding. `known` (B, H, W) marks cells whose (a, s) are given; `keys` (B, H, W)
    int64 are the cells' world-coordinate hash keys (the only randomness)."""
    a = torch.where(known, a, torch.full_like(a, K_AMP))
    s = torch.where(known, s, torch.full_like(s, K_SHAPE))
    unknown = ~known
    n_unknown = unknown.flatten(1).sum(1)
    for t in range(steps):
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=cond.is_cuda):
            la, ls = model(cond, a, s)
        la, ls = la.float(), ls.float()
        na = (la / temperature + gumbel(hashed_uniform(keys, 3 * t + 1, K_AMP))).argmax(-1)
        ns = (ls / temperature + gumbel(hashed_uniform(keys, 3 * t + 2, K_SHAPE))).argmax(-1)
        conf = (torch.log_softmax(la, -1).gather(-1, na[..., None])[..., 0] +
                torch.log_softmax(ls, -1).gather(-1, ns[..., None])[..., 0])
        # Annealed confidence noise: early passes explore, the last ones commit.
        conf = conf + gumbel(hashed_uniform(keys, 3 * t + 3)) * 2.0 * (1.0 - (t + 1) / steps)
        conf = torch.where(unknown, conf, torch.full_like(conf, float("inf")))
        # Cosine schedule: how many of the originally unknown cells stay masked after this pass.
        keep_masked = torch.floor(n_unknown.float() * math.cos(math.pi / 2 * (t + 1) / steps)).long()
        flat = conf.flatten(1)
        order = flat.argsort(dim=1)
        rank = torch.empty_like(order)
        rank.scatter_(1, order, torch.arange(flat.shape[1], device=flat.device).expand_as(order))
        still = (rank < keep_masked[:, None]).view_as(unknown) & unknown
        commit = unknown & ~still
        a = torch.where(commit, na, a)
        s = torch.where(commit, ns, s)
        unknown = still
    return a, s
