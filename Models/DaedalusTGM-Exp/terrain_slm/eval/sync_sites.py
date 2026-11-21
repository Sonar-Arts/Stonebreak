"""Where a tile build synchronises the GPU: torch's sync debug mode over a spiral of tile builds, counted by source
line (each torch op that waits for the device -- .item(), bool(tensor), .cpu(), a pageable host->device copy, ...).

    python -m terrain_slm.eval.sync_sites [model_dir] [--device cuda:1] [--scale 1:4] [--tiles 4] [--top 40]

Reports the lines of the LAST tiles (steady state: the first tile's neighbourhood is cached by then) and of the first.
Native launches through ctypes are not torch ops and never show up (they do not sync).
"""
from __future__ import annotations

import argparse
import collections
import sys
import traceback
import warnings
from pathlib import Path

import torch


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.eval.sync_sites")
    ap.add_argument("model_path", nargs="?", default="checkpoints/exp2/world_exp2b")
    ap.add_argument("--device", default="cuda:1")
    ap.add_argument("--scale", default="1:4")
    ap.add_argument("--tiles", type=int, default=4)
    ap.add_argument("--seed", type=int, default=12345)
    ap.add_argument("--top", type=int, default=40)
    args = ap.parse_args(argv)
    from terrain_slm.eval.pipe_bench import game_world, spiral
    from terrain_slm.paths import MODEL_DIR
    from terrain_slm.world.generator import GenConfig
    from terrain_slm.world.tiles import TileBuilder
    model_dir = Path(args.model_path)
    if not model_dir.is_absolute():
        model_dir = MODEL_DIR / model_dir
    torch.cuda.set_device(torch.device(args.device))
    if (model_dir / "climate.pt").exists():
        from terrain_slm.world.generator2 import WorldGenerator2 as Gen
    else:
        from terrain_slm.world.generator import WorldGenerator as Gen
    gen = Gen(model_dir, args.seed, args.device, GenConfig(t_start=0.6, steps=8, amp_scale=1.0, river_log_threshold=None))
    gen.warm_up()
    builder = TileBuilder(gen, game_world(args.scale))
    root = str(Path(__file__).resolve().parents[1])
    per_tile = []
    orig = warnings.showwarning

    for tx, tz in spiral(args.tiles):
        sites = collections.Counter()

        def show(message, category, filename, lineno, file=None, line=None):
            # the innermost frame inside terrain_slm = the line that synchronised
            for fr in reversed(traceback.extract_stack()[:-1]):
                if fr.filename.startswith(root) and "sync_sites" not in fr.filename:
                    sites[f"{Path(fr.filename).relative_to(root)}:{fr.lineno} {fr.line.strip()[:70]}"] += 1
                    return
            sites[f"{filename}:{lineno}"] += 1

        warnings.showwarning = show
        with warnings.catch_warnings():
            warnings.simplefilter("always")
            warnings.showwarning = show
            torch.cuda.set_sync_debug_mode("warn")
            try:
                builder.build(args.seed, tx, tz, 1)
            finally:
                torch.cuda.set_sync_debug_mode("default")
        warnings.showwarning = orig
        per_tile.append(sites)
        print(f"tile {tx},{tz}: {sum(sites.values())} syncs", flush=True)
    for label, sites in (("first tile", per_tile[0]), ("last tile", per_tile[-1])):
        print(f"--- {label}: {sum(sites.values())} syncs")
        for site, n in sites.most_common(args.top):
            print(f"  {n:6d}  {site}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
