import asyncio
import threading

import numpy as np
import pytest

from bridge.cache import TileCache
from bridge.config import BridgeConfig
from bridge.queue import GpuWorkQueue, TilePending
from bridge.tiling import TileId
from bridge.water import SeaLevelWater


class _FakeClient:
    """Stands in for UpstreamClient: records call order/concurrency instead
    of hitting a real GPU server."""

    def __init__(self, elevation_m: int = 30):
        self.calls: list[tuple[int, int, int, int]] = []
        self.concurrent = 0
        self.max_concurrent = 0
        self.elevation_m = elevation_m

    def __repr__(self):
        return f"_FakeClient(calls={len(self.calls)})"

    def fetch_tile(self, i1, j1, i2, j2):
        self.concurrent += 1
        self.max_concurrent = max(self.max_concurrent, self.concurrent)
        self.calls.append((i1, j1, i2, j2))
        try:
            import time

            time.sleep(0.02)
            h = w = i2 - i1
            elev = np.full((h, w), self.elevation_m, dtype=np.int16)
            biome = np.full((h, w), 1, dtype=np.int16)
            return elev, biome
        finally:
            self.concurrent -= 1


class _GatedClient:
    """A fetch_tile that blocks until the test releases it. Lets a test observe
    "the job is still running" deterministically instead of racing a sleep against
    a timeout."""

    def __init__(self):
        self.calls: list[tuple[int, int, int, int]] = []
        self._gate = threading.Event()

    def release(self) -> None:
        self._gate.set()

    def fetch_tile(self, i1, j1, i2, j2):
        self.calls.append((i1, j1, i2, j2))
        self._gate.wait()
        h = w = i2 - i1
        elev = np.full((h, w), 30, dtype=np.int16)
        biome = np.full((h, w), 1, dtype=np.int16)
        return elev, biome


def _cfg(tmp_path):
    return BridgeConfig(
        upstream_url="http://localhost:8000",
        seed=1,
        scale=2,
        tile_size_blocks=4,
        meters_per_block=15.0,
        world_height=1024,
        sea_level=320,
        noise_scale=1.0,
        cache_dir=str(tmp_path),
        cache_max_bytes=10_000_000,
        upstream_timeout_s=5.0,
    )


@pytest.mark.asyncio
async def test_concurrent_requests_for_same_tile_dedupe(tmp_path):
    cfg = _cfg(tmp_path)
    cache = TileCache(cfg)
    client = _FakeClient()
    q = GpuWorkQueue(cfg, cache, client, SeaLevelWater(cfg))
    q.start()

    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    results = await asyncio.gather(*[q.get_tile(tile) for _ in range(5)])

    assert len(client.calls) == 1  # only one upstream fetch for 5 concurrent requests
    assert client.max_concurrent == 1  # never called concurrently
    for (block_height, biome, water_level), _from_cache in results:
        assert block_height.shape == biome.shape == water_level.shape == (4, 4)

    await q.stop()


@pytest.mark.asyncio
async def test_the_water_plane_is_the_old_sea_level_rule_per_column(tmp_path):
    """With hydrology off, `y < waterLevel` has to place exactly the blocks
    `y < SEA_LEVEL` used to. Land above sea level carries the -1 "no water" sentinel;
    anything below carries sea level itself."""
    cfg = _cfg(tmp_path)
    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)

    q = GpuWorkQueue(cfg, TileCache(cfg), _FakeClient(elevation_m=30), SeaLevelWater(cfg))
    q.start()
    (heights, _, water), _ = await q.get_tile(tile)
    assert (heights > cfg.sea_level).all() and (water == -1).all()
    await q.stop()

    q = GpuWorkQueue(cfg, TileCache(cfg), _FakeClient(elevation_m=-500), SeaLevelWater(cfg))
    q.start()
    (heights, _, water), _ = await q.get_tile(TileId(seed=1, tile_x=9, tile_z=0, scale=2))
    assert (heights < cfg.sea_level).all() and (water == cfg.sea_level).all()
    await q.stop()


@pytest.mark.asyncio
async def test_max_wait_s_times_out_without_disturbing_the_job(tmp_path):
    """A caller that gives up after `max_wait_s` must not cancel or duplicate the
    underlying job -- that job is the (potentially minutes-long) cold hydrology solve
    the cold-start fix (Rivers and lakes plan.md section 19) exists to not repeat. A
    second, more patient caller for the same tile has to land on the same in-flight
    work and get the real result once it finishes, with only one upstream fetch."""
    cfg = _cfg(tmp_path)
    client = _GatedClient()
    q = GpuWorkQueue(cfg, TileCache(cfg), client, SeaLevelWater(cfg))
    q.start()

    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)

    with pytest.raises(TilePending):
        await q.get_tile(tile, max_wait_s=0.05)

    assert len(client.calls) == 1  # timing out did not cancel or duplicate the job

    client.release()
    (heights, _, _water), from_cache = await q.get_tile(tile)  # unbounded wait
    assert from_cache is False
    assert heights.shape == (4, 4)
    assert len(client.calls) == 1  # still only the one upstream fetch

    # And the tile is now genuinely cached -- a third call needs no wait at all.
    _, from_cache_again = await q.get_tile(tile, max_wait_s=0.0)
    assert from_cache_again is True

    await q.stop()


@pytest.mark.asyncio
async def test_second_request_after_completion_is_a_cache_hit(tmp_path):
    cfg = _cfg(tmp_path)
    cache = TileCache(cfg)
    client = _FakeClient()
    q = GpuWorkQueue(cfg, cache, client, SeaLevelWater(cfg))
    q.start()

    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    _, from_cache_1 = await q.get_tile(tile)
    _, from_cache_2 = await q.get_tile(tile)

    assert from_cache_1 is False
    assert from_cache_2 is True
    assert len(client.calls) == 1

    await q.stop()
