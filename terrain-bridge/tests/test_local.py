"""Phase 7: L1 local solve, seeding from L0, and channel extraction.

Split the same way `test_basins.py` is: closed-form checks of the pieces that have an
exactly knowable answer (window shape, stitching, seed placement, monotonicity), then
whole-solve invariants over a synthetic DEM that has a real drainage network -- run at a
toy geometry, because the production one is 3072 native pixels square and a test that
takes a minute is a test nobody runs.

The seam tests are the point of the phase. Two adjacent tiles are solved independently,
from the same analytic field, and asked to agree where they meet.
"""
from __future__ import annotations

import numpy as np
import pytest

from hydrology import basins as B
from hydrology import local as L


# --------------------------------------------------------------------------- fixtures

# Toy geometries. L1 tiles are 64 native px with a 16 px halo (a 96 px solve window,
# exactly 3x3 fetch blocks); L0 regions are 64 cells of 8 px = 512 px, so a region is
# 8x8 L1 tiles and every alignment the production pair has, this pair has too.
#
# The ratio matters as much as the sizes. Production runs 4 L1 tiles to an L0 region, so
# the inner tiles draw their seeds from one region and only the outer ring reaches into a
# neighbour; a toy L0 small enough that *every* tile straddles a region border would test
# the cross-region case exclusively and call the ordinary one a failure.
GEOM = L.L1Geometry(tile_native_px=64, halo_native_px=16, fetch_native_px=32)
L0GEOM = B.L0Geometry(region_cells=64, halo_cells=8, fetch_cells=8)

L0PARAMS = B.L0Params(geometry=L0GEOM, river_threshold_cells=4.0, min_lake_area_cells=2)

# `min_width_m=0` so the seam tests see real width variation; the one-block floor that
# production uses is exercised on its own in `test_channel_width_never_falls_below...`.
# `l0_params` has to match the cache behind every mosaic in this file -- `solve_tile`
# refuses the mismatch, because a tile cached under a fingerprint claiming one set of L0
# knobs while solved against another is a stale-cache bug waiting to happen.
PARAMS = L.L1Params(geometry=GEOM, l0_params=L0PARAMS, river_threshold_cells=40.0,
                    min_lake_area_cells=2, min_width_m=0.0)

SEED = 12345


