"""EXP-2 controls: pointwise (any window is exact), bounded gates, temperate spawn, v4's trend untouched underneath."""
import pytest
import torch

from terrain_slm.world import controls2 as C2
from terrain_slm.world import generator as G

DEV = "cuda:0" if torch.cuda.is_available() else "cpu"


def test_windows_agree_pointwise():
    big = C2.controls2(-300, 200, 400, 300, 11, DEV)
    small = C2.controls2(-250, 260, 100, 120, 11, DEV)
    for k in ("trend", "lat", "belt", "wind_u", "wind_v", "coast"):
        assert torch.equal(big[k][50:150, 60:180], small[k]), k
    assert torch.equal(big["gates"][:, 50:150, 60:180], small["gates"])


def test_gates_bounded_and_spawn_temperate():
    c = C2.controls2(-64, -64, 128, 128, 3, DEV)
    assert c["gates"].min() >= 0 and c["gates"].max() <= 1
    phi0 = float(c["lat"][64, 64])
    assert abs(phi0 - C2.PHI_HOME_DEG) < 15, phi0      # warp moves it a little


def test_v4_trend_is_kept_underneath():
    c = C2.controls2(0, 0, 64, 64, 5, DEV)
    v4 = G.procedural_controls(0, 0, 64, 64, 5, DEV)
    assert torch.equal(c["trend_v4"], v4["trend"])


def test_hotspots_are_round_cones():
    ci = torch.arange(-2000, 2000, 4, device=DEV, dtype=torch.float32).view(-1, 1).expand(1000, 1000)
    cj = ci.t()
    h = C2.hotspots(ci, cj, 9)
    assert 0 < float((h > 0.5).float().mean()) < 0.05            # sparse
    assert float(h.max()) > 0.99                                   # cones peak at 1
