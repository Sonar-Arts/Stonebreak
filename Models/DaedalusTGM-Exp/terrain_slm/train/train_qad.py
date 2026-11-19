"""Quantisation-aware distillation to FP8 (plan §1.5, P6): a student with FP8 fake-quantised convs learns to match
its full-precision teacher's velocity (plus a small flow-matching term on the data), straight-through gradients.

    python -m terrain_slm.train.train_qad --teacher checkpoints/exp2/d2/ema_0.10.pt --out checkpoints/exp2/d2_fp8

The student starts as a copy of the teacher; the stem and output conv stay full precision (the usual carve-outs).
Eval = eval/quant_ptq's measure on the student: sampled residual under FP8 vs the teacher in full precision, metres.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import time
from pathlib import Path

import numpy as np
import torch

from terrain_slm.eval.detail_ab import load, sample
from terrain_slm.models import detail as DT
from terrain_slm.models.mp import force_weight_norm_
from terrain_slm.quant.fp8 import fp8_convs
from terrain_slm.train.train_detail import DetailData
from terrain_slm.train.train_detail2 import sample_t

SKIP = (1 + DT.N_COND, 1 + DT.N_COND + 1)     # stem input widths (v4 / Detail2): full precision


@torch.no_grad()
def fp8_error(student, teacher, data: DetailData, steps: int, batches: int = 4) -> dict:
    student.eval()
    rms, worst = [], 0.0
    for k, (cond, x1, base, dem) in enumerate(data.val_batches(32)):
        if k >= batches:
            break
        g = torch.Generator(device=x1.device).manual_seed(k)
        noise = torch.randn(x1.shape, generator=g, device=x1.device)
        ref = DT.decode(sample(teacher, cond, noise, steps))
        with fp8_convs(act="fixed", skip_in_channels=SKIP):
            q = DT.decode(sample(student, cond, noise, steps))
        d = (q - ref).flatten(1)
        rms += d.pow(2).mean(1).sqrt().tolist()
        worst = max(worst, float(d.abs().max()))
    student.train()
    return {"fp8_rms_m_median": round(float(np.median(rms)), 2), "fp8_rms_m_p90": round(float(np.percentile(rms, 90)), 2),
            "fp8_worst_px_m": round(worst, 1)}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data"))
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--steps", type=int, default=3000)
    ap.add_argument("--batch", type=int, default=24)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--data-weight", type=float, default=0.1)
    ap.add_argument("--eval-every", type=int, default=1000)
    ap.add_argument("--eval-steps", type=int, default=16)
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    dev = args.device
    data = DetailData(args.data_dir, dev)
    teacher, ck = load(args.teacher, dev)
    if "norms" in ck:
        data.desc_mean = torch.tensor(ck["norms"]["desc_mean"], device=dev)
        data.desc_std = torch.tensor(ck["norms"]["desc_std"], device=dev)
    teacher.eval().requires_grad_(False)
    student = copy.deepcopy(teacher).train().requires_grad_(True)
    mp = hasattr(student, "fourier_t")
    opt = torch.optim.Adam(student.parameters(), lr=args.lr, betas=(0.9, 0.99))
    log = open(args.out / "log.jsonl", "a")
    ev = {"step": 0, **fp8_error(student, teacher, data, args.eval_steps)}
    print("  eval", json.dumps(ev), flush=True)
    log.write(json.dumps(ev) + "\n"); log.flush()
    t0 = time.time()
    for step in range(1, args.steps + 1):
        cond, x1, *_ = data.train_batch(args.batch)
        t = sample_t(x1.shape[0], dev)
        noise = torch.randn_like(x1)
        xt = (1 - t)[:, None, None, None] * noise + t[:, None, None, None] * x1
        with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
            v_t = teacher(xt, t, cond).float()
        if mp:
            force_weight_norm_(student)
        with fp8_convs(act="fixed", skip_in_channels=SKIP), torch.autocast("cuda", dtype=torch.bfloat16):
            v_s = student(xt, t, cond).float()
        loss = (v_s - v_t).pow(2).mean() + args.data_weight * (v_s - (x1 - noise)).pow(2).mean()
        for g in opt.param_groups:
            g["lr"] = args.lr * min(1.0, step / 100) * 0.5 * (1 + math.cos(math.pi * step / args.steps))
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(student.parameters(), 1.0)
        opt.step()
        if step % 100 == 0:
            print(f"step {step:5d} loss {loss.item():.5f} ({(time.time() - t0) / step * 1000:.0f} ms/step)", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, **fp8_error(student, teacher, data, args.eval_steps)}
            print("  eval", json.dumps(ev), flush=True)
            log.write(json.dumps(ev) + "\n"); log.flush()
            torch.save({"model": student.state_dict(), "config": ck["config"], "norms": ck.get("norms"), "step": step,
                        "quant": {"format": "fp8_e4m3", "weights": "per_out_channel", "acts": "fixed_scale_1",
                                  "full_precision_in_channels": list(SKIP)}, "eval": ev}, args.out / "student.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
