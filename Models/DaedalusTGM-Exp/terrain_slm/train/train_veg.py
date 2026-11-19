"""Train the vegetation node (models/vegnet.py) on real land: ETH canopy height + ESA WorldCover targets
(data/vegetation.py) aligned to the v2 regions, inputs as the tile builder has them (block-quantised 60 m terrain,
7-channel climate lapsed to the regional elevation, standing water).

    python -m terrain_slm.train.train_veg build --data-dir /mnt/extrastorage/terrain-slm-data/v2-gedtm30 \
        --out /mnt/extrastorage/terrain-slm-data/veg_dataset
    python -m terrain_slm.train.train_veg train --dataset .../veg_dataset --out checkpoints/exp2/veg_r0 --device cuda:1

Splits: HOLDOUT regions are never trained on (per-region generalisation numbers); every other region gives train
crops outside its val tiles and val crops inside them. Cropland / built-up / water pixels carry no loss weight
(the node learns natural vegetation), so a farmed plain is predicted as the forest / grass its climate would hold.
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

from terrain_slm.data import descriptors as D
from terrain_slm.models import vegnet as VN
from terrain_slm.river.scale import BlockScale

CROP = 128            # 60 m blocks (32 cells)
EDGE = 22             # loss on the interior: the receptive field sees replicate padding nearer the edge
CELL60 = 4            # 60 m blocks per 240 m cell
CLIMATE_KEYS = ("t0", "tseason", "precip", "pcv", "aridity", "soil", "snow")
REGIONAL_SIGMA_CELLS = 6.0   # generator.LAPSE_REGIONAL_SIGMA_CELLS
HOLDOUT = ("carpathian_hills", "ozarks", "east_africa", "norway", "nz_southern_alps", "mar_de_morros")
# veg60 channels (data/vegetation.VEG_CHANNELS)
C_VALID, C_COVER, C_HMEAN, C_HCAN, C_P90 = 0, 1, 2, 3, 4
WC = {"tree": 5, "shrub": 6, "grass": 7, "crop": 8, "built": 9, "bare": 10, "snow": 11, "water": 12, "wetland": 13,
      "moss": 14}
C_WCVALID = 15
NATURAL = ("tree", "shrub", "grass", "bare", "snow", "wetland", "moss")


# ----------------------------------------------------------------------------------------------- dataset
class RegionSource:
    def __init__(self, root: Path, scale: BlockScale):
        self.root, self.scale = root, scale
        self.meta = json.loads((root / "meta.json").read_text())
        self.name = self.meta["region"]
        c = np.load(root / "cells.npz")
        self.clim = np.stack([c[k] for k in CLIMATE_KEYS]).astype(np.float32)          # (7, hc, wc)
        coarse = torch.from_numpy(c["coarse"].astype(np.float32))[None, None]
        self.regional = D.blur(coarse, REGIONAL_SIGMA_CELLS)[0, 0].numpy()
        self.dem = np.load(root / "dem.npy", mmap_mode="r")
        self.sea = np.load(root / "sea.npy", mmap_mode="r")
        wp = root / "water.npz"   # lakes / rivers; some regions (ocean-built) have none: sea.npy alone
        self.water = np.load(wp)["mask"] if wp.exists() else np.zeros(self.sea.shape, np.uint8)
        self.veg = np.load(root / "veg60.npy", mmap_mode="r")
        self.hc, self.wc = self.clim.shape[1:]
        tile_h = self.meta["tile_px"][0] // D.CELL_PX
        tile_w = self.meta["tile_px"][1] // D.CELL_PX
        lat1, lon0 = self.meta["lat"][1], self.meta["lon"][0]
        self.val_boxes = [((lat1 - 1 - la) * tile_h, (lo - lon0) * tile_w, tile_h, tile_w)
                          for la, lo in self.meta["val_tiles"]]

    def in_val(self, cr: int, cc: int, n: int) -> int:
        """1 = crop inside a val box, 0 = no overlap, -1 = partial overlap."""
        for r, c, h, w in self.val_boxes:
            if cr >= r and cc >= c and cr + n <= r + h and cc + n <= c + w:
                return 1
            if cr < r + h and cr + n > r and cc < c + w and cc + n > c:
                return -1
        return 0

    def sample(self, cr: int, cc: int, size: int = CROP, max_water: float = 0.7):
        """Crop whose top-left cell is (cr, cc): (inputs (N_IN, size, size) f16, targets (16, size, size) u8) or None."""
        CROP = size
        n = CROP // CELL60
        if cr < 1 or cc < 1 or cr + n + 1 > self.hc or cc + n + 1 > self.wc:
            return None
        r60, c60 = cr * CELL60, cc * CELL60
        veg = np.asarray(self.veg[:, r60 : r60 + CROP, c60 : c60 + CROP])
        if veg.shape[1:] != (CROP, CROP):
            return None
        dem = torch.from_numpy(np.asarray(self.dem[2 * r60 : 2 * r60 + 2 * CROP, 2 * c60 : 2 * c60 + 2 * CROP], np.float32))
        sea = np.asarray(self.sea[2 * r60 : 2 * r60 + 2 * CROP, 2 * c60 : 2 * c60 + 2 * CROP])
        wat = self.water[2 * r60 : 2 * r60 + 2 * CROP, 2 * c60 : 2 * c60 + 2 * CROP]
        wet = torch.from_numpy(((sea > 0) | (wat > 0)).astype(np.float32))
        water = (F.avg_pool2d(wet[None, None], 2)[0, 0] >= 0.5).float()
        if float(water.mean()) > max_water:
            return None
        e60 = F.avg_pool2d(dem[None, None], 2)[0, 0]
        sc = self.scale
        elev = sc.to_metres(torch.floor(sc.to_blocks(e60)).clamp(sc.min_y, sc.world_height - 1) + 0.5)
        up = lambda a: F.interpolate(torch.from_numpy(a)[None] if a.ndim == 3 else torch.from_numpy(a)[None, None],
                                     size=((n + 2) * CELL60, (n + 2) * CELL60), mode="bilinear",
                                     align_corners=False)[0][..., CELL60 : CELL60 + CROP, CELL60 : CELL60 + CROP]
        clim = up(np.ascontiguousarray(self.clim[:, cr - 1 : cr + n + 1, cc - 1 : cc + n + 1])).clone()
        regional = up(np.ascontiguousarray(self.regional[cr - 1 : cr + n + 1, cc - 1 : cc + n + 1]))[0]
        clim[0] = clim[0] - VN.LAPSE_C_PER_M * regional.clamp_min(0.0)
        x = VN.build_input(elev, clim, regional, water)[0]
        return x.half().numpy(), veg


def build_region(root: Path, out: Path, train_n: int, val_n: int, hold_n: int, seed: int) -> str:
    """One region's crops -> <out>/<region>.npz (x f16 (N, N_IN, CROP, CROP), y u8 (N, 16, CROP, CROP), split, cell)."""
    torch.set_num_threads(2)
    scale = BlockScale.game_default("cpu")
    g = np.random.default_rng(seed)
    n = CROP // CELL60
    t0 = time.time()
    src = RegionSource(root, scale)
    hold = src.name in HOLDOUT
    want = {"train": 0 if hold else train_n, "val": 0 if hold else val_n, "holdout": hold_n if hold else 0}
    got = {k: 0 for k in want}
    xs, ys, splits, cells = [], [], [], []
    tries = 0
    while any(got[k] < want[k] for k in want) and tries < 40 * sum(want.values()):
        tries += 1
        if got["val"] < want["val"] and tries % 3 == 0:
            r, c, h, w = src.val_boxes[int(g.integers(len(src.val_boxes)))]
            if h < n or w < n:
                continue
            cr, cc = r + int(g.integers(0, h - n + 1)), c + int(g.integers(0, w - n + 1))
        else:
            cr, cc = int(g.integers(1, src.hc - n - 1)), int(g.integers(1, src.wc - n - 1))
        v = src.in_val(cr, cc, n)
        split = "holdout" if hold else ("val" if v == 1 else "train" if v == 0 else None)
        if split is None or got[split] >= want[split]:
            continue
        s = src.sample(cr, cc)
        if s is None:
            continue
        x, y = s
        if ((y[C_VALID] > 128) & (y[C_WCVALID] > 128)).mean() < 0.25:
            continue
        xs.append(x); ys.append(y); splits.append(split); cells.append((cr, cc))
        got[split] += 1
    tmp = out / f"{src.name}.tmp.npz"
    np.savez(tmp, x=np.stack(xs) if xs else np.zeros((0, VN.N_IN, CROP, CROP), np.float16),
             y=np.stack(ys) if ys else np.zeros((0, 16, CROP, CROP), np.uint8), split=np.array(splits),
             cell=np.array(cells, np.int32).reshape(-1, 2), region=src.name)
    tmp.rename(out / f"{src.name}.npz")
    return f"{src.name}: {got} in {time.time() - t0:.0f}s"


