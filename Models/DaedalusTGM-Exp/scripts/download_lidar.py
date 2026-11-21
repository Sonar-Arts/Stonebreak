"""Open national lidar DTMs for SR-15 v2 (15 m truth) and the surface-detail node (sub-block labels), EXP-2 agent L.

    python scripts/download_lidar.py --root /mnt/extrastorage/terrain-slm-data/raw [--only sr|fine|us13] [--sources ...]

Two products per box, both float32 GeoTIFFs (deflate) in the provider's own projected CRS, nodata -9999:
- `<root>/<source>/sr10/<box>.tif`: 10 m grid, AREA-AVERAGED from the provider's finer DTM (COG overviews +
  `Resampling.average`, or 5 m service requests averaged 2x2, or 2 m tiles averaged 5x5). 10 m = the USGS 3DEP
  1/3 arc-second pixel the first SR-15 trained on, so `build_15m` takes every source to 15 m the same way.
- `<root>/<source>/fine/<win>.tif`: a few km at the best available resolution (0.5-2 m) for the surface labels.
Plus whole USGS 3DEP 1/3" 1-degree tiles in `<root>/3dep13_v2/` (new US landscapes; `raw/3dep` stays as sr15_r0 used it).

Every licence below was checked on the provider's own page / metadata on 2026-11-19 (evidence URLs in SOURCES and in
`<root>/<source>/PROVENANCE.json`, which also lists every URL each file was built from).
"""
from __future__ import annotations

import argparse
import json
import math
import os
import threading
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

NODATA = -9999.0

SOURCES = {
    "3dep13_v2": dict(name="USGS 3D Elevation Program (3DEP) 1/3 arc-second DEM", licence="Public domain (U.S. Government work)",
                      attribution="U.S. Geological Survey, 3D Elevation Program",
                      evidence=["https://www.usgs.gov/faqs/are-usgs-reportspublications-copyrighted ('USGS-authored or produced "
                                "data and information are considered to be in the U.S. public domain')"]),
    "usgs_1m": dict(name="USGS 3DEP 1 meter DEM (lidar)", licence="Public domain (U.S. Government work)",
                    attribution="U.S. Geological Survey, 3D Elevation Program",
                    evidence=["https://www.usgs.gov/faqs/are-usgs-reportspublications-copyrighted",
                              "https://tnmaccess.nationalmap.gov/api/v1/products (dataset 'Digital Elevation Model (DEM) 1 meter')"]),
    "kartverket": dict(name="Kartverket Nasjonal hoydemodell DTM (NHM, lidar), WCS nhm_dtm_topo_25833", licence="CC BY 4.0",
                       attribution="(c) Kartverket", crs=25833,
                       evidence=["https://www.kartverket.no/en/api-and-data/terms-of-use (free products 'are licensed under "
                                 "Creative Commons Attribution 4.0 International (CC BY 4.0)', credit (c)Kartverket)",
                                 "https://kartkatalog.geonorge.no/api/getdata/fd96e6c3-eff6-437c-8fd6-e7c4718b1bf8 (DTM1: Apne data, "
                                 "Creative Commons BY 4.0)",
                                 "https://kartkatalog.geonorge.no/api/getdata/0f0a0f38-00c4-4213-a9e5-2d861dc4abb0 (DTM 25833 WCS: "
                                 "Apne data, no conditions apply)"]),
    "swisstopo": dict(name="swisstopo swissALTI3D (lidar DTM, 0.5 m / 2 m tiles)", licence="swisstopo free geodata (OGD): "
                      "free use incl. commercial, source reference mandatory", attribution="(c) swisstopo", crs=2056,
                      evidence=["https://www.swisstopo.admin.ch/en/terms-of-use-free-geodata-and-geoservices ('may be enriched "
                                "and processed and also used commercially', 'A reference to the source is mandatory')"]),
    "ahn": dict(name="Actueel Hoogtebestand Nederland (AHN) DTM 0.5 m", licence="CC0 1.0", attribution="AHN (Rijkswaterstaat / "
                "Het Waterschapshuis)", crs=28992,
                evidence=["https://service.pdok.nl/rws/actueel-hoogtebestand-nederland/atom/dtm_05m.xml (<rights> "
                          "https://creativecommons.org/publicdomain/zero/1.0/)"]),
    "linz": dict(name="Toitu Te Whenua LINZ, New Zealand LiDAR 1m DEM", licence="CC BY 4.0",
                 attribution="Sourced from Toitu Te Whenua Land Information New Zealand, CC BY 4.0", crs=2193,
                 evidence=["s3://nz-elevation/new-zealand/new-zealand/dem_1m/2193/collection.json (license CC-BY-4.0, "
                           "licensor Toitu Te Whenua Land Information New Zealand)",
                           "https://registry.opendata.aws/nz-elevation/"]),
    "ign": dict(name="IGN RGE ALTI (1 m / 5 m DTM; WMS-R ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES) + IGN LiDAR HD MNT",
                licence="Licence Ouverte / Open Licence v2.0 (Etalab)", attribution="IGN - RGE ALTI, IGN - LiDAR HD", crs=2154,
                evidence=["https://www.data.gouv.fr/datasets/rge-alti-r (publisher IGN, license lov2)",
                          "https://www.data.gouv.fr/datasets/mnt-lidar-hd (publisher IGN, license lov2; fine windows use "
                          "WMS-R layer IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.LAMB93)",
                          "https://data.geopf.fr/wms-r GetCapabilities (Fees none; CGU https://cartes.gouv.fr/cgu)"]),
}

