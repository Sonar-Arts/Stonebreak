"""Distillation fit: retune a Daedalus-shaped noise formula for the Standard generator against DaedalusTGM-Exp.

    python -m terrain_slm.eval.distill_fit --target /mnt/extrastorage/terrain-slm-data/distill/v4 \
        --out reports/distill --iters 400

The candidate (`distilled_height`) is DaedalusTGM-Exp's own procedural control layer
(world/generator.py `procedural_controls_torch`: continents with a home bump, mountain ranges along
the zero lines of a domain-warped field, spurs inside the range body, rise and fall along the chain,
lowland hill provinces), written in BLOCK coordinates over the game's real FastNoise2
(`cenda_noise`), plus a roughness-scaled surrogate for what the learned stages add (V valleys on
the zero lines of an fbm, fine texture) and the game's metres -> blocks height curve. The fit
moves its knobs until `distill_stats.distance` to the Daedalus sample is smallest.

Domain warp is evaluated on a globally anchored lattice (`WARP_LATTICE` blocks) and B-spline
interpolated, so every column stays a pure function of its position (Java and C++ can reproduce it
bit for bit with single-point fills), with no position-array API needed from Cenda.
"""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import asdict, dataclass, fields, replace
from pathlib import Path

import numpy as np
from scipy import optimize

from terrain_slm.eval import distill_stats as DS
from terrain_slm.eval.cenda_noise import Fbm
from terrain_slm.world.world_config import WorldConfig

WARP_LATTICE = 16
EXACT_WARP = False   # True = the single-point path Java/C++ use (slow in Python); False = fit-speed interpolation
CURVE = WorldConfig.GAME.curve()


@dataclass(frozen=True)
class Params:
    # continents (Daedalus: fbm 900 cells = 3600 blocks, 4 octaves; land = smoothstep((c + 0.35) / 0.2))
    cont_period: float = 3600.0
    cont_offset: float = 0.35
    cont_width: float = 0.20
    home_radius: float = 600.0
    home_amp: float = 0.7
    # ranges (560 cells = 2240 blocks; warp 60 cells over 400-cell fields)
    range_period: float = 2240.0
    warp_period: float = 1600.0
    warp_amp: float = 240.0
    spine_width: float = 0.5
    spur_period: float = 520.0
    along_period: float = 3600.0
    along_bias: float = 0.2
    spine_m: float = 2800.0
    spur_m: float = 900.0
    foot_m: float = 500.0
    # lowland
    hills_period: float = 280.0
    hilly_period: float = 1400.0
    hill_bias: float = 0.8
    hill_softness: float = 2.4
    hill_plains_m: float = 100.0
    hill_country_m: float = 360.0
    land_base_m: float = 600.0
    sea_floor_m: float = -300.0
    # learned-relief surrogate
    rough_floor: float = 0.14
    rough_hill: float = 0.55
    valley_period: float = 600.0
    valley_m: float = 600.0          # at rough = 1
    valley_power: float = 1.0        # |v|^k: < 1 = narrow V floors between broad shoulders, > 1 = rounded bottoms
    texture_period: float = 48.0
    texture_m: float = 40.0          # at rough = 1
    texture_floor_m: float = 4.0     # at rough = 0
    # block-space fuzz (the old DETAIL_AMPLITUDE)
    detail_blocks: float = 1.0
    # summit soft cap (generator.soft_cap_peaks: 4300 / 850 m)
    peak_knee_m: float = 4300.0
    peak_span_m: float = 850.0


# Bounds for the optimiser, (lo, hi) per field, log-scaled when both are > 0 and span > 4x.
BOUNDS = {
    "cont_period": (1500, 9000), "cont_offset": (0.0, 0.7), "cont_width": (0.05, 0.6),
    "range_period": (900, 6000), "warp_period": (400, 4000), "warp_amp": (0, 600),
    "spine_width": (0.2, 1.2), "spur_period": (200, 1600), "along_period": (1200, 9000),
    "along_bias": (-0.8, 1.0), "spine_m": (800, 4500), "spur_m": (0, 2500), "foot_m": (0, 1500),
    "hills_period": (80, 1000), "hilly_period": (500, 4000), "hill_bias": (-0.5, 2.0),
    "hill_softness": (0.8, 5.0), "hill_plains_m": (0, 300), "hill_country_m": (50, 900),
    "land_base_m": (100, 1200), "sea_floor_m": (-900, -50),
    "rough_floor": (0.0, 0.5), "rough_hill": (0.0, 1.0), "valley_period": (150, 2500),
    "valley_m": (0, 1500), "valley_power": (0.4, 2.5), "texture_period": (12, 200), "texture_m": (0, 150), "texture_floor_m": (0, 30),
    "detail_blocks": (0, 3), "peak_knee_m": (2000, 4800), "peak_span_m": (200, 1500),
}

