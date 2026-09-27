#!/usr/bin/env python3
"""Generate a water-depth map from a terrain-diffusion elevation map (DEM).

Reads a single-channel elevation map in metres and writes a water-depth raster on
exactly the same grid: lakes recovered by filling the DEM's closed depressions,
rivers by accumulating D8 flow over the filled surface.

Examples
--------
Real diffusion terrain straight out of the bridge's tile cache (no GPU, no server):

    python scripts/generate_water_map.py \\
        --from-tile-cache tile_cache/df27340a8b39 \\
        --seed 3825783951015184678 --tile-x 2 --tile-z 0 --tiles 4 \\
        --out /tmp/water_depth.npy

A GeoTIFF from `python -m terrain_diffusion tiff-export` (CRS and transform are
carried through unchanged, so the result overlays the input exactly):

    python scripts/generate_water_map.py --dem elevation_map.tif

Tuning the rivers-vs-ponds threshold, writing every intermediate layer:

    python scripts/generate_water_map.py --dem elevation_map.tif \\
        --river-threshold 4000 --min-lake-depth 2.0 --write-layers
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np

from hydrology import load_dem, save_map, stitch_tile_cache, water_depth
from hydrology.depth import (
    DEFAULT_HORIZONTAL_CELL_SIZE_M,
    DEFAULT_RIVER_COEFF,
    DEFAULT_RIVER_EXP,
)
from hydrology.flats import DEFAULT_FLAT_EPSILON
from hydrology.io import vertical_resolution_warning


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    src = p.add_argument_group("input (choose one)")
    src.add_argument("--dem", type=Path, help="elevation map in metres (.tif/.tiff/.npy)")
    src.add_argument("--from-tile-cache", type=Path, metavar="DIR",
                     help="bridge tile-cache fingerprint directory to stitch a DEM from")
    src.add_argument("--seed", type=int, help="tile-cache seed")
    src.add_argument("--tile-x", type=int, default=0, help="origin tile x (world i axis)")
    src.add_argument("--tile-z", type=int, default=0, help="origin tile z (world j axis)")
    src.add_argument("--tiles", type=int, default=4, help="window edge length in tiles (default 4 = 1024 px)")
    # No --sea-level / --meters-per-block: block heights are inverted through the
    # bridge's elevation curve, which is non-linear and has ten knobs. Set the same
    # TERRAIN_BRIDGE_* env vars the tiles were written under.

    out = p.add_argument_group("output")
    out.add_argument("--out", type=Path, help="output path (default: <input>_water_depth.<ext>)")
    out.add_argument("--write-layers", action="store_true",
                     help="also write the lake, river and accumulation layers alongside --out")

    tune = p.add_argument_group("model")
    tune.add_argument("--cell-size-m", type=float, default=None,
                      help="horizontal ground size of one cell in metres "
                           "(default: the bridge's TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK, 15)")
    tune.add_argument("--river-threshold", type=float, default=1000.0,
                      help="upslope cell count above which a channel counts as a river (default 1000)")
    tune.add_argument("--river-coeff", type=float, default=DEFAULT_RIVER_COEFF,
                      help=f"hydraulic-geometry k in depth = k * area_km2**b (default {DEFAULT_RIVER_COEFF})")
    tune.add_argument("--river-exp", type=float, default=DEFAULT_RIVER_EXP,
                      help=f"hydraulic-geometry b (default {DEFAULT_RIVER_EXP})")
    tune.add_argument("--min-lake-area", type=int, default=8,
                      help="discard basins smaller than this many cells (default 8)")
    tune.add_argument("--min-lake-depth", type=float, default=0.5,
                      help="discard basins shallower than this many metres (default 0.5)")
    tune.add_argument("--epsilon", type=float, default=DEFAULT_FLAT_EPSILON,
                      help=f"drainage gradient laid across flats (default {DEFAULT_FLAT_EPSILON})")
    return p


def load_input(args: argparse.Namespace):
    """Resolve whichever input mode was requested into (elevation, meta, label)."""
    if args.dem and args.from_tile_cache:
        raise SystemExit("error: pass either --dem or --from-tile-cache, not both")

    if args.from_tile_cache:
        if args.seed is None:
            raise SystemExit("error: --from-tile-cache also needs --seed")
        z, meta = stitch_tile_cache(
            args.from_tile_cache, args.seed, args.tile_x, args.tile_z, args.tiles,
            horizontal_cell_size_m=args.cell_size_m,
        )
        label = f"tile_cache s{args.seed} x{args.tile_x} z{args.tile_z} {args.tiles}x{args.tiles}"
        default_out = Path(f"water_depth_s{args.seed}_x{args.tile_x}_z{args.tile_z}.npy")
        return z, meta, label, default_out

    if args.dem:
        z, meta = load_dem(args.dem)
        default_out = args.dem.with_name(f"{args.dem.stem}_water_depth{args.dem.suffix}")
        return z, meta, str(args.dem), default_out

    raise SystemExit("error: one of --dem or --from-tile-cache is required")


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    z, meta, label, default_out = load_input(args)
    out_path = args.out or default_out

    cell_size = args.cell_size_m
    if cell_size is None:
        cell_size = meta.horizontal_cell_size_m or DEFAULT_HORIZONTAL_CELL_SIZE_M

    land = int((~np.isnan(z) & (z > 0)).sum())
    print(f"DEM      {label}")
    print(f"         {z.shape[0]}x{z.shape[1]} cells @ {cell_size:g} m, "
          f"{np.nanmin(z):.0f}..{np.nanmax(z):.0f} m, {100.0 * land / z.size:.1f}% land")

    warning = vertical_resolution_warning(z)
    if warning:
        print(f"WARNING  {warning}")

    t0 = time.perf_counter()
    wm = water_depth(
        z,
        horizontal_cell_size_m=cell_size,
        epsilon=args.epsilon,
        river_threshold=args.river_threshold,
        river_coeff=args.river_coeff,
        river_exp=args.river_exp,
        min_lake_area=args.min_lake_area,
        min_lake_depth=args.min_lake_depth,
    )
    elapsed = time.perf_counter() - t0

    s = wm.stats()
    print(f"water    {elapsed:.2f} s")
    print(f"         {s['lake_cells']} lake cells, {s['river_cells']} river cells "
          f"({100.0 * s['wet_land_fraction']:.1f}% of land)")
    print(f"         max depth {s['max_depth_m']:.1f} m, "
          f"max accumulation {s['max_accumulation']:.0f} cells")

    save_map(out_path, wm.depth, meta)
    print(f"wrote    {out_path}")

    if args.write_layers:
        for name, layer in (("lake", wm.lake_depth),
                            ("river", wm.river_depth),
                            ("accumulation", wm.accumulation)):
            p = out_path.with_name(f"{out_path.stem}_{name}{out_path.suffix}")
            save_map(p, layer, meta)
            print(f"wrote    {p}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
