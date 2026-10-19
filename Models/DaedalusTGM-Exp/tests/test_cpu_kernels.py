"""Native CPU kernels (kernels/csrc/unet_cpu.cpp, controls_cpu.h): the CPU samplers against torch's fp32
UNets, their determinism across thread counts, and the CPU controls against the torch and GPU definitions.
Needs a C++ compiler (skipped otherwise)."""
import pytest
import torch

from terrain_slm.kernels import cpu_sampler as CS

pytestmark = pytest.mark.skipif(not CS.available(), reason="needs a C++ compiler (and TGM_KERNELS not 0)")


def _randomise(m):
    """Random weights (the checkpoints zero-initialise conv2 / FiLM / out, which would hide bugs)."""
    g = torch.Generator().manual_seed(0)
    with torch.no_grad():
        for p in m.parameters():
            if p.dim() > 1:
                p.copy_(torch.randn(p.shape, generator=g) * (0.5 / p[0].numel()) ** 0.5)
            else:
                p.copy_(torch.randn(p.shape, generator=g) * 0.1)
    return m.eval()


@torch.no_grad()
def _reference(model, cond, noise, steps, t_start):
    x = (1.0 - t_start) * noise
    ts = torch.linspace(t_start, 1.0, steps + 1)
    for i in range(steps):
        t = torch.full((1,), float(ts[i]))
        x = x + (ts[i + 1] - ts[i]) * model(x, t, cond).float()
    return x


def _models():
    from terrain_slm.models import detail as DT
    from terrain_slm.models import relief as RL
    return [
        ("detail", _randomise(DT.Detail(DT.DetailConfig(channels=(16, 32, 48), temb=32))), DT.N_COND, 64),
        ("relief", _randomise(RL.Relief(RL.ReliefConfig(channels=(16, 32, 48), temb=32))), RL.N_COND, 48),
    ]


@pytest.mark.parametrize("winograd", [True, False, 2, 4, 6])
@pytest.mark.parametrize("which", [0, 1])
def test_sampler_matches_torch_fp32(which, winograd):
    """Every op of the plan (stem, ResBlocks, stride-2 downs, the folded up+merge / phase up-conv + concat
    merge, Winograd and direct 3x3, Euler output) against the torch model in fp32."""
    name, model, n_cond, n = _models()[which]
    g = torch.Generator().manual_seed(1)
    cond = torch.randn(1, n_cond, n, n, generator=g)
    noise = torch.randn(1, 1, n, n, generator=g)
    for steps, t_start in ((3, 0.0), (2, 0.25)):
        ref = _reference(model, cond, noise, steps, t_start)
        got = CS.CpuSampler(model, n, n, steps, t_start, winograd=winograd)(cond, noise)
        err = float((got - ref).abs().max()) / (float(ref.abs().max()) + 1e-6)
        assert err < 1e-5, f"{name} winograd={winograd}: relative error {err:.3g}"
    if winograd:
        kind = {True: 4, 2: 3, 4: 2, 6: 4}[winograd]
        assert any(o.kind == kind for o in CS.CpuSampler(model, n, n, 1, winograd=winograd)._ops)


def test_sampler_is_deterministic_across_thread_counts():
    name, model, n_cond, n = _models()[0]
    g = torch.Generator().manual_seed(2)
    cond, noise = torch.randn(1, n_cond, n, n, generator=g), torch.randn(1, 1, n, n, generator=g)
    s = CS.CpuSampler(model, n, n, 2)
    before = CS.threads()
    try:
        outs = []
        for th in (1, 3, 7, before):
            CS.set_threads(th)
            outs.append(s(cond, noise))
            outs.append(s(cond, noise))
    finally:
        CS.set_threads(before)
    assert all(torch.equal(outs[0], o) for o in outs[1:])


