"""Geometry stages: where rivers run, how big they are, where their water stands, their bed.

All local and bounded-radius (see pipeline.HALO_PX), so a pixel's result never depends on
which request computed it.
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.river.field import RiverContext, RiverField

CP = D.CELL_PX


def _disk(r: int, device) -> torch.Tensor:
    y, x = torch.meshgrid(torch.arange(-r, r + 1, device=device), torch.arange(-r, r + 1, device=device), indexing="ij")
    return ((x * x + y * y) <= r * r + r).float()[None, None]


def dilate(mask: torch.Tensor, r: int) -> torch.Tensor:
    """Round (Euclidean) dilation of a bool (H, W) mask by radius r px."""
    if r <= 0:
        return mask
    return F.conv2d(mask.float()[None, None], _disk(r, mask.device), padding=r)[0, 0] > 0.5


def erosion_depth(mask: torch.Tensor, cap: int) -> torch.Tensor:
    """Px from the mask's edge (1 on the edge row), up to `cap`, by repeated 3x3 erosion."""
    m = mask.float()[None, None]
    d = m.clone()
    for _ in range(cap - 1):
        m = -F.max_pool2d(-m, 3, 1, 1)
        d = d + m
    return d[0, 0]


def ridge_lines(flow_px: torch.Tensor, log_threshold: float, smooth_px: float) -> tuple[torch.Tensor, torch.Tensor]:
    """Thin centrelines: local maxima of the smoothed flow across its ridge (Hessian NMS),
    above the threshold. Returns (bool mask, smoothed flow)."""
    s = D.blur(flow_px[None, None], smooth_px)
    sp = F.pad(s, (1, 1, 1, 1), mode="replicate")
    sxx = sp[..., 1:-1, 2:] - 2 * s + sp[..., 1:-1, :-2]
    syy = sp[..., 2:, 1:-1] - 2 * s + sp[..., :-2, 1:-1]
    sxy = 0.25 * (sp[..., 2:, 2:] - sp[..., 2:, :-2] - sp[..., :-2, 2:] + sp[..., :-2, :-2])
    lam_b = 0.5 * (sxx + syy) - torch.sqrt((0.5 * (sxx - syy)) ** 2 + sxy**2)
    across = 0.5 * torch.atan2(2 * sxy, sxx - syy) + math.pi / 2
    h, w = flow_px.shape
    yy, xx = torch.meshgrid(torch.arange(h, device=s.device, dtype=torch.float32),
                            torch.arange(w, device=s.device, dtype=torch.float32), indexing="ij")

    def sample(sign):
        gx = (xx + sign * torch.cos(across[0, 0])) / max(1, w - 1) * 2 - 1
        gy = (yy + sign * torch.sin(across[0, 0])) / max(1, h - 1) * 2 - 1
        return F.grid_sample(s, torch.stack([gx, gy], dim=-1)[None], mode="bilinear",
                             padding_mode="border", align_corners=True)

    ridge = (s >= sample(1.0)) & (s >= sample(-1.0)) & (lam_b < 0) & (s >= log_threshold)
    return ridge[0, 0], s[0, 0]


class Centrelines:
    """Drainage cells -> thin river centrelines and the smoothed flow at every pixel.

    Hysteresis (as in Canny edges): a centreline pixel above `log_threshold` is a river; one
    down to `log_threshold - hysteresis` is kept only if it connects to such a stretch, grown
    at most `reach_px` pixels. Flow that hovers at the threshold no longer breaks a river into
    fragments, and the bounded reach keeps the result request-independent."""
    name = "centrelines"

    def __init__(self, hysteresis: float = 0.7, reach_px: int = 64):
        self.hysteresis, self.reach_px = hysteresis, reach_px

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        dr = ctx.drainage
        hc, wc = dr.logacc.shape
        up = F.interpolate(dr.logacc[None, None], size=(hc * CP, wc * CP), mode="bilinear", align_corners=False)[0, 0]
        oi = ctx.px_origin[0] - dr.origin[0] * CP
        oj = ctx.px_origin[1] - dr.origin[1] * CP
        h, w = f.ground.shape
        weak, flow = ridge_lines(up, ctx.cfg.log_threshold - self.hysteresis, ctx.cfg.ridge_smooth_px)
        line = weak & (flow >= ctx.cfg.log_threshold)
        wk = weak.float()[None, None]
        grown = line.float()[None, None]
        for _ in range(self.reach_px):
            nxt = F.max_pool2d(grown, 3, 1, 1) * wk
            if torch.equal(nxt, grown):
                break
            grown = nxt
        line = grown[0, 0] > 0
        f.line, f.flow = line[oi : oi + h, oj : oj + w], flow[oi : oi + h, oj : oj + w]
        return f


