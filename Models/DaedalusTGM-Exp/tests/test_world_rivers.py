"""Generated terrain with rivers: request-shape independence and no spills on real output.
Skips when the release weights are not present (checkpoints/exp2-r3, Git LFS)."""
from pathlib import Path

import pytest
import torch

from terrain_slm.world.generator2 import WorldGenerator2

from terrain_slm.model_files import DEFAULT_MODEL, lfs_pointers

MODEL = Path(__file__).resolve().parents[1] / DEFAULT_MODEL     # the release (EXP-1's v3 was removed 2026-11-19)
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
pytestmark = pytest.mark.skipif(not (MODEL / "planner.pt").exists() or bool(lfs_pointers(MODEL)),
                                reason="no release weights (git lfs pull)")


@pytest.fixture(scope="module")
def gen():
    gen = WorldGenerator2(MODEL, seed=7, device=DEV)   # the service's generator
    gen.use_climate_layout("belts")
    return gen


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
        # River mouths: water at or below sea level (0 m) beside a sea cell runs into the sea, which the tile's water
        # rule fills; it is not a spill. (EXP-2 has coasts near spawn; the v3 square this was written for had none.)
        mouth = (nb_ground < 0.0) & (top <= 0.0)
        assert int((wet & ~nb_wet & (nb_ground < top - 1e-3) & inner & ~mouth).sum()) == 0
