# DaedalusTGM-Exp: Terrain Generation Model Architecture

**Branch:** `Project-Daedalus` (off Project-Heracles) · **As of:** 2026-09-27 (v3: hydrology sidecar, river pipeline with learned 3D banks, MaskGIT descriptors)
**Name:** **DaedalusTGM-Exp** (Daedalus Terrain Generation Model, experimental): the official name of the model and its server. Code identifiers keep their original names: Python package `terrain_slm`, backend key `-Dstonebreak.terrainService.backend=slm`. The name appears in `terrain_slm.MODEL_NAME`, the server's `/health` (`"name"`), its `model_id` (`DaedalusTGM-Exp/v3:p…:r…:l…:h…:b…:g…`), its log prefix, and `TerrainServiceProcessManager.SLM_MODEL_NAME` (Java logs)
**Home:** `Models/DaedalusTGM-Exp/` (this model: code, training, server, checkpoints, reports, docs) · `Models/terrain-bridge/` (shared tile adapter) · Java process manager + world constants
**Plan / history:** [`Terrain-SLM-Plan.md`](Terrain-SLM-Plan.md) (§13 onward is the running log)

---

## 1. Status at a glance

| | |
|---|---|
| Default generator | **DaedalusTGM-Exp v3** (`TerrainServiceProcessManager.DEFAULT_BACKEND = "slm"`, model dir `checkpoints/v3`, tracked in git, ~18 MB); the diffusion upstream is reachable only via `-Dstonebreak.terrainService.backend=upstream` |
| Controls | **v2**: continents + long, winding **mountain ranges** (spines, spurs, foothills) + climate archetypes (§6) |
| Relief sampler | **new**: flow-matching conv UNet on 240 m cells, **425,033 params**, 11 min; samples valley networks onto the smooth trend (§7) |
| Planner (the "SLM") | **R1**: ViT d176 × 4 layers × 4 heads, **1,558,656 params** (unchanged; now fed a valley-rich trend) |
| Refiner | conditional flow-matching conv UNet, **336,785 params** (unchanged) |
| Descriptor sampler | **new in v3**: MaskGIT over split-codebook tokens, **1,552,248 params**, 20 min; replaces the planner's averaged descriptors (§9a) |
| Hydrology sidecar | **new in v3**: conv UNet on 240 m cells, **400,051 params**, 8 min; drainage, rivers and D8 flow from the final coarse height (§11a) |
| Bank model | **new in v3**: conv UNet on 30 m pixels, **236,497 params**, 6 min; real river banks, run only where a river exists (§11c) |
| Training data | 7 Copernicus GLO-30 regions, 134 one-degree tiles (§4) |
| World | **256 tall, sea level y 64**, 60 m blocks, **vertically exaggerated** height curve: 2 km → y 170, 3 km → y 197, 4 km → y 223 (§3) |
| Water | rivers from the hydro sidecar through the river pipeline (§11): 3–16 blocks wide, 2–6 deep, learned banks, undercuts and overhangs in the game's 3D tunnel planes, flow octants; no lakes; threshold 5.5 |
| Mountains (seed 2, 61 km square) | seeds 0–3: peaks y 230–232 (highest real-Alps window: 237), 144–164 blocks of relief (real: 154), p99 block step 2 (real: 3); report `reports/v3/` (v3 keeps these; see §14) |

---

## 2. System overview

![System overview](system-overview.png)

(`system-overview.py` regenerates it; from `docs/`: `../.venv/bin/python system-overview.py`.)

**Tile contract (unchanged for the game):** 256×256 int16 block heights + int16 biome ids + int16 water levels (`-1` = dry).

Per request, the model server runs:

