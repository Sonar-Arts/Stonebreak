"""Train the relief sampler: flow matching on real valley-scale relief (per cell).

Reuses the planner's region data, split and augmentation (PlannerData crops), so the
held-out tiles are the same ones every other model is validated on.

MeanFlow student (2026-11-19, the detail recipe of train_detail2 applied to the relief node):

    python -m terrain_slm.train.train_relief --data <regions> --objective meanflow \
        --init checkpoints/exp2/relief_v2c/best.pt --out checkpoints/exp2/relief_mf --steps 20000 --lr 1e-4

`--init` an FM teacher: same UNet + a zero-initialised h = t - r embedding (models/relief.py), so step 0 is the
teacher. Loss = MeanFlow identity U = v + (r - t) dU/dt (one JVP along (v, 1)), t from train_detail2.sample_t, r
uniform in [t, 1] for a --p-mean share (the rest r = t, plain FM), adaptive weight 1 / (err + 1e-3). Every eval saves
a snapshot (step_<n>.pt); best.pt = the 2-step sample amplitude closest to the teacher's 16-step amplitude.
`--distill-weight w` adds w x teacher-ODE distillation at the sampler's own pairs (0, 1), (0, .5), (.5, 1)
(distill_loss: targets from the frozen teacher's 16-step path from the same noise), which pins 1-2 step samples to the
teacher's numbers instead of only to its statistics.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import time
from pathlib import Path

import torch

from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P
from terrain_slm.models import refiner as R
from terrain_slm.models import relief as RL
from terrain_slm.train.planner_data import PlannerData, built_regions
from terrain_slm.train.train_refiner import fm_loss
from terrain_slm.train.train_detail2 import MF_ADAPTIVE_C, MF_ADAPTIVE_P, sample_t
from terrain_slm.device import default_device

MARGIN_CELLS = 40  # most of 3 x the largest smooth sigma; the rest is reflect-padded identically
                   # for target and condition, so it is an edge artefact, never a mismatch
VAL_STRIDE = 32


class ReliefData:
    def __init__(self, roots, device: str, crop_cells: int = 144, seed: int = 0):
        roots = [Path(roots)] if isinstance(roots, (str, Path)) else [Path(r) for r in roots]
        self.cells = PlannerData(roots, device, crop=crop_cells + 2 * MARGIN_CELLS, seed=seed)
        self.gen = self.cells.gen
        self.device = device
        self.crop_cells = crop_cells
        # Denser held-out crops than the planner's (one per tile would leave the narrow
        # high-latitude tiles with none).
        n = crop_cells + 2 * MARGIN_CELLS
        self.val_corners = [(reg.index, r + dr, c + dc) for reg in self.cells.regions
                            for r, c, h, w in reg.val_boxes
                            for dr in range(0, h - n + 1, VAL_STRIDE) for dc in range(0, w - n + 1, VAL_STRIDE)]

    def _u(self, lo, hi):
        return float(torch.empty(1).uniform_(lo, hi, generator=self.gen))

    def _assemble(self, corners, train: bool):
        f = self.cells._crop(corners, augment=train)
        b = f.shape[0]
        m, s = MARGIN_CELLS, self.crop_cells
        crop = lambda x: x[..., m : m + s, m : m + s]
        coarse = f[:, 0:1]
        fine = D.blur(coarse, RL.RELIEF_FINE_SIGMA)
        smooth = torch.empty_like(coarse)
        for i in range(b):
            sig = self._u(*RL.RELIEF_SMOOTH_SIGMA) if train else sum(RL.RELIEF_SMOOTH_SIGMA) / 2
            smooth[i : i + 1] = D.blur(coarse[i : i + 1], sig)
        x1 = crop(fine - smooth) / RL.RELIEF_SCALE_M
        cond_smooth = smooth
        if train:  # a painted trend is also a little wrong: low-frequency jitter
            jit = D.blur(torch.randn(smooth.shape, generator=self.gen).to(smooth.device), 12.0)
            jit = jit / jit.flatten(1).std(dim=1).clamp_min(1e-6).view(-1, 1, 1, 1)
            amp = torch.empty(b, 1, 1, 1).uniform_(0, 60, generator=self.gen).to(smooth.device)
            cond_smooth = smooth + jit * amp
        wild = self.cells._wildness(f, train)
        climate = torch.cat([P.climate_input(n, f[:, 9 + k : 10 + k]) for k, n in enumerate(P.CLIMATE_NAMES)], dim=1)
        if train:
            has = lambda p: (torch.rand(b, 1, 1, 1, generator=self.gen) < p).float().to(f.device)
            hw, hc = has(0.85), has(0.85)
        else:
            hw = hc = torch.ones(b, 1, 1, 1, device=f.device)
        cond = RL.build_cond(crop(cond_smooth), crop(wild), crop(climate), hw, hc)
        return cond, x1

    def train_batch(self, batch: int):
        return self._assemble(self.cells.sample_corners(batch), train=True)

    def val_batches(self, batch: int):
        corners = self.val_corners
        for i in range(0, len(corners), batch):
            yield self._assemble(corners[i : i + batch], train=False)


def meanflow_loss(model, cond, x1, p_mean: float = 0.5):
    """MeanFlow (Geng et al. 2025) in this repo's convention (t = 0 noise -> t = 1 data, r >= t), as
    train_detail2.meanflow_loss: U(x_t, t, r) = v + (r - t) dU/dt, dU/dt = J_x U . v + dU/dt|explicit (forward-mode JVP)."""
    b = x1.shape[0]
    t = sample_t(b, x1.device)
    r = t + (1 - t) * torch.rand(b, device=x1.device)
    r = torch.where(torch.rand(b, device=x1.device) > p_mean, t, r)     # this share trains plain FM (r = t)
    noise = torch.randn_like(x1)
    tt = t[:, None, None, None]
    xt = (1 - tt) * noise + tt * x1
    v = x1 - noise
    fn = lambda x, tv: model(x, tv, cond, r)
    with torch.autocast("cuda", dtype=torch.bfloat16):
        u_pred, dudt = torch.func.jvp(fn, (xt, t), (v, torch.ones_like(t)))
    target = (v + (r - t)[:, None, None, None] * dudt.float()).detach()
    err = (u_pred.float() - target).pow(2).mean(dim=(1, 2, 3))
    w = 1.0 / (err.detach() + MF_ADAPTIVE_C) ** MF_ADAPTIVE_P          # heavy-tailed JVP targets: bound each pull
    return (w * err).mean(), err.mean()


@torch.no_grad()
def teacher_path(teacher, cond, noise, steps: int = 16):
    """The FM teacher's own Euler path (relief.sample, bf16 autocast) from `noise`: {t: x_t} at t = 0.5 and 1."""
    x, out = noise, {}
    ts = torch.linspace(0.0, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((cond.shape[0],), float(ts[i]), device=cond.device)
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=cond.is_cuda):
            x = x + (ts[i + 1] - ts[i]) * teacher(x, t, cond).float()
        if 2 * (i + 1) == steps:
            out[0.5] = x
    out[1.0] = x
    return out


