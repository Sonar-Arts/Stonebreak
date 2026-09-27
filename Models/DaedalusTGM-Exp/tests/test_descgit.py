"""Descriptor sampler (MaskGIT): codebook fidelity, deterministic sampling, and the checkerboard
tiling's request independence."""
from pathlib import Path

import pytest
import torch

from terrain_slm.models import descgit as G

MODEL = Path(__file__).resolve().parents[1] / "checkpoints/v3"
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
needs_model = pytest.mark.skipif(not (MODEL / "descgit.pt").exists(), reason="no packaged v3 model")


def test_codebook_round_trip_is_close_on_its_own_centroids():
    g = torch.Generator().manual_seed(0)
    cb = G.Codebook(torch.randn(G.K_AMP, 5, generator=g), torch.randn(G.K_SHAPE, 3, generator=g))
    a = torch.randint(0, G.K_AMP, (2, 8, 8), generator=g)
    s = torch.randint(0, G.K_SHAPE, (2, 8, 8), generator=g)
    a2, s2 = cb.encode(cb.decode(a, s))
    assert torch.equal(a, a2) and torch.equal(s, s2)


def test_sampling_is_deterministic_and_keeps_known_tokens():
    m = G.DescGit().eval()
    x = torch.randn(1, 11, 32, 32)
    a = torch.randint(0, G.K_AMP, (1, 32, 32))
    s = torch.randint(0, G.K_SHAPE, (1, 32, 32))
    known = torch.zeros(1, 32, 32, dtype=torch.bool)
    known[:, :8] = True
    keys = torch.arange(32 * 32, dtype=torch.int64).view(1, 32, 32) * 7919
    r1 = G.sample(m, x, a, s, known, keys, steps=4)
    r2 = G.sample(m, x, a, s, known, keys, steps=4)
    assert torch.equal(r1[0], r2[0]) and torch.equal(r1[1], r2[1])
    assert torch.equal(r1[0][known], a[known]) and torch.equal(r1[1][known], s[known])
    assert (r1[0] < G.K_AMP).all() and (r1[1] < G.K_SHAPE).all(), "a cell was left masked"


@needs_model
def test_sampled_descriptors_are_request_independent_across_window_cores():
    from terrain_slm.world.generator import WorldGenerator
    gen = WorldGenerator(MODEL, seed=11, device=DEV)
    big = gen.sampled_desc(20, -50, 80, 90)
    small = gen.sampled_desc(47, -20, 20, 30)   # straddles core boundaries at 64 and -32
    assert (small - big[:, 27:47, 30:60]).abs().max().item() < 1e-3


@needs_model
def test_later_phase_windows_honour_earlier_neighbours():
    from terrain_slm.world.generator import DG_CORE, WorldGenerator
    gen = WorldGenerator(MODEL, seed=11, device=DEV)
    # Window (1, 1) is phase 3: it must not change its phase-0 neighbour (0, 0)'s core.
    before = gen._dg_window(0, 0)
    gen._dg_window(1, 1)
    after = gen._dg_window(0, 0)
    assert torch.equal(before[0], after[0]) and before[0].shape == (DG_CORE, DG_CORE)
