"""Elevation (meters) -> block height.

Deterministic and global: no per-tile min/max normalization, which would make
the same real-world elevation map to a different block height depending on
what else happened to be in the request and reintroduce cross-tile seams.

The mapping is non-linear but strictly monotone, defined as a metres-per-block
*rate* function integrated once onto a lookup table:

    m(e) = smooth blend between per-band rates
    y(e) = sea_level + integral from 0 to e of de' / m(e')

Rates, rather than a piecewise-linear y(e), because a piecewise-linear curve
puts slope discontinuities at its knots and a block-quantised world renders
those as terraces following elevation contours. Blending uses quintic
smoothstep with *finite* support so that each band holds its configured rate
exactly in its interior; a sum of logistics was tried first and rejected,
because its tails never vanish and the lowland band never actually reaches its
configured rate.

Lowlands get ~4 m/block so river incision (5-20 m) and lake depth (2-30 m) are
legible at all -- see `Dev Working/Rivers and lakes plan.md` Phase 5. The ocean
floor keeps a coarser rate deliberately: at 4 m/block a 1024-block column
bottoms out at -1280 m and flattens the whole abyssal plain onto y=0.
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache

import numpy as np

from .config import BridgeConfig

# The integration grid. Wide enough that both endpoints already fall outside
# the block column (asserted in _curve_table), so np.interp's edge clamping can
# never invent an in-range block height for an out-of-range elevation.
_TABLE_MIN_M = -20000.0
_TABLE_MAX_M = 20000.0
_TABLE_STEP_M = 1.0


def _smoothstep(t: np.ndarray) -> np.ndarray:
    """Quintic smoothstep, C2 at both ends, clamped outside [0, 1]."""
    t = np.clip(t, 0.0, 1.0)
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0)


def _blend(
    below: np.ndarray, above: np.ndarray, elev_m: np.ndarray, center: float, half_width: float
) -> np.ndarray:
    """`below` outside the window, `above` outside it on the other side, smooth between."""
    return below + (above - below) * _smoothstep((elev_m - (center - half_width)) / (2.0 * half_width))


@dataclass(frozen=True)
class HeightCurve:
    """Elevation -> block height for one fixed set of knobs.

    Frozen and hashable so the integrated table can be memoized on the knobs
    themselves. The table is a pure function of those knobs, so it re-derives
    identically on every process start.
    """

    ocean_meters_per_block: float
    lowland_meters_per_block: float
    midland_meters_per_block: float
    highland_meters_per_block: float
    lowland_top_m: float
    highland_base_m: float
    shore_blend_m: float
    midland_blend_m: float
    highland_blend_m: float
    sea_level: int
    world_height: int

    def __post_init__(self) -> None:
        rates = (
            self.ocean_meters_per_block,
            self.lowland_meters_per_block,
            self.midland_meters_per_block,
            self.highland_meters_per_block,
        )
        if not all(r > 0.0 for r in rates):
            raise ValueError(f"all metres-per-block rates must be > 0, got {rates}")
        widths = (self.shore_blend_m, self.midland_blend_m, self.highland_blend_m)
        if not all(w > 0.0 for w in widths):
            raise ValueError(f"all blend widths must be > 0, got {widths}")
        if self.lowland_top_m >= self.highland_base_m:
            raise ValueError(
                f"lowland_top_m ({self.lowland_top_m}) must be below "
                f"highland_base_m ({self.highland_base_m})"
            )
        # Blend windows must not overlap, or bands stop holding their configured
        # rate and the knobs quietly stop meaning what they say.
        if self.shore_blend_m > self.lowland_top_m - self.midland_blend_m:
            raise ValueError(
                "shore and midland blend windows overlap: "
                f"{self.shore_blend_m} > {self.lowland_top_m} - {self.midland_blend_m}"
            )
        if self.lowland_top_m + self.midland_blend_m > self.highland_base_m - self.highland_blend_m:
            raise ValueError(
                "midland and highland blend windows overlap: "
                f"{self.lowland_top_m} + {self.midland_blend_m} > "
                f"{self.highland_base_m} - {self.highland_blend_m}"
            )

    @staticmethod
    def from_config(cfg: BridgeConfig) -> "HeightCurve":
        return HeightCurve(
            ocean_meters_per_block=cfg.ocean_meters_per_block,
            lowland_meters_per_block=cfg.lowland_meters_per_block,
            midland_meters_per_block=cfg.midland_meters_per_block,
            highland_meters_per_block=cfg.highland_meters_per_block,
            lowland_top_m=cfg.lowland_top_m,
            highland_base_m=cfg.highland_base_m,
            shore_blend_m=cfg.shore_blend_m,
            midland_blend_m=cfg.midland_blend_m,
            highland_blend_m=cfg.highland_blend_m,
            sea_level=cfg.sea_level,
            world_height=cfg.world_height,
        )

    def rate(self, elev_m: np.ndarray) -> np.ndarray:
        """Metres per block at each elevation. Strictly positive."""
        elev_m = np.asarray(elev_m, dtype=np.float64)
        ocean = np.full_like(elev_m, self.ocean_meters_per_block)
        lowland = np.full_like(elev_m, self.lowland_meters_per_block)
        highland = np.full_like(elev_m, self.highland_meters_per_block)

        lo, hi = self.lowland_meters_per_block, self.midland_meters_per_block
        span = self.highland_base_m - self.lowland_top_m
        ramp = lo + (hi - lo) * (elev_m - self.lowland_top_m) / span
        # Clamped so the ramp's extrapolation below lowland_top_m cannot pull the
        # blended rate under the lowland rate.
        ramp = np.clip(ramp, min(lo, hi), max(lo, hi))

        m = _blend(ocean, lowland, elev_m, 0.0, self.shore_blend_m)
        m = _blend(m, ramp, elev_m, self.lowland_top_m, self.midland_blend_m)
        m = _blend(m, highland, elev_m, self.highland_base_m, self.highland_blend_m)
        return m

    def to_block_height_exact(self, elev_m: np.ndarray) -> np.ndarray:
        """Unquantised block height (float64), before floor and clamp."""
        elev_grid, block_grid = _curve_table(self)
        return np.interp(np.asarray(elev_m, dtype=np.float64), elev_grid, block_grid)

    def to_block_height(self, elev_m: np.ndarray) -> np.ndarray:
        blocks = np.floor(self.to_block_height_exact(elev_m))
        return np.clip(blocks, 0, self.world_height - 1).astype(np.int16)

    def to_elevation(self, block_height: np.ndarray) -> np.ndarray:
        """Inverse: block height back to elevation in metres.

        The table is strictly increasing, so this is well defined, but it is only
        a left inverse of `to_block_height` -- that one floors and clamps, so what
        comes back is the *bottom* of the block's elevation band, and a column
        clamped at 0 or `world_height - 1` is unrecoverable. For reading a tile
        cache back as a DEM (`hydrology/io.py`), not for round-tripping.
        """
        elev_grid, block_grid = _curve_table(self)
        return np.interp(np.asarray(block_height, dtype=np.float64), block_grid, elev_grid)


@lru_cache(maxsize=8)
def _curve_table(curve: HeightCurve) -> tuple[np.ndarray, np.ndarray]:
    """Integrate 1/m(e) onto a fixed grid. Memoized per distinct knob set."""
    elev = np.arange(_TABLE_MIN_M, _TABLE_MAX_M + _TABLE_STEP_M, _TABLE_STEP_M)
    inv_rate = 1.0 / curve.rate(elev)
    # Cumulative trapezoid, then anchored so elevation 0 lands exactly on sea level.
    blocks = np.concatenate(
        [[0.0], np.cumsum(0.5 * (inv_rate[1:] + inv_rate[:-1]) * _TABLE_STEP_M)]
    )
    blocks = blocks - np.interp(0.0, elev, blocks) + curve.sea_level

    if blocks[0] > 0.0 or blocks[-1] < curve.world_height - 1:
        raise ValueError(
            f"curve does not span the block column within +-{_TABLE_MAX_M:.0f} m "
            f"(spans y {blocks[0]:.1f}..{blocks[-1]:.1f}, need 0..{curve.world_height - 1}); "
            "np.interp would clamp an out-of-range elevation to an in-range block height"
        )
    return elev, blocks
