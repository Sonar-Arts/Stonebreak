#!/usr/bin/env python3
"""Render elevation, water depth and a combined overlay side by side.

This is the eyeball check, not the correctness check -- numeric verification lives in
`tests/test_hydrology.py`, against synthetic DEMs whose answers are known in closed
form. What this catches is the class of problem that is obvious to a human and
awkward to assert: rivers running along ridges, tributaries splitting instead of
merging, streams stopping in mid-slope, lakes with sloped surfaces.

The terrain rendering reuses upstream's shaded-relief renderer
(`terrain_diffusion/inference/relief_map.py`), which already draws exactly the river
overlay we want from a flow-accumulation array. It is pure numpy/scipy/matplotlib, so
importing it does not pull in torch. If the upstream checkout is missing, a plain
local hillshade stands in.

Examples
--------
    python scripts/test_water_overlay.py \\
        --from-tile-cache tile_cache/df27340a8b39 \\
        --seed 3825783951015184678 --tile-x 2 --tile-z 0 --tiles 4 \\
        --out /tmp/water_overlay.png

    python scripts/test_water_overlay.py --synthetic paraboloid_crater --out /tmp/crater.png
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.colors import LogNorm

from hydrology import load_dem, stitch_tile_cache, water_depth
from hydrology import fixtures as fx
from hydrology.depth import DEFAULT_HORIZONTAL_CELL_SIZE_M
from hydrology.io import vertical_resolution_warning

# terrain-bridge/scripts/ -> terrain-bridge/ -> Stonebreak/
DEFAULT_REPO = Path(__file__).resolve().parents[2] / "Dev Working/terrain-diffusion-spike/repo"

_SYNTHETIC = tuple(fx.ALL_FIXTURES) + (fx.fractal_terrain,)


def _load_relief_renderer():
    """Upstream's shaded-relief renderer, or None if the checkout is not present."""
    repo = Path(os.environ.get("TERRAIN_DIFFUSION_REPO", DEFAULT_REPO))
    if not (repo / "terrain_diffusion" / "inference" / "relief_map.py").exists():
        return None
    sys.path.insert(0, str(repo))
    try:
        from terrain_diffusion.inference.relief_map import get_relief_map
        return get_relief_map
    except Exception as e:  # noqa: BLE001 - a missing renderer must not fail the check
        print(f"note: upstream relief renderer unavailable ({e}); using local hillshade")
        return None


def _local_hillshade(z: np.ndarray) -> np.ndarray:
    """Minimal stand-in for upstream's renderer: grey hillshade, blue sea."""
    zf = np.nan_to_num(z, nan=0.0)
    dy, dx = np.gradient(zf)
    slope = np.pi / 2 - np.arctan(np.hypot(dx, dy) / 15.0)
    aspect = np.arctan2(dy, -dx)
    hs = np.sin(np.pi / 4) * np.sin(slope) + np.cos(np.pi / 4) * np.cos(slope) * np.cos(np.deg2rad(315) - aspect)
    hs = np.clip(hs, 0, 1)[..., None]
    rgb = np.repeat(0.35 + 0.6 * hs, 3, axis=2)
    rgb[zf <= 0] = (0.10, 0.25, 0.50)
    return rgb.astype(np.float32)


