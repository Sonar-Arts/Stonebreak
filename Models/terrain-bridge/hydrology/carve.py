"""L2: the block-resolution carve, and the containment invariant that makes it safe.

L1 (`local.py`) answers *where* the water is and *what elevation its surface sits at*,
in metres, over a 61 km window at the model's native 30 m. This module turns that into
the two planes a chunk generator can use: a block height with the channel cut into it,
and a per-column water level in blocks.

**The one rule this module exists to hold** (plan section 4.5):

    for every column with water level L, each 4-neighbour must satisfy
    terrain >= L, or be wet itself.

Worldgen water in Stonebreak is a *source* block by definition -- `ChunkWaterLayer`'s
contract is "block == WATER, no entry => source (level 0)", so ocean chunks cost zero
bytes and there is no seeding pass. A column of water with a dry neighbour below it is
therefore not a cosmetic artifact: it is a permanent spring that `WaterSim` propagates
outward across every chunk that loads. Worse, `WaterSim.onChunkLoaded` -> `isExposed`
deliberately stops at the chunk boundary, so a leak that lands on a chunk seam is not
detected at load at all and waits for an unrelated block change to wake it. The repair
pass below is load-bearing rather than belt-and-braces.

**Why a per-tile check is sufficient.** Terrain tiles are generated independently, so
the invariant has to hold across their seams too, and nothing here ever sees two tiles
at once. It holds because every quantity the carve reads is either

  * L1 data, which is a pure function of the L1 tile ID (plan section 4.2), or
  * the column's own canonical elevation, which no other tile computes,

and because the repair is a radius-1 function of *water levels only* -- never of a
neighbour's terrain. So a column's carved height and water level are the same however
the terrain-tile borders happen to fall across it, and a wet column's dry neighbour is
raised by whichever tile owns that neighbour, using water levels it can read for
itself. `tests/test_carve.py` measures that on adjacent tiles rather than trusting it.

**Why the bed is carved in metres and quantised afterwards.** The elevation curve is
non-linear (~4 m/block in lowlands, ~24 in highlands), so a carve expressed in blocks
would mean a different incision at different altitudes. Carving `min(elev, bed)` in
metres and mapping once keeps the depth law physical. It also means nothing in this
module's *geometry* depends on the Phase 5 curve -- only the final quantisation does.
"""
from __future__ import annotations

import hashlib
from collections import OrderedDict
from dataclasses import dataclass

import numpy as np
from scipy import ndimage

from bridge.height_mapping import HeightCurve

from .basins import ElevationSource
from .local import L0Mosaic, L1, L1Cache, L1Geometry, L1Params, L1TileId

# Bumped when the *shape* of the carve changes in a way the knobs below do not capture.
CARVE_SCHEMA_VERSION = 1


@dataclass(frozen=True)
class CarveParams:
    """Every knob that changes a carved tile. Hashed into the terrain cache namespace.

    Unlike `L0Params` and `L1Params` this one is *not* curve-independent: it is the
    layer where metres become blocks. It does not carry the curve knobs itself, though
    -- `bridge/cache.py` already fingerprints those, and duplicating them here would
    give two places to forget one.
    """

    # How far outside the tile the channel geometry is resolved. The carve reads the
    # nearest channel within this distance, so it must exceed the widest half-channel
    # in the world or a river entering from outside would appear to start at the tile
    # border. 48 blocks is 720 m at 1:15 against a widest measured channel of ~75 m
    # (L1's `1.5 * sqrt(area_km2)` at the largest catchment a region contains), so it
    # is generous by an order of magnitude and costs one extra L1 window read.
    bank_margin_blocks: int = 48

    # A wet column must have somewhere to put its water. The block quantisation can
    # otherwise round a shallow brook -- the depth law gives 0.84 m at the L1 channel
    # threshold, a fifth of a lowland block -- onto the same block as its own surface,
    # emitting a water level with no room under it. One block is plan section 7 item
    # 4's "deliberate 1-block brook for gameplay", made the floor rather than the
    # typical case.
    min_water_blocks: int = 1

    def __post_init__(self) -> None:
        if self.bank_margin_blocks < 1:
            raise ValueError("bank_margin_blocks must be >= 1: the repair reads a 1-block ring")
        if self.min_water_blocks < 1:
            raise ValueError("min_water_blocks must be >= 1, or a wet column holds no water")

    def fingerprint_fields(self) -> str:
        return f"v{CARVE_SCHEMA_VERSION}|{self.bank_margin_blocks}|{self.min_water_blocks}"


