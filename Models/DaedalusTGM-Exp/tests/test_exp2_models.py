"""EXP-2 model invariants: MP activations stay unit-scale, experts are inert at gate 0, MeanFlow inputs, SR is
exactly mean-preserving, the generator builds both detail architectures."""
import pytest
import torch

from terrain_slm.models import detail2 as D2
from terrain_slm.models import sr as SR
from terrain_slm.models.mp import force_weight_norm_, normalize

DEV = "cuda" if torch.cuda.is_available() else "cpu"
TINY = D2.Detail2Config(channels=(32, 64, 96, 128, 160))


def _inputs(n=64):
    g = torch.Generator(device=DEV).manual_seed(0)
    return (torch.randn(2, 1, n, n, device=DEV, generator=g), torch.rand(2, device=DEV, generator=g),
            torch.randn(2, D2.N_COND, n, n, device=DEV, generator=g))


def test_blocks_keep_unit_magnitude():
    m = D2.Detail2(TINY).to(DEV).eval()
    rms = []
    hooks = [b.register_forward_hook(lambda mod, i, o: rms.append(float(o.pow(2).mean().sqrt())))
             for lvl in m.enc for b in lvl]
    with torch.no_grad():
        m(*_inputs())
    assert all(0.7 < r < 1.4 for r in rms), rms


def test_untrained_output_is_zero_and_r_is_accepted():
    m = D2.Detail2(TINY).to(DEV).eval()
    x, t, c = _inputs()
    with torch.no_grad():
        assert m(x, t, c).abs().max() == 0          # zero-init output gain
        m.out_gain.data.fill_(1.0)
        for mod in m.modules():                     # time-embedding gains start at 0 (EDM2): open them
            if hasattr(mod, "emb_gain"):
                mod.emb_gain.data.fill_(1.0)
        assert not torch.equal(m(x, t, c), m(x, t, c, r=torch.ones_like(t)))   # (t, r) both condition


def test_an_expert_at_gate_zero_changes_nothing():
    m = D2.Detail2(TINY).to(DEV).eval()
    m.out_gain.data.fill_(1.0)
    e = m.add_expert("volcanic")
    for ad in [*e.mid, *e.enc["2"]]:
        ad.gain.data.fill_(1.0)
    x, t, c = _inputs()
    gates = torch.zeros(2, len(D2.STYLES), 64, 64, device=DEV)
    with torch.no_grad():
        plain = m(x, t, c)
        assert torch.equal(plain, m(x, t, c, gates=gates))
        gates[:, D2.STYLES.index("volcanic")] = 1.0
        assert not torch.equal(plain, m(x, t, c, gates=gates))


def test_forced_weight_norm():
    m = D2.Detail2(TINY).to(DEV)
    with torch.no_grad():
        m.stem.weight.mul_(7.0)
    force_weight_norm_(m)
    w = m.stem.weight
    assert torch.allclose(normalize(w), w, rtol=1e-3, atol=1e-4)   # normalize's eps


def test_sr_assemble_is_mean_preserving():
    fine = torch.randn(3, 1, 32, 32, device=DEV).cumsum(-1) * 10
    coarse = SR.block_mean(fine)
    out = SR.assemble(coarse, torch.randn(3, 1, 32, 32, device=DEV))
    assert (SR.block_mean(out) - coarse).abs().max() < 1e-3


def test_generator_factory_builds_both_architectures():
    from terrain_slm.models import detail as DT
    from terrain_slm.world.generator import _detail_model
    assert isinstance(_detail_model(DT.DetailConfig().to_dict()), DT.Detail)
    m = _detail_model({**TINY.to_dict(), "mean_flow": True})
    assert isinstance(m, D2.Detail2) and m.mean_flow
