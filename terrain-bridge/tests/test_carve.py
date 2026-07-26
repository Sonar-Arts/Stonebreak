"""Phase 8: the block-resolution carve, and the containment invariant.

The invariant is the whole point of this file. Worldgen water is a source block by
definition, so a water column with dry ground below it beside it is a permanent spring
that `WaterSim` propagates outward on every chunk load -- and one landing on a chunk seam
is not even detected at load. Plan section 7 lists it as the only Critical risk in the
document.

Two things are therefore tested harder than anything else here: that a carved tile has no
violation inside itself, and that two tiles carved *independently* agree over the ground
they share. The second is the one that cannot be got by inspection, because nothing in
production ever holds two terrain tiles at once.
"""
from __future__ import annotations

import numpy as np
import pytest

from bridge.config import BridgeConfig
from bridge.height_mapping import HeightCurve

import hydrology.carve as C
from hydrology.basins import L0Params
from hydrology.local import L1Geometry, L1Params

CFG = BridgeConfig.from_env(seed=1)
CURVE = HeightCurve.from_config(CFG)
SCALE = CFG.scale
SEA = CFG.sea_level
HORIZ = CFG.horizontal_meters_per_block

# `min_lake_area_cells` and the rest do not matter here -- nothing in this module solves.
# The threshold does: it is what turns accumulation into a channel.
PARAMS = L1Params(river_threshold_cells=10_000.0)


# ------------------------------------------------------------------------------ fixtures


class FieldMosaic:
    """An L1 field defined on *global* native coordinates.

    Global, so any two windows over the same ground see the same values -- which is what
    makes a seam test mean anything. A mosaic that generated per-window would make the
    tiles differ for a reason that has nothing to do with the code under test.
    """

    def __init__(self, surface_of, accumulation_of, params: L1Params = PARAMS):
        self.params = params
        self.geometry = params.geometry
        self.tiles_resident = [(0, 0)]
        self._surface = surface_of
        self._accumulation = accumulation_of

    def window(self, i1, j1, i2, j2):
        ii, jj = np.meshgrid(np.arange(i1, i2), np.arange(j1, j2), indexing="ij")
        return {
            "water_surface": np.asarray(self._surface(ii, jj), dtype=np.float32),
            "accumulation": np.asarray(self._accumulation(ii, jj), dtype=np.float32),
        }


def dry_mosaic() -> FieldMosaic:
    return FieldMosaic(lambda i, j: np.full(i.shape, np.nan), lambda i, j: np.zeros(i.shape))


def river_mosaic(native_col: int = 64, head_m: float = 500.0, fall_per_cell: float = 0.5,
                 accumulation: float = 50_000.0) -> FieldMosaic:
    """One straight channel down a constant native column, descending with i."""
    def surface(i, j):
        out = np.full(i.shape, np.nan)
        on = j == native_col
        out[on] = (head_m - i * fall_per_cell)[on]
        return out

    def acc(i, j):
        return np.where(j == native_col, accumulation, 1.0)

    return FieldMosaic(surface, acc)


def lake_mosaic(level_m: float, i0: int, j0: int, side: int) -> FieldMosaic:
    """A square standing-water body, level by construction, below the channel threshold."""
    def surface(i, j):
        inside = (i >= i0) & (i < i0 + side) & (j >= j0) & (j < j0 + side)
        return np.where(inside, level_m, np.nan)

    return FieldMosaic(surface, lambda i, j: np.ones(i.shape))


def sloping_ground(i1: int, j1: int, h: int, w: int, *, base=500.0, di=-0.25, dj=0.0,
                   ripple=0.0) -> np.ndarray:
    ii, jj = np.meshgrid(np.arange(i1, i1 + h), np.arange(j1, j1 + w), indexing="ij")
    return base + di * ii + dj * np.abs(jj) + ripple * np.sin(jj * 0.3)


def carve(bounds, elevation, mosaic, params=C.CARVE) -> C.CarveResult:
    return C.carve_tile(
        bounds, elevation, curve=CURVE, scale=SCALE, sea_level=SEA,
        mosaic=mosaic, horizontal_meters_per_block=HORIZ, params=params,
    )


# ------------------------------------------------------------------------------- params


def test_every_carve_knob_rotates_the_fingerprint():
    base = C.CarveParams()
    for field, value in [("bank_margin_blocks", 64), ("min_water_blocks", 2)]:
        other = C.CarveParams(**{**base.__dict__, field: value})
        assert C.fingerprint(other, PARAMS) != C.fingerprint(base, PARAMS), field


