# DaedalusTGM-Exp: Terrain Generation Model Architecture

**Branch:** `Project-Daedalus` (off Project-Heracles) · **As of:** 2026-09-27 (v2: mountain ranges + relief sampler)
**Name:** **DaedalusTGM-Exp** (Daedalus Terrain Generation Model, experimental): the official name of the model and its server. Code identifiers keep their original names: Python package `terrain_slm`, backend key `-Dstonebreak.terrainService.backend=slm`. The name appears in `terrain_slm.MODEL_NAME`, the server's `/health` (`"name"`), its `model_id` (`DaedalusTGM-Exp/v2:p…:r…:l…`), its log prefix, and `TerrainServiceProcessManager.SLM_MODEL_NAME` (Java logs)
**Home:** `Models/DaedalusTGM-Exp/` (this model: code, training, server, checkpoints, reports, docs) · `Models/terrain-bridge/` (shared tile adapter) · Java process manager + world constants
**Plan / history:** [`Terrain-SLM-Plan.md`](Terrain-SLM-Plan.md) (§13 onward is the running log)

---

## 1. Status at a glance

| | |
|---|---|
| Default generator | **DaedalusTGM-Exp v2** (`TerrainServiceProcessManager.DEFAULT_BACKEND = "slm"`, model dir `checkpoints/v2`); the diffusion upstream is reachable only via `-Dstonebreak.terrainService.backend=upstream` |
| Controls | **v2**: continents + long, winding **mountain ranges** (spines, spurs, foothills) + climate archetypes (§6) |
| Relief sampler | **new**: flow-matching conv UNet on 240 m cells, **425,033 params**, 11 min; samples valley networks onto the smooth trend (§7) |
| Planner (the "SLM") | **R1**: ViT d176 × 4 layers × 4 heads, **1,558,656 params** (unchanged; now fed a valley-rich trend) |
| Refiner | conditional flow-matching conv UNet, **336,785 params** (unchanged) |
| Training data | 7 Copernicus GLO-30 regions, 134 one-degree tiles (§4) |
| World | **256 tall, sea level y 64**, 60 m blocks, **vertically exaggerated** height curve: 2 km → y 170, 3 km → y 197, 4 km → y 223 (§3) |
| Water | rivers from the model; no lakes on slm worlds; river threshold 4.5 with relief |
| Mountains (seed 2, 61 km square) | seeds 0–3: peaks y 230–232 (highest real-Alps window: 237), 144–164 blocks of relief (real: 154), p99 block step 2 (real: 3); report `reports/v2/` |

---

## 2. System overview

![System overview](system-overview.png)

(`system-overview.py` regenerates it; from `docs/`: `../.venv/bin/python system-overview.py`.)

**Tile contract (unchanged for the game):** 256×256 int16 block heights + int16 biome ids + int16 water levels (`-1` = dry).

Per request, the model server runs:

1. **Procedural controls** (per 240 m cell): trend, wildness, climate.
2. **Relief sampler**: adds valley networks to the smooth trend. Canonical 448-cell windows, cross-faded.
3. **Planner**: turns the valley-rich trend into coarse height, 8 descriptors, river logit and log flow.
4. **Synth**: descriptor-driven noise at 30 m pixels.
5. **Refiner**: SDEdit at t = 0.6.
6. **Summit soft cap**, then the **river pass**.
7. **2×2 downscale to blocks**, with water settled at block resolution.

---

## 3. Scale: tall mountains in a 256-block world

### What Minecraft does
Minecraft (1.18+) uses 1 m blocks, sea level y 63 and a build limit of y 320. Its mountain peaks usually top out around y 200–260, which is 150–200 m above the sea. A range spans a few hundred to a couple of thousand blocks. Measured against real terrain, that is roughly 15–20 m of reality per block on both axes, with the vertical exaggerated a further ~2–3× (jagged peaks climb 2–3 blocks per block).

### What we do
Blocks are 60 m horizontally: the model's 30 m pixels are averaged 2×2 (`TerrainScale.DOWNSCALE`). A 100 km range is therefore ~1,700 blocks long, similar to a large Minecraft range. Height is set by the bridge's `HeightCurve`, using per-band metres-per-block rates (`TerrainScale.CURVE_RATES`):

| Band | Rate (m/block) | Vertical exaggeration vs 60 m horizontal |
|---|---|---|
| ocean | 48 | 1.25× |
| lowland (0–600 m) | 16 | 3.75× (keeps river incision legible) |
| midland (600–2000 m) | 24 | 2.5× |
| highland (> 2000 m) | 38 | 1.6× |

