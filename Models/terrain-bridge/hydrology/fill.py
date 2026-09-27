"""Priority-Flood depression fill (Barnes, Lehman & Mulla 2014).

Reference implementation: upstream's `fill_depressions_priority_flood()` in
terrain_diffusion/inference/postprocessing.py. That one cannot be imported here --
its module does `import torch` at top level and this venv is deliberately torch-free
-- and its per-pixel Python seeding loop plus heapq flood is far too slow at 1024**2.
This is the same algorithm with a numba-compiled binary heap.

One deliberate departure from upstream: float64 throughout. float32 spacing at
8000 m is ~1e-3, so `elev + epsilon` with epsilon=1e-3 rounds away to nothing on
high terrain and flats stop draining.

`max_raise` is ported from upstream but **off by default**, and that default is
the one `depth.py` uses: a *complete* fill is what a water map wants, because the
fill delta is then exactly the lake depth with no free parameter, and which basins
survive is a post-filter on area/depth. It exists for the macro-tile solver
(`Dev Working/Rivers and lakes plan.md` section 4.2), where an unbounded basin
straddling a macro-tile border would fill to a different level either side of the
seam. See `fill_depressions` for what turning it on costs.
"""
from __future__ import annotations

import numpy as np
from numba import njit
from scipy import ndimage

# Epsilon large enough to survive float64 rounding at any terrestrial elevation,
# small enough that a filled flat rises negligibly: the rise across a flat is
# epsilon * (graph distance to its outlet), i.e. O(sqrt(area)), not O(area).
DEFAULT_EPSILON = 1e-3


@njit(cache=True, inline="always")
def _sift_up(keys, ords, idxs, i):
    while i > 0:
        p = (i - 1) >> 1
        if keys[p] > keys[i] or (keys[p] == keys[i] and ords[p] > ords[i]):
            keys[p], keys[i] = keys[i], keys[p]
            ords[p], ords[i] = ords[i], ords[p]
            idxs[p], idxs[i] = idxs[i], idxs[p]
            i = p
        else:
            break


@njit(cache=True, inline="always")
def _sift_down(keys, ords, idxs, n):
    i = 0
    while True:
        l = 2 * i + 1
        r = l + 1
        m = i
        if l < n and (keys[l] < keys[m] or (keys[l] == keys[m] and ords[l] < ords[m])):
            m = l
        if r < n and (keys[r] < keys[m] or (keys[r] == keys[m] and ords[r] < ords[m])):
            m = r
        if m == i:
            break
        keys[m], keys[i] = keys[i], keys[m]
        ords[m], ords[i] = ords[i], ords[m]
        idxs[m], idxs[i] = idxs[i], idxs[m]
        i = m


@njit(cache=True)
def _priority_flood(z, invalid, epsilon):
    """Return the depression-filled surface.

    Cells are popped in increasing filled-elevation order, so each cell is finalised
    at the lowest elevation from which some already-finalised cell can be reached --
    which is precisely the water level a spilling basin settles at.

    Ties are broken by insertion counter rather than left to heap order, so the
    result is bit-identical run to run. Determinism is the whole premise of this
    terrain pipeline; a nondeterministic fill would put seams back.
    """
    h, w = z.shape
    n = h * w
    filled = z.copy()
    visited = np.zeros(n, dtype=np.bool_)

    keys = np.empty(n, dtype=np.float64)
    ords = np.empty(n, dtype=np.int64)
    idxs = np.empty(n, dtype=np.int64)
    size = 0
    counter = 0

    zf = z.reshape(n)
    inv = invalid.reshape(n)
    ff = filled.reshape(n)

    # Seed every valid cell that can already discharge: the array border (water
    # leaves the tile) and any cell touching ocean (water reaches the sea).
    for i in range(h):
        for j in range(w):
            k = i * w + j
            if inv[k]:
                continue
            outlet = i == 0 or i == h - 1 or j == 0 or j == w - 1
            if not outlet:
                for di in range(-1, 2):
                    for dj in range(-1, 2):
                        if di == 0 and dj == 0:
                            continue
                        if invalid[i + di, j + dj]:
                            outlet = True
                            break
                    if outlet:
                        break
            if outlet:
                keys[size] = zf[k]
                ords[size] = counter
                idxs[size] = k
                size += 1
                _sift_up(keys, ords, idxs, size - 1)
                counter += 1
                visited[k] = True

    while size > 0:
        elev = keys[0]
        k = idxs[0]
        size -= 1
        keys[0], keys[size] = keys[size], keys[0]
        ords[0], ords[size] = ords[size], ords[0]
        idxs[0], idxs[size] = idxs[size], idxs[0]
        _sift_down(keys, ords, idxs, size)

        i = k // w
        j = k - i * w
        for di in range(-1, 2):
            for dj in range(-1, 2):
                if di == 0 and dj == 0:
                    continue
                ni = i + di
                nj = j + dj
                if ni < 0 or ni >= h or nj < 0 or nj >= w:
                    continue
                nk = ni * w + nj
                if visited[nk] or inv[nk]:
                    continue
                if zf[nk] <= elev:
                    # Below the spill level: raise to just above it so the cell
                    # still has somewhere to drain (this is the "+ epsilon" variant).
                    ff[nk] = elev + epsilon
                else:
                    ff[nk] = zf[nk]
                keys[size] = ff[nk]
                ords[size] = counter
                idxs[size] = nk
                size += 1
                _sift_up(keys, ords, idxs, size - 1)
                counter += 1
                visited[nk] = True

    return filled


