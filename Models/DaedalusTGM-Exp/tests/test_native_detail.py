"""stages/detail of libstonebreak_terrain (the native generator) against the Python generator, bit for bit:
_detail_window (guard + retries + lowland boost), _detail_samples, _native_detail / native, fine_detail, SR-30 / SR-15
windows and blends, native_fine (SR-15 and its bilinear stand-in), TileBuilder._carve -- at the 1:4, 1:2 and 1:1 block
scales, far coordinates and negative seeds, each method alone (dependencies from Python) and with the whole stage
routed native.

Needs CUDA, the Cenda engine, the shipping model folder and a libstonebreak_terrain built with the detail stage
(TGM_TERRAIN_LIB, e.g. stonebreak-game/native/build/agent-terrain-detail); skipped otherwise. TGM_TEST_DEVICE picks the
card (default cuda:1)."""
import os
from pathlib import Path

import pytest
import torch

DEV = os.getenv("TGM_TEST_DEVICE", "cuda:1")
ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2e"
METHODS = ("_detail_window", "_detail_samples", "_native_detail", "native", "fine_detail", "_sr_window",
           "_sr15_window", "_sr_blend", "native_fine", "TileBuilder._carve")


def _ok() -> str | None:
    if not torch.cuda.is_available() or not (WORLD / "planner.pt").exists():
        return "needs CUDA and checkpoints/exp2/world_exp2e"
    try:
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.export import native_gen as NG
        if not E.available(DEV):
            return "Cenda engine unavailable"
        if not set(METHODS) <= set(NG.implemented()):
            return "libstonebreak_terrain without the detail stage"
    except Exception as e:  # noqa: BLE001
        return f"native library unavailable: {e}"
    return None


_WHY = _ok()
pytestmark = pytest.mark.skipif(_WHY is not None, reason=_WHY or "")


def _world(downscale, curve=1):
    from terrain_slm.world.world_config import WorldConfig
    base = WorldConfig.GAME.to_dict()
    hm = {2: 60.0, 1: 30.0, 0.5: 15.0}[downscale]
    w = {**base, "downscale": downscale, "horizontal_m": hm}
    if curve >= 2:              # height-curve v2: shelf + shallows bands (the game's v2 handshake values at 1:4)
        w.update(curve_version=2, shelf_m_per_block=6.0 * hm / 60.0, shallow_m_per_block=4.0 * hm / 60.0,
                 ocean_m_per_block=32.0, shore_blend_m=10.0, shelf_base_m=-300.0, shelf_blend_m=150.0,
                 shallow_base_m=-24.0, shallow_blend_m=12.0)
    return WorldConfig.from_dict(w)


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
    return NG.NativeGen(gen)


def _scale(ng, downscale, curve=1):
    """The per-build state TileBuilder.build sets: block scale, sea shelf and the FX4 block look (v2 worlds)."""
    from terrain_slm.world.tiles import TileBuilder
    gen = ng.gen
    w = _world(downscale, curve)
    TileBuilder(gen, w)                    # sets the generator's block scale
    gen.use_sea_shelf(curve >= 2)
    if hasattr(gen, "use_block_look"):
        gen.use_block_look(curve >= 2 and gen.block_look > 0)
    ng.sync_state(world=w)


def _seed(ng, seed, downscale=2, curve=1):
    ng.gen.use_seed(seed)
    _scale(ng, downscale, curve)


def _check(ng, method, ints, floats=(), none=False):
    """Bitwise parity; `none`: both sides return None (an empty result) is the expected answer."""
    r = ng.parity(method, ints, floats)
    if none:
        assert r == {}, (method, ints, floats, r)
        return
    assert r and all(v.get("bitwise") for v in r.values()), (method, ints, floats, r)


def test_detail_window_and_samples(ng):
    for seed in (4242, -9876543210123):
        _seed(ng, seed)
        for wi, wj in ((0, 0), (1, -1), (-3000, 4100)):
            _check(ng, "_detail_window", [wi, wj])
        _check(ng, "_detail_samples", [-100, 37, 300, 500])
        _check(ng, "_detail_samples", [700001, -654321, 700001 + 64, -654321 + 200])


def test_detail_guard_retries_and_fallback(ng, monkeypatch, capfd):
    """A tiny X limit fails every attempt: same warnings, retries and smooth-base fallback as Python."""
    from terrain_slm.export import native_gen as NG
    from terrain_slm.export import native_manifest as NM
    from terrain_slm.world import generator as G
    gen = ng.gen
    _seed(ng, 31337)
    man = NM.build(gen)
    man["constants"]["dt.X_LIMIT"] = 0.25
    n2 = NG.NativeGen(gen, manifest=man)
    n2.sync_state(world=_world(2))
    monkeypatch.setattr(G, "DETAIL_X_LIMIT", 0.25)
    gen._detail_windows.clear()
    n2.route("_detail_window")
    got = n2.call("_detail_window", [5, 6])[""]
    out_native = capfd.readouterr().out
    ref = gen._detail_window(5, 6)
    out_py = capfd.readouterr().out
    assert torch.equal(got, ref)
    assert out_native.count("retrying") == len(G.DETAIL_RETRY) and "fell back" in out_native
    assert out_native == out_py
    gen._detail_windows.clear()