# (source, name, lon, lat, size_km, role) -> 10 m SR boxes. One 'val' (held out whole) per source.
SR_BOXES = [
    ("kartverket", "no_geiranger", 7.10, 62.10, 32, "val"),
    ("kartverket", "no_jotunheimen", 8.30, 61.55, 32, "train"),
    ("kartverket", "no_aurland", 7.20, 60.90, 32, "train"),
    ("kartverket", "no_lofoten", 13.90, 68.15, 32, "train"),
    ("kartverket", "no_lyngen", 20.20, 69.60, 32, "train"),
    ("kartverket", "no_hardangervidda", 7.60, 60.20, 32, "train"),
    ("kartverket", "no_romsdalen", 7.70, 62.50, 32, "train"),
    ("swisstopo", "ch_zermatt", 7.75, 46.02, 20, "val"),
    ("swisstopo", "ch_jungfrau", 7.95, 46.55, 20, "train"),
    ("swisstopo", "ch_jura", 6.75, 46.85, 20, "train"),
    ("swisstopo", "ch_emmental", 7.75, 46.95, 20, "train"),
    ("swisstopo", "ch_engadin", 9.85, 46.50, 20, "train"),
    ("swisstopo", "ch_ticino", 8.80, 46.30, 20, "train"),
    ("ahn", "nl_limburg", 5.90, 50.85, 24, "val"),
    ("ahn", "nl_flevoland", 5.55, 52.50, 24, "train"),
    ("ahn", "nl_veluwe", 5.85, 52.15, 24, "train"),
    ("ahn", "nl_dunes", 4.65, 52.55, 24, "train"),
    ("linz", "nz_ruapehu", 175.57, -39.28, 32, "val"),
    ("linz", "nz_taranaki", 174.06, -39.30, 32, "train"),
    ("linz", "nz_aoraki", 170.14, -43.60, 32, "train"),
    ("linz", "nz_rakaia", 171.90, -43.55, 32, "train"),
    ("linz", "nz_rotorua", 176.25, -38.10, 32, "train"),
    ("linz", "nz_banks", 172.95, -43.75, 32, "train"),
    ("linz", "nz_kaikoura", 173.60, -42.30, 32, "train"),
    ("linz", "nz_wanaka", 169.10, -44.60, 32, "train"),
    ("ign", "fr_pyrenees", 0.00, 42.75, 32, "val"),
    ("ign", "fr_chamonix", 6.87, 45.92, 32, "train"),
    ("ign", "fr_vercors", 5.50, 45.00, 32, "train"),
    ("ign", "fr_puys", 2.95, 45.77, 32, "train"),
    ("ign", "fr_landes", -1.15, 44.55, 32, "train"),
    ("ign", "fr_verdon", 6.35, 43.77, 32, "train"),
    ("ign", "fr_bretagne", -3.60, 48.35, 32, "train"),
    ("ign", "fr_corse", 9.00, 42.25, 32, "train"),
    # second batch (more landscapes per country, same rules)
    ("kartverket", "no_sunnmore", 6.60, 62.20, 32, "train"), ("kartverket", "no_finnmark", 25.00, 70.00, 32, "train"),
    ("kartverket", "no_senja", 17.40, 69.35, 32, "train"), ("kartverket", "no_rondane", 9.80, 61.90, 32, "train"),
    ("kartverket", "no_trondheim", 10.40, 63.40, 32, "train"), ("kartverket", "no_hardangerfjord", 6.40, 60.30, 32, "train"),
    ("swisstopo", "ch_gotthard", 8.60, 46.60, 20, "train"), ("swisstopo", "ch_santis", 9.35, 47.25, 20, "train"),
    ("swisstopo", "ch_valais", 7.40, 46.25, 20, "train"), ("swisstopo", "ch_lavaux", 6.75, 46.50, 20, "train"),
    ("ahn", "nl_hondsrug", 6.85, 52.85, 24, "train"), ("ahn", "nl_zeeland", 3.85, 51.50, 24, "train"),
    ("ahn", "nl_texel", 4.80, 53.10, 24, "train"),
    ("linz", "nz_otago_tors", 169.60, -45.20, 32, "train"), ("linz", "nz_coromandel", 175.70, -36.90, 32, "train"),
    ("linz", "nz_marlborough", 174.00, -41.20, 32, "train"), ("linz", "nz_hawkesbay", 176.60, -39.60, 32, "train"),
    ("linz", "nz_northland", 173.90, -35.40, 32, "train"), ("linz", "nz_wellington", 175.00, -41.20, 32, "train"),
    ("ign", "fr_ecrins", 6.35, 44.90, 32, "train"), ("ign", "fr_causses", 3.30, 44.25, 32, "train"),
    ("ign", "fr_vosges", 7.00, 48.05, 32, "train"), ("ign", "fr_etretat", 0.25, 49.70, 32, "train"),
    ("ign", "fr_camargue", 4.55, 43.55, 32, "train"), ("ign", "fr_morvan", 4.05, 47.15, 32, "train"),
]

