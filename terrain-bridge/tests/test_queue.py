import asyncio

import numpy as np
import pytest

from bridge.cache import TileCache
from bridge.config import BridgeConfig
from bridge.queue import GpuWorkQueue
from bridge.tiling import TileId


class _FakeClient:
    """Stands in for UpstreamClient: records call order/concurrency instead
    of hitting a real GPU server."""

    def __init__(self):
        self.calls: list[tuple[int, int, int, int]] = []
        self.concurrent = 0
        self.max_concurrent = 0

    def fetch_tile(self, i1, j1, i2, j2):
        self.concurrent += 1
        self.max_concurrent = max(self.max_concurrent, self.concurrent)
        self.calls.append((i1, j1, i2, j2))
        try:
            import time

            time.sleep(0.02)
            h = w = i2 - i1
            elev = np.full((h, w), 30, dtype=np.int16)
            biome = np.full((h, w), 1, dtype=np.int16)
            return elev, biome
        finally:
            self.concurrent -= 1


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
    q = GpuWorkQueue(cfg, cache, client)
    q.start()

    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    results = await asyncio.gather(*[q.get_tile(tile) for _ in range(5)])

    assert len(client.calls) == 1  # only one upstream fetch for 5 concurrent requests
    assert client.max_concurrent == 1  # never called concurrently
    for (block_height, biome), _from_cache in results:
        assert block_height.shape == (4, 4)

    await q.stop()


@pytest.mark.asyncio
async def test_second_request_after_completion_is_a_cache_hit(tmp_path):
    cfg = _cfg(tmp_path)
    cache = TileCache(cfg)
    client = _FakeClient()
    q = GpuWorkQueue(cfg, cache, client)
    q.start()

    tile = TileId(seed=1, tile_x=0, tile_z=0, scale=2)
    _, from_cache_1 = await q.get_tile(tile)
    _, from_cache_2 = await q.get_tile(tile)

    assert from_cache_1 is False
    assert from_cache_2 is True
    assert len(client.calls) == 1

    await q.stop()
