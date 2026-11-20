"""Compile every GPU kernel the terrain model needs, outside the game (scripts/terrain-kernels.sh runs this).

The game's first-launch setup (ModelSetup -> warmup.py) compiles the Python path's kernels only. The native in-process
pipeline (libstonebreak_terrain on the Cenda engine) compiles its own kernels lazily, the first time a tile needs each
one - i.e. during the first world load, while that load already keeps the GPU busy. This compiles them ahead:

  1. the Python path's kernels (exactly what warmup.py does; --native-only skips it),
  2. the native release export (<model>/native), written when missing or stale,
  3. the native engine opened on the GPU (compiles the model kernels at warm-up),
  4. tiles of two climates at every level of detail the game asks for (1, 2, 8, 16) at each scale (1:4, 1:2, 1:1), which compiles
     every tile-stage kernel variant (kernels are specialised by shape, so each scale has its own).

Everything lands in the kernel cache (TGM_KERNEL_CACHE, default Models/kernel_cache) where the game and the TGMPipe
service look first; a second run finds everything cached and only checks. Prints one line per step, and a final
"{...}" JSON line (--json) for tools.

    python -m terrain_slm.tgmpipe.precompile [model_path] [--device cuda:N] [--native-only | --python-only]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

#: The handshake world configs of the three scales (height curve v2, belts): what new worlds send. Frozen by
#: tests/test_height_curve_v2_golden.py and the game's HeightCurveV2GoldenTest. Older curve / layout worlds use the
#: same kernel shapes per scale.
SCALES = {
    "1:4": {"world_height": 640, "min_y": -384, "sea_level": 64, "horizontal_m": 60.0, "downscale": 2,
            "tile_size": 256, "ocean_m_per_block": 32.0, "lowland_m_per_block": 16.0, "midland_m_per_block": 24.0,
            "highland_m_per_block": 38.0, "lowland_top_m": 600.0, "highland_base_m": 2000.0, "shore_blend_m": 10.0,
            "midland_blend_m": 150.0, "highland_blend_m": 300.0, "climate_layout": "belts", "curve_version": 2,
            "shelf_m_per_block": 6.0, "shelf_base_m": -300.0, "shelf_blend_m": 150.0, "shallow_m_per_block": 4.0,
            "shallow_base_m": -24.0, "shallow_blend_m": 12.0},
    "1:2": {"world_height": 640, "min_y": -384, "sea_level": 64, "horizontal_m": 30.0, "downscale": 1,
            "tile_size": 256, "ocean_m_per_block": 32.0, "lowland_m_per_block": 8.0, "midland_m_per_block": 12.0,
            "highland_m_per_block": 19.0, "lowland_top_m": 600.0, "highland_base_m": 2000.0, "shore_blend_m": 10.0,
            "midland_blend_m": 150.0, "highland_blend_m": 300.0, "climate_layout": "belts", "curve_version": 2,
            "shelf_m_per_block": 3.0, "shelf_base_m": -300.0, "shelf_blend_m": 150.0, "shallow_m_per_block": 2.0,
            "shallow_base_m": -24.0, "shallow_blend_m": 12.0},
    "1:1": {"world_height": 640, "min_y": -384, "sea_level": 64, "horizontal_m": 15.0, "downscale": 0.5,
            "tile_size": 256, "ocean_m_per_block": 48.0, "lowland_m_per_block": 4.0, "midland_m_per_block": 6.85,
            "highland_m_per_block": 32.0, "lowland_top_m": 600.0, "highland_base_m": 2600.0, "shore_blend_m": 10.0,
            "midland_blend_m": 150.0, "highland_blend_m": 1000.0, "climate_layout": "belts", "curve_version": 2,
            "shelf_m_per_block": 1.5, "shelf_base_m": -300.0, "shelf_blend_m": 150.0, "shallow_m_per_block": 1.0,
            "shallow_base_m": -24.0, "shallow_blend_m": 12.0},
}
#: Levels of detail the game requests (1 = cubes, 2 / 8 / 16 = far terrain and the map).
LODS = (1, 2, 8, 16)
#: Seeds whose spawn tiles are land in different climates (4242 temperate forest + rivers, 12345 warm semi-arid):
#: the controls / biome stages specialise kernels by what a tile holds, so one climate alone misses some.
SEEDS = (4242, 12345)


def _say(msg: str) -> None:
    print(f"[kernels] {msg}", flush=True)


def _cache_files(cache: Path) -> int:
    return sum(1 for _ in cache.rglob("*") if _.is_file())


def python_kernels(model_dir: Path, device: str) -> None:
    from terrain_slm.world.generator2 import WorldGenerator2
    gen = WorldGenerator2(model_dir, 0, device)
    gen.warm_up(lambda what: _say(f"  python: {what}"))
    del gen


def native_kernels(model_dir: Path, device: str) -> dict:
    import gc

    import torch

    from terrain_slm.export import native_release as NR
    from terrain_slm.export.native_terrain import NativeTerrain

    out = model_dir / "native"
    key = NR.export_key(model_dir)
    if not NR.current(out, key):
        _say(f"writing the native release export {out} (key {key})")
        os.environ.setdefault("TGM_ENGINE", "cenda")
        from terrain_slm.world import generator2 as G2
        gen = G2.WorldGenerator2(model_dir, 0, device)
        gen.use_climate_layout("belts")
        gen.default_climate_layout = "belts"
        NR.write(gen, out, key)
        del gen
        gc.collect()
        torch.cuda.empty_cache()
    idx = torch.device(device).index
    if idx is None:
        idx = torch.cuda.current_device()
    t0 = time.monotonic()
    t = NativeTerrain(out, idx, lanes=1)          # warm-up = the model kernels
    _say(f"native engine open + model kernels: {time.monotonic() - t0:.1f} s ({t.model_id})")
    tiles = {}
    for i, (scale, cfg) in enumerate(SCALES.items()):
        t.set_world(i, cfg)
        for lod in LODS:
            t1 = time.monotonic()
            for seed in SEEDS:
                t.tile(i, seed, 0, 0, lod)
            dt = time.monotonic() - t1
            tiles[f"{scale}/l{lod}"] = round(dt, 2)
            _say(f"  tiles {scale} lod {lod}: {dt:.1f} s")
    del t
    gc.collect()
    return tiles


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.tgmpipe.precompile", description=__doc__.split("\n")[0])
    from terrain_slm.model_files import DEFAULT_MODEL
    ap.add_argument("model_path", nargs="?", default=DEFAULT_MODEL)
    ap.add_argument("--device", default="auto", help="cuda:N; auto = the game's rule (cuda:1 on a two-GPU machine)")
    which = ap.add_mutually_exclusive_group()
    which.add_argument("--native-only", action="store_true", help="skip the Python path's kernels")
    which.add_argument("--python-only", action="store_true", help="skip the native engine's kernels")
    ap.add_argument("--json", action="store_true", help="end with one JSON summary line")
    args = ap.parse_args(argv)

    import torch

    from terrain_slm.kernels import native
    from terrain_slm.model_files import ModelFilesError, require_exp2_model
    from terrain_slm.paths import MODEL_DIR
    from terrain_slm.tgmpipe.service import pick_device

    if not torch.cuda.is_available():
        _say("ERROR: PyTorch cannot see a CUDA GPU (NVIDIA driver missing or too old?)")
        return 1
    device = pick_device("cuda" if args.device == "auto" else args.device)
    model_dir = Path(args.model_path)
    if not model_dir.is_absolute():
        model_dir = MODEL_DIR / model_dir
    try:
        require_exp2_model(model_dir)
    except ModelFilesError as e:
        _say(f"ERROR: {e}")
        return 1
    cache = native.cache_dir()
    before = _cache_files(cache)
    _say(f"{model_dir.name} on {device} ({torch.cuda.get_device_name(torch.device(device))}); cache {cache} "
         f"({before} files)")
    t0 = time.monotonic()
    summary = {"model": model_dir.name, "device": device, "cache": str(cache)}
    try:
        if not args.native_only:
            t1 = time.monotonic()
            python_kernels(model_dir, device)
            summary["python_s"] = round(time.monotonic() - t1, 1)
            _say(f"python path kernels: {summary['python_s']} s")
            torch.cuda.empty_cache()
        if not args.python_only:
            t1 = time.monotonic()
            summary["tiles_s"] = native_kernels(model_dir, device)
            summary["native_s"] = round(time.monotonic() - t1, 1)
            _say(f"native kernels: {summary['native_s']} s")
    except Exception as e:  # noqa: BLE001 - one readable line for the player / the game
        _say(f"ERROR: {type(e).__name__}: {e}")
        import traceback
        traceback.print_exc()
        return 1
    summary["new_files"] = _cache_files(cache) - before
    summary["seconds"] = round(time.monotonic() - t0, 1)
    _say(f"done in {summary['seconds']} s: {summary['new_files']} new cache files"
         + (" (everything was already compiled)" if summary["new_files"] == 0 else ""))
    if args.json:
        print(json.dumps(summary), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
