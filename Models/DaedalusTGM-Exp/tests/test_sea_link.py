"""BV (beaches, 2026-11-20): height-curve v2 worlds seed the true sea from layout-drowned cells only where the layout
links them to the open ocean (generator2.sea_link_cells). Closed basins inside the continents -- the coast provinces'
island noise drowning interior lowland -- are lakes: land biome, lake bed, no beach ring."""
import os
from pathlib import Path

import pytest
import torch

from terrain_slm.world import generator2 as G2
from terrain_slm.world import ocean as O

pytestmark = pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the EXP-2 model on a GPU")


@pytest.fixture(scope="module")
def gen():
    from terrain_slm.eval import DEFAULT_EXP2_MODEL
    if not (Path(DEFAULT_EXP2_MODEL) / "planner.pt").exists():
        pytest.skip("no EXP-2 model")
    dev = os.environ.get("TGM_TEST_DEVICE") or ("cuda:1" if torch.cuda.device_count() > 1 else "cuda")
    g = G2.WorldGenerator2(Path(DEFAULT_EXP2_MODEL), 12345, dev)
    yield g
    g.use_sea_shelf(False)
    g.use_seed(12345)


def _sea(g, seed, ci0, cj0, n, link=True):
    old = G2.SEA_LINK
    G2.SEA_LINK = link
    try:
        g.use_seed(seed)
        g.use_sea_shelf(True)
        with torch.no_grad():
            cells = g._planner_cells(ci0, cj0, n, n)[0]
            sea = O.sea_cells(cells, g.continent_land_cells(ci0, cj0, n, n, g.device)) & (cells < 0)
        return sea, cells < 0
    finally:
        G2.SEA_LINK = old


def test_link_is_request_independent(gen):
    gen.use_seed(12345)
    gen.use_sea_shelf(True)
    with torch.no_grad():
        a = gen.sea_link_cells(-200, -150, 160, 120, gen.device)
        b = gen.sea_link_cells(-137, -97, 50, 61, gen.device)       # an inner window, unaligned to the super grid
    assert torch.equal(a[63:113, 53:114], b)


def test_closed_basins_around_12345_spawn_are_lakes(gen):
    """Every sub-sea basin within +-300 cells (72 km) of seed 12345's spawn is closed in the layout (continent2 land
    1.0, ground +150 m before the island noise): the trend gate alone made them sea (~19 k cells)."""
    sea0, sub = _sea(gen, 12345, -300, -300, 600, link=False)
    sea1, _ = _sea(gen, 12345, -300, -300, 600)
    assert int(sea0.sum()) > 10_000
    assert int(sea1.sum()) == 0


def test_linked_seas_are_kept(gen):
    """Seed 4242's spawn sea and seed 2's drowned coast (A's capture) open onto the ocean: (nearly) all kept."""
    for seed, ci0, cj0, n in ((4242, -300, -300, 600), (2, -408, -4008, 800)):
        sea0, _ = _sea(gen, seed, ci0, cj0, n, link=False)
        sea1, _ = _sea(gen, seed, ci0, cj0, n)
        assert bool(torch.all(sea1 <= sea0))                       # the link only removes seeds
        assert int(sea1.sum()) >= 0.9 * int(sea0.sum()), (seed, int(sea1.sum()), int(sea0.sum()))


def test_v1_worlds_are_untouched(gen):
    from terrain_slm.world import generator as G
    gen.use_seed(12345)
    gen.use_sea_shelf(False)
    with torch.no_grad():
        assert torch.equal(gen.continent_land_cells(-40, -60, 48, 48, gen.device),
                           G.continent_land(-40, -60, 48, 48, 12345, gen.device))


def test_model_id_names_the_link(gen):
    assert (f":sl{G2.SEA_LINK_VERSION}" in gen.model_id) == G2.SEA_LINK


def test_no_sidecar_sand_rings_on_lake_shores(gen):
    """tiles.LAKE_SHORE_DESERT_M: the biome sidecar's coastal desert (sand) beside lakes is gone in v2 worlds (seed 3's
    temperate lakes had it on ~half their shore columns; v1 worlds never run the gate: it reads gen.sea_shelf)."""
    from scipy import ndimage
    from terrain_slm.world import tiles as T
    from test_native_tiles import _world
    import numpy as np

    def lake_shore_desert(world):
        tb = T.TileBuilder(gen, world)
        p = np.asarray(tb.build(3, 0, 0, 1)).astype(np.int32)
        h, b, w, flow = p[0], p[1], p[2], p[5]
        wet = (w >= 0) & (w > h)
        ocean = wet & np.isin(b, (41, 44, 46, 48))
        lake = wet & ~ocean & (flow < 0)
        shore = ~wet & ndimage.binary_dilation(lake, np.ones((3, 3), bool)) \
            & (ndimage.distance_transform_edt(~ocean) > 6)
        return int(shore.sum()), int((shore & (b == 5)).sum())

    n2, d2 = lake_shore_desert(_world("1:4", 2, tile=256))
    old = T.LAKE_SHORE_DESERT_M
    try:
        T.LAKE_SHORE_DESERT_M = 0.0
        _, d2_off = lake_shore_desert(_world("1:4", 2, tile=256))
    finally:
        T.LAKE_SHORE_DESERT_M = old
    assert n2 > 100
    assert d2_off > 0.2 * n2, "the probe tile should show the sidecar's lake-shore sand without the gate"
    assert d2 <= 0.02 * n2
