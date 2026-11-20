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
    ap.add_argument("dump", nargs="?")
    ap.add_argument("--lod", type=int, default=8)
    ap.add_argument("--v2", help="Standard shape 2: the shape fit's params json (fits the climate to --target)")
    ap.add_argument("--target", help="--v2: the DaedalusTGM-Exp-2 sample dir")
    ap.add_argument("--coarse", type=int, default=144)
    ap.add_argument("--iters", type=int, default=1500)
    ap.add_argument("--restarts", type=int, default=3)
    ap.add_argument("--out")
    ap.add_argument("--write", action="store_true", help="--v2: write the knobs into shape.cpp (BIOMES_V2)")
    args = ap.parse_args(argv)
    if args.v2:
        return v2_main(args)
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



# ============================================================================ Standard shape 2 (2026-11-19)
# The climate of Standard shape 2 worlds, distilled from DaedalusTGM-Exp-2: instead of calibrating the shape thresholds
# against elevation bands (v1, above), every knob of the native selector (shape.cpp selectBiome + temperatureV2 /
# moistureV2) is fitted so the biome mix of a Standard shape-2 world matches the EXP-2 sample's (coarse tiles, all
# columns; beaches - a post-process on both sides - left out). The planes come straight from the fit's own model
# (distill_fit.distilled_elevation + the game's FastNoise climate channels), not from a Java dump.
#
#   python -m terrain_slm.eval.distill_biomes --v2 /mnt/extrastorage/terrain-slm-data/distill/sd/fit_v2b/best.json \
#       --target /mnt/extrastorage/terrain-slm-data/distill/exp2-r3 [--write]

V2_KNOBS = ("t_scale", "t_offset", "m_scale", "m_offset", "mountain_min", "hill_min_mountain", "hill_min_hilly",
            "swamp_max_rough", "chill_start", "chill_factor")
V2_START = (0.5, 0.5, 0.5, 0.5, 0.617, 0.46, 0.237, 0.3, 110.0, 250.0)
# Bounds: the climate maps may sharpen a lot (scale up to 6: EXP-2's mix of wet meadows and arid deserts needs a
# steep moisture map from one symmetric noise); the shape thresholds stay where the classes keep a meaning (a first
# run with 0.02 for hill_min_hilly turned all land into "hills", whose rules have no desert, and lost the deserts).
V2_BOUNDS = ((0.2, 6.0), (-1.5, 2.0), (0.2, 6.0), (-1.5, 2.0), (0.4, 0.95), (0.3, 0.9), (0.15, 0.98), (0.1, 1.0),
             (70.0, 200.0), (60.0, 600.0))
HIGH_Y = 150   # "high ground" (~2.2 km at 1:4): EXP-2's snow line / bare rock / treeline (FX4) is matched here too
T_FREEZING, T_WARM, T_HOT, M_ARID, M_DRY = 0.20, 0.62, 0.78, 0.25, 0.45
PLATEAU_MAX_ROUGH = 0.45


def v2_planes(params_json: Path, target: Path, coarse: int, step: int = 8):
    """Biome inputs of the shape-2 fit at the target's coarse tile positions (lod 8, like the sample)."""
    import json
    from terrain_slm.eval import distill_fit as F
    from terrain_slm.eval.cenda_noise import Fbm
    p = F.Params(**json.loads(params_json.read_text()))
    spec = [m for m in F.spec_from_target(target, coarse, 0)]
    temp = Fbm(6, 0.40, 1 / 2000.0, 2.0)
    moist = Fbm(6, 0.45, 1 / 1500.0, 2.1)
    out = {k: [] for k in ("h", "shaped", "land", "mountain", "hilly", "rough", "t", "m")}
    for s, x, z, lod in spec:
        ch = F.Channels(p)
        pl = {}
        x0, z0 = x * 256 * lod, z * 256 * lod
        elev, det = F.distilled_elevation(p, ch, s, x0, z0, 256, lod, planes=pl)
        curve = F.CURVES[2]
        blocks = curve.to_block_height_exact(np.maximum(elev, -11000.0))
        h = np.clip(np.floor(blocks + p.detail_blocks * det), F.Y_LO, F.Y_HI)
        shaped = np.clip(np.floor(blocks), F.Y_LO, F.Y_HI)
        lake = (h < SEA) & (pl["land"] >= 0.999)
        h = np.where(lake & (h == SEA - 1), SEA, h)
        sub = (slice(None, None, 2), slice(None, None, 2))   # every 2nd sample per axis: 16 blocks, plenty
        out["h"].append(h[sub].ravel())
        out["shaped"].append(shaped[sub].ravel())
        for k in ("land", "mountain", "hilly", "rough"):
            out[k].append(np.broadcast_to(pl[k], h.shape)[sub].ravel())
        out["t"].append(temp.grid(s + 1, x0, z0, 256, 256, lod, -100000, -100000)[sub].ravel())
        out["m"].append(moist.grid(s, x0, z0, 256, 256, lod, 150000, 150000)[sub].ravel())
    return {k: np.concatenate(v).astype(np.float64) for k, v in out.items()}


