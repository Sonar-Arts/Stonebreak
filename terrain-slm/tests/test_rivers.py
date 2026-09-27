"""River pass: channels follow the flow ridge, water never stands above a dry bank, weak
flow stays dry, ground only changes in the channel (cut) and its levee ring (raised)."""
import math

import torch

from terrain_slm.world import rivers as RV

DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
CP = RV.CP


def _valley(hc=40, wc=40, peak=RV.LOG_THRESHOLD + 1.5):
    """A valley running along columns at the block's centre row, and a matching flow ridge."""
    h, w = hc * CP, wc * CP
    rows = torch.arange(h, device=DEV, dtype=torch.float32).view(-1, 1)
    cols = torch.arange(w, device=DEV, dtype=torch.float32).view(1, -1)
    centre = h / 2
    heights = 300.0 + 0.8 * (rows - centre).abs() + 0.02 * cols  # V valley, gentle downstream tilt
    crow = torch.arange(hc, device=DEV, dtype=torch.float32).view(-1, 1)
    logacc = peak - 0.9 * ((crow + 0.5) * CP - centre).abs() / CP
    logacc = logacc.expand(hc, wc).contiguous()
    return heights.expand(h, w).contiguous(), logacc


def _dry_neighbour_violations(carved, surface):
    wet = ~torch.isnan(surface)
    top = torch.nan_to_num(surface, nan=-1e9)
    bad = 0
    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nb_wet = torch.roll(wet, (di, dj), (0, 1))
        nb_ground = torch.roll(carved, (di, dj), (0, 1))
        bad += int((wet & ~nb_wet & (nb_ground < top - 1e-3)).sum())
    return bad


def test_channel_follows_the_valley_and_never_spills():
    heights, logacc = _valley()
    carved, surface = RV.carve(heights, logacc, (0, 0), (0, 0))
    inner = (slice(RV.HALO_PX, -RV.HALO_PX), slice(RV.HALO_PX, -RV.HALO_PX))
    wet = ~torch.isnan(surface[inner])
    assert wet.any(), "no river where the flow ridge is strong"
    rows = torch.nonzero(wet)[:, 0].float() + RV.HALO_PX
    centre = heights.shape[0] / 2
    assert (rows - centre).abs().max().item() <= 6, "water strayed from the valley floor"
    # Most columns along the valley carry water (continuity).
    assert wet.any(dim=0).float().mean().item() > 0.9
    assert _dry_neighbour_violations(carved[inner], surface[inner]) == 0
    raised = carved > heights + 1e-4
    near = torch.nn.functional.max_pool2d((~torch.isnan(surface)).float()[None, None],
                                          2 * RV.LEVEE_PX + 3, 1, RV.LEVEE_PX + 1)[0, 0] > 0
    assert not (raised & ~near).any(), "ground raised away from any river"


def test_river_across_a_slope_holds_water_behind_levees():
    heights, logacc = _valley()
    rows = torch.arange(heights.shape[0], device=DEV, dtype=torch.float32).view(-1, 1)
    tilted = 300.0 + 0.5 * rows + 0.0 * heights  # plain slope: no valley under the flow ridge
    carved, surface = RV.carve(tilted.expand_as(heights).contiguous(), logacc, (0, 0), (0, 0))
    inner = (slice(RV.HALO_PX, -RV.HALO_PX), slice(RV.HALO_PX, -RV.HALO_PX))
    assert (~torch.isnan(surface[inner])).any(dim=0).float().mean().item() > 0.8
    assert _dry_neighbour_violations(carved[inner], surface[inner]) == 0


def test_weak_flow_stays_dry():
    heights, logacc = _valley(peak=RV.LOG_THRESHOLD - 0.5)
    _, surface = RV.carve(heights, logacc, (0, 0), (0, 0))
    assert torch.isnan(surface).all()


def test_ridge_lines_follow_a_diagonal_ridge_thinly():
    n = 256
    i = torch.arange(n, device=DEV, dtype=torch.float32)
    d = (i.view(-1, 1) - i.view(1, -1)).abs()  # distance-like offset from the diagonal
    line, _ = RV.ridge_lines(RV.LOG_THRESHOLD + 2.0 - 0.1 * d)
    inner = line[32:-32, 32:-32]
    rows = torch.arange(inner.shape[0], device=DEV)
    # On (or within a pixel of) the diagonal, never far from it, and thin.
    near = inner[rows, rows] | inner[rows, (rows + 1).clamp(max=inner.shape[1] - 1)]
    assert near.float().mean().item() > 0.95
    off = torch.nonzero(inner)
    assert (off[:, 0] - off[:, 1]).abs().max().item() <= 2
    assert inner.sum().item() <= 3 * inner.shape[0]
