"""Train an SR node (models/sr.py): SR-30 = 60 m -> 30 m on data v2's 30 m DEMs (topo-bathy, GEDTM30 land).

    python -m terrain_slm.train.train_sr --data-dir /mnt/extrastorage/terrain-slm-data/v2-gedtm30 --out checkpoints/exp2/sr30

Pairs come from the DEM itself: fine = a 30 m crop, coarse = its 2x2 means (exactly what the detail sampler's 60 m
samples are), target = asinh((fine - bicubic(coarse)) / ASINH_M). Validation crops come from the regions' held-out
tiles. Same recipe as train_detail2 (MP layers, learned per-time loss weighting, power-function EMAs, Adam with
inverse-sqrt decay). Eval reports the flow loss, the sampled residual's amplitude vs the real one, and the
mean-preservation error of `sr.assemble` (must be ~0).
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import numpy as np
import torch

from terrain_slm.models import sr as SR
from terrain_slm.models.mp import force_weight_norm_
from terrain_slm.train.train_detail2 import PowerEMA, sample_t

CROP = 256          # fine pixels (7.7 km at 30 m)
MARGIN = 16


def _to_device(a: np.ndarray, device: str, dtype) -> torch.Tensor:
    out = torch.empty(a.shape, dtype=dtype, device=device)
    for r0 in range(0, a.shape[0], 2048):
        out[r0 : r0 + 2048] = torch.from_numpy(np.ascontiguousarray(a[r0 : r0 + 2048])).to(device=device, dtype=dtype)
    return out


class SRData:
    def __init__(self, root: Path, device: str, seed: int = 0, val_per_region: int = 8):
        self.device, self.gen = device, np.random.default_rng(seed)
        self.regions = []
        for meta_p in sorted(root.glob("*/meta.json")):
            meta = json.loads(meta_p.read_text())
            # Resident on the GPU (plan §7): random crops from 40 memory-mapped regions (~27 GB) thrashed the page
            # cache (730 ms/step for a 3.75M model). Streamed in row blocks so host RAM never holds a region.
            dem = _to_device(np.load(meta_p.parent / "dem.npy", mmap_mode="r"), device, torch.float32)
            sea = _to_device(np.load(meta_p.parent / "sea.npy", mmap_mode="r"), device, torch.uint8)
            tile_h, tile_w = meta["tile_px"]
            lat1, lon0 = meta["lat"][1], meta["lon"][0]
            val_boxes = [((lat1 - 1 - la) * tile_h, (lo - lon0) * tile_w) for la, lo in meta["val_tiles"]]
            if meta.get("val_whole"):                    # 15 m data: whole tiles held out (build_15m)
                val_boxes = [(0, 0)]
            self.regions.append({"name": meta["region"], "dem": dem, "sea": sea, "tile": (tile_h, tile_w),
                                 "val": val_boxes, "val_whole": bool(meta.get("val_whole")),
                                 "px_m": float(meta.get("pixel_m", 30.0))})
        self.px_m = self.regions[0]["px_m"] if self.regions else 30.0   # 30 (SR-30) or 15 (SR-15) metres
        n = CROP + 2 * MARGIN
        self.val = []
        for ri, r in enumerate(self.regions):
            th, tw = r["tile"]
            for (r0, c0) in r["val"]:
                for _ in range(val_per_region // max(1, len(r["val"]))):
                    self.val.append((ri, r0 + int(self.gen.integers(0, th - n)), c0 + int(self.gen.integers(0, tw - n))))

    def _in_val(self, r, row, col):
        if r["val_whole"]:
            return True
        th, tw = r["tile"]
        n = CROP + 2 * MARGIN
        return any(r0 - n < row < r0 + th and c0 - n < col < c0 + tw for r0, c0 in r["val"])

    def _corner(self):
        while True:
            ri = int(self.gen.integers(0, len(self.regions)))
            r = self.regions[ri]
            h, w = r["dem"].shape
            n = CROP + 2 * MARGIN
            row, col = int(self.gen.integers(0, h - n)), int(self.gen.integers(0, w - n))
            if self._in_val(r, row, col):
                continue
            sea = float(r["sea"][row : row + n : 8, col : col + n : 8].float().mean())
            if sea > 0.9 and self.gen.random() < 0.8:      # mostly-ocean crops: keep some, not most
                continue
            return ri, row, col

    def _assemble(self, corners, train: bool):
        n = CROP + 2 * MARGIN
        fine = torch.stack([self.regions[ri]["dem"][r : r + n, c : c + n] for ri, r, c in corners])[:, None]
        if train:
            k = int(self.gen.integers(0, 8))
            if k & 1:
                fine = fine.transpose(-1, -2)
            if k & 2:
                fine = fine.flip(-2)
            if k & 4:
                fine = fine.flip(-1)
        coarse = SR.block_mean(fine)
        up = SR.upsample(coarse)
        cond = SR.build_cond(up, self.px_m)
        crop = lambda x: x[..., MARGIN : MARGIN + CROP, MARGIN : MARGIN + CROP]
        cm = lambda x: x[..., MARGIN // 2 : (MARGIN + CROP) // 2, MARGIN // 2 : (MARGIN + CROP) // 2]
        x1 = SR.encode(crop(fine) - crop(up))
        return crop(cond).contiguous(memory_format=torch.channels_last), x1, cm(coarse), crop(fine)

    def train_batch(self, b):
        return self._assemble([self._corner() for _ in range(b)], True)

    def val_batches(self, b):
        for i in range(0, len(self.val), b):
            yield self._assemble(self.val[i : i + b], False)


def fm_loss(model, cond, x1):
    t = sample_t(x1.shape[0], x1.device)
    noise = torch.randn_like(x1)
    tt = t[:, None, None, None]
    with torch.autocast("cuda", dtype=torch.bfloat16):
        v = model((1 - tt) * noise + tt * x1, t, cond).float()
    return (v - (x1 - noise)).pow(2).mean()


@torch.no_grad()
def evaluate(model, data: SRData, batch: int, steps: int) -> dict:
    model.eval()
    fm, amp, mp_err, n = [], [], 0.0, 0
    for cond, x1, coarse, fine in data.val_batches(batch):
        g = torch.Generator(device=x1.device).manual_seed(n)
        noise = torch.randn(x1.shape, generator=g, device=x1.device)
        for tv in (0.1, 0.5, 0.9):
            t = torch.full((x1.shape[0],), tv, device=x1.device)
            with torch.autocast("cuda", dtype=torch.bfloat16):
                v = model((1 - tv) * noise + tv * x1, t, cond).float()
            fm.append(float((v - (x1 - noise)).pow(2).mean()))
        x = SR.sample(model, cond, noise, steps)
        out = SR.assemble(coarse, x)
        mp_err = max(mp_err, float((SR.block_mean(out) - coarse).abs().max()))
        real = (fine - SR.upsample(coarse)).flatten(1).std(1)
        got = (out - SR.upsample(coarse)).flatten(1).std(1)
        amp += (got / real.clamp_min(1e-3)).tolist()
        n += x1.shape[0]
    model.train()
    return {"val_fm": float(np.mean(fm)), "amp_ratio_median": float(np.median(amp)), "mean_preserve_err_m": mp_err,
            "crops": n}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--channels", type=int, nargs="+", default=list(SR.SRConfig.channels))
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=4e-3)
    ap.add_argument("--lr-ref-steps", type=int, default=10000)
    ap.add_argument("--warmup", type=int, default=500)
    ap.add_argument("--eval-every", type=int, default=2500)
    ap.add_argument("--eval-steps", type=int, default=8)
    ap.add_argument("--compile", action="store_true")
    ap.add_argument("--probe", type=int, default=0)
    args = ap.parse_args()
    torch.manual_seed(0)
    torch.backends.cudnn.benchmark = True
    args.out.mkdir(parents=True, exist_ok=True)
    data = SRData(args.data_dir, args.device)
    cfg = SR.SRConfig(channels=tuple(args.channels))
    model = SR.SR(cfg).to(args.device).to(memory_format=torch.channels_last)
    force_weight_norm_(model)
    emas = PowerEMA(model, [0.05, 0.10])
    opt = torch.optim.Adam(model.parameters(), lr=args.lr, betas=(0.9, 0.99), fused=True)
    lr_at = lambda s: args.lr * min(1.0, (s + 1) / args.warmup) / math.sqrt(max(s / args.lr_ref_steps, 1.0))
    fwd = torch.compile(model) if args.compile else model
    print(f"sr {SR.count_params(model):,} params, {len(data.regions)} regions, {len(data.val)} val crops", flush=True)
    log = open(args.out / "log.jsonl", "a")
    t0 = t_mark = time.time()
    for step in range(1, args.steps + 1):
        cond, x1, *_ = data.train_batch(args.batch)
        for g in opt.param_groups:
            g["lr"] = lr_at(step)
        force_weight_norm_(model)
        loss = fm_loss(fwd, cond, x1)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 10.0)
        opt.step()
        emas.update(model, step)
        if step % 100 == 0:
            torch.cuda.synchronize(args.device)
            print(f"step {step:6d}  loss {loss.item():.4f}  {(time.time() - t_mark) * 10:.0f} ms/step", flush=True)
            t_mark = time.time()
        if args.probe and step >= args.probe:
            return
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, "min": round((time.time() - t0) / 60, 1)}
            for std, ema in zip(emas.stds, emas.emas):
                r = evaluate(ema, data, args.batch, args.eval_steps)
                ev[f"ema_{std:.2f}"] = r
                torch.save({"model": ema.state_dict(), "config": cfg.to_dict(), "step": step, "ema_std": std, "eval": r},
                           args.out / f"ema_{std:.2f}.pt")
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", json.dumps(ev), flush=True)
    print(f"done in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