#: The shape production uses. Import this, do not build your own.
CARVE = CarveParams()


# ------------------------------------------------------------------------------ mosaic


class L1Mosaic:
    """L1's solved tiles read as one continuous field, across tile borders.

    The same stitch `L0Mosaic` does one level up, and needed for the same reason: a
    terrain tile near an L1 border has a bank margin that reaches into the next tile.
    Tiles cover the plane without overlap and each native pixel has exactly one owner,
    so the stitch is unambiguous.

    Bounded, unlike `L0Mosaic`, because this one lives in the request path. A solved L1
    tile is 54.5 MB and one serves 256 terrain tiles, so the working set is small and
    spatially coherent -- but a bridge that runs for hours would otherwise hold every
    tile a player has ever walked across. Only the two planes the carve reads are kept
    resident; `elevation` and `flow_dir` are dropped at load.
    """

    #: Resident tiles. Four covers a terrain tile straddling an L1 corner, with slack.
    DEFAULT_RESIDENT_TILES = 4

    def __init__(
        self,
        cache: L1Cache,
        source: ElevationSource,
        seed: int,
        l0: L0Mosaic | None = None,
        resident_tiles: int = DEFAULT_RESIDENT_TILES,
    ):
        self._cache = cache
        self._source = source
        self._seed = seed
        self._l0 = l0
        self._geom = cache.params.geometry
        self._resident = max(1, resident_tiles)
        self._loaded: OrderedDict[tuple[int, int], dict[str, np.ndarray]] = OrderedDict()

    @property
    def geometry(self) -> L1Geometry:
        return self._geom

    @property
    def params(self) -> L1Params:
        return self._cache.params

    @property
    def tiles_resident(self) -> list[tuple[int, int]]:
        return sorted(self._loaded)

    def tile(self, tile_i: int, tile_j: int) -> dict[str, np.ndarray]:
        key = (tile_i, tile_j)
        cached = self._loaded.get(key)
        if cached is not None:
            self._loaded.move_to_end(key)
            return cached
        solution = self._cache.get_or_solve(
            L1TileId(self._seed, tile_i, tile_j), self._source, self._l0
        )
        planes = {
            "water_surface": solution.water_surface,
            "accumulation": solution.accumulation,
        }
        self._loaded[key] = planes
        while len(self._loaded) > self._resident:
            self._loaded.popitem(last=False)
        return planes

    def window(self, i1: int, j1: int, i2: int, j2: int) -> dict[str, np.ndarray]:
        """Stitch `[i1, i2) x [j1, j2)` in *global native pixels*.

        Returns `water_surface` and `accumulation`, each `(i2-i1, j2-j1)` and indexed
        from the window's own origin.
        """
        n = self._geom.tile_native_px
        out = {
            "water_surface": np.full((i2 - i1, j2 - j1), np.nan, dtype=np.float32),
            "accumulation": np.zeros((i2 - i1, j2 - j1), dtype=np.float32),
        }
        for ti in range(_floordiv(i1, n), _floordiv(i2 - 1, n) + 1):
            for tj in range(_floordiv(j1, n), _floordiv(j2 - 1, n) + 1):
                planes = self.tile(ti, tj)
                # Overlap of the request with what this tile owns, globally...
                a1, b1 = max(i1, ti * n), max(j1, tj * n)
                a2, b2 = min(i2, (ti + 1) * n), min(j2, (tj + 1) * n)
                # ...read at the tile's own indices, written at the window's.
                src = np.s_[a1 - ti * n: a2 - ti * n, b1 - tj * n: b2 - tj * n]
                dst = np.s_[a1 - i1: a2 - i1, b1 - j1: b2 - j1]
                for name in out:
                    out[name][dst] = planes[name][src]
        return out


