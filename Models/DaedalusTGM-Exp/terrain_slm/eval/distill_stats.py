"""Distillation statistics: one set of terrain measurements for DaedalusTGM-Exp tiles and Standard dumps.

    python -m terrain_slm.eval.distill_stats DIR [DIR ...] [--json out.json] [--sheet out.png]

DIR holds either `distill_sample` .npz tiles (DaedalusTGM-Exp) or `StandardTerrainDump` .bin tiles
(Standard, Cenda FastNoise2); both are 256 x 256 block-height grids, row = x, at lod 1 (fine, one
block per sample) or lod 8 (coarse, 8 blocks per sample). Biomes are compared as game BiomeType names
(Daedalus ids through a copy of DiffusionBiomeMapper.landBiomeFor). With several dirs the first is
the TARGET and the rest are scored against it (`distance`, the fit's objective).

Everything is measured in game blocks, so the two worlds are comparable as players see them.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi

SEA = 64
N = 256
# The cubic world's column [-384, 640) (2026-11-18); the 2026-11-01 sample lived in [0, 256), whose heights fit too.
Y_MIN, Y_MAX = -384, 640

# DiffusionBiomeMapper.landBiomeFor (stonebreak-game), Daedalus id -> BiomeType name. Beach bands are a
# Java-side post-process for both generators and are left out of both.
_DAEDALUS_BIOME = {
    41: "OCEAN", 44: "OCEAN", 46: "OCEAN", 48: "ICE_FIELDS",
    1: "PLAINS", 8: "PLAINS", 23: "PLAINS", 3: "SNOWY_PLAINS", 116: "SNOWY_PLAINS", 115: "MEADOW",
    5: "DESERT", 205: "RED_SAND_DESERT", 6: "SWAMP", 29: "MEADOW", 108: "MEADOW", 15: "TAIGA", 16: "TAIGA",
    17: "PLAINS", 19: "STONY_PEAKS", 35: "STONY_PEAKS", 26: "BADLANDS", 31: "TUNDRA",
    32: "ICE_FIELDS", 33: "ICE_FIELDS",
}

LAND_Q = (0.05, 0.25, 0.5, 0.75, 0.9, 0.95, 0.99, 0.999)
SEA_Q = (0.05, 0.5, 0.95)
SLOPE_Q = (0.5, 0.75, 0.9, 0.95, 0.99)
ELEV_BANDS = ((64, 80), (80, 110), (110, 150), (150, 256))
COARSE_BANDS = (16, 32, 64, 128, 256, 512, 1024, 2048)     # wavelength edges, blocks
FINE_BANDS = (2, 4, 8, 16, 32, 64, 128, 256)


# ----------------------------------------------------------------------------- loading
def load_tile(path: Path, biome_names: dict[int, str] | None):
    """(height int array, biome name array or None, lod)."""
    if path.suffix == ".npz":
        z = np.load(path)
        lod = int(z["meta"][3])
        ids = z["biome"]
        lut = np.vectorize(lambda i: _DAEDALUS_BIOME.get(int(i), "PLAINS"), otypes=[object])
        return z["height"].astype(np.int32), lut(ids), lod
    raw = path.read_bytes()
    n = int(np.frombuffer(raw[:4], "<i4")[0])
    h = np.frombuffer(raw[4:4 + 2 * n * n], "<i2").reshape(n, n).astype(np.int32)
    b = np.frombuffer(raw[4 + 2 * n * n:4 + 4 * n * n], "<i2").reshape(n, n)
    lod = int(path.stem.rsplit("_l", 1)[1])
    if (b < 0).all():                       # StandardNativeDumpTest: heights only
        return h, None, lod
    names = np.array([biome_names[i] for i in range(max(biome_names) + 1)], dtype=object)
    return h, names[b], lod


def tiles(d: Path):
    files = sorted(d.glob("*.npz")) or sorted(d.glob("*.bin"))
    names = None
    if (d / "biomes.txt").exists():
        names = {int(a): b for a, b in (l.split() for l in (d / "biomes.txt").read_text().splitlines() if l.strip())}
    for f in files:
        yield (f, *load_tile(f, names))


# ----------------------------------------------------------------------------- measurements
def band_rms(h: np.ndarray, block_per_sample: int, edges) -> list[float]:
    """RMS amplitude (blocks) of h in wavelength bands [edges[k], edges[k+1]), Hann-windowed."""
    n = h.shape[0]
    w = np.hanning(n)
    win = np.outer(w, w)
    x = (h - h.mean()) * win
    p = np.abs(np.fft.fft2(x)) ** 2 / (win ** 2).sum() / (n * n)
    f = np.fft.fftfreq(n)
    fr = np.hypot(f[:, None], f[None, :])
    with np.errstate(divide="ignore"):
        wl = np.where(fr > 0, block_per_sample / fr, np.inf)
    return [float(np.sqrt(p[(wl >= lo) & (wl < hi)].sum())) for lo, hi in zip(edges[:-1], edges[1:])]


def elongation(mask: np.ndarray, min_area: int) -> tuple[float, int]:
    """Area-weighted mean sqrt(major/minor) eigenvalue ratio of the mask's connected components."""
    lab, k = ndi.label(mask)
    if k == 0:
        return float("nan"), 0
    ratios, weights = [], []
    for sl_i, sl in enumerate(ndi.find_objects(lab), start=1):
        ys, xs = np.nonzero(lab[sl] == sl_i)
        if ys.size < min_area:
            continue
        ev = np.linalg.eigvalsh(np.cov(np.stack([ys, xs]).astype(np.float64)))
        ratios.append(math.sqrt(max(ev[1], 1e-9) / max(ev[0], 0.25)))
        weights.append(ys.size)
    if not ratios:
        return float("nan"), 0
    return float(np.average(ratios, weights=weights)), len(ratios)


