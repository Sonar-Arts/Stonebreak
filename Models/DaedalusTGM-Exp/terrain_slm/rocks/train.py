"""Train RockVAE on the augmented rock set.

    python -m terrain_slm.rocks.train --out checkpoints/rocks/rockvae_v1 --steps 20000 --device cuda:0

Hold-out: 8 % of the SCANNED shapes (with all their variants) never train; their reconstruction error is the
validation number. The checkpoint stores the data files' sha256 so the bake can cite its lineage.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
from pathlib import Path

import numpy as np
import torch

from . import augment as A
from .dataset import TRUNC
from .model import RockVAE, cond_vector


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def loss_fn(pred, target, mu, logvar, beta, free_bits=0.05):
    w = 1.0 + 4.0 * torch.exp(-3.0 * target.abs())  # emphasise the surface band
    rec = (w * (pred - target).abs()).mean()
    kl = 0.5 * (mu.pow(2) + logvar.exp() - 1 - logvar)
    kl = torch.clamp(kl.mean(0), min=free_bits).sum()
    return rec + beta * kl / pred[0].numel(), rec, kl


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", type=Path, default=A.AUG_DEFAULT)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=48)
    ap.add_argument("--lr", type=float, default=4e-4)
    ap.add_argument("--beta", type=float, default=1.0, help="KL weight per voxel-normalised unit")
    ap.add_argument("--latent", type=int, default=96)
    ap.add_argument("--width", type=int, default=32)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args(argv)
    torch.manual_seed(a.seed)
    torch.set_num_threads(a.threads)
    dev = torch.device(a.device)
    z = np.load(a.data)
    sdf = torch.from_numpy(z["sdf32"])  # float16, cells
    style, size_bin, grounded, src = (torch.from_numpy(z[k]) for k in ("style", "size_bin", "grounded", "src"))
    rng = np.random.default_rng(a.seed)
    nsrc = int(src.max()) + 1
    val_src = set(rng.choice(nsrc, size=max(4, int(0.08 * nsrc)), replace=False).tolist())
    is_val = torch.tensor([int(s) in val_src for s in src])
    tr_idx = torch.nonzero(~is_val)[:, 0]
    # style-balanced sampling (counts^-0.5): slabs dominate the scans, tors are rare
    cnt = torch.bincount(style[tr_idx], minlength=4).float().clamp(min=1)
    tr_w = cnt[style[tr_idx]].pow(-0.5)
    va_idx = torch.nonzero(is_val & (torch.arange(len(src)) % int(z["sdf32"].shape[0] // nsrc) == 0))[:, 0]
    on_gpu = dev.type == "cuda"
    data = (sdf.to(dev) if on_gpu else sdf)
    model = RockVAE(a.latent, a.width).to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, a.lr, total_steps=a.steps, pct_start=0.05)
    a.out.mkdir(parents=True, exist_ok=True)
    log = open(a.out / "train.log", "a")
    meta = {"args": {k: str(v) for k, v in vars(a).items()}, "data_sha256": sha256(a.data),
            "data_json": json.loads(a.data.with_suffix(".json").read_text()), "val_src": sorted(val_src),
            "params": sum(p.numel() for p in model.parameters())}
    print(json.dumps({k: meta[k] for k in ("params", "data_sha256")}), file=log, flush=True)
    t0 = time.time()
    for step in range(1, a.steps + 1):
        bi = tr_idx[torch.multinomial(tr_w, a.batch, replacement=True)]
        x = data[bi.to(data.device)].to(dev).float() / TRUNC
        c = cond_vector(style[bi], size_bin[bi], grounded[bi], dev)
        beta = a.beta * min(1.0, step / (0.3 * a.steps))
        pred, mu, logvar = model(x, c)
        loss, rec, kl = loss_fn(pred, x, mu, logvar, beta)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        if step % 250 == 0 or step == a.steps:
            model.eval()
            with torch.no_grad():
                vx = data[va_idx.to(data.device)].to(dev).float() / TRUNC
                vc = cond_vector(style[va_idx], size_bin[va_idx], grounded[va_idx], dev)
                vmu, _ = model.encode(vx, vc)
                vp = model.decode(vmu, vc)
                viou = (((vp < 0) & (vx < 0)).flatten(1).sum(1) /
                        ((vp < 0) | (vx < 0)).flatten(1).sum(1).clamp(min=1)).mean().item()
                tiou = (((pred < 0) & (x < 0)).flatten(1).sum(1) /
                        ((pred < 0) | (x < 0)).flatten(1).sum(1).clamp(min=1)).mean().item()
            model.train()
            msg = (f"step {step} loss {loss.item():.4f} rec {rec.item():.4f} kl {kl.item():.1f} beta {beta:.2f} "
                   f"train_iou {tiou:.3f} val_iou {viou:.3f} {time.time() - t0:.0f}s")
            print(msg, file=log, flush=True)
            print(msg, flush=True)
        if step % 2000 == 0 or step == a.steps:
            torch.save({"model": model.state_dict(), "latent": a.latent, "width": a.width, "step": step,
                        "meta": meta}, a.out / "last.pt")
    log.close()


if __name__ == "__main__":
    main()