def analytic_dem(i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
    """A DEM defined on *global* coordinates, so any two windows over the same ground
    see the same field. That is what makes a seam test meaningful: the tiles differ
    only in where they were cut, never in what they were cut from.

    Tilted toward increasing i so it reaches sea level around i = 225, with ridges in j
    to make flow converge into valleys rather than run as parallel sheets. The downhill
    gradient (4 m/px) deliberately dominates the lateral one (at most ~1.7), or the
    flow leaves through the sides of a window instead of crossing its downstream edge
    and there is no seam left to test.
    """
    ii, jj = np.mgrid[i1:i2, j1:j2].astype(np.float64)
    return (
        900.0
        - 4.0 * ii
        + 12.0 * np.sin(jj / 11.0)
        + 15.0 * np.cos(ii / 17.0 + jj / 23.0)
        + 8.0 * np.sin(ii / 5.0 + 1.3)
    )


def ridge_dem(i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
    """A watershed divide at i = 0 falling away to the south, essentially flat in j.

    Closed form on purpose: with flow running straight down each column from the divide,
    the upslope cell count at row i is exactly i + 1, whatever window it is measured in.
    That makes it the fixture that can say whether seeding delivers the *right amount* of
    water, not merely some water -- the tiny j term only breaks ties so that D8 has a
    strict descent to follow.
    """
    ii, jj = np.mgrid[i1:i2, j1:j2].astype(np.float64)
    return 900.0 - 4.0 * np.abs(ii) + 1e-3 * np.sin(jj / 7.0)


def gentle_ridge_dem(i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
    """`ridge_dem` shallow enough to stay dry land past an L0 region border, so the
    closed form still holds where the two levels' seams coincide."""
    ii, jj = np.mgrid[i1:i2, j1:j2].astype(np.float64)
    return 900.0 - 0.9 * np.abs(ii) + 1e-3 * np.sin(jj / 7.0)


def basin_dem(i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
    """A gentle slope with one closed bowl inside tile (1, 1)'s owned ground.

    The whole-solve lake assertions need standing water to assert about, and a slope
    alone never produces any: a depression fill has nothing to fill.
    """
    ii, jj = np.mgrid[i1:i2, j1:j2].astype(np.float64)
    r2 = ((ii - 96.0) ** 2 + (jj - 96.0) ** 2) / 400.0
    return 600.0 - ii - jj - 60.0 * np.maximum(0.0, 1.0 - r2)


class CountingSource:
    """An `ElevationSource` that records the windows it was asked for."""

    def __init__(self, fn=analytic_dem):
        self._fn = fn
        self.calls: list[tuple[int, int, int, int]] = []

    def __call__(self, i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
        self.calls.append((i1, j1, i2, j2))
        return self._fn(i1, j1, i2, j2)


def mosaic(cache_dir, source=None) -> L.L0Mosaic:
    cache = B.L0Cache(cache_dir, L0PARAMS)
    return L.L0Mosaic(cache, source or CountingSource(), SEED)


def flat_solution(**planes) -> B.L0Solution:
    """An L0 solution with everything switched off, for overriding a plane at a time."""
    n = L0GEOM.region_cells
    base = {
        "elevation": np.full((n, n), 100.0, dtype=np.float32),
        "lake_surface": np.full((n, n), np.nan, dtype=np.float32),
        "accumulation": np.ones((n, n), dtype=np.float32),
        "flow_dir": np.full((n, n), B.TERMINAL, dtype=np.int8),
    }
    base.update(planes)
    return B.L0Solution(region=B.L0RegionId(SEED, 0, 0), geometry=L0GEOM, **base)


# --------------------------------------------------------------------------- geometry


def test_solve_bounds_is_a_pure_function_of_the_tile_id():
    # The rule the whole module exists to hold (plan section 4.2): a window is derived
    # from the ID and never from whoever asked for it.
    assert GEOM.solve_bounds_native(3, -2) == GEOM.solve_bounds_native(3, -2)
    assert GEOM.solve_bounds_native(3, -2) != GEOM.solve_bounds_native(3, -1)


def test_tiles_cover_the_plane_without_overlap_or_gap():
    a = GEOM.tile_bounds_native(0, 0)
    b = GEOM.tile_bounds_native(1, 0)
    assert a[2] == b[0]
    assert GEOM.tile_bounds_native(-1, 0)[2] == a[0]


def test_solve_window_is_the_owned_ground_plus_halo_on_every_side():
    i1, j1, i2, j2 = GEOM.solve_bounds_native(2, 5)
    t1, u1, t2, u2 = GEOM.tile_bounds_native(2, 5)
    assert (t1 - i1, u1 - j1, i2 - t2, j2 - u2) == (16, 16, 16, 16)
    assert i2 - i1 == GEOM.solve_native_px


def test_interior_slice_recovers_the_owned_ground_from_the_solve_window():
    i1, j1, _, _ = GEOM.solve_bounds_native(1, 1)
    window = analytic_dem(*GEOM.solve_bounds_native(1, 1))
    owned = analytic_dem(*GEOM.tile_bounds_native(1, 1))
    assert np.allclose(window[GEOM.interior_slice()], owned)
    assert (i1, j1) == (48, 48)


def test_tile_containing_floors_toward_negative_infinity():
    assert GEOM.tile_containing_native(-1, -1) == (-1, -1)
    assert GEOM.tile_containing_native(0, 63) == (0, 0)
    # One native pixel spans `scale` blocks, exactly as in basins.py.
    assert GEOM.tile_containing_block(-1, 0, scale=2) == (-1, 0)
    assert GEOM.tile_containing_block(127, 0, scale=2) == (0, 0)


def test_geometry_rejects_a_window_that_is_not_a_whole_number_of_fetch_blocks():
    with pytest.raises(ValueError, match="second request shape"):
        L.L1Geometry(tile_native_px=64, halo_native_px=8, fetch_native_px=32)


def test_geometry_rejects_a_negative_halo():
    with pytest.raises(ValueError, match="halo_native_px"):
        L.L1Geometry(tile_native_px=64, halo_native_px=-8, fetch_native_px=32)


def test_production_geometry_aligns_with_production_l0():
    # Both the seeding and the deferral index one grid from the other; a fractional
    # ratio would truncate silently at every border.
    L.L1.check_l0_alignment(B.L0)
    assert L.L1.tile_native_px % B.L0.cell_native_px == 0
    assert B.L0.region_native_px % L.L1.tile_native_px == 0
    assert GEOM.solve_native_px % GEOM.fetch_native_px == 0


def test_alignment_check_rejects_a_tile_that_is_not_whole_l0_cells():
    with pytest.raises(ValueError, match="tile of 60"):
        L.L1Geometry(tile_native_px=60, halo_native_px=18, fetch_native_px=32).check_l0_alignment()


def test_alignment_check_rejects_a_halo_that_is_not_whole_l0_cells():
    with pytest.raises(ValueError, match="halo of 20"):
        L.L1Geometry(tile_native_px=64, halo_native_px=20, fetch_native_px=52).check_l0_alignment()


def test_alignment_check_rejects_a_region_that_is_not_whole_tiles():
    geom = L.L1Geometry(tile_native_px=48, halo_native_px=8, fetch_native_px=32)
    with pytest.raises(ValueError, match="not a whole"):
        geom.check_l0_alignment(B.L0Geometry(region_cells=16))


# ------------------------------------------------------------------------ fingerprint


def test_every_stored_knob_rotates_the_fingerprint():
    base = L.L1Params()
    for field, value in [
        ("river_threshold_cells", 5000.0),
        ("max_raise_m", 50.0),
        ("river_coeff", 0.4),
        ("river_exp", 0.5),
        ("width_coeff", 2.0),
        ("width_exp", 0.6),
        ("min_width_m", 30.0),
        ("min_lake_area_cells", 16),
        ("min_lake_depth_m", 1.0),
        ("flat_epsilon", 1e-5),
        ("geometry", L.L1Geometry(tile_native_px=1024, halo_native_px=512)),
        # L0's knobs reach stored L1 tiles through the seeding and the deferral, so a
        # retune of L0 that left this namespace alone would keep serving tiles solved
        # against the old L0 after L0 itself had rotated away from them.
        ("l0_params", B.L0Params(max_raise_m=50.0)),
    ]:
        other = L.L1Params(**{**base.__dict__, field: value})
        assert other.fingerprint() != base.fingerprint(), field


def test_fingerprint_is_independent_of_the_phase_5_elevation_curve():
    """The property plan section 14.9 made structural for L0, held for L1 too.

    L1 solves in metres over a window measured in native pixels, so retuning the curve
    -- or reverting TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK -- must leave every solved
    tile valid. If a curve knob ever leaks into this string, that stops being true and
    a gate verdict starts invalidating GPU-minutes of cached work.
    """
    from bridge.config import BridgeConfig

    cfg = BridgeConfig.from_env(seed=1)
    curve_knobs = {
        "sea_level", "meters_per_block", "world_height", "scale", "noise_scale",
        "ocean_meters_per_block", "lowland_meters_per_block", "midland_meters_per_block",
        "highland_meters_per_block", "lowland_top_m", "highland_base_m",
        "shore_blend_m", "midland_blend_m", "highland_blend_m",
    }
    stored = set(L.L1Params().__dict__) | set(L.L1Geometry().__dict__)
    assert stored & curve_knobs == set()
    # And the values themselves do not appear: a fingerprint over the same knobs
    # computed under a different curve is the same string.
    assert cfg.sea_level and L.L1Params().fingerprint() == L.L1Params().fingerprint()


def test_l1_and_l0_namespaces_do_not_collide(tmp_path):
    l1 = L.L1Cache(tmp_path, PARAMS)
    l0 = B.L0Cache(tmp_path, L0PARAMS)
    assert l1.root != l0.root
    assert l1.root.parent.name == "l1" and l0.root.parent.name == "l0"


# ------------------------------------------------------------------------ fetch shape


def test_fetch_grid_at_one_pixel_per_cell_is_the_identity():
    # L1 cells *are* native pixels, so the box mean it shares with L0 must not resample.
    src = CountingSource()
    bounds = GEOM.solve_bounds_native(0, 0)
    got = B.fetch_grid(bounds, src, 1, GEOM.fetch_native_px)
    assert np.allclose(got, analytic_dem(*bounds))
    assert got.shape == (GEOM.solve_native_px,) * 2


def test_fetch_uses_square_blocks_of_the_canonical_size():
    # Plan section 14.3: shape, not just area, sets upstream throughput. A run that
    # blocks differently is a run that answers differently.
    src = CountingSource()
    B.fetch_grid(GEOM.solve_bounds_native(0, 0), src, 1, GEOM.fetch_native_px)
    assert len(src.calls) == (GEOM.solve_native_px // GEOM.fetch_native_px) ** 2
    for i1, j1, i2, j2 in src.calls:
        assert (i2 - i1, j2 - j1) == (GEOM.fetch_native_px, GEOM.fetch_native_px)


def test_fetch_rejects_a_source_that_returns_the_wrong_shape():
    with pytest.raises(ValueError, match="elevation source returned"):
        B.fetch_grid((0, 0, 32, 32), lambda *_: np.zeros((8, 8)), 1, 32)


# ----------------------------------------------------------------------- L0 stitching


def test_mosaic_window_inside_one_region_is_a_slice_of_it(tmp_path):
    m = mosaic(tmp_path)
    sol = m.region(0, 0)
    win = m.window(2, 3, 9, 11)
    assert np.array_equal(win["accumulation"], sol.accumulation[2:9, 3:11])
    assert np.array_equal(win["flow_dir"], sol.flow_dir[2:9, 3:11])
    assert m.regions_used == [(0, 0)]


def test_mosaic_stitches_across_four_regions(tmp_path):
    """The case an L1 tile at a region corner actually hits: the ring its seeding needs
    is not a slice of any one solution."""
    m = mosaic(tmp_path)
    n = L0GEOM.region_cells
    win = m.window(n - 2, n - 3, n + 2, n + 3)
    assert sorted(m.regions_used) == [(0, 0), (0, 1), (1, 0), (1, 1)]
    for (ri, rj), (dr, dc) in {
        (0, 0): (0, 0), (0, 1): (0, 3), (1, 0): (2, 0), (1, 1): (2, 3),
    }.items():
        sol = m.region(ri, rj)
        want = sol.accumulation[
            (n - 2) % n if ri == 0 else 0: n if ri == 0 else 2,
            (n - 3) % n if rj == 0 else 0: n if rj == 0 else 3,
        ]
        assert np.array_equal(win["accumulation"][dr: dr + want.shape[0],
                                                  dc: dc + want.shape[1]], want)


def test_mosaic_only_solves_the_regions_it_is_asked_about(tmp_path):
    m = mosaic(tmp_path)
    m.window(0, 0, 4, 4)
    assert m.regions_used == [(0, 0)]
    m.window(0, 0, 4, 4)
    assert m.regions_used == [(0, 0)]


def test_mosaic_reads_a_cached_region_rather_than_re_solving(tmp_path):
    src = CountingSource()
    m = mosaic(tmp_path, src)
    m.region(0, 0)
    calls = len(src.calls)
    assert calls > 0
    L.L0Mosaic(B.L0Cache(tmp_path, L0PARAMS), src, SEED).region(0, 0)
    assert len(src.calls) == calls


# ---------------------------------------------------------------------------- seeding


def _seeding_mosaic(tmp_path, monkeypatch, solution: B.L0Solution) -> L.L0Mosaic:
    m = mosaic(tmp_path)
    monkeypatch.setattr(m, "region", lambda ri, rj: solution)
    return m


def test_unseeded_solve_counts_upslope_cells_exactly_as_l0_does():
    # No mosaic -> no weights -> accumulation is a plain cell count, which is what
    # every closed-form fixture in test_hydrology.py is written against.
    src = CountingSource()
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), src, None, PARAMS)
    assert sol.report == {} or "seeding" not in sol.report
    assert float(sol.accumulation.max()) == pytest.approx(round(float(sol.accumulation.max())))


def _one_edge_mosaic(tmp_path, monkeypatch, direction: int, area: float = 7.0):
    """An L0 field where exactly one cell, just upstream of the window, drains into it.

    D8 index 1 is (+1, 0); see flow.D8_DY / flow.D8_DX.
    """
    n = L0GEOM.region_cells
    flow = np.full((n, n), B.TERMINAL, dtype=np.int8)
    acc = np.zeros((n, n), dtype=np.float32)
    flow[1, 0] = direction
    acc[1, 0] = area
    return _seeding_mosaic(tmp_path, monkeypatch,
                           flat_solution(flow_dir=flow, accumulation=acc))


def _window_at(px: int) -> tuple[tuple[int, int, int, int], np.ndarray]:
    """A solve window whose first L0 cell is the one `_one_edge_mosaic` drains into."""
    bounds = (2 * px, 0, 2 * px + GEOM.solve_native_px, GEOM.solve_native_px)
    return bounds, np.full((GEOM.solve_native_px,) * 2, 100.0)


def test_inflow_spreads_evenly_across_the_boundary_the_two_cells_share(tmp_path, monkeypatch):
    px = L0GEOM.cell_native_px
    m = _one_edge_mosaic(tmp_path, monkeypatch, direction=1)
    bounds, z = _window_at(px)
    weights, report = L.inflow_weights(z, bounds, m, GEOM)

    assert (report["seeded_edges"], report["dropped_to_sea"]) == (1, 0)
    # A cardinal step shares one full cell edge: `px` L1 cells, each taking its share.
    seeded = weights - 1.0
    assert np.array_equal(np.nonzero(seeded)[0], np.zeros(px))
    assert np.array_equal(np.nonzero(seeded)[1], np.arange(px))
    assert seeded[0, :px] == pytest.approx(7.0 * px * px / px)
    # Whatever the spread, the area L0 handed over is the area that arrives.
    assert seeded.sum() == pytest.approx(7.0 * px * px)
    assert report["seeded_area_cells"] == pytest.approx(report["offered_area_cells"])


def test_a_diagonal_inflow_enters_through_the_single_corner_cell(tmp_path, monkeypatch):
    px = L0GEOM.cell_native_px
    # D8 index 7 is (+1, +1): the two cells touch at one corner only.
    m = _one_edge_mosaic(tmp_path, monkeypatch, direction=7)
    bounds = (2 * px, -px, 2 * px + GEOM.solve_native_px, -px + GEOM.solve_native_px)
    z = np.full((GEOM.solve_native_px,) * 2, 100.0)
    seeded = L.inflow_weights(z, bounds, m, GEOM)[0] - 1.0
    assert int(np.count_nonzero(seeded)) == 1
    assert seeded.sum() == pytest.approx(7.0 * px * px)


def test_seeding_never_moves_water_that_l0_did_not_send(tmp_path):
    # Total delivered area is bounded by total offered area, whatever the terrain does.
    src = CountingSource()
    bounds = GEOM.solve_bounds_native(1, 1)
    z = B.fetch_grid(bounds, src, 1, GEOM.fetch_native_px)
    weights, report = L.inflow_weights(z, bounds, mosaic(tmp_path, src), GEOM)
    assert report["seeded_area_cells"] <= report["offered_area_cells"] + 1e-6
    assert weights.sum() == pytest.approx(weights.size + report["seeded_area_cells"])


def test_inflow_into_open_sea_is_dropped_rather_than_seeded(tmp_path, monkeypatch):
    px = L0GEOM.cell_native_px
    m = _one_edge_mosaic(tmp_path, monkeypatch, direction=1)
    bounds, z = _window_at(px)
    z[0:px, 0:px] = -20.0  # the whole receiving boundary is ocean

    weights, report = L.inflow_weights(z, bounds, m, GEOM)
    assert (report["seeded_edges"], report["dropped_to_sea"]) == (0, 1)
    assert report["seeded_area_cells"] == 0.0
    assert weights.max() == 1.0


def test_inflow_skips_the_ocean_part_of_a_partly_flooded_boundary(tmp_path, monkeypatch):
    px = L0GEOM.cell_native_px
    m = _one_edge_mosaic(tmp_path, monkeypatch, direction=1)
    bounds, z = _window_at(px)
    z[0, : px // 2] = -20.0

    seeded = L.inflow_weights(z, bounds, m, GEOM)[0] - 1.0
    assert not seeded[0, : px // 2].any()
    assert seeded.sum() == pytest.approx(7.0 * px * px)


def test_seeding_rejects_a_window_not_aligned_to_l0_cells(tmp_path):
    m = mosaic(tmp_path)
    z = np.full((GEOM.solve_native_px,) * 2, 100.0)
    with pytest.raises(ValueError, match="not aligned"):
        L.inflow_weights(z, (3, 0, 3 + GEOM.solve_native_px, GEOM.solve_native_px), m, GEOM)


def test_seeding_recovers_the_true_catchment_a_window_cannot_see(tmp_path):
    """The closed-form check on the *amount*, not merely the presence, of seeded water.

    On `ridge_dem` flow runs straight down each column from a divide at i = 0, so the
    upslope count at row i is exactly i + 1 -- a quantity no bounded window can reach on
    its own. Tile 1 owns rows 64..127 and can see back only as far as row 48, so without
    L0 it under-reads by row 48's worth. With L0 it should land on the truth.
    """
    src = CountingSource(ridge_dem)
    tile = L.L1TileId(SEED, 1, 0)
    bare = L.solve_tile(tile, src, None, PARAMS)
    seeded = L.solve_tile(tile, src, mosaic(tmp_path, src), PARAMS)

    truth = np.arange(64, 128) + 1.0                       # rows 64..127, count = i + 1
    bare_rows = bare.accumulation.mean(axis=1)
    seeded_rows = seeded.accumulation.mean(axis=1)

    assert bare_rows[0] == pytest.approx(17.0)             # only its own 16-row halo
    assert seeded_rows == pytest.approx(truth, rel=0.02)
    assert np.all(seeded_rows > bare_rows)


def test_seeding_records_and_solves_every_l0_region_a_tile_reaches_into(tmp_path):
    # A tile against a region border legitimately reaches into its neighbours, and the
    # ring is only correct if all of them are solved rather than left as zeros.
    src = CountingSource(gentle_ridge_dem)   # still land this far down the slope
    corner = L.solve_tile(L.L1TileId(SEED, 7, 0), src, mosaic(tmp_path, src), PARAMS)
    used = corner.report["seeding"]["l0_regions_used"]
    assert [0, 0] in used and [1, 0] in used
    assert corner.report["seeding"]["seeded_area_cells"] > 0.0


def test_a_tile_away_from_a_region_border_needs_only_its_own_region():
    """Arithmetic, not a solve: an L0 region is minutes of GPU time, and only the 12
    tiles of a region's 16 that touch its border may ever pull in a second one."""
    px = B.L0.cell_native_px
    per_region = B.L0.region_native_px // L.L1.tile_native_px
    assert per_region == 4
    for ti in range(1, per_region - 1):
        for tj in range(1, per_region - 1):
            i1, j1, i2, j2 = L.L1.solve_bounds_native(ti, tj)
            # The ring reaches one L0 cell beyond the solve window on every side.
            assert i1 - px >= 0 and j1 - px >= 0
            assert i2 + px <= B.L0.region_native_px and j2 + px <= B.L0.region_native_px


# ----------------------------------------------------------------------- monotonicity


def _chain(values: list[float]) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """A single flow path running down one row, as (surface, receivers, land)."""
    n = len(values)
    surface = np.array([values], dtype=np.float64)
    recv = np.array([k + 1 for k in range(n - 1)] + [-1], dtype=np.int64)
    return surface, recv, np.ones((1, n), dtype=bool)


def test_monotone_pass_leaves_a_descending_surface_untouched():
    surface, recv, land = _chain([50.0, 40.0, 30.0, 20.0])
    out, report = L.enforce_monotone_downstream(surface, recv, land)
    assert np.array_equal(out, surface)
    assert report["cells_lowered"] == 0


def test_monotone_pass_lowers_a_cell_that_asks_water_to_climb():
    surface, recv, land = _chain([50.0, 40.0, 45.0, 20.0])
    out, report = L.enforce_monotone_downstream(surface, recv, land)
    assert list(out.ravel()) == [50.0, 40.0, 40.0, 20.0]
    assert (report["cells_lowered"], report["max_lowering_m"]) == (1, 5.0)


def test_monotone_pass_propagates_a_constraint_down_a_whole_chain():
    surface, recv, land = _chain([50.0, 10.0, 45.0, 44.0, 43.0])
    out, _ = L.enforce_monotone_downstream(surface, recv, land)
    assert list(out.ravel()) == [50.0, 10.0, 10.0, 10.0, 10.0]


def test_monotone_pass_holds_a_lake_level_and_counts_the_violation():
    """A lake surface is level by construction; tilting it would be worse than the
    violation being repaired. Nothing should reach this on real terrain, so the count
    is how we find out that it did."""
    surface, recv, land = _chain([50.0, 10.0, 45.0, 45.0, 20.0])
    protect = np.array([[False, False, True, True, False]])
    out, report = L.enforce_monotone_downstream(surface, recv, land, protect=protect)
    # Cell 2 would have been dragged to 10 m by the dip above it; it is held at 45 and
    # the attempt is counted. Cell 3 is already level with it, so nothing is asked of it.
    # ...and holding it also stops the dip propagating past it: cell 4 sees 45, not 10.
    assert list(out.ravel()) == [50.0, 10.0, 45.0, 45.0, 20.0]
    assert report["protected_violations"] == 1


def test_a_lake_held_under_its_spill_does_not_drag_the_river_below_it_down():
    """The failure that made `max_raise_m` wrong at L1, in miniature.

    Cell 1 is a lake capped 40 m under its own spill. If it were allowed to impose its
    level downstream, cells 2 and 3 -- a river at grade, on ground the fill never
    touched -- would be pulled to 10 m as well, and on real terrain that propagated
    387 m down a whole trunk. A lake is a boundary condition, not a source.
    """
    surface, recv, land = _chain([50.0, 10.0, 45.0, 40.0, 35.0])
    protect = np.array([[False, True, False, False, False]])
    out, report = L.enforce_monotone_downstream(surface, recv, land, protect=protect)
    assert list(out.ravel()) == [50.0, 10.0, 45.0, 40.0, 35.0]
    assert report["cells_lowered"] == 0


def test_monotone_pass_takes_the_minimum_over_all_of_a_confluence():
    # Two branches (0 -> 1 -> 4 and 3 -> 4) meet at cell 4, which then drains to 5.
    # The junction must satisfy both arms, and the cell below it must satisfy the
    # junction -- 25 m of the surface at cell 5 is water asked to climb.
    surface = np.array([[50.0, 30.0, 99.0], [40.0, 20.0, 25.0]], dtype=np.float64)
    recv = np.array([1, 4, -1, 4, 5, -1], dtype=np.int64)
    land = np.ones((2, 3), dtype=bool)
    out, report = L.enforce_monotone_downstream(surface, recv, land)
    assert out[1, 1] == 20.0
    assert out[1, 2] == 20.0
    assert report["cells_lowered"] == 1


# --------------------------------------------------------------------- channel extent


def test_channel_width_is_zero_below_the_threshold():
    params = L.L1Params(geometry=GEOM, river_threshold_cells=1000.0)
    acc = np.array([[999.0, 1000.0]])
    assert L.channel_width_m(acc, params)[0, 0] == 0.0
    assert L.channel_width_m(acc, params)[0, 1] > 0.0


def test_channel_width_never_falls_below_one_block():
    # Plan section 7 item 4: a sub-block creek is emitted as a deliberate one-block
    # brook rather than as a width the world cannot represent.
    params = L.L1Params(geometry=GEOM, river_threshold_cells=1.0, min_width_m=15.0)
    assert L.channel_width_m(np.array([[1.0]]), params)[0, 0] == 15.0


def test_channel_width_grows_with_catchment():
    params = L.L1Params(geometry=GEOM, river_threshold_cells=1.0, min_width_m=0.0)
    w = L.channel_width_m(np.array([[1e3, 1e5, 1e7]]), params).ravel()
    assert w[0] < w[1] < w[2]
    # 1.5 * sqrt(A_km2) at 30 m cells: 1e7 cells is 9000 km2, so ~142 m.
    assert w[2] == pytest.approx(1.5 * np.sqrt(1e7 * 0.03 * 0.03), rel=1e-9)


# -------------------------------------------------------------------- basin deferral


def _bowl(n: int, floor: float, rim: float, side: int) -> np.ndarray:
    """A `side`-cell square depression in the middle of an otherwise flat window."""
    z = np.full((n, n), rim)
    lo = (n - side) // 2
    z[lo: lo + side, lo: lo + side] = floor
    return z


def test_an_interior_basin_keeps_its_own_level(tmp_path):
    # Narrower than the halo, so every tile that owns any of it also contains all of it.
    z = _bowl(GEOM.solve_native_px, 50.0, 200.0, GEOM.halo_native_px - 2)
    lake_depth = np.where(z < 100.0, 100.0 - z, 0.0)
    surface = np.maximum(z, 100.0)
    out_depth, out_surface, _, report = L._defer_wide_basins(
        lake_depth, surface, z, np.ones_like(z, bool),
        GEOM.solve_bounds_native(0, 0), mosaic(tmp_path), GEOM,
    )
    # No window can truncate it, so no two tiles can disagree about it.
    assert report == {"wide_basins": 0, "deferred_to_l0": 0, "dropped_no_l0_lake": 0,
                      "suppressed_cells": 0}
    assert np.array_equal(out_depth, lake_depth)
    assert np.array_equal(out_surface, surface)


def test_a_basin_spanning_the_window_takes_l0s_level(tmp_path, monkeypatch):
    n = GEOM.solve_native_px
    z = np.full((n, n), 20.0)          # every cell below any plausible level
    lake_depth = np.full((n, n), 60.0)  # ...and the window-truncated fill said 80 m
    surface = np.full((n, n), 80.0)
    m = _seeding_mosaic(
        tmp_path, monkeypatch,
        flat_solution(lake_surface=np.full((L0GEOM.region_cells,) * 2, 55.0, np.float32)),
    )
    out_depth, out_surface, _, report = L._defer_wide_basins(
        lake_depth, surface, z, np.ones_like(z, bool),
        GEOM.solve_bounds_native(0, 0), m, GEOM,
    )
    assert (report["wide_basins"], report["deferred_to_l0"]) == (1, 1)
    assert np.allclose(out_surface, 55.0)
    assert np.allclose(out_depth, 35.0)


def test_a_basin_inside_the_window_is_still_deferred_if_it_is_wider_than_the_halo(
    tmp_path, monkeypatch
):
    """The case a window-local test gets wrong, and the reason section 14.11's rule is
    about the basin rather than about this window.

    This basin fits inside the solve window with room to spare, so *this* tile computes a
    perfectly good level for it. The neighbouring tile, whose window cuts it in half,
    does not -- and the two would disagree. Measured on real terrain first: a 247 km2
    basin, 825 x 1092 cells, sitting entirely inside one 3072 px window.
    """
    n = GEOM.solve_native_px
    side = GEOM.halo_native_px + 4
    z = _bowl(n, 50.0, 200.0, side)
    lake_depth = np.where(z < 100.0, 100.0 - z, 0.0)
    assert not lake_depth[[0, -1], :].any() and not lake_depth[:, [0, -1]].any()

    m = _seeding_mosaic(
        tmp_path, monkeypatch,
        flat_solution(lake_surface=np.full((L0GEOM.region_cells,) * 2, 90.0, np.float32)),
    )
    out_depth, out_surface, _, report = L._defer_wide_basins(
        lake_depth, np.maximum(z, 100.0), z, np.ones_like(z, bool),
        GEOM.solve_bounds_native(0, 0), m, GEOM,
    )
    assert (report["wide_basins"], report["deferred_to_l0"]) == (1, 1)
    assert out_surface[lake_depth > 0].max() == pytest.approx(90.0)
    assert out_depth.max() == pytest.approx(40.0)


def test_a_long_ribbon_basin_is_deferred_even_though_it_is_narrow(tmp_path, monkeypatch):
    # Either axis counts: a ribbon is at risk along its length. Section 14.11 found 65 of
    # them at L0, holding 4 % of lake area.
    n = GEOM.solve_native_px
    z = np.full((n, n), 200.0)
    z[n // 2, 4: 4 + GEOM.halo_native_px + 4] = 50.0
    lake_depth = np.where(z < 100.0, 100.0 - z, 0.0)
    m = _seeding_mosaic(tmp_path, monkeypatch, flat_solution())
    _, _, _, report = L._defer_wide_basins(
        lake_depth, np.maximum(z, 100.0), z, np.ones_like(z, bool),
        GEOM.solve_bounds_native(0, 0), m, GEOM,
    )
    assert report["wide_basins"] == 1


def test_deferring_dries_the_rim_the_old_level_covered(tmp_path, monkeypatch):
    """A lower L0 level leaves the old lake's margins above water; they have to go back
    to grade, or a ring of standing water sits around the lake at the old level and the
    two read as one body with a step in it."""
    n = GEOM.solve_native_px
    z = _bowl(n, 50.0, 200.0, GEOM.halo_native_px + 4)
    lake_depth = np.where(z < 100.0, 100.0 - z, 0.0)
    rim = (z == 50.0) & (lake_depth > 0)
    m = _seeding_mosaic(
        tmp_path, monkeypatch,
        flat_solution(lake_surface=np.full((L0GEOM.region_cells,) * 2, 60.0, np.float32)),
    )
    out_depth, out_surface, _, _ = L._defer_wide_basins(
        lake_depth, np.maximum(z, 100.0), z, np.ones_like(z, bool),
        GEOM.solve_bounds_native(0, 0), m, GEOM,
    )
    assert rim.any()
    assert out_surface[out_depth > 0].max() == pytest.approx(60.0)
    dried = (lake_depth > 0) & (out_depth == 0)
    assert np.allclose(out_surface[dried], z[dried])


def test_a_wide_basin_with_no_l0_lake_is_dropped_not_invented(tmp_path, monkeypatch):
    n = GEOM.solve_native_px
    z = np.full((n, n), 20.0)
    lake_depth = np.full((n, n), 60.0)
    surface = np.full((n, n), 80.0)
    m = _seeding_mosaic(tmp_path, monkeypatch, flat_solution())
    out_depth, _, suppressed, report = L._defer_wide_basins(
        lake_depth, surface, z, np.ones_like(z, bool),
        GEOM.solve_bounds_native(0, 0), m, GEOM,
    )
    assert (report["wide_basins"], report["dropped_no_l0_lake"]) == (1, 1)
    assert not out_depth.any()


def test_a_wide_basin_is_dropped_when_there_is_no_l0_at_all():
    n = GEOM.solve_native_px
    z = np.full((n, n), 20.0)
    lake_depth = np.full((n, n), 60.0)
    out_depth, _, suppressed, report = L._defer_wide_basins(
        lake_depth, np.full((n, n), 80.0), z, np.ones_like(z, bool),
        GEOM.solve_bounds_native(0, 0), None, GEOM,
    )
    assert report["dropped_no_l0_lake"] == 1
    assert not out_depth.any()


# ------------------------------------------------------------------- whole-tile solve


def test_solution_planes_have_the_owned_shape_and_stated_dtypes(tmp_path):
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), CountingSource(), mosaic(tmp_path), PARAMS)
    for plane in (sol.elevation, sol.water_surface, sol.accumulation):
        assert plane.shape == (GEOM.tile_native_px,) * 2
        assert plane.dtype == np.float32
    assert np.allclose(sol.elevation, analytic_dem(*GEOM.tile_bounds_native(0, 0)), atol=1e-4)


def test_solving_the_same_tile_twice_produces_identical_bytes(tmp_path):
    tile = L.L1TileId(SEED, 1, 1)
    a = L.solve_tile(tile, CountingSource(), mosaic(tmp_path), PARAMS)
    b = L.solve_tile(tile, CountingSource(), mosaic(tmp_path / "second"), PARAMS)
    assert L.encode(a) == L.encode(b)


def test_water_is_never_below_the_ground_it_sits_on(tmp_path):
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), CountingSource(), mosaic(tmp_path), PARAMS)
    wet = sol.wet
    assert wet.any()
    assert np.all(sol.water_surface[wet] >= sol.elevation[wet] - 1e-3)


def test_the_ocean_is_never_marked_as_inland_water(tmp_path):
    # Sea level water is `y < SEA_LEVEL` in Java and stays that way; a water level
    # emitted over the sea would be a second, conflicting source for the same column.
    sol = L.solve_tile(L.L1TileId(SEED, 1, 1), CountingSource(), mosaic(tmp_path), PARAMS)
    assert not (sol.wet & sol.ocean).any()


def test_lake_surfaces_are_level_to_the_bit(tmp_path):
    """What a stepped lake would look like in-game is a waterfall around its own rim,
    and what it would do is drain -- so `WaterSim` makes this an invariant, not a
    quality bar."""
    from scipy import ndimage

    src = CountingSource(basin_dem)
    sol = L.solve_tile(L.L1TileId(SEED, 1, 1), src, mosaic(tmp_path, src), PARAMS)
    lake = sol.lake
    assert lake.sum() > 20
    labels, n = ndimage.label(lake, structure=np.ones((3, 3), dtype=int))
    index = np.arange(1, n + 1)
    spread = np.asarray(ndimage.maximum(sol.water_surface, labels, index)) - np.asarray(
        ndimage.minimum(sol.water_surface, labels, index)
    )
    assert float(np.max(spread)) == 0.0


def test_no_river_asks_water_to_climb(tmp_path):
    """The invariant the whole surface exists to hold, reported by the solve itself so
    that a tile which broke it says so instead of being carved anyway."""
    for tile, dem in (((1, 0), analytic_dem), ((1, 1), basin_dem)):
        src = CountingSource(dem)
        sol = L.solve_tile(L.L1TileId(SEED, *tile), src, mosaic(tmp_path / str(tile), src),
                           PARAMS)
        assert sol.report["monotone_residual"]["cells_lowered"] == 0
        assert sol.report["monotone"]["cells_lowered"] == 0


def test_ground_a_deferred_lake_left_dry_carries_no_water(tmp_path):
    """A basin the fill drowned and L0's level does not is dry, not a channel.

    The flow graph still routes across it as the flat it used to be, toward a spill the
    water no longer reaches, so a channel drawn there would climb. Suppressing it is what
    keeps `monotone_residual` at zero on real terrain.
    """
    src = CountingSource(basin_dem)
    sol = L.solve_tile(L.L1TileId(SEED, 1, 1), src, mosaic(tmp_path, src), PARAMS)
    defer = sol.report["deferral"]
    assert defer["deferred_to_l0"] == 1
    assert defer["suppressed_cells"] > 0
    assert sol.report["monotone_residual"]["cells_lowered"] == 0


def test_channels_are_a_subset_of_wet_columns(tmp_path):
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), CountingSource(), mosaic(tmp_path), PARAMS)
    assert sol.channels(PARAMS).any()
    assert np.all(sol.wet[sol.channels(PARAMS)])


def test_stats_report_the_quantities_the_phase_is_judged_on(tmp_path):
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), CountingSource(), mosaic(tmp_path), PARAMS)
    st = sol.stats(PARAMS)
    assert st["cells_per_side"] == GEOM.tile_native_px
    assert st["km_per_side"] == pytest.approx(GEOM.tile_native_px * 30.0 / 1000.0)
    assert st["land_cells"] + st["ocean_cells"] == GEOM.tile_native_px ** 2
    assert st["channel_cells"] > 0


