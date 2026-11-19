"""Terrain census (2026-11-19): how much of a world is mountains, hills and plains, and how big the mountains get --
for v4 vs EXP-2, at the 240 m cell scale, over large areas of many seeds. Separates the layout (controls trend) from
what the relief node and the planner build on it.
  sources: layout = controls trend (procedural), relief = trend after the relief node, planner = in-game cell heights

    python -m terrain_slm.eval.terrain_census --gen v4 exp2 [--seeds 0 1 2 ...] [--areas 4] [--size 768] \\
        [--device cuda:0] [--json out.json]

Per generator and source (trend = layout, planner = in-game cell heights), over land cells:
  land            share of cells above sea level (of all cells)
  >1000 / >2000 / >3000 m   shares of land
  peak p99 / max  land height percentiles (m)
  relief10        local relief (max - min) in ~10 km windows (41 cells): share of land with > 1000 m and > 2000 m
                  ("big mountains" / "epic")
  hills           share of land below 1000 m whose ~2.4 km relief (11 cells) is 60..400 m ("hill country")
  flat            share of land below 1000 m whose ~2.4 km relief is < 30 m
Areas: a grid of size x size cell windows around spawn (area k at offset k * size along a spiral).
"""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

ROOT = Path(__file__).resolve().parents[2] / "checkpoints"
SPIRAL = [(0, 0), (1, 0), (0, 1), (-1, 0), (0, -1), (1, 1), (-1, -1), (1, -1), (-1, 1), (2, 0), (0, 2), (-2, 0)]


MODEL: Path | None = None                       # --model (EXP-2 folder); None = eval.DEFAULT_EXP2_MODEL


def make(which: str, seed: int, device: str, swaps: dict[str, Path] | None = None):
    """v4, or EXP-2 (--model, default the release candidate) with optional part swaps {"relief": path, ...}."""
    from terrain_slm.world.generator import WorldGenerator
    from terrain_slm.world.generator2 import WorldGenerator2

    if which == "v4":
        return WorldGenerator(ROOT / "v4", seed, device)
    from terrain_slm.eval import DEFAULT_EXP2_MODEL
    d = MODEL or DEFAULT_EXP2_MODEL
    if swaps:
        tmp = Path(tempfile.mkdtemp(prefix="census_"))
        for f in d.iterdir():
            if f.stem not in swaps:
                (tmp / f.name).symlink_to(f.resolve())
        for part, path in swaps.items():
            (tmp / f"{part}.pt").symlink_to(Path(path).resolve())
        d = tmp
    return WorldGenerator2(d, seed, device)