def census(elev: np.ndarray, out: dict) -> None:
    """eval/terrain_census on a lod-8 tile (8 blocks = 480 m per sample at 1:4), in metres: local relief in ~10 km
    (21 samples) and ~2.4 km (5 samples) windows. hills = land below 1000 m with 2.4 km relief 60..400 m, flat = < 30 m,
    epic = 10 km relief > 2000 m, big = > 1000 m (shares of land; the window's own tile only, edges cropped)."""
    e = elev.astype(np.float64)
    r10 = (ndi.maximum_filter(e, 21) - ndi.minimum_filter(e, 21))[10:-10, 10:-10]
    r24 = (ndi.maximum_filter(e, 5) - ndi.minimum_filter(e, 5))[10:-10, 10:-10]
    ec = e[10:-10, 10:-10]
    land = ec > 0
    low = land & (ec < 1000)
    out.setdefault("c_land", []).append(int(land.sum()))
    out.setdefault("c_hills", []).append(int((low & (r24 >= 60) & (r24 <= 400)).sum()))
    out.setdefault("c_flat", []).append(int((low & (r24 < 30)).sum()))
    out.setdefault("c_epic", []).append(int((land & (r10 > 2000)).sum()))
    out.setdefault("c_big", []).append(int((land & (r10 > 1000)).sum()))


def coarse_metrics(h: np.ndarray, biome: np.ndarray, out: dict, elev: np.ndarray | None = None) -> None:
    land = h >= SEA
    out.setdefault("land", []).append(land.ravel())
    out.setdefault("h_land", []).append(h[land])
    out.setdefault("h_sea", []).append(h[~land])
    out.setdefault("hist", []).append(np.bincount((np.clip(h, Y_MIN, Y_MAX - 1) - Y_MIN).ravel(),
                                                  minlength=Y_MAX - Y_MIN))
    out.setdefault("spec_coarse", []).append(band_rms(h.astype(np.float64), 8, COARSE_BANDS))
    # coastline: land/sea 4-neighbour edges per sample (complexity of the land shape), on a mask smoothed by
    # one sample so area-averaged (Daedalus lod 8) and point-sampled (noise) tiles are measured alike
    sm = ndi.gaussian_filter(h.astype(np.float64), 1.0) >= SEA
    edges = (sm[1:] != sm[:-1]).sum() + (sm[:, 1:] != sm[:, :-1]).sum()
    out.setdefault("coast", []).append(edges / land.size)
    # mountain chains: band-passed highs (256..2048 blocks) above +1 std, on land
    bp = ndi.gaussian_filter(h.astype(np.float64), 4) - ndi.gaussian_filter(h.astype(np.float64), 32)
    if land.mean() > 0.3:
        thr = bp[land].mean() + bp[land].std()
        e, k = elongation((bp > thr) & land, 12)
        if not math.isnan(e):
            out.setdefault("chain_elong", []).append(e)
            out.setdefault("chain_count", []).append(k)
        # top 10% of land by height (the high ground itself)
        hi = h >= np.quantile(h[land], 0.9)
        e, k = elongation(hi & land, 12)
        if not math.isnan(e):
            out.setdefault("high_elong", []).append(e)
    if elev is not None:
        census(elev, out)
    if biome is None:
        return
    for name in np.unique(biome):
        m = biome == name
        out.setdefault("biome_n", {}).setdefault(name, 0)
        out["biome_n"][name] += int(m.sum())
        lab, k = ndi.label(m)
        sizes = np.bincount(lab.ravel())[1:]
        out.setdefault("biome_patch", {}).setdefault(name, []).extend(sizes.tolist())


