"""EXP-2 coastal shelf (2026-11-19): generator2._shelf_remap, the sub-sea depth remap of the sea merge."""
import pytest
import torch

from terrain_slm.world import generator2 as G2


def test_land_and_coastline_unchanged():
    h = torch.tensor([0.0, 1e-3, 5.0, 300.0, 4000.0])
    assert torch.equal(G2._shelf_remap(h), h)
    sea = torch.tensor([-1e-3, -1.0, -5.0])
    assert torch.all(G2._shelf_remap(sea) < 0)                # a sea cell never becomes land


def test_strictly_monotone_and_c1():
    h = torch.arange(-12000.0, 0.25, 0.5, dtype=torch.float64)   # up to the waterline (0)
    f = G2._shelf_remap(h)
    df = torch.diff(f) / 0.5
    assert torch.all(df > 0)
    # Slope: SEA_SHELF_KEEP at the waterline (shallow water keeps its depth), SEA_SHELF_SLOPE once past
    # SEA_SHELF_KEEP_M, 1 below SEA_SHELF_DEPTH_M, no jumps anywhere (C1).
    assert df[-1].item() == pytest.approx(G2.SEA_SHELF_KEEP, abs=1e-3)
    mid = int((12000.0 - 2 * G2.SEA_SHELF_KEEP_M) / 0.5)                        # depth 2 x keep_m
    assert df[mid].item() == pytest.approx(G2.SEA_SHELF_SLOPE, abs=0.01)
    assert df[0].item() == pytest.approx(1.0, abs=1e-9)
    # C1: per 0.5 m the slope moves at most ~0.75 x 1.875 / (2 x 30) (the keep band's smoothstep), no jump
    assert torch.abs(torch.diff(df)).max().item() < 0.03


def test_depth_profile_and_deep_offset():
    k, D, k0, d0 = G2.SEA_SHELF_SLOPE, G2.SEA_SHELF_DEPTH_M, G2.SEA_SHELF_KEEP, G2.SEA_SHELF_KEEP_M
    f = lambda d: -G2._shelf_remap(torch.tensor([-d], dtype=torch.float64)).item()
    # The first metres keep their depth (round 2: compressing shallow water left 18 % of the sea < 3 blocks deep).
    assert f(5.0) == pytest.approx(k0 * 5.0, rel=0.02)
    # Past the keep band: compressed by k (census medians 207 / 359 m at 0.5-2 / 2-5 km -> 64 / 108 m).
    assert f(207.0) == pytest.approx(64.0, abs=1.0) and f(359.0) == pytest.approx(108.0, abs=1.0)
    # Below D every depth is the same constant shallower: abyss and trenches keep their shape.
    off = (1 - k) * D / 2 - (k0 - k) * d0 / 2
    for d in (D, 4200.0, 9700.0):
        assert d - f(d) == pytest.approx(off, abs=1e-6)


def test_off_switch_is_identity():
    h = torch.linspace(-5000.0, 500.0, 101)
    assert torch.equal(G2._shelf_remap(h, slope=1.0), h)


