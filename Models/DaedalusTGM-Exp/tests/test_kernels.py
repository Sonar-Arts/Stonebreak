"""Custom GPU kernels (terrain_slm/kernels): exactness of the controls kernel, the fused conv's
variants against torch, and the fused samplers against an fp32 reference. CUDA + Triton only."""
import pytest
import torch
import torch.nn.functional as F

from terrain_slm import kernels as K

DEV = "cuda"
pytestmark = pytest.mark.skipif(not K.available(DEV), reason="needs CUDA + Triton (and TGM_KERNELS not 0)")


# ----------------------------------------------------------------------------- controls
@pytest.mark.parametrize("ci0,cj0,hc,wc,seed", [
    (0, 0, 144, 144, 1), (200, 200, 300, 300, 1), (-1000, -1000, 300, 300, 1), (-500, 300, 448, 448, 7),
    (-37, -9001, 32, 97, 123456789), (123456, -654321, 200, 64, 42), (7, 11, 17, 3, 5),
    (-2100, 50, 64, 64, 3),   # the home bump's exp() is subnormal out here: no flush to zero
])
def test_controls_kernel_is_bit_identical_to_torch(ci0, cj0, hc, wc, seed):
    from terrain_slm.kernels import controls as KC
    from terrain_slm.world import generator as G
    ref = G.procedural_controls_torch(ci0, cj0, hc, wc, seed, DEV)
    got = KC.procedural_controls(ci0, cj0, hc, wc, seed, DEV)
    for name in KC.FIELDS:
        diff = int((ref[name] != got[name]).sum())
        assert diff == 0, f"{name}: {diff} values differ (max {float((ref[name] - got[name]).abs().max()):.3g})"


def test_controls_kernel_is_request_independent():
    """A cell's value never depends on the window around it (every specialisation of the kernel agrees)."""
    from terrain_slm.kernels import controls as KC
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


# ----------------------------------------------------------------------------- fused conv
def _bf(x):
    return x.to(torch.bfloat16)


def _nhwc(x):
    return _bf(x).permute(0, 2, 3, 1).contiguous()


def _nchw(x):
    return x.float().permute(0, 3, 1, 2)


def _assert_close(got, ref, rtol=1.5e-2):
    scale = float(ref.abs().max()) + 1e-6
    err = float((got - ref).abs().max()) / scale
    assert err < rtol, f"relative error {err:.3g}"


@pytest.mark.parametrize("ks,stride,up,cat", [(3, 1, False, False), (3, 2, False, False), (1, 1, False, True),
                                              (3, 1, True, False), (3, 1, False, True)])
