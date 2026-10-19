"""Train the refiner (ladder rung R0 by default): flow matching on real DEM residuals."""
from __future__ import annotations

import argparse
import copy
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import refiner as R
from terrain_slm.synth.noise import coarse_surface
from terrain_slm.train.planner_data import RIVER_MIN_UPSLOPE_CELLS, PlannerData, built_regions
from terrain_slm.device import default_device

MARGIN_CELLS = 6  # covers the coarse surface blur (3 * 12 px)


class RefinerData:
    """Pixel crops of the mosaic with their cell-derived conditioning, all on the GPU."""

    def __init__(self, roots, device: str, crop_cells: int = 16, seed: int = 0):
        # Reuse the planner's split/normalisation logic on a (crop + 2 * margin) cell window.
        roots = [Path(roots)] if isinstance(roots, (str, Path)) else [Path(r) for r in roots]
        self.cells = PlannerData(roots, device, crop=crop_cells + 2 * MARGIN_CELLS, seed=seed)
        self.device = device
        self.crop_cells = crop_cells
        self.gen = self.cells.gen
        # One region at a time through host memory (the mosaics are ~0.4-1.4 GB each).
        self.dems = []
        for root in roots:
            dem = np.load(root / "dem.npy", mmap_mode="r")
            self.dems.append(torch.from_numpy(np.ascontiguousarray(dem)).to(device))
            del dem
        self.desc_mean = torch.tensor(self.cells.norms.desc_mean, device=device)
        self.desc_std = torch.tensor(self.cells.norms.desc_std, device=device)

    def _assemble(self, corners, augment: bool):
        m, s, cp = MARGIN_CELLS, self.crop_cells, D.CELL_PX
        n = s + 2 * m
        regs = self.cells.regions
        coarse = torch.stack([regs[i].coarse[None, r : r + n, q : q + n] for i, r, q in corners])
        desc = torch.stack([regs[i].desc[:, r : r + n, q : q + n] for i, r, q in corners])
        river = torch.stack([regs[i].river[None, r : r + n, q : q + n] for i, r, q in corners])
        dem = torch.stack([self.dems[i][None, (r + m) * cp : (r + m + s) * cp, (q + m) * cp : (q + m + s) * cp]
                           for i, r, q in corners])
        crop = lambda x: x[..., m * cp : (m + s) * cp, m * cp : (m + s) * cp]
        base = crop(coarse_surface(coarse))
        up = lambda x: F.interpolate(x, size=(n * cp, n * cp), mode="bilinear", align_corners=False)
        desc_px = crop(up(desc))
        river_px = crop(D.blur(up(river), 3.0))
        if augment:
            ops = (torch.randint(0, 2, (3,), generator=self.gen) == 1).tolist()
            base, desc_px, river_px, dem = [self._dihedral(x, ops) for x in (base, desc_px, river_px, dem)]
            if ops[0]:
                desc_px[:, 6] = -desc_px[:, 6]
            if ops[1] ^ ops[2]:
                desc_px[:, 7] = -desc_px[:, 7]
        cond = R.build_cond(base, desc_px, river_px, self.desc_mean, self.desc_std)
        scale = R.scale_field(desc_px)
        return cond, (dem - base) / scale, base, scale, dem

    @staticmethod
    def _dihedral(x, ops):
        tr, fr, fc = ops
        if tr:
            x = x.transpose(-1, -2)
        if fr:
            x = x.flip(-2)
        if fc:
            x = x.flip(-1)
        return x.contiguous()

    def train_batch(self, batch: int):
        return self._assemble(self.cells.sample_corners(batch), augment=True)

    def val_batches(self, batch: int, limit_per_region: int = 64):
        taken, corners = {}, []
        for vc in self.cells.val_corners:  # the first few crops of every region's val tiles
            if taken.get(vc[0], 0) < limit_per_region:
                taken[vc[0]] = taken.get(vc[0], 0) + 1
                corners.append(vc)
        for i in range(0, len(corners), batch):
            yield self._assemble(corners[i : i + batch], augment=False)


def fm_loss(model, cond, x1, gen=None):
    b = x1.shape[0]
    x0 = torch.randn(x1.shape, device=x1.device, generator=gen)
    # Logit-normal t concentrates training where the velocity is hardest to predict.
    t = torch.sigmoid(torch.randn(b, device=x1.device, generator=gen))
    xt = (1 - t.view(-1, 1, 1, 1)) * x0 + t.view(-1, 1, 1, 1) * x1
    with torch.autocast("cuda", dtype=torch.bfloat16):
        v = model(xt, t, cond)
    return F.mse_loss(v.float(), x1 - x0)


@torch.no_grad()
def evaluate(model, data, batch):
    model.eval()
    g = torch.Generator(device=data.device).manual_seed(123)
    tot, n = 0.0, 0
    for cond, x1, *_ in data.val_batches(batch):
        tot += fm_loss(model, cond, x1, g).item() * x1.shape[0]
        n += x1.shape[0]
    model.train()
    return tot / n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, nargs="+", default=None,
                    help="region directories (default: every built region under data/)")
    ap.add_argument("--out", type=Path, default=Path("checkpoints/refiner_r0"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--eval-every", type=int, default=1000)
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)

    data = RefinerData(args.data or built_regions(), args.device)
    cfg = R.RefinerConfig()
    model = R.Refiner(cfg).to(args.device)
    ema = copy.deepcopy(model).eval()
    nparam = sum(p.numel() for p in model.parameters())
    print(f"refiner {nparam:,} params", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.0, betas=(0.9, 0.99))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))
    log = open(args.out / "log.jsonl", "a")
    best, t0 = float("inf"), time.time()
    for step in range(1, args.steps + 1):
        cond, x1, *_ = data.train_batch(args.batch)
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
            ev = {"step": step, "val_fm": evaluate(ema, data, args.batch)}
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", ev, flush=True)
            ckpt = {"model": ema.state_dict(), "config": cfg.to_dict(), "norms": data.cells.norms.to_dict(),
                    "step": step, "eval": ev}
            torch.save(ckpt, args.out / "last.pt")
            if ev["val_fm"] < best:
                best = ev["val_fm"]
                torch.save(ckpt, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best val {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
