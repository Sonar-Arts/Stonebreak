import numpy as np

from bridge.cache import TileCache
from bridge.config import BridgeConfig
from bridge.tiling import TileId


def _cfg(tmp_path, max_bytes=10_000_000, sea_level=320):
    return BridgeConfig(
        upstream_url="http://localhost:8000",
        seed=1,
        scale=2,
        tile_size_blocks=4,
        meters_per_block=15.0,
        world_height=1024,
        sea_level=sea_level,
        noise_scale=1.0,
        cache_dir=str(tmp_path),
        cache_max_bytes=max_bytes,
        upstream_timeout_s=5.0,
    )


def test_miss_then_hit(tmp_path):
    cache = TileCache(_cfg(tmp_path))
    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    assert cache.get(tile) is None

    h = np.array([[1, 2], [3, 4]], dtype=np.int16)
    b = np.array([[5, 6], [7, 8]], dtype=np.int16)
    cache.put(tile, h, b)

    got = cache.get(tile)
    assert got is not None
    got_h, got_b = got
    np.testing.assert_array_equal(got_h, h)
    np.testing.assert_array_equal(got_b, b)


def test_config_change_gets_isolated_namespace(tmp_path):
    cache_a = TileCache(_cfg(tmp_path, sea_level=320))
    cache_b = TileCache(_cfg(tmp_path, sea_level=64))
    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    h = np.zeros((2, 2), dtype=np.int16)
    b = np.zeros((2, 2), dtype=np.int16)
    cache_a.put(tile, h, b)
    assert cache_b.get(tile) is None  # different fingerprint => different directory


def test_lru_eviction_drops_oldest(tmp_path):
    cache = TileCache(_cfg(tmp_path, max_bytes=1))  # force eviction immediately
    tile1 = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    tile2 = TileId(seed=1, tile_x=1, tile_z=0, scale=2)
    h = np.zeros((2, 2), dtype=np.int16)
    b = np.zeros((2, 2), dtype=np.int16)
    cache.put(tile1, h, b)
    cache.put(tile2, h, b)
    stats = cache.stats()
    assert stats["tiles"] <= 1
