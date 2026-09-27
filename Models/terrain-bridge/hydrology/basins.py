"""L0: continental drainage solved at 240 m over canonical macro-regions.

Flow accumulation is the one quantity in this pipeline that is not local -- discharge
at a point depends on its entire upstream watershed, which is the direct opposite of
the O(1) random access the terrain pipeline is built on. `Dev Working/Rivers and lakes
plan.md` section 4.2 resolves that with a bounded window of *canonical shape*, and this
module is where the L0 window's shape is decided. It is `bridge/tiling.py` one level up,
and the same rule applies for the same reason:

    a macro-region's fetch window, halo included, is a pure function of its ID and is
    never re-derived from whichever consumer happened to ask for it.

Two windows over the same ground accumulate differently, so a region whose halo depended
on its caller would put rivers that stop at a border back into the world. `L0Geometry`
holds that shape; `L0` is the one instance production ever uses.

**Where the elevation comes from.** Not from the tile cache, and not from a coarse
upstream stage. Phase 6b (section 13 of the plan) measured both alternatives and rejected
them: block heights are quantised far too coarsely to route on at all, and the cheap
latent-lowfreq field misplaces lake surfaces by ~10 blocks, which is disqualifying for
the one L0 output that feeds block placement directly. L0 is instead built by generating
elevation at the model's *native* resolution over the whole region and box-downsampling
it to 240 m -- exact by construction, because it is the terrain, and needing no upstream
change. ~140 s per 245.8 km region over HTTP, computed once and cached.

**Two fills, deliberately.** `water_depth` routes on a complete fill and takes lake
surfaces from a `max_raise`-capped copy of it. Uncapped, real continental basins fill to
their spill points and produce 644 m lakes over 7.1 % of land (plan section 13.8); capped,
a basin has no guaranteed outlet and becomes a pit that swallows its whole catchment. So
routing gets the uncapped surface and depth gets the capped one. That is the split plan
section 11.2 left open for this phase to decide against real data.

Nothing here is imported by the running bridge service, and the upstream fetch is
injected as a callable rather than constructed, so the solve is testable without a GPU,
a network, or `requests`.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterator

import numpy as np

from .depth import DEFAULT_RIVER_COEFF, DEFAULT_RIVER_EXP, water_depth
from .fill import ocean_mask
from .flats import DEFAULT_FLAT_EPSILON
from .flow import D8_DX, D8_DY

# Bumped when the *meaning* of a stored plane changes in a way the knobs below do not
# capture, so a namespace solved by an older shape is never read back as a newer one.
L0_SCHEMA_VERSION = 1

_MAGIC = b"SBL0"
_HEADER_BYTES = 12

# A cell with no downstream neighbour: it discharges to sea, or off the solve window.
TERMINAL = -1


# --------------------------------------------------------------------------- geometry


@dataclass(frozen=True)
class L0Geometry:
    """The canonical L0 window shape. One instance (`L0`) is used in production.

    Parameterised only so the solve can be exercised at a size a test can hold in
    closed form -- every production caller takes the default, exactly as every caller
    of `bridge/tiling.py` passes the configured tile size. Nothing may derive a window
    from a request; see the module docstring.
    """

    # The model's own cell size. Not configurable in practice: it is a property of the
    # checkpoint upstream loaded, and there is no endpoint to ask it for one. It sits in
    # the cache fingerprint so a different model rotates the namespace rather than
    # silently reusing regions solved at the wrong scale.
    native_resolution_m: float = 30.0

    # 8 native pixels = 240 m, which is `latent_compression` against a 30 m model -- the
    # resolution plan section 4.2 asks L0 for, reached by downsampling rather than by
    # reading a latent stage (section 13.2 measures why that alternative fails).
    cell_native_px: int = 8

    # 1024 cells = 8192 native px = 245.76 km per side, covering ~60,400 km2.
    region_cells: int = 1024

    # Context solved but not kept. It does not make the window seam-free -- nothing
    # bounded can, when a real catchment is larger than the window -- but it moves the
    # array-border artifact (the fill treats the border as an outlet) 61.44 km away from
    # any cell that is actually stored, and gives a border basin most of its true extent
    # before `max_raise` looks at it.
    #
    # Doubled from the original 128 (plan section 18): section 15.12 measured this halo
    # as the direct bound on how much upstream catchment a border region's own
    # accumulation can see, on a closed-form fixture where the true count was 577 and a
    # 128-cell halo read 129 -- exactly halo_cells + 1. Doubling it does not make a
    # bounded window seamless (no halo does, for a catchment wider than it), but it
    # halves the fraction of realistic catchments that are wider than the window, at a
    # projected 1.44x fetch-area cost per region -- worth taking once, since a region is
    # solved and cached permanently rather than re-paid.
    halo_cells: int = 256

    # Side of one upstream fetch, in L0 cells. 256 cells = 2048 native px, and the
    # solve window is 6x6 of them (was 5x5 at halo_cells=128).
    #
    # Square, and that is not incidental. Upstream generates in overlapping decoder
    # windows, so throughput depends on the *shape* of the request and not only its
    # area: measured against the live service at a fixed 4.19 M px, a 2048x2048 block
    # runs at 1002k px/s where a 1024x4096 strip of identical area manages 331k, and a
    # full-width 512x10240 strip -- the obvious way to bound memory -- collapses to
    # 70k. A region is ~105 s squarely and ~25 min in strips.
    #
    # Canonical like everything else here: upstream output depends on request shape at
    # the ~1 m level (plan section 2.2), so a run that blocks differently is a run that
    # answers differently.
    fetch_cells: int = 256

    def __post_init__(self) -> None:
        if min(self.cell_native_px, self.region_cells, self.fetch_cells) < 1:
            raise ValueError("cell, region and fetch sizes must all be >= 1")
        if self.halo_cells < 0:
            raise ValueError("halo_cells must be >= 0")
        if self.solve_cells % self.fetch_cells:
            raise ValueError(
                f"solve window of {self.solve_cells} cells is not a whole number of "
                f"{self.fetch_cells}-cell blocks; a ragged final block would be a "
                "second request shape over the same ground"
            )

    @property
    def cell_m(self) -> float:
        return self.cell_native_px * self.native_resolution_m

    @property
    def solve_cells(self) -> int:
        return self.region_cells + 2 * self.halo_cells

    @property
    def region_native_px(self) -> int:
        return self.region_cells * self.cell_native_px

    def region_containing_native(self, native_i: int, native_j: int) -> tuple[int, int]:
        """Floor toward negative infinity, so regions cover negative coordinates
        contiguously. Python's `//` already does this for ints; a Java port must use
        Math.floorDiv, exactly as `bridge/tiling.py` warns."""
        side = self.region_native_px
        return native_i // side, native_j // side

    def region_containing_block(self, block_x: int, block_z: int, scale: int) -> tuple[int, int]:
        """Region owning a world block. `scale` is `BridgeConfig.scale`: one native
        pixel spans `scale` blocks, so a block is `block // scale` in native space."""
        return self.region_containing_native(block_x // scale, block_z // scale)

    def region_bounds_native(self, region_i: int, region_j: int) -> tuple[int, int, int, int]:
        """The ground a region *owns*, in native pixels. Regions tile it without overlap."""
        side = self.region_native_px
        i1, j1 = region_i * side, region_j * side
        return i1, j1, i1 + side, j1 + side

    def solve_bounds_native(self, region_i: int, region_j: int) -> tuple[int, int, int, int]:
        """The canonical fetch window: owned ground plus halo. A pure function of the ID.

        This is the invariant the module exists to hold. Everything else follows from it.
        """
        i1, j1, i2, j2 = self.region_bounds_native(region_i, region_j)
        pad = self.halo_cells * self.cell_native_px
        return i1 - pad, j1 - pad, i2 + pad, j2 + pad

    def region_bounds_blocks(
        self, region_i: int, region_j: int, scale: int
    ) -> tuple[int, int, int, int]:
        i1, j1, i2, j2 = self.region_bounds_native(region_i, region_j)
        return i1 * scale, j1 * scale, i2 * scale, j2 * scale

    def cell_containing_block(self, block_x: int, block_z: int, scale: int) -> tuple[int, int]:
        """(row, col) of the L0 cell covering a block, within its own region's array."""
        region_i, region_j = self.region_containing_block(block_x, block_z, scale)
        i1, j1, _, _ = self.region_bounds_blocks(region_i, region_j, scale)
        cell_blocks = self.cell_native_px * scale
        return (block_x - i1) // cell_blocks, (block_z - j1) // cell_blocks

    def fetch_windows(
        self, bounds: tuple[int, int, int, int]
    ) -> Iterator[tuple[int, int, int, int]]:
        """Split a solve window into canonical square blocks, row-major."""
        return split_windows(bounds, self.fetch_cells * self.cell_native_px)

    def interior_slice(self) -> tuple[slice, slice]:
        k = self.halo_cells
        return np.s_[k: k + self.region_cells, k: k + self.region_cells]

    def fingerprint_fields(self) -> str:
        return (
            f"{self.native_resolution_m}|{self.cell_native_px}|{self.region_cells}|"
            f"{self.halo_cells}|{self.fetch_cells}"
        )


