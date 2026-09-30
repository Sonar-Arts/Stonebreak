"""D8 flow routing and upslope-area accumulation.

Reference implementation: upstream's `d8_flow()` / `flow_accumulation()` in
terrain_diffusion/inference/postprocessing.py (unreachable from here -- that module
imports torch). Three defects found in it are fixed rather than carried over:

  1. Diagonal flats did not route. `d8_flow(tol=1e-3)` discarded any slope below
     `tol`, but a diagonal step across a flat filled with epsilon=1e-3 has slope
     1e-3/sqrt(2) = 7.07e-4 < tol, so it was zeroed. Diagonal drainage across every
     filled flat silently died. Here descent is compared against 0 on the *drop*,
     not a distance-normalised slope against a fixed tolerance, so the flat gradient
     the fill injected is respected at any epsilon.
  2. Border cells could point at themselves. Upstream padded `mode='edge'` and then
     clipped the receiver indices, so an edge cell whose lowest neighbour lay outside
     resolved to itself -- a self-loop, which hangs a topological accumulation. Here
     anything outside the array is unreachable (infinitely high, never a valid
     receiver), so a border cell routes to its lowest in-domain neighbour and becomes
     terminal only when it has none -- i.e. when water genuinely exits the tile.
  3. The ocean mask has to come from the *original* DEM. Routing runs on the filled
     surface, where no land is <= 0 any more, so recomputing the mask there would
     classify nothing as sea. Callers pass the pre-fill mask in.

Accumulation itself is rewritten. Upstream ran a Python `for` loop over every cell
in elevation-sorted order (~1e6 iterations at 1024**2, minutes of runtime), and that
ordering is fragile across filled flats where many cells share an elevation. This is
a level-synchronous Kahn's algorithm over the D8 graph: repeatedly process the whole
in-degree-zero frontier in one vectorised pass. Total work is O(N) with the iteration
count set by the longest flow path, and it needs no numba.
"""
from __future__ import annotations

from typing import Iterator

import numpy as np

# Cardinals first so that ties resolve to a cardinal step, which keeps flow on
# axis-aligned terrain (test fixtures depend on this) rather than wandering diagonally.
_DY = np.array([-1, 1, 0, 0, -1, -1, 1, 1], dtype=np.int64)
_DX = np.array([0, 0, -1, 1, -1, 1, -1, 1], dtype=np.int64)
_DIST = np.array([1.0, 1.0, 1.0, 1.0, np.sqrt(2.0), np.sqrt(2.0), np.sqrt(2.0), np.sqrt(2.0)])

# Public aliases. `basins.py` stores a receiver as an *index into these* rather than
# as a flat cell index, so that a direction stays meaningful after the array it was
# computed on is cropped. Both encodings then have exactly one definition, here.
D8_DY, D8_DX = _DY, _DX


def d8_receivers(filled: np.ndarray, invalid: np.ndarray) -> np.ndarray:
    """Steepest-descent D8 receiver for every cell.

    Args:
        filled: (H, W) depression-filled elevation.
        invalid: (H, W) ocean mask from the *original* DEM.

    Returns:
        (H*W,) int64 flat index of each cell's downstream neighbour, or -1 where the
        cell is terminal -- ocean, or land that discharges to sea or off the tile edge.
        The result is a forest: every edge strictly descends, so it cannot contain a cycle.
    """
    h, w = filled.shape
    z = np.asarray(filled, dtype=np.float64)

    # Outside the array is infinitely high and never ocean. That makes it an
    # impossible receiver without making it a sink, so an edge cell still routes to
    # its lowest in-domain neighbour; only a cell with no descending neighbour at all
    # terminates. Padding it as a sink instead would make *every* border cell
    # terminal and truncate flow arriving at the tile edge.
    zp = np.full((h + 2, w + 2), np.inf, dtype=np.float64)
    ip = np.zeros((h + 2, w + 2), dtype=bool)
    zp[1:-1, 1:-1] = z
    ip[1:-1, 1:-1] = invalid

    best_slope = np.full((h, w), -np.inf, dtype=np.float64)
    best_k = np.zeros((h, w), dtype=np.int64)
    touches_ocean = np.zeros((h, w), dtype=bool)

    for k in range(8):
        dy, dx = int(_DY[k]), int(_DX[k])
        nb_z = zp[1 + dy: 1 + dy + h, 1 + dx: 1 + dx + w]
        nb_inv = ip[1 + dy: 1 + dy + h, 1 + dx: 1 + dx + w]

        touches_ocean |= nb_inv

        drop = z - nb_z
        # Strict descent only. Equal-elevation neighbours must not become edges or
        # the graph stops being acyclic; the fill's epsilon guarantees a real drop
        # exists wherever drainage is possible.
        slope = np.where(nb_inv | (drop <= 0.0), -np.inf, drop / _DIST[k])

        better = slope > best_slope
        best_slope = np.where(better, slope, best_slope)
        best_k = np.where(better, k, best_k)

    rows = np.arange(h, dtype=np.int64)[:, None]
    cols = np.arange(w, dtype=np.int64)[None, :]
    recv = (rows + _DY[best_k]) * w + (cols + _DX[best_k])

    # Terminal where water reaches the sea, where no descending neighbour exists at
    # all (water exits the tile, or a genuine pit the fill should have removed -- but
    # never trust that), and on the ocean itself.
    terminal = touches_ocean | ~np.isfinite(best_slope) | invalid
    recv = np.where(terminal, -1, recv).astype(np.int64).ravel()
    return recv


