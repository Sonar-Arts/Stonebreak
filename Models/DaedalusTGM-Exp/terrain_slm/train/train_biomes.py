"""Train the biome sidecar (models/biomenet.py) on real terrain, quantised to blocks like TileBuilder.

Targets: the rule classifier on each crop, cleaned by 9x9 then 5x5 majority filters. The dataset is
built once (the classifier's noise runs on the CPU) and held on the GPU; training uses dihedral
augmentation. Loss on the crop interior (the edges see padding).
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

from terrain_slm import biomes as B
from terrain_slm.data import descriptors as D
from terrain_slm.models import biomenet as BN
from terrain_slm.models import detail as DT
from terrain_slm.river.scale import BlockScale
from terrain_slm.train.planner_data import built_regions
from terrain_slm.train.train_detail import DetailRegion

LAPSE_C_PER_M = 0.0065
CROP = 160
EDGE = 16


def make_sample(reg: DetailRegion, r: int, c: int, scale: BlockScale, lut: torch.Tensor, i0: int, j0: int,
                aug: np.random.Generator | None = None):
    """Crop at cell (r, c): (inputs (N_IN, CROP, CROP), rule labels, cleaned labels) as class indices."""
    spc = DT.SAMPLES_PER_CELL
    n = CROP // spc
    dem = reg.dem60[r * spc : (r + n) * spc, c * spc : (c + n) * spc]
    elev = scale.to_metres(torch.floor(scale.to_blocks(dem)).clamp(0, scale.world_height - 1) + 0.5)
    cl = torch.stack([reg.cells.climate[k][r : r + n, c : c + n] for k in range(4)])[None]
    cl = F.interpolate(cl, size=(CROP, CROP), mode="bilinear", align_corners=False)[0]
    if aug is not None:
        # Climate augmentation: in game, archetype climate lands on any terrain, so pair real landforms
        # with shifted climates (warm lowlands -> swamp, cold uplands -> snow, ...); the rule labels follow.
        cl[0] = cl[0] + float(aug.uniform(-8.0, 8.0))
        cl[1] = cl[1] * float(aug.uniform(0.6, 1.5))
        cl[2] = cl[2] * float(np.exp(aug.uniform(-1.2, 1.2)))
        cl[3] = cl[3] * float(aug.uniform(0.7, 1.4))
    cl[0] = cl[0] - LAPSE_C_PER_M * elev.clamp_min(0.0)
    ep = F.pad(elev[None, None], (1, 1, 1, 1), mode="replicate")[0, 0]
    rule = B._classify_biome(elev, cl, i0, j0, elev_padded=ep, pixel_size_m=BN.PIXEL_M)
    rule_c = lut[rule.long()]
    clean = BN.majority(BN.majority(rule_c, 9), 5)
    return BN.build_input(elev, cl, i0, j0)[0].half(), rule_c.byte(), clean.byte()


def build(regions, corners, scale, lut, seed, p_aug=0.0):
    g = np.random.default_rng(seed)
    xs, rules, cleans = [], [], []
    for ri, r, c in corners:
        i0, j0 = int(g.integers(-200000, 200000)), int(g.integers(-200000, 200000))
        x, rl, cl = make_sample(regions[ri], r, c, scale, lut, i0, j0, g if g.random() < p_aug else None)
        xs.append(x); rules.append(rl); cleans.append(cl)
    return torch.stack(xs), torch.stack(rules), torch.stack(cleans)


def fragmentation(labels: torch.Tensor) -> dict:
    """Boundary share and small-patch count (< 20 columns) over a batch of class-index maps."""
    from scipy import ndimage
    lab = labels.cpu().numpy()
    edge = ((lab[:, 1:, :-1] != lab[:, :-1, :-1]) | (lab[:, :-1, 1:] != lab[:, :-1, :-1])).mean()
    tiny = 0
    for m in lab:
        for v in np.unique(m):
            l, k = ndimage.label(m == v)
            tiny += int((np.bincount(l.ravel())[1:] < 20).sum())
    return {"boundary": round(float(edge), 4), "tiny_patches_per_crop": round(tiny / len(lab), 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data"))
    ap.add_argument("--out", type=Path, default=Path("checkpoints/biome_b0"))
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--train-crops", type=int, default=6000)
    ap.add_argument("--steps", type=int, default=12000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=2e-3)
    ap.add_argument("--channels", type=int, nargs=3, default=None)
    ap.add_argument("--p-aug", type=float, default=0.5, help="share of crops with shifted climate")
    ap.add_argument("--norm", action="store_true", help="per-pixel channel norm in every block")
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    dev = args.device
    scale = BlockScale.game_default(dev)
    lut = torch.zeros(max(BN.CLASS_IDS) + 1, dtype=torch.long, device=dev)
    for k, bid in enumerate(BN.CLASS_IDS):
        lut[bid] = k

    t0 = time.time()
    regions = [DetailRegion(i, root, dev) for i, root in enumerate(built_regions(args.data_dir))]
    n = CROP // DT.SAMPLES_PER_CELL
    g = torch.Generator().manual_seed(0)
    train_c, val_c = [], []
    for reg in regions:
        tc = reg.cells.train_corners  # corners for crop CROP_CELLS + 2*margin (80 cells) >= n = 40: valid
        for k in torch.randint(0, len(tc), (args.train_crops // len(regions) + 1,), generator=g).tolist():
            r, c = tc[k].tolist()
            train_c.append((reg.cells.index, r, c))
        for r, c, h, w in reg.cells.val_boxes:
            for dr in range(0, h - n + 1, n * 2):
                for dc in range(0, w - n + 1, n * 2):
                    val_c.append((reg.cells.index, r + dr, c + dc))
    val_c = val_c[:: max(1, len(val_c) // 240)]
    xt, rt, yt = build(regions, train_c[: args.train_crops], scale, lut, 1, args.p_aug)
    xv, rv, yv = build(regions, val_c + val_c, scale, lut, 2, 0.5)   # val: each crop as-is and climate-shifted
    del regions
    torch.cuda.empty_cache()
    print(f"dataset: {len(xt)} train / {len(xv)} val crops of {CROP}^2 in {time.time() - t0:.0f}s", flush=True)
    print("target fragmentation: rule", fragmentation(rv), "cleaned", fragmentation(yv), flush=True)

    cfg = BN.BiomeNetConfig(channels=tuple(args.channels) if args.channels else BN.BiomeNetConfig.channels, norm=args.norm)
    model = BN.BiomeNet(cfg).to(dev)
    print(f"biomenet {sum(p.numel() for p in model.parameters()):,} params, {BN.N_CLASSES} classes", flush=True)
    freq = torch.bincount(yt.long().flatten(), minlength=BN.N_CLASSES).float()
    cw = (freq.sum() / freq.clamp_min(1.0) / BN.N_CLASSES).sqrt().clamp(0.3, 5.0).to(dev)
    share = lambda y: {BN.CLASS_IDS[k]: round(float(v), 4) for k, v in enumerate(torch.bincount(y.long().flatten(), minlength=BN.N_CLASSES).float() / y.numel()) if v > 0}
    print('class shares (train cleaned):', share(yt), flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 300) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))
    inner = lambda t: t[..., EDGE:-EDGE, EDGE:-EDGE]
    log = open(args.out / "log.jsonl", "a")
    best = 0.0
    for step in range(1, args.steps + 1):
        idx = torch.randint(0, len(xt), (args.batch,), device=dev)
        x, y = xt[idx].float(), yt[idx].long()
        tr, fr, fc = (torch.randint(0, 2, (3,)) == 1).tolist()
        if tr:
            x, y = x.transpose(-1, -2), y.transpose(-1, -2)
        if fr:
            x, y = x.flip(-2), y.flip(-2)
        if fc:
            x, y = x.flip(-1), y.flip(-1)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            logits = model(x.contiguous())
        loss = F.cross_entropy(inner(logits.float()), inner(y), weight=cw)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step(); sched.step()
        if step % 200 == 0:
            print(f"step {step:6d}  loss {loss.item():.4f}  {(time.time() - t0):.0f}s", flush=True)
        if step % 2000 == 0 or step == args.steps:
            model.eval()
            with torch.no_grad():
                preds = torch.cat([model(xv[i : i + 32].float()).argmax(1) for i in range(0, len(xv), 32)])
            model.train()
            acc_clean = float((inner(preds) == inner(yv.long())).float().mean())
            acc_rule = float((inner(preds) == inner(rv.long())).float().mean())
            ev = {"step": step, "acc_vs_cleaned": round(acc_clean, 4), "acc_vs_rule": round(acc_rule, 4),
                  "pred_fragmentation": fragmentation(preds), "share_rule": share(rv), "share_pred": share(preds)}
            print("  eval", ev, flush=True)
            log.write(json.dumps(ev) + "\n"); log.flush()
            ck = {"model": model.state_dict(), "config": model.cfg.to_dict(), "class_ids": list(BN.CLASS_IDS),
                  "step": step, "eval": ev}
            torch.save(ck, args.out / "last.pt")
            if acc_clean > best:
                best = acc_clean
                torch.save(ck, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best acc vs cleaned {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
