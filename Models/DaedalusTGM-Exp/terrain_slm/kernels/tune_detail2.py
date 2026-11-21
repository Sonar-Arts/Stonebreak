"""Launch-config tuner for the Detail2 samplers (kernels/cuda_detail2.py fp16, kernels/cuda_detail2_fp8.py FP8; K4,
2026-11-19): every layer shape either sampler builds for one window, a wide candidate space per kernel family, burst
timing (the card at its boost clock, as in the service: short GPU bursts between host work), two passes (coarse over
every candidate, then the best dozen re-timed interleaved), winners written per GPU name:

    python -m terrain_slm.kernels.tune_detail2 [--device cuda:0] [--model checkpoints/exp2-r3] [--only fp8|fp16]
                                               [--kinds c1,c2,...] [--quick] [--dry]

  fp16 layers  -> kernels/cuda_configs.json      (keys (kind, cin, cout, k, stride, ho); shared with v4 / relief keys,
                                                 only the Detail2 keys are touched)
  FP8 layers   -> kernels/cuda_configs_fp8.json  (keys (kind + "8", ...))

Configs never change numbers within a kernel type (fixed K order, no split-K), so retuning leaves tile caches alone. Each layer is
timed with the epilogue its kind runs in the sampler (FiLM + SiLU for conv0, residual + clip + raw + SiLU for conv1,
...), on synthetic data of the real shape.
"""
from __future__ import annotations

import argparse
import copy
import itertools
import json
import time
from pathlib import Path

import numpy as np
import torch

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.kernels import cuda_conv_fp8 as C8
from terrain_slm.kernels import native

HERE = Path(__file__).resolve().parent
TABLE16 = HERE / "cuda_configs.json"
TABLE8 = HERE / "cuda_configs_fp8.json"

TILES = ((4, 32), (8, 8), (8, 16), (8, 32), (16, 16), (16, 32), (32, 16), (8, 64), (4, 16), (4, 64))
WARPS = ((2, 1), (4, 1), (8, 1), (2, 2), (4, 2), (8, 2), (2, 4), (4, 4), (1, 2), (1, 4), (16, 1))


def _bns(co: int):
    return [b for b in (16, 32, 48, 64, 96, 128, 144, 192) if co % b == 0 or (co < 16 and b == 16)]


def hconv_cands(co: int, h: int, fp8: bool, quick: bool):
    out = []
    for (th, tw), bn, (wm, wn), st in itertools.product(TILES, _bns(co), WARPS, (2, 3, 4)):
        if th * tw > max(256, h * h) or (quick and st == 4):
            continue
        cfg = (th, tw, bn, wm, wn, st)
        if (C8.hconv8_valid(cfg) if fp8 else C.hconv_valid(cfg)):
            out.append(cfg)
    return out


def tile_cands(co: int, lo: int, valid, quick: bool, divide: bool):
    """uconv / uconv8 (divide: the tile must divide the low-res grid) and dconv8 tile configs."""
    out = []
    for (th, tw), bn, (wm, wn), st in itertools.product(TILES, _bns(co), WARPS, (2, 3, 4)):
        if quick and st == 4:
            continue
        if divide and (lo % th or lo % tw):
            continue
        cfg = (th, tw, bn, wm, wn, st)
        if valid(cfg):
            out.append(cfg)
    return out


def conv_cands(co: int, fp8: bool, quick: bool):
    bns = [b for b in (16, 32, 48, 64, 96, 128, 144, 192, 256) if co % b == 0 or (b > co and b == C.round_up(co, 16))]
    out = []
    for bm, bn, (wm, wn), st in itertools.product((32, 64, 128, 256), bns,
                                                  ((1, 2), (2, 1), (2, 2), (4, 1), (1, 4), (2, 4), (4, 2), (8, 1)),
                                                  (2, 3, 4, 6)):
        cfg = (bm, bn, wm, wn, st)
        if quick and st in (2, 6):
            continue
        if (C8.conv8_valid(cfg) if fp8 else C.valid_config(cfg)):
            out.append(cfg)
    return out


