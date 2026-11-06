"""Calibrate BiomeSelector's shape thresholds on real StandardTerrainDump tiles of the distilled shape.

    python -m terrain_slm.eval.distill_biomes /mnt/extrastorage/terrain-slm-data/distill/standard_v1

Targets (until the climate itself is distilled):
  mountain class (STONY_PEAKS & co)  covers the land share that stands at or above MOUNTAIN_Y
  hill class                         covers the land share in [HILL_Y, MOUNTAIN_Y)
  swamp                              SWAMP_LAND_SHARE of land (DaedalusTGM-Exp v4: 5.25% of all = ~7% of land)

Reads the dump's float planes (land, mountain, hilly, rough, tRaw, mRaw) and replays the selector's
precedence (coast -> mountain -> hill -> flat/swamp) in numpy; prints the thresholds to paste.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np

SEA = 64
MOUNTAIN_Y = 150
HILL_Y = 110
SWAMP_LAND_SHARE = 0.055   # DaedalusTGM-Exp v4 sample: 4.26% of all columns at 78% land
# fixed selector constants (BiomeSelector.java)
COAST_MAX_Y, COAST_MAX_LAND, BEACH_MAX_MOUNTAIN = SEA + 2, 0.999, 0.15
T_COLD, M_WET = 0.38, 0.65
SWAMP_MAX_Y, SWAMP_MAX_MOUNTAIN = 80, 0.1
CHILL_START_Y, CHILL = 110, 250.0   # NoiseRouter altitude chill


def load(d: Path, lod: int):
    out = {k: [] for k in ("h", "land", "mountain", "hilly", "rough", "t", "m")}
    for f in sorted(d.glob(f"*_l{lod}.bin")):
        raw = f.read_bytes()
        n = int(np.frombuffer(raw[:4], "<i4")[0])
        c = n * n
        h = np.frombuffer(raw[4:4 + 2 * c], "<i2").astype(np.int32)
        fl = np.frombuffer(raw[4 + 4 * c:], "<f4").reshape(6, c)
        out["h"].append(h)
        for k, plane in zip(("land", "mountain", "hilly", "rough"), fl[:4]):
            out[k].append(plane)
        out["t"].append(np.clip(fl[4] * 0.5 + 0.5 - np.maximum(h - CHILL_START_Y, 0) / CHILL, 0, 1))  # final ~ shaped
        out["m"].append(fl[5] * 0.5 + 0.5)
    return {k: np.concatenate(v) for k, v in out.items()}


def bisect(f, target, lo=0.0, hi=1.0, increasing=True):
    for _ in range(50):
        mid = (lo + hi) / 2
        if (f(mid) < target) == increasing:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def main(argv=None) -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("dump")
    ap.add_argument("--lod", type=int, default=8)
    args = ap.parse_args(argv)
    a = load(Path(args.dump), args.lod)
    h = a["h"]
    dry = h >= SEA
    n_land = dry.sum()
    coastal = dry & (h <= COAST_MAX_Y) & (a["land"] < COAST_MAX_LAND) & (a["mountain"] < BEACH_MAX_MOUNTAIN)
    inland = dry & ~coastal

    target_m = (h[dry] >= MOUNTAIN_Y).mean()
    target_h = ((h[dry] >= HILL_Y) & (h[dry] < MOUNTAIN_Y)).mean()
    mountain_min = bisect(lambda t: (inland & (a["mountain"] >= t)).sum() / n_land, target_m, increasing=False)
    rest = inland & (a["mountain"] < mountain_min)
    # half the hill share from range flanks, half from hill-country provinces
    hill_min_mountain = bisect(lambda t: (rest & (a["mountain"] >= t)).sum() / n_land, target_h / 2,
                               hi=mountain_min, increasing=False)
    flank = rest & (a["mountain"] >= hill_min_mountain)
    hill_min_hilly = bisect(lambda t: (rest & ~flank & (a["hilly"] >= t)).sum() / n_land, target_h / 2,
                            hi=1.01, increasing=False)
    is_h = flank | (rest & (a["hilly"] >= hill_min_hilly))
    flat = rest & ~is_h
    base = flat & (h <= SWAMP_MAX_Y) & (a["mountain"] < SWAMP_MAX_MOUNTAIN) & (a["t"] >= T_COLD) & (a["m"] >= M_WET)
    swamp_max_rough = bisect(lambda t: (base & (a["rough"] < t)).sum() / n_land, SWAMP_LAND_SHARE, hi=1.0001)
    print(f"land samples {n_land}; beach {coastal.sum() / n_land:.4f} of land; swamp ceiling {base.sum() / n_land:.4f}")
    print(f"targets: mountain {target_m:.4f}, hill {target_h:.4f}, swamp {SWAMP_LAND_SHARE:.4f} (of land)")
    print(f"MOUNTAIN_MIN      = {mountain_min:.3f}f  -> {(inland & (a['mountain'] >= mountain_min)).sum() / n_land:.4f}")
    print(f"HILL_MIN_MOUNTAIN = {hill_min_mountain:.3f}f")
    print(f"HILL_MIN_HILLY    = {hill_min_hilly:.3f}f  -> hill {is_h.sum() / n_land:.4f}")
    print(f"SWAMP_MAX_ROUGH   = {swamp_max_rough:.3f}f  -> swamp {(base & (a['rough'] < swamp_max_rough)).sum() / n_land:.4f}")


if __name__ == "__main__":
    main()
