"""Bank stages: how the ground meets the water.

`LearnedBanks` runs the bank model: a small conv UNet trained on real river and lake edges
(GLO-30 flattens water wider than ~183 m, so exact-flat patches are water: data/water.py).
It sees the ground around a band beside the water, with the band itself blanked out, plus
the water outline and level, all in BLOCKS relative to the water, and redraws the band the way
real banks look: cut banks on outer bends, point bars on inner ones, walls where a river
meets a hillside. It runs only where a river exists (the gate is `field.has_river`).

`LeveeBanks` is the old deterministic ring (kept as a swappable fallback), and `BankGuard` is
the minimal safety raise that runs after either one: it lifts only bank pixels that would
otherwise let the water out, and counts them (the "leak pressure" the model should keep low).

`bank_inputs` is shared by training and the game, so the two can never drift apart.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass

import torch
import torch.nn as nn
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.river.field import RiverContext, RiverField
from terrain_slm.river.geometry import dilate

N_IN = 4                  # filled relative ground, band mask, water mask, signed distance
REL_SCALE_BLOCKS = 10.0   # model units: blocks / 10
SD_CAP_PX = 32
FILL_SIGMAS = (3.0, 6.0, 12.0, 24.0)
GUARD_MARGIN_BLOCKS = 0.05


@dataclass
class BankConfig:
    channels: tuple = (16, 32, 48)
    blocks: int = 2

    def to_dict(self):
        d = asdict(self)
        d["channels"] = list(self.channels)
        return d


class _Res(nn.Module):
    def __init__(self, c):
        super().__init__()
        self.a = nn.Conv2d(c, c, 3, padding=1)
        self.b = nn.Conv2d(c, c, 3, padding=1)
        nn.init.zeros_(self.b.weight)
        nn.init.zeros_(self.b.bias)

    def forward(self, x):
        return x + self.b(F.silu(self.a(F.silu(x))))


class BankNet(nn.Module):
    def __init__(self, cfg: BankConfig = BankConfig()):
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
        self.out = nn.Conv2d(ch[0], 1, 3, padding=1)

    @property
    def multiple(self) -> int:
        return 2 ** (len(self.cfg.channels) - 1)

    def forward(self, x):
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
        # The input's filled ground is the baseline; the model predicts the correction.
        return x[:, 0:1] + self.out(F.silu(h))


def fill_unknown(rel: torch.Tensor, known: torch.Tensor) -> torch.Tensor:
    """Fill unknown pixels of (B,1,H,W) `rel` from known ones by normalised convolution,
    the smallest scale with support winning."""
    k = known.float()
    out = rel * k
    done = known.clone()
    for s in FILL_SIGMAS:
        num, den = D.blur(rel * k, s), D.blur(k, s)
        take = (~done) & (den > 0.02)
        out = torch.where(take, num / den.clamp_min(1e-6), out)
        done = done | take
    return out


def signed_distance(water: torch.Tensor, cap: int = SD_CAP_PX) -> torch.Tensor:
    """(B,1,H,W) bool -> px to the water edge: negative inside, positive outside (Chebyshev)."""
    w = water.float()
    inside, outside = torch.zeros_like(w), torch.zeros_like(w)
    a, b = w, w
    for _ in range(cap):
        a = -F.max_pool2d(-a, 3, 1, 1)     # erode
        b = F.max_pool2d(b, 3, 1, 1)       # dilate
        inside, outside = inside + a, outside + (1 - b)
    return torch.where(water, -(inside + 1), outside + 1)


def bank_inputs(ground_rel: torch.Tensor, water: torch.Tensor, band: torch.Tensor) -> torch.Tensor:
    """Shared input stack. `ground_rel` (B,1,H,W) is ground minus water level, in BLOCKS; the
    band and the water are blanked (water to 0, band filled from the ground beyond it)."""
    known = ~(band | water)
    filled = fill_unknown(ground_rel, known)
    filled = torch.where(water, torch.zeros_like(filled), filled)
    sd = signed_distance(water)
    return torch.cat([(filled / REL_SCALE_BLOCKS).clamp(-4, 4), band.float(), water.float(),
                      (sd / 16.0).clamp(-2, 2)], dim=1)


def _band(f: RiverField, ctx: RiverContext) -> torch.Tensor:
    r = int(round(ctx.cfg.bank_band_blocks * ctx.scale.px_per_block))
    return dilate(f.channel, r) & ~f.channel


class LearnedBanks:
    """Bank model over the band beside the channel. A no-op where there is no river."""
    name = "banks"

    def __init__(self, model: BankNet):
        self.model = model

    @torch.no_grad()
    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        base = f.carved if f.carved is not None else f.ground
        f.band = _band(f, ctx) if f.has_river else torch.zeros_like(f.ground, dtype=torch.bool)
        if not f.has_river:
            f.carved = base
            return f
        rate = ctx.scale.rate(f.level)
        rel = ((base - f.level) / rate)[None, None]
        x = bank_inputs(rel, f.channel[None, None], f.band[None, None])
        m = self.model.multiple
        h, w = f.ground.shape
        ph, pw = (-h) % m, (-w) % m
        x = F.pad(x, (0, pw, 0, ph), mode="replicate")
        # Full precision: this runs on request-shaped tensors, and bf16 rounding that depends on
        # the kernel chosen for a shape would make the ground depend on the request.
        pred = self.model(x.float())[0, 0, :h, :w] * REL_SCALE_BLOCKS
        # Fade to the untouched ground over the band's outer rim, so the band never shows.
        rim = ctx.scale.px_per_block * 1.5
        sd = signed_distance(f.channel[None, None])[0, 0]
        band_px = ctx.cfg.bank_band_blocks * ctx.scale.px_per_block
        wgt = ((band_px - sd) / rim).clamp(0, 1)
        new = f.level + rate * pred
        f.carved = torch.where(f.band, wgt * new + (1 - wgt) * base, base)
        return f


class LeveeBanks:
    """Deterministic fallback: raise a ring beside the channel to just above the water."""
    name = "banks"
    LEVEE_PX = 2

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        base = f.carved if f.carved is not None else f.ground
        f.band = dilate(f.channel, self.LEVEE_PX) & ~f.channel if f.has_river else torch.zeros_like(base, dtype=torch.bool)
        f.carved = torch.where(f.band, torch.maximum(base, f.level + 0.3), base)
        return f


class BankGuard:
    """Leak guard: bank pixels touching the channel that stand below the water are lifted to
    just above it. Counts what it had to do (ctx.stats['guard_raised_px'])."""
    name = "guard"

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        if not f.has_river:
            ctx.stats["guard_raised_px"] = 0
            return f
        ring = dilate(f.channel, 1) & ~f.channel & (f.ground > 0.5)
        need = f.level + GUARD_MARGIN_BLOCKS * ctx.scale.rate(f.level)
        low = ring & (f.carved < need)
        ctx.stats["guard_raised_px"] = int(low.sum())
        ctx.stats["bank_ring_px"] = int(ring.sum())
        lift = ((need - f.carved) / ctx.scale.rate(f.level))[low]
        ctx.stats["guard_mean_lift_blocks"] = float(lift.mean()) if lift.numel() else 0.0
        ctx.stats["guard_lift_over_half_block_px"] = int((lift > 0.5).sum())
        f.carved = torch.where(low, need, f.carved)
        return f
