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


def coarse_metrics(h: np.ndarray, biome: np.ndarray, out: dict) -> None:
    land = h >= SEA
    out.setdefault("land", []).append(land.ravel())
    out.setdefault("h_land", []).append(h[land])
    out.setdefault("h_sea", []).append(h[~land])
    out.setdefault("hist", []).append(np.bincount(np.clip(h, 0, 255).ravel(), minlength=256))
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
    if biome is None:
        return
    for name in np.unique(biome):
        m = biome == name
        out.setdefault("biome_n", {}).setdefault(name, 0)
        out["biome_n"][name] += int(m.sum())
        lab, k = ndi.label(m)
        sizes = np.bincount(lab.ravel())[1:]
        out.setdefault("biome_patch", {}).setdefault(name, []).extend(sizes.tolist())


def fine_metrics(h: np.ndarray, biome: np.ndarray, out: dict) -> None:
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


def measure(d: Path) -> dict:
    return measure_tiles(((h, b, lod) for _, h, b, lod in tiles(d)), str(d))


def measure_tiles(items, label: str) -> dict:
    """`items`: (height, biome names or None, lod) per tile."""
    coarse, fine = {}, {}
    nc = nf = 0
    for h, b, lod in items:
        if lod == 8:
            coarse_metrics(h, b, coarse)
            nc += 1
        elif lod == 1:
            fine_metrics(h, b, fine)
            nf += 1
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
    return r


# ----------------------------------------------------------------------------- comparison
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
