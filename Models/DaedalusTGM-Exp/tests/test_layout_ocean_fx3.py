"""FX3 (2026-11-19): v2 worlds' karst provinces at the intended 1 in 8 of land (controls2 `karst2`), the distance-aware
coastal shelf (generator2._shelf_profile_remap: only depth beyond the real coastal profile is compressed) and the
inland lake-bed cap. Report: Dev Working/docs/exp2-work/FX3-layout-ocean-fixes.md."""
import math
import threading

import pytest
import torch

from terrain_slm.world import controls2 as C2
from terrain_slm.world import generator as G
from terrain_slm.world import generator2 as G2

P = lambda r: G2.SEA_PROFILE_M0 + G2.SEA_PROFILE_SLOPE * r


# ---- the distance-aware shelf --------------------------------------------------------------------------------------

def test_profile_shelf_keeps_land_coastline_and_floors_above_the_profile():
    dist = torch.tensor([0.0, 500.0, 5000.0, 15000.0], dtype=torch.float64)
    land = torch.tensor([0.0, 3.0, 250.0, 4000.0], dtype=torch.float64)
    assert torch.equal(G2._shelf_profile_remap(land, dist), land)
    shallow = -0.9 * P(dist)                                       # above the profile: untouched, bit for bit
    assert torch.equal(G2._shelf_profile_remap(shallow, dist), shallow)
    sea = torch.tensor([-1e-3, -1.0, -5.0, -50.0], dtype=torch.float64)
    assert torch.all(G2._shelf_profile_remap(sea, dist) < 0)       # a sea cell never becomes land


def test_profile_shelf_compresses_only_the_excess_monotone_and_c1():
    for r in (0.0, 1000.0, 3000.0, 15000.0):
        h = torch.arange(-6000.0, 0.25, 0.25, dtype=torch.float64)
        f = G2._shelf_profile_remap(h, torch.full_like(h, r))
        df = torch.diff(f) / 0.25
        assert torch.all(df > 0), r                                # strictly monotone at a fixed distance
        assert torch.all(f >= h) and torch.all(-f >= torch.minimum(-h, torch.full_like(h, P(r))) - 1e-9)
        assert torch.abs(torch.diff(df)).max().item() < 0.05, r     # C1: no slope jump at the profile
    # Past the keep band the excess is compressed by SEA_PROFILE_COMPRESS
    r = 1000.0
    d = P(r) + 2 * G2.SEA_PROFILE_KEEP_M + 1.0
    h = torch.tensor([-d, -d - 0.5], dtype=torch.float64)
    f = G2._shelf_profile_remap(h, torch.full_like(h, r))
    assert ((f[0] - f[1]) / 0.5).item() == pytest.approx(G2.SEA_PROFILE_COMPRESS, abs=0.02)


def test_spawn_style_shallow_seas_keep_their_depth():
    """review-terrain item 2: seed 4242's spawn sea (46 m median, 5-25 km out) was halved by the pointwise remap."""
    h = torch.full((5,), -46.5, dtype=torch.float64)
    dist = torch.tensor([3000.0, 5000.0, 10000.0, 15000.0, 25000.0], dtype=torch.float64).clamp_max(
        G2.SEA_PROFILE_MAX_CELLS * 240.0)
    assert torch.equal(G2._shelf_profile_remap(h, dist), h)
    assert G2._shelf_remap(h)[0].item() > -25.0                     # what the pointwise remap did


def test_lake_cap():
    dist = torch.tensor([0.0, 300.0, 3000.0, 9000.0], dtype=torch.float64)
    h = torch.full((4,), -1000.0, dtype=torch.float64)
    lake = torch.ones_like(h)
    f = G2._shelf_profile_remap(h, dist, lake)
    assert torch.allclose(-f, G2.LAKE_DEPTH_M0 + G2.LAKE_DEPTH_SLOPE * dist)   # a bowl from the lake's shore
    shallow = torch.full((4,), -5.0, dtype=torch.float64)
    assert torch.equal(G2._shelf_profile_remap(shallow, dist, lake), shallow)  # shallow lakes are untouched
    assert torch.equal(G2._shelf_profile_remap(h, dist, torch.zeros_like(h)), G2._shelf_profile_remap(h, dist))


