"""Tests for `fill_depressions(max_raise=...)`, the bounded-basin fill.

Kept out of `test_hydrology.py` so that file stays the recovered package's own
baseline. This knob was added on recovery, for section 4.2 of
`Dev Working/Rivers and lakes plan.md`: a basin bigger than the window it is
solved in must not fill to wildly different levels depending on the window.

The property that buys that is *locality* -- a basin's level must be a function
of that basin's own cells and nothing else. Upstream's in-heap `max_raise`
measures against the running minimum of the flood path instead, so it fails all
three of the closed-form cases below; those tests are the regression guard.
"""
from __future__ import annotations

import numpy as np
import pytest
from scipy import ndimage

from hydrology import fixtures as fx
from hydrology.fill import fill_depressions

CAPS = [30.0, 10.0, 2.0, 0.5]
DEMS = [
    pytest.param(fx.paraboloid_crater().z, id="crater"),
    pytest.param(fx.two_basins_saddle().z, id="two_basins"),
]


def _basins(z, filled):
    """(label array, count) over the wet cells, 8-connected -- one label per lake."""
    return ndimage.label(filled > z, structure=np.ones((3, 3), dtype=int))


def test_no_cap_is_the_default():
    """Omitting the knob must not perturb the uncapped fill by a single bit."""
    z = fx.paraboloid_crater().z
    np.testing.assert_array_equal(
        fill_depressions(z, epsilon=0.0),
        fill_depressions(z, epsilon=0.0, max_raise=None),
    )


def test_a_cap_far_above_any_relief_is_a_no_op():
    """The capped path must degenerate exactly, not approximately, to the plain fill."""
    z = fx.paraboloid_crater().z
    np.testing.assert_array_equal(
        fill_depressions(z, epsilon=0.0),
        fill_depressions(z, epsilon=0.0, max_raise=1e18),
    )


@pytest.mark.parametrize("z", DEMS)
@pytest.mark.parametrize("cap", CAPS)
def test_cap_bounds_water_depth_everywhere(z, cap):
    """`filled - z <= max_raise` at every cell. This is the knob's whole contract.

    Upstream's version fails this: the crater fills 40 m under a 30 m cap, because
    the flood only stops one cell *after* the running minimum crosses the limit.
    """
    filled = fill_depressions(z, epsilon=0.0, max_raise=cap)
    assert (filled - z).max() <= cap + 1e-9


@pytest.mark.parametrize("z", DEMS)
@pytest.mark.parametrize("cap", CAPS)
def test_cap_only_ever_lowers_water(z, cap):
    """A cap must sit between the bare ground and the uncapped fill, never outside."""
    uncapped = fill_depressions(z, epsilon=0.0)
    capped = fill_depressions(z, epsilon=0.0, max_raise=cap)
    assert (capped >= z - 1e-12).all()
    assert (capped <= uncapped + 1e-9).all()


@pytest.mark.parametrize("z", DEMS)
@pytest.mark.parametrize("cap", CAPS)
def test_capped_lake_surfaces_stay_level(z, cap):
    """Standing water is flat. A cap that tilts a lake would drain at runtime.

    Upstream's version tilts them, because the level it assigns a cell depends on
    the flood path that reached it rather than on the basin.
    """
    capped = fill_depressions(z, epsilon=0.0, max_raise=cap)
    labels, n = _basins(z, capped)
    for i in range(1, n + 1):
        assert np.ptp(capped[labels == i]) < 1e-9, f"basin {i} is not level"


@pytest.mark.parametrize("cap", CAPS)
def test_a_basin_perched_above_lower_ground_still_fills(cap):
    """The crater rim is ~500 m; the plain outside it falls to ~54 m.

    Upstream's version leaves this crater bone dry at every cap, because by the
    time the flood climbs to the rim its running minimum is the plain 446 m below,
    and `elev - minimum >= max_raise` is already true. The cap must be measured
    against the basin's floor, not against wherever the water came from.
    """
    z = fx.paraboloid_crater().z
    filled = fill_depressions(z, epsilon=0.0, max_raise=cap)
    assert (filled - z).max() == pytest.approx(cap)


@pytest.mark.parametrize("cap", CAPS)
def test_deep_basin_is_capped_and_shallow_one_is_untouched(cap):
    """`two_basins_saddle` fills to 30 m and 10 m. A cap must bite per basin.

    This is the one that matters for section 4.2: capping is not a global lowering
    of the water table, it is a per-basin bound, so basins that already fit are
    left exactly as the uncapped solve found them.
    """
    z = fx.two_basins_saddle().z
    uncapped = fill_depressions(z, epsilon=0.0)
    capped = fill_depressions(z, epsilon=0.0, max_raise=cap)

    labels, n = _basins(z, uncapped)
    assert n == 2, "fixture is supposed to produce exactly two basins"
    for i in range(1, n + 1):
        cells = labels == i
        depth = (uncapped - z)[cells].max()
        expected = min(depth, cap)
        assert (capped - z)[cells].max() == pytest.approx(expected)
        if depth <= cap:
            np.testing.assert_array_equal(capped[cells], uncapped[cells])


@pytest.mark.parametrize("cap", CAPS)
def test_cap_is_window_independent(cap):
    """The point of the knob: crop the DEM and a contained basin is unchanged.

    Run the same crater under two different bounding boxes. Where a basin lies
    wholly inside both, the capped level has to agree cell for cell -- otherwise
    two adjacent macro-tiles disagree about a shared lake and the seam is visible.
    """
    z = fx.paraboloid_crater().z
    wide = fill_depressions(z, epsilon=0.0, max_raise=cap)
    inner = slice(8, -8)
    narrow = fill_depressions(z[inner, inner], epsilon=0.0, max_raise=cap)

    # Only compare away from the crop edge, where the narrow window genuinely has
    # less terrain to work with; the crater itself is well inside both.
    core = slice(16, -16)
    np.testing.assert_allclose(narrow[8:-8, 8:-8][core, core], wide[16:-16, 16:-16][core, core])


def test_cap_must_be_positive():
    z = fx.paraboloid_crater().z
    with pytest.raises(ValueError, match="max_raise"):
        fill_depressions(z, max_raise=0.0)
    with pytest.raises(ValueError, match="max_raise"):
        fill_depressions(z, max_raise=-5.0)


@pytest.mark.parametrize("cap", CAPS)
def test_capped_fill_is_deterministic(cap):
    """Byte-identical across runs, like the uncapped fill. Seams come back otherwise."""
    z = fx.paraboloid_crater().z
    a = fill_depressions(z, epsilon=0.0, max_raise=cap)
    b = fill_depressions(z, epsilon=0.0, max_raise=cap)
    np.testing.assert_array_equal(a, b)


@pytest.mark.parametrize("cap", CAPS)
def test_ocean_is_untouched_by_a_cap(cap):
    """Ocean cells are returned as-is, so `filled - z` stays clean at zero there."""
    z = fx.paraboloid_crater().z.copy()
    z[:4, :] = -100.0
    filled = fill_depressions(z, epsilon=0.0, max_raise=cap)
    np.testing.assert_array_equal(filled[:4, :], z[:4, :])