# ------------------------------------------------------------------------------ seams
#
# Phase 7's exit criterion at toy scale. Tiles 1 and 2 are used rather than 0 and 1
# because both of their seed rings fall inside one L0 region, which is the ordinary
# case (12 of every 16 production tiles); the cross-region case is its own test below,
# and it is a weaker guarantee for a reason the plan already states -- nothing bounded
# can be seamless when a catchment is larger than the window.


def _pair(tmp_path, a: tuple[int, int], b: tuple[int, int], dem=None):
    """Two tiles solved independently, each with its own mosaic and cache."""
    src = CountingSource(dem or analytic_dem)
    return (
        L.solve_tile(L.L1TileId(SEED, *a), src, mosaic(tmp_path / "a", src), PARAMS),
        L.solve_tile(L.L1TileId(SEED, *b), src, mosaic(tmp_path / "b", src), PARAMS),
    )


def _downstream_of(down, col: int):
    """The cells in `down`'s first row a channel arriving at `col` could continue into.
    D8 lets a channel step one cell sideways as it crosses."""
    return np.s_[0, max(col - 1, 0): col + 2]


def test_crossings_reports_only_the_flow_that_leaves_through_an_edge(tmp_path):
    """Without this, a seam audit has to assume every channel touching a border crosses
    it, and calls a river running *parallel* to the border a discontinuity. On the first
    real four-tile run that turned a sound j-seam into a reported 33 % continuity."""
    src = CountingSource()
    sol = L.solve_tile(L.L1TileId(SEED, 1, 0), src, mosaic(tmp_path, src), PARAMS)
    # Flow runs toward increasing i, so the downhill edge carries it and the uphill one
    # cannot: an i- crossing would be water leaving uphill.
    assert sol.crossings("i+").sum() > 0
    assert sol.crossings("i-").sum() == 0
    for edge in ("i+", "i-", "j+", "j-"):
        assert sol.crossings(edge).shape == (GEOM.tile_native_px,)