# ----------------------------------------------------------------------------- one layer, one config -> a launch
class Layer:
    """Synthetic tensors of one layer shape (shared by every candidate) and a launch factory."""

    def __init__(self, key: tuple, fp8: bool, dev):
        self.key, self.fp8, self.dev = key, fp8, dev
        kind, ci, co, k, stride, ho = key
        self.kind = kind[:-1] if fp8 else kind
        g = torch.Generator(device=dev).manual_seed(hash(key) % 1000)
        hi = ho * stride if self.kind not in ("upmerge", "uconv") else ho
        self.ci, self.co, self.k, self.stride, self.ho, self.hi = ci, co, k, stride, ho, hi
        u8 = dict(device=dev, dtype=torch.uint8)
        f16 = dict(device=dev, dtype=torch.float16)
        if fp8:
            self.x = torch.randint(0, 120, (1, hi, hi, ci), generator=g, **u8)
        else:
            self.x = (torch.randn(1, hi, hi, ci, generator=g, device=dev) * 0.5).half()
        hout = 2 * ho if self.kind in ("upmerge", "uconv") else ho
        self.hout = hout
        self.skip8 = torch.randint(0, 120, (1, hout, hout, co), generator=g, **u8)
        self.skip16 = (torch.randn(1, hout, hout, co, generator=g, device=dev) * 0.5).half()
        self.raw = (torch.randn(1, hout, hout, max(co, 1), generator=g, device=dev) * 0.5).half()
        self.o16 = torch.zeros(1, hout, hout, max(co, 1), **f16)
        self.o8 = torch.zeros(1, hout, hout, max(co, 1), **u8)
        self.film = torch.randn(2 * max(co, 1), generator=g, device=dev) * 0.1 + 1.0
        self.gate = torch.rand(hout * hout, generator=g, device=dev)
        self.xs = torch.zeros(hout * hout, device=dev)
        self.stem_in = torch.zeros(1, hout, hout, 32, **f16)
        self.wt = torch.randn(max(co, 1), ci, k, k, generator=g, device=dev) * (1.0 / (k * k * ci) ** 0.5)
        self._packs = {}

    def _outs(self) -> dict:
        """The epilogue each kind runs in the sampler (FP8: e4m3 SiLU for the e4m3 consumer)."""
        silu = dict(out_silu8=self.o8, qs=8.0) if self.fp8 else dict(out_silu=self.o16)
        k = self.kind
        if k == "c1":
            return dict(film=self.film, **silu)
        if k == "c2":
            return dict(res=self.raw, res_scale=0.7, clip=True, out_raw=self.raw, **silu)
        if k == "ad_up":
            return dict(res=self.raw, gate=self.gate, out_raw=self.raw, **silu)
        if k in ("ad_down", "ad_mid"):
            return dict(**silu)
        if k == "out":
            return dict(euler=(self.xs, self.stem_in, 0.5))
        if k in ("upmerge", "uconv"):
            return dict(out_raw=self.raw, **silu)
        return dict(out_raw=self.raw)   # stem, down, dconv

    def launch(self, cfg: tuple):
        kind, co = self.kind, self.co
        hc = kind in ("c1", "c2", "ad_mid", "out") and self.k == 3 and self.stride == 1
        bn = cfg[2] if hc else cfg[1]
        np_ = C.round_up(co, bn) if co > 1 else bn
        kw = self._outs()
        if kind == "uconv":
            bn = cfg[2]
            np_ = C.round_up(co, bn)
            if self.fp8:
                pk = ("u8", np_)
                if pk not in self._packs:
                    w = torch.randn(co, 4 * self.ci + co, device=self.dev) * 0.03
                    codes, sc = C8.e4m3_rows(w, np_)
                    self._packs[pk] = (torch.cat([codes] * 4).contiguous(), torch.cat([sc] * 4).contiguous())
                w, sc = self._packs[pk]
                return C8.make_uconv8(self.x, w, sc, cfg, x2=self.skip8, **kw)
            pk = ("u", np_)
            if pk not in self._packs:
                self._packs[pk] = C._finish(torch.randn(4 * (4 * self.ci + co), co, device=self.dev) * 0.03, np_)
            return C.make_uconv(self.x, self._packs[pk], torch.zeros(np_, device=self.dev), cfg, x2=self.skip16, **kw)
        if kind == "dconv":
            np_ = C.round_up(co, cfg[2])
            if self.fp8:
                pk = ("d8", np_)
                if pk not in self._packs:
                    self._packs[pk] = C8.pack_hconv8(self.wt, np_)
                w, sc = self._packs[pk]
                return C8.make_dconv8(self.x, w, sc, cfg, **kw)
            pk = ("d", np_)
            if pk not in self._packs:
                self._packs[pk] = C.pack_hconv(self.wt, np_)
            return C.make_dconv(self.x, self._packs[pk], torch.zeros(np_, device=self.dev), cfg, **kw)
        if self.fp8:
            if kind == "upmerge":
                pk = ("um8", np_)
                if pk not in self._packs:
                    w = torch.randn(co, 4 * self.ci + co, device=self.dev) * 0.03
                    codes, sc = C8.e4m3_rows(w, np_)
                    self._packs[pk] = (torch.cat([codes] * 4).contiguous(), torch.cat([sc] * 4).contiguous())
                w, sc = self._packs[pk]
                return C8.make_conv8(self.x, w, sc, cfg, mode=1, x2=self.skip8, **kw)
            pk = ("h8" if hc else "c8", np_)
            if pk not in self._packs:
                self._packs[pk] = C8.pack_hconv8(self.wt, np_) if hc else C8.pack_conv8(self.wt, np_)
            w, sc = self._packs[pk]
            if hc:
                return C8.make_hconv8(self.x, w, sc, cfg, **kw)
            return C8.make_conv8(self.x, w, sc, cfg, ks=self.k, stride=self.stride, **kw)
        if kind == "upmerge":
            pk = ("um", np_)
            if pk not in self._packs:
                self._packs[pk] = C._finish(torch.randn(4 * (4 * self.ci + co), co, device=self.dev) * 0.03, np_)
            return C.make_conv(self.x, self._packs[pk], torch.zeros(np_, device=self.dev), cfg, mode=1, x2=self.skip16,
                               **kw)
        pk = ("h" if hc else "c", np_)
        if pk not in self._packs:
            self._packs[pk] = C.pack_hconv(self.wt, np_) if hc else C.pack_conv(self.wt, np_)
        b = torch.zeros(np_, device=self.dev)
        if hc:
            return C.make_hconv(self.x, self._packs[pk], b, cfg, **kw)
        return C.make_conv(self.x, self._packs[pk], b, cfg, ks=self.k, stride=self.stride, **kw)

    def candidates(self, quick: bool) -> list:
        kind, co = self.kind, self.co
        if kind == "uconv":
            return tile_cands(co, self.ho, C8.uconv8_valid if self.fp8 else C.uconv_valid, quick, True)
        if kind == "dconv":
            return tile_cands(co, self.ho, C8.dconv8_valid if self.fp8 else C.dconv_valid, quick, False)
        hc = kind in ("c1", "c2", "ad_mid", "out") and self.k == 3 and self.stride == 1
        if hc:
            cands = hconv_cands(max(co, 16) if co > 1 else 16, self.ho, self.fp8, quick)
            if co == 1:
                cands = [c for c in cands if c[2] in (16, 32)]
            return cands
        return conv_cands(co, self.fp8, quick)


