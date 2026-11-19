"""Relief node: MeanFlow student vs its FM teacher on the held-out relief crops (2026-11-19, agent B).

    python -m terrain_slm.eval.relief_mf_compare --teacher checkpoints/exp2/relief_v2c/best.pt \\
        --student checkpoints/exp2/relief_mf/best.pt [--student-steps 1 2] [--device cuda:0] [--json out.json]

Same val crops as train_relief (ReliefData, smooth sigma 14 cells, every presence flag on), same noise for every
sampler. Per sampler (and the real target), in metres of relief (x * RELIEF_SCALE_M, gain 1):
  amp            std(sample) / std(real) over all crops (train_relief's amp_ratio)
  q01..q99       value percentiles (m)
  grad           mean |central-difference gradient| / real's (slope texture)
  band_<lo>-<hi> spectral power in that wavelength band (cells) / real's (does it draw valleys at the right scales)
  relief11_p50/p90  local relief (max - min, 11 cells ~ 2.6 km) percentiles (m)
  wild_slope     least-squares slope of per-crop log-std vs mean normalised wildness (how much it follows `wild`)
  rms_vs_teacher same-noise RMS(sample - teacher sample) / RMS(teacher sample) (how close to the teacher's ODE map)
Also the sampler time for one generator relief window (1 x 448 x 448, native kernels when available).
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.models import relief as RL
from terrain_slm.train.planner_data import built_regions
from terrain_slm.train.train_relief import ReliefData

BANDS = ((2, 4), (4, 8), (8, 16), (16, 32), (32, 72))


def load(path: Path, device: str) -> RL.Relief:
    k = torch.load(path, map_location=device, weights_only=False)
    c = dict(k["config"]); c["channels"] = tuple(c["channels"])
    m = RL.Relief(RL.ReliefConfig(**c)).to(device).eval()
    m.load_state_dict(k["model"])
    return m


def band_power(x: torch.Tensor) -> torch.Tensor:
    """(B,1,H,W) -> (len(BANDS),) summed spectral power per wavelength band (cells)."""
    h, w = x.shape[-2:]
    p = torch.fft.rfft2(x[:, 0] - x[:, 0].mean(dim=(-2, -1), keepdim=True)).abs().pow(2).sum(0)
    fy = torch.fft.fftfreq(h, device=x.device)[:, None]
    fx = torch.fft.rfftfreq(w, device=x.device)[None, :]
    f = (fx ** 2 + fy ** 2).sqrt()
    wl = 1.0 / f.clamp_min(1e-9)
    return torch.stack([p[(wl >= lo) & (wl < hi)].sum() for lo, hi in BANDS])


def local_relief(x: torch.Tensor, k: int = 11) -> torch.Tensor:
    return F.max_pool2d(x, k, 1, k // 2) - (-F.max_pool2d(-x, k, 1, k // 2))


class Acc:
    def __init__(self):
        self.sq = self.n = self.grad = 0.0
        self.vals, self.lr, self.crop_logstd = [], [], []
        self.bands = None

    def add(self, x: torch.Tensor):
        m = x * RL.RELIEF_SCALE_M
        self.sq += float(m.pow(2).sum()); self.n += m.numel()
        gx = (m[..., :, 2:] - m[..., :, :-2]).abs().mean() * 0.5
        gy = (m[..., 2:, :] - m[..., :-2, :]).abs().mean() * 0.5
        self.grad += float(gx + gy) * m.shape[0]
        flat = m.flatten()
        self.vals.append(flat[torch.randperm(flat.numel(), device=m.device)[:20000]].cpu())
        lr = local_relief(m).flatten()
        self.lr.append(lr[torch.randperm(lr.numel(), device=m.device)[:20000]].cpu())
        self.crop_logstd.append(m.flatten(1).std(1).clamp_min(1e-3).log().cpu())
        b = band_power(m).cpu()
        self.bands = b if self.bands is None else self.bands + b

    def summary(self, crops: int, wild: torch.Tensor, real: "Acc | None") -> dict:
        v = torch.cat(self.vals).numpy(); lr = torch.cat(self.lr).numpy(); ls = torch.cat(self.crop_logstd).numpy()
        out = {"std_m": math.sqrt(self.sq / self.n)}
        out.update({f"q{q:02d}_m": float(np.percentile(v, q)) for q in (1, 5, 50, 95, 99)})
        out["grad"] = self.grad / crops
        out["relief11_p50_m"], out["relief11_p90_m"] = float(np.percentile(lr, 50)), float(np.percentile(lr, 90))
        wv = wild.numpy()
        out["wild_slope"] = float(np.polyfit(wv, ls, 1)[0])
        if real is not None:
            out["amp"] = out["std_m"] / math.sqrt(real.sq / real.n)
            out["grad"] = out["grad"] / (real.grad / crops)
            for (lo, hi), a, b in zip(BANDS, self.bands.tolist(), real.bands.tolist()):
                out[f"band_{lo}-{hi}"] = a / b
        return out


def window_time(model: RL.Relief, steps: int, device: str, reps: int = 20) -> dict:
    """ms per generator relief window (1 x RELIEF_EXT^2): native sampler when available, else torch."""
    from terrain_slm import kernels as K
    from terrain_slm.world.generator import RELIEF_EXT
    n = RELIEF_EXT
    cond = torch.randn(1, RL.N_COND, n, n, device=device) * 0.3
    noise = torch.randn(1, 1, n, n, device=device)
    out = {}
    runs = {"torch": lambda: RL.sample(model, cond, noise, steps=steps)}
    if K.available(device):
        from terrain_slm.kernels.cuda_unet import NativeSampler
        ns = NativeSampler(model, n, n, steps)
        runs["native"] = lambda: ns(cond, noise)
    for name, fn in runs.items():
        for _ in range(3):
            fn()
        ts = []
        for _ in range(reps):
            torch.cuda.synchronize(device); t0 = time.perf_counter(); fn(); torch.cuda.synchronize(device)
            ts.append((time.perf_counter() - t0) * 1000)
        out[name + "_ms"] = float(np.median(ts))
    return out


@torch.no_grad()
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", type=Path, default=Path("checkpoints/exp2/relief_v2c/best.pt"))
    ap.add_argument("--teacher-steps", type=int, default=16)
    ap.add_argument("--student", type=Path, nargs="*", default=[])
    ap.add_argument("--student-steps", type=int, nargs="+", default=[1, 2])
    ap.add_argument("--teacher-few", action="store_true", help="also the FM teacher at the student step counts")
    ap.add_argument("--data-dir", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/v2-gedtm30"),
                    help="every region with cells.npz under it (the relief_v2c / relief_mf training set)")
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--max-crops", type=int, default=0)
    ap.add_argument("--no-timing", action="store_true")
    ap.add_argument("--json", type=Path, default=None)
    args = ap.parse_args()
    dev = args.device
    torch.manual_seed(0)
    regions = sorted(d for d in args.data_dir.iterdir() if (d / "cells.npz").exists()) if args.data_dir else built_regions()
    data = ReliefData(regions, dev)
    if args.max_crops:
        data.val_corners = data.val_corners[: args.max_crops]
    teacher = load(args.teacher, dev)
    samplers = {f"teacher@{args.teacher_steps}": (teacher, args.teacher_steps)}
    if args.teacher_few:
        samplers.update({f"teacher@{k}": (teacher, k) for k in args.student_steps})
    for p in args.student:
        m = load(p, dev)
        name = p.parent.name + ("" if p.stem == "best" else f"/{p.stem}")
        samplers.update({f"{name}@{k}": (m, k) for k in args.student_steps})
    accs = {k: Acc() for k in samplers}
    real = Acc()
    rms = {k: [0.0, 0.0] for k in samplers}
    wild, crops = [], 0
    g = torch.Generator(device=dev).manual_seed(123)
    t_key = f"teacher@{args.teacher_steps}"
    for cond, x1 in data.val_batches(args.batch):
        noise = torch.randn(x1.shape, device=dev, generator=g)
        real.add(x1)
        outs = {k: RL.sample(m, cond, noise, steps=s) for k, (m, s) in samplers.items()}
        for k, x in outs.items():
            accs[k].add(x)
            rms[k][0] += float((x - outs[t_key]).pow(2).sum()); rms[k][1] += float(outs[t_key].pow(2).sum())
        wild.append(cond[:, RL.C_WILD].flatten(1).mean(1).cpu())
        crops += x1.shape[0]
    wild = torch.cat(wild)
    res = {"crops": crops, "real": real.summary(crops, wild, None)}
    for k in samplers:
        r = accs[k].summary(crops, wild, real)
        r["rms_vs_teacher"] = math.sqrt(rms[k][0] / rms[k][1])
        res[k] = r
    if not args.no_timing:
        res["timing"] = {k: window_time(m, s, dev) for k, (m, s) in samplers.items()}
    print(json.dumps(res, indent=1))
    if args.json:
        args.json.write_text(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()
