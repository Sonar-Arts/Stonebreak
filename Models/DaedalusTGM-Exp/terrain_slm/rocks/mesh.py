"""Minimal triangle-mesh tools (no trimesh dependency): glTF reader, decimation, connected components.

glTF is Y-up; every mesh this module returns is in metres, Y-up, world space (node transforms applied).
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

_COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
_NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


@dataclass
class Mesh:
    name: str
    v: np.ndarray  # (N, 3) float64
    f: np.ndarray  # (M, 3) int64

    def bounds(self):
        return self.v.min(0), self.v.max(0)


def _quat_to_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def _node_matrix(n) -> np.ndarray:
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4)
    s = np.array(n.get("scale", [1, 1, 1]), dtype=np.float64)
    r = _quat_to_mat(n.get("rotation", [0, 0, 0, 1]))
    m[:3, :3] = r * s[None, :]
    m[:3, 3] = n.get("translation", [0, 0, 0])
    return m


def load_gltf(path: Path) -> list[Mesh]:
    """Every mesh node of a .gltf (external .bin buffers), node transforms applied."""
    path = Path(path)
    g = json.loads(path.read_text())
    bufs = [np.frombuffer((path.parent / b["uri"]).read_bytes(), dtype=np.uint8) for b in g["buffers"]]

    def accessor(i):
        a = g["accessors"][i]
        bv = g["bufferViews"][a["bufferView"]]
        dt = np.dtype(_COMP[a["componentType"]])
        nc = _NCOMP[a["type"]]
        off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0)
        buf = bufs[bv["buffer"]]
        if stride and stride != dt.itemsize * nc:
            rows = [np.frombuffer(buf[off + k * stride: off + k * stride + dt.itemsize * nc].tobytes(), dt)
                    for k in range(a["count"])]
            return np.stack(rows)
        arr = np.frombuffer(buf[off: off + a["count"] * nc * dt.itemsize].tobytes(), dt)
        return arr.reshape(a["count"], nc) if nc > 1 else arr

    out: list[Mesh] = []

    def walk(ni, parent):
        n = g["nodes"][ni]
        m = parent @ _node_matrix(n)
        if "mesh" in n:
            vs, fs, base = [], [], 0
            for prim in g["meshes"][n["mesh"]]["primitives"]:
                if prim.get("mode", 4) != 4:
                    continue
                v = accessor(prim["attributes"]["POSITION"]).astype(np.float64)
                f = (accessor(prim["indices"]).astype(np.int64).reshape(-1, 3) if "indices" in prim
                     else np.arange(len(v), dtype=np.int64).reshape(-1, 3))
                v = v @ m[:3, :3].T + m[:3, 3]
                vs.append(v)
                fs.append(f + base)
                base += len(v)
            if vs:
                out.append(Mesh(n.get("name", f"node{ni}"), np.concatenate(vs), np.concatenate(fs)))
        for c in n.get("children", []):
            walk(c, m)

    scene = g["scenes"][g.get("scene", 0)]
    for ni in scene["nodes"]:
        walk(ni, np.eye(4))
    return out


def weld(mesh: Mesh, tol: float) -> Mesh:
    """Merge vertices closer than `tol` (grid snap) and drop degenerate / duplicate faces."""
    key = np.round(mesh.v / tol).astype(np.int64)
    uniq, inv = np.unique(key, axis=0, return_inverse=True)
    inv = inv.reshape(-1)
    v = np.zeros((len(uniq), 3))
    np.add.at(v, inv, mesh.v)
    v /= np.bincount(inv, minlength=len(uniq))[:, None]
    f = inv[mesh.f]
    ok = (f[:, 0] != f[:, 1]) & (f[:, 1] != f[:, 2]) & (f[:, 0] != f[:, 2])
    f = f[ok]
    fs = np.sort(f, axis=1)
    _, keep = np.unique(fs, axis=0, return_index=True)
    f = f[np.sort(keep)]
    return Mesh(mesh.name, v, f)


def decimate(mesh: Mesh, cells: int) -> Mesh:
    """Vertex-clustering decimation onto a `cells`-per-longest-axis grid (keeps the shape to ~1 cell)."""
    lo, hi = mesh.bounds()
    return weld(mesh, float((hi - lo).max()) / cells)


def components(mesh: Mesh) -> list[Mesh]:
    """Split into face-connected (shared vertex) components, largest first."""
    n = len(mesh.v)
    # min-label propagation over edges with pointer jumping
    e = np.concatenate([mesh.f[:, [0, 1]], mesh.f[:, [1, 2]], mesh.f[:, [2, 0]]])
    lab = np.arange(n)
    while True:
        m = np.minimum(lab[e[:, 0]], lab[e[:, 1]])
        new = lab.copy()
        np.minimum.at(new, e[:, 0], m)
        np.minimum.at(new, e[:, 1], m)
        new = new[new]  # pointer jumping
        if np.array_equal(new, lab):
            break
        lab = new
    flab = lab[mesh.f[:, 0]]
    out = []
    for l in np.unique(flab):
        fsel = mesh.f[flab == l]
        used, inv = np.unique(fsel, return_inverse=True)
        out.append(Mesh(f"{mesh.name}#{len(out)}", mesh.v[used], inv.reshape(-1, 3)))
    out.sort(key=lambda m: -len(m.f))
    return out


def area(mesh: Mesh) -> float:
    a = mesh.v[mesh.f]
    return float(0.5 * np.linalg.norm(np.cross(a[:, 1] - a[:, 0], a[:, 2] - a[:, 0]), axis=1).sum())


def boundary_edge_fraction(mesh: Mesh) -> float:
    """Share of edges used by exactly one face (0 = closed surface)."""
    e = np.sort(np.concatenate([mesh.f[:, [0, 1]], mesh.f[:, [1, 2]], mesh.f[:, [2, 0]]]), axis=1)
    _, cnt = np.unique(e, axis=0, return_counts=True)
    return float((cnt == 1).sum() / max(len(cnt), 1))