def _time(lcs: list, reps: int, inner: int = 3, gap: float = 0.002) -> list[float]:
    """Interleaved medians (us); a short sleep per round keeps the card near its burst clock."""
    ts = [[] for _ in lcs]
    for lc in lcs:
        lc()
    torch.cuda.synchronize()
    for _ in range(reps):
        for i, lc in enumerate(lcs):
            e0, e1 = torch.cuda.Event(enable_timing=True), torch.cuda.Event(enable_timing=True)
            e0.record()
            for _ in range(inner):
                lc()
            e1.record()
            e1.synchronize()
            ts[i].append(e0.elapsed_time(e1) * 1000 / inner)
        time.sleep(gap)
    # a low percentile, not the median: other jobs on the card only ever ADD time (2026-11-19 tuning ran next to other
    # agents' jobs; medians picked configs that lost in the quiet window)
    return [float(np.percentile(t, 20)) for t in ts]


GROUP = 8      # kernel instantiations per NVRTC program
WORKERS = 8    # parallel NVRTC compiles (processes)


def _compile_group(job) -> None:
    """Worker: compile one group of instantiations into the kernel cache under the exact key native.CudaModule uses."""
    name, names, cc = job
    src = native._read_source(native.CSRC / f"{name}.cu")
    names = sorted(set(names))
    key = native._digest(src, names, (), cc, "nvrtc-v2", native.nvrtc_tag())
    cdir = native.cache_dir()
    bin_path, meta_path = cdir / f"{name}-{key}.bin", cdir / f"{name}-{key}.json"
    if bin_path.exists() and meta_path.exists():
        return
    image, lowered, _ = native._compile_cubin(src, name, names, [], cc)
    native._write_atomic(bin_path, image)
    native._write_atomic(meta_path, json.dumps(lowered).encode())


def _bind_one_module(lcs: list, dev, key) -> None:
    """Candidates compiled in groups of GROUP instantiations, WORKERS NVRTC processes in parallel, then loaded."""
    import multiprocessing as mp
    from concurrent.futures import ProcessPoolExecutor
    cc = torch.cuda.get_device_capability(dev)
    by = {}
    for lc in lcs:
        by.setdefault(getattr(lc, "source", "conv"), []).append(lc)
    jobs, groups = [], []
    for src, group in by.items():
        names = sorted({lc.name for lc in group})
        for i in range(0, len(names), GROUP):
            jobs.append((src, names[i:i + GROUP], cc))
    with ProcessPoolExecutor(WORKERS, mp_context=mp.get_context("spawn")) as ex:
        list(ex.map(_compile_group, jobs))
    mods = {}
    for src, names, _ in jobs:
        mod = native.cuda_module(src, names, dev)
        for n in names:
            mods[n] = mod
    for lc in lcs:
        lc.module = mods[lc.name]


