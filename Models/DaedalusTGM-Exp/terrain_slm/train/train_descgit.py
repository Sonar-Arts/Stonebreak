"""Train the descriptor sampler (MaskGIT, models/descgit.py).

Same crops, controls degradation and split as the planner. Targets are the real descriptors as
split-codebook tokens; the model learns to predict masked tokens from unmasked ones plus the
planner's inputs. Masks are either random (a cosine-scheduled fraction of cells) or tiling-like
(the window's core hidden, some neighbouring cores given), which is exactly what the generator
asks of it.

Eval reports the averaging symptom directly: within-crop spatial spread of the band-3 log
amplitude for real terrain, for the planner-style mean (argmax of a fully masked pass), and for
a sampled pass. A sampler that works has sampled/real near 1.
"""
from __future__ import annotations

import argparse
import json
import math
import time
from pathlib import Path

import torch
import torch.nn.functional as F

from terrain_slm.device import default_device
from terrain_slm.models import descgit as G
from terrain_slm.models import planner as P
from terrain_slm.train.planner_data import PlannerData, built_regions

CROP = 64
CORE = 32


def build_codebook(data: PlannerData, device) -> G.Codebook:
    mean = torch.tensor(data.norms.desc_mean, device=device).view(-1, 1)
    std = torch.tensor(data.norms.desc_std, device=device).view(-1, 1)
    parts = []
    for reg in data.regions:
        d = reg.desc[:, reg.train_mask].clone()
        d[5] = d[5].clamp(-3, 3)
        d = (d - mean) / std
        parts.append(d[:, torch.randperm(d.shape[1], device=device)[:400000]])
    x = torch.cat(parts, 1).T.contiguous()
    return G.Codebook(G.kmeans(x[:, G.AMP], G.K_AMP), G.kmeans(x[:, G.SHAPE], G.K_SHAPE)).to(device)


def make_mask(b: int, gen: torch.Generator, device, tiling_p: float = 0.4) -> torch.Tensor:
    """(B, CROP, CROP) bool, True = masked (to predict)."""
    m = torch.empty(b, CROP, CROP, dtype=torch.bool)
    for i in range(b):
        if float(torch.rand(1, generator=gen)) < tiling_p:
            given = torch.zeros(CROP, CROP, dtype=torch.bool)
            ring = CROP // 2 - CORE // 2  # 16
            spans = ((0, ring), (ring, ring + CORE), (ring + CORE, CROP))
            for r in range(3):
                for c in range(3):
                    if (r, c) != (1, 1) and float(torch.rand(1, generator=gen)) < 0.5:
                        given[spans[r][0]:spans[r][1], spans[c][0]:spans[c][1]] = True
            m[i] = ~given
        else:
            ratio = math.cos(math.pi / 2 * float(torch.rand(1, generator=gen)))
            m[i] = torch.rand(CROP, CROP, generator=gen) < max(ratio, 1.0 / (CROP * CROP))
    return m.to(device)


def losses(model, x, a, s, mask, stat_w):
    ai = torch.where(mask, torch.full_like(a, G.K_AMP), a)
    si = torch.where(mask, torch.full_like(s, G.K_SHAPE), s)
    with torch.autocast("cuda", dtype=torch.bfloat16):
        la, ls = model(x, ai, si)
    ca = F.cross_entropy(la.float()[mask], a[mask])
    w = stat_w[mask]
    cs = (F.cross_entropy(ls.float()[mask], s[mask], reduction="none") * w).sum() / w.sum().clamp_min(1.0)
    acc = (la.argmax(-1)[mask] == a[mask]).float().mean()
    return {"amp_ce": ca, "shape_ce": cs, "total": ca + 0.5 * cs, "amp_acc": acc}


def spread(desc_norm: torch.Tensor, std3: float, mean3: float) -> torch.Tensor:
    """Per-crop spatial std of band-3 log amplitude (raw units)."""
    la = desc_norm[:, 3] * std3 + mean3
    return la.flatten(1).std(1)


