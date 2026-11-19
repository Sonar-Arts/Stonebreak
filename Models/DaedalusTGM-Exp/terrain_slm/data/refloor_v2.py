"""Rebuild the sea floor of built data-v2 regions in place (2026-11-18): the smooth slope continuation
(topobathy.slope_continuation: shore slope averaged instead of nearest-shore Voronoi facets, blurred blend width
instead of square plateaus) and bilinear 60 m -> 30 m upsampling of depth / weight instead of pixel repeats.

    python -m terrain_slm.data.refloor_v2 --data /mnt/extrastorage/terrain-slm-data [--region R ...] [--device cuda:0]

Land pixels (sea.npy == 0) are untouched; sea pixels get GEBCO (re-resampled from the local GEBCO 2024 file exactly as
build_v2 pass 1 does) blended toward the smooth shore-slope depth. Then cells_geo.npz (coarse, descriptors, sea
fraction) is recomputed; run finish_v2 afterwards to re-merge cells.npz. Writes go to temporary files renamed over the
old ones, so a trainer that already loaded a region keeps its copy.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from terrain_slm.data import glo30
from terrain_slm.data import topobathy as TB
from terrain_slm.data.build_v2 import CELL_PX, GEBCO, _gebco_tile


def _bathy(region: glo30.Region, data: Path, shape) -> np.ndarray:
    out = np.empty(shape, np.float32)
    for lat, lon in region.tiles():
        r0 = (region.lat1 - 1 - lat) * glo30.TILE_PX
        c0 = (lon - region.lon0) * region.tile_width_px
        out[r0 : r0 + glo30.TILE_PX, c0 : c0 + region.tile_width_px] = _gebco_tile(data / GEBCO, lat, lon,
                                                                                    region.tile_width_px)
    return out


CUDEM_FEATHER_PX = 33          # ~1 km ramp from the edge of CUDEM coverage into it (no step where it ends)


def _cudem(region: glo30.Region, shape, tiles: list[Path]) -> np.ndarray:
    """NOAA CUDEM 1/9" topobathy (public domain, ~3.4 m) block-averaged onto the region's 30 m grid; NaN outside
    coverage. Each tile is reprojected into its window of the region grid (average resampling)."""
    import rasterio
    from rasterio.transform import from_origin
    from rasterio.warp import Resampling, reproject

    out = np.full(shape, np.nan, np.float32)
    dst_tf = from_origin(region.lon0, region.lat1, 1.0 / region.tile_width_px, 1.0 / glo30.TILE_PX)
    for t in tiles:
        with rasterio.open(t) as src:
            b = src.bounds
            if b.right <= region.lon0 or b.left >= region.lon1 or b.top <= region.lat0 or b.bottom >= region.lat1:
                continue
            c0 = max(0, int(np.floor((b.left - region.lon0) * region.tile_width_px)))
            c1 = min(shape[1], int(np.ceil((b.right - region.lon0) * region.tile_width_px)))
            r0 = max(0, int(np.floor((region.lat1 - b.top) * glo30.TILE_PX)))
            r1 = min(shape[0], int(np.ceil((region.lat1 - b.bottom) * glo30.TILE_PX)))
            win = np.full((r1 - r0, c1 - c0), np.nan, np.float32)
            # The tiles carry compound CRSs (NAD83 / WGS84 + a vertical datum): treat them as plain lat/lon (NAD83 vs
            # WGS84 is ~1 m, nothing on a 30 m grid) so PROJ never attempts a vertical transformation.
            reproject(source=src.read(1), destination=win, src_transform=src.transform, src_crs="EPSG:4326",
                      src_nodata=src.nodata, dst_transform=dst_tf * dst_tf.translation(c0, r0),
                      dst_crs="EPSG:4326", dst_nodata=np.nan, resampling=Resampling.average)
            cur = out[r0:r1, c0:c1]
            out[r0:r1, c0:c1] = np.where(np.isfinite(win), win, cur)
    return out


def refloor(region: glo30.Region, data: Path, root: str, device: str, cudem: list[Path] | None = None) -> None:
    import torch
    from terrain_slm.data.descriptors import extract_tiled

    out = data / root / region.name
    dem = np.load(out / "dem.npy")
    sea = np.load(out / "sea.npy") > 0
    if not sea.any():
        print(f"{region.name}: no sea, unchanged", flush=True)
        return
    shape = dem.shape
    bathy = _bathy(region, data, shape)
    ds = 2
    sea2 = np.ascontiguousarray(sea[::ds, ::ds])
    land2 = np.ascontiguousarray(dem[::ds, ::ds])            # only land pixels are read (nearest-shore indices)
    depth2, weight2 = TB.slope_continuation(sea2, land2, glo30.PIXEL_M * ds)
    floor = TB.sea_floor(bathy, TB.upsample2(depth2, shape), np.clip(TB.upsample2(weight2, shape), 0.0, 1.0))
    if cudem:                      # measured high-resolution bathymetry wins where it exists, feathered in at its edge
        from scipy import ndimage

        cu = _cudem(region, shape, cudem)
        have = np.isfinite(cu)
        w = np.clip(ndimage.distance_transform_edt(have) / CUDEM_FEATHER_PX, 0.0, 1.0).astype(np.float32)
        floor = np.where(have, (1 - w) * floor + w * np.minimum(np.nan_to_num(cu), TB.SEA_MAX_M), floor)
        print(f"  {region.name}: CUDEM covers {(have & sea).sum() / max(sea.sum(), 1):.1%} of the sea", flush=True)
    old = dem[sea].copy()
    dem[sea] = floor[sea]
    d = dem[sea] - old
    tmp = out / "dem.refloor.npy"
    np.save(tmp, dem)
    tmp.rename(out / "dem.npy")
    hc, wc = shape[0] // CELL_PX, shape[1] // CELL_PX
    blk = dem[: hc * CELL_PX, : wc * CELL_PX]
    coarse = blk.reshape(hc, CELL_PX, wc, CELL_PX).mean(axis=(1, 3)).astype(np.float32)
    sea_frac = sea[: hc * CELL_PX, : wc * CELL_PX].reshape(hc, CELL_PX, wc, CELL_PX).mean(axis=(1, 3)).astype(np.float32)
    with torch.no_grad():
        desc = extract_tiled(dem, device=device).numpy()
    np.savez(out / "cells_geo.refloor.npz", coarse=coarse, desc=desc, sea=sea_frac)
    (out / "cells_geo.refloor.npz").rename(out / "cells_geo.npz")
    meta = json.loads((out / "meta.json").read_text())
    meta.setdefault("rules", {})["floor_fix"] = ("2026-11-18: shore slope Gaussian-averaged over the sea "
                                                 f"(sigma {TB.SHORE_SLOPE_SIGMA_M:.0f} m), blurred blend width, "
                                                 "bilinear 60->30 m (data/refloor_v2.py)")
    (out / "meta.json").write_text(json.dumps(meta, indent=2))
    print(f"{region.name}: sea {sea.mean():.1%}; floor change median {np.median(np.abs(d)):.1f} m, "
          f"p99 {np.percentile(np.abs(d), 99):.0f} m", flush=True)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, required=True)
    ap.add_argument("--root", default="v2-gedtm30")
    ap.add_argument("--region", action="append")
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--cudem", type=Path, action="append", help="directory of NOAA CUDEM 1/9\" tiles (*.tif)")
    args = ap.parse_args()
    tiles = sorted(t for d in (args.cudem or []) for t in d.glob("*.tif"))
    names = args.region or sorted(p.name for p in (args.data / args.root).iterdir() if (p / "sea.npy").exists())
    for name in names:
        refloor(glo30.REGIONS[name], args.data, args.root, args.device, tiles or None)


if __name__ == "__main__":
    main()
