"""stages/cells of libstonebreak_terrain (the native generator) against the Python generator, bit for bit: the relief
windows and trend, the planner (_planner_input, _window, _planner_cells), the routed drainage (_drainage_inputs,
_route_inputs, _route_window, _stitched_route, _hydro_pred, _hydro_window, drainage), the per-request views (cells,
overview_cells with and without the coastal shelf, swamp_relief_cells at 1:4 / 1:2 / 1:1), generator2._shelf_remap and
FX3's distance-aware shelf (shelf, sea_fields_cells, sea_fields_px; lake beds included)
-- each method alone (dependencies from Python) and with the whole stage routed native, at far coordinates, negative
and huge seeds and both climate layouts.

Needs CUDA, the Cenda engine, the shipping model folder and a libstonebreak_terrain built with the cells stage
(TGM_TERRAIN_LIB, e.g. stonebreak-game/native/build/agent-terrain-cells); skipped otherwise. TGM_TEST_DEVICE picks the
card (default cuda:1)."""
import os
from dataclasses import replace
from pathlib import Path

import pytest
import torch

DEV = os.getenv("TGM_TEST_DEVICE", "cuda:1")
ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2e"
METHODS = ("_relief_window", "trend", "_planner_input", "_window", "_planner_cells", "_drainage_inputs",
           "_route_inputs", "_route_window", "_stitched_route", "_hydro_pred", "_hydro_window", "drainage", "cells",
           "overview_cells", "swamp_relief_cells", "SR.cell_fields", "G2._shelf_remap", "shelf", "sea_fields_cells",
           "sea_fields_px")


def _ok() -> str | None:
    if not torch.cuda.is_available() or not (WORLD / "planner.pt").exists():
        return "needs CUDA and checkpoints/exp2/world_exp2e"
    try:
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.export import native_gen as NG
        if not E.available(DEV):
            return "Cenda engine unavailable"
        if not set(METHODS) <= set(NG.implemented()):
            return "libstonebreak_terrain without the cells stage"
    except Exception as e:  # noqa: BLE001
        return f"native library unavailable: {e}"
    return None


_WHY = _ok()
pytestmark = pytest.mark.skipif(_WHY is not None, reason=_WHY or "")


@pytest.fixture(scope="module")
def ng():
    os.environ["TGM_ENGINE"] = "cenda"
    from terrain_slm.export import native_gen as NG
    from terrain_slm.world import generator2 as G2
    old = G2.CENDA_ENGINE
    G2.CENDA_ENGINE = True
    try:
        gen = G2.WorldGenerator2(WORLD, 4242, DEV)
    finally:
        G2.CENDA_ENGINE = old
    n = NG.NativeGen(gen)
    yield n
    n.route("*", False)
    gen.use_seed(4242)
    gen.use_climate_layout("belts")
    gen.use_sea_shelf(False)
    _scale(n, 60.0)


def _world(ng, seed, layout="belts", shelf=False):
    g = ng.gen
    g.use_seed(seed)
    g.use_climate_layout(layout)
    g.use_sea_shelf(shelf)
    ng.sync_state()


def _scale(ng, horizontal_m):
    from terrain_slm.river.scale import BlockScale
    from terrain_slm.world.world_config import WorldConfig
    ng.gen.set_block_scale(BlockScale.from_world(replace(WorldConfig.GAME, horizontal_m=horizontal_m), DEV))
    ng.sync_state()


def _bitwise(ng, method, ints=(), tensors=()):
    rep = ng.parity(method, list(ints), tensors=list(tensors))
    assert rep, f"{method}{tuple(ints)}: no fields"
    bad = {k: v for k, v in rep.items() if not v.get("bitwise")}
    assert not bad, f"{method}{tuple(ints)}: {bad}"
    return rep


ALONE = [
    ("_relief_window", (0, 0)), ("_relief_window", (-3, 5)), ("_relief_window", (3900, -2501)),
    ("trend", (-300, 250, 100, 37)), ("trend", (1000003, -998001, 40, 50)),
    ("_planner_input", (10, -20, 64)), ("_planner_input", (10, -20, 70, 90)),
    ("_window", (-7, 3)), ("_window", (31250, -31187)),
    ("_planner_cells", (-100, 37, 80, 50)),
    ("_drainage_inputs", (0, 0)), ("_route_inputs", (-2, 1)), ("_route_window", (-2, 1)), ("_stitched_route", (0, 0)),
    ("_hydro_pred", (0, 0)), ("_hydro_window", (0, 0)), ("drainage", (10, -30, 64, 48)),
    ("cells", (5, -9, 40, 33)), ("overview_cells", (300, -100, 48, 48)), ("swamp_relief_cells", (0, 130, 30, 30)),
]