def _floordiv(a: int, b: int) -> int:
    """Floor toward negative infinity. Python's `//` already does this for ints; named
    so a Java port reads as `Math.floorDiv` rather than `/` (see `bridge/tiling.py`)."""
    return a // b


# -------------------------------------------------------------------- channel geometry


@dataclass(frozen=True)
class ChannelField:
    """Channel geometry over one window, already at block resolution.

    `bed_m` is NaN outside the carve footprint -- the set of columns close enough to a
    channel for the falloff to reach them -- rather than 0, so "do not carve here" and
    "carve to sea level here" cannot be confused.
    """

    surface_m: np.ndarray   # water surface in metres, NaN where the column is dry
    bed_m: np.ndarray       # bed target in metres, NaN outside the carve footprint
    channel: np.ndarray     # bool, cells L1 calls a channel *and* emitted water for


def channel_field(
    water_surface: np.ndarray,
    accumulation: np.ndarray,
    *,
    l1_params: L1Params,
    horizontal_meters_per_block: float,
    geometry: L1Geometry = L1,
) -> ChannelField:
    """Bed elevation for a cosine bank, from L1's surface and accumulation planes.

    Both inputs are already at block resolution (see `_upsample`); the *areas* they
    describe are still in L1 cells, which is why `geometry` is L1's rather than the
    block grid's -- a drainage area does not change because it is being drawn at twice
    the resolution.

    The bed is a cosine rather than a flat trench:

        bed = surface - depth * 0.5 * (1 + cos(pi * d / halfwidth))

    which is 1 at the centreline, 0 at the bank, and has zero slope at both, so the
    channel meets the surrounding ground tangentially instead of at a vertical wall
    that quantises into a staircase. `d` is the distance to the *nearest* channel cell
    and the width and depth are that cell's, so a confluence widens smoothly instead of
    taking the near bank's numbers across the whole junction.
    """
    channel = (accumulation >= l1_params.river_threshold_cells) & np.isfinite(water_surface)
    bed = np.full(water_surface.shape, np.nan, dtype=np.float64)
    if not channel.any():
        return ChannelField(surface_m=water_surface, bed_m=bed, channel=channel)

    area_km2 = np.maximum(accumulation, 0.0) * (geometry.cell_m / 1000.0) ** 2
    width_m = np.maximum(
        l1_params.width_coeff * np.power(area_km2, l1_params.width_exp), l1_params.min_width_m
    )
    depth_m = l1_params.river_coeff * np.power(area_km2, l1_params.river_exp)

    # Distance to the nearest channel cell, in blocks, with that cell's own index so
    # its width, depth and surface travel with it across the bank.
    distance, indices = ndimage.distance_transform_edt(~channel, return_indices=True)
    near = (indices[0], indices[1])

    # Half a block, so a channel cell always carves itself even where the width law
    # returns less than one block -- that is the brook case, not a no-op.
    half_width = np.maximum(width_m[near] / 2.0 / horizontal_meters_per_block, 0.5)
    t = distance / half_width
    inside = t <= 1.0
    falloff = 0.5 * (1.0 + np.cos(np.pi * np.clip(t, 0.0, 1.0)))

    surface_near = water_surface[near].astype(np.float64)
    bed[inside] = (surface_near - depth_m[near] * falloff)[inside]
    return ChannelField(surface_m=water_surface, bed_m=bed, channel=channel)


def _upsample(a: np.ndarray, factor: int) -> np.ndarray:
    """Nearest-neighbour, deliberately, not bilinear.

    Bilinear over a window reads a neighbour outside it, so the same ground would
    interpolate differently depending on which window asked -- exactly the seam this
    plan spends its whole design budget avoiding. Nearest is window-independent by
    construction, and the fields being upsampled are a water *surface* (level over a
    lake, and changing by ~1 m per 30 m cell along a river) and an *area*, neither of
    which carries detail below the L1 cell for interpolation to recover.
    """
    if factor == 1:
        return a
    return np.repeat(np.repeat(a, factor, axis=0), factor, axis=1)


