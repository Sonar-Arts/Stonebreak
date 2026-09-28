"""World report: the three things players see first, measured in game blocks.

    python -m terrain_slm.eval.world_report --model checkpoints/v4 --seeds 0 1 2 --out reports/v4

For each seed, builds a BLOCKS x BLOCKS square of block columns through the full pipeline
(river_field -> block stages, exactly what TGMPipe sends) centred on spawn, and reports:

  lowland hilliness  over dry land below LOWLAND_Y: local relief (y minus its 8-block blur, std),
                     the share of columns whose 16-block neighbourhood spans >= 4 / >= 8 blocks,
                     and the p90 block step -- flat lowlands show as ~0 relief and ~0% hilly;
  rivers             wet share of land; UPHILL = wet columns whose downstream neighbour (along
                     their own flow octant) is wet with a HIGHER water level; FLIPS = wet columns
                     whose downstream wet neighbour's octant turns by more than 90 degrees;
                     REVERSALS = downstream neighbour flows straight back (135-180 degrees);
  a hillshade PNG with rivers.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.device import default_device
from terrain_slm.river import pipeline as RP
from terrain_slm.world import generator as G

BLOCKS = 768
LOWLAND_Y = 100      # ~600 m under the game curve
D = 2                # native px per block
OCT = [(round(np.cos(k * np.pi / 4)), round(np.sin(k * np.pi / 4))) for k in range(8)]  # (d row=x, d col=z)


def columns(gen, bi0: int, bj0: int, n: int):
    mb = RP.BLOCK_MARGIN
    n1, m1, n2, m2 = (bi0 - mb) * D, (bj0 - mb) * D, (bi0 + n + mb) * D, (bj0 + n + mb) * D
    field, ctx = gen.river_field(n1, m1, n2, m2)
    cols = gen.river.columns(field, ctx, D)
    crop = lambda x: x[mb:-mb, mb:-mb].cpu().numpy()
    return {k: crop(getattr(cols, k)) for k in ("height", "water", "flow", "wet")}, ctx.stats


def containment(builder, bi0: int, bj0: int, n: int) -> dict:
    """Leaks in the planes the game receives (TileBuilder): every river water block -- not only a
    column's top one -- must meet, sideways, solid ground, the sea or another wet column; and a wet
    neighbour may sit at most one block lower (a taller drop exposes water to air)."""
    height, _, level, wet, (floor, roof, _) = builder._blocks(bi0, bj0, bi0 + n, bj0 + n, D)
    top = builder.world.world_height - 1
    h = height.clamp(0, top).cpu().numpy(); wet = wet.cpu().numpy()
    lv = np.where(wet, level.clamp(0, top).cpu().numpy(), -1)
    floor, roof = floor.cpu().numpy(), roof.cpu().numpy()
    sea = h < builder.world.sea_level
    tunnel = roof > floor
    bottom = np.where(tunnel, floor + 1, h)
    leaks = steps = 0
    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nb = lambda a: np.roll(a, (di, dj), (0, 1))
        inner = np.zeros_like(wet); inner[1:-1, 1:-1] = True
        exposed = wet & ~nb(wet) & ~nb(sea) & inner
        for y in range(int(bottom[wet].min()) if wet.any() else 0, int(lv.max()) if wet.any() else 0):
            has = exposed & (bottom <= y) & (y < lv)
            if not has.any():
                continue
            nt = (nb(roof) > nb(floor)) & (y > nb(floor)) & (y < nb(roof))
            solid = (y < nb(h)) & ~nt
            leaks += int((has & ~solid).sum())
        steps += int((wet & nb(wet) & (lv - nb(lv) > 1) & inner).sum())
    return {"leaking_water_blocks": leaks, "steps_over_1": steps, "wet_columns": int(wet.sum())}


def lowland_stats(y: np.ndarray, wet: np.ndarray, sea_y: int) -> dict:
    from scipy import ndimage
    yf = y.astype(np.float64)
    low = (~wet) & (y > sea_y) & (y < LOWLAND_Y)
    local = yf - ndimage.gaussian_filter(yf, 8)
    span = ndimage.maximum_filter(yf, 16) - ndimage.minimum_filter(yf, 16)
    g = np.maximum(np.abs(np.diff(yf, axis=0))[:, :-1], np.abs(np.diff(yf, axis=1))[:-1])
    lowc = low[:-1, :-1]
    if low.sum() == 0:
        return {"lowland_frac": 0.0}
    return {"lowland_frac": round(float(low.mean()), 3),
            "local_relief_std": round(float(local[low].std()), 2),
            "span16_ge4": round(float((span[low] >= 4).mean()), 3),
            "span16_ge8": round(float((span[low] >= 8).mean()), 3),
            "p90_step": float(np.percentile(g[lowc], 90))}


def river_stats(c: dict, sea_y: int) -> dict:
    wet, water, flow, h = c["wet"], c["water"], c["flow"], c["height"]
    land = h >= sea_y
    n, m = wet.shape
    downstream_wet = uphill = flips = reversals = 0
    ii, jj = np.nonzero(wet & (flow >= 0))
    for i, j in zip(ii, jj):
        o = int(flow[i, j])
        di, dj = OCT[o]
        a, b = i + di, j + dj
        if not (0 <= a < n and 0 <= b < m) or not wet[a, b]:
            continue
        downstream_wet += 1
        if water[a, b] > water[i, j]:
            uphill += 1
        o2 = int(flow[a, b])
        if o2 >= 0:
            turn = min((o2 - o) % 8, (o - o2) % 8)
            flips += turn >= 3
            reversals += turn == 4
    k = max(1, downstream_wet)
    return {"wet_frac_of_land": round(float((wet & land).sum() / max(1, land.sum())), 4),
            "wet_columns": int(wet.sum()),
            "uphill_rate": round(uphill / k, 4), "flip_rate": round(flips / k, 4),
            "reversal_rate": round(reversals / k, 4)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", type=Path, required=True)
    ap.add_argument("--seeds", type=int, nargs="+", default=[0, 1, 2])
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--blocks", type=int, default=BLOCKS)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    rows = []
    for seed in args.seeds:
        gen = G.WorldGenerator(args.model, seed, args.device)
        sea_y = int(gen.scale.sea_level)
        c, stats = columns(gen, -args.blocks // 2, -args.blocks // 2, args.blocks)
        from terrain_slm.world.tiles import TileBuilder
        from terrain_slm.world.world_config import WorldConfig
        leak = containment(TileBuilder(gen, WorldConfig.GAME), -args.blocks // 2, -args.blocks // 2, args.blocks)
        row = {"model": gen.model_id, "seed": seed, "river_threshold": gen.cfg.river_log_threshold, "containment": leak,
               "lowland": lowland_stats(c["height"], c["wet"], sea_y), "rivers": river_stats(c, sea_y),
               "pipeline_stats": {k: v for k, v in stats.items() if isinstance(v, (int, float))}}
        rows.append(row)
        print(json.dumps(row), flush=True)
        _sheet(c, args.out / f"world_seed{seed}.png", f"seed {seed} · {gen.model_id}")
        np.savez_compressed(args.out / f"world_seed{seed}.npz", **c)
    (args.out / "world_report.json").write_text(json.dumps(rows, indent=2))


def _sheet(c: dict, path: Path, title: str):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import LightSource
    y = c["height"].astype(np.float64)
    rgb = LightSource(315, 40).shade(y, cmap=plt.cm.terrain, vert_exag=1.0, blend_mode="overlay", vmin=40, vmax=255)
    rgb[c["wet"]] = (0.15, 0.35, 0.9, 1.0)
    fig, ax = plt.subplots(figsize=(12, 12.4))
    ax.imshow(rgb)
    ax.set_title(title, fontsize=12)
    ax.axis("off")
    plt.tight_layout()
    plt.savefig(path, dpi=80)
    plt.close(fig)


if __name__ == "__main__":
    main()
