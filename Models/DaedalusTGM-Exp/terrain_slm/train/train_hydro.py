"""Train the hydrology sidecar: coarse height + climate -> drainage, rivers, D8 directions.

Same regions, split and augmentation as the planner (PlannerData crops). The input height is
the real coarse height, degraded to look like the planner's output (a light blur plus
low-frequency jitter). The loss skips a margin: a cell near the crop edge can drain from
ground outside the crop.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import time
from pathlib import Path

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import hydro as HY
from terrain_slm.models import planner as P
from terrain_slm.train.planner_data import PlannerData, built_regions
from terrain_slm.device import default_device

LOSS_MARGIN = 16      # cells ignored at each crop edge
VAL_STRIDE = 48
D8_WEIGHT = 0.25
RIVER_THR = math.log1p(P.RIVER_MIN_UPSLOPE_CELLS) / P.LOGACC_SCALE  # same IoU definition as the planner


class HydroData:
    def __init__(self, roots, device: str, crop: int = 160, seed: int = 0):
        self.cells = PlannerData(roots, device, crop=crop, seed=seed)
        self.gen = self.cells.gen
        self.device, self.crop = device, crop
        self.val_corners = [(reg.index, r + dr, c + dc) for reg in self.cells.regions
                            for r, c, h, w in reg.val_boxes
                            for dr in range(0, h - crop + 1, VAL_STRIDE) for dc in range(0, w - crop + 1, VAL_STRIDE)]

    def _u(self, lo, hi):
        return float(torch.empty(1).uniform_(lo, hi, generator=self.gen))

    def _assemble(self, corners, train: bool):
        f = self.cells._crop(corners, augment=train)
        b = f.shape[0]
        coarse = f[:, 0:1]
        height = torch.empty_like(coarse)
        for i in range(b):
            sig = self._u(0.7, 2.0) if train else 1.2
            height[i : i + 1] = D.blur(coarse[i : i + 1], sig)
        if train:
            jit = D.blur(torch.randn(height.shape, generator=self.gen).to(height.device), 4.0)
            jit = jit / jit.flatten(1).std(dim=1).clamp_min(1e-6).view(-1, 1, 1, 1)
            height = height + jit * torch.empty(b, 1, 1, 1).uniform_(0, 25, generator=self.gen).to(height.device)
        climate = torch.cat([P.climate_input(n, f[:, 9 + k : 10 + k]) for k, n in enumerate(P.CLIMATE_NAMES)], dim=1)
        x = HY.build_input(height, climate)
        tgt = {"logacc": f[:, 15:16], "river": f[:, 13:14], "d8": f[:, 14].long()}
        return x, tgt

    def train_batch(self, batch: int):
        return self._assemble(self.cells.sample_corners(batch), train=True)

    def val_batches(self, batch: int):
        for i in range(0, len(self.val_corners), batch):
            yield self._assemble(self.val_corners[i : i + batch], train=False)


def losses(out, tgt):
    m = LOSS_MARGIN
    inner = lambda x: x[..., m:-m, m:-m]
    l = {"logacc": F.l1_loss(inner(out[:, HY.OUT_LOGACC : HY.OUT_LOGACC + 1]), inner(tgt["logacc"])),
         "river": F.binary_cross_entropy_with_logits(inner(out[:, HY.OUT_RIVER : HY.OUT_RIVER + 1]), inner(tgt["river"]),
                                                     pos_weight=torch.tensor(8.0, device=out.device)),
         "d8": F.cross_entropy(inner(out[:, HY.OUT_D8]), inner(tgt["d8"]))}
    l["total"] = l["logacc"] + l["river"] + D8_WEIGHT * l["d8"]
    return l


@torch.no_grad()
def evaluate(model, data, batch):
    model.eval()
    acc, n, tp, fp, fn, d8_ok, d8_n = {}, 0, 0.0, 0.0, 0.0, 0.0, 0.0
    m = LOSS_MARGIN
    for x, tgt in data.val_batches(batch):
        with torch.autocast("cuda", dtype=torch.bfloat16):
            out = model(x)
        out = out.float()
        for k, v in losses(out, tgt).items():
            acc[k] = acc.get(k, 0.0) + v.item() * x.shape[0]
        n += x.shape[0]
        pr = out[:, HY.OUT_LOGACC : HY.OUT_LOGACC + 1, m:-m, m:-m] >= RIVER_THR
        t = tgt["river"][..., m:-m, m:-m] > 0.5
        tp += (pr & t).sum().item(); fp += (pr & ~t).sum().item(); fn += (~pr & t).sum().item()
        d8p = out[:, HY.OUT_D8, m:-m, m:-m].argmax(1)
        d8t = tgt["d8"][..., m:-m, m:-m]
        d8_ok += (d8p == d8t).sum().item(); d8_n += d8t.numel()
    model.train()
    res = {k: v / n for k, v in acc.items()}
    res["river_iou"] = tp / max(1.0, tp + fp + fn)
    res["d8_acc"] = d8_ok / max(1.0, d8_n)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, nargs="+", default=None)
    ap.add_argument("--out", type=Path, default=Path("checkpoints/hydro_h0"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=5e-4)
    ap.add_argument("--eval-every", type=int, default=2000)
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)

    data = HydroData(args.data or built_regions(), args.device)
    cfg = HY.HydroConfig()
    model = HY.Hydro(cfg).to(args.device)
    ema = copy.deepcopy(model).eval()
    print(f"hydro {sum(p.numel() for p in model.parameters()):,} params, {len(data.val_corners)} val crops", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01, betas=(0.9, 0.99))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))
    log = open(args.out / "log.jsonl", "a")
    best, t0 = float("inf"), time.time()
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
        with torch.no_grad():
            d = min(0.999, (1 + step) / (10 + step))
            for pe, pm in zip(ema.parameters(), model.parameters()):
                pe.lerp_(pm, 1 - d)
        if step % 100 == 0:
            print(f"step {step:6d}  loss {l['total'].item():.4f}  logacc {l['logacc'].item():.4f}  "
                  f"river {l['river'].item():.4f}  d8 {l['d8'].item():.3f}  {(time.time() - t0) / step * 1000:.1f} ms/step",
                  flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, **evaluate(ema, data, args.batch)}
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", {k: round(v, 4) for k, v in ev.items()}, flush=True)
            ckpt = {"model": ema.state_dict(), "config": cfg.to_dict(), "step": step, "eval": ev}
            torch.save(ckpt, args.out / "last.pt")
            if ev["total"] < best:
                best = ev["total"]
                torch.save(ckpt, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best val {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
