# Terrain Bridge

Thin FastAPI adapter between Stonebreak's Java client (`DiffusionTerrainClient`) and the
DaedalusTGM-Exp model server (`terrain_slm.serve.upstream_api`, called "upstream" here).

It does what the model server doesn't: buckets requests to a fixed tile grid (seam-free
tiles), maps elevation in metres to Stonebreak block heights through one height curve
(`HeightCurve`, also imported by the model), maps the model's water-surface plane to
per-column water levels, passes the 3D river planes through (tile protocol v3), caches
finished tiles to disk with LRU eviction, and serializes all GPU calls through one queue
with depth/latency logging.

Normally you never start it by hand: `TerrainServiceProcessManager` launches the model
server and this bridge together, with the env vars below, and logs both to `Models/logs/`.

`hydrology/` (priority-flood fill + D8 flow) is not used by the running bridge. It stays
here because DaedalusTGM-Exp's training-data build imports it.

## Setup

```bash
cd Models/terrain-bridge
python3 -m venv venv
./venv/bin/pip install -r requirements-dev.txt
```

## Run by hand

Start the model server with `--seed` matching `TERRAIN_BRIDGE_SEED` (the bridge cannot
verify that match), then:

```bash
TERRAIN_BRIDGE_SEED=42 TERRAIN_BRIDGE_UPSTREAM_URL=http://localhost:8010 \
  ./venv/bin/uvicorn bridge.main:app --port 8180
```

and add the scale settings from `TerrainScale.serviceEnvironment()` (world height, sea level,
curve rates, `SCALE=1`, `DOWNSCALE=2`, 60 m horizontal) or the tiles will not match the game.

### Config (env vars)

| Var | Default | Meaning |
|---|---|---|
| `TERRAIN_BRIDGE_SEED` | *(required)* | Pinned for the process lifetime |
| `TERRAIN_BRIDGE_UPSTREAM_URL` | `http://localhost:8000` | Where the model server is listening (the game uses 8010) |
| `TERRAIN_BRIDGE_UPSTREAM_ID` | *(empty)* | Model identity (`slm:<model dir>`), part of the tile-cache namespace — a generator change needs a new model dir or old tiles are served |
| `TERRAIN_BRIDGE_SCALE` / `TERRAIN_BRIDGE_DOWNSCALE` | `2` / `1` | Blocks per native pixel / native pixels averaged per block (the game: 1 / 2) |
| `TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK`, `TERRAIN_BRIDGE_METERS_PER_BLOCK` | `15.0` | Horizontal block size (the game: 60) |
| `TERRAIN_BRIDGE_{OCEAN,LOWLAND,MIDLAND,HIGHLAND}_METERS_PER_BLOCK` | `12 / 4 / 10 / 24` | Height-curve rates (the game: 48 / 16 / 24 / 38) |
| `TERRAIN_BRIDGE_WORLD_HEIGHT` / `TERRAIN_BRIDGE_SEA_LEVEL` | `1024` / `320` | Stonebreak `WORLD_HEIGHT` / `SEA_LEVEL` (the game: 256 / 64) |
| `TERRAIN_BRIDGE_RIVER3D` | `1` | Request and pass through the river tunnel floor/roof/flow planes |
| `TERRAIN_BRIDGE_TILE_SIZE` | `256` | Tile edge length, in blocks |
| `TERRAIN_BRIDGE_NOISE_SCALE` | `1.0` | Model `noise` param |
| `TERRAIN_BRIDGE_CACHE_DIR` | `./tile_cache` | Disk LRU cache root |
| `TERRAIN_BRIDGE_CACHE_MAX_BYTES` | `2147483648` (2 GiB) | Cache eviction budget |
| `TERRAIN_BRIDGE_UPSTREAM_TIMEOUT_S` | `30.0` | Per-request timeout to the model server |
| `TERRAIN_BRIDGE_MAX_WAIT_S` / `TERRAIN_BRIDGE_SOLVE_RETRY_AFTER_S` | `20` / `5` | How long one request waits on an unfinished tile before answering 503 + `Retry-After` |

## Endpoints

- `POST /generate_heightmap` — body `{"world_x": int, "world_z": int, "seed"?: int, "lod"?: int}`.
  Response is six bare `int16` LE planes (H×W each): block height, biome id, water level,
  river tunnel floor, roof, flow octant — with `X-Protocol-Version`/`X-Height`/`X-Width`/
  `X-Tile-X`/`X-Tile-Z`/`X-World-I1..J2`/`X-Cache-Hit` headers. `lod` > 1 is the terrain
  mapper's far-zoom overview (sample coordinates, needs `DOWNSCALE` > 1).
- `GET /health` — bridge + model status, cache stats, queue depth, pinned seed.
- `POST /prefetch` — body `{"world_x": int, "world_z": int}`. Fire-and-forget warm.

## Tests

```bash
./venv/bin/pytest
```

Pure logic, no GPU or live model server: tiling math, the height curve, the water mapping,
disk cache LRU/fingerprinting, work-queue de-duplication/serialization, and the fill/flow
primitives the model's data build uses.
