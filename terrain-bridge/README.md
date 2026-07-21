# Terrain Bridge

Thin FastAPI adapter between Stonebreak's Java client and upstream's
`terrain_diffusion.inference.minecraft_api` server. See `../Dev Working/plan.md` (repo root)
for the full design — this is Phase 1.

It does four things upstream doesn't: buckets requests to a fixed tile grid
(required for seam-free tiles, see plan.md §5 Phase 1 item 1), caches
finished tiles to disk with LRU eviction, serializes all GPU calls through
one queue with depth/latency logging, and converts elevation in meters to
Stonebreak block height.

## Prerequisites

- Python 3.10+ (no torch/GPU deps here — those live in the upstream server).
- A running upstream `minecraft_api.py` instance (see
  `Dev Working/terrain-diffusion-spike/` for how that was stood up locally in
  Phase 0). **Start it with `--seed` matching `TERRAIN_BRIDGE_SEED` below** —
  this bridge cannot verify that match itself; `minecraft_api.py` has no
  `GET /seed` probe to check it against (unlike the generic `api.py`).

## Setup

```bash
cd terrain-bridge
python3 -m venv venv
./venv/bin/pip install -r requirements-dev.txt
```

## Run

```bash
TERRAIN_BRIDGE_SEED=42 ./venv/bin/uvicorn bridge.main:app --port 8080
```

### Config (env vars)

| Var | Default | Meaning |
|---|---|---|
| `TERRAIN_BRIDGE_SEED` | *(required)* | Pinned for the process lifetime — see plan.md §5 Phase 1 item 4 |
| `TERRAIN_BRIDGE_UPSTREAM_URL` | `http://localhost:8000` | Where `minecraft_api.py` is listening |
| `TERRAIN_BRIDGE_SCALE` | `2` | Upstream `scale` param — 15 m/block on the 30m model, plan.md §4's recommendation |
| `TERRAIN_BRIDGE_TILE_SIZE` | `256` | Tile edge length, in blocks |
| `TERRAIN_BRIDGE_METERS_PER_BLOCK` | `15.0` | Must match `native_resolution / scale` on whatever model upstream loaded |
| `TERRAIN_BRIDGE_WORLD_HEIGHT` | `1024` | Stonebreak `WORLD_HEIGHT` |
| `TERRAIN_BRIDGE_SEA_LEVEL` | `320` | Stonebreak `SEA_LEVEL` |
| `TERRAIN_BRIDGE_NOISE_SCALE` | `1.0` | Upstream's slope-scaled detail-noise `noise` param |
| `TERRAIN_BRIDGE_CACHE_DIR` | `./tile_cache` | Disk LRU cache root |
| `TERRAIN_BRIDGE_CACHE_MAX_BYTES` | `2147483648` (2 GiB) | Cache eviction budget |
| `TERRAIN_BRIDGE_UPSTREAM_TIMEOUT_S` | `30.0` | Per-request timeout to upstream |

## Endpoints

- `POST /generate_heightmap` — body `{"world_x": int, "world_z": int, "seed"?: int}`.
  Response is binary: block-height `int16` LE (H×W) followed by biome id
  `int16` LE (H×W), with `X-Height`/`X-Width`/`X-Tile-X`/`X-Tile-Z`/
  `X-World-I1`/`X-World-J1`/`X-World-I2`/`X-World-J2`/`X-Cache-Hit` headers.
  Biome ids are upstream's vanilla-Minecraft ids, unmapped — Phase 4 rewrites
  this into Stonebreak's own biome registry.
- `GET /health` — bridge + upstream status, cache stats, queue depth.
- `POST /prefetch` — body `{"world_x": int, "world_z": int}`. Fire-and-forget
  warm; does not wait for the tile to finish.

## Tests

```bash
./venv/bin/pytest
```

Tests cover tiling math, the elevation→block-height mapping, disk cache
LRU/fingerprinting, and work-queue de-duplication/serialization — all pure
logic, no GPU or live upstream server required.
