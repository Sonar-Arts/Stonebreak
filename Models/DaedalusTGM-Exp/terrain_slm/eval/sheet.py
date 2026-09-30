"""Eval sheet: held-out real terrain vs each pipeline stage, plus a generated-world preview.

Panels (held-out tile, 512 px = 15 km):
  real | synth(real descriptors) | planner -> synth | planner -> synth -> refiner(t) | refiner(real desc, from noise)
Prints radially averaged spectrum slopes and slope-histogram distances per panel.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P
from terrain_slm.models import refiner as R
from terrain_slm.synth import noise as S
from terrain_slm.world.generator import GenConfig, WorldGenerator
from terrain_slm.device import default_device

CP = D.CELL_PX


def hillshade(z: np.ndarray, pixel_m: float = 30.0, az: float = 315, alt: float = 45) -> np.ndarray:
    gy, gx = np.gradient(z, pixel_m)
    slope = np.arctan(np.hypot(gx, gy))
    aspect = np.arctan2(-gx, gy)
    a, b = math.radians(360 - az + 90), math.radians(alt)
    return np.clip(np.sin(b) * np.cos(slope) + np.cos(b) * np.sin(slope) * np.cos(a - aspect), 0, 1)


def render(ax, z: np.ndarray, title: str, vmin: float, vmax: float):
    hs = hillshade(z)
    tint = plt.get_cmap("terrain")(np.clip((z - vmin) / max(1.0, vmax - vmin), 0, 1) * 0.8 + 0.2)[..., :3]
    ax.imshow(tint * (0.35 + 0.65 * hs[..., None]))
    ax.set_title(title, fontsize=8)
    ax.axis("off")


def spectrum_slope(z: np.ndarray) -> float:
    """Log-log slope of the radially averaged power spectrum over 4..64 px wavelengths."""
    z = z - z.mean()
    win = np.outer(np.hanning(z.shape[0]), np.hanning(z.shape[1]))
    p = np.abs(np.fft.fftshift(np.fft.fft2(z * win))) ** 2
    h, w = z.shape
    yy, xx = np.indices(p.shape)
    r = np.hypot(yy - h / 2, xx - w / 2)
    f = r / h
    sel = (f > 1 / 64) & (f < 1 / 4)
    bins = np.logspace(np.log10(1 / 64), np.log10(1 / 4), 16)
    idx = np.digitize(f[sel], bins)
    pf = [(bins[i - 1], p[sel][idx == i].mean()) for i in range(1, len(bins)) if (idx == i).any()]
    x, y = np.log([a for a, _ in pf]), np.log([b for _, b in pf])
    return float(np.polyfit(x, y, 1)[0])


def slope_hist(z: np.ndarray) -> np.ndarray:
    gy, gx = np.gradient(z, 30.0)
    s = np.degrees(np.arctan(np.hypot(gx, gy)))
    h, _ = np.histogram(s, bins=18, range=(0, 90), density=True)
    return h


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("data"), help="directory holding the region builds")
    ap.add_argument("--model", type=Path, default=Path("checkpoints/v3"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--t-start", type=float, default=0.5)
    ap.add_argument("--out", type=Path, default=Path("reports"))
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    dev = args.device

    gen = WorldGenerator(args.model, seed=42, device=dev, cfg=GenConfig(t_start=args.t_start))
    from terrain_slm.train.planner_data import built_regions
    m = S.MARGIN_CELLS
    n = 64  # cells -> 512 px panels

    rows = []
    for region_dir in built_regions(args.data):
        meta = json.loads((region_dir / "meta.json").read_text())
        cells = np.load(region_dir / "cells.npz")
        dem = np.load(region_dir / "dem.npy", mmap_mode="r")
        tile_h, tile_w = meta["tile_px"][0] // CP, meta["tile_px"][1] // CP
        lat1, lon0 = meta["lat"][1], meta["lon"][0]
        la, lo = meta["val_tiles"][0]
        r0 = (lat1 - 1 - la) * tile_h + tile_h // 2 - n // 2
        c0 = (lo - lon0) * tile_w + tile_w // 2 - n // 2
        sl = (slice(r0 - m, r0 + n + m), slice(c0 - m, c0 + n + m))
        t = lambda a: torch.from_numpy(np.ascontiguousarray(a)).float().to(dev)
        coarse = t(cells["coarse"][sl])[None, None]
        desc = t(cells["desc"][:, sl[0], sl[1]])[None]
        real = np.asarray(dem[r0 * CP : (r0 + n) * CP, c0 * CP : (c0 + n) * CP], dtype=np.float32)
        origin = (r0 * CP, c0 * CP)
        with torch.no_grad():
            synth_real = S.synth(coarse, desc, origin, 1)[0, 0]
            # Planner from degraded-real controls (the way the val loss sees it).
            x = torch.zeros(1, P.N_IN, n + 2 * m, n + 2 * m, device=dev)
            x[0, P.IN_TREND] = D.blur(coarse, 3.0)[0, 0] / P.HEIGHT_SCALE_M
            for k, name in enumerate(P.CLIMATE_NAMES):
                x[0, P.IN_T0 + k] = P.climate_input(name, t(cells[name][sl]))
            x[0, P.IN_WILD] = (D.blur(desc[:, P.WILD_BAND : P.WILD_BAND + 1], 5.0)[0, 0] - P.WILD_NORM[0]) / P.WILD_NORM[1]
            x[0, P.IN_HAS_TREND] = 1
            x[0, P.IN_HAS_CLIMATE] = 1
            x[0, P.IN_HAS_WILD] = 1
            out = gen.planner(x)[0].float()
            p_coarse = (out[P.OUT_HEIGHT] * P.HEIGHT_SCALE_M)[None, None]
            p_desc = (out[P.OUT_DESC] * gen.desc_std.view(-1, 1, 1) + gen.desc_mean.view(-1, 1, 1))[None]
            p_river = torch.sigmoid(out[P.OUT_RIVER])[None, None]
            synth_plan = S.synth(p_coarse, p_desc, origin, 1, river=p_river)[0, 0]

            def refine(cz, dz, rz, start, t0):
                crop = lambda v: v[..., m * CP : (m + n) * CP, m * CP : (m + n) * CP]
                up = lambda v: F.interpolate(v, size=((n + 2 * m) * CP,) * 2, mode="bilinear", align_corners=False)
                base = crop(S.coarse_surface(cz))
                dpx = crop(up(dz))
                rpx = crop(D.blur(up(rz), 3.0))
                scale = R.scale_field(dpx)
                cond = R.build_cond(base, dpx, rpx, gen.desc_mean, gen.desc_std)
                noise = S.white_noise(origin[0], origin[1], n * CP, n * CP, 1, 4096, dev)[None, None]
                xs = None if start is None else (start[None, None] - base) / scale
                return (base + scale * R.sample(gen.refiner, cond, noise, 8, xs, t0))[0, 0]

            refined_plan = refine(p_coarse, p_desc, p_river, synth_plan, args.t_start)
            real_river = t((cells["acc"][sl] >= 434).astype(np.float32))[None, None]
            refined_real = refine(coarse, desc, D.blur(real_river, 1.0), None, 0.0)
        panels = [("real", real), ("synth (real desc)", synth_real.cpu().numpy()),
                  ("planner->synth", synth_plan.cpu().numpy()),
                  (f"planner->synth->refiner t={args.t_start}", refined_plan.cpu().numpy()),
                  ("refiner (real desc, noise)", refined_real.cpu().numpy())]
        rows.append((f"{meta['region']} {la},{lo}", panels))

    fig, axes = plt.subplots(len(rows), 5, figsize=(20, 4.2 * len(rows)))
    report = {}
    for ri, (label, panels) in enumerate(rows):
        real = panels[0][1]
        vmin, vmax = float(np.percentile(real, 1)), float(np.percentile(real, 99))
        hr = slope_hist(real)
        for ci, (name, z) in enumerate(panels):
            ss = spectrum_slope(z)
            sh = float(np.abs(slope_hist(z) - hr).sum() * 5)
            render(axes[ri, ci], z, f"{label} | {name}\nspec slope {ss:.2f}  slope-hist L1 {sh:.2f}", vmin, vmax)
            report.setdefault(name, []).append({"tile": label, "spectrum_slope": ss, "slope_hist_l1": sh})
    fig.tight_layout()
    fig.savefig(args.out / "eval_sheet.png", dpi=90)
    (args.out / "eval_report.json").write_text(json.dumps(report, indent=2))
    for name, v in report.items():
        print(f"{name:40s} spec {np.mean([x['spectrum_slope'] for x in v]):6.2f}  "
              f"slope-hist L1 {np.mean([x['slope_hist_l1'] for x in v]):5.2f}")

    # Generated world preview from procedural controls: 1536 px = 46 km.
    import time
    t0 = time.time()
    world = gen.native(0, 0, 1536, 1536).cpu().numpy()
    dt = time.time() - t0
    fig, ax = plt.subplots(1, 1, figsize=(10, 10))
    render(ax, world, f"generated world, seed 42, 46 km ({dt:.1f} s cold)", float(np.percentile(world, 1)), float(np.percentile(world, 99)))
    fig.tight_layout()
    fig.savefig(args.out / "world_preview.png", dpi=90)
    print(f"world preview 1536^2 native px in {dt:.1f} s; elev range {world.min():.0f}..{world.max():.0f} m")


if __name__ == "__main__":
    main()
