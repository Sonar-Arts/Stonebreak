"""D8 routing + accumulation, through the same fill -> route -> accumulate chain
DaedalusTGM-Exp's training-data build runs (`terrain_slm/data/build.py`,
`build_hydrology`): default-epsilon fill, no flat resolution.
"""
from __future__ import annotations

import numpy as np
import pytest

from terrain_slm.data.hydrology import fixtures as fx
from terrain_slm.data.hydrology.fill import fill_depressions, ocean_mask
from terrain_slm.data.hydrology.flow import d8_receivers, flow_accumulation


def route(z, weights=None):
    ocean = ocean_mask(z.astype(np.float64))
    filled = fill_depressions(z, invalid=ocean)
    recv = d8_receivers(filled, ocean)
    acc = flow_accumulation(recv, ~ocean, weights=weights)
    return ocean, filled, recv, acc


def test_inclined_plane_accumulation_is_exact():
    """On a pure downslope, the upslope count at row i must be exactly i + 1.

    Regression guard for the two boundary bugs in upstream's `d8_flow`: treating the
    array edge as a sink strands row 0 and the outer columns at 1.0, and clipping the
    receiver index instead makes edge cells point at themselves.
    """
    f = fx.inclined_plane()
    _, _, _, acc = route(f.z)
    np.testing.assert_array_equal(acc, f.expected_accumulation)


@pytest.mark.parametrize("factory", fx.ALL_FIXTURES, ids=lambda f: f.__name__)
def test_water_is_conserved(factory):
    """Every land cell contributes 1.0 and leaves through exactly one outlet, so the
    accumulation over terminal cells has to equal the land-cell count."""
    f = factory()
    ocean, _, recv, acc = route(f.z)
    terminal = (recv.reshape(f.z.shape) < 0) & ~ocean
    assert acc[terminal].sum() == pytest.approx(float((~ocean).sum()))


@pytest.mark.parametrize("factory", fx.ALL_FIXTURES, ids=lambda f: f.__name__)
def test_nothing_flows_uphill_on_the_filled_surface(factory):
    f = factory()
    _, filled, recv, _ = route(f.z)
    flat = filled.ravel()
    live = np.flatnonzero(recv >= 0)
    assert (flat[recv[live]] <= flat[live]).all()


def test_weights_scale_the_accumulation():
    """Precipitation weighting (the build's `precip_mm` path) is linear in the weights."""
    f = fx.inclined_plane()
    _, _, _, unit = route(f.z)
    _, _, _, doubled = route(f.z, weights=np.full(f.z.shape, 2.0))
    np.testing.assert_allclose(doubled, 2.0 * unit)
