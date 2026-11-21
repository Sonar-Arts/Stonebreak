"""The planner's whole-chunk post-processing (generator._run_planner_windows, 2026-11-19) makes exactly the windows the
per-window loop made, on the shipping EXP-2 model (skips without it / without CUDA)."""
from __future__ import annotations

from pathlib import Path

import pytest
import torch

from terrain_slm.models import planner as P

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "checkpoints/exp2/world_exp2b"
pytestmark = pytest.mark.skipif(not ((MODEL / "planner.pt").exists() and torch.cuda.is_available()),
                                reason="no EXP-2 model or no CUDA")


def _old_windows(gen, keys):
    """The pre-2026-11-19 loop, verbatim but returning the values instead of caching them."""
    from terrain_slm.data import descriptors as D
    from terrain_slm.world import generator as G
    i0 = min(k[0] for k in keys) * G.STRIDE
    j0 = min(k[1] for k in keys) * G.STRIDE
    hn = max(k[0] for k in keys) * G.STRIDE + G.WIN - i0
    wn = max(k[1] for k in keys) * G.STRIDE + G.WIN - j0
    xa, ca = gen._planner_input(i0, j0, hn, wn)
    res = {}
    for start in range(0, len(keys), G.PLANNER_BATCH):
        chunk = keys[start : start + G.PLANNER_BATCH]
        xs = torch.zeros(G.PLANNER_BATCH, P.N_IN, G.WIN, G.WIN, device=gen.device)
        for b, (wi, wj) in enumerate(chunk):
            a, c0 = wi * G.STRIDE - i0, wj * G.STRIDE - j0
            xs[b] = xa[0, :, a : a + G.WIN, c0 : c0 + G.WIN]
        with torch.no_grad():
            out = gen.planner(xs).float()
        out = D.blur(out, G.PATCH_BLUR_CELLS)
        for b, (wi, wj) in enumerate(chunk):
            o = out[b]
            a, c0 = wi * G.STRIDE - i0, wj * G.STRIDE - j0
            trend = ca["trend"][a : a + G.WIN, c0 : c0 + G.WIN]
            height = o[P.OUT_HEIGHT] * P.HEIGHT_SCALE_M
            desc = o[P.OUT_DESC] * gen.desc_std.view(-1, 1, 1) + gen.desc_mean.view(-1, 1, 1)
            river = torch.sigmoid(o[P.OUT_RIVER])
            logacc = o[P.OUT_LOGACC] * P.LOGACC_SCALE
            height = gen._sea_merge(height, trend)
            res[wi, wj] = torch.cat([height[None], desc, river[None], logacc[None]], dim=0)
    return res


def test_batched_planner_windows_are_bit_identical():
    from terrain_slm.world.generator import GenConfig
    from terrain_slm.world.generator2 import WorldGenerator2
    dev = f"cuda:{torch.cuda.device_count() - 1}"
    gen = WorldGenerator2(MODEL, 777, dev, GenConfig())
    keys = [(wi, wj) for wi in range(-3, 4) for wj in range(-2, 5)]        # 49 windows: a full and a partial chunk
    ref = _old_windows(gen, keys)
    gen._run_planner_windows(keys)
    for k in keys:
        got = gen._windows.get_or((gen.seed, *k), lambda: None)
        assert got is not None and got.is_contiguous()
        assert torch.equal(got.view(torch.int32), ref[k].view(torch.int32)), k


def _clear(gen):
    from terrain_slm.world import generator as G
    for v in list(vars(gen).values()):
        if isinstance(v, G._LRU):
            v.clear()


def test_fast_paths_build_the_same_tile():
    """Cross-fade kernel + parallel host routing + async LRU spills + native river steps on vs the torch loop +
    one-by-one routing + sync spills + torch river loops: identical planes."""
    from terrain_slm.eval.pipe_bench import game_world
    from terrain_slm.kernels import cuda_blend as KB
    from terrain_slm.world import generator as G
    from terrain_slm.world.generator import GenConfig
    from terrain_slm.world.generator2 import WorldGenerator2
    from terrain_slm.world.tiles import TileBuilder
    dev = f"cuda:{torch.cuda.device_count() - 1}"
    gen = WorldGenerator2(MODEL, 4242, dev, GenConfig())
    gen.warm_up()
    builder = TileBuilder(gen, game_world("1:4"))
    fast = builder.build(4242, 1, -1, 1)
    from terrain_slm.kernels import cuda_river as KR
    flags = (KB.ENABLED, G.PARALLEL_HOST_WORK, G.ASYNC_SPILL, KR.ENABLED)
    try:
        KB.ENABLED, G.PARALLEL_HOST_WORK, G.ASYNC_SPILL, KR.ENABLED = False, False, False, False
        _clear(gen)
        slow = builder.build(4242, 1, -1, 1)
    finally:
        KB.ENABLED, G.PARALLEL_HOST_WORK, G.ASYNC_SPILL, KR.ENABLED = flags
    assert (fast == slow).all(), [int((fast[p] != slow[p]).sum()) for p in range(fast.shape[0])]


def test_climate_cells_are_request_independent():
    """A cell's climate is the same whichever request asks for it (2026-11-19: grid_sample's normalised coordinates
    made it depend on the request's extent by a few ulps, and tiles on the order they were built in)."""
    from terrain_slm.world.generator import GenConfig
    from terrain_slm.world.generator2 import WorldGenerator2
    dev = f"cuda:{torch.cuda.device_count() - 1}"
    gen = WorldGenerator2(MODEL, 4242, dev, GenConfig())
    big = gen.climate_cells(-200, -200, 448, 448)
    for oi, oj, n in ((100, 50, 64), (0, 0, 17), (300, 233, 140)):
        small = gen.climate_cells(-200 + oi, -200 + oj, n, n)
        for k, v in big.items():
            assert torch.equal(v[oi : oi + n, oj : oj + n], small[k]), k
