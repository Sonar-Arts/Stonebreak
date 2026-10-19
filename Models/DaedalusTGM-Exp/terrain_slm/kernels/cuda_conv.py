"""Python side of csrc/conv.cu: weight packing, launch configs and prepared launches.

A `Launch` holds one kernel launch with every pointer and size resolved (the buffers of a sampler
never move), so a sampler step is a list of `cuLaunchKernel` calls -- recorded once into a CUDA graph.

Packing (all in fp32/fp64, rounded to fp16 ONCE):
  pack_conv     (CO, Cin, k, k) -> (k*k*Cin_pad, NP), row = tap * Cin_pad + c
  pack_concat   conv over cat([x1, x2]) -> segment-major rows: x1's taps, then x2's taps
  pack_up       conv3x3(nearest_up(x)) -> 4 phases x (4 taps * C) rows (collapsed taps summed)
  pack_upmerge  merge1x1(cat([up(x), skip])) folded into the up weights: per phase 4 taps of
                (Wmerge_a @ Wup) plus one skip tap of Wmerge_b; bias Wmerge_a @ b_up + b_merge
"""
from __future__ import annotations

import ctypes

import torch

from terrain_slm.kernels import native

BK = 32
F_FILM, F_RES, F_RAW, F_SILU, F_EULER = 1, 2, 4, 8, 16


class ConvArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in
                ("x1", "x2", "w", "bias", "film", "res", "out_raw", "out_silu", "xs", "stem_in")] + [
        ("w_phase", ctypes.c_int64),
        *[(n, ctypes.c_int32) for n in ("M", "Hg", "Wg", "H1", "W1", "C1", "H2", "W2", "C2", "CO", "NP",
                                         "Ho", "Wo", "stem_c", "flags")],
        ("dt", ctypes.c_float),
    ]


def round_up(x: int, m: int) -> int:
    return (x + m - 1) // m * m


# ----------------------------------------------------------------------------- packing
def _to_rows(w: torch.Tensor) -> torch.Tensor:
    """(CO, C, kh, kw) fp32 -> (kh*kw*C, CO): row = tap * C + c."""
    co, c, kh, kw = w.shape
    return w.permute(2, 3, 1, 0).reshape(kh * kw * c, co)


def _finish(rows: torch.Tensor, np_: int) -> torch.Tensor:
    out = torch.zeros(rows.shape[0], np_, device=rows.device, dtype=torch.float32)
    out[:, : rows.shape[1]] = rows
    return out.to(torch.float16).contiguous()


def pad_bias(b: torch.Tensor, np_: int) -> torch.Tensor:
    out = torch.zeros(np_, device=b.device, dtype=torch.float32)
    out[: b.numel()] = b.detach().float()
    return out


def pack_conv(weight: torch.Tensor, np_: int, cin_pad: int | None = None) -> torch.Tensor:
    co, ci, kh, kw = weight.shape
    w = weight.detach().float()
    if cin_pad and cin_pad > ci:
        w = torch.cat([w, torch.zeros(co, cin_pad - ci, kh, kw, device=w.device)], dim=1)
    return _finish(_to_rows(w), np_)


def pack_concat(weight: torch.Tensor, c1: int, np_: int) -> torch.Tensor:
    w = weight.detach().float()
    return _finish(torch.cat([_to_rows(w[:, :c1]), _to_rows(w[:, c1:])]), np_)


# Which 3x3 taps (along one axis) each phase's 2x2 tap collects: phase 0 reads source offset -1 with
# tap 0 and offset 0 with taps 1+2; phase 1 reads offset 0 with taps 0+1 and offset +1 with tap 2.
_UP_TAPS = (((0,), (1, 2)), ((0, 1), (2,)))


def _phase_weights(w: torch.Tensor) -> torch.Tensor:
    """3x3 weights (CO, C, 3, 3), float64 -> (4 phases, 4 taps, C, CO): collapsed taps summed."""
    co, c = w.shape[:2]
    out = torch.zeros(2, 2, 2, 2, c, co, device=w.device, dtype=torch.float64)
    for py in range(2):
        for px in range(2):
            for ty in range(2):
                for tx in range(2):
                    for a in _UP_TAPS[py][ty]:
                        for b in _UP_TAPS[px][tx]:
                            out[py, px, ty, tx] += w[:, :, a, b].t()
    return out.reshape(4, 4, c, co)