# New USGS 1/3" 1-degree tiles (USGS names = NORTH-west corner). One held out whole.
US13_TILES = [("n49w114", "val"), ("n48w124", "train"), ("n47w122", "train"), ("n37w119", "train"), ("n38w120", "train"),
              ("n44w111", "train"), ("n39w110", "train"), ("n39w080", "train"), ("n44w102", "train"), ("n45w072", "train"),
              ("n26w081", "train"), ("n44w122", "train")]

# (source, name, lon, lat, size_km, res_m) -> fine windows for the surface labels.
FINE_WINDOWS = [
    ("usgs_1m", "us_whitney", -118.30, 36.58, 4, 1.0), ("usgs_1m", "us_yosemite", -119.55, 37.75, 4, 1.0),
    ("usgs_1m", "us_rmnp", -105.68, 40.28, 4, 1.0), ("usgs_1m", "us_arches", -109.58, 38.75, 4, 1.0),
    ("usgs_1m", "us_grandcanyon", -112.10, 36.10, 4, 1.0), ("usgs_1m", "us_wv", -79.50, 38.60, 4, 1.0),
    ("usgs_1m", "us_iowa", -93.50, 42.00, 4, 1.0), ("usgs_1m", "us_bend_lava", -121.40, 43.80, 4, 1.0),
    ("usgs_1m", "us_glacier", -113.70, 48.70, 4, 1.0), ("usgs_1m", "us_mojave", -115.60, 35.00, 4, 1.0),
    ("usgs_1m", "us_badlands", -102.20, 43.80, 4, 1.0), ("usgs_1m", "us_tetons", -110.80, 43.75, 4, 1.0),
    ("usgs_1m", "us_hawaii_lava", -155.30, 19.40, 4, 1.0), ("usgs_1m", "us_whitemtns", -71.30, 44.27, 4, 1.0),
    ("swisstopo", "ch_jungfrau_f", 7.97, 46.57, 4, 0.5), ("swisstopo", "ch_zermatt_f", 7.78, 45.98, 4, 0.5),
    ("swisstopo", "ch_jura_f", 6.40, 46.65, 4, 0.5), ("swisstopo", "ch_engadin_f", 9.95, 46.45, 4, 0.5),
    ("kartverket", "no_lofoten_f", 14.00, 68.20, 4, 1.0), ("kartverket", "no_romsdal_f", 7.75, 62.45, 4, 1.0),
    ("kartverket", "no_hardanger_f", 7.50, 60.15, 4, 1.0), ("kartverket", "no_lyngen_f", 20.10, 69.60, 4, 1.0),
    ("kartverket", "no_jotunheimen_f", 8.30, 61.50, 4, 1.0),
    ("ahn", "nl_veluwe_f", 5.85, 52.10, 4, 1.0), ("ahn", "nl_limburg_f", 5.90, 50.83, 4, 1.0),
    ("linz", "nz_taranaki_f", 174.05, -39.27, 4, 1.0), ("linz", "nz_ruapehu_f", 175.55, -39.25, 4, 1.0),
    ("linz", "nz_aoraki_f", 170.10, -43.65, 4, 1.0), ("linz", "nz_rakaia_f", 171.60, -43.45, 4, 1.0),
    ("linz", "nz_kaikoura_f", 173.55, -42.35, 4, 1.0),
    ("ign", "fr_chamonix_f", 6.90, 45.90, 4, 1.0), ("ign", "fr_pyrenees_f", 0.00, 42.72, 4, 1.0),
    ("ign", "fr_vercors_f", 5.45, 44.95, 4, 1.0), ("ign", "fr_corse_f", 8.95, 42.30, 4, 1.0),
    ("ign", "fr_puys_f", 2.95, 45.77, 4, 1.0),
    # second batch of fine windows (climate / landscape diversity for the surface node)
    ("usgs_1m", "us_flagstaff", -111.55, 35.35, 4, 1.0),
    ("usgs_1m", "us_zion", -112.95, 37.25, 4, 1.0),
    ("usgs_1m", "us_sthelens", -122.18, 46.20, 4, 1.0),
    ("usgs_1m", "us_sandia", -106.45, 35.20, 4, 1.0),
    ("usgs_1m", "us_smokies", -83.50, 35.60, 4, 1.0),
    ("usgs_1m", "us_michigan_up", -88.50, 46.50, 4, 1.0),
    ("usgs_1m", "us_texas_hill", -98.10, 30.30, 4, 1.0),
    ("usgs_1m", "us_sanbernardino", -116.85, 34.15, 4, 1.0),
    ("usgs_1m", "us_mammoth", -119.05, 37.60, 4, 1.0),
    ("usgs_1m", "us_evans", -105.64, 39.59, 4, 1.0),
    ("usgs_1m", "us_lakemead", -114.40, 36.20, 4, 1.0),
    ("usgs_1m", "us_palouse", -117.20, 46.80, 4, 1.0),
    ("usgs_1m", "us_yellowstone", -110.40, 44.75, 4, 1.0),
    ("usgs_1m", "us_maine_coast", -68.25, 44.35, 4, 1.0),
    ("usgs_1m", "us_olympics", -123.45, 47.80, 4, 1.0),
    ("swisstopo", "ch_aletsch_f", 8.05, 46.42, 4, 0.5),
    ("swisstopo", "ch_glarus_f", 9.05, 46.95, 4, 0.5),
    ("swisstopo", "ch_plateau_f", 8.25, 47.30, 4, 0.5),
    ("swisstopo", "ch_valais_f", 7.20, 46.15, 4, 0.5),
    ("kartverket", "no_trollveggen_f", 7.75, 62.50, 4, 1.0),
    ("kartverket", "no_trondheim_f", 10.30, 63.35, 4, 1.0),
    ("kartverket", "no_senja_f", 17.30, 69.35, 4, 1.0),
    ("kartverket", "no_rondane_f", 9.80, 61.90, 4, 1.0),
    ("kartverket", "no_finnmark_f", 25.00, 70.00, 4, 1.0),
    ("kartverket", "no_sunnmore_f", 6.60, 62.20, 4, 1.0),
    ("linz", "nz_otago_f", 169.60, -45.20, 4, 1.0),
    ("linz", "nz_coromandel_f", 175.70, -36.90, 4, 1.0),
    ("linz", "nz_hawkesbay_f", 176.60, -39.60, 4, 1.0),
    ("linz", "nz_marlborough_f", 174.00, -41.20, 4, 1.0),
    ("linz", "nz_banks_f", 172.95, -43.75, 4, 1.0),
    ("ign", "fr_ecrins_f", 6.35, 44.90, 4, 1.0),
    ("ign", "fr_causses_f", 3.30, 44.25, 4, 1.0),
    ("ign", "fr_vosges_f", 7.00, 48.05, 4, 1.0),
    ("ign", "fr_etretat_f", 0.25, 49.70, 4, 1.0),
    ("ign", "fr_beauce_f", 1.80, 48.30, 4, 1.0),
    ("ign", "fr_ventoux_f", 5.28, 44.17, 4, 1.0),
    ("ign", "fr_pyrenees_w_f", -0.45, 42.95, 4, 1.0),
    ("ahn", "nl_hondsrug_f", 6.85, 52.85, 4, 1.0),
    ("ahn", "nl_texel_f", 4.80, 53.10, 4, 1.0),
]

