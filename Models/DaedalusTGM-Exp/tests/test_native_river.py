"""stages/river of libstonebreak_terrain (the native generator) against the Python generator: river_field (the river
pipeline's native stages + swamp flattening + the v2 coastal shelf), _flatten_swamps and river.columns (the block
stages, = gen.river.columns(*gen.river_field(frame), d, ground_fine)) bit for bit at 1:4 / 1:2 / 1:1, height-curve v1
and v2 worlds, river / delta / swamp / sub-sea-bed areas, far coordinates and negative / huge seeds -- each method
alone (dependencies from Python) and with river_field native under river.columns.

The learned bank network (river.bank_net) is the one exception: the native fp32 forward is bit-identical to torch's
fp32 forward with TF32 off, but torch's default (cuDNN TF32 convolutions) differs from it by ~1e-3 model units; the
bitwise tests route it to Python, test_bank_net pins both facts.

Needs CUDA, the Cenda engine, the shipping model folder and a libstonebreak_terrain built with the river stage
(TGM_TERRAIN_LIB, e.g. stonebreak-game/native/build/agent-terrain-river); skipped otherwise. TGM_TEST_DEVICE picks the
card (default cuda:1)."""
import os
from pathlib import Path

import pytest
import torch

DEV = os.getenv("TGM_TEST_DEVICE", "cuda:1")
ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2e"
METHODS = ("_flatten_swamps", "river_field", "river.columns", "river.bank_net")
HALO = 256          # river/pipeline.HALO_PX (frames below)
MARGIN = 32         # river/pipeline.BLOCK_MARGIN


def _ok() -> str | None:
    if not torch.cuda.is_available() or not (WORLD / "bank.pt").exists():
        return "needs CUDA and checkpoints/exp2/world_exp2e"
    try:
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.export import native_gen as NG
        if not E.available(DEV):
            return "Cenda engine unavailable"
        if not set(METHODS) <= set(NG.implemented()):
            return "libstonebreak_terrain without the river stage"
    except Exception as e:  # noqa: BLE001
        return f"native library unavailable: {e}"
    return None


_WHY = _ok()
pytestmark = pytest.mark.skipif(_WHY is not None, reason=_WHY or "")


def _world(scale: str, version: int, layout: str = "belts"):
    """The game's handshake worlds (TerrainScale.worldConfigJson): 1:4 / 1:2 / 1:1, curve v1 / v2."""
    from terrain_slm.world.world_config import ONE_TO_ONE_CURVE, WorldConfig, curve_v2_keys
    d = dict(world_height=640, min_y=-384, sea_level=64, tile_size=256, lowland_top_m=600.0, highland_base_m=2000.0,
             shore_blend_m=60.0, midland_blend_m=150.0, highland_blend_m=300.0, climate_layout=layout)
    if scale == "1:4":
        d.update(horizontal_m=60.0, downscale=2, ocean_m_per_block=48.0, lowland_m_per_block=16.0,
                 midland_m_per_block=24.0, highland_m_per_block=38.0)
    elif scale == "1:2":
        d.update(horizontal_m=30.0, downscale=1, ocean_m_per_block=24.0, lowland_m_per_block=8.0,
                 midland_m_per_block=12.0, highland_m_per_block=19.0)
    else:
        r, k = ONE_TO_ONE_CURVE
        d.update(horizontal_m=15.0, downscale=0.5, ocean_m_per_block=r[0], lowland_m_per_block=r[1],
                 midland_m_per_block=r[2], highland_m_per_block=r[3], lowland_top_m=k[0], highland_base_m=k[1],
                 shore_blend_m=k[2], midland_blend_m=k[3], highland_blend_m=k[4])
    if version == 2:
        d.update(curve_v2_keys(scale))
    return WorldConfig(**d)


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


def _use(ng, world, seed):
    from terrain_slm.world.tiles import TileBuilder
    gen = ng.gen
    tb = TileBuilder(gen, world)
    gen.set_block_scale(tb.scale)
    gen.use_seed(seed)
    gen.use_climate_layout(world.climate_layout or "belts")
    gen.use_sea_shelf(world.curve_version >= 2)
    ng.sync_state(world=world)


