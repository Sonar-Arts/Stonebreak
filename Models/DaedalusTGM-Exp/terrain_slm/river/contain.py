"""Containment at model resolution: water never stands above adjacent dry ground.

A fixed point: every dry pixel caps its 8 neighbours' water top; wetness only shrinks and the
top only drops, so it terminates. A stretch that cannot hold water ends up dry (a gap, never a
spill). The block-level stage (blocks.BlockContainment) re-checks after quantisation.
"""
from __future__ import annotations

import torch
import torch.nn.functional as F

from terrain_slm.river.field import RiverContext, RiverField

MIN_WATER_M = 0.4


class NoSpill:
    name = "contain"

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        if not f.has_river:
            f.wet = torch.zeros_like(f.ground, dtype=torch.bool)
            f.top = torch.full_like(f.ground, float("nan"))
            ctx.stats["dried_px"] = 0
            return f
        carved = f.carved
        wet = f.channel & (f.ground > 0.5)
        top = f.level.clone()
        start = int(wet.sum())
        inf = torch.full_like(carved, float("inf"))
        for _ in range(512):
            dry = torch.where(wet, inf, carved)[None, None]
            top = torch.minimum(top, -F.max_pool2d(-dry, 3, 1, 1)[0, 0] - 0.05)
            still = wet & (top > carved + MIN_WATER_M)
            if torch.equal(still, wet):
                break
            wet = still
        ctx.stats["dried_px"] = start - int(wet.sum())
        ctx.stats["channel_px"] = start
        f.wet = wet
        f.top = torch.where(wet, top, torch.full_like(top, float("nan")))
        return f


class MonotoneTop:
    """After containment: the water top never rises downstream across the wet footprint either
    (NoSpill lowers the top locally where a bank is low, which would otherwise leave a dip the
    river then climbs out of). Lowered water keeps at least one block of depth by cutting the bed
    -- cutting inside the channel can never open a leak, and lowering water never spills."""
    name = "monotone_top"

    def __init__(self, iters: int = 96):
        self.iters = iters

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        from terrain_slm.river.geometry import monotone_downstream
        if f.wet is None or not bool(f.wet.any()):
            return f
        top = torch.where(f.wet, f.top, f.carved)
        mono = monotone_downstream(top, f.wet, f.flow, self.iters)
        lowered = f.wet & (mono < top - 1e-3)
        ctx.stats["top_lowered_px"] = int(lowered.sum())
        rate = ctx.scale.rate(mono)
        f.carved = torch.where(f.wet, torch.minimum(f.carved, mono - rate), f.carved)
        f.top = torch.where(f.wet, mono, f.top)
        return f
