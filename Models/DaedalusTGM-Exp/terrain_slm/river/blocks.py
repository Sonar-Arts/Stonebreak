"""Block stages: model-resolution river field -> game block columns (the TerrainTile contract).

    BlockQuantize     metres -> blocks through the game's height curve (beds take the channel floor)
    FlowOctants       which way each wet column runs (0 = +x, toward +z), downhill along the
                      water surface, the hydro sidecar's D8 where the surface is flat
    FlattenAcross     one water level per cross-section (no sideways steps in a river surface)
    BlockContainment  a dry column beside water stands at least as high as that water
    Undercuts         tall dry walls beside the water get an undercut: a stone lip level with
                      the top water block and an air pocket above it (riverFloor/riverRoof)
    Overhangs         on wide rivers, a wet edge column next to an undercut gets the bank's lip
                      over it: water under an overhang (a wet column with a roof)

The tunnel semantics are TerrainTile's: for floor < roof, y == floor and y == roof are stone,
floor < y < roof holds water below the column's water level and air above it; everything else
below `height` is ground. So a lip at (neighbour's water level - 1) can never leak: the water
beside it meets stone at its top block and only air above the water.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.river.field import NO_FLOW, NO_TUNNEL, NO_WATER, BlockColumns, Drainage, RiverContext, RiverField
from terrain_slm.synth import noise as S

CP = D.CELL_PX
# D8 classes (drow, dcol), class 8 = terminal. Must match data/build.py.
D8_OFFSETS = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))
UNDERCUT_NOISE_BLOCKS = 12.0
UNDERCUT_STREAM = 7717


@dataclass
class BlockSource:
    """The river field pooled to block columns (d x d native pixels each)."""
    ground_m: torch.Tensor      # wet: channel floor; dry: mean ground
    top_m: torch.Tensor         # lowest wet water top, +inf where dry
    wet: torch.Tensor
    half_blocks: torch.Tensor   # channel half width, blocks (0 where dry)
    origin: tuple[int, int]     # absolute block coordinates of [0, 0]
    d: int
    drainage: Drainage | None
    flow_log: torch.Tensor | None = None  # smoothed log flow (grows downstream), block mean


def pool_to_blocks(f: RiverField, d: int, block_origin: tuple[int, int], drainage: Drainage | None) -> BlockSource:
    pool = lambda x: F.avg_pool2d(x[None, None], d)[0, 0]
    minpool = lambda x: -F.max_pool2d(-x[None, None], d)[0, 0]
    wet_n = f.wet if f.wet is not None else torch.zeros_like(f.ground, dtype=torch.bool)
    wet = pool(wet_n.float()) >= 0.25
    top_n = torch.where(wet_n, f.top, torch.full_like(f.ground, float("inf")))
    top = minpool(top_n)
    ground = torch.where(wet, minpool(f.carved), pool(f.carved))
    wet = wet & torch.isfinite(top) & (ground > 0.5)
    half = F.max_pool2d((f.half_px * (f.channel if f.channel is not None else 0))[None, None], d)[0, 0] if f.half_px is not None \
        else torch.zeros_like(ground)
    flow_log = pool(f.flow) if f.flow is not None else None
    return BlockSource(ground, top, wet, half / d, block_origin, d, drainage, flow_log)


class BlockQuantize:
    name = "quantize"

    def __call__(self, cols: BlockColumns | None, src: BlockSource, ctx: RiverContext) -> BlockColumns:
        sc = ctx.scale
        hb = torch.floor(sc.to_blocks(src.ground_m)).clamp(0, sc.world_height - 1).long()
        lb = torch.floor(sc.to_blocks(torch.where(src.wet, src.top_m, src.ground_m))).long()
        lb = torch.where(src.wet, torch.maximum(lb, hb + 1), torch.full_like(lb, NO_WATER))
        full = lambda v: torch.full_like(hb, v)
        return BlockColumns(hb, lb, full(NO_TUNNEL), full(NO_TUNNEL), full(NO_FLOW), src.wet.clone())


def _neigh_max(x: torch.Tensor, k: int = 3) -> torch.Tensor:
    return F.max_pool2d(x[None, None].float(), k, 1, k // 2)[0, 0]


def _neigh4_max(x: torch.Tensor, fill: float) -> torch.Tensor:
    p = F.pad(x[None, None].float(), (1, 1, 1, 1), value=fill)[0, 0]
    return torch.maximum(torch.maximum(p[:-2, 1:-1], p[2:, 1:-1]), torch.maximum(p[1:-1, :-2], p[1:-1, 2:]))


class BlockContainment:
    """A dry column next to water (8-neighbourhood) is raised to at least that water's level.
    Sea is decided exactly as the tile builder fills it (block height below sea level): a coastal
    column at 0-0.5 m sits AT sea level, gets no sea water, and so must be walled like any other
    (the old metres test `ground > 0.5 m` skipped it and river mouths leaked sideways)."""
    name = "block_contain"

    def __call__(self, cols: BlockColumns, src: BlockSource, ctx: RiverContext) -> BlockColumns:
        held = _neigh_max(torch.where(cols.wet, cols.water, torch.full_like(cols.water, -1)))
        not_sea = cols.height >= ctx.scale.sea_level
        need = (~cols.wet) & (held >= 0) & not_sea & (cols.height < held)
        ctx.stats["block_raised"] = int(need.sum())
        cols.height = torch.where(need, held.long(), cols.height)
        return cols


def _hash_noise(bi: torch.Tensor, bj: torch.Tensor, period: float, seed: int, stream: int) -> torch.Tensor:
    """Smooth value noise in [-1, 1] on block coordinates (coordinate-hashed, request-independent)."""
    x, y = bi / period, bj / period
    x0, y0 = torch.floor(x), torch.floor(y)
    fx, fy = x - x0, y - y0
    ux, uy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    base = S._hash32(torch.tensor((seed * 0x9E3779B1 + stream * 0x632BE5AB) & S._MASK32, device=bi.device))

    def corner(dx, dy):
        return S._hash32(S._hash32((x0.long() + dx) ^ base) ^ (y0.long() + dy)).float() / 2147483648.0 - 1.0

    return (corner(0, 0) * (1 - uy) + corner(0, 1) * uy) * (1 - ux) + (corner(1, 0) * (1 - uy) + corner(1, 1) * uy) * ux


class Undercuts:
    """Tall walls beside the water, in patches, get an undercut bank (dry column tunnels)."""
    name = "undercuts"

    def __call__(self, cols: BlockColumns, src: BlockSource, ctx: RiverContext) -> BlockColumns:
        c = ctx.cfg
        nb_level = _neigh4_max(torch.where(cols.wet, cols.water, torch.full_like(cols.water, -1)), -1).long()
        h, w = cols.height.shape
        bi = torch.arange(src.origin[0], src.origin[0] + h, device=cols.height.device).float().view(-1, 1).expand(h, w)
        bj = torch.arange(src.origin[1], src.origin[1] + w, device=cols.height.device).float().view(1, -1).expand(h, w)
        patch = _hash_noise(bi, bj, UNDERCUT_NOISE_BLOCKS, ctx.seed, UNDERCUT_STREAM) > 0.0
        floor = nb_level - 1
        roof = floor + 1 + c.undercut_air
        ok = ((~cols.wet) & (nb_level >= 0) & (cols.height - nb_level >= c.undercut_min_rise)
              & (cols.height >= roof + c.undercut_cover) & patch)
        cols.floor = torch.where(ok, floor, cols.floor)
        cols.roof = torch.where(ok, roof, cols.roof)
        ctx.stats["undercut_columns"] = int(ok.sum())
        return cols


class Overhangs:
    """A wet edge column of a wide river beside an undercut takes the bank's lip over it."""
    name = "overhangs"

    def __call__(self, cols: BlockColumns, src: BlockSource, ctx: RiverContext) -> BlockColumns:
        c = ctx.cfg
        under = (~cols.wet) & (cols.roof != NO_TUNNEL)
        neg = torch.full_like(cols.height, -1)
        nb_roof = _neigh4_max(torch.where(under, cols.roof, neg), -1).long()
        nb_top = _neigh4_max(torch.where(under, cols.height, neg), -1).long()
        wide = src.half_blocks * 2 >= c.overhang_min_width_blocks
        ok = cols.wet & (nb_roof >= 0) & wide & (nb_roof >= cols.water + 1) & (cols.height >= 1)
        cols.floor = torch.where(ok, cols.height - 1, cols.floor)
        cols.roof = torch.where(ok, nb_roof, cols.roof)
        cols.height = torch.where(ok, nb_top, cols.height)
        ctx.stats["overhang_columns"] = int(ok.sum())
        return cols


