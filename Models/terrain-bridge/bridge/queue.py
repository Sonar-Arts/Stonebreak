"""Serializes all upstream `/terrain` calls behind one consumer.

The model server generates on one GPU under a lock, so it can't usefully serve
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

from .cache import TileCache, no_river_planes
from .config import BridgeConfig
from .tiling import TileId, tile_bounds
from .upstream_client import UpstreamClient
from .water import WaterSource

log = logging.getLogger("terrain_bridge.queue")


@dataclass
class _Job:
    tile: TileId
    future: asyncio.Future


class TilePending(Exception):
    """Raised by `get_tile` when `max_wait_s` elapses before a tile's job finishes.

    The job itself is untouched -- it is still queued or running, and still tracked
    in `_inflight`, exactly as if nobody had timed out on it. This only means the
    caller gave up *waiting*, so `POST /generate_heightmap` can answer without
    holding the HTTP connection open for however long a slow tile takes (a cold
    model's first tiles run ~30 s). A second call for the same tile,
    including a client's own retry, finds the same in-flight job rather than
    starting a duplicate one.
    """

    def __init__(self, tile: TileId):
        super().__init__(f"tile {tile.cache_key()} is still being generated")
        self.tile = tile


def _water_summary(report: dict) -> str:
    if not report:
        return ""
    return f", water {report.get('wet_columns', 0)} cols"


class GpuWorkQueue:
    def __init__(
        self,
        cfg: BridgeConfig,
        cache: TileCache,
        client: UpstreamClient,
        water: WaterSource,
    ):
        self._cfg = cfg
        self._cache = cache
        self._client = client
        self._water = water
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

    async def get_tile(
        self, tile: TileId, max_wait_s: float | None = None
    ) -> tuple[tuple[np.ndarray, np.ndarray, np.ndarray], bool]:
        """Returns ((block_height, biome, water_level, river floor, roof, flow), from_cache).

        `max_wait_s`, if given, bounds how long this call blocks on a tile that
        isn't cached yet -- it does not bound the job, which keeps running
        regardless of whether anyone is still waiting on it. Raises
        `TilePending` on expiry rather than cancelling the shared future: other
        callers, including a client's own retry for the same tile, may still be
        awaiting it.
        """
        loop = asyncio.get_running_loop()
        cached = await loop.run_in_executor(None, self._cache.get, tile)
        if cached is not None:
            return cached, True

        future = self._inflight.get(tile)
        if future is None:
            future = loop.create_future()
            self._inflight[tile] = future
            await self._queue.put(_Job(tile, future))

        if max_wait_s is None:
            return await future, False

        try:
            # Shielded: on timeout, asyncio.wait_for cancels whatever it's awaiting.
            # The bare future is shared by every caller waiting on this tile (plus
            # the worker loop that will eventually resolve it), so cancelling it
            # directly would break every one of them, not just this call.
            return await asyncio.wait_for(asyncio.shield(future), timeout=max_wait_s), False
        except asyncio.TimeoutError:
            raise TilePending(tile) from None

    async def _run(self) -> None:
        loop = asyncio.get_running_loop()
        while True:
            job = await self._queue.get()
            start = time.monotonic()
            try:
                bounds = tile_bounds(job.tile.tile_x, job.tile.tile_z, self._cfg.tile_size_blocks)
                elev, biome, surface, river = await loop.run_in_executor(
                    None, self._client.fetch_tile_with_water, *bounds, job.tile.lod
                )
                block_height, water_level, report = await loop.run_in_executor(
                    None, self._water.planes, bounds, elev, surface, river
                )
                river = river if river is not None else no_river_planes(block_height.shape)
                await loop.run_in_executor(
                    None, self._cache.put, job.tile, block_height, biome, water_level, river
                )
                if not job.future.done():
                    job.future.set_result((block_height, biome, water_level, *river))
                log.info(
                    "tile %s generated in %.1f ms (queue depth %d)%s",
                    job.tile.cache_key(),
                    (time.monotonic() - start) * 1000,
                    self._queue.qsize(),
                    _water_summary(report),
                )
            except Exception as e:  # noqa: BLE001 - surfaced to the awaiting request(s)
                log.exception("tile %s failed", job.tile.cache_key())
                if not job.future.done():
                    job.future.set_exception(e)
            finally:
                # Owned here, not by whichever caller happened to create the job:
                # a creator can now time out and walk away (TilePending) without the
                # job ending, so cleanup can no longer ride on that caller's own
                # control flow. Popped only once the future is truly resolved.
                self._inflight.pop(job.tile, None)
                self._queue.task_done()
