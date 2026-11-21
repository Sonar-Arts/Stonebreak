import numpy as np
import pytest

from terrain_slm.world.height_curve import HeightCurve
from terrain_slm.world.world_config import WorldConfig

# A fixed knob set the regression values below were computed against (the pre-1:4 curve).
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


# WorldConfig keys added with curve v2 (2026-11-19): an older game sends none of them.
from terrain_slm.world.world_config import CURVE_V2_KEYS as _CURVE_V2_KEYS  # noqa: E402


def _curve(**overrides) -> HeightCurve:
    return HeightCurve(**{**_DEFAULTS, **overrides})


def test_world_config_maps_every_knob():
    w = WorldConfig.GAME
    c = HeightCurve.from_world(w)
    assert (c.ocean_meters_per_block, c.lowland_meters_per_block, c.midland_meters_per_block,
            c.highland_meters_per_block) == (w.ocean_m_per_block, w.lowland_m_per_block,
                                             w.midland_m_per_block, w.highland_m_per_block)
    assert (c.lowland_top_m, c.highland_base_m, c.shore_blend_m, c.midland_blend_m, c.highland_blend_m) == (
        w.lowland_top_m, w.highland_base_m, w.shore_blend_m, w.midland_blend_m, w.highland_blend_m)
    assert (c.sea_level, c.world_height) == (w.sea_level, w.world_height)


def test_world_config_round_trips_and_rejects_drift():
    w = WorldConfig.GAME
    assert WorldConfig.from_dict(w.to_dict()) == w
    with pytest.raises(ValueError, match="missing"):
        WorldConfig.from_dict({k: v for k, v in w.to_dict().items() if k != "sea_level"})
    with pytest.raises(ValueError, match="unknown"):
        WorldConfig.from_dict({**w.to_dict(), "sea_levle": 64})


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
    10.6). This is the bound a caller relies on when it calls a tile
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
    """Guard: the old linear inverse is not a valid substitute.

    Reading Phase-5 tiles back with `(blocks - sea_level) * meters_per_block` --
    what `stitch_tile_cache` used to do -- overstates lowland elevation several
    times over. If this ever stops failing, the curve has quietly gone linear.
    """
    curve = _curve()
    blocks = np.arange(curve.sea_level, curve.sea_level + 100)
    linear = (blocks - curve.sea_level) * 15.0
    assert curve.to_elevation(blocks)[-1] < 0.5 * linear[-1]


# ---- min_y (the cubic world spans [-384, 640); heights are absolute block y) -----------------------------------

def test_world_config_min_y_is_optional_and_keeps_old_fingerprints():
    import hashlib
    import json

    w = WorldConfig.GAME
    assert w.min_y == 0
    old = {k: v for k, v in w.to_dict().items() if k not in ("min_y", "climate_layout") + _CURVE_V2_KEYS}   # older game
    assert WorldConfig.from_dict(old) == w
    # The default keeps the pre-min_y fingerprint (cache namespaces stay valid).
    assert w.fingerprint() == hashlib.sha1(json.dumps(old, sort_keys=True).encode()).hexdigest()[:12]
    cubic = WorldConfig.from_dict({**old, "world_height": 640, "min_y": -384})
    assert (cubic.min_y, cubic.world_height) == (-384, 640)
    assert cubic.fingerprint() != WorldConfig.from_dict({**old, "world_height": 640}).fingerprint()
    assert WorldConfig.from_dict(cubic.to_dict()) == cubic
    with pytest.raises(ValueError, match="unknown"):
        WorldConfig.from_dict({**old, "min_yy": -384})


def test_world_config_min_y_bounds():
    old = {k: v for k, v in WorldConfig.GAME.to_dict().items() if k != "min_y"}
    with pytest.raises(ValueError, match="min_y"):
        WorldConfig.from_dict({**old, "min_y": 300})          # >= world_height 256
    with pytest.raises(ValueError, match="sea_level"):
        WorldConfig.from_dict({**old, "min_y": 100})          # sea level 64 below the floor
    WorldConfig.from_dict({**old, "min_y": -384, "sea_level": -10})   # a sea below 0 is inside the world now


def test_min_y_moves_the_floor_clamp():
    flat = _curve(sea_level=64, world_height=640)
    deep = _curve(sea_level=64, world_height=640, min_y=-384)
    trench = np.array([-3000.0, -10000.0, -60000.0])
    assert np.all(flat.to_block_height(trench) == 0)
    out = deep.to_block_height(trench)
    assert out[0] < 0 and out[1] < out[0]                    # below y 0 now, still monotone
    assert out[2] == -384                                    # clamped at the floor
    # Above the old floor nothing changes.
    elev = np.arange(-200.0, 9000.0, 7.0)
    keep = flat.to_block_height(elev) > 0
    assert np.array_equal(flat.to_block_height(elev)[keep], deep.to_block_height(elev)[keep])


