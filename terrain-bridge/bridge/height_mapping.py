"""Elevation (meters) -> block height.

Deterministic and global: no per-tile min/max normalization, which would make
the same real-world elevation map to a different block height depending on
what else happened to be in the request and reintroduce cross-tile seams.
Ocean depth is clamped rather than rescaled (plan.md section 4) — the model's
full abyssal range is not worth reserving hundreds of blocks of empty water
for.
"""
from __future__ import annotations

import numpy as np


def elevation_to_block_height(
    elev_m: np.ndarray,
    meters_per_block: float,
    sea_level: int,
    world_height: int,
) -> np.ndarray:
    blocks = sea_level + np.floor(elev_m.astype(np.float64) / meters_per_block)
    blocks = np.clip(blocks, 0, world_height - 1)
    return blocks.astype(np.int16)