def test_flow_dir_round_trips_and_matches_the_stored_accumulation(tmp_path):
    src = CountingSource()
    sol = L.solve_tile(L.L1TileId(SEED, 1, 0), src, mosaic(tmp_path, src), PARAMS)
    back = L.decode(sol.tile, L.encode(sol), GEOM)
    assert np.array_equal(back.flow_dir, sol.flow_dir)
    assert sol.flow_dir.dtype == np.int8
    # Every cell either sends water to an 8-neighbour or is terminal; nothing else.
    assert set(np.unique(sol.flow_dir)) <= set(range(8)) | {B.TERMINAL}


def test_adjacent_tiles_see_the_same_ground_at_their_shared_edge(tmp_path):
    # The precondition for every other seam claim: the tiles differ in where they were
    # cut, not in what they were cut from.
    up, down = _pair(tmp_path, (1, 0), (2, 0))
    assert np.allclose(up.elevation[-1, :], analytic_dem(127, 0, 128, 64).ravel(), atol=1e-4)
    assert np.allclose(down.elevation[0, :], analytic_dem(128, 0, 129, 64).ravel(), atol=1e-4)


def test_every_channel_crossing_a_seam_continues_on_the_other_side(tmp_path):
    """The visible failure: a river that stops dead at a tile border."""
    up, down = _pair(tmp_path, (1, 0), (2, 0))
    crossing = np.flatnonzero(up.channels(PARAMS)[-1, :])
    assert crossing.size > 10
    downstream = down.channels(PARAMS)
    for c in crossing:
        assert downstream[_downstream_of(down, c)].any(), f"channel at column {c} vanishes"


