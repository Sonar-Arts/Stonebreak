"""Containment at model resolution: water never stands above adjacent dry ground.

Every dry pixel caps its 8 neighbours' water top. Ground below sea level is not dry ground at
its (sea-floor) height: the tile builder fills it with water up to the sea's surface (0 m, the top
of block sea_level - 1), so it caps the river AT that surface: a river reaching the sea or a lake
cuts its last reach below sea level and runs into it water to water. (Capping at the top of block
sea_level instead perched every river mouth one block above the sea, and BlockContainment then
walled it off with a dry column.) Where the cap leaves less than MIN_WATER_M over the bed, the
bed is cut instead of the water drying: a dried pixel used to cap its neighbours at its own bed,
which dried the next one, and so on -- a single pixel beside a sub-sea-level pit dried up to
512 px of river (rivers that stopped and started again). Cutting inside the channel never opens a leak, and lowering water never spills.
The block-level stage (blocks.BlockContainment) re-checks after quantisation.
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
            ctx.stats["incised_px"] = 0
            return f
        carved = f.carved
        wet = f.channel & (f.ground > 0.5)
        sc = ctx.scale
        sea_level_m = float(sc.to_metres(torch.tensor([float(sc.sea_level)], device=carved.device)))
        wall = torch.where(carved < sea_level_m, torch.full_like(carved, sea_level_m), carved)
        wall = torch.where(wet, torch.full_like(carved, float("inf")), wall)
        top = torch.minimum(f.level, -F.max_pool2d(-wall[None, None], 3, 1, 1)[0, 0] - 0.05)
        cut = wet & (top < carved + MIN_WATER_M)
        ctx.stats["incised_px"] = int(cut.sum())
        ctx.stats["incised_max_m"] = float((carved - top + MIN_WATER_M)[cut].max()) if bool(cut.any()) else 0.0
        ctx.stats["channel_px"] = int(wet.sum())
        f.carved = torch.where(cut, top - MIN_WATER_M, carved)
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
