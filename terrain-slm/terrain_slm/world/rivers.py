"""Rivers from the planner's flow field, carved into generated terrain.

The planner predicts log flow accumulation per cell -- how much upstream area drains
through it -- which is exactly the non-local knowledge a drainage solve would provide.
This module turns that field into water at native resolution using only LOCAL
operations of bounded radius (HALO_PX), so the result at a pixel never depends on which
request computed it (the bridge's seam rule), and no region-scale solve is needed.

Steps:
  1. Centrelines at pixel level: ridge lines of the smoothed, upsampled flow field
     (non-maximum suppression across the ridge, via the Hessian), thresholded.
  2. Channels: every pixel within a flow-dependent half-width of a centreline.
  3. Water surface: the channel's own ground smoothed along the channel, so rivers slope
     with the land; the bed is carved `depth` below it (depth grows with flow), capped at
     MAX_INCISION_M so a channel that disagrees with the terrain cuts a bounded notch.
  4. Levees: a thin ring beside the channel is raised to just above the water where the
     ground is lower, so channels that cross bumps or run along slopes still hold water.
  5. Bank check, iterated to a fixed point: every dry pixel (bank, or a channel stretch
     too shallow to hold water) caps its neighbours' water top, so water never stands
     above adjacent dry ground; too little water left => that stretch is dry (a gap, not
     a spill).
"""
from __future__ import annotations

import math

import torch
import torch.nn.functional as F

from terrain_slm.data import descriptors as D
from terrain_slm.models import planner as P

CP = D.CELL_PX
LOG_THRESHOLD = math.log1p(P.RIVER_MIN_UPSLOPE_CELLS)
SURFACE_SMOOTH_PX = 3.0
MAX_INCISION_M = 25.0
MIN_WATER_M = 0.4
LEVEE_PX = 2
CELL_HALO = 3  # cells of context the centreline pass needs on each side
RIDGE_SMOOTH_PX = 8.0
MAX_HALF_WIDTH_PX = 4
HALO_PX = 40   # pixel context the carve needs on each side (surface radius + blurs)


def _minpool(x: torch.Tensor, r: int) -> torch.Tensor:
    return -F.max_pool2d(-x, 2 * r + 1, stride=1, padding=r)


def ridge_lines(flow_px: torch.Tensor, log_threshold: float = LOG_THRESHOLD) -> tuple[torch.Tensor, torch.Tensor]:
    """Thin river centrelines at pixel resolution.

    Args:
        flow_px: (H, W) log1p(upslope cells), bilinearly upsampled from the planner cells.
    Returns:
        (centreline mask (H, W) bool, smoothed flow (H, W)).

    The flow is smoothed first (RIDGE_SMOOTH_PX): at cell resolution the ridges snap to the
    8 px lattice and to the planner's 4-cell patch grid, which drew Manhattan-style river
    networks. A ridge pixel is a local maximum across the ridge (the direction of the most
    negative Hessian eigenvalue) above the threshold.
    """
    s = D.blur(flow_px[None, None], RIDGE_SMOOTH_PX)
    sp = F.pad(s, (1, 1, 1, 1), mode="replicate")
    sxx = sp[..., 1:-1, 2:] - 2 * s + sp[..., 1:-1, :-2]
    syy = sp[..., 2:, 1:-1] - 2 * s + sp[..., :-2, 1:-1]
    sxy = 0.25 * (sp[..., 2:, 2:] - sp[..., 2:, :-2] - sp[..., :-2, 2:] + sp[..., :-2, :-2])
    half_tr = 0.5 * (sxx + syy)
    disc = torch.sqrt((0.5 * (sxx - syy)) ** 2 + sxy**2)
    lam_b = half_tr - disc  # most negative eigenvalue
    # Eigenvector of the larger eigenvalue is at theta; across-ridge is perpendicular.
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


