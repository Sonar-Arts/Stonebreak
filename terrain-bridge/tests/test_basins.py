"""Phase 6c: the L0 macro-region solver.

Two things are being protected here, and they are different in kind.

The first is *shape*. A macro-region's fetch window, halo included, has to be a pure
function of its ID, because two windows over the same ground accumulate differently and
the difference shows up in the world as a river that stops at a border. Those tests are
arithmetic on the geometry and need no solver at all.

The second is the *solve*, checked the way the recovered package checks itself: against
DEMs whose answer is known in closed form rather than against a previous run's output.
`fixtures.paraboloid_crater` pins the lake surface (its rim is a flat annulus, so the
spill elevation is unambiguous) and `fixtures.inclined_plane` pins accumulation (flow
runs in straight independent columns, so the upslope count at row i is exactly i + 1 --
which is also what makes it possible to assert on what the halo contributes).
"""
from __future__ import annotations

from dataclasses import fields, replace

import numpy as np
import pytest

from bridge.config import BridgeConfig
from hydrology import basins as B
from hydrology import fixtures as fx

# One native pixel per L0 cell, so a fixture's own grid *is* the L0 grid and its
# closed-form answers stay readable. 96 = 76 + 2*10 matches paraboloid_crater's size.
SMALL = B.L0Geometry(cell_native_px=1, region_cells=76, halo_cells=10, fetch_cells=6)
REGION = B.L0RegionId(seed=7, region_i=0, region_j=0)


