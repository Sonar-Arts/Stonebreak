"""Game-launch check of the terrain model: GPU, model, and the GPU kernels compiled ahead of time.

    python -m terrain_slm.tgmpipe.warmup [model_dir] [--device cuda]

The game's model setup (Java `ModelSetup`) runs this once after installing the environment and
whenever the kernels change, so the first world never waits on a compiler. It reports progress
as one JSON object per line on stdout, which the setup screen shows:

    {"stage": "verify", "detail": "..."}      checking the GPU and loading the model
    {"stage": "compile", "detail": "..."}     compiling one group of kernels
    {"done": true, "gpu": "...", "device": "...", "seconds": 12.3}
    {"error": "..."}                          (then exit status 1)

Compiled kernels land in Triton's cache (TRITON_CACHE_DIR, which the game points at
Models/triton_cache), where the TGMPipe service finds them.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path


def _emit(**fields) -> None:
    sys.stdout.write(json.dumps(fields) + "\n")
    sys.stdout.flush()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.tgmpipe.warmup")
    ap.add_argument("model_path", nargs="?", default="checkpoints/v4")
    ap.add_argument("--device", default="cuda")
    args = ap.parse_args(argv)
    t0 = time.monotonic()
    try:
        _emit(stage="verify", detail="Starting PyTorch")
        import torch

        from terrain_slm.paths import MODEL_DIR
        from terrain_slm.tgmpipe.service import pick_device
        from terrain_slm.world.generator import WorldGenerator

        if not torch.cuda.is_available():
            _emit(error="PyTorch cannot see a CUDA GPU (NVIDIA driver missing or too old?)")
            return 1
        device = pick_device(args.device)
        gpu = torch.cuda.get_device_name(torch.device(device))
        _emit(stage="verify", detail=f"Loading the model on {gpu}")
        model_dir = Path(args.model_path)
        if not model_dir.is_absolute():
            model_dir = MODEL_DIR / model_dir
        if not (model_dir / "planner.pt").exists():
            _emit(error=f"model checkpoints not found in {model_dir}")
            return 1
        gen = WorldGenerator(model_dir, 0, device)
        gen.warm_up(lambda what: _emit(stage="compile", detail=what))
        _emit(done=True, gpu=gpu, device=device, model=gen.model_id, seconds=round(time.monotonic() - t0, 1))
        return 0
    except Exception as e:  # noqa: BLE001 - reported to the game, which shows it
        _emit(error=f"{type(e).__name__}: {e}")
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
