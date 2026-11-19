"""1:1 worlds (15 m blocks, WorldConfig.downscale 0.5, 2026-11-19): config, the river pipeline's sub-pixel block path,
the SR-15 stand-in's exact means, and -- with the EXP-2 + SR-15 model folder -- tiles that are request-independent and
whose 15 m ground averages to the 30 m terrain."""
import threading
from pathlib import Path

import numpy as np
import pytest
import torch

from terrain_slm.world.world_config import WorldConfig

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "checkpoints/exp2/world_exp2b_sr15"
DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")


def one_to_one(curve_version: int = 2) -> WorldConfig:
    """The game's 1:1 world (TerrainScale.worldConfigJson(ONE_TO_ONE, BELTS, V<n>)); new worlds get v2."""
    from terrain_slm.world.world_config import ONE_TO_ONE_CURVE, curve_v2_keys
    r, k = ONE_TO_ONE_CURVE
    d = dict(world_height=640, min_y=-384, sea_level=64, horizontal_m=15.0, downscale=0.5, tile_size=256,
             ocean_m_per_block=r[0], lowland_m_per_block=r[1], midland_m_per_block=r[2],
             highland_m_per_block=r[3], lowland_top_m=k[0], highland_base_m=k[1], shore_blend_m=k[2],
             midland_blend_m=k[3], highland_blend_m=k[4], climate_layout="belts")
    if curve_version == 2:
        d.update(curve_v2_keys("1:1"))
    return WorldConfig(**d)


def test_downscale_half_is_accepted_and_integral_values_keep_their_fingerprint():
    g = WorldConfig.GAME
    d = g.to_dict()
    d["downscale"] = 2.0                                     # a JSON float from the game
    w = WorldConfig.from_dict(d)
    assert w.downscale == 2 and isinstance(w.downscale, int) and w.fingerprint() == g.fingerprint()
    d.update(downscale=0.5, horizontal_m=15.0)
    assert WorldConfig.from_dict(d).downscale == 0.5
    assert WorldConfig.from_dict(d).fingerprint() != g.fingerprint()
    for bad in (0.25, 1.5, 0, -1):
        d["downscale"] = bad
        with pytest.raises(ValueError):
            WorldConfig.from_dict(d)


def test_one_to_one_curve_keeps_quarter_rates_low_and_fits_the_summits():
    """The user's 1:1 decision (2026-11-18): CAP, not less exaggeration -- 1:4's exaggeration (quarter rates) below
    ~2 km, the summits (the generator soft-caps them at ~5150 m) compressed under the build limit."""
    from terrain_slm.world.height_curve import HeightCurve

    from terrain_slm.world.world_config import CURVE_V2
    b = CURVE_V2["1:4"]
    sea4 = dict(shelf_meters_per_block=b["shelf"] / 4, shelf_base_m=b["shelf_base"], shelf_blend_m=b["shelf_blend"],
                shallow_meters_per_block=b["shallow"] / 4, shallow_base_m=b["shallow_base"],
                shallow_blend_m=b["shallow_blend"])
    for version, shelf, shore in ((1, {}, 60.0), (2, sea4, b["shore_blend"])):
        one = one_to_one(version).curve()
        quarter = HeightCurve(12.0, 4.0, 6.0, 9.5, 600.0, 2000.0, shore, 150.0, 300.0, sea_level=64, world_height=640,
                              min_y=-384, **shelf)       # 1:4's curve (v1 / v2) at a quarter of every rate
        e = np.array([-100.0, -50.0, 0.0, 100.0, 300.0, 600.0, 1000.0, 1500.0, 2000.0])
        assert np.abs(one.to_block_height_exact(e) - quarter.to_block_height_exact(e)).max() < 1.0
        top = one.to_block_height_exact(np.array([5150.0, 6000.0]))
        assert 600 < top[0] < 639 - 5 and top[1] <= 639 + 40
        y = one.to_block_height_exact(np.arange(-11000.0, 9000.0, 25.0))
        assert np.all(np.diff(y) > 0)
    # v2: the deepest trench (-9.7 km) keeps >= 100 blocks above the floor, Challenger Deep (-10.9 km) fits too.
    assert one.to_block_height_exact(np.array([-9700.0]))[0] > -384 + 100
    assert -384 < one.to_block_height_exact(np.array([-10900.0]))[0]


