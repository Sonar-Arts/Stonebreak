"""1:4 world path (downscale=2): block-resolution water settling against the bridge curve.
Skips without a packaged model."""
import os
from pathlib import Path

import numpy as np
import pytest
import torch

MODEL = Path(__file__).resolve().parents[1] / "checkpoints/v2"
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
pytestmark = pytest.mark.skipif(not (MODEL / "planner.pt").exists(), reason="no packaged model")

ENV_1_TO_4 = {
    "TERRAIN_BRIDGE_WORLD_HEIGHT": "256", "TERRAIN_BRIDGE_SEA_LEVEL": "64",
    "TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK": "48", "TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK": "16",
    "TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK": "40", "TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK": "96",
}


@pytest.fixture(scope="module")
def api():
    old = {k: os.environ.get(k) for k in ENV_1_TO_4}
    os.environ.update(ENV_1_TO_4)
    from terrain_slm.serve import upstream_api as A
    from terrain_slm.world.generator import WorldGenerator

    A.CURVE = A._bridge_curve(7)
    A.GEN = WorldGenerator(MODEL, seed=7, device=DEV)
    yield A
    for k, v in old.items():
        if v is None:
            os.environ.pop(k, None)
        else:
            os.environ[k] = v


def _blocks(A, elev, water):
    h = A.CURVE.to_block_height(elev.cpu().numpy())
    wet = ~np.isnan(water.cpu().numpy())
    lv = np.where(wet, A.CURVE.to_block_height(np.nan_to_num(water.cpu().numpy())), -1)
    return h, lv, wet


def test_tile_matches_bigger_request(api):
    e1, _, w1 = api._downsampled(0, 0, 64, 64, 2, True)
    e2, _, w2 = api._downsampled(-32, -32, 96, 96, 2, True)
    h1, l1, _ = _blocks(api, e1, w1)
    h2, l2, _ = _blocks(api, e2[32:96, 32:96], w2[32:96, 32:96])
    assert np.array_equal(h1, h2) and np.array_equal(l1, l2)


def test_block_water_is_at_least_one_deep_and_never_spills(api):
    elev, _, water = api._downsampled(-256, -256, 256, 256, 2, True)
    h, lv, wet = _blocks(api, elev, water)
    assert wet.mean() > 0.002, "no rivers in a 30 km square at 1:4"
    assert (lv[wet] >= h[wet] + 1).all(), "a river column holds no water block"
    inner = np.zeros_like(wet)
    inner[1:-1, 1:-1] = True
    for di in (-1, 0, 1):
        for dj in (-1, 0, 1):
            if di == dj == 0:
                continue
            nb_wet = np.roll(wet, (di, dj), (0, 1))
            nb_h = np.roll(h, (di, dj), (0, 1))
            nb_sea = np.roll(elev.cpu().numpy() <= 0.5, (di, dj), (0, 1))
            spill = wet & ~nb_wet & ~nb_sea & (nb_h < lv) & inner
            assert int(spill.sum()) == 0