1. **Procedural controls** (per 240 m cell): trend, wildness, climate.
2. **Relief sampler**: adds valley networks to the smooth trend. Canonical 448-cell windows, cross-faded.
3. **Planner**: turns the valley-rich trend into coarse height (its descriptors and drainage are superseded below).
4. **Descriptor sampler (MaskGIT)**: samples 8 terrain descriptors per cell, checkerboard windows.
5. **Hydrology sidecar**: drainage, river probability and D8 directions from the coarse height, 448-cell windows.
6. **Synth**: descriptor-driven noise at 30 m pixels.
7. **Refiner**: SDEdit at t = 0.6.
8. **Summit soft cap**, then the **river pipeline** (§11): centrelines, channel, water level, learned banks, bed, containment.
9. **Block stages** (2×2 downscale): quantise, flow, flatten, contain, undercuts, overhangs → heights, water and the 3D river planes.

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
| R1 without river/flow losses (ablation) | 1.56M | 38.0 m | — | desc 0.372 (R1 0.375) |

In v3 the planner's descriptors are replaced by MaskGIT samples (§9a) and its drainage by the hydro sidecar (§11a); its coarse height is still used.

## 9. Synth (`synth/noise.py`) and refiner (`models/refiner.py`)

These are unchanged in v2 and v3.
- **Synth:** hash noise, fixed band kernels, analytic amplitudes/anisotropy/ridge, 16-cell margins, so the output is region-exact.
- **Refiner:** 16→32→48 conv UNet, flow matching on the residual over the coarse surface divided by a descriptor-derived scale field, sampled as SDEdit from the synth at t 0.6 with 8 Euler steps. Lowering t_start makes terrain smoother (the refiner under-produces amplitude too), so 0.6 stays.

## 9a. Descriptor sampler: MaskGIT (`models/descgit.py`, `train/train_descgit.py`)

The planner regresses its 8 descriptors, so where the inputs cannot pin texture down it predicts the mean. v3 **samples** them instead.

| | |
|---|---|
| Tokens | split k-means codebook: 512 codes for the 5 band amplitudes plus 256 for ridge/anisotropy, 2 tokens per cell. Reconstruction error 0.11 in normalised units (one 1024-code token: 0.19) |
| Model | the planner's ViT trunk (4×4-cell patches, axial RoPE), d160 × 4, conditioned on the planner's inputs, 1,552,248 params |
| Training | planner crops; masks are either random (cosine-scheduled fraction) or tiling-like (core hidden, random neighbour cores given); cross-entropy on masked cells; 30k steps |
| Sampling | 12 passes, cosine unmasking schedule, Gumbel sampling with coordinate-hashed noise per cell, class and pass (deterministic, request-independent) |
| Tiling | 64-cell windows around 32-cell cores in a 4-phase checkerboard. A window conditions only on earlier-phase neighbours' cores, so the chain is at most 3 windows deep; hard core ownership, no cross-fade to average samples away; light 0.7-cell blur after decoding |

**Held-out result** (fine-scale variability = std of the descriptor field minus its 2-cell blur):

| Channel | Real | Planner R1 | MaskGIT |
|---|---|---|---|
| amp0 (finest) | 0.210 | 0.056 | 0.184 |
| amp1 | 0.201 | 0.053 | 0.160 |
| ridge | 0.605 | 0.052 | 0.573 |
| anisotropy | 0.45–0.48 | 0.11 | 0.44–0.46 |

