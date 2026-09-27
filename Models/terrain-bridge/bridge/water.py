"""Maps the model server's water plane to per-column water levels.

The terrain model (DaedalusTGM-Exp) supplies rivers with each tile: the elevation it
sends already has the channels carved, and a separate plane carries the water SURFACE
in metres. This module maps both through the one height curve so a column holds water
up to the block height its surface would have as terrain. The ocean is the plain
`y < SEA_LEVEL` rule on top.
"""
from __future__ import annotations

from typing import Protocol

import numpy as np

from .config import BridgeConfig
from .height_mapping import HeightCurve


class WaterSource(Protocol):
    """Elevation (+ water surface / river planes) in metres -> (block heights, water levels, report)."""

    #: Contribution to the tile cache namespace.
    fingerprint: str

    def planes(
        self,
        bounds_blocks: tuple[int, int, int, int],
        elevation_m: np.ndarray,
        surface_m: np.ndarray | None = None,
        river: tuple[np.ndarray, ...] | None = None,
    ) -> tuple[np.ndarray, np.ndarray, dict]:
        ...


class UpstreamWater:
    """Rivers supplied by the model server with each tile (`/terrain?...&water=1`).

    `-1` in the returned water plane means "this column holds no water" (the wire
    protocol's sentinel); below-sea-level columns hold sea level.
    """

    # Unchanged since this was one of several sources, so existing tile caches stay valid.
    fingerprint = "upstream-water-v1"
    WATER_NONE = -32768

    def __init__(self, cfg: BridgeConfig):
        self._curve = HeightCurve.from_config(cfg)
        self._sea_level = cfg.sea_level

    def planes(
        self,
        bounds_blocks: tuple[int, int, int, int],
        elevation_m: np.ndarray,
        surface_m: np.ndarray | None = None,
        river: tuple[np.ndarray, ...] | None = None,
    ) -> tuple[np.ndarray, np.ndarray, dict]:
        heights = self._curve.to_block_height(elevation_m)
        water = np.where(heights < self._sea_level, self._sea_level, -1).astype(np.int16)
        report = {}
        if surface_m is not None:
            wet = surface_m != self.WATER_NONE
            levels = self._curve.to_block_height(np.where(wet, surface_m, elevation_m))
            # A wet column under an overhang keeps the bank's full height above its water
            # (the water lives in its tunnel, river[0] = floor), so it is water even though its
            # level is below its height.
            tunnel = river is not None and river[0] is not None
            under = wet & (river[0] >= 0) if tunnel else np.zeros_like(wet)
            river = wet & ((levels > heights) | under)
            water = np.where(river, np.maximum(water, levels), water).astype(np.int16)
            report["wet_columns"] = int(river.sum())
        return heights, water, report


def build(cfg: BridgeConfig) -> WaterSource:
    return UpstreamWater(cfg)