def distill_loss(model, teacher, cond, steps: int = 16):
    """Teacher-ODE distillation at the sampler's own (t, r) pairs: U(noise, 0, 1), U(noise, 0, 0.5), U(x_.5, 0.5, 1)
    against the teacher's 16-step path's average velocities, so 1- and 2-step samples land where the teacher's would
    (same noise -> same sample). Adaptive weight as meanflow_loss, so the two terms pull with equal strength."""
    noise = torch.randn(cond.shape[0], 1, *cond.shape[-2:], device=cond.device)
    path = teacher_path(teacher, cond, noise, steps)
    b = cond.shape[0]
    full = lambda v: torch.full((b,), v, device=cond.device)
    pairs = ((noise, 0.0, 1.0, path[1.0] - noise), (noise, 0.0, 0.5, 2 * (path[0.5] - noise)),
             (path[0.5], 0.5, 1.0, 2 * (path[1.0] - path[0.5])))
    tot, raw = 0.0, 0.0
    for x, t, r, target in pairs:
        with torch.autocast("cuda", dtype=torch.bfloat16):
            u = model(x, full(t), cond, full(r)).float()
        err = (u - target).pow(2).mean(dim=(1, 2, 3))
        tot = tot + (err / (err.detach() + MF_ADAPTIVE_C) ** MF_ADAPTIVE_P).mean() / len(pairs)
        raw = raw + err.mean().detach() / len(pairs)
    return tot, raw


