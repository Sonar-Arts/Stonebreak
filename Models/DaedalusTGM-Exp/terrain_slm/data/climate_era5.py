"""ERA5 climate for data v2 (DaedalusTGM-Exp-2 plan §5b): replaces WorldClim (non-commercial) with Copernicus ERA5
(CC BY 4.0), and adds the fields the climate node needs.

    python -m terrain_slm.data.climate_era5 climatology --raw /mnt/extrastorage/terrain-slm-data/raw/era5
    python -m terrain_slm.data.climate_era5 cells --data /mnt/extrastorage/terrain-slm-data --root v2-gedtm30 [--region R]

`climatology` reduces the 30 yearly files (scripts/download_era5.py, 1991-2020 monthly means, 0.25 deg global) to one
`climatology.npz`; `cells` samples it at every cell of a data-v2 region into `cells_climate.npz`:

  t0        sea-level-equivalent mean annual temperature, degC (2 m temperature + lapse x ERA5 orography;
            v4's definition, which used WorldClim + ETOPO)
  tseason   temperature seasonality: std of the 12 monthly means x 100 (WorldClim bio4's definition)
  precip    annual precipitation, mm
  pcv       precipitation seasonality: CV of the 12 monthly totals, % (bio15's definition)
  aridity   P / PET (potential evaporation), the UNEP aridity index: < 0.05 hyper-arid ... > 0.65 humid
  soil      top-layer volumetric soil water, m3/m3 (wetness: swamps, marshes)
  snow      mean snow depth, m water equivalent (glaciation / snow-cover tendency)
  u10, v10  mean 10 m wind, m/s (prevailing wind: windward / leeward slopes, rain shadows)

Sampling is bilinear at cell centres; ERA5 covers the sea too, so no ocean fill is needed. Names t0/tseason/precip/pcv
match v4's cells.npz, so the existing trainers read v2 regions unchanged.
"""
from __future__ import annotations

import argparse
import calendar
from pathlib import Path

import numpy as np

LAPSE_C_PER_M = 0.0065
G0 = 9.80665
VAR_KEYS = {"t2m": "2m_temperature", "tp": "total_precipitation", "u10": "10m_u", "v10": "10m_v",
            "pev": "potential_evaporation", "sd": "snow_depth", "swvl1": "volumetric_soil_water_layer_1"}


def _read_nc(path: Path, var: str) -> np.ndarray:
    """(bands, 721, 1440) float32 of `var` from a CDS NetCDF via GDAL, scale/offset applied; rows north -> south,
    columns from longitude 0 east (ERA5's native order)."""
    import rasterio

    with rasterio.open(f'NETCDF:"{path}":{var}') as src:
        a = src.read().astype(np.float32)
        scale = np.asarray(src.scales, np.float32)[:, None, None]
        offset = np.asarray(src.offsets, np.float32)[:, None, None]
        nodata = src.nodata
    if nodata is not None:
        a[a == nodata] = np.nan
    return a * scale + offset


def _year_files(raw: Path, first: int, last: int) -> list[tuple[Path, int]]:
    """(file, number of years) covering first..last: 5-year chunk files (era5_monthly_1991-1995.nc) and/or single
    years (era5_monthly_1991.nc), each year exactly once."""
    out, y = [], first
    while y <= last:
        chunks = sorted(raw.glob(f"era5_monthly_{y}-*.nc"))
        if chunks:
            y1 = int(chunks[0].stem.split("-")[-1])
            out.append((chunks[0], y1 - y + 1))
            y = y1 + 1
        else:
            f = raw / f"era5_monthly_{y}.nc"
            if not f.exists():
                raise FileNotFoundError(f"no ERA5 file covers {y} in {raw}")
            out.append((f, 1))
            y += 1
    return out


