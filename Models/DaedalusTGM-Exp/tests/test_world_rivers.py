"""Generated terrain with rivers: request-shape independence and no spills on real output.
Skips when no packaged model is present (checkpoints/v2)."""
from pathlib import Path

import pytest
import torch

from terrain_slm.world.generator import WorldGenerator

MODEL = Path(__file__).resolve().parents[1] / "checkpoints/v2"
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
pytestmark = pytest.mark.skipif(not (MODEL / "planner.pt").exists(), reason="no packaged model")


@pytest.fixture(scope="module")
def gen():
    return WorldGenerator(MODEL, seed=7, device=DEV)


def test_tile_equals_the_same_ground_inside_a_bigger_request(gen):
    small_e, small_w = gen.terrain(512, -256, 640, -128)
    big_e, big_w = gen.terrain(384, -384, 768, 0)
    e = big_e[128:256, 128:256]
    w = big_w[128:256, 128:256]
    assert (small_e - e).abs().max().item() < 0.05
    assert torch.equal(torch.isnan(small_w), torch.isnan(w))
    both = ~torch.isnan(w)
    if both.any():
        assert (small_w[both] - w[both]).abs().max().item() < 0.05


def test_generated_rivers_exist_and_never_spill(gen):
    elev, surf = gen.terrain(-1024, -1024, 1024, 1024)
    wet = ~torch.isnan(surf)
    assert wet.float().mean().item() > 0.001, "no rivers in a 61 km square"
    top = torch.nan_to_num(surf, nan=-1e9)
    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nb_wet = torch.roll(wet, (di, dj), (0, 1))
        nb_ground = torch.roll(elev, (di, dj), (0, 1))
        inner = torch.zeros_like(wet)
        inner[1:-1, 1:-1] = True
        assert int((wet & ~nb_wet & (nb_ground < top - 1e-3) & inner).sum()) == 0