# ------------------------------------------------------------------------------- carve


@dataclass(frozen=True)
class CarveResult:
    """One terrain tile's carved heights and water levels, cropped to the tile."""

    block_heights: np.ndarray  # int16 blocks, the channel cut in
    water_levels: np.ndarray   # int16 blocks; -1 means "this column holds no water"
    report: dict

    @property
    def wet(self) -> np.ndarray:
        return self.water_levels >= 0


def carve_tile(
    bounds_blocks: tuple[int, int, int, int],
    elevation_m: np.ndarray,
    *,
    curve: HeightCurve,
    scale: int,
    sea_level: int,
    mosaic: L1Mosaic,
    horizontal_meters_per_block: float,
    params: CarveParams = CARVE,
) -> CarveResult:
    """Carve one terrain tile and derive its water levels.

    `elevation_m` is the tile's own canonical elevation in metres -- the *post*-noise
    field the bridge already fetches -- in row-major (i, j) order matching
    `bridge/tiling.py`'s `tile_bounds`, i.e. row indexes world X and column world Z.
    Routing ran on the smooth field one level up (plan section 4.3); the carve applies
    here, to the field the player actually stands on, which is the whole point of the
    split.
    """
    i1, j1, i2, j2 = bounds_blocks
    h, w = elevation_m.shape
    if (i2 - i1, j2 - j1) != (h, w):
        raise ValueError(f"bounds {bounds_blocks} do not match elevation shape {(h, w)}")

    pad = params.bank_margin_blocks
    surface_pad, accumulation_pad = _padded_l1_planes(bounds_blocks, pad, mosaic, scale)

    field = channel_field(
        surface_pad,
        accumulation_pad,
        l1_params=mosaic.params,
        horizontal_meters_per_block=horizontal_meters_per_block,
        geometry=mosaic.geometry,
    )

    own = np.s_[pad: pad + h, pad: pad + w]
    bed = field.bed_m[own]
    surface = field.surface_m[own]

    elev = np.asarray(elevation_m, dtype=np.float64)
    carved = np.where(np.isfinite(bed), np.minimum(elev, bed), elev)

    heights = curve.to_block_height(carved).astype(np.int32)

    # The water level a column *would* hold, from L1's surface. Computed on the padded
    # window because the repair below needs the one-block ring outside the tile, and it
    # is a pure function of L1 data there -- no terrain from a neighbouring tile is
    # read, which is what keeps the repair seam-safe.
    level_pad = _surface_to_level(field.surface_m, curve, params.min_water_blocks)
    level = level_pad[own]
    offered = level >= 0
    channel = field.channel[own]

    # A *channel* must have room under its surface, always: the depth law returns 0.84 m
    # at L1's threshold, a fifth of a lowland block, so quantisation would otherwise erase
    # the smallest streams entirely and leave a river that stops and restarts. The cosine
    # bed has already cut the notch; this only takes the last fraction of a block.
    heights = np.where(
        channel & offered, np.minimum(heights, level - params.min_water_blocks), heights
    )
    heights = np.maximum(heights, 1)

    # A *lake* column the ground still stands above is an island, not a bed to excavate.
    # L1 routes on the smooth field and guarantees its lake surfaces clear the ground
    # there; the field carved here is the post-noise one, up to ~14 m taller in places
    # (plan section 13.6). Flattening those bumps to one block under the surface would
    # trade every lake island in the world for a shelf, and containment does not need it
    # -- an island is dry ground at or above the level beside it, which is a wall.
    island = offered & ~channel & (heights >= level)
    wet = offered & ~island

    water = np.full(heights.shape, -1, dtype=np.int32)
    # The sea is one case of a per-column water surface, not a separate rule: this is
    # exactly what `y < SEA_LEVEL` did before, restated per column. Strictly below, so
    # a column whose top block *is* sea level stays dry, as it was.
    water[heights < sea_level] = sea_level
    water[wet] = np.maximum(water[wet], level[wet])

    heights, repair = _repair_containment(heights, water, level_pad, pad, params)

    report = {
        "wet_columns": int((water >= 0).sum()),
        "inland_wet_columns": int(wet.sum()),
        "island_columns": int(island.sum()),
        "channel_columns": int(channel.sum()),
        "carved_columns": int(np.count_nonzero(carved < elev)),
        "max_carve_m": float((elev - carved).max()) if elev.size else 0.0,
        "l1_tiles_resident": len(mosaic.tiles_resident),
        **repair,
    }
    return CarveResult(
        block_heights=heights.astype(np.int16),
        water_levels=water.astype(np.int16),
        report=report,
    )


