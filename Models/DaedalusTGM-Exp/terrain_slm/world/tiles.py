"""World tiles in game blocks: what the service sends and what the game's TerrainTile holds.

A tile is `tile_size` x `tile_size` samples at level of detail `lod` (world blocks per sample; 1
for chunks, larger for the terrain mapper zoomed out). Each sample averages `downscale * lod`
native 30 m pixels per axis. Six int16 planes, row = x, column = z:

  0 block height   ground occupies y < height
  1 biome id       game-compatible ids (terrain_slm.biomes)
  2 water level    first non-water y, -1 = dry; the sea fills every column below sea level
  3 tunnel floor   river tunnel floor (stone) under an overhang, -1 = none
  4 tunnel roof    river tunnel roof (stone), -1 = none
  5 flow octant    river flow direction 0..7 (0 = +x, toward +z), -1 = none

Blocks are decided here, once, by the river pipeline's block stages against the game's own height
curve (quantise, contain, undercuts, overhangs, flow), so nothing spills after quantisation.
"""
from __future__ import annotations

import math

import numpy as np
import torch
import torch.nn.functional as F

from terrain_slm import biomes as B
from terrain_slm.models import biomenet as BN

# Biome climate is lapse-adjusted by the REGIONAL elevation (generator.regional_elevation): with each
# column's own height, hard classifier thresholds made biome boundaries follow every hill's contours
# (desert rings around forested hilltops, white bands along hillsides).
REGIONAL_LAPSE = True
from terrain_slm.data.descriptors import CELL_PX
from terrain_slm.river import pipeline as RP
from terrain_slm.river.field import NO_FLOW, NO_TUNNEL
from terrain_slm.river.scale import NATIVE_M, BlockScale
from terrain_slm.world.world_config import WorldConfig

PLANES = 6
#: At a whole cell (8 native px) or coarser per sample, tiles come from the cells alone.
OVERVIEW_MIN_PX = CELL_PX
# D8 class (drow, dcol) -> flow octant (0 = +x, toward +z); class 8 = terminal. Must match data/build.py.
_D8_OCTANT = [round(math.atan2(dc, dr) / (math.pi / 4)) % 8
              for dr, dc in ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))] + [-1]