def test_lowland_boost(ng):
    from terrain_slm.export import native_gen as NG
    from terrain_slm.export import native_manifest as NM
    gen = ng.gen
    _seed(ng, 4242)
    old = gen.lowland_boost
    try:
        gen.use_lowland_boost(3.5)
        n2 = NG.NativeGen(gen, manifest=NM.build(gen))
        n2.sync_state(world=_world(2))
        r = n2.parity("_detail_window", [2, -1])
        assert r[""]["bitwise"], r
    finally:
        gen.use_lowland_boost(old)


@pytest.mark.parametrize("downscale", [2, 1, 0.5])
def test_native_pixels(ng, downscale):
    _seed(ng, -77, downscale)
    _check(ng, "_native_detail", [-300, 77, 200, 600])
    _check(ng, "native", [123456, -517, 123456 + 100, -300])
    _check(ng, "fine_detail", [10, 20, 300, 41], none=downscale >= 2)   # 1:4: no SR (None on both sides)


def test_sr_windows(ng):
    _seed(ng, 4242, 1)
    _check(ng, "_sr_window", [0, -1])
    _check(ng, "_sr_window", [-4000, 2711])
    _check(ng, "_sr_blend", [-100, 50, 300, 400, 0])
    _seed(ng, -123, 0.5)
    _check(ng, "_sr15_window", [2, 0])
    _check(ng, "_sr_blend", [-1000, 333, -700, 600, 1])
    _check(ng, "native_fine", [100, -333, 400, -100])


def test_native_fine_stand_in(ng, monkeypatch):
    """Without sr15.pt: generator.native_fine's bilinear, mean-preserving stand-in."""
    from terrain_slm.export import native_gen as NG
    from terrain_slm.export import native_manifest as NM
    gen = ng.gen
    _seed(ng, 9, 0.5)
    monkeypatch.setattr(gen, "sr15", None)
    n2 = NG.NativeGen(gen, manifest=NM.build(gen))
    n2.sync_state(world=_world(0.5))
    r = n2.parity("native_fine", [100, -333, 400, -100])
    assert r[""]["bitwise"], r


@pytest.mark.parametrize("downscale", [2, 1, 0.5])
def test_carve(ng, downscale):
    _seed(ng, 4242, downscale)
    for lod in (1, 2, 4):
        d = float(downscale * lod)
        for box in ((0, 0, 256, 256), (-512, 768, -256, 1024), (40000, -90000, 40000 + 256, -90000 + 256)):
            _check(ng, "TileBuilder._carve", list(box), [d])


def test_whole_stage_native(ng):
    """Every detail method routed native at once (only the other stages' methods come from Python)."""
    def check(method, ints, floats=()):
        for m in METHODS:
            ng.route(m, True)
        _check(ng, method, ints, floats)   # parity routes `method` off again at the end

    try:
        _seed(ng, -555, 1)
        check("native", [5000, -7000, 5000 + 300, -7000 + 260])
        check("TileBuilder._carve", [256, -256, 512, 0], [1.0])
        _seed(ng, -555, 0.5)
        check("native_fine", [600, 600, 900, 777])
        check("TileBuilder._carve", [256, -256, 512, 0], [0.5])
        _seed(ng, -555, 2)
        check("TileBuilder._carve", [-256, 256, 0, 512], [2.0])
    finally:
        ng.route("*", False)


def test_block_look_v2(ng):
    """FX4 block look (height-curve v2 builds): the hill band in _detail_window, the linear SR gain in SR-30 / SR-15
    (their own caches), and FX3's karst2 carve gate; then the same windows again in a v1 build (no cross-talk)."""
    gen = ng.gen
    if not getattr(gen, "block_look", 0):
        pytest.skip("block look off (TERRAIN_SLM_BLOCK_LOOK=0)")
    for curve in (2, 1, 2):
        _seed(ng, -4242, 1, curve)
        assert gen.look_build == (curve >= 2)
        _check(ng, "_detail_window", [3, -2])
        _check(ng, "_sr_window", [1, 1])
        _check(ng, "_sr_blend", [-100, 50, 300, 400, 0])
        _seed(ng, -4242, 0.5, curve)
        _check(ng, "_sr15_window", [-1, 2])
        _check(ng, "native_fine", [100, -333, 400, -100])
        for ds in (2, 1, 0.5):
            _seed(ng, 777, ds, curve)
            _check(ng, "TileBuilder._carve", [-512, 768, -256, 1024], [float(ds)])
            _check(ng, "TileBuilder._carve", [40000, -90000, 40000 + 256, -90000 + 256], [float(ds * 2)])
    _seed(ng, 4242)
