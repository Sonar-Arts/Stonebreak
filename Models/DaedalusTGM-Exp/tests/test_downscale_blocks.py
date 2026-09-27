"""1:4 world path (downscale=2): the river pipeline's block stages against the game's curve,
including the 3D river planes (tunnel floor / roof, flow octants). Skips without a model."""
import os
from pathlib import Path

import numpy as np
import pytest
import torch

MODEL = Path(__file__).resolve().parents[1] / "checkpoints/v3"
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
pytestmark = pytest.mark.skipif(not (MODEL / "planner.pt").exists(), reason="no packaged model")

ENV_1_TO_4 = {
    "TERRAIN_BRIDGE_WORLD_HEIGHT": "256", "TERRAIN_BRIDGE_SEA_LEVEL": "64",
    "TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK": "48", "TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK": "16",
    "TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK": "24", "TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK": "38",
}


@pytest.fixture(scope="module")
def api():
    old = {k: os.environ.get(k) for k in ENV_1_TO_4}
    os.environ.update(ENV_1_TO_4)
    from terrain_slm.river.scale import BlockScale
    from terrain_slm.serve import upstream_api as A
    from terrain_slm.world.generator import WorldGenerator

    A.CURVE = A._bridge_curve(7)
    A.GEN = WorldGenerator(MODEL, seed=7, device=DEV)
    A.GEN.set_block_scale(BlockScale.from_curve(A.CURVE, DEV))
    yield A
    for k, v in old.items():
        if v is None:
            os.environ.pop(k, None)
        else:
            os.environ[k] = v


def _blocks(A, elev, water, river):
    """What the bridge would send the game: block heights, water levels, tunnel planes."""
    h = A.CURVE.to_block_height(elev.cpu().numpy())
    wv = water.cpu().numpy()
    wet = ~np.isnan(wv)
    lv = np.where(wet, A.CURVE.to_block_height(np.nan_to_num(wv)), -1)
    floor, roof, flow = (p.cpu().numpy() for p in river)
    return h, lv, wet, floor, roof, flow


def test_tile_matches_bigger_request(api):
    e1, _, w1, r1 = api._downsampled(0, 0, 64, 64, 2)
    e2, _, w2, r2 = api._downsampled(-32, -32, 96, 96, 2)
    crop = lambda t: t[32:96, 32:96]
    b1 = _blocks(api, e1, w1, r1)
    b2 = _blocks(api, crop(e2), crop(w2), tuple(crop(p) for p in r2))
    for x, y in zip(b1, b2):
        assert np.array_equal(x, y)


def _solid(h, lv, floor, roof, y):
    """Is block y of a column solid ground? (TerrainTile semantics, no caves.)"""
    tunnel = (roof > floor) & (y >= floor) & (y <= roof)
    in_void = tunnel & (y > floor) & (y < roof)
    return (y < h) & ~in_void


def test_block_rivers_hold_their_water_in_3d(api):
    elev, _, water, river = api._downsampled(-256, -256, 256, 256, 2)
    h, lv, wet, floor, roof, flow = _blocks(api, elev, water, river)
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
    sea = elev.cpu().numpy() <= 0.5
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
    elev, _, water, river = api._downsampled(-256, -256, 256, 256, 2)
    h, lv, wet, floor, roof, flow = _blocks(api, elev, water, river)
    tunnel = roof > floor
    assert ((floor == -1) == (roof == -1)).all()
    assert (h[tunnel] >= roof[tunnel] + 1).all(), "a tunnel roof breaches the surface"
    assert ((flow >= -1) & (flow <= 7)).all()
    assert (flow[~wet] == -1).all(), "flow on a dry column"
    assert (flow[wet] >= 0).mean() > 0.9, "most river columns should run somewhere"


def test_far_zoom_overview_matches_itself_and_the_contract(api):
    """lod-8 preview blocks (d = 16 px = 2 cells per sample): same planes as a full tile, request-
    independent, rivers flowing, no tunnels; far cheaper than full tiles."""
    e1, b1, w1, r1 = api._overview(0, 0, 32, 32, 16)
    e2, b2, w2, r2 = api._overview(-16, -16, 48, 48, 16)
    crop = lambda t: t[16:48, 16:48]
    assert torch.equal(e1, crop(e2)) and torch.equal(b1, crop(b2))
    assert torch.equal(torch.isnan(w1), torch.isnan(crop(w2)))
    floor, roof, flow = r1
    assert (floor == -1).all() and (roof == -1).all()
    wet = ~torch.isnan(w1)
    assert (flow[~wet] == -1).all() and (flow[wet] >= 0).float().mean() > 0.9