def _overlay_water(rgb: np.ndarray, depth: np.ndarray, ocean: np.ndarray) -> np.ndarray:
    """Paint inland water onto the relief, shading deeper water darker."""
    out = rgb.copy()
    wet = (depth > 0) & ~ocean
    if not wet.any():
        return out
    # Normalise against a high percentile so one deep lake does not flatten the rivers.
    ref = max(float(np.percentile(depth[wet], 95)), 1e-6)
    t = np.clip(depth / ref, 0.0, 1.0)[..., None]
    shallow = np.array([0.45, 0.75, 0.95], dtype=np.float32)
    deep = np.array([0.02, 0.18, 0.55], dtype=np.float32)
    water_rgb = (1.0 - t) * shallow + t * deep
    out[wet] = water_rgb[wet]
    return out


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--dem", type=Path, help="elevation map (.tif/.tiff/.npy)")
    p.add_argument("--from-tile-cache", type=Path, metavar="DIR",
                   help="bridge tile-cache fingerprint directory")
    p.add_argument("--synthetic", choices=[f.__name__ for f in _SYNTHETIC],
                   help="render a built-in DEM instead: an analytic fixture, or "
                        "'fractal_terrain' for continuous-valued realistic terrain")
    p.add_argument("--synthetic-size", type=int, default=512,
                   help="grid size for --synthetic fractal_terrain (default 512)")
    p.add_argument("--synthetic-seed", type=int, default=0,
                   help="seed for --synthetic fractal_terrain (default 0)")
    p.add_argument("--seed", type=int)
    p.add_argument("--tile-x", type=int, default=0)
    p.add_argument("--tile-z", type=int, default=0)
    p.add_argument("--tiles", type=int, default=4)
    # Block heights are inverted through the bridge's elevation curve; set the same
    # TERRAIN_BRIDGE_* env vars the tiles were written under.
    p.add_argument("--cell-size-m", type=float, default=None)
    p.add_argument("--river-threshold", type=float, default=1000.0)
    p.add_argument("--min-lake-area", type=int, default=8)
    p.add_argument("--min-lake-depth", type=float, default=0.5)
    p.add_argument("--out", type=Path, default=Path("water_overlay.png"))
    p.add_argument("--dpi", type=int, default=140)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    cell_size = args.cell_size_m or DEFAULT_HORIZONTAL_CELL_SIZE_M
    if args.synthetic:
        factory = dict((f.__name__, f) for f in _SYNTHETIC)[args.synthetic]
        if factory is fx.fractal_terrain:
            fixture = factory(size=args.synthetic_size, seed=args.synthetic_seed)
        else:
            fixture = factory()
            args.min_lake_area, args.min_lake_depth = 1, 0.0
        z, label = fixture.z, f"synthetic: {fixture.name}"
    elif args.from_tile_cache:
        if args.seed is None:
            raise SystemExit("error: --from-tile-cache also needs --seed")
        z, meta = stitch_tile_cache(args.from_tile_cache, args.seed, args.tile_x,
                                    args.tile_z, args.tiles,
                                    horizontal_cell_size_m=args.cell_size_m)
        cell_size = args.cell_size_m or meta.horizontal_cell_size_m or cell_size
        label = f"diffusion terrain  seed {args.seed}  tile ({args.tile_x}, {args.tile_z})"
    elif args.dem:
        z, _ = load_dem(args.dem)
        label = args.dem.name
    else:
        raise SystemExit("error: one of --dem, --from-tile-cache or --synthetic is required")

    warning = vertical_resolution_warning(z)
    if warning:
        print(f"WARNING: {warning}")

    wm = water_depth(z, horizontal_cell_size_m=cell_size, river_threshold=args.river_threshold,
                     min_lake_area=args.min_lake_area, min_lake_depth=args.min_lake_depth)
    s = wm.stats()
    print(f"{label}\n  {s}")

    get_relief_map = _load_relief_renderer()
    if get_relief_map is not None:
        # Upstream signature: (elevation, climate, biome, flow, ...). Passing flow=None
        # suppresses its own river overlay -- we draw ours from depth, not accumulation.
        relief = np.asarray(get_relief_map(z.astype(np.float32), None, None, None,
                                           resolution=cell_size))
        relief = np.nan_to_num(np.clip(relief, 0, 1), nan=0.0)
    else:
        relief = _local_hillshade(z)

    fig, axes = plt.subplots(2, 2, figsize=(13, 13), constrained_layout=True)
    fig.suptitle(f"Hydrological water overlay — {label}", fontsize=13)

    axes[0, 0].imshow(relief)
    axes[0, 0].set_title("Elevation (shaded relief)")

    depth_masked = np.ma.masked_where(wm.depth <= 0, wm.depth)
    im = axes[0, 1].imshow(depth_masked, cmap="viridis")
    axes[0, 1].set_title("Water depth (m)")
    fig.colorbar(im, ax=axes[0, 1], shrink=0.75, label="metres")

    acc = np.maximum(wm.accumulation, 1.0)
    im2 = axes[1, 0].imshow(acc, cmap="magma", norm=LogNorm(vmin=1, vmax=max(acc.max(), 10)))
    axes[1, 0].set_title("Flow accumulation (upslope cells, log)")
    fig.colorbar(im2, ax=axes[1, 0], shrink=0.75)

    axes[1, 1].imshow(_overlay_water(relief, wm.depth, wm.ocean))
    axes[1, 1].set_title("Terrain with water")

    for ax in axes.ravel():
        ax.set_xticks([])
        ax.set_yticks([])

    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, dpi=args.dpi)
    print(f"wrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