def test_total_discharge_is_conserved_across_a_seam(tmp_path):
    """Position alone is not enough -- an unseeded downstream tile would still draw
    channels, just hairlines. The water has to cross too.

    Aggregated over the whole edge rather than per column, because D8 lets a channel
    shift a cell sideways as it crosses and the sum is indifferent to that.
    """
    up, down = _pair(tmp_path, (1, 0), (2, 0))
    above = float(up.accumulation[-1, :].sum())
    below = float(down.accumulation[0, :].sum())
    assert below / above == pytest.approx(1.0, abs=0.15)


def test_a_channel_keeps_its_catchment_column_by_column_across_a_seam(tmp_path):
    up, down = _pair(tmp_path, (1, 0), (2, 0))
    crossing = np.flatnonzero(up.channels(PARAMS)[-1, :])
    ratios = np.array([
        float(down.accumulation[_downstream_of(down, c)].max() / up.accumulation[-1, c])
        for c in crossing
    ])
    # A river gains a little over one cell and loses nothing; the tails are confluences
    # landing on one side of the seam or the other, which is geometry, not a leak.
    assert np.median(ratios) == pytest.approx(1.0, abs=0.25)
    assert float(np.percentile(ratios, 10)) > 0.25


def test_a_channel_keeps_its_width_across_a_seam(tmp_path):
    up, down = _pair(tmp_path, (1, 0), (2, 0))
    wu, wd = up.width_m(PARAMS), down.width_m(PARAMS)
    crossing = np.flatnonzero(up.channels(PARAMS)[-1, :])
    ratios = np.array([
        float(wd[_downstream_of(down, c)].max() / wu[-1, c]) for c in crossing
    ])
    # Width goes as the square root of area, so it is the forgiving half of the pair --
    # which is the point: what a player sees at a seam is the width.
    assert np.median(ratios) == pytest.approx(1.0, abs=0.15)
    assert float(ratios.min()) > 0.4