BIOME_NAMES = ("PLAINS", "DESERT", "RED_SAND_DESERT", "SNOWY_PLAINS", "TUNDRA", "TAIGA", "STONY_PEAKS", "BEACH",
               "ICE_FIELDS", "BADLANDS", "MEADOW", "OCEAN", "SWAMP")
_B = {n: i for i, n in enumerate(BIOME_NAMES)}


def v2_select(a: dict, k) -> np.ndarray:
    """shape.cpp selectBiome (+ the shape-2 lake rule) in numpy; returns biome codes (BIOME_NAMES order)."""
    t_scale, t_offset, m_scale, m_offset, mountain_min, hill_min_mountain, hill_min_hilly, swamp_max_rough, \
        chill_start, chill_factor = k
    h, shaped = a["h"], a["shaped"]
    land, mtn, hilly, rough = a["land"], a["mountain"], a["hilly"], a["rough"]
    chill_start = int(round(chill_start))
    t = np.clip(a["t"] * t_scale + t_offset - np.maximum(h - chill_start, 0) / chill_factor, 0, 1)
    m = np.clip(a["m"] * m_scale + m_offset, 0, 1)
    lake = (h < SEA) & (land >= 0.999)
    sub = (h < SEA) & ~lake
    B = _B
    wet = m >= M_WET
    flat = np.select(
        [t < T_FREEZING, t < T_COLD, t < T_WARM, t < T_HOT],
        [np.where(wet, B["ICE_FIELDS"], B["TUNDRA"]), np.where(wet, B["TAIGA"], B["SNOWY_PLAINS"]),
         np.where(wet, B["MEADOW"], B["PLAINS"]),
         np.where(m < M_ARID, B["DESERT"], np.where(m < M_DRY, B["RED_SAND_DESERT"], B["PLAINS"]))],
        np.where(m < M_ARID, B["DESERT"], np.where(m < M_DRY, B["RED_SAND_DESERT"],
                                                   np.where(wet, B["PLAINS"], B["BADLANDS"]))))
    hill = np.select(
        [t < T_FREEZING, t < T_COLD, t < T_WARM],
        [B["TUNDRA"], np.where(wet, B["TAIGA"], B["SNOWY_PLAINS"]), np.where(wet, B["MEADOW"], B["PLAINS"])],
        np.where(m < M_ARID, B["BADLANDS"], np.where(m < M_DRY, B["RED_SAND_DESERT"], B["PLAINS"])))
    peak = np.select(
        [t < T_FREEZING, t < T_COLD, (t >= T_HOT) & (m < M_DRY)],
        [B["ICE_FIELDS"], np.where(rough < PLATEAU_MAX_ROUGH, B["SNOWY_PLAINS"], B["STONY_PEAKS"]), B["BADLANDS"]],
        B["STONY_PEAKS"])
    swamp = (shaped <= SWAMP_MAX_Y) & (rough < swamp_max_rough) & (mtn < SWAMP_MAX_MOUNTAIN) & (t >= T_COLD) & wet
    out = np.where(swamp, B["SWAMP"], flat)
    out = np.where((mtn >= hill_min_mountain) | (hilly >= hill_min_hilly), hill, out)
    out = np.where(mtn >= mountain_min, peak, out)
    beach = (shaped <= SEA + 2) & (land < COAST_MAX_LAND) & (mtn < BEACH_MAX_MOUNTAIN)
    out = np.where(beach & ~sub, np.where(t < T_COLD, B["ICE_FIELDS"], B["BEACH"]), out)
    out = np.where(sub, np.where(t < T_FREEZING, B["ICE_FIELDS"], B["OCEAN"]), out)
    return out


