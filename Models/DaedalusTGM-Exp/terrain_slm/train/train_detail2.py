"""Train the v2 detail backbone (models/detail2.py): DaedalusTGM-Exp-2 plan §3, §4, §7.

    python -m terrain_slm.train.train_detail2 --size small --steps 20000 --out checkpoints/d2_small   (proxy)
    python -m terrain_slm.train.train_detail2 --size full --objective meanflow --init checkpoints/d2_full/ema_0.10.pt

Same data as v4's trainer (train_detail.DetailData: real 60 m DEM crops, coarse degraded like planner output,
dihedral augmentation, presence-flag dropout). What changed, and why:

  objective   fm: flow matching, x_t = (1 - t) noise + t data, target v = data - noise (the teacher);
              meanflow: average velocity U(x_t, t, r) over [t, r] with the MeanFlow identity
              U = v + (r - t) dU/dt (dU/dt by one JVP along (v, 1)), so the student jumps noise -> data in 1-2 steps.
  weighting   EDM2's learned per-time uncertainty: loss / e^u(t) + u(t) (no hand-tuned t weights).
  t coverage  logit-normal + 10% uniform + 10% in [0, 0.1] from the start: v4's 80k checkpoint failed in game in the
              barely-trained t ~ 0 regime.
  EMAs        several power-function EMAs (EDM2) at relative widths --ema-std, saved side by side at every eval;
              the EMA is chosen after training on in-game metrics (v4 had to pick 66k over 80k by hand).
  optimizer   Adam, no weight decay (forced weight normalisation bounds the weights), inverse-sqrt LR decay.
  progressive the first --prog-frac of steps train on 128-sample crops (a quarter of the pixels per step).
  stability   eval also samples from UNBLURRED coarse bases (the in-game trigger of v4's blow-ups) and counts
              non-finite / implausible windows (|x| >= 7, ~5.5 km).
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
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import detail as DT
from terrain_slm.models import detail2 as D2
from terrain_slm.models.mp import force_weight_norm_
from terrain_slm.train.train_detail import DetailData

SIZES = {   # channel ladders for proxy-scale sweeps (plan §8); "full" = v4's widths
    "tiny": (32, 64, 96, 128, 160),
    "small": (48, 96, 144, 192, 256),
    "medium": (64, 128, 192, 288, 384),
    "full": (96, 192, 288, 384, 512),
    "large": (128, 256, 384, 512, 640),
    "xl": (160, 320, 480, 640, 768),
}


# ----------------------------------------------------------------------------- power-function EMA (EDM2)
def std_to_gamma(std: float) -> float:
    """EDM2: the power-function EMA exponent whose profile has relative standard deviation `std`."""
    tmp = std ** -2
    return float(np.roots([1, 7, 16 - tmp, 12 - tmp]).real.max())


class PowerEMA:
    def __init__(self, model: torch.nn.Module, stds: list[float]):
        self.stds = stds
        self.gammas = [std_to_gamma(s) for s in stds]
        self.emas = [copy.deepcopy(model).eval().requires_grad_(False) for _ in stds]

    @torch.no_grad()
    def update(self, model: torch.nn.Module, step: int) -> None:
        src = [p.detach() for p in model.parameters()]
        for gamma, ema in zip(self.gammas, self.emas):
            beta = (1 - 1 / max(step, 1)) ** (gamma + 1)
            torch._foreach_lerp_(list(ema.parameters()), src, 1 - beta)

    def state(self):
        return {f"{s:.3f}": e.state_dict() for s, e in zip(self.stds, self.emas)}

    def load(self, st):
        for s, e in zip(self.stds, self.emas):
            if f"{s:.3f}" in st:
                e.load_state_dict(st[f"{s:.3f}"])


# ----------------------------------------------------------------------------- objectives
def sample_t(n: int, device, gen=None) -> torch.Tensor:
    """Logit-normal(0, 1) with 10% uniform and 10% early ([0, 0.1]) coverage."""
    u = torch.rand(n, device=device, generator=gen)
    t = torch.sigmoid(torch.randn(n, device=device, generator=gen))
    t = torch.where(u < 0.1, torch.rand(n, device=device, generator=gen), t)
    t = torch.where((u >= 0.1) & (u < 0.2), 0.1 * torch.rand(n, device=device, generator=gen), t)
    return t


def fm_loss(model, cond, x1, gates=None):
    b = x1.shape[0]
    t = sample_t(b, x1.device)
    noise = torch.randn_like(x1)
    xt = (1 - t)[:, None, None, None] * noise + t[:, None, None, None] * x1
    with torch.autocast("cuda", dtype=torch.bfloat16):
        v = model(xt, t, cond, gates=gates).float()
    err = (v - (x1 - noise)).pow(2).mean(dim=(1, 2, 3))
    u = model_u(model, t)
    return (err / u.exp() + u).mean(), err.mean()


MF_ADAPTIVE_C, MF_ADAPTIVE_P = 1e-3, 1.0


def meanflow_loss(model, cond, x1, p_mean: float = 0.5, gates=None):
    """MeanFlow (Geng et al. 2025) in this file's convention (t = 0 noise -> t = 1 data, r >= t):
    U(x_t, t, r) = v + (r - t) dU/dt, dU/dt = J_x U . v + dU/dt|explicit (one forward-mode JVP)."""
    b = x1.shape[0]
    t = sample_t(b, x1.device)
    r = t + (1 - t) * torch.rand(b, device=x1.device)
    same = torch.rand(b, device=x1.device) > p_mean          # this share trains plain FM (r = t)
    r = torch.where(same, t, r)
    noise = torch.randn_like(x1)
    tt = t[:, None, None, None]
    xt = (1 - tt) * noise + tt * x1
    v = x1 - noise
    fn = lambda x, tv: model(x, tv, cond, r, gates=gates)
    with torch.autocast("cuda", dtype=torch.bfloat16):
        u_pred, dudt = torch.func.jvp(fn, (xt, t), (v, torch.ones_like(t)))
    target = (v + (r - t)[:, None, None, None] * dudt.float()).detach()
    err = (u_pred.float() - target).pow(2).mean(dim=(1, 2, 3))
    # MeanFlow's adaptive loss weighting (w = 1 / (err + c)^p, p = 1, detached): the JVP targets are heavy-tailed
    # (the first small run spiked to 15-16x its typical loss every ~1000 steps); this bounds each sample's pull.
    w = 1.0 / (err.detach() + MF_ADAPTIVE_C) ** MF_ADAPTIVE_P
    return (w * err).mean(), err.mean()


def model_u(model, t):
    m = model._orig_mod if hasattr(model, "_orig_mod") else model
    return m.uncertainty(t).float()


# ----------------------------------------------------------------------------- eval
@torch.no_grad()
def evaluate(model, data: DetailData, batch: int, steps: int, mean_flow: bool) -> dict:
    """Val flow loss at fixed t, sampled amplitude vs real, and stability from unblurred bases (v4's in-game
    failure mode): non-finite and implausible (|x| >= 7) windows."""
    model.eval()
    fm, ratios, bad, maxabs, n = [], [], 0, 0.0, 0
    for cond, x1, base, dem in data.val_batches(batch):
        g = torch.Generator(device=x1.device).manual_seed(n)
        noise = torch.randn(x1.shape, generator=g, device=x1.device)
        for tv in (0.05, 0.3, 0.6, 0.9):
            t = torch.full((x1.shape[0],), tv, device=x1.device)
            xt = (1 - tv) * noise + tv * x1
            with torch.autocast("cuda", dtype=torch.bfloat16):
                v = model(xt, t, cond).float()
            fm.append(float((v - (x1 - noise)).pow(2).mean()))
        x = D2.sample(model, cond, noise, steps=steps, mean_flow=mean_flow)
        fin = torch.isfinite(x).flatten(1).all(1)
        mx = x.abs().flatten(1).amax(1)
        bad += int((~fin | (mx >= 7)).sum())
        maxabs = max(maxabs, float(mx[fin].max()) if fin.any() else float("inf"))
        rs = DT.decode(x).flatten(1).std(1)
        rr = (dem - base).flatten(1).std(1)
        ratios += (rs / rr.clamp_min(1e-3)).tolist()
        n += x1.shape[0]
    model.train()
    return {"val_fm": float(np.mean(fm)), "amp_ratio_median": float(np.median(ratios)),
            "unstable": bad, "windows": n, "max_abs_x": round(maxabs, 3)}


@torch.no_grad()
def unblurred_stability(model, data: DetailData, batch: int, steps: int, mean_flow: bool, batches: int = 4) -> dict:
    """v4's in-game trigger: the coarse base built from UNBLURRED cells (training always blurs 0.5-2 cells)."""
    model.eval()
    bad = n = 0
    for k, (cond, x1, base, dem) in enumerate(data.val_batches(batch, val_blur=0.0)):
        if k >= batches:
            break
        g = torch.Generator(device=x1.device).manual_seed(1000 + k)
        noise = torch.randn(x1.shape, generator=g, device=x1.device)
        x = D2.sample(model, cond, noise, steps=steps, mean_flow=mean_flow)
        mx = x.abs().flatten(1).amax(1)
        bad += int((~torch.isfinite(x).flatten(1).all(1) | (mx >= 7)).sum())
        n += x1.shape[0]
    model.train()
    return {"unblurred_unstable": bad, "unblurred_windows": n}


