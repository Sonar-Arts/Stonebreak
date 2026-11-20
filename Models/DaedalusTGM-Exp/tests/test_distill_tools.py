"""Distillation tools of the Standard generator (agent SD, 2026-11-19): stats accumulators, channel tables, fit model."""
from __future__ import annotations

import numpy as np
import pytest

from terrain_slm.eval import distill_stats as DS


def _tiles(seed=0):
    rng = np.random.default_rng(seed)
    out = []
    for lod in (8, 1, 1):
        x = np.linspace(0, 6 * np.pi, 256)
        h = 64 + 30 * np.sin(x)[:, None] * np.cos(x)[None, :] + rng.integers(0, 3, (256, 256))
        out.append((h.astype(np.int32), None, lod, (h.astype(np.float64) - 64) * 16.0 if lod == 8 else None, None))
    return out


def test_worker_accumulate_merge_equals_measure_tiles():
    items = _tiles()
    whole = DS.measure_tiles(items, "x")
    coarse, fine, nc, nf = {}, {}, 0, 0
    for it in items:
        c, f = {}, {}
        a, b = DS.accumulate(it, c, f)
        DS.merge(coarse, c)
        DS.merge(fine, f)
        nc, nf = nc + a, nf + b
    parts = DS.summarize(coarse, fine, nc, nf, "x")
    assert parts["census"] == whole["census"]
    assert parts["lowland_mix"] == whole["lowland_mix"]
    assert parts["sea_depth_by_km"] == whole["sea_depth_by_km"]
    assert abs(sum(whole["lowland_mix"].values()) - 1.0) < 1e-9
    d = DS.distance(whole, parts)
    assert d["total"] == pytest.approx(0.0, abs=1e-12)
    for k in ("census", "pits", "lowland_mix", "sea"):
        assert k in d


def test_hypsometry_spans_the_cubic_column():
    h = np.full((256, 256), -300, np.int32)
    h[:128] = 600
    r = DS.measure_tiles([(h, None, 8, None, None)], "x")
    hist = np.array(r["hypsometry"])
    assert len(hist) == DS.Y_MAX - DS.Y_MIN
    assert hist[-300 - DS.Y_MIN] == pytest.approx(0.5)
    assert hist[600 - DS.Y_MIN] == pytest.approx(0.5)


def test_channel_tables_and_fallback():
    from terrain_slm.eval import distill_channels as C
    n = 50_000
    rng = np.random.default_rng(1)
    B = rng.choice([C.SBW_BIOMES.index("PLAINS"), C.SBW_BIOMES.index("RED_SAND_DESERT")], n).astype(np.int32)
    H = (64 + rng.integers(0, 200, n)).astype(np.int32)
    S = rng.integers(0, 8, n).astype(np.float64)
    cols = {k: rng.integers(0, 254, n).astype(np.float64) for k in C.VEG + C.ROCK}
    cols["rock_exposure"] = np.clip(S * 30.0, 0, 254)          # rock rises with slope
    veg_mean, veg_std, rock, counts = C.tables(cols, B, H, S)
    plains = C.SBW_BIOMES.index("PLAINS")
    assert rock[plains, 0, 4, 0] > rock[plains, 0, 0, 0]
    bad, red = C.SBW_BIOMES.index("BADLANDS"), C.SBW_BIOMES.index("RED_SAND_DESERT")
    assert (veg_mean[bad] == veg_mean[red]).all() and (rock[bad] == rock[red]).all()
    assert (veg_mean[C.SBW_BIOMES.index("OCEAN")] == 255).all()
    text = C.render(veg_mean, veg_std, rock, counts, n, "test")
    assert "SC_VEG_MEAN[SBW_BIOME_COUNT][SC_HBANDS][8]" in text and "SC_ROCK" in text


def test_shape2_model_is_pure_and_differs_from_shape1():
    F = pytest.importorskip("terrain_slm.eval.distill_fit")   # needs the game's libcenda_kernels (cenda_noise)
    from dataclasses import replace
    p1 = F.Params()
    p2 = replace(p1, shape_version=2.0, abyss_m=-800.0, roll_m=100.0, mesa_m=80.0, trib_m=50.0, prov_rough=0.3)
    a = F.distilled_height(p2, F.Channels(p2), 7, 0, 0, 64, 4)
    b = F.distilled_height(p2, F.Channels(p2), 7, 0, 0, 64, 4)
    c = F.distilled_height(p1, F.Channels(p1), 7, 0, 0, 64, 4)
    assert (a == b).all()
    assert (a != c).mean() > 0.2
    assert a.min() >= F.Y_LO and a.max() <= F.Y_HI
