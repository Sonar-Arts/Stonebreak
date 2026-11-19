"""Data v2 region build (DaedalusTGM-Exp-2 plan, P1): topo-bathy mosaic + geometry cells, from licence-safe sources.

    python -m terrain_slm.data.build_v2 --data /mnt/extrastorage/terrain-slm-data --base gedtm30 --region aegean

Writes <data>/v2-<base>/<region>/ (v4's <data>/<region>/ is left alone):
  dem.npy        float32 (H, W) metres, row 0 = north, ~30 m square pixels: land from the base DEM, sea floor from
                 GEBCO (topobathy.py). Unlike v1 the sea is not a flat 0.
  sea.npy        uint8 (H, W): 1 = open sea (water connected to the frame's edge), 0 = land / lakes. Narrow fjords
                 GEBCO cannot resolve get the shore's slope carried down (topobathy.slope_continuation)
  cells_geo.npz  per 8x8 cell: coarse (cell-mean elevation), desc (8 descriptors), sea (sea fraction)
  meta.json      region, grid, sources and their attribution

Climate (cells_climate.npz) and drainage (cells_hydro.npz) are separate steps: climate comes from a licence-safe
source (ERA5 family) instead of WorldClim, and drainage is precipitation-weighted, so it follows climate.

Bases: `gedtm30` (bare-earth DTM, CC BY 4.0; canopy and buildings removed) or `glo30` (Copernicus surface model,
v4's base). Either way GEDTM30 supplies the sea mask, because its ocean is nodata.
"""
from __future__ import annotations

import argparse
import json
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

from terrain_slm.data import glo30
from terrain_slm.data import topobathy as TB
from terrain_slm.data.descriptors import CELL_PX
from terrain_slm.device import default_device

GEBCO = "raw/gebco/GEBCO_2024.tif"
GEDTM_DIR = "raw/gedtm30"
GLO_DIR = "raw/glo30"
GEDTM_NODATA = -32768.0

ATTRIBUTION = {
    "gedtm30": "GEDTM30 v1.2, OpenGeoHub, CC BY 4.0, doi:10.5281/zenodo.14900181",
    "glo30": "Produced using Copernicus WorldDEM-30 (c) DLR e.V. 2010-2014 and (c) Airbus Defence and Space GmbH "
             "2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved",
    "gebco": "GEBCO Compilation Group (2024) GEBCO 2024 Grid, doi:10.5285/1c44ce99-0a0d-5f4f-e063-7086abc0ea0f "
             "(public domain)",
}


def gedtm_name(lat: int, lon: int) -> str:
    return f"GEDTM30_{'N' if lat >= 0 else 'S'}{abs(lat):02d}_{'E' if lon >= 0 else 'W'}{abs(lon):03d}.tif"


def _read_gedtm(path: Path, width: int) -> tuple[np.ndarray, np.ndarray]:
    """(metres, valid) resampled to `width` columns. Nodata is filled from the nearest land before the
    resample (so the coast is not dragged toward a fill value) and the valid mask is resampled nearest."""
    import rasterio
    from scipy import ndimage

    with rasterio.open(path) as src:
        z = src.read(1).astype(np.float32)
    # Water is nodata (open ocean) OR exactly 0.0: GEDTM30 (like GLO-30) flattens fjords and other sea-level water to
    # 0.0 instead of blanking them, and its fitted terrain never lands on exactly 0.0 (checked: Skjerstadfjorden).
    valid = (z > GEDTM_NODATA + 1) & (z != 0.0)
    if not valid.all():
        if valid.any():
            idx = ndimage.distance_transform_edt(~valid, return_distances=False, return_indices=True)
            z = z[tuple(idx)]
        else:
            z[:] = 0.0
    factor = width / z.shape[1]
    sigma = max(0.0, 0.5 * (1.0 / factor - 1.0))
    if sigma > 0:
        z = ndimage.gaussian_filter1d(z, sigma, axis=1, mode="nearest")
    z = ndimage.zoom(z, (glo30.TILE_PX / z.shape[0], factor), order=1, mode="nearest")[:, :width]
    v = ndimage.zoom(valid.astype(np.uint8), (glo30.TILE_PX / valid.shape[0], factor), order=0, mode="nearest")[:, :width]
    return z.astype(np.float32), v.astype(bool)


