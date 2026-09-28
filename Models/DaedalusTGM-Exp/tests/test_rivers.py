"""River pipeline stages (terrain_slm.river) on synthetic valleys: geometry in blocks, water that
never spills, stages that swap like parts, and the bank model's gate."""
import math

import torch

from terrain_slm.river import pipeline as RP
from terrain_slm.river.banks import BankNet, LearnedBanks, LeveeBanks
from terrain_slm.river.field import Drainage, RiverConfig, RiverContext, RiverField
from terrain_slm.river.geometry import CP, ridge_lines
from terrain_slm.river.scale import BlockScale

DEV = "cuda:1" if torch.cuda.device_count() > 1 else ("cuda" if torch.cuda.is_available() else "cpu")
THR = 4.5
SCALE = BlockScale.game_default(DEV)
HP = RP.HALO_PX


def _valley(hc=96, wc=96, peak=THR + 1.5, slope=0.8):
    """A valley running along the columns at the block's centre row, and a matching flow ridge."""
    h, w = hc * CP, wc * CP
    rows = torch.arange(h, device=DEV, dtype=torch.float32).view(-1, 1)
    cols = torch.arange(w, device=DEV, dtype=torch.float32).view(1, -1)
    centre = h / 2
    heights = 300.0 + slope * (rows - centre).abs() + 0.02 * cols
    crow = torch.arange(hc, device=DEV, dtype=torch.float32).view(-1, 1)
    logacc = (peak - 0.9 * ((crow + 0.5) * CP - centre).abs() / CP).expand(hc, wc).contiguous()
    return heights.expand(h, w).contiguous(), logacc


def _run(heights, logacc, pipe=None, cfg=RiverConfig(log_threshold=THR)):
    pipe = pipe or RP.default_pipeline(None)
    return pipe.run(heights, Drainage(logacc, (0, 0)), (0, 0), SCALE, cfg), pipe


def _spills(f: RiverField) -> int:
    wet = f.wet
    top = torch.where(wet, f.top, torch.full_like(f.top, -1e9))
    bad = 0
    for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nb_wet = torch.roll(wet, (di, dj), (0, 1))
        nb_ground = torch.roll(f.carved, (di, dj), (0, 1))
        m = wet & ~nb_wet & (nb_ground < top - 1e-3)
        bad += int(m[HP:-HP, HP:-HP].sum())
    return bad


def test_channel_follows_the_valley_is_sized_in_blocks_and_never_spills():
    heights, logacc = _valley()
    (f, ctx), _ = _run(heights, logacc)
    wet = f.wet[HP:-HP, HP:-HP]
    assert wet.any(dim=0).float().mean() > 0.9, "the river should run the whole valley"
    rows = torch.nonzero(wet)[:, 0].float() + HP
    assert (rows - heights.shape[0] / 2).abs().max() <= 12, "water strayed from the valley floor"
    # 1.5 log units above the threshold: 3 + 1.8 * 1.5 = 5.7 blocks wide, give or take a pixel.
    width_blocks = wet.sum(0).float().mean() / SCALE.px_per_block
    assert 4.5 <= width_blocks <= 7.0, width_blocks
    assert _spills(f) == 0


def test_bigger_flow_makes_a_wider_deeper_river():
    small, _ = _run(*_valley(peak=THR + 1.0))
    big, _ = _run(*_valley(peak=THR + 5.0))
    width = lambda f: f[0].wet[HP:-HP, HP:-HP].sum(0).float().mean()
    depth = lambda f: (f[0].top - f[0].carved)[f[0].wet].mean()
    assert width(big) > 2 * width(small)
    assert depth(big) > depth(small)


def test_weak_flow_stays_dry():
    (f, _), _ = _run(*_valley(peak=THR - 0.5))
    assert not f.wet.any()


def test_river_across_a_plain_slope_still_holds_its_water():
    heights, logacc = _valley()
    rows = torch.arange(heights.shape[0], device=DEV, dtype=torch.float32).view(-1, 1)
    tilted = (300.0 + 0.5 * rows).expand_as(heights).contiguous()  # no valley under the flow ridge
    (f, _), _ = _run(tilted, logacc)
    assert _spills(f) == 0