def test_the_l1_knobs_reach_the_terrain_cache_namespace_too():
    """A tile's water is a function of both, and L1's own namespace covers only L1's
    payloads -- so a river-threshold retune has to rotate the *terrain* tiles as well or
    the bridge keeps serving carves of a network it no longer believes in."""
    other = L1Params(river_threshold_cells=5_000.0)
    assert C.fingerprint(C.CARVE, other) != C.fingerprint(C.CARVE, PARAMS)
    # And L0's, through L1Params.l0_params -- the deferral imports L0's lake levels.
    deep = L1Params(l0_params=L0Params(max_raise_m=250.0))
    assert C.fingerprint(C.CARVE, deep) != C.fingerprint(C.CARVE, PARAMS)


def test_a_margin_too_small_for_the_repair_is_refused():
    with pytest.raises(ValueError, match="1-block ring"):
        C.CarveParams(bank_margin_blocks=0)


def test_a_wet_column_with_no_room_under_it_is_refused():
    with pytest.raises(ValueError, match="holds no water"):
        C.CarveParams(min_water_blocks=0)


# ------------------------------------------------------------------------ channel field


def test_the_bed_is_a_cosine_that_reaches_the_surface_at_the_bank():
    """1 at the centreline, 0 at the bank, and flat at both -- so the channel meets the
    ground tangentially instead of at a wall that quantises into a staircase."""
    n = 41
    surface = np.full((1, n), 100.0, dtype=np.float32)
    acc = np.full((1, n), 1.0, dtype=np.float32)
    acc[0, 20] = 4_000_000.0  # ~3.2 km2 -> a wide channel, so the falloff has room
    field = C.channel_field(
        surface, acc, l1_params=PARAMS, horizontal_meters_per_block=HORIZ, geometry=L1Geometry()
    )

    bed = field.bed_m[0]
    inside = np.isfinite(bed)
    assert inside[20], "the channel cell itself must always carve"
    depth = 100.0 - bed
    # Deepest at the centre, monotone out to the bank, and nothing carved beyond it.
    assert depth[20] == pytest.approx(np.nanmax(depth))
    right = depth[20:][np.isfinite(depth[20:])]
    assert np.all(np.diff(right) <= 1e-9)
    assert right[-1] == pytest.approx(0.0, abs=0.05), "the bank must land on the surface"
    # Symmetric about the centreline: a cosine of |distance| cannot favour a side.
    left = depth[: 21][np.isfinite(depth[: 21])]
    np.testing.assert_allclose(left, right[::-1], atol=1e-9)


def test_a_channel_cell_l1_emitted_no_water_for_is_not_carved():
    """Plan section 15.8: a basin the deferral leaves dry still routes flow across it, so
    accumulation alone is a fiction there. The surface plane is the authority."""
    surface = np.full((1, 5), np.nan, dtype=np.float32)
    acc = np.full((1, 5), 50_000.0, dtype=np.float32)
    field = C.channel_field(
        surface, acc, l1_params=PARAMS, horizontal_meters_per_block=HORIZ, geometry=L1Geometry()
    )
    assert not field.channel.any()
    assert np.isnan(field.bed_m).all()


def test_upsampling_is_window_independent():
    """Nearest, not bilinear: bilinear reads a neighbour outside the window, so the same
    ground would interpolate differently depending on which tile asked."""
    a = np.arange(36, dtype=np.float64).reshape(6, 6)
    full = C._upsample(a, 2)
    part = C._upsample(a[2:5, 1:4], 2)
    np.testing.assert_array_equal(part, full[4:10, 2:8])


# ------------------------------------------------------------------------------- mosaic


class _RecordingCache:
    """Stands in for L1Cache: hands back a tile whose planes encode its own ID."""

    def __init__(self, params=PARAMS):
        self.params = params
        self.loads: list[tuple[int, int]] = []

    def get_or_solve(self, tile, source, mosaic=None):
        from hydrology.local import L1Solution

        self.loads.append((tile.tile_i, tile.tile_j))
        n = self.params.geometry.tile_native_px
        marker = float(tile.tile_i * 1000 + tile.tile_j)
        planes = {
            "elevation": np.zeros((n, n), dtype=np.float32),
            "water_surface": np.full((n, n), marker, dtype=np.float32),
            "accumulation": np.full((n, n), marker, dtype=np.float32),
            "flow_dir": np.full((n, n), -1, dtype=np.int8),
        }
        return L1Solution(tile=tile, geometry=self.params.geometry, **planes)


