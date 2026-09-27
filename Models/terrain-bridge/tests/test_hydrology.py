"""Correctness tests for the hydrology package.

Split in two, deliberately:

  * **Closed-form** tests run against `hydrology.fixtures` -- synthetic DEMs built
    backwards from a known answer, so the assertions are arithmetic rather than a
    snapshot of a previous run. A real DEM cannot serve here: it shows you where the
    basins are but never how deep the water in them should be, so a plausible-looking
    result on real terrain would prove nothing.

  * **Invariant** tests hold on *any* DEM, ground truth or not: water is conserved,
    nothing flows uphill, lake surfaces are level, output stays aligned with input.
    These are what protect the real-terrain path, which is exercised by
    `scripts/test_water_overlay.py` visually rather than numerically.
"""
from __future__ import annotations

import numpy as np
import pytest
from scipy import ndimage

from hydrology import fixtures as fx
from hydrology.depth import water_depth
from hydrology.fill import fill_depressions, ocean_mask
from hydrology.flats import resolve_flats
from hydrology.flow import d8_receivers, flow_accumulation
from hydrology.io import DemMeta, save_map

# The fixtures are built to be resolved exactly, so basin filtering is disabled for
# them; puddle rejection is a presentation choice and is tested separately.
EXACT = dict(min_lake_area=1, min_lake_depth=0.0)


def route(z):
    """Fill, resolve flats, route and accumulate -- the same chain `water_depth` runs.

    Returns the *routing* surface, not the fill, since that is what the flow graph is
    built on and what the descent invariants have to hold against.
    """
    ocean = ocean_mask(z)
    filled = fill_depressions(z, epsilon=0.0, invalid=ocean)
    routing = resolve_flats(filled, ocean)
    recv = d8_receivers(routing, ocean)
    acc = flow_accumulation(recv, ~ocean)
    return ocean, routing, recv, acc


# --------------------------------------------------------------------------------
# Closed-form ground truth
# --------------------------------------------------------------------------------

def test_inclined_plane_accumulation_is_exact():
    """On a pure downslope, the upslope count at row i must be exactly i + 1.

    Regression guard for the two boundary bugs in upstream's `d8_flow`: treating the
    array edge as a sink strands row 0 and the outer columns at 1.0, and clipping the
    receiver index instead makes edge cells point at themselves.
    """
    f = fx.inclined_plane()
    _, _, _, acc = route(f.z)
    np.testing.assert_array_equal(acc, f.expected_accumulation)


def test_paraboloid_crater_depth_matches_the_analytic_profile():
    """Lake surface settles at the rim, so depth is rim - z at every submerged cell."""
    f = fx.paraboloid_crater()
    wm = water_depth(f.z, **EXACT)
    np.testing.assert_allclose(wm.lake_depth, f.expected_lake_depth, atol=1e-4)


def test_paraboloid_crater_volume_matches_the_integral():
    """Summed depth must match depth * pi * r**2 / 2, up to grid sampling error."""
    f = fx.paraboloid_crater()
    wm = water_depth(f.z, **EXACT)
    assert wm.lake_depth.sum() == pytest.approx(f.expected_lake_volume, rel=0.02)


def test_basins_fill_to_their_spill_points_not_their_walls():
    """Pit A fills to its 80 m saddle (depth 30), pit B to its 70 m channel (depth 10).

    Filling to the 100 m plateau instead would give 50 and 40; not filling at all
    would give 0. Only correct spill-point resolution produces 30 and 10.
    """
    f = fx.two_basins_saddle()
    wm = water_depth(f.z, **EXACT)
    assert wm.lake_depth[20, 15] == pytest.approx(30.0)
    assert wm.lake_depth[20, 35] == pytest.approx(10.0)
    np.testing.assert_allclose(wm.lake_depth, f.expected_lake_depth, atol=1e-6)


