"""Mirror USGS 3DEP 1/3 arc-second DEM tiles (~10 m, largely lidar-derived, PUBLIC DOMAIN) for the regions of ours that
the US covers: the 15 m truth SR-15 (1:1 worlds) trains on (plan §2, §5). Tiles named by their NORTH-WEST corner
(USGS_13_n39w106 covers lat 38-39, lon -106..-105); ours are named by the south-west corner.

    python scripts/download_3dep.py --out /mnt/extrastorage/terrain-slm-data/raw/3dep [--per-region 6]

Credit: U.S. Geological Survey, 3D Elevation Program (public domain).
"""
from __future__ import annotations

import argparse
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

from terrain_slm.data.glo30 import REGIONS

US_REGIONS = ("rockies_colorado", "colorado_plateau", "ozarks", "outer_banks", "hawaii")
URL = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/{n}/USGS_13_{n}.tif"


def usgs_name(lat: int, lon: int) -> str:
    return f"{'n' if lat + 1 >= 0 else 's'}{abs(lat + 1):02d}{'w' if lon < 0 else 'e'}{abs(lon):03d}"


def fetch(lat: int, lon: int, out: Path) -> str:
    n = usgs_name(lat, lon)
    dst = out / f"USGS_13_{n}.tif"
    if dst.exists() and dst.stat().st_size > 0:
        return f"{n}: cached"
    tmp = dst.with_suffix(".part")
    for attempt in range(4):
        try:
            with requests.get(URL.format(n=n), stream=True, timeout=120) as r:
                if r.status_code == 404:
                    return f"{n}: absent"
                r.raise_for_status()
                with open(tmp, "wb") as f:
                    for chunk in r.iter_content(1 << 22):
                        f.write(chunk)
            tmp.rename(dst)
            return f"{n}: {dst.stat().st_size / 2**20:.0f} MiB"
        except requests.RequestException as e:
            if attempt == 3:
                return f"{n}: FAILED {e}"
            time.sleep(5 * (attempt + 1))
    return f"{n}: FAILED"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--per-region", type=int, default=6, help="tiles per region, val tiles first (0 = all)")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    tiles = []
    for name in US_REGIONS:
        r = REGIONS[name]
        order = list(r.val_tiles) + [t for t in r.tiles() if t not in r.val_tiles]
        tiles += order[: args.per_region] if args.per_region else order
    with ThreadPoolExecutor(args.workers) as pool:
        for msg in pool.map(lambda t: fetch(*t, args.out), tiles):
            print(msg, flush=True)


if __name__ == "__main__":
    main()
