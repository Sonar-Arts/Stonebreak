"""Finish a data-v2 region: precipitation-weighted drainage + the merged cells.npz the trainers read.

    python -m terrain_slm.data.finish_v2 --data /mnt/extrastorage/terrain-slm-data --root v2-gedtm30 [--region R]

Needs cells_geo.npz (build_v2) and cells_climate.npz (climate_era5 cells). Writes:
  cells_hydro.npz  acc (D8 accumulation weighted by precipitation / 1000 mm, as v4) and d8 (0..7, 8 terminal)
  cells.npz        coarse, desc, sea, acc, d8, t0, tseason, precip, pcv (+ aridity, soil, snow, u10, v10):
                   v4's keys plus the new ones, so planner_data / train_detail / train_detail2 load a v2 region
                   exactly like a v4 one (point --data-dir at <data>/<root>).
The ocean for routing is the real sea mask (sea fraction >= 0.5), not v4's "elevation <= 0": with bathymetry the
sea has depth, and land below sea level drains like land.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from terrain_slm.data import glo30
from terrain_slm.data.build import D8_OFFSETS


def hydrology(coarse: np.ndarray, sea: np.ndarray, precip_mm: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    from terrain_slm.data.hydrology.fill import fill_depressions
    from terrain_slm.data.hydrology.flow import d8_receivers, flow_accumulation

    invalid = sea >= 0.5
    filled = fill_depressions(coarse, invalid=invalid)
    recv = d8_receivers(filled, invalid)
    acc = flow_accumulation(recv, ~invalid, weights=np.clip(precip_mm, 0.0, None) / 1000.0).astype(np.float32)
    h, w = coarse.shape
    idx = np.arange(h * w)
    d8 = np.full(h * w, 8, dtype=np.uint8)
    live = recv >= 0
    dr = recv[live] // w - idx[live] // w
    dc = recv[live] % w - idx[live] % w
    for k, (a, b) in enumerate(D8_OFFSETS):
        d8[np.flatnonzero(live)[(dr == a) & (dc == b)]] = k
    return acc, d8.reshape(h, w)


def finish(rdir: Path) -> bool:
    if not (rdir / "cells_geo.npz").exists() or not (rdir / "cells_climate.npz").exists():
        print(f"{rdir.name}: needs cells_geo.npz and cells_climate.npz", flush=True)
        return False
    geo = dict(np.load(rdir / "cells_geo.npz"))
    clim = dict(np.load(rdir / "cells_climate.npz"))
    acc, d8 = hydrology(geo["coarse"], geo["sea"], clim["precip"])
    np.savez(rdir / "cells_hydro.npz", acc=acc, d8=d8)
    np.savez(rdir / "cells.npz", **geo, **clim, acc=acc, d8=d8)
    print(f"{rdir.name}: cells.npz ({geo['coarse'].shape}), land drains {float((acc > 0).mean()):.0%}", flush=True)
    return True


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--root", default="v2-gedtm30")
    ap.add_argument("--region", action="append")
    args = ap.parse_args()
    for name in args.region or sorted(glo30.REGIONS):
        finish(args.data / args.root / name)


if __name__ == "__main__":
    main()