def carve(heights: torch.Tensor, logacc_cells: torch.Tensor, cell_origin: tuple[int, int],
          px_origin: tuple[int, int], log_threshold: float = LOG_THRESHOLD) -> tuple[torch.Tensor, torch.Tensor]:
    """Carve rivers into a pixel block.

    Args:
        heights: (H, W) metres for pixels starting at absolute `px_origin`.
        logacc_cells: (Hc, Wc) log1p(upslope cells) for cells starting at `cell_origin`,
            covering the pixel block plus CELL_HALO cells on every side.
        log_threshold: flow level (log1p upslope cells) where a river starts. The training
            definition (~25 km^2) is the default; generated worlds use a calibrated lower
            value because the planner under-predicts drainage magnitude on smooth
            procedural controls (its ridge LINES are still right) -- a river-density knob.
    Returns:
        (carved heights (H, W), water surface (H, W) with NaN where dry).
        Pixels within HALO_PX of the block edge are not trustworthy -- crop them.
    """
    h, w = heights.shape
    hc, wc = logacc_cells.shape
    up = lambda x: F.interpolate(x[None, None], size=(hc * CP, wc * CP), mode="bilinear", align_corners=False)[0, 0]
    oi = px_origin[0] - cell_origin[0] * CP
    oj = px_origin[1] - cell_origin[1] * CP
    line, flow = ridge_lines(up(logacc_cells), log_threshold)
    line, flow = line[oi : oi + h, oj : oj + w], flow[oi : oi + h, oj : oj + w]
    excess = (flow - log_threshold).clamp_min(0.0)

    # Width by (Chebyshev) distance from the thin centreline, per pixel, growing with flow:
    # junctions stay river-wide instead of ballooning.
    half_px = (1.0 + 1.2 * excess).clamp(1.0, MAX_HALF_WIDTH_PX)
    lf = line.float()[None, None]
    core = line.clone()
    for k in range(1, MAX_HALF_WIDTH_PX + 1):
        core |= (F.max_pool2d(lf, 2 * k + 1, 1, k)[0, 0] > 0) & (half_px >= k)
    depth = (1.5 + 1.2 * excess).clamp(1.5, 6.0)

    # Water surface follows the channel's OWN ground, smoothed along the channel (a masked
    # blur over core pixels only), so rivers slope with the land -- sloped water is simply
    # flowing water. An isotropic local minimum (the first design) dropped the surface into
    # neighbouring pits on rough or steep ground and left most channels dry.
    cf = core.float()[None, None]
    surface = (D.blur(heights[None, None] * cf, SURFACE_SMOOTH_PX) /
               D.blur(cf, SURFACE_SMOOTH_PX).clamp_min(1e-6))[0, 0]
    bed = torch.maximum(surface - depth, heights - MAX_INCISION_M)
    # The whole channel core is cut to the bed and banks are left alone: a partially cut
    # rim would sit just too high to hold water, cap its neighbours, and dry the channel
    # out ring by ring. Steep 1-2 block banks are also what voxel rivers look like.
    carved = torch.where(core, torch.minimum(heights, bed), heights)

    top = torch.minimum(bed + 0.85 * depth, surface)
    # Levees: the ground conforms to the river, not the other way round. At pixel scale
    # the synth's hills wander across the planner's river lines, so channels cross bumps
    # and run along slopes; a ring LEVEE_PX wide beside the channel is raised to just above
    # the water it holds wherever the ground there is lower. Without it, low banks capped
    # the water until most channels dried out.
    ninf = torch.full_like(carved, float("-inf"))
    held = F.max_pool2d(torch.where(core, top, ninf)[None, None], 2 * LEVEE_PX + 1, 1, LEVEE_PX)[0, 0]
    ring = (~core) & torch.isfinite(held) & (heights > 0.5)
    carved = torch.where(ring, torch.maximum(carved, held + 0.3), carved)

    wet = core & (heights > 0.5)
    # Safety net, iterated to a fixed point: every DRY pixel -- bank or a channel stretch
    # too shallow to hold water -- caps its 8 neighbours' water top, which is exactly the
    # no-spill condition. (A wider margin was tried and drained 99% of channels: it reached
    # past the levee to ordinary downhill ground.) Wetness only shrinks and the top only
    # drops, so this terminates; long channels can need many rounds.
    inf = torch.full_like(carved, float("inf"))
    for _ in range(512):
        dry_ground = torch.where(wet, inf, carved)[None, None]
        top = torch.minimum(top, _minpool(dry_ground, 1)[0, 0] - 0.05)
        still = wet & (top > carved + MIN_WATER_M)
        if torch.equal(still, wet):
            break
        wet = still
    return carved, torch.where(wet, top, torch.full_like(top, float("nan")))
