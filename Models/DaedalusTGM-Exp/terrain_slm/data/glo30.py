"""Copernicus GLO-30 DEM tiles: naming, download, and resampling to square pixels.

GLO-30 ships as 1x1 degree Cloud-Optimized GeoTIFFs on the public AWS Open Data
bucket `copernicus-dem-30m`. Below 50 degrees latitude a tile is 3600x3600 at
1 arcsecond in both axes, so a pixel is ~30.9 m north-south but only
~30.9 m * cos(lat) east-west. The model wants square pixels, so every tile in a
region is resampled along x by one shared factor (cos of the region's centre
latitude). A single factor keeps all tiles the same width, which is what lets
them tile into one mosaic; the price is a few percent of east-west distortion
at the region's edges, acceptable for a regional training slice.
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import requests

BUCKET_URL = "https://copernicus-dem-30m.s3.amazonaws.com"
TILE_PX = 3600  # rows (and source columns) per 1-degree tile below 50 degrees latitude
PIXEL_M = 30.0  # nominal square pixel size after resampling


def tile_name(lat: int, lon: int) -> str:
    """GLO-30 tile stem for the 1-degree cell whose south-west corner is (lat, lon)."""
    ns = "N" if lat >= 0 else "S"
    ew = "E" if lon >= 0 else "W"
    return f"Copernicus_DSM_COG_10_{ns}{abs(lat):02d}_00_{ew}{abs(lon):03d}_00_DEM"


def tile_url(lat: int, lon: int) -> str:
    stem = tile_name(lat, lon)
    return f"{BUCKET_URL}/{stem}/{stem}.tif"


@dataclass(frozen=True)
class Region:
    """A lat/lon box of whole tiles. `lat0`/`lon0` are the south-west tile corner."""

    name: str
    lat0: int
    lat1: int  # exclusive
    lon0: int
    lon1: int  # exclusive
    #: Spatially held-out validation tiles (south-west corners), inside the box.
    val_tiles: tuple = ()
    note: str = ""

    def tiles(self) -> list[tuple[int, int]]:
        return [(lat, lon) for lat in range(self.lat0, self.lat1) for lon in range(self.lon0, self.lon1)]

    @property
    def x_scale(self) -> float:
        return math.cos(math.radians((self.lat0 + self.lat1) / 2.0))

    @property
    def tile_width_px(self) -> int:
        """Resampled tile width, rounded down to a multiple of 8 so cells align."""
        return int(TILE_PX * self.x_scale) // 8 * 8

    @property
    def shape_px(self) -> tuple[int, int]:
        return (self.lat1 - self.lat0) * TILE_PX, (self.lon1 - self.lon0) * self.tile_width_px


ALPS = Region("alps", 44, 48, 5, 15, val_tiles=((46, 10), (44, 7), (47, 13)),
              note="temperate high mountains, Po plain, Adriatic/Ligurian coast")

#: Training regions, chosen to span landform x climate. GLO-30 is a surface model (DSM):
#: forest canopy and buildings are in it, which shows as fine noise over rainforest.
REGIONS = {
    r.name: r
    for r in (
        ALPS,
        Region("colorado_plateau", 35, 39, -114, -109, val_tiles=((36, -112),),
               note="arid canyons, mesas, Grand Canyon"),
        Region("sahara_erg", 29, 32, 6, 10, val_tiles=((30, 8),),
               note="sand sea: dunes, hamada, hyper-arid"),
        Region("borneo", 0, 4, 113, 117, val_tiles=((1, 115),),
               note="tropical rainforest hills, big rivers (canopy in the DSM)"),
        Region("great_plains", 40, 43, -101, -96, val_tiles=((41, -99),),
               note="rolling plains, Sandhills, braided rivers"),
        Region("norway", 60, 63, 5, 10, val_tiles=((61, 7),),
               note="boreal/alpine, fjords, glacial valleys, lakes"),
        Region("east_africa", -5, -1, 34, 38, val_tiles=((-4, 36),),
               note="savanna, rift valley escarpments, volcanoes"),
    )
}


def download_tile(lat: int, lon: int, out_dir: Path, retries: int = 4) -> Path | None:
    """Download one tile if missing. Returns the path, or None if the tile does not exist
    (open ocean tiles are simply absent from the bucket)."""
    out = out_dir / f"{tile_name(lat, lon)}.tif"
    if out.exists() and out.stat().st_size > 0:
        return out
    tmp = out.with_suffix(".part")
    for attempt in range(retries):
        try:
            with requests.get(tile_url(lat, lon), stream=True, timeout=60) as r:
                if r.status_code == 404:
                    return None
                r.raise_for_status()
                with open(tmp, "wb") as f:
                    for chunk in r.iter_content(1 << 20):
                        f.write(chunk)
            tmp.rename(out)
            return out
        except requests.RequestException:
            if attempt == retries - 1:
                raise
            time.sleep(2.0 * (attempt + 1))
    return None


def read_resampled(path: Path, width_px: int) -> np.ndarray:
    """Read a tile as float32 metres (row 0 = north) resampled to `width_px` columns."""
    import rasterio
    from scipy import ndimage

    with rasterio.open(path) as src:
        z = src.read(1).astype(np.float32)
        nodata = src.nodata
    if nodata is not None:
        z[z == nodata] = 0.0
    z = np.nan_to_num(z, nan=0.0)
    if z.shape[0] != TILE_PX:
        raise ValueError(f"{path.name}: expected {TILE_PX} rows, got {z.shape[0]}")
    # Area-weighted 1-D resample along x: order-1 zoom on a pre-smoothed field keeps
    # the sub-pixel detail that survives the ~0.7x shrink without aliasing.
    factor = width_px / z.shape[1]
    sigma = max(0.0, 0.5 * (1.0 / factor - 1.0))
    if sigma > 0:
        z = ndimage.gaussian_filter1d(z, sigma, axis=1, mode="nearest")
    return ndimage.zoom(z, (1.0, factor), order=1, mode="nearest")[:, :width_px].astype(np.float32)
