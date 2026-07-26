import os

import numpy as np
import pytest

from bridge.height_mapping import HeightCurve

# The Phase 5 defaults, mirrored from BridgeConfig so a knob default changing
# without the regression values below being revisited shows up as a failure.
_DEFAULTS = dict(
    ocean_meters_per_block=12.0,
    lowland_meters_per_block=4.0,
    midland_meters_per_block=10.0,
    highland_meters_per_block=24.0,
    lowland_top_m=600.0,
    highland_base_m=2000.0,
    shore_blend_m=60.0,
    midland_blend_m=150.0,
    highland_blend_m=300.0,
    sea_level=320,
    world_height=1024,
)


def _curve(**overrides) -> HeightCurve:
    return HeightCurve(**{**_DEFAULTS, **overrides})


def test_config_defaults_match_the_curve_defaults_used_here(monkeypatch):
    from bridge.config import BridgeConfig

    for name in [k for k in os.environ if k.startswith("TERRAIN_BRIDGE_")]:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("TERRAIN_BRIDGE_SEED", "1")  # required, unrelated to the curve
    assert HeightCurve.from_config(BridgeConfig.from_env()) == _curve()


def test_sea_level_maps_to_configured_sea_level():
    # Exact, by construction: the integral is anchored at elevation 0.
    curve = _curve()
    assert curve.to_block_height_exact(np.array([0.0]))[0] == 320.0
    assert curve.to_block_height(np.array([[0]], dtype=np.int16))[0, 0] == 320


def test_strictly_monotone_across_full_input_range():
    curve = _curve()
    elev = np.arange(-20000.0, 20000.0, 3.0)  # deliberately off the table's 1 m grid
    exact = curve.to_block_height_exact(elev)
    assert np.all(np.diff(exact) > 0), "curve must be strictly increasing before quantisation"
    assert np.all(np.diff(curve.to_block_height(elev)) >= 0), "quantised output must not invert"


def test_clamped_to_world_height_ceiling_and_floor():
    curve = _curve()
    out = curve.to_block_height(np.array([[20000, -10000]], dtype=np.int16))
    assert out[0, 0] == 1023
    assert out[0, 1] == 0


def test_table_endpoints_fall_outside_the_block_column():
    # np.interp clamps to the endpoint value outside its grid. If the table did
    # not already reach past 0 and world_height-1, an elevation beyond the grid
    # would silently resolve to an in-range block height instead of clamping.
    curve = _curve()
    assert curve.to_block_height_exact(np.array([-20000.0]))[0] <= 0.0
    assert curve.to_block_height_exact(np.array([20000.0]))[0] >= curve.world_height - 1


def test_curve_too_shallow_to_span_the_column_is_rejected():
    with pytest.raises(ValueError, match="does not span the block column"):
        _curve(ocean_meters_per_block=5000.0).to_block_height_exact(np.array([0.0]))


@pytest.mark.parametrize(
    "elev_m,expected_rate",
    [
        (-5000.0, 12.0),  # ocean floor
        (-500.0, 12.0),
        (100.0, 4.0),  # lowland, just past the shore blend
        (300.0, 4.0),
        (450.0, 4.0),  # last metre before the midland blend opens
        (1300.0, 7.0),  # ramp midpoint, halfway from 4 to 10
        (3000.0, 24.0),  # highland, past the blend
        (9000.0, 24.0),
    ],
)
def test_band_interiors_hold_their_configured_rate(elev_m, expected_rate):
    # The guard that rejected the sum-of-logistics form, whose tails never
    # vanish and which bottomed the lowland band out at 4.46 m/block.
    assert _curve().rate(np.array([elev_m]))[0] == pytest.approx(expected_rate, abs=1e-9)


@pytest.mark.parametrize(
    "elev_m,expected_block",
    [
        (-2000.0, 152),  # ocean
        (-500.0, 277),
        (300.0, 392),  # lowland
        (600.0, 467),
        (1400.0, 611),  # ramp
        (5000.0, 802),  # highland
        (8848.0, 962),  # Everest, still 61 blocks under the 1023 ceiling
    ],
)
def test_regression_value_per_band(elev_m, expected_block):
    assert _curve().to_block_height(np.array([elev_m]))[0] == expected_block


def test_first_difference_is_continuous_across_every_knot():
    # The terracing guard. A piecewise-linear curve steps its first difference
    # at each knot, and a block-quantised world renders that as terraces
    # following elevation contours. An integrated rate function must not.
    curve = _curve()
    elev = np.arange(-20000.0, 20001.0, 1.0)
    second_diff = np.abs(np.diff(np.diff(curve.to_block_height_exact(elev))))
    assert second_diff.max() < 1e-2

    for knot in (0.0, _DEFAULTS["lowland_top_m"], _DEFAULTS["highland_base_m"]):
        i = int(knot - elev[0])
        assert second_diff[i - 400 : i + 400].max() < 1e-2