def test_lower_basin_drains_through_the_upper_one_to_the_sea():
    """Pit A's catchment must reach the coast through pit B's channel.

    The chain is A (fills to 80) -> saddle -> B (fills to 70) -> channel -> sea, so
    every one of those 134 cells has to be counted at the channel's exit. Getting only
    B's own 74 back means A's water was lost to a pit somewhere upstream.
    """
    f = fx.two_basins_saddle()
    ocean, _, recv, acc = route(f.z)

    upstream = np.zeros(f.z.shape, dtype=bool)
    upstream[18:22, 10:20] = True   # pit A
    upstream[19:21, 20:30] = True   # saddle
    upstream[18:22, 30:40] = True   # pit B
    upstream[19:21, 40:57] = True   # channel to the sea
    exit_cells = acc[19:21, 55:57]

    assert exit_cells.sum() >= upstream.sum(), (
        f"only {exit_cells.sum():.0f} of {upstream.sum()} upstream cells reached the "
        "coast; part of the catchment drained into a spurious pit"
    )


def test_monotone_valley_has_no_lakes():
    f = fx.v_valley_to_coast()
    wm = water_depth(f.z, **EXACT)
    assert wm.lake_depth.sum() == 0.0
    assert wm.depth.max() > 0.0, "a valley this size should still carry a river"


# --------------------------------------------------------------------------------
# Invariants that hold on any DEM
# --------------------------------------------------------------------------------

@pytest.mark.parametrize("factory", fx.ALL_FIXTURES, ids=lambda f: f.__name__)
def test_water_is_conserved(factory):
    """Accumulation over the terminal cells must equal the land-cell count.

    Every land cell contributes 1.0 and every drop leaves through exactly one outlet,
    so the totals have to match. Under-counting here is how a broken traversal order
    fails: it produces a map that still looks like a river network but with the wrong
    discharge, and therefore the wrong depths.
    """
    f = factory()
    ocean, _, recv, acc = route(f.z)
    terminal = (recv.reshape(f.z.shape) < 0) & ~ocean
    assert acc[terminal].sum() == pytest.approx(float((~ocean).sum()))


@pytest.mark.parametrize("factory", fx.ALL_FIXTURES, ids=lambda f: f.__name__)
def test_no_land_cell_is_a_spurious_pit(factory):
    """Flow may only stop at the sea or at the tile edge -- never mid-terrain.

    This is the invariant that catches a flat-resolution bug, which is otherwise
    almost invisible: conservation still balances and the surface still descends,
    but a chunk of the catchment quietly terminates inland instead of reaching the
    coast, and the rivers downstream of it come out too small.
    """
    f = factory()
    ocean, _, recv, _ = route(f.z)
    h, w = f.z.shape

    terminal = (recv.reshape(h, w) < 0) & ~ocean
    coastal = ndimage.binary_dilation(ocean, structure=np.ones((3, 3), dtype=bool))
    on_edge = np.zeros((h, w), dtype=bool)
    on_edge[0, :] = on_edge[-1, :] = on_edge[:, 0] = on_edge[:, -1] = True

    stranded = terminal & ~coastal & ~on_edge
    assert not stranded.any(), (
        f"{stranded.sum()} land cells drain nowhere; "
        f"first at {tuple(int(v) for v in np.argwhere(stranded)[0])}"
    )


@pytest.mark.parametrize("factory", fx.ALL_FIXTURES, ids=lambda f: f.__name__)
def test_nothing_flows_uphill(factory):
    """Every D8 edge must strictly descend on the filled surface, or the graph cycles."""
    f = factory()
    _, filled, recv, _ = route(f.z)
    flat = filled.ravel()
    has_recv = recv >= 0
    assert np.all(flat[np.flatnonzero(has_recv)] > flat[recv[has_recv]])


@pytest.mark.parametrize("factory", fx.ALL_FIXTURES, ids=lambda f: f.__name__)
def test_fill_only_ever_raises_terrain(factory):
    f = factory()
    ocean = ocean_mask(f.z)
    filled = fill_depressions(f.z, epsilon=0.0, invalid=ocean)
    assert np.all(filled[~ocean] >= f.z[~ocean] - 1e-9)
    np.testing.assert_array_equal(filled[ocean], f.z[ocean])


