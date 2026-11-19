"""Evaluate the vegetation node on whole windows of held-out regions: per-region error tables and maps vs truth.

    python -m terrain_slm.eval.veg_eval --ckpt checkpoints/exp2/veg_r0/best.pt \
        --data-dir /mnt/extrastorage/terrain-slm-data/v2-gedtm30 --regions norway ozarks ... --out <dir> --device cuda:1

Per region: one large window (default 512 x 512 blocks = 30 km) in the region's most vegetated land, predicted in one
pass (replicate-padded edges excluded), plus metrics.json. Maps (PNG): truth | prediction for canopy cover, canopy
height, clearing, and the ground classes as RGB (grass G, shrub R+G, bare R+B), with a hillshade underlay.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageDraw

from terrain_slm.models import vegnet as VN
from terrain_slm.river.scale import BlockScale
from terrain_slm.train import train_veg as T


def load(ckpt: Path, device: str) -> VN.VegNet:
    k = torch.load(ckpt, map_location=device, weights_only=False)
    m = VN.VegNet(VN.VegNetConfig(channels=tuple(k["config"]["channels"]))).to(device).eval()
    m.load_state_dict(k["model"])
    return m


def pick_window(src: T.RegionSource, size: int, g: np.random.Generator, tries: int = 60):
    """Window corner (cells) with the most valid natural land among random candidates."""
    n = size // T.CELL60
    best, bc = -1.0, None
    for _ in range(tries):
        cr, cc = int(g.integers(1, src.hc - n - 1)), int(g.integers(1, src.wc - n - 1))
        v = np.asarray(src.veg[:, cr * 4 : cr * 4 + size : 8, cc * 4 : cc * 4 + size : 8]).astype(np.float32)
        nat = sum(v[T.WC[k]] for k in T.NATURAL) / 255.0
        score = float((nat * (v[T.C_VALID] > 128)).mean() + 0.3 * (v[T.C_COVER] / 255.0).std())
        if score > best:
            best, bc = score, (cr, cc)
    return bc


def shade(elev: np.ndarray) -> np.ndarray:
    gy, gx = np.gradient(elev)
    return np.clip(0.75 + (gx - gy) / 120.0, 0.35, 1.0)


def to_img(a, sh, cmap):
    rgb = cmap(np.clip(a, 0, 1))
    return (rgb * sh[..., None] * 255).astype(np.uint8)


def green(a):
    return np.stack([0.85 - 0.75 * a, 0.9 - 0.35 * a, 0.6 - 0.5 * a], -1)


def heat(a):
    return np.stack([0.95 * a + 0.05, 0.85 - 0.3 * a, 0.3 + 0.5 * (1 - a)], -1)


def ground_rgb(grass, shrub, bare):
    return lambda _: np.stack([np.clip(0.3 * grass + 0.8 * shrub + 0.9 * bare, 0, 1),
                               np.clip(0.9 * grass + 0.6 * shrub + 0.5 * bare, 0, 1), np.clip(0.2 + 0.7 * bare, 0, 1)], -1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", type=Path, required=True)
    ap.add_argument("--data-dir", type=Path, required=True)
    ap.add_argument("--regions", nargs="+", default=list(T.HOLDOUT))
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--device", default="cpu")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    model = load(args.ckpt, args.device)
    scale = BlockScale.game_default("cpu")
    g = np.random.default_rng(7)
    res = {}
    E = T.EDGE
    for name in args.regions:
        src = T.RegionSource(args.data_dir / name, scale)
        cr, cc = pick_window(src, args.size, g)
        s = src.sample(cr, cc, size=args.size, max_water=1.0)
        if s is None:
            print(name, "no window"); continue
        x, y = s
        with torch.no_grad():
            p = torch.sigmoid(model(torch.from_numpy(x.astype(np.float32))[None].to(args.device)))[0].cpu()
        t, w = T.targets(torch.from_numpy(y)[None])
        crop = lambda a: a[..., E:-E, E:-E]
        pc, tc, wc = crop(p)[None], crop(t), crop(w)
        m = T.metrics(pc, tc, wc)
        # spatial pattern: correlation of 5x5-pooled cover (forest structure, not pixel noise)
        pool = lambda a: torch.nn.functional.avg_pool2d(a[None, None], 5)[0, 0]
        msk = pool(wc[0, 0]) > 0.3
        a, b = pool(pc[0, 0])[msk], pool(tc[0, 0])[msk]
        if a.numel() > 10:
            m["cover_r_300m"] = round(float(torch.corrcoef(torch.stack([a, b]))[0, 1]), 4)
        m["window_cells"] = [cr, cc]
        res[name] = m
        print(name, json.dumps(m), flush=True)
        # maps
        elev = x[0].astype(np.float32)[E:-E, E:-E] * 1000.0
        sh = shade(elev)
        P, Tt, W = pc[0].numpy(), tc[0].numpy(), wc[0].numpy()
        mask = W[4] > 0      # natural, valid
        fade = lambda a: np.where(mask, a, np.nan)
        rows = []
        for k, cm, label in ((0, green, "canopy cover"), (1, heat, "canopy height (0-50 m)"), (3, heat, "clearing share")):
            rows.append((to_img(np.nan_to_num(fade(Tt[k]), nan=0), sh, cm), to_img(P[k], sh, cm), label))
        gt = ground_rgb(Tt[6], Tt[5], Tt[7])(None)
        gp = ground_rgb(P[6], P[5], P[7])(None)
        rows.append(((np.clip(gt, 0, 1) * sh[..., None] * 255).astype(np.uint8),
                     (np.clip(gp, 0, 1) * sh[..., None] * 255).astype(np.uint8), "ground: grass G, shrub Y, bare M"))
        hh, ww = rows[0][0].shape[:2]
        canvas = Image.new("RGB", (2 * ww + 30, len(rows) * (hh + 24) + 24), (255, 255, 255))
        d = ImageDraw.Draw(canvas)
        d.text((10, 4), f"{name}: truth (left, white = masked: cropland/built/water) | prediction (right)", fill=(0, 0, 0))
        for i, (a, b, label) in enumerate(rows):
            y0 = 24 + i * (hh + 24)
            if a is not None:
                am = a.copy()
                if i < 3:
                    am[~mask] = 255
                canvas.paste(Image.fromarray(am), (10, y0))
            canvas.paste(Image.fromarray(b), (20 + ww, y0))
            d.text((10, y0 + hh + 4), label, fill=(0, 0, 0))
        canvas.save(args.out / f"{name}.png")
        del src
    (args.out / "metrics.json").write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
