# Models

Stonebreak's terrain models, and the shared service that connects them to the game.

| Folder | What it is | Status |
|---|---|---|
| [`DaedalusTGM-Exp/`](DaedalusTGM-Exp/) | Daedalus Terrain Generation Model (experimental), v3: our own terrain model. Procedural controls → relief sampler → planner → MaskGIT descriptors + hydrology sidecar → synth → refiner → river pipeline (learned banks, 3D undercuts/overhangs) | The terrain model behind the DaedalusTGM-Exp world generator |
| [`terrain-bridge/`](terrain-bridge/) | FastAPI tile adapter between the game's `DiffusionTerrainClient` and a model server: metres → blocks (`HeightCurve`), water levels, 3D river planes (tile protocol v3), seed pinning, on-disk tile cache | Required: every model is served through it |

Service logs go to `Models/logs/` (gitignored; `-Dstonebreak.terrainService.logDir` overrides).

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
