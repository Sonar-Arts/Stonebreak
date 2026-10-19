"""Build a training region: GLO-30 tiles -> square-pixel mosaic + per-cell fields.

Outputs in <data>/<region>/:
  dem.npy     float32 (H, W) metres, row 0 = north, ~30 m square pixels (memmap)
  cells.npz   per 8x8-pixel cell (H/8, W/8):
                coarse   cell-mean elevation, m
                desc     (8, ...) descriptors (descriptors.py layout)
                t0       sea-level mean annual temperature, degC (WorldClim bio1 + lapse)
                tseason  temperature seasonality, std*100 (bio4)
                precip   annual precipitation, mm (bio12)
                pcv      precipitation CV, % (bio15)
                acc      D8 flow accumulation weighted by precipitation: upslope cells at
                         1000 mm/yr equivalent (runoff proxy), so deserts drain little
                d8       0..7 downstream direction, 8 = terminal (ocean / off-edge)
  meta.json   region, pixel size, val tiles
"""
from __future__ import annotations

import argparse
import json
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

from terrain_slm.data import glo30
from terrain_slm.data.descriptors import CELL_PX
from terrain_slm.paths import REPO_DIR
from terrain_slm.device import default_device

WORLDCLIM_DIR = REPO_DIR / "Dev Working/terrain-diffusion-spike/repo/data/global"
LAPSE_C_PER_M = 0.0065


# D8 direction classes, (drow, dcol) -> class.
D8_OFFSETS = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))


def _place_tile(args):
    path, row0, col0, width, dem_path, shape = args
    z = glo30.read_resampled(Path(path), width)
    dem = np.lib.format.open_memmap(dem_path, mode="r+", dtype=np.float32, shape=shape)
    dem[row0 : row0 + z.shape[0], col0 : col0 + z.shape[1]] = z
    dem.flush()
    return path


def build_mosaic(region: glo30.Region, raw_dir: Path, out_dir: Path, workers: int = 4) -> Path:
    shape = region.shape_px
    dem_path = out_dir / "dem.npy"
    if dem_path.exists():
        return dem_path
    tmp = out_dir / "dem.partial.npy"
    np.lib.format.open_memmap(tmp, mode="w+", dtype=np.float32, shape=shape)
    jobs = []
    for lat, lon in region.tiles():
        p = raw_dir / f"{glo30.tile_name(lat, lon)}.tif"
        if not p.exists():
            continue  # absent ocean tile: stays 0 (sea level)
        row0 = (region.lat1 - 1 - lat) * glo30.TILE_PX
        col0 = (lon - region.lon0) * region.tile_width_px
        jobs.append((str(p), row0, col0, region.tile_width_px, str(tmp), shape))
    with ProcessPoolExecutor(workers) as pool:
        for done in pool.map(_place_tile, jobs):
            print(f"  placed {Path(done).name}", flush=True)
    tmp.rename(dem_path)
    return dem_path


def _cell_latlon(region: glo30.Region, hc: int, wc: int):
    rows = (np.arange(hc) + 0.5) * CELL_PX / glo30.TILE_PX
    cols = (np.arange(wc) + 0.5) * CELL_PX / region.tile_width_px
    return region.lat1 - rows, region.lon0 + cols


def _sample_raster(path: Path, lat: np.ndarray, lon: np.ndarray) -> np.ndarray:
    """Bilinear sample of a global lat/lon raster at the outer product of lat x lon."""
    import rasterio
    from scipy import ndimage

    with rasterio.open(path) as src:
        a = src.read(1).astype(np.float32)
        nodata = src.nodata
        inv = ~src.transform
    if nodata is not None:
        a[a == nodata] = np.nan
    cc, rr = inv * (lon[None, :].repeat(len(lat), 0), lat[:, None].repeat(len(lon), 1))
    # Fill NaNs (ocean in WorldClim) from the nearest valid pixel before interpolating.
    if np.isnan(a).any():
        idx = ndimage.distance_transform_edt(np.isnan(a), return_distances=False, return_indices=True)
        a = a[tuple(idx)]
    return ndimage.map_coordinates(a, [rr - 0.5, cc - 0.5], order=1, mode="nearest").astype(np.float32)