def test_fused_conv_matches_torch(ks, stride, up, cat):
    from terrain_slm.kernels import conv as KV
    g = torch.Generator(device=DEV).manual_seed(0)
    c1, c2, co, h = 64, (32 if cat else 0), 96, 32
    hin = h // 2 if up else h
    x1 = _bf(torch.randn(1, c1, hin, hin, device=DEV, generator=g)).float()
    x2 = _bf(torch.randn(1, c2, h, h, device=DEV, generator=g)).float() if cat else None
    w = _bf(torch.randn(co, c1 + c2, ks, ks, device=DEV, generator=g) / (9 * (c1 + c2)) ** 0.5).float()
    b = torch.randn(co, device=DEV, generator=g)
    film = torch.cat([1 + 0.1 * torch.randn(co, device=DEV, generator=g), torch.randn(co, device=DEV, generator=g)])
    ho = (h + stride - 1) // stride
    res = _bf(torch.randn(1, co, ho, ho, device=DEV, generator=g)).float()

    inp = F.interpolate(x1, scale_factor=2, mode="nearest") if up else x1
    if cat:
        inp = torch.cat([inp, x2], dim=1)
    ref = F.conv2d(inp, w, b, stride=stride, padding=ks // 2)
    ref = ref * film[:co].view(1, -1, 1, 1) + film[co:].view(1, -1, 1, 1) + res

    raw = torch.empty(1, ho, ho, co, device=DEV, dtype=torch.bfloat16)
    silu = torch.empty_like(raw)
    KV.conv(_nhwc(x1), KV.pack_weight(w), b, ks=ks, stride=stride, up1=up, x2=_nhwc(x2) if cat else None,
            film=film, res=_nhwc(res), out_raw=raw, out_silu=silu)
    _assert_close(_nchw(raw), ref)
    _assert_close(_nchw(silu), F.silu(ref))


@pytest.mark.parametrize("h", [8, 13])
def test_upconv_phase_decomposition_matches_upsample_then_conv(h):
    from terrain_slm.kernels import conv as KV
    g = torch.Generator(device=DEV).manual_seed(2)
    x = _bf(torch.randn(1, 64, h, h + 3, device=DEV, generator=g)).float()
    w = _bf(torch.randn(96, 64, 3, 3, device=DEV, generator=g) / 24).float()
    b = torch.randn(96, device=DEV, generator=g)
    ref = F.conv2d(F.interpolate(x, scale_factor=2, mode="nearest"), w, b, padding=1)
    out = torch.empty(1, 2 * h, 2 * (h + 3), 96, device=DEV, dtype=torch.bfloat16)
    KV.upconv(_nhwc(x), KV.pack_up_weight(w), b, out=out)
    _assert_close(_nchw(out), ref)


def test_fused_conv_euler_epilogue():
    """Output conv: x += dt * v in fp32, and the new x written into the stem input's channel 0."""
    from terrain_slm.kernels import conv as KV
    g = torch.Generator(device=DEV).manual_seed(1)
    x = _bf(torch.randn(1, 32, 16, 16, device=DEV, generator=g)).float()
    w = _bf(torch.randn(1, 32, 3, 3, device=DEV, generator=g) / 17).float()
    b = torch.randn(1, device=DEV, generator=g)
    state = torch.randn(16 * 16, device=DEV, generator=g)
    stem = torch.zeros(1, 16, 16, 32, device=DEV, dtype=torch.bfloat16)
    ref = state.view(1, 1, 16, 16) + 0.25 * F.conv2d(x, w, b, padding=1)
    KV.conv(_nhwc(x), KV.pack_weight(w), b, euler=(state, stem, 0.25))
    _assert_close(state.view(1, 1, 16, 16), ref, rtol=5e-3)
    assert torch.equal(stem[0, :, :, 0], state.view(16, 16).to(torch.bfloat16))
    assert float(stem[..., 1:].abs().max()) == 0.0


# ----------------------------------------------------------------------------- fused samplers
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
def test_fused_sampler_tracks_fp32_and_beats_autocast(kind):
    from terrain_slm.kernels.unet import FusedSampler
    from terrain_slm.models import detail as DT
    from terrain_slm.models import refiner as R
    from terrain_slm.models import relief as RL
    if kind == "detail":
        model = DT.Detail(DT.DetailConfig(channels=(32, 64, 96), blocks=2, temb=32, dropout=0.0))
        n_cond, sample = DT.N_COND, lambda c, n: DT.sample(model, c, n, steps=6)
    else:
        model = RL.Relief(RL.ReliefConfig(channels=(32, 64, 96), blocks=2, temb=32))
        n_cond, sample = RL.N_COND, lambda c, n: R.sample(model, c, n, steps=6)
    model = _randomise(model).to(DEV).eval()
    g = torch.Generator(device=DEV).manual_seed(5)
    cond = torch.randn(1, n_cond, 64, 64, device=DEV, generator=g)
    noise = torch.randn(1, 1, 64, 64, device=DEV, generator=g)
    fs = FusedSampler(model, 64, 64, steps=6)
    fused = fs(cond, noise)
    ref = _fp32_sample(model, cond, noise, 6)
    auto = sample(cond, noise)
    e_fused = float((fused - ref).pow(2).mean().sqrt())
    e_auto = float((auto - ref).pow(2).mean().sqrt())
    assert torch.isfinite(fused).all()
    assert e_fused <= 1.25 * e_auto + 1e-4, (e_fused, e_auto)
    assert e_fused < 0.02 * float(ref.std()), (e_fused, float(ref.std()))
    # Replays are deterministic and independent of what ran before.
    other = fs(torch.randn_like(cond), torch.randn_like(noise))
    assert not torch.equal(other, fused)
    assert torch.equal(fs(cond, noise), fused)


@pytest.mark.skipif(torch.cuda.device_count() < 2, reason="needs a second GPU")
def test_kernels_run_on_a_non_current_device():
    """The service samples on cuda:1 while cuda:0 stays current (Triton launches on the current device)."""
    from terrain_slm.kernels import controls as KC
    from terrain_slm.kernels.unet import FusedSampler
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
    fs = FusedSampler(model, 32, 32, steps=3)
    out = fs(cond, noise)
    assert out.device == torch.device(dev) and torch.isfinite(out).all()
    assert torch.equal(fs(cond, noise), out)
    assert float((out - DT.sample(model, cond, noise, steps=3)).abs().max()) < 0.05 * float(out.abs().max())