@pytest.mark.parametrize("factory", fx.ALL_FIXTURES, ids=lambda f: f.__name__)
def test_output_layers_stay_aligned_and_finite(factory):
    f = factory()
    wm = water_depth(f.z, **EXACT)
    for name in ("depth", "lake_depth", "river_depth", "accumulation", "filled", "ocean"):
        layer = getattr(wm, name)
        assert layer.shape == f.z.shape, f"{name} lost alignment with the DEM"
    assert np.all(np.isfinite(wm.depth))
    assert np.all(wm.depth >= 0.0)
    assert np.all(wm.depth[wm.ocean] == 0.0), "ocean is a separate mask, not a depth"


def test_lake_surfaces_are_level():
    """Within one basin, filled elevation must be constant -- water does not slope.

    This is the fill that lake depth is measured against. The routing surface is
    deliberately *not* level, which is why the two are kept separate.
    """
    f = fx.two_basins_saddle()
    ocean = ocean_mask(f.z)
    flat = fill_depressions(f.z, epsilon=0.0, invalid=ocean)
    for rows, cols, level in ((slice(18, 22), slice(10, 20), 80.0),
                              (slice(18, 22), slice(30, 40), 70.0)):
        np.testing.assert_allclose(flat[rows, cols], level, atol=1e-9)


def test_routing_gradient_does_not_leak_into_depth():
    """Flat resolution must not be mistaken for standing water.

    A flat plain gets a drainage gradient laid over it so D8 can route. Measuring
    depth against *that* surface would report a film of water across the whole plain,
    which is why depth comes from the untouched fill.
    """
    z = np.full((64, 64), 100.0)
    z[-1, :] = fx.OCEAN
    wm = water_depth(z, **EXACT)
    assert wm.lake_depth.sum() == 0.0


def test_flat_resolution_leaves_terrain_alone_outside_flats():
    """The gradient is a routing device; off the flats the surface must be identical."""
    f = fx.two_basins_saddle()
    ocean = ocean_mask(f.z)
    filled = fill_depressions(f.z, epsilon=0.0, invalid=ocean)
    routing = resolve_flats(filled, ocean)
    # Everywhere it does differ, it differs by far less than the terrain's own relief.
    assert np.all(routing >= filled - 1e-12)
    assert (routing - filled).max() < 0.01


def test_flat_resolution_does_not_produce_parallel_channels():
    """Regression guard for the D8 herringbone artifact.

    A wide level plain draining to one coast is the worst case: a distance-from-outlet
    gradient alone sends every cell the same direction, so flow arrives as straight
    parallel lines and a handful of cells carry enormous accumulation. Garbrecht-Martz
    bends flow off the plain's upslope margin, spreading discharge across many more
    channels. Asserted as a bound on how concentrated the top of the network gets.
    """
    h, w = 96, 96
    z = np.full((h, w), 100.0)
    z[-1, :] = fx.OCEAN
    z[0, :] = 130.0                      # high margin feeding the plain
    _, _, _, acc = route(z)

    land = z > 0
    peak_share = acc[land].max() / float(land.sum())
    assert peak_share < 0.25, (
        f"one channel carries {peak_share:.0%} of the plain's drainage; "
        "flat resolution has collapsed into parallel flow"
    )


# --------------------------------------------------------------------------------
# Thresholding and I/O
# --------------------------------------------------------------------------------

def test_min_lake_area_discards_ponds_but_keeps_lakes():
    """The rivers-vs-ponds knob filters whole basins, not individual cells."""
    z = np.full((64, 64), 100.0)
    z[-1, :] = fx.OCEAN
    z[10:12, 10:12] = 90.0      # pond:  4 cells
    z[30:40, 30:40] = 90.0      # lake: 100 cells

    kept_all = water_depth(z, min_lake_area=1, min_lake_depth=0.0)
    assert kept_all.lake_depth[10, 10] > 0 and kept_all.lake_depth[35, 35] > 0

    filtered = water_depth(z, min_lake_area=16, min_lake_depth=0.0)
    assert filtered.lake_depth[10, 10] == 0.0, "pond should have been dropped"
    assert filtered.lake_depth[35, 35] > 0.0, "lake should have survived"


def test_min_lake_depth_keeps_shallow_margins_of_a_deep_basin():
    """Filtering is per basin, so a kept lake retains its shallow edge cells."""
    f = fx.paraboloid_crater()
    wm = water_depth(f.z, min_lake_area=1, min_lake_depth=50.0)
    shallow = (wm.lake_depth > 0) & (wm.lake_depth < 1.0)
    assert shallow.any(), "a surviving basin must keep cells shallower than the threshold"


