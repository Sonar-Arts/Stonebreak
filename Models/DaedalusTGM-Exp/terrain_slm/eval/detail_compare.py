"""Side-by-side hillshades of detail checkpoints on held-out crops: real terrain | each model, same conditioning and
the same noise, from the usual lightly blurred coarse base and from raw (unblurred) coarse cells, v4's in-game trigger.

    python -m terrain_slm.eval.detail_compare v4=checkpoints/v4/detail.pt exp2=checkpoints/exp2/d2_full_v2/ema_0.10.pt \\
        --regions alps norway himalaya_nepal --out /tmp/compare.png

Crops come from each region's validation tiles (never trained on); per region the `--per-region` crops with the most
real relief are shown, so the comparison is on terrain that has something to get right.
"""
from __future__ import annotations

import argparse
import tempfile
from pathlib import Path

import numpy as np
import torch

from terrain_slm.eval.detail_ab import load, sample
from terrain_slm.models import detail as DT
from terrain_slm.train.train_detail import DetailData


def hillshade(z: np.ndarray, px_m: float = 60.0, az: float = 315.0, alt: float = 40.0) -> np.ndarray:
    gy, gx = np.gradient(z, px_m)
    slope = np.pi / 2 - np.arctan(np.hypot(gx, gy))
    aspect = np.arctan2(-gx, gy)
    a, l = np.radians(az), np.radians(alt)
    return np.clip(np.sin(l) * np.sin(slope) + np.cos(l) * np.cos(slope) * np.cos(a - aspect), 0, 1)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="+", help="label=checkpoint.pt")
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/v2-gedtm30"))
    ap.add_argument("--regions", nargs="+", default=["alps", "norway", "himalaya_nepal"])
    ap.add_argument("--per-region", type=int, default=1)
    ap.add_argument("--steps", type=int, default=16)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--pick", choices=("relief", "coast"), default="relief",
                    help="relief: the most rugged held-out crops; coast: crops 25-85%% below sea level, most rugged")
    ap.add_argument("--zoom", type=int, default=1, help="show the central 1/zoom of each crop (60 m detail becomes visible)")
    args = ap.parse_args(argv)
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    dev = args.device
    with tempfile.TemporaryDirectory() as tmp:                 # load only the requested regions
        for r in args.regions:
            (Path(tmp) / r).symlink_to(args.data_dir / r)
        data = DetailData(Path(tmp), dev)
    models = [(lab, load(Path(p), dev)[0]) for lab, p in (m.split("=", 1) for m in args.models)]
    # the most relief-rich held-out crops of each region
    picks = []
    for reg in data.regions:
        mine = [c for c in data.val_corners if c[0] == reg.cells.index] if hasattr(reg, "cells") else \
               [c for c in data.val_corners if data.regions[c[0]] is reg]
        if not mine:
            continue
        scored = []
        for c in mine[:: max(1, len(mine) // 24)]:
            _, _, base, dem = data.assemble([c], train=False)
            score = float((dem - base).std())
            if args.pick == "coast":
                sea = float((dem < 0).float().mean())
                if not 0.25 <= sea <= 0.85:
                    continue
                score = float(dem.std())                          # coastline + sea-floor relief
            scored.append((score, c))
        picks += [c for _, c in sorted(scored, key=lambda t: -t[0])[: args.per_region]]
    rows = []
    for c in picks:
        name = data.regions[c[0]].name
        for blur, tag in ((1.0, "blurred base"), (0.0, "RAW coarse cells")):
            cond, x1, base, dem = data.assemble([c], train=False, val_blur=blur)
            g = torch.Generator(device=x1.device).manual_seed(7)
            noise = torch.randn(x1.shape, generator=g, device=x1.device)
            outs = []
            with torch.no_grad():
                for lab, m in models:
                    x = sample(m, cond, noise, args.steps)
                    bad = (not bool(torch.isfinite(x).all())) or float(x.abs().max()) >= 7
                    z = (base + DT.decode(x))[0, 0].float().cpu().numpy()
                    rr = float((DT.decode(x)).std() / (dem - base).std().clamp_min(1e-3))
                    outs.append((lab, z, bad, rr))
            rows.append((f"{name} ({tag})", dem[0, 0].float().cpu().numpy(), outs))
    ncol = 1 + len(models)
    fig, axes = plt.subplots(len(rows), ncol, figsize=(4.2 * ncol, 4.2 * len(rows)), squeeze=False)
    def zoomed(z):
        if args.zoom <= 1:
            return z
        h, w = z.shape
        a, b = h // 2 - h // (2 * args.zoom), w // 2 - w // (2 * args.zoom)
        return z[a : a + h // args.zoom, b : b + w // args.zoom]

    for i, (title, real, outs) in enumerate(rows):
        real = zoomed(real)
        outs = [(lab, zoomed(z), bad, rr) for lab, z, bad, rr in outs]
        lo, hi = np.percentile(real, [1, 99])
        panels = [("real", real, False, 1.0)] + outs
        for j, (lab, z, bad, rr) in enumerate(panels):
            ax = axes[i, j]
            zz = np.nan_to_num(z, nan=lo)
            shade = hillshade(zz)
            top = max(hi, 1.0)
            land_rgb = plt.cm.terrain(0.25 + 0.7 * np.clip(zz / top, 0, 1))[..., :3]
            deep = max(-lo, 1.0)
            sea_rgb = plt.cm.Blues(0.95 - 0.7 * np.clip(-zz / deep, 0, 1))[..., :3]
            rgb = np.where((zz < 0)[..., None], sea_rgb, land_rgb) * (0.35 + 0.65 * shade[..., None])
            ax.imshow(rgb, interpolation="nearest")
            sub = "" if lab == "real" else f"  amp {rr:.2f}" + ("  ** BLEW UP **" if bad else "")
            ax.set_title(f"{lab}{sub}", fontsize=11, color="red" if bad else "black")
            ax.set_xticks([]); ax.set_yticks([])
        axes[i, 0].set_ylabel(title, fontsize=11)
    zt = f" -- central 1/{args.zoom} zoom, one pixel = 60 m" if args.zoom > 1 else ""
    fig.suptitle("Held-out crops (never trained on): real | models, same input + same noise. "
                 "Rows alternate blurred base / raw coarse cells (v4's in-game blow-up trigger)" + zt, fontsize=12)
    fig.tight_layout()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(args.out, dpi=80)
    print(f"wrote {args.out} ({len(rows)} rows)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