def _padded_l1_planes(
    bounds_blocks: tuple[int, int, int, int], pad: int, mosaic: L1Mosaic, scale: int
) -> tuple[np.ndarray, np.ndarray]:
    """L1's two planes over the tile plus `pad` blocks, upsampled to block resolution.

    The native window is snapped outward to whole native pixels and the surplus cropped
    afterwards, so the result depends only on the block window asked for -- a tile whose
    bounds do not divide by `scale` still reads the same values as its neighbour over
    the ground they share.
    """
    i1, j1, i2, j2 = bounds_blocks
    pi1, pj1, pi2, pj2 = i1 - pad, j1 - pad, i2 + pad, j2 + pad
    ni1, nj1 = _floordiv(pi1, scale), _floordiv(pj1, scale)
    ni2, nj2 = -_floordiv(-pi2, scale), -_floordiv(-pj2, scale)

    planes = mosaic.window(ni1, nj1, ni2, nj2)
    di, dj = pi1 - ni1 * scale, pj1 - nj1 * scale
    crop = np.s_[di: di + (pi2 - pi1), dj: dj + (pj2 - pj1)]
    return (
        _upsample(planes["water_surface"], scale)[crop],
        _upsample(planes["accumulation"], scale)[crop],
    )


def _surface_to_level(
    surface_m: np.ndarray, curve: HeightCurve, min_water_blocks: int
) -> np.ndarray:
    """Water surface in metres -> water level in blocks, `-1` where the column is dry.

    A level too close to the bottom of the world to leave `min_water_blocks` under it is
    reported dry rather than emitted with no room in it. Unreachable on any real world --
    inland water sits at or above sea level, 320 blocks up -- but the alternative is a
    pair of planes that quietly disagree, and the repair pass reads this same function so
    both sides of a seam must apply the rule identically.
    """
    wet = np.isfinite(surface_m)
    out = np.full(surface_m.shape, -1, dtype=np.int32)
    if wet.any():
        out[wet] = curve.to_block_height(surface_m[wet]).astype(np.int32)
    return np.where(out > min_water_blocks, out, -1)


def _repair_containment(
    heights: np.ndarray,
    water: np.ndarray,
    level_pad: np.ndarray,
    pad: int,
    params: CarveParams,
) -> tuple[np.ndarray, dict]:
    """Raise dry ground that a neighbouring water surface would otherwise pour over.

    Plan section 4.5 allows repair by raising terrain (preferred) or by extending water.
    Raising is chosen: extending water moves the shoreline away from the elevation the
    solver computed it at, and would have to be re-checked against *its* new neighbours,
    where raising terminates in one pass.

    A wet neighbour is left alone deliberately. Two adjacent water columns at different
    levels is a waterfall -- a step-down whose falling water lands in a body that is
    itself contained -- and it is also the common case, since a river descends about a
    block every few cells. Walling those off would put a levee down both banks of every
    river in the world.

    One pass suffices, and the order does not matter, because a repaired column is
    raised to `max` over its neighbours' *water levels*, which this pass never changes.
    That is also what makes it agree across a terrain-tile seam: the ring outside the
    tile contributes its water level, never its terrain, so two tiles never need to see
    each other's heights.
    """
    h, w = heights.shape
    # The four 1-block-shifted views of the ring-inclusive level plane, aligned to the
    # tile's own columns. `level_pad` is indexed from the padded window's origin.
    need = np.full((h, w), -1, dtype=np.int32)
    for di, dj in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        r0, c0 = pad + di, pad + dj
        np.maximum(need, level_pad[r0: r0 + h, c0: c0 + w], out=need)

    dry = water < 0
    short = dry & (heights < need)
    raised = np.where(short, need, heights)
    lift = np.where(short, need - heights, 0)

    return raised, {
        "containment_repairs": int(short.sum()),
        "containment_repair_fraction": float(short.mean()) if short.size else 0.0,
        "max_repair_blocks": int(lift.max()) if lift.size else 0,
    }


