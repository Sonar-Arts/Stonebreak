"""FastAPI adapter in front of the DaedalusTGM-Exp model server (`/terrain`).

Contract for the Java client (plan.md section 5, Phase 1):
  POST /generate_heightmap  {world_x, world_z, seed?} -> binary tile + headers
  GET  /health               -> model/queue/cache status
  POST /prefetch             {world_x, world_z}        -> fire-and-forget warm

The tile body is bare concatenated int16-LE planes with no header bytes of its
own, so a consumer that expects a different plane count mis-slices it into
plausible garbage terrain rather than failing. `X-Protocol-Version` is the only
thing that makes that loud; `DiffusionTerrainClient.parseTile` requires it.
"""
from __future__ import annotations

import asyncio
import logging

from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel

from . import water as water_module
from .cache import PLANES, TileCache
from .config import BridgeConfig
from .queue import GpuWorkQueue, TilePending
from .tiling import TileId, tile_bounds, tile_containing
from .upstream_client import UpstreamClient, UpstreamError

# Bumped whenever the tile body's plane count or layout changes. 1 was
# (block height, biome); 2 adds the per-column water level.
PROTOCOL_VERSION = 3

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("terrain_bridge")

cfg = BridgeConfig.from_env()
water = water_module.build(cfg)
cache = TileCache(cfg, water.fingerprint)
client = UpstreamClient(cfg)
work_queue = GpuWorkQueue(cfg, cache, client, water)

app = FastAPI(title="Stonebreak Terrain Bridge")


class TileCoordRequest(BaseModel):
    """`lod` > 1 asks for a far-zoom preview tile: world_x/world_z are then SAMPLE coordinates
    (world blocks // lod) and each sample stands for lod x lod blocks. Needs
    TERRAIN_BRIDGE_DOWNSCALE > 1; the terrain mapper uses it when zoomed out."""
    world_x: int
    world_z: int
    seed: int | None = None
    lod: int = 1


def _tile_for(req: TileCoordRequest) -> TileId:
    if req.lod != 1 and (req.lod < 1 or req.lod & (req.lod - 1) or cfg.downscale == 1):
        raise HTTPException(status_code=400, detail=f"lod {req.lod} unsupported: a power of two, "
                            "and only with TERRAIN_BRIDGE_DOWNSCALE > 1")
    tile_x, tile_z = tile_containing(req.world_x, req.world_z, cfg.tile_size_blocks)
    return TileId(seed=cfg.seed, tile_x=tile_x, tile_z=tile_z, scale=cfg.scale, lod=req.lod)


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
        "downscale": cfg.downscale,
        "protocol_version": PROTOCOL_VERSION,
        # Horizontal only. The vertical mapping stopped being one number at Phase 5 —
        # it is an integrated rate curve, ~4 m/block in lowlands and ~24 in highlands —
        # so the old `meters_per_block` field described nothing and is gone rather than
        # left reporting 15.0 (plan section 10.7).
        "horizontal_meters_per_block": cfg.horizontal_meters_per_block,
        "cache": cache.stats(),
        "cache_namespace": cache.root.name,
        "queue_depth": work_queue.queue_depth(),
    }


@app.post("/generate_heightmap")
async def generate_heightmap(req: TileCoordRequest):
    _require_matching_seed(req.seed)
    tile = _tile_for(req)

    try:
        planes, from_cache = await work_queue.get_tile(tile, max_wait_s=cfg.max_wait_s)
    except TilePending:
        # A slow tile (a cold model's first ones run ~30 s) must not hold the HTTP
        # request open indefinitely. The job keeps
        # running on the queue regardless of this request giving up on it; the
        # client is expected to poll again after Retry-After and land on the same
        # in-flight job rather than start a second one.
        resp = Response(
            content=b"tile is still being generated, retry shortly",
            media_type="text/plain",
            status_code=503,
        )
        resp.headers["Retry-After"] = str(cfg.solve_retry_after_s)
        return resp
    except UpstreamError as e:
        raise HTTPException(status_code=502, detail=str(e)) from e

    h, w = planes[0].shape
    payload = b"".join(plane.astype("<i2").tobytes() for plane in planes)
    i1, j1, i2, j2 = tile_bounds(tile.tile_x, tile.tile_z, cfg.tile_size_blocks)

    resp = Response(content=payload, media_type="application/octet-stream")
    resp.headers["X-Protocol-Version"] = str(PROTOCOL_VERSION)
    resp.headers["X-Planes"] = str(PLANES)
    resp.headers["X-Height"] = str(h)
    resp.headers["X-Width"] = str(w)
    resp.headers["X-Dtype"] = "int16-le"
    resp.headers["X-Tile-X"] = str(tile.tile_x)
    resp.headers["X-Tile-Z"] = str(tile.tile_z)
    resp.headers["X-World-I1"] = str(i1)
    resp.headers["X-World-J1"] = str(j1)
    resp.headers["X-World-I2"] = str(i2)
    resp.headers["X-World-J2"] = str(j2)
    resp.headers["X-Sea-Level"] = str(cfg.sea_level)
    resp.headers["X-Cache-Hit"] = "1" if from_cache else "0"
    resp.headers["X-Lod"] = str(tile.lod)
    return resp


@app.post("/prefetch")
async def prefetch(req: TileCoordRequest):
    _require_matching_seed(req.seed)
    tile = _tile_for(req)
    asyncio.create_task(work_queue.get_tile(tile))
    return {"queued": True, "tile_x": tile.tile_x, "tile_z": tile.tile_z}
