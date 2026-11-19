"""MeanFlow relief student (models/relief.py ReliefConfig.mean_flow, train_relief --objective meanflow): starts as its
FM teacher, the (t, r) step grid, the training loss, and the native samplers' FiLM tables from e(t, r)."""
import pytest
import torch

from terrain_slm.models import refiner as R
from terrain_slm.models import relief as RL

DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")


def _randomise(m, seed=0):
    """Random weights (checkpoints zero-initialise conv2 / FiLM / out / temb_h's last layer, which would hide bugs)."""
    g = torch.Generator().manual_seed(seed)
    with torch.no_grad():
        for p in m.parameters():
            p.copy_(torch.randn(p.shape, generator=g) * ((0.5 / p[0].numel()) ** 0.5 if p.dim() > 1 else 0.1))
    return m.eval()


def _pair(channels=(16, 32, 48), temb=32):
    teacher = _randomise(RL.Relief(RL.ReliefConfig(channels=channels, temb=temb)))
    student = RL.Relief(RL.ReliefConfig(channels=channels, temb=temb, mean_flow=True)).eval()
    got = student.load_state_dict(teacher.state_dict(), strict=False)
    assert sorted(got.missing_keys) == ["temb_h.0.bias", "temb_h.0.weight", "temb_h.2.bias", "temb_h.2.weight"]
    assert not got.unexpected_keys
    return teacher, student


@torch.no_grad()
def test_student_starts_as_its_teacher():
    teacher, student = _pair()
    g = torch.Generator().manual_seed(1)
    x, cond = torch.randn(2, 1, 32, 32, generator=g), torch.randn(2, RL.N_COND, 32, 32, generator=g)
    t = torch.tensor([0.1, 0.7])
    v = teacher(x, t, cond)
    for r in (None, torch.tensor([0.5, 1.0])):
        assert torch.equal(student(x, t, cond, r), v)
    assert not teacher.mean_flow and student.mean_flow


@torch.no_grad()
def test_teacher_sample_is_refiner_sample_exactly():
    teacher, _ = _pair()
    g = torch.Generator().manual_seed(2)
    cond, noise = torch.randn(1, RL.N_COND, 32, 32, generator=g), torch.randn(1, 1, 32, 32, generator=g)
    assert torch.equal(RL.sample(teacher, cond, noise, steps=5), R.sample(teacher, cond, noise, steps=5))


@torch.no_grad()
def test_meanflow_steps_use_the_t_r_grid():
    _, student = _pair()
    calls = []
    fwd = student.forward
    student.forward = lambda x, t, cond, r=None: (calls.append((float(t[0]), None if r is None else float(r[0]))),
                                                  fwd(x, t, cond, r))[1]
    cond, noise = torch.zeros(1, RL.N_COND, 16, 16), torch.zeros(1, 1, 16, 16)
    RL.sample(student, cond, noise, steps=1)
    RL.sample(student, cond, noise, steps=2)
    assert calls == [(0.0, 1.0), (0.0, 0.5), (0.5, 1.0)]


@torch.no_grad()
def test_embedding_depends_on_r_once_trained():
    _, student = _pair()
    _randomise(student.temb_h, seed=4)
    t = torch.tensor([0.25, 0.25])
    e = student.embed(t, torch.tensor([0.5, 1.0]))
    assert not torch.allclose(e[0], e[1])
    assert torch.equal(student.embed(t), student.embed(t, t))   # r = None is r = t


def test_meanflow_loss_trains_the_h_embedding():
    from terrain_slm.train.train_relief import meanflow_loss
    _, student = _pair()
    student.train()
    g = torch.Generator().manual_seed(3)
    cond, x1 = torch.randn(4, RL.N_COND, 16, 16, generator=g), torch.randn(4, 1, 16, 16, generator=g)
    torch.manual_seed(0)
    loss, raw = meanflow_loss(student, cond, x1, p_mean=1.0)
    loss.backward()
    assert torch.isfinite(loss) and torch.isfinite(raw)
    assert float(student.temb_h[2].weight.grad.abs().sum()) > 0
    assert all(torch.isfinite(p.grad).all() for p in student.parameters() if p.grad is not None)


@torch.no_grad()
def _reference(model, cond, noise, steps):
    x = noise.clone()
    ts = torch.linspace(0.0, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((1,), float(ts[i]), device=cond.device)
        x = x + (ts[i + 1] - ts[i]) * model(x, t, cond, torch.full_like(t, float(ts[i + 1]))).float()
    return x


def test_cpu_sampler_meanflow_matches_torch_fp32():
    from terrain_slm.kernels import cpu_sampler as CS
    if not CS.available():
        pytest.skip("needs a C++ compiler")
    _, student = _pair()
    _randomise(student.temb_h, seed=4)
    g = torch.Generator().manual_seed(5)
    cond, noise = torch.randn(1, RL.N_COND, 48, 48, generator=g), torch.randn(1, 1, 48, 48, generator=g)
    for steps in (1, 2):
        ref = _reference(student, cond, noise, steps)
        got = CS.CpuSampler(student, 48, 48, steps)(cond, noise)
        assert float((got - ref).abs().max()) / (float(ref.abs().max()) + 1e-6) < 1e-5


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs CUDA")
def test_native_cuda_sampler_meanflow_matches_torch_fp32():
    from terrain_slm import kernels as K
    if not K.available(DEV):
        pytest.skip("native CUDA kernels unavailable")
    from terrain_slm.kernels.cuda_unet import NativeSampler
    torch.backends.cudnn.allow_tf32 = False
    _, student = _pair(channels=(32, 64, 96))
    _randomise(student.temb_h, seed=4)
    student = student.to(DEV)
    g = torch.Generator(device=DEV).manual_seed(6)
    cond = torch.randn(1, RL.N_COND, 64, 72, device=DEV, generator=g)
    noise = torch.randn(1, 1, 64, 72, device=DEV, generator=g)
    for steps in (1, 2):
        ref = _reference(student, cond, noise, steps)
        got = NativeSampler(student, 64, 72, steps)(cond, noise)
        assert torch.isfinite(got).all()
        # fp16 operands (the FM test's bound); a FiLM table from temb(t) alone would be off by O(ref.std())
        assert float((got - ref).pow(2).mean().sqrt()) < 0.005 * float(ref.std())
