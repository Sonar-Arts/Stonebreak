"""Synthetic DEMs whose correct water depth is known in closed form.

Real terrain cannot validate this code. A DEM tells you where the basins are but not
how deep the water in them should be, so a plausible-looking overlay on real terrain
proves nothing. These fixtures are built backwards from a known answer instead: each
returns the elevation map *and* the exact expected result, so the tests assert against
arithmetic rather than against a previous run's output.

Realism is a separate concern, covered by real diffusion terrain stitched out of the
bridge's tile cache (see `io.stitch_tile_cache`) -- used for visual review and timing,
never for numeric assertions.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

OCEAN = -10.0


@dataclass(frozen=True)
class Fixture:
    """A DEM paired with whatever about it is known exactly."""

    name: str
    z: np.ndarray
    expected_accumulation: np.ndarray | None = None
    expected_lake_depth: np.ndarray | None = None
    expected_lake_volume: float | None = None
    notes: str = ""


def inclined_plane(h: int = 64, w: int = 48, drop_per_row: float = 10.0,
                   top: float = 1000.0) -> Fixture:
    """A uniform slope draining to a coast along its bottom edge.

    Elevation depends only on the row, so the steepest descent from any cell is the
    cardinal step directly downslope -- a diagonal covers the same drop over sqrt(2)
    times the distance, so it always loses. Flow therefore runs in straight,
    independent columns and the upslope count at row `i` is exactly `i + 1`.

    Catches routing that leaks sideways, and any boundary rule that wrongly strands
    the first row or the outer columns.
    """
    z = np.empty((h, w), dtype=np.float64)
    z[:] = (top - drop_per_row * np.arange(h, dtype=np.float64))[:, None]
    z[-1, :] = OCEAN

    expected = np.zeros((h, w), dtype=np.float64)
    expected[:-1, :] = (np.arange(h - 1, dtype=np.float64) + 1.0)[:, None]

    return Fixture(
        name="inclined_plane",
        z=z,
        expected_accumulation=expected,
        expected_lake_depth=np.zeros((h, w)),
        expected_lake_volume=0.0,
        notes="A[i, j] == i + 1; no depressions anywhere.",
    )


def paraboloid_crater(size: int = 96, rim: float = 500.0, depth: float = 120.0,
                      radius: float = 24.0, wall: float = 6.0) -> Fixture:
    """A paraboloid basin ringed by a flat wall at exactly the rim elevation.

    This is the fixture that pins down water *depth* rather than merely basin extent.
    The wall is a flat annulus at `rim`, so the spill elevation is unambiguously `rim`
    -- no discretisation ambiguity about which cell the basin overtops. The lake
    surface must therefore settle at `rim`, giving a per-cell depth of

        d(r) = depth * (1 - (r / radius) ** 2)     for r < radius

    and a volume of `depth * pi * radius**2 / 2` by integration, which the discrete
    sum approximates to within its sampling error.
    """
    c = (size - 1) / 2.0
    yy, xx = np.mgrid[0:size, 0:size]
    r = np.hypot(yy - c, xx - c)

    inside = r < radius
    in_wall = (r >= radius) & (r < radius + wall)

    # Outside the wall, fall away to the sea so the basin has somewhere to spill to.
    outer_drop = (r - (radius + wall)) * 12.0
    z = np.where(inside, rim - depth * (1.0 - (r / radius) ** 2), rim - outer_drop)
    z = np.where(in_wall, rim, z)
    z = np.where(z <= 0.0, OCEAN, z)

    expected_depth = np.where(inside, depth * (1.0 - (r / radius) ** 2), 0.0)

    return Fixture(
        name="paraboloid_crater",
        z=z,
        expected_lake_depth=expected_depth,
        expected_lake_volume=float(depth * np.pi * radius ** 2 / 2.0),
        notes="Lake surface must settle at the rim; depth is rim - z, exactly.",
    )


def two_basins_saddle() -> Fixture:
    """Two pits in a plateau, one draining into the other, the second to the sea.

    Water levels are set by the *outlets*, not by the surrounding walls:

        pit B (floor 60) spills down a channel at 70  ->  fills to  70, depth 10
        pit A (floor 50) spills over a saddle at 80 into B  ->  fills to 80, depth 30

    A fill that ignores spill points would raise both pits to the 100 plateau and
    report depths of 50 and 40. A fill that stops at the first barrier would leave
    them dry. Only correct spill-point routing produces 30 and 10.
    """
    h, w = 40, 60
    z = np.full((h, w), 100.0, dtype=np.float64)

    z[:, -3:] = OCEAN                       # coast along the right edge
    z[18:22, 10:20] = 50.0                  # pit A floor
    z[18:22, 30:40] = 60.0                  # pit B floor
    z[19:21, 20:30] = 80.0                  # saddle: A spills into B here
    z[19:21, 40:57] = 70.0                  # channel: B spills to the sea here

    expected = np.zeros((h, w), dtype=np.float64)
    expected[18:22, 10:20] = 80.0 - 50.0
    expected[18:22, 30:40] = 70.0 - 60.0

    volume = float(expected.sum())
    return Fixture(
        name="two_basins_saddle",
        z=z,
        expected_lake_depth=expected,
        expected_lake_volume=volume,
        notes="A fills to its saddle (80), B to its channel (70).",
    )


def v_valley_to_coast(h: int = 64, w: int = 64, side_slope: float = 8.0,
                      down_slope: float = 4.0, floor: float = 400.0) -> Fixture:
    """A V-shaped valley running to a coast: everything drains, nothing pools.

    Used for whole-network invariants -- every land cell must discharge, and the
    accumulation totalled over the terminal cells must equal the land-cell count,
    i.e. no water is created or lost in transit.
    """
    yy, xx = np.mgrid[0:h, 0:w]
    centre = (w - 1) / 2.0
    z = floor + side_slope * np.abs(xx - centre) - down_slope * yy
    z[-1, :] = OCEAN

    return Fixture(
        name="v_valley_to_coast",
        z=z.astype(np.float64),
        expected_lake_depth=np.zeros((h, w)),
        expected_lake_volume=0.0,
        notes="Strictly descending toward the coast; no closed basins.",
    )


ALL_FIXTURES = (
    inclined_plane,
    paraboloid_crater,
    two_basins_saddle,
    v_valley_to_coast,
)


def fractal_terrain(size: int = 512, seed: int = 0, relief: float = 900.0,
                    sea_fraction: float = 0.25, octaves: int = 8) -> Fixture:
    """Continuous-valued fractal terrain. **No ground truth** -- do not assert against it.

    This exists because the obvious realistic input, terrain stitched out of the
    bridge's tile cache, turns out to be a poor hydrological test surface: those tiles
    store Stonebreak *block heights*, so a 1024-cell window carries only ~31 distinct
    elevations and 84% of its land is perfectly level. Drainage across flats that large
    is a distance field, not a river network, whatever algorithm resolves them.

    Fractional Brownian motion has the property the block heights lost -- continuous
    elevation, so almost every cell has a strictly lower neighbour and the flow network
    is decided by terrain rather than by flat resolution. It is the right surface for
    judging whether rivers look like rivers.

    Deterministic in `seed`; needs nothing but numpy.
    """
    rng = np.random.default_rng(seed)
    field = np.zeros((size, size), dtype=np.float64)
    amplitude = 1.0
    total = 0.0
    res = 4
    while res <= size and octaves > 0:
        coarse = rng.standard_normal((res + 1, res + 1))
        # Bilinear upsample of the coarse lattice -- cheap value noise, no scipy needed.
        t = np.linspace(0.0, res, size, endpoint=False)
        i0 = np.floor(t).astype(int)
        frac = (t - i0)[:, None]
        row = coarse[i0][:, :] * (1 - frac) + coarse[i0 + 1][:, :] * frac
        col = row[:, i0] * (1 - frac.T) + row[:, i0 + 1] * frac.T
        field += amplitude * col
        total += amplitude
        amplitude *= 0.5
        res *= 2
        octaves -= 1

    field /= total
    field -= field.min()
    field /= field.max()

    # Push the requested fraction below sea level, then scale to the wanted relief.
    cut = float(np.quantile(field, sea_fraction))
    z = (field - cut) / max(1.0 - cut, 1e-9) * relief
    z[z <= 0.0] = OCEAN

    return Fixture(
        name="fractal_terrain",
        z=z,
        notes="Continuous elevation; realistic drainage. Not ground truth.",
    )