| Elevation | 0 | 600 | 1000 | 2000 | 3000 | 4000 | 4500 | 5000 |
|---|---|---|---|---|---|---|---|---|
| Block y | 64 | 100 | 124 | 170 | 197 | 223 | 236 | 250 |

The old curve (48/16/40/96, a straight 1:4 of the diffusion-era rates) put 4 km at y 174 and turned a real 35° slope into 0.44 blocks of rise per block. Under that curve, even real Alps data looked like rolling hills.

**Summit soft cap** (`generator.soft_cap_peaks`): above 4300 m, elevation approaches 5150 m (y ~254) but never reaches it. The rare summit taller than the curve allows is rounded rather than sheared flat at the build limit. It is applied in the model server, so the bridge, water and biomes all see the same ground.

`TerrainScale.serviceEnvironment()` emits one set of `TERRAIN_BRIDGE_*` values (world height, sea level, curve rates, scale/downscale, horizontal m/block) to **both** the bridge and the model server, so they map metres to blocks identically. The curve rates are part of the bridge's tile-cache fingerprint.

---

## 4. Data (`terrain_slm/data/`)

| Region | Tiles | Character | t0 °C (sea-level) | Precip mm | River cells |
|---|---|---|---|---|---|
| alps | 40 | temperate high mountains, Po plain, coast | 11.6–16.8 | 659–1570 | 2.97% |
| colorado_plateau | 20 | arid canyons, mesas | 20.6–23.7 | 180–421 | 1.68% |
| sahara_erg | 12 | sand sea, dunes | 24.0–25.8 | 26–52 | 0.91% |
| borneo | 16 | tropical rainforest hills (canopy in the DSM) | 26.9–29.4 | 2660–4083 | 5.48% |
| great_plains | 15 | rolling plains, Sandhills, braided rivers | 11.5–15.3 | 511–784 | 2.77% |
| norway | 15 | boreal/alpine, fjords, glacial valleys | 5.1–8.7 | 636–3060 | 2.37% |
| east_africa | 16 | savanna, rift escarpments, volcanoes | 28.1–30.9 | 529–1098 | 3.09% |

Each region holds out one validation tile, spatially separated from its training tiles (`Region.val_tiles`). The build (`python -m terrain_slm.data.build --region <name>`) writes:
- a ~30 m square-pixel mosaic (`dem.npy`);
- per-cell fields (8×8 px) in `cells.npz`: `coarse` (cell-mean elevation), `desc` (§5), WorldClim `t0`/`tseason`/`precip`/`pcv`, precipitation-weighted D8 `acc` and `d8`.

---

## 5. Descriptor contract (`data/descriptors.py`)

| Channel | Meaning |
|---|---|
| `log_amp0..4` | log(local RMS + 0.01) of band *o*, wavelengths 2^(o+1)–2^(o+2) px (2–64 px); sharp FFT bands with raised-cosine edges |
| `ridge` | skewness of the mid band (bands 1–3): + sharp crests, − sharp valleys |
| `aniso_c`, `aniso_s` | structure-tensor coherence × cos/sin 2θ |

The same statistics are measured on real DEMs (as targets) and on synth output (in the round-trip gate). Wavelengths above 64 px belong to the coarse height.

---

## 6. Controls v2 (`world/generator.procedural_controls`)

These are stand-ins for painted maps. Every field is pointwise in cell coordinates, so any window of it is exact.

| Field | Construction |
|---|---|
| continents | 900-cell fBm + a "home continent" bump at the origin (spawn on land), smoothstep coast |
| **range field** | 2-octave fBm at `RANGE_PERIOD_CELLS` 560, domain-warped by 60 cells, normalised to unit std. Ranges run along its **zero lines**: long, winding chains ~70 km apart |
| spine / foothills | Gaussian profiles of the range field, widths 0.5 and 1.2 std |
| spurs | same construction on a 130-cell field, only inside the range body (Gaussian width 0.8 std). Out in the foothills a thin spur reads as an artificial winding wall |
| along-range height | 900-cell fBm → smoothstep (`RANGE_ALONG_BIAS` 0.2): chains rise into massifs and dip into hills over hundreds of km |
| trend | sea-level base + `along × (2800·spine + 900·spur + 500·foot)` + hills |
| wildness | roughness in [0, 1] from the same fields → band-3 log amplitude 2–40 m |
| climate | sharp softmax blend of 7 region archetypes (unchanged) |

