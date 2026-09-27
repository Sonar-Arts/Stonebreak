"""Train the relief sampler: flow matching on real valley-scale relief (per cell).

Reuses the planner's region data, split and augmentation (PlannerData crops), so the
held-out tiles are the same ones every other model is validated on.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import time
from pathlib import Path

import torch

from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P
from terrain_slm.models import refiner as R
from terrain_slm.models import relief as RL
from terrain_slm.train.planner_data import PlannerData, built_regions
from terrain_slm.train.train_refiner import fm_loss
from terrain_slm.device import default_device

MARGIN_CELLS = 40  # most of 3 x the largest smooth sigma; the rest is reflect-padded identically
                   # for target and condition, so it is an edge artefact, never a mismatch
VAL_STRIDE = 32


class ReliefData:
    def __init__(self, roots, device: str, crop_cells: int = 144, seed: int = 0):
        roots = [Path(roots)] if isinstance(roots, (str, Path)) else [Path(r) for r in roots]
        self.cells = PlannerData(roots, device, crop=crop_cells + 2 * MARGIN_CELLS, seed=seed)
        self.gen = self.cells.gen
        self.device = device
        self.crop_cells = crop_cells
        # Denser held-out crops than the planner's (one per tile would leave the narrow
        # high-latitude tiles with none).
        n = crop_cells + 2 * MARGIN_CELLS
        self.val_corners = [(reg.index, r + dr, c + dc) for reg in self.cells.regions
                            for r, c, h, w in reg.val_boxes
                            for dr in range(0, h - n + 1, VAL_STRIDE) for dc in range(0, w - n + 1, VAL_STRIDE)]

    def _u(self, lo, hi):
        return float(torch.empty(1).uniform_(lo, hi, generator=self.gen))

    def _assemble(self, corners, train: bool):
        f = self.cells._crop(corners, augment=train)
        b = f.shape[0]
        m, s = MARGIN_CELLS, self.crop_cells
        crop = lambda x: x[..., m : m + s, m : m + s]
        coarse = f[:, 0:1]
        fine = D.blur(coarse, RL.RELIEF_FINE_SIGMA)
        smooth = torch.empty_like(coarse)
        for i in range(b):
            sig = self._u(*RL.RELIEF_SMOOTH_SIGMA) if train else sum(RL.RELIEF_SMOOTH_SIGMA) / 2
            smooth[i : i + 1] = D.blur(coarse[i : i + 1], sig)
        x1 = crop(fine - smooth) / RL.RELIEF_SCALE_M
        cond_smooth = smooth
        if train:  # a painted trend is also a little wrong: low-frequency jitter
            jit = D.blur(torch.randn(smooth.shape, generator=self.gen).to(smooth.device), 12.0)
            jit = jit / jit.flatten(1).std(dim=1).clamp_min(1e-6).view(-1, 1, 1, 1)
            amp = torch.empty(b, 1, 1, 1).uniform_(0, 60, generator=self.gen).to(smooth.device)
            cond_smooth = smooth + jit * amp
        wild = self.cells._wildness(f, train)
        climate = torch.cat([P.climate_input(n, f[:, 9 + k : 10 + k]) for k, n in enumerate(P.CLIMATE_NAMES)], dim=1)
        if train:
            has = lambda p: (torch.rand(b, 1, 1, 1, generator=self.gen) < p).float().to(f.device)
            hw, hc = has(0.85), has(0.85)
        else:
            hw = hc = torch.ones(b, 1, 1, 1, device=f.device)
        cond = RL.build_cond(crop(cond_smooth), crop(wild), crop(climate), hw, hc)
        return cond, x1

    def train_batch(self, batch: int):
        return self._assemble(self.cells.sample_corners(batch), train=True)

    def val_batches(self, batch: int):
        corners = self.val_corners
        for i in range(0, len(corners), batch):
            yield self._assemble(corners[i : i + batch], train=False)


@torch.no_grad()
def evaluate(model, data, batch, steps=16):
    """Flow-matching loss, plus the relief-amplitude ratio std(sample) / std(real): the
    averaging symptom this model exists to remove shows up as a ratio well below 1."""
    model.eval()
    g = torch.Generator(device=data.device).manual_seed(123)
    tot, n, s_std, r_std = 0.0, 0, 0.0, 0.0
    for cond, x1 in data.val_batches(batch):
        tot += fm_loss(model, cond, x1, g).item() * x1.shape[0]
        n += x1.shape[0]
        noise = torch.randn(x1.shape, device=x1.device, generator=g)
        x = R.sample(model, cond, noise, steps=steps)
        s_std += x.std().item() * x1.shape[0]
        r_std += x1.std().item() * x1.shape[0]
    model.train()
    return {"val_fm": tot / n, "amp_ratio": s_std / r_std}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, nargs="+", default=None)
    ap.add_argument("--out", type=Path, default=Path("checkpoints/relief_r0"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--eval-every", type=int, default=2000)
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)

    data = ReliefData(args.data or built_regions(), args.device)
    cfg = RL.ReliefConfig()
    model = RL.Relief(cfg).to(args.device)
    ema = copy.deepcopy(model).eval()
    print(f"relief {sum(p.numel() for p in model.parameters()):,} params, "
          f"{data.cells.train_count:,} train corners, {len(data.val_corners)} val crops", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.0, betas=(0.9, 0.99))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))
    log = open(args.out / "log.jsonl", "a")
    best, t0 = float("inf"), time.time()
    for step in range(1, args.steps + 1):
        cond, x1 = data.train_batch(args.batch)
        loss = fm_loss(model, cond, x1)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        with torch.no_grad():
            d = min(0.999, (1 + step) / (10 + step))
            for pe, pm in zip(ema.parameters(), model.parameters()):
                pe.lerp_(pm, 1 - d)
        if step % 100 == 0:
            print(f"step {step:6d}  loss {loss.item():.4f}  {(time.time() - t0) / step * 1000:.1f} ms/step", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, **evaluate(ema, data, args.batch)}
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", ev, flush=True)
            ckpt = {"model": ema.state_dict(), "config": cfg.to_dict(), "step": step, "eval": ev}
            torch.save(ckpt, args.out / "last.pt")
            if ev["val_fm"] < best:
                best = ev["val_fm"]
                torch.save(ckpt, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best val {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