def test_a_water_surface_does_not_step_across_a_seam(tmp_path):
    up, down = _pair(tmp_path, (1, 0), (2, 0))
    shared = np.flatnonzero(up.wet[-1, :] & down.wet[0, :])
    assert shared.size > 10
    step = np.abs(up.water_surface[-1, shared] - down.water_surface[0, shared])
    # The analytic field itself drops 4 m per pixel downhill, so one cell of real
    # gradient is 4 m; a discontinuity would be a multiple of that, not a fraction.
    assert float(step.max()) < 8.0


def test_the_left_right_seam_behaves_like_the_up_down_one(tmp_path):
    # Flow runs down i, so the j seam is crossed sideways rather than head on -- a
    # different geometry through the same code, and the one a four-tile render shows.
    left, right = _pair(tmp_path, (1, 0), (1, 1))
    assert np.allclose(left.elevation[:, -1], analytic_dem(64, 63, 128, 64).ravel(), atol=1e-4)
    shared = np.flatnonzero(left.wet[:, -1] & right.wet[:, 0])
    assert shared.size > 10
    step = np.abs(left.water_surface[shared, -1] - right.water_surface[shared, 0])
    assert float(step.max()) < 8.0


def test_seeding_is_exact_where_l0_can_see_the_whole_catchment(tmp_path):
    """The strong claim, in closed form: L1 recovers the true upslope count, not an
    approximation of it. Tiles 7 and 8 both draw their seed from L0 region 0, whose own
    window reaches back past the divide, so nothing anywhere in the chain is truncated.
    """
    up, down = _pair(tmp_path, (7, 0), (8, 0), dem=gentle_ridge_dem)
    assert up.accumulation[-1, :].mean() == pytest.approx(512.0)   # rows 0..511
    assert down.accumulation[0, :].mean() == pytest.approx(513.0)  # rows 0..512


