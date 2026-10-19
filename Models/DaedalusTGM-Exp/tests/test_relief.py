"""Controls v2 (mountain ranges), the relief sampler's tiling, and the summit soft cap."""
from pathlib import Path

import pytest
import torch

from terrain_slm.models import relief as RL
from terrain_slm.world import generator as G

MODEL = Path(__file__).resolve().parents[1] / "checkpoints/v3"
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")


def test_controls_raise_real_mountain_ranges():
    # 1024 cells = 245 km around spawn: some ground must be alpine, most land must not be.
    c = G.procedural_controls(-512, -512, 1024, 1024, seed=2, device=DEV)
    land = c["trend"] > 0
    high = (c["trend"][land] > 2000).float().mean().item()
    assert c["trend"].max().item() > 3000
    assert 0.01 < high < 0.4


def test_controls_are_pointwise_in_cell_coordinates():
    big = G.procedural_controls(-64, -64, 128, 128, seed=5, device=DEV)
    small = G.procedural_controls(-10, 7, 20, 30, seed=5, device=DEV)
    for k in ("trend", "wild", "t0", "precip"):
        assert torch.allclose(small[k], big[k][54:74, 71:101], atol=1e-3), k


def test_soft_cap_is_monotone_bounded_and_identity_below_the_knee():
    e = torch.linspace(-500.0, 20000.0, 4001)
    out = G.soft_cap_peaks(e)
    assert torch.all(out[1:] >= out[:-1])
    assert out.max().item() < G.PEAK_KNEE_M + G.PEAK_SPAN_M
    below = e <= G.PEAK_KNEE_M
    assert torch.equal(out[below], e[below])


def test_relief_unet_accepts_any_multiple_of_its_stride():
    m = RL.Relief().eval()
    for n in (m.multiple * 3, m.multiple * 5):
        x = torch.zeros(1, 1, n, n)
        cond = torch.zeros(1, RL.N_COND, n, n)
        assert m(x, torch.zeros(1), cond).shape == x.shape


@pytest.mark.skipif(not (MODEL / "relief.pt").exists(), reason="no packaged v2 model")
def test_relief_trend_is_request_independent_across_window_seams():
    gen = G.WorldGenerator(MODEL, seed=3, device=DEV)
    # Straddles the window-core boundary at cell 256 on both axes.
    big = gen.trend(200, 200, 112, 112)
    small = gen.trend(240, 250, 40, 30)
    assert (small - big[40:80, 50:80]).abs().max().item() < 1e-2
    # The sampler adds relief: the trend is no longer the smooth procedural one.
    raw = G.procedural_controls(200, 200, 112, 112, 3, DEV)["trend"]
    assert (big - raw).std().item() > 1.0
