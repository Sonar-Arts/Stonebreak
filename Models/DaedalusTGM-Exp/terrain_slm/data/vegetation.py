"""Vegetation targets for the training regions (EXP-2 vegetation node, agent V, 2026-11-19).

Sources (both CC BY 4.0, verified on the providers' pages 2026-11-19):
- ETH Global Canopy Height 2020 10 m (Lang, Jetz, Schindler, Wegner 2023, Nat. Ecol. Evol.;
  doi:10.3929/ethz-b-000609802). uint8 metres, nodata 255, 3x3 degree COGs, 1/12000 degree pixels.
- ESA WorldCover 10 m 2021 v200 (Zanaga et al. 2022, doi:10.5281/zenodo.7254221). uint8 class codes,
  nodata 0, same 3x3 grid. "(c) ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021)
  processed by ESA WorldCover consortium".

Each 10 m source pixel is binned (by its centre) into the region's 60 m grid (half the v2 30 m grid built by
build_v2: rows 1/1800 degree from lat1 down, columns tile_width_px/2 per 1-degree tile), so every source pixel
lands in exactly one target pixel. Products per region (`<region>/veg60.npy`, uint8 (C, H/2, W/2), channel
list in VEG_CHANNELS; `<region>/veg_cc30.npy` = canopy cover at 30 m x254, 255 = no data):

  eth_valid   share of 10 m pixels with a canopy value            x255
  cover       share of valid pixels with canopy height >= 5 m     x255   (FAO tree threshold)
  hmean       mean canopy height of all valid pixels              x4  (0.25 m units)
  hcan        mean height of the canopy (>= 5 m) pixels           x4
  p90         90th percentile height of all valid pixels          metres (1 m histogram, interpolated)
  wc_*        WorldCover class shares of the WorldCover-valid pixels  x255
  wc_valid    share of pixels with a WorldCover class             x255

Raw tiles go to raw/eth_canopy and raw/worldcover and are deleted once every region using them is done
(disk budget); the manifest `raw/vegetation_manifest.json` records every file fetched (bytes, sha256).

    python -m terrain_slm.data.vegetation --data-dir /mnt/extrastorage/terrain-slm-data/v2-gedtm30 [--regions a b]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

ETH_URL = ("https://libdrive.ethz.ch/index.php/s/cO8or7iOe5dT2Rt/download?path=%2F3deg_cogs"
           "&files=ETH_GlobalCanopyHeight_10m_2020_{t}_Map.tif")
WC_URL = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_{t}_Map.tif"
SRC_PER_DEG = 12000
ROWS60_PER_DEG = 1800
CANOPY_M = 5
HBINS = 64
# WorldCover class code -> group index (0 = nodata)
WC_GROUPS = ("tree", "shrub", "grass", "crop", "built", "bare", "snow", "water", "wetland", "moss")
_WC_CODE = {10: 1, 20: 2, 30: 3, 40: 4, 50: 5, 60: 6, 70: 7, 80: 8, 90: 9, 95: 9, 100: 10}
WC_LUT = np.zeros(256, np.uint8)
for _c, _g in _WC_CODE.items():
    WC_LUT[_c] = _g
VEG_CHANNELS = ("eth_valid", "cover", "hmean", "hcan", "p90") + tuple("wc_" + g for g in WC_GROUPS) + ("wc_valid",)
SKIP_REGIONS = {"mariana_trench", "mid_atlantic_ridge", "atlantic_canyons"}   # open ocean: no vegetation
STRIP = 300   # source rows per strip (0.05 degree = 90 rows at 60 m)


def tile_name(lat3: int, lon3: int) -> str:
    return f"{'N' if lat3 >= 0 else 'S'}{abs(lat3):02d}{'E' if lon3 >= 0 else 'W'}{abs(lon3):03d}"


class RegionGrid:
    def __init__(self, root: Path):
        self.root = root
        m = json.loads((root / "meta.json").read_text())
        self.name = m["region"]
        self.lat0, self.lat1 = m["lat"]
        self.lon0, self.lon1 = m["lon"]
        h, w = m["shape_px"]
        self.w30 = w // (self.lon1 - self.lon0)        # 30 m columns per degree (multiple of 8)
        self.w60 = self.w30 // 2
        self.h60, self.wd60 = h // 2, w // 2
        assert self.h60 == (self.lat1 - self.lat0) * ROWS60_PER_DEG

    def tiles3(self) -> list[tuple[int, int]]:
        return sorted({(3 * math.floor(a / 3), 3 * math.floor(b / 3))
                       for a in range(self.lat0, self.lat1) for b in range(self.lon0, self.lon1)})


def _col_map(g: RegionGrid, lon_a: int, lon_b: int, w_per_deg: int) -> np.ndarray:
    """Target column (in a grid of w_per_deg columns per degree) of every source column between lon_a and lon_b."""
    k = np.arange((lon_b - lon_a) * SRC_PER_DEG)
    lon = lon_a + (k + 0.5) / SRC_PER_DEG
    deg = np.floor(lon).astype(np.int64)
    return ((deg - g.lon0) * w_per_deg + np.floor((lon - deg) * w_per_deg).astype(np.int64)).astype(np.int64)


def _row_map(g: RegionGrid, lat_top: float, n: int, rows_per_deg: int) -> np.ndarray:
    k = np.arange(n)
    lat = lat_top - (k + 0.5) / SRC_PER_DEG
    return np.floor((g.lat1 - lat) * rows_per_deg).astype(np.int64)


def process_tile(g: RegionGrid, t: tuple[int, int], eth: Path | None, wc: Path | None, acc: dict):
    """Accumulate one 3-degree tile's overlap with the region into the region's sums (acc arrays, 60 m / 30 m)."""
    import rasterio
    from rasterio.windows import Window

    lat3, lon3 = t
    la, lb = max(lat3, g.lat0), min(lat3 + 3, g.lat1)
    oa, ob = max(lon3, g.lon0), min(lon3 + 3, g.lon1)
    if la >= lb or oa >= ob:
        return
    c0 = (oa - lon3) * SRC_PER_DEG
    ncol = (ob - oa) * SRC_PER_DEG
    cm60 = _col_map(g, oa, ob, g.w60)
    cm30 = _col_map(g, oa, ob, g.w30)
    r_first = (lat3 + 3 - lb) * SRC_PER_DEG
    r_last = (lat3 + 3 - la) * SRC_PER_DEG
    srcs = {k: rasterio.open(p) for k, p in (("eth", eth), ("wc", wc)) if p is not None}
    try:
        for r in range(r_first, r_last, STRIP):
            n = min(STRIP, r_last - r)
            win = Window(c0, r, ncol, n)
            rm60 = _row_map(g, lat3 + 3 - r / SRC_PER_DEG, n, ROWS60_PER_DEG)
            r0 = int(rm60[0])
            nr = int(rm60[-1]) - r0 + 1
            idx = ((rm60 - r0)[:, None] * g.wd60 + cm60[None, :]).ravel()
            m = nr * g.wd60
            sl = slice(r0, r0 + nr)
            if "eth" in srcs:
                h = srcs["eth"].read(1, window=win).ravel()
                v = h != 255
                iv, hv = idx[v], h[v]
                can = hv >= CANOPY_M
                _add(acc["n"][sl], np.bincount(iv, minlength=m).reshape(nr, -1))
                _add(acc["ncan"][sl], np.bincount(iv[can], minlength=m).reshape(nr, -1))
                _add(acc["hsum"][sl], np.bincount(iv, weights=hv, minlength=m).reshape(nr, -1))
                _add(acc["hcsum"][sl], np.bincount(iv[can], weights=hv[can], minlength=m).reshape(nr, -1))
                hist = np.bincount(iv * HBINS + np.minimum(hv, HBINS - 1), minlength=m * HBINS).reshape(nr, -1, HBINS)
                acc["p90"][sl] = _p90(hist, acc["p90"][sl])
                # 30 m canopy cover
                rm30 = _row_map(g, lat3 + 3 - r / SRC_PER_DEG, n, 2 * ROWS60_PER_DEG)
                q0 = int(rm30[0]); nq = int(rm30[-1]) - q0 + 1
                wd30 = 2 * g.wd60
                i30 = ((rm30 - q0)[:, None] * wd30 + cm30[None, :]).ravel()[v]
                _add(acc["n30"][q0 : q0 + nq], np.bincount(i30, minlength=nq * wd30).reshape(nq, -1))
                _add(acc["c30"][q0 : q0 + nq], np.bincount(i30[can], minlength=nq * wd30).reshape(nq, -1))
                del h, v, iv, hv, can, hist, i30
            if "wc" in srcs:
                c = WC_LUT[srcs["wc"].read(1, window=win).ravel()]
                cnt = np.bincount(idx * 11 + c, minlength=m * 11).reshape(nr, -1, 11)
                _add(acc["wc"][:, sl], np.moveaxis(cnt, -1, 0))
                del c, cnt
    finally:
        for s in srcs.values():
            s.close()


def _p90(hist: np.ndarray, prev: np.ndarray) -> np.ndarray:
    """p90 of each cell's 1 m histogram (linear inside the bin); cells without samples keep prev."""
    hist = hist.astype(np.uint16)          # counts per 60 m cell stay < 200: 4x less memory traffic than int64
    cum = np.cumsum(hist, -1, dtype=np.uint16)
    tot = cum[..., -1]
    target = 0.9 * tot
    b = (cum < target[..., None]).sum(-1).clip(0, HBINS - 1)
    below = np.take_along_axis(cum, b[..., None], -1)[..., 0] - np.take_along_axis(hist, b[..., None], -1)[..., 0]
    inbin = np.take_along_axis(hist, b[..., None], -1)[..., 0].clip(1)
    val = b + (target - below) / inbin
    return np.where(tot > 0, np.clip(np.rint(val), 0, 255).astype(np.uint8), prev)