def topological_levels(recv: np.ndarray, valid: np.ndarray) -> Iterator[np.ndarray]:
    """Yield the D8 graph's cells in upstream-first order, one whole level per step.

    Level-synchronous Kahn's algorithm: each yielded array holds every land cell whose
    upstream contributors have all been yielded already, so a consumer can process a
    level in one vectorised pass and be certain the values it reads are final. The
    iteration count is the longest flow path, not the cell count.

    Separate from `flow_accumulation` because the order is worth more than the one
    quantity it was written for. Accumulation pushes an upslope sum downstream; the
    L1 water surface pushes a monotonicity constraint downstream (`hydrology/local.py`)
    over exactly the same graph in exactly the same order, and re-deriving the order
    there would be a second implementation of the one thing that must not disagree.

    A consumer *must* drain the generator: the check that every land cell was reached
    -- i.e. that the fill really did leave an acyclic, strictly descending graph --
    can only run once the last level has been consumed, and is raised from here.
    """
    h, w = valid.shape
    n = h * w
    if recv.shape != (n,):
        raise ValueError(f"recv has {recv.shape}, expected ({n},) for a {h}x{w} grid")

    flat_valid = valid.ravel()

    # In-degree over land edges only, so ocean cells never gate the frontier.
    src = np.flatnonzero((recv >= 0) & flat_valid)
    indeg = np.bincount(recv[src], minlength=n)

    frontier = np.flatnonzero(flat_valid & (indeg == 0))
    processed = 0
    while frontier.size:
        processed += frontier.size
        yield frontier

        tgt = recv[frontier]
        tgt = tgt[tgt >= 0]
        if tgt.size == 0:
            break
        np.subtract.at(indeg, tgt, 1)
        frontier = np.unique(tgt[indeg[tgt] == 0])

    n_land = int(flat_valid.sum())
    if processed != n_land:
        # Only reachable if the receiver graph contains a cycle, which would mean the
        # fill left a non-descending edge. Fail loudly rather than return a silent
        # under-count of every downstream river.
        raise RuntimeError(
            f"flow graph is not acyclic: {n_land - processed} of {n_land} land cells "
            "were never drained. The depression fill did not produce a strictly "
            "descending surface."
        )


def flow_accumulation(
    recv: np.ndarray,
    valid: np.ndarray,
    weights: np.ndarray | None = None,
) -> np.ndarray:
    """Total upslope contribution draining through each cell, itself included.

    Args:
        recv: (H*W,) receiver indices from `d8_receivers`.
        valid: (H, W) land mask (the complement of the ocean mask).
        weights: (H, W) per-cell contribution. Default 1.0 per land cell, making the
            result an upslope *cell count*. Pass e.g. precipitation to weight by runoff,
            or -- as `hydrology/local.py` does -- the drainage area arriving from
            outside the window, which is the same operation seen from a tile's edge.

    Returns:
        (H, W) float64 accumulation. Ocean cells are 0.
    """
    if weights is None:
        acc = valid.astype(np.float64).ravel().copy()
    else:
        acc = np.where(valid, np.asarray(weights, dtype=np.float64), 0.0).ravel().copy()

    for level in topological_levels(recv, valid):
        tgt = recv[level]
        live = tgt >= 0
        if live.any():
            np.add.at(acc, tgt[live], acc[level[live]])

    return acc.reshape(valid.shape)
