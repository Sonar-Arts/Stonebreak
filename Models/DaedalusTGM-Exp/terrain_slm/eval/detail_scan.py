"""In-game stability scan of a detail checkpoint: the "re-scan any new detail checkpoint before shipping it" rule
(Architecture §1a) as a tool. Runs the real generator path (planner cells, controls, drainage, guard) over many
canonical detail windows and seeds, with the candidate's detail.pt swapped into a model directory.

    python -m terrain_slm.eval.detail_scan CANDIDATE.pt [--base checkpoints/v4] [--seeds 8] [--grid 6]
                                           [--blur 1.5] [--torch] [--native-detail] [--device cuda:0]

--blur sets the base blur of the FIRST attempt (1.5 = shipped; 0 = the unblurred planner cells that blew v4 up).
--torch forces the torch samplers for every model (fair A/B between architectures with and without native plans).
--native-detail (with --exp2) runs a Detail2 on its native sampler (kernels/cuda_detail2.py) whatever
generator2.NATIVE_DETAIL says.
Reports per seed and in total: first-attempt failures (non-finite or |x| >= limit), windows that needed the
smooth-base fallback, the largest |x|, and the mean per-window sample std (amplitude).
"""
from __future__ import annotations

import argparse
import json
import shutil
import tempfile
import time
from dataclasses import replace
from pathlib import Path

import torch


def main(argv=None) -> int:
    from terrain_slm.paths import MODEL_DIR
    from terrain_slm.world import generator as G

    ap = argparse.ArgumentParser(prog="python -m terrain_slm.eval.detail_scan")
    ap.add_argument("candidate", type=Path)
    ap.add_argument("--base", default="checkpoints/v4")
    ap.add_argument("--seeds", type=int, default=8)
    ap.add_argument("--grid", type=int, default=6, help="windows per axis per seed")
    ap.add_argument("--blur", type=float, default=G.DETAIL_BASE_BLUR_CELLS)
    ap.add_argument("--torch", action="store_true")
    ap.add_argument("--steps", type=int, default=None, help="sampler steps (default: the generator's)")
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--json", type=Path, default=None)
    ap.add_argument("--exp2", action="store_true", help="scan through WorldGenerator2 (EXP-2 controls + nodes)")
    ap.add_argument("--native-detail", action="store_true", help="force the native Detail2 sampler (with --exp2)")
    args = ap.parse_args(argv)
    torch.cuda.set_device(torch.device(args.device))

    base = Path(args.base)
    base = base if base.is_absolute() else MODEL_DIR / base
    tmp = Path(tempfile.mkdtemp(prefix="detail_scan_"))
    for f in base.glob("*.pt"):
        if f.name != "detail.pt":
            (tmp / f.name).symlink_to(f.resolve())
    (tmp / "detail.pt").symlink_to(args.candidate.resolve())
    # descriptors.blur(x, 0) is NaN (a 0/0 Gaussian): "unblurred" = an identity-width kernel (checked equal to x).
    first = max(args.blur, 1e-3)
    G.DETAIL_RETRY = ((first, True),) + tuple(r for r in G.DETAIL_RETRY if r != (args.blur, True))

    cfg = G.GenConfig(t_start=0.6, steps=8, amp_scale=1.0, river_log_threshold=None, fused_kernels=not args.torch)
    if args.steps is not None:
        cfg = replace(cfg, detail_steps=args.steps)
    if args.exp2:
        from terrain_slm.world import generator2 as G2
        if args.native_detail:
            G2.NATIVE_DETAIL = True
        gen = G2.WorldGenerator2(tmp, 0, args.device, cfg)
        if args.native_detail and gen._detail2_native is None:
            raise SystemExit("--native-detail: the native Detail2 sampler is not available for this checkpoint/device")
    else:
        gen = G.WorldGenerator(tmp, 0, args.device, cfg)
    gen.warm_up()
    calls = []
    inner = gen._sample

    def spy(model, cond, noise, steps, t_start=0.0, **kw):
        x = inner(model, cond, noise, steps, t_start, **kw)
        if model is gen.detail:
            fin = bool(torch.isfinite(x).all())
            calls.append((fin, float(x.nan_to_num(1e9).abs().max()), float(x.nan_to_num(0).std()) if fin else float("nan")))
        return x

    gen._sample = spy
    total = {"windows": 0, "first_fail": 0, "fallback": 0, "max_abs": 0.0, "std": []}
    per_seed = []
    t0 = time.time()
    for seed in range(args.seeds):
        gen.use_seed(seed)
        s = {"seed": seed, "windows": 0, "first_fail": 0, "fallback": 0, "max_abs": 0.0}
        for wi in range(-args.grid // 2, args.grid - args.grid // 2):
            for wj in range(-args.grid // 2, args.grid - args.grid // 2):
                calls.clear()
                gen._detail_window(wi, wj)
                first_ok = calls and calls[0][0] and calls[0][1] < G.DETAIL_X_LIMIT
                fell_back = calls and not any(c[0] and c[1] < G.DETAIL_X_LIMIT for c in calls)
                s["windows"] += 1
                s["first_fail"] += int(not first_ok)
                s["fallback"] += int(bool(fell_back))
                s["max_abs"] = max(s["max_abs"], calls[0][1] if calls else 0.0)
                if calls and calls[0][0]:
                    total["std"].append(calls[0][2])
        per_seed.append(s)
        for k in ("windows", "first_fail", "fallback"):
            total[k] += s[k]
        total["max_abs"] = max(total["max_abs"], s["max_abs"])
        print(json.dumps(s), flush=True)
    total["mean_std"] = round(sum(total["std"]) / max(1, len(total["std"])), 4)
    del total["std"]
    total.update({"candidate": str(args.candidate), "blur": args.blur, "torch": args.torch,
                  "model_id": gen.model_id, "minutes": round((time.time() - t0) / 60, 1)})
    if hasattr(gen, "detail_native_windows"):
        total.update({"native_windows": gen.detail_native_windows, "torch_fallback_windows": gen.detail_torch_windows})
    print("TOTAL", json.dumps(total), flush=True)
    if args.json:
        args.json.write_text(json.dumps({"total": total, "seeds": per_seed}, indent=1))
    shutil.rmtree(tmp, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
