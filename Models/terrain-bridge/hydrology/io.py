"""DEM input, water-map output, and georeference passthrough.

Alignment is the one hard requirement here: the water map must overlay the elevation
map cell for cell. That is enforced, not assumed -- `save_map` refuses a shape that
differs from the source, and a GeoTIFF's CRS and affine transform are copied through
verbatim. Nothing in this module resamples, crops, or reprojects.

GeoTIFF conventions match upstream's `terrain_diffusion/inference/tiff_export.py`
(LZW, 256x256 tiles) so the outputs sit alongside its elevation rasters cleanly.
`rasterio` is imported lazily, so .npy workflows do not need it installed.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from bridge.config import BridgeConfig
from bridge.height_mapping import HeightCurve

TILE_HEADER_BYTES = 8
_TILE_RE = re.compile(r"^s(-?\d+)_x(-?\d+)_z(-?\d+)_sc(\d+)\.bin$")


def _bridge_config() -> BridgeConfig:
    """The bridge's env-configured knobs, for reading a tile cache back.

    The seed is supplied rather than read from the environment: nothing needed
    here depends on it -- the tile's own seed comes from its filename -- and
    demanding `TERRAIN_BRIDGE_SEED` would stop the offline scripts running at all.
    """
    return BridgeConfig.from_env(seed=0)


@dataclass(frozen=True)
class DemMeta:
    """Everything needed to write a companion raster onto the same grid."""

    shape: tuple[int, int]
    crs: object | None = None
    transform: object | None = None
    # Ground distance across one cell, horizontally. Never a vertical rate --
    # see `hydrology.depth.DEFAULT_HORIZONTAL_CELL_SIZE_M`.
    horizontal_cell_size_m: float | None = None
    extra: dict = field(default_factory=dict)


def load_dem(path: str | Path) -> tuple[np.ndarray, DemMeta]:
    """Load a single-channel elevation map in metres.

    Accepts GeoTIFF (`.tif`/`.tiff`) and NumPy (`.npy`). The source nodata value is
    converted to NaN, which the rest of the package already treats as ocean.
    """
    path = Path(path)
    suffix = path.suffix.lower()

    if suffix in (".tif", ".tiff"):
        import rasterio

        with rasterio.open(path) as ds:
            arr = ds.read(1).astype(np.float64)
            if ds.nodata is not None:
                arr[arr == ds.nodata] = np.nan
            meta = DemMeta(
                shape=arr.shape,
                crs=ds.crs,
                transform=ds.transform,
                extra={"nodata": ds.nodata},
            )
        return arr, meta

    if suffix == ".npy":
        arr = np.load(path).astype(np.float64)
        if arr.ndim != 2:
            raise ValueError(f"{path} holds a {arr.ndim}D array; expected 2D elevation")
        return arr, DemMeta(shape=arr.shape)

    raise ValueError(f"unsupported DEM format '{suffix}' (expected .tif, .tiff or .npy)")


def save_map(path: str | Path, arr: np.ndarray, meta: DemMeta | None = None) -> Path:
    """Write a derived raster onto the source grid.

    Raises if `arr` does not match `meta.shape` -- a silently misaligned water map is
    worse than no water map, because nothing downstream would notice.
    """
    path = Path(path)
    arr = np.asarray(arr)
    if meta is not None and arr.shape != meta.shape:
        raise ValueError(
            f"output shape {arr.shape} does not match the source DEM {meta.shape}; "
            "the water map must align cell-for-cell with its elevation map"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    suffix = path.suffix.lower()

    if suffix in (".tif", ".tiff"):
        import rasterio

        out = arr.astype(np.float32)
        with rasterio.open(
            path, "w",
            driver="GTiff", height=out.shape[0], width=out.shape[1],
            count=1, dtype="float32",
            crs=meta.crs if meta else None,
            transform=meta.transform if meta else None,
            compress="lzw", tiled=True, blockxsize=256, blockysize=256,
        ) as dst:
            dst.write(out, 1)
        return path

    if suffix == ".npy":
        np.save(path, arr.astype(np.float32))
        return path

    raise ValueError(f"unsupported output format '{suffix}' (expected .tif, .tiff or .npy)")


def vertical_resolution_warning(z: np.ndarray, min_levels: int = 200) -> str | None:
    """Warn when a DEM is too coarsely quantised for flow routing to mean anything.

    Flow routing needs each cell to have a strictly lower neighbour. A DEM stored as
    whole Stonebreak blocks has none over huge areas -- a 1024-cell window of the
    bridge's tile cache carries about 31 distinct elevations and 84% of its land is
    exactly level. Depression filling and flat resolution will still produce an answer
    there, and it will still be self-consistent, but the channels are laid out by a
    distance field rather than by terrain and come out as straight parallel lines.

    Nothing downstream can detect this, so it is worth saying out loud. Derive water
    from elevation in metres (`tiff-export`, or the bridge upstream of
    `elevation_to_block_height`), not from block heights.
    """
    land = np.isfinite(z) & (z > 0)
    if land.sum() < 2:
        return None
    levels = np.unique(z[land])
    if levels.size >= min_levels:
        return None
    step = float(np.diff(levels).min()) if levels.size > 1 else 0.0
    return (
        f"DEM has only {levels.size} distinct land elevations "
        f"(smallest step {step:g} m). Flow routing needs finer vertical detail than "
        "this: most cells have no lower neighbour, so rivers will follow the flat "
        "resolver rather than the terrain. Use elevation in metres, not block heights."
    )


def _read_tile(path: Path) -> np.ndarray:
    """Decode one bridge cache tile into its block-height plane.

    Mirrors `bridge/cache.py::TileCache.get` -- 8-byte (h, w) header, then the
    block-height plane, then the biome plane. Only the first is needed here.
    """
    data = path.read_bytes()
    h, w = (int(v) for v in np.frombuffer(data[:TILE_HEADER_BYTES], dtype="<u4"))
    body = data[TILE_HEADER_BYTES:]
    return np.frombuffer(body[: h * w * 2], dtype="<i2").reshape(h, w)


def available_scales(cache_dir: str | Path, seed: int) -> list[int]:
    scales = set()
    for p in Path(cache_dir).glob(f"s{seed}_*.bin"):
        m = _TILE_RE.match(p.name)
        if m:
            scales.add(int(m.group(4)))
    return sorted(scales)


def stitch_tile_cache(
    cache_dir: str | Path,
    seed: int,
    tile_x: int,
    tile_z: int,
    tiles: int = 4,
    *,
    scale: int | None = None,
    curve: HeightCurve | None = None,
    horizontal_cell_size_m: float | None = None,
) -> tuple[np.ndarray, DemMeta]:
    """Reassemble real diffusion terrain from the bridge's on-disk tile cache.

    This is the realistic test DEM: genuine model output, no GPU, no running server,
    no model download. Tiles are stored as Stonebreak block heights, so this inverts
    `bridge/height_mapping.py::HeightCurve.to_block_height`.

    Inverting the *live* curve matters. Before Phase 5 the forward mapping was
    linear and this could undo it with `(blocks - sea_level) * meters_per_block`;
    that mapping no longer exists, and applying it to a tile written by the curve
    reports lowlands ~3.75x too tall while flattening the highlands. A tile cache
    directory is named after the config fingerprint that produced it, so it can
    only be read back through the same knobs -- pass the `curve` those tiles were
    written with.

    Two lossy steps are baked into the round trip and are worth knowing about: the
    forward mapping floors to whole blocks, so elevation comes back quantised to
    whatever the curve's rate is locally (~4 m in lowlands after Phase 5, ~24 m in
    highlands), and it clips to the world column, so cells at block 0 or
    `world_height - 1` were clamped and their true elevation is unrecoverable.
    Neither matters for routing; the quantisation is in fact a useful stress test
    of the flat-drainage path -- see the README on why block heights are a poor
    routing input.

    Args:
        cache_dir: a config-fingerprint directory under `tile_cache/`.
        seed, tile_x, tile_z: origin tile. `tile_x` indexes rows (world i), `tile_z`
            columns (world j), matching `bridge/tiling.py`.
        tiles: edge length in tiles, so the result is `tiles * 256` cells square.
        curve: the elevation curve these tiles were generated with. Defaults to the
            bridge's own env-configured curve.
        horizontal_cell_size_m: ground distance across one cell. Defaults to the
            bridge's `horizontal_meters_per_block`; unrelated to the curve.

    Returns:
        (elevation in metres, meta).
    """
    cfg = _bridge_config()
    if curve is None:
        curve = HeightCurve.from_config(cfg)
    if horizontal_cell_size_m is None:
        horizontal_cell_size_m = cfg.horizontal_meters_per_block
    cache_dir = Path(cache_dir)
    if scale is None:
        scales = available_scales(cache_dir, seed)
        if not scales:
            raise FileNotFoundError(f"no cached tiles for seed {seed} in {cache_dir}")
        scale = scales[0]

    grid = None
    tile_size = None
    for i in range(tiles):
        for j in range(tiles):
            name = f"s{seed}_x{tile_x + i}_z{tile_z + j}_sc{scale}.bin"
            p = cache_dir / name
            if not p.exists():
                raise FileNotFoundError(
                    f"missing tile {name}; the {tiles}x{tiles} block at "
                    f"(x={tile_x}, z={tile_z}) is not fully cached"
                )
            block = _read_tile(p)
            if grid is None:
                tile_size = block.shape[0]
                grid = np.empty((tile_size * tiles, tile_size * tiles), dtype=np.int16)
            grid[i * tile_size:(i + 1) * tile_size, j * tile_size:(j + 1) * tile_size] = block

    elev = curve.to_elevation(grid)
    meta = DemMeta(
        shape=elev.shape,
        horizontal_cell_size_m=horizontal_cell_size_m,
        extra={"source": "tile_cache", "seed": seed, "scale": scale,
               "tile_x": tile_x, "tile_z": tile_z, "tiles": tiles},
    )
    return elev, meta
