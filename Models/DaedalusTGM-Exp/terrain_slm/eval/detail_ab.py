"""Like-for-like comparison of detail checkpoints (v4 Detail or EXP-2 Detail2) on the same validation crops.

    python -m terrain_slm.eval.detail_ab A.pt B.pt [...] [--data-dir D] [--steps 16]

Per checkpoint: flow loss at fixed t (0.05, 0.3, 0.6, 0.9; same noise), sampled amplitude vs the real residual
(median per-crop std ratio), and stability: windows that go non-finite or implausible (|x| >= 7) from the usual
blurred base AND from unblurred coarse cells (v4's in-game trigger).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from terrain_slm.models import detail as DT
from terrain_slm.models import detail2 as D2
from terrain_slm.train.train_detail import DetailData
from terrain_slm.world.generator import _detail_model


def load(path: Path, device: str):
    ck = torch.load(path, map_location=device, weights_only=False)
    cfg = dict(ck["config"])
    m = _detail_model(cfg).to(device).eval()
    m.load_state_dict(ck["model"])
    return m, ck


def velocity(m, x, t, cond):
    with torch.autocast("cuda", dtype=torch.bfloat16):
        return (m(x, t, cond) if isinstance(m, DT.Detail) else m(x, t, cond)).float()


def sample(m, cond, noise, steps):
    if isinstance(m, D2.Detail2):
        return D2.sample(m, cond, noise, steps=steps, mean_flow=getattr(m, "mean_flow", False))
    return DT.sample(m, cond, noise, steps=steps)


@torch.no_grad()
def run(m, data: DetailData, steps: int, batch: int = 32) -> dict:
    fm, ratios, bad, n, bad_u, n_u = [], [], 0, 0, 0, 0
    for k, (cond, x1, base, dem) in enumerate(data.val_batches(batch)):
        g = torch.Generator(device=x1.device).manual_seed(k)
        noise = torch.randn(x1.shape, generator=g, device=x1.device)
        for tv in (0.05, 0.3, 0.6, 0.9):
            t = torch.full((x1.shape[0],), tv, device=x1.device)
            fm.append(float((velocity(m, (1 - tv) * noise + tv * x1, t, cond) - (x1 - noise)).pow(2).mean()))
        x = sample(m, cond, noise, steps)
        mx = x.abs().flatten(1).amax(1)
        bad += int((~torch.isfinite(x).flatten(1).all(1) | (mx >= 7)).sum())
        rs, rr = DT.decode(x).flatten(1).std(1), (dem - base).flatten(1).std(1)
        ratios += (rs / rr.clamp_min(1e-3)).tolist()
        n += x1.shape[0]
    for k, (cond, x1, base, dem) in enumerate(data.val_batches(batch, val_blur=0.0)):
        if k >= 4:
            break
        g = torch.Generator(device=x1.device).manual_seed(1000 + k)
        x = sample(m, cond, torch.randn(x1.shape, generator=g, device=x1.device), steps)
        mx = x.abs().flatten(1).amax(1)
        bad_u += int((~torch.isfinite(x).flatten(1).all(1) | (mx >= 7)).sum())
        n_u += x1.shape[0]
    return {"val_fm_fixed_t": round(float(np.mean(fm)), 4), "amp_ratio_median": round(float(np.median(ratios)), 3),
            "unstable": f"{bad}/{n}", "unstable_unblurred": f"{bad_u}/{n_u}"}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.eval.detail_ab")
    ap.add_argument("checkpoints", type=Path, nargs="+")
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data"))
    ap.add_argument("--steps", type=int, default=16)
    ap.add_argument("--device", default="cuda:0")
    args = ap.parse_args(argv)
    data = DetailData(args.data_dir, args.device)
    for p in args.checkpoints:
        m, ck = load(p, args.device)
        if "norms" in ck:
            data.desc_mean = torch.tensor(ck["norms"]["desc_mean"], device=args.device)
            data.desc_std = torch.tensor(ck["norms"]["desc_std"], device=args.device)
        print(json.dumps({"ckpt": str(p), "arch": type(m).__name__, "step": ck.get("step"), **run(m, data, args.steps)}),
              flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
