"""Download ERA5 monthly means for the climate node (DaedalusTGM-Exp-2 plan §5b), one NetCDF per year.

    uv run --no-project --with cdsapi python scripts/download_era5.py --out /mnt/extrastorage/terrain-slm-data/raw/era5

Needs a Copernicus CDS token in ~/.cdsapirc and the ERA5 licence accepted on the dataset page. ERA5 is Copernicus
Climate Change Service data, CC BY 4.0 (since 2025-07-02). Cite: Hersbach et al. (2023), ERA5 monthly averaged
data on single levels from 1940 to present, C3S CDS, doi:10.24381/cds.f17050d7. WorldClim (v4's climate source)
is non-commercial, so EXP-2 trains on this instead.

Variables (0.25 deg, global, monthly averaged reanalysis 1991-2020): 2 m temperature, total precipitation,
10 m wind u/v (prevailing wind -> rain shadows), potential evaporation (aridity = P / PET), snow depth (water
equivalent) and top-layer soil moisture (wetness, swamps); plus the land-sea mask and orography once.
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

DATASET = "reanalysis-era5-single-levels-monthly-means"
VARIABLES = ["2m_temperature", "total_precipitation", "10m_u_component_of_wind", "10m_v_component_of_wind",
             "potential_evaporation", "snow_depth", "volumetric_soil_water_layer_1"]
INVARIANT = ["land_sea_mask", "geopotential"]


def retrieve(c, name: str, variables, years, months, out: Path) -> bool:
    target = out / f"era5_monthly_{name}.nc"
    if target.exists() and target.stat().st_size > 0:
        print(f"{name}: cached", flush=True)
        return True
    t0 = time.time()
    req = {"product_type": ["monthly_averaged_reanalysis"], "variable": variables, "year": years,
           "month": months, "time": ["00:00"], "data_format": "netcdf", "download_format": "unarchived"}
    tmp = target.with_suffix(".part")
    c.retrieve(DATASET, req, str(tmp))
    tmp.rename(target)
    print(f"{name}: {target.stat().st_size / 2**20:.0f} MiB in {time.time() - t0:.0f} s", flush=True)
    return True


def main() -> None:
    import cdsapi

    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--first", type=int, default=1991)
    ap.add_argument("--last", type=int, default=2020)
    ap.add_argument("--chunk", type=int, default=5, help="years per request: one CDS queue wait per chunk, not per "
                    "year (CDS allows one queued request per user for this dataset)")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    c = cdsapi.Client()
    months = [f"{m:02d}" for m in range(1, 13)]
    retrieve(c, "invariant", INVARIANT, ["2020"], ["01"], args.out)
    for y0 in range(args.first, args.last + 1, args.chunk):
        y1 = min(args.last, y0 + args.chunk - 1)
        years = [str(y) for y in range(y0, y1 + 1)]
        if all((args.out / f"era5_monthly_{y}.nc").exists() for y in years):
            print(f"{y0}-{y1}: cached as single years", flush=True)
            continue
        try:
            retrieve(c, f"{y0}-{y1}", VARIABLES, years, months, args.out)
        except Exception as e:  # noqa: BLE001 - too large for one request: fall back to one year at a time
            if "queued requests" in str(e) or "licence" in str(e):
                raise
            print(f"{y0}-{y1}: chunk failed ({str(e)[:120]}); falling back to single years", flush=True)
            for y in years:
                retrieve(c, str(y), VARIABLES, [str(y)], months, args.out)


if __name__ == "__main__":
    main()
