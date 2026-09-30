"""Augmentation must transform descriptors and D8 exactly like re-measuring the
transformed terrain would."""
import itertools

import numpy as np
import pytest
import torch

from terrain_slm.data import descriptors as D
from terrain_slm.train.planner_data import D8_OFFSETS, PlannerData, _d8_permutation

DEV = "cuda:1" if torch.cuda.device_count() > 1 else "cpu"
ROOT = __import__("pathlib").Path(__file__).resolve().parents[1] / "data/alps"
needs_data = pytest.mark.skipif(not (ROOT / "cells.npz").exists(), reason="dataset not built")


def _apply(x, tr, fr, fc):
    if tr:
        x = x.transpose(-1, -2)
    if fr:
        x = x.flip(-2)
    if fc:
        x = x.flip(-1)
    return x


@needs_data
@pytest.mark.parametrize("ops", list(itertools.product([False, True], repeat=3)))
def test_dihedral_matches_remeasurement(ops):
    dem = np.load(ROOT / "dem.npy", mmap_mode="r")
    z = torch.from_numpy(np.ascontiguousarray(dem[4000:4512, 9000:9512])).float().to(DEV)
    d_orig = D.extract(z)[0]
    d_re = D.extract(_apply(z, *ops).contiguous())[0]
    # Build a fake 15-channel field stack with desc at 1..8 and transform it.
    f = torch.zeros(15, 64, 64, device=DEV)
    f[1:9] = d_orig
    pd = PlannerData.__new__(PlannerData)
    got = pd._dihedral(f, ops)[1:9]
    inner = (slice(None), slice(12, -12), slice(12, -12))
    err = (got - d_re)[inner].abs().amax(dim=(1, 2))
    assert err[6].item() < 1e-3 and err[7].item() < 1e-3, err
    assert err.max().item() < 1e-3, err


@pytest.mark.parametrize("ops", list(itertools.product([False, True], repeat=3)))
def test_d8_permutation_follows_offsets(ops):
    tr, fr, fc = ops
    perm = _d8_permutation(fr, fc, tr)
    grid = torch.zeros(5, 5)
    for k, (dr, dc) in enumerate(D8_OFFSETS):
        g = torch.zeros(5, 5)
        g[2 + dr, 2 + dc] = 1.0
        moved = _apply(g, tr, fr, fc)
        r, c = [int(v) for v in torch.nonzero(moved)[0]]
        assert D8_OFFSETS[int(perm[k])] == (r - 2, c - 2)
    assert int(perm[8]) == 8
