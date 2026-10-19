"""Download every GLO-30 tile of a region (default: the Alps POC slice)."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from terrain_slm.data.glo30 import REGIONS, download_tile


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("data/raw/glo30"))
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--region", action="append", help="region name(s); default: all")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    names = args.region or list(REGIONS)
    tiles = [t for n in names for t in REGIONS[n].tiles()]
    with ThreadPoolExecutor(args.workers) as pool:
        for (lat, lon), path in zip(tiles, pool.map(lambda t: download_tile(*t, args.out), tiles)):
            print(f"N{lat} E{lon:03d}: {path.name if path else 'absent (ocean)'}", flush=True)


if __name__ == "__main__":
    main()