def cap_basins(z: np.ndarray, filled: np.ndarray, max_raise: float) -> np.ndarray:
    """Hold every filled basin to `max_raise` metres above its own floor.

    Public because it is a post-pass, not an inner step: a caller that needs a
    *complete* fill for routing and a *capped* one for depth runs the flood once
    and caps the copy, rather than flooding twice. `basins.py` does exactly that,
    for the reason in the last paragraph of `fill_depressions`.

    Applied after the flood rather than inside it. Priority-Flood finalises a
    cell's level *before* it has descended far enough to know how deep the basin
    under it goes, so upstream's in-heap version measures against the running
    minimum of the flood path instead. That is both wrong and unusable here: a
    basin perched above lower ground never fills at all (the path minimum is the
    low ground it climbed from), a deep basin overshoots its cap by the depth of
    the last cell that passed the test, and the answer depends on which direction
    the flood arrived from -- which is precisely the seam that section 4.2 of
    `Dev Working/Rivers and lakes plan.md` uses this knob to avoid.

    Afterwards: a basin is a connected component of `filled > z`, its surface is
    level at its spill elevation by construction, so its capped level is
    `min(spill, floor + max_raise)`. That reads only cells of the basin itself,
    so a basin lying wholly inside the window gets the same level whatever the
    window is, and one straddling the border disagrees by at most `max_raise`.
    """
    wet = filled > z
    if not wet.any():
        return filled

    labels, n = ndimage.label(wet, structure=np.ones((3, 3), dtype=int))
    if n == 0:
        return filled

    index = np.arange(1, n + 1)
    floors = np.asarray(ndimage.minimum(z, labels, index), dtype=np.float64).reshape(-1)
    spills = np.asarray(ndimage.maximum(filled, labels, index), dtype=np.float64).reshape(-1)
    # Label 0 is dry ground and is never read, because `wet` gates the result.
    levels = np.concatenate([[0.0], np.minimum(spills, floors + max_raise)])
    return np.where(wet, np.maximum(z, levels[labels]), filled)


def ocean_mask(z: np.ndarray) -> np.ndarray:
    """Cells that are sea, not land. Upstream's convention: NaN or elevation <= 0."""
    return np.isnan(z) | (z <= 0.0)


def fill_depressions(
    z: np.ndarray,
    epsilon: float = DEFAULT_EPSILON,
    invalid: np.ndarray | None = None,
    max_raise: float | None = None,
) -> np.ndarray:
    """Fill closed depressions so every land cell has a descending path to an outlet.

    Args:
        z: (H, W) elevation in metres. NaN or <= 0 is ocean.
        epsilon: gradient injected across filled flats.
        invalid: override for the ocean mask.
        max_raise: cap, in metres, on how far a basin may be filled above its own
            floor. `None` (the default) fills every basin to its spill elevation,
            which is what a water map wants -- the fill delta is then exactly the
            lake depth, with no free parameter. Setting it bounds a basin's fill
            without reference to where its spill point is, so a basin too large
            for the window cannot fill to wildly different levels in two windows
            that overlap it (see `cap_basins`). The cost is deliberate: a capped
            basin is no longer guaranteed a descending path out, so it becomes a
            pit that swallows any flow routed into it. Fill for routing and fill
            for depth are therefore separate calls once this is in use.

    Returns:
        (H, W) float64 filled surface. Ocean cells are returned untouched, so
        `filled - z` is zero there and the lake-depth delta stays clean.
    """
    if z.ndim != 2:
        raise ValueError(f"expected a 2D elevation array, got shape {z.shape}")
    if max_raise is not None and max_raise <= 0.0:
        raise ValueError(f"max_raise must be > 0 or None, got {max_raise}")
    z64 = np.ascontiguousarray(z, dtype=np.float64)
    inv = ocean_mask(z64) if invalid is None else np.ascontiguousarray(invalid, dtype=np.bool_)
    if inv.shape != z64.shape:
        raise ValueError(f"invalid mask shape {inv.shape} != elevation shape {z64.shape}")

    # NaN would poison the heap comparisons; the mask already excludes those cells,
    # but the array still has to hold a finite value for them.
    z_safe = np.where(np.isnan(z64), 0.0, z64)
    filled = _priority_flood(z_safe, inv, float(epsilon))
    if max_raise is not None:
        filled = cap_basins(z_safe, filled, float(max_raise))
    filled[inv] = z64[inv]
    return filled