# Std of FastNoise2 simplex fbm (gain 0.5, lacunarity 2) by octave count, measured once over 6 seeds x
# 512^2 samples (spread < 0.3%) and frozen: the game carries these literals, so they must not drift.
FBM_STD = {2: 0.405, 3: 0.3555, 4: 0.3337}


def fbm_std(octaves: int) -> float:
    return FBM_STD[octaves]


def smoothstep(x):
    x = np.clip(x, 0.0, 1.0)
    return x * x * (3 - 2 * x)


# Port arithmetic (PORT_SAFE): Java and the native worldgen lib must agree bit for bit, and Java's
# Math.exp/pow and glibc's are not guaranteed to round alike. With PORT_SAFE the formula uses only
# + - * /, sqrt, abs and min/max (IEEE exact on both sides, the native lib is built -ffp-contract=off).
PORT_SAFE = True


def bell(u):
    """exp(-u) look-alike for u >= 0 (u = squared normalised distance): (1 - u/3)^3, 0 past u = 3."""
    if not PORT_SAFE:
        return np.exp(-u)
    t = np.maximum(1.0 - u / 3.0, 0.0)
    return t * t * t


def valley_shape(a, k):
    """a^k with k snapped to {0.5, 1, 1.5, 2} under PORT_SAFE (sqrt and products only)."""
    if not PORT_SAFE:
        return np.power(a, k)
    k = min((0.5, 1.0, 1.5, 2.0), key=lambda c: abs(c - k))
    if k == 0.5:
        return np.sqrt(a)
    if k == 1.0:
        return a
    if k == 1.5:
        return a * np.sqrt(a)
    return a * a


def bspline_weights(t):
    t2, t3 = t * t, t * t * t
    return np.stack([(1 - t) ** 3, 3 * t3 - 6 * t2 + 4, -3 * t3 + 3 * t2 + 3 * t + 1, t3]) / 6


class Channels:
    """Noise nodes for one Params (frequencies live in the nodes)."""

    def __init__(self, p: Params):
        self.cont = Fbm(4, 0.5, 1 / p.cont_period)
        self.warp = Fbm(2, 0.5, 1 / p.warp_period)
        self.range = Fbm(2, 0.5, 1 / p.range_period)
        self.spur = Fbm(2, 0.5, 1 / p.spur_period)
        self.along = Fbm(2, 0.5, 1 / p.along_period)
        self.hills = Fbm(4, 0.5, 1 / p.hills_period)
        self.hilly = Fbm(3, 0.5, 1 / p.hilly_period)
        self.valley = Fbm(4, 0.5, 1 / p.valley_period)
        self.texture = Fbm(4, 0.5, 1 / p.texture_period)
        self.detail = Fbm(3, 0.5, 1 / 45.0)


def warped_lattice_field(ch: Channels, p: Params, seed: int, x0: int, z0: int, n: int, step: int):
    """(range, spur) normalised fields at the n x n sample grid, through the warp lattice."""
    L = WARP_LATTICE
    xs = x0 + np.arange(n) * step
    zs = z0 + np.arange(n) * step
    li0, lj0 = xs[0] // L - 1, zs[0] // L - 1
    li1, lj1 = xs[-1] // L + 3, zs[-1] // L + 3
    gi, gj = np.arange(li0, li1) * L, np.arange(lj0, lj1) * L
    nx, nz = len(gi), len(gj)
    wx = ch.warp.grid(seed + 21, int(gi[0]), int(gj[0]), nx, nz, L)
    wz = ch.warp.grid(seed + 22, int(gi[0]), int(gj[0]), nx, nz, L)
    px = gi[:, None] + p.warp_amp * wx
    pz = gj[None, :] + p.warp_amp * wz
    if EXACT_WARP:
        rng = np.empty((nx, nz), np.float32)
        spr = np.empty((nx, nz), np.float32)
        # Single-point fills at warped positions (what Java/C++ do per lattice node).
        from terrain_slm.eval.cenda_noise import _LIB, java_long_hash
        out = np.empty(1, np.float32)
        s_r, s_s = java_long_hash(seed + 8), java_long_hash(seed + 23)
        for i in range(nx):
            for j in range(nz):
                _LIB.ck_gen_grid_2d(ch.range.node, out.ctypes.data, float(pz[i, j]), float(px[i, j]), 1, 1, 1.0, 1.0, s_r)
                rng[i, j] = out[0]
                _LIB.ck_gen_grid_2d(ch.spur.node, out.ctypes.data, float(pz[i, j]), float(px[i, j]), 1, 1, 1.0, 1.0, s_s)
                spr[i, j] = out[0]
    else:
        # Fit-speed path: both fields are smooth at the lattice scale (periods >= 200 blocks), so a dense
        # 8-block grid + cubic interpolation at the warped positions is statistically the same field.
        from scipy.ndimage import map_coordinates
        G = 8
        pad = int(math.ceil(p.warp_amp * 1.2)) + 4 * G
        ax0, az0 = int(gi[0]) - pad, int(gj[0]) - pad
        mx = (int(gi[-1]) + pad - ax0) // G + 1
        mz = (int(gj[-1]) + pad - az0) // G + 1
        dr = ch.range.grid(seed + 8, ax0, az0, mx, mz, G)
        ds = ch.spur.grid(seed + 23, ax0, az0, mx, mz, G)
        coords = np.stack([((px - ax0) / G).ravel(), ((pz - az0) / G).ravel()])
        rng = map_coordinates(dr, coords, order=3, mode="nearest").reshape(nx, nz)
        spr = map_coordinates(ds, coords, order=3, mode="nearest").reshape(nx, nz)
    # B-spline over the 4 x 4 nodes around each sample
    fi = (xs - gi[0]) / L
    fj = (zs - gj[0]) / L
    i0 = np.floor(fi).astype(int)
    j0 = np.floor(fj).astype(int)
    wi = bspline_weights(fi - i0)   # 4 x n  (nodes i0-1 .. i0+2)
    wj = bspline_weights(fj - j0)

    def interp(f):
        acc = np.zeros((n, n), np.float64)
        for a in range(4):
            rows = f[i0 - 1 + a]                     # n x nz
            for b in range(4):
                acc += wi[a][:, None] * wj[b][None, :] * rows[:, j0 - 1 + b]
        return acc

    s2 = fbm_std(2)
    return interp(rng) / s2, interp(spr) / s2