def _shift(x: torch.Tensor, di: int, dj: int, fill) -> torch.Tensor:
    """out[r, c] = x[r + di, c + dj], `fill` past the edge (no wrap-around)."""
    out = torch.full_like(x, fill)
    h, w = x.shape
    out[max(0, -di) : h - max(0, di), max(0, -dj) : w - max(0, dj)] = \
        x[max(0, di) : h - max(0, -di), max(0, dj) : w - max(0, -dj)]
    return out


class FlattenAcross:
    """One water level per cross-section: each wet column takes the lowest level found by walking
    PERPENDICULAR to its flow (both ways, through wet columns), so a river's surface never
    steps sideways; beds are cut down where that leaves less than one block of water. Lowering
    water cannot open a leak. Needs FlowOctants to have run."""
    name = "flatten"

    def __call__(self, cols: BlockColumns, src: BlockSource, ctx: RiverContext) -> BlockColumns:
        wet = cols.wet
        if not bool(wet.any()):
            return cols
        big = torch.iinfo(torch.int64).max // 4
        lv = torch.where(wet, cols.water, torch.full_like(cols.water, big))
        oct_ = cols.flow
        steps = int(math.ceil(ctx.cfg.width_max_blocks)) + 1
        for side in (2, 6):   # the two directions perpendicular to the flow octant
            d = torch.remainder(oct_ + side, 8)
            for _ in range(steps):
                best = lv.clone()
                for k in range(8):
                    ang = k * math.pi / 4
                    di, dj = int(round(math.cos(ang))), int(round(math.sin(ang)))
                    nb = _shift(lv, di, dj, big)
                    use = wet & (d == k) & (oct_ >= 0)
                    best = torch.where(use, torch.minimum(best, nb), best)
                if torch.equal(best, lv):
                    break
                lv = best
        lowered = wet & (lv < cols.water)
        ctx.stats["flattened_columns"] = int(lowered.sum())
        cols.water = torch.where(wet, lv, cols.water)
        cols.height = torch.where(wet, torch.minimum(cols.height, cols.water - 1), cols.height)
        return cols


