"""Native CUDA kernels (terrain_slm/kernels, csrc/*.cu): exactness of the controls kernel, every fused
convolution variant against torch, config independence (tuning never changes a number), and the
native samplers against an fp32 reference. Needs an sm_80+ CUDA device (NVRTC ships with torch)."""
import pytest
import torch
import torch.nn.functional as F

from terrain_slm import kernels as K

DEV = "cuda"
pytestmark = pytest.mark.skipif(not K.available(DEV), reason="needs an sm_80+ CUDA device (and TGM_KERNELS not 0)")


# ----------------------------------------------------------------------------- controls
@pytest.mark.parametrize("ci0,cj0,hc,wc,seed", [
    (0, 0, 144, 144, 1), (200, 200, 300, 300, 1), (-1000, -1000, 300, 300, 1), (-500, 300, 448, 448, 7),
    (-37, -9001, 32, 97, 123456789), (123456, -654321, 200, 64, 42), (7, 11, 17, 3, 5),
    (-2100, 50, 64, 64, 3),   # the home bump's exp() is subnormal out here: no flush to zero
])
def test_controls_kernel_is_bit_identical_to_torch(ci0, cj0, hc, wc, seed):
    from terrain_slm.kernels import cuda_controls as KC
    from terrain_slm.world import generator as G
    ref = G.procedural_controls_torch(ci0, cj0, hc, wc, seed, DEV)
    got = KC.procedural_controls(ci0, cj0, hc, wc, seed, DEV)
    for name in KC.FIELDS:
        diff = int((ref[name] != got[name]).sum())
        assert diff == 0, f"{name}: {diff} values differ (max {float((ref[name] - got[name]).abs().max()):.3g})"


def test_controls_kernel_is_request_independent():
    from terrain_slm.kernels import cuda_controls as KC
    big = KC.procedural_controls(-50, -50, 200, 200, 3, DEV)
    for n in (1, 2, 3, 15, 16, 17, 63, 100):
        small = KC.procedural_controls(-50, -50, n, n, 3, DEV)
        for name in KC.FIELDS:
            assert torch.equal(small[name], big[name][:n, :n]), (n, name)


def test_generator_dispatches_to_the_kernel():
    from terrain_slm.world import generator as G
    a = G.procedural_controls(10, 20, 48, 48, 9, DEV)
    b = G.procedural_controls_torch(10, 20, 48, 48, 9, DEV)
    assert all(torch.equal(a[k], b[k]) for k in a)


# ----------------------------------------------------------------------------- convolutions
def _h(x):
    return x.half().float()


def _nhwc(x):
    return x.half().permute(0, 2, 3, 1).contiguous()


def _nchw(x):
    return x.float().permute(0, 3, 1, 2)


def _rel(got, ref):
    return float((got - ref).abs().max()) / (float(ref.abs().max()) + 1e-9)


def _run(lc):
    from terrain_slm.kernels import cuda_conv as C
    C.bind([lc], DEV)
    lc()
    torch.cuda.synchronize()


def _layer(c1, c2, co, ks, h, w, seed=0):
    g = torch.Generator(device=DEV).manual_seed(seed)
    x1 = _h(torch.randn(1, c1, h, w, device=DEV, generator=g))
    x2 = _h(torch.randn(1, c2, h, w, device=DEV, generator=g)) if c2 else None
    wt = torch.randn(co, c1 + c2, ks, ks, device=DEV, generator=g) / (ks * ks * (c1 + c2)) ** 0.5
    b = torch.randn(co, device=DEV, generator=g)
    film = torch.cat([1 + 0.1 * torch.randn(co, device=DEV, generator=g), torch.randn(co, device=DEV, generator=g)])
    return x1, x2, wt, b, film


