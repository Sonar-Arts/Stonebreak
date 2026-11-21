"""Ocean census (2026-11-19): how deep and how flat EXP-2's seas are, in metres AND in blocks, near coasts.

    python -m terrain_slm.eval.ocean_census [--seeds 0 1 2 3] [--areas 3] [--size 512] [--device cuda:0] \\
        [--model checkpoints/exp2-r3] [--curve 1:4 [1:4+shelf:10:-200:100 ...]] [--json out.json] \\
        [--samples cache.npz] [--real aegean dalmatia ...]

Areas: 512 x 512 detail samples (60 m, ~30 km) whose centre probe is 20..70 % land (coastal). `--real` reads real
coastal regions of the training data instead (v2-gedtm30 dem.npy + sea.npy, 30 m pooled to 60 m), so the generated
numbers have a reference. Per sea sample:
  depth by distance from the coast (median m and blocks; bins 0-0.5, 0.5-2, 2-5, 5-15, 15-40 km)
  floor relief in metres: median range of a 9 x 9 sample (540 m) window of all-sea samples, per distance bin
  block flatness: share of sea whose 9 x 9 block neighbourhood (1:4: 540 m) spans <= 1 block of floor height
  depth share: < 3 blocks deep, 3-10, 10-30, > 30
`--shelf` applies the coastal shelf of height-curve v2 worlds (generator2._shelf_remap, post-detail, as their tiles get
it) to the samples. Every `--curve` is evaluated on the same samples (`--samples` caches them, so sweeps cost no GPU):
  `1:4` / `1:2` = the game's v1 presets; `+shelf:<m_per_block>:<base_m>:<blend_m>[:<ocean m_per_block>]` adds the
  v2 shelf band (and optionally another deep-ocean rate);
  `v2` / `1:2v2` = the game's v2 presets (`world_config.CURVE_V2`).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from scipy import ndimage

from terrain_slm.world.height_curve import HeightCurve

ROOT = Path(__file__).resolve().parents[2]
DATA = Path("/mnt/extrastorage/terrain-slm-data/v2-gedtm30")
BINS_KM = [(0, 0.5), (0.5, 2), (2, 5), (5, 15), (15, 40)]
PRESETS = {"1:4": (60.0, (48.0, 16.0, 24.0, 38.0)), "1:2": (30.0, (24.0, 8.0, 12.0, 19.0))}
REAL_COASTS = ["aegean", "atlantic_canyons", "baltic_skerries", "bc_coast", "canaries", "dalmatia", "halong", "hawaii",
               "iceland_west", "norway_nordland", "outer_banks", "outer_hebrides", "puerto_rico", "seto_inland",
               "visayas", "wadden"]


def curve(spec: str) -> tuple[HeightCurve, float]:
    """(curve, horizontal metres per block) of a curve spec: `1:4`, `1:2`, `<scale>+shelf:<m>:<base>:<blend>`, or
    `v2` / `1:2v2` (the game's v2 presets from world_config)."""
    from terrain_slm.world import world_config as WC
    if spec in ("v2", "1:4v2", "1:2v2"):
        scale = "1:2" if spec == "1:2v2" else "1:4"
        hm, r = PRESETS[scale]
        v = WC.curve_v2_keys(scale)
        return HeightCurve(v["ocean_m_per_block"], *r[1:], 600.0, 2000.0, v["shore_blend_m"], 150.0, 300.0, 64, 640,
                           -384, shelf_meters_per_block=v["shelf_m_per_block"], shelf_base_m=v["shelf_base_m"],
                           shelf_blend_m=v["shelf_blend_m"], shallow_meters_per_block=v["shallow_m_per_block"],
                           shallow_base_m=v["shallow_base_m"], shallow_blend_m=v["shallow_blend_m"]), hm
    scale, _, extra = spec.partition("+")
    hm, r = PRESETS[scale]
    kw = {}
    if extra:
        name, *vals = extra.split(":")
        assert name == "shelf", spec
        kw = dict(shelf_meters_per_block=float(vals[0]), shelf_base_m=float(vals[1]), shelf_blend_m=float(vals[2]))
        if len(vals) > 3:                                               # optional 4th value: the deep-ocean rate
            r = (float(vals[3]),) + tuple(r[1:])
    return HeightCurve(*r, 600.0, 2000.0, 60.0, 150.0, 300.0, 64, 640, -384, **kw), hm


def pick_areas(gen, k: int, size: int):
    import torch
    found = []
    with torch.no_grad():
        for a in range(-6, 7):
            for b in range(-6, 7):
                ci, cj = a * 900, b * 900                              # cells (240 m)
                t = gen.trend(ci - 24, cj - 24, 48, 48)
                share = float((t > 0).float().mean())
                if 0.2 <= share <= 0.7:
                    found.append((abs(share - 0.45), ci, cj))
    found.sort()
    return [(ci * 4 - size // 2, cj * 4 - size // 2) for _, ci, cj in found[:k]]   # samples: 4 per cell


def generated_areas(args) -> list[tuple[np.ndarray, np.ndarray]]:
    import importlib
    import torch
    for kv in args.set:
        name, val = kv.split("=", 1)
        mod, const = name.rsplit(".", 1)
        m = importlib.import_module(f"terrain_slm.world.{mod}")
        setattr(m, const, type(getattr(m, const))(float(val)))
    from terrain_slm.world.generator2 import WorldGenerator2
    out = []
    for seed in args.seeds:
        gen = WorldGenerator2(args.model, seed, args.device)
        for (s0, t0) in pick_areas(gen, args.areas, args.size):
            with torch.no_grad():
                h = gen._detail_samples(s0, t0, s0 + args.size, t0 + args.size).cpu().numpy().astype(np.float32)
            if (h < 0).mean() >= 0.05:
                out.append((h, h < 0))
        print(f"seed {seed} done", flush=True)
        del gen
        torch.cuda.empty_cache()
    return out


def real_areas(regions: list[str]) -> list[tuple[np.ndarray, np.ndarray]]:
    """Real coastal regions at 60 m: 2 x 2 means of the 30 m DEM; sea = the data's sea mask (>= half the pixels)."""
    out = []
    for r in regions:
        dem = np.load(DATA / r / "dem.npy", mmap_mode="r")
        sea = np.load(DATA / r / "sea.npy", mmap_mode="r")
        H, W = (dem.shape[0] // 2) * 2, (dem.shape[1] // 2) * 2
        h = np.zeros((H // 2, W // 2), np.float32)
        s = np.zeros((H // 2, W // 2), np.float32)
        for r0 in range(0, H, 2048):                                     # bounded memory
            r1 = min(H, r0 + 2048)
            h[r0 // 2 : r1 // 2] = np.asarray(dem[r0:r1, :W], np.float32).reshape(-1, 2, W // 2, 2).mean((1, 3))
            s[r0 // 2 : r1 // 2] = np.asarray(sea[r0:r1, :W], np.float32).reshape(-1, 2, W // 2, 2).mean((1, 3))
        m = s >= 0.5
        m &= h < 0                                                       # a few sea pixels sit above 0 (tidal flats)
        out.append((h, m))
        print(f"{r}: {h.shape}, sea {m.mean():.0%}", flush=True)
    return out


def census(areas, spec: str) -> dict:
    hc, hm = curve(spec)
    bpx = 60.0 / hm                                                     # blocks per 60 m sample: 1:4 = 1, 1:2 = 2
    depth_m = {b: [] for b in BINS_KM}
    depth_b = {b: [] for b in BINS_KM}
    relief_m = {b: [] for b in BINS_KM}
    flat_b = {b: [0, 0] for b in BINS_KM}
    flat = sea_n = 0
    shares = np.zeros(4)
    k = 9
    for h, sea in areas:
        dist_km = ndimage.distance_transform_edt(sea) * 0.06
        rng_m = ndimage.maximum_filter(h, k) - ndimage.minimum_filter(h, k)
        all_sea = ndimage.binary_erosion(sea, iterations=k // 2)
        if bpx != 1:
            h_b = ndimage.zoom(h, bpx, order=1)
            sea_b = ndimage.zoom(sea.astype(np.uint8), bpx, order=0).astype(bool) & (h_b < 0)
            dist_b = ndimage.zoom(dist_km, bpx, order=1)
        else:
            h_b, sea_b, dist_b = h, sea, dist_km
        yb = hc.to_block_height(h_b).astype(np.float32)
        blocks_deep = 64 - yb
        mx = ndimage.maximum_filter(yb, k)
        mn = ndimage.minimum_filter(yb, k)
        inner = sea_b & ndimage.binary_erosion(sea_b, iterations=k // 2)
        is_flat = (mx - mn) <= 1
        flat += int(is_flat[inner].sum())
        sea_n += int(inner.sum())
        bd = blocks_deep[sea_b]
        shares += [(bd < 3).sum(), ((bd >= 3) & (bd < 10)).sum(), ((bd >= 10) & (bd < 30)).sum(), (bd >= 30).sum()]
        for lo, hi in BINS_KM:
            m = sea & (dist_km >= lo) & (dist_km < hi)
            depth_m[(lo, hi)].append(-h[m])
            relief_m[(lo, hi)].append(rng_m[m & all_sea])
            mb = sea_b & (dist_b >= lo) & (dist_b < hi)
            depth_b[(lo, hi)].append(blocks_deep[mb])
            flat_b[(lo, hi)][0] += int(is_flat[mb & inner].sum())
            flat_b[(lo, hi)][1] += int((mb & inner).sum())
    res = {"curve": spec, "flat_share": flat / max(sea_n, 1),
           "depth_share_<3/3-10/10-30/>30": (shares / max(shares.sum(), 1)).tolist(), "by_distance": {},
           "columns": ["p10 m", "median m", "p90 m", "median blk", "p90 blk", "relief m (540 m window, median)",
                       "block-flat share"]}
    print(f"\ncurve {spec}  sea samples {int(shares.sum())}")
    print(f"block-flat sea (9x9 blocks span <= 1 block): {res['flat_share']:.1%}")
    print("depth share  <3 / 3-10 / 10-30 / >30 blocks: " + " / ".join(f"{s:.1%}" for s in res["depth_share_<3/3-10/10-30/>30"]))
    print(f"{'km from coast':>14s} {'p10 m':>8s} {'median m':>9s} {'p90 m':>8s} {'median blk':>11s} {'p90 blk':>8s} {'relief m':>9s} {'flat':>6s}")
    for b in BINS_KM:
        cat = lambda xs: np.concatenate(xs) if xs and sum(len(x) for x in xs) else np.zeros(1)
        dm, db, rm = cat(depth_m[b]), cat(depth_b[b]), cat(relief_m[b])
        row = [float(np.percentile(dm, 10)), float(np.median(dm)), float(np.percentile(dm, 90)),
               float(np.median(db)), float(np.percentile(db, 90)), float(np.median(rm)),
               flat_b[b][0] / max(flat_b[b][1], 1)]
        res["by_distance"][f"{b[0]}-{b[1]}"] = row
        print(f"{f'{b[0]}-{b[1]}':>14s} {row[0]:8.0f} {row[1]:9.0f} {row[2]:8.0f} {row[3]:11.0f} {row[4]:8.0f} {row[5]:9.0f}"
              f" {row[6]:6.0%}")
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seeds", type=int, nargs="+", default=[0, 1, 2, 3])
    ap.add_argument("--areas", type=int, default=3)
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--model", type=Path, default=None, help="EXP-2 model folder (default: eval.DEFAULT_EXP2_MODEL)")
    ap.add_argument("--curve", nargs="+", default=["1:4"])
    ap.add_argument("--set", action="append", default=[], metavar="MOD.CONST=VALUE")
    ap.add_argument("--real", nargs="*", default=None, help="real coastal regions (no names = the default list)")
    ap.add_argument("--samples", type=Path, default=None, help="npz cache of the 60 m samples (read if present)")
    ap.add_argument("--shelf", action="store_true",
                    help="apply the coastal shelf (generator2._shelf_remap) to the samples, as v2 worlds' tiles do")
    ap.add_argument("--shelf-profile", action="store_true",
                    help="apply the distance-aware coastal shelf of v2 worlds (generator2._shelf_profile_remap, FX3) "
                         "with each sample's distance from the coast (the game measures it on planner cells)")
    ap.add_argument("--json", type=Path, default=None)
    args = ap.parse_args()
    from terrain_slm.eval import DEFAULT_EXP2_MODEL, model_path
    args.model = model_path(args.model) if args.model is not None else DEFAULT_EXP2_MODEL

    if args.samples is not None and args.samples.exists():
        z = np.load(args.samples)
        areas = [(z[f"h{i}"], z[f"s{i}"]) for i in range(int(z["n"]))]
    else:
        areas = real_areas(args.real or REAL_COASTS) if args.real is not None else generated_areas(args)
        if args.samples is not None:
            np.savez_compressed(args.samples, n=len(areas), **{f"h{i}": a[0] for i, a in enumerate(areas)},
                                **{f"s{i}": a[1] for i, a in enumerate(areas)})
    if args.shelf_profile:
        import torch
        from terrain_slm.world.generator2 import SEA_PROFILE_MAX_CELLS, _shelf_profile_remap
        out = []
        for h, sea in areas:                                            # capped like the game's (240 m cells)
            dist_m = np.minimum(ndimage.distance_transform_edt(h < 0) * 60.0, SEA_PROFILE_MAX_CELLS * 240.0)
            h2 = _shelf_profile_remap(torch.from_numpy(h).double(), torch.from_numpy(dist_m).double()).float().numpy()
            out.append((h2, h2 < 0))
        areas = out
    elif args.shelf:
        import torch
        from terrain_slm.world.generator2 import _shelf_remap
        areas = [(h2, h2 < 0) for h2 in (_shelf_remap(torch.from_numpy(h).double()).float().numpy() for h, _ in areas)]
    results = [census(areas, spec) for spec in args.curve]
    if args.json:
        args.json.write_text(json.dumps(results[0] if len(results) == 1 else results, indent=1))


if __name__ == "__main__":
    main()
