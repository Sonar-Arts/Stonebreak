"""Swamp relief (world/swamp_relief.py): swamp ground is pulled onto a flat water table, request-independently,
and nothing else moves."""
import threading
import types

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm.river.scale import BlockScale
from terrain_slm.world import generator as G
from terrain_slm.world import swamp_relief as SR

DEV = "cpu"
CP = G.CP


def _slope(ci0, cj0, hc, wc):
    """Planner heights: a 25 m/cell (~1.6 blocks per cell) slope with a ripple, a pure function of cell coords."""
    ci = torch.arange(ci0, ci0 + hc, dtype=torch.float32).view(-1, 1)
    cj = torch.arange(cj0, cj0 + wc, dtype=torch.float32).view(1, -1)
    return 40.0 + 25.0 * ci * 0.1 + 6.0 * torch.sin(cj * 0.7)


def _stub_gen(monkeypatch, swamp):
    """A WorldGenerator with only what swamp_relief needs: stub planner cells and swamp candidates `swamp(ci, cj)`."""
    gen = types.SimpleNamespace(lock=threading.RLock(), seed=7, device=DEV, scale=BlockScale.game_default(DEV))
    gen._planner_cells = lambda ci0, cj0, hc, wc: _slope(ci0, cj0, hc, wc)[None].expand(11, hc, wc)
    gen.swamp_relief_cells = types.MethodType(G.WorldGenerator.swamp_relief_cells, gen)
    gen._flatten_swamps = types.MethodType(G.WorldGenerator._flatten_swamps, gen)

    def candidates(elev, regional, climate, i0, j0, step=1):
        h, w = elev.shape
        bi = (torch.arange(h).view(-1, 1) * step + i0) // step       # back to cell coordinates
        bj = (torch.arange(w).view(1, -1) * step + j0) // step
        return swamp(bi.expand(h, w), bj.expand(h, w))

    monkeypatch.setattr(SR.B, "swamp_candidates", candidates)
    return gen


def test_overlapping_frames_agree(monkeypatch):
    """The seam guard: two different frames over the same pixels flatten them identically."""
    gen = _stub_gen(monkeypatch, lambda ci, cj: (ci > 20) & (ci < 70) & (cj > 10))
    native = lambda i1, j1, h, w: torch.from_numpy(
        np.fromfunction(lambda i, j: 30.0 + 0.4 * (i + i1) + 3.0 * np.sin((j + j1) * 0.05), (h, w))).float()
    f1 = gen._flatten_swamps(native(0, 0, 512, 400), 0, 0)
    f2 = gen._flatten_swamps(native(136, 72, 300, 300), 136, 72)
    assert not torch.equal(f1, native(0, 0, 512, 400))           # something was flattened
    assert torch.allclose(f1[136:436, 72:372], f2, atol=1e-4)


def test_ground_outside_swamps_is_untouched(monkeypatch):
    gen = _stub_gen(monkeypatch, lambda ci, cj: torch.zeros_like(ci, dtype=torch.bool))
    h = torch.linspace(5.0, 400.0, 256 * 256).view(256, 256)
    assert torch.equal(gen._flatten_swamps(h, 64, -128), h)


def test_swamp_interior_is_flattened(monkeypatch):
    """Deep inside a swamp the ground is the table: sub-cell bumps gone, and an even slope (which no blur flattens)
    broken into flat basins with banks between them."""
    gen = _stub_gen(monkeypatch, lambda ci, cj: torch.ones_like(ci, dtype=torch.bool))
    i = torch.arange(800, dtype=torch.float32).view(-1, 1).expand(800, 800)
    j = torch.arange(800, dtype=torch.float32).view(1, -1).expand(800, 800)
    # The stub's planner ramp (2.5 m per cell along i) plus native bumps the table must not keep.
    h = 40.0 + 2.5 * (i + 0.5) / CP + 15.0 * torch.sin(j * 0.3) * torch.sin(i * 0.21)
    core = gen._flatten_swamps(h, 0, 0)[200:600, 200:600]
    grad = (core[1:] - core[:-1]).abs()
    assert float((grad <= 0.25 * 2.5 / CP).float().mean()) > 0.4      # basins: at most a quarter of the ramp
    bumps = core - F.avg_pool2d(core[None, None], 9, 1, 4, count_include_pad=False)[0, 0]
    assert float(bumps.abs().max()) < 1.0                              # was 15 m


def test_flatten_never_raises_sub_sea_ground_or_digs_below_the_floor():
    h = torch.tensor([[-30.0, -1.0, 0.0, 200.0]])
    w = torch.ones_like(h)
    table = torch.full_like(h, SR.SWAMP_TABLE_FLOOR_M)
    out = SR.flatten(h, w, table)
    assert torch.equal(out[0, :2], h[0, :2])                # lakes and sea floor stay
    assert bool((out[0, 2:] >= SR.SWAMP_TABLE_FLOOR_M - 1e-6).all())


def test_cell_table_respects_its_floor():
    h = torch.full((80, 80), -50.0)
    climate = torch.zeros(4, 80, 80)
    _, table = SR.cell_fields(h, h, climate, 0, 0, 4)
    assert bool((table >= SR.SWAMP_TABLE_FLOOR_M).all())
