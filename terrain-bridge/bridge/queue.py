"""Serializes all upstream `/terrain` calls behind one consumer.

Upstream's own Flask server runs single-threaded (`app.run(..., threaded=False)`
in minecraft_api.py) specifically because one GPU can't usefully serve
concurrent inference requests. Letting several chunk-worker threads hit it at
once wouldn't parallelize anything — it would just queue at the TCP socket
instead of the application, with no visibility into depth or per-tile
latency and no de-duplication of two requests racing for the same tile. This
queue gives us that visibility and de-dupes for free.
"""
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass

import numpy as np

from .cache import TileCache
from .config import BridgeConfig
from .height_mapping import elevation_to_block_height
from .tiling import TileId, tile_bounds
from .upstream_client import UpstreamClient

log = logging.getLogger("terrain_bridge.queue")


@dataclass
class _Job:
    tile: TileId
    future: asyncio.Future


class GpuWorkQueue:
    def __init__(self, cfg: BridgeConfig, cache: TileCache, client: UpstreamClient):
        self._cfg = cfg
        self._cache = cache
        self._client = client
        self._queue: asyncio.Queue[_Job] = asyncio.Queue()
        self._inflight: dict[TileId, asyncio.Future] = {}
        self._task: asyncio.Task | None = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()

    def queue_depth(self) -> int:
        return self._queue.qsize()

    async def get_tile(self, tile: TileId) -> tuple[tuple[np.ndarray, np.ndarray], bool]:
        """Returns ((block_height, biome), from_cache)."""
        loop = asyncio.get_running_loop()
        cached = await loop.run_in_executor(None, self._cache.get, tile)
        if cached is not None:
            return cached, True

        existing = self._inflight.get(tile)
        if existing is not None:
            return await existing, False

        future: asyncio.Future = loop.create_future()
        self._inflight[tile] = future
        await self._queue.put(_Job(tile, future))
        try:
            return await future, False
        finally:
            self._inflight.pop(tile, None)

    async def _run(self) -> None:
        loop = asyncio.get_running_loop()
        while True:
            job = await self._queue.get()
            start = time.monotonic()
            try:
                i1, j1, i2, j2 = tile_bounds(job.tile.tile_x, job.tile.tile_z, self._cfg.tile_size_blocks)
                elev, biome = await loop.run_in_executor(None, self._client.fetch_tile, i1, j1, i2, j2)
                block_height = elevation_to_block_height(
                    elev, self._cfg.meters_per_block, self._cfg.sea_level, self._cfg.world_height
                )
                await loop.run_in_executor(None, self._cache.put, job.tile, block_height, biome)
                if not job.future.done():
                    job.future.set_result((block_height, biome))
                log.info(
                    "tile %s generated in %.1f ms (queue depth %d)",
                    job.tile.cache_key(),
                    (time.monotonic() - start) * 1000,
                    self._queue.qsize(),
                )
            except Exception as e:  # noqa: BLE001 - surfaced to the awaiting request(s)
                log.exception("tile %s failed", job.tile.cache_key())
                if not job.future.done():
                    job.future.set_exception(e)
            finally:
                self._queue.task_done()