def _add(dst: np.ndarray, src: np.ndarray):
    """dst += src in dst's (small) dtype; dst is a view into a memmap."""
    np.add(dst, np.rint(src).astype(dst.dtype) if src.dtype.kind == "f" else src.astype(dst.dtype), out=dst)


def finish(g: RegionGrid, acc: dict, out60: Path, out30: Path):
    """Write veg60.npy (C, H, W) and veg_cc30.npy (H30, W30) as uint8, row strips at a time (bounded RAM)."""
    exp = (SRC_PER_DEG / ROWS60_PER_DEG) * (SRC_PER_DEG / g.w60)   # 10 m pixels per 60 m cell
    out = np.lib.format.open_memmap(out60.with_suffix(".tmp.npy"), mode="w+", dtype=np.uint8,
                                    shape=(len(VEG_CHANNELS), g.h60, g.wd60))
    u8 = lambda a: np.clip(np.rint(a), 0, 255).astype(np.uint8)
    for r in range(0, g.h60, 512):
        s = slice(r, r + 512)
        n = acc["n"][s].astype(np.float32)
        nc = acc["ncan"][s].astype(np.float32)
        out[0, s] = u8(n / exp * 255)
        out[1, s] = u8(nc / n.clip(1) * 255)
        out[2, s] = u8(acc["hsum"][s] / n.clip(1) * 4)
        out[3, s] = u8(acc["hcsum"][s] / nc.clip(1) * 4)
        out[4, s] = acc["p90"][s]
        wc = acc["wc"][:, s].astype(np.float32)
        wct = wc[1:].sum(0)
        for k in range(10):
            out[5 + k, s] = u8(wc[k + 1] / wct.clip(1) * 255)
        out[15, s] = u8(wct / exp * 255)
    out.flush(); del out
    out60.with_suffix(".tmp.npy").rename(out60)
    c30 = np.lib.format.open_memmap(out30.with_suffix(".tmp.npy"), mode="w+", dtype=np.uint8, shape=acc["n30"].shape)
    for r in range(0, c30.shape[0], 1024):
        s = slice(r, r + 1024)
        n30 = acc["n30"][s].astype(np.float32)
        c30[s] = np.where(n30 > 0, u8(acc["c30"][s] / n30.clip(1) * 254), 255)   # 255 = no data
    c30.flush(); del c30
    out30.with_suffix(".tmp.npy").rename(out30)
    (out60.parent / "veg_meta.json").write_text(json.dumps({
        "channels": list(VEG_CHANNELS), "grid": "60 m = 2x2 of the region's 30 m pixels", "canopy_m": CANOPY_M,
        "scales": {"eth_valid": "x255", "cover": "x255", "hmean": "x4 (m)", "hcan": "x4 (m)", "p90": "m",
                   "wc_*": "x255 of wc-valid", "wc_valid": "x255", "veg_cc30": "x254, 255 = no data"},
        "sources": {"eth_canopy": "ETH Global Canopy Height 2020 10 m, Lang et al. 2023, CC BY 4.0, doi:10.3929/ethz-b-000609802",
                    "worldcover": "ESA WorldCover 10 m 2021 v200, Zanaga et al. 2022, CC BY 4.0, doi:10.5281/zenodo.7254221"},
        "built": time.strftime("%Y-%m-%dT%H:%M:%S")}, indent=1))


