# Hydrological water overlay

Derives rivers and lakes from a terrain-diffusion elevation map, so water can be placed
from the terrain the model already produced instead of retraining it to emit a water
channel.

Nothing here is imported by the running bridge service — `bridge/main.py` never touches
this package, so deploying the tile server does not pull in numba. Install separately:

```bash
./venv/bin/pip install -r requirements-hydro.txt
```

## Use

```bash
# From a GeoTIFF (CRS and transform carried through unchanged)
python scripts/generate_water_map.py --dem elevation_map.tif
#   -> elevation_map_water_depth.tif

# Side-by-side visual check
python scripts/test_water_overlay.py --dem elevation_map.tif --out overlay.png

# No data to hand? Continuous-valued synthetic terrain, deterministic, numpy only
python scripts/test_water_overlay.py --synthetic fractal_terrain --cell-size-m 90 --out demo.png
```

```python
from hydrology import water_depth
wm = water_depth(elev_metres, horizontal_cell_size_m=15.0, river_threshold=1000)
wm.depth          # (H, W) float32 metres, lakes and rivers combined
wm.lake_depth     # standing water only
wm.river_depth    # channel water only
wm.water_surface  # elevation of the top of the water column
```

## Read this before feeding it block heights

**Flow routing needs elevation in metres. It does not work on Stonebreak block heights.**

A DEM quantised to whole blocks has no strictly-lower neighbour across most of its area,
and routing degenerates into a distance field: rivers come out as straight parallel lines
converging in herringbone chevrons. Measured on one 1024² window of real diffusion
terrain, for the same underlying surface:

| vertical quantisation | source | land that is perfectly flat |
|---|---|---|
| continuous | model output in metres | **8 %** |
| 1 m | `tiff-export` int16 metres | **9 %** |
| 15 m | bridge tile cache (block heights) | **40 %** |

That last row was measured against the pipeline's *old* linear `elevation_to_block_height`
at a uniform 15 m/block. On a genuinely flat lowland it gets far worse — the tile-cache
window used during development retained just **31 distinct elevations and 84 % flat land**,
and produced exactly the herringbone described above.

Phase 5 replaced that mapping with `height_mapping.HeightCurve`, which spends ~4 m/block on
lowlands, so block heights are now roughly 3.75× finer exactly where the flat fraction was
worst. That shrinks the problem; it does not remove it, and the table above has not been
re-measured against the curve. Keep routing upstream of quantisation regardless — this is a
readability improvement, not a licence to route on block heights.

So derive water *upstream* of block quantisation: from `python -m terrain_diffusion
tiff-export` output, or inside the bridge before `height_mapping.HeightCurve.to_block_height`.
`scripts/generate_water_map.py` prints a warning when it detects a DEM this coarse, but
nothing further downstream can.

`--from-tile-cache` still exists, because stitched cache tiles are the only real diffusion
terrain available without a GPU. Use it for plumbing and performance checks, not to judge
whether the rivers look right. It inverts whichever curve the bridge is *currently*
configured with, so it can only read a cache namespace written under the same
`TERRAIN_BRIDGE_*` values — the fingerprint in the directory name is what those values
were. Tiles written before Phase 5 came from the old linear mapping and cannot be read
back correctly at all.

## How it works

Lakes and rivers come from different mechanisms, because they are different things.

1. **Fill** — Priority-Flood (Barnes et al. 2014), numba-compiled, `epsilon=0`. Every land
   cell gets a descending path to the sea or the tile edge. Optionally `max_raise`, which
   holds each basin to a fixed depth above its own floor so a basin larger than the window
   cannot fill to a different level in an overlapping window; off by default, because the
   uncapped fill delta *is* the lake depth.
2. **Lakes** — `filled - z`. A basin's fill delta *is* its standing water depth, and the
   surface is flat at the spill elevation by construction. No tuning constant.
3. **Flat resolution** — Garbrecht & Martz (1997). Level ground has no D8 direction, so a
   gradient is laid across it: distance-from-outlet as the primary key, distance-from-high-
   ground as a sub-unit tie-break. The two are combined lexicographically, not summed —
   summing them lets an outlet near high ground sit above its own flat's interior, which
   turns the basin into a pit that swallows its catchment.
4. **Routing** — D8 steepest descent on the resolved surface.
5. **Accumulation** — level-synchronous Kahn's algorithm over the D8 forest; the whole
   in-degree-zero frontier is processed per vectorised pass. O(N), pure numpy.
6. **Rivers** — downstream hydraulic geometry, `depth = k · area_km² ^ b` (Leopold &
   Maddock 1953), above a drainage-area threshold. The defaults are tuned to look right,
   not calibrated against measurements.
7. **Composite** — `max(lake, river)`. Ocean is a separate mask, never folded into depth.

Runtime, warm: **0.34 s at 1024², 1.7 s at 2048²**.

## Relationship to upstream

`terrain_diffusion/inference/postprocessing.py` has dormant versions of the fill, D8 and
accumulation. They are unreachable from here (that module imports torch, this venv is
torch-free) and could not be used as-is regardless — accumulation loops in Python over
every cell. This package re-implements them and fixes three defects found in the originals:

- `d8_flow(tol=1e-3)` discarded diagonal steps across `epsilon=1e-3` flats, because
  `1e-3/√2 < tol`. Diagonal drainage across every filled flat died silently.
- Receiver indices were clipped after an edge-padded neighbour lookup, so a border cell
  could resolve to itself — a self-loop that hangs a topological traversal.
- The ocean mask was recomputed from the filled surface, where no land is `<= 0` any more.
- `max_raise` measured basin depth against the running minimum of the flood path, not the
  basin's floor. A basin perched above lower ground therefore never filled at all, a deep
  one overshot its cap, and the answer depended on the direction the flood arrived from —
  which defeats the only reason the knob exists. Re-derived here as a post-pass over the
  connected components of `filled > z`, which is exact and window-independent.

`relief_map.get_relief_map` *is* reused, by `scripts/test_water_overlay.py` — it is pure
numpy/scipy/matplotlib and imports fine.

## Tests

```bash
./venv/bin/pytest tests/test_hydrology.py tests/test_hydrology_fill_cap.py -v
```

40 in the first file, 47 in the second. `test_hydrology_fill_cap.py` covers `max_raise`
only, and is kept separate so `test_hydrology.py` stays this package's own baseline.

Two kinds. **Closed-form**: synthetic DEMs from `hydrology/fixtures.py` built backwards
from a known answer — a paraboloid crater whose depth is `rim - z` at every cell and whose
volume is `depth·πr²/2`, an inclined plane whose upslope count at row *i* is exactly
*i + 1*, two basins that must fill to their spill points (30 m and 10 m) rather than to
their walls. **Invariants** that hold on any DEM: water is conserved, nothing flows uphill,
no land cell strands mid-terrain, lake surfaces are level, output stays aligned with input.

Real terrain is deliberately absent from the assertions. A DEM tells you where the basins
are but never how deep the water in them should be, so a plausible-looking result on real
terrain proves nothing — that is what the visual check is for.