def fine_metrics(h: np.ndarray, biome: np.ndarray, out: dict, water: np.ndarray | None = None) -> None:
    land = h >= SEA
    f = h.astype(np.float64)
    s = np.maximum(np.abs(np.diff(f, axis=0))[:, :-1], np.abs(np.diff(f, axis=1))[:-1, :])
    sl = s[land[:-1, :-1]]
    out.setdefault("slope", []).append(sl)
    rel = f - ndi.uniform_filter(f, 9, mode="nearest")
    out.setdefault("rel8", []).append(rel[land])
    out.setdefault("rel8_h", []).append(h[land])
    rng = ndi.maximum_filter(h, 16) - ndi.minimum_filter(h, 16)
    out.setdefault("rng16", []).append(rng[land])
    out.setdefault("spec_fine", []).append(band_rms(f, 1, FINE_BANDS))
    hp = f - ndi.gaussian_filter(f, 16, mode="nearest")
    out.setdefault("hp16", []).append(hp[land])
    # closed depressions ("orange peel" vs drained, dendritic relief): a column whose ring at radius 6..8 blocks
    # stands everywhere >= 2 blocks above it sits in a pit; eroded land drains, so almost none do
    yy, xx = np.mgrid[-8:9, -8:9]
    rr = np.hypot(yy, xx)
    ring = (rr >= 6) & (rr <= 8)
    rmin = ndi.minimum_filter(f, footprint=ring, mode="nearest")
    core = np.zeros_like(land)
    core[8:-8, 8:-8] = True
    out.setdefault("pit", []).append(((rmin - f >= 2) & land & core)[core & land])
    # lowland block mix (eval/lowland_flatness): 9x9 block range of dry lowland (sea < h <= sea + 40)
    rel9 = ndi.maximum_filter(h, 9) - ndi.minimum_filter(h, 9)
    low = (h > SEA) & (h <= SEA + 40) & core
    if water is not None:
        low &= ~((water != -1) & (water > h))
    out.setdefault("low_rel9", []).append(rel9[low])
    # sea: depth and block-flat (9x9 range <= 1) by distance to the nearest dry column, in blocks
    wet = h < SEA
    if wet.any() and (~wet).any():
        dist = ndi.distance_transform_edt(wet)
        sea = wet & core
        out.setdefault("sea_depth", []).append((SEA - h)[sea])
        out.setdefault("sea_dist", []).append(dist[sea])
        out.setdefault("sea_flat", []).append((rel9 <= 1)[sea])


def dir_curve(d: Path):
    """The height curve a sample dir was built with (`curve.json`: {"scale": "1:4", "version": 2}), or None."""
    f = d / "curve.json"
    if not f.exists():
        return None
    import json as _json
    c = _json.loads(f.read_text())
    return game_curve(c.get("scale", "1:4"), int(c.get("version", 1)))


def game_curve(scale: str = "1:4", version: int = 2):
    from terrain_slm.eval.distill_sample import game_world
    return game_world(scale, version).curve()


def measure(d: Path) -> dict:
    curve = dir_curve(d)

    def items():
        for f, h, b, lod in tiles(d):
            water = np.load(f)["water"].astype(np.int32) if f.suffix == ".npz" else None
            yield h, b, lod, None, water
    return measure_tiles(items(), str(d), curve)


def measure_tiles(items, label: str, curve=None) -> dict:
    """`items`: (height, biome names or None, lod) per tile, or (height, biome, lod, elevation metres or None[, water]).
    `curve` converts block heights to metres for the census when a tile carries no elevation."""
    coarse, fine = {}, {}
    nc = nf = 0
    for item in items:
        nc_, nf_ = accumulate(item, coarse, fine, curve)
        nc += nc_
        nf += nf_
    return summarize(coarse, fine, nc, nf, label)


