"""K4 (2026-11-19) detail-kernel additions: the fp16 epilogue's e4m3 SiLU (F_SILU8, bitwise = the old quant8 pass) and
the generation-2 kernel types of the up-merge (uconv / uconv8)
and the FP8 down conv (dconv8): their own K order, so checked against torch on the same operands and against the
generation-1 kernels within an fp32-summation-order tolerance; the samplers' generation switch.

Needs an sm_89+ CUDA device ($TGM_TEST_DEVICE, default cuda:0) for the FP8 parts."""
import os

import pytest
import torch
import torch.nn.functional as F

from terrain_slm import kernels as K

DEV = os.environ.get("TGM_TEST_DEVICE", "cuda:0")
pytestmark = pytest.mark.skipif(not K.available(DEV), reason="needs a CUDA device (and TGM_KERNELS not 0)")
fp8_only = pytest.mark.skipif(not K.available(DEV) or torch.cuda.get_device_capability(DEV) < (8, 9),
                              reason="needs sm_89+ (FP8 tensor cores)")


@pytest.fixture(autouse=True)
def _no_tf32():
    old = torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = False
    yield
    torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32 = old


def _codes(g, *shape):
    """Random e4m3 codes of both signs, no NaN."""
    x = torch.randint(0, 120, shape, device=DEV, dtype=torch.uint8, generator=g)
    return x ^ (torch.randint(0, 2, shape, device=DEV, dtype=torch.uint8, generator=g) << 7)


def _deq(c):
    return c.view(torch.float8_e4m3fn).float()


@fp8_only
def test_fp16_epilogue_silu8_is_bitwise_quant8():
    """F_SILU8: an fp16 kernel's e4m3 SiLU = quant8(fp16 SiLU) exactly (the FP8 sampler dropped those passes)."""
    from terrain_slm.kernels import cuda_conv as C, cuda_conv_fp8 as C8
    g = torch.Generator(device=DEV).manual_seed(3)
    x = torch.randn(1, 40, 24, 64, device=DEV, generator=g).half()
    wt = torch.randn(96, 64, 3, 3, device=DEV, generator=g) / 24
    w = C.pack_hconv(wt, 96)
    b = torch.zeros(96, device=DEV)
    film = torch.randn(192, device=DEV, generator=g)
    s16 = torch.zeros(1, 40, 24, 96, device=DEV, dtype=torch.float16)
    s8 = torch.zeros(1, 40, 24, 96, device=DEV, dtype=torch.uint8)
    q8 = torch.zeros_like(s8)
    lcs = [C.make_hconv(x, w, b, (8, 16, 32, 4, 1, 2), film=film, out_silu=s16),
           C8.make_quant8(s16, q8, 8.0),
           C.make_hconv(x, w, b, (8, 16, 32, 4, 1, 2), film=film, out_silu8=s8, qs=8.0)]
    C.bind(lcs, DEV)
    for lc in lcs:
        lc()
    torch.cuda.synchronize()
    assert torch.equal(s8, q8)


def _upmerge_ref(x1, x2, up, mg):
    xu = F.interpolate(x1.double().permute(0, 3, 1, 2), scale_factor=2, mode="nearest")
    return mg(torch.cat([up(xu), x2.double().permute(0, 3, 1, 2)], 1)).permute(0, 2, 3, 1)


@pytest.mark.parametrize("b,lo,c1,c", [(1, 32, 64, 32), (2, 16, 96, 64)])
def test_uconv_matches_torch_and_mode1(b, lo, c1, c):
    """Halo-tiled fp16 up-merge: fp64 torch within fp16 output rounding, and the MODE 1 kernel up to the summation
    order (one fp16 ulp on a small fraction of the outputs)."""
    from terrain_slm.kernels import cuda_conv as C
    g = torch.Generator(device=DEV).manual_seed(lo)
    x1 = torch.randn(b, lo, lo, c1, device=DEV, generator=g).half()
    x2 = torch.randn(b, 2 * lo, 2 * lo, c, device=DEV, generator=g).half()
    up = torch.nn.Conv2d(c1, c, 3, padding=1).to(DEV).double()
    mg = torch.nn.Conv2d(2 * c, c, 1).to(DEV).double()
    with torch.no_grad():
        ref = _upmerge_ref(x1, x2, up, mg)
    outs = []
    for cfg in [(64, 32, 2, 1, 3), C.default_uconv_config(c, lo), (8, 8, 32, 2, 2, 3)]:
        np_ = C.round_up(c, cfg[1] if len(cfg) == 5 else cfg[2])
        w, bias = C.pack_upmerge(up, mg, np_)
        out = torch.zeros(b, 2 * lo, 2 * lo, c, device=DEV, dtype=torch.float16)
        lc = (C.make_conv(x1, w, bias, cfg, mode=1, x2=x2, out_raw=out) if len(cfg) == 5
              else C.make_uconv(x1, w, bias, cfg, x2=x2, out_raw=out))
        C.bind([lc], DEV)
        lc()
        torch.cuda.synchronize()
        outs.append(out.double())
        assert float((out.double() - ref).abs().max()) < 4e-3 * float(ref.abs().max()) + 2e-3, cfg
    assert torch.equal(outs[1], outs[2])           # configs never change numbers within the type
    d = (outs[0] - outs[1]).abs()
    assert float((d > 0).float().mean()) < 0.02 and float(d.max()) < 2e-3 * float(ref.abs().max()) + 1e-3