GDAL_ENV = dict(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", GDAL_HTTP_MULTIRANGE="YES",
                GDAL_HTTP_MERGE_CONSECUTIVE_RANGES="YES", GDAL_HTTP_MAX_RETRY="6", GDAL_HTTP_RETRY_DELAY="3",
                CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif,.tiff", VSI_CACHE="TRUE", GDAL_CACHEMAX="512")
os.environ.update(GDAL_ENV)
_lock = threading.Lock()


def log(msg: str) -> None:
    with _lock:
        print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def http_get(url: str, timeout: int = 300, tries: int = 6) -> bytes:
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "terrain-slm-data/1.0"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - retried, then raised
            if k == tries - 1:
                raise
            log(f"retry {k + 1} {url[:120]}: {e}")
            time.sleep(4 * (k + 1))
    raise RuntimeError("unreachable")


def box_bounds(epsg: int, lon: float, lat: float, km: float, snap: float) -> tuple[float, float, float, float]:
    from rasterio.warp import transform
    xs, ys = transform("EPSG:4326", f"EPSG:{epsg}", [lon], [lat])
    h = km * 500.0
    x0, y0 = math.floor((xs[0] - h) / snap) * snap, math.floor((ys[0] - h) / snap) * snap
    return x0, y0, x0 + 2 * h, y0 + 2 * h


def write_tif(path: Path, a: np.ndarray, epsg: int, bounds, res: float, extra: dict) -> None:
    import rasterio
    from rasterio.transform import from_origin
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".part.tif")
    prof = dict(driver="GTiff", dtype="float32", count=1, width=a.shape[1], height=a.shape[0], crs=f"EPSG:{epsg}",
                transform=from_origin(bounds[0], bounds[3], res, res), nodata=NODATA, compress="deflate", predictor=3,
                tiled=True, blockxsize=512, blockysize=512, zlevel=6)
    with rasterio.open(tmp, "w", **prof) as dst:
        dst.write(a.astype(np.float32), 1)
        dst.update_tags(**{k: json.dumps(v) if not isinstance(v, str) else v for k, v in extra.items()})
    tmp.rename(path)