The planner keeps 9–27% of real fine texture; MaskGIT restores 80–95%, with value distributions closer to real for all five amplitudes and the same large-scale fidelity (correlation with real at 8-cell scale 0.94–1.00, equal to the planner's). One flaw: the coarsest band is over-varied (0.066 vs 0.022) from codebook rounding.

**What it did not fix:** block-level relief per band is unchanged v2 → v3, and the "orange peel" look on high ground remains. MaskGIT restores how texture *varies*, but the synth still turns descriptors into isotropic noise with no drainage structure. The 1–4 km dendritic valleys need a generative model at that scale (§15).

## 10. World generator (`world/generator.py`)

| Stage | Tiling | Cache |
|---|---|---|
| relief windows | 448-cell windows, stride 256, sin² fade over 192 cells | LRU 64 |
| planner windows | 64 cells, stride 32, sin² (exact partition of unity), 1-cell blur hides the patch grid | LRU 4096 |
| hydro windows | the relief windows' layout; flow/river cross-faded, D8 by core ownership | LRU 64 |
| descriptor windows | 64-cell windows, 32-cell cores, 4-phase checkerboard, core ownership | LRU 8192 |
| pixel regions | 256 px cores + 64 px apron, cross-faded over 128 px | LRU from `--cache-size` |

`river_field(i1,j1,i2,j2)` runs native heights → summit soft cap → the river pipeline's native stages; `terrain()` returns its carved elevation plus water surface, and the server's block path runs the block stages. Optional models load when their file is in the model dir (`relief.pt`, `hydro.pt`, `bank.pt`, `descgit.pt`); without one, that stage falls back to the previous behaviour. `model_id` = `DaedalusTGM-Exp/<dir>:p<planner>:r<refiner>:l<relief>:h<hydro>:b<bank>:g<descgit>` (steps, or `none`).

## 11. River pipeline (`terrain_slm/river/`) — rebuilt in v3

Rivers are an ordered list of small stages, each with one job and a typed input and output, so any stage can be swapped or dropped by editing the list (`pipeline.default_pipeline`):

```
native pixels (30 m, metres):
  Centrelines -> ChannelGeometry -> WaterSurface -> Banks -> BankGuard -> UBed -> NoSpill
block columns (60 m, blocks):
  BlockQuantize -> FlowOctants -> FlattenAcross -> BlockContainment -> Undercuts -> Overhangs
```

| Stage | Module | What it does |
|---|---|---|
| Centrelines | `geometry.py` | Ridge lines of the smoothed drainage field (Hessian non-maximum suppression). **Hysteresis**: above the threshold is a river; down to 0.7 below it continues only if connected to a river stretch (≤ 64 px), so flow hovering at the threshold does not fragment rivers |
| ChannelGeometry | `geometry.py` | Channel sized **in blocks** from flow above the threshold: width 3 + 1.8·excess (3–16 blocks), depth 2 + 0.6·excess (2–6 blocks), whatever band of the curve it crosses |
| WaterSurface | `geometry.py` | Level = ground under the centreline, smoothed along it, carried straight across the channel (one level per cross-section), spread past the banks |
| Banks | `banks.py` | `LearnedBanks` (bank model, §11c) when `bank.pt` is present, else `LeveeBanks` (deterministic ring) |
| BankGuard | `banks.py` | Lifts only bank pixels that would let water out, and records how much (`guard_mean_lift_blocks`) |
| UBed | `geometry.py` | U-shaped bed, 1 block deep at the edge, full depth at the centre. Elevation data never records beds, so this is shaped, not learned |
| NoSpill | `contain.py` | Fixed point: water never stands above adjacent dry ground |
| BlockQuantize | `blocks.py` | Metres → blocks through the game's curve (`river/scale.py`, `BlockScale`), beds take the channel floor |
| FlowOctants | `blocks.py` | Downhill along the continuous water surface; flat reaches use the hydro sidecar's D8 |
| FlattenAcross | `blocks.py` | Each wet column takes the lowest level walking perpendicular to its flow: sideways surface steps 284 → 39 on a 30 km test square; remaining steps are one-block cascades along the flow |
| BlockContainment | `blocks.py` | A dry column beside water stands at least as high as that water |
| Undercuts | `blocks.py` | Walls ≥ 3 blocks above the water, in noise patches, get an undercut: stone lip level with the top water block, 2 air blocks above it (`riverFloor`/`riverRoof` on a dry column) |
| Overhangs | `blocks.py` | On rivers ≥ 5 blocks wide, the wet edge column beside an undercut takes the bank's lip over it: water under an overhang (a wet column with a tunnel) |

**Context and determinism:** `HALO_PX` 128 (centreline smoothing, hysteresis reach, widest channel, bank band, bank-model receptive field), `BLOCK_MARGIN` 24 blocks, no wrap-around anywhere, and the bank model runs in fp32 (bf16 rounding depended on request shape). Output is request-independent (tested).

Every stage records statistics in `ctx.stats` (guard lifts, dried pixels, block raises, flattened columns, undercuts, overhangs), and `pipeline.describe()` prints the chain (the server logs it at startup).

## 11a. Hydrology sidecar (`models/hydro.py`, `train/train_hydro.py`)

Reads the **final coarse height** (planner output) plus climate and predicts, per cell, log flow accumulation, river probability and **D8 flow direction** (9 classes). Rivers therefore follow the valleys that were actually generated; before, the planner guessed drainage from its trend input and nothing tied rivers to valleys.

| | |
|---|---|
| Model | conv UNet, 4 levels (16/24/40/56 ch), receptive field ~100 cells, 400,051 params |
| Training | planner crops (160 cells), real coarse height blurred σ 0.7–2 cells + ≤ 25 m low-frequency jitter (to look like planner output), L1 flow + BCE river + 0.25 × D8 cross-entropy, 16-cell loss margin, 20k steps |
| Held-out | river IoU **0.577** (planner: 0.30), flow L1 **0.088** (planner: 0.144), D8 accuracy **70%**. The sidecar sees real height where the planner sees a trend, so part of the gap is the input |
| Tiling | the relief sampler's 448-cell windows; flow and river cross-faded, D8 taken from the window whose core owns the cell (classes cannot be blended) |
| Threshold | 5.5 (training definition 6.08): about 1–5% of land water with 3–16-block rivers |

**Ablation: does removing the rivers from the planner free capacity?** No. R1 retrained without the river and flow losses: descriptor loss −0.6%, but coarse height error **+10%** (34.5 → 38.0 m). Learning drainage helps the planner model terrain. The planner keeps its river heads; the sidecar provides the rivers.

## 11b. 3D river planes and tile protocol v3

The game's `TerrainTile` already supports per-column river tunnels: `riverFloor`/`riverRoof` (stone shells, water below the column's water level and air above inside), plus `riverFlow` octants. `HeightMapGenerator`, chunk filling, `WaterGuard`, the carvers and the water simulation all read them. v3 carries them from the model to the game:

- **Model server:** `/terrain?...&water=1&river3d=1` appends three int16 planes in **blocks** (floor, roof, flow; −1 = none).
- **Bridge** (`PROTOCOL_VERSION` 3, cache schema 4, 6 planes): passes them through (`TERRAIN_BRIDGE_RIVER3D`, default on). `UpstreamWater` keeps water under overhangs, whose height is the bank top above the water.
- **Java** (`DiffusionTerrainClient` v3): decodes 6 planes. All-empty river planes become `null`, which `TerrainTile` already supports.

Containment in 3D (tested): a water block never meets dry air, an undercut's air pocket or a tunnel sideways. A wet neighbour at most one block lower is the river flowing.

## 11c. Bank model (`river/banks.py`, `data/water.py`, `train/train_banks.py`)

Copernicus GLO-30 is an *edited* DSM: water wider than ~183 m is flattened to its level (rivers in monotone steps). So exact flatness (3×3 span < 1 cm, ≥ 150-px patches, above sea level) detects real rivers and lakes (`data/water.py`, `water.npz` per region): 33,401 training and 1,606 held-out water edges. 183 m ≈ 3 blocks, exactly the smallest river the game draws.

| | |
|---|---|
| Task | a 12-px (6-block) band beside the water is blanked and filled from the ground beyond it; the model redraws it. Inputs and target in **blocks above the water level**, through the game's curve |
| Model | conv UNet, 3 levels (16/32/48 ch), 236,497 params, predicts a correction to the filled band |
| Held-out | **0.45 blocks** mean error (filling alone: 4.35) |
| In game | runs only where a river exists; fades into untouched ground over the band's outer 1.5 blocks |
| Leak pressure | real banks sit flush with the water (median 0.000 blocks below). The model is below by a median 0.012 and p95 0.08 blocks, so the guard's lifts (mean 0.056 blocks) are invisible after quantisation |