class TileBuilder:
    def __init__(self, gen, world: WorldConfig):
        self.gen, self.world = gen, world
        gen.set_block_scale(BlockScale.from_world(world, gen.device))

    def bounds(self, tile_x: int, tile_z: int) -> tuple[int, int, int, int]:
        """Sample coordinates (i1, j1, i2, j2) of a tile: the only shape ever built for it."""
        n = self.world.tile_size
        return tile_x * n, tile_z * n, (tile_x + 1) * n, (tile_z + 1) * n

    def build(self, seed: int, tile_x: int, tile_z: int, lod: int) -> np.ndarray:
        """(PLANES, tile_size, tile_size) int16."""
        if lod < 1 or lod & (lod - 1):
            raise ValueError(f"lod must be a power of two, got {lod}")
        i1, j1, i2, j2 = self.bounds(tile_x, tile_z)
        d = self.world.downscale * lod
        with self.gen.lock:
            self.gen.use_seed(seed)
            overview = d >= OVERVIEW_MIN_PX and d % OVERVIEW_MIN_PX == 0
            height, biome, level, wet, river = (self._overview if overview else self._blocks)(i1, j1, i2, j2, d)
            planes = self._planes(height, biome, level, wet, river)
        return planes

    # ------------------------------------------------------------------ block assembly
    def _planes(self, height, biome, level, wet, river) -> np.ndarray:
        """The water rule: the sea fills every column below sea level; a river column holds water up
        to its level where that stands above its ground, or anywhere inside its tunnel (an
        undercut bank keeps its full height above the water)."""
        top = self.world.world_height - 1
        sea = self.world.sea_level
        height = height.clamp(0, top)
        level = level.clamp(0, top)
        floor, roof, flow = river
        water = torch.where(height < sea, torch.full_like(height, sea), torch.full_like(height, -1))
        river_wet = wet & ((level > height) | (floor >= 0))
        water = torch.where(river_wet, torch.maximum(water, level), water)
        stack = torch.stack([height, biome.to(height.dtype), water, floor, roof, flow])
        return stack.to(torch.int16).cpu().numpy()

    def _blocks(self, i1: int, j1: int, i2: int, j2: int, d: int):
        """Samples of d x d native pixels, built by the river pipeline's block stages over the tile
        plus BLOCK_MARGIN samples of context (the block stages look at neighbours), then cropped."""
        gen, mb = self.gen, RP.BLOCK_MARGIN
        n1, m1, n2, m2 = (i1 - mb) * d, (j1 - mb) * d, (i2 + mb) * d, (j2 + mb) * d
        field, ctx = gen.river_field(n1, m1, n2, m2)
        cols = gen.river.columns(field, ctx, d)
        elev = gen.scale.to_metres(cols.height.float() + 0.5)  # biomes read mid-block metres
        lapse_elev = gen.regional_elevation(n1, m1, n2, m2) if REGIONAL_LAPSE else field.carved
        climate = F.avg_pool2d(gen.climate_native(n1, m1, n2, m2, lapse_elev)[None], d)[0]
        crop = lambda x: x[..., mb:-mb, mb:-mb]
        pad1 = lambda x: x[..., mb - 1 : -(mb - 1), mb - 1 : -(mb - 1)]
        if gen.biomenet is not None and d == 2:
            # Biome sidecar over the tile + its margin (receptive-field radius 23 < BLOCK_MARGIN), noise
            # coordinates aligned with the rule classifier's (block units), then cropped to the tile. It reads
            # the ground BEFORE the river pipeline (quantised like its training data): carved channels and
            # raised banks are a landform the real DEMs never show, and drew thin biome ribbons along rivers.
            sc = gen.scale
            pre = F.avg_pool2d(field.ground[None, None], d)[0, 0]
            pre = sc.to_metres(torch.floor(sc.to_blocks(pre)).clamp(0, sc.world_height - 1) + 0.5)
            biome = crop(BN.classify(gen.biomenet, pre, climate, i1 - mb, j1 - mb))
            # The sidecar knows land biomes only: sea columns keep the rule classifier's ocean ids (warm /
            # normal / cold / frozen ocean), which the game maps to OCEAN / ICE_FIELDS.
            rule = B._classify_biome(crop(elev), crop(climate), i1, j1, elev_padded=pad1(elev), pixel_size_m=NATIVE_M * d)
            ocean = torch.isin(rule, torch.tensor(B.OCEAN_IDS, device=rule.device, dtype=rule.dtype))
            biome = torch.where(ocean, rule, biome)
        else:
            biome = B._classify_biome(crop(elev), crop(climate), i1, j1, elev_padded=pad1(elev), pixel_size_m=NATIVE_M * d)
        river = tuple(crop(p).long() for p in (cols.floor, cols.roof, cols.flow))
        return crop(cols.height).long(), biome, crop(cols.water).long(), crop(cols.wet), river

    def _overview(self, i1: int, j1: int, i2: int, j2: int, d: int):
        """Far-zoom samples of d native pixels (whole 240 m cells) straight from the model's cell
        fields: coarse height, hydro-sidecar rivers (one sample wide) and their D8 flow. No synth,
        refiner or river pipeline -- a tiny fraction of a full tile's cost; previews only."""
        gen = self.gen
        k = d // CELL_PX
        h, w = i2 - i1, j2 - j1
        c = gen.overview_cells((i1 - 1) * k, (j1 - 1) * k, (h + 2) * k, (w + 2) * k)
        pool = lambda x: F.avg_pool2d(x[None], k)[0]
        elev = pool(c["height"][None])[0]
        peak, idx = F.max_pool2d(c["logacc"][None, None], k, return_indices=True)
        wet = (peak[0, 0] >= gen.cfg.river_log_threshold) & (elev > 0.5)
        sc = gen.scale
        hb = torch.floor(sc.to_blocks(elev)).clamp(0, sc.world_height - 1).long()
        none = torch.full(elev.shape, NO_TUNNEL, dtype=torch.long, device=elev.device)
        flow = torch.full_like(none, NO_FLOW)
        if c["d8"] is not None:
            cls = c["d8"].flatten()[idx[0, 0].flatten()].view(elev.shape).clamp(0, 8)
            flow = torch.where(wet, torch.tensor(_D8_OCTANT, device=elev.device)[cls], flow)
        elev_m = sc.to_metres(hb.float() + 0.5)
        crop = lambda x: x[..., 1:-1, 1:-1]
        biome = B._classify_biome(crop(elev_m), crop(pool(c["climate"])), i1, j1, elev_padded=elev_m,
                                  pixel_size_m=NATIVE_M * d)
        return crop(hb), biome, crop(hb + 1), crop(wet), (crop(none), crop(none), crop(flow))