Coverage over a 490 km square, seeds 0–3: peaks 4.3–4.4 km; 2.6–16% of land above 2000 m, 1–8% above 3000 m. The old controls peaked at ~1.3 km, because their `mountainous` mask never went above 0.54.

---

## 7. Relief sampler (`models/relief.py`, `train/train_relief.py`) — new in v2

**Why it exists.** The planner reproduces valleys that are already present in its trend input, but it cannot invent them. Measured on validation tiles, valley-scale relief (wavelengths below ~6 km) is 93 m in the real data. The planner's output depends on how smooth its input trend is:

| Trend given to the planner | Valley-scale relief out |
|---|---|
| lightly blurred (σ 3 cells) | 79 m |
| σ 8 cells | 23 m |
| σ 16 cells | 10 m |

Procedural and painted trends are smooth, so mountains came out as rough plateaus. A regression model predicts the mean of the valley layout, which is flat. A **sampler** is needed.

| | |
|---|---|
| Model | conv UNet, 4 levels (16/24/32/48 ch, 3 downsamples, receptive field ~100 cells ≈ 24 km), 2 ResBlocks per level, FiLM time conditioning, zero-init output, no norm layers, **425,033 params** |
| Target | `(blur(coarse, 2) − blur(coarse, σ)) / 250 m`, σ ~ U(8, 20) cells: the relief between what the planner expects as a trend (σ ≈ 2) and a painted-smooth one |
| Conditioning (10 ch/cell) | smooth trend (+ 12-cell-blurred jitter up to 60 m in training) and its slope (2), wildness, 4 climate channels, presence flags for wildness and climate (dropout 0.15 each) |
| Training | 144-cell crops + 40-cell margin, all 7 regions sampled uniformly, 8 dihedral transforms (planner data pipeline); flow matching, logit-normal t, EMA 0.999, AdamW 3e-4, batch 32, 30k steps, 11 min on one GPU |
| Validation | 320 held-out crops (stride 32 inside the val tiles); **amplitude ratio** std(sample)/std(real) = **0.957** (0.74 at 6k steps) (the averaging symptom is a ratio well below 1) |
| Sampling | Euler, 16 steps from coordinate-hashed white noise (stream 8191), t 0 → 1 |

**In the generator:**
- **Input trend:** the procedural trend is pre-blurred σ 4 cells, which puts it in the training distribution.
- **Windows:** canonical 448-cell windows (256-cell cores + 96-cell aprons) are cross-faded with sin² ramps. Neighbouring windows agree in their overlap at correlation **0.995–0.9997**, so the fade doesn't average relief away.
- **Oceans:** the residual is gated off below sea level (`smoothstep((trend+100)/300)`); the training regions have little deep sea.
- **No inland seas:** inland ground is floored at 30 m.
- **Fallback:** without `relief.pt` in the model directory, the generator runs the old smooth-trend pipeline.

---

## 8. Planner (`models/planner.py`)

| | |
|---|---|
| Architecture | ViT over 4×4-cell patches, axial 2D RoPE, pre-LN blocks with zero-initialised residual projections |
| Inputs (11 ch/cell) | trend (now the **relief-sampled** trend), t0, tseason, precip, pcv, river mask, wildness, 4 presence flags |
| Outputs (11 ch/cell) | coarse height, 8 descriptors, river logit, log flow accumulation |
| Training | random 64×64-cell crops, regions sampled uniformly, 8 dihedral transforms; trend degraded (blur σ 1.5–5, quantise, jitter): exactly the kind of valley-rich trend the relief sampler now provides |

| Planner | Params | Val height MAE | River IoU | Val total |
|---|---|---|---|---|
| R0 (d128×2), 7 regions | 0.44M | 38.6 m | 0.22 | 1.395 |
| **R1 (d176×4), 7 regions** | 1.56M | **34.5 m** | **0.30** | **1.307** |

## 9. Synth (`synth/noise.py`) and refiner (`models/refiner.py`)

These are unchanged in v2.
- **Synth:** hash noise, fixed band kernels, analytic amplitudes/anisotropy/ridge, 16-cell margins, so the output is region-exact.
- **Refiner:** 16→32→48 conv UNet, flow matching on the residual over the coarse surface divided by a descriptor-derived scale field, sampled as SDEdit from the synth at t 0.6 with 8 Euler steps.

## 10. World generator (`world/generator.py`)