class ChannelGeometry:
    """Centrelines -> channel footprint, sized in BLOCKS from the flow: 3-block streams up to
    16-block rivers, 2 to 6 blocks deep, whatever band of the height curve they cross."""
    name = "channel"

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        c, ppb = ctx.cfg, ctx.scale.px_per_block
        excess = (f.flow - c.log_threshold).clamp_min(0.0)
        max_half = int(math.ceil(c.width_max_blocks * ppb / 2))
        # Size by the flow ON the centreline, carried out to the channel's own pixels, so a
        # channel's width does not taper towards its banks.
        line_ex = F.max_pool2d((excess * f.line)[None, None], 2 * max_half + 1, 1, max_half)[0, 0]
        width_b = (c.width_min_blocks + c.width_per_log * line_ex).clamp(c.width_min_blocks, c.width_max_blocks)
        half_px = width_b * ppb / 2
        channel = f.line.clone()
        for k in range(1, max_half + 1):
            channel |= dilate(f.line, k) & (half_px >= k)
        f.channel = channel
        f.half_px = half_px
        f.edge_px = erosion_depth(channel, max_half + 1)
        f.depth_blocks = (c.depth_min_blocks + c.depth_per_log * line_ex).clamp(c.depth_min_blocks, c.depth_max_blocks)
        return f


def propagate(values: torch.Tensor, seed: torch.Tensor, region: torch.Tensor, steps: int) -> torch.Tensor:
    """Carry `values` from `seed` pixels out through `region` ring by ring (each new pixel takes
    the mean of its already-assigned 8-neighbours), i.e. roughly the nearest seed's value."""
    v = torch.where(seed, values, torch.zeros_like(values))[None, None]
    done = seed.float()[None, None]
    reg = region.float()[None, None]
    k = torch.ones(1, 1, 3, 3, device=values.device)
    for _ in range(steps):
        num = F.conv2d(v * done, k, padding=1)
        den = F.conv2d(done, k, padding=1)
        new = (den > 0) & (done == 0) & (reg > 0)
        v = torch.where(new, num / den.clamp_min(1e-6), v)
        done = torch.where(new, torch.ones_like(done), done)
    return v[0, 0]


_N8 = ((-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1))


def _shift2(x: torch.Tensor, di: int, dj: int, fill: float) -> torch.Tensor:
    """out[r, c] = x[r + di, c + dj], `fill` past the edge (no wrap-around)."""
    out = torch.full_like(x, fill)
    h, w = x.shape
    out[max(0, -di) : h - max(0, di), max(0, -dj) : w - max(0, dj)] = \
        x[max(0, di) : h - max(0, -di), max(0, dj) : w - max(0, -dj)]
    return out