def test_an_l0_region_border_upstream_truncates_the_catchment_it_can_report(tmp_path):
    """The limitation this design has and does not hide, pinned exactly.

    L1 can only be as good as the L0 it is seeded from, and L0 is itself a bounded
    window: region 1's accumulation counts upslope from where region 1's *own* window
    begins -- its ground less one halo -- and knows nothing above that. So an L1 tile
    just inside a region, whose catchment runs off the top of that region's window,
    under-reads by exactly the area beyond it.

    Tile 8 sits in region 0 and reads the truth; tile 9 sits in region 1 and reads what
    region 1's window can see. Both numbers are exact, and the gap between them is the
    L0 halo's, which is the knob that buys it back (plan section 14.14).
    """
    up, down = _pair(tmp_path, (8, 0), (9, 0), dem=gentle_ridge_dem)
    assert up.report["seeding"]["l0_regions_used"] != down.report["seeding"]["l0_regions_used"]

    seam = 9 * GEOM.tile_native_px
    l0_window_top = L0GEOM.solve_bounds_native(1, 0)[0]
    assert up.accumulation[-1, :].mean() == pytest.approx(float(seam))          # the truth
    assert down.accumulation[0, :].mean() == pytest.approx(seam - l0_window_top + 1.0)

    # The river still crosses and is still a river -- the failure is quiet, not visible.
    assert up.channels(PARAMS)[-1, :].all() and down.channels(PARAMS)[0, :].all()


