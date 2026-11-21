"""FP8 (e4m3) kernels (kernels/csrc/conv_fp8.cu, kernels/cuda_conv_fp8.py) and the FP8 Detail2 sampler
(kernels/cuda_detail2_fp8.py) against the kernel-exact simulation (quant/detail2_sim.py).

  * every kernel against torch on dequantised codes: products of e4m3 codes are exact in fp32, so the only
    difference is the fp32 summation order and the fp16 / e4m3 rounding of the stored result;
  * the sampler on a small random Detail2 with experts vs the simulation with the same layer formats.

Needs an sm_89+ CUDA device ($TGM_TEST_DEVICE, default cuda:0)."""
import os

import pytest
import torch
import torch.nn.functional as F

from terrain_slm import kernels as K

DEV = os.environ.get("TGM_TEST_DEVICE", "cuda:0")
pytestmark = pytest.mark.skipif(not K.available(DEV) or torch.cuda.get_device_capability(DEV) < (8, 9),
                                reason="needs an sm_89+ CUDA device (and TGM_KERNELS not 0)")


@pytest.fixture(autouse=True)
def _no_tf32():
    old = torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = False
    yield
    torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32 = old


def _codes(x: torch.Tensor, qs: float) -> torch.Tensor:
    return (x * qs).clamp(-448, 448).to(torch.float8_e4m3fn).view(torch.uint8).contiguous()


def _deq(c: torch.Tensor) -> torch.Tensor:
    return c.view(torch.float8_e4m3fn).float()


def _nchw(x):
    return x.permute(0, 3, 1, 2)


def _nhwc(x):
    return x.permute(0, 2, 3, 1).contiguous()


@pytest.mark.parametrize("h,w,c,co", [(24, 40, 32, 32), (33, 17, 96, 64), (16, 16, 64, 96)])
def test_hconv8_matches_dequantised_conv(h, w, c, co):
    from terrain_slm.kernels import cuda_conv as C, cuda_conv_fp8 as C8
    g = torch.Generator(device=DEV).manual_seed(h * c)
    x = torch.randn(2, h, w, c, device=DEV, generator=g)
    wt = torch.randn(co, c, 3, 3, device=DEV, generator=g) / (9 * c) ** 0.5
    film = torch.cat([torch.rand(co, device=DEV, generator=g) + 0.5, torch.zeros(co, device=DEV)])
    res = torch.randn(2, h, w, co, device=DEV, generator=g).half()
    gate = torch.rand(2 * h * w, device=DEV, generator=g)
    xq = _codes(x, 8.0)
    np_ = C.round_up(co, 32)
    wc, sc = C8.pack_hconv8(wt, np_)
    raw = torch.zeros(2, h, w, co, device=DEV, dtype=torch.float16)
    s16 = torch.zeros_like(raw)
    s8 = torch.zeros(2, h, w, co, device=DEV, dtype=torch.uint8)
    r8 = torch.zeros_like(s8)
    for cfg in [(8, 16, 32, 8, 1, 2), (16, 16, 32, 4, 2, 3)]:
        lc = C8.make_hconv8(xq, wc, sc / 8.0, cfg, film=film, res=res, res_scale=0.7, gate=gate, clip=True,
                            out_raw=raw, out_silu=s16, out_silu8=s8, out_raw8=r8, qs=4.0, qs_raw=2.0)
        C.bind([lc], DEV)
        lc()
        wd = _deq(wc.permute(1, 2, 0, 3).reshape(np_, 9, c)[:co]).reshape(co, 3, 3, c).permute(0, 3, 1, 2)
        v = F.conv2d(_nchw(_deq(xq) / 8.0), wd * sc[:co, None, None, None], padding=1)
        v = _nhwc(v) * film[:co] * gate.view(2, h, w, 1) + 0.7 * res.float()
        v = v.clamp(-256, 256)
        tol = 2e-3 * float(v.abs().max())
        assert float((raw.float() - v).abs().max()) < tol, cfg
        assert float((s16.float() - F.silu(v)).abs().max()) < tol, cfg
        # e4m3 stores: the kernel rounds its own fp32 value; allow one e4m3 step of the reference
        assert float((_deq(s8) / 4.0 - F.silu(v)).abs().max()) <= 0.07 * float(F.silu(v).abs().max()), cfg
        assert float((_deq(r8) / 2.0 - v).abs().max()) <= 0.07 * float(v.abs().max()), cfg


