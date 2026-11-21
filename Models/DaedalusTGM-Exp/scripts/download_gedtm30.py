"""Mirror GEDTM30 (OpenGeoHub's global ensemble bare-earth DTM, 1 arc-second, CC BY 4.0) as 1-degree tiles
aligned with the GLO-30 tiles of our regions (DaedalusTGM-Exp-2 plan, P1).

    python scripts/download_gedtm30.py --out /mnt/extrastorage/terrain-slm-data/raw/gedtm30 [--region R ...]

GEDTM30 removes the canopy and buildings GLO-30 (a surface model) keeps -- Borneo's forest, Venice's city --
so it is the candidate training base for EXP-2 (A/B against GLO-30 before switching). It ships as one global
Cloud-Optimized GeoTIFF; each tile here is a windowed HTTP read of [lon, lon+1) x [lat, lat+1), written as a
compressed float32 GeoTIFF named like the GLO-30 tile (Copernicus_DSM_..._DEM -> GEDTM30_...). Unlike GLO-30,
GEDTM30 is 1 arc-second in both axes at every latitude (3600 x 3600 per tile). Ocean is nodata; a tile with no
valid pixel is not written. Values are metres (the COG's scale tag is not applied; raw values match GLO-30).

Attribution (CC BY 4.0): Ho, Y.-F., Hengl, T., Parente, L. et al., "Global Ensemble Digital Terrain Model 30m
(GEDTM30)", OpenGeoHub, doi:10.5281/zenodo.14900181; technical paper doi:10.7717/peerj.19673.
"""
from __future__ import annotations

import argparse
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

from terrain_slm.data.glo30 import REGIONS

COG = "/vsicurl/https://s3.opengeohub.org/global/dtm/v1.2/gedtm_rf_m_30m_s_20060101_20151231_go_epsg.4326.3855_v1.2.tif"
TILE = 3600


def tile_name(lat: int, lon: int) -> str:
    ns = "N" if lat >= 0 else "S"
    ew = "E" if lon >= 0 else "W"
    return f"GEDTM30_{ns}{abs(lat):02d}_{ew}{abs(lon):03d}"


def fetch(lat: int, lon: int, out_dir: Path, retries: int = 4) -> str:
    import rasterio
    from rasterio.transform import from_origin
    from rasterio.windows import Window

    out = out_dir / f"{tile_name(lat, lon)}.tif"
    if out.exists() and out.stat().st_size > 0:
        return "cached"
    for attempt in range(retries):
        try:
            with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif",
                              GDAL_HTTP_MAX_RETRY="4", GDAL_HTTP_RETRY_DELAY="2"):
                with rasterio.open(COG) as src:
                    col, row = ~src.transform * (lon, lat + 1)          # north-west corner of the tile
                    win = Window(int(round(col)), int(round(row)), TILE, TILE)
                    z = src.read(1, window=win, boundless=True, fill_value=src.nodata).astype(np.float32)
                    nodata = src.nodata
            valid = z < 1e30
            if not valid.any():
                return "empty (ocean)"
            z[~valid] = np.float32(-32768.0)
            tmp = out.with_suffix(".part.tif")
            with rasterio.open(tmp, "w", driver="GTiff", width=TILE, height=TILE, count=1, dtype="float32",
                               crs="EPSG:4326", transform=from_origin(lon, lat + 1, 1 / TILE, 1 / TILE),
                               nodata=-32768.0, compress="deflate", predictor=3, tiled=True,
                               blockxsize=512, blockysize=512) as dst:
                dst.write(z, 1)
            tmp.rename(out)
            return f"ok ({valid.mean():.0%} land)"
        except Exception as e:  # noqa: BLE001 - network hiccups: retry, then report
            if attempt == retries - 1:
                return f"FAILED {type(e).__name__}: {e}"
            time.sleep(3.0 * (attempt + 1))
    return "FAILED"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--region", action="append", help="region name(s); default: all")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    names = args.region or list(REGIONS)
    tiles = sorted({t for n in names for t in REGIONS[n].tiles()})
    t0 = time.time()
    with ThreadPoolExecutor(args.workers) as pool:
        for i, ((lat, lon), status) in enumerate(zip(tiles, pool.map(lambda t: fetch(*t, args.out), tiles))):
            print(f"[{i + 1}/{len(tiles)} {time.time() - t0:6.0f}s] {tile_name(lat, lon)}: {status}", flush=True)


if __name__ == "__main__":
    main()