def test_the_mosaic_stitches_across_an_l1_tile_border():
    cache = _RecordingCache()
    mosaic = C.L1Mosaic(cache, lambda *a: None, seed=7)
    n = PARAMS.geometry.tile_native_px

    win = mosaic.window(n - 2, -1, n + 2, 1)
    # Rows either side of the border come from tiles i=0 and i=1; columns either side of 0
    # from tiles j=-1 and j=0. Four owners, no overlap, no gaps. The marker is
    # `1000 * tile_i + tile_j`, so the four corners are distinguishable.
    assert set(np.unique(win["water_surface"])) == {-1.0, 0.0, 999.0, 1000.0}
    assert sorted(cache.loads) == [(0, -1), (0, 0), (1, -1), (1, 0)]


def test_the_mosaic_reads_a_tile_once_and_bounds_what_it_keeps():
    """It lives in the request path, and a solved tile is 54.5 MB. Unbounded, a bridge
    that ran for hours would hold every tile a player had ever walked across."""
    cache = _RecordingCache()
    mosaic = C.L1Mosaic(cache, lambda *a: None, seed=7, resident_tiles=2)
    for _ in range(3):
        mosaic.window(0, 0, 4, 4)
    assert cache.loads == [(0, 0)], "a resident tile must not be re-solved"

    n = PARAMS.geometry.tile_native_px
    mosaic.window(n, 0, n + 4, 4)
    mosaic.window(2 * n, 0, 2 * n + 4, 4)
    assert mosaic.tiles_resident == [(1, 0), (2, 0)]
    mosaic.window(0, 0, 4, 4)  # evicted, so it comes back through the cache
    assert cache.loads == [(0, 0), (1, 0), (2, 0), (0, 0)]


# ---------------------------------------------------------------------------- the carve


def test_a_dry_tile_is_the_curve_and_nothing_else():
    """Hydrology must not perturb ground it has no water for -- the carve is additive."""
    elev = sloping_ground(0, 0, 64, 64, ripple=6.0)
    res = carve((0, 0, 64, 64), elev, dry_mosaic())
    np.testing.assert_array_equal(res.block_heights, CURVE.to_block_height(elev))
    assert (res.water_levels == -1).all()


def test_the_sea_is_one_case_of_the_water_plane():
    """`y < SEA_LEVEL` restated per column: submerged reports sea level, a column whose
    top block *is* sea level stays dry, exactly as before."""
    elev = np.array([[-4000.0, -100.0, 0.0, 40.0, 4000.0]])
    res = carve((0, 0, 1, 5), elev, dry_mosaic())
    submerged = res.block_heights < SEA
    np.testing.assert_array_equal(res.water_levels[submerged], SEA)
    np.testing.assert_array_equal(res.water_levels[~submerged], -1)
    assert submerged.any() and (~submerged).any()


def test_a_channel_column_always_has_room_for_its_water():
    """The depth law returns 0.84 m at L1's threshold -- a fifth of a lowland block -- so
    without the floor, quantisation erases the smallest streams and a river stops and
    restarts along its length."""
    thin = river_mosaic(accumulation=PARAMS.river_threshold_cells)
    elev = sloping_ground(0, 0, 64, 256)
    res = carve((0, 0, 64, 256), elev, thin)
    wet = res.wet
    assert wet.any()
    assert (res.block_heights[wet] < res.water_levels[wet]).all()


def test_ground_standing_above_a_lake_is_an_island_not_a_bed_to_flatten():
    """L1 routes on the smooth field; the field carved here is the post-noise one, up to
    ~14 m taller in places (plan section 13.6). Flattening those bumps to one block under
    the surface would trade every lake island in the world for a shelf."""
    level = 400.0
    mosaic = lake_mosaic(level, 0, 0, 64)
    elev = np.full((64, 64), 380.0)
    elev[10:14, 10:14] = 460.0  # a knoll well clear of the surface
    res = carve((0, 0, 64, 64), elev, mosaic)

    island = res.water_levels[10:14, 10:14]
    assert (island == -1).all(), "the knoll must stay dry land"
    np.testing.assert_array_equal(
        res.block_heights[10:14, 10:14], CURVE.to_block_height(elev[10:14, 10:14])
    )
    assert (res.water_levels[30:40, 30:40] >= 0).all(), "the lake itself must still be wet"


# ------------------------------------------------------------------------ the invariant


def test_a_carved_tile_has_no_containment_violation():
    """The rule in full: every wet column's 4-neighbours are walled to its level or wet."""
    # A valley the river sits *above* on one flank, which is what makes the repair fire.
    elev = sloping_ground(0, 0, 256, 256, di=-0.25, dj=-0.30, ripple=6.0)
    res = carve((0, 0, 256, 256), elev, river_mosaic(native_col=64))
    assert res.report["containment_repairs"] > 0, "nothing was repaired; the fixture is too kind"
    assert not C.containment_violations(res.block_heights, res.water_levels).any()


