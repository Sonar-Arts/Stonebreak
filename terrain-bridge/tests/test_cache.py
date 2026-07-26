import numpy as np
import pytest

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
    # -1 is the "no water" sentinel, so it has to survive the round trip as a
    # negative int16 rather than as an unsigned 65535.
    water = np.array([[320, -1], [-1, 400]], dtype=np.int16)
    cache.put(tile, h, b, water)

    got = cache.get(tile)
    assert got is not None
    got_h, got_b, got_water = got
    np.testing.assert_array_equal(got_h, h)
    np.testing.assert_array_equal(got_b, b)
    np.testing.assert_array_equal(got_water, water)


def test_config_change_gets_isolated_namespace(tmp_path):
    cache_a = TileCache(_cfg(tmp_path, sea_level=320))
    cache_b = TileCache(_cfg(tmp_path, sea_level=64))
    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    h = np.zeros((2, 2), dtype=np.int16)
    b = np.zeros((2, 2), dtype=np.int16)
    cache_a.put(tile, h, b, b)
    assert cache_b.get(tile) is None  # different fingerprint => different directory


def test_hydrology_knobs_get_their_own_namespace(tmp_path):
    """The carve's knobs live in `hydrology/` and reach the tile cache as a digest, so
    this is the only place a change to them can be caught before a stale tile is served
    with the wrong river in it."""
    cfg = _cfg(tmp_path)
    assert TileCache(cfg, "aaaaaaaaaaaa").root != TileCache(cfg, "bbbbbbbbbbbb").root
    # And "hydrology off" is its own namespace, not a subset of any of them.
    assert TileCache(cfg, "").root != TileCache(cfg, "aaaaaaaaaaaa").root


def test_a_payload_with_the_wrong_plane_count_is_rejected(tmp_path):
    """The body is bare concatenated planes with no header bytes, so a two-plane tile
    left over from before Phase 8 would be sliced into plausible garbage terrain. The
    fingerprint should already have kept it out of this namespace; this is the check
    that says so out loud if it did not."""
    cache = TileCache(_cfg(tmp_path))
    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    h = np.zeros((2, 2), dtype=np.int16)
    header = np.array([2, 2], dtype="<u4").tobytes()
    (cache.root / f"{tile.cache_key()}.bin").write_bytes(header + h.tobytes() * 2)
    with pytest.raises(ValueError, match="int16 planes"):
        cache.get(tile)


def test_lru_eviction_drops_oldest(tmp_path):
    cache = TileCache(_cfg(tmp_path, max_bytes=1))  # force eviction immediately
    tile1 = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    tile2 = TileId(seed=1, tile_x=1, tile_z=0, scale=2)
    h = np.zeros((2, 2), dtype=np.int16)
    b = np.zeros((2, 2), dtype=np.int16)
    cache.put(tile1, h, b, b)
    cache.put(tile2, h, b, b)
    stats = cache.stats()
    assert stats["tiles"] <= 1
