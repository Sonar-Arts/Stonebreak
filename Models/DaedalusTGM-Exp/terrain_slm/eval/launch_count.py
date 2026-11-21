"""Kernel launches and host<->device syncs per tile: a speed metric that does not depend on how busy the GPU is.

    python -m terrain_slm.eval.launch_count [model_dir] [--device cuda:1] [--scale 1:4] [--tiles 9] [--json out.json]

Builds a spiral of tiles in-process (TileBuilder, as the service does) after warm-up, each under torch.profiler, and
reports per tile: GPU kernels (device-side activity records: torch's and the project's own NVRTC kernels alike),
blocking syncs (cudaStreamSynchronize / cudaDeviceSynchronize / cudaEventSynchronize / synchronous memcpy calls), the
host time and the GPU's busy time (summed kernel + copy durations; under the profiler, so a little inflated). On a shared GPU (the game rendering, another job) every sync waits for a time slice, so a tile's latency
there grows with its sync count; on an idle GPU launches cost CPU time. Wall-clock benchmarks of the real service:
eval/pipe_bench.py.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from pathlib import Path

import torch

SYNC_NAMES = ("cudaStreamSynchronize", "cudaDeviceSynchronize", "cudaEventSynchronize", "cudaMemcpy",
              "cuStreamSynchronize", "cuCtxSynchronize")


def count(fn):
    from torch.profiler import ProfilerActivity, profile
    t0 = time.perf_counter()
    with profile(activities=[ProfilerActivity.CPU, ProfilerActivity.CUDA]) as prof:
        out = fn()
    dt = time.perf_counter() - t0
    kernels = syncs = memcpy_async = 0
    gpu_us = 0.0
    for e in prof.events():
        name = e.name
        dev = getattr(e, "device_type", None)
        if dev is not None and str(dev).endswith("CUDA"):
            if not name.startswith("Memcpy") and not name.startswith("Memset"):
                kernels += 1
            gpu_us += e.time_range.end - e.time_range.start
            continue
        if name in SYNC_NAMES:
            syncs += 1
        elif name == "cudaMemcpyAsync":
            memcpy_async += 1
    return out, {"kernels": kernels, "syncs": syncs, "memcpy_async": memcpy_async, "seconds": round(dt, 3),
                 "gpu_busy_s": round(gpu_us / 1e6, 3)}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.eval.launch_count")
    ap.add_argument("model_path", nargs="?", default="checkpoints/exp2/world_exp2b")
    ap.add_argument("--device", default="cuda:1")
    ap.add_argument("--scale", default="1:4")
    ap.add_argument("--tiles", type=int, default=9)
    ap.add_argument("--seed", type=int, default=12345)
    ap.add_argument("--json")
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
    rows = []
    for tx, tz in spiral(args.tiles):
        _, st = count(lambda: builder.build(args.seed, tx, tz, 1))
        st["tile"] = [tx, tz]
        rows.append(st)
        print(json.dumps(st), flush=True)
    rest = rows[1:] or rows
    summary = {k: statistics.median(r[k] for r in rest) for k in ("kernels", "syncs", "memcpy_async", "seconds", "gpu_busy_s")}
    summary["first"] = {k: rows[0][k] for k in ("kernels", "syncs", "memcpy_async", "seconds", "gpu_busy_s")}
    summary["model_id"] = gen.model_id
    print("median after the first:", json.dumps(summary))
    if args.json:
        Path(args.json).write_text(json.dumps({"tiles": rows, "summary": summary}, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
