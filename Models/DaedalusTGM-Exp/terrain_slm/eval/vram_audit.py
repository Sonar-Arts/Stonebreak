"""Where the TGMPipe service's GPU memory goes (DaedalusTGM-Exp-2 plan, P0).

    python -m terrain_slm.eval.vram_audit [model_dir] [--device cuda:0] [--cache-size 4G]
                                          [--tiles 36] [--overview 0] [--json out.json]

Builds the generator exactly as the service does (same GenConfig defaults, same cache sizing from
--cache-size), warms it up, then builds a spiral of tiles around spawn the way a fresh world asks for
them. After each phase it prints one byte table:

    owner                       bytes   (what holds them)
    params:<module>             model weights (torch modules)
    native:<sampler>            buffers + weights the native samplers allocated through torch
    lru:<cache>                 tensors held by each generator LRU (shared storages counted once)
    torch allocated (other)     everything else torch has live
    torch cached (free)         reserved by the caching allocator but not in use
    outside torch               NVML process total minus torch reserved: CUDA context, cuBLAS/cuDNN
                                workspaces, NVRTC modules, CUDA-graph pools

Nothing here changes a tile: it only reads the generator's state.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

import torch

MiB = 1 << 20


def _storages(obj, seen: set, depth: int = 0) -> int:
    """Bytes of the CUDA tensor storages reachable from `obj` that `seen` has not counted yet."""
    if depth > 6:
        return 0
    if isinstance(obj, torch.Tensor):
        if not obj.is_cuda:
            return 0
        st = obj.untyped_storage()
        key = st.data_ptr()
        if key in seen:
            return 0
        seen.add(key)
        return st.nbytes()
    if isinstance(obj, dict):
        return sum(_storages(v, seen, depth + 1) for v in obj.values())
    if isinstance(obj, (list, tuple, set)):
        return sum(_storages(v, seen, depth + 1) for v in obj)
    if hasattr(obj, "__dict__") and not isinstance(obj, (torch.nn.Module, type)):
        return sum(_storages(v, seen, depth + 1) for v in vars(obj).values())
    return 0


def audit(gen, device) -> list[tuple[str, int]]:
    from terrain_slm.tgmpipe import gpu_memory

    torch.cuda.synchronize(device)
    seen: set = set()
    rows: list[tuple[str, int]] = []
    for name in ("planner", "refiner", "relief", "hydro", "banks", "descgit", "detail", "biomenet"):
        m = getattr(gen, name, None)
        if isinstance(m, torch.nn.Module):
            rows.append((f"params:{name}", _storages(list(m.parameters()) + list(m.buffers()), seen)))
    for key, sampler in getattr(gen, "_samplers", {}).items():
        label = getattr(sampler, "name", None) or type(sampler).__name__
        rows.append((f"native:{label}#{key if isinstance(key, (int, str)) else id(key) % 1000}",
                     _storages(sampler, seen)))
    for attr in sorted(vars(gen)):
        val = getattr(gen, attr)
        if isinstance(val, dict) and type(val).__name__ == "_LRU":
            rows.append((f"lru:{attr} ({len(val)}/{val.cap})", _storages(val, seen)))
    rest = _storages({k: v for k, v in vars(gen).items() if not isinstance(v, torch.nn.Module)}, seen)
    rows.append(("other generator state", rest))
    snap = gpu_memory.snapshot(device)
    counted = sum(b for _, b in rows)
    alloc, reserved = snap.get("allocated_bytes", 0), snap.get("reserved_bytes", 0)
    rows.append(("torch allocated (other)", max(0, alloc - counted)))
    rows.append(("torch cached (free)", reserved - alloc))
    if "process_bytes" in snap:
        rows.append(("outside torch (context, libs, graphs)", snap["process_bytes"] - reserved))
        rows.append(("= NVML process total", snap["process_bytes"]))
    rows.append(("(torch peak allocated)", snap.get("peak_bytes", 0)))
    return rows


def show(title: str, rows) -> None:
    print(f"\n== {title}", flush=True)
    for name, b in rows:
        if b >= MiB or name.startswith(("=", "(")):
            print(f"  {name:<44s} {b / MiB:9.1f} MiB")
    sys.stdout.flush()


def spiral(n: int):
    """Tile coords around (0, 0), nearest first: the order a fresh world asks for them."""
    r = math.ceil(math.sqrt(n)) // 2 + 1
    pts = [(x, z) for x in range(-r, r + 1) for z in range(-r, r + 1)]
    pts.sort(key=lambda p: (max(abs(p[0]), abs(p[1])), p[0] * p[0] + p[1] * p[1], p))
    return pts[:n]


def main(argv=None) -> int:
    from terrain_slm.paths import MODEL_DIR
    from terrain_slm.tgmpipe.service import _bytes
    from terrain_slm.model_files import DEFAULT_MODEL, require_exp2_model
    from terrain_slm.world.generator import GenConfig
    from terrain_slm.world.generator2 import WorldGenerator2
    from terrain_slm.world.tiles import TileBuilder
    from terrain_slm.world.world_config import WorldConfig

    ap = argparse.ArgumentParser(prog="python -m terrain_slm.eval.vram_audit")
    ap.add_argument("model_path", nargs="?", default=DEFAULT_MODEL)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--cache-size", default="4G", help="as the service's --cache-size")
    ap.add_argument("--tiles", type=int, default=36)
    ap.add_argument("--overview", type=int, default=0, help="lod-8 overview tiles after the full ones")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--json", default=None)
    ap.add_argument("--release", action="store_true",
                    help="empty torch's cache after every tile, as the service does when its queue is idle")
    args = ap.parse_args(argv)

    device = torch.device(args.device)
    torch.cuda.set_device(device)
    model_dir = Path(args.model_path)
    if not model_dir.is_absolute():
        model_dir = MODEL_DIR / model_dir
    require_exp2_model(model_dir)       # the service's generator: EXP-2 only (v4 removed 2026-11-19)
    regions = max(64, min(8192, _bytes(args.cache_size) // (384 * 384 * 4)))   # the service's sizing
    report: dict = {"cache_regions": regions}

    from terrain_slm.tgmpipe import gpu_memory
    torch.zeros(1, device=device)
    report["context_only"] = gpu_memory.snapshot(device)
    show("CUDA context only", [("= NVML process total", report["context_only"].get("process_bytes", 0))])

    t0 = time.monotonic()
    gen = WorldGenerator2(model_dir, args.seed, str(device),
                          GenConfig(t_start=0.6, steps=8, amp_scale=1.0, river_log_threshold=None),
                          cache_regions=regions)
    gen.use_climate_layout("belts")
    rows = audit(gen, device)
    show(f"model loaded ({time.monotonic() - t0:.1f} s) {gen.model_id}", rows)
    report["loaded"] = rows

    t0 = time.monotonic()
    gen.warm_up()
    rows = audit(gen, device)
    show(f"warmed up ({time.monotonic() - t0:.1f} s)", rows)
    report["warm"] = rows

    # The game's handshake (main, 2026-11-18): cube world Y [-384, 640), sea 64, 1:4.
    world = WorldConfig(world_height=640, min_y=-384, sea_level=64, horizontal_m=60.0, downscale=2,
                        tile_size=256, ocean_m_per_block=48.0, lowland_m_per_block=16.0,
                        midland_m_per_block=24.0, highland_m_per_block=38.0, lowland_top_m=600.0,
                        highland_base_m=2000.0, shore_blend_m=60.0, midland_blend_m=150.0,
                        highland_blend_m=300.0)
    builder = TileBuilder(gen, world)
    times = []
    for i, (tx, tz) in enumerate(spiral(args.tiles)):
        t0 = time.monotonic()
        if i == 1:
            torch.cuda.reset_peak_memory_stats(device)   # peak from here on = steady-state streaming
        builder.build(args.seed, tx, tz, 1)
        torch.cuda.synchronize(device)
        times.append(time.monotonic() - t0)
        if args.release:
            torch.cuda.empty_cache()
        if i + 1 in (1, 4, 9, 16, 25, 36, 64, 100) or i + 1 == args.tiles:
            rows = audit(gen, device)
            show(f"after {i + 1} tiles (last {times[-1]:.2f} s, mean since #2 "
                 f"{(sum(times[1:]) / max(1, len(times) - 1)):.2f} s)", rows)
            report[f"tiles_{i + 1}"] = rows
    for i, (tx, tz) in enumerate(spiral(args.overview)):
        builder.build(args.seed, tx, tz, 8)
    if args.overview:
        rows = audit(gen, device)
        show(f"after {args.overview} lod-8 overview tiles", rows)
        report["overview"] = rows
    report["tile_seconds"] = times
    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