| Stage | Tiling | Cache |
|---|---|---|
| relief windows | 448-cell windows, stride 256, sin² fade over 192 cells | LRU 64 |
| planner windows | 64 cells, stride 32, sin² (exact partition of unity), 1-cell blur hides the patch grid | LRU 4096 |
| pixel regions | 256 px cores + 64 px apron, cross-faded over 128 px | LRU from `--cache-size` |

`terrain(i1,j1,i2,j2)` runs native heights → summit soft cap → river pass, and returns carved elevation plus water surface. `model_id` = `<dir>:p<planner step>:r<refiner step>:l<relief step>`.

## 11. Rivers (`world/rivers.py`)

The river pass is local and bounded (40 px halo), so it doesn't depend on which request asked for the tile:
1. Ridge lines of the upsampled, smoothed planner log flow, via Hessian non-maximum suppression.
2. Flow-dependent channel width (1–4 px) and depth (1.5–6 m).
3. The water surface follows the channel ground.
4. Levees.
5. A fixed-point no-spill rule.

**Threshold:** 4.5 when a relief model is loaded (~2% river cells, tree-like networks), 3.3 without one (`RIVER_THRESHOLD_RELIEF` / `_SMOOTH`; `TERRAIN_SLM_RIVER_THRESHOLD` overrides).
- On a relief-sampled trend, the planner's drainage is realistic, and 3.3 drew a looping mesh over 5.8% of the ground.
- On smooth trends it under-predicts drainage, which is why the older default was 3.3.

## 12. Serving (`serve/upstream_api.py`) and bridge (`Models/terrain-bridge/`)

**Server:** a drop-in for upstream's `minecraft_api` (same CLI flags, `/health`, `/terrain?i1&j1&i2&j2&scale&downscale&noise&elev_only&water&format`).
- With `downscale=2`: 2×2 area mean, riverbed = channel floor, water settled at block resolution against the bridge's own `HeightCurve`.
- Biomes come from upstream's classifier, vendored verbatim.
- Knobs (env): `TERRAIN_SLM_T_START` 0.6, `TERRAIN_SLM_STEPS` 8, `TERRAIN_SLM_AMP_SCALE` 1.0, `TERRAIN_SLM_RIVER_THRESHOLD` (model default), `TERRAIN_SLM_DEVICE`.

**Bridge:**
- `TERRAIN_BRIDGE_WATER_SOURCE=upstream` takes the model's water surface.
- `TERRAIN_BRIDGE_UPSTREAM_ID` (= `slm:<model dir>`) and the curve rates are part of the tile-cache fingerprint.
- **Any generator change (controls, models) needs a new model dir name**, or the bridge will serve old cached tiles.

## 13. Game integration (Java)

