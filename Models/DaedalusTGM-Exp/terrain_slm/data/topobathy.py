"""Topo-bathy merge for data v2 (DaedalusTGM-Exp-2 plan, P1 §5): land from a 30 m DEM, sea floor from bathymetry.

GLO-30 (and every land DEM) puts the sea at a flat 0, so fjords look like flat lakes between cliffs and island
chains have no shelf; a model trained on that can only learn coasts as cliffs into a flat plane. Here every
region mosaic pixel is one of:

  land  the base DEM (GLO-30 surface model, or GEDTM30 bare-earth terrain)
  sea   bathymetry (GEBCO 15", bilinear to the 30 m grid), never above SEA_MAX_M, so the shore stays a shore
  lake  water that is not the sea (a bathymetry surface above sea level there, e.g. a lake's own level):
        the base DEM's flat water surface, as before

The sea mask comes from GEDTM30, whose ocean is nodata (a clean mask, unlike GLO-30's flattened water), together
with the bathymetry: nodata where the bathymetry stands at or below SEA_BATHY_MAX_M is sea; nodata above it is a
lake or a gap and keeps the base DEM. A whole tile the land DEM does not have (open ocean) is sea.

Pure numpy on arrays; the region builder (data/build_v2.py) does the I/O. Sources and licences:
GEDTM30 CC BY 4.0 (OpenGeoHub), GLO-30 Copernicus DEM licence, GEBCO public domain (attribution requested).
"""
from __future__ import annotations

import numpy as np

#: Sea pixels never stand higher than this (m): GEBCO's 450 m cells blend land into the first sea cells.
SEA_MAX_M = -1.0
#: Nodata (water) pixels count as sea where the bathymetry there is at or below this (m). A little above 0
#: because the coarse bathymetry averages the shore's first land into its sea cells.
SEA_BATHY_MAX_M = 5.0


def sea_mask(land_valid: np.ndarray, bathy_m: np.ndarray) -> np.ndarray:
    """Sea pixels: no land-DEM value and bathymetry at or below SEA_BATHY_MAX_M."""
    return ~land_valid & (bathy_m <= SEA_BATHY_MAX_M)


def merge(base_m: np.ndarray, base_valid: np.ndarray, mask_valid: np.ndarray, bathy_m: np.ndarray,
          fallback_m: np.ndarray | None = None, fallback_valid: np.ndarray | None = None,
          sea: np.ndarray | None = None) -> tuple[np.ndarray, np.ndarray]:
    """(elevation m, sea mask) for one tile.

    base_m          base DEM (GLO-30 or GEDTM30), metres; anything where base_valid is False
    base_valid      where base_m is real data
    mask_valid      where the MASK DEM (GEDTM30) has land; pass base_valid when the base is the mask DEM
    bathy_m         bathymetry on the same grid, metres (negative below sea level)
    fallback_m      where the base has no value and it is not sea (a lake GEDTM30 blanks): GLO-30's flat water
    sea             a precomputed sea mask (the region builder's, which also drops inland basins); default
                    sea_mask(mask_valid, bathy_m)
    """
    if sea is None:
        sea = sea_mask(mask_valid, bathy_m)
    out = np.where(sea, np.minimum(bathy_m, SEA_MAX_M), base_m).astype(np.float32)
    gap = ~sea & ~base_valid
    if fallback_m is not None:
        fb = gap & (fallback_valid if fallback_valid is not None else np.ones_like(gap))
        out[fb] = fallback_m[fb]
        gap &= ~fb
    # Still nothing: the bathymetry's own surface, never below sea level (a lake is not the sea).
    out[gap] = np.maximum(bathy_m[gap], 0.0)
    return out, sea


#: Slope continuation (fjords): the sea floor near a shore is at least shore_slope * min(distance, this) deep.
SLOPE_CONTINUE_M = 1500.0
SHORE_SLOPE_SIGMA_M = 600.0   # averaging radius of the shore slope carried under the sea
SLOPE_MAX = 1.2           # tan(50 deg): steeper shore slopes are cliffs, not a trough's wall


def sea_from_water(water: np.ndarray, bathy_m: np.ndarray) -> np.ndarray:
    """Sea = water (no land-DEM value) in a body that holds some clearly sub-sea bathymetry AND reaches the frame's
    edge (the open ocean continues outside the region). A narrow fjord whose coarse bathymetry reads as land
    (GEBCO's 450 m cells smear the walls into it) is still sea, being connected; a lake, or an inland basin below
    sea level (a Dead Sea), is not."""
    from scipy import ndimage

    labels, n = ndimage.label(water, structure=np.ones((3, 3), bool))
    if n == 0:
        return np.zeros_like(water)
    seeded = np.zeros(n + 1, bool)
    seeded[np.unique(labels[water & (bathy_m <= SEA_BATHY_MAX_M)])] = True
    edge = np.zeros(n + 1, bool)
    edge[np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))] = True
    keep = seeded & edge
    keep[0] = False
    return keep[labels]


