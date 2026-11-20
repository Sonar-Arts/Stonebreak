"""Python side of csrc/conv_fp8.cu: e4m3 weight packing, launch configs and prepared launches (the FP8 twin of
cuda_conv.py; same `Launch` objects, so a sampler mixes fp16 and FP8 launches in one CUDA graph).

Packing (fp32 / fp64 in, e4m3 codes out, one scale per output channel -- `quant/detail2_sim.weight_codes`'s rule):
  pack_hconv8   (CO, C, 3, 3) -> (C/32, NP, 9, 32) bytes
  pack_conv8    (CO, C, k, k) -> (NP, k*k*C) bytes, row = output channel, k = tap * C + c
  pack_rows8    (CO, K) rows already in conv8 order (the up-merge's phase blocks)
Every packer returns (codes uint8, scale fp32 (NP,)); padded channels have code 0, scale 0.
"""
from __future__ import annotations

import ctypes

import torch

from terrain_slm.kernels import cuda_conv as C
from terrain_slm.kernels.cuda_conv import Launch, ptr, round_up

E4M3_MAX = 448.0
F8_SC, F8_FILM, F8_RES, F8_RAW, F8_SILU, F8_EULER = 1, 2, 4, 8, 16, 32
F8_RSCALE, F8_GATE, F8_CLIP, F8_SILU8, F8_RAW8 = 64, 128, 256, 512, 1024
SRC = "conv_fp8"


class Epi8(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("sc", "film", "res", "out_raw", "out_silu", "out_silu8", "out_raw8",
                                                "xs", "stem_in", "gate")] + [
        (n, ctypes.c_float) for n in ("rs", "qs", "qs_raw", "dt")] + [
        (n, ctypes.c_int32) for n in ("CO", "flags", "stem_c")]


class HConv8Args(ctypes.Structure):
    _fields_ = [("x", ctypes.c_uint64), ("w", ctypes.c_uint64), ("e", Epi8)] + [
        (n, ctypes.c_int32) for n in ("B", "H", "W", "C", "NP")]


class Conv8Args(ctypes.Structure):
    _fields_ = [("x1", ctypes.c_uint64), ("x2", ctypes.c_uint64), ("w", ctypes.c_uint64), ("e", Epi8),
                ("w_phase", ctypes.c_int64)] + [
        (n, ctypes.c_int32) for n in ("M", "Hg", "Wg", "H1", "W1", "C1", "H2", "W2", "C2", "NP", "K", "Ho", "Wo")]


class PixNorm8Args(ctypes.Structure):
    _fields_ = [("raw", ctypes.c_uint64), ("out_silu8", ctypes.c_uint64), ("P", ctypes.c_int32), ("C", ctypes.c_int32),
                ("inv_sqrt_c", ctypes.c_float), ("qs", ctypes.c_float)]


class Quant8Args(ctypes.Structure):
    _fields_ = [("x", ctypes.c_uint64), ("y", ctypes.c_uint64), ("n8", ctypes.c_int64), ("qs", ctypes.c_float)]


# ----------------------------------------------------------------------------- packing
def e4m3_rows(w: torch.Tensor, np_: int) -> tuple[torch.Tensor, torch.Tensor]:
    """w (CO, K) fp32/fp64 -> (codes (NP, K) uint8 e4m3 bits, scale (NP,) fp32), per-row amax / 448."""
    co, k = w.shape
    w = w.float()
    amax = w.abs().amax(1).clamp_min(1e-12)
    scale = amax / E4M3_MAX
    q = (w / scale[:, None]).clamp(-E4M3_MAX, E4M3_MAX).to(torch.float8_e4m3fn)
    codes = torch.zeros(np_, k, device=w.device, dtype=torch.uint8)
    codes[:co] = q.view(torch.uint8)
    sc = torch.zeros(np_, device=w.device, dtype=torch.float32)
    sc[:co] = scale
    return codes.contiguous(), sc


def pack_conv8(weight: torch.Tensor, np_: int) -> tuple[torch.Tensor, torch.Tensor]:
    co, c, kh, kw = weight.shape
    assert c % 32 == 0, c
    return e4m3_rows(weight.permute(0, 2, 3, 1).reshape(co, kh * kw * c), np_)    # k = tap * C + c