@fp8_only
@pytest.mark.parametrize("lo,c1,c", [(32, 64, 32), (16, 128, 96)])
def test_uconv8_matches_mode1(lo, c1, c):
    from terrain_slm.kernels import cuda_conv as C, cuda_conv_fp8 as C8
    g = torch.Generator(device=DEV).manual_seed(c1)
    x1, x2 = _codes(g, 1, lo, lo, c1), _codes(g, 1, 2 * lo, 2 * lo, c)
    wf = torch.randn(4 * c, 4 * c1 + c, device=DEV, generator=g) * 0.03
    outs = []
    for cfg in [(64, 32, 2, 1, 3), C8.default_uconv8_config(c, lo), (8, 8, 32, 2, 2, 3)]:
        np_ = C.round_up(c, cfg[1] if len(cfg) == 5 else cfg[2])
        parts = [C8.e4m3_rows(wf[p * c:(p + 1) * c], np_) for p in range(4)]
        wq = torch.cat([p[0] for p in parts]).contiguous()
        sc = (torch.cat([p[1] for p in parts]) / 64).contiguous()
        out = torch.zeros(1, 2 * lo, 2 * lo, c, device=DEV, dtype=torch.float16)
        lc = (C8.make_conv8(x1, wq, sc, cfg, mode=1, x2=x2, out_raw=out) if len(cfg) == 5
              else C8.make_uconv8(x1, wq, sc, cfg, x2=x2, out_raw=out))
        C.bind([lc], DEV)
        lc()
        torch.cuda.synchronize()
        outs.append(out.double())
    assert torch.equal(outs[1], outs[2])
    d = (outs[0] - outs[1]).abs()
    assert float((d > 0).float().mean()) < 0.02 and float(d.max()) < 2e-3 * float(outs[0].abs().max()) + 1e-3


@fp8_only
@pytest.mark.parametrize("b,h,c,co", [(1, 64, 96, 128), (2, 34, 64, 96)])
def test_dconv8_matches_dequantised_conv(b, h, c, co):
    from terrain_slm.kernels import cuda_conv as C, cuda_conv_fp8 as C8
    g = torch.Generator(device=DEV).manual_seed(h)
    x = _codes(g, b, h, h, c)
    wt = torch.randn(co, c, 3, 3, device=DEV, generator=g) / (9 * c) ** 0.5
    outs = []
    for cfg in [C8.default_dconv8_config(co, h // 2), (8, 8, 32, 2, 2, 3)]:
        np_ = C.round_up(co, cfg[2])
        wc, sc = C8.pack_hconv8(wt, np_)
        out = torch.zeros(b, h // 2, h // 2, co, device=DEV, dtype=torch.float16)
        lc = C8.make_dconv8(x, wc, sc, cfg, out_raw=out)
        C.bind([lc], DEV)
        lc()
        torch.cuda.synchronize()
        outs.append(out.double())
        wd = _deq(wc.permute(1, 2, 0, 3).reshape(np_, 9, c)[:co]).reshape(co, 3, 3, c).permute(0, 3, 1, 2)
        ref = F.conv2d(_deq(x).double().permute(0, 3, 1, 2), wd.double() * sc[:co, None, None, None].double(),
                       stride=2, padding=1).permute(0, 2, 3, 1)
        assert float((out.double() - ref).abs().max()) < 2e-3 * float(ref.abs().max()), cfg
    assert torch.equal(outs[0], outs[1])


def test_generation_switch_changes_only_summation_order():
    """The fp16 sampler of generation 2 (uconv up-merges) stays within fp32-summation-order noise of generation 1 on a
    small random Detail2, and its model_id mark differs."""
    import importlib
    import subprocess
    import sys
    code = ("import torch, os\n"
            "from terrain_slm.models import detail2 as D2\n"
            "from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler, MARK\n"
            "torch.manual_seed(0)\n"
            "m = D2.Detail2(D2.Detail2Config(channels=(32, 64, 96, 128, 128), blocks=1, mid_blocks=1)).to('%s').eval()\n"
            "for p in m.parameters():\n"
            "    p.data.normal_(0, 0.5)\n"
            "n = 64\n"
            "g = torch.Generator(device='%s').manual_seed(1)\n"
            "cond = torch.randn(1, D2.N_COND, n, n, device='%s', generator=g)\n"
            "z = torch.randn(1, 1, n, n, device='%s', generator=g)\n"
            "with torch.no_grad():\n"
            "    x = NativeDetail2Sampler(m, n, n, 2)(cond, z)\n"
            "torch.save((MARK, x.cpu()), os.environ['OUT'])\n") % ((DEV,) * 4)
    res = {}
    for gen in ("1", "2"):
        out = os.path.join(os.environ.get("TMPDIR", "/tmp"), f"k4_gen{gen}_{os.getpid()}.pt")
        env = dict(os.environ, TGM_DETAIL_KGEN=gen, OUT=out)
        subprocess.run([sys.executable, "-c", code], check=True, env=env, cwd=os.path.dirname(os.path.dirname(__file__)))
        res[gen] = torch.load(out)
        os.remove(out)
    assert res["1"][0] == "" and res["2"][0] != ""
    x1, x2 = res["1"][1], res["2"][1]
    assert not torch.equal(x1, x2)
    assert float((x1 - x2).abs().max()) < 1e-2 * float(x1.abs().max())
