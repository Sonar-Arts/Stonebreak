"""Sub-block surface labels from 1 m lidar DTMs (EXP-2 agent L, 2026-11-19): what lies INSIDE a 15 m / 60 m block.

    python -m terrain_slm.data.surface_labels build --raw /mnt/extrastorage/terrain-slm-data/raw \
        --out /mnt/extrastorage/terrain-slm-data/v2-surface [--png <dir>]

Input: the `fine` windows of scripts/download_lidar.py (0.5-1 m bare-earth DTMs, projected metres; 0.5 m is
area-averaged to 1 m first). Per window, one `.npz` with labels at 15 m and 60 m cells (aligned: a 60 m cell = 4 x 4
15 m cells) and the inputs the surface node sees at tile time (60 m / 30 m block means of the same DTM + climate).

Per 1 m pixel (all thresholds in metres / degrees, chosen by looking at hillshades of the windows, see `png`):
  slope      gradient of the DTM after a 1 m Gaussian (degrees)
  resid      z - Gaussian(z, 4 m): relief finer than ~10 m (boulders, steps, gullies, scree texture)
  rms5       RMS of resid in a 5 m box (local roughness)
Labels per cell (fractions are shares of the cell's valid 1 m pixels):
  rock_exposure  slope >= 45 deg (cliffs, rock walls and outcrop faces: soil does not hold there)
  scree          28 <= slope < 45 deg AND rms5 >= 0.12 m (rough slopes at the angle of repose: talus, block fields)
  smooth         slope < 20 deg AND rms5 < 0.06 m (meadow, field, fine soil)
  roughness      RMS of resid over the cell (m)
  boulder_density, boulder_size   from detected boulders (below): boulders per 100 m^2 of the cell; mean
                                  equivalent diameter (m) of the boulders whose centre lies in the cell (NaN = none)
Boulders: white top-hat of the DTM (z - grey opening with a 9 x 9 m disk) > 0.5 m; connected blobs with
  2..36 m^2 area (1.6..6.8 m equivalent diameter), peak top-hat >= 0.6 m and >= 0.2 x diameter, bounding-box aspect
  <= 3 and fill >= 0.35 (compact, not a ridge / dyke / road bank), centre slope < 50 deg (not cliff texture).
Cells with < 90 % valid pixels are NaN (ignored by the loss).
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np

NODATA = -9999.0
CELL15, CELL60 = 15, 60
FIELDS = ("rock_exposure", "scree", "smooth", "roughness", "boulder_density", "boulder_size")
ROCK_DEG, SCREE_DEG, SMOOTH_DEG = 45.0, 28.0, 20.0
SCREE_RMS, SMOOTH_RMS = 0.12, 0.06
TOPHAT_R, TOPHAT_MIN, PEAK_MIN = 4, 0.5, 0.6
B_AREA = (2, 36)
LAPSE = 0.0065


def load_fine(path: Path):
    import rasterio

    with rasterio.open(path) as s:
        z = s.read(1).astype(np.float32)
        res = s.res[0]
        tags = s.tags()
        epsg = s.crs.to_epsg()
        bounds = s.bounds
    valid = z > NODATA + 1
    k = int(round(1.0 / res))
    if k > 1:   # 0.5 m -> 1 m area means
        h, w = z.shape[0] // k * k, z.shape[1] // k * k
        zz = np.where(valid, z, 0)[:h, :w].reshape(h // k, k, w // k, k).sum((1, 3))
        n = valid[:h, :w].reshape(h // k, k, w // k, k).sum((1, 3))
        z = (zz / np.maximum(n, 1)).astype(np.float32)
        valid = n == k * k
    return z, valid, epsg, bounds, tags


def fill(z: np.ndarray, valid: np.ndarray) -> np.ndarray:
    """Normalised-convolution fill of holes (buildings removed from the DTM, water): smooth, no terraces."""
    from scipy import ndimage

    if valid.all():
        return z
    out = np.where(valid, z, 0).astype(np.float32)
    known = valid.copy()
    for s in (2, 8, 32, 128, 512):
        num = ndimage.gaussian_filter(np.where(known, out, 0).astype(np.float32), s)
        den = ndimage.gaussian_filter(known.astype(np.float32), s)
        upd = ~known & (den > 1e-3)
        out[upd] = num[upd] / den[upd]
        known |= upd
        if known.all():
            break
    if not known.all():
        out[~known] = float(out[known].mean()) if known.any() else 0.0
    return out


def pixel_fields(z: np.ndarray, valid: np.ndarray) -> dict:
    from scipy import ndimage

    zf = fill(z, valid)
    zs = ndimage.gaussian_filter(zf, 1.0)
    gy, gx = np.gradient(zs)
    slope = np.degrees(np.arctan(np.hypot(gx, gy))).astype(np.float32)
    resid = (zf - ndimage.gaussian_filter(zf, 4.0)).astype(np.float32)
    rms5 = np.sqrt(ndimage.uniform_filter(resid * resid, 5)).astype(np.float32)
    r = TOPHAT_R
    yy, xx = np.mgrid[-r : r + 1, -r : r + 1]
    disk = (xx * xx + yy * yy) <= r * r + 0.5
    tophat = (zf - ndimage.grey_opening(zf, footprint=disk)).astype(np.float32)
    return dict(z=zf, slope=slope, resid=resid, rms5=rms5, tophat=tophat)


def boulders(f: dict, valid: np.ndarray) -> np.ndarray:
    """(N, 4) float32: row, col (centre, pixels), equivalent diameter (m), peak top-hat (m)."""
    from scipy import ndimage

    cand = (f["tophat"] > TOPHAT_MIN) & valid
    lab, n = ndimage.label(cand)
    if n == 0:
        return np.zeros((0, 4), np.float32)
    idx = np.arange(1, n + 1)
    area = ndimage.sum(np.ones_like(lab, np.float32), lab, idx)
    peak = ndimage.maximum(f["tophat"], lab, idx)
    com = np.array(ndimage.center_of_mass(cand, lab, idx), np.float32).reshape(-1, 2)
    objs = ndimage.find_objects(lab)
    bh = np.array([o[0].stop - o[0].start for o in objs], np.float32)
    bw = np.array([o[1].stop - o[1].start for o in objs], np.float32)
    diam = 2.0 * np.sqrt(area / math.pi)
    rr = np.clip(np.rint(com[:, 0]).astype(int), 0, lab.shape[0] - 1)
    cc = np.clip(np.rint(com[:, 1]).astype(int), 0, lab.shape[1] - 1)
    ok = ((area >= B_AREA[0]) & (area <= B_AREA[1]) & (peak >= PEAK_MIN) & (peak >= 0.2 * diam)
          & (np.maximum(bh, bw) <= 3 * np.minimum(bh, bw)) & (area >= 0.35 * bh * bw)
          & (f["slope"][rr, cc] < 50.0))
    return np.stack([com[ok, 0], com[ok, 1], diam[ok], peak[ok]], 1).astype(np.float32)


def _pool(x: np.ndarray, k: int, w: np.ndarray | None = None) -> np.ndarray:
    h, wd = x.shape[0] // k * k, x.shape[1] // k * k
    x = x[:h, :wd].reshape(h // k, k, wd // k, k)
    if w is None:
        return x.mean((1, 3))
    w = w[:h, :wd].reshape(h // k, k, wd // k, k)
    return (x * w).sum((1, 3)) / np.maximum(w.sum((1, 3)), 1e-6)


def cell_labels(f: dict, valid: np.ndarray, bl: np.ndarray, k: int) -> dict:
    v = valid.astype(np.float32)
    s, rms = f["slope"], f["rms5"]
    out = {
        "rock_exposure": _pool((s >= ROCK_DEG).astype(np.float32), k, v),
        "scree": _pool(((s >= SCREE_DEG) & (s < ROCK_DEG) & (rms >= SCREE_RMS)).astype(np.float32), k, v),
        "smooth": _pool(((s < SMOOTH_DEG) & (rms < SMOOTH_RMS)).astype(np.float32), k, v),
        "roughness": np.sqrt(_pool(f["resid"] ** 2, k, v)),
    }
    H, W = out["scree"].shape
    cnt = np.zeros((H, W), np.float32)
    dsum = np.zeros((H, W), np.float32)
    if len(bl):
        r = (bl[:, 0] // k).astype(int)
        c = (bl[:, 1] // k).astype(int)
        m = (r < H) & (c < W)
        np.add.at(cnt, (r[m], c[m]), 1.0)
        np.add.at(dsum, (r[m], c[m]), bl[m, 2])
    vfrac = _pool(v, k)
    out["boulder_density"] = cnt / (k * k * np.maximum(vfrac, 1e-6)) * 100.0
    out["boulder_size"] = np.where(cnt > 0, dsum / np.maximum(cnt, 1), np.nan)
    bad = vfrac < 0.9
    for key in out:
        out[key] = np.where(bad, np.nan, out[key]).astype(np.float32)
    return out


def climate_at(raw: Path, lon: float, lat: float) -> tuple[float, float]:
    """CHELSA v2.1 (1981-2010) mean annual temperature (degC) and precipitation (mm/yr) at a point."""
    import rasterio

    vals = []
    for var, sc, off in (("bio1", 0.1, -273.15), ("bio12", 0.1, 0.0)):
        with rasterio.open(raw / "chelsa" / f"CHELSA_{var}_1981-2010_V.2.1.tif") as s:
            v = next(s.sample([(lon, lat)]))[0]
            vals.append(float(v) * sc + off)
    return vals[0], vals[1]


def build_window(path: Path, raw: Path) -> dict:
    from rasterio.warp import transform

    z, valid, epsg, bounds, tags = load_fine(path)
    if float(valid.mean()) < 0.5:
        return None                                  # mostly outside the survey (mosaic gaps)
    H, W = z.shape[0] // CELL60 * CELL60, z.shape[1] // CELL60 * CELL60
    z, valid = z[:H, :W], valid[:H, :W]
    f = pixel_fields(z, valid)
    bl = boulders(f, valid)
    lab15 = cell_labels(f, valid, bl, CELL15)
    lab60 = cell_labels(f, valid, bl, CELL60)
    z60 = _pool(f["z"], CELL60)
    z30 = _pool(f["z"], 30)
    z15 = _pool(f["z"], CELL15)
    cx, cy = (bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2
    lon, lat = transform(f"EPSG:{epsg}", "EPSG:4326", [cx], [cy])
    t, p = climate_at(raw, lon[0], lat[0])
    # CHELSA is a ~1 km mean surface: put the window's own relief back by the lapse rate
    temp60 = (t - LAPSE * (z60 - float(np.nanmean(z60)))).astype(np.float32)
    v60 = _pool(valid.astype(np.float32), CELL60)
    return dict(name=path.stem, source=path.parent.parent.name, lon=lon[0], lat=lat[0], t=t, p=p, z60=z60, z30=z30,
                z15=z15, temp60=temp60, valid60=v60, boulders=bl, **{f"l15_{k}": v for k, v in lab15.items()},
                **{f"l60_{k}": v for k, v in lab60.items()})


def overview_png(path: Path, out: Path, crop: int = 600) -> None:
    """Hand-check image: hillshade of a crop with rock (red), scree (yellow) and boulders (cyan circles)."""
    from PIL import Image, ImageDraw

    z, valid, *_ = load_fine(path)
    f = pixel_fields(z, valid)
    bl = boulders(f, valid)
    # the crop with the most boulders + rock (the interesting bit), else the centre
    H, W = z.shape
    best, br, bc = -1.0, (H - crop) // 2, (W - crop) // 2
    for r0 in range(0, H - crop + 1, crop // 2):
        for c0 in range(0, W - crop + 1, crop // 2):
            m = (bl[:, 0] >= r0) & (bl[:, 0] < r0 + crop) & (bl[:, 1] >= c0) & (bl[:, 1] < c0 + crop) if len(bl) else []
            score = float(np.sum(m)) + 0.002 * float((f["slope"][r0 : r0 + crop, c0 : c0 + crop] >= ROCK_DEG).sum())
            if score > best:
                best, br, bc = score, r0, c0
    sl = (slice(br, br + crop), slice(bc, bc + crop))
    zs = f["z"][sl]
    gy, gx = np.gradient(zs)
    shade = np.clip(0.55 + 0.45 * (-gx - gy) / np.sqrt(2 + gx * gx + gy * gy) * 1.4, 0, 1)
    rgb = np.stack([shade] * 3, -1)
    s, rms = f["slope"][sl], f["rms5"][sl]
    rgb[s >= ROCK_DEG] = rgb[s >= ROCK_DEG] * 0.5 + np.array([0.5, 0, 0])
    sc = (s >= SCREE_DEG) & (s < ROCK_DEG) & (rms >= SCREE_RMS)
    rgb[sc] = rgb[sc] * 0.6 + np.array([0.4, 0.35, 0])
    img = Image.fromarray((rgb * 255).astype(np.uint8)).resize((crop * 2, crop * 2), Image.NEAREST)
    dr = ImageDraw.Draw(img)
    for r, c, d, _ in bl:
        if br <= r < br + crop and bc <= c < bc + crop:
            y, x, rad = (r - br) * 2, (c - bc) * 2, max(2.0, d)
            dr.ellipse([x - rad, y - rad, x + rad, y + rad], outline=(0, 230, 255), width=2)
    for k in range(0, crop * 2, CELL15 * 2 * 4):     # 60 m grid
        dr.line([(k, 0), (k, crop * 2)], fill=(90, 90, 90))
        dr.line([(0, k), (crop * 2, k)], fill=(90, 90, 90))
    dr.text((6, 6), f"{path.stem}: {len(bl)} boulders in window, crop r{br} c{bc}; red rock, yellow scree", fill=(255, 255, 0))
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["build", "png"])
    ap.add_argument("--raw", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/raw"))
    ap.add_argument("--out", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/v2-surface"))
    ap.add_argument("--png", type=Path, default=None)
    ap.add_argument("--only", nargs="*", default=None)
    args = ap.parse_args()
    paths = sorted(args.raw.glob("*/fine/*.tif"))
    if args.only:
        paths = [p for p in paths if p.stem in args.only]
    for p in paths:
        if args.cmd == "png":
            overview_png(p, (args.png or args.out / "png") / f"{p.stem}.png")
            print(f"png {p.stem}", flush=True)
            continue
        dst = args.out / f"{p.stem}.npz"
        if dst.exists():
            continue
        d = build_window(p, args.raw)
        if d is None:
            print(f"{p.stem}: skipped (< 50 % valid)", flush=True)
            continue
        args.out.mkdir(parents=True, exist_ok=True)
        meta = {k: v for k, v in d.items() if not isinstance(v, np.ndarray)}
        np.savez_compressed(dst, **{k: v for k, v in d.items() if isinstance(v, np.ndarray)}, meta=json.dumps(meta))
        l60 = {k: float(np.nanmean(d[f"l60_{k}"])) for k in FIELDS}
        print(f"{p.stem}: z60 {d['z60'].shape} T {d['t']:.1f} P {d['p']:.0f} boulders {len(d['boulders'])} "
              + " ".join(f"{k} {v:.3f}" for k, v in l60.items()), flush=True)


if __name__ == "__main__":
    main()