def accumulate(item, coarse: dict, fine: dict, curve=None) -> tuple[int, int]:
    """Adds one tile's measurements to the (coarse, fine) accumulators; returns (coarse tiles, fine tiles) added."""
    h, b, lod = item[:3]
    elev = item[3] if len(item) > 3 else None
    water = item[4] if len(item) > 4 else None
    if elev is None and curve is not None and lod == 8:
        elev = curve.to_elevation(h.astype(np.float64) + 0.5)
    if lod == 8:
        coarse_metrics(h, b, coarse, elev)
        return 1, 0
    if lod == 1:
        fine_metrics(h, b, fine, water)
        return 0, 1
    return 0, 0


def merge(into: dict, part: dict) -> None:
    """Merges accumulator `part` into `into` (lists extend, biome counters add)."""
    for k, v in part.items():
        if isinstance(v, list):
            into.setdefault(k, []).extend(v)
        elif isinstance(v, dict):
            d = into.setdefault(k, {})
            for kk, vv in v.items():
                if isinstance(vv, list):
                    d.setdefault(kk, []).extend(vv)
                else:
                    d[kk] = d.get(kk, 0) + vv


def summarize(coarse: dict, fine: dict, nc: int, nf: int, label: str) -> dict:
    r: dict = {"dir": label, "tiles_coarse": nc, "tiles_fine": nf}
    if nc:
        hl = np.concatenate(coarse["h_land"])
        hs = np.concatenate(coarse["h_sea"])
        r["land_frac"] = float(np.concatenate(coarse["land"]).mean())
        r["land_q"] = dict(zip(map(str, LAND_Q), np.quantile(hl, LAND_Q).tolist()))
        r["sea_q"] = dict(zip(map(str, SEA_Q), np.quantile(hs, SEA_Q).tolist())) if hs.size else {}
        hist = np.sum(coarse["hist"], axis=0).astype(np.float64)
        r["hypsometry"] = (hist / hist.sum()).tolist()
        r["spec_coarse"] = dict(zip([f"{a}-{b}" for a, b in zip(COARSE_BANDS[:-1], COARSE_BANDS[1:])],
                                    np.sqrt(np.mean(np.square(coarse["spec_coarse"]), axis=0)).tolist()))
        r["coast_edges_per_sample"] = float(np.mean(coarse["coast"]))
        r["chain_elongation"] = float(np.nanmean(coarse.get("chain_elong", [np.nan])))
        r["chains_per_tile"] = float(np.mean(coarse.get("chain_count", [0])))
        r["high_ground_elongation"] = float(np.nanmean(coarse.get("high_elong", [np.nan])))
        if coarse.get("c_land"):
            nl = max(1, sum(coarse["c_land"]))
            r["census"] = {k: sum(coarse["c_" + k]) / nl for k in ("hills", "flat", "big", "epic")}
    if nc and "biome_n" in coarse:
        tot = sum(coarse["biome_n"].values())
        r["biome_share"] = {k: v / tot for k, v in sorted(coarse["biome_n"].items(), key=lambda kv: -kv[1])}
        # area-weighted mean patch size, in blocks^2 (coarse samples are 8x8 blocks)
        r["biome_patch_km_blocks"] = {
            k: float(np.sum(np.square(v)) / max(1, np.sum(v)) * 64) for k, v in coarse["biome_patch"].items()}
    if nf:
        sl = np.concatenate(fine["slope"])
        r["slope_q"] = dict(zip(map(str, SLOPE_Q), np.quantile(sl, SLOPE_Q).tolist()))
        r["step_ge"] = {str(k): float((sl >= k).mean()) for k in (1, 2, 4, 8)}
        rel = np.concatenate(fine["rel8"])
        rh = np.concatenate(fine["rel8_h"])
        r["relief8_std"] = float(rel.std())
        r["relief8_by_elev"] = {f"{lo}-{hi}": (float(rel[(rh >= lo) & (rh < hi)].std())
                                               if ((rh >= lo) & (rh < hi)).sum() > 1000 else None)
                                for lo, hi in ELEV_BANDS}
        rg = np.concatenate(fine["rng16"])
        r["range16_ge"] = {str(k): float((rg >= k).mean()) for k in (4, 8, 16, 32)}
        r["spec_fine"] = dict(zip([f"{a}-{b}" for a, b in zip(FINE_BANDS[:-1], FINE_BANDS[1:])],
                                  np.sqrt(np.mean(np.square(fine["spec_fine"]), axis=0)).tolist()))
        hp = np.concatenate(fine["hp16"])
        r["hp16_skew"] = float(((hp - hp.mean()) ** 3).mean() / max(hp.std() ** 3, 1e-9))
        r["pit_share"] = float(np.concatenate(fine["pit"]).mean())
        lr = np.concatenate(fine["low_rel9"])
        r["lowland_mix"] = {"flat": float((lr <= 1).mean()), "gentle": float(((lr >= 2) & (lr <= 3)).mean()),
                            "rolling": float(((lr >= 4) & (lr <= 8)).mean()), "hilly": float((lr >= 9).mean())} \
            if lr.size else {}
        if fine.get("sea_depth"):
            dep = np.concatenate(fine["sea_depth"])
            dist = np.concatenate(fine["sea_dist"])
            fl = np.concatenate(fine["sea_flat"])
            r["sea_flat"] = float(fl.mean())
            r["sea_shallow3"] = float((dep < 3).mean())
            bins = ((0, 8), (8, 33), (33, 83), (83, 250))     # blocks at 1:4 = 0-0.5 / 0.5-2 / 2-5 / 5-15 km
            r["sea_depth_by_km"] = {f"{a}-{b}": (float(np.median(dep[(dist >= a) & (dist < b)]))
                                                  if ((dist >= a) & (dist < b)).sum() > 200 else None) for a, b in bins}
            r["sea_flat_by_km"] = {f"{a}-{b}": (float(fl[(dist >= a) & (dist < b)].mean())
                                                 if ((dist >= a) & (dist < b)).sum() > 200 else None) for a, b in bins}
    return r


