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

Curve v2 (2026-11-19) adds a *shelf* band below sea level: a finer rate from the
shore down to `shelf_base_m`, blended (same finite-support smoothstep) into the
deep-ocean rate below it, so coastal seas are tens of blocks deep with a visible
floor instead of 2-5 blocks of flat sand. `shelf_meters_per_block == 0` is the v1
curve, bit for bit (saved v1 worlds keep their curve: no seams).

The game owns the knobs (`TerrainScale` in stonebreak-game, sent in the service
handshake as a `WorldConfig`); the model only evaluates the curve.
"""
from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache

import numpy as np


# The integration grid. Wide enough that both endpoints already fall outside
# the block column (asserted in _curve_table, which widens it for deep columns),
# so np.interp's edge clamping can never invent an in-range block height for an
# out-of-range elevation.
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
    #: Bottom of the block column (inclusive); heights clamp to [min_y, world_height - 1].
    min_y: int = 0
    #: Shelf band (curve v2): metres per block from the shore blend down to `shelf_base_m`. 0 = no shelf band
    #: (the v1 curve: the ocean rate runs right up to the shore blend).
    shelf_meters_per_block: float = 0.0
    #: Centre of the shelf -> ocean rate blend (metres, below sea level), and its half-width.
    shelf_base_m: float = -200.0
    shelf_blend_m: float = 100.0
    #: Shallows band (curve v2, round 2): an even finer rate for the first metres below the waterline, blended into the
    #: shelf rate around `shallow_base_m` (+- `shallow_blend_m`). 0 = none. Needs a shelf band and a narrow shore blend.
    shallow_meters_per_block: float = 0.0
    shallow_base_m: float = -16.0
    shallow_blend_m: float = 8.0

    def __post_init__(self) -> None:
        rates = (
            self.ocean_meters_per_block,
            self.lowland_meters_per_block,
            self.midland_meters_per_block,
            self.highland_meters_per_block,
        )
        if not all(r > 0.0 for r in rates):
            raise ValueError(f"all metres-per-block rates must be > 0, got {rates}")
        if self.shelf_meters_per_block < 0.0:
            raise ValueError(f"shelf_meters_per_block must be >= 0 (0 = no shelf band), got {self.shelf_meters_per_block}")
        if self.has_shelf:
            if not self.shelf_blend_m > 0.0:
                raise ValueError(f"shelf_blend_m must be > 0, got {self.shelf_blend_m}")
            # The shelf -> ocean blend must end before the shore blend starts (same rule as the land bands).
            if self.shelf_base_m + self.shelf_blend_m > -self.shore_blend_m:
                raise ValueError(
                    "shelf and shore blend windows overlap: "
                    f"{self.shelf_base_m} + {self.shelf_blend_m} > -{self.shore_blend_m}"
                )
        if self.shallow_meters_per_block < 0.0:
            raise ValueError(f"shallow_meters_per_block must be >= 0, got {self.shallow_meters_per_block}")
        if self.has_shallows:
            if not self.has_shelf:
                raise ValueError("a shallows band needs a shelf band")
            if not self.shallow_blend_m > 0.0:
                raise ValueError(f"shallow_blend_m must be > 0, got {self.shallow_blend_m}")
            if self.shallow_base_m - self.shallow_blend_m < self.shelf_base_m + self.shelf_blend_m:
                raise ValueError("shallows and shelf blend windows overlap: "
                                 f"{self.shallow_base_m} - {self.shallow_blend_m} < "
                                 f"{self.shelf_base_m} + {self.shelf_blend_m}")
            if self.shallow_base_m + self.shallow_blend_m > -self.shore_blend_m:
                raise ValueError("shallows and shore blend windows overlap: "
                                 f"{self.shallow_base_m} + {self.shallow_blend_m} > -{self.shore_blend_m}")
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
    def from_world(w) -> "HeightCurve":
        """The curve of a `world_config.WorldConfig`."""
        return HeightCurve(
            ocean_meters_per_block=w.ocean_m_per_block,
            lowland_meters_per_block=w.lowland_m_per_block,
            midland_meters_per_block=w.midland_m_per_block,
            highland_meters_per_block=w.highland_m_per_block,
            lowland_top_m=w.lowland_top_m,
            highland_base_m=w.highland_base_m,
            shore_blend_m=w.shore_blend_m,
            midland_blend_m=w.midland_blend_m,
            highland_blend_m=w.highland_blend_m,
            sea_level=w.sea_level,
            world_height=w.world_height,
            min_y=getattr(w, "min_y", 0),
            shelf_meters_per_block=getattr(w, "shelf_m_per_block", 0.0),
            shelf_base_m=getattr(w, "shelf_base_m", -200.0),
            shelf_blend_m=getattr(w, "shelf_blend_m", 100.0),
            shallow_meters_per_block=getattr(w, "shallow_m_per_block", 0.0),
            shallow_base_m=getattr(w, "shallow_base_m", -16.0),
            shallow_blend_m=getattr(w, "shallow_blend_m", 8.0),
        )

    @property
    def has_shallows(self) -> bool:
        return self.shallow_meters_per_block > 0.0

    @property
    def has_shelf(self) -> bool:
        """True for a v2 curve (a shelf band below sea level)."""
        return self.shelf_meters_per_block > 0.0

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

        if self.has_shelf:
            shelf = np.full_like(elev_m, self.shelf_meters_per_block)
            ocean = _blend(ocean, shelf, elev_m, self.shelf_base_m, self.shelf_blend_m)
        if self.has_shallows:
            shallow = np.full_like(elev_m, self.shallow_meters_per_block)
            ocean = _blend(ocean, shallow, elev_m, self.shallow_base_m, self.shallow_blend_m)
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
        return np.clip(blocks, self.min_y, self.world_height - 1).astype(np.int16)

    def to_elevation(self, block_height: np.ndarray) -> np.ndarray:
        """Inverse: block height back to elevation in metres.

        The table is strictly increasing, so this is well defined, but it is only
        a left inverse of `to_block_height` -- that one floors and clamps, so what
        comes back is the *bottom* of the block's elevation band, and a column
        clamped at `min_y` or `world_height - 1` is unrecoverable. For reading a tile
        cache back as a DEM or building test fixtures, not for round-tripping.
        """
        elev_grid, block_grid = _curve_table(self)
        return np.interp(np.asarray(block_height, dtype=np.float64), block_grid, elev_grid)


def _integrate(curve: HeightCurve, lo_m: float, hi_m: float) -> tuple[np.ndarray, np.ndarray]:
    elev = np.arange(lo_m, hi_m + _TABLE_STEP_M, _TABLE_STEP_M)
    inv_rate = 1.0 / curve.rate(elev)
    # Cumulative trapezoid, then anchored so elevation 0 lands exactly on sea level.
    blocks = np.concatenate(
        [[0.0], np.cumsum(0.5 * (inv_rate[1:] + inv_rate[:-1]) * _TABLE_STEP_M)]
    )
    return elev, blocks - np.interp(0.0, elev, blocks) + curve.sea_level


@lru_cache(maxsize=8)
def _curve_table(curve: HeightCurve) -> tuple[np.ndarray, np.ndarray]:
    """Integrate 1/m(e) onto a fixed grid. Memoized per distinct knob set.

    The grid is +-20 km (bit-identical tables for every column that fits in it); a deeper or taller column
    (the cubic world's min_y -384) widens the side that falls short, doubling up to +-320 km."""
    lo_m, hi_m = _TABLE_MIN_M, _TABLE_MAX_M
    elev, blocks = _integrate(curve, lo_m, hi_m)
    while (blocks[0] > curve.min_y or blocks[-1] < curve.world_height - 1) and hi_m - lo_m < 640000.0:
        if blocks[0] > curve.min_y:
            lo_m *= 2.0
        if blocks[-1] < curve.world_height - 1:
            hi_m *= 2.0
        elev, blocks = _integrate(curve, lo_m, hi_m)

    if blocks[0] > curve.min_y or blocks[-1] < curve.world_height - 1:
        raise ValueError(
            f"curve does not span the block column within {lo_m:.0f}..{hi_m:.0f} m "
            f"(spans y {blocks[0]:.1f}..{blocks[-1]:.1f}, need {curve.min_y}..{curve.world_height - 1}); "
            "np.interp would clamp an out-of-range elevation to an in-range block height"
        )
    return elev, blocks
