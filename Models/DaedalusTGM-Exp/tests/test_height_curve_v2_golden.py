"""FROZEN height curve v2 (2026-11-19 release freeze): curve v2 shipped with EXP-2 release exp2-r3. Saved v2 worlds
depend on these exact numbers - a world must never get different block heights for already-explored land. Any later
tuning is a NEW curve version (v3), never an edit of v2. If this test fails, revert the curve change or add v3.

The configs are exactly what the game sends in its handshake for curve v2 (TerrainScale.worldConfigJson, pinned on the
Java side by HeightCurveV2GoldenTest)."""
import json

import numpy as np
import pytest

from terrain_slm.world.world_config import WorldConfig

ELEVATIONS_M = [-9700, -3000, -1000, -300, -100, -24, -5, 0, 5, 50, 300, 600, 1000, 2000, 3000, 5150]

GOLDEN = {
    '1:4': (
        '{"world_height":640,"min_y":-384,"sea_level":64,"horizontal_m":60.0,"downscale":2,"tile_size":256,"ocean_m_per_block":32.0,"lowland_m_per_block":16.0,"midland_m_per_block":24.0,"highland_m_per_block":38.0,"lowland_top_m":600.0,"highland_base_m":2000.0,"shore_blend_m":10.0,"midland_blend_m":150.0,"highland_blend_m":300.0,"climate_layout":"belts","curve_version":2,"shelf_m_per_block":6.0,"shelf_base_m":-300.0,"shelf_blend_m":150.0,"shallow_m_per_block":4.0,"shallow_base_m":-24.0,"shallow_blend_m":12.0}',
        '5729ec789308',
        [-274.006998, -64.631998, -2.131998, 20.551972, 46.062608, 58.846126, 63.299531, 64.0, 64.402952, 67.222652, 82.847652, 101.597652, 124.982955, 171.54108, 198.370879, 254.949827]),
    '1:2': (
        '{"world_height":640,"min_y":-384,"sea_level":64,"horizontal_m":30.0,"downscale":1,"tile_size":256,"ocean_m_per_block":32.0,"lowland_m_per_block":8.0,"midland_m_per_block":12.0,"highland_m_per_block":19.0,"lowland_top_m":600.0,"highland_base_m":2000.0,"shore_blend_m":10.0,"midland_blend_m":150.0,"highland_blend_m":300.0,"climate_layout":"belts","curve_version":2,"shelf_m_per_block":3.0,"shelf_base_m":-300.0,"shelf_blend_m":150.0,"shallow_m_per_block":2.0,"shallow_base_m":-24.0,"shallow_blend_m":12.0}',
        'db1addf31200',
        [-311.130975, -101.755975, -39.255975, -16.436215, 28.125216, 53.692252, 62.599062, 64.0, 64.805904, 70.445303, 101.695303, 139.195303, 185.96591, 279.082159, 332.741759, 445.899653]),
    '1:1': (
        '{"world_height":640,"min_y":-384,"sea_level":64,"horizontal_m":15.0,"downscale":0.5,"tile_size":256,"ocean_m_per_block":48.0,"lowland_m_per_block":4.0,"midland_m_per_block":6.85,"highland_m_per_block":32.0,"lowland_top_m":600.0,"highland_base_m":2600.0,"shore_blend_m":10.0,"midland_blend_m":150.0,"highland_blend_m":1000.0,"climate_layout":"belts","curve_version":2,"shelf_m_per_block":1.5,"shelf_base_m":-300.0,"shelf_blend_m":150.0,"shallow_m_per_block":1.0,"shallow_base_m":-24.0,"shallow_blend_m":12.0}',
        '8cc8f5215470',
        [-276.430394, -136.84706, -95.180394, -79.901557, -7.749568, 43.384504, 61.198125, 64.0, 65.611808, 76.890606, 139.390606, 214.390606, 307.946586, 494.295027, 561.989369, 629.905324]),
}


def _curve(w):
    c = w.curve
    return c() if callable(c) else c


@pytest.mark.parametrize("scale", sorted(GOLDEN))
def test_curve_v2_frozen(scale):
    cfg, fingerprint, blocks = GOLDEN[scale]
    w = WorldConfig.from_dict(json.loads(cfg))
    assert w.curve_version == 2
    assert w.fingerprint() == fingerprint, "v2 world fingerprint changed: caches and saved worlds would split"
    got = _curve(w).to_block_height_exact(ELEVATIONS_M)
    np.testing.assert_allclose(got, blocks, rtol=0, atol=1e-5)
