"""L1: streams and small lakes solved at native 30 m over canonical macro-tiles.

L0 (`basins.py`) answers where the continent's water goes, at 240 m. That is the right
resolution for a drainage network and the wrong one for a river: at 240 m a trunk stream
is one cell wide and a creek does not exist. L1 re-solves the same physics at the model's
native resolution over a 61 km window, and the only thing it takes from L0 is the one
thing a bounded window cannot know on its own -- how much water is already arriving at
its edge.

**The seam rule, unchanged from `basins.py` and for the same reason.** A macro-tile's
fetch window, halo included, is a pure function of its ID. Two windows over the same
ground accumulate differently, so a tile whose halo depended on its caller would put
rivers that stop at a border back into the world.

**What L1 adds over L0.**

  seeding      A tile's own window sees only the catchment inside it, so every river
               would appear to start at the tile edge with a trickle. `inflow_weights`
               reads L0's flow directions on the ring just outside the window and
               injects the upslope area crossing it as extra accumulation weight, which
               is the same operation `flow_accumulation` already does per cell.
  deferral     A basin wider than the halo fills to a level that depends on the window,
               so adjacent tiles would disagree and leave a stepped lake surface that
               `WaterSim` drains at runtime. Those basins take L0's level instead
               (plan section 7 item 6, sharpened by section 14.11 into an extent test).
  monotonicity The water surface is forced non-increasing downstream, so no carved
               channel ever asks water to climb. Step-downs are waterfalls.

**Where the elevation comes from.** `GET /terrain?scale=1`, exactly as L0 does, and for
the same reason: at native resolution upstream never adds its detail Perlin, so the
response *is* the smooth field plan section 4.3 wants to route on -- no `noise=0`
parameter, no upstream change (section 14.8). Unlike L0 there is no downsampling: an L1
cell is one native pixel.

Nothing here is imported by the running bridge service, and the upstream fetch is
injected as a callable, so the solve is testable without a GPU or a network.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from scipy import ndimage

from .basins import (
    L0,
    TERMINAL,
    D8_DX,
    D8_DY,
    ElevationSource,
    L0Cache,
    L0Geometry,
    L0Params,
    L0RegionId,
    L0Solution,
    fetch_grid,
    inflow_edges_from,
    receiver_directions,
)
from .depth import DEFAULT_RIVER_COEFF, DEFAULT_RIVER_EXP, water_depth
from .fill import ocean_mask
from .flats import DEFAULT_FLAT_EPSILON
from .flow import topological_levels

# Bumped when the *meaning* of a stored plane changes in a way the knobs below do not
# capture, so a namespace solved by an older shape is never read back as a newer one.
L1_SCHEMA_VERSION = 1

_MAGIC = b"SBL1"
_HEADER_BYTES = 12


# --------------------------------------------------------------------------- geometry


@dataclass(frozen=True)
class L1Geometry:
    """The canonical L1 window shape. One instance (`L1`) is used in production.

    Everything is stated in *native pixels*, never blocks, exactly as `L0Geometry` is
    and for the same reason: `scale` then cannot move a window, so a tile solved at one
    horizontal scale stays valid at another and never enters the cache fingerprint.

    Parameterised only so the solve can be exercised at a size a test can hold in closed
    form; every production caller takes the default.
    """

    native_resolution_m: float = 30.0

    # 2048 px = 61.44 km, and 4096 blocks at scale=2 -- plan section 7's starting size.
    # Also exactly 256 L0 cells, so an L0 region is 4x4 tiles with no ragged remainder;
    # `__post_init__` holds that alignment, which both the seeding and the deferral
    # index against.
    tile_native_px: int = 2048

    # 512 px = 15.36 km = 1024 blocks at scale=2, comfortably over plan section 7's
    # ">= 512 blocks". The size is not free: it sets the width above which a basin has
    # to be deferred to L0 (see `_defer_wide_basins`), so a bigger halo is strictly
    # more accurate, and it is also what makes the solve window a whole number of
    # square fetch blocks. 256 px would satisfy the plan's floor but give a 2560 px
    # window, which is not a multiple of 1024 and would force a smaller -- and per
    # section 14.3 markedly slower -- request shape.
    halo_native_px: int = 512

    # Side of one upstream fetch. Square, for the reason measured in plan section 14.3:
    # upstream generates in overlapping decoder windows, so throughput depends on the
    # *shape* of a request and not only its area, and a full-width strip runs 14x
    # slower than a square block of the same area. The solve window is 3x3 of these.
    fetch_native_px: int = 1024

    def __post_init__(self) -> None:
        if min(self.tile_native_px, self.fetch_native_px) < 1:
            raise ValueError("tile and fetch sizes must be >= 1")
        if self.halo_native_px < 0:
            raise ValueError("halo_native_px must be >= 0")
        if self.solve_native_px % self.fetch_native_px:
            raise ValueError(
                f"solve window of {self.solve_native_px} px is not a whole number of "
                f"{self.fetch_native_px}px blocks; a ragged final block would be a "
                "second request shape over the same ground"
            )

    def check_l0_alignment(self, l0: L0Geometry = L0) -> None:
        """L1 must tile L0's cells and L0's regions must tile L1's tiles.

        Not decoration. The seeding reads L0 cells covering the window edge and the
        deferral reads L0 lake levels under an L1 basin; both index one grid from the
        other, and a fractional ratio would silently truncate at every tile border --
        the one place this plan cannot afford to be approximate.
        """
        for name, px in (("tile", self.tile_native_px), ("halo", self.halo_native_px)):
            if px % l0.cell_native_px:
                raise ValueError(
                    f"L1 {name} of {px} native px is not a whole number of "
                    f"{l0.cell_native_px}px L0 cells"
                )
        if l0.region_native_px % self.tile_native_px:
            raise ValueError(
                f"an L0 region of {l0.region_native_px} native px is not a whole "
                f"number of {self.tile_native_px}px L1 tiles"
            )

    @property
    def cell_m(self) -> float:
        return self.native_resolution_m

    @property
    def solve_native_px(self) -> int:
        return self.tile_native_px + 2 * self.halo_native_px

    def tile_containing_native(self, native_i: int, native_j: int) -> tuple[int, int]:
        """Floor toward negative infinity, so tiles cover negative coordinates
        contiguously (a Java port must use Math.floorDiv -- see `bridge/tiling.py`)."""
        side = self.tile_native_px
        return native_i // side, native_j // side

    def tile_containing_block(self, block_x: int, block_z: int, scale: int) -> tuple[int, int]:
        return self.tile_containing_native(block_x // scale, block_z // scale)

    def tile_bounds_native(self, tile_i: int, tile_j: int) -> tuple[int, int, int, int]:
        """The ground a tile *owns*. Tiles cover it without overlap."""
        side = self.tile_native_px
        i1, j1 = tile_i * side, tile_j * side
        return i1, j1, i1 + side, j1 + side

    def solve_bounds_native(self, tile_i: int, tile_j: int) -> tuple[int, int, int, int]:
        """The canonical fetch window: owned ground plus halo. A pure function of the ID.

        This is the invariant the module exists to hold; everything else follows from it.
        """
        i1, j1, i2, j2 = self.tile_bounds_native(tile_i, tile_j)
        k = self.halo_native_px
        return i1 - k, j1 - k, i2 + k, j2 + k

    def tile_bounds_blocks(
        self, tile_i: int, tile_j: int, scale: int
    ) -> tuple[int, int, int, int]:
        i1, j1, i2, j2 = self.tile_bounds_native(tile_i, tile_j)
        return i1 * scale, j1 * scale, i2 * scale, j2 * scale

    def interior_slice(self) -> tuple[slice, slice]:
        k = self.halo_native_px
        return np.s_[k: k + self.tile_native_px, k: k + self.tile_native_px]

    def fingerprint_fields(self) -> str:
        return (
            f"{self.native_resolution_m}|{self.tile_native_px}|"
            f"{self.halo_native_px}|{self.fetch_native_px}"
        )


#: The shape production uses. Import this, do not build your own.
L1 = L1Geometry()


# Downstream hydraulic geometry for channel *width*, the horizontal counterpart of
# `depth.py`'s depth law: width grows as a power of discharge, and discharge scales with
# drainage area. 1.5 * sqrt(A) gives ~5 m at 10 km2, 15 m at 100 km2, 150 m at 10,000
# km2 and ~2.6 km at the Mississippi's 3 M km2 -- the right order at every scale, and
# tuned to look right rather than calibrated. At 15 m horizontally per block that is
# one block for a 100 km2 catchment, so anything smaller is a deliberate one-block
# brook rather than a measured width (plan section 7 item 4).
DEFAULT_WIDTH_COEFF = 1.5
DEFAULT_WIDTH_EXP = 0.5
DEFAULT_MIN_WIDTH_M = 15.0


@dataclass(frozen=True)
class L1Params:
    """Every knob that changes a stored tile. Hashed into the cache namespace.

    Like `L0Params`, deliberately excludes the Phase 5 elevation curve, `sea_level`,
    `meters_per_block` and `scale`. L1 is solved entirely in metres over a window
    defined in native pixels, so retuning the curve -- or reverting
    `TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK` per plan section 12.2 -- leaves every
    solved tile valid. The block snap is Phase 8's, downstream of everything here;
    see `L1Solution.water_surface`.
    """

    geometry: L1Geometry = field(default_factory=L1Geometry)

    # L0's knobs, carried because L1's output depends on them: the deferral (see
    # `_defer_wide_basins`) imports L0's lake level, and the seeding imports L0's
    # accumulation, so retuning `L0Params.max_raise_m` changes stored L1 tiles. Without
    # this, L0 would rotate its namespace and L1 would keep serving tiles solved against
    # the old one. Not a knob to set independently -- it must match the `L0Cache` behind
    # the mosaic, which `solve_tile` checks rather than assumes.
    l0_params: L0Params = field(default_factory=L0Params)

    # 10,000 cells = 9 km2 at 30 m. Roughly a channel every 3 km, which is a dense but
    # plausible temperate drainage density -- and the knob most likely to want retuning
    # once water is visible in-game, exactly as `L0Params.max_raise_m` is.
    river_threshold_cells: float = 10_000.0

    # Off, unlike L0, and measured rather than assumed. L0 needs a cap because a 245 km
    # window contains continental basins that fill to 644 m over 7.1 % of land (plan
    # section 13.8); a 92 km L1 window does not. On real terrain L1's uncapped lakes run
    # to a median depth of 4 m and a p95 of 18 m, with 3720 of 3720 narrower than the
    # halo -- and capping them costs the property that makes the whole surface sound: the
    # uncapped fill is monotone downstream by construction (`enforce_monotone_downstream`
    # finds nothing to repair), while a cap puts a lake up to 387 m below the river
    # leaving it. Basins genuinely too wide for the window take L0's capped level through
    # `_defer_wide_basins`, so the cap still applies exactly where it was needed.
    #
    # Kept as a knob because it is the honest way to bound a deep basin if one turns up,
    # and because it belongs in the fingerprint either way.
    max_raise_m: float | None = None

    river_coeff: float = DEFAULT_RIVER_COEFF
    river_exp: float = DEFAULT_RIVER_EXP
    width_coeff: float = DEFAULT_WIDTH_COEFF
    width_exp: float = DEFAULT_WIDTH_EXP
    min_width_m: float = DEFAULT_MIN_WIDTH_M
    min_lake_area_cells: int = 8
    min_lake_depth_m: float = 0.5
    flat_epsilon: float = DEFAULT_FLAT_EPSILON

    def fingerprint(self) -> str:
        raw = (
            f"v{L1_SCHEMA_VERSION}|{self.geometry.fingerprint_fields()}|"
            f"l0={self.l0_params.fingerprint()}|"
            f"{self.river_threshold_cells}|{self.max_raise_m}|"
            f"{self.river_coeff}|{self.river_exp}|"
            f"{self.width_coeff}|{self.width_exp}|{self.min_width_m}|"
            f"{self.min_lake_area_cells}|{self.min_lake_depth_m}|{self.flat_epsilon}"
        )
        return hashlib.sha1(raw.encode()).hexdigest()[:12]


@dataclass(frozen=True)
class L1TileId:
    seed: int
    tile_i: int
    tile_j: int

    def cache_key(self) -> str:
        return f"s{self.seed}_t{self.tile_i}_{self.tile_j}"


# -------------------------------------------------------------------------- L0 mosaic


class L0Mosaic:
    """L0's solved planes read as one continuous field, across region borders.

    An L1 tile at the edge of an L0 region has a halo that reaches into the next one, so
    the ring its seeding reads is not necessarily inside a single solution. Regions tile
    the plane without overlap and each cell has exactly one owner, so the stitch is
    unambiguous -- it just cannot be expressed as a slice of one array, which is why
    `basins.inflow_edges_from` takes bare planes.

    Solving a neighbouring region is minutes of GPU time, so this asks the cache first
    and only falls through to a solve when a tile genuinely straddles a border.
    """

    def __init__(self, cache: L0Cache, source: ElevationSource, seed: int):
        self._cache = cache
        self._source = source
        self._seed = seed
        self._geom = cache.params.geometry
        self._loaded: dict[tuple[int, int], L0Solution] = {}

    @property
    def geometry(self) -> L0Geometry:
        return self._geom

    @property
    def params(self):
        return self._cache.params

    @property
    def regions_used(self) -> list[tuple[int, int]]:
        return sorted(self._loaded)

    def region(self, region_i: int, region_j: int) -> L0Solution:
        key = (region_i, region_j)
        if key not in self._loaded:
            self._loaded[key] = self._cache.get_or_solve(
                L0RegionId(self._seed, region_i, region_j), self._source
            )
        return self._loaded[key]

    def window(self, gi1: int, gj1: int, gi2: int, gj2: int) -> dict[str, np.ndarray]:
        """Stitch `[gi1, gi2) x [gj1, gj2)` in *global* L0 cell coordinates.

        Returns `accumulation`, `flow_dir` and `lake_surface`, each `(gi2-gi1, gj2-gj1)`
        and indexed from the window's own origin.
        """
        n = self._geom.region_cells
        out = {
            "accumulation": np.zeros((gi2 - gi1, gj2 - gj1), dtype=np.float32),
            "flow_dir": np.full((gi2 - gi1, gj2 - gj1), -1, dtype=np.int8),
            "lake_surface": np.full((gi2 - gi1, gj2 - gj1), np.nan, dtype=np.float32),
        }
        for ri in range(gi1 // n, (gi2 - 1) // n + 1):
            for rj in range(gj1 // n, (gj2 - 1) // n + 1):
                sol = self.region(ri, rj)
                # Overlap of the request with what this region owns, globally...
                a1, b1 = max(gi1, ri * n), max(gj1, rj * n)
                a2, b2 = min(gi2, (ri + 1) * n), min(gj2, (rj + 1) * n)
                # ...read at the region's own indices, written at the window's.
                src = np.s_[a1 - ri * n: a2 - ri * n, b1 - rj * n: b2 - rj * n]
                dst = np.s_[a1 - gi1: a2 - gi1, b1 - gj1: b2 - gj1]
                out["accumulation"][dst] = sol.accumulation[src]
                out["flow_dir"][dst] = sol.flow_dir[src]
                out["lake_surface"][dst] = sol.lake_surface[src]
        return out


def inflow_weights(
    z: np.ndarray,
    bounds: tuple[int, int, int, int],
    mosaic: L0Mosaic,
    geometry: L1Geometry = L1,
) -> tuple[np.ndarray, dict]:
    """Per-cell accumulation weights for the solve window, seeded from L0.

    Every land cell contributes 1, so an unseeded solve counts upslope *cells* exactly
    as L0 does. On top of that, each L0 cell just outside the window whose D8 receiver
    lies inside it delivers its whole upslope area across the boundary they share --
    converted to L1 cells, since one L0 cell is `cell_native_px ** 2` of them.

    The area is spread evenly over the land cells of that shared boundary -- the whole
    row of the footprint facing the donor for a cardinal step, the single corner for a
    diagonal one -- rather than dropped on one pixel. Concentrating it would assert something L0 cannot support:
    below its own channel threshold an L0 cell is carrying hillslope drainage, not a
    river, and putting 57 km2 of it into one 30 m pixel invents a channel that then
    persists all the way across the halo. Spreading claims only what L0 actually said --
    this much water arrives along this edge -- and leaves L1's own routing to concentrate
    it, which is what the halo is for.
    """
    i1, j1, i2, j2 = bounds
    l0 = mosaic.geometry
    px = l0.cell_native_px
    if any(v % px for v in bounds):
        raise ValueError(f"solve window {bounds} is not aligned to {px}px L0 cells")

    weights = np.ones_like(z, dtype=np.float64)
    land = ~ocean_mask(z)

    # The window in global L0 cells, plus the one-cell ring the crossing is read from.
    gi1, gj1, gi2, gj2 = i1 // px, j1 // px, i2 // px, j2 // px
    win = mosaic.window(gi1 - 1, gj1 - 1, gi2 + 1, gj2 + 1)
    table = inflow_edges_from(
        win["flow_dir"], win["accumulation"], 1, 1, 1 + (gi2 - gi1), 1 + (gj2 - gj1)
    )

    def facing(step: int) -> slice:
        """The side of the receiving footprint that touches the donor cell."""
        if step > 0:
            return slice(0, 1)
        if step < 0:
            return slice(px - 1, px)
        return slice(0, px)

    per_l0_cell = float(px * px)
    seeded = 0
    dropped = 0
    delivered = 0.0
    for k in range(len(table)):
        # `table` indexes the ring array, which starts one L0 cell before the window,
        # so subtracting that one cell puts a target back in the window's own cells --
        # and multiplying by `px` puts it in the window's own native pixels.
        r0 = (int(table.target_row[k]) - 1) * px
        c0 = (int(table.target_col[k]) - 1) * px
        rows = facing(int(table.target_row[k]) - int(table.source_row[k]))
        cols = facing(int(table.target_col[k]) - int(table.source_col[k]))
        edge = np.s_[r0 + rows.start: r0 + rows.stop, c0 + cols.start: c0 + cols.stop]

        on_land = land[edge]
        n = int(on_land.sum())
        if n == 0:
            # The river arrives at open sea. Nothing to route; the flow is already home.
            dropped += 1
            continue
        area = float(table.accumulation[k]) * per_l0_cell
        weights[edge] += np.where(on_land, area / n, 0.0)
        delivered += area
        seeded += 1

    report = {
        "l0_inflow_edges": len(table),
        "seeded_edges": seeded,
        "dropped_to_sea": dropped,
        "offered_area_cells": float(table.accumulation.sum()) * per_l0_cell,
        "seeded_area_cells": delivered,
        "l0_regions_used": [list(r) for r in mosaic.regions_used],
    }
    return weights, report


# ---------------------------------------------------------------- surface constraints


def enforce_monotone_downstream(
    surface: np.ndarray,
    receivers: np.ndarray,
    land: np.ndarray,
    protect: np.ndarray | None = None,
) -> tuple[np.ndarray, dict]:
    """Force the water surface non-increasing along every D8 edge.

    Walks `flow.topological_levels` -- the same order, from the same graph, that the
    accumulation pass just used -- and lowers each receiver to the minimum of its
    contributors. Upstream-first is what makes one pass enough: a cell's own value is
    final before it is read.

    Lowering rather than raising is the safe direction. Raising a channel to meet its
    downstream neighbour would push water above banks that were never checked for it,
    which is the containment failure plan section 4.5 exists to prevent.

    `protect` (lakes) is an *anchor* in both directions: a lake is neither lowered nor
    allowed to lower anything below it. Both halves are load-bearing.

      Not lowered, because a lake surface is level by construction and tilting it would
      be worse than the violation being repaired.

      Not a constraint on what follows, because a lake held under its own spill -- which
      is what `max_raise_m` does -- is a fiction about the lake, not a claim about the
      river downstream. Letting it propagate drags the whole trunk down to the capped
      level: measured at 387 m over 91k cells on one real macro-tile, against nothing at
      all uncapped. So the rule this enforces is the one that is actually true, "no
      *river* may climb", and lakes are the boundary conditions rather than the sources.

    Note the input is the *filled* surface, not filled-plus-depth. A river's surface sits
    at grade and Phase 8 carves its bed below; that is what makes lowering safe at a
    confluence, where a shallow tributary meeting a deep trunk would otherwise drag the
    trunk's surface down by the difference in their depths. It is also why, with the
    default knobs, this pass finds nothing: the priority flood already leaves a surface
    that never rises downstream. It stays a pass rather than an assertion because
    `_defer_wide_basins` writes L0's levels in afterwards, and because `max_raise_m`
    exists.
    """
    orig = np.asarray(surface, dtype=np.float64)
    s = orig.ravel().copy()
    flat_orig = orig.ravel()
    prot = None if protect is None else protect.ravel()
    violated = np.zeros(s.shape, dtype=bool)

    for level in topological_levels(receivers, land):
        tgt = receivers[level]
        live = tgt >= 0
        if not live.any():
            continue
        src = level[live]
        t = tgt[live]
        vals = s[src] if prot is None else np.where(prot[src], np.inf, s[src])
        np.minimum.at(s, t, vals)
        if prot is not None:
            f = t[prot[t]]
            if f.size:
                violated[f[s[f] < flat_orig[f]]] = True
                s[f] = flat_orig[f]

    out = s.reshape(orig.shape)
    # `orig` may hold +inf for columns the caller wants excluded (see `solve_tile`'s
    # residual check), and inf - inf is a NaN, not a lowering.
    finite = np.isfinite(orig) & np.isfinite(out)
    drop = np.zeros(orig.shape, dtype=np.float64)
    drop[finite] = orig[finite] - out[finite]
    return out, {
        "cells_lowered": int(np.count_nonzero(drop > 0.0)),
        "max_lowering_m": float(drop.max()) if drop.size else 0.0,
        "protected_violations": int(violated.sum()),
    }


def _defer_wide_basins(
    lake_depth: np.ndarray,
    surface: np.ndarray,
    z: np.ndarray,
    land: np.ndarray,
    bounds: tuple[int, int, int, int],
    mosaic: L0Mosaic | None,
    geometry: L1Geometry,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, dict]:
    """Hand basins too wide for this window to L0, which solved them in one piece.

    Plan section 7 item 6: a basin whose spill point can fall outside a tile's halo fills
    to a level that is a property of the window rather than of the basin, so two adjacent
    tiles pick different levels and leave a stepped lake surface -- which `WaterSim`
    drains at runtime. Section 14.11 sharpened "large" from an area test to an extent
    test, and the extent that matters is the halo:

        a basin no wider than the halo is fully inside the window of *every* tile that
        owns any of it, however the tile borders happen to fall across it, because each
        window reaches a halo past its own ground on all four sides. One wider than the
        halo is not, for at least one of those tiles.

    So the test is the basin's own bounding box, not whether *this* window truncated it.
    That distinction is the whole point: the 247 km2 basin this was first run against sits
    entirely inside one tile's window -- which computes a perfectly good level for it --
    and is cut in half by its neighbour's. A window-local test would have let the two
    disagree, which is the failure being prevented. Either axis counts: a long ribbon is
    at risk along its length even if it is three cells across.

    L0's level replaces it, and the shoreline is then re-derived at 30 m from `z < level`
    so the lake keeps a native-resolution outline rather than L0's 240 m staircase. Both
    neighbouring tiles read the same L0 level, so they agree.

    If L0 has no lake there, the basin is dropped rather than invented: "large basins are
    resolved at L0 only" cuts both ways.

    The third return value is the set of cells to suppress from the output entirely. A
    basin the fill had drowned and L0's level leaves dry is not just shallower -- the flow
    graph still routes across it as the flat it used to be, toward a spill the water no
    longer reaches, so any channel drawn there is a fiction with a surface that climbs.
    Left in, it was 104k cells rising up to 149 m on one real macro-tile. Dry ground is
    the honest answer: the water it used to carry is in the lake.
    """
    wet = lake_depth > 0.0
    suppressed = np.zeros(wet.shape, dtype=bool)
    report = {"wide_basins": 0, "deferred_to_l0": 0, "dropped_no_l0_lake": 0,
              "suppressed_cells": 0}
    if not wet.any():
        return lake_depth, surface, suppressed, report

    labels, n = ndimage.label(wet, structure=np.ones((3, 3), dtype=int))
    if n == 0:
        return lake_depth, surface, suppressed, report

    index = np.arange(1, n + 1)
    boxes = ndimage.find_objects(labels)
    wide = np.array([
        max(b[0].stop - b[0].start, b[1].stop - b[1].start) >= geometry.halo_native_px
        for b in boxes
    ])
    report["wide_basins"] = int(wide.sum())
    if not wide.any():
        return lake_depth, surface, suppressed, report

    lake_depth = lake_depth.copy()
    surface = surface.copy()

    l0_level: np.ndarray | None = None
    if mosaic is not None:
        px = mosaic.geometry.cell_native_px
        i1, j1, i2, j2 = bounds
        win = mosaic.window(i1 // px, j1 // px, i2 // px, j2 // px)
        # Nearest-neighbour upsample: every native pixel takes its own L0 cell's level.
        l0_level = np.repeat(np.repeat(win["lake_surface"], px, axis=0), px, axis=1)

    for lab in index[wide]:
        blob = labels == lab
        level = np.nan
        if l0_level is not None:
            under = l0_level[blob]
            under = under[np.isfinite(under)]
            if under.size:
                # Median, not mean: a basin can overlap the margin of a neighbouring L0
                # lake, and one stray level must not shift the one that matters.
                level = float(np.median(under))
        if not np.isfinite(level):
            lake_depth[blob] = 0.0
            suppressed |= blob
            report["dropped_no_l0_lake"] += 1
            continue

        # Re-cut the shoreline at 30 m: the parts of `z < level` this basin connects to.
        below, m = ndimage.label((z < level) & land, structure=np.ones((3, 3), dtype=int))
        keep = np.unique(below[blob & (below > 0)])
        new = np.isin(below, keep[keep > 0]) if keep.size else np.zeros_like(blob)

        # Cells the old level covered and the new one does not are dry ground again, so
        # their surface goes back to grade. Leaving them at the old fill level would keep
        # a rim of water standing above the lake it used to belong to -- level in itself,
        # but touching the lake, so the two read as one body with a step in it.
        drained = blob & ~new
        lake_depth[drained] = 0.0
        surface[drained] = z[drained]
        suppressed |= drained
        lake_depth[new] = np.clip(level - z[new], 0.0, None)
        surface[new] = np.maximum(z[new], level)
        report["deferred_to_l0"] += 1

    report["suppressed_cells"] = int(suppressed.sum())
    return lake_depth, surface, suppressed, report


# ------------------------------------------------------------------------------ solve


def channel_width_m(
    accumulation: np.ndarray, params: L1Params, geometry: L1Geometry | None = None
) -> np.ndarray:
    """Channel width in metres from upslope cell count. Zero where there is no channel.

    Derived rather than stored, for `basins.py` section 14.7's reason: accumulation is
    the exact quantity and width is a pure function of it, so storing width would cost a
    plane to save an expression. Phase 8 clamps the result to whole blocks; it stays in
    metres here so nothing in L1 depends on the horizontal scale.
    """
    geom = geometry or params.geometry
    area_km2 = accumulation * (geom.cell_m / 1000.0) ** 2
    w = params.width_coeff * np.power(np.maximum(area_km2, 0.0), params.width_exp)
    return np.where(
        accumulation >= params.river_threshold_cells, np.maximum(w, params.min_width_m), 0.0
    )


@dataclass(frozen=True)
class L1Solution:
    """One solved macro-tile, cropped to the ground it owns.

    Every plane is `geometry.tile_native_px` square, in row-major (i, j) order matching
    `tile_bounds_native`: row indexes world i, column indexes world j.
    """

    tile: L1TileId
    elevation: np.ndarray      # float32 metres, the smooth native field
    water_surface: np.ndarray  # float32 metres, NaN where the column is dry
    accumulation: np.ndarray   # float32 upslope cell count, itself included
    flow_dir: np.ndarray       # int8 index into D8_DY / D8_DX, TERMINAL where none
    geometry: L1Geometry = field(default_factory=L1Geometry)
    report: dict = field(default_factory=dict, compare=False)

    def crossings(self, edge: str) -> np.ndarray:
        """Cells on `edge` ('i+', 'i-', 'j+', 'j-') whose flow leaves through it.

        The tile's own answer to "does this river cross here", rather than an inference
        from which way the ground slopes. Without it a seam audit has to assume every
        channel touching a border crosses it, and calls a river running *parallel* to
        the border a discontinuity -- which on real terrain is most of them.
        """
        row, sign = (0, -1) if edge in ("i-", "j-") else (-1, 1)
        axis = 0 if edge.startswith("i") else 1
        code = self.flow_dir[row, :] if axis == 0 else self.flow_dir[:, row]
        step = (D8_DY if axis == 0 else D8_DX)[np.where(code >= 0, code, 0)]
        return (code != TERMINAL) & (step == sign)

    @property
    def ocean(self) -> np.ndarray:
        return ocean_mask(self.elevation.astype(np.float64))

    @property
    def wet(self) -> np.ndarray:
        return np.isfinite(self.water_surface)

    @property
    def lake(self) -> np.ndarray:
        """Standing water: the surface is above the ground it covers."""
        return self.wet & (self.water_surface > self.elevation)

    def channels(self, params: L1Params) -> np.ndarray:
        return (self.accumulation >= params.river_threshold_cells) & ~self.ocean

    def width_m(self, params: L1Params) -> np.ndarray:
        return channel_width_m(self.accumulation, params, self.geometry)

    def catchment_km2(self) -> np.ndarray:
        return self.accumulation * (self.geometry.cell_m / 1000.0) ** 2

    def stats(self, params: L1Params) -> dict:
        ocean = self.ocean
        land = ~ocean
        n_land = int(land.sum())
        lake = self.lake & land
        ch = self.channels(params)
        w = self.width_m(params)
        return {
            "tile": self.tile.cache_key(),
            "cells_per_side": int(self.elevation.shape[0]),
            "cell_m": self.geometry.cell_m,
            "km_per_side": self.elevation.shape[0] * self.geometry.cell_m / 1000.0,
            "land_cells": n_land,
            "ocean_cells": int(ocean.sum()),
            "lake_cells": int(lake.sum()),
            "lake_land_fraction": (float(lake.sum()) / n_land) if n_land else 0.0,
            "channel_cells": int(ch.sum()),
            "channel_land_fraction": (float(ch.sum()) / n_land) if n_land else 0.0,
            "channel_km": float(ch.sum()) * self.geometry.cell_m / 1000.0,
            "max_width_m": float(w.max()) if w.size else 0.0,
            "max_catchment_km2": float(self.catchment_km2().max()) if n_land else 0.0,
            "elev_min_m": float(np.nanmin(self.elevation)),
            "elev_max_m": float(np.nanmax(self.elevation)),
        }


def solve_tile(
    tile: L1TileId,
    source: ElevationSource,
    mosaic: L0Mosaic | None = None,
    params: L1Params = L1Params(),
) -> L1Solution:
    """Fetch, solve, and crop one macro-tile.

    `mosaic` is optional so the solver runs standalone -- a tile with no L0 behind it is
    self-consistent, it simply believes every river starts at its own edge. Production
    always passes one.

    The crop is last, as in `basins.solve_region`: the halo has to be solved, not merely
    fetched, or it contributes nothing.
    """
    geom = params.geometry
    geom.check_l0_alignment(mosaic.geometry if mosaic is not None else L0)
    if mosaic is not None and mosaic.params != params.l0_params:
        # The tile is about to be cached under a fingerprint that claims one set of L0
        # knobs while being solved against another. Loud here, silently wrong later.
        raise ValueError(
            "the L0 cache behind this mosaic was built with different knobs than "
            "L1Params.l0_params declares; the L1 namespace would misdescribe its own "
            f"contents ({mosaic.params.fingerprint()} vs {params.l0_params.fingerprint()})"
        )
    bounds = geom.solve_bounds_native(tile.tile_i, tile.tile_j)
    z = fetch_grid(bounds, source, 1, geom.fetch_native_px)

    report: dict = {}
    weights = None
    if mosaic is not None:
        weights, seed_report = inflow_weights(z, bounds, mosaic, geom)
        report["seeding"] = seed_report

    wm = water_depth(
        z,
        horizontal_cell_size_m=geom.cell_m,
        epsilon=params.flat_epsilon,
        river_threshold=params.river_threshold_cells,
        river_coeff=params.river_coeff,
        river_exp=params.river_exp,
        min_lake_area=params.min_lake_area_cells,
        min_lake_depth=params.min_lake_depth_m,
        lake_max_raise=params.max_raise_m,
        weights=weights,
    )

    land = ~wm.ocean

    # The water surface is the filled ground, not the ground plus a depth: a lake's top
    # is where its basin fills to, and a river runs at grade with its bed carved below
    # (Phase 8). Stated this way the surface is already non-increasing downstream, so
    # with the default knobs this pass finds nothing and only earns its keep when
    # `max_raise_m` is set.
    #
    # It runs *before* the deferral, which is the only order that works. The deferral
    # rewrites one basin's surface, and the flow graph still describes the old one: every
    # cell in a filled basin routes across the flat toward its spill, so a cell the new
    # level leaves dry looks like a 300 m dip to a pass that trusts that graph, and the
    # dip propagates out of the basin and down the trunk. Afterwards the substituted
    # surface is level by construction and needs no repair; the residual check below says
    # so rather than assuming it.
    surface, mono_report = enforce_monotone_downstream(
        wm.filled, wm.receivers, land, protect=(wm.lake_depth > 0.0)
    )
    report["monotone"] = mono_report

    lake_depth, surface, suppressed, defer_report = _defer_wide_basins(
        wm.lake_depth, surface, z, land, bounds, mosaic, geom
    )
    report["deferral"] = defer_report

    lake = lake_depth > 0.0
    wet = (lake | (wm.accumulation >= params.river_threshold_cells)) & land & ~suppressed
    # `maximum` guards the one case the monotone pass can produce: with `max_raise_m` set
    # it may lower a river below its own bed, and water under the ground is worse than
    # the inversion it was repairing.
    emitted = np.maximum(surface, z)
    water_surface = np.where(wet, emitted, np.nan)

    # The invariant, checked on exactly the values stored rather than on the surface they
    # were derived from: no *river* column may sit above the one below it. Dry columns and
    # lakes are anchors -- a dry column carries no water to climb, and a lake is a boundary
    # condition (see `enforce_monotone_downstream`). Report-only; if it is ever non-zero
    # the fix belongs upstream of here, not in a second repair pass.
    _, residual = enforce_monotone_downstream(
        np.where(wet, emitted, np.inf), wm.receivers, land, protect=(~wet | lake)
    )
    report["monotone_residual"] = residual

    flow_dir = receiver_directions(wm.receivers, z.shape)

    interior = geom.interior_slice()
    return L1Solution(
        tile=tile,
        elevation=z[interior].astype(np.float32),
        water_surface=water_surface[interior].astype(np.float32),
        accumulation=wm.accumulation[interior].astype(np.float32),
        flow_dir=flow_dir[interior],
        geometry=geom,
        report=report,
    )


# ------------------------------------------------------------------------------ cache


def encode(solution: L1Solution) -> bytes:
    """Serialise a solution. Byte-identical for equal inputs -- no container, no
    timestamps, fixed dtypes and fixed byte order -- which is what the determinism
    check is measured against."""
    n = solution.elevation.shape[0]
    header = _MAGIC + np.array([L1_SCHEMA_VERSION, n], dtype="<u2").tobytes() + b"\0\0\0\0"
    assert len(header) == _HEADER_BYTES
    return (
        header
        + solution.elevation.astype("<f4").tobytes()
        + solution.water_surface.astype("<f4").tobytes()
        + solution.accumulation.astype("<f4").tobytes()
        + solution.flow_dir.astype("<i1").tobytes()
    )


def decode(tile: L1TileId, data: bytes, geometry: L1Geometry = L1) -> L1Solution:
    if data[:4] != _MAGIC:
        raise ValueError(f"not an L1 tile payload (magic {data[:4]!r})")
    version, n = (int(v) for v in np.frombuffer(data[4:8], dtype="<u2"))
    if version != L1_SCHEMA_VERSION:
        raise ValueError(f"L1 payload schema v{version}, this build reads v{L1_SCHEMA_VERSION}")
    if n != geometry.tile_native_px:
        raise ValueError(f"L1 payload is {n} cells/side, geometry says {geometry.tile_native_px}")

    body = data[_HEADER_BYTES:]
    plane = n * n * 4
    expected = 3 * plane + n * n
    if len(body) != expected:
        raise ValueError(f"L1 payload for {n}x{n} is {len(body)} bytes, expected {expected}")

    def f32(k: int) -> np.ndarray:
        return np.frombuffer(body[k * plane: (k + 1) * plane], dtype="<f4").reshape(n, n).copy()

    return L1Solution(
        tile=tile,
        elevation=f32(0),
        water_surface=f32(1),
        accumulation=f32(2),
        flow_dir=np.frombuffer(body[3 * plane:], dtype="<i1").reshape(n, n).copy(),
        geometry=geometry,
    )


class L1Cache:
    """Tiles on disk, namespaced by the knobs that produced them.

    An LRU where `L0Cache` is not, and the difference is the cost ratio: an L0 region is
    minutes of GPU time over 60,400 km2, an L1 tile is tens of seconds over 3,800, and
    there are 16 tiles to a region. Eviction is left to the caller for now -- nothing in
    this phase generates enough tiles to need it, and guessing a policy before there is a
    consumer to observe would be inventing a requirement.
    """

    def __init__(self, cache_dir: str | Path, params: L1Params = L1Params()):
        self._root = Path(cache_dir) / "l1" / params.fingerprint()
        self._root.mkdir(parents=True, exist_ok=True)
        self._params = params

    @property
    def root(self) -> Path:
        return self._root

    @property
    def params(self) -> L1Params:
        return self._params

    def path(self, tile: L1TileId) -> Path:
        return self._root / f"{tile.cache_key()}.l1"

    def get(self, tile: L1TileId) -> L1Solution | None:
        try:
            data = self.path(tile).read_bytes()
        except FileNotFoundError:
            return None
        return decode(tile, data, self._params.geometry)

    def put(self, solution: L1Solution) -> Path:
        path = self.path(solution.tile)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(encode(solution))
        tmp.replace(path)  # atomic on the same filesystem: never a torn half-tile
        return path

    def get_or_solve(
        self, tile: L1TileId, source: ElevationSource, mosaic: L0Mosaic | None = None
    ) -> L1Solution:
        cached = self.get(tile)
        if cached is not None:
            return cached
        solution = solve_tile(tile, source, mosaic, self._params)
        self.put(solution)
        return solution
