"""Paleo "planets" for the climate node (DaedalusTGM-Exp-2 plan §5b, 2026-11-18).

    python -m terrain_slm.data.climate_paleo --raw /mnt/extrastorage/terrain-slm-data/raw

The climate node learns geography + wind -> climate from ONE planet and overfits it after ~8k steps (precipitation
on held-out continents drifts back toward the predict-the-mean baseline). CHELSA-TraCE21k (Karger et al. 2020,
doi:10.16904/envidat.211; CC BY / CC BY-SA, attribution required) gives the same physics on different geography:
sea level 120 m lower at the Last Glacial Maximum (Doggerland, Sundaland, Beringia, Sahul as land), the Laurentide
and Fennoscandian ice sheets kilometres high, and the deglaciation between. Each slice is one more planet.

Delta method (standard for paleoclimate): TraCE comes from a GCM (CCSM3) downscaled, so its present-day climate is
biased against CHELSA v2.1. A slice is therefore CHELSA v2.1 + (TraCE(slice) - TraCE(present)): additive for
temperature, seasonality and precipitation seasonality, a clamped ratio for precipitation. Written per slice on the
ERA5 0.25 deg grid, the names train_climate reads:

  raw/chelsa_trace/paleo_<t>.npz   t_mean (degC at the cell's mean surface), orog (that surface), tseason, precip,
                                   pcv, land (land fraction), elev (land mean elevation, m, delta-corrected
                                   against GEBCO), forcing_dt (land-mean dT vs today), age_ka ; t = TraCE time id
                                   (centuries; -200 = 22 ka ... -40 = 6 ka, 20 = 1990 CE)

TraCE has no PET, snow, soil moisture or wind: those targets are masked for paleo planets, and the inputs use
today's ERA5 wind (the westerlies and trades sit roughly where they did; ice-sheet anticyclones are not captured).
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

from terrain_slm.data.climate_hybrid import H, W, _fill_nearest

SLICES = (-200, -160, -120, -80, -40)
PRESENT = 20
PRECIP_RATIO = (0.1, 10.0)


def trace_path(raw: Path, var: str, t: int) -> Path:
    return raw / "chelsa_trace" / f"CHELSA_TraCE21k_{var}_{t}_V1.0.tif"


def block_025(path: Path, valid=lambda a: np.isfinite(a)) -> tuple[np.ndarray, np.ndarray]:
    """(mean over valid pixels, valid fraction) per 0.25 deg ERA5 cell, from a 30" global grid starting at 84 N
    (same block layout as climate_hybrid.chelsa_025; TraCE's half-pixel offset is 0.00014 deg, ignored)."""
    import rasterio

    with rasterio.open(path) as src:
        a = src.read(1)
        nodata = src.nodata
    mean = np.full((H, W), np.nan, np.float32)
    frac = np.zeros((H, W), np.float32)
    shift = 120 * 180 - 15
    for i in range(H):
        r0 = 30 * i - 735
        lo, hi = max(r0, 0), min(r0 + 30, a.shape[0])
        if hi <= lo:
            continue
        band = np.roll(a[lo:hi], -shift, axis=1).reshape(hi - lo, W, 30).astype(np.float32)
        ok = valid(band) if nodata is None else (band != nodata) & valid(band)
        n = ok.sum((0, 2))
        frac[i] = n / (ok.shape[0] * 30)
        with np.errstate(invalid="ignore", divide="ignore"):
            mean[i] = np.where(n > 0, np.where(ok, band, 0).sum((0, 2)) / np.maximum(n, 1), np.nan)
    return mean, frac


def _bio(raw: Path, t: int) -> dict[str, np.ndarray]:
    ok = lambda a: np.isfinite(a) & (np.abs(a) < 1e6)
    return {v: _fill_nearest(block_025(trace_path(raw, v, t), ok)[0]) for v in ("bio01", "bio04", "bio12", "bio15")}


def age_ka(t: int) -> float:
    """TraCE time id (centuries; 20 = 1990 CE) -> thousands of years before present."""
    return (PRESENT - t) / 10.0


def build(raw: Path) -> None:
    clim = dict(np.load(raw / "era5" / "climatology.npz"))            # CHELSA v2.1 (+ ERA5) present day, 0.25 deg
    geo = dict(np.load(raw / "era5" / "geo_025.npz"))                # GEBCO land-mean elevation + land fraction
    ref = _bio(raw, PRESENT)
    dem20, land20 = block_025(trace_path(raw, "dem", PRESENT))
    # TraCE's DEM reads ~120 m above GEBCO over today's land: delta-correct elevation like the climate fields, so a
    # paleo planet's inputs differ from today's only where the world actually changed (ice sheets, exposed shelves)
    both = (geo["land"] > 0.5) & (land20 > 0.5)
    bias = _fill_nearest(np.where(both, dem20 - geo["elev"], np.nan).astype(np.float32))
    lat_w = np.cos(np.radians(90.0 - 0.25 * np.arange(H)))[:, None] * (geo["land"] > 0.5)
    print("  TraCE present-day reference done", flush=True)
    for t in SLICES:
        f = _bio(raw, t)
        dem, land = block_025(trace_path(raw, "dem", t))
        elev = np.where(both & (land > 0.5), geo["elev"] + (dem - dem20), dem - bias)
        elev = np.nan_to_num(elev, nan=0.0)
        ratio = np.clip((f["bio12"] + 10.0) / (ref["bio12"] + 10.0), *PRECIP_RATIO)
        d_t = f["bio01"] - ref["bio01"]
        out = {
            "t_mean": clim["t_mean"] + d_t,
            "tseason": np.maximum(clim["tseason"] + (f["bio04"] - ref["bio04"]), 0.0),
            "precip": clim["precip"] * ratio,
            "pcv": np.maximum(clim["pcv"] + (f["bio15"] - ref["bio15"]), 0.0),
            "land": land, "elev": elev.astype(np.float32),
        }
        out["orog"] = (np.maximum(elev, 0.0) * land).astype(np.float32)
        forcing_dt = float((d_t * lat_w).sum() / lat_w.sum())          # area-weighted over today's land
        path = raw / "chelsa_trace" / f"paleo_{t}.npz"
        np.savez_compressed(path, **{k: v.astype(np.float32) for k, v in out.items()},
                            forcing_dt=np.float32(forcing_dt), age_ka=np.float32(age_ka(t)))
        print(f"  slice {t} ({age_ka(t):.0f} ka): land {land.mean():.1%} (today {clim['lsm'].mean():.1%}), "
              f"land dT {forcing_dt:+.1f} C, land elev median {np.median(elev[land > 0.5]):.0f} m "
              f"(today {np.median(geo['elev'][geo['land'] > 0.5]):.0f}) -> {path.name}", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", type=Path, required=True)
    build(ap.parse_args().raw)


if __name__ == "__main__":
    main()