def test_deep_column_widens_the_table_and_default_table_is_unchanged():
    from terrain_slm.world import height_curve as H

    # 12 m/block under sea level reaches -384 within the default +-20 km; 48 m/block (the game's ocean) does not:
    # the table widens instead of refusing.
    deep = _curve(ocean_meters_per_block=48.0, sea_level=64, world_height=640, min_y=-384)
    elev, blocks = H._curve_table(deep)
    assert blocks[0] <= -384 and elev[0] < H._TABLE_MIN_M
    assert deep.to_block_height_exact(np.array([0.0]))[0] == 64.0
    # min_y 0: the table is the one the +-20 km grid always gave (bit-identical).
    c = _curve()
    e0, b0 = H._curve_table(c)
    e1, b1 = H._integrate(c, H._TABLE_MIN_M, H._TABLE_MAX_M)
    assert np.array_equal(e0, e1) and np.array_equal(b0, b1)
    assert e0[0] == H._TABLE_MIN_M and e0[-1] == H._TABLE_MAX_M


def test_world_config_curve_carries_min_y():
    old = {k: v for k, v in WorldConfig.GAME.to_dict().items() if k != "min_y"}
    w = WorldConfig.from_dict({**old, "world_height": 640, "min_y": -384})
    assert HeightCurve.from_world(w).min_y == -384


# ---- curve v2: the shelf band (2026-11-19) -----------------------------------------------------------------------

def _game(scale: str = "1:4", shelf: bool = True) -> HeightCurve:
    """The game's curve as TerrainScale sends it (cubic column; v1, or v2 with its sea bands)."""
    return _game_world(2 if shelf else 1, scale).curve()


def _game_world(curve_version: int, scale: str = "1:4") -> WorldConfig:
    k = 0.5 if scale == "1:2" else 1.0
    d = {**{k_: v for k_, v in WorldConfig.GAME.to_dict().items() if k_ not in _CURVE_V2_KEYS},
         "world_height": 640, "min_y": -384, "horizontal_m": 60.0 * k, "downscale": 2 if k == 1.0 else 1,
         "ocean_m_per_block": 48.0 * k, "lowland_m_per_block": 16.0 * k, "midland_m_per_block": 24.0 * k,
         "highland_m_per_block": 38.0 * k}
    if curve_version == 2:
        from terrain_slm.world.world_config import curve_v2_keys
        d.update(curve_v2_keys(scale))
    return WorldConfig.from_dict(d)


def test_v1_curve_is_bit_identical_without_a_shelf():
    from terrain_slm.world import height_curve as H
    v1 = _game(shelf=False)
    explicit = HeightCurve(**{**v1.__dict__, "shelf_meters_per_block": 0.0, "shelf_base_m": -999.0,
                              "shelf_blend_m": 1.0})
    e0, b0 = H._curve_table(v1)
    e1, b1 = H._curve_table(explicit)
    assert np.array_equal(e0, e1) and np.array_equal(b0, b1)   # the shelf knobs mean nothing without the band
    assert not v1.has_shelf and _game().has_shelf


@pytest.mark.parametrize("scale", ["1:4", "1:2"])
def test_v2_strictly_monotone_and_smooth(scale):
    curve = _game(scale)
    elev = np.arange(-20000.0, 20001.0, 1.0)
    exact = curve.to_block_height_exact(elev)
    assert np.all(np.diff(exact) > 0)
    second_diff = np.abs(np.diff(np.diff(exact)))
    # No terraces: the first difference changes smoothly everywhere. The steepest change is the narrow (+-10 m) shore
    # blend from the 4 m/block shallows to the 16 m/block lowland: ~0.02 blocks/m^2 at 1:4, 0.045 at 1:2 -- a beach
    # slope break spread over 20 m, while a piecewise-linear knot spikes the second difference to ~1 (see
    # test_first_difference_test_would_catch_a_piecewise_linear_curve). Elsewhere < 1e-2 as for v1.
    assert second_diff.max() < 0.05
    far = np.abs(elev[1:-1]) > 40.0
    assert second_diff[far].max() < 1e-2
    i = int(curve.shelf_base_m - elev[0])
    assert second_diff[i - 200 : i + 200].max() < 1e-2


def test_v2_bands_hold_their_rates():
    curve = _game()
    assert curve.rate(np.array([-6.0]))[0] < curve.shelf_meters_per_block           # shallows (blending into the shore)
    assert curve.rate(np.array([-80.0]))[0] == pytest.approx(curve.shelf_meters_per_block, abs=1e-9)   # shelf
    deep = curve.ocean_meters_per_block
    assert curve.rate(np.array([-460.0]))[0] == pytest.approx(deep, abs=1e-9)      # below the shelf blend: ocean
    assert curve.rate(np.array([-6000.0]))[0] == pytest.approx(deep, abs=1e-9)
    # Land keeps v1's rates above the shore blend (the same relief everywhere inland, just ~1 block higher); the first
    # 60 m above the waterline blend from the shelf rate instead of the 48 m ocean rate, so beaches and coastal flats
    # are resolved at ~13-16 m per block instead of up to 32 (v1 put the first 30 m of land into one block).
    v1 = _game(shelf=False)
    elev = np.arange(60.0, 9000.0, 3.0)
    offset = curve.to_block_height_exact(elev) - v1.to_block_height_exact(elev)
    assert np.ptp(offset) < 1e-6 and 0.5 < offset[0] < 1.5
    coast = np.arange(1.0, 60.0, 1.0)
    assert np.all(curve.rate(coast) < v1.rate(coast))


