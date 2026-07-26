"""Flat resolution (Garbrecht & Martz 1997).

A depression fill leaves large perfectly level areas -- lake surfaces by construction,
and any plain the source DEM quantised flat. D8 cannot route across level ground, so
something has to impose a drainage direction, and the naive choice is fatal to how the
result looks: injecting a gradient that simply grows with distance from the outlet
(upstream's `epsilon` fill does exactly this) makes every cell on the flat point the
same way, and the rivers come out as straight parallel lines converging in herringbone
chevrons. It is the single most recognisable D8 artifact.

Garbrecht & Martz resolve it by superimposing *two* gradients over each flat:

    towards lower terrain    elevation rises with distance from the flat's outlets,
                             so water still leaves by the right exit;
    away from higher terrain elevation rises with proximity to the surrounding high
                             ground, so water entering the flat keeps going instead of
                             turning to run along the shortest path out.

Their sum bends flow away from the flat's upslope margin while still converging on the
outlet, which produces the branching, curved channels real drainage has.

The added relief is a routing device, not terrain: the increment is scaled so it stays
far below the smallest real drop on the map (see `_safe_epsilon`), leaving the surface
`fill_depressions` produced -- and therefore the lake depths derived from it --
untouched.
"""
from __future__ import annotations

import numpy as np
from numba import njit
from scipy import ndimage

DEFAULT_FLAT_EPSILON = 1e-6


@njit(cache=True)
def _classify(filled, invalid):
    """Split land into flats and the seeds of the two gradients.

    Returns (flat, seed_low, seed_high) where `flat` marks land with nowhere to drain,
    `seed_low` its exits (adjacent to something it can discharge into) and `seed_high`
    its upslope margin (adjacent to higher ground).
    """
    h, w = filled.shape
    flat = np.zeros((h, w), dtype=np.bool_)
    has_higher = np.zeros((h, w), dtype=np.bool_)
    can_exit = np.zeros((h, w), dtype=np.bool_)

    for i in range(h):
        for j in range(w):
            if invalid[i, j]:
                continue
            zc = filled[i, j]
            lower = False
            higher = False
            for di in range(-1, 2):
                for dj in range(-1, 2):
                    if di == 0 and dj == 0:
                        continue
                    ni = i + di
                    nj = j + dj
                    if ni < 0 or ni >= h or nj < 0 or nj >= w:
                        continue
                    if invalid[ni, nj]:
                        lower = True          # the sea is always downhill
                        continue
                    if filled[ni, nj] < zc:
                        lower = True
                    elif filled[ni, nj] > zc:
                        higher = True
            flat[i, j] = not lower
            has_higher[i, j] = higher
            can_exit[i, j] = lower

    seed_low = np.zeros((h, w), dtype=np.bool_)
    seed_high = np.zeros((h, w), dtype=np.bool_)
    for i in range(h):
        for j in range(w):
            if not flat[i, j]:
                continue
            zc = filled[i, j]
            for di in range(-1, 2):
                for dj in range(-1, 2):
                    if di == 0 and dj == 0:
                        continue
                    ni = i + di
                    nj = j + dj
                    if ni < 0 or ni >= h or nj < 0 or nj >= w:
                        continue
                    if invalid[ni, nj]:
                        seed_low[i, j] = True
                        continue
                    zn = filled[ni, nj]
                    # A neighbour that is itself draining, at or below this level, is
                    # a way out of the flat.
                    if zn < zc or (zn == zc and can_exit[ni, nj]):
                        seed_low[i, j] = True
                    if zn > zc:
                        seed_high[i, j] = True
    return flat, seed_low, seed_high


@njit(cache=True)
def _bfs(seeds, region):
    """Multi-source 8-connected BFS, confined to `region`. Unreached cells stay -1.

    All flats are traversed in a single pass: they are disjoint, so their frontiers
    never meet and one queue serves the lot.
    """
    h, w = region.shape
    n = h * w
    dist = np.full(n, -1, dtype=np.int32)
    queue = np.empty(n, dtype=np.int64)
    head = 0
    tail = 0

    for i in range(h):
        for j in range(w):
            if seeds[i, j] and region[i, j]:
                k = i * w + j
                dist[k] = 0
                queue[tail] = k
                tail += 1

    while head < tail:
        k = queue[head]
        head += 1
        i = k // w
        j = k - i * w
        d = dist[k] + 1
        for di in range(-1, 2):
            for dj in range(-1, 2):
                if di == 0 and dj == 0:
                    continue
                ni = i + di
                nj = j + dj
                if ni < 0 or ni >= h or nj < 0 or nj >= w:
                    continue
                if not region[ni, nj]:
                    continue
                nk = ni * w + nj
                if dist[nk] < 0:
                    dist[nk] = d
                    queue[tail] = nk
                    tail += 1
    return dist.reshape(h, w)


