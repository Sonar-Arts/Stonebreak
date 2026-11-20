"""Distillation sample: unbiased DaedalusTGM-Exp tiles for retuning the Standard (Cenda FastNoise2) generator.

    CUDA_VISIBLE_DEVICES=1 python -m terrain_slm.eval.distill_sample --out /mnt/extrastorage/terrain-slm-data/distill/exp2-r3 \
        --seeds 16 --fine 12 --coarse 4

The game's tile cache only holds what players walked through (spawn-biased). This builds tiles at
seeded random positions instead, exactly as TGMPipe would (TileBuilder, game WorldConfig):

  fine    lod 1 tiles (256 x 256 blocks)  -- slopes, local relief, rivers, biome texture
  coarse  lod 8 tiles (2048 x 2048 blocks, 8 blocks/sample) -- continents, range spacing, hypsometry

One .npz per tile: height / biome / water int16 planes plus (seed, tile_x, tile_z, lod). Resumable:
existing files are skipped. Lives under eval/ so it does not change the tile-cache source fingerprint.
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

import numpy as np
import torch

from terrain_slm.device import default_device
from terrain_slm.paths import MODEL_DIR
from terrain_slm.model_files import DEFAULT_MODEL
from terrain_slm.world.generator import GenConfig
from terrain_slm.world.generator2 import WorldGenerator2
from terrain_slm.world.tiles import TileBuilder
from terrain_slm.world.world_config import WorldConfig


def game_world(scale: str = "1:4", curve: int = 2, layout: str = "belts") -> WorldConfig:
    """The world a NEW game world sends TGMPipe (TerrainScale.worldConfigJson, 2026-11-19): the cubic column
    [-384, 640) with the sea at 64, the preset's v1 rates, the height-curve version's sea bands and the climate layout.
    (The 2026-11-01 v4 sample used WorldConfig.GAME: the old [0, 256) column, curve v1, no layout.)"""
    from terrain_slm.world import world_config as WC
    k = 1.0 if scale == "1:4" else 0.5
    kw = WC.curve_v2_keys(scale) if curve >= 2 else {}
    base = dict(world_height=640, min_y=-384, sea_level=64, horizontal_m=60.0 * k, downscale=int(round(2 * k)),
                tile_size=256, ocean_m_per_block=48.0 * k, lowland_m_per_block=16.0 * k, midland_m_per_block=24.0 * k,
                highland_m_per_block=38.0 * k, lowland_top_m=600.0, highland_base_m=2000.0, shore_blend_m=60.0,
                midland_blend_m=150.0, highland_blend_m=300.0, climate_layout=layout)
    base.update(kw)
    return WorldConfig(**base)


def positions(rng: np.random.Generator, n: int, radius_blocks: int, tile_blocks: int, include_origin: bool):
    r = max(1, radius_blocks // tile_blocks)
    out = [(0, 0)] if include_origin else []
    while len(out) < n:
        p = (int(rng.integers(-r, r)), int(rng.integers(-r, r)))
        if p not in out:
            out.append(p)
    return out


def main(argv=None) -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=DEFAULT_MODEL)   # EXP-2 only (v4, the 2026-11-01 sample's model, was removed)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seeds", type=int, default=16)
    ap.add_argument("--fine", type=int, default=12, help="lod-1 tiles per seed")
    ap.add_argument("--coarse", type=int, default=4, help="lod-8 tiles per seed")
    ap.add_argument("--radius", type=int, default=12_000, help="blocks from origin")
    ap.add_argument("--master-seed", type=int, default=20261101)
    ap.add_argument("--device", default=None)
    ap.add_argument("--scale", default="1:4", choices=("1:4", "1:2"), help="terrain scale preset")
    ap.add_argument("--curve", type=int, default=2, choices=(1, 2), help="height-curve version")
    ap.add_argument("--layout", default="belts", help="EXP-2 climate layout")
    args = ap.parse_args(argv)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    device = args.device or default_device()
    model_dir = Path(args.model)
    if not model_dir.is_absolute():
        model_dir = MODEL_DIR / model_dir
    world = game_world(args.scale, args.curve, args.layout)
    gen = WorldGenerator2(model_dir, 0, device, GenConfig())
    gen.warm_up()
    builder = TileBuilder(gen, world)
    n = world.tile_size

    rng = np.random.default_rng(args.master_seed)
    seeds = [int(s) for s in rng.integers(-2**62, 2**62, size=args.seeds)]
    jobs = []
    for s in seeds:
        jobs += [(s, x, z, 8) for x, z in positions(rng, args.coarse, args.radius, n * 8, True)]
        jobs += [(s, x, z, 1) for x, z in positions(rng, args.fine, args.radius, n, True)]
    print(f"{len(jobs)} tiles, model {gen.model_id}", flush=True)
    t_all = time.monotonic()
    for k, (s, x, z, lod) in enumerate(jobs):
        path = out / f"s{s}_x{x}_z{z}_l{lod}.npz"
        if path.exists():
            continue
        t0 = time.monotonic()
        with torch.no_grad():
            planes = builder.build(s, x, z, lod)
        surface = {f"surf_{k}": np.asarray(v) for k, v in (getattr(planes, "surface", None) or {}).items()}
        np.savez_compressed(path, height=planes[0], biome=planes[1], water=planes[2],
                            river_floor=planes[3], river_roof=planes[4], river_flow=planes[5], carve=planes[6],
                            meta=np.array([s, x, z, lod], dtype=np.int64), **surface)
        print(f"[{k + 1}/{len(jobs)}] s{s} ({x},{z}) l{lod} {time.monotonic() - t0:.1f} s", flush=True)
    print(f"done in {(time.monotonic() - t_all) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