@pytest.mark.parametrize("ks,stride,c,co", [(3, 2, 32, 64), (1, 1, 96, 32), (3, 1, 64, 96)])
def test_conv8_mode0(ks, stride, c, co):
    from terrain_slm.kernels import cuda_conv as C, cuda_conv_fp8 as C8
    g = torch.Generator(device=DEV).manual_seed(ks * 100 + co)
    h, w = 21, 30
    x = torch.randn(1, h, w, c, device=DEV, generator=g)
    wt = torch.randn(co, c, ks, ks, device=DEV, generator=g) / (ks * ks * c) ** 0.5
    xq = _codes(x, 8.0)
    np_ = C.round_up(co, 32)
    wc, sc = C8.pack_conv8(wt, np_)
    ho, wo = (h + stride - 1) // stride, (w + stride - 1) // stride
    raw = torch.zeros(1, ho, wo, co, device=DEV, dtype=torch.float16)
    for cfg in [(64, 32, 2, 2, 4), (128, 32, 4, 1, 3)]:
        lc = C8.make_conv8(xq, wc, sc / 8.0, cfg, ks=ks, stride=stride, out_raw=raw)
        C.bind([lc], DEV)
        lc()
        wd = _deq(wc[:co]).reshape(co, ks, ks, c).permute(0, 3, 1, 2) * sc[:co, None, None, None]
        v = _nhwc(F.conv2d(_nchw(_deq(xq) / 8.0), wd, stride=stride, padding=ks // 2))
        assert float((raw.float() - v).abs().max()) < 2e-3 * float(v.abs().max()), cfg


def test_conv8_upmerge_phases():
    """MODE 1: 4 phase 2x2 convs over the low-res input + one skip tap, per-phase scales."""
    from terrain_slm.kernels import cuda_conv as C, cuda_conv_fp8 as C8
    g = torch.Generator(device=DEV).manual_seed(7)
    h, w, clow, c = 9, 14, 64, 32
    x = torch.randn(1, h, w, clow, device=DEV, generator=g)
    skip = torch.randn(1, 2 * h, 2 * w, c, device=DEV, generator=g)
    ph = torch.randn(4, c, 4 * clow + c, device=DEV, generator=g) * 0.05     # per phase: rows (tap * clow + ci), skip
    codes, scs = zip(*[C8.e4m3_rows(ph[p], c) for p in range(4)])
    wq, sc = torch.cat(codes), torch.cat(scs)
    xq, sq = _codes(x, 8.0), _codes(skip, 8.0)
    out = torch.zeros(1, 2 * h, 2 * w, c, device=DEV, dtype=torch.float16)
    lc = C8.make_conv8(xq, wq, sc / 8.0, (64, 32, 2, 2, 4), mode=1, x2=sq, out_raw=out)
    C.bind([lc], DEV)
    lc()
    xd, sd = _deq(xq) / 8.0, _deq(sq) / 8.0
    ref = torch.zeros(1, 2 * h, 2 * w, c, device=DEV)
    xp = F.pad(xd, (0, 0, 1, 1, 1, 1))
    for p in range(4):
        py, px = p >> 1, p & 1
        wd = _deq(codes[p]) * scs[p][:, None]
        acc = torch.zeros(1, h, w, c, device=DEV)
        for tap in range(4):
            ty, tx = tap >> 1, tap & 1
            acc += xp[:, py + ty: py + ty + h, px + tx: px + tx + w] @ wd[:, tap * clow: (tap + 1) * clow].t()
        acc += sd[:, py::2, px::2] @ wd[:, 4 * clow:].t()
        ref[:, py::2, px::2] = acc
    assert float((out.float() - ref).abs().max()) < 2e-3 * float(ref.abs().max())


def test_pixnorm8_and_quant8():
    from terrain_slm.kernels import cuda_conv as C, cuda_conv_fp8 as C8
    from terrain_slm.models.mp import pixel_norm
    x = (torch.randn(1, 13, 11, 288, device=DEV) * 3).half()
    raw, s8 = x.clone(), torch.zeros(x.shape, device=DEV, dtype=torch.uint8)
    lc = C8.make_pixnorm8(raw, s8, qs=8.0)
    q = torch.zeros_like(s8)
    lq = C8.make_quant8(x, q, qs=2.0)
    C.bind([lc, lq], DEV)
    lc()
    lq()
    ref = _nhwc(pixel_norm(_nchw(x.float())))
    assert float((raw.float() - ref).abs().max()) <= 2e-3 * float(ref.abs().max())
    assert torch.equal(s8, _codes(F.silu(raw.float()), 8.0)) or \
        float((_deq(s8) / 8 - F.silu(ref)).abs().max()) <= 0.07 * float(F.silu(ref).abs().max())
    assert torch.equal(q, _codes(x.float(), 2.0))


# ----------------------------------------------------------------------------- the FP8 sampler
def _small():
    import test_cuda_detail2 as T
    from terrain_slm.models import detail2 as D2
    old = T.DEV
    T.DEV = DEV
    try:
        m = T._small_model()
    finally:
        T.DEV = old
    return m, D2


@pytest.mark.parametrize("keep", [("stem",), ("stem", "down0", "enc1.0.c0", "up0", "out", "ad.undersea.enc0.1.down",
                                              "dec1.0.c1", "up1")])
def test_fp8_sampler_matches_simulation(keep):
    """The kernels compute the simulation's numbers: same error vs fp32 as the simulation (to a few %), graph == eager,
    deterministic; their mutual difference is e4m3 code flips from the fp32 summation order (chaotic in this random
    net, so it is bounded loosely), far from the fp32 error."""
    from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8
    from terrain_slm.quant import detail2_sim as Q
    import test_cuda_detail2 as T
    m, D2 = _small()
    gd = torch.Generator(device=DEV).manual_seed(5)
    h, w = 64, 96
    cond = torch.randn(1, D2.N_COND, h, w, device=DEV, generator=gd)
    noise = torch.randn(1, 1, h, w, device=DEV, generator=gd)
    gates = torch.zeros(1, len(D2.STYLES), h, w, device=DEV)
    gates[:, D2.STYLES.index("aeolian"), :, : w // 2] = torch.rand(h, w // 2, device=DEV, generator=gd)
    gates[:, D2.STYLES.index("undersea"), h // 3:] = 0.7
    qc = Q.QConfig(default="e4m3", layers={k: "fp16" for k in keep}, act_scale=0.125)
    ns = NativeDetail2SamplerFP8(m, h, w, 2, qc=qc)
    rms = lambda a, b: float((a - b).pow(2).mean().sqrt())
    for gt in (None, gates):
        got = ns(cond, noise, gt)
        sim = Q.Detail2Sim(m, qc).sample(cond, noise, 2, gates=gt)
        ref = T.fp32_sample(m, cond, noise, 2, gates=gt)
        e_k, e_s = rms(got, ref), rms(sim, ref)
        assert torch.isfinite(got).all()
        assert abs(e_k - e_s) < 0.15 * e_s, (e_k, e_s)
        assert rms(got, sim) < 0.9 * e_s, (rms(got, sim), e_s)
        assert torch.equal(ns.eager(cond, noise, gt), got)
        assert torch.equal(ns(cond, noise, gt), got)


# ----------------------------------------------------------------------------- real windows (the FP8 shipping gate)
ROOT = __import__("pathlib").Path(__file__).resolve().parents[1]
STUDENT = ROOT / "checkpoints" / "exp2" / "qad_fp8_v4k8" / "student.pt"   # keep-8, the most accurate student


@pytest.mark.skipif(not STUDENT.exists() or not (ROOT / "checkpoints/exp2/world_exp2b/detail.pt").exists(),
                    reason="needs the QAD student checkpoints/exp2/qad_fp8_v4k8 and world_exp2b")
def test_real_windows_fp8_gate():
    """FP8 kernels on the generator's own windows vs the fp32 TEACHER: median and max no worse than the torch bf16 path
    (the pre-native shipping numerics), stable (|x| < 7). Run with -s for the numbers."""
    import numpy as np
    import test_cuda_detail2 as T
    from terrain_slm.eval.detail_ab import load
    from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8, qconfig_of
    from terrain_slm.models import detail as DT
    from terrain_slm.models import detail2 as D2
    old = T.DEV
    T.DEV = DEV
    try:
        windows, teacher = T.capture_windows(device=DEV)
    finally:
        T.DEV = old
    student, ck = load(STUDENT, DEV)
    student.mean_flow = True
    student.eval()
    qc = qconfig_of(ck)
    c0 = windows[0][0]
    ns = NativeDetail2SamplerFP8(student, c0.shape[-2], c0.shape[-1], windows[0][3], windows[0][4], qc=qc)
    dec = lambda x: DT.decode(x.clamp(-7.0, 7.0))
    e8, e16, mx = [], [], 0.0
    for cond, noise, gates, st, t0 in windows:
        ref = T.fp32_sample(teacher, cond, noise, st, t0, gates=gates)
        x8 = ns(cond, noise, gates)
        auto = D2.sample(teacher, cond, noise, steps=st, t_start=t0, mean_flow=True, gates=gates)
        e8.append(float((dec(x8) - dec(ref)).pow(2).mean().sqrt()))
        e16.append(float((dec(auto) - dec(ref)).pow(2).mean().sqrt()))
        mx = max(mx, float(x8.nan_to_num(1e9).abs().max()))
    s = {"fp8_median": float(np.median(e8)), "fp8_max": max(e8), "bf16_median": float(np.median(e16)),
         "bf16_max": max(e16), "max_abs_x": mx}
    print("\nfp8 gate", s)
    assert s["fp8_median"] <= s["bf16_median"] and s["fp8_max"] <= s["bf16_max"], s
    assert mx < 7.0, s


@pytest.mark.skipif(not (ROOT / "checkpoints/exp2/world_exp2e/detail_fp8.pt").exists(), reason="needs world_exp2e")
def test_generator_falls_back_to_fp16_without_fp8_tensor_cores(monkeypatch, capsys):
    """DETAIL_FP8 on, but a GPU without FP8 tensor cores (faked sm_86): native fp16 with its own model_id (no :kd8),
    logged once on stderr; with the capability the student runs on the FP8 sampler (:kd8)."""
    from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    from terrain_slm.world import generator2 as G2
    monkeypatch.setattr(G2, "DETAIL_FP8", True)
    monkeypatch.setattr(G2, "_LOGGED", set())
    model = ROOT / "checkpoints/exp2/world_exp2e"
    on = G2.WorldGenerator2(model, 1, DEV)
    assert on._detail2_native is NativeDetail2SamplerFP8 and ":kd8-" in on.model_id
    del on
    real = torch.cuda.get_device_capability
    monkeypatch.setattr(torch.cuda, "get_device_capability", lambda d=None: (8, 6))
    off = G2.WorldGenerator2(model, 1, DEV)
    monkeypatch.setattr(torch.cuda, "get_device_capability", real)
    assert off._detail2_native is NativeDetail2Sampler and ":kd1" in off.model_id and ":kd8-" not in off.model_id
    err = capsys.readouterr().err
    assert "FP8 detail sampler on" in err and "sm_86 has no FP8 tensor cores" in err
