"""FastAPI adapter in front of upstream's terrain_diffusion minecraft_api server.

Contract for the Java client (plan.md section 5, Phase 1):
  POST /generate_heightmap  {world_x, world_z, seed?} -> binary tile + headers
  GET  /health               -> model/queue/cache status
  POST /prefetch             {world_x, world_z}        -> fire-and-forget warm
"""
from __future__ import annotations

import asyncio
import logging

from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel

from .cache import TileCache
from .config import BridgeConfig
from .queue import GpuWorkQueue
from .tiling import TileId, tile_bounds, tile_containing
from .upstream_client import UpstreamClient, UpstreamError

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("terrain_bridge")

cfg = BridgeConfig.from_env()
cache = TileCache(cfg)
client = UpstreamClient(cfg)
work_queue = GpuWorkQueue(cfg, cache, client)

app = FastAPI(title="Stonebreak Terrain Bridge")


class TileCoordRequest(BaseModel):
    world_x: int
    world_z: int
    seed: int | None = None


def _require_matching_seed(seed: int | None) -> None:
    if seed is not None and seed != cfg.seed:
        raise HTTPException(
            status_code=400,
            detail=(
                f"this bridge instance is pinned to seed {cfg.seed}; got {seed}. "
                "Start a separate bridge+upstream pair for a different seed "
                "instead of switching seeds on a live server (plan.md Phase 1 item 4)."
            ),
        )


@app.on_event("startup")
async def _startup() -> None:
    work_queue.start()


@app.on_event("shutdown")
async def _shutdown() -> None:
    await work_queue.stop()


@app.get("/health")
def health():
    try:
        upstream = client.health()
        upstream_ok = True
    except Exception as e:  # noqa: BLE001 - reported, not raised; /health must not 500
        upstream = {"error": str(e)}
        upstream_ok = False
    return {
        "status": "ok" if upstream_ok else "degraded",
        "upstream": upstream,
        "seed": cfg.seed,
        "scale": cfg.scale,
        "meters_per_block": cfg.meters_per_block,
        "cache": cache.stats(),
        "queue_depth": work_queue.queue_depth(),
    }


@app.post("/generate_heightmap")
async def generate_heightmap(req: TileCoordRequest):
    _require_matching_seed(req.seed)
    tile_x, tile_z = tile_containing(req.world_x, req.world_z, cfg.tile_size_blocks)
    tile = TileId(seed=cfg.seed, tile_x=tile_x, tile_z=tile_z, scale=cfg.scale)

    try:
        (block_height, biome), from_cache = await work_queue.get_tile(tile)
    except UpstreamError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e

    h, w = block_height.shape
    payload = block_height.astype("<i2").tobytes() + biome.astype("<i2").tobytes()
    i1, j1, i2, j2 = tile_bounds(tile.tile_x, tile.tile_z, cfg.tile_size_blocks)

    resp = Response(content=payload, media_type="application/octet-stream")
    resp.headers["X-Height"] = str(h)
    resp.headers["X-Width"] = str(w)
    resp.headers["X-Dtype"] = "int16-le"
    resp.headers["X-Tile-X"] = str(tile.tile_x)
    resp.headers["X-Tile-Z"] = str(tile.tile_z)
    resp.headers["X-World-I1"] = str(i1)
    resp.headers["X-World-J1"] = str(j1)
    resp.headers["X-World-I2"] = str(i2)
    resp.headers["X-World-J2"] = str(j2)
    resp.headers["X-Meters-Per-Block"] = str(cfg.meters_per_block)
    resp.headers["X-Sea-Level"] = str(cfg.sea_level)
    resp.headers["X-Cache-Hit"] = "1" if from_cache else "0"
    return resp


@app.post("/prefetch")
async def prefetch(req: TileCoordRequest):
    _require_matching_seed(req.seed)
    tile_x, tile_z = tile_containing(req.world_x, req.world_z, cfg.tile_size_blocks)
    tile = TileId(seed=cfg.seed, tile_x=tile_x, tile_z=tile_z, scale=cfg.scale)
    asyncio.create_task(work_queue.get_tile(tile))
    return {"queued": True, "tile_x": tile.tile_x, "tile_z": tile.tile_z}
