"""Planner A/B on the same held-out crops, split by what the crop is: land cells, sea cells, and the deep-ocean regions
(2026-11-18). Used to decide whether a retrained planner may replace the shipped one: it must not lose on land.

    python -m terrain_slm.eval.planner_compare v2=checkpoints/exp2/planner_v2/best.pt v3=checkpoints/exp2/planner_v3/best.pt \\
        [--data /mnt/extrastorage/terrain-slm-data/v2-gedtm30] [--device cuda:1] [--json out.json]

Rows: "land" / "sea" = every cell of the shared regions (all built regions except DEEP_OCEAN) by the target's sign;
"deep ocean" = all cells of the DEEP_OCEAN regions (unseen by planners trained before them). Height MAE in metres,
river IoU from the predicted accumulation (same rule as train_planner.evaluate), gradient L1 in metres per cell.
Descriptor loss is left out: each checkpoint normalises descriptors with its own training statistics.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import torch

from terrain_slm.models import planner as P
from terrain_slm.train.planner_data import PlannerData

DEEP_OCEAN = ("mariana_trench", "puerto_rico", "mid_atlantic_ridge", "atlantic_canyons")


def load(path: Path, device: str):
    ck = torch.load(path, map_location=device, weights_only=False)
    model = P.Planner(P.PlannerConfig(**ck["config"])).to(device).eval()
    model.load_state_dict(ck["model"])
    return model, ck.get("step"), ck.get("eval", {}).get("total")


class Acc:
    def __init__(self):
        self.n = self.h = self.g = 0.0
        self.tp = self.fp = self.fn = 0.0

    def add(self, out, tgt, mask):
        h, th = out[:, 0:1], tgt["height"]
        m = mask.float()
        self.n += float(m.sum())
        self.h += float(((h - th).abs() * m).sum()) * P.HEIGHT_SCALE_M
        gy = ((h[..., 1:, :] - h[..., :-1, :]) - (th[..., 1:, :] - th[..., :-1, :])).abs() * m[..., 1:, :]
        gx = ((h[..., :, 1:] - h[..., :, :-1]) - (th[..., :, 1:] - th[..., :, :-1])).abs() * m[..., :, 1:]
        self.g += float(gy.sum() + gx.sum()) / 2 * P.HEIGHT_SCALE_M
        thr = math.log1p(P.RIVER_MIN_UPSLOPE_CELLS) / P.LOGACC_SCALE
        pr = (out[:, P.OUT_LOGACC : P.OUT_LOGACC + 1] >= thr) & mask
        t = (tgt["river"] > 0.5) & mask
        self.tp += float((pr & t).sum()); self.fp += float((pr & ~t).sum()); self.fn += float((~pr & t).sum())

    def result(self):
        n = max(self.n, 1.0)
        return {"cells": int(self.n), "height_mae_m": round(self.h / n, 2), "grad_l1_m": round(self.g / n, 3),
                "river_iou": round(self.tp / max(1.0, self.tp + self.fp + self.fn), 4)}


@torch.no_grad()
def score(model, data: PlannerData, batch: int = 64) -> dict:
    land, sea = Acc(), Acc()
    for x, tgt in data.val_batches(batch):
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = model(x)
        out = out.float()
        above = tgt["height"] > 0
        land.add(out, tgt, above)
        sea.add(out, tgt, ~above)
    return {"land": land.result(), "sea": sea.result()}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="+", help="label=checkpoint.pt")
    ap.add_argument("--data", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/v2-gedtm30"))
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--json", type=Path, default=None)
    args = ap.parse_args()
    roots = sorted(p for p in args.data.iterdir() if (p / "cells.npz").exists())
    shared = [r for r in roots if r.name not in DEEP_OCEAN]
    deep = [r for r in roots if r.name in DEEP_OCEAN]
    sets = {"shared": PlannerData(shared, args.device, crop=64)}
    if deep:
        sets["deep ocean"] = PlannerData(deep, args.device, crop=64)
    res = {}
    for lab, path in (m.split("=", 1) for m in args.models):
        model, step, total = load(Path(path), args.device)
        r = {"step": step, "own_val_total": total}
        s = score(model, sets["shared"])
        r["land"], r["sea"] = s["land"], s["sea"]
        if "deep ocean" in sets:
            d = score(model, sets["deep ocean"])
            r["deep ocean"] = d["sea"]
        res[lab] = r
        del model
        torch.cuda.empty_cache()
    print(f"shared regions {len(shared)}, deep-ocean regions {len(deep)}")
    print(f"{'':10s}{'step':>7s}  {'row':12s}{'height MAE m':>14s}{'grad L1 m':>11s}{'river IoU':>11s}{'cells':>12s}")
    for lab, r in res.items():
        for row in ("land", "sea", "deep ocean"):
            if row in r:
                v = r[row]
                print(f"{lab:10s}{r['step'] or 0:7d}  {row:12s}{v['height_mae_m']:14.1f}{v['grad_l1_m']:11.2f}"
                      f"{v['river_iou']:11.3f}{v['cells']:12,d}")
    if args.json:
        args.json.write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
