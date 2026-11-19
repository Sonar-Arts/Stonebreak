"""Vegetation node (models/vegnet.py + world/vegetation_node.py): channel shapes, water = no data, and request
independence at 1:4 (a column's channels do not depend on which tile frame computed them)."""
from types import SimpleNamespace

import numpy as np
import torch

from terrain_slm.models import vegnet as VN
from terrain_slm.river.pipeline import BLOCK_MARGIN
from terrain_slm.river.scale import BlockScale
from terrain_slm.world import surface_channels as SC
from terrain_slm.world import vegetation_node as VEG


def _world(h, w, seed=0):
    g = torch.Generator().manual_seed(seed)
    yy, xx = torch.meshgrid(torch.arange(h, dtype=torch.float32), torch.arange(w, dtype=torch.float32), indexing="ij")
    ground = 300 + 200 * torch.sin(xx / 17) * torch.cos(yy / 23) + 20 * torch.rand(h, w, generator=g)
    ground[:, :6] = -20.0                                   # a strip of sea
    climate = torch.stack([12 + 0 * xx, 600 + 0 * xx, 900 + 3 * yy, 40 + 0 * xx, 1.2 + 0 * xx, 0.3 + 0 * xx, 0 * xx])
    return ground, climate


def _ctx(ground, climate, i0, j0, n, mb, scale, sea=64):
    sl = (slice(i0 - mb, i0 + n + mb), slice(j0 - mb, j0 + n + mb))
    g = ground[sl]
    height = torch.floor(scale.to_blocks(g)).long()
    return {"d": 2, "i1": i0, "j1": j0, "mb": mb, "ground": g.repeat_interleave(2, 0).repeat_interleave(2, 1),
            "climate": climate[(slice(None),) + sl], "regional": torch.full_like(g, 300.0), "height": height,
            "water": torch.full_like(height, -1), "wet": torch.zeros_like(height, dtype=torch.bool), "biome": None}


def test_channels_shape_water_and_request_independence():
    torch.manual_seed(0)
    model = VN.VegNet(VN.VegNetConfig(channels=(8, 16))).eval()
    scale = BlockScale.game_default("cpu")
    gen = SimpleNamespace(scale=scale, vegnet=model, climate_net=object())
    builder = SimpleNamespace(gen=gen, world=SimpleNamespace(sea_level=64))
    ground, climate = _world(260, 260)
    mb, n = BLOCK_MARGIN, 64
    a = VEG.channels(model, builder, _ctx(ground, climate, 40, 40, n, mb, scale))
    b = VEG.channels(model, builder, _ctx(ground, climate, 40 + 32, 40, n, mb, scale))   # overlapping frame
    assert set(a) == {"canopy_cover", "canopy_height", "clearing", "ground_grass", "ground_shrub", "ground_moss",
                      "ground_bare", "ground_wetland"}
    assert a["canopy_cover"].shape == (n, n)
    # rows 32..63 of a == rows 0..31 of b (same world columns): identical up to float noise
    assert torch.allclose(a["canopy_cover"][32:], b["canopy_cover"][:32], atol=1e-5)
    assert torch.allclose(a["canopy_height"][32:], b["canopy_height"][:32], atol=1e-4)
    # the sea strip (columns < 6) is outside this tile; a tile over it carries no data there
    c = VEG.channels(model, builder, _ctx(ground, climate, 40, mb, n, mb, scale))
    enc = SC.encode("canopy_cover", c["canopy_cover"])
    assert (enc[:, :6 - mb] == SC.NO_DATA).all() if 6 - mb > 0 else True
    sea_cols = (_ctx(ground, climate, 40, mb, n, mb, scale)["height"][mb:-mb, mb:-mb] < 64).numpy()
    assert (enc[sea_cols] == SC.NO_DATA).all() and (enc[~sea_cols] != SC.NO_DATA).all()


def test_provider_absent_without_checkpoint():
    assert VEG.provider(SimpleNamespace(vegnet=None, climate_net=object())) is None
    assert VEG.provider(SimpleNamespace(vegnet=object(), climate_net=None)) is None


def test_receptive_field_fits_the_margin():
    """A column's prediction must not see past BLOCK_MARGIN (60 m blocks at 1:4)."""
    torch.manual_seed(1)
    model = VN.VegNet(VN.VegNetConfig(channels=(8, 16))).eval()
    h = 2 * BLOCK_MARGIN + 9
    x = torch.randn(1, VN.N_IN, h, h, requires_grad=True)
    model(x)[0, :, h // 2, h // 2].sum().backward()
    g = x.grad[0].abs().sum(0)
    reach = torch.nonzero(g > 0)
    r = int((reach - h // 2).abs().max())
    # the network's own reach + the widest input feature window (box 17: 8) must stay inside the margin
    assert r + 8 <= BLOCK_MARGIN, r