| Where | What |
|---|---|
| `TerrainServiceProcessManager` | `DEFAULT_BACKEND="slm"`, slm model dir **`checkpoints/v2`** |
| `TerrainScale` | 60 m blocks (`DOWNSCALE` 2), `CURVE_RATES` {48, 16, 24, 38} |
| `TerrainMapperConfig.TOPO_LAND_CEILING` | 240 (the topography map's white level) |
| `WorldConfiguration` | 256 / 64; `HeightMapGenerator` clamps surfaces to y 255 |
| `NativeWaterTiles.nativeBackendSelected()` | false on slm (no basin solver / coarse DEM) |
| Engine | `VoxelChunkCodec.CHUNK_H = 256`, `CloudRenderer.CLOUD_Y = 192` (high peaks now rise through the cloud layer) |

## 14. Verification

- **Model tests (`tests/`): 37.** New in v2 (`test_relief.py`):
  - ranges reach > 3000 m and cover a sane share of the land;
  - controls are pointwise;
  - the soft cap is monotone and bounded;
  - the UNet accepts any size that is a multiple of 8;
  - relief trend is request-independent across window seams.
  - The existing river tests (request independence, no spill) now run with the relief model in the loop.
- **Java:** `TerrainServiceProcessManagerTest`, `TopographyVisualizerTest`, `WaterVisualizerTest`: 24 pass.
- **Mountain report:** `python -m terrain_slm.eval.mountains --seed 2 --out reports/v2`. It prints block stats and band RMS against the highest real-Alps window, plus seam agreement, and writes `mountains_seed<N>.png` (generated with rivers | real).

## 15. Known limitations and next steps

1. **Valleys are still shallower than real.** At 1–4 km wavelengths, generated band RMS is 0.3–0.8 of the most alpine real-Alps window. This depends on the seed and is pessimistic, since generated windows that include lowland score lower. Block steps of 3+ cover 0.1–0.7% of generated ground vs 5.8% in the real window. The high ground also carries the synth's isotropic "orange peel" rather than 1–4 km dendritic valleys; lowering the refiner's t_start made it smoother, not sharper. Next rungs:
   - a bigger relief sampler (R1);
   - conditioning the sampler on a terrain type, so fjords, canyons and rift escarpments get their own styles;
   - longer training.
2. **Planner descriptors still regress to the mean** (dunes, isolated volcanoes averaged; plains too rough). Planned fix: tokenised descriptors with seeded MaskGIT sampling. This targets texture, not valleys; the relief sampler handled the valleys.
3. **Rivers come from the planner's learned flow, not the sampled heights.** They mostly follow valleys at threshold 4.5, but nothing enforces it. A true drainage solve on the sampled trend is non-local and would break request independence; it would need a hierarchical design.
4. Terrain shape doesn't follow climate zones yet; a desert zone gets generic relief.
5. **Lowlands are bland, and some wet flat lowlands grow a looping river mesh** (seed 2, NE of spawn, `reports/v2/wide_seed2.png`).
   - Lowland hills are smooth 70-cell domes: wildness is low there, so the sampler adds little.
   - Flat, wet ground gives the planner a blobby flow field, and the ridge-line pass draws loops in it.
6. No lakes on slm worlds. No flow-direction octants for the water simulation.
7. Old worlds: chunks generated under v1 won't match v2 tiles at their borders; start a new world.
8. Deferred: CUDA native inference (M6), distillation (M7), CPU path.

## 16. Mountain diagnosis that led to v2 (2026-09-27)

Scripts: `docs/diagnostics/diag1..7.py` (run from `Models/DaedalusTGM-Exp/`; output goes to `reports/diagnostics/`). Image: `mountain-diagnosis.png`.

| Stage | Finding |
|---|---|
| Procedural controls | The v1 trend never asked for mountains: `mountainous` ≤ 0.54, so trend ≤ ~1.3 km (0% of land > 1500 m on seed 0) |
| Planner | Follows tall trends (a 3200 m trend peak comes out at 3165 m) but copies valleys rather than inventing them (§7), so v1 mountains became plateaus |
| Averaging, by band | v1 with fixed controls vs real Alps at block resolution: 6/8/16/20/32 m vs 10/14/31/60/104 m. The shortfall grew with wavelength: missing valley networks, not texture |
| Height curve | v1 highland at 96 m/block flattened even real Alps to p99 steps of 2 blocks |

## 17. File map

```
Models/
├── README.md                 index of models and shared services
├── terrain-bridge/           shared tile adapter
│   ├── bridge/               water.py (UpstreamWater) · config.py · height_mapping.py · cache.py
│   └── hydrology/            D8 fill/flow (also used by data/build.py)
└── DaedalusTGM-Exp/          this model (uv project, Python package terrain_slm)
    ├── README.md             quickstart: setup, serve, train, evaluate, test
    ├── pyproject.toml · uv.lock · .venv/ (ignored) · data/ (ignored; folder or symlink to a data disk)
    ├── terrain_slm/
    │   ├── paths.py          the one place for cross-folder paths (model, Models/, repo, bridge)
    │   ├── data/             glo30.py · build.py · descriptors.py
    │   ├── synth/            noise.py
    │   ├── models/           planner.py · refiner.py · relief.py (v2)
    │   ├── train/            planner_data.py · train_planner.py · train_refiner.py · train_relief.py (v2)
    │   ├── world/            generator.py (controls v2, relief windows, soft cap, tiling) · rivers.py
    │   ├── serve/            upstream_api.py
    │   ├── eval/             sheet.py · mountains.py (v2)
    │   └── biomes.py         vendored classifier
    ├── tests/                37 tests
    ├── checkpoints/v2/       planner.pt (R1) · refiner.pt · relief.pt: the shipping model, TRACKED (~9 MB); runs + poc/ ignored
    ├── reports/v2/           mountain report (ignored)
    └── docs/                 Architecture.md (this file) · Terrain-SLM-Plan.md (+ Terrain-SLM/ diagrams)
                              system-overview.png/.py · mountain-diagnosis.png · diagnostics/
stonebreak-game/.../world/generation/diffusion/TerrainScale.java
stonebreak-game/.../world/generation/diffusion/process/TerrainServiceProcessManager.java   (Models/ paths, SLM_MODEL_NAME)
```