# ----------------------------------------------------------------------------- comparison
#: Score the 2026-11-19 terms too (census, pits, lowland mix, sea). False = the 2026-11-01 distance (14.2 -> 6.76).
WIDE = True


def distance(t: dict, s: dict) -> dict:
    """Per-group mismatch (dimensionless, 0 = identical) and the weighted total the fit minimises."""
    g = {}
    if "land_frac" in t and "land_frac" in s:
        g["land"] = abs(t["land_frac"] - s["land_frac"]) / 0.1
        ht, hs = np.cumsum(t["hypsometry"]), np.cumsum(s["hypsometry"])
        g["hypsometry"] = float(np.abs(ht - hs).sum()) / 10.0              # W1 in blocks / 10
        qt, qs = np.array(list(t["land_q"].values())), np.array(list(s["land_q"].values()))
        g["summits"] = float(np.abs(qt[-3:] - qs[-3:]).mean()) / 10.0     # p95 / p99 / p99.9 heights
        lt = np.log(np.array(list(t["spec_coarse"].values())) + 1e-3)
        ls = np.log(np.array(list(s["spec_coarse"].values())) + 1e-3)
        g["spectrum_coarse"] = float(np.abs(lt - ls).mean()) / math.log(2)  # mean |log2 ratio|
        g["chains"] = abs(t["chain_elongation"] - s["chain_elongation"]) / 0.5
        g["coast"] = abs(math.log(t["coast_edges_per_sample"] / max(s["coast_edges_per_sample"], 1e-6))) / math.log(2)
    if "biome_share" in t and "biome_share" in s:
        bt, bs = t["biome_share"], s["biome_share"]
        g["biomes"] = 0.5 * sum(abs(bt.get(k, 0) - bs.get(k, 0)) for k in set(bt) | set(bs)) / 0.1
    if "slope_q" in t and "slope_q" in s:
        lt = np.log(np.array(list(t["spec_fine"].values())) + 1e-3)
        ls = np.log(np.array(list(s["spec_fine"].values())) + 1e-3)
        g["spectrum_fine"] = float(np.abs(lt - ls).mean()) / math.log(2)
        g["steps"] = float(np.mean([abs(t["step_ge"][k] - s["step_ge"][k]) for k in t["step_ge"]])) / 0.05
        g["relief"] = abs(math.log(t["relief8_std"] / max(s["relief8_std"], 1e-6))) / math.log(2)
        g["skew"] = abs(t["hp16_skew"] - s["hp16_skew"]) / 0.5          # peaky vs V-valleyed relief
        g["cliffs"] = abs(math.log((t["step_ge"]["4"] + 1e-4) / (s["step_ge"]["4"] + 1e-4))) / math.log(4)
    # 2026-11-19 (EXP-2 distillation): landform census, closed depressions, lowland block mix, the coastal sea
    if WIDE and "census" in t and "census" in s:
        ct, cs = t["census"], s["census"]
        g["census"] = sum(abs(ct[k] - cs[k]) for k in ("hills", "flat", "epic")) / 0.1
    if WIDE and "pit_share" in t and "pit_share" in s:
        g["pits"] = abs(math.log((t["pit_share"] + 1e-3) / (s["pit_share"] + 1e-3))) / math.log(2)
    if WIDE and t.get("lowland_mix") and s.get("lowland_mix"):
        g["lowland_mix"] = 0.5 * sum(abs(t["lowland_mix"][k] - s["lowland_mix"][k]) for k in t["lowland_mix"]) / 0.1
    if WIDE and "sea_flat" in t and "sea_flat" in s:
        dt, ds = t["sea_depth_by_km"], s["sea_depth_by_km"]
        ks = [k for k in dt if dt[k] is not None and ds.get(k) is not None]
        dd = float(np.mean([abs(math.log((dt[k] + 1) / (ds[k] + 1))) for k in ks])) / math.log(2) if ks else 0.0
        g["sea"] = dd + abs(t["sea_flat"] - s["sea_flat"]) / 0.1
    g["total"] = float(sum(g.values()))
    return g