def slope_continuation(sea: np.ndarray, land_m: np.ndarray, px_m: float) -> tuple[np.ndarray, np.ndarray]:
    """(depth m >= 0, weight 0..1) the shore implies at every sea pixel. Depth = the slope of the nearest land carried
    down over the distance to shore: steep fjord walls meet in a deep U, gentle coasts stay shallow. Weight = how
    little the bathymetry can be trusted there: 1 in water whose local half-width (the largest distance to shore within
    SLOPE_CONTINUE_M) is a few hundred metres, falling to 0 at SLOPE_CONTINUE_M, where GEBCO's 450 m cells resolve
    the floor. (Without the weight, every open-sea pixel took its nearest cliff's slope: wedges off steep islands.)"""
    from scipy import ndimage

    # The land's own slope: sea pixels take their nearest land's height first, so the shoreline itself adds no
    # step (a beach 3 m above the water is not a 3 m cliff).
    dist, (ri, ci) = ndimage.distance_transform_edt(sea, return_indices=True)
    filled = land_m[ri, ci].astype(np.float32)
    smooth = ndimage.gaussian_filter(filled, 1.0)
    gy, gx = np.gradient(smooth, px_m)
    # The steepest wall within a couple of pixels of the shore: the fill flattens the slope right at the water line.
    slope = np.clip(ndimage.maximum_filter(np.hypot(gx, gy), size=5), 0.0, SLOPE_MAX)
    dist_m = (dist * px_m).astype(np.float32)
    # Smooth fields only (2026-11-18): each sea pixel's NEAREST-shore slope is a Voronoi map -- every cell its own
    # flat facet (Ha Long's polygon floors) -- so the shore slope is averaged over the sea (normalised Gaussian,
    # SHORE_SLOPE_SIGMA_M), and the square maximum filter's plateaus (square steps in the blend weight, Nordland)
    # are blurred out.
    sig = SHORE_SLOPE_SIGMA_M / px_m
    seaf = sea.astype(np.float32)
    near = slope[ri, ci].astype(np.float32)
    shore_slope = ndimage.gaussian_filter(near * seaf, sig) / np.maximum(ndimage.gaussian_filter(seaf, sig), 1e-6)
    window = 2 * int(SLOPE_CONTINUE_M / px_m) + 1
    half_width = ndimage.gaussian_filter(ndimage.maximum_filter(dist_m, size=window), window / 3.0)
    weight = np.clip((SLOPE_CONTINUE_M - half_width) / (0.5 * SLOPE_CONTINUE_M), 0.0, 1.0) * sea
    return (shore_slope * dist_m * sea).astype(np.float32), weight.astype(np.float32)


def sea_floor(bathy_m: np.ndarray, depth: np.ndarray, weight: np.ndarray) -> np.ndarray:
    """The sea floor: GEBCO (never above SEA_MAX_M), deepened toward the shore-slope depth where it is unresolved."""
    gebco = np.minimum(bathy_m, SEA_MAX_M)
    return (1.0 - weight) * gebco + weight * np.minimum(gebco, -depth)


def ocean_connected(sea_coarse: np.ndarray) -> np.ndarray:
    """Keep the sea components that reach the frame's edge (the open ocean continues outside a region);
    inland basins below sea level (a Dead Sea, a polder) stop being sea. Works on a coarse mask."""
    from scipy import ndimage

    labels, n = ndimage.label(sea_coarse, structure=np.ones((3, 3), bool))
    if n == 0:
        return sea_coarse.copy()
    edge = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    keep = np.zeros(n + 1, bool)
    keep[edge[edge > 0]] = True
    return keep[labels]


def bilinear_to_grid(src: np.ndarray, src_lat0: float, src_lon0: float, src_res: float,
                     lat: np.ndarray, lon: np.ndarray) -> np.ndarray:
    """Bilinear sample of a north-up lat/lon raster window at the outer product lat (rows) x lon (cols).

    src_lat0 / src_lon0: the window's NORTH-WEST corner (degrees); src_res: degrees per pixel (square)."""
    from scipy import ndimage

    rr = (src_lat0 - lat) / src_res - 0.5           # pixel centres at half-integers
    cc = (lon - src_lon0) / src_res - 0.5
    rows = np.broadcast_to(rr[:, None], (len(lat), len(lon)))
    cols = np.broadcast_to(cc[None, :], (len(lat), len(lon)))
    return ndimage.map_coordinates(src.astype(np.float32), [rows, cols], order=1, mode="nearest").astype(np.float32)


def upsample2(a: np.ndarray, shape) -> np.ndarray:
    """60 m field -> 30 m grid, bilinear (pixel centres aligned), clipped to `shape`."""
    from scipy import ndimage

    h, w = shape
    rows = (np.arange(h) + 0.5) / 2 - 0.5
    cols = (np.arange(w) + 0.5) / 2 - 0.5
    out = np.empty(shape, np.float32)
    for r0 in range(0, h, 2048):
        rr = rows[r0 : r0 + 2048]
        grid = np.meshgrid(rr, cols, indexing="ij")
        out[r0 : r0 + len(rr)] = ndimage.map_coordinates(a, grid, order=1, mode="nearest")
    return out