def local_relief(h: torch.Tensor, k: int) -> torch.Tensor:
    t = h[None, None]
    mx = F.max_pool2d(t, k, 1, k // 2)
    mn = -F.max_pool2d(-t, k, 1, k // 2)
    return (mx - mn)[0, 0]


def stats(h: torch.Tensor, acc: dict) -> None:
    land = h > 0
    r10, r2 = local_relief(h, 41), local_relief(h, 11)
    low = land & (h < 1000)
    acc["n"] += h.numel(); acc["land"] += int(land.sum())
    for t in (1000, 2000, 3000):
        acc[f">{t}"] += int((h > t).sum())
    acc["r10>1000"] += int((land & (r10 > 1000)).sum()); acc["r10>2000"] += int((land & (r10 > 2000)).sum())
    acc["low"] += int(low.sum())
    acc["hills"] += int((low & (r2 >= 60) & (r2 <= 400)).sum()); acc["flat"] += int((low & (r2 < 30)).sum())
    if land.any():
        acc["heights"].append(h[land][torch.randperm(int(land.sum()), device=h.device)[:20000]].cpu().numpy())
    acc["max"] = max(acc["max"], float(h.max()))


def summary(acc: dict) -> dict:
    L = max(acc["land"], 1); low = max(acc["low"], 1)
    hs = np.concatenate(acc["heights"]) if acc["heights"] else np.zeros(1)
    return {"land": acc["land"] / acc["n"], ">1000": acc[">1000"] / L, ">2000": acc[">2000"] / L, ">3000": acc[">3000"] / L,
            "p99_m": float(np.percentile(hs, 99)), "max_m": acc["max"], "relief10>1000": acc["r10>1000"] / L,
            "relief10>2000": acc["r10>2000"] / L, "hills": acc["hills"] / low, "flat": acc["flat"] / low}


def new_acc():
    return {"n": 0, "land": 0, ">1000": 0, ">2000": 0, ">3000": 0, "r10>1000": 0, "r10>2000": 0, "low": 0,
            "hills": 0, "flat": 0, "heights": [], "max": 0.0}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gen", nargs="+", default=["v4", "exp2"],
                    help="v4 | exp2 | exp2:relief=<pt>,planner=<pt> (swap EXP-2 parts)")
    ap.add_argument("--seeds", type=int, nargs="+", default=list(range(8)))
    ap.add_argument("--areas", type=int, default=3)
    ap.add_argument("--size", type=int, default=768)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--relief-gain", type=float, default=None, help="EXP-2: sampled relief x this")
    ap.add_argument("--relief-preblur", type=float, default=None, help="EXP-2: trend blur before the relief node, cells")
    ap.add_argument("--hill-transfer", type=float, default=None)
    ap.add_argument("--no-planner", action="store_true", help="layout (trend) only: fast")
    ap.add_argument("--json", type=Path, default=None)
    ap.add_argument("--model", type=Path, default=None, help="EXP-2 model folder (default: eval.DEFAULT_EXP2_MODEL)")
    ap.add_argument("--set", action="append", default=[], metavar="MOD.CONST=VALUE",
                    help="override a world-module constant, e.g. generator2.HILL_TRANSFER_SIGMA_CELLS=4")
    args = ap.parse_args()
    global MODEL
    if args.model is not None:
        from terrain_slm.eval import model_path
        MODEL = model_path(args.model)
    import importlib
    for kv in args.set:
        name, val = kv.split("=", 1)
        mod, const = name.rsplit(".", 1)
        m = importlib.import_module(f"terrain_slm.world.{mod}")
        setattr(m, const, type(getattr(m, const))(float(val)))
        print(f"set {mod}.{const} = {getattr(m, const)}", flush=True)
    res = {}
    for spec in args.gen:
        which, _, sw = spec.partition(":")
        swaps = dict(kv.split("=", 1) for kv in sw.split(",")) if sw else None
        accs = {"layout": new_acc(), "relief": new_acc(), "planner": new_acc()}
        for seed in args.seeds:
            gen = make(which, seed, args.device, swaps)
            if args.relief_gain is not None:
                gen.relief_gain = args.relief_gain
            if args.hill_transfer is not None:
                gen.use_hill_transfer(args.hill_transfer)
            if args.relief_preblur is not None:
                gen.relief_preblur = args.relief_preblur
            with torch.no_grad():
                for k in range(args.areas):
                    a, b = SPIRAL[k]
                    i0, j0 = a * args.size - args.size // 2, b * args.size - args.size // 2
                    stats(gen.controls(i0, j0, args.size, args.size)["trend"], accs["layout"])
                    stats(gen.trend(i0, j0, args.size, args.size), accs["relief"])
                    if not args.no_planner:
                        h = gen._planner_cells(i0, j0, args.size, args.size)[0]
                        stats(h, accs["planner"])
            print(f"  {spec} seed {seed} done", flush=True)
            del gen
            torch.cuda.empty_cache()
        res[spec] = {src: summary(a) for src, a in accs.items() if a["n"]}
    cols = ["land", ">1000", ">2000", ">3000", "p99_m", "max_m", "relief10>1000", "relief10>2000", "hills", "flat"]
    print(f"{'':22s}" + "".join(f"{c:>14s}" for c in cols))
    for spec, r in res.items():
        for src, s in r.items():
            print(f"{spec + ' ' + src:22s}" + "".join(
                f"{s[c]:14.0f}" if c.endswith("_m") else f"{s[c]:14.1%}" for c in cols))
    if args.json:
        args.json.write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
