"""Topo-bathy merge rules (data v2): land from the DEM, sea floor from bathymetry, lakes stay lakes."""
import numpy as np

from terrain_slm.data import topobathy as TB


def _grid():
    # 1 x 6 strip: land, land, lake (GEDTM blank, bathymetry = lake level 300 m), shore, sea, deep sea
    base = np.array([[120.0, 15.0, 0.0, 0.0, 0.0, 0.0]], np.float32)
    valid = np.array([[True, True, False, False, False, False]])
    bathy = np.array([[110.0, 10.0, 300.0, 2.0, -40.0, -900.0]], np.float32)
    return base, valid, bathy


def test_land_keeps_the_dem_and_the_sea_takes_the_bathymetry():
    base, valid, bathy = _grid()
    out, sea = TB.merge(base, valid, valid, bathy)
    assert sea.tolist() == [[False, False, False, True, True, True]]
    assert out[0, 0] == 120.0 and out[0, 1] == 15.0            # land: the DEM, never the coarse bathymetry
    assert out[0, 3] == TB.SEA_MAX_M                            # shore cell blended up by GEBCO: kept below 0
    assert out[0, 4] == -40.0 and out[0, 5] == -900.0


def test_a_lake_is_not_the_sea():
    base, valid, bathy = _grid()
    fallback = np.array([[0, 0, 305.0, 0, 0, 0]], np.float32)   # GLO-30's flat lake surface
    out, sea = TB.merge(base, valid, valid, bathy, fallback, np.ones_like(valid))
    assert not sea[0, 2] and out[0, 2] == 305.0
    out2, _ = TB.merge(base, valid, valid, bathy)               # no fallback: the bathymetry surface, >= 0
    assert out2[0, 2] == 300.0


def test_inland_basins_below_sea_level_are_dropped():
    sea = np.zeros((7, 7), bool)
    sea[:, 0] = True                     # open sea along the west edge
    sea[1:3, 1:4] = True                 # a bay connected to it
    sea[4:6, 4:6] = True                 # a closed basin (Dead Sea / polder)
    keep = TB.ocean_connected(sea)
    assert keep[:, 0].all() and keep[1:3, 1:4].all()
    assert not keep[4:6, 4:6].any()


def test_bilinear_grid_hits_pixel_centres():
    src = np.arange(16, dtype=np.float32).reshape(4, 4)   # north-west corner (10, 20), 1 deg pixels
    lat = np.array([9.5, 8.5])                             # centres of rows 0 and 1
    lon = np.array([20.5, 21.0])                           # centre of col 0, halfway between 1 and... col 0/1 edge
    out = TB.bilinear_to_grid(src, 10.0, 20.0, 1.0, lat, lon)
    assert out[0, 0] == 0.0 and out[1, 0] == 4.0
    assert out[0, 1] == 0.5                                # halfway between cols 0 and 1


def test_a_narrow_fjord_is_sea_and_deepens_with_its_walls():
    # 40 x 60 px at 30 m: a 6-px-wide fjord from the west edge (open sea) into steep mountains;
    # coarse bathymetry reads it as land (+50 m), only its mouth as sea. A separate lake sits inland.
    h, w = 40, 60
    water = np.zeros((h, w), bool)
    water[17:23, :45] = True                          # the fjord
    water[30:34, 50:56] = True                        # a lake
    bathy = np.full((h, w), 50.0, np.float32)
    bathy[:, :3] = -200.0                             # GEBCO resolves only the mouth
    yy = np.abs(np.arange(h)[:, None] - 19.5)
    land = (yy * 30.0 * 0.8).repeat(w, 1).astype(np.float32)   # 0.8 slope walls (~39 deg)
    sea = TB.sea_from_water(water, bathy)
    assert sea[17:23, :45].all() and not sea[30:34, 50:56].any()
    depth, weight = TB.slope_continuation(sea, land, 30.0)
    assert weight[17:23, 10:40].min() > 0.9         # 180 m wide: GEBCO cannot resolve it
    mid = depth[19:21, 10:40].mean()
    edge = depth[17, 10:40].mean()
    assert mid > edge > 0 and 40 < mid < 200          # a U: deepest mid-channel, tens of metres at 6 px wide


def test_gentle_coast_stays_shallow():
    h, w = 20, 60
    water = np.zeros((h, w), bool)
    water[:, :30] = True
    bathy = np.full((h, w), -3.0, np.float32)
    land = np.tile(np.linspace(0, 6, w).astype(np.float32), (h, 1))   # 6 m over 1.8 km
    sea = TB.sea_from_water(water, bathy)
    depth, weight = TB.slope_continuation(sea, land, 30.0)
    floor = TB.sea_floor(bathy, depth, weight)
    assert floor[sea].min() > -10                       # tidal flats stay flats


def test_open_sea_beside_a_cliff_keeps_the_bathymetry():
    # A steep island in open water: no wedge of cliff-slope depth reaching out to sea.
    h = w = 200
    yy, xx = np.mgrid[:h, :w]
    r = np.hypot(yy - 100, xx - 100) * 30.0
    island = r < 600
    land = np.where(island, (600 - r) * 1.0, 0.0).astype(np.float32)     # 45 deg cliffs
    sea = ~island
    bathy = np.full((h, w), -80.0, np.float32)
    depth, weight = TB.slope_continuation(sea, land, 30.0)
    floor = TB.sea_floor(bathy, depth, weight)
    assert weight[sea].max() == 0.0 and np.allclose(floor[sea], -80.0)
