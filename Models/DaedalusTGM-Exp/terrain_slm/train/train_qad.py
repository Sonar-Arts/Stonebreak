"""Quantisation-aware distillation to FP8 (plan §1.5, P6).

Detail2 (default, 2026-11-19 v2): the student runs the KERNEL-EXACT simulation of the native FP8 sampler
(quant/detail2_sim.py: folded weights, e4m3 codes with per-output-channel weight scales, per-tensor activation scale,
fp16 residual stream, fp32 Euler state, the same rounding points as kernels/cuda_detail2_fp8.py), straight-through
gradients, and learns to reproduce the full-precision teacher's SAMPLES at the sampler's own schedule:

  * the MeanFlow 2-step sample is unrolled: student x(0.5) and x(1) vs the teacher's (fp32), the loss weighted by the
    decode slope (decode = ASINH_M sinh(x): the error in metres is ASINH_M cosh(x) dx) -- the metric itself;
  * the step-2 velocity is also matched at the TEACHER's x(0.5) (keeps the student on the teacher's trajectory);
  * conditioning: real generator windows (WorldGenerator2's own detail windows over training seeds, never the eval
    seeds, their own expert gates; captured once to --windows) mixed with DetailData crops (random expert gates);
  * layers named in --keep stay fp16 (the per-layer sensitivity sweep, eval/quant_ptq.py --sweep, picks them).

Eval = decoded residual RMS (m) vs the fp32 teacher on the 21 real windows of tests/test_cuda_detail2 (the same metric
as the native fp16 gate), through the same simulation.

    python -m terrain_slm.train.train_qad --teacher checkpoints/exp2/d2_full_v2_mf_experts/ema_0.10.pt \
        --out checkpoints/exp2/qad_fp8_v2 --data-dir /mnt/extrastorage/terrain-slm-data/v2-gedtm30 --device cuda:1

`--legacy` keeps the first version (torch conv2d patch under bf16 autocast, quant/fp8.py) for v4 detail models.
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
from terrain_slm.models import detail2 as D2
from terrain_slm.models.mp import force_weight_norm_
from terrain_slm.quant import detail2_sim as Q

ROOT = Path(__file__).resolve().parents[2]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2b"
EVAL_SEEDS = (12345, 777, 4242)
EVAL_GRID = ((-1, -1), (-1, 0), (0, -1), (0, 0), (0, 1), (1, 0), (1, 1))
TRAIN_SEEDS = (11, 23, 101, 2027, 31337, 4096, 55555, 8675309, 99991, 123456789)
DEFAULT_KEEP = ("stem", "down0", "enc1.1.c0")
ACT_SCALE = 0.125


# ----------------------------------------------------------------------------- generator windows
def capture_windows(seeds, grid, device: str, model_dir: Path = WORLD):
    """(cond, noise, gates, steps, t_start) of WorldGenerator2's own detail windows (first guard attempt), on the CPU
    in fp16 (cond, gates) / fp32 (noise)."""
    from terrain_slm.world import generator as G
    from terrain_slm.world import generator2 as G2
    gen = G2.WorldGenerator2(model_dir, seeds[0], device)
    got = []

    def spy(model, cond, noise, steps, t_start=0.0, gates=None, **kw):
        if model is gen.detail:
            got.append((cond.half(), noise.clone(), None if gates is None else gates.half(), steps, t_start))
            return torch.zeros_like(noise)
        return G.WorldGenerator._sample(gen, model, cond, noise, steps, t_start, gates=gates, **kw)

    gen._sample = spy
    for seed in seeds:
        gen.use_seed(seed)
        for wi, wj in grid:
            gen._detail_window(wi, wj)
    del gen
    torch.cuda.empty_cache()
    return got


def eval_windows(device: str, cache: Path | None = None):
    if cache is not None and cache.exists():
        w = torch.load(cache, weights_only=False)
    else:
        w = capture_windows(EVAL_SEEDS, EVAL_GRID, device)
        if cache is not None:
            torch.save(w, cache)
    return [(c.float().to(device), z.to(device), None if g is None else g.float().to(device), st, t0)
            for c, z, g, st, t0 in w]


# ----------------------------------------------------------------------------- eval
@torch.no_grad()
def window_errors(model, qc: Q.QConfig, windows, refs) -> np.ndarray:
    """Decoded residual RMS (m) per window of the simulated sampler vs the fp32 references."""
    sim = Q.Detail2Sim(model, qc)
    dec = lambda x: DT.decode(x.clamp(-7.0, 7.0))
    out = []
    for (c, z, g, st, t0), r in zip(windows, refs):
        x = sim.sample(c, z, st, t0, gates=g)
        out.append(float((dec(x) - dec(r)).pow(2).mean().sqrt()))
    return np.array(out)


def summary(a: np.ndarray) -> dict:
    return {"median": round(float(np.median(a)), 3), "p90": round(float(np.percentile(a, 90)), 3),
            "max": round(float(a.max()), 3)}


# ----------------------------------------------------------------------------- training batches
class Batches:
    """Crops of generator windows (prob `p_gen`) or DetailData crops, `size` x `size`, with gates."""

    def __init__(self, gen_windows, data, size: int, p_gen: float, experts: list[str], device: str, seed: int = 0):
        self.gw = gen_windows
        self.data, self.size, self.p_gen, self.experts, self.dev = data, size, p_gen, experts, device
        self.rng = np.random.default_rng(seed)

    def __call__(self, b: int):
        if self.data is None or self.rng.random() < self.p_gen:
            conds, gates = [], []
            for _ in range(b):
                c, g = self.gw[int(self.rng.integers(len(self.gw)))]
                h = c.shape[-1]
                i, j = (int(self.rng.integers(0, h - self.size + 1)) // 16 * 16 for _ in range(2))
                conds.append(c[:, :, i: i + self.size, j: j + self.size])
                gates.append(g[:, :, i: i + self.size, j: j + self.size] if g is not None
                             else torch.zeros(1, len(D2.STYLES), self.size, self.size, device=self.dev, dtype=c.dtype))
            return torch.cat(conds).float(), torch.cat(gates).float()
        cond, x1, *_ = self.data.train_batch(b)
        if cond.shape[-1] != self.size:
            cond = cond[..., : self.size, : self.size]
        gates = None
        if self.experts and self.rng.random() < 0.4:                     # one expert on, whole crop
            gates = torch.zeros(b, len(D2.STYLES), *cond.shape[-2:], device=self.dev)
            gates[:, D2.STYLES.index(self.experts[int(self.rng.integers(len(self.experts)))])] = 1.0
        return cond.float(), gates


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--legacy", action="store_true", help="first version (quant/fp8.py patch under bf16 autocast)")
    ap.add_argument("--data-dir", type=Path, required=True,
                    help="DetailData root: /mnt/extrastorage/terrain-slm-data/v2-gedtm30 (the bare root holds the EXP-1 "
                         "WorldClim regions: non-commercial, never train on it). Required even with --no-data.")
    ap.add_argument("--device", default="cuda:1")
    ap.add_argument("--steps", type=int, default=6000)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--crop", type=int, default=256)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--warmup", type=int, default=200)
    ap.add_argument("--p-gen", type=float, default=0.6, help="share of batches from generator windows")
    ap.add_argument("--no-data", action="store_true", help="generator windows only (DetailData not loaded)")
    ap.add_argument("--keep", default=",".join(DEFAULT_KEEP), help="layers kept fp16 (comma list)")
    ap.add_argument("--act-scale", type=float, default=ACT_SCALE)
    ap.add_argument("--fmt", default="e4m3")
    ap.add_argument("--ema", type=float, default=0.999)
    ap.add_argument("--eval-every", type=int, default=500)
    ap.add_argument("--windows", type=Path, default=None, help="generator training windows cache (.pt)")
    ap.add_argument("--init", type=Path, default=None, help="start the student from this checkpoint")
    # legacy flags
    ap.add_argument("--data-weight", type=float, default=0.1)
    ap.add_argument("--eval-steps", type=int, default=16)
    ap.add_argument("--mean-flow", action="store_true")
    ap.add_argument("--mf-steps", type=int, default=2)
    args = ap.parse_args()
    if args.legacy:
        return legacy_main(args)
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    dev = args.device
    torch.cuda.set_device(dev)
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = True   # exact on pre-rounded operands
    teacher, ck = load(args.teacher, dev)
    teacher.mean_flow = True
    teacher.eval().requires_grad_(False)
    student = copy.deepcopy(teacher)
    if args.init is not None:
        student.load_state_dict(torch.load(args.init, map_location=dev, weights_only=False)["model"])
    student.train().requires_grad_(True)
    student.mean_flow = True
    for p in student.u_linear.parameters():
        p.requires_grad_(False)
    keep = tuple(k for k in args.keep.split(",") if k)
    qc = Q.QConfig(default=args.fmt, layers={k: "fp16" for k in keep}, act_scale=args.act_scale, ste=True)
    qc_eval = Q.QConfig(default=args.fmt, layers={k: "fp16" for k in keep}, act_scale=args.act_scale)

    # eval windows + fp32 references (TF32 off)
    ev_w = eval_windows(dev, args.out / "eval_windows.pt")
    ev_ref = [Q.fp32_sample(teacher, c, z, st, t0, gates=g) for c, z, g, st, t0 in ev_w]

    # training windows
    wpath = args.windows or (args.out / "train_windows.pt")
    if wpath.exists():
        tw = torch.load(wpath, weights_only=False)
    else:
        grid = tuple((i, j) for i in range(-2, 3) for j in range(-2, 3))
        t0 = time.time()
        tw = capture_windows(TRAIN_SEEDS, grid, dev)
        torch.save(tw, wpath)
        print(f"captured {len(tw)} training windows in {time.time() - t0:.0f} s", flush=True)
    gen_windows = [(c.to(dev), None if g is None else g.to(dev)) for c, _, g, _, _ in tw]
    del tw
    data = None
    if not args.no_data and args.p_gen < 1.0:
        from terrain_slm.train.train_detail import DetailData
        data = DetailData(args.data_dir, dev)
        if ck.get("norms"):
            data.desc_mean = torch.tensor(ck["norms"]["desc_mean"], device=dev)
            data.desc_std = torch.tensor(ck["norms"]["desc_std"], device=dev)
    from terrain_slm.world.generator2 import EXPERTS_ENABLED
    experts = [s for s in D2.STYLES if s in student.adapters and s in EXPERTS_ENABLED]
    batches = Batches(gen_windows, data, args.crop, args.p_gen, experts, dev)

    params = [p for p in student.parameters() if p.requires_grad]
    opt = torch.optim.Adam(params, lr=args.lr, betas=(0.9, 0.99))
    ema = copy.deepcopy(student).eval().requires_grad_(False)
    log = open(args.out / "log.jsonl", "a")
    quant_meta = {"format": args.fmt, "weights": "per_out_channel (per phase x channel for up-merges)",
                  "acts": f"per_tensor_static scale {args.act_scale}", "fp16_layers": list(keep),
                  "sim": "quant/detail2_sim.py"}

    def evaluate(step: int) -> dict:
        r = {"step": step}
        for name, m in (("student", student), ("ema", ema)):
            was = m.training
            m.eval()
            r[name] = summary(window_errors(m, qc_eval, ev_w, ev_ref))
            m.train(was)
        print("  eval", json.dumps(r), flush=True)
        log.write(json.dumps(r) + "\n")
        log.flush()
        return r

    best = {"median": 1e9}
    r = evaluate(0)
    sim = Q.Detail2Sim(student, qc)
    t_start = time.time()
    h = 0.5
    for step in range(1, args.steps + 1):
        cond, gates = batches(args.batch)
        b = cond.shape[0]
        noise = torch.randn(b, 1, *cond.shape[-2:], device=dev)
        ts = [torch.full((b,), v, device=dev) for v in (0.0, 0.5, 1.0)]
        with torch.no_grad():
            vt1 = teacher(noise, ts[0], cond, ts[1], gates=gates).float()
            xt_h = noise + h * vt1
            vt2 = teacher(xt_h, ts[1], cond, ts[2], gates=gates).float()
            xt_1 = xt_h + h * vt2
            w = torch.cosh(xt_1.clamp(-7, 7)).pow(2)          # metres per unit x, squared
            w = w / w.mean(dim=(1, 2, 3), keepdim=True)        # per sample: each window's RMS counts alike (the metric
                                                                # is the median over windows; one batch-wide mean let a
                                                                # few high-relief pixels dominate every step)
        force_weight_norm_(student)
        e1 = student.embed(ts[0], ts[1]).float()
        e2 = student.embed(ts[1], ts[2]).float()
        vs1 = sim.velocity(Q.r16(noise), cond, e1, gates)
        xs_h = noise + h * vs1
        vs2 = sim.velocity(Q.r16(xs_h, True), cond, e2, gates)          # student's own trajectory
        xs_1 = xs_h + h * vs2
        vs2t = sim.velocity(Q.r16(xt_h), cond, e2, gates)                # teacher's trajectory
        loss = ((w * (xs_1 - xt_1).pow(2)).mean() + (w * (xs_h - xt_h).pow(2)).mean()
                + 0.5 * h * h * (w * (vs2t - vt2).pow(2)).mean())
        for g in opt.param_groups:
            g["lr"] = args.lr * min(1.0, step / args.warmup) * 0.5 * (1 + math.cos(math.pi * step / args.steps))
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(params, 1.0)
        opt.step()
        with torch.no_grad():
            for pe, ps in zip(ema.parameters(), student.parameters()):
                pe.lerp_(ps, 1 - args.ema)
        if step % 50 == 0:
            print(f"step {step:5d} loss {loss.item():.6f} ({(time.time() - t_start) / step * 1000:.0f} ms/step)", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            r = evaluate(step)
            for name, m in (("student", student), ("ema", ema)):
                force_weight_norm_(m)
                if r[name]["median"] < best["median"]:
                    best = dict(r[name], step=step, which=name)
                    torch.save({"model": m.state_dict(), "config": ck["config"], "norms": ck.get("norms"),
                                "step": step, "quant": quant_meta, "eval": r[name], "teacher": str(args.teacher)},
                               args.out / "student.pt")
            torch.save({"model": student.state_dict(), "config": ck["config"], "norms": ck.get("norms"), "step": step,
                        "quant": quant_meta}, args.out / "last.pt")
            print("  best", json.dumps(best), flush=True)
    print(f"done in {(time.time() - t_start) / 60:.1f} min; best {best}", flush=True)


# ----------------------------------------------------------------------------- legacy (v1, kept for v4 models)
def legacy_main(args) -> None:
    from terrain_slm.models import detail as DT_
    from terrain_slm.quant.fp8 import fp8_convs
    from terrain_slm.train.train_detail import DetailData
    from terrain_slm.train.train_detail2 import sample_t
    skip = (1 + DT_.N_COND, 1 + DT_.N_COND + 1)

    @torch.no_grad()
    def fp8_error(student, teacher, data, steps, batches=4):
        student.eval()
        rms, worst = [], 0.0
        for k, (cond, x1, base, dem) in enumerate(data.val_batches(32)):
            if k >= batches:
                break
            g = torch.Generator(device=x1.device).manual_seed(k)
            noise = torch.randn(x1.shape, generator=g, device=x1.device)
            ref = DT_.decode(sample(teacher, cond, noise, steps))
            with fp8_convs(act="fixed", skip_in_channels=skip):
                q = DT_.decode(sample(student, cond, noise, steps))
            d = (q - ref).flatten(1)
            rms += d.pow(2).mean(1).sqrt().tolist()
            worst = max(worst, float(d.abs().max()))
        student.train()
        return {"fp8_rms_m_median": round(float(np.median(rms)), 2), "fp8_rms_m_p90": round(float(np.percentile(rms, 90)), 2),
                "fp8_worst_px_m": round(worst, 1)}

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
    experts = list(getattr(student, "adapters", {}).keys()) if hasattr(student, "adapters") else []
    opt = torch.optim.Adam(student.parameters(), lr=args.lr, betas=(0.9, 0.99))
    log = open(args.out / "log.jsonl", "a")
    ev = {"step": 0, **fp8_error(student, teacher, data, args.eval_steps)}
    print("  eval", json.dumps(ev), flush=True)
    log.write(json.dumps(ev) + "\n"); log.flush()
    t0 = time.time()
    for step in range(1, args.steps + 1):
        cond, x1, *_ = data.train_batch(args.batch)
        noise = torch.randn_like(x1)
        b = x1.shape[0]
        if args.mean_flow:
            gates = None
            if experts and float(torch.rand(1)) < 0.3:
                gates = torch.zeros(b, len(D2.STYLES), *x1.shape[-2:], device=dev)
                gates[:, D2.STYLES.index(experts[int(torch.randint(0, len(experts), (1,)))])] = 1.0
            h = 1.0 / args.mf_steps
            if float(torch.rand(1)) < 0.7:
                k = int(torch.randint(0, args.mf_steps, (1,)))
                x = noise
                with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
                    for i in range(k):
                        ti = torch.full((b,), i * h, device=dev)
                        x = x + h * teacher(x, ti, cond, ti + h, gates=gates).float()
                t = torch.full((b,), k * h, device=dev)
                r = t + h
                xt = x
            else:
                t = torch.rand(b, device=dev) * 0.95
                r = t + (1 - t) * torch.rand(b, device=dev)
                xt = (1 - t)[:, None, None, None] * noise + t[:, None, None, None] * x1
            with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
                v_t = teacher(xt, t, cond, r, gates=gates).float()
            if mp:
                force_weight_norm_(student)
            with fp8_convs(act="fixed", skip_in_channels=skip), torch.autocast("cuda", dtype=torch.bfloat16):
                v_s = student(xt, t, cond, r, gates=gates).float()
            loss = (v_s - v_t).pow(2).mean()
        else:
            t = sample_t(b, dev)
            xt = (1 - t)[:, None, None, None] * noise + t[:, None, None, None] * x1
            with torch.no_grad(), torch.autocast("cuda", dtype=torch.bfloat16):
                v_t = teacher(xt, t, cond).float()
            if mp:
                force_weight_norm_(student)
            with fp8_convs(act="fixed", skip_in_channels=skip), torch.autocast("cuda", dtype=torch.bfloat16):
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
                                  "full_precision_in_channels": list(skip)}, "eval": ev}, args.out / "student.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