def block_avg(a: np.ndarray, k: int) -> np.ndarray:
    """k x k area mean, nodata-aware (a cell is nodata only when all of its pixels are)."""
    h, w = a.shape[0] // k * k, a.shape[1] // k * k
    a = a[:h, :w].reshape(h // k, k, w // k, k)
    ok = a > NODATA + 1
    s = np.where(ok, a, 0).sum((1, 3), dtype=np.float64)
    n = ok.sum((1, 3))
    return np.where(n > 0, s / np.maximum(n, 1), NODATA).astype(np.float32)


# ---------------------------------------------------------------------------------------------------------- fetchers
def cog_mosaic(urls: list[str], epsg: int, bounds, res: float) -> np.ndarray:
    """Area-averaged mosaic of remote COGs (GDAL picks the overview, Resampling.average finishes)."""
    import rasterio
    from rasterio.enums import Resampling
    from rasterio.merge import merge
    with rasterio.Env():
        srcs = [rasterio.open("/vsicurl/" + u) for u in urls]
        try:
            arr, _ = merge(srcs, bounds=bounds, res=res, nodata=NODATA, resampling=Resampling.average, dtype="float32")
        finally:
            for s in srcs:
                s.close()
    a = arr[0]
    a[~np.isfinite(a)] = NODATA
    return a


_ahn_index: list | None = None
_nz_index: dict | None = None


def ahn_urls(lon0, lat0, lon1, lat1) -> list[str]:
    global _ahn_index
    with _lock:
        if _ahn_index is None:
            import re
            x = http_get("https://service.pdok.nl/rws/actueel-hoogtebestand-nederland/atom/dtm_05m.xml").decode()
            _ahn_index = [(m.group(1), tuple(map(float, m.group(2).split()))) for m in
                          re.finditer(r'href="([^"]+\.tif)"[^>]*bbox="([^"]+)"', x)]
    return [u for u, (a, b, c, d) in _ahn_index if a < lon1 and c > lon0 and b < lat1 and d > lat0]


def nz_urls(lon0, lat0, lon1, lat1) -> list[str]:
    global _nz_index
    base = "https://nz-elevation.s3.ap-southeast-2.amazonaws.com/new-zealand/new-zealand/dem_1m/2193/"
    with _lock:
        if _nz_index is None:
            coll = json.loads(http_get(base + "collection.json"))
            names = [l["href"][2:] for l in coll["links"] if l["rel"] == "item"]
            with ThreadPoolExecutor(32) as p:
                items = list(p.map(lambda n: json.loads(http_get(base + n)), names))
            _nz_index = {i["id"]: i["bbox"] for i in items}
    return [base + f"{k}.tiff" for k, (a, b, c, d) in _nz_index.items() if a < lon1 and c > lon0 and b < lat1 and d > lat0]


def usgs1m_urls(lon0, lat0, lon1, lat1) -> list[str]:
    q = ("https://tnmaccess.nationalmap.gov/api/v1/products?datasets=Digital%20Elevation%20Model%20(DEM)%201%20meter"
         f"&bbox={lon0},{lat0},{lon1},{lat1}&max=200&prodFormats=GeoTIFF")
    items = json.loads(http_get(q, timeout=120)).get("items", [])
    # Prefer the newest project per tile name (x..y.. cell); TNM lists every project that covers the box.
    best: dict[str, dict] = {}
    for it in items:
        u = it["downloadURL"]
        if not u.endswith(".tif"):
            continue
        cell = u.rsplit("/", 1)[1].split("_")[2:4]
        key = "_".join(cell)
        if key not in best or it.get("publicationDate", "") > best[key].get("publicationDate", ""):
            best[key] = it
    return [it["downloadURL"] for it in best.values()]


def latlon_box(epsg, bounds):
    from rasterio.warp import transform_bounds
    return transform_bounds(f"EPSG:{epsg}", "EPSG:4326", *bounds, densify_pts=21)


def fetch_cog_source(src: str, bounds, res: float, epsg: int) -> tuple[np.ndarray, list[str], int]:
    lon0, lat0, lon1, lat1 = latlon_box(epsg, bounds)
    urls = ahn_urls(lon0, lat0, lon1, lat1) if src == "ahn" else nz_urls(lon0, lat0, lon1, lat1)
    if not urls:
        raise RuntimeError("no tiles cover the box")
    return cog_mosaic(urls, epsg, bounds, res), urls, epsg


def fetch_usgs1m(lon, lat, km, res, snap):
    """USGS 3DEP 1 m tiles (TNM API); the window is laid out in the UTM zone of the first tile found."""
    import rasterio
    from rasterio.warp import transform_bounds
    d = km / 111.0 * 0.75
    urls = usgs1m_urls(lon - d / math.cos(math.radians(lat)), lat - d, lon + d / math.cos(math.radians(lat)), lat + d)
    if not urls:
        raise RuntimeError("no 1 m tiles")
    crs = {}
    for u in urls:
        with rasterio.Env(), rasterio.open("/vsicurl/" + u) as s:
            crs[u] = s.crs.to_epsg()
    from collections import Counter
    epsg = Counter(crs.values()).most_common(1)[0][0]
    bounds = box_bounds(epsg, lon, lat, km, snap)
    lb = transform_bounds(f"EPSG:{epsg}", "EPSG:4326", *bounds)
    same = [u for u in usgs1m_urls(*lb) if crs.get(u, epsg) == epsg]
    return cog_mosaic(same, epsg, bounds, res), same, epsg, bounds


def fetch_wcs_no(bounds, res: float) -> tuple[np.ndarray, list[str]]:
    """Kartverket WCS (max 3840 x 2160 per request); res 10 -> 5 m requests averaged 2x2."""
    req_res = 5.0 if res >= 10 else res
    k = int(round(res / req_res))
    x0, y0, x1, y1 = bounds
    W, H = int(round((x1 - x0) / req_res)), int(round((y1 - y0) / req_res))
    out = np.full((H, W), NODATA, np.float32)
    cw, ch = 2000, 2000
    jobs = [(c, r) for r in range(0, H, ch) for c in range(0, W, cw)]
    urls = []

    def one(cr):
        import rasterio
        from rasterio.io import MemoryFile
        c, r = cr
        w, h = min(cw, W - c), min(ch, H - r)
        bx0, by1 = x0 + c * req_res, y1 - r * req_res
        bb = f"{bx0},{by1 - h * req_res},{bx0 + w * req_res},{by1}"
        u = ("https://wcs.geonorge.no/skwms1/wcs.hoyde-dtm-nhm-25833?service=WCS&version=1.0.0&request=GetCoverage"
             f"&coverage=nhm_dtm_topo_25833&CRS=EPSG:25833&BBOX={bb}&WIDTH={w}&HEIGHT={h}&FORMAT=GeoTIFF")
        data = http_get(u)
        with MemoryFile(data) as mf, mf.open() as s:
            a = s.read(1).astype(np.float32)
            if s.nodata is not None:
                a[a == s.nodata] = NODATA
        a[(a <= 0.0) | ~np.isfinite(a)] = NODATA      # NHM DTM: sea = 0 m (no nodata value set)
        out[r : r + h, c : c + w] = a
        return u

    with ThreadPoolExecutor(4) as p:
        urls = list(p.map(one, jobs))
    return (block_avg(out, k) if k > 1 else out), urls


def fetch_wmsr_fr(bounds, res: float) -> tuple[np.ndarray, list[str]]:
    """IGN Geoplateforme WMS-R float32 BIL (max 5010 px); res 10 -> 5 m requests averaged 2x2."""
    req_res = 5.0 if res >= 10 else res
    k = int(round(res / req_res))
    x0, y0, x1, y1 = bounds
    W, H = int(round((x1 - x0) / req_res)), int(round((y1 - y0) / req_res))
    out = np.full((H, W), NODATA, np.float32)
    cs = 4000
    jobs = [(c, r) for r in range(0, H, cs) for c in range(0, W, cs)]

    def one(cr):
        c, r = cr
        w, h = min(cs, W - c), min(cs, H - r)
        bx0, by1 = x0 + c * req_res, y1 - r * req_res
        layer = ("ELEVATION.ELEVATIONGRIDCOVERAGE.HIGHRES" if res >= 10 else   # RGE ALTI (1 m lidar / 5 m) for 10 m boxes
                 "IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.LAMB93")      # LiDAR HD MNT (0.5 m) for fine windows
        u = ("https://data.geopf.fr/wms-r?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=" + layer +
             f"&STYLES=&CRS=EPSG:2154&BBOX={bx0},{by1 - h * req_res},{bx0 + w * req_res},{by1}&WIDTH={w}&HEIGHT={h}"
             "&FORMAT=image/x-bil;bits=32")
        data = http_get(u)
        a = np.frombuffer(data, "<f4").copy()
        if a.size != w * h:
            raise RuntimeError(f"WMS-R returned {len(data)} bytes for {w}x{h}: {data[:200]!r}")
        a = a.reshape(h, w)
        a[(a < -500) | (a > 9000) | ~np.isfinite(a)] = NODATA
        out[r : r + h, c : c + w] = a
        return u

    with ThreadPoolExecutor(4) as p:
        urls = list(p.map(one, jobs))
    return (block_avg(out, k) if k > 1 else out), urls


def fetch_swiss(bounds, res: float, cache: Path) -> tuple[np.ndarray, list[str]]:
    """swissALTI3D 1 km tiles (STAC); res >= 2 -> the 2 m tiles averaged, else the 0.5 m tiles."""
    import rasterio
    gsd = 2.0 if res >= 2 else 0.5
    k = int(round(res / gsd))
    x0, y0, x1, y1 = bounds
    lon0, lat0, lon1, lat1 = latlon_box(2056, bounds)
    feats, url = [], (f"https://data.geo.admin.ch/api/stac/v0.9/collections/ch.swisstopo.swissalti3d/items"
                      f"?bbox={lon0},{lat0},{lon1},{lat1}&limit=100")
    while url:
        d = json.loads(http_get(url))
        feats += d["features"]
        url = next((l["href"] for l in d.get("links", []) if l["rel"] == "next"), None)
    tiles: dict[tuple[int, int], tuple[str, str]] = {}
    for f in feats:     # id swissalti3d_<year>_<E km>-<N km>; keep the newest year per km cell
        _, year, cell = f["id"].split("_")
        e, n = map(int, cell.split("-"))
        href = next((a["href"] for a in f["assets"].values() if a["href"].endswith(".tif") and a.get("eo:gsd") == gsd), None)
        if href and ((e, n) not in tiles or tiles[(e, n)][0] < year):
            tiles[(e, n)] = (year, href)
    W, H = int(round((x1 - x0) / gsd)), int(round((y1 - y0) / gsd))
    out = np.full((H, W), NODATA, np.float32)

    def one(item):
        (e, n), (_, href) = item
        dst = cache / href.rsplit("/", 1)[1]
        if not dst.exists():
            dst.parent.mkdir(parents=True, exist_ok=True)
            tmp = dst.with_suffix(".part")
            tmp.write_bytes(http_get(href))
            tmp.rename(dst)
        with rasterio.open(dst) as s:
            a = s.read(1).astype(np.float32)
            if s.nodata is not None:
                a[a == s.nodata] = NODATA
            l, t = s.bounds.left, s.bounds.top
        c0, r0 = int(round((l - x0) / gsd)), int(round((y1 - t) / gsd))
        rs, cs_ = max(0, -r0), max(0, -c0)
        re_, ce = min(a.shape[0], H - r0), min(a.shape[1], W - c0)
        if re_ > rs and ce > cs_:
            out[r0 + rs : r0 + re_, c0 + cs_ : c0 + ce] = a[rs:re_, cs_:ce]
        return href

    with ThreadPoolExecutor(16) as p:
        urls = list(p.map(one, tiles.items()))
    return (block_avg(out, k) if k > 1 else out), urls


# ---------------------------------------------------------------------------------------------------------- drivers
def do_box(root: Path, src: str, name: str, lon: float, lat: float, km: float, res: float, kind: str, role: str) -> str:
    dst = root / src / kind / f"{name}.tif"
    if dst.exists():
        return f"{src}/{kind}/{name}: cached"
    t0 = time.time()
    epsg = SOURCES[src].get("crs", 4326)
    snap = max(res, 10.0)
    try:
        if src == "usgs_1m":
            a, urls, epsg, bounds = fetch_usgs1m(lon, lat, km, res, snap)
        elif src in ("ahn", "linz"):
            bounds = box_bounds(epsg, lon, lat, km, snap)
            a, urls, epsg = fetch_cog_source(src, bounds, res, epsg)
        else:
            bounds = box_bounds(epsg, lon, lat, km, snap)
            if src == "kartverket":
                a, urls = fetch_wcs_no(bounds, res)
            elif src == "ign":
                a, urls = fetch_wmsr_fr(bounds, res)
            elif src == "swisstopo":
                a, urls = fetch_swiss(bounds, res, root / src / "tiles")
            else:
                raise ValueError(src)
    except Exception as e:  # noqa: BLE001 - reported, the run continues with the other boxes
        return f"{src}/{kind}/{name}: FAILED {type(e).__name__}: {e}"
    valid = float((a > NODATA + 1).mean())
    meta = dict(source=src, licence=SOURCES[src]["licence"], attribution=SOURCES[src]["attribution"], role=role,
                centre_lonlat=[lon, lat], size_km=km, res_m=res, epsg=epsg, valid_frac=round(valid, 4),
                n_inputs=len(urls), method="area-average" if kind == "sr10" else "native")
    write_tif(dst, a, epsg, bounds, res, meta)
    with _lock:
        pf = root / src / "PROVENANCE.jsonl"
        with open(pf, "a") as f:
            f.write(json.dumps(dict(file=str(dst.relative_to(root)), **meta, urls=urls)) + "\n")
    return (f"{src}/{kind}/{name}: {a.shape} valid {valid:.2f} {dst.stat().st_size / 2**20:.0f} MiB "
            f"{len(urls)} inputs {time.time() - t0:.0f}s")


def do_us13(root: Path, tile: str, role: str) -> str:
    out = root / "3dep13_v2"
    out.mkdir(parents=True, exist_ok=True)
    dst = out / f"USGS_13_{tile}.tif"
    if dst.exists() and dst.stat().st_size > 0:
        return f"3dep13_v2/{tile}: cached"
    url = f"https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/current/{tile}/USGS_13_{tile}.tif"
    t0 = time.time()
    try:
        data = http_get(url, timeout=600)
    except Exception as e:  # noqa: BLE001
        return f"3dep13_v2/{tile}: FAILED {e}"
    tmp = dst.with_suffix(".part")
    tmp.write_bytes(data)
    tmp.rename(dst)
    with _lock:
        with open(out / "PROVENANCE.jsonl", "a") as f:
            f.write(json.dumps(dict(file=dst.name, source="3dep13_v2", licence=SOURCES["3dep13_v2"]["licence"], role=role,
                                    urls=[url])) + "\n")
    return f"3dep13_v2/{tile}: {len(data) / 2**20:.0f} MiB {time.time() - t0:.0f}s"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=Path("/mnt/extrastorage/terrain-slm-data/raw"))
    ap.add_argument("--only", choices=["sr", "fine", "us13", "all"], default="all")
    ap.add_argument("--sources", nargs="*", default=None)
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args()
    os.environ.update(GDAL_ENV)
    for s, info in SOURCES.items():
        d = args.root / s
        d.mkdir(parents=True, exist_ok=True)
        (d / "LICENCE.json").write_text(json.dumps(dict(source=s, **info, checked="2026-11-19"), indent=1))
    jobs = []
    if args.only in ("us13", "all"):
        jobs += [(do_us13, (args.root, t, r)) for t, r in US13_TILES]
    if args.only in ("sr", "all"):
        jobs += [(do_box, (args.root, s, n, lo, la, km, 10.0, "sr10", role)) for s, n, lo, la, km, role in SR_BOXES]
    if args.only in ("fine", "all"):
        jobs += [(do_box, (args.root, s, n, lo, la, km, res, "fine", "fine")) for s, n, lo, la, km, res in FINE_WINDOWS]
    if args.sources:
        jobs = [j for j in jobs if (j[1][1] if j[0] is do_box else "3dep13_v2") in args.sources]
    with ThreadPoolExecutor(args.workers) as pool:
        for msg in pool.map(lambda j: j[0](*j[1]), jobs):
            log(msg)


if __name__ == "__main__":
    main()
