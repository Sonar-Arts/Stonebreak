"""Bit-exact tile regression check: dump tiles from the current code, change something that must not
change terrain (memory layout, caching, offloading), dump again, compare.

    python -m terrain_slm.eval.tile_parity dump  out.npz [--device cuda:0] [--tiles 16] [--overview 4] [--seeds 1 7]
    python -m terrain_slm.eval.tile_parity check out.npz [same flags]

`check` rebuilds the same tiles and fails (exit 1) on the first plane that differs by a single value.
Tiles are built in a fixed order that revisits early tiles last, so cache hits, spills and reloads
are all exercised. Uses the game's current handshake (cube world Y [-384, 640), sea 64, 1:4).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import torch


def game_world():
    from terrain_slm.world.world_config import WorldConfig
    return WorldConfig(world_height=640, min_y=-384, sea_level=64, horizontal_m=60.0, downscale=2,
                       tile_size=256, ocean_m_per_block=48.0, lowland_m_per_block=16.0,
                       midland_m_per_block=24.0, highland_m_per_block=38.0, lowland_top_m=600.0,
                       highland_base_m=2000.0, shore_blend_m=60.0, midland_blend_m=150.0,
                       highland_blend_m=300.0)


def build_all(args) -> dict[str, np.ndarray]:
    from terrain_slm.eval.vram_audit import spiral
    from terrain_slm.paths import MODEL_DIR
    from terrain_slm.model_files import require_exp2_model
    from terrain_slm.world.generator import GenConfig
    from terrain_slm.world.generator2 import WorldGenerator2
    from terrain_slm.world.tiles import TileBuilder

    torch.cuda.set_device(torch.device(args.device))
    model_dir = Path(args.model_path)
    if not model_dir.is_absolute():
        model_dir = MODEL_DIR / model_dir
    require_exp2_model(model_dir)
    # The service's generator and defaults (tgmpipe/service.py): EXP-2 only since v4 was removed (2026-11-19).
    gen = WorldGenerator2(model_dir, args.seeds[0], args.device,
                          GenConfig(t_start=0.6, steps=8, amp_scale=1.0, river_log_threshold=None))
    gen.use_climate_layout("belts")
    gen.warm_up()
    builder = TileBuilder(gen, game_world())
    out = {}
    for seed in args.seeds:
        full = spiral(args.tiles)
        order = full + full[: max(1, len(full) // 4)]          # revisit: cache hits after spills
        for tx, tz in order:
            out[f"s{seed}_x{tx}_z{tz}_l1"] = np.asarray(builder.build(seed, tx, tz, 1))
        for tx, tz in spiral(args.overview):
            out[f"s{seed}_x{tx}_z{tz}_l8"] = np.asarray(builder.build(seed, tx, tz, 8))
    out["_model_id"] = np.array(gen.model_id)
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.eval.tile_parity")
    ap.add_argument("mode", choices=("dump", "check"))
    ap.add_argument("path")
    from terrain_slm.model_files import DEFAULT_MODEL
    ap.add_argument("model_path", nargs="?", default=DEFAULT_MODEL)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--tiles", type=int, default=16)
    ap.add_argument("--overview", type=int, default=4)
    ap.add_argument("--seeds", type=int, nargs="+", default=[1, 7])
    args = ap.parse_args(argv)
    tiles = build_all(args)
    if args.mode == "dump":
        np.savez_compressed(args.path, **tiles)
        print(f"dumped {len(tiles) - 1} tiles ({tiles['_model_id']}) to {args.path}")
        return 0
    ref = np.load(args.path)
    bad = 0
    for k in sorted(set(ref.files) - {"_model_id"}):
        a, b = ref[k], tiles.get(k)
        if b is not None and a.ndim == 3 and b.ndim == 3 and b.shape[0] > a.shape[0]:
            b = b[: a.shape[0]]          # planes added since the reference (carve, 2026-11-18): compare the old ones
        if b is None or a.shape != b.shape or not np.array_equal(a, b):
            diff = "missing" if b is None else f"{int(np.count_nonzero(a != b)) if a.shape == b.shape else 'shape'} values"
            print(f"DIFF {k}: {diff}")
            bad += 1
    n = len(ref.files) - 1
    print(f"{n - bad}/{n} tiles bit-identical (ref {ref['_model_id']}, now {tiles['_model_id']})")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