Learned banks against the deterministic levees (61 km mountain square): banks slope naturally into the water instead of ending in 1-block walls, and block-level containment raises 1,795 columns instead of 2,780. Because learned banks are gentle at the water, they leave fewer tall walls to undercut, hence the 3-block trigger.

## 12. Serving (`serve/upstream_api.py`) and bridge (`Models/terrain-bridge/`)

**Server:** a drop-in for upstream's `minecraft_api` (same CLI flags, `/health`, `/terrain?i1&j1&i2&j2&scale&downscale&noise&elev_only&water&river3d&format`).
- With `downscale=2`: runs the river pipeline over the request plus `BLOCK_MARGIN`. It returns heights and water as **mid-band metres** (the bridge's curve maps them back to exactly the blocks decided here), plus, with `river3d=1`, the three river planes in blocks.
- The live bridge curve is passed to the generator (`set_block_scale`), and the startup log prints the river chain.
- Biomes come from upstream's classifier, vendored verbatim.
- Knobs (env): `TERRAIN_SLM_T_START` 0.6, `TERRAIN_SLM_STEPS` 8, `TERRAIN_SLM_AMP_SCALE` 1.0, `TERRAIN_SLM_RIVER_THRESHOLD` (model default: 5.5 with hydro, 4.5 relief-only, 3.3 without either), `TERRAIN_SLM_DEVICE`.

**Bridge:**
- `TERRAIN_BRIDGE_WATER_SOURCE=upstream` takes the model's water; `TERRAIN_BRIDGE_RIVER3D` (default on) the 3D planes.
- Protocol v3, 6 planes; the cache schema bump rotates old caches.
- `TERRAIN_BRIDGE_UPSTREAM_ID` (= `slm:<model dir>`) and the curve rates are part of the tile-cache fingerprint. **Any generator change needs a new model dir name**, or the bridge will serve old cached tiles.

**Timing** (one RTX PRO 6000): first tile of a new world **28.7 s** cold (relief, hydro and descriptor windows warm up), then about **1.5 s** per tile.

## 13. Game integration (Java)

| Where | What |
|---|---|
| `TerrainServiceProcessManager` | `DEFAULT_BACKEND="slm"`, model dir **`checkpoints/v3`**, `SLM_MODEL_NAME`, `MODELS_DIR`/`BRIDGE_DIR`/`SLM_DIR` |
| `DiffusionTerrainClient` | tile protocol **v3**: 6 planes → `TerrainTile` river floor/roof/flow (null when empty) |
| `TerrainScale` | 60 m blocks (`DOWNSCALE` 2), `CURVE_RATES` {48, 16, 24, 38} |
| `TerrainMapperConfig.TOPO_LAND_CEILING` | 240 |
| `WorldConfiguration` | 256 / 64; `HeightMapGenerator` clamps surfaces to y 255 and reads the tile's river planes |
| `NativeWaterTiles.nativeBackendSelected()` | false on slm (no basin solver / coarse DEM) |
| Engine | `VoxelChunkCodec.CHUNK_H = 256`, `CloudRenderer.CLOUD_Y = 192` |

## 14. Verification

- **Model tests: 46.** New in v3:
  - river stages (`test_rivers.py`): geometry in blocks, bigger flow means wider and deeper, weak flow stays dry, no spills, thin centrelines, stages swap without disturbing each other, the bank model is a no-op without a river, block depth and flow;
  - 3D block containment and plane well-formedness, request independence including the river planes (`test_downscale_blocks.py`);
  - MaskGIT codebook, deterministic sampling that keeps known tokens, checkerboard request independence, phase ordering (`test_descgit.py`).
- **Bridge tests: 294** (291 + 3 for protocol v3: river planes round-trip the cache, six-plane payloads pass through with `river3d` sent, water under an overhang survives the mapping).
- **Java:** `DiffusionTerrainClientTest` (14, incl. two new for river planes), `DiffusionTileCacheTest`, `TerrainTileTest`, `TerrainServiceProcessManagerTest`, `TopographyVisualizerTest`, `WaterVisualizerTest`.
- **End to end:** model server + bridge from their `Models/` paths; a mountain tile came back over protocol 3 with 6 planes, 3,821 wet columns (5.8%), all flowing.
- **Mountain report v3** (`reports/v3/`, seeds 0–3): heights and relief unchanged from v2 (y 231–232, 147–165 blocks); rivers 0.6–4.8% of the square (v2: 0.2–2.5%) with 3–16-block widths.

## 15. Known limitations and next steps

1. **Orange peel / missing 1–4 km dendritic valleys.** Neither MaskGIT (texture variety, fixed) nor a lower refiner t_start (smoother) creates drainage-structured texture at that scale. Next rung: a finer relief sampler (60–240 m, conditioned on the coarse relief and the hydro sidecar's drainage) or a stronger refiner, trained on the valley networks the DEMs contain.
2. **Cold start 28.7 s for the first tile** (v2 was ~15 s to PLAYING). The hydro windows (108 km) pull in wide planner context. Options: smaller hydro windows (apron 64), caching windows across worlds with the same seed, or warming in the background during the loading screen.
3. **Undercuts are uncommon** (~0.7% of bank columns on mountain ground), because learned banks meet the water gently. The trigger (`RiverConfig.undercut_*`) and the patch noise are the knobs. A learned 3D wall profile would need 3D training data that real DEMs do not have.
4. **River surfaces step** one block at a time along the flow (cascades inside the channel); 39 sideways steps remain on a 30 km square, in bends.
5. Terrain shape doesn't follow climate zones yet; lowlands are bland smooth domes.
6. No lakes on slm worlds.
7. Old worlds: chunks generated under v1/v2 won't match v3 tiles at their borders; start a new world.
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
├── terrain-bridge/           shared tile adapter (protocol v3: 6 planes)
│   ├── bridge/               water.py (UpstreamWater) · config.py · height_mapping.py · cache.py · queue.py
│   └── hydrology/            D8 fill/flow (also used by data/build.py)
└── DaedalusTGM-Exp/          this model (uv project, Python package terrain_slm)
    ├── README.md             quickstart: setup, serve, train, evaluate, test
    ├── terrain_slm/
    │   ├── paths.py · device.py   cross-folder paths; default training device
    │   ├── data/             glo30.py · build.py · descriptors.py · water.py (v3: water masks from flat DSM water)
    │   ├── synth/            noise.py
    │   ├── models/           planner.py · refiner.py · relief.py · hydro.py (v3) · descgit.py (v3)
    │   ├── river/            (v3) scale.py · field.py · geometry.py · banks.py · contain.py · blocks.py · pipeline.py
    │   ├── train/            planner_data · train_planner · train_refiner · train_relief · train_hydro · train_banks · train_descgit
    │   ├── world/            generator.py (controls, relief/hydro/descriptor windows, soft cap, river pipeline)
    │   ├── serve/            upstream_api.py (river3d planes)
    │   ├── eval/             sheet.py · mountains.py
    │   └── biomes.py         vendored classifier
    ├── tests/                46 tests
    ├── scripts/              download_region.py · package_training_data.py
    ├── checkpoints/v3/       planner · refiner · relief · hydro · bank · descgit (.pt), TRACKED (~18 MB); v2/ tracked; runs ignored
    ├── reports/              ignored
    └── docs/                 Architecture.md · Terrain-SLM-Plan.md · Remote-Training.md · system-overview.{png,py} · diagnostics/
stonebreak-game/.../world/generation/diffusion/{TerrainScale, DiffusionTerrainClient, TerrainTile}.java
stonebreak-game/.../world/generation/diffusion/process/TerrainServiceProcessManager.java
```
