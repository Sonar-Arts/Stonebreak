"""Swamp relief: flatten swamp ground into broad basins before the river pipeline.

The game floods a swamp column's own top block into a pool, so every 1-block step of the ground is a new water
level. Near sea level a block is ~16 m tall against 60 m wide, and swamps sat on real slopes (median a step every
~7 blocks, 2026-10-18 tiles), so swamp water read as terraces. Here swamp ground is pulled onto a water table: the
coarse terrain with its hills and hollows mostly squashed (SWAMP_SLOPE_KEEP), even slopes broken into flat basins
(stairs) and all sub-cell relief removed.

Request-independent like everything upstream of the tiles: the weight and the table are built from canonical
planner cells and procedural controls (plus coordinate noise) over a fixed margin, never from a neighbourhood of
native pixels, so the halo frame `river_field` flattens meets its neighbours without seams. Rivers, banks and the
block stages then run on the flattened ground, so containment holds by construction.
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

from terrain_slm import biomes as B
from terrain_slm.data import descriptors as D

#: Blur (cells) of the cell-level swamp-candidate mask: the edge ramp of the flattening.
SWAMP_WEIGHT_SIGMA_CELLS = 1.5
#: Blurred-mask values mapped through a smoothstep to 0 .. 1: a swamp interior is fully flat, a lone speck is not.
SWAMP_WEIGHT_EDGES = (0.2, 0.6)
#: The table's local level (cells): removes every hill and hollow smaller than a few cells.
SWAMP_TABLE_SIGMA_CELLS = 2.0
#: The broad level the table's slope is compressed toward (cells, ~1.9 km).
SWAMP_BASE_SIGMA_CELLS = 8.0
#: Share of the local level's departure from the broad level that the table keeps (2026-10-18 sweep: 0.15 with 48 m
#: stairs gave the largest single-level swamp patches, 13x the unflattened ones; 0.25 or 32 / 64 m stairs fewer).
SWAMP_SLOPE_KEEP = 0.15
#: Height (m) between the flat basins a long, even slope is broken into (~3 blocks near sea level), 0 = off. Blurs
#: leave a linear ramp exactly as it is, so the slope compression above cannot flatten one: a smooth staircase
#: of the broad level does, flat around each basin's level and steep (a bank, never a pool) between them.
SWAMP_STAIR_M = 48.0
#: Passes of the smooth staircase x - sin(2 pi x) / (2 pi): each one flattens the basins and narrows the banks.
SWAMP_STAIR_PASSES = 2
#: The table never dips below this (m): flattened swamp stays dry ground at the sea's edge, never sea floor.
SWAMP_TABLE_FLOOR_M = 0.5
#: Cells of context each side, so no blur in the chain reads the frame's reflect padding.
MARGIN_CELLS = (math.ceil(3 * max(SWAMP_BASE_SIGMA_CELLS, SWAMP_TABLE_SIGMA_CELLS))
                + math.ceil(3 * SWAMP_WEIGHT_SIGMA_CELLS) + 2)


def cell_fields(height: torch.Tensor, regional: torch.Tensor, climate: torch.Tensor,
                ci0: int, cj0: int, blocks_per_cell: int) -> tuple[torch.Tensor, torch.Tensor]:
    """(weight 0..1, table m) for cells framed MARGIN_CELLS wider than wanted on every side; the caller crops.

    `height` soft-capped planner metres, `regional` and `climate` as the tiles' swamp candidates take them (climate
    lapse-adjusted by regional), all (H, W) cells from (ci0, cj0). Candidates use block-unit noise at cell centres,
    the overlay's own noise (biomes.swamp_candidates), so the flattening sits where the swamp biome does."""
    half = blocks_per_cell // 2
    cand = B.swamp_candidates(height, regional, climate, ci0 * blocks_per_cell + half,
                              cj0 * blocks_per_cell + half, step=blocks_per_cell)
    blurred = D.blur(cand.float()[None, None], SWAMP_WEIGHT_SIGMA_CELLS)[0, 0]
    lo, hi = SWAMP_WEIGHT_EDGES
    t = ((blurred - lo) / (hi - lo)).clamp(0.0, 1.0)
    weight = t * t * (3.0 - 2.0 * t)
    h = height[None, None]
    local = D.blur(h, SWAMP_TABLE_SIGMA_CELLS)[0, 0]
    broad = D.blur(h, SWAMP_BASE_SIGMA_CELLS)[0, 0]
    table = (stairs(broad) + SWAMP_SLOPE_KEEP * (local - broad)).clamp_min(SWAMP_TABLE_FLOOR_M)
    return weight, table


def stairs(level: torch.Tensor) -> torch.Tensor:
    """The smooth staircase of SWAMP_STAIR_M: flat steps at whole multiples, pointwise (so request-independent)."""
    if SWAMP_STAIR_M <= 0:
        return level
    x = level / SWAMP_STAIR_M
    for _ in range(SWAMP_STAIR_PASSES):
        x = x - torch.sin(2.0 * math.pi * x) / (2.0 * math.pi)
    return x * SWAMP_STAIR_M


def upsample(cells: torch.Tensor, cell_px: int, oi: int, oj: int, h: int, w: int) -> torch.Tensor:
    """Bilinear cells -> native pixels (cell centres at (c + 0.5) * cell_px), cropped to [oi, oi+h) x [oj, oj+w)
    pixels from the frame's first cell -- the convention of WorldGenerator.regional_elevation."""
    hc, wc = cells.shape[-2:]
    up = F.interpolate(cells[None, None], size=(hc * cell_px, wc * cell_px), mode="bilinear", align_corners=False)
    return up[0, 0, oi : oi + h, oj : oj + w]


def flatten(height: torch.Tensor, weight: torch.Tensor, table: torch.Tensor) -> torch.Tensor:
    """Pull ground onto the table by `weight`. Ground below sea level (lakes, the sea) is left alone: raising it would
    fill lakes and push coasts out."""
    return torch.where(height >= 0.0, height + weight * (table - height), height)