def _safe_epsilon(filled: np.ndarray, invalid: np.ndarray, flat: np.ndarray,
                  max_increment: int, requested: float) -> float:
    """Largest increment that cannot reorder any genuine pair of neighbours.

    Cells outside the flats already drain somewhere; raising a flat by more than their
    smallest real drop could invert one of those pairs and inject a cycle into the flow
    graph. So the gradient is capped at a quarter of the smallest such drop -- big
    enough to order the flat, far too small to disturb the terrain.
    """
    drains = (~invalid) & (~flat)
    if not drains.any() or max_increment <= 0:
        return float(requested)

    z = np.where(invalid, -np.inf, filled)
    best = np.full(filled.shape, -np.inf)
    for di in (-1, 0, 1):
        for dj in (-1, 0, 1):
            if di == 0 and dj == 0:
                continue
            shifted = np.full(filled.shape, -np.inf)
            src = np.roll(np.roll(z, -di, axis=0), -dj, axis=1)
            sl_i = slice(max(0, -di), filled.shape[0] - max(0, di))
            sl_j = slice(max(0, -dj), filled.shape[1] - max(0, dj))
            shifted[sl_i, sl_j] = src[sl_i, sl_j]
            best = np.maximum(best, filled - shifted)

    drops = best[drains]
    drops = drops[np.isfinite(drops) & (drops > 0)]
    if drops.size == 0:
        return float(requested)
    return float(min(requested, 0.25 * drops.min() / max_increment))


def resolve_flats(
    filled: np.ndarray,
    invalid: np.ndarray,
    epsilon: float = DEFAULT_FLAT_EPSILON,
) -> np.ndarray:
    """Return a routing surface: `filled` plus a drainage gradient across its flats.

    Args:
        filled: depression-filled elevation (an `epsilon=0` fill, so its flats are
            genuinely level and its lake surfaces are exact).
        invalid: ocean mask from the original DEM.
        epsilon: requested gradient magnitude; automatically reduced if the terrain
            has drops small enough for it to matter.

    Returns:
        A surface identical to `filled` off the flats, and strictly descending across
        them. Use it for D8 routing only -- never for depth.
    """
    filled = np.ascontiguousarray(filled, dtype=np.float64)
    invalid = np.ascontiguousarray(invalid, dtype=np.bool_)

    flat, seed_low, seed_high = _classify(filled, invalid)
    if not flat.any():
        return filled.copy()

    d_low = _bfs(seed_low, flat)
    d_high = _bfs(seed_high, flat)

    # The two gradients are combined lexicographically, not added.
    #
    # Adding them with comparable weight is wrong, and quietly so: an outlet that sits
    # close to high ground picks up a large away-from-high term, which can leave it
    # higher than the middle of its own flat. Flow then converges on the interior
    # instead of the exit, the fill's work is undone, and those cells become pits that
    # swallow their catchment.
    #
    # So distance-from-outlet is the primary key with integer spacing, and the
    # away-from-high term is squeezed into [0, 1) as a tie-break. Any step towards the
    # outlet drops the primary by a full 1, which no secondary difference can offset,
    # so descent to the outlet is guaranteed. Within a tier of equal distance the
    # secondary still decides, steering flow off the upslope margin and towards the
    # middle of the flat -- which is what turns parallel chevrons into converging
    # channels.
    #
    # The `+ 1` matters: a flat's outlet cell drains into a neighbour that is *at the
    # same elevation* but which already has somewhere to go. Leaving the outlet at
    # distance 0 puts the two at the identical routing height, D8 finds no descent,
    # and the entire flat strands one cell short of its own exit. The offset lifts
    # every flat cell strictly above the ground it discharges onto.
    inc = np.where(d_low >= 0, d_low.astype(np.float64) + 1.0, 0.0)

    if seed_high.any():
        labels, n = ndimage.label(flat, structure=np.ones((3, 3), dtype=int))
        if n:
            index = np.arange(1, n + 1)
            reach = np.where(d_high >= 0, d_high, -1)
            per_flat_max = np.asarray(ndimage.maximum(reach, labels, index)).reshape(-1)
            # Per flat, so one huge flat cannot compress a small neighbour's tie-break.
            lut = np.concatenate([[0.0], np.maximum(per_flat_max, 0.0)])
            span = lut[labels]
            tie = np.where(d_high >= 0, (span - d_high) / (span + 1.0), 0.0)
            inc = inc + tie

    inc = np.where(flat & (d_low >= 0), inc, 0.0)
    max_inc = int(np.ceil(inc.max())) if inc.size else 0
    eps = _safe_epsilon(filled, invalid, flat, max_inc, float(epsilon))
    return filled + eps * inc