def test_sampler_on_the_shipped_detail_model():
    """The real v4 detail weights (folded merges, 96-512 channels, Winograd + direct levels) on a small window."""
    from terrain_slm.models import detail as DT
    from terrain_slm.paths import MODEL_DIR
    path = MODEL_DIR / "checkpoints" / "v4" / "detail.pt"
    if not path.exists():
        pytest.skip("no v4 checkpoint")
    k = torch.load(path, map_location="cpu", weights_only=False)
    c = dict(k["config"])
    c["channels"] = tuple(c["channels"])
    model = DT.Detail(DT.DetailConfig(**c)).eval()
    model.load_state_dict(k["model"])
    model.float()
    g = torch.Generator().manual_seed(3)
    n = 64
    cond = torch.randn(1, DT.N_COND, n, n, generator=g) * 0.5
    noise = torch.randn(1, 1, n, n, generator=g)
    ref = _reference(model, cond, noise, 2, 0.0)
    got = CS.CpuSampler(model, n, n, 2)(cond, noise)
    # Random conditioning is far out of distribution (the sample runs away), so compare relative to its scale.
    err = float((got - ref).abs().max()) / float(ref.abs().max())
    assert err < 1e-4, f"relative error {err:.3g}"


# ----------------------------------------------------------------------------- controls
# Near the origin only: far out (|cell| ~ 1e5) torch's CPU path drifts from the GPU definition (true division
# vs the reciprocal multiply, at large coordinates) as far as it does from this code; the GPU test covers those.
@pytest.mark.parametrize("ci0,cj0,hc,wc,seed", [
    (0, 0, 144, 144, 1), (-500, 300, 100, 120, 7), (7, 11, 17, 3, 5), (-2100, 50, 64, 64, 3),
])
def test_controls_match_torch(ci0, cj0, hc, wc, seed):
    from terrain_slm.kernels import controls_cpu as CC
    from terrain_slm.world import generator as G
    ref = G.procedural_controls_torch(ci0, cj0, hc, wc, seed, "cpu")
    got = CC.procedural_controls(ci0, cj0, hc, wc, seed)
    # Only fp32 rounding apart (torch's CPU exp / division / reductions differ from the GPU definition the
    # native code mirrors): absolute tolerance per field's scale.
    tol = {"wild": 1e-5, "trend": 1e-2, "t0": 1e-3, "tseason": 1e-2, "precip": 5e-2, "pcv": 1e-3}
    for name in CC.FIELDS:
        assert got[name].shape == (hc, wc)
        diff = float((ref[name] - got[name]).abs().max())
        assert diff < tol[name], f"{name}: max diff {diff:.3g}"


def test_controls_are_request_independent():
    from terrain_slm.kernels import controls_cpu as CC
    big = CC.procedural_controls(-50, -50, 120, 120, 3)
    for n in (1, 2, 15, 16, 17, 63):
        small = CC.procedural_controls(-50, -50, n, n, 3)
        for name in CC.FIELDS:
            assert torch.equal(small[name], big[name][:n, :n]), (n, name)


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs CUDA for the GPU definition")
@pytest.mark.parametrize("ci0,cj0,hc,wc,seed", [
    (-500, 300, 160, 160, 7), (-37, -9001, 32, 97, 123456789), (123456, -654321, 200, 64, 42), (-2100, 50, 64, 64, 3),
])
def test_controls_close_to_gpu_definition(ci0, cj0, hc, wc, seed):
    """The native CPU controls mirror the GPU kernel op for op: they differ only where an exp feeds a value
    (glibc expf vs CUDA expf), by an ulp or so of each field."""
    from terrain_slm.kernels import controls_cpu as CC
    from terrain_slm.world import generator as G
    got = CC.procedural_controls(ci0, cj0, hc, wc, seed)
    gpu = G.procedural_controls(ci0, cj0, hc, wc, seed, "cuda")
    tol = {"wild": 1e-6, "trend": 1e-3, "t0": 1e-4, "tseason": 1e-3, "precip": 1e-2, "pcv": 1e-4}
    for name in CC.FIELDS:
        diff = float((gpu[name].cpu() - got[name]).abs().max())
        assert diff < tol[name], f"{name}: max diff {diff:.3g}"
