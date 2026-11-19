"""Hybrid climate for data v2 (DaedalusTGM-Exp-2 plan §5b, revised 2026-11-18): CHELSA v2.1 at 1 km for everything it
has, ERA5 only for what it lacks. Replaces the CDS-queued ERA5 path (data/climate_era5.py, kept for reference): no
queue, ~30x sharper climate where it matters (rain shadows, fjord heads, valley floors).

    uv run --no-project --with xarray --with zarr --with fsspec --with aiohttp --with requests --with numpy \\
        python -m terrain_slm.data.climate_hybrid era5 --raw /mnt/extrastorage/terrain-slm-data/raw
    python -m terrain_slm.data.climate_hybrid climatology --raw /mnt/extrastorage/terrain-slm-data/raw
    python -m terrain_slm.data.climate_hybrid cells --data /mnt/extrastorage/terrain-slm-data --root v2-gedtm30

Sources
  CHELSA v2.1 climatologies 1981-2010, 30" (raw/chelsa/CHELSA_<v>_1981-2010_V.2.1.tif; Karger et al. 2017, Sci. Data
  4:170122, doi:10.16904/envidat.228.v2.1; opendata.swiss terms: commercial use allowed): bio1 (mean annual T),
  bio4 (T seasonality, std x 100), bio12 (annual precip), bio15 (precip CV %), pet_penman_mean (mean monthly
  Penman PET, land only), swe (mean snow water equivalent, nodata = no snow). bio1/4/12/15 cover the ocean too.
  ERA5 via the WeatherBench2 hourly climatology 1990-2019 at 0.25 deg on Google Cloud (CC BY 4.0, Copernicus; no
  CDS queue): 10 m wind u/v (prevailing wind: rain shadows, dunes), top-layer soil water (wetness); land-sea mask
  and surface geopotential from the WB2 ERA5 store.

Outputs keep climate_era5's names and units, so train_climate and finish_v2 read them unchanged:
  raw/era5/era5_wb2.npz        u10, v10, soil, lsm, orog on the 0.25 deg ERA5 grid (721 x 1440, north first, lon 0 E)
  raw/era5/climatology.npz     t_mean (degC at the cell's mean surface, orog = that surface), tseason, precip,
                               pcv, pet, aridity, soil, snow (m w.e.), u10, v10, lsm, orog
  <region>/cells_climate.npz   t0, tseason, precip, pcv, aridity, soil, snow, u10, v10 per 240 m cell

Sea-level-equivalent temperature t0 = T + lapse x elevation, with the elevation the temperature refers to: the
0.25 deg cell's mean surface (GEBCO, sea = 0) for the climatology, the ~1 km mean of the region's own surface for
cells (CHELSA's resolution; sub-km relief is then put back by the lapse rate in game). Land-only fields (aridity)
reach the sea by nearest-land fill, so the coastal band the climate node is trained on stays defined.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

H, W = 721, 1440
LAPSE_C_PER_M = 0.0065
G0 = 9.80665
CHELSA_PX = 120                     # pixels per degree (30")
CHELSA_TOP = 84.0                   # CHELSA rows run 84 N -> 90 S
WB2 = "https://storage.googleapis.com/weatherbench2/datasets"
WB2_CLIM = f"{WB2}/era5-hourly-climatology/1990-2019_6h_1440x721.zarr"
WB2_ERA5 = f"{WB2}/era5/1959-2023_01_10-wb13-6h-1440x721_with_derived_variables.zarr"
CHELSA = {  # name -> (file variable, scale, offset, nodata means)
    "t": ("bio1", 0.1, -273.15, None), "tseason": ("bio4", 0.1, 0.0, None), "precip": ("bio12", 0.1, 0.0, None),
    "pcv": ("bio15", 0.1, 0.0, None), "pet": ("pet_penman_mean", 0.01 * 12, 0.0, "nan"),   # monthly mean -> annual
    "snow": ("swe", 0.1 / 1000.0, 0.0, "zero"),                                            # kg m-2 -> m w.e.
}


def chelsa_path(raw: Path, var: str) -> Path:
    return raw / "chelsa" / f"CHELSA_{var}_1981-2010_V.2.1.tif"


def _fill_nearest(a: np.ndarray) -> np.ndarray:
    from scipy import ndimage

    bad = ~np.isfinite(a)
    if not bad.any() or bad.all():
        return a
    idx = ndimage.distance_transform_edt(bad, return_distances=False, return_indices=True)
    return a[tuple(idx)]


# ---------------------------------------------------------------- ERA5 (WeatherBench2)
def era5_wb2(raw: Path, day_stride: int = 4) -> Path:
    """Annual means of u10, v10, soil water from the WB2 hourly climatology (every `day_stride`-th 3-day chunk, all
    four hours: ~31 days spread over the year of an already 30-year-averaged climatology) + the static fields."""
    import xarray as xr

    clim = xr.open_zarr(WB2_CLIM, consolidated=True, chunks=None)
    lat = clim["latitude"].values
    flip = lat[0] < lat[-1]                                       # WB2 stores south first; ERA5 native is north first
    out = {}
    for name, key in (("u10", "10m_u_component_of_wind"), ("v10", "10m_v_component_of_wind"),
                      ("soil", "volumetric_soil_water_layer_1")):
        da = clim[key]
        acc, n = np.zeros((H, W), np.float64), 0
        for d0 in range(0, da.sizes["dayofyear"], 3 * day_stride):
            a = da.isel(dayofyear=slice(d0, d0 + 3)).values.astype(np.float32)      # (hour, 3, lat, lon)
            acc += np.nan_to_num(a, nan=0.0).sum((0, 1))
            n += a.shape[0] * a.shape[1]
            if name == "soil":
                seen = np.isfinite(a).all((0, 1))
                out.setdefault("soil_valid", seen)
        f = (acc / n).astype(np.float32)
        if name == "soil":
            f[~out.pop("soil_valid")] = np.nan                       # sea / ice: no soil
        out[name] = f[::-1] if flip else f
        print(f"  {name}: {n} fields", flush=True)
    era = xr.open_zarr(WB2_ERA5, consolidated=True, chunks=None)
    for name, key in (("lsm", "land_sea_mask"), ("orog", "geopotential_at_surface")):
        da = era[key]
        for d in [d for d in da.dims if d not in ("latitude", "longitude")]:
            da = da.isel({d: 0})
        f = da.transpose("latitude", "longitude").values.astype(np.float32)
        if era["latitude"].values[0] < era["latitude"].values[-1]:
            f = f[::-1]
        out[name] = f / G0 if name == "orog" else f
    out["soil"] = _fill_nearest(out["soil"])                         # coastal sea inherits the nearest land's wetness
    path = raw / "era5" / "era5_wb2.npz"
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savez(path, **out)
    print(f"era5 (WB2) -> {path}", flush=True)
    return path


# ---------------------------------------------------------------- CHELSA on the 0.25 deg grid
def chelsa_025(raw: Path, name: str) -> np.ndarray:
    """Block mean of a CHELSA layer over each 0.25 deg ERA5 cell (30 x 30 px, exactly aligned: cell i spans CHELSA
    rows 30 i - 735 .. +30, cell j columns 21600 + 30 j - 15 .. +30, wrapping). Rows north of 84 N: NaN."""
    import rasterio

    var, scale, offset, nod_mode = CHELSA[name]
    with rasterio.open(chelsa_path(raw, var)) as src:
        a = src.read(1)
        nodata = src.nodata
    out = np.full((H, W), np.nan, np.float32)
    shift = CHELSA_PX * 180 - 15
    for i in range(H):
        r0 = 30 * i - 735
        r1 = r0 + 30
        lo, hi = max(r0, 0), min(r1, a.shape[0])
        if hi <= lo:
            continue
        band = np.roll(a[lo:hi], -shift, axis=1).reshape(hi - lo, W, 30)
        v = band.astype(np.float32) * scale + offset
        if nodata is not None:
            miss = band == nodata
            if nod_mode == "zero":
                v[miss] = 0.0
            else:
                v[miss] = np.nan
        with np.errstate(invalid="ignore"):
            out[i] = np.nanmean(v, axis=(0, 2)) if nod_mode == "nan" else v.mean((0, 2))
    return out


def land_soil(era: dict) -> np.ndarray:
    """ERA5 soil water on land only (lsm >= 0.5), nearest-land filled out to sea. ERA5 stores ~0, not nodata, over
    the ocean, so without this the coastal sea band teaches the climate node "soil ~ 0 near coasts"."""
    soil = np.where(era["lsm"] >= 0.5, era["soil"], np.nan).astype(np.float32)
    return _fill_nearest(soil)


def climatology(raw: Path) -> Path:
    era = dict(np.load(raw / "era5" / "era5_wb2.npz"))
    era["soil"] = land_soil(era)
    geo = dict(np.load(raw / "era5" / "geo_025.npz"))                # train_climate's GEBCO 0.25 deg cache
    f = {}
    for name in CHELSA:
        f[name] = chelsa_025(raw, name)
        print(f"  chelsa {name}: {np.isfinite(f[name]).mean():.1%} of cells", flush=True)
    north = ~np.isfinite(f["t"])                                     # > 84 N (Arctic ocean): nearest CHELSA row
    for name in ("t", "tseason", "precip", "pcv", "snow"):
        f[name] = _fill_nearest(f[name])
    aridity = np.where(np.isfinite(f["pet"]), f["precip"] / np.maximum(f["pet"], 1.0), np.nan)
    surface = np.maximum(geo["elev"], 0.0) * geo["land"]             # mean surface of the cell (sea = 0)
    out = raw / "era5" / "climatology.npz"
    np.savez_compressed(
        out, t_mean=f["t"], orog=surface.astype(np.float32), tseason=f["tseason"], precip=f["precip"], pcv=f["pcv"],
        pet=_fill_nearest(f["pet"]), aridity=_fill_nearest(aridity).astype(np.float32), snow=f["snow"],
        soil=era["soil"], u10=era["u10"], v10=era["v10"], lsm=era["lsm"], orog_era5=era["orog"],
        north_filled=north, source=np.array("CHELSA v2.1 1981-2010 (0.25 deg block means) + ERA5 via WeatherBench2"))
    print(f"climatology -> {out}", flush=True)
    return out


# ---------------------------------------------------------------- per-region cells
def _chelsa_window(raw: Path, name: str, lat: np.ndarray, lon: np.ndarray) -> np.ndarray:
    """Bilinear sample of a CHELSA layer at the outer product lat (rows) x lon (cols), reading only the window."""
    import rasterio
    from rasterio.windows import Window
    from scipy import ndimage

    var, scale, offset, nod_mode = CHELSA[name]
    r0 = int(np.floor((CHELSA_TOP - lat.max()) * CHELSA_PX)) - 2
    r1 = int(np.ceil((CHELSA_TOP - lat.min()) * CHELSA_PX)) + 2
    c0 = int(np.floor((lon.min() + 180.0) * CHELSA_PX)) - 2
    c1 = int(np.ceil((lon.max() + 180.0) * CHELSA_PX)) + 2
    with rasterio.open(chelsa_path(raw, var)) as src:
        r0, c0 = max(r0, 0), max(c0, 0)
        r1, c1 = min(r1, src.height), min(c1, src.width)
        a = src.read(1, window=Window(c0, r0, c1 - c0, r1 - r0))
        nodata = src.nodata
    v = a.astype(np.float32) * scale + offset
    if nodata is not None:
        miss = a == nodata
        v[miss] = 0.0 if nod_mode == "zero" else np.nan
    rows = (CHELSA_TOP - lat) * CHELSA_PX - 0.5 - r0
    cols = (lon + 180.0) * CHELSA_PX - 0.5 - c0
    rr = np.broadcast_to(rows[:, None], (len(lat), len(lon)))
    cc = np.broadcast_to(cols[None, :], (len(lat), len(lon)))
    if nod_mode == "nan":                                            # NaN-aware: sample value and validity separately
        ok = np.isfinite(v)
        val = ndimage.map_coordinates(np.where(ok, v, 0.0), [rr, cc], order=1, mode="nearest")
        wgt = ndimage.map_coordinates(ok.astype(np.float32), [rr, cc], order=1, mode="nearest")
        return np.where(wgt > 0.5, val / np.maximum(wgt, 1e-6), np.nan).astype(np.float32)
    return ndimage.map_coordinates(v, [rr, cc], order=1, mode="nearest").astype(np.float32)


def region_cells(raw: Path, era: dict, clim: dict, lat: np.ndarray, lon: np.ndarray,
                 coarse: np.ndarray) -> dict[str, np.ndarray]:
    from scipy import ndimage

    from terrain_slm.data.climate_era5 import sample

    c = {name: _chelsa_window(raw, name, lat, lon) for name in CHELSA}
    cell_m = abs(float(lat[0] - lat[-1])) / max(len(lat) - 1, 1) * 111_320.0
    k = max(1, int(round(1000.0 / cell_m)))                          # ~1 km: the surface CHELSA's T refers to
    surface = ndimage.uniform_filter(np.maximum(coarse, 0.0).astype(np.float32), k, mode="nearest")
    aridity = np.where(np.isfinite(c["pet"]), c["precip"] / np.maximum(c["pet"], 1.0), np.nan)
    if not np.isfinite(aridity).any():                               # all-sea window: coarse climatology
        aridity = sample(clim["aridity"], lat, lon)
    return {
        "t0": c["t"] + LAPSE_C_PER_M * surface,
        "tseason": c["tseason"], "precip": c["precip"], "pcv": c["pcv"],
        "aridity": _fill_nearest(aridity).astype(np.float32), "snow": c["snow"],
        "soil": sample(era["soil"], lat, lon), "u10": sample(era["u10"], lat, lon), "v10": sample(era["v10"], lat, lon),
    }


def build_cells(data: Path, root: str, regions: list[str] | None) -> None:
    from terrain_slm.data import glo30
    from terrain_slm.data.build import _cell_latlon

    raw = data / "raw"
    era = dict(np.load(raw / "era5" / "era5_wb2.npz"))
    era["soil"] = land_soil(era)
    clim = dict(np.load(raw / "era5" / "climatology.npz"))
    for name in regions or sorted(glo30.REGIONS):
        rdir = data / root / name
        if not (rdir / "cells_geo.npz").exists():
            print(f"{name}: no cells_geo.npz yet, skipped", flush=True)
            continue
        coarse = np.load(rdir / "cells_geo.npz")["coarse"]
        lat, lon = _cell_latlon(glo30.REGIONS[name], *coarse.shape)
        if lon.min() < -180 or lon.max() > 180:
            raise ValueError(f"{name}: crosses the antimeridian; CHELSA window read does not wrap")
        cells = region_cells(raw, era, clim, lat, lon, coarse)
        np.savez(rdir / "cells_climate.npz", **cells)
        land = coarse > 0
        print(f"{name}: cells_climate.npz {coarse.shape}; land t0 {np.median(cells['t0'][land]):.1f} C, "
              f"precip {np.median(cells['precip'][land]):.0f} mm, aridity {np.median(cells['aridity'][land]):.2f}",
              flush=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("era5")
    a.add_argument("--raw", type=Path, required=True)
    a.add_argument("--day-stride", type=int, default=4)
    b = sub.add_parser("climatology")
    b.add_argument("--raw", type=Path, required=True)
    c = sub.add_parser("cells")
    c.add_argument("--data", type=Path, required=True)
    c.add_argument("--root", default="v2-gedtm30")
    c.add_argument("--region", action="append")
    args = ap.parse_args()
    if args.cmd == "era5":
        era5_wb2(args.raw, args.day_stride)
    elif args.cmd == "climatology":
        climatology(args.raw)
    else:
        build_cells(args.data, args.root, args.region)


if __name__ == "__main__":
    main()
