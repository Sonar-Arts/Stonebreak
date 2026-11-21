"""The cross-fade kernel (kernels/cuda_blend.py, csrc/blend.cu) is bit-identical to the torch loop it replaced."""
from __future__ import annotations

import math

import pytest
import torch

from terrain_slm.kernels import cuda_blend as KB
from terrain_slm.kernels import native

cuda = pytest.mark.skipif(not (torch.cuda.is_available() and native.cuda_available("cuda")), reason="no CUDA kernels")


def _fade(ext: int, apron: int, device) -> torch.Tensor:
    from terrain_slm.world.generator import WorldGenerator
    return WorldGenerator._fade(ext, apron, device)


def _items(c, h, w, ext, core, apron, oi, oj, device, gen):
    """Windows of a canonical grid over a request at (oi, oj), in the generator's loop order."""
    items = []
    for wi in range(math.floor((oi - ext + apron) / core), math.floor((oi + h - 1 + apron) / core) + 1):
        for wj in range(math.floor((oj - ext + apron) / core), math.floor((oj + w - 1 + apron) / core) + 1):
            win = (torch.randn(c, ext, ext, generator=gen) * 300).to(device)
            items.append((win, wi * core - apron - oi, wj * core - apron - oj))
    return items


@cuda
@pytest.mark.parametrize("c,h,w,ext,core,apron,oi,oj", [
    (11, 448, 448, 64, 32, 16, -37, 5),          # planner-like (stride 32, many windows: several launches)
    (1, 600, 520, 448, 256, 96, -1000, 333),     # relief trend
    (2, 77, 300, 448, 256, 96, 255, -96),        # drainage channels
    (1, 540, 540, 512, 256, 128, -130, 1),       # detail samples
    (7, 30, 41, 96, 48, 24, -60, 17),            # climate grid
])
def test_kernel_matches_loop_bit_for_bit(c, h, w, ext, core, apron, oi, oj):
    dev = torch.device("cuda", torch.cuda.current_device())
    gen = torch.Generator().manual_seed(h * 7 + w)
    items = _items(c, h, w, ext, core, apron, oi, oj, dev, gen)
    fade = _fade(ext, apron, dev)
    ref = KB.torch_overlap_add((c, h, w), items, fade, dev)
    got = KB.overlap_add((c, h, w), items, fade, dev)
    assert torch.equal(ref.view(torch.int32), got.view(torch.int32))


@cuda
def test_no_windows_is_zeros_and_2d_windows_work():
    dev = torch.device("cuda", torch.cuda.current_device())
    fade = _fade(64, 16, dev)
    assert torch.equal(KB.overlap_add((1, 5, 5), [], fade, dev), torch.zeros(1, 5, 5, device=dev))
    win = torch.randn(64, 64, device=dev)
    items = [(win, -3, -9), (win * 2, 30, 20), (win, 500, 500)]
    ref = KB.torch_overlap_add((1, 70, 70), items, fade, dev)
    assert torch.equal(ref.view(torch.int32), KB.overlap_add((1, 70, 70), items, fade, dev).view(torch.int32))


@pytest.mark.skipif(not (torch.cuda.is_available() and torch.cuda.device_count() > 1 and native.cuda_available("cuda:1")),
                    reason="needs two GPUs")
def test_launch_on_a_non_current_device():
    """The cross-fade on cuda:1 while cuda:0 is the current device (the module lives in cuda:1's context)."""
    torch.zeros(1, device="cuda:0")                      # both contexts exist, cuda:0 current
    torch.cuda.set_device(0)
    dev = torch.device("cuda:1")
    fade = _fade(64, 16, dev)
    items = [(torch.randn(3, 64, 64, device=dev), -5, 7), (torch.randn(3, 64, 64, device=dev), 20, -3)]
    ref = KB.torch_overlap_add((3, 70, 70), items, fade, dev)
    assert torch.equal(ref.view(torch.int32), KB.overlap_add((3, 70, 70), items, fade, dev).view(torch.int32))