def test_distance_cells_values_and_request_independence():
    g = torch.Generator().manual_seed(3)
    land = torch.rand(120, 140, generator=g) > 0.995
    cap = 12
    d = G2._distance_cells(land, cap)
    assert torch.all(d[land] == 0) and float(d.max()) <= cap
    one = torch.zeros(9, 9, dtype=torch.bool)
    one[4, 4] = True
    d1 = G2._distance_cells(one, 8)
    assert d1[4, 5].item() == 1.0 and d1[5, 5].item() == pytest.approx(math.sqrt(2)) and d1[4, 7].item() == 3.0
    # sub-windows: exact for cells >= cap + 1 inside
    for a, b in ((20, 30), (37, 11), (0, 0)):
        sub = land[a:a + 70, b:b + 90]
        ds = G2._distance_cells(sub, cap)
        m = cap + 1
        inner = ds[m:-m, m:-m]
        if a >= 0 and b >= 0:
            ref = d[a + m:a + 70 - m, b + m:b + 90 - m]
            if a >= m and b >= m:
                assert torch.equal(inner, ref), (a, b)


# ---- karst2 ----------------------------------------------------------------------------------------------------------

def test_karst2_formula_home_and_share():
    ci = torch.arange(-40, 40, dtype=torch.float32).view(-1, 1).expand(80, 80) * 9.0     # +-360 cells, sparse
    cj = torch.arange(-40, 40, dtype=torch.float32).view(1, -1).expand(80, 80) * 9.0 + 3.0
    c = C2.controls2_at(ci, cj, 12345)
    z = G._fbm(ci, cj, C2.KARST_PERIOD_CELLS, 12345, 45, octaves=2) / G._FBM2_STD
    home = C2._ss((torch.sqrt(ci ** 2 + cj ** 2) - C2.KARST_HOME_CELLS[0]) / C2.KARST_HOME_CELLS[1])
    assert torch.equal(c["karst2"], C2._ss((z + C2.KARST_BIAS_V2) / 0.3) * home * c["land"])
    near = torch.sqrt(ci ** 2 + cj ** 2) <= C2.KARST_HOME_CELLS[0]
    assert float(c["karst2"][near].abs().max()) == 0.0              # no karst provinces around spawn
    assert torch.equal(c["karst"], C2._ss((z + C2.KARST_BIAS) / 0.3) * c["land"])    # v1's field unchanged
    # Share: z > 0.15 - bias (karst2 > 0.5) on ~1 in 8 of random cells far from spawn (the province field is
    # independent of land); v1's bias gives ~21 %.
    g = torch.Generator().manual_seed(0)
    pi, pj = [(torch.rand(400_000, generator=g) - 0.5) * 4e6 for _ in range(2)]
    zz = torch.cat([G._fbm(pi, pj, C2.KARST_PERIOD_CELLS, s, 45, octaves=2) / G._FBM2_STD for s in range(4)])
    share2 = float((C2._ss((zz + C2.KARST_BIAS_V2) / 0.3) > 0.5).float().mean())
    share1 = float((C2._ss((zz + C2.KARST_BIAS) / 0.3) > 0.5).float().mean())
    assert 0.11 < share2 < 0.14 and 0.19 < share1 < 0.24, (share2, share1)
    assert float(zz.std()) == pytest.approx(0.337 / G._FBM2_STD, rel=0.03)  # why v1's share is off: std 1.25


def test_tile_carve_reads_karst2_for_v2_worlds_only(monkeypatch):
    from terrain_slm.models import detail2 as D2
    from terrain_slm.world import carve_node as CN
    from terrain_slm.world.tiles import TileBuilder
    from terrain_slm.world.world_config import CURVE_V2_KEYS, WorldConfig, curve_v2_keys

    class Gen:
        device = "cpu"
        lock = threading.RLock()
        detail = object()

        def _detail_samples(self, s1, t1, s2, t2):
            return torch.zeros(s2 - s1, t2 - t1)

        def controls(self, ci0, cj0, hc, wc):
            z = torch.zeros(hc, wc)
            return {"gates": torch.zeros(len(D2.STYLES), hc, wc), "aridity": z + 1, "karst": z, "karst2": z + 1}

        def drainage(self, *a):
            return {}

        def set_block_scale(self, scale):
            self.scale = scale

    seen = []
    monkeypatch.setattr(CN, "carve_codes", lambda h60, ar, rv, gates, margin: seen.append(
        float(gates["karst"].mean())) or torch.zeros_like(h60, dtype=torch.long))
    base = {**{k: v for k, v in WorldConfig.GAME.to_dict().items() if k not in CURVE_V2_KEYS},
            "world_height": 640, "min_y": -384}
    for v2 in (False, True):                 # build() sets the generator's flag from the world's height curve
        gen = Gen()
        gen.sea_shelf = v2
        TileBuilder(gen, WorldConfig.from_dict(base))._carve(0, 0, 16, 16, 1)
    assert seen == [0.0, 1.0]


