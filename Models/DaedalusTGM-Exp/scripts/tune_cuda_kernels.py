"""Offline launch-config tuner for the native CUDA convolutions (terrain_slm/kernels/csrc/conv.cu).

    python scripts/tune_cuda_kernels.py [model_dir] [--device cuda:1] [--quick]

Builds the native detail and relief samplers, takes every distinct layer shape one Euler step launches,
and times candidate (BM, BN, WM, WN, STAGES) configs on it. Writes the winners for this GPU (by name)
into terrain_slm/kernels/cuda_configs.json. A config changes speed only, never a result (fixed K
order, no split-K -- tests/test_cuda_kernels.py pins it), so tuning never touches tile caches.
Lives outside the package on purpose (the tile cache fingerprints terrain_slm's source).
"""
from __future__ import annotations

import argparse
import itertools
import json
import os
import shutil
import tempfile
from pathlib import Path

import torch

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.kernels.cuda_unet import NativeSampler, uses_hconv
from terrain_slm.models import detail as DT
from terrain_slm.models import relief as RL

CONFIG_FILE = Path(__file__).resolve().parents[1] / "terrain_slm" / "kernels" / "cuda_configs.json"


def load(path: Path, cls, cfg_cls, dev):
    k = torch.load(path, map_location=dev, weights_only=False)
    c = dict(k["config"])
    c["channels"] = tuple(c["channels"])
    m = cls(cfg_cls(**c)).to(dev).eval()
    m.load_state_dict(k["model"])
    return m


def hconv_candidates(co: int, h: int, quick: bool):
    bns = [b for b in (16, 32, 48, 64, 96, 128, 144, 192) if co % b == 0 or (co < 16 and b == 16)]
    for th, tw, bn, (wm, wn), st in itertools.product((8, 16), (8, 16, 32), bns,
                                                      ((2, 2), (4, 2), (2, 4), (4, 1), (8, 1), (1, 4)), (2, 3, 4)):
        cfg = (th, tw, bn, wm, wn, st)
        if C.hconv_valid(cfg) and th * tw <= max(256, h * h) and not (quick and st == 4):
            yield cfg


def candidates(co: int, m: int, quick: bool):
    bns = [b for b in (16, 32, 48, 64, 96, 128, 144, 192, 256) if co % b == 0 or (b > co and b == C.round_up(co, 16))]
    bms = (64, 128, 256)
    for bm, bn, (wm, wn), st in itertools.product(bms, bns, ((1, 2), (2, 1), (2, 2), (4, 1), (1, 4), (2, 4), (4, 2)),
                                                  (2, 3, 4)):
        cfg = (bm, bn, wm, wn, st)
        if not C.valid_config(cfg):
            continue
        if quick and st == 2:
            continue
        yield cfg