def build_dataset(args):
    from concurrent.futures import ProcessPoolExecutor
    roots = sorted(p.parent for p in args.data_dir.glob("*/veg60.npy"))
    args.out.mkdir(parents=True, exist_ok=True)
    todo = [r for r in roots if not (args.out / f"{r.name}.npz").exists()]
    with ProcessPoolExecutor(args.workers) as ex:
        futs = [ex.submit(build_region, r, args.out, args.train_per_region, args.val_per_region,
                          args.holdout_per_region, 1000 + i) for i, r in enumerate(todo)]
        for f in futs:
            print(f.result(), flush=True)
    print(f"dataset: {len(list(args.out.glob('*.npz')))} regions", flush=True)


# ----------------------------------------------------------------------------------------------- targets
def targets(y: torch.Tensor):
    """(B, 16, H, W) uint8 veg60 -> (targets (B, N_OUT, H, W) 0..1, weights (B, N_OUT, H, W))."""
    y = y.float()
    share = lambda k: y[:, WC[k]] / 255.0
    nat = sum(share(k) for k in NATURAL)
    valid = ((y[:, C_VALID] > 128) & (y[:, C_WCVALID] > 128)).float()
    wnat = (nat.clamp(0, 1) ** 2) * valid
    cover = y[:, C_COVER] / 255.0
    hcan = (y[:, C_HCAN] / 4.0 / VN.HEIGHT_SCALE).clamp(0, 1)
    p90 = (y[:, C_P90] / VN.HEIGHT_SCALE).clamp(0, 1)
    natm = (nat > 0.5).float() * valid
    box = lambda a: F.avg_pool2d(F.pad(a[:, None], (2, 2, 2, 2), mode="replicate"), 5, stride=1)[:, 0]
    open_ = ((cover < 0.25).float() * natm)
    nb = box(natm)
    clearing = box(open_) / nb.clamp_min(1e-3)
    wc = [share(k) / nat.clamp_min(1e-3) for k in ("tree", "shrub", "grass", "bare", "snow", "wetland", "moss")]
    t = torch.stack([cover, hcan, p90, clearing] + [x.clamp(0, 1) for x in wc], 1)
    w = wnat[:, None].expand_as(t).clone()
    w[:, 1] = w[:, 1] * (cover > 0.1).float()          # canopy height only where there is canopy
    w[:, 3] = (nb > 0.5).float() * valid
    return t, w