def _frame(world, ci: int, cj: int, size: int):
    """The river_field frame TileBuilder._blocks / _blocks_fine build for the `size`-sample tile holding cell (ci, cj),
    and the `d` they pass to river.columns."""
    d = world.downscale
    tx, tz = int((ci * 8 / d) // size), int((cj * 8 / d) // size)
    i1, j1 = tx * size, tz * size
    if d < 1:
        return ((i1 - MARGIN) // 2, (j1 - MARGIN) // 2, (i1 + size + MARGIN) // 2, (j1 + size + MARGIN) // 2), 0.5
    return ((i1 - MARGIN) * d, (j1 - MARGIN) * d, (i1 + size + MARGIN) * d, (j1 + size + MARGIN) * d), float(d)


def _ground_fine(gen, fr):
    """TileBuilder._blocks_fine's 15 m dry ground for a 1:1 frame (30 m pixels)."""
    from terrain_slm.world import generator as G
    from terrain_slm.world import tiles as T
    n1, m1, n2, m2 = fr
    k = T.FINE_K
    field, _ = gen.river_field(n1, m1, n2, m2)
    shelf = getattr(gen, "shelf", lambda x, *a: x)
    base = shelf(G.soft_cap_peaks(gen.native(n1, m1, n2, m2)), n1, m1, G.CP)
    fine = shelf(G.soft_cap_peaks(gen.native_fine(n1 * k, m1 * k, n2 * k, m2 * k)), n1 * k, m1 * k, G.CP * k)
    up = lambda x: x.repeat_interleave(k, -2).repeat_interleave(k, -1)
    keep = (1.0 - (field.carved - base).abs() / T.FINE_KEEP_M).clamp(0.0, 1.0)
    return up(field.carved) + up(keep) * (fine - up(base))


def _bitwise(rep: dict, what: str) -> None:
    bad = {k: v for k, v in rep.items() if not v.get("bitwise", False)}
    assert not bad, f"{what}: {bad}"


# (seed, cell): a big river reaching the sea (delta, sub-sea beds), a swamp coast, far coordinates.
CASES = [
    (4242, (-20, 240), "1:4", 1, "belts"),
    (4242, (-17, 244), "1:2", 2, "zones"),
    (4242, (-254, 30), "1:1", 2, "belts"),
    (4242, (-254, 30), "1:4", 2, "latitude"),
    (4242, (-20, 240), "1:1", 1, "belts"),
    (-123456789012, (150000, -320000), "1:2", 1, "belts"),
    (987654321987, (-999999, 777777), "1:4", 2, "zones"),
]


@pytest.mark.parametrize("seed,cell,scale,version,layout", CASES)
def test_river_bitwise(ng, seed, cell, scale, version, layout):
    from terrain_slm.world import generator as G
    gen = ng.gen
    world = _world(scale, version, layout)
    _use(ng, world, seed)
    fr, d = _frame(world, *cell, 64)
    ng.route("river.bank_net", False)
    a1, b1, a2, b2 = fr[0] - HALO, fr[1] - HALO, fr[2] + HALO, fr[3] + HALO
    with torch.no_grad():
        heights = G.soft_cap_peaks(gen.native(a1, b1, a2, b2))
    _bitwise(ng.parity("_flatten_swamps", [a1, b1], [], [heights]), "_flatten_swamps")
    for deps in ((), ("_flatten_swamps",)):
        for m in deps:
            ng.route(m, True)
        try:
            _bitwise(ng.parity("river_field", list(fr)), f"river_field (native {deps})")
        finally:
            for m in deps:
                ng.route(m, False)
    tens = [_ground_fine(gen, fr)] if d < 1 else []
    _bitwise(ng.parity("river.columns", list(fr), [d], tens), "river.columns (Python river_field)")
    ng.route("river_field", True)
    ng.route("_flatten_swamps", True)
    try:
        _bitwise(ng.parity("river.columns", list(fr), [d], tens), "river.columns (native river_field)")
    finally:
        ng.route("river_field", False)
        ng.route("_flatten_swamps", False)


def test_bank_net(ng):
    """The native bank forward = torch's fp32 forward with TF32 off, bit for bit; torch's default (TF32) differs a
    little; a river_field with the native network then differs from Python's only in the banks' carved ground."""
    gen = ng.gen
    _use(ng, _world("1:4", 1), 4242)
    torch.manual_seed(0)
    x = torch.randn(1, 4, 192, 256, device=DEV)
    ng.route("river.bank_net", True)
    try:
        got = ng.call("river.bank_net", [], [], [x])[""]
        old = torch.backends.cudnn.allow_tf32
        with torch.no_grad():
            torch.backends.cudnn.allow_tf32 = False
            try:
                ref = gen.banks(x)
            finally:
                torch.backends.cudnn.allow_tf32 = old
            dflt = gen.banks(x)
        assert torch.equal(got, ref)
        assert float((got - dflt).abs().max()) < 2e-2
        fr, _ = _frame(_world("1:4", 1), -20, 240, 64)
        rep = ng.parity("river_field", list(fr))
        for k, v in rep.items():
            if k != "0.carved":
                assert v.get("bitwise"), (k, v)
        assert rep["0.carved"].get("bitwise") or rep["0.carved"]["max_diff"] < 0.1
    finally:
        ng.route("river.bank_net", False)