def test_tile_builder_turns_the_shelf_on_for_curve_v2_worlds_only():
    """Saved v1 worlds keep their sea bed (no seams); v2 worlds get the shelf. Wiring only (no model)."""
    import threading
    from terrain_slm.world.tiles import TileBuilder
    from terrain_slm.world.world_config import CURVE_V2_KEYS, WorldConfig, curve_v2_keys

    class Gen:
        device = "cpu"
        lock = threading.RLock()
        sea_shelf = None

        def set_block_scale(self, scale):
            self.scale = scale

        def use_seed(self, seed):
            pass

        def use_sea_shelf(self, on):
            self.sea_shelf = on

    base = {**{k: v for k, v in WorldConfig.GAME.to_dict().items() if k not in CURVE_V2_KEYS},
            "world_height": 640, "min_y": -384}
    v1 = WorldConfig.from_dict(base)
    v2 = WorldConfig.from_dict({**base, **curve_v2_keys("1:4")})
    seen = []
    for world in (v2, v1, v2):
        gen = Gen()
        b = TileBuilder(gen, world)
        b._blocks = lambda *a: seen.append(gen.sea_shelf) or (_ for _ in ()).throw(StopIteration)
        try:
            b.build(1, 0, 0, 1)
        except StopIteration:
            pass
    assert seen == [True, False, True]


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the EXP-2 model on a GPU")
def test_overview_shelf_matches_the_remap_and_keeps_land():
    from pathlib import Path
    model = Path(__file__).resolve().parents[1] / "checkpoints/exp2/world_exp2b"
    if not (model / "planner.pt").exists():
        pytest.skip("no EXP-2 model")
    dev = "cuda:1" if torch.cuda.device_count() > 1 else "cuda"
    gen = G2.WorldGenerator2(model, 0, dev)
    # A coastal window (the ocean census' seed-0 coast near block (3626, 10904) = cell (906, 2726)), or the first
    # candidate that is 10-90 % sea (the terrain keeps evolving under other workstreams).
    with torch.no_grad():
        gen.use_sea_shelf(False)
        for ci, cj in ((858, 2678), (0, 0), (-400, 300), (300, -400), (1200, 1200)):
            off = gen.overview_cells(ci, cj, 96, 96)["height"]
            if 0.1 < float((off < 0).float().mean()) < 0.9:
                break
        else:
            pytest.skip("no coastal window among the candidates")
        gen.use_sea_shelf(True)
        on = gen.overview_cells(ci, cj, 96, 96)["height"]
        dist, lake = gen.sea_fields_cells(ci, cj, 96, 96)
        old = G2.SEA_PROFILE
        try:                                                  # FX3's A/B switch: round 2's pointwise shelf
            G2.SEA_PROFILE = False
            pointwise = gen.overview_cells(ci, cj, 96, 96)["height"]
        finally:
            G2.SEA_PROFILE = old
    gen.use_sea_shelf(False)
    assert bool((off < 0).any()) and bool((off > 0).any()), "pick a coastal window"
    # Two overview calls may recompute evicted windows (GPU: not always bit-identical), hence tolerances.
    clear = off.abs() > 1.0
    assert torch.allclose(on[off >= 0], off[off >= 0], atol=0.05)
    # FX3: the distance-aware shelf (+ lake-bed cap) by default, the pointwise remap with SEA_PROFILE off
    assert torch.allclose(on, G2._shelf_profile_remap(off, dist, lake.float()), atol=0.05)
    assert torch.allclose(pointwise, G2._shelf_remap(off), atol=0.05)
    assert torch.equal((on < 0)[clear], (off < 0)[clear])                     # the coastline does not move
    wet = off < -20.0
    assert bool(wet.any())
    assert bool(torch.all(on[wet] >= off[wet] - 0.05))                        # never deeper
    over = wet & (-off > 2.0 * (G2.SEA_PROFILE_M0 + G2.SEA_PROFILE_SLOPE * dist) + 50.0)
    assert bool(over.any()) and float((-on[over]).mean()) < 0.8 * float((-off[over]).mean())   # over-deep floors rise


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the EXP-2 model on a GPU")
def test_v2_worlds_seed_the_sea_from_the_exp2_layout_v1_keeps_v4():
    """Round 2: v4's continent field called most of EXP-2's basin and coast-province seas land, so their coasts were
    LAKES (land biomes, lake-bed floor, no beach). v2 worlds seed the true sea from EXP-2's layout; v1 keeps v4's."""
    from pathlib import Path
    from terrain_slm.world import generator as G
    model = Path(__file__).resolve().parents[1] / "checkpoints/exp2/world_exp2b"
    if not (model / "planner.pt").exists():
        pytest.skip("no EXP-2 model")
    dev = "cuda:1" if torch.cuda.device_count() > 1 else "cuda"
    gen = G2.WorldGenerator2(model, 2, dev)
    ci0, cj0, n = -40, -3640, 64                  # the seed-2 drowned coast (block ~(-49, -14480))
    with torch.no_grad():
        gen.use_sea_shelf(False)
        v1 = gen.continent_land_cells(ci0, cj0, n, n, dev)
        assert torch.equal(v1, G.continent_land(ci0, cj0, n, n, 2, dev))
        gen.use_sea_shelf(True)
        v2 = gen.continent_land_cells(ci0, cj0, n, n, dev)
        trend = gen.controls(ci0, cj0, n, n)["trend"]
        link = gen.sea_link_cells(ci0, cj0, n, n, dev) if G2.SEA_LINK else torch.ones_like(trend, dtype=torch.bool)
    gen.use_sea_shelf(False)
    # BV: layout-drowned cells seed the sea where the layout links them to the open ocean (this drowned coast is)
    assert float(link[trend < G2.SEA_SEED_TREND_M].float().mean()) > 0.9
    assert torch.all(v2[(trend < G2.SEA_SEED_TREND_M) & link] == 0)
    assert float((v2 < 0.5).float().mean()) > float((v1 < 0.5).float().mean())   # more sea seeds than v4 saw


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the EXP-2 model on a GPU")
def test_sea_seed_layout_trend_equals_the_full_controls_trend():
    """review-python LOW-3: continent_land_cells reads the layout's trend without the climate node; the value is the
    one controls() returns (bit for bit), at several windows and both climate layouts."""
    import os
    from pathlib import Path
    model = Path(__file__).resolve().parents[1] / "checkpoints/exp2/world_exp2b"
    if not (model / "planner.pt").exists():
        pytest.skip("no EXP-2 model")
    dev = os.environ.get("TGM_TEST_DEVICE") or ("cuda:1" if torch.cuda.device_count() > 1 else "cuda")
    gen = G2.WorldGenerator2(model, 2, dev)
    with torch.no_grad():
        for layout in ("belts", "zones"):
            gen.use_climate_layout(layout)
            for ci0, cj0, h, w in ((-40, -3640, 64, 64), (0, 0, 48, 80), (906, 2726, 96, 96), (-2500, 1700, 33, 17)):
                full = gen.controls(ci0, cj0, h, w)
                assert "trend" in gen._layout(ci0, cj0, h, w)
                assert torch.equal(gen._layout(ci0, cj0, h, w)["trend"], full["trend"]), (layout, ci0, cj0)
        gen.use_sea_shelf(True)
        a = gen.continent_land_cells(-40, -3640, 64, 64, dev)
        drowned = gen.controls(-40, -3640, 64, 64)["trend"].to(dev) < G2.SEA_SEED_TREND_M
        if G2.SEA_LINK:                                    # BV: only drowned cells the layout links to the open ocean
            drowned = drowned & gen.sea_link_cells(-40, -3640, 64, 64, dev)
        b = torch.where(drowned, 0.0,
                        __import__("terrain_slm.world.controls2", fromlist=["x"]).continent2(
                            torch.arange(-40, 24, device=dev, dtype=torch.float32).view(-1, 1).expand(64, 64),
                            torch.arange(-3640, -3576, device=dev, dtype=torch.float32).view(1, -1).expand(64, 64),
                            seed=2)[1])
        gen.use_sea_shelf(False)
    assert torch.equal(a, b)
