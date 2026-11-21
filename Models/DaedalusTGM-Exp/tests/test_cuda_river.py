"""The river fixed-point loops on native steps (kernels/cuda_river.py) give the torch loops' numbers bit for bit."""
from __future__ import annotations

import pytest
import torch
import torch.nn.functional as F

from terrain_slm.kernels import cuda_river as KR
from terrain_slm.kernels import native
from terrain_slm.river import geometry as GEO

cuda = pytest.mark.skipif(not (torch.cuda.is_available() and native.cuda_available("cuda")), reason="no CUDA kernels")


def _torch_mono(level, line, flow, iters):
    old = KR.ENABLED
    KR.ENABLED = False
    try:
        return GEO.monotone_downstream(level, line, flow, iters)
    finally:
        KR.ENABLED = old


def _torch_grow(grown, wk, steps):
    for _ in range(steps):
        nxt = F.max_pool2d(grown, 3, 1, 1) * wk
        if torch.equal(nxt, grown):
            break
        grown = nxt
    return grown


def _river(h, w, gen, dev):
    """A meandering line with noisy flow (rises downstream with jitter) and a bumpy level."""
    line = torch.zeros(h, w, dtype=torch.bool)
    flow = torch.zeros(h, w)
    for r in range(h):
        c = int(w / 2 + (w / 4) * torch.sin(torch.tensor(r / 9.0)))
        line[r, max(0, c - 1) : c + 2] = True
        flow[r, max(0, c - 1) : c + 2] = r + torch.randn(min(w, c + 2) - max(0, c - 1), generator=gen) * 3
    level = 500 - torch.arange(h, dtype=torch.float32)[:, None] * 2 + torch.randn(h, w, generator=gen) * 15
    return level.to(dev), line.to(dev), flow.to(dev)


@cuda
@pytest.mark.parametrize("iters", [0, 1, 5, 8, 9, 37, 128])
def test_monotone_downstream_matches(iters):
    dev = torch.device("cuda", torch.cuda.current_device())
    gen = torch.Generator().manual_seed(iters)
    level, line, flow = _river(150, 120, gen, dev)
    level[3, 60] = float("nan")                                   # NaN propagates like torch.minimum
    ref = _torch_mono(level, line, flow, iters)
    got = KR.monotone_downstream(level, line, flow, iters)
    assert torch.equal(torch.nan_to_num(ref, nan=-1.0).view(torch.int32), torch.nan_to_num(got, nan=-1.0).view(torch.int32))
    assert torch.equal(torch.isnan(ref), torch.isnan(got))


@cuda
@pytest.mark.parametrize("steps", [1, 3, 8, 20, 64])
def test_growth_matches(steps):
    dev = torch.device("cuda", torch.cuda.current_device())
    gen = torch.Generator().manual_seed(steps)
    weak = (torch.rand(1, 1, 90, 110, generator=gen) < 0.55).float().to(dev)
    grown = ((torch.rand(1, 1, 90, 110, generator=gen) < 0.01).float().to(dev)) * weak
    ref = _torch_grow(grown, weak, steps)
    got = KR.grow(grown, weak, steps)
    assert torch.equal(ref, got)