def distilled_elevation(p: Params, ch: Channels, seed: int, x0: int, z0: int, n: int, step: int):
    """(elevation metres, detail noise) on the n x n grid."""
    xs = (x0 + np.arange(n) * step).astype(np.float64)
    zs = (z0 + np.arange(n) * step).astype(np.float64)
    r2 = xs[:, None] ** 2 + zs[None, :] ** 2
    home = bell(r2 / (2 * p.home_radius ** 2))
    cont = ch.cont.grid(seed + 2, x0, z0, n, n, step) + p.home_amp * home
    land = smoothstep((cont + p.cont_offset) / p.cont_width)
    n_range, n_spur = warped_lattice_field(ch, p, seed, x0, z0, n, step)
    w = p.spine_width
    spine = bell((n_range / w) ** 2)
    foot = bell((n_range / (2.4 * w)) ** 2)
    body = bell((n_range / (1.6 * w)) ** 2)
    spur = bell((n_spur / 0.6) ** 2) * body
    along = smoothstep((ch.along.grid(seed + 24, x0, z0, n, n, step) / fbm_std(2) + p.along_bias) / 1.2)
    hills = ch.hills.grid(seed + 25, x0, z0, n, n, step)
    hilly = smoothstep((ch.hilly.grid(seed + 26, x0, z0, n, n, step) / fbm_std(3) + p.hill_bias) / p.hill_softness)
    hilly = np.maximum(hilly, 0.55 * home)
    mountains = along * (p.spine_m * spine + p.spur_m * spur + p.foot_m * foot)
    hill_m = p.hill_plains_m + (p.hill_country_m - p.hill_plains_m) * hilly + 200.0 * along * foot
    trend = p.sea_floor_m + (p.land_base_m - p.sea_floor_m) * land * (0.6 + 0.5 * cont) \
        + land * (mountains + hill_m * hills)
    rough = np.clip(p.rough_floor + p.rough_hill * hilly + along * (0.9 * spine + 0.45 * spur + 0.25 * foot)
                    + 0.25 * (0.3 + along * foot) * np.abs(hills), 0, 1) * land
    v = ch.valley.grid(seed + 27, x0, z0, n, n, step)
    a = np.abs(v) / (2 * fbm_std(4))                                        # ~[0, 1+], zero lines = valley floors
    valleys = p.valley_m * rough * (valley_shape(a, p.valley_power) - 0.5)     # V valleys on zero lines
    tex = (p.texture_floor_m + (p.texture_m - p.texture_floor_m) * rough) * ch.texture.grid(seed + 28, x0, z0, n, n, step)
    det = ch.detail.grid(seed + 3, x0, z0, n, n, step)
    e = trend + valleys + tex
    d = np.maximum(e - p.peak_knee_m, 0.0)
    return e - d + d / (1.0 + d / p.peak_span_m), det


def distilled_height(p: Params, ch: Channels, seed: int, x0: int, z0: int, n: int, step: int) -> np.ndarray:
    elev, det = distilled_elevation(p, ch, seed, x0, z0, n, step)
    blocks = CURVE.to_block_height_exact(elev) + p.detail_blocks * det
    # floor, as the model's own tiles quantise (HeightCurve.to_block_height): ground occupies y < height
    return np.clip(np.floor(blocks), 1, 255).astype(np.int32)


