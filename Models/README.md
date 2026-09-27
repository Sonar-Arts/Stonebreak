# Models

Stonebreak's terrain models.

| Folder | What it is | Status |
|---|---|---|
| [`DaedalusTGM-Exp/`](DaedalusTGM-Exp/) | Daedalus Terrain Generation Model (experimental), v3: our own terrain model. Procedural controls → relief sampler → planner → MaskGIT descriptors + hydrology sidecar → synth → refiner → river pipeline (learned banks, 3D undercuts/overhangs) → game blocks | The terrain model behind the DaedalusTGM-Exp world generator |

Gitignored, created at runtime: `Models/logs/tgmpipe.log` (`-Dstonebreak.tgmpipe.logDir`
overrides) and `Models/tile_cache/` (finished tiles on disk).

## How the game reaches the model: TGMPipe

```
Stonebreak ══ stdin/stdout frames ══▶ python -m terrain_slm.tgmpipe  (one child process)
  TGMPipe (launch, handshake, restart)             model on the GPU → blocks → disk cache
  DiffusionTileCache (per seed / lod, priority)    any seed per request, priority queue, cancel
```

- **One process, no network.** `TGMPipe` (stonebreak-game,
  `world/generation/diffusion/tgmpipe/`) launches the service as a child and speaks binary frames
  over its pipes (`TGMPipeProtocol` ⇄ `terrain_slm/tgmpipe/protocol.py`). Nothing listens on a port.
- **The game owns the world's scale.** The handshake carries the world config (height, sea level,
  height curve, tile size) from `TerrainScale`; the service decides blocks with exactly those
  numbers and rejects a config it does not recognise.
- **Any seed, per request.** The model is loaded once and its caches are keyed by seed, so the
  world and the terrain mapper share it and a seed change costs nothing.
- **Pushed, prioritised, cancellable.** Tiles are sent the moment they finish; world tiles go
  before preview tiles; closing a tile cache withdraws its unanswered requests.
- **Never outlives the game.** The service exits when its stdin closes, including when the JVM is
  killed. If it dies while the game runs, it is restarted and in-flight tiles are re-sent.
- **The disk cache invalidates itself.** Its namespace hashes the world config, the checkpoint
  steps and the generator's own source, so retraining or editing the pipeline never serves stale
  tiles.

The launcher finds `Models/` from the repo root or any module directory; paths and knobs are
`-Dstonebreak.tgmpipe.*` properties (see `TGMPipe`).

## Conventions for a model folder

- A self-contained Python project: its own `pyproject.toml`, lock file and virtualenv.
- `docs/` holds the architecture document, plan and diagrams.
- A `.gitignore` for data, checkpoints, reports and the environment. Only code, configs, tests,
  docs and small fixtures are tracked.
- A TGMPipe module (`python -m <package>.tgmpipe`) that speaks the TGMPipe protocol over stdio.