@torch.no_grad()
def evaluate_mf(model, data, batch, steps=(1, 2)):
    """MeanFlow student: instantaneous-velocity FM loss (r = t) and the sample amplitude ratio at 1 and 2 steps."""
    model.eval()
    g = torch.Generator(device=data.device).manual_seed(123)
    tot, n, r_std = 0.0, 0, 0.0
    s_std = {k: 0.0 for k in steps}
    amp_norm = getattr(model.cfg, "amp_norm", False)
    for cond, x1 in data.val_batches(batch):
        amp = RL.relief_amp(cond) if amp_norm else 1.0
        tot += fm_loss(model, cond, x1 / amp, g).item() * x1.shape[0]
        n += x1.shape[0]
        noise = torch.randn(x1.shape, device=x1.device, generator=g)
        for k in steps:
            s_std[k] += (RL.sample(model, cond, noise, steps=k) * amp).std().item() * x1.shape[0]
        r_std += x1.std().item() * x1.shape[0]
    model.train()
    return {"val_fm": tot / n, **{f"amp_ratio_{k}": s_std[k] / r_std for k in steps}}


@torch.no_grad()
def evaluate(model, data, batch, steps=16):
    """Flow-matching loss, plus the relief-amplitude ratio std(sample) / std(real): the
    averaging symptom this model exists to remove shows up as a ratio well below 1."""
    model.eval()
    g = torch.Generator(device=data.device).manual_seed(123)
    tot, n, s_std, r_std = 0.0, 0, 0.0, 0.0
    amp_norm = getattr(model.cfg, "amp_norm", False)
    for cond, x1 in data.val_batches(batch):
        amp = RL.relief_amp(cond) if amp_norm else 1.0
        tot += fm_loss(model, cond, x1 / amp, g).item() * x1.shape[0]
        n += x1.shape[0]
        noise = torch.randn(x1.shape, device=x1.device, generator=g)
        x = R.sample(model, cond, noise, steps=steps) * amp
        s_std += x.std().item() * x1.shape[0]
        r_std += x1.std().item() * x1.shape[0]
    model.train()
    return {"val_fm": tot / n, "amp_ratio": s_std / r_std}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, nargs="+", default=None)
    ap.add_argument("--out", type=Path, default=Path("checkpoints/relief_r0"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--steps", type=int, default=20000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--channels", type=int, nargs="+", default=None, help="UNet widths (default ReliefConfig)")
    ap.add_argument("--amp-norm", action="store_true", help="amplitude-normalised target (relief.AMP_SLOPE)")
    ap.add_argument("--init", type=Path, default=None, help="continue from a checkpoint's (EMA) weights, fresh optimiser")
    ap.add_argument("--objective", choices=("fm", "meanflow"), default="fm",
                    help="meanflow: distil --init (an FM teacher) into a 1-2 step MeanFlow student")
    ap.add_argument("--p-mean", type=float, default=0.5, help="meanflow: share of samples with r > t")
    ap.add_argument("--ema", type=float, default=0.999, help="EMA decay cap")
    ap.add_argument("--distill-weight", type=float, default=0.0,
                    help="meanflow: + this x teacher-ODE distillation at the sampler's (t, r) pairs (distill_loss)")
    ap.add_argument("--done-marker", type=Path, default=None, help="touch this file when the job ends (any outcome)")
    args = ap.parse_args()
    try:
        _train(args)
    finally:
        if args.done_marker is not None:
            args.done_marker.write_text(time.strftime("%F %T") + "\n")


def _train(args):
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)

    data = ReliefData(args.data or built_regions(), args.device)
    cfg = RL.ReliefConfig(channels=tuple(args.channels)) if args.channels else RL.ReliefConfig()
    cfg.amp_norm = args.amp_norm
    if args.init:
        ick = torch.load(args.init, map_location=args.device, weights_only=False)
        ic = dict(ick["config"]); ic["channels"] = tuple(ic["channels"])
        cfg = RL.ReliefConfig(**ic)
    mf = args.objective == "meanflow"
    if mf:
        cfg.mean_flow = True
    model = RL.Relief(cfg).to(args.device)
    if args.init:
        got = model.load_state_dict(ick["model"], strict=not mf)
        print(f"init from {args.init} (step {ick.get('step')}); new: {list(got.missing_keys) if mf else []}", flush=True)
    ema = copy.deepcopy(model).eval()
    teacher = None
    if mf and args.distill_weight > 0:   # the FM teacher itself, frozen (its config, without temb_h)
        tc = dict(ick["config"]); tc["channels"] = tuple(tc["channels"])
        teacher = RL.Relief(RL.ReliefConfig(**tc)).to(args.device).eval().requires_grad_(False)
        teacher.load_state_dict(ick["model"])
    print(f"relief {sum(p.numel() for p in model.parameters()):,} params, "
          f"{data.cells.train_count:,} train corners, {len(data.val_corners)} val crops", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.0, betas=(0.9, 0.99))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / args.steps))))
    log = open(args.out / "log.jsonl", "a")
    best, t0 = float("inf"), time.time()
    if mf:   # the target amplitude: the teacher's own 16-step samples on the same val crops
        teacher_amp = evaluate(model, data, args.batch)["amp_ratio"]
        print(f"teacher (= step 0) 16-step amp_ratio {teacher_amp:.4f}", flush=True)
        log.write(json.dumps({"step": 0, "teacher_amp_ratio_16": teacher_amp}) + "\n"); log.flush()
    for step in range(1, args.steps + 1):
        cond, x1 = data.train_batch(args.batch)
        if cfg.amp_norm:
            x1 = x1 / RL.relief_amp(cond)
        if mf:
            loss, raw = meanflow_loss(model, cond, x1, args.p_mean)
            if teacher is not None:
                dl, draw = distill_loss(model, teacher, cond)
                loss = loss + args.distill_weight * dl
        else:
            loss = raw = fm_loss(model, cond, x1)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        with torch.no_grad():
            d = min(args.ema, (1 + step) / (10 + step))
            for pe, pm in zip(ema.parameters(), model.parameters()):
                pe.lerp_(pm, 1 - d)
        if step % 100 == 0:
            dstr = f"  distill {draw.item():.4f}" if teacher is not None else ""
            print(f"step {step:6d}  loss {loss.item():.4f}  err {raw.item():.4f}{dstr}  {(time.time() - t0) / step * 1000:.1f} ms/step", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, **(evaluate_mf(ema, data, args.batch) if mf else evaluate(ema, data, args.batch))}
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", ev, flush=True)
            ckpt = {"model": ema.state_dict(), "config": cfg.to_dict(), "step": step, "eval": ev,
                    "objective": args.objective}
            torch.save(ckpt, args.out / "last.pt")
            score = abs(math.log(ev["amp_ratio_2"] / teacher_amp)) if mf else ev["val_fm"]
            if mf:
                torch.save(ckpt, args.out / f"step_{step:06d}.pt")
            if score < best:
                best = score
                torch.save(ckpt, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best val {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