def pack_up(weight: torch.Tensor, np_: int) -> torch.Tensor:
    """-> (4 * 4 * C, NP): phase-major blocks of 4*C rows."""
    ph = _phase_weights(weight.detach().double())
    c, co = ph.shape[2:]
    return _finish(ph.reshape(16 * c, co).float(), np_)


def pack_upmerge(up: torch.nn.Conv2d, merge: torch.nn.Conv2d, np_: int) -> tuple[torch.Tensor, torch.Tensor]:
    """Weights (4 * (4*Clow + C), NP) and bias (NP,) of merge(cat([up(x), skip])) for a 1x1 merge."""
    c = merge.out_channels
    assert merge.kernel_size == (1, 1) and merge.in_channels == 2 * c and up.out_channels == c
    wm = merge.weight.detach().double()[:, :, 0, 0]          # (C, 2C)
    wa, wb = wm[:, :c], wm[:, c:]
    wu = up.weight.detach().double()                         # (C, Clow, 3, 3)
    comp = torch.einsum("oc,cikl->oikl", wa, wu)             # (C, Clow, 3, 3)
    ph = _phase_weights(comp)                                # (4, 4, Clow, C)
    clow = wu.shape[1]
    skip = wb.t()                                            # (C_skip, C)
    blocks = [torch.cat([ph[p].reshape(4 * clow, c), skip]) for p in range(4)]
    w = _finish(torch.cat(blocks).float(), np_)
    bias = wa @ up.bias.detach().double() + merge.bias.detach().double()
    return w, pad_bias(bias.float(), np_)


# ----------------------------------------------------------------------------- configs
# (BM, BN, WM, WN, STAGES). The result never depends on the config (fixed K order, no split-K), so
# configs only change speed: TUNED (scripts/tune_cuda_kernels.py writes cuda_configs.json, per GPU
# name; data, not source, so retuning leaves tile caches alone) or `default_config`.
def _load_tuned() -> dict[str, dict[tuple, tuple]]:
    import json
    from pathlib import Path
    path = Path(__file__).resolve().parent / "cuda_configs.json"
    if not path.exists():
        return {}
    raw = json.loads(path.read_text())
    return {gpu: {tuple(json.loads(k)): tuple(v) for k, v in table.items()} for gpu, table in raw.items()}


TUNED: dict[str, dict[tuple, tuple]] = _load_tuned()