@pytest.mark.parametrize("cfg", [(128, 96, 2, 2, 3), (64, 32, 2, 1, 2), (256, 96, 4, 2, 3)])
@pytest.mark.parametrize("ks,stride,cat", [(3, 1, False), (3, 2, False), (1, 1, True), (3, 1, True)])
def test_conv_matches_torch(cfg, ks, stride, cat):
    from terrain_slm.kernels import cuda_conv as C
    co = 96 if cfg[1] == 96 else 64
    h, w = 40, 46
    x1, x2, wt, b, film = _layer(64, 32 if cat else 0, co, ks, h, w)
    ho, wo = (h + stride - 1) // stride, (w + stride - 1) // stride
    res = _h(torch.randn(1, co, ho, wo, device=DEV))
    ref = F.conv2d(torch.cat([x1, x2], 1) if cat else x1, wt, b, stride=stride, padding=ks // 2)
    ref = ref * film[:co].view(1, -1, 1, 1) + film[co:].view(1, -1, 1, 1) + res
    np_ = C.round_up(co, cfg[1])
    wq = C.pack_concat(wt, 64, np_) if cat else C.pack_conv(wt, np_)
    raw = torch.zeros(1, ho, wo, co, device=DEV, dtype=torch.half)
    sil = torch.zeros_like(raw)
    _run(C.make_conv(_nhwc(x1), wq, C.pad_bias(b, np_), cfg, ks=ks, stride=stride, x2=_nhwc(x2) if cat else None,
                     film=film, res=_nhwc(res), out_raw=raw, out_silu=sil))
    assert _rel(_nchw(raw), ref) < 2e-3
    assert _rel(_nchw(sil), F.silu(ref)) < 2e-3


@pytest.mark.parametrize("merge", [False, True])
def test_phase_upconv_and_folded_merge_match_torch(merge):
    from terrain_slm.kernels import cuda_conv as C
    clow, c, h = 192, 96, 20
    up = torch.nn.Conv2d(clow, c, 3, padding=1).to(DEV)
    mg = torch.nn.Conv2d(2 * c, c, 1).to(DEV)
    x = _h(torch.randn(1, clow, h, h + 4, device=DEV))
    skip = _h(torch.randn(1, c, 2 * h, 2 * h + 8, device=DEV))
    out = torch.zeros(1, 2 * h, 2 * h + 8, c, device=DEV, dtype=torch.half)
    with torch.no_grad():
        u = up(F.interpolate(x, scale_factor=2, mode="nearest"))
        if merge:
            ref = mg(torch.cat([u, skip], 1))
            w, b = C.pack_upmerge(up, mg, 96)
            _run(C.make_conv(_nhwc(x), w, b, (128, 96, 2, 2, 3), mode=1, x2=_nhwc(skip), out_raw=out))
        else:
            ref = u
            _run(C.make_conv(_nhwc(x), C.pack_up(up.weight, 96), C.pad_bias(up.bias, 96), (128, 96, 2, 2, 3),
                             mode=1, out_raw=out))
    assert _rel(_nchw(out), ref) < 2e-3


@pytest.mark.parametrize("cfg", [(16, 16, 96, 4, 2, 2), (8, 16, 32, 2, 2, 3), (8, 8, 64, 2, 2, 2), (8, 32, 32, 8, 1, 2)])
@pytest.mark.parametrize("c,h,w", [(32, 40, 40), (96, 30, 46), (64, 7, 9)])
def test_halo_conv_matches_torch(cfg, c, h, w):
    from terrain_slm.kernels import cuda_conv as C
    co = 96 if cfg[2] == 96 else 64
    x, _, wt, b, film = _layer(c, 0, co, 3, h, w, seed=1)
    res = _h(torch.randn(1, co, h, w, device=DEV))
    ref = F.conv2d(x, wt, b, padding=1) * film[:co].view(1, -1, 1, 1) + film[co:].view(1, -1, 1, 1) + res
    np_ = C.round_up(co, cfg[2])
    raw = torch.zeros(1, h, w, co, device=DEV, dtype=torch.half)
    sil = torch.zeros_like(raw)
    _run(C.make_hconv(_nhwc(x), C.pack_hconv(wt, np_), C.pad_bias(b, np_), cfg, film=film, res=_nhwc(res),
                      out_raw=raw, out_silu=sil))
    assert _rel(_nchw(raw), ref) < 2e-3
    assert _rel(_nchw(sil), F.silu(ref)) < 2e-3


def test_halo_conv_euler_epilogue():
    from terrain_slm.kernels import cuda_conv as C
    h, w, c = 24, 40, 32
    x, _, wt, b, _ = _layer(c, 0, 1, 3, h, w, seed=2)
    xs = torch.randn(h * w, device=DEV)
    stem = torch.zeros(1, h, w, 32, device=DEV, dtype=torch.half)
    v = F.conv2d(x, wt, b, padding=1)[0, 0].reshape(-1)
    want = xs + 0.25 * v
    _run(C.make_hconv(_nhwc(x), C.pack_hconv(wt, 16), C.pad_bias(b, 16), (8, 32, 16, 8, 1, 2), euler=(xs, stem, 0.25)))
    assert float((xs - want).abs().max()) < 2e-3 * float(want.abs().max())
    assert torch.equal(stem[..., 0].reshape(-1), xs.half())
    assert float(stem[..., 1:].abs().max()) == 0.0


def test_configs_never_change_a_number():
    """Tile configs only change speed: each output is the same fixed K sequence with no split-K."""
    from terrain_slm.kernels import cuda_conv as C
    h, w = 37, 53
    x, _, wt, b, film = _layer(96, 0, 96, 3, h, w, seed=4)
    xn = _nhwc(x)
    outs = []
    for cfg in [(16, 16, 96, 4, 2, 2), (8, 16, 32, 2, 2, 3), (8, 32, 48, 8, 1, 2), (8, 8, 96, 2, 2, 3)]:
        out = torch.zeros(1, h, w, 96, device=DEV, dtype=torch.half)
        np_ = C.round_up(96, cfg[2])
        _run(C.make_hconv(xn, C.pack_hconv(wt, np_), C.pad_bias(b, np_), cfg, film=film, out_silu=out))
        outs.append(out)
    for cfg in [(128, 96, 2, 2, 3), (64, 32, 2, 1, 2), (256, 96, 4, 2, 3), (64, 48, 1, 1, 4)]:
        out = torch.zeros(1, h, w, 96, device=DEV, dtype=torch.half)
        np_ = C.round_up(96, cfg[1])
        _run(C.make_conv(xn, C.pack_conv(wt, np_), C.pad_bias(b, np_), cfg, film=film, out_silu=out))
        outs.append(out)
    assert all(torch.equal(outs[0], o) for o in outs[1:4]), "halo kernel configs disagree"
    assert all(torch.equal(outs[4], o) for o in outs[5:]), "implicit-GEMM kernel configs disagree"


# ----------------------------------------------------------------------------- native samplers
def _fp32_sample(model, cond, noise, steps):
    x = noise.clone()
    ts = torch.linspace(0.0, 1.0, steps + 1, device=DEV)
    with torch.no_grad():
        for i in range(steps):
            x = x + (ts[i + 1] - ts[i]) * model(x, torch.full((1,), float(ts[i]), device=DEV), cond)
    return x


def _randomise(model):
    """Untrained models are zero-initialised (they predict nothing); give every layer real weights."""
    g = torch.Generator().manual_seed(3)
    with torch.no_grad():
        for p in model.parameters():
            p.copy_((torch.randn(p.shape, generator=g) * (0.5 / max(1, p[0].numel()) ** 0.5)).to(p.dtype))
    return model


@pytest.mark.parametrize("kind", ["detail", "relief"])
def test_native_sampler_tracks_fp32_far_better_than_autocast(kind):
    from terrain_slm.kernels.cuda_unet import NativeSampler
    from terrain_slm.models import detail as DT
    from terrain_slm.models import refiner as R
    from terrain_slm.models import relief as RL
    torch.backends.cudnn.allow_tf32 = False
    if kind == "detail":
        model = DT.Detail(DT.DetailConfig(channels=(32, 64, 96), blocks=2, temb=32, dropout=0.0))
        n_cond, sample = DT.N_COND, lambda c, n: DT.sample(model, c, n, steps=6)
    else:
        model = RL.Relief(RL.ReliefConfig(channels=(32, 64, 96), blocks=2, temb=32))
        n_cond, sample = RL.N_COND, lambda c, n: R.sample(model, c, n, steps=6)
    model = _randomise(model).to(DEV).eval()
    g = torch.Generator(device=DEV).manual_seed(5)
    cond = torch.randn(1, n_cond, 64, 72, device=DEV, generator=g)
    noise = torch.randn(1, 1, 64, 72, device=DEV, generator=g)
    ns = NativeSampler(model, 64, 72, steps=6)
    got = ns(cond, noise)
    ref = _fp32_sample(model, cond, noise, 6)
    auto = sample(cond, noise)
    e_nat = float((got - ref).pow(2).mean().sqrt())
    e_auto = float((auto - ref).pow(2).mean().sqrt())
    assert torch.isfinite(got).all()
    assert e_nat < 0.35 * e_auto, (e_nat, e_auto)        # fp16 operands: ~10x closer than bf16 autocast
    assert e_nat < 0.005 * float(ref.std()), (e_nat, float(ref.std()))
    # Replays are deterministic, independent of what ran before, and equal to the eager launches.
    other = ns(torch.randn_like(cond), torch.randn_like(noise))
    assert not torch.equal(other, got)
    assert torch.equal(ns(cond, noise), got)
    assert torch.equal(ns.eager(cond, noise), got)


@pytest.mark.skipif(torch.cuda.device_count() < 2, reason="needs a second GPU")
def test_kernels_run_on_a_non_current_device():
    """The service samples on cuda:1 while cuda:0 stays current."""
    from terrain_slm.kernels import cuda_controls as KC
    from terrain_slm.kernels.cuda_unet import NativeSampler
    from terrain_slm.models import detail as DT
    from terrain_slm.world import generator as G
    torch.cuda.set_device(0)
    dev = "cuda:1"
    ref = G.procedural_controls_torch(3, 4, 40, 40, 2, dev)
    got = KC.procedural_controls(3, 4, 40, 40, 2, dev)
    assert all(torch.equal(ref[k], got[k]) for k in KC.FIELDS)
    model = _randomise(DT.Detail(DT.DetailConfig(channels=(32, 64), blocks=1, temb=32, dropout=0.0))).to(dev).eval()
    cond = torch.randn(1, DT.N_COND, 32, 32, device=dev)
    noise = torch.randn(1, 1, 32, 32, device=dev)
    ns = NativeSampler(model, 32, 32, steps=3)
    out = ns(cond, noise)
    assert out.device == torch.device(dev) and torch.isfinite(out).all()
    assert torch.equal(ns(cond, noise), out)
    assert float((out - DT.sample(model, cond, noise, steps=3)).abs().max()) < 0.05 * float(out.abs().max())
