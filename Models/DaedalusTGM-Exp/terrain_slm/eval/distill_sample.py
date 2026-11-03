"""Distillation sample: unbiased DaedalusTGM-Exp tiles for retuning the Standard (Cenda FastNoise2) generator.

    CUDA_VISIBLE_DEVICES=1 python -m terrain_slm.eval.distill_sample --out /mnt/extrastorage/terrain-slm-data/distill/v4 \
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
from terrain_slm.world.generator import GenConfig, WorldGenerator
from terrain_slm.world.tiles import TileBuilder
from terrain_slm.world.world_config import WorldConfig


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
    ap.add_argument("--model", default="checkpoints/v4")
    ap.add_argument("--out", required=True)
    ap.add_argument("--seeds", type=int, default=16)
    ap.add_argument("--fine", type=int, default=12, help="lod-1 tiles per seed")
    ap.add_argument("--coarse", type=int, default=4, help="lod-8 tiles per seed")
    ap.add_argument("--radius", type=int, default=12_000, help="blocks from origin")
    ap.add_argument("--master-seed", type=int, default=20261101)
    ap.add_argument("--device", default=None)
    args = ap.parse_args(argv)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    device = args.device or default_device()
    model_dir = Path(args.model)
    if not model_dir.is_absolute():
        model_dir = MODEL_DIR / model_dir
    world = WorldConfig.GAME
    gen = WorldGenerator(model_dir, 0, device, GenConfig())
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
        np.savez_compressed(path, height=planes[0], biome=planes[1], water=planes[2],
                            meta=np.array([s, x, z, lod], dtype=np.int64))
        print(f"[{k + 1}/{len(jobs)}] s{s} ({x},{z}) l{lod} {time.monotonic() - t0:.1f} s", flush=True)
    print(f"done in {(time.monotonic() - t_all) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
