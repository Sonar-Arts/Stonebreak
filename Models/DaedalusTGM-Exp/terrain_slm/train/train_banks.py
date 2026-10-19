"""Train the bank model on real water edges (river.banks, data/water.py).

Each sample is a native-pixel crop centred on a real water-edge pixel. The water is the
exact-flat patch (its level is its own elevation); the band beside it is blanked and filled
from the ground beyond, exactly as the game does before calling the model; the target is the
real ground in the band, in BLOCKS above the water through the game's height curve.
Held-out: edges inside each region's validation tiles.

    python -m terrain_slm.train.train_banks --out checkpoints/bank_b0
"""
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
from terrain_slm.device import default_device
from terrain_slm.river import banks as B
from terrain_slm.river.field import RiverConfig
from terrain_slm.river.geometry import dilate
from terrain_slm.river.scale import BlockScale

CROP = 128


class BankData:
    def __init__(self, data_dir: Path, device: str, seed: int = 0):
        self.device = device
        self.gen = torch.Generator(device="cpu").manual_seed(seed)
        self.scale = BlockScale.game_default(device)
        self.band_px = int(round(RiverConfig().bank_band_blocks * self.scale.px_per_block))
        self.dems, self.masks, self.train, self.val = [], [], [], []
        for root in sorted(p.parent for p in data_dir.glob("*/water.npz")):
            meta = json.loads((root / "meta.json").read_text())
            w = np.load(root / "water.npz")
            edges = w["edges"]
            if len(edges) < 50:
                continue  # e.g. the Sahara: no water to learn from
            dem = np.load(root / "dem.npy", mmap_mode="r")
            k = len(self.dems)
            self.dems.append(torch.from_numpy(np.ascontiguousarray(dem)).to(device))
            self.masks.append(torch.from_numpy(w["mask"]).to(device))
            th, tw = meta["tile_px"]
            lat1, lon0 = meta["lat"][1], meta["lon"][0]
            val = np.zeros(len(edges), dtype=bool)
            for la, lo in meta["val_tiles"]:
                r0, c0 = (lat1 - 1 - la) * th, (lo - lon0) * tw
                val |= (edges[:, 0] >= r0) & (edges[:, 0] < r0 + th) & (edges[:, 1] >= c0) & (edges[:, 1] < c0 + tw)
            h, wd = dem.shape
            inside = ((edges[:, 0] >= CROP) & (edges[:, 0] < h - CROP) & (edges[:, 1] >= CROP) & (edges[:, 1] < wd - CROP))
            self.train += [(k, int(r), int(c)) for r, c in edges[~val & inside]]
            self.val += [(k, int(r), int(c)) for r, c in edges[val & inside]]
            print(f"  {meta['region']}: {int((~val & inside).sum())} train / {int((val & inside).sum())} val edges", flush=True)

    def _assemble(self, samples, augment: bool):
        half = CROP // 2
        dem = torch.stack([self.dems[k][r - half : r + half, c - half : c + half] for k, r, c in samples])[:, None].float()
        water = torch.stack([self.masks[k][r - half : r + half, c - half : c + half] for k, r, c in samples])[:, None] > 0
        if augment:
            tr, fr, fc = (torch.randint(0, 2, (3,), generator=self.gen) == 1).tolist()
            for f in (lambda x: x.transpose(-1, -2) if tr else x, lambda x: x.flip(-2) if fr else x,
                      lambda x: x.flip(-1) if fc else x):
                dem, water = f(dem), f(water)
        wf = water.float()
        level = D.blur(dem * wf, self.band_px) / D.blur(wf, self.band_px).clamp_min(1e-6)
        level = torch.where(water, dem, level)
        rate = self.scale.rate(level)
        rel = (dem - level) / rate
        band = torch.stack([dilate(w[0], self.band_px) for w in water])[:, None] & ~water
        x = B.bank_inputs(rel, water, band)
        return x, rel / B.REL_SCALE_BLOCKS, band

    def train_batch(self, batch: int):
        idx = torch.randint(0, len(self.train), (batch,), generator=self.gen).tolist()
        return self._assemble([self.train[i] for i in idx], augment=True)

    def val_batches(self, batch: int, limit: int = 2048):
        step = max(1, len(self.val) // limit)
        vs = self.val[::step]
        for i in range(0, len(vs), batch):
            yield self._assemble(vs[i : i + batch], augment=False)


def losses(pred, target, band):
    m = band.float()
    n = m.sum().clamp_min(1.0)
    l1 = ((pred - target).abs() * m).sum() / n
    gy = lambda x: x[..., 1:, :] - x[..., :-1, :]
    gx = lambda x: x[..., :, 1:] - x[..., :, :-1]
    my, mx = m[..., 1:, :] * m[..., :-1, :], m[..., :, 1:] * m[..., :, :-1]
    grad = (((gy(pred) - gy(target)).abs() * my).sum() / my.sum().clamp_min(1) +
            ((gx(pred) - gx(target)).abs() * mx).sum() / mx.sum().clamp_min(1))
    return {"l1": l1, "grad": grad, "total": l1 + grad}


@torch.no_grad()
def evaluate(model, data, batch):
    model.eval()
    acc, n = {}, 0
    below = total = 0.0
    fill_l1 = 0.0
    for x, target, band in data.val_batches(batch):
        pred = model(x)
        for k, v in losses(pred, target, band).items():
            acc[k] = acc.get(k, 0.0) + v.item() * x.shape[0]
        fill_l1 += losses(x[:, 0:1], target, band)["l1"].item() * x.shape[0]
        n += x.shape[0]
        # Leak pressure: bank pixels touching the water predicted below the water level.
        ring = torch.stack([dilate(w[0] > 0.5, 1) for w in x[:, 2:3]])[:, None] & band
        below += ((pred * B.REL_SCALE_BLOCKS < 0) & ring).sum().item()
        total += ring.sum().item()
    model.train()
    res = {k: v / n for k, v in acc.items()}
    res["blocks_mae"] = res["l1"] * B.REL_SCALE_BLOCKS
    res["fill_only_blocks_mae"] = fill_l1 / n * B.REL_SCALE_BLOCKS
    res["ring_below_water"] = below / max(1.0, total)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--out", type=Path, default=Path("checkpoints/bank_b0"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--steps", type=int, default=15000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=5e-4)
    ap.add_argument("--eval-every", type=int, default=1500)
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    data = BankData(args.data, args.device)
    cfg = B.BankConfig()
    model = B.BankNet(cfg).to(args.device)
    ema = copy.deepcopy(model).eval()
    print(f"bank {sum(p.numel() for p in model.parameters()):,} params, {len(data.train):,} train / {len(data.val):,} val edges",
          flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01, betas=(0.9, 0.99))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))
    log = open(args.out / "log.jsonl", "a")
    best, t0 = float("inf"), time.time()
    for step in range(1, args.steps + 1):
        x, target, band = data.train_batch(args.batch)
        l = losses(model(x), target, band)
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
            print(f"step {step:6d}  loss {l['total'].item():.4f}  l1 {l['l1'].item() * B.REL_SCALE_BLOCKS:.3f} blocks  "
                  f"{(time.time() - t0) / step * 1000:.1f} ms/step", flush=True)
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
