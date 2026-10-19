"""True ocean vs inland water (world/ocean.py): the sea is seeded where the controls put open water
and grows only through connected sub-sea ground; every other sub-sea basin is a lake."""
import torch

from terrain_slm.world import ocean as O

DEV = "cpu"


def _cells(n=120):
    """Land at 50 m with open ocean (land weight 0) along the top rows."""
    h = torch.full((n, n), 50.0, device=DEV)
    land = torch.ones(n, n, device=DEV)
    h[:10] = -300.0
    land[:10] = 0.0
    return h, land


def test_an_inland_basin_is_a_lake_however_large():
    h, land = _cells()
    h[50:100, 30:90] = -40.0                     # 50 x 60 cells (~12 x 14 km), not touching the sea
    sea = O.sea_cells(h, land)
    assert bool(sea[:10].all())
    assert not bool(sea[55:95, 35:85].any())


def test_a_bay_connected_to_the_sea_is_sea():
    h, land = _cells()
    h[10:40, 55:60] = -5.0                       # an inlet off the coast
    h[40:60, 40:80] = -20.0                      # the bay it opens into
    sea = O.sea_cells(h, land)
    assert bool(sea[40:60, 40:80].all())


def test_sea_growth_is_bounded():
    """Bounded growth keeps the mask request-independent: water further than SEA_GROW_CELLS of
    sub-sea path from the seed is not reached (a lake), whatever it connects to."""
    n = O.SEA_GROW_CELLS + 60
    h = torch.full((n, 9), 50.0, device=DEV)
    land = torch.ones(n, 9, device=DEV)
    h[0, :], land[0, :] = -300.0, 0.0
    h[:, 4] = -5.0                               # a long one-cell channel inland
    sea = O.sea_cells(h, land)
    assert bool(sea[O.SEA_GROW_CELLS, 4])
    assert not bool(sea[O.SEA_GROW_CELLS + O.SEA_COAST_CELLS + 2 :, 4].any())


def test_ocean_columns_join_connected_sub_sea_columns_within_reach():
    sub = torch.zeros(10, 40, dtype=torch.bool, device=DEV)
    sea = torch.zeros_like(sub)
    sub[4, :] = True                             # one sub-sea channel along the row
    sea[:, :3] = True                            # the cell sea mask covers its first columns
    sub[8, 30] = True                            # a separate pit
    oc = O.ocean_columns(sub, sea, reach=10)
    assert bool(oc[4, :13].all()) and not bool(oc[4, 13:].any())
    assert not bool(oc[8, 30])
    assert not bool((oc & ~sub).any())


def test_lakes_classify_just_above_sea_level():
    elev = torch.tensor([[-30.0, -30.0, 12.0]], device=DEV)
    lake = torch.tensor([[True, False, False]], device=DEV)
    out = O.classify_elev(elev, lake)
    assert out.tolist() == [[O.LAKE_CLASSIFY_ELEV_M, -30.0, 12.0]]