class MonotoneBlocks:
    """Block-level guarantee that water never climbs downstream and never drops more than one
    block per column (steeper reaches cut down instead -- rivers incise): along each wet column's flow
    octant, the downstream wet neighbour's level is capped at this column's (iterated, so a low
    point carries downstream), and ground is cut to keep a block of water. FlattenAcross can
    otherwise leave a cross-section lower than the next one down. Lowering water never spills.
    Needs FlowOctants."""
    name = "monotone_blocks"

    def __init__(self, iters: int = 20):   # < BLOCK_MARGIN: stays request-independent
        self.iters = iters

    def __call__(self, cols: BlockColumns, src: BlockSource, ctx: RiverContext) -> BlockColumns:
        wet = cols.wet & (cols.flow >= 0)
        if not bool(wet.any()):
            return cols
        big = torch.iinfo(torch.int64).max // 4
        lv = torch.where(cols.wet, cols.water, torch.full_like(cols.water, big))
        start = lv.clone()
        for _ in range(self.iters):
            cap = torch.full_like(lv, big)
            for k in range(8):
                ang = k * math.pi / 4
                di, dj = int(round(math.cos(ang))), int(round(math.sin(ang)))
                # The forward cone: a column flowing along k, k-1 or k+1 has this neighbour downstream.
                # (Exact-octant only misses diagonal rivers' orthogonal steps across the block grid.)
                along = wet & ((cols.flow == k) | (cols.flow == (k + 1) % 8) | (cols.flow == (k + 7) % 8))
                # column (r, c) flowing along k feeds (r + di, c + dj): shift its level there
                src_lv = torch.where(along, lv, torch.full_like(lv, big))
                cap = torch.minimum(cap, _shift(src_lv, -di, -dj, big))
                # ... and may stand at most one block above it (a one-block cascade): steep
                # reaches incise instead of dropping two blocks, which would spill sideways.
                down = _shift(torch.where(cols.wet, lv, torch.full_like(lv, big)), di, dj, big)
                cap = torch.where(along & (down < big), torch.minimum(cap, down + 1), cap)
            nxt = torch.where(cols.wet, torch.minimum(lv, cap), lv)
            if torch.equal(nxt, lv):
                break
            lv = nxt
        lowered = cols.wet & (lv < start)
        ctx.stats["monotone_block_columns"] = int(lowered.sum())
        cols.water = torch.where(cols.wet, lv, cols.water)
        cols.height = torch.where(cols.wet, torch.minimum(cols.height, cols.water - 1), cols.height)
        return cols


