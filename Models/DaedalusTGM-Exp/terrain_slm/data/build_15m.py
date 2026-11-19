"""15 m truth for SR-15 (1:1 worlds, plan §2): USGS 3DEP 1/3 arc-second tiles resampled to a 15 m square grid.

    python -m terrain_slm.data.build_15m --raw /mnt/extrastorage/terrain-slm-data/raw/3dep \
                                         --out /mnt/extrastorage/terrain-slm-data/v2-15m

One directory per 1-degree tile, in the layout train_sr.SRData reads: dem.npy (float32 metres, ~15 m square pixels,
row 0 = north), sea.npy (uint8: nodata / sea), meta.json. The tiles of each region's validation corner (the
download script fetches val tiles first) are held out WHOLE (`val_whole`), the rest train.
Source: U.S. Geological Survey 3D Elevation Program (public domain).
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np

from terrain_slm.data.glo30 import REGIONS

TARGET_M = 15.0
ARCSEC_M = 30.87 / 1.0      # metres per arc-second of latitude (~30.87 m)


def resample(path: Path) -> tuple[np.ndarray, np.ndarray, float]:
    import rasterio
    from scipy import ndimage

    with rasterio.open(path) as src:
        z = src.read(1).astype(np.float32)
        nodata = src.nodata
        lat_top = src.bounds.top
        lat_bot = src.bounds.bottom
        res_deg = src.res[0]
    valid = np.isfinite(z) & ((z != nodata) if nodata is not None else True) & (z > -1000)
    if not valid.all():
        idx = ndimage.distance_transform_edt(~valid, return_distances=False, return_indices=True)
        z = z[tuple(idx)]
    lat_c = 0.5 * (lat_top + lat_bot)
    dy = res_deg * 3600 * ARCSEC_M
    dx = dy * math.cos(math.radians(lat_c))
    fy, fx = dy / TARGET_M, dx / TARGET_M
    z = ndimage.gaussian_filter(z, (max(0.0, 0.5 * (1 / fy - 1)), max(0.0, 0.5 * (1 / fx - 1))), mode="nearest")
    out = ndimage.zoom(z, (fy, fx), order=1, mode="nearest")
    sea = ndimage.zoom((~valid).astype(np.uint8), (fy, fx), order=0, mode="nearest")
    h, w = out.shape[0] // 8 * 8, out.shape[1] // 8 * 8
    return out[:h, :w].astype(np.float32), sea[:h, :w], lat_c


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    val_names = set()
    for r in REGIONS.values():
        for la, lo in r.val_tiles:
            val_names.add(f"USGS_13_{'n' if la + 1 >= 0 else 's'}{abs(la + 1):02d}{'w' if lo < 0 else 'e'}{abs(lo):03d}")
    for path in sorted(args.raw.glob("USGS_13_*.tif")):
        d = args.out / path.stem
        if (d / "meta.json").exists():
            continue
        d.mkdir(parents=True, exist_ok=True)
        dem, sea, lat_c = resample(path)
        np.save(d / "dem.npy", dem)
        np.save(d / "sea.npy", sea)
        meta = {"region": path.stem, "pixel_m": TARGET_M, "lat_c": lat_c, "shape_px": list(dem.shape),
                "val_whole": path.stem in val_names, "val_tiles": [], "tile_px": list(dem.shape), "lat": [0, 1],
                "lon": [0, 1], "source": "USGS 3DEP 1/3 arc-second (public domain)"}
        (d / "meta.json").write_text(json.dumps(meta, indent=1))
        print(f"{path.stem}: {dem.shape} {'VAL' if meta['val_whole'] else 'train'}", flush=True)


if __name__ == "__main__":
    main()
