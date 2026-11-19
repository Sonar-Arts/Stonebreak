"""Vegetation node at tile time: the surface channels canopy_cover / canopy_height / clearing / ground_* (contract:
world/surface_channels.py) from the VegNet sidecar (models/vegnet.py, optional `veg.pt` in the model folder - absent =
no channels = the game's pre-v3 vegetation).

Runs on the tile's block frame (tile + BLOCK_MARGIN) that TileBuilder._blocks hands over: the pre-river ground
(quantised like the training DEMs), the 7-channel climate lapsed to the regional elevation, standing water. 1:4
(60 m blocks) runs at block resolution; 1:2 (30 m blocks) runs on 2x2-pooled blocks and upsamples bilinearly.
Water columns (sea, lakes, rivers) carry no data (255).
"""
from __future__ import annotations

import torch
import torch.nn.functional as F

from terrain_slm.models import vegnet as VN

# output index -> surface channel name and physical scale
_CHANNELS = (("canopy_cover", "cover", 1.0), ("canopy_height", "hcan", VN.HEIGHT_SCALE), ("clearing", "clearing", 1.0),
             ("ground_grass", "grass", 1.0), ("ground_shrub", "shrub", 1.0), ("ground_moss", "moss", 1.0),
             ("ground_bare", "bare", 1.0), ("ground_wetland", "wetland", 1.0))


def provider(gen):
    """The tile builder's surface provider for this generator, or None (no veg.pt / no 7-channel climate node)."""
    if getattr(gen, "vegnet", None) is None or getattr(gen, "climate_net", None) is None:
        return None
    return lambda builder, ctx: channels(gen.vegnet, builder, ctx)


@torch.no_grad()
def channels(model: VN.VegNet, builder, ctx: dict) -> dict:
    d, mb = ctx["d"], ctx["mb"]
    if d not in (1, 2) or ctx["climate"].shape[0] < 7:
        return {}
    sc = builder.gen.scale
    sea = builder.world.sea_level
    pre = F.avg_pool2d(ctx["ground"][None, None], d)[0, 0]
    elev = sc.to_metres(torch.floor(sc.to_blocks(pre)).clamp(sc.min_y, sc.world_height - 1) + 0.5)
    height = ctx["height"]
    water = ((height < sea) | (ctx["wet"] & (ctx["water"] > height))).float()
    climate, regional = ctx["climate"].float(), ctx["regional"].float()
    if d == 2:
        out = VN.predict(model, elev, climate, regional, water)
    else:   # 1:2: the node was trained on 60 m blocks; the frame (tile + margin) starts on an even block
        half = lambda x: F.avg_pool2d(x[None] if x.dim() == 3 else x[None, None], 2)[0]
        out60 = VN.predict(model, half(elev)[0], half(climate), half(regional)[0], (half(water)[0] >= 0.5).float())
        out = F.interpolate(out60[None], scale_factor=2, mode="bilinear", align_corners=False)[0]
    crop = lambda x: x[..., mb:-mb, mb:-mb]
    out, wet = crop(out), crop(water) > 0.5
    idx = {name: k for k, name in enumerate(VN.OUTPUTS)}
    res = {}
    for channel, name, scale in _CHANNELS:
        v = out[idx[name]] * scale
        res[channel] = torch.where(wet, torch.full_like(v, float("nan")), v)
    return res