def default_config(m: int, co: int, mode: int, kdim: int) -> tuple:
    if co <= 16:
        return 128, 16, 4, 1, 3
    if co % 96 == 0 and co <= 288:
        bn = 96
    elif co % 128 == 0:
        bn = 128
    elif co % 64 == 0:
        bn = 64
    elif co % 32 == 0:
        bn = 32
    else:
        bn = 16
    big = m * (co // bn) >= 188 * 8 * (2 if mode == 0 else 1)
    if big and m >= 65536:
        return 256, bn, 4, 2, 3
    if m >= 16384:
        return 128, bn, 2, 2, 3
    return 64, bn, 2, 2, 3


def smem_bytes(cfg: tuple) -> int:
    bm, bn, wm, wn, st = cfg
    bstr = bn + 8 if (bn // 8) % 2 == 0 else bn
    pipeline = st * (bm * BK + BK * bstr) * 2
    staging = wm * wn * 16 * (bn // wn + 4) * 4     # the epilogue's per-warp fp32 rows (reuses the pipeline's)
    return max(pipeline, staging)


def kernel_name(cfg: tuple, mode: int, ks: int, stride: int) -> str:
    bm, bn, wm, wn, st = cfg
    return f"conv_kernel<{bm},{bn},{wm},{wn},{st},{mode},{ks},{stride}>"


def valid_config(cfg: tuple) -> bool:
    bm, bn, wm, wn, st = cfg
    nt = wm * wn * 32
    if bm % wm or bn % wn or (bm // wm) % 16 or (bn // wn) % 16 or (bm * 4) % nt:
        return False
    if (bm // wm // 16) * (bn // wn // 8) * 4 > 128:      # accumulator registers per thread
        return False
    return smem_bytes(cfg) <= 99 * 1024 and nt <= 512


class Launch:
    """One prepared launch of csrc/conv.cu."""

    def __init__(self, name: str, grid: tuple, block: int, smem: int, args: ConvArgs):
        self.name, self.grid, self.block, self.smem, self.args = name, grid, block, smem, args
        self.module = None

    def __call__(self, stream=None) -> None:
        self.module.launch(self.name, self.grid, (self.block,), self.args, self.smem, stream)


def ptr(t: torch.Tensor | None) -> int:
    return 0 if t is None else t.data_ptr()


def make_conv(x1: torch.Tensor, w: torch.Tensor, bias: torch.Tensor, cfg: tuple, *, mode: int = 0, ks: int = 3,
              stride: int = 1, x2: torch.Tensor | None = None, film: torch.Tensor | None = None,
              res: torch.Tensor | None = None, out_raw: torch.Tensor | None = None,
              out_silu: torch.Tensor | None = None, co: int | None = None,
              euler: tuple | None = None) -> Launch:
    """Prepare one launch. x1 (B, H1, W1, C1) NHWC fp16; w packed (rows, NP) fp16; outputs NHWC fp16
    (B, Ho, Wo, CO). MODE 1: x1 is the low-res input, output (B, 2H1, 2W1, CO), x2 = skip (or None).
    `euler` = (xs fp32 (B*Ho*Wo,), stem_in NHWC fp16, dt) for the output conv (CO = 1)."""
    b, h1, w1, c1 = x1.shape
    assert x1.dtype == torch.float16 and x1.is_contiguous() and c1 % BK == 0, (x1.dtype, x1.shape)
    np_ = w.shape[1]
    bm, bn = cfg[0], cfg[1]
    assert np_ % bn == 0, (np_, bn)
    if mode == 0:
        hg, wg = (h1 + stride - 1) // stride, (w1 + stride - 1) // stride
        ho, wo = hg, wg
        nz = 1
    else:
        hg, wg, ho, wo, nz = h1, w1, 2 * h1, 2 * w1, 4
    c2 = 0 if x2 is None else x2.shape[3]
    h2, w2 = (0, 0) if x2 is None else x2.shape[1:3]
    if x2 is not None:
        assert x2.dtype == torch.float16 and x2.is_contiguous() and c2 % BK == 0
    co = co if co is not None else (out_raw if out_raw is not None else out_silu).shape[3]
    flags = ((F_FILM if film is not None else 0) | (F_RES if res is not None else 0)
             | (F_RAW if out_raw is not None else 0) | (F_SILU if out_silu is not None else 0)
             | (F_EULER if euler is not None else 0))
    xs, stem_in, dt = euler if euler is not None else (None, None, 0.0)
    m = b * hg * wg
    taps0 = 4 if mode == 1 else ks * ks
    taps1 = 1 if mode == 1 else ks * ks
    rows = taps0 * c1 + (taps1 * c2 if x2 is not None else 0)
    assert w.shape[0] == rows * (4 if mode == 1 else 1), (w.shape, rows)
    args = ConvArgs(
        x1=ptr(x1), x2=ptr(x2), w=ptr(w), bias=ptr(bias), film=ptr(film), res=ptr(res), out_raw=ptr(out_raw),
        out_silu=ptr(out_silu), xs=ptr(xs), stem_in=ptr(stem_in), w_phase=rows * np_,
        M=m, Hg=hg, Wg=wg, H1=h1, W1=w1, C1=c1, H2=h2, W2=w2, C2=c2, CO=co, NP=np_, Ho=ho, Wo=wo,
        stem_c=stem_in.shape[-1] if stem_in is not None else 1, flags=flags, dt=float(dt))
    grid = (((m + bm - 1) // bm) * (np_ // bn), 1, nz)
    lc = Launch(kernel_name(cfg, mode, ks if mode == 0 else 3, stride if mode == 0 else 1), grid,
                cfg[2] * cfg[3] * 32, smem_bytes(cfg), args)
    lc._keep = (x1, x2, w, bias, film, res, out_raw, out_silu, xs, stem_in)   # pointers stay valid
    return lc


def bind(launches: list[Launch], device) -> None:
    """Compile (once, cached) every kernel variant the launches use and attach the module."""
    # One module per variant: each compiles once and is shared by every sampler, test and tuner run.
    srcs = {lc.name: getattr(lc, "source", "conv") for lc in launches}
    mods = {n: native.cuda_module(src, [n], device) for n, src in sorted(srcs.items())}
    for lc in launches:
        lc.module = mods[lc.name]


# ----------------------------------------------------------------------------- halo-tiled 3x3 (csrc/hconv.cu)
class HConvArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("x", "w", "bias", "film", "res", "out_raw", "out_silu", "xs", "stem_in")] + [
        (n, ctypes.c_int32) for n in ("B", "H", "W", "C", "CO", "NP", "flags", "stem_c")] + [("dt", ctypes.c_float)]


def pack_hconv(weight: torch.Tensor, np_: int) -> torch.Tensor:
    """3x3 weights (CO, C, 3, 3) -> (9 * C, NP) fp16, chunk-major rows: (c // 16) * 144 + tap * 16 + c % 16."""
    co, c = weight.shape[:2]
    assert c % 16 == 0
    w = weight.detach().float().permute(2, 3, 1, 0).reshape(9, c // 16, 16, co)   # tap, chunk, k, n
    return _finish(w.permute(1, 0, 2, 3).reshape(9 * c, co), np_)


def hconv_smem(cfg: tuple) -> int:
    th, tw, bn, wm, wn, st = cfg
    bstr = bn + 8 if (bn // 8) % 2 == 0 else bn
    pipeline = st * ((th + 2) * (tw + 2) * 16 + 144 * bstr) * 2
    return max(pipeline, wm * wn * 16 * (bn // wn + 4) * 4)


def hconv_valid(cfg: tuple) -> bool:
    th, tw, bn, wm, wn, st = cfg
    bm, nt = th * tw, wm * wn * 32
    if tw % 8 or bm % wm or bn % wn or (bm // wm) % 16 or (bn // wn) % 16 or nt > 512:
        return False
    if (bm // wm // 16) * (bn // wn // 8) * 4 > 128:
        return False
    return hconv_smem(cfg) <= 99 * 1024


def default_hconv_config(co: int, h: int) -> tuple:
    """(TH, TW, BN, WM, WN, STAGES) for an untuned GPU (fastest family on sm_120, burst-timed)."""
    if co % 96 == 0 and co >= 192 and h >= 128:
        return 16, 16, 96, 4, 2, 2
    bn = 48 if co % 48 == 0 and co % 32 else 32 if co % 32 == 0 else 16
    if h >= 64:
        return 8, 32, bn, 8, 1, 2
    return 8, 16, bn, 8, 1, 3


def hconv_name(cfg: tuple) -> str:
    return "hconv_kernel<{},{},{},{},{},{}>".format(*cfg)


def make_hconv(x: torch.Tensor, w: torch.Tensor, bias: torch.Tensor, cfg: tuple, *, film=None, res=None,
               out_raw=None, out_silu=None, euler: tuple | None = None) -> Launch:
    """Prepare one halo-tiled 3x3 stride-1 convolution: x (B, H, W, C) NHWC fp16, w from `pack_hconv`.
    `euler` = (xs fp32 (B*H*W,), stem_in NHWC fp16, dt) for the output conv (CO = 1)."""
    b, h, wd, c = x.shape
    assert x.dtype == torch.float16 and x.is_contiguous() and c % 16 == 0, (x.dtype, x.shape)
    th, tw, bn = cfg[:3]
    np_ = w.shape[1]
    assert np_ % bn == 0 and w.shape[0] == 9 * c, (w.shape, cfg)
    co = 1 if euler is not None else (out_raw if out_raw is not None else out_silu).shape[3]
    flags = ((F_FILM if film is not None else 0) | (F_RES if res is not None else 0)
             | (F_RAW if out_raw is not None else 0) | (F_SILU if out_silu is not None else 0)
             | (F_EULER if euler is not None else 0))
    xs, stem_in, dt = euler if euler is not None else (None, None, 0.0)
    args = HConvArgs(x=ptr(x), w=ptr(w), bias=ptr(bias), film=ptr(film), res=ptr(res), out_raw=ptr(out_raw),
                     out_silu=ptr(out_silu), xs=ptr(xs), stem_in=ptr(stem_in), B=b, H=h, W=wd, C=c, CO=co, NP=np_,
                     flags=flags, stem_c=stem_in.shape[-1] if stem_in is not None else 1, dt=float(dt))
    blocks = b * -(-h // th) * -(-wd // tw) * (np_ // bn)
    lc = Launch(hconv_name(cfg), (blocks, 1, 1), cfg[3] * cfg[4] * 32, hconv_smem(cfg), args)
    lc._keep = (x, w, bias, film, res, out_raw, out_silu, xs, stem_in)
    lc.source = "hconv"
    return lc