@pytest.mark.parametrize("method,args", ALONE, ids=[f"{m}{a}" for m, a in ALONE])
def test_alone(ng, method, args):
    """Each method natively, everything it calls from Python."""
    _world(ng, 4242)
    _bitwise(ng, method, args)


def test_shelf_remap(ng):
    g = torch.Generator(device=DEV).manual_seed(5)
    h = torch.randn(64, 80, device=DEV, generator=g) * 900.0
    h[0, :4] = torch.tensor([0.0, -0.0, -30.0, -1500.0])
    _bitwise(ng, "G2._shelf_remap", tensors=[h])


@pytest.mark.parametrize("seed", [4242, -987654321, 2**53 + 1])
def test_stage_native(ng, seed):
    """The whole stage routed native (controls / _layout / swamp candidates from Python), far coordinates."""
    _world(ng, seed)
    ng.route("*")
    try:
        for args in ((5, -9, 40, 33), (100000, -250000, 24, 24), (-777777, 123457, 17, 30)):
            _bitwise(ng, "cells", args)
            _bitwise(ng, "overview_cells", args)
            _bitwise(ng, "swamp_relief_cells", args)
    finally:
        ng.route("*", False)


def test_layouts_shelf_scales(ng):
    """zones layout; the coastal shelf on a sea region; swamp relief (a swampy region) at 1:4, 1:2 and 1:1."""
    ng.route("*")
    try:
        _world(ng, 4242, "zones")
        _bitwise(ng, "cells", (3, 4, 30, 30))
        _bitwise(ng, "overview_cells", (3, 4, 30, 30))
        _world(ng, 4242, "belts", shelf=True)
        rep = ng.call("overview_cells", [300, -100, 48, 48])
        assert bool((rep["height"] < 0).any()), "the shelf region has no sea"
        _bitwise(ng, "overview_cells", (300, -100, 48, 48))
        _world(ng, 4242)
        for hm in (60.0, 30.0, 15.0):
            _scale(ng, hm)
            w = ng.call("swamp_relief_cells", [0, 130, 30, 30])["0"]
            assert bool((w > 0).any()), "the swamp region has no swamp"
            _bitwise(ng, "swamp_relief_cells", (0, 130, 30, 30))
    finally:
        _scale(ng, 60.0)
        _world(ng, 4242)
        ng.route("*", False)


def test_fx3_shelf(ng):
    """generator2.shelf (pointwise without an origin, distance-aware + lake-bed cap with one, at 30 m / 15 m pixels and
    cells), sea_fields_cells / sea_fields_px over a region with sea and lakes (seed 4242), alone and routed native."""
    g = torch.Generator(device=DEV).manual_seed(3)
    hts = lambda h, w: torch.randn(h, w, device=DEV, generator=g) * 150.0 - 40.0
    try:
        for native in (False, True):
            if native:
                ng.route("*")
            _world(ng, 4242, shelf=False)
            _bitwise(ng, "shelf", (124 * 8 - 40, -17 * 8 - 31, 8), [hts(32, 40)])     # no shelf: unchanged
            _world(ng, 4242, shelf=True)
            d, lk = ng.gen.sea_fields_cells(100, -40, 48, 48)
            assert bool(lk.any()) and bool((d > 0).any()), "the test region has no lake / sea"
            _bitwise(ng, "sea_fields_cells", (100, -40, 48, 48))
            _bitwise(ng, "sea_fields_cells", (-123456, 654321, 20, 30))
            _bitwise(ng, "sea_fields_px", (124 * 16 - 50, -17 * 16 - 9, 120, 90, 16))
            _bitwise(ng, "sea_fields_px", (100, -40, 48, 48, 1))
            _bitwise(ng, "shelf", (), [hts(40, 50)])
            _bitwise(ng, "shelf", (124 * 8 - 40, -17 * 8 - 31, 8), [hts(96, 128)])
            _bitwise(ng, "shelf", (300 * 16 - 7, -90 * 16 + 1, 16), [hts(80, 72)])
            _bitwise(ng, "shelf", (110, -30, 1), [hts(40, 40)])
            _bitwise(ng, "overview_cells", (100, -40, 48, 48))
            ng.route("*", False)
    finally:
        ng.route("*", False)
        _world(ng, 4242)
