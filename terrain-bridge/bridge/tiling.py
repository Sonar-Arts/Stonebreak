"""Fixed tile-grid bucketing.

Phase 0 found that InfiniteDiffusion's determinism guarantee holds only for an
*identical* request shape — the same world coordinates fetched via a small vs.
large bounding box can differ by ~1m on a small fraction of pixels (GPU batch
grouping, not a spatial seam). That means every tile ID must always be fetched
from upstream using the same canonical (i1, j1, i2, j2) shape, never re-derived
from an ad hoc query — otherwise neighboring tiles show that jitter as a visible
seam. This module is the single place tile shape is decided.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TileId:
    seed: int
    tile_x: int
    tile_z: int
    scale: int

    def cache_key(self) -> str:
        return f"s{self.seed}_x{self.tile_x}_z{self.tile_z}_sc{self.scale}"


def block_to_tile(coord: int, tile_size_blocks: int) -> int:
    """Floor-divide toward negative infinity, so tiles cover negative coords
    contiguously (Python's `//` already does this for ints — a Java port of
    this logic must use Math.floorDiv, not plain `/`)."""
    return coord // tile_size_blocks


def tile_containing(world_x: int, world_z: int, tile_size_blocks: int) -> tuple[int, int]:
    return block_to_tile(world_x, tile_size_blocks), block_to_tile(world_z, tile_size_blocks)


def tile_bounds(tile_x: int, tile_z: int, tile_size_blocks: int) -> tuple[int, int, int, int]:
    """Canonical (i1, j1, i2, j2) for a tile — the only shape ever requested for it."""
    i1 = tile_x * tile_size_blocks
    j1 = tile_z * tile_size_blocks
    return i1, j1, i1 + tile_size_blocks, j1 + tile_size_blocks