@pytest.mark.parametrize("scale", ["1:4", "1:2"])
def test_v2_depths_in_range(scale):
    k = 2 if scale == "1:2" else 1
    curve, v1 = _game(scale), _game(scale, shelf=False)
    deep = lambda c, e: 64 - c.to_block_height_exact(np.array([e]))[0]
    # Coastal seas: shallows of several blocks (v1: < 1) and Minecraft-like 10-40 blocks within the shelf (v1: 2-4).
    assert 4.5 * k <= deep(curve, -24.0) <= 6 * k
    assert 15 * k <= deep(curve, -100.0) <= 20 * k
    assert 30 * k <= deep(curve, -200.0) <= 38 * k
    assert deep(v1, -100.0) < 3 * k
    # Below the shelf the deep-ocean rate holds (1:4: 48 m per block, as v1)...
    assert curve.rate(np.array([-3000.0]))[0] == pytest.approx(curve.ocean_meters_per_block)
    assert deep(curve, -6000.0) - deep(curve, -3000.0) == pytest.approx(3000.0 / curve.ocean_meters_per_block, abs=1e-6)
    # ... and the deepest trench (-9.7 km; Challenger Deep -10.9 km) stays well above the column floor.
    trench = curve.to_block_height(np.array([-9700.0, -10900.0]))
    assert trench[0] > -384 + 64 and trench[1] > -384 + 25 and trench[1] < trench[0]


def test_v2_shelf_rejects_overlapping_or_negative_bands():
    with pytest.raises(ValueError, match="shelf and shore blend windows overlap"):
        _game().__class__(**{**_game().__dict__, "shelf_base_m": -60.0})
    with pytest.raises(ValueError, match="shelf_meters_per_block must be >= 0"):
        _game().__class__(**{**_game().__dict__, "shelf_meters_per_block": -1.0})
    with pytest.raises(ValueError, match="shelf_blend_m must be > 0"):
        _game().__class__(**{**_game().__dict__, "shelf_blend_m": 0.0})


def test_world_config_curve_version_keys_and_fingerprints():
    import hashlib
    import json

    v1, v2 = _game_world(1), _game_world(2)
    assert v1.curve_version == 1 and not v1.curve().has_shelf
    assert v2.curve_version == 2 and v2.curve().shelf_meters_per_block == 6.0 and v2.ocean_m_per_block == 32.0
    assert v2.curve().shallow_meters_per_block == 4.0 and v2.shore_blend_m == 10.0
    # A v1 world's fingerprint (its tile caches) is what it was before curve versions existed, whether the game sends
    # the v1 keys explicitly (it does) or not at all (an older game).
    old = {k: v for k, v in v1.to_dict().items() if k not in _CURVE_V2_KEYS + ("climate_layout",)}
    assert WorldConfig.from_dict(old) == v1
    assert v1.fingerprint() == hashlib.sha1(json.dumps(old, sort_keys=True).encode()).hexdigest()[:12]
    assert WorldConfig.from_dict(v1.to_dict()).fingerprint() == v1.fingerprint()
    assert v2.fingerprint() != v1.fingerprint()
    assert WorldConfig.from_dict(v2.to_dict()) == v2
    with pytest.raises(ValueError, match="v1 has no shelf band"):
        WorldConfig.from_dict({**old, "shelf_m_per_block": 10.0})
    with pytest.raises(ValueError, match="v2 needs one"):
        WorldConfig.from_dict({**old, "curve_version": 2})
    with pytest.raises(ValueError, match="curve_version must be one of"):
        WorldConfig.from_dict({**old, "curve_version": 7, "shelf_m_per_block": 10.0})


def test_block_scale_v1_unchanged_v2_reaches_the_trenches():
    import torch
    from terrain_slm.river.scale import BlockScale

    v1, v2 = _game_world(1), _game_world(2)
    assert v1.sea_bed_floor_m() == -4000.0
    s1, s2 = BlockScale.from_world(v1, "cpu"), BlockScale.from_world(v2, "cpu")
    # v1: the table every saved v1 world was generated with (-4 km .. 9 km on the 2 m grid): no seams.
    e = np.arange(-4000.0, 9000.0, 2.0)
    assert np.array_equal(s1.elev.numpy(), torch.tensor(e, dtype=torch.float32).numpy())
    assert np.array_equal(s1.blocks.numpy(),
                          torch.tensor(v1.curve().to_block_height_exact(e), dtype=torch.float32).numpy())
    # v2: the abyss and the trenches keep their depth (v1 floors everything below -4 km at one height).
    probe = torch.tensor([-4000.0, -6000.0, -9700.0])
    b1, b2 = s1.to_blocks(probe), s2.to_blocks(probe)
    assert b1[0] == b1[1] == b1[2]
    assert b2[0] > b2[1] > b2[2] > v2.min_y
    assert v2.sea_bed_floor_m() <= float(v2.curve().to_elevation(np.array([float(v2.min_y)]))[0])
