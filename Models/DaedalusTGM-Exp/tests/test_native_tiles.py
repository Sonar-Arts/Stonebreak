"""stages/tiles of libstonebreak_terrain (the native generator) against the Python generator, bit for bit: the block
scale / height curves (block_scale), biomes.py (rule classifier incl. the 3x3 slope, swamp candidates, majority /
smooth filters, the classifier's FastNoiseLite fields), world/ocean.py (sea masks, lakes), and TileBuilder (_red_deserts,
_blocks at 1:4 / 1:2, _blocks_fine at 1:1, _overview, _planes, build) -- each method alone (dependencies from Python),
over the game's handshake worlds (1:4 / 1:2 / 1:1, height-curve v1 / v2, belts / zones / latitude), several seeds
(negative, huge) and tiles (origin, coasts, far).

Needs CUDA, the Cenda engine, the shipping model folder and a libstonebreak_terrain built with the tiles stage
(TGM_TERRAIN_LIB, e.g. stonebreak-game/native/build/agent-terrain-tiles); skipped otherwise. TGM_TEST_DEVICE picks the
card (default cuda:1)."""
import os
from pathlib import Path

import pytest
import torch
import torch.nn.functional as F

DEV = os.getenv("TGM_TEST_DEVICE", "cuda:1")
ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2e"
METHODS = ("block_scale", "B._classify_biome", "B.swamp_candidates", "B.majority", "B.smooth_mask",
           "O.sea_mask_for_samples", "TileBuilder._blocks", "TileBuilder._blocks_fine", "TileBuilder._overview",
           "TileBuilder._planes", "TileBuilder._red_deserts", "TileBuilder.build")
MARGIN = 32         # river/pipeline.BLOCK_MARGIN


def _ok() -> str | None:
    if not torch.cuda.is_available() or not (WORLD / "biome.pt").exists():
        return "needs CUDA and checkpoints/exp2/world_exp2e"
    try:
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.export import native_gen as NG
        if not E.available(DEV):
            return "Cenda engine unavailable"
        if not set(METHODS) <= set(NG.implemented()):
            return "libstonebreak_terrain without the tiles stage"
    except Exception as e:  # noqa: BLE001
        return f"native library unavailable: {e}"
    return None


_WHY = _ok()
pytestmark = pytest.mark.skipif(_WHY is not None, reason=_WHY or "")


