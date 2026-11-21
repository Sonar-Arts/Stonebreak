"""Carve-node labels (DaedalusTGM-Exp-2 plan §6): where real terrain is incised, how deep and narrow, which way the
incision runs, and where closed depressions (sinkholes) cluster. Measured on data v2's DEMs at 60 m, reported per
240 m cell.

    python -m terrain_slm.data.carve_labels --data /mnt/extrastorage/terrain-slm-data/v2-gedtm30 [--region R]

Writes <region>/cells_carve.npz:
  vd150, vd600, vd2000  valley depth (m) at three scales: grey closing of the surface with a ~150 m / ~600 m / ~2 km
                        window minus the surface. A slot canyon or gorge is deep at 150 m already; a canyon at 600 m;
                        a rift or broad trough only at 2 km.
  strike_c, strike_s    cos 2theta / sin 2theta of the incision's along-axis direction (structure tensor of vd600),
                        times its coherence (0 = no preferred direction)
  (no sinkhole label: closed depressions on GEDTM30 are mostly canopy/river-bed artifacts; see region_labels)
  land                  land fraction (labels are meaningless at sea)

Today's ravines (stonebreak-game/native carvers.cpp) spawn on `hash % 450` anywhere, in a random direction; these
labels are what a carve node (learned, or a rule calibrated on them) uses to put them where real terrain is incised,
along its grain.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

PX_M = 60.0
SCALES_M = (150.0, 600.0, 2000.0)
CELL = 4            # 60 m pixels per 240 m cell


def _closing(z: torch.Tensor, k: int) -> torch.Tensor:
    pad = k // 2
    dil = F.max_pool2d(F.pad(z, (pad, pad, pad, pad), mode="replicate"), k, stride=1)
    return -F.max_pool2d(F.pad(-dil, (pad, pad, pad, pad), mode="replicate"), k, stride=1)


def valley_depth(z: torch.Tensor, scale_m: float) -> torch.Tensor:
    k = max(3, int(round(scale_m / PX_M)) | 1)
    return (_closing(z, k) - z).clamp_min(0.0)


def strike(vd: torch.Tensor, sigma_px: float = 4.0) -> tuple[torch.Tensor, torch.Tensor]:
    """Coherence-weighted (cos 2theta, sin 2theta) of the incision's along-axis direction."""
    from terrain_slm.data.descriptors import blur

    gy, gx = torch.gradient(vd[0, 0])
    jxx, jyy, jxy = (blur(a[None, None], sigma_px)[0, 0] for a in (gx * gx, gy * gy, gx * gy))
    tr = jxx + jyy
    coh = torch.sqrt((jxx - jyy) ** 2 + 4 * jxy ** 2) / tr.clamp_min(1e-6)
    # Gradients point ACROSS the valley; the axis is perpendicular: rotate 2theta by pi.
    c2, s2 = -(jxx - jyy) / torch.sqrt((jxx - jyy) ** 2 + 4 * jxy ** 2).clamp_min(1e-6), \
        -(2 * jxy) / torch.sqrt((jxx - jyy) ** 2 + 4 * jxy ** 2).clamp_min(1e-6)
    return c2 * coh, s2 * coh


def sinks_per_km2(z60: np.ndarray, land: np.ndarray) -> np.ndarray:
    """Closed depressions > 2 m deep and < 0.2 km2, counted per 240 m cell, scaled to per km2."""
    from scipy import ndimage

    from terrain_slm.data.hydrology.fill import fill_depressions

    invalid = ~land
    filled = fill_depressions(z60.astype(np.float64), invalid=invalid, epsilon=0.0)
    pit = (filled - z60) > 2.0
    lab, n = ndimage.label(pit)
    if n == 0:
        return np.zeros((z60.shape[0] // CELL, z60.shape[1] // CELL), np.float32)
    area = ndimage.sum(np.ones_like(lab), lab, index=np.arange(1, n + 1)) * (PX_M ** 2) / 1e6
    keep = np.zeros(n + 1, bool)
    keep[1:] = area < 0.2
    counts = np.zeros((z60.shape[0] // CELL, z60.shape[1] // CELL), np.float32)
    if keep.any():
        com = np.array(ndimage.center_of_mass(pit, lab, index=np.flatnonzero(keep)))
        ci = np.clip((com[:, 0] // CELL).astype(int), 0, counts.shape[0] - 1)
        cj = np.clip((com[:, 1] // CELL).astype(int), 0, counts.shape[1] - 1)
        np.add.at(counts, (ci, cj), 1.0)
    return counts / ((CELL * PX_M) ** 2 / 1e6)


def region_labels(rdir: Path, device: str) -> dict[str, np.ndarray]:
    dem = np.load(rdir / "dem.npy", mmap_mode="r")
    sea = np.load(rdir / "sea.npy", mmap_mode="r")
    h, w = dem.shape[0] // 8 * 4, dem.shape[1] // 8 * 4          # 60 m grid, whole cells
    z60 = np.asarray(dem[: h * 2, : w * 2], np.float32).reshape(h, 2, w, 2).mean((1, 3))
    land = np.asarray(sea[: h * 2 : 2, : w * 2 : 2]) == 0
    z = torch.from_numpy(z60).to(device)[None, None]
    out = {}
    pool = lambda a: F.avg_pool2d(a, CELL)[0, 0].cpu().numpy()
    land_t = torch.from_numpy(land.astype(np.float32)).to(device)[None, None]
    for s in SCALES_M:
        vd = valley_depth(z, s) * land_t
        out[f"vd{int(s)}"] = pool(vd)
        if s == 600.0:
            c2, s2 = strike(vd)
            out["strike_c"], out["strike_s"] = pool(c2[None, None]), pool(s2[None, None])
    # No sinkhole label: on GEDTM30 closed depressions are dominated by artifacts (canopy-removal pits in forests,
    # pits along river beds; checked on the Appalachian Plateau and the central Andes, 2026-11-18). Karst sink density
    # comes from the karst style regions (dalmatia, halong) instead.
    out["land"] = pool(land_t)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--region", action="append")
    ap.add_argument("--device", default="cuda:0")
    args = ap.parse_args()
    dirs = [args.data / r for r in args.region] if args.region else sorted(p.parent for p in args.data.glob("*/meta.json"))
    for rdir in dirs:
        lab = region_labels(rdir, args.device)
        np.savez(rdir / "cells_carve.npz", **lab)
        landm = lab["land"] > 0.5
        stats = {k: round(float(np.percentile(v[landm], 95)), 2) for k, v in lab.items() if k not in ("land",)} if landm.any() else {}
        print(rdir.name, json.dumps({"p95_on_land": stats}), flush=True)


if __name__ == "__main__":
    main()