def tune_layer(layer: Layer, current, quick: bool, log=print) -> tuple:
    cands = layer.candidates(quick)
    if current is not None and tuple(current) not in cands:
        cands.append(tuple(current))
    lcs, ok = [], []
    for cfg in cands:
        try:
            lcs.append(layer.launch(cfg))
            ok.append(cfg)
        except AssertionError:
            pass
    _bind_one_module(lcs, layer.dev, layer.key)
    t = _time(lcs, 3 if quick else 4)
    order = sorted(range(len(ok)), key=lambda i: t[i])[:12]
    if current is not None and ok.index(tuple(current)) not in order:
        order.append(ok.index(tuple(current)))
    fine = _time([lcs[i] for i in order], 30)
    best = order[int(np.argmin(fine))]
    cur_t = fine[order.index(ok.index(tuple(current)))] if current is not None else float("nan")
    log(f"{layer.key}: best {ok[best]} {min(fine):.1f} us (current {current} {cur_t:.1f} us; {len(ok)} candidates)")
    return ok[best], min(fine), cur_t


def _layer_keys(model_dir: Path, dev, which: str) -> dict:
    """{(key, fp8): current config} for every layer the shipping samplers build (one 512^2 window, all experts)."""
    from terrain_slm.world import generator as G
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8, qconfig_of
    ck = torch.load(model_dir / "detail.pt", map_location=dev, weights_only=False)
    m = G._detail_model(ck["config"]).to(dev).eval()
    m.load_state_dict(ck["model"])
    n = G.DETAIL_EXT
    keys = {}
    with torch.no_grad():
        if which in ("both", "fp16"):
            s = NativeDetail2Sampler(m, n, n, 2, G.DETAIL_T_START)
            for key, mm, co, mode, kd in s.layer_keys:
                keys[(tuple(key), False)] = None
            del s
        if which in ("both", "fp8") and (model_dir / "detail_fp8.pt").exists():
            ck8 = torch.load(model_dir / "detail_fp8.pt", map_location=dev, weights_only=False)
            st = copy.deepcopy(m)
            st.load_state_dict(ck8["model"])
            st.fp8_qconfig = qconfig_of(ck8)
            s8 = NativeDetail2SamplerFP8(st, n, n, 2, G.DETAIL_T_START)
            for e in s8.layer_keys:
                key = tuple(e[0])
                keys[(key, str(key[0]).endswith("8"))] = None
            del s8
    gpu = torch.cuda.get_device_name(dev)
    t16 = C.TUNED.get(gpu, {})
    t8 = C8_table().get(gpu, {})
    for (key, fp8) in list(keys):
        keys[(key, fp8)] = (t8 if fp8 else t16).get(key)
    return keys


def C8_table() -> dict:
    return C.load_tables(TABLE8)


def main(argv=None) -> int:
    from terrain_slm.paths import MODEL_DIR
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--model", default=str(MODEL_DIR / "checkpoints" / "exp2-r3"))
    ap.add_argument("--only", choices=("both", "fp16", "fp8"), default="both")
    ap.add_argument("--kinds", default="")
    ap.add_argument("--ho", default="", help="only layers with these output / low-res sizes (e.g. 512,256)")
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--dry", action="store_true", help="time, but do not write the tables")
    args = ap.parse_args(argv)
    dev = torch.device(args.device)
    torch.cuda.set_device(dev)
    gpu = torch.cuda.get_device_name(dev)
    keys = _layer_keys(Path(args.model), dev, args.only)
    kinds = set(args.kinds.split(",")) if args.kinds else None
    best16, best8, saved = {}, {}, 0.0
    for (key, fp8), cur in sorted(keys.items(), key=lambda kv: (kv[0][1], str(kv[0][0]))):
        kind = key[0][:-1] if fp8 else key[0]
        if kinds and kind not in kinds:
            continue
        if args.ho and key[5] not in {int(v) for v in args.ho.split(",")}:
            continue
        layer = Layer(key, fp8, dev)
        cfg, t, tc = tune_layer(layer, cur, args.quick, log=lambda m: print(m, flush=True))
        (best8 if fp8 else best16)[json.dumps(list(key))] = list(cfg)
        if tc == tc:
            saved += tc - t
        del layer
        torch.cuda.empty_cache()
        if not args.dry:   # written after every layer: an interrupted run keeps what it measured
            path = TABLE8 if fp8 else TABLE16
            raw = json.loads(path.read_text()) if path.exists() else {}
            raw.setdefault(gpu, {})[json.dumps(list(key))] = list(cfg)
            raw.setdefault("_cc", {})[gpu] = list(torch.cuda.get_device_capability(dev))
            path.write_text(json.dumps(raw, indent=1))
    print(f"sum over layer shapes (one launch each): {saved:.1f} us saved vs the current table; "
          f"{len(best16)} fp16 + {len(best8)} FP8 entries{'' if args.dry else ' written'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