def make_layer(key, mode, dev, cfg):
    """A synthetic launch with the layer's shapes and the heaviest epilogue its kind uses."""
    kind, cin, cout, ks, stride, ho = key
    hf = dict(device=dev, dtype=torch.float16)
    if kind == "out" or len(cfg) == 6:
        np_ = C.round_up(cout, cfg[2])
        x = torch.randn(1, ho, ho, cin, **hf)
        w = (torch.randn(9 * cin, np_, device=dev) * 0.01).half()
        bias = torch.zeros(np_, device=dev)
        if kind == "out":
            return C.make_hconv(x, w, bias, cfg, euler=(torch.zeros(ho * ho, device=dev),
                                                       torch.zeros(1, ho, ho, 32, **hf), 0.1))
        out = lambda: torch.zeros(1, ho, ho, cout, **hf)
        return C.make_hconv(x, w, bias, cfg, film=torch.ones(2 * cout, device=dev) if kind == "c1" else None,
                            res=out() if kind == "c2" else None, out_raw=out() if kind == "c2" else None, out_silu=out())
    np_ = C.round_up(cout, cfg[1])
    out = lambda hh: torch.zeros(1, hh, hh, cout, **hf)
    bias = torch.zeros(np_, device=dev)
    if mode == 1:
        x = torch.randn(1, ho, ho, cin, **hf)
        skip = torch.randn(1, 2 * ho, 2 * ho, cout, **hf) if kind == "upmerge" else None
        rows = 4 * (4 * cin + (cout if skip is not None else 0))
        w = (torch.randn(rows, np_, device=dev) * 0.01).half()
        return C.make_conv(x, w, bias, cfg, mode=1, x2=skip, out_raw=out(2 * ho), out_silu=out(2 * ho) if skip is not None else None)
    hi = ho * stride
    x = torch.randn(1, hi, hi, cin, **hf)
    x2 = torch.randn(1, hi, hi, cin, **hf) if kind == "merge" else None   # relief merge: concat of two equal halves
    rows = ks * ks * cin * (2 if x2 is not None else 1)
    if x2 is not None:
        x = torch.randn(1, hi, hi, cin // 2, **hf)
        x2 = torch.randn(1, hi, hi, cin // 2, **hf)
        rows = ks * ks * cin
    w = (torch.randn(rows, np_, device=dev) * 0.01).half()
    film = torch.ones(2 * cout, device=dev) if kind == "c1" else None
    res = out(ho) if kind == "c2" else None
    return C.make_conv(x, w, bias, cfg, ks=ks, stride=stride, x2=x2, film=film, res=res,
                       out_raw=out(ho) if kind != "c1" else None, out_silu=out(ho))


def time_launch(lc, reps=30) -> float:
    for _ in range(3):
        lc()
    torch.cuda.synchronize()
    best = float("inf")
    for _ in range(2):
        s, e = torch.cuda.Event(True), torch.cuda.Event(True)
        s.record()
        for _ in range(reps):
            lc()
        e.record()
        torch.cuda.synchronize()
        best = min(best, s.elapsed_time(e) / reps)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model", nargs="?", default="checkpoints/v4")
    ap.add_argument("--device", default="cuda:1")
    ap.add_argument("--quick", action="store_true")
    a = ap.parse_args()
    # Hundreds of candidate variants: compile them into a throwaway cache, not the game's Models/kernel_cache.
    scratch = None
    if not os.getenv("TGM_KERNEL_CACHE"):
        scratch = tempfile.mkdtemp(prefix="tgm-tune-")
        os.environ["TGM_KERNEL_CACHE"] = scratch
    try:
        run(a)
    finally:
        if scratch:
            shutil.rmtree(scratch, ignore_errors=True)


def run(a) -> None:
    dev = torch.device(a.device)
    torch.cuda.set_device(dev)
    root = Path(__file__).resolve().parents[1]
    mdir = root / a.model
    layers = {}
    for path, cls, cfg_cls, n in ((mdir / "detail.pt", DT.Detail, DT.DetailConfig, 512),
                                  (mdir / "relief.pt", RL.Relief, RL.ReliefConfig, 448)):
        if not path.exists():
            continue
        model = load(path, cls, cfg_cls, dev)
        s = NativeSampler(model, n, n, 1)
        for key, m, co, mode, kdim in s.layer_keys:
            layers.setdefault((key, mode), [m, co, 0])[2] += 1
        del s
    name = torch.cuda.get_device_name(dev)
    table = json.loads(CONFIG_FILE.read_text()) if CONFIG_FILE.exists() else {}
    tuned = table.setdefault(name, {})
    total_old = total_new = 0.0
    for (key, mode), (m, co, count) in sorted(layers.items(), key=lambda kv: -kv[1][0] * kv[1][1]):
        if key[0] == "out":
            default, cands = (8, 32, 16, 8, 1, 2), list(hconv_candidates(1, key[5], a.quick))
        elif uses_hconv(key):
            default, cands = C.default_hconv_config(co, key[5]), list(hconv_candidates(co, key[5], a.quick))
        else:
            default, cands = C.default_config(m, co, mode, 0), list(candidates(co, m, a.quick))
        launches = {}
        for cfg in [default] + [c for c in cands if c != default]:
            try:
                lc = make_layer(key, mode, dev, cfg)
                C.bind([lc], dev)
                lc()
                launches[cfg] = lc
            except RuntimeError as e:   # e.g. too many registers for the block size
                print(f"  skip {cfg}: {str(e).splitlines()[0][:80]}")
        torch.cuda.synchronize()
        # Round-robin rounds, best of each: the GPU's clocks drift with temperature and its power cap,
        # so timing every candidate once in sequence would favour whichever ran while it was cool.
        best_t = {cfg: float("inf") for cfg in launches}
        for _ in range(4):
            for cfg, lc in launches.items():
                best_t[cfg] = min(best_t[cfg], time_launch(lc, reps=10))
        results = sorted((t, cfg) for cfg, t in best_t.items())
        t_def = best_t[default]
        t_best, best = results[0]
        total_old += t_def * count
        total_new += t_best * count
        tuned[json.dumps(list(key))] = list(best)
        print(f"{str(key):44s} x{count:2d}  default {t_def*1000:7.1f} us  best {t_best*1000:7.1f} us  {best}", flush=True)
    CONFIG_FILE.write_text(json.dumps(table, indent=1, sort_keys=True) + "\n")
    print(f"one step (all layers): default {total_old:.2f} ms -> tuned {total_new:.2f} ms; wrote {CONFIG_FILE}")


if __name__ == "__main__":
    main()