def pack_hconv8(weight: torch.Tensor, np_: int) -> tuple[torch.Tensor, torch.Tensor]:
    """(CO, C, 3, 3) -> codes (C/32, NP, 9, 32) bytes, scale (NP,). The scale is per output channel over all chunks."""
    co, c = weight.shape[:2]
    assert c % 32 == 0 and weight.shape[2:] == (3, 3), weight.shape
    rows, sc = pack_conv8(weight, np_)                                     # (NP, 9 * C), k = tap * C + c
    codes = rows.view(np_, 9, c // 32, 32).permute(2, 0, 1, 3).contiguous()   # (C/32, NP, 9, 32)
    return codes, sc


def dequant(codes: torch.Tensor) -> torch.Tensor:
    return codes.view(torch.float8_e4m3fn).float()


# ----------------------------------------------------------------------------- configs
# hconv8: (TH, TW, BN, WM, WN, ST); conv8: (BM, BN, WM, WN, ST). Configs never change numbers (fixed K order).
def hconv8_smem(cfg: tuple) -> int:
    th, tw, bn, wm, wn, st = cfg
    pipeline = st * ((th + 2) * (tw + 2) * 32 + bn * 304)
    return max(pipeline, wm * wn * 16 * (bn // wn + 4) * 4)


def hconv8_valid(cfg: tuple) -> bool:
    th, tw, bn, wm, wn, st = cfg
    bm, nt = th * tw, wm * wn * 32
    if tw % 8 or bm % wm or bn % wn or (bm // wm) % 16 or (bn // wn) % 16 or nt > 512:
        return False
    if (bm // wm // 16) * (bn // wn // 8) * 4 > 128:
        return False
    return hconv8_smem(cfg) <= 99 * 1024


def default_hconv8_config(co: int, h: int) -> tuple:
    if co % 96 == 0 and co >= 192 and h >= 128:
        return 16, 16, 96, 4, 2, 2
    bn = 48 if co % 48 == 0 and co % 32 else 32 if co % 32 == 0 else 16
    if h >= 64:
        return 8, 32, bn, 8, 1, 2
    return 8, 16, bn, 8, 1, 3


def conv8_smem(cfg: tuple) -> int:
    bm, bn, wm, wn, st = cfg
    return max(st * (bm + bn) * 32, wm * wn * 16 * (bn // wn + 4) * 4)


def conv8_valid(cfg: tuple) -> bool:
    bm, bn, wm, wn, st = cfg
    nt = wm * wn * 32
    if bm % wm or bn % wn or (bm // wm) % 16 or (bn // wn) % 16 or nt > 512:
        return False
    if (bm // wm // 16) * (bn // wn // 8) * 4 > 128:
        return False
    return conv8_smem(cfg) <= 99 * 1024


def default_conv8_config(m: int, co: int, mode: int) -> tuple:
    bm, bn, wm, wn, _ = C.default_config(m, co, mode, 0)
    return bm, bn, wm, wn, 4


def hconv8_name(cfg: tuple) -> str:
    return "hconv8_kernel<{},{},{},{},{},{}>".format(*cfg)


def conv8_name(cfg: tuple, mode: int, ks: int, stride: int) -> str:
    return "conv8_kernel<{},{},{},{},{},{},{},{}>".format(*cfg, mode, ks, stride)


# ----------------------------------------------------------------------------- launches
def _epi(co: int, sc=None, film=None, res=None, out_raw=None, out_silu=None, out_silu8=None, out_raw8=None,
         euler=None, gate=None, res_scale=None, clip=False, qs: float = 1.0, qs_raw: float = 1.0) -> tuple[Epi8, tuple]:
    xs, stem_in, dt = euler if euler is not None else (None, None, 0.0)
    flags = ((F8_SC if sc is not None else 0) | (F8_FILM if film is not None else 0) | (F8_RES if res is not None else 0)
             | (F8_RAW if out_raw is not None else 0) | (F8_SILU if out_silu is not None else 0)
             | (F8_SILU8 if out_silu8 is not None else 0) | (F8_RAW8 if out_raw8 is not None else 0)
             | (F8_EULER if euler is not None else 0) | (F8_GATE if gate is not None else 0)
             | (F8_RSCALE if res_scale is not None else 0) | (F8_CLIP if clip else 0))
    for t, dt_ in ((out_raw, torch.float16), (out_silu, torch.float16), (res, torch.float16), (out_silu8, torch.uint8),
                   (out_raw8, torch.uint8)):
        assert t is None or (t.dtype == dt_ and t.is_contiguous()), (t.dtype, dt_)
    e = Epi8(sc=ptr(sc), film=ptr(film), res=ptr(res), out_raw=ptr(out_raw), out_silu=ptr(out_silu),
             out_silu8=ptr(out_silu8), out_raw8=ptr(out_raw8), xs=ptr(xs), stem_in=ptr(stem_in), gate=ptr(gate),
             rs=float(res_scale or 0.0), qs=float(qs), qs_raw=float(qs_raw), dt=float(dt), CO=co, flags=flags,
             stem_c=stem_in.shape[-1] if stem_in is not None else 1)
    return e, (sc, film, res, out_raw, out_silu, out_silu8, out_raw8, xs, stem_in, gate)


def _co(euler, *outs) -> int:
    if euler is not None:
        return 1
    return next(t for t in outs if t is not None).shape[3]


def make_hconv8(x: torch.Tensor, w: torch.Tensor, sc: torch.Tensor, cfg: tuple, *, film=None, res=None, out_raw=None,
                out_silu=None, out_silu8=None, out_raw8=None, euler=None, res_scale=None, gate=None, clip=False,
                qs: float = 1.0, qs_raw: float = 1.0) -> Launch:
    """Halo-tiled 3x3 stride-1 FP8 conv: x (B, H, W, C) uint8 e4m3 codes, (w, sc) from `pack_hconv8` (sc already
    multiplied by the input's activation scale)."""
    b, h, wd, c = x.shape
    assert x.dtype == torch.uint8 and x.is_contiguous() and c % 32 == 0, (x.dtype, x.shape)
    np_ = w.shape[1]
    assert w.shape == (c // 32, np_, 9, 32) and np_ % cfg[2] == 0, (w.shape, cfg)
    co = _co(euler, out_raw, out_silu, out_silu8, out_raw8)
    e, keep = _epi(co, sc, film, res, out_raw, out_silu, out_silu8, out_raw8, euler, gate, res_scale, clip, qs, qs_raw)
    args = HConv8Args(x=ptr(x), w=ptr(w), e=e, B=b, H=h, W=wd, C=c, NP=np_)
    th, tw, bn = cfg[:3]
    blocks = b * -(-h // th) * -(-wd // tw) * (np_ // bn)
    lc = Launch(hconv8_name(cfg), (blocks, 1, 1), cfg[3] * cfg[4] * 32, hconv8_smem(cfg), args)
    lc._keep = (x, w) + keep
    lc.source = SRC
    return lc


def make_conv8(x1: torch.Tensor, w: torch.Tensor, sc: torch.Tensor, cfg: tuple, *, mode: int = 0, ks: int = 3,
               stride: int = 1, x2: torch.Tensor | None = None, film=None, res=None, out_raw=None, out_silu=None,
               out_silu8=None, out_raw8=None, euler=None, res_scale=None, gate=None, clip=False, qs: float = 1.0,
               qs_raw: float = 1.0) -> Launch:
    """Implicit-GEMM FP8 conv. x1 (B, H1, W1, C1) uint8; w (NP, K) uint8 (MODE 1: 4 phase blocks, (4 * NP, K));
    sc (NP,) (MODE 1: (4 * NP,)). MODE 1: x1 low-res, output (B, 2H1, 2W1, CO), x2 = skip."""
    b, h1, w1, c1 = x1.shape
    assert x1.dtype == torch.uint8 and x1.is_contiguous() and c1 % 32 == 0, (x1.dtype, x1.shape)
    np_ = w.shape[0] // (4 if mode == 1 else 1)
    kb = w.shape[1]
    assert np_ % cfg[1] == 0, (np_, cfg)
    if mode == 0:
        hg, wg = (h1 + stride - 1) // stride, (w1 + stride - 1) // stride
        ho, wo, nz = hg, wg, 1
    else:
        hg, wg, ho, wo, nz = h1, w1, 2 * h1, 2 * w1, 4
    c2 = 0 if x2 is None else x2.shape[3]
    h2, w2 = (0, 0) if x2 is None else x2.shape[1:3]
    if x2 is not None:
        assert x2.dtype == torch.uint8 and x2.is_contiguous() and c2 % 32 == 0
    taps0 = 4 if mode == 1 else ks * ks
    taps1 = 1 if mode == 1 else ks * ks
    assert kb == taps0 * c1 + (taps1 * c2 if x2 is not None else 0), (w.shape, c1, c2)
    co = _co(euler, out_raw, out_silu, out_silu8, out_raw8)
    e, keep = _epi(co, sc, film, res, out_raw, out_silu, out_silu8, out_raw8, euler, gate, res_scale, clip, qs, qs_raw)
    m = b * hg * wg
    args = Conv8Args(x1=ptr(x1), x2=ptr(x2), w=ptr(w), e=e, w_phase=np_ * kb, M=m, Hg=hg, Wg=wg, H1=h1, W1=w1, C1=c1,
                     H2=h2, W2=w2, C2=c2, NP=np_, K=kb, Ho=ho, Wo=wo)
    grid = (((m + cfg[0] - 1) // cfg[0]) * (np_ // cfg[1]), 1, nz)
    lc = Launch(conv8_name(cfg, mode, ks if mode == 0 else 3, stride if mode == 0 else 1), grid,
                cfg[2] * cfg[3] * 32, conv8_smem(cfg), args)
    lc._keep = (x1, x2, w) + keep
    lc.source = SRC
    return lc


def make_pixnorm8(raw: torch.Tensor, out_silu8: torch.Tensor, qs: float = 1.0) -> Launch:
    b, h, w, c = raw.shape
    assert raw.dtype == torch.float16 and out_silu8.dtype == torch.uint8 and out_silu8.shape == raw.shape and c % 8 == 0
    cpl = -(-c // 256)
    p = b * h * w
    args = PixNorm8Args(raw=ptr(raw), out_silu8=ptr(out_silu8), P=p, C=c, inv_sqrt_c=float((1.0 / c) ** 0.5), qs=float(qs))
    lc = Launch(f"pixnorm8_kernel<{cpl}>", (-(-p // 8), 1, 1), 256, 0, args)
    lc._keep = (raw, out_silu8)
    lc.source = SRC
    return lc


def make_quant8(x: torch.Tensor, y: torch.Tensor, qs: float = 1.0) -> Launch:
    assert x.dtype == torch.float16 and y.dtype == torch.uint8 and x.numel() == y.numel() and x.numel() % 8 == 0
    n8 = x.numel() // 8
    args = Quant8Args(x=ptr(x), y=ptr(y), n8=n8, qs=float(qs))
    lc = Launch("quant8_kernel", (-(-n8 // 256), 1, 1), 256, 0, args)
    lc._keep = (x, y)
    lc.source = SRC
    return lc


# ----------------------------------------------------------------------------- halo-tiled FP8 up-merge (uconv8_kernel)
def uconv8_smem(cfg: tuple) -> int:
    th, tw, bn, wm, wn, st = cfg
    ar = max((th + 1) * (tw + 1), th * tw)
    return max(st * (ar * 32 + 4 * bn * 32), wm * wn * 16 * (bn // wn + 4) * 4)


def uconv8_valid(cfg: tuple) -> bool:
    th, tw, bn, wm, wn, st = cfg
    bm, nt = th * tw, wm * wn * 32
    if tw % 8 or bm % wm or bn % wn or (bm // wm) % 16 or (bn // wn) % 16 or nt > 512 or st < 2:
        return False
    if (bm // wm // 16) * (bn // wn // 8) * 4 > 128:
        return False
    return uconv8_smem(cfg) <= 99 * 1024


def default_uconv8_config(c: int, lo: int, lo_w: int | None = None) -> tuple | None:
    """An untuned GPU's config, or None when the low-resolution grid (lo x lo_w) does not tile by 8 (the sampler then
    keeps the generation-1 MODE 1 kernel for that layer -- a fixed rule per shape)."""
    lo_w = lo if lo_w is None else lo_w
    if lo % 8 or lo_w % 8:
        return None
    bn = 96 if c % 96 == 0 else 64 if c % 64 == 0 else 32 if c % 32 == 0 else 16
    wn = 1 if bn == 16 else 2
    tw = 32 if lo_w % 32 == 0 else 16 if lo_w % 16 == 0 else 8
    return (8, tw, bn, tw // 4, wn, 2)


def make_uconv8(x1: torch.Tensor, w: torch.Tensor, sc: torch.Tensor, cfg: tuple, *, x2: torch.Tensor, out_raw=None,
                out_silu=None, out_silu8=None, out_raw8=None, qs: float = 1.0, qs_raw: float = 1.0) -> Launch:
    """Halo-tiled FP8 up-merge (kernel type "uconv8"): make_conv8(mode=1)'s contract with a chunk-major K order."""
    b, h1, w1, c1 = x1.shape
    th, tw, bn = cfg[:3]
    assert x1.dtype == torch.uint8 and x1.is_contiguous() and c1 % 32 == 0 and h1 % th == 0 and w1 % tw == 0
    assert x2.dtype == torch.uint8 and x2.is_contiguous() and x2.shape[1:3] == (2 * h1, 2 * w1) and x2.shape[3] % 32 == 0
    np_ = w.shape[0] // 4
    kb = w.shape[1]
    c2 = x2.shape[3]
    assert np_ % bn == 0 and kb == 4 * c1 + c2, (w.shape, c1, c2)
    co = _co(None, out_raw, out_silu, out_silu8, out_raw8)
    e, keep = _epi(co, sc, None, None, out_raw, out_silu, out_silu8, out_raw8, None, None, None, False, qs, qs_raw)
    m = b * h1 * w1
    args = Conv8Args(x1=ptr(x1), x2=ptr(x2), w=ptr(w), e=e, w_phase=np_ * kb, M=m, Hg=h1, Wg=w1, H1=h1, W1=w1, C1=c1,
                     H2=2 * h1, W2=2 * w1, C2=c2, NP=np_, K=kb, Ho=2 * h1, Wo=2 * w1)
    tiles = np_ // bn
    lc = Launch("uconv8_kernel<{},{},{},{},{},{}>".format(*cfg), ((m // (th * tw)) * tiles, 1, 4), cfg[3] * cfg[4] * 32,
                uconv8_smem(cfg), args)
    lc.scale = {"field": "M", "rule": "ceil", "tile": th * tw, "tiles": tiles}
    lc._keep = (x1, x2, w) + keep
    lc.source = SRC
    return lc


# ----------------------------------------------------------------------------- halo-tiled stride-2 FP8 (dconv8_kernel)
def dconv8_smem(cfg: tuple) -> int:
    th, tw, bn, wm, wn, st = cfg
    hn = (2 * th + 1) * (2 * tw + 1)
    return max(st * (hn * 32 + bn * 304), wm * wn * 16 * (bn // wn + 4) * 4)


def dconv8_valid(cfg: tuple) -> bool:
    th, tw, bn, wm, wn, st = cfg
    bm, nt = th * tw, wm * wn * 32
    if tw % 8 or bm % wm or bn % wn or (bm // wm) % 16 or (bn // wn) % 16 or nt > 512 or st < 2:
        return False
    if (bm // wm // 16) * (bn // wn // 8) * 4 > 128:
        return False
    return dconv8_smem(cfg) <= 99 * 1024


def default_dconv8_config(co: int, ho: int) -> tuple:
    bn = 96 if co % 96 == 0 else 64 if co % 64 == 0 else 32
    return (8, 16, bn, 4, 2, 2) if ho % 16 == 0 else (8, 8, bn, 4, 2, 2)


def make_dconv8(x: torch.Tensor, w: torch.Tensor, sc: torch.Tensor, cfg: tuple, *, out_raw=None, out_silu=None,
                out_silu8=None, out_raw8=None, qs: float = 1.0, qs_raw: float = 1.0) -> Launch:
    """Halo-tiled stride-2 3x3 FP8 conv (kernel type "dconv8"): x (B, H, W, C) e4m3 -> (B, H/2, W/2, CO); (w, sc) from
    `pack_hconv8`."""
    b, h, wd, c = x.shape
    assert x.dtype == torch.uint8 and x.is_contiguous() and c % 32 == 0 and h % 2 == 0 and wd % 2 == 0
    np_ = w.shape[1]
    th, tw, bn = cfg[:3]
    assert w.shape == (c // 32, np_, 9, 32) and np_ % bn == 0, (w.shape, cfg)
    co = _co(None, out_raw, out_silu, out_silu8, out_raw8)
    e, keep = _epi(co, sc, None, None, out_raw, out_silu, out_silu8, out_raw8, None, None, None, False, qs, qs_raw)
    args = HConv8Args(x=ptr(x), w=ptr(w), e=e, B=b, H=h, W=wd, C=c, NP=np_)
    blocks = b * -(-(h // 2) // th) * -(-(wd // 2) // tw) * (np_ // bn)
    lc = Launch("dconv8_kernel<{},{},{},{},{},{}>".format(*cfg), (blocks, 1, 1), cfg[3] * cfg[4] * 32, dconv8_smem(cfg),
                args)
    lc._keep = (x, w) + keep
    lc.source = SRC
    return lc