def _gebco_tile(gebco: Path, lat: int, lon: int, width: int) -> np.ndarray:
    """GEBCO bilinear on the tile's 30 m grid (TILE_PX rows x width columns)."""
    import rasterio
    from rasterio.windows import from_bounds

    m = 0.05
    with rasterio.open(gebco) as src:
        win = from_bounds(lon - m, lat - m, lon + 1 + m, lat + 1 + m, src.transform).round_offsets().round_lengths()
        a = src.read(1, window=win, boundless=True, fill_value=0).astype(np.float32)
        tf = src.window_transform(win)
    rows = lat + 1 - (np.arange(glo30.TILE_PX) + 0.5) / glo30.TILE_PX
    cols = lon + (np.arange(width) + 0.5) / width
    return TB.bilinear_to_grid(a, tf.f, tf.c, tf.a, rows, cols)


def _tile_job(args):
    """Pass 1 for one tile: land value (base, else GLO-30 water surface, else bathymetry surface), bathymetry and
    the raw sea flag (no land DEM + bathymetry at or below SEA_BATHY_MAX_M), into the region memmaps."""
    data, base, lat, lon, row0, col0, width, shapes, tmp = args
    data = Path(data)
    glo_p = data / GLO_DIR / f"{glo30.tile_name(lat, lon)}.tif"
    ged_p = data / GEDTM_DIR / gedtm_name(lat, lon)
    bathy = _gebco_tile(data / GEBCO, lat, lon, width)
    h = glo30.TILE_PX
    if glo_p.exists():
        glo = glo30.read_resampled(glo_p, width)
        glo_valid = np.ones_like(glo, bool)
    else:
        glo, glo_valid = np.zeros((h, width), np.float32), np.zeros((h, width), bool)
    if ged_p.exists():
        ged, ged_valid = _read_gedtm(ged_p, width)
    else:   # GEDTM30 wrote no tile: no land there (or, rarely, only GLO-30 has it)
        ged, ged_valid = np.zeros((h, width), np.float32), glo_valid & (glo != 0.0)
    if base == "gedtm30":
        base_m, base_valid, fb, fb_valid = ged, ged_valid, glo, glo_valid
    else:
        base_m, base_valid, fb, fb_valid = glo, glo_valid, None, None
    no_sea = np.zeros((h, width), bool)
    land, _ = TB.merge(base_m, base_valid, ged_valid, bathy, fb, fb_valid, sea=no_sea)
    water = ~ged_valid                      # sea or lake: decided region-wide in pass 2 (connectivity)
    for name, arr in (("land", land), ("bathy", bathy), ("water", water.astype(np.uint8))):
        dtype = np.uint8 if name == "water" else np.float32
        mm = np.lib.format.open_memmap(Path(tmp) / f"{name}.npy", mode="r+", dtype=dtype, shape=shapes)
        mm[row0 : row0 + h, col0 : col0 + width] = arr
        mm.flush()
    return f"{lat},{lon}: land {ged_valid.mean():.0%}, water {water.mean():.0%}"