# ----------------------------------------------------------------------------- fitting
def spec_from_target(target: Path, coarse: int, fine: int):
    metas = []
    for f in sorted(target.glob("*.npz")):
        s, x, z, lod = (int(v) for v in np.load(f)["meta"])
        metas.append((s, x, z, lod))
    rng = np.random.default_rng(7)
    c = [m for m in metas if m[3] == 8]
    f = [m for m in metas if m[3] == 1]
    rng.shuffle(c)
    rng.shuffle(f)
    return c[:coarse] + f[:fine]


def _tile_job(args):
    """Worker: build + measure one tile (tile-local metrics are additive across tiles)."""
    p, (s, x, z, lod) = args
    ch = Channels(p)
    return distilled_height(p, ch, s, x * 256 * lod, z * 256 * lod, 256, lod), lod


_POOL = None


def evaluate(p: Params, spec, workers: int = 12) -> dict:
    global _POOL
    if workers <= 1:
        ch = Channels(p)
        items = ((distilled_height(p, ch, s, x * 256 * lod, z * 256 * lod, 256, lod), None, lod) for s, x, z, lod in spec)
        return DS.measure_tiles(items, "distilled")
    if _POOL is None:
        import multiprocessing as mp
        _POOL = mp.get_context("fork").Pool(workers)
    tiles = _POOL.map(_tile_job, [(p, t) for t in spec], chunksize=2)
    return DS.measure_tiles(((h, None, lod) for h, lod in tiles), "distilled")


def to_vec(p: Params, keys):
    v = []
    for k in keys:
        lo, hi = BOUNDS[k]
        x = getattr(p, k)
        if lo > 0 and hi / lo > 4:
            v.append((math.log(x) - math.log(lo)) / (math.log(hi) - math.log(lo)))
        else:
            v.append((x - lo) / (hi - lo))
    return np.array(v)


def from_vec(v, base: Params, keys) -> Params:
    kw = {}
    for k, x in zip(keys, np.clip(v, 0, 1)):
        lo, hi = BOUNDS[k]
        kw[k] = math.exp(math.log(lo) + x * (math.log(hi) - math.log(lo))) if lo > 0 and hi / lo > 4 else lo + x * (hi - lo)
    return replace(base, **kw)


def main(argv=None) -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--coarse", type=int, default=40)
    ap.add_argument("--fine", type=int, default=40)
    ap.add_argument("--iters", type=int, default=300)
    ap.add_argument("--init", help="params json to start from")
    ap.add_argument("--keys", help="comma list of knobs to fit (default: all)")
    ap.add_argument("--eval-only", action="store_true")
    args = ap.parse_args(argv)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    print("measuring target ...", flush=True)
    target = DS.measure(Path(args.target))
    spec = spec_from_target(Path(args.target), args.coarse, args.fine)
    base = Params(**json.loads(Path(args.init).read_text())) if args.init else Params()
    keys = args.keys.split(",") if args.keys else list(BOUNDS)

    t0 = time.monotonic()
    r0 = evaluate(base, spec)
    d0 = DS.distance(target, r0)
    print(f"init distance {d0['total']:.3f} ({time.monotonic() - t0:.1f} s/eval):",
          json.dumps({k: round(v, 3) for k, v in d0.items()}), flush=True)
    if args.eval_only:
        DS.print_table([target, r0])
        return

    best = {"d": d0["total"], "p": base, "g": d0}
    log = (out / "fit.log").open("a")

    def objective(v):
        p = from_vec(v, base, keys)
        try:
            g = DS.distance(target, evaluate(p, spec))
        except Exception as e:  # noqa: BLE001 - a degenerate world (no land, ...) is just a bad candidate
            print("eval failed:", e, flush=True)
            return 1e3
        if g["total"] < best["d"]:
            best.update(d=g["total"], p=p, g=g)
            (out / "best.json").write_text(json.dumps(asdict(p), indent=1))
            print(f"best {g['total']:.3f}", json.dumps({k: round(x, 3) for k, x in g.items()}), flush=True)
        log.write(json.dumps({"d": g["total"], **{k: round(x, 4) for k, x in g.items()}}) + "\n")
        log.flush()
        return g["total"]

    x0 = to_vec(base, keys)
    optimize.minimize(objective, x0, method="Powell", bounds=[(0, 1)] * len(keys),
                      options={"maxfev": args.iters, "xtol": 1e-3, "ftol": 1e-4})
    final = evaluate(best["p"], spec)
    DS.print_table([target, final])
    print("final distance:", json.dumps({k: round(x, 3) for k, x in DS.distance(target, final).items()}))
    (out / "final_stats.json").write_text(json.dumps([target, final], indent=1))


if __name__ == "__main__":
    main()