def array_source(arr: np.ndarray, geometry: B.L0Geometry):
    """Serve `arr` as if its [0, 0] were the top-left of region (0, 0)'s solve window."""
    i0, j0, _, _ = geometry.solve_bounds_native(0, 0)

    def source(i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
        return arr[i1 - i0: i2 - i0, j1 - j0: j2 - j0]

    return source


def recording_source(arr: np.ndarray, geometry: B.L0Geometry):
    calls: list[tuple[int, int, int, int]] = []
    inner = array_source(arr, geometry)

    def source(i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
        calls.append((i1, j1, i2, j2))
        return inner(i1, j1, i2, j2)

    return source, calls


def plane_geometry(region_cells: int = 24, halo_cells: int = 8) -> B.L0Geometry:
    return B.L0Geometry(
        cell_native_px=1, region_cells=region_cells, halo_cells=halo_cells, fetch_cells=4
    )


def descending_plane(geometry: B.L0Geometry, drop: float = 1.0, top: float = 10_000.0):
    """`inclined_plane` over the solve window: elevation falls with the row, nothing
    reaches sea level, so every column drains straight off the bottom edge and the
    upslope count at solve-array row s is exactly s + 1."""
    n = geometry.solve_cells
    z = np.repeat((top - drop * np.arange(n, dtype=np.float64))[:, None], n, axis=1)
    return z


# --------------------------------------------------------------------------- geometry


def test_the_solve_window_is_a_pure_function_of_the_region_id():
    for _ in range(3):
        assert B.L0.solve_bounds_native(3, -5) == B.L0.solve_bounds_native(3, -5)

    i1, j1, i2, j2 = B.L0.region_bounds_native(3, -5)
    pad = B.L0.halo_cells * B.L0.cell_native_px
    assert B.L0.solve_bounds_native(3, -5) == (i1 - pad, j1 - pad, i2 + pad, j2 + pad)


def test_the_default_geometry_is_the_shape_the_plan_specifies():
    assert B.L0.cell_m == 240.0
    assert B.L0.region_cells * B.L0.cell_m == pytest.approx(245_760.0)
    assert B.L0.solve_cells == 1536
    assert B.L0.solve_cells % B.L0.fetch_cells == 0


@pytest.mark.parametrize("region_i,region_j", [(0, 0), (1, 0), (0, 1), (-1, -1), (5, -3)])
def test_regions_tile_native_space_without_gap_or_overlap(region_i, region_j):
    i1, j1, i2, j2 = B.L0.region_bounds_native(region_i, region_j)
    right = B.L0.region_bounds_native(region_i, region_j + 1)
    below = B.L0.region_bounds_native(region_i + 1, region_j)
    assert j2 == right[1]
    assert i2 == below[0]

    for i, j in ((i1, j1), (i2 - 1, j2 - 1), ((i1 + i2) // 2, (j1 + j2) // 2)):
        assert B.L0.region_containing_native(i, j) == (region_i, region_j)
    assert B.L0.region_containing_native(i2, j1) == (region_i + 1, region_j)


def test_negative_coordinates_floor_toward_negative_infinity():
    # The bug a Java port makes with `/` instead of Math.floorDiv: -1 must belong to
    # region -1, not to region 0, or the origin region is twice as wide as every other.
    assert B.L0.region_containing_native(-1, -1) == (-1, -1)
    assert B.L0.region_bounds_native(-1, -1)[2:] == (0, 0)


@pytest.mark.parametrize("scale", [1, 2, 8])
def test_region_containing_block_respects_scale(scale):
    side_blocks = B.L0.region_native_px * scale
    assert B.L0.region_containing_block(0, 0, scale) == (0, 0)
    assert B.L0.region_containing_block(side_blocks - 1, 0, scale) == (0, 0)
    assert B.L0.region_containing_block(side_blocks, 0, scale) == (1, 0)
    assert B.L0.region_containing_block(-1, -1, scale) == (-1, -1)
    assert B.L0.region_bounds_blocks(1, 0, scale)[0] == side_blocks


@pytest.mark.parametrize("scale", [1, 2])
def test_cell_containing_block_covers_its_region_exactly(scale):
    cell_blocks = B.L0.cell_native_px * scale
    assert B.L0.cell_containing_block(0, 0, scale) == (0, 0)
    assert B.L0.cell_containing_block(cell_blocks - 1, 0, scale) == (0, 0)
    assert B.L0.cell_containing_block(cell_blocks, 0, scale) == (1, 0)
    # Last block of the region maps to the last cell, not one past it.
    last = B.L0.region_native_px * scale - 1
    assert B.L0.cell_containing_block(last, last, scale) == (
        B.L0.region_cells - 1, B.L0.region_cells - 1
    )


def test_fetch_windows_partition_the_solve_window():
    i1, j1, i2, j2 = bounds = B.L0.solve_bounds_native(2, -1)
    per_side = B.L0.solve_cells // B.L0.fetch_cells
    blocks = list(B.L0.fetch_windows(bounds))
    assert len(blocks) == per_side * per_side

    # Exactly one request shape over the whole window, and every native pixel covered
    # exactly once -- a gap or an overlap would be a second shape over the same ground.
    assert {(b[2] - b[0], b[3] - b[1]) for b in blocks} == {
        (B.L0.fetch_cells * B.L0.cell_native_px,) * 2
    }
    covered = np.zeros((i2 - i1, j2 - j1), dtype=np.int8)
    for bi1, bj1, bi2, bj2 in blocks:
        covered[bi1 - i1: bi2 - i1, bj1 - j1: bj2 - j1] += 1
    assert (covered == 1).all()


def test_a_ragged_final_block_is_rejected():
    with pytest.raises(ValueError, match="ragged"):
        B.L0Geometry(region_cells=100, halo_cells=0, fetch_cells=32)


@pytest.mark.parametrize("kwargs", [{"cell_native_px": 0}, {"region_cells": 0}, {"halo_cells": -1}])
def test_geometry_rejects_impossible_shapes(kwargs):
    with pytest.raises(ValueError):
        B.L0Geometry(fetch_cells=1, **kwargs)


# ------------------------------------------------------------------------------ fetch


def test_fetch_box_downsamples_exactly():
    geom = B.L0Geometry(cell_native_px=4, region_cells=4, halo_cells=2, fetch_cells=2)
    n = geom.solve_cells * geom.cell_native_px
    native = np.arange(n * n, dtype=np.float64).reshape(n, n)

    grid = B.fetch_l0_grid(geom.solve_bounds_native(0, 0), array_source(native, geom), geom)

    assert grid.shape == (geom.solve_cells, geom.solve_cells)
    px = geom.cell_native_px
    expected = native.reshape(geom.solve_cells, px, geom.solve_cells, px).mean(axis=(1, 3))
    np.testing.assert_allclose(grid, expected)


def test_fetch_asks_only_for_canonical_blocks():
    geom = plane_geometry()
    source, calls = recording_source(descending_plane(geom), geom)
    B.fetch_l0_grid(geom.solve_bounds_native(0, 0), source, geom)
    assert calls == list(geom.fetch_windows(geom.solve_bounds_native(0, 0)))


def test_fetch_rejects_a_source_that_returns_the_wrong_shape():
    geom = plane_geometry()

    def short(i1, j1, i2, j2):
        return np.zeros((i2 - i1 - 1, j2 - j1))

    with pytest.raises(ValueError, match="elevation source returned"):
        B.fetch_l0_grid(geom.solve_bounds_native(0, 0), short, geom)


def test_fetch_rejects_a_window_that_is_not_whole_cells():
    geom = B.L0Geometry(cell_native_px=8, region_cells=4, halo_cells=0, fetch_cells=1)
    with pytest.raises(ValueError, match="whole number"):
        B.fetch_l0_grid((0, 0, 33, 32), lambda *a: np.zeros((33, 32)), geom)


# ------------------------------------------------------------------------- directions


def test_receiver_directions_round_trip_and_survive_a_crop():
    geom = plane_geometry()
    z = descending_plane(geom)
    wm = B.water_depth(z, horizontal_cell_size_m=geom.cell_m)
    code = B.receiver_directions(wm.receivers, z.shape)

    h, w = z.shape
    live = code != B.TERMINAL
    rows, cols = np.mgrid[0:h, 0:w]
    rebuilt = np.where(
        live,
        (rows + B.D8_DY[np.where(live, code, 0)]) * w + cols + B.D8_DX[np.where(live, code, 0)],
        B.TERMINAL,
    )
    np.testing.assert_array_equal(rebuilt.ravel(), wm.receivers)

    # Every cell but the last row steps straight downslope, and that stays true of the
    # cropped interior -- which a flat receiver index would not have.
    assert (code[:-1] == 1).all()


def test_receiver_directions_reject_a_corrupt_graph():
    recv = np.array([5, -1, -1, -1], dtype=np.int64)  # 0 -> 5 is not a neighbour step
    with pytest.raises(ValueError, match="corrupt"):
        B.receiver_directions(recv, (2, 2))


# ------------------------------------------------------------------------------ solve


def crater_params(**kwargs) -> B.L0Params:
    """The crater is one basin of 1810 cells; the puddle filters would only get in the
    way of asserting on it."""
    base = dict(geometry=SMALL, max_raise_m=None, min_lake_area_cells=1, min_lake_depth_m=0.0)
    return B.L0Params(**{**base, **kwargs})


def test_a_known_basin_fills_to_its_spill_elevation():
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    sol = B.solve_region(REGION, array_source(crater.z, SMALL), crater_params())

    lake = sol.lake
    assert lake.any()
    # The rim is a flat annulus at exactly 500 m, so the surface has one value and the
    # depth is `rim - z` everywhere, not merely "somewhere between the floor and the rim".
    np.testing.assert_allclose(sol.lake_surface[lake], 500.0, atol=1e-6)

    interior = SMALL.interior_slice()
    expected = crater.expected_lake_depth[interior]
    got = np.where(lake, sol.lake_surface - sol.elevation, 0.0)
    np.testing.assert_allclose(got, expected, atol=1e-3)


def test_max_raise_caps_the_lake_surface_without_touching_routing():
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)  # spills at its 500 m rim
    source = array_source(crater.z, SMALL)

    uncapped = B.solve_region(REGION, source, crater_params())
    capped = B.solve_region(REGION, source, crater_params(max_raise_m=50.0))

    # `cap_basins` measures against the basin's own floor -- the lowest cell sampled
    # inside it, which is a shade above the paraboloid's analytic 380 m because no cell
    # sits exactly on the centre.
    floor = crater.z[crater.expected_lake_depth > 0].min()
    np.testing.assert_allclose(capped.lake_surface[capped.lake], floor + 50.0, atol=1e-4)
    assert capped.lake.sum() < uncapped.lake.sum()

    # The whole point of capping in a post-pass: routing still ran on the complete fill,
    # so a capped basin has not become a pit that swallows its catchment.
    np.testing.assert_array_equal(capped.accumulation, uncapped.accumulation)
    np.testing.assert_array_equal(capped.flow_dir, uncapped.flow_dir)


def test_a_cap_far_above_the_relief_changes_nothing():
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    source = array_source(crater.z, SMALL)
    a = B.solve_region(REGION, source, crater_params())
    b = B.solve_region(REGION, source, crater_params(max_raise_m=10_000.0))
    assert B.encode(a) == B.encode(b)


def test_the_halo_contributes_upstream_area():
    """The halo is solved, not merely fetched -- otherwise it is dead cost.

    On a plane draining top to bottom the upslope count at solve-array row s is s + 1,
    so interior row 0 must carry `halo_cells + 1`: the halo's own rows, plus itself.
    A solve that cropped before accumulating would report 1.
    """
    geom = plane_geometry(region_cells=24, halo_cells=8)
    sol = B.solve_region(
        REGION, array_source(descending_plane(geom), geom), B.L0Params(geometry=geom)
    )
    expected = np.repeat(
        (np.arange(geom.region_cells, dtype=np.float32) + geom.halo_cells + 1)[:, None],
        geom.region_cells, axis=1,
    )
    np.testing.assert_allclose(sol.accumulation, expected)
    assert sol.accumulation[0, 0] == geom.halo_cells + 1


def test_solving_the_same_region_twice_is_byte_identical():
    """Phase 6c's exit criterion, minus the GPU. Determinism of the *solve* is what this
    can prove offline; determinism of the fetch is upstream's, and rests on the canonical
    request shape `test_fetch_asks_only_for_canonical_blocks` pins down."""
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    source = array_source(crater.z, SMALL)
    assert B.encode(B.solve_region(REGION, source, crater_params())) == B.encode(
        B.solve_region(REGION, source, crater_params())
    )


def test_solution_planes_are_cropped_to_the_region():
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    sol = B.solve_region(REGION, array_source(crater.z, SMALL), crater_params())
    for plane in (sol.elevation, sol.lake_surface, sol.accumulation, sol.flow_dir):
        assert plane.shape == (SMALL.region_cells, SMALL.region_cells)
    interior = SMALL.interior_slice()
    np.testing.assert_allclose(sol.elevation, crater.z[interior].astype(np.float32))


# ----------------------------------------------------------------------------- inflow


def solved_plane(region_cells: int = 24, halo_cells: int = 8) -> B.L0Solution:
    geom = plane_geometry(region_cells, halo_cells)
    return B.solve_region(
        REGION, array_source(descending_plane(geom), geom), B.L0Params(geometry=geom)
    )


def test_inflow_edges_report_what_crosses_into_a_rectangle():
    sol = solved_plane()
    halo = sol.geometry.halo_cells
    i1, j1, i2, j2 = 6, 4, 12, 9        # (i1, j1, i2, j2), as everywhere else here

    inflow = B.inflow_edges(sol, i1, j1, i2, j2)
    width = j2 - j1
    assert len(inflow) == width                   # one per column, from the row above
    np.testing.assert_array_equal(inflow.source_row, np.full(width, i1 - 1))
    np.testing.assert_array_equal(inflow.target_row, np.full(width, i1))
    np.testing.assert_array_equal(inflow.source_col, np.arange(j1, j2))
    np.testing.assert_array_equal(inflow.target_col, np.arange(j1, j2))
    # Upslope area at the contributing row, which is what crosses the boundary.
    np.testing.assert_allclose(inflow.accumulation, i1 - 1 + halo + 1)
    assert inflow.total() == pytest.approx(width * (i1 + halo))


def test_inflow_edges_ignore_flow_running_past_a_rectangle():
    """The side and bottom rings drain down their own columns, never into the rect."""
    sol = solved_plane()
    table = B.inflow_edges(sol, 6, 4, 12, 9)
    assert set(zip(table.source_row.tolist(), table.source_col.tolist())) == {
        (5, c) for c in range(4, 9)
    }


def test_a_rectangle_on_the_region_border_has_no_ring_outside_it():
    """Flow crossing that edge belongs to the neighbouring region, and is its to supply."""
    sol = solved_plane()
    n = sol.geometry.region_cells
    assert len(B.inflow_edges(sol, 0, 0, n, n)) == 0
    # A band along the region's upstream edge: its only contributing ring row lies in
    # the region above, so nothing crosses in here either.
    assert len(B.inflow_edges(sol, 0, 0, 4, n)) == 0


@pytest.mark.parametrize("rect", [(-1, 0, 4, 4), (0, 0, 4, 999), (4, 0, 4, 4)])
def test_inflow_edges_reject_a_rectangle_outside_the_region(rect):
    with pytest.raises(ValueError, match="not inside"):
        B.inflow_edges(solved_plane(), *rect)


# ------------------------------------------------------------------------------ cache


def test_cache_round_trip_is_lossless_including_the_no_lake_sentinel():
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    params = crater_params()
    sol = B.solve_region(REGION, array_source(crater.z, SMALL), params)
    assert not np.isfinite(sol.lake_surface).all()  # the NaN sentinel is exercised

    back = B.decode(REGION, B.encode(sol), SMALL)
    np.testing.assert_array_equal(back.elevation, sol.elevation)
    np.testing.assert_array_equal(back.accumulation, sol.accumulation)
    np.testing.assert_array_equal(back.flow_dir, sol.flow_dir)
    np.testing.assert_array_equal(back.lake_surface, sol.lake_surface)  # NaN == NaN here
    assert np.array_equal(back.lake, sol.lake)


def test_cache_writes_and_reads_a_region(tmp_path):
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    params = crater_params()
    cache = B.L0Cache(tmp_path, params)
    assert cache.get(REGION) is None

    sol = B.solve_region(REGION, array_source(crater.z, SMALL), params)
    path = cache.put(sol)
    assert path.parent == cache.root and params.fingerprint() in str(path)
    np.testing.assert_array_equal(cache.get(REGION).elevation, sol.elevation)


def test_get_or_solve_solves_once_and_then_reads(tmp_path):
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    params = crater_params()
    source, calls = recording_source(crater.z, SMALL)
    cache = B.L0Cache(tmp_path, params)

    first = cache.get_or_solve(REGION, source)
    n_calls = len(calls)
    assert n_calls == (SMALL.solve_cells // SMALL.fetch_cells) ** 2

    second = cache.get_or_solve(REGION, source)
    assert len(calls) == n_calls        # the second call generated no terrain
    np.testing.assert_array_equal(second.accumulation, first.accumulation)


def test_every_knob_rotates_the_cache_namespace():
    base = B.L0Params()
    seen = {base.fingerprint()}
    changed = {
        "geometry": B.L0Geometry(halo_cells=384),
        "max_raise_m": 50.0,
        "river_threshold_cells": 500.0,
        "river_coeff": 0.4,
        "river_exp": 0.5,
        "min_lake_area_cells": 4,
        "min_lake_depth_m": 1.0,
        "flat_epsilon": 1e-5,
    }
    assert set(changed) == {f.name for f in fields(B.L0Params)}
    for name, value in changed.items():
        fp = replace(base, **{name: value}).fingerprint()
        assert fp not in seen, f"changing {name} did not rotate the fingerprint"
        seen.add(fp)


def test_the_l0_namespace_does_not_move_with_the_elevation_curve():
    """L0 is solved in metres, upstream of block quantisation, so a Phase 5 curve
    retune must not invalidate regions that cost minutes of GPU time each. Nothing
    vertical, and nothing scale-dependent, may reach the namespace.

    Stated as the vertical/scale knobs by name rather than as disjointness from all of
    `BridgeConfig`. That shortcut held only while no solver knob was env-tunable; Phase
    8 exposes `TERRAIN_BRIDGE_L0_MAX_RAISE_M` and `TERRAIN_BRIDGE_RIVER_THRESHOLD_CELLS`
    so the game can be retuned without editing source, and those *should* collide by
    name -- they are the same knob. The property being protected was never name
    disjointness; it was that nothing describing the block column gets in.
    """
    curve_knobs = {
        "sea_level", "meters_per_block", "world_height", "scale", "noise_scale",
        "tile_size_blocks", "horizontal_meters_per_block",
        "ocean_meters_per_block", "lowland_meters_per_block", "midland_meters_per_block",
        "highland_meters_per_block", "lowland_top_m", "highland_base_m",
        "shore_blend_m", "midland_blend_m", "highland_blend_m",
        "bank_margin_blocks", "min_water_blocks",
    }
    # Every name above is really a BridgeConfig field, so a rename there fails here
    # rather than silently emptying the set this test guards against.
    assert curve_knobs <= {f.name for f in fields(BridgeConfig)}
    l0_knobs = {f.name for f in fields(B.L0Params)} | {f.name for f in fields(B.L0Geometry)}
    assert not l0_knobs & curve_knobs


def test_decode_rejects_a_payload_it_did_not_write():
    with pytest.raises(ValueError, match="magic"):
        B.decode(REGION, b"NOPE" + b"\0" * 64, SMALL)


def test_decode_rejects_a_truncated_payload():
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    data = B.encode(B.solve_region(REGION, array_source(crater.z, SMALL), crater_params()))
    with pytest.raises(ValueError, match="expected"):
        B.decode(REGION, data[:-4], SMALL)


def test_decode_rejects_a_region_solved_at_a_different_size():
    crater = fx.paraboloid_crater(size=SMALL.solve_cells)
    data = B.encode(B.solve_region(REGION, array_source(crater.z, SMALL), crater_params()))
    with pytest.raises(ValueError, match="cells/side"):
        B.decode(REGION, data, plane_geometry())
