"""stages/controls of libstonebreak_terrain (the native generator) against the Python generator, bit for bit:
_layout (v4 procedural_controls + the controls2 kernel), _controls_at, the climate node (_land_lattice, _continentality,
_climate_window, _climate_grid, climate_cells), controls() (aeolian gate + expert pruning), continent_land_cells (v1 and
the v2 sea_shelf branch), climate_native, detail_gates and generator.red_desert_cells -- both climate layouts, far
coordinates (|ci| up to 7e6), negative and huge seeds, each method alone (dependencies from Python) and with the whole
stage routed native.

Needs CUDA, the Cenda engine, the shipping model folder and a libstonebreak_terrain built with the controls stage
(TGM_TERRAIN_LIB, e.g. stonebreak-game/native/build/agent-terrain-controls); skipped otherwise. TGM_TEST_DEVICE picks
the card (default cuda:1)."""
import os
from pathlib import Path

import pytest
import torch

DEV = os.getenv("TGM_TEST_DEVICE", "cuda:1")
ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2e"
METHODS = ("_layout", "_controls_at", "_land_lattice", "_continentality", "_climate_window", "_climate_grid",
           "climate_cells", "controls", "continent_land_cells", "climate_native", "detail_gates", "G.red_desert_cells",
           "C2.continent2")


def _ok() -> str | None:
    if not torch.cuda.is_available() or not (WORLD / "planner.pt").exists():
        return "needs CUDA and checkpoints/exp2/world_exp2e"
    try:
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.export import native_gen as NG
        if not E.available(DEV):
            return "Cenda engine unavailable"
        if not set(METHODS) <= set(NG.implemented()):
            return "libstonebreak_terrain without the controls stage"
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


def _world(ng, seed, layout="belts", shelf=False):
    g = ng.gen
    if g.seed != seed:
        g.use_seed(seed)
    g.use_climate_layout(layout)
    g.use_sea_shelf(shelf)
    ng.sync_state()
    ng.clear_caches()


def _check(ng, method, ints=(), floats=(), tensors=(), all_native=False):
    if all_native:
        ng.route("*")
    try:
        rep = ng.parity(method, list(ints), list(floats), list(tensors))
    finally:
        if all_native:
            ng.route("*", False)
    assert rep, f"{method}: no fields"
    bad = {k: v for k, v in rep.items() if not v.get("bitwise")}
    assert not bad, f"{method}{tuple(ints)}: {bad}"


WINDOWS = [(0, 0, 64, 64), (-1000, 523, 37, 81), (123456, -98765, 50, 33), (7_000_000, -6_900_000, 20, 20),
           (-6_999_000, 6_500_123, 9, 70)]
SEEDS = [(4242, "belts"), (-7, "zones"), (2**40 + 3, "belts"), (-(2**62) + 11, "zones")]


@pytest.mark.parametrize("seed,layout", SEEDS)
def test_layout_and_controls_at(ng, seed, layout):
    _world(ng, seed, layout)
    for w in WINDOWS:
        _check(ng, "_layout", w)
    g = torch.Generator(device="cpu").manual_seed(seed & 0xFFFF)
    for scale in (1e3, 1e5, 7e6):
        ci = ((torch.rand(41, 57, generator=g) * 2 - 1) * scale).to(DEV)
        cj = ((torch.rand(41, 57, generator=g) * 2 - 1) * scale).to(DEV)
        _check(ng, "_controls_at", (), (), (ci, cj))
    _check(ng, "_controls_at", (), (), (ci[0, :1].contiguous(), cj[0, :1].contiguous()))


@pytest.mark.parametrize("all_native", [False, True])
@pytest.mark.parametrize("seed,layout", SEEDS[:2])
def test_climate_node(ng, seed, layout, all_native):
    _world(ng, seed, layout)
    _check(ng, "_land_lattice", (-40, 13, 79, 2), all_native=all_native)
    for km in (250.0, 1000.0, 2500.0):
        _check(ng, "_continentality", (-24, 72), (km,), all_native=all_native)
    for wij in ((0, 0), (-3, 2), (1000, -1700), (-120_000, 90_000)):
        _check(ng, "_climate_window", wij, all_native=all_native)
    _check(ng, "_climate_grid", (-3, -2, 5, 6), all_native=all_native)
    for w in WINDOWS:
        _check(ng, "climate_cells", w, all_native=all_native)


@pytest.mark.parametrize("all_native", [False, True])
@pytest.mark.parametrize("seed,layout", SEEDS)
def test_controls_and_users(ng, seed, layout, all_native):
    for shelf in (False, True):
        _world(ng, seed, layout, shelf)
        for w in WINDOWS[:3] + WINDOWS[4:]:
            _check(ng, "controls", w, all_native=all_native)
            _check(ng, "continent_land_cells", w, all_native=all_native)
            _check(ng, "C2.continent2", w, all_native=all_native)
    _check(ng, "detail_gates", (-40, 17, 144, 32), all_native=all_native)
    g = torch.Generator(device="cpu").manual_seed(3)
    for i1, j1 in ((-517, 33), (1_000_003, -2_345_678)):
        elev = (torch.randn(70, 90, generator=g) * 800).to(DEV)
        _check(ng, "climate_native", (i1, j1, i1 + 70, j1 + 90), (), (elev,), all_native=all_native)


@pytest.mark.parametrize("seed", [4242, -7])
def test_red_desert_cells(ng, seed):
    _world(ng, seed)
    for i0, j0, h, w, step in ((0, 0, 48, 48, 1.0), (-3000, 1200, 30, 50, 0.25), (500_000, -400_000, 24, 32, 0.5)):
        ci = (torch.arange(h, device=DEV, dtype=torch.float32) * step + i0).view(-1, 1).expand(h, w).contiguous()
        cj = (torch.arange(w, device=DEV, dtype=torch.float32) * step + j0).view(1, -1).expand(h, w).contiguous()
        _check(ng, "G.red_desert_cells", (), (), (ci, cj))
    one = torch.tensor([12.0], device=DEV)
    _check(ng, "G.red_desert_cells", (), (), (one, one * 3))
