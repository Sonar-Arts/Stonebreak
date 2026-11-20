"""Launch-config tuner for the FP8 kernels (csrc/conv_fp8.cu): times every candidate config of every FP8 layer shape a
NativeDetail2SamplerFP8 builds and writes the fastest per GPU name to kernels/cuda_configs_fp8.json (data, not source:
configs never change numbers -- fixed K order, no split-K -- so retuning leaves tile caches alone).

    python -m terrain_slm.kernels.tune_fp8 [--device cuda:1] [--size 512] [--reps 30]

Timing is a median over interleaved rounds (other jobs on the card make single runs noisy).
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import torch

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.kernels import cuda_conv_fp8 as C8

TABLE = Path(__file__).resolve().parent / "cuda_configs_fp8.json"


def hconv8_candidates(co: int) -> list[tuple]:
    out = []
    for th, tw in ((8, 16), (8, 32), (16, 16), (16, 32), (8, 8), (4, 32)):
        for bn in (32, 64, 96, 128):
            if co % bn and not (co < bn):
                continue
            for wm, wn in ((4, 1), (8, 1), (4, 2), (2, 2), (2, 4), (8, 2)):
                for st in (2, 3):
                    cfg = (th, tw, bn, wm, wn, st)
                    if C8.hconv8_valid(cfg):
                        out.append(cfg)
    return out


def conv8_candidates(co: int) -> list[tuple]:
    out = []
    for bm in (64, 128, 256):
        for bn in (32, 64, 96, 128):
            if co % bn and not (co < bn):
                continue
            for wm, wn in ((2, 2), (4, 1), (4, 2), (2, 1), (8, 1)):
                for st in (3, 4, 6):
                    cfg = (bm, bn, wm, wn, st)
                    if C8.conv8_valid(cfg):
                        out.append(cfg)
    return out


def _time(lcs, reps: int) -> list[float]:
    """Interleaved: each round runs every candidate once (reps rounds)."""
    ts = [[] for _ in lcs]
    for lc in lcs:
        lc()
    torch.cuda.synchronize()
    for _ in range(reps):
        for i, lc in enumerate(lcs):
            e0, e1 = torch.cuda.Event(enable_timing=True), torch.cuda.Event(enable_timing=True)
            e0.record()
            for _ in range(3):
                lc()
            e1.record()
            e1.synchronize()
            ts[i].append(e0.elapsed_time(e1) / 3)
    return [float(np.median(t)) for t in ts]


def tune(shapes: list[tuple], dev, reps: int) -> dict:
    """shapes: (kind8, ci, co, k, stride, ho). Returns {json key: best cfg}."""
    best = {}
    for key in sorted(set(shapes)):
        kind, ci, co, k, stride, ho = key
        hh = ho * stride
        x = torch.randint(0, 120, (1, hh, hh, ci), device=dev, dtype=torch.uint8)
        if kind in ("upmerge8",):
            continue   # tuned with mode 1 below
        hconv = kind in ("c18", "c28", "ad_mid8", "out8") and k == 3 and stride == 1
        w = torch.randn(co, ci, k, k, device=dev) / (k * k * ci) ** 0.5
        lcs, cfgs = [], []
        for cfg in (hconv8_candidates(co) if hconv else conv8_candidates(co)):
            np_ = C.round_up(co, cfg[2] if hconv else cfg[1])
            if hconv:
                wc, sc = C8.pack_hconv8(w, np_)
                o = torch.zeros(1, ho, ho, co, device=dev, dtype=torch.uint8)
                if co == 1:
                    xs = torch.zeros(ho * ho, device=dev)
                    st_in = torch.zeros(1, ho, ho, 32, device=dev, dtype=torch.float16)
                    lc = C8.make_hconv8(x, wc, sc, cfg, euler=(xs, st_in, 0.5))
                else:
                    lc = C8.make_hconv8(x, wc, sc, cfg, out_silu8=o)
            else:
                wc, sc = C8.pack_conv8(w, np_)
                o = torch.zeros(1, ho, ho, co, device=dev, dtype=torch.uint8)
                lc = C8.make_conv8(x, wc, sc, cfg, ks=k, stride=stride, out_silu8=o)
            lcs.append(lc)
            cfgs.append(cfg)
        C.bind(lcs, dev)
        t = _time(lcs, reps)
        i = int(np.argmin(t))
        best[json.dumps(list(key))] = list(cfgs[i])
        print(f"{key}: best {cfgs[i]} {t[i]:.4f} ms (default-ish first {t[0]:.4f}, {len(cfgs)} candidates)", flush=True)
    for key in sorted(set(s for s in shapes if s[0] == "upmerge8")):
        kind, clow, c, _, _, lo = key
        x = torch.randint(0, 120, (1, lo, lo, clow), device=dev, dtype=torch.uint8)
        skip = torch.randint(0, 120, (1, 2 * lo, 2 * lo, c), device=dev, dtype=torch.uint8)
        lcs, cfgs = [], []
        for cfg in conv8_candidates(c):
            np_ = C.round_up(c, cfg[1])
            wq = torch.randint(0, 120, (4 * np_, 4 * clow + c), device=dev, dtype=torch.uint8)
            sc = torch.ones(4 * np_, device=dev)
            o = torch.zeros(1, 2 * lo, 2 * lo, c, device=dev, dtype=torch.uint8)
            raw = torch.zeros(1, 2 * lo, 2 * lo, c, device=dev, dtype=torch.float16)
            lcs.append(C8.make_conv8(x, wq, sc, cfg, mode=1, x2=skip, out_raw=raw, out_silu8=o))
            cfgs.append(cfg)
        C.bind(lcs, dev)
        t = _time(lcs, reps)
        i = int(np.argmin(t))
        best[json.dumps(list(key))] = list(cfgs[i])
        print(f"{key}: best {cfgs[i]} {t[i]:.4f} ms ({len(cfgs)} candidates)", flush=True)
    return best


def load_table() -> dict:
    """{GPU name: {key: config}}; a GPU without its own table borrows a same-capability GPU's (cuda_conv.TunedTables)."""
    return C.load_tables(TABLE)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--reps", type=int, default=20)
    args = ap.parse_args(argv)
    dev = torch.device(args.device)
    torch.cuda.set_device(dev)
    from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8
    from terrain_slm.models import detail2 as D2
    from terrain_slm.quant import detail2_sim as Q
    # the shipping architecture with every layer FP8 (the stem stays fp16): every FP8 shape
    m = D2.Detail2(D2.Detail2Config()).to(dev).eval()
    for s in ("aeolian", "undersea", "skerries"):
        m.add_expert(s)
    ns = NativeDetail2SamplerFP8(m, args.size, args.size, 2, qc=Q.QConfig(default="e4m3", layers={"stem": "fp16"}),
                                 tuned={})
    shapes = [e[0] for e in ns.layer_keys if str(e[0][0]).endswith("8")]
    best = tune(shapes, dev, args.reps)
    raw = json.loads(TABLE.read_text()) if TABLE.exists() else {}
    raw.setdefault(torch.cuda.get_device_name(dev), {}).update(best)
    TABLE.write_text(json.dumps(raw, indent=1))
    print(f"wrote {TABLE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
