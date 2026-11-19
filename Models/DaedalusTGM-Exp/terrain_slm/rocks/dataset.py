"""Build the rock shape dataset from the downloaded scans.

Two kinds of shapes:
- `closed`  : a whole scanned rock (one mesh node / component of a rock or rock-set asset), voxelised with winding
              numbers at 64^3 (keeps overhangs and the real underside).
- `outcrop` : a rock standing out of an open terrain scan (coast / field scans): the upper surface is rasterised as a
              height field, the ground under it estimated by a grey opening, every blob above the ground becomes a
              shape from its lowest ground level up (flat base, no overhangs - how a half-buried boulder looks).

Every shape is stored in a canonical frame: Y up, normalised so its longest extent spans 60 of 64 cells (x/z
centred, resting 2 cells above the grid floor), as a 32^3 truncated SDF (cells of the 32 grid, negative inside) plus
its 64^3 occupancy (bit-packed) and descriptors -> style / size-bin labels.

    python -m terrain_slm.rocks.dataset --out /mnt/extrastorage/terrain-slm-data/rocks/rocks_v1.npz --device cuda:0
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
from scipy import ndimage
from scipy.spatial import ConvexHull

from . import mesh as M
from . import voxelize as V
from .sources import RAW_DIR

OUT_DEFAULT = Path("/mnt/extrastorage/terrain-slm-data/rocks/rocks_v1.npz")
RES_HI = 64
RES = 32
TRUNC = 4.0  # SDF truncation, cells of the 32 grid

# assets whose meshes are individual closed rocks (verified: boundary-edge fraction <= 0.016)
CLOSED_ASSETS = [
    "boulder_01", "moon_rock_01", "moon_rock_02", "moon_rock_03", "moon_rock_04", "moon_rock_05", "moon_rock_06",
    "moon_rock_07", "namaqualand_boulder_02", "namaqualand_boulder_03", "namaqualand_boulder_04",
    "namaqualand_boulder_05", "namaqualand_boulder_06", "namaqualand_boulders_01", "namaqualand_rocks_01",
    "namaqualand_stones_01", "rock_07", "rock_09", "rock_moss_set_01", "rock_moss_set_02", "stone_01",
]
# open terrain scans with rocks standing out of the ground
OUTCROP_ASSETS = [
    "coast_land_rocks_02", "coast_land_rocks_03", "coast_land_rocks_04", "coast_line_01", "coast_line_02",
    "coast_rocks_01", "coast_rocks_02", "coast_rocks_03", "coast_rocks_05", "sand_rocks_small_01",
]
# downloaded but not used (vertical cliff / rock-face shells, not free-standing rocks):
# coastal_cliff_04, mountainside, namaqualand_cliff_01, namaqualand_cliff_02, rock_face_01, rock_face_02

STYLES = ["rounded", "angular", "slab", "tor"]
SIZE_BINS = ["stone<0.6m", "rock0.6-2.5m", "boulder>2.5m"]


def _merge(meshes: list[M.Mesh], name: str) -> M.Mesh:
    vs, fs, base = [], [], 0
    for m in meshes:
        vs.append(m.v)
        fs.append(m.f + base)
        base += len(m.v)
    return M.Mesh(name, np.concatenate(vs), np.concatenate(fs))


def clean_occupancy(occ: np.ndarray) -> np.ndarray:
    """Largest 6-connected component, internal cavities filled."""
    lab, n = ndimage.label(occ)
    if n == 0:
        return occ
    if n > 1:
        sizes = ndimage.sum(occ, lab, range(1, n + 1))
        occ = lab == (1 + int(np.argmax(sizes)))
    return ndimage.binary_fill_holes(occ)


def canonical_occupancy(occ: np.ndarray, res: int = RES_HI, pad: int = 2) -> np.ndarray:
    """Re-place a shape in a fresh res^3 grid: longest extent = res - 2*pad cells, x/z centred, base at y = pad."""
    idx = np.argwhere(occ)
    lo, hi = idx.min(0), idx.max(0) + 1
    crop = occ[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]].astype(np.float32)
    s = (res - 2 * pad) / max(crop.shape)
    new = np.maximum(1, np.round(np.array(crop.shape) * s).astype(int))
    z = ndimage.zoom(crop, new / np.array(crop.shape), order=1) >= 0.5
    out = np.zeros((res, res, res), bool)
    ox, oz = (res - z.shape[0]) // 2, (res - z.shape[2]) // 2
    out[ox:ox + z.shape[0], pad:pad + z.shape[1], oz:oz + z.shape[2]] = z
    return clean_occupancy(out)


def sdf32(occ64: np.ndarray) -> np.ndarray:
    s = V.downsample_sdf(V.sdf_from_occupancy(occ64), RES_HI // RES)
    return np.clip(s, -TRUNC, TRUNC).astype(np.float32)


def descriptors(occ: np.ndarray) -> dict:
    idx = np.argwhere(occ).astype(np.float64)
    ext = idx.max(0) - idx.min(0) + 1
    horiz = max(ext[0], ext[2])
    vol = float(occ.sum())
    # exposed faces = surface area in cell faces
    a = 0
    for ax in range(3):
        d = np.diff(np.pad(occ, 1).astype(np.int8), axis=ax)
        a += int(np.abs(d).sum())
    surf = idx[ndimage.binary_erosion(occ)[tuple(idx.astype(int).T)] == 0]
    try:
        hull = ConvexHull(surf).volume
    except Exception:  # noqa: BLE001 - degenerate shapes
        hull = vol
    fp = occ.any(axis=1)
    fpi = np.argwhere(fp).astype(np.float64)
    try:
        fp_hull = ConvexHull(np.concatenate([fpi + o for o in ([0, 0], [1, 0], [0, 1], [1, 1])])).volume
    except Exception:  # noqa: BLE001
        fp_hull = float(fp.sum())
    sdf = V.sdf_from_occupancy(occ)
    band = np.abs(sdf) < 2.0
    rough = float(np.abs(sdf - ndimage.gaussian_filter(sdf, 2.0))[band].mean())
    return {
        "ext": ext.tolist(), "flatness": float(ext[1] / horiz), "elongation": float(min(ext[0], ext[2]) / horiz),
        "solidity": float(vol / max(hull, vol)), "sphericity": float(np.pi ** (1 / 3) * (6 * vol) ** (2 / 3) / a),
        "roughness": rough, "volume_frac": vol / occ.size, "footprint_solidity": float(fp.sum() / max(fp_hull, 1.0)),
    }


def style_of(d: dict, rough_split: float) -> int:
    if d["flatness"] < 0.45:
        return STYLES.index("slab")
    if d["flatness"] > 1.0:
        return STYLES.index("tor")
    return STYLES.index("rounded") if d["roughness"] < rough_split else STYLES.index("angular")


def size_bin(max_dim_m: float) -> int:
    return 0 if max_dim_m < 0.6 else (1 if max_dim_m < 2.5 else 2)


def closed_shapes(raw: Path, device: str):
    for aid in CLOSED_ASSETS:
        nodes = M.load_gltf(raw / "polyhaven" / aid / f"{aid}.gltf")
        parts = [c for n in nodes for c in M.components(M.decimate(n, 160)) if len(c.f) >= 200]
        for k, part in enumerate(parts):
            t = time.time()
            occ, h, _ = V.winding_occupancy(part, res=RES_HI, device=device)
            occ = clean_occupancy(occ)
            if occ.sum() < 50:
                continue
            lo, hi = part.bounds()
            yield {"asset": aid, "part": k, "kind": "closed", "dims_m": (hi - lo).tolist(),
                   "occ": canonical_occupancy(occ), "secs": time.time() - t}


def outcrop_shapes(raw: Path, rng: np.random.Generator, min_h: float = 0.25, max_dim: float = 9.0,
                   min_dim: float = 0.35):
    for aid in OUTCROP_ASSETS:
        m = _merge(M.load_gltf(raw / "polyhaven" / aid / f"{aid}.gltf"), aid)
        lo, hi = m.bounds()
        cell = max(0.02, max(hi[0] - lo[0], hi[2] - lo[2]) / 600)
        hf, x0, z0 = V.heightfield(m, cell, rng)
        valid = np.isfinite(hf)
        # the scan footprint: sampling leaves speckle holes on steep rock sides - only the outer border counts
        footprint = ndimage.binary_fill_holes(ndimage.binary_closing(valid, structure=_disk(3), iterations=2))
        filled = hf.copy()
        if (~valid).any():  # nearest-neighbour fill for the opening only
            _, (ix, iz) = ndimage.distance_transform_edt(~valid, return_indices=True)
            filled = hf[ix, iz]
        # ground = grey opening with a ~3.2 m disk (removes rocks up to that size), on a ~0.1 m grid for speed
        f = max(1, int(round(0.1 / cell)))
        cx, cz = (filled.shape[0] // f) * f, (filled.shape[1] // f) * f
        coarse = filled[:cx, :cz].reshape(cx // f, f, cz // f, f).min((1, 3))
        r = max(3, int(round(1.6 / (cell * f))))
        g = ndimage.grey_opening(coarse, footprint=_disk(r))
        g = ndimage.uniform_filter(g, size=max(3, r // 2))
        ground = ndimage.zoom(g, (filled.shape[0] / g.shape[0], filled.shape[1] / g.shape[1]), order=1)
        ground = np.pad(ground, ((0, filled.shape[0] - ground.shape[0]), (0, filled.shape[1] - ground.shape[1])),
                        mode="edge")[:filled.shape[0], :filled.shape[1]]
        rel = np.where(footprint, filled - ground, 0.0)
        mask = ndimage.binary_opening(rel > 0.10, structure=_disk(max(1, int(0.06 / cell))))
        lab, n = split_touching(rel, mask, min_px=int((0.3 / cell) ** 2), grow_px=max(2, int(0.2 / cell)))
        objs = ndimage.find_objects(lab)
        got = 0
        for li, sl in enumerate(objs, start=1):
            if sl is None:
                continue
            sub = lab[sl] == li
            # a blob touching missing data or the scan border is incomplete
            ex = tuple(slice(max(0, s.start - 2), s.stop + 2) for s in sl)
            if (~footprint[ex]).any() or sl[0].start == 0 or sl[1].start == 0 or sl[0].stop >= hf.shape[0] or \
                    sl[1].stop >= hf.shape[1]:
                continue
            top = np.where(sub, filled[sl], -np.inf)
            base = float(ground[sl][sub].min())
            height = float(top.max() - base)
            wx, wz = sub.shape[0] * cell, sub.shape[1] * cell
            if height < min_h or max(wx, wz) > max_dim or max(wx, wz, height) < min_dim:
                continue
            # 64^3 occupancy: scale so the longest of (wx, height, wz) spans 60 cells
            s = (RES_HI - 4) / max(wx, wz, height)
            nx, nz = max(1, int(round(wx * s))), max(1, int(round(wz * s)))
            topz = ndimage.zoom(np.where(sub, top - base, 0.0), (nx / sub.shape[0], nz / sub.shape[1]), order=1)
            subz = ndimage.zoom(sub.astype(np.float32), (nx / sub.shape[0], nz / sub.shape[1]), order=1) >= 0.5
            hcells = np.where(subz, np.round(topz * s), 0).astype(int)
            occ = np.zeros((RES_HI, RES_HI, RES_HI), bool)
            ox, oz = (RES_HI - nx) // 2, (RES_HI - nz) // 2
            ys = np.arange(RES_HI)[None, :, None]
            col = (ys >= 2) & (ys < 2 + hcells[:, None, :])
            occ[ox:ox + nx, :, oz:oz + nz] = col
            occ = clean_occupancy(occ)
            if occ.sum() < 50:
                continue
            got += 1
            yield {"asset": aid, "part": li, "kind": "outcrop", "dims_m": [wx, height, wz],
                   "occ": canonical_occupancy(occ), "secs": 0.0}
        print(f"[dataset] {aid}: {got} outcrops (cell {cell:.3f} m, {n} blobs)")


def split_touching(rel: np.ndarray, mask: np.ndarray, min_px: int, grow_px: int,
                   levels=(0.15, 0.25, 0.35, 0.5, 0.7, 0.9)) -> tuple[np.ndarray, int]:
    """Label blobs, splitting rocks that touch: a blob that falls apart into >= 2 big parts at a higher height
    threshold L is split there (recursively). Each part keeps the blob pixels nearest to it, but only within
    `grow_px` of the part and above L/2 (so the gravel / scree a clump stands in is not glued onto a rock)."""
    out = np.zeros(mask.shape, np.int32)
    n = 0
    stack = []
    lab, k = ndimage.label(mask)
    for i, sl in enumerate(ndimage.find_objects(lab), start=1):
        stack.append((sl, lab[sl] == i, 0))
    while stack:
        sl, sub, li = stack.pop()
        split = None
        for j in range(li, len(levels)):
            parts, m = ndimage.label(sub & (rel[sl] > levels[j]))
            if m < 2:
                continue
            sizes = ndimage.sum(np.ones_like(parts), parts, range(1, m + 1))
            big = [q + 1 for q in range(m) if sizes[q] >= max(min_px, 0.08 * sub.sum())]
            if len(big) >= 2:
                seeds = np.zeros(sub.shape, np.int32)
                for q, b in enumerate(big, start=1):
                    seeds[parts == b] = q
                dist, (ix, iz) = ndimage.distance_transform_edt(seeds == 0, return_indices=True)
                keep = sub & (dist <= grow_px) & (rel[sl] > 0.5 * levels[j])
                split = (np.where(keep, seeds[ix, iz], 0), len(big), j + 1)
                break
        if split is None:
            n += 1
            out[sl][sub] = n
            continue
        assign, m, nxt = split
        for q in range(1, m + 1):
            part = assign == q
            pl, _ = ndimage.label(part)
            if pl.max() > 1:  # keep the largest piece of a nearest-seed region
                sz = ndimage.sum(part, pl, range(1, pl.max() + 1))
                part = pl == 1 + int(np.argmax(sz))
            idx = np.argwhere(part)
            lo, hi = idx.min(0), idx.max(0) + 1
            psl = (slice(sl[0].start + lo[0], sl[0].start + hi[0]), slice(sl[1].start + lo[1], sl[1].start + hi[1]))
            stack.append((psl, part[lo[0]:hi[0], lo[1]:hi[1]], nxt))
    return out, n


def _disk(r: int) -> np.ndarray:
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r


def dedupe(shapes: list[dict], max_iou: float = 0.95) -> list[dict]:
    """Drop exact / near repeats inside one asset (e.g. moon_rock_01 ships four copies of one rock)."""
    out = []
    for s in shapes:
        o = s["occ"]
        if any(t["asset"] == s["asset"] and (o & t["occ"]).sum() / max((o | t["occ"]).sum(), 1) > max_iou
               for t in out):
            continue
        out.append(s)
    if len(out) < len(shapes):
        print(f"[dataset] dropped {len(shapes) - len(out)} near-duplicate shapes")
    return out


def build(out: Path, raw: Path = RAW_DIR, device: str = "cpu", seed: int = 0) -> dict:
    rng = np.random.default_rng(seed)
    shapes = []
    for s in closed_shapes(raw, device):
        shapes.append(s)
        print(f"[dataset] closed {s['asset']}#{s['part']} dims {np.round(s['dims_m'], 2)} {s['secs']:.1f}s")
    shapes += list(outcrop_shapes(raw, rng))
    shapes = dedupe(shapes)
    descs = [descriptors(s["occ"]) for s in shapes]
    # outcrop quality gate: flat scree / gravel scraps are not rocks (thin, sprawling footprints)
    keep = [s["kind"] == "closed" or (d["flatness"] >= 0.2 and d["footprint_solidity"] >= 0.7)
            for s, d in zip(shapes, descs)]
    print(f"[dataset] quality gate keeps {sum(keep)}/{len(keep)}")
    shapes = [s for s, k in zip(shapes, keep) if k]
    descs = [d for d, k in zip(descs, keep) if k]
    rough_split = float(np.median([d["roughness"] for d in descs if 0.45 <= d["flatness"] <= 1.0]))
    meta = []
    for s, d in zip(shapes, descs):
        meta.append({"asset": s["asset"], "part": int(s["part"]), "kind": s["kind"],
                     "dims_m": [float(x) for x in s["dims_m"]], "style": style_of(d, rough_split),
                     "size_bin": size_bin(max(s["dims_m"])), **d})
    occ = np.stack([s["occ"] for s in shapes])
    sdf = np.stack([sdf32(o) for o in occ])
    out.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out, sdf32=sdf.astype(np.float16), occ64=np.packbits(occ.reshape(len(occ), -1), axis=1),
                        style=np.array([m["style"] for m in meta], np.int64),
                        size_bin=np.array([m["size_bin"] for m in meta], np.int64))
    info = {"n": len(meta), "rough_split": rough_split, "styles": STYLES, "size_bins": SIZE_BINS,
            "style_counts": np.bincount([m["style"] for m in meta], minlength=4).tolist(),
            "size_counts": np.bincount([m["size_bin"] for m in meta], minlength=3).tolist(),
            "kinds": {k: sum(m["kind"] == k for m in meta) for k in ("closed", "outcrop")},
            "assets": sorted({m["asset"] for m in meta}), "shapes": meta}
    out.with_suffix(".json").write_text(json.dumps(info, indent=1))
    return info


def load(path: Path = OUT_DEFAULT):
    z = np.load(path)
    info = json.loads(path.with_suffix(".json").read_text())
    n = len(z["sdf32"])
    occ64 = np.unpackbits(z["occ64"], axis=1)[:, :RES_HI ** 3].reshape(n, RES_HI, RES_HI, RES_HI).astype(bool)
    return z["sdf32"].astype(np.float32), occ64, z["style"], z["size_bin"], info


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=OUT_DEFAULT)
    ap.add_argument("--raw-dir", type=Path, default=RAW_DIR)
    ap.add_argument("--device", default="cpu")
    a = ap.parse_args(argv)
    info = build(a.out, a.raw_dir, a.device)
    print(json.dumps({k: v for k, v in info.items() if k != "shapes"}, indent=1))


if __name__ == "__main__":
    main()
