"""Offline augmentation of the rock dataset: K variants per scanned shape, each re-normalised to the canonical frame.

Variants: free yaw + mirror, small tilt, anisotropic scale, occasional stand-on-end (closed rocks), fresh-fracture
chipping (planar cuts), erosion (rounding) or roughening. Labels (style) are re-derived from the variant's own
descriptors, so a stretched / stood-up rock is labelled for what it now looks like.
Augmentation only widens the training distribution; novelty must come from the model (see `evaluate`).

    python -m terrain_slm.rocks.augment --variants 40 --workers 8
"""
from __future__ import annotations

import argparse
import json
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
from scipy import ndimage

from . import dataset as D

AUG_DEFAULT = Path("/mnt/extrastorage/terrain-slm-data/rocks/rocks_v1_aug.npz")


def _crop(occ):
    idx = np.argwhere(occ)
    lo, hi = idx.min(0), idx.max(0) + 1
    return occ[lo[0]:hi[0], lo[1]:hi[1], lo[2]:hi[2]]


def _embed(a: np.ndarray, pad: int = 4) -> np.ndarray:
    return np.pad(a, pad)


def augment_one(occ: np.ndarray, kind: str, rng: np.random.Generator) -> np.ndarray:
    a = _embed(_crop(occ)).astype(np.float32)
    # stand an elongated closed rock on end (tor-like) now and then
    if kind == "closed" and rng.random() < 0.15:
        a = np.rot90(a, k=1, axes=(0, 1) if rng.random() < 0.5 else (2, 1)).copy()
    # small tilt about x or z
    tilt = rng.normal(0, 6) if kind == "closed" else 0.0  # outcrops keep their flat base
    if abs(tilt) > 1:
        a = ndimage.rotate(a, tilt, axes=(0, 1) if rng.random() < 0.5 else (2, 1), order=1, reshape=True)
    # yaw + mirror
    a = ndimage.rotate(a, rng.uniform(0, 360), axes=(0, 2), order=1, reshape=True)
    if rng.random() < 0.5:
        a = a[::-1]
    # anisotropic scale
    sc = np.exp(rng.normal(0, 0.14, 3)).clip(0.7, 1.4)
    a = ndimage.zoom(a, sc, order=1)
    occ2 = a >= 0.5
    if occ2.sum() < 50:
        return occ
    occ2 = D.clean_occupancy(_embed(_crop(occ2)))
    r = rng.random()
    if r < 0.35:  # fresh fracture: 1-3 planar cuts, each removing a slice of the shape
        idx = np.argwhere(occ2).astype(np.float32)
        c = idx.mean(0)
        grid = np.indices(occ2.shape, dtype=np.float32).transpose(1, 2, 3, 0)
        for _ in range(rng.integers(1, 4)):
            nrm = rng.normal(size=3)
            nrm[1] = abs(nrm[1]) * 0.6 if rng.random() < 0.8 else nrm[1]  # mostly not the base
            nrm /= np.linalg.norm(nrm) + 1e-9
            proj = (idx - c) @ nrm
            cut = np.quantile(proj, 1 - rng.uniform(0.04, 0.18))
            occ2 &= ((grid - c) @ nrm) <= cut
    elif r < 0.6:  # erosion / rounding
        occ2 = ndimage.gaussian_filter(occ2.astype(np.float32), rng.uniform(0.8, 2.2)) >= 0.5
    elif r < 0.75:  # roughening: low-amplitude 3D noise on the signed distance
        s = D.V.sdf_from_occupancy(occ2)
        noise = ndimage.gaussian_filter(rng.normal(size=occ2.shape).astype(np.float32), 1.5)
        noise *= 1.4 / (noise.std() + 1e-6)
        occ2 = (s + noise) < 0
    if occ2.sum() < 50:
        return occ
    return D.canonical_occupancy(D.clean_occupancy(occ2))


def _job(args):
    i, occ, kind, k, seed, rough_split = args
    rng = np.random.default_rng([seed, i])
    sdfs, styles, flats = [], [], []
    for j in range(k):
        o = occ if j == 0 else augment_one(occ, kind, rng)
        d = D.descriptors(o)
        styles.append(D.style_of(d, rough_split))
        flats.append(d["flatness"])
        sdfs.append(D.sdf32(o).astype(np.float16))
    return i, np.stack(sdfs), styles, flats


def build(src: Path, out: Path, variants: int, workers: int, seed: int = 0):
    _, occ64, _, size_bin, info = D.load(src)
    kinds = [m["kind"] for m in info["shapes"]]
    n = len(occ64)
    sdf = np.zeros((n * variants, D.RES, D.RES, D.RES), np.float16)
    style = np.zeros(n * variants, np.int64)
    flat = np.zeros(n * variants, np.float32)
    jobs = [(i, occ64[i], kinds[i], variants, seed, info["rough_split"]) for i in range(n)]
    with ProcessPoolExecutor(workers) as ex:
        for done, (i, s, st, fl) in enumerate(ex.map(_job, jobs, chunksize=2)):
            sdf[i * variants:(i + 1) * variants] = s
            style[i * variants:(i + 1) * variants] = st
            flat[i * variants:(i + 1) * variants] = fl
            if done % 25 == 0:
                print(f"[augment] {done}/{n}", flush=True)
    src_idx = np.repeat(np.arange(n), variants)
    grounded = np.array([k == "outcrop" for k in kinds], np.int64)[src_idx]
    np.savez(out, sdf32=sdf, style=style, size_bin=size_bin[src_idx], grounded=grounded, src=src_idx,
             flatness=flat)
    stats = {"n": int(len(sdf)), "variants": variants, "seed": seed, "src": str(src),
             "style_counts": np.bincount(style, minlength=4).tolist()}
    out.with_suffix(".json").write_text(json.dumps(stats, indent=1))
    print(json.dumps(stats))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", type=Path, default=D.OUT_DEFAULT)
    ap.add_argument("--out", type=Path, default=AUG_DEFAULT)
    ap.add_argument("--variants", type=int, default=40)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args(argv)
    build(a.src, a.out, a.variants, a.workers, a.seed)


if __name__ == "__main__":
    main()
