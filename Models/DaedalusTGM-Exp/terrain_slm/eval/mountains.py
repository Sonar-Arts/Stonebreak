"""Mountain report: does a generated world have tall, valley-cut ranges in blocks?

    python -m terrain_slm.eval.mountains --model checkpoints/v2 --seed 2 --out reports/v2

Finds the tallest trend near spawn, generates a 1024x1024-block (61 km) square around it,
and compares it with the highest Alps window of the same size:
  * block stats under the game's height curve (relief, max y, p90/p99 step),
  * band RMS of the block-mean elevation (the averaging symptom: generated/real << 1),
  * relief-window seam agreement (correlation of the two windows' detail in their overlap),
and writes a hillshade sheet (generated with rivers | real) plus a JSON summary.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.paths import BRIDGE_DIR
from terrain_slm.world import generator as G
from terrain_slm.device import default_device

# The game's TerrainScale values (keep in step with TerrainScale.CURVE_RATES).
GAME_ENV = {
    "TERRAIN_BRIDGE_WORLD_HEIGHT": "256", "TERRAIN_BRIDGE_SEA_LEVEL": "64",
    "TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK": "48", "TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK": "16",
    "TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK": "24", "TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK": "38",
}
BLOCKS = 1024


def game_curve():
    for k, v in GAME_ENV.items():
        os.environ.setdefault(k, v)
    sys.path.insert(0, str(BRIDGE_DIR))
    from bridge.config import BridgeConfig
    from bridge.height_mapping import HeightCurve
    return HeightCurve.from_config(BridgeConfig.from_env(seed=0))


def block_stats(y: np.ndarray) -> dict:
    g = np.maximum(np.abs(np.diff(y, axis=0))[:, :-1], np.abs(np.diff(y, axis=1))[:-1])
    return {"relief": int(y.max() - y.min()), "max_y": int(y.max()),
            "p90_step": float(np.percentile(g, 90)), "p99_step": float(np.percentile(g, 99)),
            "frac_step_ge3": float((g >= 3).mean())}


def band_rms(e: np.ndarray) -> list[float]:
    t = torch.from_numpy(e)[None, None].float()
    out, prev = [], t
    for s in (1, 2, 4, 8, 16):
        b = D.blur(t, s)
        out.append(round(float((prev - b).std()), 1))
        prev = b
    return out


def seam_agreement(gen: G.WorldGenerator, wi: int, wj: int) -> float:
    """Correlation of the detail (window minus its sigma-8 blur) of relief windows (wi, wj)
    and (wi, wj+1) over their overlap. ~1 means the cross-fade blends near-identical fields
    (no variance lost at seams); ~0 would halve relief variance mid-seam."""
    a, b = gen._relief_window(wi, wj), gen._relief_window(wi, wj + 1)
    ov = 2 * G.RELIEF_APRON
    hp = lambda x: (x - D.blur(x[None, None], 8.0)[0, 0])
    da = hp(a)[G.RELIEF_APRON:-G.RELIEF_APRON, -ov:]
    db = hp(b)[G.RELIEF_APRON:-G.RELIEF_APRON, :ov]
    # Ignore the 24 cells nearest each window's own edge (weight ~0 in the fade anyway).
    da, db = da[:, 24:-24].flatten(), db[:, 24:-24].flatten()
    return float(torch.corrcoef(torch.stack([da, db]))[0, 1])


def real_alps_window(data: Path) -> np.ndarray:
    dem = np.load(data / "alps" / "dem.npy", mmap_mode="r")
    rng, best, n = np.random.default_rng(1), None, 2 * BLOCKS
    for _ in range(400):
        i, j = rng.integers(0, dem.shape[0] - n), rng.integers(0, dem.shape[1] - n)
        s = float(np.asarray(dem[i + n // 4 : i + 3 * n // 4 : 64, j + n // 4 : j + 3 * n // 4 : 64]).mean())
        if best is None or s > best[0]:
            best = (s, i, j)
    _, i, j = best
    p = np.asarray(dem[i : i + n, j : j + n], dtype=np.float64)
    return p.reshape(BLOCKS, 2, BLOCKS, 2).mean((1, 3))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", type=Path, default=Path("checkpoints/v2"))
    ap.add_argument("--seed", type=int, default=2)
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--out", type=Path, default=Path("reports/v2"))
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    curve = game_curve()
    gen = G.WorldGenerator(args.model, args.seed, args.device)

    c = G.procedural_controls(-1200, -1200, 2400, 2400, args.seed, args.device)
    k = int(torch.argmax(c["trend"]))
    pi, pj = k // 2400 - 1200, k % 2400 - 1200
    i0, j0 = pi * G.CP - BLOCKS, pj * G.CP - BLOCKS  # native px; 2 px per block
    elev, surf = gen.terrain(i0, j0, i0 + 2 * BLOCKS, j0 + 2 * BLOCKS)
    e = F.avg_pool2d(elev[None, None], 2)[0, 0].cpu().numpy().astype(np.float64)
    wet = F.avg_pool2d((~torch.isnan(surf)).float()[None, None], 2)[0, 0].cpu().numpy() >= 0.25
    real = real_alps_window(args.data)

    y_gen = np.floor(curve.to_block_height(e))
    y_real = np.floor(curve.to_block_height(real))
    wi, wj = (pi + G.RELIEF_APRON) // G.RELIEF_CORE, (pj + G.RELIEF_APRON) // G.RELIEF_CORE
    report = {
        "model": gen.model_id, "seed": args.seed, "center_cell": [pi, pj],
        "trend_peak_m": round(float(c["trend"].max()), 0),
        "generated": {"elev_p50_p99_max": [round(float(np.percentile(e, q)), 0) for q in (50, 99, 100)],
                      "blocks": block_stats(y_gen), "band_rms_m": band_rms(e), "river_frac": float(wet.mean())},
        "real_alps": {"elev_p50_p99_max": [round(float(np.percentile(real, q)), 0) for q in (50, 99, 100)],
                      "blocks": block_stats(y_real), "band_rms_m": band_rms(real)},
        "seam_corr": seam_agreement(gen, wi, wj) if gen.relief is not None else None,
    }
    report["band_ratio"] = [round(a / b, 2) for a, b in zip(report["generated"]["band_rms_m"],
                                                             report["real_alps"]["band_rms_m"])]
    (args.out / f"mountains_seed{args.seed}.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import LightSource
    ls = LightSource(315, 40)
    fig, ax = plt.subplots(1, 2, figsize=(22, 11.5))
    for a, y, title, w in ((ax[0], y_gen, f"generated seed {args.seed} ({gen.model_id})", wet),
                           (ax[1], y_real, "real Alps (same curve)", None)):
        rgb = ls.shade(y, cmap=plt.cm.terrain, vert_exag=1.0, blend_mode="overlay", vmin=40, vmax=255)
        if w is not None:
            rgb[w] = (0.15, 0.35, 0.9, 1.0)
        a.imshow(rgb)
        s = block_stats(y)
        a.set_title(f"{title}\nmax y {s['max_y']} · relief {s['relief']} · p99 step {s['p99_step']:.0f}", fontsize=15)
        a.axis("off")
    plt.tight_layout()
    plt.savefig(args.out / f"mountains_seed{args.seed}.png", dpi=70)


if __name__ == "__main__":
    main()