def test_ridge_lines_follow_a_diagonal_ridge_thinly():
    n = 256
    i = torch.arange(n, device=DEV, dtype=torch.float32)
    d = (i.view(-1, 1) - i.view(1, -1)).abs()
    line, _ = ridge_lines(THR + 2.0 - 0.1 * d, THR, 8.0)
    inner = line[32:-32, 32:-32]
    rows = torch.arange(inner.shape[0], device=DEV)
    near = inner[rows, rows] | inner[rows, (rows + 1).clamp(max=inner.shape[1] - 1)]
    assert near.float().mean() > 0.95
    off = torch.nonzero(inner)
    assert (off[:, 0] - off[:, 1]).abs().max() <= 2
    assert inner.sum() <= 3 * inner.shape[0]


def test_stages_swap_like_parts():
    """Replacing the bank stage (learned <-> levee) or dropping the guard changes only what those
    stages do: the channel the other stages decide is identical."""
    heights, logacc = _valley()
    a, pa = _run(heights, logacc, RP.default_pipeline(None))
    model = BankNet().to(DEV).eval()
    b, pb = _run(heights, logacc, RP.default_pipeline(model))
    assert torch.equal(a[0].channel, b[0].channel) and torch.equal(a[0].line, b[0].line)
    assert "banks" in pa.describe() and "banks" in pb.describe()
    custom = RP.RiverPipeline(native=[s for s in pa.native if s.name != "guard"], blocks=pa.blocks)
    c, _ = _run(heights, logacc, custom)
    assert "guard_raised_px" not in c[1].stats and torch.equal(c[0].channel, a[0].channel)


def test_learned_banks_are_a_no_op_without_a_river():
    heights, logacc = _valley(peak=THR - 2.0)
    ctx = RiverContext(SCALE, RiverConfig(log_threshold=THR), Drainage(logacc, (0, 0)), (0, 0))
    f = RiverField(ground=heights, channel=torch.zeros_like(heights, dtype=torch.bool), carved=heights)
    out = LearnedBanks(BankNet().to(DEV).eval())(f, ctx)
    assert torch.equal(out.carved, heights) and not out.band.any()


def test_block_columns_carry_river_depth_in_blocks():
    (f, ctx), pipe = _run(*_valley(peak=THR + 5.0))
    cols = pipe.columns(f, ctx, 2)
    m = RP.BLOCK_MARGIN
    wet = cols.wet[HP // 2 : -HP // 2, HP // 2 : -HP // 2]
    depth = (cols.water - cols.height)[HP // 2 : -HP // 2, HP // 2 : -HP // 2][wet]
    assert depth.min() >= 1 and depth.float().mean() >= 2.0
    assert (cols.flow[cols.wet] >= 0).float().mean() > 0.9


def test_water_never_rises_downstream_over_a_bump():
    """A valley whose floor has a 25 m bump across it, with flow growing downstream (+cols):
    the water top along the river must be non-increasing in the flow direction (the bed cuts a
    gorge through the bump instead of the water climbing over it), and nothing spills."""
    heights, logacc = _valley(peak=THR + 2.0)
    h, w = heights.shape
    cols = torch.arange(w, device=DEV, dtype=torch.float32).view(1, -1)
    heights = heights - 0.04 * cols + 25.0 * torch.exp(-((cols - w / 2) / 20.0) ** 2)  # falls downstream, one bump
    wc = logacc.shape[1]
    logacc = logacc + torch.linspace(0.0, 1.0, wc, device=DEV).view(1, -1)            # flow grows downstream
    (f, ctx), _ = _run(heights, logacc)
    wet = f.wet[HP:-HP, HP:-HP]
    top = torch.where(wet, f.top[HP:-HP, HP:-HP], torch.full_like(wet, float("inf"), dtype=torch.float32))
    col_top = top.min(dim=0).values
    run = torch.isfinite(col_top)
    assert run.float().mean() > 0.9, "the river should run through the bump, not stop at it"
    t = col_top[run]
    assert bool((t[1:] <= t[:-1] + 1e-3).all()), f"water rose downstream by {float((t[1:] - t[:-1]).max()):.3f} m"
    assert _spills(f) == 0