def build_climate(region, hc, wc) -> dict[str, np.ndarray]:
    lat, lon = _cell_latlon(region, hc, wc)
    bio = {k: _sample_raster(WORLDCLIM_DIR / f"wc2.1_10m_bio_{n}.tif", lat, lon) for k, n in
           (("t", 1), ("tseason", 4), ("precip", 12), ("pcv", 15))}
    ref_elev = np.maximum(_sample_raster(WORLDCLIM_DIR / "etopo_10m.tif", lat, lon), 0.0)
    return {
        "t0": bio["t"] + LAPSE_C_PER_M * ref_elev,
        "tseason": bio["tseason"],
        "precip": bio["precip"],
        "pcv": bio["pcv"],
    }


def build_hydrology(coarse: np.ndarray, precip_mm: np.ndarray | None = None) -> tuple[np.ndarray, np.ndarray]:
    from terrain_slm.data.hydrology.fill import fill_depressions, ocean_mask
    from terrain_slm.data.hydrology.flow import d8_receivers, flow_accumulation

    invalid = ocean_mask(coarse.astype(np.float64))
    filled = fill_depressions(coarse, invalid=invalid)
    recv = d8_receivers(filled, invalid)
    weights = None if precip_mm is None else np.clip(precip_mm, 0.0, None) / 1000.0
    acc = flow_accumulation(recv, ~invalid, weights=weights).astype(np.float32)

    h, w = coarse.shape
    idx = np.arange(h * w)
    d8 = np.full(h * w, 8, dtype=np.uint8)
    live = recv >= 0
    dr = recv[live] // w - idx[live] // w
    dc = recv[live] % w - idx[live] % w
    for k, (a, b) in enumerate(D8_OFFSETS):
        sel = np.flatnonzero(live)[(dr == a) & (dc == b)]
        d8[sel] = k
    return acc, d8.reshape(h, w)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--region", default="alps", choices=sorted(glo30.REGIONS))
    args = ap.parse_args()
    region = glo30.REGIONS[args.region]
    out = args.data / region.name
    out.mkdir(parents=True, exist_ok=True)

    print(f"[1/4] mosaic {region.shape_px} px", flush=True)
    dem_path = build_mosaic(region, args.data / "raw/glo30", out)
    dem = np.load(dem_path, mmap_mode="r")
    hc, wc = dem.shape[0] // CELL_PX, dem.shape[1] // CELL_PX

    print("[2/4] coarse + descriptors", flush=True)
    import torch
    from terrain_slm.data.descriptors import extract_tiled

    coarse = np.empty((hc, wc), np.float32)
    for r0 in range(0, hc, 256):
        blk = np.asarray(dem[r0 * CELL_PX : (r0 + 256) * CELL_PX])
        coarse[r0 : r0 + blk.shape[0] // CELL_PX] = blk.reshape(-1, CELL_PX, wc, CELL_PX).mean(axis=(1, 3))
    with torch.no_grad():
        desc = extract_tiled(dem, device=args.device).numpy()

    print("[3/4] climate", flush=True)
    climate = build_climate(region, hc, wc)

    print("[4/4] hydrology", flush=True)
    acc, d8 = build_hydrology(coarse, climate["precip"])

    np.savez(out / "cells.npz", coarse=coarse, desc=desc, acc=acc, d8=d8, **climate)
    meta = {
        "region": region.name,
        "lat": [region.lat0, region.lat1],
        "lon": [region.lon0, region.lon1],
        "shape_px": list(dem.shape),
        "shape_cells": [hc, wc],
        "pixel_m": glo30.PIXEL_M,
        "x_scale": region.x_scale,
        "cell_px": CELL_PX,
        "tile_px": [glo30.TILE_PX, region.tile_width_px],
        "val_tiles": [list(t) for t in region.val_tiles],
        "note": region.note,
        "acc_units": "upslope cells x precip/1000mm",
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2))
    print("done:", out, flush=True)


if __name__ == "__main__":
    main()