#: The shape production uses. Import this, do not build your own.
L0 = L0Geometry()


@dataclass(frozen=True)
class L0Params:
    """Every knob that changes a stored region. Hashed into the cache namespace.

    Deliberately does *not* include the Phase 5 elevation curve, `sea_level`, or
    `meters_per_block`. L0 is solved entirely in metres, upstream of block quantisation
    -- that is the finding the whole plan rests on (section 3.1) -- so retuning the
    curve leaves every solved region valid. Only `scale` could matter, and it does not
    appear either: the window is defined in native pixels, which `scale` does not move.
    """

    geometry: L0Geometry = field(default_factory=L0Geometry)

    # 100 m bounds the pathological continental basins section 13.8 measured (644 m,
    # over 7.1 % of land) while leaving room for a genuinely deep large lake -- the
    # Great Lakes run 60-400 m. Applies to lake depth only, never to routing.
    max_raise_m: float | None = 100.0

    # 1000 cells is 57.6 km2 of catchment at 240 m. L0's network is the trunk system;
    # everything finer is L1's to resolve at 30 m.
    river_threshold_cells: float = 1000.0
    river_coeff: float = DEFAULT_RIVER_COEFF
    river_exp: float = DEFAULT_RIVER_EXP
    min_lake_area_cells: int = 8
    min_lake_depth_m: float = 0.5
    flat_epsilon: float = DEFAULT_FLAT_EPSILON

    def fingerprint(self) -> str:
        raw = (
            f"v{L0_SCHEMA_VERSION}|{self.geometry.fingerprint_fields()}|"
            f"{self.max_raise_m}|{self.river_threshold_cells}|"
            f"{self.river_coeff}|{self.river_exp}|"
            f"{self.min_lake_area_cells}|{self.min_lake_depth_m}|{self.flat_epsilon}"
        )
        return hashlib.sha1(raw.encode()).hexdigest()[:12]


