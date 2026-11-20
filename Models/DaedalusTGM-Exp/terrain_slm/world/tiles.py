"""World tiles in game blocks: what the service sends and what the game's TerrainTile holds.

A tile is `tile_size` x `tile_size` samples at level of detail `lod` (world blocks per sample; 1
for chunks, larger for the terrain mapper zoomed out). Each sample averages `downscale * lod`
native 30 m pixels per axis. Six int16 planes, row = x, column = z:

  0 block height   ground occupies y < height
  1 biome id       game-compatible ids (terrain_slm.biomes); ocean ids only on TRUE ocean (world/ocean.py)
  2 water level    first non-water y, -1 = dry; every column below sea level fills to sea level
                   (the sea, or a lake: the biome plane tells them apart)
  3 tunnel floor   river tunnel floor (stone) under an overhang, -1 = none
  4 tunnel roof    river tunnel roof (stone), -1 = none
  5 flow octant    river flow direction 0..7 (0 = +x, toward +z), -1 = none
  6 carve          carve-node code (world/carve_node.py: ravine density / style / strike / depth, 16 bits as int16);
                   0 = no terrain-aware carving (overview tiles, models without a detail sampler)

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
from terrain_slm.world import generator as G
from terrain_slm.world import ocean as O
from terrain_slm.world import surface_channels as SC
from terrain_slm.world import swamp_relief as SR
from terrain_slm.world.devcopy import const

# Biome climate is lapse-adjusted by the REGIONAL elevation (generator.regional_elevation): with each
# column's own height, hard classifier thresholds made biome boundaries follow every hill's contours
# (desert rings around forested hilltops, white bands along hillsides).
REGIONAL_LAPSE = True
from terrain_slm.data.descriptors import CELL_PX
from terrain_slm.river import pipeline as RP
from terrain_slm.river.field import NO_FLOW, NO_TUNNEL
from terrain_slm.river.scale import NATIVE_M, BlockScale
from terrain_slm.world.world_config import WorldConfig

PLANES = 7
#: Majority window (samples) that cleans specks and pinholes out of the swamp overlay (radius 5, far inside BLOCK_MARGIN).
SWAMP_SMOOTH = 11
#: At a whole cell (8 native px) or coarser per sample, tiles come from the cells alone.
OVERVIEW_MIN_PX = CELL_PX
#: 1:1 worlds (downscale 0.5): blocks per 30 m pixel and axis at lod 1.
FINE_K = 2
#: 1:1: metres of change by the river stages (carving, banks, swamp flattening) over which the 15 m relief fades out.
FINE_KEEP_M = 3.0
#: BV (beaches, 2026-11-20), height-curve v2 worlds: the biome sidecar paints its coastal DESERT (sand) on the first
#: 1-3 samples beside ANY sub-sea water it sees -- lakes too: it reads the pre-river ground, where a lake is a sub-sea
#: basin like the sea -- so temperate lakes got sand rings (seed 3, 1:4: 52 % desert on lake shores, 0 % four
#: samples out). Within LAKE_SHORE_DESERT_M of inland water (sub-sea columns off the true ocean, lifted to
#: ocean.LAKE_CLASSIFY_ELEV_M: lakes, sub-sea river reaches), sidecar desert that the climate rule does not also call
#: desert takes the cleaned rule biome (as the sidecar's swamp does). The sea keeps its coastal sand. 0 = off.
LAKE_SHORE_DESERT_M = 180.0
# D8 class (drow, dcol) -> flow octant (0 = +x, toward +z); class 8 = terminal. Must match data/build.py.
_D8_OCTANT = [round(math.atan2(dc, dr) / (math.pi / 4)) % 8
              for dr, dc in ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))] + [-1]


class TileBuilder:
    def __init__(self, gen, world: WorldConfig):
        self.gen, self.world = gen, world
        self.scale = BlockScale.from_world(world, gen.device)
        gen.set_block_scale(self.scale)
        # Surface channel providers (world/surface_channels.py, TGMPipe v3): callables (builder, ctx) -> {name: float
        # tensor (tile rows, cols), NaN = no data}; ctx = the block frame of the build (_blocks). Absent nodes add none.
        from terrain_slm.world import vegetation_node as VN
        from terrain_slm.world import surface_node as SFN       # agent L: rock / scree / boulders / roughness
        self.surface_providers = [p for p in (VN.provider(gen), SFN.provider(gen)) if p is not None]
        self._surface_ctx = None
        self._look = False            # FX4 block look for the current build (set in build)

    def bounds(self, tile_x: int, tile_z: int) -> tuple[int, int, int, int]:
        """Sample coordinates (i1, j1, i2, j2) of a tile: the only shape ever built for it."""
        n = self.world.tile_size
        return tile_x * n, tile_z * n, (tile_x + 1) * n, (tile_z + 1) * n

    def build(self, seed: int, tile_x: int, tile_z: int, lod: int) -> np.ndarray:
        """(PLANES, tile_size, tile_size) int16, as a surface_channels.TilePlanes carrying the tile's surface
        channels (`.surface`, TGMPipe v3; empty without surface nodes / for overview and 1:1 tiles)."""
        if lod < 1 or lod & (lod - 1):
            raise ValueError(f"lod must be a power of two, got {lod}")
        i1, j1, i2, j2 = self.bounds(tile_x, tile_z)
        d = self.world.downscale * lod
        d = int(d) if d == int(d) else d         # 1:1: 0.5 at lod 1 (two blocks per pixel), whole pixels above
        with self.gen.lock:
            # One generator serves every world config (terrain scale) the game sent: its caches are in
            # metres; the block scale is per build, set under the lock the whole build holds.
            self.gen.set_block_scale(self.scale)
            self.gen.use_seed(seed)
            if hasattr(self.gen, "use_climate_layout"):     # EXP-2: the world's own climate layout (or the default)
                self.gen.use_climate_layout(self.world.climate_layout or getattr(self.gen, "default_climate_layout", "belts"))
            if hasattr(self.gen, "use_sea_shelf"):           # height-curve v2 worlds: the coastal shelf (generator2)
                self.gen.use_sea_shelf(getattr(self.world, "curve_version", 1) >= 2)
            # FX4 block look (alpine climate + zones, despeckle): EXP-2 generators on height-curve v2 worlds only
            self._look = getattr(self.world, "curve_version", 1) >= 2 and getattr(self.gen, "block_look", 0) > 0
            if hasattr(self.gen, "use_block_look"):          # generator2: its SR windows' mode (1:2 / 1:1 detail)
                self.gen.use_block_look(self._look)
            overview = d >= OVERVIEW_MIN_PX and d % OVERVIEW_MIN_PX == 0
            self._surface_ctx = None
            if d < 1:
                height, biome, level, wet, river = self._blocks_fine(i1, j1, i2, j2)
            else:
                height, biome, level, wet, river = (self._overview if overview else self._blocks)(i1, j1, i2, j2, d)
            carve = None if overview else self._carve(i1, j1, i2, j2, d)
            planes = self._planes(height, biome, level, wet, river, carve)
            surface = self._surface(self._surface_ctx) if self._surface_ctx is not None and not overview else {}
            self._surface_ctx = None
        return SC.TilePlanes(planes, surface)

    def _surface(self, ctx: dict) -> dict:
        """The tile's surface channels (name -> uint8 (rows, cols), 255 = no data) from every provider."""
        out = {}
        for provider in self.surface_providers:
            for name, values in provider(self, ctx).items():
                out[name] = SC.encode(name, values)
        return out

    # ------------------------------------------------------------------ block assembly
    def _planes(self, height, biome, level, wet, river, carve=None) -> np.ndarray:
        """The water rule: the sea fills every column below sea level; a river column holds water up
        to its level where that stands above its ground, or anywhere inside its tunnel (an
        undercut bank keeps its full height above the water)."""
        top, bottom = self.world.world_height - 1, self.world.min_y
        sea = self.world.sea_level
        height = height.clamp(bottom, top)
        level = level.clamp(bottom, top)
        floor, roof, flow = river
        water = torch.where(height < sea, torch.full_like(height, sea), torch.full_like(height, -1))
        river_wet = wet & ((level > height) | (floor >= 0))
        water = torch.where(river_wet, torch.maximum(water, level), water)
        carve = torch.zeros_like(height) if carve is None else carve.to(height.dtype)
        # The carve code is 16 bits: carried as int16 (bit pattern), like every plane.
        carve = torch.where(carve >= 32768, carve - 65536, carve)
        stack = torch.stack([height, biome.to(height.dtype), water, floor, roof, flow, carve])
        return stack.to(torch.int16).cpu().numpy()

    def _blocks(self, i1: int, j1: int, i2: int, j2: int, d: int):
        """Samples of d x d native pixels, built by the river pipeline's block stages over the tile
        plus BLOCK_MARGIN samples of context (the block stages look at neighbours), then cropped."""
        gen, mb = self.gen, RP.BLOCK_MARGIN
        n1, m1, n2, m2 = (i1 - mb) * d, (j1 - mb) * d, (i2 + mb) * d, (j2 + mb) * d
        field, ctx = gen.river_field(n1, m1, n2, m2)
        cols = gen.river.columns(field, ctx, d)
        if self._look:
            cols.height = despeckle(cols.height, self._dry(cols))
        elev = gen.scale.to_metres(cols.height.float() + 0.5)  # biomes read mid-block metres
        # Sub-sea columns off the true ocean -- lakes, and river reaches whose beds dip under sea level --
        # are classified as the land around them (no ocean ids, so the game draws no beach and lays a
        # lake bed), over tile + margin like everything below. The ocean grows through still water only.
        sub = cols.height < self.world.sea_level
        sea = O.sea_mask_for_samples(gen, i1 - mb, j1 - mb, sub.shape[0], sub.shape[1], d)
        elev = O.classify_elev(elev, sub & ~O.ocean_columns(sub & ~cols.wet, sea))
        regional = gen.regional_elevation(n1, m1, n2, m2)
        lapse_elev = regional if REGIONAL_LAPSE else field.carved
        if self._look:
            lapse_elev = alpine_elevation(regional, field.carved)
        climate = F.avg_pool2d(gen.climate_native(n1, m1, n2, m2, lapse_elev)[None], d)[0]
        crop = lambda x: x[..., mb:-mb, mb:-mb]
        sidecar = None
        if gen.biomenet is not None and d in (1, 2):
            def sidecar():
                # Biome sidecar over the tile + its margin (receptive-field radius 23 < BLOCK_MARGIN), noise
                # coordinates aligned with the rule classifier's (block units), then cropped to the tile. It reads
                # the ground BEFORE the river pipeline (quantised like its training data): carved channels and
                # raised banks are a landform the real DEMs never show, and drew thin biome ribbons along rivers.
                sc = gen.scale
                pre = F.avg_pool2d(field.ground[None, None], d)[0, 0]
                pre = sc.to_metres(torch.floor(sc.to_blocks(pre)).clamp(sc.min_y, sc.world_height - 1) + 0.5)
                if d == 2:
                    return BN.classify(gen.biomenet, pre, climate, i1 - mb, j1 - mb)
                # 1:2 (30 m blocks): the sidecar was trained on 60 m blocks, so it reads 2x2-pooled blocks
                # (tile + margin start on even samples) in 60 m sample coordinates, and each class covers 2x2.
                half = lambda x: F.avg_pool2d(x[None] if x.dim() == 3 else x[None, None], 2)[0]
                b60 = BN.classify(gen.biomenet, half(pre)[0], half(climate), (i1 - mb) // 2, (j1 - mb) // 2)
                return b60.repeat_interleave(2, 0).repeat_interleave(2, 1)
        regional = F.avg_pool2d(regional[None, None], d)[0, 0]
        biome = self._classify(elev, climate, regional, i1, j1, NATIVE_M * d, sidecar, d)
        river = tuple(crop(p).long() for p in (cols.floor, cols.roof, cols.flow))
        if self.surface_providers:   # the block frame (tile + BLOCK_MARGIN) the surface providers read
            self._surface_ctx = {"d": d, "i1": i1, "j1": j1, "mb": mb, "ground": field.ground, "climate": climate,
                                 "regional": regional, "height": cols.height, "water": cols.water, "wet": cols.wet,
                                 "biome": biome}
        return crop(cols.height).long(), biome, crop(cols.water).long(), crop(cols.wet), river

    def _blocks_fine(self, i1: int, j1: int, i2: int, j2: int):
        """1:1 (15 m blocks, two per 30 m pixel and axis), lod 1. The river pipeline's native stages run on the 30 m
        field exactly as at 1:2 (rivers sized in blocks through the 1:1 scale); the dry ground under the block stages
        is the 15 m terrain (generator.native_fine: SR-15) carrying the field's carving -- rivers, banks, swamp
        flattening and the summit cap -- as a per-pixel offset, so each 2x2 block still averages to its carved 30 m
        pixel; the block stages then run per 15 m block. Drainage, regional elevation and climate are per pixel,
        repeated / interpolated to blocks; the biome sidecar reads 4x4-pooled (60 m) blocks."""
        gen, mb, k = self.gen, RP.BLOCK_MARGIN, FINE_K
        b1, c1, b2, c2 = i1 - mb, j1 - mb, i2 + mb, j2 + mb                  # blocks = 15 m half pixels
        if b1 % k or c1 % k or b2 % k or c2 % k:
            raise ValueError(f"1:1 tile frame [{b1}, {b2}) x [{c1}, {c2}) is not pixel-aligned")
        n1, m1, n2, m2 = b1 // k, c1 // k, b2 // k, c2 // k                  # 30 m pixels
        field, ctx = gen.river_field(n1, m1, n2, m2)
        shelf = getattr(gen, "shelf", lambda x, *a: x)   # v2 worlds' coastal shelf, as river_field applied it to `carved`
        with gen.lock:
            base = shelf(G.soft_cap_peaks(gen.native(n1, m1, n2, m2)), n1, m1, G.CP)        # 30 m pixels
            fine = shelf(G.soft_cap_peaks(gen.native_fine(b1, c1, b2, c2)), b1, c1, G.CP * k)   # 15 m blocks
        up = lambda x: x.repeat_interleave(k, -2).repeat_interleave(k, -1)
        # The 15 m relief rides where the river stages left the 30 m ground alone; rivers, banks and swamps own their
        # pixels (the relief fades out over FINE_KEEP_M of change).
        keep = (1.0 - (field.carved - base).abs() / FINE_KEEP_M).clamp(0.0, 1.0)
        ground = up(field.carved) + up(keep) * (fine - up(base))
        cols = gen.river.columns(field, ctx, 1.0 / k, ground_fine=ground)
        if self._look:
            cols.height = despeckle(cols.height, self._dry(cols))
        elev = gen.scale.to_metres(cols.height.float() + 0.5)
        sub = cols.height < self.world.sea_level
        sea = up(O.sea_mask_for_samples(gen, n1, m1, n2 - n1, m2 - m1, 1))
        elev = O.classify_elev(elev, sub & ~O.ocean_columns(sub & ~cols.wet, sea))
        regional30 = gen.regional_elevation(n1, m1, n2, m2)
        lapse_elev = regional30 if REGIONAL_LAPSE else field.carved
        if self._look:
            lapse_elev = alpine_elevation(regional30, field.carved)
        smooth = lambda x: F.interpolate(x[None] if x.dim() == 3 else x[None, None], scale_factor=k, mode="bilinear",
                                         align_corners=False)[0]
        climate30 = gen.climate_native(n1, m1, n2, m2, lapse_elev)
        climate, regional = smooth(climate30), smooth(regional30)[0]
        sidecar = None
        if gen.biomenet is not None:
            def sidecar():
                # The sidecar was trained on 60 m blocks: it reads 2x2-pooled pixels (tile + margin start on a
                # 60 m sample) in 60 m sample coordinates, quantised under this world's curve; each class covers 4x4.
                sc = gen.scale
                pre = sc.to_metres(torch.floor(sc.to_blocks(field.ground)).clamp(sc.min_y, sc.world_height - 1) + 0.5)
                half = lambda x: F.avg_pool2d(x[None] if x.dim() == 3 else x[None, None], 2)[0]
                b60 = BN.classify(gen.biomenet, half(pre)[0], half(climate30), b1 // (2 * k), c1 // (2 * k))
                return b60.repeat_interleave(2 * k, 0).repeat_interleave(2 * k, 1)
        biome = self._classify(elev, climate, regional, i1, j1, NATIVE_M / k, sidecar, 1.0 / k)
        crop = lambda x: x[..., mb:-mb, mb:-mb]
        river = tuple(crop(p).long() for p in (cols.floor, cols.roof, cols.flow))
        if self.surface_providers:   # 1:1 block frame (agent L): d = 0.5, ground / climate per 15 m block
            self._surface_ctx = {"d": 0.5, "i1": i1, "j1": j1, "mb": mb, "ground": ground, "climate": climate,
                                 "regional": regional, "height": cols.height, "water": cols.water, "wet": cols.wet,
                                 "biome": biome}
        return crop(cols.height).long(), biome, crop(cols.water).long(), crop(cols.wet), river

    def _classify(self, elev, climate, regional, i1: int, j1: int, px_m: float, sidecar, d):
        """Biomes for the tile from samples over tile + BLOCK_MARGIN: `elev` (lake columns lifted), `climate`
        (C, H, W), `regional` (m) at sample resolution, `sidecar()` = the biome sidecar's classes over the same frame
        (or None: the rule classifier alone); `px_m` metres per sample, `d` native pixels per sample (red deserts)."""
        mb = RP.BLOCK_MARGIN
        crop = lambda x: x[..., mb:-mb, mb:-mb]
        pad1 = lambda x: x[..., mb - 1 : -(mb - 1), mb - 1 : -(mb - 1)]
        if sidecar is not None:
            biome = crop(sidecar())
            # The rule over tile + margin (per column but for its 3x3 slope, so the frame's own edge is far outside
            # the tile).
            rule_m = B._classify_biome(elev, climate, i1 - mb, j1 - mb, pixel_size_m=px_m,
                                       elev_padded=F.pad(elev[None, None], (1, 1, 1, 1), mode="replicate")[0, 0])
            rule = crop(rule_m)
            ocean_ids = const(B.OCEAN_IDS, rule.device, rule.dtype)
            # The sidecar knows land biomes only: sea columns keep the rule classifier's ocean ids (warm /
            # normal / cold / frozen ocean), which the game maps to OCEAN / ICE_FIELDS.
            ocean = torch.isin(rule, ocean_ids)
            # The sidecar (b4) learnt the old rule's swamp band; swamp now comes only from the overlay below, so its
            # swamp columns take the rule's biome, cleaned by the majority filters its training targets got (raw,
            # the rule's per-column slope classes speckle). Not the sidecar's runner-up class: where swamp
            # dominated its training that was nonsense (snowy slopes at 12 C on block 72).
            clean = crop(B.majority(B.majority(rule_m, 9), 5))
            clean = torch.where(torch.isin(clean, ocean_ids), rule, clean)
            biome = torch.where(ocean, rule, torch.where(biome == B.SWAMP_ID, clean, biome))
            if LAKE_SHORE_DESERT_M > 0 and getattr(self.gen, "sea_shelf", False):   # v2 worlds: no sand rings on lakes
                inland = (elev >= 0.0) & (elev <= O.LAKE_CLASSIFY_ELEV_M)            # lifted sub-sea columns
                r = max(1, math.ceil(LAKE_SHORE_DESERT_M / px_m))
                near = crop(F.max_pool2d(inland.float()[None, None], 2 * r + 1, 1, r)[0, 0] > 0.5)
                desert = B._BIOME_ID["desert"]
                biome = torch.where(near & (biome == desert) & (rule != desert), clean, biome)
        else:
            biome = B._classify_biome(crop(elev), crop(climate), i1, j1, elev_padded=pad1(elev), pixel_size_m=px_m)
        # Swamp candidates are per column, so smoothing them over tile + margin is request-independent.
        swamps = B.smooth_mask(B.swamp_candidates(elev, regional, climate, i1 - mb, j1 - mb), SWAMP_SMOOTH)
        biome = B.apply_swamps(biome, crop(swamps))
        if self._look:   # FX4: treeline / alpine rock band (60 m sample coordinates for the zone noise)
            u = px_m / (2 * NATIVE_M)
            biome = alpine_zones(biome, crop(elev), crop(climate[0]), crop(climate[1]), i1 * u, j1 * u, u)
        return self._red_deserts(biome, i1, j1, d)

    def _dry(self, cols) -> torch.Tensor:
        """Dry land columns of a block frame (despeckle may change them): no river, lake, tunnel or sea water."""
        return ((~cols.wet) & (cols.flow == NO_FLOW) & (cols.floor == NO_TUNNEL) & (cols.roof == NO_TUNNEL)
                & (cols.height >= self.world.sea_level) & (cols.water <= cols.height))

    def _carve(self, i1: int, j1: int, i2: int, j2: int, d: int) -> torch.Tensor | None:
        """Carve-node codes per block (world/carve_node.py), from the generator's canonical 60 m detail samples with a
        margin -- request-independent, and the same in metres at every terrain scale. Aridity is estimated from the
        controls' climate until the climate node supplies it."""
        from terrain_slm.models import detail as DT
        from terrain_slm.world import carve_node as CN

        gen = self.gen
        if gen.detail is None:
            return None
        ds, spc, m = DT.DS, DT.SAMPLES_PER_CELL, CN.MARGIN_SAMPLES
        if d < 1:                                      # 1:1: blocks per 60 m sample = ds / d (integer maths)
            per = round(ds / d)
            sample_of, sample_end = (lambda i: i // per), (lambda i: -(-i // per))
        else:
            sample_of, sample_end = (lambda i: (i * d) // ds), (lambda i: -(-(i * d) // ds))
        s1, t1 = sample_of(i1) - m, sample_of(j1) - m
        s2, t2 = sample_end(i2) + m, sample_end(j2) + m
        h60 = gen._detail_samples(s1, t1, s2, t2)
        ci0, cj0 = s1 // spc, t1 // spc
        hc, wc = -(-s2 // spc) - ci0, -(-t2 // spc) - cj0
        ctrl = gen.controls(ci0, cj0, hc, wc)
        dr = gen.drainage(ci0, cj0, hc, wc)
        oi, oj = s1 - ci0 * spc, t1 - cj0 * spc
        up = lambda x: x.repeat_interleave(spc, 0).repeat_interleave(spc, 1)[oi : oi + (s2 - s1), oj : oj + (t2 - t1)]
        if "aridity" in ctrl:   # EXP-2 climate node: real P / PET
            aridity = up(ctrl["aridity"])
        else:
            aridity = up(ctrl["precip"]) / (300.0 + 45.0 * up(ctrl["t0"]).clamp_min(0.0))   # ~P / PET
        river = up(dr["river"]) if dr else torch.zeros_like(h60)
        gates = None
        if "gates" in ctrl:     # EXP-2 controls: the style gates by name + the karst provinces (v4 has none: unchanged)
            from terrain_slm.models import detail2 as D2

            gates = {name: up(ctrl["gates"][k]) for k, name in enumerate(D2.STYLES)}
            # Height-curve v2 worlds: `karst2` (FX3: 1 in 8 of land as intended, none around spawn); v1 worlds keep
            # the old 21 % provinces (saved worlds stay seamless). The generator's per-build v2 flag (`use_sea_shelf`,
            # set by build() from the world's curve), so callers that only hold the generator agree.
            from terrain_slm.world import controls2 as C2
            v2 = bool(getattr(gen, "sea_shelf", False)) and C2.KARST_V2
            karst = "karst2" if v2 and "karst2" in ctrl else "karst"
            if karst in ctrl:
                gates["karst"] = up(ctrl[karst])
        codes = CN.carve_codes(h60, aridity, river, gates, margin=m)         # samples [s1 + m, s2 - m)
        rows = sample_of(torch.arange(i1, i2, device=h60.device)) - (s1 + m)
        cols = sample_of(torch.arange(j1, j2, device=h60.device)) - (t1 + m)
        return codes[rows][:, cols].long()

    def _red_deserts(self, biome: torch.Tensor, i1: int, j1: int, d: int) -> torch.Tensor:
        """Whole deserts turn red or stay sand, one decision per desert (generator.red_desert_cells).
        `biome` covers samples [i1, i1+H) x [j1, j1+W) of d native pixels each."""
        desert = biome == B._BIOME_ID["desert"]
        if not bool(desert.any()):
            return biome
        h, w = biome.shape
        dev = biome.device
        si = torch.arange(i1, i1 + h, device=dev).view(-1, 1).expand(h, w)
        sj = torch.arange(j1, j1 + w, device=dev).view(1, -1).expand(h, w)
        # Float cell coords of the sample centres: the red/sand border is resolved per sample, not per cell.
        ci, cj = (si * d + d / 2) / CELL_PX, (sj * d + d / 2) / CELL_PX
        red = G.red_desert_cells(ci[desert], cj[desert], self.gen.seed)
        out = biome.clone()
        out[desert] = torch.where(red, torch.full_like(biome[desert], B.RED_DESERT_ID), biome[desert])
        return out

    def _overview(self, i1: int, j1: int, i2: int, j2: int, d: int):
        """Far-zoom samples of d native pixels (whole 240 m cells) straight from the model's cell
        fields: coarse height, hydro-sidecar rivers (one sample wide) and their D8 flow. No synth,
        refiner or river pipeline -- a tiny fraction of a full tile's cost; previews only."""
        gen = self.gen
        k = d // CELL_PX
        h, w = i2 - i1, j2 - j1
        c = gen.overview_cells((i1 - 1) * k, (j1 - 1) * k, (h + 2) * k, (w + 2) * k)
        if self._look:   # FX4: the alpine climate of full tiles (cell height for the column's own elevation)
            reg = c["regional"].clamp_min(0.0)
            t = c["climate"][0] - G.LAPSE_C_PER_M * (alpine_elevation(reg, c["height"]) - reg)
            c = {**c, "climate": torch.cat([t[None], c["climate"][1:]])}
        pool = lambda x: F.avg_pool2d(x[None], k)[0]
        # Swamp ground on its water table, as river_field flattens full tiles (swamp_relief).
        elev = pool(SR.flatten(c["height"], *gen.swamp_relief_cells((i1 - 1) * k, (j1 - 1) * k, (h + 2) * k,
                                                                    (w + 2) * k))[None])[0]
        peak, idx = F.max_pool2d(c["logacc"][None, None], k, return_indices=True)
        wet = (peak[0, 0] >= gen.cfg.river_log_threshold) & (elev > 0.5)
        sc = gen.scale
        hb = torch.floor(sc.to_blocks(elev)).clamp(sc.min_y, sc.world_height - 1).long()
        none = torch.full(elev.shape, NO_TUNNEL, dtype=torch.long, device=elev.device)
        flow = torch.full_like(none, NO_FLOW)
        if c["d8"] is not None:
            cls = c["d8"].flatten()[idx[0, 0].flatten()].view(elev.shape).clamp(0, 8)
            flow = torch.where(wet, const(_D8_OCTANT, elev.device, torch.int64)[cls], flow)
        elev_m = sc.to_metres(hb.float() + 0.5)
        # Lakes as in full tiles, against the cell sea mask alone (a sample is a cell or more). Built from the
        # cells fetched above rather than a margin-padded second fetch (that doubled the planner windows a far
        # tile thrashes through), so growth from a seed outside this frame is missed: previews only.
        ci0, cj0 = (i1 - 1) * k, (j1 - 1) * k
        sea = O.sea_cells(c["height"], O.land_cells(gen, ci0, cj0, (h + 2) * k, (w + 2) * k, elev.device))
        sea = F.max_pool2d(sea.float()[None, None], k)[0, 0] > 0.5
        elev_m = O.classify_elev(elev_m, (hb < self.world.sea_level) & ~sea)
        crop = lambda x: x[..., 1:-1, 1:-1]
        biome = B._classify_biome(crop(elev_m), crop(pool(c["climate"])), i1, j1, elev_padded=elev_m,
                                  pixel_size_m=NATIVE_M * d)
        # Swamp noise in block coordinates (lod blocks per sample), so far-zoom swamps sit where tiles put them.
        lod = round(d / self.world.downscale)        # 1:1: downscale 0.5 (int, so noise coords stay ints)
        swamps = B.swamp_candidates(crop(elev_m), crop(pool(c["regional"][None])[0]), crop(pool(c["climate"])),
                                    i1 * lod, j1 * lod, step=lod)
        biome = B.apply_swamps(biome, swamps)
        if self._look:
            cl = crop(pool(c["climate"]))
            biome = alpine_zones(biome, crop(elev_m), cl[0], cl[1], i1 * d / 2, j1 * d / 2, d / 2)
        biome = self._red_deserts(biome, i1, j1, d)
        return crop(hb), biome, crop(hb + 1), crop(wet), (crop(none), crop(none), crop(flow))


# ================================================================================ block look (agent FX4, 2026-11-19)
# Height-curve v2 worlds of EXP-2 generators only (TileBuilder.block_look: world.curve_version >= 2 and the generator's
# `block_look` switch, generator2.BLOCK_LOOK, in its model_id as :bl<version>). Saved v1 worlds and v4 keep their tiles.
# Every function here is pointwise in world coordinates or reads a 1-column ring, so tiles stay request-independent.
# D's native TileBuilder mirrors: alpine_elevation, alpine_zones, despeckle (+ their constants).

#: The game draws mountains 1.6x taller than their footprint (1:4 highland 38 m per block vs 60 m wide; more below), so
#: a real-world lapse put snow and the treeline ~1 km higher than mountains of that apparent size carry them (play-test
#: 2026-11-19: "no epic mountains", 4-5 km massifs read as forested domes; v4's archetypes snowed from ~1.5 km). The
#: biome climate cools ALPINE_LAPSE_GAIN x the lapse rate above ALPINE_BASE_M of (effective) elevation: snow and the
#: treeline move down in warm belts most (t0 20 C: 0 C at ~2.35 km instead of ~3.1 km), lowland (< 1 km) unchanged.
ALPINE_LAPSE_GAIN = 1.6
ALPINE_BASE_M = 1000.0
#: Lapse elevation: the regional (~1.4 km blurred) elevation in lowland -- per-column elevation drew biome rings around
#: every hill -- moving to the column's own elevation over this band of regional elevation, so on big mountains the
#: snow sits on the ridges and summits and the valleys stay green (snow and treelines ARE contours up there).
ALPINE_LOCAL_M = (800.0, 1800.0)
#: Treeline: no trees (and no grass) where the growing season (days with T > 5 C, the rule classifier's sinusoid of mean
#: temperature and its seasonality) is shorter than this; the classifier itself only drops trees at ~60-90 days, i.e.
#: right at the snow line, which left no rock band between forest and snow. 150 days ~ 3 C mean (maritime ~4, continental ~1).
TREELINE_GS_DAYS = 150.0
TREELINE_NOISE_C = 2.5            # ragged treeline: x the classifier's snow-noise fields (normalised to +-1)
ALPINE_MIN_M = 900.0              # alpine zones only above this (+-ALPINE_MIN_NOISE_M): cold lowland keeps its forests
ALPINE_MIN_NOISE_M = 150.0
#: Above the treeline: forest / taiga / grassland -> windswept_hills (the game's STONY_PEAKS: bare rock, gravel);
#: snowy forest -> snowy_slopes (ICE_FIELDS: snow cover, no trees). Deserts, badlands, swamps, snow ids unchanged.
ALPINE_TREE_IDS = (1, 8, 15, 17, 23, 29, 108, 115)
ALPINE_SNOWY_TREE_IDS = (16, 116)
ALPINE_ROCK_ID = 19
ALPINE_SNOW_ID = 32


def _smooth01(t: torch.Tensor) -> torch.Tensor:
    t = t.clamp(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def alpine_elevation(regional: torch.Tensor, ground: torch.Tensor) -> torch.Tensor:
    """The elevation (m) the biome climate is lapse-adjusted to: regional -> the column's own ground over ALPINE_LOCAL_M,
    then ALPINE_LAPSE_GAIN x the metres above ALPINE_BASE_M (the base lapse rate applies to the result)."""
    w = _smooth01((regional - ALPINE_LOCAL_M[0]) / (ALPINE_LOCAL_M[1] - ALPINE_LOCAL_M[0]))
    loc = regional + w * (ground.clamp_min(0.0) - regional)
    return loc + (ALPINE_LAPSE_GAIN - 1.0) * (loc - ALPINE_BASE_M).clamp_min(0.0)


def _zone_noise(i0: float, j0: float, h: int, w: int, step: float, device) -> torch.Tensor:
    """(2, h, w) in ~[-1, 1]: the rule classifier's snow-noise fields (coarse, fine) at 60 m sample coordinates."""
    from terrain_slm.world.devcopy import to_device
    a = B._noise(B._SNOW_NOISE, i0, j0, h, w, step)
    b = B._noise(B._SNOW_NOISE_FINE, i0, j0, h, w, step)
    return to_device(np.stack([0.6 * a + 0.4 * b, b]), device, torch.float32)


def alpine_zones(biome: torch.Tensor, elev_m: torch.Tensor, temp: torch.Tensor, tseason: torch.Tensor,
                 i0: float, j0: float, step: float) -> torch.Tensor:
    """Biomes above the treeline (see TREELINE_GS_DAYS): forest / grass -> rock, snowy forest -> snow. `elev_m`, `temp`
    (lapse-adjusted, C), `tseason` (std x 100) per column; (i0, j0, step): the columns' 60 m sample coordinates."""
    h, w = biome.shape
    n = _zone_noise(i0, j0, h, w, step, biome.device)
    t = temp.float() + TREELINE_NOISE_C * n[0]
    amp = (tseason.float() / 100.0 * 1.414).clamp_min(0.1)
    x = (5.0 - t) / amp
    gs = torch.where(x <= -1.0, torch.full_like(t, 365.0),
                     torch.where(x >= 1.0, torch.zeros_like(t), 365.0 * (0.5 - torch.asin(x.clamp(-1.0, 1.0)) / 3.14159)))
    above = (gs < TREELINE_GS_DAYS) & (elev_m.float() >= ALPINE_MIN_M + ALPINE_MIN_NOISE_M * n[1])
    ids = lambda v: const(v, biome.device, biome.dtype)
    out = torch.where(above & torch.isin(biome, ids(ALPINE_TREE_IDS)), torch.full_like(biome, ALPINE_ROCK_ID), biome)
    return torch.where(above & torch.isin(biome, ids(ALPINE_SNOWY_TREE_IDS)), torch.full_like(biome, ALPINE_SNOW_ID), out)


#: Block-level clean-up of the quantised ground (play-test: "jagged meadows"; R5: single-column 1-block bumps / dents on
#: flat ground 15-24 per 1e4 lowland columns vs v4 ~4, single-column needles / holes on rock faces 0.2-0.8 vs v4 0).
#: Only dry land columns whose 8 neighbours are dry land (never rivers, tunnels, lakes, the sea or their banks) change:
#:  - a column 1 block above / below its 4 face neighbours, all on one level, takes their level (single islets /
#:    pinholes, also on gentle slopes); on flat ground (two levels in the 3x3) a column whose level at most one of
#:    its 8 neighbours shares while >= 3 of its 4 face neighbours sit on the other level takes that level (dominoes);
#:  - a needle >= DESPIKE_UP blocks above all 4 face neighbours drops to their max + 1; a hole >= DESPIKE_DOWN below
#:    all 4 rises to their min - 1 (steep rock keeps one block of relief, not a 60-240 m single-column spike).
#: Needles first, then DESPECKLE_PASSES passes of the flat rule (an L-tromino leaves a single after the first).
DESPIKE_UP = 3
DESPIKE_DOWN = 2
DESPECKLE_PASSES = 2


def _ring(h: torch.Tensor):
    """(4 face, 8 all) neighbour stacks of an (H, W) long tensor (edge-replicated)."""
    hp = F.pad(h[None, None].float(), (1, 1, 1, 1), mode="replicate")[0, 0].long()
    s = lambda di, dj: hp[1 + di : hp.shape[0] - 1 + di, 1 + dj : hp.shape[1] - 1 + dj]
    n4 = torch.stack([s(-1, 0), s(1, 0), s(0, -1), s(0, 1)])
    return n4, torch.cat([n4, torch.stack([s(-1, -1), s(-1, 1), s(1, -1), s(1, 1)])])


def despeckle(height: torch.Tensor, dry: torch.Tensor) -> torch.Tensor:
    """(H, W) integer block heights -> cleaned (same dtype); `dry`: dry land columns (may change, count as dry
    neighbours). The frame's outer ring never changes (no neighbours there); the result reads a 5x5 per column."""
    h = height.long()
    wetp = F.pad((~dry)[None, None].float(), (1, 1, 1, 1), value=1.0)
    ok = dry & (F.max_pool2d(wetp, 3, 1)[0, 0] < 0.5)          # itself and all 8 neighbours dry land
    ok[0, :] = False
    ok[-1, :] = False
    ok[:, 0] = False
    ok[:, -1] = False
    n4, n8 = _ring(h)
    mx4, mn4 = n4.max(0).values, n4.min(0).values
    out = torch.where(ok & (h - mx4 >= DESPIKE_UP), mx4 + 1, h)
    out = torch.where(ok & (mn4 - h >= DESPIKE_DOWN), mn4 - 1, out)
    for _ in range(DESPECKLE_PASSES):
        n4, n8 = _ring(out)
        lo3, hi3 = torch.minimum(n8.min(0).values, out), torch.maximum(n8.max(0).values, out)
        other = torch.where(out == lo3, hi3, lo3)
        # single: all 4 face neighbours on one level, the column 1 block off it (diagonals free: islets on slopes);
        # domino: two levels in the 3x3, the column's level shared by at most one of its 8 neighbours, >= 3 face
        # neighbours on the other level
        mx4, mn4 = n4.max(0).values, n4.min(0).values
        single = (mx4 == mn4) & ((out - mx4).abs() == 1)
        domino = ((hi3 - lo3) == 1) & ((n8 == out).sum(0) <= 1) & ((n4 == other).sum(0) >= 3)
        out = torch.where(ok & single, mx4, torch.where(ok & domino, other, out))
    return out.to(height.dtype)
