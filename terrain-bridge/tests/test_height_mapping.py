import numpy as np

from bridge.height_mapping import elevation_to_block_height


def test_sea_level_maps_to_configured_sea_level():
    elev = np.array([[0]], dtype=np.int16)
    out = elevation_to_block_height(elev, meters_per_block=15.0, sea_level=320, world_height=1024)
    assert out[0, 0] == 320


def test_positive_and_negative_elevation_scale_linearly():
    elev = np.array([[150, -150]], dtype=np.int16)
    out = elevation_to_block_height(elev, meters_per_block=15.0, sea_level=320, world_height=1024)
    assert out[0, 0] == 330  # +150m / 15 = +10 blocks
    assert out[0, 1] == 310  # -150m / 15 = -10 blocks


def test_clamped_to_world_height_ceiling_and_floor():
    # 20000m and -10000m both fall outside the 1024-block column at 15 m/block
    # (704 blocks above sea level, 320 below) and must clamp, not wrap or overflow.
    elev = np.array([[20000, -10000]], dtype=np.int16)
    out = elevation_to_block_height(elev, meters_per_block=15.0, sea_level=320, world_height=1024)
    assert out[0, 0] == 1023
    assert out[0, 1] == 0


def test_deterministic_no_per_tile_normalization():
    # Same elevation must map to the same block height regardless of what else
    # is in the tile — the whole point is no per-tile min/max, or cross-tile
    # seams reappear (plan.md section 5, Phase 1 item 5).
    tile_a = np.array([[100, 100, 100]], dtype=np.int16)
    tile_b = np.array([[100, 5000, -5000]], dtype=np.int16)
    out_a = elevation_to_block_height(tile_a, 15.0, 320, 1024)
    out_b = elevation_to_block_height(tile_b, 15.0, 320, 1024)
    assert out_a[0, 0] == out_b[0, 0]
