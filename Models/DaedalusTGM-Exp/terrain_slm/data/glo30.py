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
        # --- 2026-09-28 expansion: hilly non-mountain ground (the "flat lowlands" gap),
        # dendritic valley networks, and more mountain types. Boxes avoid GLO-30's
        # withheld countries (Armenia, Azerbaijan): a missing land tile would read as sea.
        Region("appalachian_plateau", 37, 41, -83, -78, val_tiles=((38, -81),),
               note="dissected plateau: dense dendritic hills and hollows"),
        Region("ozarks", 35, 38, -94, -90, val_tiles=((36, -92),),
               note="rolling dissected upland, karst hills"),
        Region("loess_plateau", 35, 38, 107, 111, val_tiles=((36, 109),),
               note="intensely gullied loess hills"),
        Region("mar_de_morros", -23, -20, -46, -42, val_tiles=((-22, -44),),
               note="SE Brazil 'sea of hills': rounded tropical hills"),
        Region("rhenish_massif", 50, 52, 6, 10, val_tiles=((50, 8),),
               note="German uplands: incised plateaus, rolling hills"),
        Region("massif_central", 44, 47, 2, 5, val_tiles=((45, 3),),
               note="French uplands, old volcanoes, gorges"),
        Region("tuscany_apennines", 42, 44, 10, 13, val_tiles=((43, 11),),
               note="hill country rising to the Apennines"),
        Region("scottish_highlands", 55, 58, -6, -3, val_tiles=((57, -5),),
               note="glaciated worn-down mountains, lochs, moors"),
        Region("carpathian_hills", 45, 48, 22, 26, val_tiles=((46, 24),),
               note="Transylvanian hills ringed by the Carpathians"),
        Region("ethiopian_highlands", 9, 13, 37, 40, val_tiles=((11, 38),),
               note="basalt plateau cut by deep gorges (Blue Nile)"),
        Region("drakensberg", -31, -28, 28, 31, val_tiles=((-30, 29),),
               note="escarpment, hills and valleys of KwaZulu-Natal"),
        Region("zagros", 30, 33, 49, 53, val_tiles=((31, 51),),
               note="long parallel folded ridges"),
        Region("rockies_colorado", 37, 41, -108, -104, val_tiles=((39, -106),),
               note="high ranges, parks, foothills"),
        Region("andes_central", -18, -14, -72, -68, val_tiles=((-16, -70),),
               note="altiplano, volcanoes, deep valleys"),
        Region("karakoram", 34, 37, 74, 78, val_tiles=((35, 76),),
               note="extreme relief, glaciated valleys"),
        Region("nz_southern_alps", -46, -42, 168, 172, val_tiles=((-44, 170),),
               note="young range, braided rivers, fjords"),
        Region("japan_chubu", 35, 37, 136, 140, val_tiles=((35, 137),),
               note="steep dissected mountains, basins"),
        # ---- DaedalusTGM-Exp-2 (2026-11-18): fjords, islands, archipelagos, lagoons. Their sea floor comes from
        # the topo-bathy merge (GEBCO / EMODnet / Kartverket), not GLO-30, whose sea is a flat 0.
        Region("norway_nordland", 66, 69, 12, 17, val_tiles=((67, 14),),
               note="fjords, Lofoten island chain, glacial troughs"),
        Region("patagonia_fjords", -50, -45, -76, -72, val_tiles=((-48, -74),),
               note="drowned glacial valleys, channels, icefield margins"),
        Region("nz_fiordland", -47, -44, 166, 168, val_tiles=((-46, 167),),
               note="deep fiords cut into crystalline mountains"),
        Region("bc_coast", 50, 54, -129, -125, val_tiles=((52, -127),),
               note="inlets, coastal range, outer islands"),
        Region("iceland_west", 64, 67, -24, -19, val_tiles=((65, -22),),
               note="Westfjords plateau fjords, volcanic peninsulas"),
        Region("aegean", 35, 39, 23, 29, val_tiles=((37, 25),),
               note="Cyclades/Dodecanese: drowned horst-and-graben islands, Mediterranean"),
        Region("dalmatia", 42, 45, 14, 18, val_tiles=((43, 16),),
               note="karst ridges parallel to the coast, island chains"),
        Region("baltic_skerries", 59, 61, 17, 23, val_tiles=((60, 21),),
               note="Stockholm / Aland / Turku archipelagos: glacially scoured skerries"),
        Region("outer_hebrides", 56, 59, -8, -6, val_tiles=((57, -8),),
               note="low knock-and-lochan islands, machair, sounds"),
        Region("canaries", 27, 30, -19, -13, val_tiles=((28, -17),),
               note="volcanic islands, calderas, barrancos, arid to humid windward"),
        Region("hawaii", 18, 23, -161, -154, val_tiles=((19, -156),),
               note="shield volcanoes, wet windward / dry leeward, deep valleys on old islands"),
        Region("visayas", 9, 12, 122, 126, val_tiles=((10, 123),),
               note="tropical archipelago, volcanic and limestone islands"),
        Region("halong", 20, 22, 106, 108, val_tiles=((20, 107),),
               note="karst towers drowned in a bay, delta plain"),
        Region("seto_inland", 33, 35, 131, 135, val_tiles=((34, 133),),
               note="inland sea dense with granite islands"),
        Region("wadden", 52, 55, 4, 10, val_tiles=((53, 6),),
               note="tidal flats, barrier islands, lagoons (Venice-like flats; Venice itself is inside alps)"),
        Region("outer_banks", 34, 37, -78, -75, val_tiles=((35, -76),),
               note="barrier islands, sounds, lagoons"),
        # Extreme relief (EXP-2, 2026-11-18): the relief node flattened the highest ranges (Karakoram 0.58, Alps 0.62
        # amplitude ratio), so more of the world's steepest terrain goes in.
        Region("himalaya_nepal", 27, 30, 83, 88, val_tiles=((28, 84),),
               note="Annapurna to Everest: 8000 m peaks, Gangetic foothills to the Tibetan plateau"),
        Region("caucasus", 42, 44, 40, 47, val_tiles=((42, 44),),
               note="Greater Caucasus: Elbrus, Kazbek, deep gorges, Black Sea to Caspian steppe"),
        Region("andes_aconcagua", -35, -31, -71, -68, val_tiles=((-34, -70),),
               note="high Andes: Aconcagua, glaciated cordillera, Chilean central valley to Cuyo"),
        Region("guilin_karst", 24, 26, 109, 112, val_tiles=((25, 111),),
               note="tower karst (fenglin / fengcong): Li river, Yangshuo; 200-500 m towers resolve at 30 m"),
        Region("zhangjiajie", 28, 30, 109, 111, val_tiles=((28, 109),),
               note="Wulingyuan sandstone pillars and gorges (pillars are 50-100 m: mostly sub-pixel at 30-60 m)"),
        # Deep ocean (EXP-2, 2026-11-18): trench walls, ridge fabric and shelf canyons for the sea-floor nodes. Sea
        # floors are GEBCO (450 m, resolves features km wide); Puerto Rico + Hawaii also get NOAA CUDEM 1/9" truth.
        Region("mariana_trench", 10, 15, 141, 147, val_tiles=((13, 146),),
               note="Challenger Deep (-10.9 km), trench + forearc, Guam"),
        Region("puerto_rico", 17, 21, -68, -64, val_tiles=((20, -65),),
               note="island, reef shelf (CUDEM), Puerto Rico Trench (-8.4 km) to the north"),
        Region("mid_atlantic_ridge", 22, 26, -47, -43, val_tiles=((24, -44),),
               note="slow-spreading ridge: rift valley, abyssal-hill fabric, transform faults"),
        Region("atlantic_canyons", 38, 41, -74, -69, val_tiles=((39, -70),),
               note="US Atlantic shelf, shelf break and submarine canyons (Hudson), New Jersey / Long Island"),
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
