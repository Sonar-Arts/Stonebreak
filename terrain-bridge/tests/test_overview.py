import asyncio

import numpy as np
import pytest

from bridge.cache import TileCache
from bridge.config import BridgeConfig
from bridge.height_mapping import HeightCurve
from bridge.overview import OverviewTiles, check_lod
from bridge.queue import GpuWorkQueue
from bridge.tiling import TileId
from bridge.water import SeaLevelWater


def _cfg(tmp_path, tile_size=16, scale=2):
    return BridgeConfig(
        upstream_url="http://localhost:8000",
        seed=7,
        scale=scale,
        tile_size_blocks=tile_size,
        meters_per_block=15.0,
        world_height=1024,
        sea_level=320,
        noise_scale=1.0,
        cache_dir=str(tmp_path),
        cache_max_bytes=10_000_000,
        upstream_timeout_s=5.0,
    )


class FakeClient:
    """Native elevation is a pure function of native pixel coords; biome = row index."""

    def __init__(self):
        self.native_calls = []
        self.tile_calls = []

    def fetch_native_with_biome(self, i1, j1, i2, j2):
        self.native_calls.append((i1, j1, i2, j2))
        i = np.arange(i1, i2, dtype=np.float64)[:, None]
        j = np.arange(j1, j2, dtype=np.float64)[None, :]
        elev = (3.0 * i + j).astype(np.int16)
        biome = np.broadcast_to(np.arange(i1, i2, dtype=np.int16)[:, None], elev.shape).copy()
        return elev, biome

    def fetch_tile(self, i1, j1, i2, j2):
        self.tile_calls.append((i1, j1, i2, j2))
        h = i2 - i1
        return np.full((h, h), 30, dtype=np.int16), np.full((h, h), 1, dtype=np.int16)


def test_one_square_native_fetch_per_tile_in_sample_units(tmp_path):
    cfg = _cfg(tmp_path)
    client = FakeClient()
    heights, biome, water = OverviewTiles(cfg, client).planes(
        TileId(seed=7, tile_x=1, tile_z=-1, scale=2, lod=8))
    # Tile (1,-1) spans samples [16,32) x [-16,0); 4 native px per sample at lod 8, scale 2.
    assert client.native_calls == [(64, -64, 128, 0)]
    assert heights.shape == biome.shape == water.shape == (16, 16)
    assert heights.dtype == biome.dtype == water.dtype == np.int16


def test_heights_are_the_curve_of_the_mean_metres(tmp_path):
    cfg = _cfg(tmp_path)
    heights, _, water = OverviewTiles(cfg, FakeClient()).planes(
        TileId(seed=7, tile_x=0, tile_z=0, scale=2, lod=8))
    # Sample (0,0) pools native px 0..3 x 0..3: mean of 3i+j = 3*1.5 + 1.5 = 6 m.
    curve = HeightCurve.from_config(cfg)
    assert heights[0, 0] == curve.to_block_height(np.array([6.0]))[0]
    sea = cfg.sea_level
    assert ((water == sea) == (heights < sea)).all()
    assert ((water == -1) == (heights >= sea)).all()


def test_biome_is_the_sample_corner_pixel(tmp_path):
    cfg = _cfg(tmp_path)
    _, biome, _ = OverviewTiles(cfg, FakeClient()).planes(
        TileId(seed=7, tile_x=0, tile_z=0, scale=2, lod=8))
    assert biome[:, 0].tolist() == list(range(0, 64, 4))


@pytest.mark.parametrize("lod", [3, 0, -8, 1024])
def test_unservable_lods_are_rejected(tmp_path, lod):
    with pytest.raises(ValueError):
        check_lod(_cfg(tmp_path, tile_size=256), lod)


def test_default_lods_fit_the_square_limit(tmp_path):
    cfg = _cfg(tmp_path, tile_size=256)
    for lod in (1, 2, 4, 8, 16):
        check_lod(cfg, lod)
    with pytest.raises(ValueError):
        check_lod(cfg, 32)


def test_lod_tiles_cache_apart_from_full_tiles():
    base = TileId(seed=1, tile_x=2, tile_z=3, scale=1)
    assert base.cache_key() == "s1_x2_z3_sc1"  # existing caches keep their keys
    assert TileId(seed=1, tile_x=2, tile_z=3, scale=1, lod=8).cache_key() == "s1_x2_z3_sc1_lod8"


def test_queue_routes_lod_tiles_to_the_overview(tmp_path):
    cfg = _cfg(tmp_path)
    client = FakeClient()

    async def run():
        q = GpuWorkQueue(cfg, TileCache(cfg), client, SeaLevelWater(cfg))
        q.start()
        try:
            await q.get_tile(TileId(seed=7, tile_x=0, tile_z=0, scale=2, lod=8))
            await q.get_tile(TileId(seed=7, tile_x=0, tile_z=0, scale=2))
            (_, _, _), cached = await q.get_tile(TileId(seed=7, tile_x=0, tile_z=0, scale=2, lod=8))
            return cached
        finally:
            await q.stop()

    assert asyncio.run(run()) is True
    assert len(client.native_calls) == 1
    assert len(client.tile_calls) == 1