def _alloc(g: RegionGrid, stage: Path) -> dict:
    """Accumulators as disk memmaps (RAM is shared with training jobs). Counts fit uint8 (<= ~120 per 60 m cell)."""
    stage.mkdir(parents=True, exist_ok=True)
    H, W = g.h60, g.wd60
    spec = {"n": ((H, W), np.uint8), "ncan": ((H, W), np.uint8), "hsum": ((H, W), np.uint16),
            "hcsum": ((H, W), np.uint16), "p90": ((H, W), np.uint8), "wc": ((11, H, W), np.uint8),
            "n30": ((2 * H, 2 * W), np.uint8), "c30": ((2 * H, 2 * W), np.uint8)}
    return {k: np.lib.format.open_memmap(stage / f"{k}.npy", mode="w+", dtype=dt, shape=sh) for k, (sh, dt) in spec.items()}


def fetch(raw: Path, kind: str, t: tuple[int, int], lock: threading.Lock) -> Path | None:
    """Download one 3-degree tile (kept if already there); None when the provider has no tile (open ocean)."""
    name = tile_name(*t)
    d = raw / ("eth_canopy" if kind == "eth" else "worldcover")
    d.mkdir(parents=True, exist_ok=True)
    url = (ETH_URL if kind == "eth" else WC_URL).format(t=name)
    p = d / url.rsplit("=", 1)[-1].rsplit("/", 1)[-1]
    if not p.exists():
        tmp = p.with_suffix(f".{os.getpid()}.{threading.get_ident()}.part")
        for attempt in range(5):
            r = subprocess.run(["curl", "-sfL", "--retry", "3", "-o", str(tmp), url])
            if r.returncode == 22 or (r.returncode == 0 and tmp.stat().st_size < 1000):
                tmp.unlink(missing_ok=True)   # HTTP error / tiny page: the provider has no tile there
                _manifest(raw, p.name, {"url": url, "missing": True}, lock)
                return None
            if r.returncode == 0:
                break
            time.sleep(5 * (attempt + 1))
        else:
            raise RuntimeError(f"download failed: {url}")
        tmp.replace(p)
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 24), b""):
            h.update(b)
    _manifest(raw, p.name, {"url": url, "bytes": p.stat().st_size, "sha256": h.hexdigest(),
                            "fetched": time.strftime("%Y-%m-%dT%H:%M:%S")}, lock)
    return p