def metrics(pred: torch.Tensor, t: torch.Tensor, w: torch.Tensor) -> dict:
    """Weighted MAE per output (heights in metres) + correlation of cover."""
    out = {}
    for k, name in enumerate(VN.OUTPUTS):
        ww = w[:, k]
        mae = float(((pred[:, k] - t[:, k]).abs() * ww).sum() / ww.sum().clamp_min(1e-6))
        out[name] = round(mae * (VN.HEIGHT_SCALE if name in ("hcan", "p90") else 1.0), 4)
    ww = w[:, 0] > 0.25
    a, b = pred[:, 0][ww], t[:, 0][ww]
    if a.numel() > 10:
        out["cover_r"] = round(float(torch.corrcoef(torch.stack([a, b]))[0, 1]), 4)
        out["cover_mean_pred"], out["cover_mean_true"] = round(float(a.mean()), 4), round(float(b.mean()), 4)
    return out


def train(args):
    dev = args.device
    torch.manual_seed(0)
    files = sorted(p for p in args.dataset.glob("*.npz") if not p.name.endswith(".tmp.npz"))
    hold = set(args.holdout.split(",")) if args.holdout is not None else set(HOLDOUT)
    parts = {"train": ([], []), "val": ([], []), "holdout": ([], [])}
    ho_regions = []
    for f in files:
        z = np.load(f)
        region, split = str(z["region"]), z["split"]
        for k in ("train", "val", "holdout"):
            m = split == k
            if not m.any():
                continue
            dest = k
            if k == "holdout" and (args.all_regions or region not in hold):
                dest = "train"
            if k == "val" and args.all_regions:
                dest = "val"
            parts[dest][0].append(torch.from_numpy(z["x"][m]).to(dev))
            parts[dest][1].append(torch.from_numpy(z["y"][m]).to(dev))
            if dest == "holdout":
                ho_regions += [region] * int(m.sum())
    cat = lambda k: (torch.cat(parts[k][0]), torch.cat(parts[k][1])) if parts[k][0] else (None, None)
    xt, yt = cat("train")
    xv, yv = cat("val")
    xh, yh = cat("holdout")
    tr, va, ho = range(len(xt)), range(len(xv)), range(0 if xh is None else len(xh))
    print(f"train {len(tr)}  val {len(va)}  holdout {len(ho)} ({sorted(set(ho_regions)) if len(ho) else '-'})", flush=True)
    args.out.mkdir(parents=True, exist_ok=True)
    model = VN.VegNet(VN.VegNetConfig(channels=tuple(args.channels))).to(dev)
    print(f"vegnet {sum(p.numel() for p in model.parameters()):,} params", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.wd)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 300) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))
    inner = lambda a: a[..., EDGE:-EDGE, EDGE:-EDGE]
    log = open(args.out / "log.jsonl", "a")
    t0 = time.time()
    best = 1e9

    def evaluate(x, y, regions=None):
        model.eval()
        preds = []
        with torch.no_grad():
            for i in range(0, len(x), 32):
                preds.append(torch.sigmoid(model(x[i : i + 32].float())))
        model.train()
        p = inner(torch.cat(preds))
        t, w = targets(y)
        t, w = inner(t), inner(w)
        res = {"all": metrics(p, t, w)}
        if regions is not None:
            for r in sorted(set(regions)):
                m = torch.tensor([q == r for q in regions], device=p.device)
                res[r] = metrics(p[m], t[m], w[m])
        return res

    for step in range(1, args.steps + 1):
        i = torch.randint(0, len(xt), (args.batch,), device=dev)
        x, y = xt[i].float(), yt[i]
        tr_, fr, fc = (torch.randint(0, 2, (3,)) == 1).tolist()
        # Dihedral augmentation (vegetation has no preferred direction here; aspect is not an input).
        if tr_:
            x, y = x.transpose(-1, -2), y.transpose(-1, -2)
        if fr:
            x, y = x.flip(-2), y.flip(-2)
        if fc:
            x, y = x.flip(-1), y.flip(-1)
        if args.climate_noise > 0:
            # Per-crop constant offsets on the climate inputs (channels 5..12, normalised units): a region's exact
            # climate fingerprint stops being a lookup key, so the node leans on terrain / water / climate GRADIENTS
            # (r0: held-out error grew after 2k steps while in-region error kept falling).
            x = x.clone()
            x[:, 5:13] += torch.randn(x.shape[0], 8, 1, 1, device=dev) * args.climate_noise
        t, w = targets(y)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            logits = model(x.contiguous())
        loss = (F.binary_cross_entropy_with_logits(inner(logits.float()), inner(t), reduction="none") * inner(w)).sum() \
            / inner(w).sum().clamp_min(1.0)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step(); sched.step()
        if step % 200 == 0:
            print(f"step {step:6d}  loss {loss.item():.4f}  {time.time() - t0:.0f}s", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, "val": evaluate(xv, yv)["all"]}
            if xh is not None:
                ev["holdout"] = evaluate(xh, yh, ho_regions)
            print("  eval", json.dumps(ev), flush=True)
            log.write(json.dumps(ev) + "\n"); log.flush()
            ck = {"model": model.state_dict(), "config": model.cfg.to_dict(), "step": step, "eval": ev,
                  "outputs": list(VN.OUTPUTS), "holdout": sorted(hold) if len(ho) else []}
            torch.save(ck, args.out / "last.pt")
            sel = ev["holdout"]["all"] if "holdout" in ev else ev["val"]
            score = sum(sel[k] for k in ("cover", "clearing", "tree", "grass", "shrub", "bare")) + sel["hcan"] / 10
            if score < best:
                best = score
                torch.save(ck, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min", flush=True)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--data-dir", type=Path, required=True)
    b.add_argument("--out", type=Path, required=True)
    b.add_argument("--train-per-region", type=int, default=180)
    b.add_argument("--val-per-region", type=int, default=16)
    b.add_argument("--holdout-per-region", type=int, default=96)
    b.add_argument("--workers", type=int, default=4)
    t = sub.add_parser("train")
    t.add_argument("--dataset", type=Path, required=True)
    t.add_argument("--out", type=Path, required=True)
    t.add_argument("--device", default="cuda:1")
    t.add_argument("--steps", type=int, default=12000)
    t.add_argument("--batch", type=int, default=24)
    t.add_argument("--lr", type=float, default=2e-3)
    t.add_argument("--channels", type=int, nargs=2, default=[48, 96])
    t.add_argument("--holdout", default=None, help="comma list (default: the dataset's HOLDOUT)")
    t.add_argument("--all-regions", action="store_true", help="train on every region (holdout too)")
    t.add_argument("--climate-noise", type=float, default=0.0, help="per-crop climate input offset sigma")
    t.add_argument("--wd", type=float, default=0.01)
    t.add_argument("--eval-every", type=int, default=2000)
    args = ap.parse_args()
    build_dataset(args) if args.cmd == "build" else train(args)


if __name__ == "__main__":
    main()