def shares(codes: np.ndarray) -> dict:
    n = np.bincount(codes.astype(np.int64), minlength=len(BIOME_NAMES)).astype(np.float64)
    n[_B["BEACH"]] = 0.0
    tot = n.sum()
    return {BIOME_NAMES[i]: float(v) / tot for i, v in enumerate(n) if v > 0}


def high_shares(target: Path) -> dict:
    """Biome mix of the EXP-2 sample's high ground (coarse tiles, y >= HIGH_Y)."""
    from terrain_slm.eval.distill_stats import _DAEDALUS_BIOME
    n = {}
    for f in sorted(target.glob("*_l8.npz")):
        z = np.load(f)
        b = z["biome"][z["height"] >= HIGH_Y]
        u, c = np.unique(b, return_counts=True)
        for i, k in zip(u, c):
            name = _DAEDALUS_BIOME.get(int(i), "PLAINS")
            n[name] = n.get(name, 0) + int(k)
    tot = max(1, sum(n.values()))
    return {k: v / tot for k, v in n.items()}


def v2_main(args) -> None:
    import json
    from scipy import optimize
    from terrain_slm.eval import distill_stats as DS
    target = Path(args.target)
    t = DS.measure(target)
    want = {k: v for k, v in t["biome_share"].items() if k != "BEACH"}
    want_high = high_shares(target)
    print("EXP-2 high ground (y >= %d) biome mix:" % HIGH_Y, {k: round(v, 3) for k, v in want_high.items()})
    a = v2_planes(Path(args.v2), target, args.coarse)
    high = a["h"] >= HIGH_Y

    def tv(x, y):
        return 0.5 * sum(abs(x.get(b, 0) - y.get(b, 0)) for b in set(x) | set(y)) / 0.1

    def dist(k):
        codes = v2_select(a, k)
        got = shares(codes)
        # the whole world's mix + the mountains' look (half weight) + every biome EXP-2 has at >= 1 % must exist
        # (at least a quarter of its share): TV alone happily trades a whole biome (the deserts) for the big ones
        missing = sum(1.0 for b, v in want.items() if v >= 0.01 and got.get(b, 0) < 0.25 * v)
        return tv(want, got) + 0.5 * tv(want_high, shares(codes[high])) + missing

    def obj(v):
        k = [lo + min(1, max(0, x)) * (hi - lo) for x, (lo, hi) in zip(v, V2_BOUNDS)]
        return dist(k)
    x0 = [(s - lo) / (hi - lo) for s, (lo, hi) in zip(V2_START, V2_BOUNDS)]
    print(f"start (v1 climate + thresholds): biomes {dist(V2_START):.3f}")
    best = None
    for restart in range(args.restarts):
        start = x0 if restart == 0 else np.clip(np.array(x0) + np.random.default_rng(restart).normal(0, 0.15, len(x0)), 0, 1)
        r = optimize.minimize(obj, start, method="Nelder-Mead", options={"maxfev": args.iters, "xatol": 1e-4, "fatol": 1e-4})
        if best is None or r.fun < best.fun:
            best = r
        print(f"restart {restart}: {r.fun:.3f}")
    k = [lo + min(1, max(0, x)) * (hi - lo) for x, (lo, hi) in zip(best.x, V2_BOUNDS)]
    codes = v2_select(a, k)
    got = shares(codes)
    got_high = shares(codes[high])
    print(f"fitted biomes distance {best.fun:.3f} (whole mix {tv(want, got):.3f}, high ground {tv(want_high, got_high):.3f})")
    for b in sorted(set(want_high) | set(got_high), key=lambda b: -want_high.get(b, 0)):
        print(f"  high {b:16s} exp2 {want_high.get(b, 0):.4f}  standard-v2 {got_high.get(b, 0):.4f}")
    for b in sorted(set(want) | set(got), key=lambda b: -want.get(b, 0)):
        print(f"  {b:16s} exp2 {want.get(b, 0):.4f}  standard-v2 {got.get(b, 0):.4f}")
    knobs = dict(zip(V2_KNOBS, k))
    print(json.dumps(knobs, indent=1))
    if args.out:
        Path(args.out).write_text(json.dumps({"knobs": knobs, "distance": best.fun, "shares": got, "target": want,
                                              "high": got_high, "target_high": want_high}, indent=1))
    if args.write:
        write_native(knobs)


