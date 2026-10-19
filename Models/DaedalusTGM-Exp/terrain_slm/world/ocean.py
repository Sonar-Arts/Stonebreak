"""True ocean vs inland water.

Every column whose ground is below sea level fills with water at sea level (tiles._planes), and
the rule classifier calls every such column ocean (elev < 0). That made each sub-sea-level pit a
tiny "ocean": a sand-and-gravel floor and, in the game, a one-column beach ring around it (and
along every river reach whose bed dipped under sea level).

A sea is decided by where the world's controls put ocean, and by connection:

  1. cells (240 m): sub-sea cells (the planner's coarse height below 0 m) where the controls'
     continent field says open water (generator.continent_land below SEA_LAND) seed the sea, which
     then grows through connected sub-sea cells for up to SEA_GROW_CELLS (the shelf up to the
     real coastline, bays) and SEA_COAST_CELLS past them;
  2. columns: a sub-sea column inside that mask is ocean, and so is any sub-sea column connected
     to one within SEA_REACH_SAMPLES steps (estuaries, lagoons, inlets off the coast).

A low basin inside a continent -- however large -- is never the sea unless it opens onto it.
Everything else below sea level is a LAKE: still water at sea level, but the land biome around it,
a dirt-and-sand bed in the game and no beach. The planner cells are canonical (request-independent)
and every step is a bounded local operator, so tiles agree at their seams; SEA_REACH_SAMPLES stays
well inside the block stages' margin (pipeline.BLOCK_MARGIN).
"""
from __future__ import annotations

import torch
import torch.nn.functional as F

#: Continent land weight below which the controls mean open ocean (land 0 = trend -300 m; the
#: real coastline lies further inland, which SEA_GROW_CELLS covers).
SEA_LAND = 0.5
#: How far (cells, ~15 km) the sea grows from that seed through connected sub-sea cells.
SEA_GROW_CELLS = 64
#: How far (cells) the sea mask reaches past the grown body.
SEA_COAST_CELLS = 2
#: Sub-sea columns connected to the sea within this many samples join it (geodesic growth).
SEA_REACH_SAMPLES = 16
#: Cells of context the cell mask needs each side to be exact.
SEA_CELL_MARGIN = SEA_GROW_CELLS + SEA_COAST_CELLS + 1
#: Elevation (m) a lake column is classified at: just above sea level, so the rule picks the land
#: biome around it rather than an ocean id.
LAKE_CLASSIFY_ELEV_M = 0.5


def _dilate(m: torch.Tensor, r: int) -> torch.Tensor:
    if r <= 0:
        return m
    return F.max_pool2d(m.float()[None, None], 2 * r + 1, 1, r)[0, 0] > 0.5


def grow(seed: torch.Tensor, through: torch.Tensor, steps: int) -> torch.Tensor:
    """Geodesic growth: `seed` spreads 8-connected through `through` for at most `steps` steps."""
    out = seed & through
    for _ in range(steps):
        nxt = _dilate(out, 1) & through
        if torch.equal(nxt, out):
            break
        out = nxt
    return out


def sea_cells(height_m: torch.Tensor, land: torch.Tensor) -> torch.Tensor:
    """(H, W) bool sea mask of a cell window from its coarse height (metres) and continent land
    weight. Exact for cells at least SEA_CELL_MARGIN inside the window."""
    sub = height_m < 0.0
    return _dilate(grow(sub & (land < SEA_LAND), sub, SEA_GROW_CELLS), SEA_COAST_CELLS)


def sea_mask_for_samples(gen, s1: int, t1: int, h: int, w: int, d: int) -> torch.Tensor:
    """(h, w) bool cell sea mask at samples [s1, s1+h) x [t1, t1+w) of d native pixels each: a
    sample is sea if any cell it covers is."""
    from terrain_slm.world.generator import CP, continent_land
    m = SEA_CELL_MARGIN
    ci0, cj0 = (s1 * d) // CP - m, (t1 * d) // CP - m
    ci1, cj1 = ((s1 + h) * d - 1) // CP + 1 + m, ((t1 + w) * d - 1) // CP + 1 + m
    with gen.lock:
        cells = gen._planner_cells(ci0, cj0, ci1 - ci0, cj1 - cj0)[0]
    sea = sea_cells(cells, continent_land(ci0, cj0, ci1 - ci0, cj1 - cj0, gen.seed, cells.device))
    dev = sea.device
    # Cells a sample covers: [first, last] per axis (one cell for d <= CP).
    si = torch.arange(s1, s1 + h, device=dev)
    sj = torch.arange(t1, t1 + w, device=dev)
    k = max(1, -(-d // CP))
    out = torch.zeros(h, w, dtype=torch.bool, device=dev)
    for a in range(k):
        for b in range(k):
            ri = ((si * d) // CP + a).clamp(max=ci1 - 1) - ci0
            rj = ((sj * d) // CP + b).clamp(max=cj1 - 1) - cj0
            out |= sea[ri][:, rj]
    return out


def ocean_columns(sub: torch.Tensor, sea: torch.Tensor, reach: int = SEA_REACH_SAMPLES) -> torch.Tensor:
    """Sub-sea columns that are ocean: inside the sea mask, or connected to such a column through
    sub-sea columns within `reach` steps (8-connected)."""
    return grow(sub & sea, sub, reach)


def classify_elev(elev: torch.Tensor, lake: torch.Tensor) -> torch.Tensor:
    """Elevation for the biome rule: lake columns lifted to just above sea level (land biomes)."""
    return torch.where(lake, elev.clamp_min(LAKE_CLASSIFY_ELEV_M), elev)