class FlowOctants:
    """Wet columns run downhill along the (smoothed, metres) water surface; flat reaches (common
    where the downstream-monotone level runs level through a bump) follow the direction in which
    flow accumulation grows -- downstream by construction -- and only then the hydro sidecar's
    D8; anything else stays NO_FLOW."""
    name = "flow"
    SMOOTH_BLOCKS = 4.0
    CONSENSUS = 5            # blocks: octant smoothing window

    def __call__(self, cols: BlockColumns, src: BlockSource, ctx: RiverContext) -> BlockColumns:
        # The continuous water surface in metres, not the block levels: a gently sloping reach
        # quantises to one flat block level but still falls in metres.
        wf = cols.wet.float()[None, None]
        top = torch.where(cols.wet & torch.isfinite(src.top_m), src.top_m, torch.zeros_like(src.top_m))[None, None]
        s = D.blur(top, self.SMOOTH_BLOCKS) / D.blur(wf, self.SMOOTH_BLOCKS).clamp_min(1e-6)
        sp = F.pad(s, (1, 1, 1, 1), mode="replicate")[0, 0]
        gx = 0.5 * (sp[2:, 1:-1] - sp[:-2, 1:-1])   # rows = world x
        gz = 0.5 * (sp[1:-1, 2:] - sp[1:-1, :-2])   # cols = world z
        dx, dz = -gx, -gz
        mag = torch.sqrt(dx * dx + dz * dz)
        oct_grad = torch.remainder(torch.round(torch.atan2(dz, dx) / (math.pi / 4)), 8).long()
        oct_d8 = torch.full_like(cols.flow, NO_FLOW)
        dr = src.drainage
        if dr is not None and dr.d8 is not None:
            h, w = cols.flow.shape
            bi = torch.arange(src.origin[0], src.origin[0] + h, device=cols.flow.device)
            bj = torch.arange(src.origin[1], src.origin[1] + w, device=cols.flow.device)
            ci = (bi * src.d) // CP - dr.origin[0]
            cj = (bj * src.d) // CP - dr.origin[1]
            ci = ci.clamp(0, dr.d8.shape[0] - 1)
            cj = cj.clamp(0, dr.d8.shape[1] - 1)
            cls = dr.d8[ci][:, cj]
            table = torch.tensor([round(math.atan2(dc, drw) / (math.pi / 4)) % 8 for drw, dc in D8_OFFSETS] + [NO_FLOW],
                                 device=cols.flow.device)
            oct_d8 = table[cls.clamp(0, 8)]
        if src.flow_log is not None:
            # Flat reaches (common where the downstream-monotone level runs level through a bump):
            # the direction in which accumulation grows -- downstream by construction -- before D8.
            fl = torch.where(cols.wet, src.flow_log, torch.zeros_like(src.flow_log))[None, None]
            fs = D.blur(fl, self.SMOOTH_BLOCKS) / D.blur(wf, self.SMOOTH_BLOCKS).clamp_min(1e-6)
            fp = F.pad(fs, (1, 1, 1, 1), mode="replicate")[0, 0]
            ax, az = 0.5 * (fp[2:, 1:-1] - fp[:-2, 1:-1]), 0.5 * (fp[1:-1, 2:] - fp[1:-1, :-2])
            oct_acc = torch.remainder(torch.round(torch.atan2(az, ax) / (math.pi / 4)), 8).long()
            oct_d8 = torch.where(torch.sqrt(ax * ax + az * az) > 1e-4, oct_acc, oct_d8)
        flow = torch.where(mag > 1e-3, oct_grad, oct_d8)   # metres per block
        # Vector-smooth the octants over nearby wet columns: an isolated column pointing against
        # its neighbours (a flip the water sim would show as a whirl) takes the local consensus.
        live = cols.wet & (flow >= 0)
        ang = flow.float() * (math.pi / 4)
        ux = torch.where(live, torch.cos(ang), torch.zeros_like(ang))[None, None]
        uz = torch.where(live, torch.sin(ang), torch.zeros_like(ang))[None, None]
        k = torch.ones(1, 1, self.CONSENSUS, self.CONSENSUS, device=ang.device)
        sx = F.conv2d(ux, k, padding=self.CONSENSUS // 2)[0, 0]
        sz = F.conv2d(uz, k, padding=self.CONSENSUS // 2)[0, 0]
        smooth = torch.remainder(torch.round(torch.atan2(sz, sx) / (math.pi / 4)), 8).long()
        flow = torch.where(live & (sx * sx + sz * sz > 0.25), smooth, flow)
        cols.flow = torch.where(cols.wet, flow, torch.full_like(flow, NO_FLOW))
        return cols
