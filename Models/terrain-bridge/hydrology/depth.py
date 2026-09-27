"""Turn elevation into a water-depth raster: lakes from basins, rivers from flow.

Lakes and rivers are computed by different mechanisms on purpose, because they are
different phenomena. A lake is standing water whose surface is flat at the elevation
its basin spills at -- that is exactly what a depression fill computes, so the fill
delta *is* the depth, with no free parameter. A river is moving water whose depth
scales with the discharge it carries, which is what flow accumulation estimates.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy import ndimage

from .fill import cap_basins, fill_depressions, ocean_mask
from .flats import DEFAULT_FLAT_EPSILON, resolve_flats
from .flow import d8_receivers, flow_accumulation

# Downstream hydraulic geometry (Leopold & Maddock 1953): channel depth grows as a
# power of discharge, and discharge scales with drainage area. These defaults are a
# plausible *shape*, tuned to look right in-game, not a calibrated physical claim --
# 1 km2 of catchment gives ~0.35 m, 100 km2 ~2.2 m, 10000 km2 ~14 m.
DEFAULT_RIVER_COEFF = 0.35
DEFAULT_RIVER_EXP = 0.4

# Ground distance across one cell, *horizontally*: `native_resolution / scale`,
# i.e. 30 m / 2. Mirrors `BridgeConfig.horizontal_meters_per_block`, and callers
# inside the bridge should pass that field rather than lean on this default.
#
# It is not `meters_per_block`. Those were the same number until the Phase 5
# elevation curve, after which the vertical rate is non-linear (~4 m/block in
# lowlands, ~24 in highlands) and no single value describes it. Drainage area is
# a horizontal quantity, so it wants this one.
DEFAULT_HORIZONTAL_CELL_SIZE_M = 15.0


@dataclass(frozen=True)
class WaterMap:
    """Every layer aligned cell-for-cell with the input DEM."""

    depth: np.ndarray          # (H, W) float32 metres, lakes and rivers combined
    lake_depth: np.ndarray     # (H, W) float32 metres, standing water only
    river_depth: np.ndarray    # (H, W) float32 metres, channel water only
    accumulation: np.ndarray   # (H, W) float32 upslope cell count
    filled: np.ndarray         # (H, W) float64 surface the lake tops sit on
    ocean: np.ndarray          # (H, W) bool, sea (elevation <= 0 or NaN)
    receivers: np.ndarray      # (H*W,) int64 D8 downstream index, -1 where terminal

    @property
    def water_surface(self) -> np.ndarray:
        """Elevation of the top of the water column, for placing blocks."""
        base = np.where(self.ocean, 0.0, np.nan_to_num(self.filled, nan=0.0))
        return np.where(self.ocean, 0.0, base + self.depth).astype(np.float32)

    def stats(self) -> dict:
        land = ~self.ocean
        n_land = int(land.sum())
        wet = (self.depth > 0) & land
        return {
            "shape": tuple(self.depth.shape),
            "land_cells": n_land,
            "ocean_cells": int(self.ocean.sum()),
            "lake_cells": int(((self.lake_depth > 0) & land).sum()),
            "river_cells": int(((self.river_depth > 0) & land).sum()),
            "wet_land_fraction": (float(wet.sum()) / n_land) if n_land else 0.0,
            "max_depth_m": float(self.depth.max()) if self.depth.size else 0.0,
            "max_accumulation": float(self.accumulation.max()) if self.accumulation.size else 0.0,
        }


def _filter_lakes(
    lake_depth: np.ndarray,
    min_area_cells: int,
    min_depth_m: float,
) -> np.ndarray:
    """Drop puddles, keep lakes. This is the mission's rivers-vs-ponds knob.

    Applied per connected basin rather than per cell, so a real lake keeps its shallow
    margins instead of being eaten away from the shoreline inward.
    """
    if min_area_cells <= 1 and min_depth_m <= 0.0:
        return lake_depth

    wet = lake_depth > 0.0
    if not wet.any():
        return lake_depth

    labels, n = ndimage.label(wet, structure=np.ones((3, 3), dtype=int))
    if n == 0:
        return lake_depth

    index = np.arange(1, n + 1)
    areas = np.bincount(labels.ravel(), minlength=n + 1)[1:]
    peaks = np.asarray(ndimage.maximum(lake_depth, labels, index)).reshape(-1)

    keep = (areas >= min_area_cells) & (peaks >= min_depth_m)
    keep_lut = np.concatenate([[False], keep])
    return np.where(keep_lut[labels], lake_depth, 0.0)


def water_depth(
    z: np.ndarray,
    *,
    horizontal_cell_size_m: float = DEFAULT_HORIZONTAL_CELL_SIZE_M,
    epsilon: float = DEFAULT_FLAT_EPSILON,
    river_threshold: float = 1000.0,
    river_coeff: float = DEFAULT_RIVER_COEFF,
    river_exp: float = DEFAULT_RIVER_EXP,
    min_lake_area: int = 8,
    min_lake_depth: float = 0.5,
    lake_max_raise: float | None = None,
    weights: np.ndarray | None = None,
) -> WaterMap:
    """Derive a water-depth map from an elevation map.

    Args:
        z: (H, W) elevation in metres. NaN or <= 0 is ocean.
        horizontal_cell_size_m: ground distance across one cell, used to convert an
            upslope cell count into a drainage area. Horizontal, not vertical --
            see `DEFAULT_HORIZONTAL_CELL_SIZE_M`.
        epsilon: magnitude of the drainage gradient laid across flats for routing.
        river_threshold: upslope cell count at which a channel becomes a river.
        river_coeff, river_exp: hydraulic-geometry `k` and `b` in `depth = k * area_km2 ** b`.
        min_lake_area: basins smaller than this many cells are discarded.
        min_lake_depth: basins shallower than this (metres, at their deepest) are discarded.
        lake_max_raise: cap, in metres, on how far a basin may fill above its own floor,
            applied to the *depth* surface only. `None` keeps the parameter-free fill.
            Routing always runs on the uncapped surface -- see below.
        weights: optional per-cell runoff weighting for accumulation.

    Returns:
        A `WaterMap` whose every layer has exactly the shape of `z`.
    """
    z = np.asarray(z, dtype=np.float64)
    if z.ndim != 2:
        raise ValueError(f"expected a 2D elevation array, got shape {z.shape}")

    ocean = ocean_mask(z)
    land = ~ocean

    # One fill, two surfaces derived from it, because depth and routing want opposite
    # things from a flat.
    #
    # Depth wants it perfectly level: `filled - z` is then exactly the standing water
    # depth, and a lake's surface is exactly its spill elevation. Measuring against a
    # surface with a gradient baked in would instead report a spurious film of water
    # across every plain.
    #
    # Routing wants it tilted: D8 cannot leave a level cell at all. `resolve_flats`
    # adds a Garbrecht-Martz gradient far below the terrain's own relief -- enough to
    # order the flat, too small to alter it.
    filled = fill_depressions(z, epsilon=0.0, invalid=ocean)
    routing = resolve_flats(filled, ocean, epsilon=epsilon)

    # `lake_max_raise` splits the two surfaces further apart, and only routing may
    # use the uncapped one. A capped basin is not filled to a spill point, so it has
    # no guaranteed descending path out; routed over, it is a pit that swallows its
    # whole catchment and `flow_accumulation` refuses the resulting graph. Capping is
    # therefore a post-pass on the depth copy -- one flood, two surfaces -- which is
    # also why `cap_basins` is called here rather than passed down to `fill_depressions`.
    surface = filled
    if lake_max_raise is not None:
        # NaN ocean is masked out of the cap by `ocean`, but it still has to hold a
        # finite value first or the connected-component reductions read it.
        z_safe = np.where(np.isnan(z), 0.0, z)
        capped = cap_basins(z_safe, np.where(np.isnan(filled), 0.0, filled), float(lake_max_raise))
        surface = np.where(ocean, filled, capped)

    lake_depth = np.clip(surface - z, 0.0, None)
    lake_depth[ocean] = 0.0
    lake_depth = _filter_lakes(lake_depth, int(min_lake_area), float(min_lake_depth))

    recv = d8_receivers(routing, ocean)
    acc = flow_accumulation(recv, land, weights=weights)

    drainage_km2 = acc * (horizontal_cell_size_m * horizontal_cell_size_m) / 1e6
    river_depth = np.where(
        (acc >= river_threshold) & land,
        river_coeff * np.power(np.maximum(drainage_km2, 0.0), river_exp),
        0.0,
    )

    depth = np.maximum(lake_depth, river_depth)
    depth[ocean] = 0.0

    return WaterMap(
        depth=depth.astype(np.float32),
        lake_depth=lake_depth.astype(np.float32),
        river_depth=river_depth.astype(np.float32),
        accumulation=acc.astype(np.float32),
        filled=surface,
        ocean=ocean,
        receivers=recv,
    )