# --------------------------------------------------------------------------- invariant


def containment_violations(
    heights: np.ndarray, water_levels: np.ndarray, *, interior_only: bool = True
) -> np.ndarray:
    """Columns that break plan section 4.5, as a boolean mask over the *wet* column.

    A violation is a wet column at level `L` with a 4-neighbour that is neither wet nor
    walled to `L`. With `interior_only` the outermost ring is skipped, because a tile's
    border columns have neighbours in the next tile that this array does not contain --
    the cross-seam case is a two-tile test (`tests/test_carve.py`), not a one-tile one.
    """
    # int64 throughout: the sentinel shifted in at the border is "infinitely tall", which
    # a plane's own int16 cannot hold.
    heights = np.asarray(heights, dtype=np.int64)
    water_levels = np.asarray(water_levels, dtype=np.int64)
    wet = water_levels >= 0
    bad = np.zeros(heights.shape, dtype=bool)
    for di, dj in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        n_h = _shift(heights, di, dj, fill=np.iinfo(np.int64).max)
        n_wet = _shift(wet.astype(np.int8), di, dj, fill=1).astype(bool)
        bad |= wet & ~n_wet & (n_h < water_levels)
    if interior_only and bad.shape[0] > 2 and bad.shape[1] > 2:
        edge = np.ones(bad.shape, dtype=bool)
        edge[1:-1, 1:-1] = False
        bad &= ~edge
    return bad


def _shift(a: np.ndarray, di: int, dj: int, fill) -> np.ndarray:
    """`a` translated by `(di, dj)`, with `fill` shifted in at the border."""
    out = np.full(a.shape, fill, dtype=a.dtype)
    src = np.s_[max(di, 0): a.shape[0] + min(di, 0), max(dj, 0): a.shape[1] + min(dj, 0)]
    dst = np.s_[max(-di, 0): a.shape[0] + min(-di, 0), max(-dj, 0): a.shape[1] + min(-dj, 0)]
    out[dst] = a[src]
    return out


def seam_columns(
    a: CarveResult, b: CarveResult, axis: int
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """The two facing rows (or columns) of a pair of tiles adjacent along `axis`.

    Returns `(heights_a, water_a, heights_b, water_b)` for the seam, with `a` the lower
    tile. Used by the cross-seam containment test, which is the only place the invariant
    can actually be checked the way plan section 4.5 states it.
    """
    if axis == 0:
        return (
            a.block_heights[-1, :], a.water_levels[-1, :],
            b.block_heights[0, :], b.water_levels[0, :],
        )
    return (
        a.block_heights[:, -1], a.water_levels[:, -1],
        b.block_heights[:, 0], b.water_levels[:, 0],
    )


def seam_violations(
    heights_a: np.ndarray, water_a: np.ndarray, heights_b: np.ndarray, water_b: np.ndarray
) -> np.ndarray:
    """Indices along a seam where one side's water pours onto the other's dry ground."""
    wet_a, wet_b = water_a >= 0, water_b >= 0
    bad = (wet_a & ~wet_b & (heights_b < water_a)) | (wet_b & ~wet_a & (heights_a < water_b))
    return np.flatnonzero(bad)


def fingerprint(params: CarveParams, l1_params: L1Params) -> str:
    """The carve's contribution to the terrain tile cache namespace.

    Covers L1's knobs as well as the carve's own: a tile's water is a function of both,
    and L1 keeps its own namespace for its own payloads, not for these.
    """
    raw = f"{params.fingerprint_fields()}|{l1_params.fingerprint()}"
    return hashlib.sha1(raw.encode()).hexdigest()[:12]