def test_river_threshold_controls_network_density():
    f = fx.v_valley_to_coast()
    dense = water_depth(f.z, river_threshold=50, **EXACT)
    sparse = water_depth(f.z, river_threshold=2000, **EXACT)
    assert (dense.river_depth > 0).sum() > (sparse.river_depth > 0).sum()


def test_save_map_refuses_a_misaligned_result(tmp_path):
    """A silently misaligned water map is worse than none -- nothing downstream would notice."""
    meta = DemMeta(shape=(64, 64))
    with pytest.raises(ValueError, match="align"):
        save_map(tmp_path / "out.npy", np.zeros((32, 32)), meta)


def test_npy_round_trip_preserves_values(tmp_path):
    from hydrology.io import load_dem

    f = fx.paraboloid_crater()
    src = tmp_path / "dem.npy"
    np.save(src, f.z)
    z, meta = load_dem(src)
    np.testing.assert_allclose(z, f.z)

    wm = water_depth(z, **EXACT)
    out = save_map(tmp_path / "dem_water_depth.npy", wm.depth, meta)
    np.testing.assert_allclose(np.load(out), wm.depth)


def test_geotiff_round_trip_carries_georeference(tmp_path):
    """CRS and transform must survive verbatim, or the overlay lands in the wrong place."""
    rasterio = pytest.importorskip("rasterio")
    from affine import Affine

    from hydrology.io import load_dem

    f = fx.paraboloid_crater()
    src = tmp_path / "dem.tif"
    transform = Affine(30.0, 0.0, 500000.0, 0.0, -30.0, 4000000.0)
    with rasterio.open(src, "w", driver="GTiff", height=f.z.shape[0], width=f.z.shape[1],
                       count=1, dtype="float32", crs="EPSG:32633", transform=transform) as dst:
        dst.write(f.z.astype("float32"), 1)

    z, meta = load_dem(src)
    wm = water_depth(z, **EXACT)
    out = save_map(tmp_path / "dem_water_depth.tif", wm.depth, meta)

    with rasterio.open(out) as ds:
        assert ds.transform == transform
        assert ds.crs.to_epsg() == 32633
        assert ds.shape == f.z.shape
        np.testing.assert_allclose(ds.read(1), wm.depth, atol=1e-5)


def test_results_are_deterministic():
    """Same input, same bytes -- seed-determinism is the premise of this whole pipeline."""
    f = fx.two_basins_saddle()
    a = water_depth(f.z, **EXACT)
    b = water_depth(f.z, **EXACT)
    np.testing.assert_array_equal(a.depth, b.depth)
    np.testing.assert_array_equal(a.accumulation, b.accumulation)


def test_all_ocean_dem_produces_no_water():
    """Degenerate input must not raise; an all-sea tile is common near the map edge."""
    wm = water_depth(np.full((32, 32), -50.0))
    assert wm.depth.sum() == 0.0
    assert wm.accumulation.sum() == 0.0


def test_coarse_quantisation_is_flagged():
    """Block-height input must be called out, not silently routed.

    A DEM quantised to whole Stonebreak blocks has no lower neighbour over most of its
    area, so the rivers come from the flat resolver rather than the terrain. The result
    looks self-consistent and nothing downstream can tell, so the warning is the only
    thing standing between that and a shipped map of straight parallel rivers.
    """
    from hydrology.io import vertical_resolution_warning

    f = fx.fractal_terrain(128, seed=1)
    assert vertical_resolution_warning(f.z) is None

    blocky = np.where(f.z > 0, np.floor(f.z / 15.0) * 15.0, f.z)
    msg = vertical_resolution_warning(blocky)
    assert msg is not None and "block heights" in msg


def test_fractal_terrain_drains_without_stranding_water():
    """The realistic-terrain path must satisfy the same invariants as the fixtures."""
    f = fx.fractal_terrain(192, seed=7)
    ocean, _, recv, acc = route(f.z)
    terminal = (recv.reshape(f.z.shape) < 0) & ~ocean
    assert acc[terminal].sum() == pytest.approx(float((~ocean).sum()))