def test_a_tile_is_not_changed_by_which_neighbour_asked_for_it(tmp_path):
    """The rule underneath every seam claim: a tile is a pure function of its ID, so
    solving it as part of a row and as part of a column must give the same bytes."""
    src = CountingSource()
    a = L.solve_tile(L.L1TileId(SEED, 1, 1), src, mosaic(tmp_path / "row", src), PARAMS)
    b = L.solve_tile(L.L1TileId(SEED, 1, 1), src, mosaic(tmp_path / "col", src), PARAMS)
    assert L.encode(a) == L.encode(b)


# ------------------------------------------------------------------------------ codec


def test_encode_decode_round_trips_every_plane(tmp_path):
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), CountingSource(), mosaic(tmp_path), PARAMS)
    back = L.decode(sol.tile, L.encode(sol), GEOM)
    assert np.array_equal(back.elevation, sol.elevation)
    assert np.array_equal(back.accumulation, sol.accumulation)
    assert np.array_equal(
        np.nan_to_num(back.water_surface, nan=-1.0), np.nan_to_num(sol.water_surface, nan=-1.0)
    )


def test_decode_rejects_a_payload_that_is_not_an_l1_tile():
    with pytest.raises(ValueError, match="magic"):
        L.decode(L.L1TileId(SEED, 0, 0), b"SBL0" + bytes(64), GEOM)


def test_decode_rejects_a_payload_from_another_schema_version():
    body = B._MAGIC  # any four bytes; the magic is replaced below
    data = bytearray(L.encode(_tiny_solution()))
    data[4:6] = np.array([L.L1_SCHEMA_VERSION + 1], dtype="<u2").tobytes()
    assert body
    with pytest.raises(ValueError, match="schema"):
        L.decode(L.L1TileId(SEED, 0, 0), bytes(data), L.L1Geometry(tile_native_px=4,
                                                                   halo_native_px=0,
                                                                   fetch_native_px=4))


def test_decode_rejects_a_payload_of_the_wrong_size():
    geom = L.L1Geometry(tile_native_px=4, halo_native_px=0, fetch_native_px=4)
    data = L.encode(_tiny_solution())[:-8]
    with pytest.raises(ValueError, match="bytes, expected"):
        L.decode(L.L1TileId(SEED, 0, 0), data, geom)


def _tiny_solution() -> L.L1Solution:
    geom = L.L1Geometry(tile_native_px=4, halo_native_px=0, fetch_native_px=4)
    return L.L1Solution(
        tile=L.L1TileId(SEED, 0, 0),
        elevation=np.arange(16, dtype=np.float32).reshape(4, 4),
        water_surface=np.full((4, 4), np.nan, dtype=np.float32),
        accumulation=np.ones((4, 4), dtype=np.float32),
        flow_dir=np.full((4, 4), B.TERMINAL, dtype=np.int8),
        geometry=geom,
    )


# ------------------------------------------------------------------------------ cache


def test_cache_round_trips_a_tile_through_disk(tmp_path):
    cache = L.L1Cache(tmp_path, PARAMS)
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), CountingSource(), mosaic(tmp_path), PARAMS)
    cache.put(sol)
    back = cache.get(sol.tile)
    assert back is not None
    assert np.array_equal(back.elevation, sol.elevation)


def test_cache_miss_returns_none(tmp_path):
    assert L.L1Cache(tmp_path, PARAMS).get(L.L1TileId(SEED, 9, 9)) is None


def test_get_or_solve_fetches_once_and_then_reads_disk(tmp_path):
    src = CountingSource()
    cache = L.L1Cache(tmp_path, PARAMS)
    tile = L.L1TileId(SEED, 0, 0)
    cache.get_or_solve(tile, src, mosaic(tmp_path, src))
    after_first = len(src.calls)
    cache.get_or_solve(tile, src, mosaic(tmp_path, src))
    assert len(src.calls) == after_first


def test_a_different_knob_writes_to_a_different_namespace(tmp_path):
    a = L.L1Cache(tmp_path, PARAMS)
    b = L.L1Cache(tmp_path, L.L1Params(geometry=GEOM, river_threshold_cells=99.0))
    assert a.root != b.root
    assert a.path(L.L1TileId(SEED, 0, 0)).name == b.path(L.L1TileId(SEED, 0, 0)).name


def test_a_torn_write_never_becomes_a_readable_tile(tmp_path):
    # `put` writes to a temp name and renames, so a crash mid-write leaves the old tile.
    cache = L.L1Cache(tmp_path, PARAMS)
    sol = L.solve_tile(L.L1TileId(SEED, 0, 0), CountingSource(), mosaic(tmp_path), PARAMS)
    path = cache.put(sol)
    assert path.suffix == ".l1"
    assert not list(cache.root.glob("*.tmp"))
