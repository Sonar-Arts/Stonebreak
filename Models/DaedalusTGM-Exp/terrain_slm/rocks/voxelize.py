"""Mesh -> occupancy / SDF grids.

`winding_occupancy` uses the generalized winding number (Jacobson et al. 2013): inside where the solid angle the
surface subtends sums to >= 0.5 x 4 pi. Robust to small holes and to scans whose base was never captured.
`heightfield` rasterises the upper surface of an open terrain scan (for outcrops cut out of coast/field scans).
"""
from __future__ import annotations

import numpy as np
import torch
from scipy import ndimage

from .mesh import Mesh


def winding_numbers(v: np.ndarray, f: np.ndarray, pts: np.ndarray, device="cpu",
                    pt_chunk: int = 2048, face_chunk: int = 16384) -> np.ndarray:
    """Generalized winding number of every point (Van Oosterom-Strackee solid angle per triangle)."""
    dev = torch.device(device)
    tri = torch.as_tensor(v[f], dtype=torch.float32, device=dev)  # (F,3,3)
    P = torch.as_tensor(pts, dtype=torch.float32, device=dev)
    out = torch.zeros(len(P), dtype=torch.float64, device=dev)
    for i in range(0, len(P), pt_chunk):
        p = P[i:i + pt_chunk]
        acc = torch.zeros(len(p), dtype=torch.float64, device=dev)
        for j in range(0, len(tri), face_chunk):
            t = tri[j:j + face_chunk]
            a = t[None, :, 0] - p[:, None]
            b = t[None, :, 1] - p[:, None]
            c = t[None, :, 2] - p[:, None]
            la, lb, lc = a.norm(dim=-1), b.norm(dim=-1), c.norm(dim=-1)
            det = (a * torch.cross(b, c, dim=-1)).sum(-1)
            den = la * lb * lc + (a * b).sum(-1) * lc + (b * c).sum(-1) * la + (c * a).sum(-1) * lb
            acc += torch.atan2(det, den).sum(1).double()
        out[i:i + pt_chunk] = acc / (2 * np.pi)
    return out.cpu().numpy()


def grid_points(lo: np.ndarray, hi: np.ndarray, res: int, pad: int = 2):
    """Cell centres of a cubic grid of `res`^3 that holds [lo, hi] (longest axis spans res - 2*pad cells),
    centred on x/z and starting at the bottom (y = lo.y) + pad. Returns (points (res^3,3) in (x,y,z) order with
    index (x, y, z) -> x*res*res + y*res + z, cell size, origin)."""
    ext = hi - lo
    h = float(ext.max()) / (res - 2 * pad)
    origin = np.array([(lo[0] + hi[0]) / 2 - res * h / 2, lo[1] - pad * h, (lo[2] + hi[2]) / 2 - res * h / 2])
    ax = (np.arange(res) + 0.5) * h
    X, Y, Z = np.meshgrid(origin[0] + ax, origin[1] + ax, origin[2] + ax, indexing="ij")
    return np.stack([X, Y, Z], -1).reshape(-1, 3), h, origin


def winding_occupancy(mesh: Mesh, res: int = 64, device="cpu", pad: int = 2, thresh: float = 0.5):
    lo, hi = mesh.bounds()
    pts, h, origin = grid_points(lo, hi, res, pad)
    w = winding_numbers(mesh.v, mesh.f, pts, device=device)
    occ = (w.reshape(res, res, res) >= thresh)
    return occ, h, origin


def sdf_from_occupancy(occ: np.ndarray) -> np.ndarray:
    """Signed distance in cells (negative inside), from the cell-centre EDT of both sides."""
    inside = ndimage.distance_transform_edt(occ)
    outside = ndimage.distance_transform_edt(~occ)
    return (outside - inside + np.where(occ, 0.5, -0.5)).astype(np.float32)


def downsample_sdf(sdf: np.ndarray, factor: int) -> np.ndarray:
    r = sdf.shape[0] // factor
    return sdf.reshape(r, factor, r, factor, r, factor).mean((1, 3, 5)) / factor


def surface_samples(mesh: Mesh, n: int, rng: np.random.Generator) -> np.ndarray:
    a = mesh.v[mesh.f]
    ar = 0.5 * np.linalg.norm(np.cross(a[:, 1] - a[:, 0], a[:, 2] - a[:, 0]), axis=1)
    idx = rng.choice(len(ar), size=n, p=ar / ar.sum())
    u, w = rng.random(n), rng.random(n)
    flip = u + w > 1
    u[flip], w[flip] = 1 - u[flip], 1 - w[flip]
    t = a[idx]
    return t[:, 0] + u[:, None] * (t[:, 1] - t[:, 0]) + w[:, None] * (t[:, 2] - t[:, 0])


def heightfield(mesh: Mesh, cell: float, rng: np.random.Generator, samples_per_cell: float = 6.0):
    """Max-Y raster of the mesh on an x/z grid of `cell` metres (NaN = no surface). Returns (hf[x,z], x0, z0)."""
    lo, hi = mesh.bounds()
    nx = int(np.ceil((hi[0] - lo[0]) / cell)) + 1
    nz = int(np.ceil((hi[2] - lo[2]) / cell)) + 1
    n = int(nx * nz * samples_per_cell)
    hf = np.full(nx * nz, -np.inf)
    for i in range(0, n, 4_000_000):
        p = surface_samples(mesh, min(4_000_000, n - i), rng)
        ix = np.clip(((p[:, 0] - lo[0]) / cell).astype(np.int64), 0, nx - 1)
        iz = np.clip(((p[:, 2] - lo[2]) / cell).astype(np.int64), 0, nz - 1)
        np.maximum.at(hf, ix * nz + iz, p[:, 1])
    hf = hf.reshape(nx, nz)
    hf[~np.isfinite(hf)] = np.nan
    return hf, lo[0], lo[2]