def build(region: glo30.Region, data: Path, base: str, device: str, workers: int = 6) -> Path:
    out = data / f"v2-{base}" / region.name
    out.mkdir(parents=True, exist_ok=True)
    if (out / "meta.json").exists():
        print(f"{region.name}: already built ({out})", flush=True)
        return out
    shape = region.shape_px
    tmp = out / "tmp"
    tmp.mkdir(exist_ok=True)
    for name, dtype in (("land", np.float32), ("bathy", np.float32), ("water", np.uint8)):
        np.lib.format.open_memmap(tmp / f"{name}.npy", mode="w+", dtype=dtype, shape=shape)
    jobs = [(str(data), base, lat, lon, (region.lat1 - 1 - lat) * glo30.TILE_PX,
             (lon - region.lon0) * region.tile_width_px, region.tile_width_px, shape, str(tmp))
            for lat, lon in region.tiles()]
    print(f"[1/3] {region.name}: {len(jobs)} tiles, {shape} px, base {base}", flush=True)
    with ProcessPoolExecutor(workers) as pool:
        for msg in pool.map(_tile_job, jobs):
            print("   ", msg, flush=True)

    print("[2/3] sea (connectivity at 60 m) + slope continuation + merge", flush=True)
    ds = 2                                                   # 60 m grid for region-wide fields
    water = np.load(tmp / "water.npy", mmap_mode="r")
    land = np.load(tmp / "land.npy", mmap_mode="r")
    bathy = np.load(tmp / "bathy.npy", mmap_mode="r")
    w2 = np.ascontiguousarray(water[::ds, ::ds]) > 0
    b2 = np.ascontiguousarray(bathy[::ds, ::ds])
    sea2 = TB.sea_from_water(w2, b2)
    depth2, weight2 = TB.slope_continuation(sea2, np.ascontiguousarray(land[::ds, ::ds]), glo30.PIXEL_M * ds)
    del w2, b2
    depth_up = TB.upsample2(depth2, shape)            # bilinear: pixel repeats left 60 m steps in the floor
    weight_up = np.clip(TB.upsample2(weight2, shape), 0.0, 1.0)
    hc, wc = shape[0] // CELL_PX, shape[1] // CELL_PX
    dem = np.lib.format.open_memmap(out / "dem.partial.npy", mode="w+", dtype=np.float32, shape=shape)
    sea_out = np.lib.format.open_memmap(out / "sea.partial.npy", mode="w+", dtype=np.uint8, shape=shape)
    up = lambda a, r0, r1: np.repeat(np.repeat(a[r0 // ds : -(-r1 // ds)], ds, 0), ds, 1)[: r1 - r0 - 0, : shape[1]]
    step = CELL_PX * 64
    for r0 in range(0, shape[0], step):
        r1 = min(shape[0], r0 + step)
        sea = (np.asarray(water[r0:r1]) > 0) & up(sea2, r0, r1)
        floor = TB.sea_floor(np.asarray(bathy[r0:r1]), depth_up[r0:r1], weight_up[r0:r1])
        dem[r0:r1] = np.where(sea, floor, np.asarray(land[r0:r1]))
        sea_out[r0:r1] = sea
    dem.flush()
    sea_out.flush()
    del dem, sea_out, land, bathy, water, sea2, depth2, weight2
    (out / "dem.partial.npy").rename(out / "dem.npy")
    (out / "sea.partial.npy").rename(out / "sea.npy")
    for f in tmp.iterdir():
        f.unlink()
    tmp.rmdir()

    print("[3/3] coarse + descriptors", flush=True)
    import torch
    from terrain_slm.data.descriptors import extract_tiled

    dem = np.load(out / "dem.npy", mmap_mode="r")
    sea_px = np.load(out / "sea.npy", mmap_mode="r")
    coarse = np.empty((hc, wc), np.float32)
    sea_frac = np.empty((hc, wc), np.float32)
    for r0 in range(0, hc, 256):
        blk = np.asarray(dem[r0 * CELL_PX : (r0 + 256) * CELL_PX, : wc * CELL_PX])
        coarse[r0 : r0 + blk.shape[0] // CELL_PX] = blk.reshape(-1, CELL_PX, wc, CELL_PX).mean(axis=(1, 3))
        sb = np.asarray(sea_px[r0 * CELL_PX : (r0 + 256) * CELL_PX, : wc * CELL_PX], dtype=np.float32)
        sea_frac[r0 : r0 + sb.shape[0] // CELL_PX] = sb.reshape(-1, CELL_PX, wc, CELL_PX).mean(axis=(1, 3))
    with torch.no_grad():
        desc = extract_tiled(dem, device=device).numpy()
    np.savez(out / "cells_geo.npz", coarse=coarse, desc=desc, sea=sea_frac)
    meta = {
        "region": region.name, "data_version": 2, "base": base,
        "lat": [region.lat0, region.lat1], "lon": [region.lon0, region.lon1],
        "shape_px": list(shape), "shape_cells": [hc, wc], "pixel_m": glo30.PIXEL_M, "x_scale": region.x_scale,
        "cell_px": CELL_PX, "tile_px": [glo30.TILE_PX, region.tile_width_px],
        "val_tiles": [list(t) for t in region.val_tiles], "note": region.note,
        "sea_share": float(sea_frac.mean()),
        "sources": {k: ATTRIBUTION[k] for k in (base, "gedtm30", "gebco")},
        "rules": {"sea_max_m": TB.SEA_MAX_M, "sea_bathy_max_m": TB.SEA_BATHY_MAX_M,
                  "slope_continue_m": TB.SLOPE_CONTINUE_M, "slope_max": TB.SLOPE_MAX,
                  "sea": "GEDTM30 water in a body holding GEBCO <= sea_bathy_max_m that reaches the frame edge",
                  "floor": "GEBCO (<= sea_max_m) blended toward min(GEBCO, -shore_slope * dist) where the water's "
                           "half-width < slope_continue_m (topobathy.sea_floor)"},
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2))
    print(f"done: {out} (sea {meta['sea_share']:.1%})", flush=True)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--base", choices=("gedtm30", "glo30"), default="gedtm30")
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--region", action="append", help="region name(s); default: all")
    args = ap.parse_args()
    for name in args.region or sorted(glo30.REGIONS):
        build(glo30.REGIONS[name], args.data, args.base, args.device, args.workers)


if __name__ == "__main__":
    main()
