"""Train the surface-detail node (models/surfacenet.py) on the lidar windows of data/surface_labels.py.

    python -m terrain_slm.train.train_surface --data-dir /mnt/extrastorage/terrain-slm-data/v2-surface \
        --out checkpoints/exp2/surface_r0 [--device cpu|cuda:N]

Inputs: the windows' 60 m block means + climate (CHELSA T / P at the window, T lapse-adjusted per cell). Targets: the
15 m and 60 m labels. Losses: BCE for the three shares, L1 on log(roughness + 0.02), Poisson NLL on the boulder
COUNT of each cell (density x area), L1 on log(size) where boulders exist. Dihedral augmentation; loss on the crop
interior (RF + 1 samples away from the edge). Whole windows are held out per source (VAL below) and reported per
source against two baselines: the training mean (constant) and a 60 m slope-bin lookup (slope -> mean label).
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.models import surfacenet as SN

VAL = ("us_tetons", "ch_engadin_f", "no_jotunheimen_f", "nl_limburg_f", "nz_kaikoura_f", "fr_puys_f")
CROP = 32
EDGE = SN.RF + 1


def load_windows(root: Path, device: str):
    wins = []
    for p in sorted(root.glob("*.npz")):
        d = np.load(p, allow_pickle=False)
        meta = json.loads(str(d["meta"]))
        H, W = d["z60"].shape
        if min(H, W) < CROP:
            continue
        t = lambda a: torch.from_numpy(np.ascontiguousarray(a)).float().to(device)
        l60 = torch.stack([t(d[f"l60_{k}"]) for k in SN.FIELDS])
        l15 = torch.stack([t(d[f"l15_{k}"]) for k in SN.FIELDS])
        wins.append(dict(name=meta["name"], source=meta["source"], val=meta["name"] in VAL, z60=t(d["z60"]),
                         temp=t(d["temp60"]), precip=torch.full((H, W), float(meta["p"]), device=device), l60=l60,
                         l15=l15))
    return wins


def dihedral(x: torch.Tensor, k: int) -> torch.Tensor:
    if k & 1:
        x = x.transpose(-1, -2)
    if k & 2:
        x = x.flip(-2)
    if k & 4:
        x = x.flip(-1)
    return x


def batch(wins, n: int, rng: np.random.Generator, train: bool = True):
    zs, ts, ps, l60s, l15s = [], [], [], [], []
    for _ in range(n):
        w = wins[int(rng.integers(0, len(wins)))]
        H, Wd = w["z60"].shape
        r, c = int(rng.integers(0, H - CROP + 1)), int(rng.integers(0, Wd - CROP + 1))
        sl = (slice(r, r + CROP), slice(c, c + CROP))
        sl15 = (slice(4 * r, 4 * r + 4 * CROP), slice(4 * c, 4 * c + 4 * CROP))
        k = int(rng.integers(0, 8)) if train else 0
        zs.append(dihedral(w["z60"][sl], k))
        ts.append(dihedral(w["temp"][sl], k))
        ps.append(dihedral(w["precip"][sl], k))
        l60s.append(dihedral(w["l60"][(slice(None),) + sl], k))
        l15s.append(dihedral(w["l15"][(slice(None),) + sl15], k))
    return torch.stack(zs), torch.stack(ts), torch.stack(ps), torch.stack(l60s), torch.stack(l15s)


def field_loss(raw: torch.Tensor, lab: torch.Tensor, cell_m: float, edge: int) -> torch.Tensor:
    raw, lab = raw[..., edge:-edge, edge:-edge], lab[..., edge:-edge, edge:-edge]
    ok = torch.isfinite(lab[:, 0])
    if not ok.any():
        return raw.sum() * 0
    pred = SN.activate(raw)
    losses = []
    for i in range(SN.FRACTIONS):
        y = lab[:, i].nan_to_num(0.0)
        losses.append(F.binary_cross_entropy_with_logits(raw[:, i], y, reduction="none")[ok].mean())
    y = lab[:, 3].nan_to_num(0.0)
    losses.append((torch.log(pred[:, 3] + 0.02) - torch.log(y + 0.02)).abs()[ok].mean())
    area = cell_m * cell_m / 100.0
    lam = pred[:, 4] * area
    cnt = lab[:, 4].nan_to_num(0.0) * area
    losses.append((lam - cnt * torch.log(lam + 1e-6))[ok].mean() / max(area, 1.0) * 4.0)
    has = ok & torch.isfinite(lab[:, 5])
    if has.any():
        losses.append(0.5 * (torch.log(pred[:, 5]) - torch.log(lab[:, 5].clamp_min(1.0))).abs()[has].mean())
    return sum(losses)


def slope_of(z60: torch.Tensor) -> torch.Tensor:
    x = SN.build_input(z60[None], torch.zeros_like(z60)[None], torch.zeros_like(z60)[None])
    return x[0, 3]


@torch.no_grad()
def evaluate(model, wins, train_wins, device) -> dict:
    """Per held-out window: MAE of each field at 60 m and 15 m for the model and two baselines; correlations."""
    model.eval()
    # baselines from the training windows: constant mean, and mean label per 60 m slope bin
    bins = torch.tensor([0.0, 0.05, 0.1, 0.2, 0.3, 0.45, 0.6, 0.8, 1.0, 1.3, 10.0], device=device)
    sums = torch.zeros(len(bins) - 1, SN.N_FIELDS, device=device)
    cnts = torch.zeros(len(bins) - 1, SN.N_FIELDS, device=device)
    for w in train_wins:
        s = slope_of(w["z60"])
        b = torch.bucketize(s, bins[1:-1])
        for f in range(SN.N_FIELDS):
            y = w["l60"][f]
            ok = torch.isfinite(y)
            sums[:, f] += torch.zeros(len(bins) - 1, device=device).index_add_(0, b[ok], y[ok])
            cnts[:, f] += torch.zeros(len(bins) - 1, device=device).index_add_(0, b[ok], torch.ones_like(y[ok]))
    table = sums / cnts.clamp_min(1)
    const = sums.sum(0) / cnts.sum(0).clamp_min(1)
    out = {}
    for w in wins:
        x = SN.build_input(w["z60"][None], w["temp"][None], w["precip"][None])
        r60, r15 = model(x)
        p60, p15 = SN.activate(r60)[0], SN.activate(r15)[0]
        e = EDGE
        res = {}
        b = torch.bucketize(slope_of(w["z60"]), bins[1:-1])
        for f, name in enumerate(SN.FIELDS):
            y60, y15 = w["l60"][f][e:-e, e:-e], w["l15"][f][4 * e : -4 * e, 4 * e : -4 * e]
            m60, m15 = p60[f][e:-e, e:-e], p15[f][4 * e : -4 * e, 4 * e : -4 * e]
            base_slope = table[b, f][e:-e, e:-e]
            ok60, ok15 = torch.isfinite(y60), torch.isfinite(y15)
            if name == "boulder_size":
                ok60, ok15 = ok60 & (w["l60"][4][e:-e, e:-e] > 0), ok15 & (w["l15"][4][4 * e : -4 * e, 4 * e : -4 * e] > 0)
            if ok60.sum() < 10:
                continue
            mae = lambda p, y, m: float((p - y).abs()[m].mean())
            corr = lambda p, y, m: float(torch.corrcoef(torch.stack([p[m], y[m]]))[0, 1]) if m.sum() > 2 else float("nan")
            res[name] = dict(true_mean60=float(y60[ok60].mean()), pred_mean60=float(m60[ok60].mean()),
                             mae60=mae(m60, y60, ok60), mae60_const=float((const[f] - y60).abs()[ok60].mean()),
                             mae60_slopebin=mae(base_slope, y60, ok60), corr60=corr(m60, y60, ok60),
                             corr60_slopebin=corr(base_slope, y60, ok60),
                             mae15=mae(m15, y15, ok15) if ok15.sum() > 10 else None,
                             corr15=corr(m15, y15, ok15) if ok15.sum() > 10 else None)
        out[w["name"]] = res
    model.train()
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--steps", type=int, default=6000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--width", type=int, default=64)
    ap.add_argument("--eval-every", type=int, default=1000)
    args = ap.parse_args()
    torch.manual_seed(0)
    rng = np.random.default_rng(0)
    args.out.mkdir(parents=True, exist_ok=True)
    wins = load_windows(args.data_dir, args.device)
    train_w = [w for w in wins if not w["val"]]
    val_w = [w for w in wins if w["val"]]
    cfg = SN.SurfaceNetConfig(width=args.width)
    model = SN.SurfaceNet(cfg).to(args.device)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, args.lr, total_steps=args.steps, pct_start=0.05)
    print(f"surface {SN.count_params(model):,} params, {len(train_w)} train / {len(val_w)} val windows "
          f"({', '.join(w['name'] for w in val_w)})", flush=True)
    log = open(args.out / "log.jsonl", "a")
    t0 = time.time()
    for step in range(1, args.steps + 1):
        z, t, p, l60, l15 = batch(train_w, args.batch, rng)
        r60, r15 = model(SN.build_input(z, t, p))
        loss = field_loss(r60, l60, 60.0, EDGE) + field_loss(r15, l15, 15.0, 4 * EDGE)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 5.0)
        opt.step()
        sched.step()
        if step % 200 == 0:
            print(f"step {step:5d} loss {loss.item():.4f} {time.time() - t0:.0f}s", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = evaluate(model, val_w, train_w, args.device)
            log.write(json.dumps({"step": step, "val": ev}) + "\n")
            log.flush()
            summ = {f: np.nanmean([ev[w][f]["corr60"] for w in ev if f in ev[w]]) for f in SN.FIELDS}
            print("  val corr60 " + " ".join(f"{k} {v:.2f}" for k, v in summ.items()), flush=True)
            torch.save({"model": model.state_dict(), "config": cfg.to_dict(), "step": step, "eval": ev,
                        "fields": SN.FIELDS, "val_windows": list(VAL)}, args.out / "surface.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