def test_profile_shelf_is_off_without_the_v2_flag_and_pointwise_without_an_origin():
    class Gen(G2.WorldGenerator2):
        def __init__(self):
            pass
    g = Gen()
    h = torch.tensor([-300.0, -40.0, 20.0])
    g.sea_shelf = False
    assert torch.equal(g.shelf(h, 0, 0, 8), h)
    g.sea_shelf = True
    assert torch.equal(g.shelf(h), G2._shelf_remap(h))


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the EXP-2 model on a GPU")
def test_sea_fields_are_request_independent_and_capped_lakes_only_inland():
    import os
    from pathlib import Path
    from terrain_slm.eval import DEFAULT_EXP2_MODEL
    if not (Path(DEFAULT_EXP2_MODEL) / "planner.pt").exists():
        pytest.skip("no EXP-2 model")
    dev = os.environ.get("TGM_TEST_DEVICE") or ("cuda:1" if torch.cuda.device_count() > 1 else "cuda")
    gen = G2.WorldGenerator2(Path(DEFAULT_EXP2_MODEL), 4242, dev)
    gen.use_sea_shelf(True)
    with torch.no_grad():
        d0, l0 = gen.sea_fields_cells(-130, -60, 96, 96)
        d1, l1 = gen.sea_fields_cells(-100, -40, 40, 50)
        assert torch.equal(d0[30:70, 20:70], d1) and torch.equal(l0[30:70, 20:70], l1)
        p0 = gen.sea_fields_px(-1040, -480, 300, 256, G2.CP, dev)
        p1 = gen.sea_fields_px(-1000, -400, 64, 72, G2.CP, dev)
        assert torch.equal(p0[0][40:104, 80:152], p1[0]) and torch.equal(p0[1][40:104, 80:152], p1[1])
        h = gen._planner_cells(-130, -60, 96, 96)[0]
        out = gen.overview_cells(-130, -60, 96, 96)["height"]
    gen.use_sea_shelf(False)
    assert bool(l0.any()), "the seed-4242 window holds the review's pit lake"
    assert torch.all(d0[h >= 0] == 0)
    assert float((-out[l0]).max()) <= float((G2.LAKE_DEPTH_M0 + G2.LAKE_DEPTH_SLOPE * d0[l0]).max()) + 1.0


def test_fixed_step_sea_growth_equals_ocean_sea_cells():
    """sea_fields_cells grows the sea with a fixed number of max-pool steps (no convergence sync); same mask."""
    import torch.nn.functional as F
    from terrain_slm.world import ocean as O
    g = torch.Generator().manual_seed(11)
    cells = torch.nn.functional.avg_pool2d(torch.randn(1, 1, 200, 220, generator=g), 9, 1, 4)[0, 0] * 900.0 - 40.0
    land = torch.rand(200, 220, generator=g)
    sub = cells < 0.0
    sea = (sub & (land < O.SEA_LAND)).float()[None, None]
    for _ in range(O.SEA_GROW_CELLS):
        sea = F.max_pool2d(sea, 3, 1, 1) * sub.float()[None, None]
    r = O.SEA_COAST_CELLS
    mine = F.max_pool2d(sea, 2 * r + 1, 1, r)[0, 0] > 0.5
    assert torch.equal(mine, O.sea_cells(cells, land))


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the EXP-2 model on a GPU")
def test_sea_fields_seed_land_matches_continent_land_cells():
    """sea_fields_cells rebuilds v2's sea seeds from continent2 + the layout trend (one continent2 call); they must
    stay the true-sea classification's (`continent_land_cells`)."""
    import os
    from pathlib import Path
    from terrain_slm.eval import DEFAULT_EXP2_MODEL
    if not (Path(DEFAULT_EXP2_MODEL) / "planner.pt").exists():
        pytest.skip("no EXP-2 model")
    dev = os.environ.get("TGM_TEST_DEVICE") or ("cuda:1" if torch.cuda.device_count() > 1 else "cuda")
    gen = G2.WorldGenerator2(Path(DEFAULT_EXP2_MODEL), 2, dev)
    gen.use_sea_shelf(True)
    ci0, cj0, n = -40, -3640, 64
    with torch.no_grad():
        ref = gen.continent_land_cells(ci0, cj0, n, n, dev)
        ci = torch.arange(ci0, ci0 + n, device=dev, dtype=torch.float32).view(-1, 1).expand(n, n)
        cj = torch.arange(cj0, cj0 + n, device=dev, dtype=torch.float32).view(1, -1).expand(n, n)
        land = C2.continent2(ci, cj, seed=2)[1]
        trend = gen._layout(ci0, cj0, n, n)["trend"].to(dev)
    gen.use_sea_shelf(False)
    assert torch.equal(torch.where(trend < G2.SEA_SEED_TREND_M, torch.zeros_like(land), land), ref)