def monotone_downstream(level: torch.Tensor, line: torch.Tensor, flow: torch.Tensor, iters: int) -> torch.Tensor:
    """Lower `level` on centreline pixels until no pixel stands above a connected upstream
    neighbour (an 8-neighbour line pixel with lower flow). Flow accumulation only grows
    downstream, so the result never rises along a river; where the flow ordering is noisy the
    river runs flat, never uphill. Only lowers. `iters` bounds how far (px, along the line) a
    low point can pull the level down downstream -- keep it inside the request halo."""
    inf = float("inf")
    lv = torch.where(line, level, torch.full_like(level, inf))
    fl = torch.where(line, flow, torch.full_like(flow, inf))
    nbs = [(_shift2(fl, di, dj, inf), di, dj) for di, dj in _N8]
    ups = [(nf < fl) & line for nf, _, _ in nbs]   # neighbour is upstream of me
    for _ in range(iters):
        best = lv
        for (nf, di, dj), up in zip(nbs, ups):
            best = torch.where(up, torch.minimum(best, _shift2(lv, di, dj, inf)), best)
        if torch.equal(best, lv):
            break
        lv = best
    return torch.where(line, lv, level)


class WaterSurface:
    """Water level: the ground along the CENTRELINE, smoothed along it (so rivers slope with the
    land), made non-increasing downstream (`monotone_downstream`: a bump across the river's path
    no longer lifts its water -- the bed cuts through it instead), carried straight across the
    channel (one level per cross-section, so the surface never tilts sideways into block steps),
    then spread past the banks for the bank stages."""
    name = "surface"

    def __init__(self, monotone_iters: int = 128):
        self.monotone_iters = monotone_iters

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        cf = f.channel.float()[None, None]
        lf = f.line.float()[None, None]
        g = f.ground[None, None]
        sig = ctx.cfg.surface_sigma_px
        # Along the centreline: a masked blur of the ground under the line itself (the valley
        # floor the river follows), with the in-channel mean as a fallback where no line is near.
        on_line = D.blur(g * lf, sig) / D.blur(lf, sig).clamp_min(1e-6)
        in_chan = D.blur(g * cf, sig) / D.blur(cf, sig).clamp_min(1e-6)
        if self.monotone_iters > 0:
            before = on_line[0, 0]
            mono = monotone_downstream(before, f.line, f.flow, self.monotone_iters)
            drop = (before - mono)[f.line]
            ctx.stats["monotone_lowered_px"] = int((drop > 0.01).sum())
            ctx.stats["monotone_max_drop_m"] = float(drop.max()) if drop.numel() else 0.0
            on_line = mono[None, None]
        steps = int(math.ceil(ctx.cfg.width_max_blocks * ctx.scale.px_per_block / 2)) + 2
        across = propagate(on_line[0, 0], f.line, f.channel, steps)
        reached = propagate(torch.ones_like(f.ground), f.line, f.channel, steps) > 0.5
        level = torch.where(reached, across, in_chan[0, 0])[None, None]
        # Spread the level beyond the channel (normalised convolution of the in-channel level).
        spread_sig = ctx.cfg.bank_band_blocks * ctx.scale.px_per_block
        spread = D.blur(level * cf, spread_sig) / D.blur(cf, spread_sig).clamp_min(1e-6)
        f.level = torch.where(f.channel, level[0, 0], spread[0, 0])
        return f


class UBed:
    """Channel bed: a smooth U, one block deep at the edge, `depth_blocks` at the centre.
    Elevation data only records water surfaces, so beds are shaped, not learned."""
    name = "bed"

    def __call__(self, f: RiverField, ctx: RiverContext) -> RiverField:
        c = ctx.cfg
        rate = ctx.scale.rate(f.level)
        u = (f.edge_px / f.half_px.clamp_min(1.0)).clamp(0, 1)
        profile = 1.0 - (1.0 - u) ** 2
        depth_m = rate * (1.0 + (f.depth_blocks - 1.0) * profile)
        bed = f.level - depth_m
        # Where the (downstream-monotone) level sits well below the ground, the bed cuts a
        # gorge rather than leaving the river a dry gap; only beyond the gorge cap does it stop.
        bed = torch.maximum(bed, f.ground - rate * (f.depth_blocks + c.extra_incision_blocks))
        base = f.carved if f.carved is not None else f.ground
        f.carved = torch.where(f.channel, torch.minimum(base, bed), base)
        return f