@dataclass(frozen=True)
class L0RegionId:
    seed: int
    region_i: int
    region_j: int

    def cache_key(self) -> str:
        return f"s{self.seed}_r{self.region_i}_{self.region_j}"


# ------------------------------------------------------------------------------ solve


ElevationSource = Callable[[int, int, int, int], np.ndarray]


def split_windows(
    bounds: tuple[int, int, int, int], step: int
) -> Iterator[tuple[int, int, int, int]]:
    """Split a window into square blocks of `step` native pixels, row-major."""
    i1, j1, i2, j2 = bounds
    for i in range(i1, i2, step):
        for j in range(j1, j2, step):
            yield i, j, min(i + step, i2), min(j + step, j2)


def fetch_grid(
    bounds: tuple[int, int, int, int],
    source: ElevationSource,
    cell_px: int,
    block_px: int,
) -> np.ndarray:
    """Generate native elevation over `bounds` in square blocks, box-meaned to `cell_px`.

    The box mean is what makes L0 exact rather than approximate: averaging 64 native
    pixels is a defensible resampling of the very field the terrain is made of, so an
    L0 lake surface sits in the terrain it occupies. It also buys back the precision
    upstream's int16-metre wire format costs -- 64 integers averaged resolve well below
    the metre they were floored to. At `cell_px = 1` the mean is the identity, which is
    what L1 wants: its cells *are* native pixels.

    Shared by both levels because the request shape is the part that must not vary --
    square blocks, for the 14x reason in plan section 14.3 -- and a second copy of that
    loop would be a second place for it to drift.
    """
    i1, j1, i2, j2 = bounds
    if (i2 - i1) % cell_px or (j2 - j1) % cell_px:
        raise ValueError(f"window {bounds} is not a whole number of {cell_px}px cells")

    out = np.empty(((i2 - i1) // cell_px, (j2 - j1) // cell_px), dtype=np.float64)
    for bi1, bj1, bi2, bj2 in split_windows(bounds, block_px):
        arr = np.asarray(source(bi1, bj1, bi2, bj2), dtype=np.float64)
        want = (bi2 - bi1, bj2 - bj1)
        if arr.shape != want:
            raise ValueError(
                f"elevation source returned {arr.shape} for window "
                f"({bi1}, {bj1}, {bi2}, {bj2}); expected {want}"
            )
        rows, cols = arr.shape[0] // cell_px, arr.shape[1] // cell_px
        r0, c0 = (bi1 - i1) // cell_px, (bj1 - j1) // cell_px
        out[r0: r0 + rows, c0: c0 + cols] = arr.reshape(
            rows, cell_px, cols, cell_px
        ).mean(axis=(1, 3))
    return out


def fetch_l0_grid(
    bounds: tuple[int, int, int, int],
    source: ElevationSource,
    geometry: L0Geometry = L0,
) -> np.ndarray:
    """`fetch_grid` at the L0 geometry's cell and block sizes."""
    return fetch_grid(
        bounds, source, geometry.cell_native_px, geometry.fetch_cells * geometry.cell_native_px
    )


def receiver_directions(receivers: np.ndarray, shape: tuple[int, int]) -> np.ndarray:
    """Re-express flat receiver indices as direction codes into `D8_DY` / `D8_DX`.

    Flat indices are relative to the array they were computed on, so they stop meaning
    anything the moment the halo is cropped off. A direction survives the crop, and a
    receiver that lands outside the kept interior stays correctly described as flow
    leaving the region rather than becoming a dangling index into a smaller array.
    """
    h, w = shape
    idx = np.arange(h * w, dtype=np.int64)
    live = receivers >= 0
    safe = np.where(live, receivers, 0)
    di = safe // w - idx // w
    dj = safe % w - idx % w

    code = np.full(h * w, TERMINAL, dtype=np.int8)
    for k in range(8):
        code[live & (di == D8_DY[k]) & (dj == D8_DX[k])] = k
    if np.any(live & (code == TERMINAL)):
        raise ValueError("a receiver is not an 8-neighbour step; the flow graph is corrupt")
    return code.reshape(h, w)


@dataclass(frozen=True)
class L0Solution:
    """One solved macro-region, cropped to the ground it owns.

    Every plane is `geometry.region_cells` square, in row-major (i, j) order matching
    `region_bounds_native`: row indexes world i, column indexes world j.
    """

    region: L0RegionId
    elevation: np.ndarray      # float32 metres, box-mean of native elevation
    lake_surface: np.ndarray   # float32 metres, NaN where the cell holds no lake
    accumulation: np.ndarray   # float32 upslope cell count, itself included
    flow_dir: np.ndarray       # int8 index into D8_DY / D8_DX, TERMINAL where none
    geometry: L0Geometry = field(default_factory=L0Geometry)

    @property
    def ocean(self) -> np.ndarray:
        return ocean_mask(self.elevation.astype(np.float64))

    @property
    def lake(self) -> np.ndarray:
        return np.isfinite(self.lake_surface)

    def catchment_km2(self) -> np.ndarray:
        return self.accumulation * (self.geometry.cell_m / 1000.0) ** 2

    def channels(self, threshold_cells: float) -> np.ndarray:
        return (self.accumulation >= threshold_cells) & ~self.ocean

    def stats(self) -> dict:
        ocean = self.ocean
        land = ~ocean
        n_land = int(land.sum())
        lake = self.lake & land
        return {
            "region": self.region.cache_key(),
            "cells_per_side": int(self.elevation.shape[0]),
            "cell_m": self.geometry.cell_m,
            "km_per_side": self.elevation.shape[0] * self.geometry.cell_m / 1000.0,
            "land_cells": n_land,
            "ocean_cells": int(ocean.sum()),
            "lake_cells": int(lake.sum()),
            "lake_land_fraction": (float(lake.sum()) / n_land) if n_land else 0.0,
            "max_catchment_km2": float(self.catchment_km2().max()) if n_land else 0.0,
            "elev_min_m": float(np.nanmin(self.elevation)),
            "elev_max_m": float(np.nanmax(self.elevation)),
        }


def solve_region(
    region: L0RegionId,
    source: ElevationSource,
    params: L0Params = L0Params(),
) -> L0Solution:
    """Fetch, solve, and crop one macro-region.

    The crop is the last step on purpose: the halo has to be *solved*, not merely
    fetched, or it contributes nothing. Accumulation arriving at an interior cell from
    the halo is exactly what the halo is for.
    """
    geom = params.geometry
    z = fetch_l0_grid(geom.solve_bounds_native(region.region_i, region.region_j), source, geom)

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
    )

    flow_dir = receiver_directions(wm.receivers, z.shape)
    lake_surface = np.where(wm.lake_depth > 0.0, wm.filled, np.nan)

    interior = geom.interior_slice()
    return L0Solution(
        region=region,
        elevation=z[interior].astype(np.float32),
        lake_surface=lake_surface[interior].astype(np.float32),
        accumulation=wm.accumulation[interior].astype(np.float32),
        flow_dir=flow_dir[interior],
        geometry=geom,
    )


# ----------------------------------------------------------------------------- inflow


@dataclass(frozen=True)
class InflowTable:
    """Flow crossing into a rectangle from the ring of cells just outside it.

    This is what an L1 macro-tile seeds its own accumulation with: a cell whose D8
    receiver lies inside the tile delivers its whole upslope area there, and without
    that the tile would believe every river started at its own edge.

    All coordinates are L0 cell indices in the solution's own array; `accumulation` is
    the upslope cell count at the *source*, which is what crosses the boundary.
    """

    source_row: np.ndarray
    source_col: np.ndarray
    target_row: np.ndarray
    target_col: np.ndarray
    accumulation: np.ndarray

    def __len__(self) -> int:
        return int(self.source_row.size)

    def total(self) -> float:
        return float(self.accumulation.sum())


def inflow_edges_from(
    flow_dir: np.ndarray,
    accumulation: np.ndarray,
    ci1: int,
    cj1: int,
    ci2: int,
    cj2: int,
) -> InflowTable:
    """`inflow_edges` over bare planes, in whatever coordinates they are indexed by.

    Split out from `inflow_edges` because an L1 tile whose halo crosses an L0 region
    border needs its ring assembled from more than one solved region (see
    `hydrology/local.py`). Regions tile the plane without overlap, so the stitch is
    unambiguous -- but only if the ring can be read as one array rather than one
    solution, which is what this signature buys.
    """
    h, w = flow_dir.shape
    if not (0 <= ci1 < ci2 <= h and 0 <= cj1 < cj2 <= w):
        raise ValueError(f"rect ({ci1}, {cj1}, {ci2}, {cj2}) is not inside a {h}x{w} array")

    r1, c1 = max(ci1 - 1, 0), max(cj1 - 1, 0)
    r2, c2 = min(ci2 + 1, h), min(cj2 + 1, w)
    rows, cols = np.mgrid[r1:r2, c1:c2]

    code = flow_dir[r1:r2, c1:c2]
    outside = ~((rows >= ci1) & (rows < ci2) & (cols >= cj1) & (cols < cj2))
    ring = outside & (code != TERMINAL)

    safe = np.where(ring, code, 0)
    trow = rows + D8_DY[safe]
    tcol = cols + D8_DX[safe]
    crossing = ring & (trow >= ci1) & (trow < ci2) & (tcol >= cj1) & (tcol < cj2)

    sel = np.flatnonzero(crossing.ravel())
    return InflowTable(
        source_row=rows.ravel()[sel],
        source_col=cols.ravel()[sel],
        target_row=trow.ravel()[sel],
        target_col=tcol.ravel()[sel],
        accumulation=accumulation[r1:r2, c1:c2].ravel()[sel],
    )


def inflow_edges(solution: L0Solution, ci1: int, cj1: int, ci2: int, cj2: int) -> InflowTable:
    """Every cell just outside `[ci1, ci2) x [cj1, cj2)` that drains into it.

    Derived on demand rather than baked into the cached region, so L0 never has to know
    how L1 chooses to tile itself. A rectangle touching the region border simply has no
    ring on that side; the flow crossing there belongs to the neighbouring region and is
    that region's to supply.
    """
    n = solution.flow_dir.shape[0]
    if not (0 <= ci1 < ci2 <= n and 0 <= cj1 < cj2 <= n):
        raise ValueError(f"rect ({ci1}, {cj1}, {ci2}, {cj2}) is not inside a {n}x{n} region")
    return inflow_edges_from(solution.flow_dir, solution.accumulation, ci1, cj1, ci2, cj2)


# ------------------------------------------------------------------------------ cache


def encode(solution: L0Solution) -> bytes:
    """Serialise a solution.

    Byte-identical for equal inputs, which is what Phase 6c's determinism exit criterion
    is checked against -- no compression container, no timestamps, fixed dtypes and
    fixed byte order.
    """
    n = solution.elevation.shape[0]
    header = _MAGIC + np.array([L0_SCHEMA_VERSION, n], dtype="<u2").tobytes() + b"\0\0\0\0"
    assert len(header) == _HEADER_BYTES
    return (
        header
        + solution.elevation.astype("<f4").tobytes()
        + solution.lake_surface.astype("<f4").tobytes()
        + solution.accumulation.astype("<f4").tobytes()
        + solution.flow_dir.astype("<i1").tobytes()
    )


def decode(region: L0RegionId, data: bytes, geometry: L0Geometry = L0) -> L0Solution:
    if data[:4] != _MAGIC:
        raise ValueError(f"not an L0 region payload (magic {data[:4]!r})")
    version, n = (int(v) for v in np.frombuffer(data[4:8], dtype="<u2"))
    if version != L0_SCHEMA_VERSION:
        raise ValueError(f"L0 payload schema v{version}, this build reads v{L0_SCHEMA_VERSION}")
    if n != geometry.region_cells:
        raise ValueError(f"L0 payload is {n} cells/side, geometry says {geometry.region_cells}")

    body = data[_HEADER_BYTES:]
    plane = n * n * 4
    expected = 3 * plane + n * n
    if len(body) != expected:
        raise ValueError(f"L0 payload for {n}x{n} is {len(body)} bytes, expected {expected}")

    def f32(k: int) -> np.ndarray:
        return np.frombuffer(body[k * plane: (k + 1) * plane], dtype="<f4").reshape(n, n).copy()

    return L0Solution(
        region=region,
        elevation=f32(0),
        lake_surface=f32(1),
        accumulation=f32(2),
        flow_dir=np.frombuffer(body[3 * plane:], dtype="<i1").reshape(n, n).copy(),
        geometry=geometry,
    )


class L0Cache:
    """Regions on disk, namespaced by the knobs that produced them.

    Separate from `bridge/cache.py`'s tile cache, and not an LRU: a region costs minutes
    of GPU time, covers ~60,400 km2, and stores in ~13 MB, so evicting one to reclaim
    space is the wrong trade. It is independent of the tile cache in the other direction
    too -- an elevation-curve change invalidates every tile and no region at all, for
    the reason in `L0Params`.
    """

    def __init__(self, cache_dir: str | Path, params: L0Params = L0Params()):
        self._root = Path(cache_dir) / "l0" / params.fingerprint()
        self._root.mkdir(parents=True, exist_ok=True)
        self._params = params

    @property
    def root(self) -> Path:
        return self._root

    @property
    def params(self) -> L0Params:
        return self._params

    def path(self, region: L0RegionId) -> Path:
        return self._root / f"{region.cache_key()}.l0"

    def get(self, region: L0RegionId) -> L0Solution | None:
        try:
            data = self.path(region).read_bytes()
        except FileNotFoundError:
            return None
        return decode(region, data, self._params.geometry)

    def put(self, solution: L0Solution) -> Path:
        path = self.path(solution.region)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(encode(solution))
        tmp.replace(path)  # atomic on the same filesystem: never a torn half-region
        return path

    def get_or_solve(self, region: L0RegionId, source: ElevationSource) -> L0Solution:
        cached = self.get(region)
        if cached is not None:
            return cached
        solution = solve_region(region, source, self._params)
        self.put(solution)
        return solution


def upstream_source(cfg, timeout_s: float | None = None) -> ElevationSource:
    """An `ElevationSource` backed by a live upstream service.

    Imported lazily so everything above stays usable -- and testable -- without
    `requests`, a network, or a GPU.
    """
    from bridge.upstream_client import UpstreamClient

    client = UpstreamClient(cfg)

    def fetch(i1: int, j1: int, i2: int, j2: int) -> np.ndarray:
        return client.fetch_native(i1, j1, i2, j2, timeout_s=timeout_s)

    return fetch