def _manifest(raw: Path, name: str, entry: dict, lock: threading.Lock):
    """Record one fetched file in raw/vegetation_manifest.json (file-locked: several workers write it)."""
    import fcntl
    with lock, open(raw / "vegetation_manifest.lock", "w") as lk:
        fcntl.flock(lk, fcntl.LOCK_EX)
        mp = raw / "vegetation_manifest.json"
        m = json.loads(mp.read_text()) if mp.exists() else {}
        m[name] = entry
        tmp = mp.with_suffix(f".{os.getpid()}.tmp")
        tmp.write_text(json.dumps(m, indent=1))
        tmp.replace(mp)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, required=True)
    ap.add_argument("--raw", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/raw"))
    ap.add_argument("--regions", nargs="*")
    ap.add_argument("--keep-raw", action="store_true")
    ap.add_argument("--stage", type=Path, default=None,
                    help="accumulator memmaps here (default <raw>/veg_stage; a local disk is much faster than FUSE)")
    args = ap.parse_args()
    roots = sorted(p.parent for p in args.data_dir.glob("*/meta.json"))
    grids = [RegionGrid(r) for r in roots if r.name not in SKIP_REGIONS and (not args.regions or r.name in args.regions)]
    grids = [g for g in grids if not (g.root / "veg60.npy").exists()]
    # Work queue over processes: a region is claimed by an exclusive claim file; largest first.
    grids.sort(key=lambda g: -g.h60 * g.wd60)
    lock = threading.Lock()
    pool = ThreadPoolExecutor(4)
    t0 = time.time()
    stage_root = args.raw / "veg_stage"
    stage_root.mkdir(parents=True, exist_ok=True)
    for g in grids:
        if (g.root / "veg60.npy").exists():
            continue
        try:
            open(stage_root / f"{g.name}.claim", "x").close()
        except FileExistsError:
            continue
        tg = time.time()
        tiles = g.tiles3()
        futs = {(k, t): pool.submit(fetch, args.raw, k, t, lock) for t in tiles for k in ("eth", "wc")}
        stage = (args.stage or stage_root) / g.name
        acc = _alloc(g, stage)
        for t in tiles:
            eth, wc = futs[("eth", t)].result(), futs[("wc", t)].result()
            process_tile(g, t, eth, wc, acc)
            if not args.keep_raw:
                for p in (eth, wc):
                    if p is not None:
                        p.unlink(missing_ok=True)   # shared tiles are fetched again by the next region
        for a in acc.values():
            a.flush()
        finish(g, acc, g.root / "veg60.npy", g.root / "veg_cc30.npy")
        del acc
        import gc
        import shutil
        gc.collect()
        shutil.rmtree(stage, ignore_errors=True)
        print(f"{g.name}: {len(tiles)} tiles in {time.time() - tg:.0f}s (total {time.time() - t0:.0f}s)", flush=True)
    print("done", flush=True)


if __name__ == "__main__":
    main()
