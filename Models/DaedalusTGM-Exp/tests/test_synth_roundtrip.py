"""Round-trip gate: extract(synth(d)) must recover d. If it doesn't, descriptor targets
mean nothing to the synth and training the planner on them is pointless."""
import math

import pytest
import torch

from terrain_slm.data import descriptors as D
from terrain_slm.synth import noise as S

DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
HC = 128  # cells -> 1024 px (band 4 needs room)


M = S.MARGIN_CELLS


def _uniform_desc(log_amps, ridge=0.0, c=0.0, s=0.0, n=HC + 2 * S.MARGIN_CELLS):
    d = torch.zeros(1, D.N_CHANNELS, n, n, device=DEV)
    for o, a in enumerate(log_amps):
        d[:, o] = a
    d[:, 5], d[:, 6], d[:, 7] = ridge, c, s
    return d


def _measure(desc, seed=7):
    coarse = torch.zeros(1, 1, desc.shape[-2], desc.shape[-1], device=DEV)
    z = S.synth(coarse, desc, (0, 0), seed)
    m = D.extract(z)
    inner = slice(24, -24)  # drop cells that see reflected context (band-4 windows are wide)
    return m[:, :, inner, inner].mean(dim=(2, 3))[0].cpu()


def test_white_noise_is_region_independent():
    a = S.white_noise(-37, 11, 64, 64, 3, 0, DEV)
    b = S.white_noise(-70, -20, 200, 200, 3, 0, DEV)[33:97, 31:95]
    assert torch.equal(a, b)
    assert abs(a.std().item() - 1.0) < 0.15


def test_synth_is_region_independent():
    """Two requests cut from one world lattice agree on their overlap."""
    torch.manual_seed(0)
    n = 96 + 2 * M
    coarse = torch.nn.functional.interpolate(torch.randn(1, 1, 12, 12, device=DEV) * 300, size=(n, n), mode="bilinear")
    desc = _uniform_desc([math.log(8.0)] * 5, ridge=0.5, c=0.2, s=-0.1, n=n)
    desc[:, 0] += torch.linspace(-1, 1, n, device=DEV).view(1, -1)
    full = S.synth(coarse, desc, (0, 0), 5)  # interior cells [M, M+96) -> px [0, 768)
    # Sub-request: interior cells [M+32, M+64) of the same lattice -> px [256, 512).
    part = S.synth(coarse[..., 32 : 32 + 32 + 2 * M, 32 : 32 + 32 + 2 * M],
                   desc[..., 32 : 32 + 32 + 2 * M, 32 : 32 + 32 + 2 * M], (256, 256), 5)
    diff = (full[..., 256:512, 256:512] - part).abs()
    print(f"region-independence max diff {diff.max().item():.4f} m, mean {diff.mean().item():.5f} m")
    assert diff.max().item() < 0.25


# Spectra steeper than ~2x per octave are infeasible by construction: band leakage alone
# exceeds the finer targets. Real DEMs never produce them (the extractor measures real
# fields with the same leakage), so only realistic spectra are asserted here.
@pytest.mark.parametrize("amps", [(4.0, 8.0, 16.0, 24.0, 40.0), (2.0, 4.5, 10.0, 22.0, 45.0), (10.0, 5.0, 2.0, 1.0, 0.5)])
def test_amplitudes_round_trip(amps):
    target = [math.log(a + D.AMP_EPS) for a in amps]
    m = _measure(_uniform_desc(target))
    got = torch.exp(m[: S.SYNTH_BANDS]) - D.AMP_EPS
    rel = ((got - torch.tensor(amps)) / torch.tensor(amps)).abs()
    print("amps target", amps, "got", [round(x, 2) for x in got.tolist()])
    assert rel.max().item() < 0.15


def test_ridge_round_trip_and_gain():
    base = [math.log(a) for a in (4.0, 8.0, 16.0, 24.0, 40.0)]
    for r in (-1.0, -0.5, 0.5, 1.0):
        m = _measure(_uniform_desc(base, ridge=r))
        print(f"ridge target {r:+.2f} got {m[5].item():+.3f}")
        assert abs(m[5].item() - r) < 0.2 + 0.2 * abs(r)
    flat = _measure(_uniform_desc(base, ridge=0.0))
    assert abs(flat[5].item()) < 0.1


def test_aniso_round_trip():
    base = [math.log(a) for a in (4.0, 8.0, 16.0, 24.0, 40.0)]
    for k, th in ((0.3, 0.0), (0.5, math.pi / 3), (0.6, -math.pi / 5)):
        c, s = k * math.cos(2 * th), k * math.sin(2 * th)
        m = _measure(_uniform_desc(base, c=c, s=s))
        print(f"aniso target ({c:+.2f},{s:+.2f}) got ({m[6].item():+.3f},{m[7].item():+.3f})")
        assert abs(m[6].item() - c) < 0.12 and abs(m[7].item() - s) < 0.12
    iso = _measure(_uniform_desc(base))
    assert math.hypot(iso[6].item(), iso[7].item()) < 0.08


def test_calibration_constants_report():
    """Prints fresh values for RIDGE_GAIN / ANISO_KMAX (not an assertion of them)."""
    base = [math.log(a) for a in (4.0, 8.0, 16.0, 24.0, 40.0)]
    saved = S.RIDGE_GAIN
    try:
        S.RIDGE_GAIN = 1.0
        m = _measure(_uniform_desc(base, ridge=0.8))
        print(f"measured ridge gain: {m[5].item() / 0.8:.3f} (current {saved})")
    finally:
        S.RIDGE_GAIN = saved
    saved_k = S.ANISO_KMAX
    try:
        S.ANISO_KMAX = 1e-6  # force fully oriented
        m = _measure(_uniform_desc(base, c=1.0, s=0.0))
        print(f"measured aniso kmax: {math.hypot(m[6].item(), m[7].item()):.3f} (current {saved_k})")
    finally:
        S.ANISO_KMAX = saved_k