def test_first_difference_test_would_catch_a_piecewise_linear_curve():
    # Sanity-check the threshold above against the shape it exists to reject:
    # the same knots joined linearly spike the second difference by ~1000x.
    knots_e = np.array([-20000.0, 0.0, 600.0, 2000.0, 20000.0])
    knots_y = np.array([-1347.0, 320.0, 467.0, 676.0, 1427.0])
    elev = np.arange(-20000.0, 20001.0, 1.0)
    piecewise = np.interp(elev, knots_e, knots_y)
    assert np.abs(np.diff(np.diff(piecewise))).max() > 1e-2


def test_deterministic_no_per_tile_normalization():
    # Same elevation must map to the same block height regardless of what else
    # is in the tile — the whole point is no per-tile min/max, or cross-tile
    # seams reappear (plan.md section 5, Phase 1 item 5).
    curve = _curve()
    tile_a = np.array([[100, 100, 100]], dtype=np.int16)
    tile_b = np.array([[100, 5000, -5000]], dtype=np.int16)
    assert curve.to_block_height(tile_a)[0, 0] == curve.to_block_height(tile_b)[0, 0]


def test_table_re_derives_identically_from_the_same_knobs():
    # The table is memoized, but it must be a pure function of the knobs so a
    # fresh process reproduces it byte for byte (statelessness, plan §2.1).
    elev = np.arange(-3000.0, 9000.0, 7.0)
    assert np.array_equal(_curve().to_block_height(elev), _curve().to_block_height(elev))


@pytest.mark.parametrize(
    "overrides,match",
    [
        (dict(lowland_meters_per_block=0.0), "must be > 0"),
        (dict(shore_blend_m=-1.0), "must be > 0"),
        (dict(lowland_top_m=3000.0), "must be below"),
        (dict(shore_blend_m=500.0), "shore and midland blend windows overlap"),
        # Widened from above, not below: a large midland_blend_m trips the
        # shore check first, so it cannot isolate this one.
        (dict(highland_blend_m=1400.0), "midland and highland blend windows overlap"),
    ],
)
def test_invalid_knobs_are_rejected(overrides, match):
    with pytest.raises(ValueError, match=match):
        _curve(**overrides)


# --------------------------------------------------------------------------------
# to_elevation -- the inverse, used to read a tile cache back as a DEM
# --------------------------------------------------------------------------------

def test_to_elevation_inverts_sea_level_exactly():
    curve = _curve()
    assert curve.to_elevation(np.array([curve.sea_level]))[0] == pytest.approx(0.0, abs=1e-9)


def test_to_elevation_is_strictly_increasing_across_the_block_column():
    curve = _curve()
    elev = curve.to_elevation(np.arange(0, _DEFAULTS["world_height"]))
    assert np.all(np.diff(elev) > 0.0)


def test_to_elevation_recovers_the_bottom_of_each_block_band():
    """`to_block_height` floors, so the inverse can only return the band's floor.

    The recovered elevation must therefore never exceed the original, and must
    fall short by less than the width of that block's elevation band. By the mean
    value theorem the band's width is `rate` somewhere inside it, so the rate at
    the two ends bounds it -- taking only the end the sample happens to sit at is
    not enough where the rate moves fast within one block, which it does across
    the shoreline blend (12 m/block to 4 m/block over 120 m; see plan section
    10.6). This is the bound `hydrology/io.py` relies on when it calls a tile
    cache a usable DEM.
    """
    curve = _curve()
    elev = np.linspace(-3000.0, 9000.0, 40001)
    blocks = curve.to_block_height(elev)
    back = curve.to_elevation(blocks)

    unclamped = (blocks > 0) & (blocks < _DEFAULTS["world_height"] - 1)
    shortfall = (elev - back)[unclamped]
    band = np.maximum(curve.rate(back), curve.rate(elev))[unclamped]
    assert (shortfall >= -1e-6).all(), "inverse overshot the original elevation"
    assert (shortfall <= band + 1e-6).all()


def test_to_elevation_disagrees_with_the_pre_phase5_linear_mapping():
    """Guard for `hydrology/io.py`: the old inverse is not a valid substitute.

    Reading Phase-5 tiles back with `(blocks - sea_level) * meters_per_block` --
    what `stitch_tile_cache` used to do -- overstates lowland elevation several
    times over. If this ever stops failing, the curve has quietly gone linear.
    """
    curve = _curve()
    blocks = np.arange(curve.sea_level, curve.sea_level + 100)
    linear = (blocks - curve.sea_level) * 15.0
    assert curve.to_elevation(blocks)[-1] < 0.5 * linear[-1]