# ----------------------------------------------------------------------------- experts (plan §3a)
def smooth_field(b: int, h: int, w: int, device, gen=None) -> torch.Tensor:
    """(b, 1, h, w) smooth random weights in [0, 1] (a low-frequency field): partial expert gates for blend training."""
    lo = torch.rand(b, 1, max(2, h // 64), max(2, w // 64), device=device, generator=gen)
    return F.interpolate(lo, size=(h, w), mode="bicubic", align_corners=False).clamp(0, 1)


class ExpertBatches:
    """Crops from the style regions only, with their style's gate: 1, or (p_partial) a smooth field in [0, 1]."""

    def __init__(self, data: DetailData, styles: list[str], p_partial: float = 0.5):
        self.data, self.p_partial = data, p_partial
        names = [r.name for r in data.regions]
        self.by_style = {s: [i for i, n in enumerate(names) if n in D2.STYLE_REGIONS[s]] for s in styles}
        self.by_style = {s: ix for s, ix in self.by_style.items() if ix}
        missing = sorted(set(styles) - set(self.by_style))
        if missing:
            print(f"  no regions for styles {missing} in {data.data_dir if hasattr(data, 'data_dir') else 'data'}", flush=True)

    def batch(self, b: int):
        d = self.data
        styles = list(self.by_style)
        corners, style_of = [], []
        for _ in range(b):
            s = styles[int(torch.randint(0, len(styles), (1,), generator=d.gen))]
            ri = self.by_style[s][int(torch.randint(0, len(self.by_style[s]), (1,), generator=d.gen))]
            reg = d.regions[ri].cells
            k = int(torch.randint(0, len(reg.train_corners), (1,), generator=d.gen))
            r, c = reg.train_corners[k].tolist()
            corners.append((reg.index, r, c))
            style_of.append(D2.STYLES.index(s))
        cond, x1, _, dem = d.assemble(corners, train=True)
        h, w = x1.shape[-2:]
        gates = torch.zeros(b, len(D2.STYLES), h, w, device=x1.device)
        partial = smooth_field(b, h, w, x1.device)
        for i, si in enumerate(style_of):
            gates[i, si] = partial[i, 0] if float(torch.rand(1)) < self.p_partial else 1.0
            if D2.STYLES[si] in D2.SEA_GATED_STYLES:       # only where the real ground is under water, softly
                z = dem[i : i + 1]                        # coastal water only: CUDEM reaches ~-2 km, deeper is GEBCO
                sea = ((z < 0) & (z > D2.SEA_GATE_MIN_M)).float()
                sea = F.interpolate(sea, size=(h, w), mode="area") if sea.shape[-1] != w else sea
                gates[i, si] = gates[i, si] * D.blur(sea, 1.0)[0, 0]
        return cond, x1, gates


@torch.no_grad()
def expert_eval(model, data: DetailData, eb: "ExpertBatches", batch: int) -> dict:
    """Per style: val flow loss (fixed t) on its regions' held-out crops with the expert's gate on vs off."""
    model.eval()
    out = {}
    for style, regs in eb.by_style.items():
        corners = [c for c in data.val_corners if c[0] in {data.regions[i].cells.index for i in regs}][: 4 * batch]
        if not corners:
            continue
        on = off = 0.0
        n = 0
        for i in range(0, len(corners), batch):
            cond, x1, *_ = data.assemble(corners[i : i + batch], train=False)
            g = torch.Generator(device=x1.device).manual_seed(i)
            noise = torch.randn(x1.shape, generator=g, device=x1.device)
            gates = torch.zeros(x1.shape[0], len(D2.STYLES), *x1.shape[-2:], device=x1.device)
            gates[:, D2.STYLES.index(style)] = 1.0
            for tv in (0.1, 0.4, 0.7):
                t = torch.full((x1.shape[0],), tv, device=x1.device)
                xt = (1 - tv) * noise + tv * x1
                with torch.autocast("cuda", dtype=torch.bfloat16):
                    on += float((model(xt, t, cond, gates=gates).float() - (x1 - noise)).pow(2).mean())
                    off += float((model(xt, t, cond).float() - (x1 - noise)).pow(2).mean())
                n += 1
        out[style] = {"fm_gate_on": round(on / n, 4), "fm_gate_off": round(off / n, 4)}
    model.train()
    return out


# ----------------------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data"))
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--size", choices=sorted(SIZES), default="full")
    ap.add_argument("--blocks", type=int, default=2)
    ap.add_argument("--mid-blocks", type=int, default=4)
    ap.add_argument("--dropout", type=float, default=0.1)
    ap.add_argument("--objective", choices=("fm", "meanflow"), default="fm")
    ap.add_argument("--steps", type=int, default=60000)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=4e-3, help="reference LR (MP nets take EDM2-sized LRs)")
    ap.add_argument("--lr-ref-steps", type=int, default=20000, help="inverse-sqrt decay starts here")
    ap.add_argument("--warmup", type=int, default=1000)
    ap.add_argument("--ema-std", type=float, nargs="+", default=[0.05, 0.10, 0.15])
    ap.add_argument("--prog-frac", type=float, default=0.3, help="share of steps on 128-sample crops")
    ap.add_argument("--eval-every", type=int, default=2000)
    ap.add_argument("--eval-steps", type=int, default=16, help="sampler steps in eval (1-2 for meanflow)")
    ap.add_argument("--compile", action="store_true")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--init", type=Path, default=None, help="start from these weights (e.g. the FM teacher's EMA)")
    ap.add_argument("--probe", type=int, default=0)
    ap.add_argument("--experts", default="", help="comma-separated styles (or 'all'): train style experts on a frozen "
                    "backbone (--init it), on the styles' regions only")
    args = ap.parse_args()
    torch.manual_seed(0)
    torch.backends.cudnn.benchmark = True
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    args.out.mkdir(parents=True, exist_ok=True)
    dev = args.device

    data = DetailData(args.data_dir, dev)
    cfg = D2.Detail2Config(channels=SIZES[args.size], blocks=args.blocks, mid_blocks=args.mid_blocks,
                           dropout=args.dropout)
    model = D2.Detail2(cfg).to(dev).to(memory_format=torch.channels_last)
    if args.init is not None:
        model.load_state_dict(torch.load(args.init, map_location=dev, weights_only=False)["model"], strict=False)
        print(f"init from {args.init}", flush=True)
    experts = [s_ for s_ in (D2.STYLES if args.experts == "all" else args.experts.split(",")) if s_]
    expert_batches = None
    if experts:
        for p_ in model.parameters():
            p_.requires_grad_(False)
        for s_ in experts:
            model.add_expert(s_)
        if args.init is not None:   # the init may also hold expert weights (e.g. MeanFlow backbone + teacher-trained
            # experts for the matching pass): the adapters only exist now, so load again for their tensors
            sd = torch.load(args.init, map_location=dev, weights_only=False)["model"]
            got = model.load_state_dict({k: v for k, v in sd.items() if k.startswith("adapters.")}, strict=False)
            n_ad = sum(1 for k in sd if k.startswith("adapters."))
            print(f"init experts: {n_ad} adapter tensors from {args.init}" if n_ad else "experts start at zero gain", flush=True)
        expert_batches = ExpertBatches(data, experts)
        print(f"experts {experts}: backbone frozen, {sum(p_.numel() for p_ in model.adapters.parameters()):,} "
              f"trainable params; regions {expert_batches.by_style}", flush=True)
    force_weight_norm_(model)
    emas = PowerEMA(model, args.ema_std)
    opt = torch.optim.Adam([p_ for p_ in model.parameters() if p_.requires_grad], lr=args.lr, betas=(0.9, 0.99),
                           fused=True)
    lr_at = lambda s: args.lr * min(1.0, (s + 1) / args.warmup) / math.sqrt(max(s / args.lr_ref_steps, 1.0))
    start = 1
    if args.resume and (args.out / "state.pt").exists():
        st = torch.load(args.out / "state.pt", map_location=dev, weights_only=False)
        model.load_state_dict(st["model"]); opt.load_state_dict(st["opt"]); emas.load(st["emas"])
        start = st["step"] + 1
        data.desc_mean = torch.tensor(st["norms"]["desc_mean"], device=dev)
        data.desc_std = torch.tensor(st["norms"]["desc_std"], device=dev)
        print(f"resumed at step {start}", flush=True)
    fwd = torch.compile(model) if args.compile else model
    loss_fn = fm_loss if args.objective == "fm" else meanflow_loss
    norms = {"desc_mean": data.desc_mean.tolist(), "desc_std": data.desc_std.tolist()}
    print(f"detail2/{args.size} {D2.count_params(model):,} params, {args.objective}, cfg {cfg.to_dict()}, "
          f"{len(data.regions)} regions, {len(data.val_corners)} val crops", flush=True)
    log = open(args.out / "log.jsonl", "a")
    prog_end = int(args.prog_frac * args.steps)
    t0, t_mark = time.time(), time.time()
    for step in range(start, args.steps + 1):
        if step % 500 == 0:
            data.refresh()
        gates = None
        if expert_batches is not None:
            cond, x1, gates = expert_batches.batch(args.batch)
        else:
            cond, x1, *_ = data.train_batch(args.batch)
        if step <= prog_end and gates is None:                       # progressive: a random 128-sample window of each crop
            o = int(torch.randint(0, x1.shape[-1] - 128 + 1, (1,)) // 16 * 16)
            cond, x1 = cond[..., o : o + 128, o : o + 128].contiguous(memory_format=torch.channels_last), \
                x1[..., o : o + 128, o : o + 128].contiguous()
        for g in opt.param_groups:
            g["lr"] = lr_at(step)
        force_weight_norm_(model)
        loss, raw = loss_fn(fwd, cond, x1, gates=gates)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        gn = torch.nn.utils.clip_grad_norm_(model.parameters(), 10.0)
        opt.step()
        emas.update(model, step)
        if step % 50 == 0:
            torch.cuda.synchronize(dev)
            dt = (time.time() - t_mark) / 50
            t_mark = time.time()
            print(f"step {step:6d}  loss {loss.item():.4f}  fm {raw.item():.4f}  gn {gn.item():.2f}  lr {lr_at(step):.2e}  "
                  f"{dt * 1000:.0f} ms/step  mem {torch.cuda.max_memory_allocated(dev) / 2**30:.1f} GB", flush=True)
        if args.probe and step >= start + args.probe - 1:
            print("probe done", flush=True)
            return
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, "min": round((time.time() - t0) / 60, 1)}
            for std, ema in zip(emas.stds, emas.emas):
                r = evaluate(ema, data, args.batch, args.eval_steps, args.objective == "meanflow")
                r.update(unblurred_stability(ema, data, args.batch, args.eval_steps, args.objective == "meanflow"))
                ev[f"ema_{std:.2f}"] = r
                torch.save({"model": ema.state_dict(), "config": {**cfg.to_dict(), "mean_flow": args.objective == "meanflow",
                                                                  "experts": list(ema.adapters.keys())},
                            "norms": norms, "step": step,
                            "ema_std": std, "objective": args.objective, "eval": r},
                           args.out / f"ema_{std:.2f}.pt")
            if expert_batches is not None:
                ev["experts"] = expert_eval(emas.emas[0], data, expert_batches, args.batch)
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", json.dumps(ev), flush=True)
            torch.save({"model": model.state_dict(), "opt": opt.state_dict(), "emas": emas.state(), "step": step,
                        "norms": norms, "config": cfg.to_dict()}, args.out / "state.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min", flush=True)


if __name__ == "__main__":
    main()
