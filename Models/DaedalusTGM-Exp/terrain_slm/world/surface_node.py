"""Surface-channel provider of the surface-detail node (agent L, models/surfacenet.py): per block, what lies inside it.

Channels (world/surface_channels.py): rock_exposure, scree (shares), boulder_density (boulders >= 1.6 m per 100 m^2,
capped at 1), boulder_size (m), surface_roughness (RMS of the < ~10 m relief, m). NaN (= no data, byte 255) on water
columns (sea, lakes, rivers) and where the generator has no `surface.pt`.

The node reads the 60 m ground of the build frame (tile + BLOCK_MARGIN; its receptive field RF = 6 samples is far
inside the margin, so a column's value never depends on the request that built it) and the frame's climate:
- 1:4 (60 m blocks, d = 2): its 60 m head, one value per block;
- 1:2 (30 m blocks, d = 1): its 15 m head pooled 2 x 2 per block;
- 1:1 (15 m blocks): its 15 m head per block (ctx from TileBuilder._blocks_fine, 60 m = 4 x 4 blocks).
Overview / LOD tiles (d > 2) carry none (the native generator only builds cubes from lod-1 tiles).
"""
from __future__ import annotations

import torch
import torch.nn.functional as F

from terrain_slm.models import surfacenet as SN

CHANNEL_OF = {"rock_exposure": 0, "scree": 1, "surface_roughness": 3, "boulder_density": 4, "boulder_size": 5}


def provider(gen):
    """The provider when the generator loaded `surface.pt`, else None."""
    if getattr(gen, "surfacenet", None) is None:
        return None
    return lambda builder, ctx: fields(gen.surfacenet, builder, ctx)


def fields(model, builder, ctx: dict) -> dict:
    d, mb = ctx["d"], ctx["mb"]
    clim = ctx["climate"].float()           # (C, frame rows, frame cols) per block
    ground = ctx["ground"].float()          # 30 m native pixels of the frame (blocks x d), or 15 m blocks at 1:1
    if d == 2:
        z60 = F.avg_pool2d(ground[None, None], 2)[0, 0]
        temp, precip = clim[0], clim[2]
    elif d == 1:
        z60 = F.avg_pool2d(ground[None, None], 2)[0, 0]
        temp, precip = (F.avg_pool2d(clim[k][None, None], 2)[0, 0] for k in (0, 2))
    elif d == 0.5:                           # 1:1: ground and climate per 15 m block
        z60 = F.avg_pool2d(ground[None, None], 4)[0, 0]
        temp, precip = (F.avg_pool2d(clim[k][None, None], 4)[0, 0] for k in (0, 2))
    else:
        return {}
    p60, p15 = SN.predict(model, z60, temp, precip)
    if d == 2:
        per_block = p60
    elif d == 1:
        per_block = F.avg_pool2d(p15[None], 2)[0]
    else:
        per_block = p15
    crop = lambda x: x[..., mb:-mb, mb:-mb]
    per_block = crop(per_block)
    height, wet, level = crop(ctx["height"]), crop(ctx["wet"]), crop(ctx["water"])
    if per_block.shape[-2:] != height.shape:
        raise RuntimeError(f"surface node frame {tuple(per_block.shape)} vs tile {tuple(height.shape)}")
    water = (height < builder.world.sea_level) | (wet & (level > height))   # same water rule as the vegetation node
    out = {}
    for name, ch in CHANNEL_OF.items():
        v = per_block[ch].clone()
        v[water] = float("nan")
        out[name] = v
    return out
