# Models

Stonebreak's terrain models, and the shared service that connects them to the game.

| Folder | What it is | Status |
|---|---|---|
| [`DaedalusTGM-Exp/`](DaedalusTGM-Exp/) | Daedalus Terrain Generation Model (experimental): our own terrain model. Procedural controls → relief sampler → planner → synth → refiner → rivers | **Default** generator (`-Dstonebreak.terrainService.backend=slm`) |
| [`terrain-bridge/`](terrain-bridge/) | FastAPI tile adapter between the game's `DiffusionTerrainClient` and a model server: metres → blocks (`HeightCurve`), water levels, seed pinning, on-disk tile cache | Required: every model is served through it |

The stock diffusion model that DaedalusTGM-Exp replaced is a local install in
`Dev Working/terrain-diffusion-spike/`. It is not part of the repo, and is reachable only through
the developer escape hatch `-Dstonebreak.terrainService.backend=upstream`.

## How the game reaches a model

```
Stonebreak ── POST /generate_heightmap ──▶ terrain-bridge ── GET /terrain ──▶ model server
 (TerrainServiceProcessManager launches both, with one set of TERRAIN_BRIDGE_* scale settings from TerrainScale)
```

The launcher's default paths are repo-relative constants in `TerrainServiceProcessManager`
(`MODELS_DIR`, `BRIDGE_DIR`, `SLM_DIR`). Model-side code finds its neighbours through
`DaedalusTGM-Exp/terrain_slm/paths.py`.

## Conventions for a model folder

- A self-contained Python project: its own `pyproject.toml`, lock file and virtualenv.
- `docs/` holds the architecture document, plan and diagrams.
- A `.gitignore` for data, checkpoints, reports and the environment. Only code, configs, tests,
  docs and small fixtures are tracked.
- A server module that speaks the upstream `/terrain` + `/health` contract the bridge expects.