def test_the_repair_only_ever_raises_dry_ground():
    """Lowering would put water above banks nobody checked; touching a wet column would
    move a shoreline the solver placed. Neither is a repair."""
    elev = sloping_ground(0, 0, 256, 256, di=-0.25, dj=-0.30, ripple=6.0)
    mosaic = river_mosaic(native_col=64)
    res = carve((0, 0, 256, 256), elev, mosaic)

    uncarved = CURVE.to_block_height(elev).astype(np.int64)
    dry = res.water_levels < 0
    assert (res.block_heights[dry].astype(np.int64) >= uncarved[dry]).all()


def test_a_lake_is_held_back_all_the_way_round_its_shore():
    level = 400.0
    elev = sloping_ground(0, 0, 128, 128, base=402.0, di=-0.5, dj=0.0)
    res = carve((0, 0, 128, 128), elev, lake_mosaic(level, 20, 20, 40))
    assert res.wet.any()
    assert not C.containment_violations(res.block_heights, res.water_levels).any()


# ------------------------------------------------------- determinism and the tile seam


def test_carving_the_same_tile_twice_gives_the_same_bytes():
    elev = sloping_ground(0, 0, 128, 128, ripple=6.0)
    mosaic = river_mosaic()
    a = carve((0, 0, 128, 128), elev, mosaic)
    b = carve((0, 0, 128, 128), elev, mosaic)
    np.testing.assert_array_equal(a.block_heights, b.block_heights)
    np.testing.assert_array_equal(a.water_levels, b.water_levels)


@pytest.mark.parametrize("origin", [(0, 0), (-512, 256), (1024, -768)])
def test_a_column_is_carved_the_same_however_the_tile_borders_fall_across_it(origin):
    """The claim plan section 4.5 rests on, measured rather than argued.

    Terrain tiles are generated independently and nothing in production ever holds two at
    once, so "the invariant holds across seams" is only true if a column's carved height
    and water level are a pure function of its own canonical elevation and of L1 data --
    never of which window happened to ask. Carving a 512-block square whole, and as four
    256-block tiles, has to give the same two planes.
    """
    i0, j0 = origin
    big = 512
    elev = sloping_ground(i0, j0, big, big, di=-0.25, dj=-0.30, ripple=6.0)
    mosaic = river_mosaic(native_col=(j0 + 300) // SCALE)

    whole = carve((i0, j0, i0 + big, j0 + big), elev, mosaic)

    heights = np.zeros((big, big), dtype=np.int16)
    water = np.zeros((big, big), dtype=np.int16)
    half = big // 2
    for di in (0, half):
        for dj in (0, half):
            sub = carve(
                (i0 + di, j0 + dj, i0 + di + half, j0 + dj + half),
                elev[di: di + half, dj: dj + half],
                mosaic,
            )
            heights[di: di + half, dj: dj + half] = sub.block_heights
            water[di: di + half, dj: dj + half] = sub.water_levels

    np.testing.assert_array_equal(heights, whole.block_heights)
    np.testing.assert_array_equal(water, whole.water_levels)


def test_two_independently_carved_neighbours_do_not_leak_across_their_seam():
    """The same property as above, stated the way the failure would actually present.

    A wet column on one tile's last row and a dry column on the next tile's first row are
    each written by a process that never saw the other. `seam_violations` is the only place
    that pairing is ever looked at.
    """
    elev = sloping_ground(0, 0, 512, 256, di=-0.25, dj=-0.30, ripple=6.0)
    mosaic = river_mosaic(native_col=64)
    top = carve((0, 0, 256, 256), elev[:256], mosaic)
    bottom = carve((256, 0, 512, 256), elev[256:], mosaic)

    assert top.wet.any() and bottom.wet.any()
    assert not len(C.seam_violations(*C.seam_columns(top, bottom, axis=0)))


def test_the_seam_check_can_actually_fail():
    """A test that only ever passes proves nothing about the checker. Hand it a wet column
    facing dry ground below its level and it has to say so."""
    wet = np.array([-1, 400, -1], dtype=np.int16)
    tall = np.array([500, 399, 500], dtype=np.int16)
    short = np.array([500, 380, 500], dtype=np.int16)
    assert list(C.seam_violations(tall, wet, short, np.full(3, -1, dtype=np.int16))) == [1]
    assert not len(C.seam_violations(tall, wet, np.full(3, 400, dtype=np.int16),
                                     np.full(3, -1, dtype=np.int16)))
