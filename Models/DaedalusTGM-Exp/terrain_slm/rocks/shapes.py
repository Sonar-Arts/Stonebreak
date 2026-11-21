"""Generated SDF -> game block shape: resampling to block dims, plausibility clean-up, sink, flags, material hints."""
from __future__ import annotations

import numpy as np
from scipy import ndimage

from . import sbrb

# SBRB size classes the bank provides (by largest horizontal extent, P-boulders.md): medium 4-5, large 6-8, erratic 9-15
CLASS_DXZ = {2: (4, 5), 3: (6, 8), 4: (9, 14)}


def largest_component(occ: np.ndarray) -> np.ndarray:
    lab, n = ndimage.label(occ)
    if n <= 1:
        return occ
    sizes = ndimage.sum(occ, lab, range(1, n + 1))
    return lab == 1 + int(np.argmax(sizes))


def plausibility(sdf: np.ndarray) -> dict:
    """At model resolution: share of the solid in the largest 6-component (1.0 = no floating voxels), cavities,
    bottom contact."""
    occ = sdf < 0
    tot = int(occ.sum())
    if tot == 0:
        return {"solid": 0, "main_frac": 0.0, "cavity_frac": 0.0, "n_components": 0}
    lab, n = ndimage.label(occ)
    main = largest_component(occ)
    filled = ndimage.binary_fill_holes(main)
    return {"solid": tot, "main_frac": float(main.sum() / tot), "n_components": int(n),
            "cavity_frac": float((filled.sum() - main.sum()) / max(filled.sum(), 1))}


def resample_blocks(sdf: np.ndarray, dxz: int, max_dy: int = sbrb.MAX_DY, ss: int = 3,
                    flat_base: bool = False) -> np.ndarray | None:
    """Scale the shape so its largest horizontal extent spans `dxz` blocks; occupancy = majority of ss^3 sub-samples
    of the trilinear SDF inside each block. Returns occ[x, y, z] (y up, y = 0 bottom) or None."""
    occ = sdf < 0
    if occ.sum() == 0:
        return None
    idx = np.argwhere(occ)
    lo, hi = idx.min(0).astype(float), idx.max(0).astype(float) + 1
    ext = hi - lo
    s = dxz / max(ext[0], ext[2])  # blocks per model cell
    dims = np.maximum(1, np.round(ext * s).astype(int))
    dims[1] = min(dims[1], max_dy)
    dims[0], dims[2] = min(dims[0], sbrb.MAX_DXZ), min(dims[2], sbrb.MAX_DXZ)
    # sample positions in model cells (cell centres at i + 0.5 -> map_coordinates index i)
    sub = (np.arange(ss) + 0.5) / ss
    axes = []
    for k in range(3):
        b = (np.arange(dims[k])[:, None] + sub[None, :]).reshape(-1)
        axes.append(lo[k] + b * ext[k] / dims[k] - 0.5)
    X, Y, Z = np.meshgrid(*axes, indexing="ij")
    v = ndimage.map_coordinates(sdf, [X, Y, Z], order=1, mode="nearest")
    frac = (v < 0).reshape(dims[0], ss, dims[1], ss, dims[2], ss).mean((1, 3, 5))
    out = frac >= 0.5
    out = clean_blocks(out)
    if out is None:
        return None
    if flat_base:
        out = flatten_base(out)
    return out


def clean_blocks(occ: np.ndarray) -> np.ndarray | None:
    if occ.sum() == 0:
        return None
    occ = ndimage.binary_fill_holes(largest_component(occ))
    idx = np.argwhere(occ)
    lo, hi = idx.min(0), idx.max(0) + 1
    return occ[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]]


def flatten_base(occ: np.ndarray) -> np.ndarray:
    """Cut the rows below the first row holding >= 45 % of the widest row's area (a seat on the terrain)."""
    area = occ.sum((0, 2))
    k = int(np.argmax(area >= 0.45 * area.max()))
    k = min(k, max(0, occ.shape[1] // 3))
    out = clean_blocks(occ[:, k:, :])
    return out if out is not None else occ


def sink_rows(occ: np.ndarray, rng: np.random.Generator | None = None, flat_base: bool = False) -> int:
    """Rows meant below ground: the narrow underside (rows whose area < 50 % of the widest row) and, so a boulder
    looks bedded rather than set down, a random 12-30 % of its height (0-12 % for a cut flat base); <= dy / 3."""
    area = occ.sum((0, 2))
    k = int(np.argmax(area >= 0.5 * area.max()))
    dy = occ.shape[1]
    if rng is not None:
        lo, hi = (0.0, 0.12) if flat_base else (0.12, 0.30)
        k = max(k, int(round(rng.uniform(lo, hi) * dy)))
    return int(min(k, dy // 3, dy - 1))


def shape_flags(occ: np.ndarray) -> int:
    dx, dy, dz = occ.shape
    area = occ.sum((0, 2))
    f = 0
    if area[-1] >= 0.4 * area.max() and dy >= 2:
        f |= sbrb.FLAG_FLAT_TOP
    if dy <= max(dx, dz) / 2:
        f |= sbrb.FLAG_SLAB
    return f


def material_hints(occ: np.ndarray, rng: np.random.Generator, moss_share=(0.04, 0.12),
                   rough_share=(0.06, 0.14)) -> np.ndarray:
    """Per-voxel hints with a per-shape quota (share of the volume, drawn from the given ranges):
    1 = moss: the upward-exposed voxels that are most sheltered (ledges, hollows: high solid share around them), low on
        the rock and on its -z ('north') side rank first;
    2 = rough: the exposed side voxels on the sharpest convex edges (lowest solid share around them) - fresh fracture.
    Everything else 0 (body)."""
    dx, dy, dz = occ.shape
    p = np.pad(occ, 1)
    up_air = ~p[1:-1, 2:, 1:-1] & occ
    side = occ & ~up_air & (~p[2:, 1:-1, 1:-1] | ~p[:-2, 1:-1, 1:-1] | ~p[1:-1, 1:-1, 2:] | ~p[1:-1, 1:-1, :-2])
    occl = ndimage.uniform_filter(occ.astype(np.float32), size=3, mode="constant")
    yrel = np.broadcast_to(np.arange(dy)[None, :, None] / max(dy - 1, 1), occ.shape)
    north = np.broadcast_to((np.arange(dz)[None, None, :] + 0.5) / dz < 0.5, occ.shape)
    noise = rng.random(occ.shape) * 0.25
    vol = int(occ.sum())
    h = np.zeros(occ.shape, np.uint8)

    def take(mask, score, share, val):
        n = min(int(mask.sum()), int(round(rng.uniform(*share) * vol)))
        if n <= 0:
            return
        idx = np.flatnonzero(mask)
        best = idx[np.argsort(-score.reshape(-1)[idx], kind="stable")[:n]]
        h.reshape(-1)[best] = val

    take(side, -occl + noise, rough_share, 2)
    take(up_air & (h == 0), occl - 0.5 * yrel + 0.2 * north + noise, moss_share, 1)
    return np.where(occ, h, 0).astype(np.uint8)