def write_native(k: dict) -> None:
    from terrain_slm.paths import MODELS_DIR
    import re
    f = MODELS_DIR.parent / "stonebreak-game/native/src/shape.cpp"
    src = f.read_text()
    body = ("// BEGIN BIOMES_V2 (terrain_slm.eval.distill_biomes --v2)\n"
            f"constexpr BiomeParams BIOME_V2 = {{{k['mountain_min']:.4f}f, {k['hill_min_mountain']:.4f}f, "
            f"{k['hill_min_hilly']:.4f}f, {k['swamp_max_rough']:.4f}f}};\n"
            "/* temperature = raw * T_SCALE + T_OFFSET - altitude chill, moisture = raw * M_SCALE + M_OFFSET (v1: 0.5, 0.5) */\n"
            f"constexpr float T2_SCALE = {k['t_scale']:.4f}f, T2_OFFSET = {k['t_offset']:.4f}f, "
            f"M2_SCALE = {k['m_scale']:.4f}f, M2_OFFSET = {k['m_offset']:.4f}f;\n"
            f"constexpr int CHILL2_START_Y = {int(round(k['chill_start']))};\n"
            f"constexpr float CHILL2_FACTOR = {k['chill_factor']:.2f}f;\n"
            "// END BIOMES_V2")
    new, n = re.subn(r"// BEGIN BIOMES_V2.*?// END BIOMES_V2", lambda _: body, src, flags=re.S)
    if n != 1:
        raise SystemExit("BIOMES_V2 markers not found exactly once in shape.cpp")
    f.write_text(new)
    print("wrote", f)
    j = MODELS_DIR.parent / "stonebreak-game/src/main/java/com/stonebreak/world/generation/biomes/BiomeSelector.java"
    src = j.read_text()
    body = ("// BEGIN BIOMES_V2 (terrain_slm.eval.distill_biomes --v2)\n"
            f"    static final float MOUNTAIN_MIN_V2 = {k['mountain_min']:.4f}f, HILL_MIN_MOUNTAIN_V2 = "
            f"{k['hill_min_mountain']:.4f}f, HILL_MIN_HILLY_V2 = {k['hill_min_hilly']:.4f}f;\n"
            f"    static final float SWAMP_MAX_ROUGH_V2 = {k['swamp_max_rough']:.4f}f;\n"
            f"    public static final float T2_SCALE = {k['t_scale']:.4f}f, T2_OFFSET = {k['t_offset']:.4f}f, "
            f"M2_SCALE = {k['m_scale']:.4f}f, M2_OFFSET = {k['m_offset']:.4f}f;\n"
            f"    public static final int CHILL2_START_Y = {int(round(k['chill_start']))};\n"
            f"    public static final float CHILL2_FACTOR = {k['chill_factor']:.2f}f;\n"
            "    // END BIOMES_V2")
    new, n = re.subn(r"// BEGIN BIOMES_V2.*?// END BIOMES_V2", lambda _: body, src, flags=re.S)
    if n != 1:
        raise SystemExit("BIOMES_V2 markers not found exactly once in BiomeSelector.java")
    j.write_text(new)
    print("wrote", j)



if __name__ == "__main__":
    main()
