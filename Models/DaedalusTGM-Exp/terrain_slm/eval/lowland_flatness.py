"""Lowland texture at block scale (2026-11-18): how much lowland is dead flat, gentle (meadows), rolling or hilly, for
real terrain and for generated tiles, at the game's 1:4 world.

    python -m terrain_slm.eval.lowland_flatness --real          # Earth lowlands (data v2), block heights via the 1:4 curve
    python -m terrain_slm.eval.lowland_flatness --gen v4 exp2 exp2-noboost [--seeds 12345 777] [--tiles 6]

Lowland = land columns 1..LOWLAND_BLOCKS above sea level, not water. Relief = max - min block height within a
RELIEF_BLOCKS-wide window (~500 m at 60 m blocks). Classes: flat <= 1 block, gentle 2-3 (meadows), rolling 4-8,
hilly > 8.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

LOWLAND_BLOCKS = 37          # 600 m at the 1:4 lowland rate (16 m / block)
RELIEF_BLOCKS = 9
CLASSES = (("flat <=1", 0, 1), ("gentle 2-3", 2, 3), ("rolling 4-8", 4, 8), ("hilly >8", 9, 10 ** 6))


def world_1to4():
    from terrain_slm.world.world_config import WorldConfig
    return WorldConfig(world_height=640, min_y=-384, sea_level=64, horizontal_m=60.0, downscale=2, tile_size=256,
                       ocean_m_per_block=48.0, lowland_m_per_block=16.0, midland_m_per_block=24.0,
                       highland_m_per_block=38.0, lowland_top_m=600.0, highland_base_m=2000.0, shore_blend_m=60.0,
                       midland_blend_m=150.0, highland_blend_m=300.0)


def classify(h: np.ndarray, water: np.ndarray | None, sea: int) -> dict:
    t = torch.from_numpy(h.astype(np.float32))[None, None]
    r = RELIEF_BLOCKS // 2
    mx = F.max_pool2d(t, RELIEF_BLOCKS, 1, r)[0, 0].numpy()
    mn = -F.max_pool2d(-t, RELIEF_BLOCKS, 1, r)[0, 0].numpy()
    rel = (mx - mn)[r:-r, r:-r]
    hh = h[r:-r, r:-r]
    low = (hh > sea) & (hh <= sea + LOWLAND_BLOCKS)
    if water is not None:
        low &= ~water[r:-r, r:-r]
    out = {"lowland_share": float(low.mean())}
    v = rel[low]
    for name, lo, hi in CLASSES:
        out[name] = float(((v >= lo) & (v <= hi)).mean()) if v.size else 0.0
    return out


def real(data: Path, regions: list[str]) -> dict:
    from terrain_slm.river.scale import BlockScale
    from terrain_slm.world.world_config import WorldConfig  # noqa: F401

    scale = BlockScale.from_world(world_1to4(), "cpu")
    res = {}
    for name in regions:
        dem = np.load(data / name / "dem.npy", mmap_mode="r")
        h, w = dem.shape
        acc = []
        g = np.random.default_rng(0)
        for _ in range(8):                                     # 8 random 512x512 (60 m) windows per region
            r0, c0 = int(g.integers(0, h - 1024)), int(g.integers(0, w - 1024))
            z = np.asarray(dem[r0 : r0 + 1024, c0 : c0 + 1024], np.float32).reshape(512, 2, 512, 2).mean((1, 3))
            blocks = np.floor(scale.to_blocks(torch.from_numpy(z)).numpy())
            acc.append(classify(blocks, None, 64))
        res[name] = {k: float(np.mean([a[k] for a in acc])) for k in acc[0]}
    return res


def generated(which: str, seeds: list[int], tiles: int, device: str) -> dict:
    from terrain_slm.world.generator import WorldGenerator
    from terrain_slm.world.generator2 import WorldGenerator2
    from terrain_slm.world.tiles import TileBuilder

    root = Path(__file__).resolve().parents[2] / "checkpoints"
    acc = []
    for seed in seeds:
        if which == "v4":
            gen = WorldGenerator(root / "v4", seed, device)
        else:
            gen = WorldGenerator2(root / "exp2" / "world_exp2", seed, device)
            if which == "exp2-noboost":
                gen.lowland_boost = 0.0
            elif which.startswith("exp2@"):                    # exp2@<boost>: sweep the lowland boost
                gen.lowland_boost = float(which[5:])
        tb = TileBuilder(gen, world_1to4())
        for k in range(tiles):                                 # a ring of tiles around spawn
            tx, tz = [(0, 0), (1, 0), (0, 1), (-1, 0), (0, -1), (1, 1), (-1, -1), (2, 0), (0, 2), (-2, 0)][k]
            p = tb.build(seed, tx, tz, 1)
            acc.append(classify(p[0].astype(np.int32), p[2] > p[0], 64))
    lw = np.array([a["lowland_share"] for a in acc])
    out = {"lowland_share": float(lw.mean())}
    for name, _, _ in CLASSES:                                 # lowland-weighted
        out[name] = float(np.sum([a[name] * a["lowland_share"] for a in acc]) / max(lw.sum(), 1e-9))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--real", action="store_true")
    ap.add_argument("--data", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/v2-gedtm30"))
    ap.add_argument("--regions", nargs="+", default=["great_plains", "ozarks", "appalachian_plateau", "rhenish_massif",
                                                      "massif_central", "loess_plateau", "wadden", "baltic_skerries"])
    ap.add_argument("--gen", nargs="*", default=[])
    ap.add_argument("--seeds", type=int, nargs="+", default=[12345, 777])
    ap.add_argument("--tiles", type=int, default=6)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--json", type=Path, default=None)
    args = ap.parse_args()
    res = {}
    if args.real:
        res["real"] = real(args.data, args.regions)
    for which in args.gen:
        res[which] = generated(which, args.seeds, args.tiles, args.device)
    cols = ["lowland_share"] + [c[0] for c in CLASSES]
    print(f"{'':22s}" + "".join(f"{c:>13s}" for c in cols))
    for group, v in res.items():
        rows = v.items() if group == "real" else [(group, v)]
        for name, r in rows:
            print(f"{name:22s}" + "".join(f"{r[c]:13.0%}" for c in cols))
    if args.json:
        args.json.write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
