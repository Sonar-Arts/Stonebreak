"""1:4 world path (downscale=2): the river pipeline's block stages against the game's curve,
including the 3D river planes (tunnel floor / roof, flow octants). Skips without a model."""
from pathlib import Path

import numpy as np
import pytest
import torch

MODEL = Path(__file__).resolve().parents[1] / "checkpoints/v3"
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
pytestmark = pytest.mark.skipif(not (MODEL / "planner.pt").exists(), reason="no packaged model")

@pytest.fixture(scope="module")
def api():
    from terrain_slm.world.generator import WorldGenerator
    from terrain_slm.world.tiles import TileBuilder
    from terrain_slm.world.world_config import WorldConfig

    gen = WorldGenerator(MODEL, seed=7, device=DEV)
    builder = TileBuilder(gen, WorldConfig.GAME)
    gen.use_seed(7)
    return builder


def _blocks(api, out):
    """Block heights, river water levels (-1 dry), wet mask and tunnel planes, as numpy."""
    height, _, level, wet, river = out
    top = api.world.world_height - 1
    h = height.clamp(0, top).cpu().numpy()
    wet = wet.cpu().numpy()
    lv = np.where(wet, level.clamp(0, top).cpu().numpy(), -1)
    floor, roof, flow = (p.cpu().numpy() for p in river)
    return h, lv, wet, floor, roof, flow


def test_tile_matches_bigger_request(api):
    b1 = _blocks(api, api._blocks(0, 0, 64, 64, 2))
    crop = lambda t: t[32:96, 32:96]
    b2 = tuple(crop(x) for x in _blocks(api, api._blocks(-32, -32, 96, 96, 2)))
    for x, y in zip(b1, b2):
        assert np.array_equal(x, y)


def test_tile_planes_follow_the_water_rule(api):
    planes = api.build(7, 0, 0, 1)
    assert planes.shape == (6, api.world.tile_size, api.world.tile_size) and planes.dtype == np.int16
    h, _, water, floor, roof, flow = planes
    sea = api.world.sea_level
    assert (water[h < sea] >= sea).all(), "a column below sea level holds no sea"
    wet = water >= 0
    assert ((water[wet] > h[wet]) | (floor[wet] >= 0)).all(), "water level at or under dry ground"


def _solid(h, lv, floor, roof, y):
    """Is block y of a column solid ground? (TerrainTile semantics, no caves.)"""
    tunnel = (roof > floor) & (y >= floor) & (y <= roof)
    in_void = tunnel & (y > floor) & (y < roof)
    return (y < h) & ~in_void


def test_block_rivers_hold_their_water_in_3d(api):
    h, lv, wet, floor, roof, flow = _blocks(api, api._blocks(-256, -256, 256, 256, 2))
    assert wet.mean() > 0.002, "no rivers in a 30 km square at 1:4"
    tunnel = roof > floor
    # Every wet column holds at least one water block: open columns above their ground,
    # tunnelled ones inside their void.
    open_wet = wet & ~tunnel
    assert (lv[open_wet] >= h[open_wet] + 1).all()
    assert (lv[wet & tunnel] >= floor[wet & tunnel] + 2).all()
    # The top water block of each wet column (y = lv - 1) must meet, horizontally, ground, the
    # sea, or ANOTHER WET COLUMN of the same river: never dry air, an undercut's air pocket or a
    # tunnel. A wet neighbour one block lower is the river flowing downhill (a one-block cascade
    # that stays in the channel); anything taller would be a waterfall inside a river.
    sea = h < api.world.sea_level
    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nb = lambda a: np.roll(a, (di, dj), (0, 1))
        y = lv - 1
        nb_solid = _solid(nb(h), nb(lv), nb(floor), nb(roof), y)
        leak = wet & ~nb(wet) & ~nb_solid & ~nb(sea)
        step = wet & nb(wet) & (lv - nb(lv) > 1)
        for m in (leak, step):
            m[[0, -1], :] = False
            m[:, [0, -1]] = False
        assert int(leak.sum()) == 0, f"{int(leak.sum())} water blocks open to dry air toward {(di, dj)}"
        assert int(step.sum()) == 0, f"{int(step.sum())} river steps taller than one block toward {(di, dj)}"


def test_river_planes_are_well_formed(api):
    h, lv, wet, floor, roof, flow = _blocks(api, api._blocks(-256, -256, 256, 256, 2))
    tunnel = roof > floor
    assert ((floor == -1) == (roof == -1)).all()
    assert (h[tunnel] >= roof[tunnel] + 1).all(), "a tunnel roof breaches the surface"
    assert ((flow >= -1) & (flow <= 7)).all()
    assert (flow[~wet] == -1).all(), "flow on a dry column"
    assert (flow[wet] >= 0).mean() > 0.9, "most river columns should run somewhere"


def test_far_zoom_overview_matches_itself_and_the_contract(api):
    """lod-8 preview samples (d = 16 px = 2 cells per sample): same planes as a full tile, request-
    independent, rivers flowing, no tunnels; far cheaper than full tiles."""
    h1, b1, l1, w1, r1 = api._overview(0, 0, 32, 32, 16)
    h2, b2, l2, w2, r2 = api._overview(-16, -16, 48, 48, 16)
    crop = lambda t: t[16:48, 16:48]
    assert torch.equal(h1, crop(h2)) and torch.equal(b1, crop(b2)) and torch.equal(w1, crop(w2))
    floor, roof, flow = r1
    assert (floor == -1).all() and (roof == -1).all()
    assert (flow[~w1] == -1).all() and (flow[w1] >= 0).float().mean() > 0.9