def climatology(raw: Path, first: int = 1991, last: int = 2020) -> Path:
    files = _year_files(raw, first, last)
    n_years = sum(n for _, n in files)
    sums = {}
    for f, n in files:
        for v in VAR_KEYS:
            a = _read_nc(f, v)
            if a.shape[0] != 12 * n:
                raise ValueError(f"{f.name}:{v} has {a.shape[0]} bands, expected {12 * n}")
            # bands are in time order (year-major): fold the years onto their months
            sums[v] = sums.get(v, 0.0) + a.reshape(n, 12, *a.shape[1:]).sum(0)
        print(f"  {f.name}", flush=True)
    mean = {v: s / n_years for v, s in sums.items()}                     # (12, 721, 1440) monthly climatology
    days = np.array([calendar.monthrange(2001, m)[1] for m in range(1, 13)], np.float32)[:, None, None]
    t_month = mean["t2m"] - 273.15
    p_month = np.clip(mean["tp"], 0, None) * 1000.0 * days               # m/day -> mm/month
    pet_month = np.clip(-mean["pev"], 0, None) * 1000.0 * days           # pev < 0 is evaporation
    inv = raw / "era5_monthly_invariant.nc"
    lsm = _read_nc(inv, "lsm")[0]
    orog = _read_nc(inv, "z")[0] / G0
    precip = p_month.sum(0)
    pet = pet_month.sum(0)
    out = raw / "climatology.npz"
    np.savez_compressed(
        out,
        t_mean=t_month.mean(0), t_month=t_month, tseason=t_month.std(0) * 100.0,
        p_month=p_month, precip=precip, pcv=p_month.std(0) / np.maximum(p_month.mean(0), 1e-3) * 100.0,
        pet=pet, aridity=precip / np.maximum(pet, 1.0),
        soil=mean["swvl1"].mean(0), snow=mean["sd"].mean(0),
        u10=mean["u10"].mean(0), v10=mean["v10"].mean(0),
        lsm=lsm, orog=orog, years=np.array([first, last]))
    print(f"climatology -> {out}", flush=True)
    return out


def sample(field: np.ndarray, lat: np.ndarray, lon: np.ndarray) -> np.ndarray:
    """Bilinear sample of a 0.25-deg ERA5 grid (721 rows from +90 to -90, 1440 cols from 0 east) at the outer
    product lat (rows) x lon (cols); longitude wraps."""
    from scipy import ndimage

    rr = (90.0 - lat) / 0.25
    cc = np.mod(lon, 360.0) / 0.25
    rows = np.broadcast_to(rr[:, None], (len(lat), len(lon)))
    cols = np.broadcast_to(cc[None, :], (len(lat), len(lon)))
    wrapped = np.concatenate([field, field[:, :1]], axis=1)              # column 1440 = column 0
    return ndimage.map_coordinates(wrapped.astype(np.float32), [rows, cols], order=1, mode="nearest").astype(np.float32)


def region_cells(clim: dict, lat: np.ndarray, lon: np.ndarray) -> dict[str, np.ndarray]:
    s = lambda k: sample(clim[k], lat, lon)
    return {
        "t0": s("t_mean") + LAPSE_C_PER_M * np.maximum(s("orog"), 0.0),
        "tseason": s("tseason"), "precip": s("precip"), "pcv": s("pcv"), "aridity": s("aridity"),
        "soil": s("soil"), "snow": s("snow"), "u10": s("u10"), "v10": s("v10"),
    }


def build_cells(data: Path, root: str, regions: list[str] | None) -> None:
    from terrain_slm.data import glo30
    from terrain_slm.data.build import _cell_latlon

    clim = dict(np.load(data / "raw/era5/climatology.npz"))
    for name in regions or sorted(glo30.REGIONS):
        rdir = data / root / name
        if not (rdir / "cells_geo.npz").exists():
            print(f"{name}: no cells_geo.npz yet, skipped", flush=True)
            continue
        hc, wc = np.load(rdir / "cells_geo.npz")["coarse"].shape
        lat, lon = _cell_latlon(glo30.REGIONS[name], hc, wc)
        np.savez(rdir / "cells_climate.npz", **region_cells(clim, lat, lon))
        print(f"{name}: cells_climate.npz ({hc}x{wc})", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("climatology")
    a.add_argument("--raw", type=Path, required=True)
    b = sub.add_parser("cells")
    b.add_argument("--data", type=Path, required=True)
    b.add_argument("--root", default="v2-gedtm30")
    b.add_argument("--region", action="append")
    args = ap.parse_args()
    if args.cmd == "climatology":
        climatology(args.raw)
    else:
        build_cells(args.data, args.root, args.region)


if __name__ == "__main__":
    main()