def test_unpool_repeats_the_field_and_takes_dry_ground_from_the_fine_terrain():
    from terrain_slm.river.blocks import unpool_to_blocks
    from terrain_slm.river.field import RiverField

    g = torch.arange(16, dtype=torch.float32).view(4, 4) + 100.0
    wet = torch.zeros(4, 4, dtype=torch.bool)
    wet[1, 2] = True
    f = RiverField(ground=g, carved=g - wet.float() * 5.0, wet=wet, top=torch.where(wet, g + 1.0, g),
                   channel=wet, half_px=wet.float() * 0.75, flow=torch.ones(4, 4))
    fine = torch.full((8, 8), 7.0)
    src = unpool_to_blocks(f, 2, (10, 20), None, fine)
    assert src.ground_m.shape == (8, 8) and src.d == 0.5 and src.origin == (10, 20)
    assert bool(src.wet[2:4, 4:6].all()) and int(src.wet.sum()) == 4
    assert torch.equal(src.ground_m[2:4, 4:6], torch.full((2, 2), float(g[1, 2]) - 5.0))
    assert float(src.ground_m[0, 0]) == 7.0
    assert torch.allclose(src.half_blocks[2:4, 4:6], torch.full((2, 2), 1.5))   # 0.75 px = 1.5 blocks


def test_bilinear_stand_in_is_exactly_mean_preserving():
    """WorldGenerator.native_fine without SR-15: every 2x2 block of the 15 m field averages to its 30 m pixel."""
    from terrain_slm.world.generator import WorldGenerator

    class Stub:
        lock = threading.RLock()

        def native(self, i1, j1, i2, j2):
            i = torch.arange(i1, i2, dtype=torch.float32).view(-1, 1)
            j = torch.arange(j1, j2, dtype=torch.float32).view(1, -1)
            return 300.0 + 40.0 * torch.sin(i / 7.0) * torch.cos(j / 5.0) + 3.0 * ((i * 13 + j * 7) % 5)

    s = Stub()
    fine = WorldGenerator.native_fine(s, -40, 18, 60, 98)               # 15 m pixels, even frame
    coarse = s.native(-20, 9, 30, 49)
    pooled = torch.nn.functional.avg_pool2d(fine[None, None], 2)[0, 0]
    assert fine.shape == (100, 80) and float((pooled - coarse).abs().max()) < 1e-3
    odd = WorldGenerator.native_fine(s, -39, 19, 59, 97)                # any frame: same values where they overlap
    assert torch.allclose(odd, fine[1:-1, 1:-1])


@pytest.mark.skipif(not (MODEL / "sr15.pt").exists() or not torch.cuda.is_available(),
                    reason="needs the EXP-2 + SR-15 model folder and a GPU")
def test_one_to_one_tiles_are_request_independent_and_mean_preserving():
    from terrain_slm.world.generator2 import WorldGenerator2
    from terrain_slm.world.tiles import TileBuilder

    gen = WorldGenerator2(MODEL, 0, DEV)
    b = TileBuilder(gen, one_to_one())
    seed, tx, tz = 777, 3, -5                                   # river country near spawn (2026-11-19)
    a, c = b.build(seed, tx, tz, 1), b.build(seed, tx + 1, tz, 1)
    assert a.shape == (7, 256, 256) and a.dtype == np.int16
    with gen.lock:
        gen.set_block_scale(b.scale)
        gen.use_seed(seed)
        gen.use_climate_layout("belts")
        i1, j1 = tx * 256 + 128, tz * 256
        h, biome, level, wet, river = b._blocks_fine(i1, j1, i1 + 256, j1 + 256)
        shifted = b._planes(h, biome, level, wet, river)
        nat = gen.native(tx * 128, tz * 128, tx * 128 + 96, tz * 128 + 96)
        fine = gen.native_fine(tx * 256, tz * 256, tx * 256 + 192, tz * 256 + 192)
    ref = np.concatenate([a[:6, 128:], c[:6, :128]], 1)
    assert np.array_equal(shifted[:6], ref), "a half-tile-shifted frame disagrees with the tiles it overlaps"
    pooled = torch.nn.functional.avg_pool2d(fine[None, None], 2)[0, 0]
    assert float((pooled - nat).abs().max()) < 0.01                 # SR-15: exact 2x2 means (fp32)
    assert float((fine - nat.repeat_interleave(2, 0).repeat_interleave(2, 1)).std()) > 0.3   # and real 15 m relief
    for lod in (2, 16):                                             # LOD / mapper tiles at 1:1
        p = b.build(seed, tx, tz, lod)
        assert p.shape == (7, 256, 256) and p[0].min() >= -384 and p[0].max() <= 639
