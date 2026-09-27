"""Train the planner (ladder rung R0 by default)."""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import torch
import torch.nn.functional as F

from terrain_slm.models import planner as P
from terrain_slm.train.planner_data import PlannerData, built_regions
from terrain_slm.device import default_device

LOSS_W = {"height": 1.0, "grad": 0.5, "desc": 1.0, "river": 1.0, "logacc": 1.0}


def losses(out: torch.Tensor, tgt: dict) -> dict:
    h = out[:, P.OUT_HEIGHT : P.OUT_HEIGHT + 1]
    l = {"height": F.l1_loss(h, tgt["height"])}
    dh = lambda x: (x[..., 1:, :] - x[..., :-1, :], x[..., :, 1:] - x[..., :, :-1])
    (py, px), (ty, tx) = dh(h), dh(tgt["height"])
    l["grad"] = F.l1_loss(py, ty) + F.l1_loss(px, tx)
    d, td = out[:, P.OUT_DESC], tgt["desc"]
    err = (d - td).abs()
    w = torch.ones_like(err)
    w[:, 5:8] = tgt["stat_weight"]
    l["desc"] = (err * w).sum() / w.sum()
    l["river"] = F.binary_cross_entropy_with_logits(out[:, P.OUT_RIVER : P.OUT_RIVER + 1], tgt["river"],
                                                    pos_weight=torch.tensor(8.0, device=out.device))
    l["logacc"] = F.l1_loss(out[:, P.OUT_LOGACC : P.OUT_LOGACC + 1], tgt["logacc"])
    l["total"] = sum(LOSS_W[k] * v for k, v in l.items())
    return l


@torch.no_grad()
def evaluate(model, data, batch: int) -> dict:
    model.eval()
    acc, n = {}, 0
    tp = fp = fn = 0.0
    hmae = 0.0
    for x, tgt in data.val_batches(batch):
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = model(x)
        out = out.float()
        for k, v in losses(out, tgt).items():
            acc[k] = acc.get(k, 0.0) + v.item() * x.shape[0]
        n += x.shape[0]
        hmae += (out[:, 0:1] - tgt["height"]).abs().mean().item() * P.HEIGHT_SCALE_M * x.shape[0]
        thr = math.log1p(P.RIVER_MIN_UPSLOPE_CELLS) / P.LOGACC_SCALE
        pr = out[:, P.OUT_LOGACC : P.OUT_LOGACC + 1] >= thr
        t = tgt["river"] > 0.5
        tp += (pr & t).sum().item(); fp += (pr & ~t).sum().item(); fn += (~pr & t).sum().item()
    model.train()
    res = {k: v / n for k, v in acc.items()}
    res["height_mae_m"] = hmae / n
    res["river_iou"] = tp / max(1.0, tp + fp + fn)
    return res


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, nargs="+", default=None,
                    help="region directories (default: every built region under data/)")
    ap.add_argument("--out", type=Path, default=Path("checkpoints/planner_r0"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--dim", type=int, default=128)
    ap.add_argument("--depth", type=int, default=2)
    ap.add_argument("--heads", type=int, default=4)
    ap.add_argument("--eval-every", type=int, default=1000)
    ap.add_argument("--loss-w", default="", help="override loss weights, e.g. river=0,logacc=0 (ablations)")
    args = ap.parse_args()
    for kv in filter(None, args.loss_w.split(",")):
        k, v = kv.split("=")
        if k not in LOSS_W:
            raise SystemExit(f"unknown loss term {k!r} (have {sorted(LOSS_W)})")
        LOSS_W[k] = float(v)
    print(f"loss weights {LOSS_W}", flush=True)
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)

    data = PlannerData(args.data or built_regions(), args.device, crop=64)
    cfg = P.PlannerConfig(dim=args.dim, depth=args.depth, heads=args.heads)
    model = P.Planner(cfg).to(args.device)
    print(f"planner {P.count_params(model):,} params; regions {[r.name for r in data.regions]}; "
          f"{data.train_count:,} train corners, {len(data.val_corners)} val crops", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01, betas=(0.9, 0.99))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))

    log = open(args.out / "log.jsonl", "a")
    best = float("inf")
    t0 = time.time()
    for step in range(1, args.steps + 1):
        x, tgt = data.train_batch(args.batch)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = model(x)
        l = losses(out.float(), tgt)
        opt.zero_grad(set_to_none=True)
        l["total"].backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        if step % 100 == 0:
            print(f"step {step:6d}  loss {l['total'].item():.4f}  h {l['height'].item():.4f}  "
                  f"desc {l['desc'].item():.4f}  river {l['river'].item():.4f}  "
                  f"{(time.time() - t0) / step * 1000:.1f} ms/step", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = evaluate(model, data, args.batch)
            ev["step"] = step
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", {k: round(v, 4) for k, v in ev.items()}, flush=True)
            ckpt = {"model": model.state_dict(), "config": cfg.to_dict(), "norms": data.norms.to_dict(),
                    "step": step, "eval": ev}
            torch.save(ckpt, args.out / "last.pt")
            if ev["total"] < best:
                best = ev["total"]
                torch.save(ckpt, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best val loss {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