def _world(scale: str, version: int, layout: str = "belts", tile: int = 64):
    """The game's handshake worlds (TerrainScale.worldConfigJson) with a small tile size (the tile size only sets the
    request frame)."""
    from terrain_slm.world.world_config import ONE_TO_ONE_CURVE, WorldConfig, curve_v2_keys
    d = dict(world_height=640, min_y=-384, sea_level=64, tile_size=tile, lowland_top_m=600.0, highland_base_m=2000.0,
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
    from terrain_slm.export import native_gen as NG
    from terrain_slm.world import generator2 as G2
    torch.cuda.set_device(DEV)
    gen = G2.WorldGenerator2(WORLD, 4242, DEV)
    return NG.NativeGen(gen)


def _bind(ng, world, seed):
    """TileBuilder.build's per-build state on the Python generator, then the same state (+ world) natively."""
    from terrain_slm.export.native_manifest_tiles import _builder
    gen = ng.gen
    gen.use_seed(seed)
    gen.use_climate_layout(world.climate_layout or "belts")
    gen.use_sea_shelf(world.curve_version >= 2)
    gen._native_world = world
    tb = _builder(gen)
    # FX4 block look of the build (TileBuilder.build sets both; the methods tested alone read them)
    tb._look = world.curve_version >= 2 and getattr(gen, "block_look", 0) > 0
    if hasattr(gen, "use_block_look"):
        gen.use_block_look(tb._look)
    ng.sync_state(world=world)
    return tb


def _bitwise(rep: dict) -> None:
    bad = {k: v for k, v in rep.items() if not v.get("bitwise")}
    assert not bad, bad
    assert rep


# ------------------------------------------------------------------------------------------------ block scale
@pytest.mark.parametrize("scale", ["1:4", "1:2", "1:1"])
@pytest.mark.parametrize("version", [1, 2])
def test_block_scale(ng, scale, version):
    _bind(ng, _world(scale, version), 4242)
    _bitwise(ng.parity("block_scale"))


def test_block_scale_game_default(ng):
    from terrain_slm.world.world_config import WorldConfig
    _bind(ng, WorldConfig.GAME, 4242)
    _bitwise(ng.parity("block_scale"))


# ------------------------------------------------------------------------------------------------ biomes.py
def _random_climate(g, h, w, c):
    t = [torch.rand(h, w, generator=g) * 45 - 20, torch.rand(h, w, generator=g) * 1500,
         torch.rand(h, w, generator=g) * 3500 - 50, torch.rand(h, w, generator=g) * 140]
    if c == 7:
        t += [torch.rand(h, w, generator=g) * 3, torch.rand(h, w, generator=g) * 0.6,
              torch.rand(h, w, generator=g) * 0.8]
    return torch.stack(t).float().to(DEV)


@pytest.mark.parametrize("c", [4, 7])
@pytest.mark.parametrize("px", [15.0, 60.0, 1920.0])
def test_classify_biome_random(ng, c, px):
    """Every branch of the rule classifier: random climate (4 / 7 channels), sea to mountain elevations, smooth and
    rough ground (the 3x3 Sobel slope), noise origins far from 0."""
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(int(px) * 10 + c)
    h, w = 80, 72
    smooth = F.interpolate(torch.rand(1, 1, 10, 9, generator=g), size=(h, w), mode="bilinear")[0, 0]
    elev = (smooth * 4200 - 400 + torch.rand(h, w, generator=g) * (60 if px < 100 else 5)).float().to(DEV)
    clim = _random_climate(g, h, w, c)
    ep = F.pad(elev[None, None], (1, 1, 1, 1), mode="replicate")[0, 0]
    for i0, j0 in ((0, 0), (-12345, 98765), (3_000_000, -2_000_000)):
        _bitwise(ng.parity("B._classify_biome", [i0, j0], [px], [elev, clim, ep]))


@pytest.mark.parametrize("c", [4, 7])
@pytest.mark.parametrize("step", [1, 4, 32])
def test_swamp_candidates_random(ng, c, step):
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(step * 10 + c)
    h, w = 64, 70
    elev = (torch.rand(h, w, generator=g) * 500 - 50).float().to(DEV)
    reg = (torch.rand(h, w, generator=g) * 400).float().to(DEV)
    clim = _random_climate(g, h, w, c)
    clim[0] = clim[0] * 0.5 + 15          # mostly warm: swamps possible
    for i0, j0 in ((0, 0), (-640, 1280), (99_999 * step, -77_777 * step)):
        _bitwise(ng.parity("B.swamp_candidates", [i0, j0, step], [], [elev, reg, clim]))


def test_majority_and_smooth_random(ng):
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(3)
    ids = torch.tensor([1, 5, 8, 15, 23, 44, 108], dtype=torch.int16)
    for h, w in ((64, 64), (37, 91)):
        coarse = torch.randint(0, len(ids), (h // 4 + 1, w // 4 + 1), generator=g)
        lab = ids[coarse.repeat_interleave(4, 0).repeat_interleave(4, 1)[:h, :w]]
        noise = torch.rand(h, w, generator=g) < 0.3
        lab = torch.where(noise, ids[torch.randint(0, len(ids), (h, w), generator=g)], lab).to(DEV)
        for size in (5, 9):
            _bitwise(ng.parity("B.majority", [size], [], [lab]))
        mask = (torch.rand(h, w, generator=g) < 0.5).to(DEV)
        _bitwise(ng.parity("B.smooth_mask", [11], [], [mask]))
        _bitwise(ng.parity("B.apply_swamps", [], [], [lab, mask]))


# ------------------------------------------------------------------------------------------------ world/ocean.py
@pytest.mark.parametrize("version", [1, 2])
def test_sea_mask_for_samples(ng, version):
    _bind(ng, _world("1:4", version), 4242)
    for s1, t1, h, w, d in ((-32, -32, 128, 128, 2), (900, -400, 96, 96, 1), (1000, 2000, 66, 66, 64),
                            (-50_000, 31_000, 40, 50, 16)):
        _bitwise(ng.parity("O.sea_mask_for_samples", [s1, t1, h, w, d]))


# ------------------------------------------------------------------------------------------------ TileBuilder
@pytest.mark.parametrize("d", [2.0, 1.0, 0.5, 64.0])
def test_red_deserts(ng, d):
    _bind(ng, _world("1:4", 2, "zones"), -7)
    g = torch.Generator().manual_seed(int(d * 2))
    b = torch.full((64, 64), 8, dtype=torch.int16)
    b[torch.rand(64, 64, generator=g) < 0.5] = 5
    for i1, j1 in ((0, 0), (8000, -5000)):
        _bitwise(ng.parity("TileBuilder._red_deserts", [i1, j1], [d], [b.to(DEV)]))


def test_planes_random(ng):
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(5)
    h = w = 48
    r = lambda lo, hi: torch.randint(lo, hi, (h, w), generator=g).to(DEV)
    height, level = r(-500, 700), r(-500, 700)
    wet = (torch.rand(h, w, generator=g) < 0.3).to(DEV)
    biome = r(0, 210).to(torch.int16)
    fl, roof, flow, carve = r(-1, 300), r(-1, 300), r(-1, 8), r(0, 65536)
    _bitwise(ng.parity("TileBuilder._planes", [], [], [height, biome, level, wet, fl, roof, flow, carve]))
    _bitwise(ng.parity("TileBuilder._planes", [], [], [height, biome, level, wet, fl, roof, flow]))


BLOCK_CASES = [("1:4", 2, "belts", 4242, 0, 0), ("1:4", 1, "zones", -7, 37, -21), ("1:2", 2, "latitude", 99, 5, 3),
               ("1:2", 1, "belts", 2**40 + 3, -40, 12), ("1:4", 2, "latitude", 2**40 + 3, -1000, 700)]


@pytest.mark.parametrize("case", BLOCK_CASES)
def test_blocks(ng, case):
    scale, version, layout, seed, tx, tz = case
    w = _world(scale, version, layout)
    tb = _bind(ng, w, seed)
    i1, j1, i2, j2 = tb.bounds(tx, tz)
    _bitwise(ng.parity("TileBuilder._blocks", [i1, j1, i2, j2], [float(w.downscale)]))


@pytest.mark.parametrize("version", [1, 2])
def test_blocks_fine(ng, version):
    w = _world("1:1", version)
    tb = _bind(ng, w, 4242 if version == 2 else -7)
    i1, j1, i2, j2 = tb.bounds(2, -1)
    _bitwise(ng.parity("TileBuilder._blocks_fine", [i1, j1, i2, j2]))


@pytest.mark.parametrize("case", [("1:4", 2, 8), ("1:4", 1, 32), ("1:2", 2, 32), ("1:1", 2, 32), ("1:2", 1, 8)])
def test_overview(ng, case):
    scale, version, lod = case
    w = _world(scale, version, "zones" if version == 1 else "belts")
    tb = _bind(ng, w, 4242)
    d = int(w.downscale * lod)
    for tx, tz in ((0, 0), (-3, 5)):
        i1, j1, i2, j2 = tb.bounds(tx, tz)
        _bitwise(ng.parity("TileBuilder._overview", [i1, j1, i2, j2], [float(d)]))


BUILD_CASES = [("1:4", 2, "belts", 4242, 0, 0), ("1:4", 1, "latitude", -7, 37, -21), ("1:2", 2, "zones", 99, 5, 3),
               ("1:1", 2, "belts", 4242, 2, -1), ("1:1", 1, "zones", 2**40 + 3, -7, 9),
               ("1:4", 2, "belts", 2**40 + 3, 4000, -2500)]


@pytest.mark.parametrize("case", BUILD_CASES)
@pytest.mark.parametrize("lod", [1, 2, 16])
def test_build(ng, case, lod):
    """Whole tiles: the int16 planes (and the surface channels, when the model folder has surface nodes)."""
    scale, version, layout, seed, tx, tz = case
    _bind(ng, _world(scale, version, layout), seed)
    _bitwise(ng.parity("TileBuilder.build", [seed, tx, tz, lod]))


def test_build_full_size_tile(ng):
    """A game-sized (256) tile at 1:4, v2."""
    _bind(ng, _world("1:4", 2, "belts", tile=256), 4242)
    _bitwise(ng.parity("TileBuilder.build", [4242, 1, -1, 1]))


# ------------------------------------------------------------------------------------------------ biome sidecar
def test_biome_sidecar(ng):
    """BN.classify: build_input + the engine BiomeNet ("biome_<H>x<W>", the plan the Python generator's proxy runs)
    + argmax, at the tests' frame sizes, incl. noise coordinates beyond float32's integer range."""
    if type(ng.gen.biomenet).__name__ != "_Proxy":
        pytest.skip("the biome sidecar is not on the engine (TGM_ENGINE=cenda)")
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(1)
    for n, (i0, j0) in ((128, (-32, -32)), (64, (1000, -3000)), (32, (2**25 + 7, -2**25))):
        sm = F.interpolate(torch.rand(1, 1, 9, 9, generator=g), size=(n, n), mode="bilinear")[0, 0]
        elev = (sm * 3000 - 200).float().to(DEV)
        clim = _random_climate(g, n, n, 7)
        _bitwise(ng.parity("BN.classify", [i0, j0], [], [elev, clim]))


def test_surface_encode(ng):
    from terrain_slm.world import surface_channels as SC
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(2)
    for k in range(len(SC.CHANNELS)):
        v = torch.randn(40, 40, generator=g) * 2
        v[0, :5] = torch.tensor([float("nan"), float("inf"), -float("inf"), 1e30, -0.0])
        _bitwise(ng.parity("SC.encode", [k], [], [v.to(DEV)]))


# ------------------------------------------------------------------------------------------------ surface channels
WORLD_F = ROOT / "checkpoints" / "exp2" / "world_exp2f"      # the r3 release candidate: vegetation + surface nodes


@pytest.mark.skipif(not ((WORLD_F / "veg.pt").exists() and (WORLD_F / "surface.pt").exists()),
                    reason="needs checkpoints/exp2/world_exp2f (veg.pt + surface.pt)")
def test_surface_providers_and_tiles():
    """The native surface providers (TileBuilder.surface.vegetation / .surface: build_input, the engine VegNet /
    SurfaceNet plans the Python proxies run, post-processing, water mask) alone on the providers' real frames, and whole
    tiles with their surface channels, at 1:4 / 1:2 / 1:1, curve v1 / v2."""
    from terrain_slm.export import native_gen as NG
    from terrain_slm.world import generator2 as G2
    gen = G2.WorldGenerator2(WORLD_F, 4242, DEV)
    if type(getattr(gen, "vegnet", None)).__name__ != "_Proxy":
        pytest.skip("the surface nodes are not on the engine (TGM_ENGINE=cenda)")
    ngf = NG.NativeGen(gen)
    try:
        assert ngf.manifest["tiles"]["providers"] == ["vegetation", "surface"]
        for scale, version, seed, tile in (("1:4", 2, 4242, (0, 0)), ("1:2", 2, -7, (1, -1)), ("1:1", 2, 99, (2, -1)),
                                           ("1:4", 1, 2**40 + 3, (37, -21)), ("1:2", 1, 4242, (0, 0)),
                                           ("1:1", 1, 4242, (-3, 5))):
            w = _world(scale, version)
            tb = _bind(ngf, w, seed)
            i1, j1, i2, j2 = tb.bounds(*tile)
            with torch.no_grad():
                if w.downscale < 1:
                    tb._blocks_fine(i1, j1, i2, j2)
                else:
                    tb._blocks(i1, j1, i2, j2, w.downscale)
            c = tb._surface_ctx
            args = ([c["i1"], c["j1"], c["mb"]], [float(c["d"])],
                    [c[k].contiguous() for k in ("ground", "climate", "regional", "height", "water", "wet", "biome")])
            for p in ("vegetation", "surface"):
                rep = ngf.parity(f"TileBuilder.surface.{p}", *args)
                assert all(v.get("bitwise") for v in rep.values()), (p, rep)
            rep = ngf.parity("TileBuilder.build", [seed, *tile, 1])
            assert any(k.startswith("surface.") for k in rep), rep.keys()
            _bitwise(rep)
            # every ported method native at once (the release path)
            ngf.route("*")
            try:
                got = ngf.call("TileBuilder.build", [seed, *tile, 1])
            finally:
                ngf.route("*", False)
            ref = tb.build(seed, *tile, 1)
            assert torch.equal(got["planes"].cpu(), torch.from_numpy(__import__("numpy").array(ref)))
            assert {k[8:] for k in got if k.startswith("surface.")} == set(ref.surface)
            for k, v in ref.surface.items():
                assert torch.equal(got["surface." + k].cpu(), torch.from_numpy(v)), k
    finally:
        del ngf, gen
        torch.cuda.empty_cache()


# ------------------------------------------------------------------------------------------------ FX4 block look
def test_despeckle_random(ng):
    """despeckle on synthetic block terrain full of the cases it fixes: islets / pinholes on flats and slopes,
    dominoes, needles / holes on rock, with wet columns and the frame edge."""
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(7)
    for h, w in ((64, 64), (45, 70)):
        base = (torch.rand(h // 8 + 1, w // 8 + 1, generator=g) * 6).floor()
        base = F.interpolate(base[None, None], size=(h, w), mode="nearest")[0, 0]
        ramp = (torch.arange(w)[None, :] * (torch.rand(1, generator=g) * 0.3)).floor()
        height = (base + ramp + 70).long()
        spots = torch.rand(h, w, generator=g)
        height = height + (spots < 0.05).long() - (spots > 0.95).long()
        height = height + 6 * (spots.roll(3, 0) < 0.01).long() - 4 * (spots.roll(5, 1) < 0.01).long()
        dry = torch.rand(h, w, generator=g) > 0.03
        _bitwise(ng.parity("T.despeckle", [], [], [height.to(DEV), dry.to(DEV)]))


def test_alpine_random(ng):
    _bind(ng, _world("1:4", 2), 4242)
    g = torch.Generator().manual_seed(8)
    h, w = 60, 70
    reg = (torch.rand(h, w, generator=g) * 3500 - 200).float().to(DEV)
    ground = (reg + torch.randn(h, w, generator=g).to(DEV) * 400).float()
    _bitwise(ng.parity("T.alpine_elevation", [], [], [reg, ground]))
    ids = torch.tensor([1, 5, 8, 15, 16, 17, 23, 29, 32, 108, 115, 116, 6, 44], dtype=torch.int16)
    biome = ids[torch.randint(0, len(ids), (h, w), generator=g)].to(DEV)
    temp = (torch.rand(h, w, generator=g) * 20 - 8).float().to(DEV)
    ts = (torch.rand(h, w, generator=g) * 1200).float().to(DEV)
    elev = (torch.rand(h, w, generator=g) * 3000).float().to(DEV)
    for i0, j0, step in ((0.0, 0.0, 1.0), (-37.5, 1200.25, 0.25), (5000.0, -9000.0, 0.5), (256.0, -512.0, 8.0)):
        _bitwise(ng.parity("T.alpine_zones", [], [i0, j0, step], [biome, elev, temp, ts]))
