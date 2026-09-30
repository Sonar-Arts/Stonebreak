"""Fused implicit-GEMM convolution (Triton) for the flow-matching UNets (detail, relief).

One kernel does the whole of a UNet layer's glue as well as its convolution, so an activation
makes one trip through memory instead of five:

  prologue   input read straight from NHWC bf16 -- optionally the NEAREST 2x UPSAMPLE of a
             half-resolution tensor (the index is halved on load, the upsampled tensor never
             exists; `upconv` goes further and runs it as four 2x2 phase convolutions, 4 taps
             instead of 9) and optionally a SECOND input concatenated along channels (the skip merge)
  mainloop   KS x KS taps x input channels as one pipelined K loop, bf16 tensor cores, fp32 acc
  epilogue   + bias, FiLM (x * (1 + scale) + shift, per-step table), + residual, then the raw
             value and/or its SiLU stored as bf16 (the next layer's input is always silu(x), so
             it is written here instead of by a separate pass), or -- for the output conv -- the
             Euler update x += dt * v fused in, writing the new state into the stem's input.

Layouts: activations NHWC contiguous bf16; weights packed once as (KS*KS*Cin, Cout) bf16, row
= tap * Cin + channel (see `pack_weight`). Every config is fixed per layer shape (never timed
at run time), so a window's numbers never depend on which process or request computed it.
"""
from __future__ import annotations

import torch
import triton
import triton.language as tl


@triton.jit
def _conv_kernel(
    x1, x2, w, bias, film, res, out_raw, out_silu, xs, stem_in,
    M, Hi, Wi, Ho, Wo, C1, C2, CO, dt,
    KS: tl.constexpr, STRIDE: tl.constexpr, UP1: tl.constexpr, HAS_X2: tl.constexpr,
    HAS_FILM: tl.constexpr, HAS_RES: tl.constexpr, STORE_RAW: tl.constexpr, STORE_SILU: tl.constexpr,
    EULER: tl.constexpr, STEM_C: tl.constexpr,
    BLOCK_M: tl.constexpr, BLOCK_N: tl.constexpr, BLOCK_K: tl.constexpr,
):
    pid = tl.program_id(0)
    num_n = tl.cdiv(CO, BLOCK_N)
    # N fastest: the programs sharing an M block run together and re-read its input from L2.
    pid_m = pid // num_n
    pid_n = pid % num_n

    offs_m = pid_m * BLOCK_M + tl.arange(0, BLOCK_M)
    offs_n = pid_n * BLOCK_N + tl.arange(0, BLOCK_N)
    offs_k = tl.arange(0, BLOCK_K)
    m_ok = offs_m < M
    n_ok = offs_n < CO
    hw = Ho * Wo
    b = offs_m // hw
    r = offs_m % hw
    ho = r // Wo
    wo = r % Wo
    PAD: tl.constexpr = KS // 2
    ctot = C1 + C2
    if UP1:
        h1 = Hi // 2
        w1 = Wi // 2
    else:
        h1 = Hi
        w1 = Wi

    acc = tl.zeros((BLOCK_M, BLOCK_N), dtype=tl.float32)
    # Input 1: one flat K loop over (tap, channel block) so the pipeliner sees a long loop.
    kc1 = C1 // BLOCK_K
    for it in range(0, KS * KS * kc1):
        tap = it // kc1
        c0 = (it % kc1) * BLOCK_K
        kh = tap // KS
        kw = tap % KS
        hi = ho * STRIDE + kh - PAD
        wi = wo * STRIDE + kw - PAD
        ok = m_ok & (hi >= 0) & (hi < Hi) & (wi >= 0) & (wi < Wi)
        if UP1:
            hi = hi // 2
            wi = wi // 2
        row = ((b * h1 + hi) * w1 + wi) * C1 + c0
        a = tl.load(x1 + row[:, None] + offs_k[None, :], mask=ok[:, None], other=0.0)
        wt = tl.load(w + (tap * ctot + c0 + offs_k)[:, None] * CO + offs_n[None, :], mask=n_ok[None, :], other=0.0)
        acc = tl.dot(a, wt, acc)
    if HAS_X2:
        kc2 = C2 // BLOCK_K
        for it in range(0, KS * KS * kc2):
            tap = it // kc2
            c0 = (it % kc2) * BLOCK_K
            kh = tap // KS
            kw = tap % KS
            hi = ho * STRIDE + kh - PAD
            wi = wo * STRIDE + kw - PAD
            ok = m_ok & (hi >= 0) & (hi < Hi) & (wi >= 0) & (wi < Wi)
            row = ((b * Hi + hi) * Wi + wi) * C2 + c0
            a = tl.load(x2 + row[:, None] + offs_k[None, :], mask=ok[:, None], other=0.0)
            wt = tl.load(w + (tap * ctot + C1 + c0 + offs_k)[:, None] * CO + offs_n[None, :],
                         mask=n_ok[None, :], other=0.0)
            acc = tl.dot(a, wt, acc)

    acc += tl.load(bias + offs_n, mask=n_ok, other=0.0)[None, :]
    if HAS_FILM:  # film = [1 + scale (CO), shift (CO)], fp32, this step's row
        acc = acc * tl.load(film + offs_n, mask=n_ok, other=1.0)[None, :] + tl.load(film + CO + offs_n, mask=n_ok, other=0.0)[None, :]
    mn = offs_m[:, None] * CO + offs_n[None, :]
    mask = m_ok[:, None] & n_ok[None, :]
    if HAS_RES:
        acc += tl.load(res + mn, mask=mask, other=0.0).to(tl.float32)
    if STORE_RAW:
        tl.store(out_raw + mn, acc.to(tl.bfloat16), mask=mask)
    if STORE_SILU:
        tl.store(out_silu + mn, (acc * tl.sigmoid(acc)).to(tl.bfloat16), mask=mask)
    if EULER:  # CO == 1: x += dt * v (fp32 state), and the new x is the stem's input channel 0
        v = tl.sum(tl.where((offs_n == 0)[None, :], acc, 0.0), axis=1)
        x = tl.load(xs + offs_m, mask=m_ok, other=0.0) + dt * v
        tl.store(xs + offs_m, x, mask=m_ok)
        tl.store(stem_in + offs_m * STEM_C, x.to(tl.bfloat16), mask=m_ok)


