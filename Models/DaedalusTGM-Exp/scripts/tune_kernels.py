"""Offline launch-config tuner for terrain_slm.kernels.conv (run once per GPU model).

    python scripts/tune_kernels.py [model_dir] [--device cuda:1]

Builds the fused detail and relief samplers, records every convolution one Euler step launches,
and times candidate (BLOCK_M, BLOCK_N, BLOCK_K, num_warps, num_stages) configs per layer shape
over all the fused variants that share it (FiLM, residual, upsample, ...). Prints a CONFIGS
table to paste into kernels/conv.py. Configs are never picked at run time: a fixed table keeps
every process computing the same numbers. Lives outside the package on purpose (the tile
cache fingerprints terrain_slm's source).
"""
from __future__ import annotations

import argparse
import itertools
from collections import defaultdict
from pathlib import Path

import torch

from terrain_slm.kernels import conv as K
from terrain_slm.kernels.unet import FusedSampler
from terrain_slm.models import detail as DT
from terrain_slm.models import relief as RL


def load(path: Path, cls, cfg_cls, dev):
    k = torch.load(path, map_location=dev, weights_only=False)
    c = dict(k["config"])
    c["channels"] = tuple(c["channels"])
    m = cls(cfg_cls(**c)).to(dev).eval()
    m.load_state_dict(k["model"])
    return m


def up_key_of(args, kw) -> tuple:
    x, w = args[0], args[1]
    return ("up", x.shape[3], w.shape[1], x.shape[1])


def key_of(args, kw) -> tuple:
    x1, w = args[0], args[1]
    up, x2, stride = kw.get("up1", False), kw.get("x2"), kw.get("stride", 1)
    hi = x1.shape[1] * (2 if up else 1)
    c2 = 0 if x2 is None else x2.shape[3]
    return (x1.shape[3], c2, w.shape[1], kw.get("ks", 3), stride, (hi + stride - 1) // stride)


def candidates(c1: int, c2: int, co: int, m: int):
    for bm, bn, bk, nw, ns in itertools.product((64, 128, 256), (16, 32, 64, 128, 256), (32, 64), (4, 8), (2, 3, 4)):
        if (c1 | c2) % bk or bn > max(16, 1 << (co - 1).bit_length()) or bm * bn > 128 * 256:
            continue
        if bm > 64 and m < 64 * 188:          # small grids: keep enough programs for the SMs
            continue
        yield bm, bn, bk, nw, ns


def timeit(calls, cfg, reps: int = 10) -> float:
    for fn, a, kw in calls:
        fn(*a, **kw, config=cfg)
    torch.cuda.synchronize()
    best = float("inf")
    for _ in range(3):
        s, e = torch.cuda.Event(True), torch.cuda.Event(True)
        s.record()
        for _ in range(reps):
            for fn, a, kw in calls:
                fn(*a, **kw, config=cfg)
        e.record()
        torch.cuda.synchronize()
        best = min(best, s.elapsed_time(e) / reps)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model", nargs="?", default="checkpoints/v4")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--only-up", action="store_true", help="tune only the phase-decomposed up-convolutions")
    a = ap.parse_args()
    dev = torch.device(a.device)
    if dev.index is not None:
        torch.cuda.set_device(dev)
    md = Path(a.model)
    samplers = [FusedSampler(load(md / "detail.pt", DT.Detail, DT.DetailConfig, dev), 512, 512, 1),
                FusedSampler(load(md / "relief.pt", RL.Relief, RL.ReliefConfig, dev), 448, 448, 1)]
    calls = defaultdict(list)        # key -> [(fn, args, kwargs)] (one entry per fused variant seen)
    seen = set()
    orig, orig_up = K.conv, K.upconv

    def record(*args, **kw):
        k = key_of(args, kw)
        variant = (k, tuple(sorted(n for n, v in kw.items() if v is not None and n not in ("ks", "stride"))))
        if variant not in seen and not a.only_up:
            seen.add(variant)
            calls[k].append((orig, args, dict(kw)))
        orig(*args, **kw)

    def record_up(*args, **kw):
        k = up_key_of(args, kw)
        if k not in seen:
            seen.add(k)
            calls[k].append((orig_up, args, dict(kw)))
        orig_up(*args, **kw)

    K.conv, K.upconv = record, record_up
    for s in samplers:
        for op in s._ops:
            op(0)
    K.conv, K.upconv = orig, orig_up
    table, up_table = {}, {}
    for k, cl in sorted(calls.items(), key=lambda kv: -kv[0][-1]):
        if k[0] == "up":
            _, c1, co, h = k
            c2, ks, m = 0, 2, h * h
            base = K.UP_CONFIGS.get(k[1:]) or K._default_config(c1, 0, co, 2, m)
        else:
            c1, c2, co, ks, _, ho = k
            m = ho * ho
            base = K.CONFIGS.get(k) or K._default_config(c1, c2, co, ks, m)
        t_base = timeit(cl, base)
        best = (t_base, base)
        for cfg in candidates(c1, c2, co, m):
            try:
                t = timeit(cl, cfg)
            except Exception:  # out of shared memory / registers for this shape
                continue
            if t < best[0]:
                best = (t, cfg)
        (up_table if k[0] == "up" else table)[k[1:] if k[0] == "up" else k] = best[1]
        print(f"{k}: {len(cl)} variant(s)  {t_base:.3f} -> {best[0]:.3f} ms  {best[1]}", flush=True)
    for name, tab in (("CONFIGS", table), ("UP_CONFIGS", up_table)):
        if tab:
            print(f"\n{name} = {{")
            for k, v in tab.items():
                print(f"    {k}: {v},")
            print("}")


if __name__ == "__main__":
    main()