def _fmt(v):
    if isinstance(v, float):
        return f"{v:.3g}"
    if v is None:
        return "-"
    return str(v)


def print_table(results: list[dict]) -> None:
    keys = [k for k in results[0] if k not in ("dir", "hypsometry", "biome_patch_km_blocks")]
    names = [Path(r["dir"]).name for r in results]
    print(f"{'metric':34s}" + "".join(f"{n[:22]:>24s}" for n in names))
    for k in keys:
        v0 = results[0].get(k)
        if isinstance(v0, dict):
            sub = list(dict.fromkeys([s for r in results for s in r.get(k, {})]))
            for s in sub:
                print(f"{(k + '.' + s)[:34]:34s}" + "".join(f"{_fmt(r.get(k, {}).get(s)):>24s}" for r in results))
        else:
            print(f"{k[:34]:34s}" + "".join(f"{_fmt(r.get(k)):>24s}" for r in results))


def sheet(results: list[dict], path: Path) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(1, 3, figsize=(16, 4.5))
    for r in results:
        n = Path(r["dir"]).name
        if "hypsometry" in r:
            ax[0].plot(np.cumsum(r["hypsometry"]), label=n)
            ks = list(r["spec_coarse"])
            ax[1].plot(range(len(ks)), list(r["spec_coarse"].values()), marker="o", label=n)
            ax[1].set_xticks(range(len(ks)), ks, rotation=30)
        if "spec_fine" in r:
            ks = list(r["spec_fine"])
            ax[2].plot(range(len(ks)), list(r["spec_fine"].values()), marker="o", label=n)
            ax[2].set_xticks(range(len(ks)), ks, rotation=30)
    ax[0].set_title("hypsometry (CDF of block height)")
    ax[0].axvline(SEA, color="gray", lw=0.5)
    ax[1].set_title("coarse band RMS (blocks)")
    ax[1].set_yscale("log")
    ax[2].set_title("fine band RMS (blocks)")
    ax[2].set_yscale("log")
    for a in ax:
        a.legend(fontsize=7)
    fig.tight_layout()
    fig.savefig(path, dpi=110)


def main(argv=None) -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--json")
    ap.add_argument("--sheet")
    args = ap.parse_args(argv)
    results = [measure(Path(d)) for d in args.dirs]
    print_table(results)
    if len(results) > 1:
        for r in results[1:]:
            print(f"\ndistance {Path(r['dir']).name} -> {Path(results[0]['dir']).name}:",
                  json.dumps({k: round(v, 3) for k, v in distance(results[0], r).items()}))
    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=1))
    if args.sheet:
        sheet(results, Path(args.sheet))


if __name__ == "__main__":
    main()