@triton.jit
def _upconv_kernel(x, w, bias, out, M, H, W, C, CO,
                   BLOCK_M: tl.constexpr, BLOCK_N: tl.constexpr, BLOCK_K: tl.constexpr):
    """conv3x3(nearest_upsample_2x(x)) without the upsample and with 4 taps instead of 9: output
    phase (py, px) of low-res pixel (i, j) is a 2x2 conv of x at rows i-1+py.., cols j-1+px..
    with the collapsed taps' weights pre-summed (`pack_up_weight`). x (B, H, W, C) -> out (B, 2H, 2W, CO)."""
    pid = tl.program_id(0)
    num_n = tl.cdiv(CO, BLOCK_N)
    num_m = tl.cdiv(M, BLOCK_M)
    pid_n = pid % num_n
    pid_m = (pid // num_n) % num_m
    phase = pid // (num_n * num_m)
    py = phase // 2
    px = phase % 2
    offs_m = pid_m * BLOCK_M + tl.arange(0, BLOCK_M)
    offs_n = pid_n * BLOCK_N + tl.arange(0, BLOCK_N)
    offs_k = tl.arange(0, BLOCK_K)
    m_ok = offs_m < M
    n_ok = offs_n < CO
    hw = H * W
    b = offs_m // hw
    r = offs_m % hw
    i = r // W
    j = r % W
    acc = tl.zeros((BLOCK_M, BLOCK_N), dtype=tl.float32)
    kc = C // BLOCK_K
    for it in range(0, 4 * kc):
        tap = it // kc
        c0 = (it % kc) * BLOCK_K
        si = i + tap // 2 - 1 + py
        sj = j + tap % 2 - 1 + px
        ok = m_ok & (si >= 0) & (si < H) & (sj >= 0) & (sj < W)
        row = ((b * H + si) * W + sj) * C + c0
        a = tl.load(x + row[:, None] + offs_k[None, :], mask=ok[:, None], other=0.0)
        wt = tl.load(w + ((phase * 4 + tap) * C + c0 + offs_k)[:, None] * CO + offs_n[None, :],
                     mask=n_ok[None, :], other=0.0)
        acc = tl.dot(a, wt, acc)
    acc += tl.load(bias + offs_n, mask=n_ok, other=0.0)[None, :]
    orow = ((b * 2 * H + 2 * i + py) * 2 * W + 2 * j + px) * CO
    tl.store(out + orow[:, None] + offs_n[None, :], acc.to(tl.bfloat16), mask=m_ok[:, None] & n_ok[None, :])


# Which 3x3 taps (kh or kw) each phase's 2x2 tap collects: phase 0 reads source offset -1 with
# tap 0 and offset 0 with taps 1+2; phase 1 reads offset 0 with taps 0+1 and offset +1 with tap 2.
_UP_TAPS = (((0,), (1, 2)), ((0, 1), (2,)))


def pack_up_weight(weight: torch.Tensor) -> torch.Tensor:
    """3x3 conv weight (Cout, Cin, 3, 3) -> the 4 phases' 2x2 weights, (4 * 4 * Cin, Cout) bf16,
    row = (phase * 4 + tap) * Cin + c. Collapsed taps are summed in fp32, then rounded once."""
    co, ci, kh, kw = weight.shape
    assert kh == kw == 3
    wf = weight.detach().float()
    w = torch.zeros(2, 2, 2, 2, ci, co, device=weight.device, dtype=torch.float32)   # py px ty tx c o
    for py in range(2):
        for px in range(2):
            for ty in range(2):
                for tx in range(2):
                    for a in _UP_TAPS[py][ty]:
                        for b in _UP_TAPS[px][tx]:
                            w[py, px, ty, tx] += wf[:, :, a, b].t()
    return w.reshape(16 * ci, co).to(torch.bfloat16).contiguous()


UP_CONFIGS: dict[tuple, tuple] = {   # (C, Cout, H) -> config, like CONFIGS
    (192, 96, 256): (128, 128, 64, 4, 2),
    (64, 32, 224): (128, 32, 32, 8, 3),
    (288, 192, 128): (128, 256, 32, 8, 3),
    (96, 64, 112): (64, 64, 32, 4, 3),
    (384, 288, 64): (64, 256, 64, 4, 3),
    (128, 96, 56): (64, 64, 64, 4, 3),
    (512, 384, 32): (64, 64, 32, 4, 4),
}


def upconv(x: torch.Tensor, w: torch.Tensor, bias: torch.Tensor, out: torch.Tensor, config: tuple | None = None) -> None:
    """out (B, 2H, 2W, CO) = conv3x3(nearest_upsample_2x(x)) + bias; w from `pack_up_weight`."""
    bsz, h, wd, c = x.shape
    co = w.shape[1]
    m = bsz * h * wd
    bm, bn, bk, nw, ns = config or UP_CONFIGS.get((c, co, h)) or _default_config(c, 0, co, 2, m)
    assert c % bk == 0, (c, bk)
    grid = (4 * triton.cdiv(m, bm) * triton.cdiv(co, bn),)
    with torch.cuda.device(x.device):
        _upconv_kernel[grid](x, w, bias, out, m, h, wd, c, co, BLOCK_M=bm, BLOCK_N=bn, BLOCK_K=bk,
                             num_warps=nw, num_stages=ns)


def pack_weight(weight: torch.Tensor, cin_pad: int | None = None) -> torch.Tensor:
    """torch conv weight (Cout, Cin, KS, KS) -> (KS*KS*Cin_pad, Cout) bf16, row = tap * Cin_pad + c.
    Padded input channels get zero rows (the stem's 23 channels run as 32)."""
    co, ci, kh, kw = weight.shape
    cp = cin_pad or ci
    w = torch.zeros(kh, kw, cp, co, device=weight.device, dtype=torch.float32)
    w[:, :, :ci] = weight.detach().float().permute(2, 3, 1, 0)
    return w.reshape(kh * kw * cp, co).to(torch.bfloat16).contiguous()


# Fixed launch configs, (BLOCK_M, BLOCK_N, BLOCK_K, num_warps, num_stages), keyed by
# (C1, C2, Cout, KS, stride, Ho). Tuned offline on an RTX PRO 6000 (sm_120) by
# scripts/tune_kernels.py; shapes missing here use `_default_config`. Fixed so numerics are stable.
CONFIGS: dict[tuple, tuple] = {
    (32, 0, 96, 3, 1, 512): (128, 64, 32, 4, 3),
    (96, 0, 96, 3, 1, 512): (256, 128, 32, 8, 4),
    (96, 96, 96, 1, 1, 512): (128, 128, 32, 8, 4),
    (96, 0, 1, 3, 1, 512): (256, 16, 32, 8, 3),
    (32, 0, 32, 3, 1, 448): (128, 32, 32, 4, 3),
    (32, 32, 32, 3, 1, 448): (128, 32, 32, 8, 4),
    (32, 0, 1, 3, 1, 448): (128, 16, 32, 4, 3),
    (96, 0, 192, 3, 2, 256): (128, 64, 32, 4, 3),
    (192, 0, 192, 3, 1, 256): (128, 64, 64, 8, 3),
    (192, 192, 192, 1, 1, 256): (128, 64, 32, 8, 3),
    (32, 0, 64, 3, 2, 224): (128, 64, 32, 4, 3),
    (64, 0, 64, 3, 1, 224): (128, 64, 64, 4, 3),
    (64, 64, 64, 3, 1, 224): (128, 64, 32, 4, 4),
    (192, 0, 288, 3, 2, 128): (256, 64, 32, 4, 2),
    (288, 0, 288, 3, 1, 128): (64, 128, 32, 4, 3),
    (288, 288, 288, 1, 1, 128): (64, 128, 32, 4, 3),
    (64, 0, 96, 3, 2, 112): (256, 32, 32, 8, 4),
    (96, 0, 96, 3, 1, 112): (128, 32, 32, 4, 3),
    (96, 96, 96, 3, 1, 112): (256, 32, 32, 4, 4),
    (288, 0, 384, 3, 2, 64): (64, 64, 32, 4, 4),
    (384, 0, 384, 3, 1, 64): (64, 128, 64, 4, 3),
    (384, 384, 384, 1, 1, 64): (64, 128, 64, 4, 3),
    (96, 0, 128, 3, 2, 56): (64, 128, 32, 4, 3),
    (128, 0, 128, 3, 1, 56): (64, 64, 32, 4, 3),
    (384, 0, 512, 3, 2, 32): (64, 64, 64, 4, 4),
    (512, 0, 512, 3, 1, 32): (64, 64, 64, 4, 3),
}


def _default_config(c1: int, c2: int, cout: int, ks: int, m: int) -> tuple:
    bn = 16 if cout <= 16 else 32 if cout <= 32 else 64 if cout <= 96 else 128
    bk = 64 if (c1 | c2) % 64 == 0 else 32 if (c1 | c2) % 32 == 0 else 16
    bm = 128 if m >= 128 * 256 else 64
    return bm, bn, bk, 4, 3


def conv(x1: torch.Tensor, w: torch.Tensor, bias: torch.Tensor, *, ks: int = 3, stride: int = 1,
         up1: bool = False, x2: torch.Tensor | None = None, film: torch.Tensor | None = None,
         res: torch.Tensor | None = None, out_raw: torch.Tensor | None = None,
         out_silu: torch.Tensor | None = None, euler: tuple | None = None, config: tuple | None = None) -> None:
    """Launch one fused conv. x1 (B, H1, W1, C1) NHWC bf16 (half the conv grid when `up1`); x2
    (B, Hi, Wi, C2) or None; w from `pack_weight`; outputs preallocated NHWC bf16 (B, Ho, Wo, CO).
    `euler` = (xs fp32 (B*Ho*Wo), stem_in NHWC bf16 (B, Ho, Wo, STEM_C), dt) for the output conv."""
    bsz, h1, w1, c1 = x1.shape
    hi, wi = (2 * h1, 2 * w1) if up1 else (h1, w1)
    c2 = 0 if x2 is None else x2.shape[3]
    co = w.shape[1]
    ho, wo = (hi + stride - 1) // stride, (wi + stride - 1) // stride
    m = bsz * ho * wo
    cfg = config or CONFIGS.get((c1, c2, co, ks, stride, ho)) or _default_config(c1, c2, co, ks, m)
    bm, bn, bk, nw, ns = cfg
    assert c1 % bk == 0 and c2 % bk == 0, (c1, c2, bk)
    dummy = bias
    xs, stem_in, dt = euler if euler is not None else (dummy, dummy, 0.0)
    grid = (triton.cdiv(m, bm) * triton.cdiv(co, bn),)
    # Triton launches on the *current* device; the service runs the model on cuda:1.
    with torch.cuda.device(x1.device):
        _launch_conv(grid, x1, x2, w, bias, film, res, out_raw, out_silu, xs, stem_in, dummy, m, hi, wi, ho, wo,
                     c1, c2, co, dt, ks, stride, up1, euler, bm, bn, bk, nw, ns)


def _launch_conv(grid, x1, x2, w, bias, film, res, out_raw, out_silu, xs, stem_in, dummy, m, hi, wi, ho, wo,
                 c1, c2, co, dt, ks, stride, up1, euler, bm, bn, bk, nw, ns):
    _conv_kernel[grid](
        x1, x2 if x2 is not None else x1, w, bias,
        film if film is not None else dummy, res if res is not None else dummy,
        out_raw if out_raw is not None else dummy, out_silu if out_silu is not None else dummy,
        xs, stem_in,
        m, hi, wi, ho, wo, c1, c2, co, float(dt),
        KS=ks, STRIDE=stride, UP1=up1, HAS_X2=x2 is not None, HAS_FILM=film is not None,
        HAS_RES=res is not None, STORE_RAW=out_raw is not None, STORE_SILU=out_silu is not None,
        EULER=euler is not None, STEM_C=stem_in.shape[-1] if euler is not None else 1,
        BLOCK_M=bm, BLOCK_N=bn, BLOCK_K=bk, num_warps=nw, num_stages=ns,
    )
