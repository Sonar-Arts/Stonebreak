"""Far-zoom overview tiles for the terrain mapper.

A zoomed-out mapper samples one column every 8+ blocks, but a full tile is only
256 blocks wide, so a 20k-block view touches thousands of them -- each a
separate per-tile request at ~20k native px/s. An overview tile at level of
detail `lod` stands for `lod x lod` blocks per sample and covers
`tile_size * lod` blocks, fetched as ONE square native-resolution request and
pooled. Square bulk runs at ~1000k px/s (Dev Working/Rivers and lakes plan.md
section 13.3), so at lod 8 one 1024^2 px request replaces 64 tiles: roughly
1.5 s instead of ~50 s for the same 2048-block square.

Addressing follows the tile path with every coordinate in SAMPLE units
(world block // lod): the tile shape stays 256x256 and `tile_bounds` gives the
sample box. The protocol is the one Project-Daedalus uses for the same feature,
so the Java client speaks to either.

What it leaves out: no hydrology. Water is the sea-level rule only; inland lakes
and rivers come from the native water kernel over full tiles, which is exactly
the cost this exists to skip. For the mapper zoomed out, never for chunks.
"""
from __future__ import annotations

import numpy as np

from .config import BridgeConfig
from .height_mapping import HeightCurve
from .tiling import TileId, tile_bounds

# Largest square fetched for one overview tile, in native px per side. At the
# default 256-block tile and scale 2 this admits lod 2..16 (lod 16 = 2048 px).
# Bigger squares cost multi-GiB of upstream tile store per request
# (l0 region measurements), for detail no mapper zoom can show.
MAX_NATIVE_PX = 2048


def check_lod(cfg: BridgeConfig, lod: int) -> None:
    """Raises ValueError for a level of detail this bridge cannot serve."""
    if lod == 1:
        return
    if lod < 1 or lod & (lod - 1):
        raise ValueError(f"lod {lod} is not a power of two")
    if lod % cfg.scale:
        raise ValueError(f"lod {lod} is not a whole number of native pixels at scale {cfg.scale}")
    px = cfg.tile_size_blocks * lod // cfg.scale
    if px > MAX_NATIVE_PX:
        raise ValueError(f"lod {lod} needs a {px}px square fetch, over the {MAX_NATIVE_PX}px limit")


class OverviewTiles:
    def __init__(self, cfg: BridgeConfig, client):
        self._cfg = cfg
        self._client = client
        self._curve = HeightCurve.from_config(cfg)

    def planes(self, tile: TileId) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """(block height, biome, water level) int16 planes, row = sample X, col = sample Z."""
        check_lod(self._cfg, tile.lod)
        i1, j1, i2, j2 = tile_bounds(tile.tile_x, tile.tile_z, self._cfg.tile_size_blocks)
        k = tile.lod // self._cfg.scale  # native px per sample side
        n = i2 - i1
        elev_m, biome = self._client.fetch_native_with_biome(i1 * k, j1 * k, i2 * k, j2 * k)
        if elev_m.shape != (n * k, n * k):
            raise ValueError(f"upstream returned {elev_m.shape} for overview tile "
                             f"{tile.cache_key()}, expected {(n * k, n * k)}")

        # Pool in METRES, like coarse.py: the curve is non-linear.
        pooled = elev_m.astype(np.float64).reshape(n, k, n, k).mean(axis=(1, 3))
        heights = self._curve.to_block_height(pooled)
        # Biome is categorical: the pixel under the sample's own corner, the column a
        # full-detail lattice point at the same place would read.
        biome = np.ascontiguousarray(biome[::k, ::k]).astype(np.int16)
        water = np.where(heights < self._cfg.sea_level, self._cfg.sea_level, -1).astype(np.int16)
        return heights, biome, water