@torch.no_grad()
def evaluate(model, data, batch, limit=256):
    model.eval()
    g = torch.Generator(device="cpu").manual_seed(123)
    acc, n = {}, 0
    real_sp, samp_sp, mean_sp = 0.0, 0.0, 0.0
    std3, mean3 = data.norms.desc_std[3], data.norms.desc_mean[3]
    for bi, (x, tgt) in enumerate(data.val_batches(batch)):
        if bi * batch >= limit:
            break
        d = tgt["desc"]
        a, s = model.codebook.encode(d)
        mask = make_mask(x.shape[0], g, x.device, tiling_p=0.0)
        for k, v in losses(model, x, a, s, mask, tgt["stat_weight"][:, 0]).items():
            acc[k] = acc.get(k, 0.0) + v.item() * x.shape[0]
        n += x.shape[0]
        # Planner-style mean: one pass from all-masked, argmax (no sampling).
        full = torch.ones_like(a, dtype=torch.bool)
        la, ls = model(x, torch.full_like(a, G.K_AMP), torch.full_like(s, G.K_SHAPE))
        mean_d = model.codebook.decode(la.argmax(-1), ls.argmax(-1))
        ii = torch.arange(x.shape[0], device=x.device).view(-1, 1, 1) * 100003
        yy = torch.arange(CROP, device=x.device).view(1, -1, 1) * 4099
        xx = torch.arange(CROP, device=x.device).view(1, 1, -1)
        keys = ii + yy + xx + bi * 7
        sa, ss = G.sample(model, x, a, s, ~full, keys, steps=10)
        samp_d = model.codebook.decode(sa, ss)
        real_sp += spread(d, std3, mean3).sum().item()
        samp_sp += spread(samp_d, std3, mean3).sum().item()
        mean_sp += spread(mean_d, std3, mean3).sum().item()
    model.train()
    res = {k: v / n for k, v in acc.items()}
    res["spread_real"] = real_sp / n
    res["spread_ratio_sampled"] = samp_sp / max(real_sp, 1e-9)
    res["spread_ratio_mean"] = mean_sp / max(real_sp, 1e-9)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, nargs="+", default=None)
    ap.add_argument("--out", type=Path, default=Path("checkpoints/descgit_d0"))
    ap.add_argument("--device", default=default_device())
    ap.add_argument("--steps", type=int, default=30000)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--eval-every", type=int, default=3000)
    args = ap.parse_args()
    torch.manual_seed(0)
    args.out.mkdir(parents=True, exist_ok=True)
    data = PlannerData(args.data or built_regions(), args.device, crop=CROP)
    cfg = G.DescGitConfig()
    model = G.DescGit(cfg).to(args.device)
    model.codebook = build_codebook(data, args.device)
    print(f"descgit {sum(p.numel() for p in model.parameters()):,} params", flush=True)
    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr, weight_decay=0.01,
                            betas=(0.9, 0.99))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda st: min(1.0, (st + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, st / args.steps))))
    log = open(args.out / "log.jsonl", "a")
    best, t0 = float("inf"), time.time()
    for step in range(1, args.steps + 1):
        x, tgt = data.train_batch(args.batch)
        a, s = model.codebook.encode(tgt["desc"])
        mask = make_mask(x.shape[0], data.gen, x.device)
        l = losses(model, x, a, s, mask, tgt["stat_weight"][:, 0])
        opt.zero_grad(set_to_none=True)
        l["total"].backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        if step % 100 == 0:
            print(f"step {step:6d}  loss {l['total'].item():.4f}  amp_ce {l['amp_ce'].item():.3f}  "
                  f"acc {l['amp_acc'].item():.3f}  {(time.time() - t0) / step * 1000:.1f} ms/step", flush=True)
        if step % args.eval_every == 0 or step == args.steps:
            ev = {"step": step, **evaluate(model, data, args.batch)}
            log.write(json.dumps(ev) + "\n"); log.flush()
            print("  eval", {k: round(v, 4) for k, v in ev.items()}, flush=True)
            ckpt = {"model": model.state_dict(), "config": cfg.to_dict(), "norms": data.norms.to_dict(),
                    "step": step, "eval": ev}
            torch.save(ckpt, args.out / "last.pt")
            if ev["total"] < best:
                best = ev["total"]
                torch.save(ckpt, args.out / "best.pt")
    print(f"done in {(time.time() - t0) / 60:.1f} min; best val {best:.4f}", flush=True)


if __name__ == "__main__":
    main()
