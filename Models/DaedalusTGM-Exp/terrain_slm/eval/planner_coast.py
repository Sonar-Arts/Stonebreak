"""Does a planner keep the world layout's coastline? (2026-11-18) Land area and coastline agreement of the generator's
in-game planner heights against the controls trend (the layout's intent), over coast-rich areas of a few seeds.

    python -m terrain_slm.eval.planner_coast v2=checkpoints/exp2/planner_v2/best.pt v3=checkpoints/exp2/planner_v3/best.pt \\
        [--seeds 12345 777] [--areas 6] [--device cuda:0] [--json out.json]

Per planner: land ratio = planner land cells / trend land cells (1.0 = islands and continents keep their size; > 1
= bloated), land IoU with the trend's land, and the share of trend-sea cells within 3 cells of the trend coast that
became land (shoreline creep). Areas: 256 x 256 cells (~61 km), picked on a grid by trend land share in 10..60 %.
"""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

ROOT = Path(__file__).resolve().parents[2] / "checkpoints" / "exp2"
N = 256


MODEL: Path | None = None                       # --model: the EXP-2 folder the planners are swapped into


def generator(planner: Path, seed: int, device: str):
    from terrain_slm.world.generator2 import WorldGenerator2

    tmp = Path(tempfile.mkdtemp(prefix="planner_coast_"))
    from terrain_slm.eval import DEFAULT_EXP2_MODEL
    for f in Path(MODEL or DEFAULT_EXP2_MODEL).iterdir():
        if f.name != "planner.pt":
            (tmp / f.name).symlink_to(f.resolve())
    (tmp / "planner.pt").symlink_to(planner.resolve())
    return WorldGenerator2(tmp, seed, device)


def pick_areas(gen, k: int, lo: float = 0.10, hi: float = 0.60) -> list[tuple[int, int]]:
    found = []
    with torch.no_grad():
        for a in range(-6, 7):                     # 13 x 13 candidates, probed by a 48-cell patch at their centre
            for b in range(-6, 7):
                i0, j0 = a * 1100, b * 1100
                t = gen.trend(i0 + N // 2 - 24, j0 + N // 2 - 24, 48, 48)
                share = float((t > 0).float().mean())
                if lo <= share <= hi:
                    found.append((abs(share - (lo + hi) / 2), i0, j0))
    found.sort()
    return [(i, j) for _, i, j in found[:k]]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="+")
    ap.add_argument("--seeds", type=int, nargs="+", default=[12345, 777])
    ap.add_argument("--areas", type=int, default=6)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--json", type=Path, default=None)
    ap.add_argument("--model", type=Path, default=None, help="EXP-2 folder the planners swap into (default: eval.DEFAULT_EXP2_MODEL)")
    ap.add_argument("--islands", action="store_true", help="open-ocean islands: areas whose probe is 0.5-8%% land")
    args = ap.parse_args()
    global MODEL
    if args.model is not None:
        from terrain_slm.eval import model_path
        MODEL = model_path(args.model)
    models = [(lab, Path(p)) for lab, p in (m.split("=", 1) for m in args.models)]
    res = {lab: {"trend_land": 0, "land": 0, "inter": 0, "union": 0, "creep_n": 0, "creep_land": 0} for lab, _ in models}
    for seed in args.seeds:
        areas = None
        for lab, path in models:
            gen = generator(path, seed, args.device)
            if areas is None:
                areas = pick_areas(gen, args.areas, *((0.005, 0.08) if args.islands else (0.10, 0.60)))
                print(f"seed {seed}: {len(areas)} coastal areas {areas}", flush=True)
            r = res[lab]
            with torch.no_grad():
                for i0, j0 in areas:
                    t = gen.trend(i0, j0, N, N)
                    h = gen._planner_cells(i0, j0, N, N)[0]
                    tl, hl = t > 0, h > 0
                    near = F.max_pool2d(tl.float()[None, None], 7, 1, 3)[0, 0] > 0
                    ring = near & ~tl
                    r["trend_land"] += int(tl.sum()); r["land"] += int(hl.sum())
                    r["inter"] += int((tl & hl).sum()); r["union"] += int((tl | hl).sum())
                    r["creep_n"] += int(ring.sum()); r["creep_land"] += int((ring & hl).sum())
            print(f"  {lab}: land {r['land']:,} vs layout {r['trend_land']:,}", flush=True)
            del gen
            torch.cuda.empty_cache()
    out = {}
    print(f"{'':6s}{'land ratio':>12s}{'land IoU':>10s}{'shore creep':>13s}")
    for lab, r in res.items():
        o = {"land_ratio": r["land"] / max(1, r["trend_land"]), "land_iou": r["inter"] / max(1, r["union"]),
             "shore_creep": r["creep_land"] / max(1, r["creep_n"])}
        out[lab] = o
        print(f"{lab:6s}{o['land_ratio']:12.3f}{o['land_iou']:10.3f}{o['shore_creep']:13.1%}")
    if args.json:
        args.json.write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
