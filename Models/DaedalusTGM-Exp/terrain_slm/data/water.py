"""Water masks from the DEM itself, for training the bank model.

Copernicus GLO-30 is an *edited* DSM: lakes and rivers wider than ~183 m are flattened to
their water level (rivers in monotonic steps). So "exactly flat" is a water detector: a
pixel whose 3x3 neighbourhood spans < FLAT_TOL_M, in a connected flat patch of at least
MIN_COMPONENT_PX, above sea level. At the 1:4 scale, 183 m is ~3 blocks, the narrowest
river the game draws, so these are real banks at the widths we need.

    python -m terrain_slm.data.water --region alps     # writes data/<region>/water.npz

water.npz: `mask` (uint8, dem shape, 1 = water) and `edges` (int32 (N, 2) water-edge pixels,
subsampled, the centres that bank-model training crops are drawn around).
"""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from scipy import ndimage

from terrain_slm.data import glo30

FLAT_TOL_M = 0.01
MIN_COMPONENT_PX = 150
MIN_ELEV_M = 1.0          # the sea is flat too
CHUNK = 2048
EDGE_SUBSAMPLE = 16       # keep every Nth edge pixel


def water_mask(dem: np.ndarray) -> np.ndarray:
    """Flat-and-connected mask, computed in overlapping chunks so memory stays bounded."""
    h, w = dem.shape
    flat = np.zeros((h, w), dtype=bool)
    for r in range(0, h, CHUNK):
        for c in range(0, w, CHUNK):
            r0, c0 = max(0, r - 1), max(0, c - 1)
            r1, c1 = min(h, r + CHUNK + 1), min(w, c + CHUNK + 1)
            p = np.asarray(dem[r0:r1, c0:c1], dtype=np.float32)
            span = ndimage.maximum_filter(p, 3, mode="nearest") - ndimage.minimum_filter(p, 3, mode="nearest")
            f = (span < FLAT_TOL_M) & (p > MIN_ELEV_M)
            flat[r:min(h, r + CHUNK), c:min(w, c + CHUNK)] = f[r - r0 : r - r0 + min(CHUNK, h - r),
                                                                c - c0 : c - c0 + min(CHUNK, w - c)]
    lab, n = ndimage.label(flat)
    sizes = np.bincount(lab.ravel(), minlength=n + 1)
    keep = sizes >= MIN_COMPONENT_PX
    keep[0] = False
    return keep[lab]


def edge_pixels(mask: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    edge = mask & ~ndimage.binary_erosion(mask, iterations=1, border_value=1)
    ys, xs = np.nonzero(edge)
    idx = rng.permutation(len(ys))[: max(1, len(ys) // EDGE_SUBSAMPLE)]
    return np.stack([ys[idx], xs[idx]], axis=1).astype(np.int32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=Path("data"))
    ap.add_argument("--region", action="append", choices=sorted(glo30.REGIONS))
    args = ap.parse_args()
    for name in args.region or sorted(glo30.REGIONS):
        root = args.data / name
        dem = np.load(root / "dem.npy", mmap_mode="r")
        mask = water_mask(dem)
        edges = edge_pixels(mask, np.random.default_rng(0))
        np.savez_compressed(root / "water.npz", mask=mask.astype(np.uint8), edges=edges)
        print(f"{name}: water {mask.mean() * 100:.2f}% of pixels, {len(edges):,} edge samples", flush=True)


if __name__ == "__main__":
    main()
