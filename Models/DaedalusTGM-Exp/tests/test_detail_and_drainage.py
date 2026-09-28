"""v4 pieces: the detail sampler's contract, routed drainage, and block-level monotone water."""
import math

import torch

from terrain_slm.models import detail as DT
from terrain_slm.river.blocks import BlockSource, MonotoneBlocks
from terrain_slm.river.field import NO_FLOW, NO_TUNNEL, BlockColumns, RiverConfig, RiverContext
from terrain_slm.river.scale import BlockScale
from terrain_slm.world.generator import routed_drainage

# D8 class -> (drow, dcol), class order of data/build.py.
D8 = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))


def test_detail_encode_decode_roundtrip_and_shapes():
    r = torch.tensor([-800.0, -12.0, -0.5, 0.0, 0.3, 7.0, 1500.0])
    assert torch.allclose(DT.decode(DT.encode(r)), r, atol=1e-3)
    m = DT.Detail(DT.DetailConfig(channels=(32, 32, 32), blocks=1, temb=16, dropout=0.0)).eval()
    x = torch.randn(2, 1, 64, 64)
    cond = torch.randn(2, DT.N_COND, 64, 64)
    v = m(x, torch.tensor([0.1, 0.9]), cond)
    assert v.shape == x.shape
    assert float(v.abs().max()) == 0.0, "zero-initialised output: an untrained model predicts no velocity"


def test_detail_base_is_the_cell_mean_on_flat_ground_and_upsamples_by_four():
    cells = torch.full((1, 1, 10, 12), 250.0)
    base = DT.detail_base(cells)
    assert base.shape == (1, 1, 40, 48)
    assert torch.allclose(base, torch.full_like(base, 250.0), atol=1e-3)


def _valley_cells(n=96):
    """A valley draining toward +col, floor falling 2 m per cell, sides rising 6 m per cell."""
    r = torch.arange(n, dtype=torch.float64).view(-1, 1)
    c = torch.arange(n, dtype=torch.float64).view(1, -1)
    return (400.0 - 2.0 * c + 6.0 * (r - n / 2).abs()).float()


def test_routed_drainage_grows_downstream_along_d8_and_rivers_connect():
    h = _valley_cells()
    out = routed_drainage(h, torch.full_like(h, 1000.0), None)
    logacc, river, d8 = out[0], out[1], out[2].long()
    n = h.shape[0]
    # Walk D8 from a high cell: accumulation never drops along the path.
    i, j = 10, 5
    prev = float(logacc[i, j])
    for _ in range(400):
        k = int(d8[i, j])
        if k == 8:
            break
        i, j = i + D8[k][0], j + D8[k][1]
        if not (0 <= i < n and 0 <= j < n):
            break
        assert float(logacc[i, j]) >= prev - 1e-5, "accumulation dropped downstream"
        prev = float(logacc[i, j])
    # The valley floor carries a river that runs, unbroken, to the outlet edge.
    floor = river[n // 2 - 1 : n // 2 + 2].amax(0)
    first = int(torch.nonzero(floor)[0])
    assert bool(floor[first:].all()), "river must stay connected from where it starts to the edge"


def test_routed_drainage_inflow_enters_through_the_window_edge():
    h = _valley_cells()
    base = routed_drainage(h, torch.full_like(h, 1000.0), None)
    inflow = torch.zeros_like(h)
    inflow[:, 0] = math.log1p(5000.0)   # a big basin upstream of the window's west edge
    fed = routed_drainage(h, torch.full_like(h, 1000.0), inflow)
    mid = h.shape[0] // 2
    assert float(fed[0, mid, 20]) > float(base[0, mid, 20]) + 1.0, "edge inflow should raise downstream flow"


def _cols(water, flow):
    h, w = water.shape
    wet = water >= 0
    return BlockColumns(height=torch.where(wet, water - 2, torch.full_like(water, 80)), water=water.clone(),
                        floor=torch.full_like(water, NO_TUNNEL), roof=torch.full_like(water, NO_TUNNEL),
                        flow=flow, wet=wet)


def test_monotone_blocks_never_lets_water_climb_downstream():
    # A river flowing +z (octant 2) along row 1 whose third column was flattened too low.
    water = torch.tensor([[-1, -1, -1, -1, -1, -1],
                          [70, 70, 66, 69, 68, 68],
                          [-1, -1, -1, -1, -1, -1]])
    flow = torch.where(water >= 0, torch.full_like(water, 2), torch.full_like(water, NO_FLOW))
    cols = _cols(water, flow)
    src = BlockSource(cols.height.float(), water.float(), cols.wet, torch.zeros(water.shape), (0, 0), 2, None)
    ctx = RiverContext(BlockScale.game_default("cpu"), RiverConfig(), None, (0, 0))
    out = MonotoneBlocks()(cols, src, ctx)
    row = out.water[1].tolist()
    assert all(b <= a for a, b in zip(row, row[1:])), row
    assert all(a - b <= 1 for a, b in zip(row, row[1:])), f"a drop taller than one block: {row}"
    # the too-low column pulls everything below it down to 66, and the reach above it incises
    # into one-block cascades (70, 70 -> 68, 67)
    assert row == [68, 67, 66, 66, 66, 66], row
    assert bool((out.height[1] < out.water[1]).all()), "every wet column keeps water above its ground"


def test_block_containment_walls_a_dry_column_standing_exactly_at_sea_level():
    """River mouths: a dry column at y == sea level gets no sea water from the tile builder
    (sea fills only height < sea level), so the river beside it must raise it, not skip it."""
    from terrain_slm.river.blocks import BlockContainment
    scale = BlockScale.game_default("cpu")
    sea = scale.sea_level
    water = torch.tensor([[-1, -1, -1], [sea + 2, sea + 2, sea + 2], [-1, -1, -1]])
    cols = _cols(water, torch.where(water >= 0, torch.full_like(water, 2), torch.full_like(water, NO_FLOW)))
    cols.height = torch.where(cols.wet, torch.full_like(water, sea), torch.full_like(water, sea))  # dry banks AT sea level
    src = BlockSource(torch.zeros(water.shape), water.float(), cols.wet, torch.zeros(water.shape), (0, 0), 2, None)
    out = BlockContainment()(cols, src, RiverContext(scale, RiverConfig(), None, (0, 0)))
    assert bool((out.height[0] >= sea + 2).all()) and bool((out.height[2] >= sea + 2).all()), out.height


def test_monotone_blocks_levels_a_diagonal_river_across_the_block_grid():
    """A river flowing diagonally (octant 1): each orthogonal neighbour in its forward cone is
    downstream too, so no column may stand more than one block above it (or below it)."""
    lv = torch.tensor([[90, 88, -1, -1],
                       [93, 90, 87, -1],
                       [-1, 93, 90, 84],
                       [-1, -1, 92, 89]])
    flow = torch.where(lv >= 0, torch.full_like(lv, 1), torch.full_like(lv, NO_FLOW))
    cols = _cols(lv, flow)
    src = BlockSource(cols.height.float(), lv.float(), cols.wet, torch.zeros(lv.shape), (0, 0), 2, None)
    out = MonotoneBlocks()(cols, src, RiverContext(BlockScale.game_default("cpu"), RiverConfig(), None, (0, 0)))
    w, wet = out.water, out.wet
    for di, dj in ((1, 0), (0, 1), (1, 1)):   # the forward cone of octant 1
        a, b = w[: w.shape[0] - di, : w.shape[1] - dj], w[di:, dj:]
        both = wet[: w.shape[0] - di, : w.shape[1] - dj] & wet[di:, dj:]
        assert bool(((a - b)[both] <= 1).all() and ((b - a)[both] <= 0).all()), (di, dj, out.water)


def test_biomenet_receptive_field_fits_inside_the_tile_margin():
    """Tiles classify biomes over tile + RP.BLOCK_MARGIN and crop: the sidecar's receptive field
    must not reach past that margin, or a tile's biomes would depend on the request."""
    from terrain_slm.models import biomenet as BN
    from terrain_slm.river import pipeline as RP
    torch.manual_seed(0)
    for ch in ((32, 64, 96), (48, 96, 128)):
        m = BN.BiomeNet(BN.BiomeNetConfig(channels=ch)).eval()
        x = torch.randn(1, BN.N_IN, 128, 128)
        y = x.clone()
        y[0, :, 64, 64] += 5.0
        with torch.no_grad():
            d = (m(x) - m(y)).abs().amax(dim=(0, 1))
        rows, cols = torch.nonzero(d > 1e-6, as_tuple=True)
        radius = int(max((rows - 64).abs().max(), (cols - 64).abs().max()))
        assert radius < RP.BLOCK_MARGIN, f"receptive-field radius {radius} >= margin {RP.BLOCK_MARGIN}"


def test_v4_tile_with_detail_sampler_and_biome_sidecar_matches_a_bigger_request():
    """The shipped v4 path end to end (detail windows, routed drainage, river stages, biome sidecar on
    pre-river ground): a tile's planes equal the same blocks cut from a larger request."""
    import pytest
    from pathlib import Path
    v4 = Path(__file__).resolve().parents[1] / "checkpoints/v4"
    if not (v4 / "detail.pt").exists() or not torch.cuda.is_available():
        pytest.skip("no v4 model / GPU")
    from terrain_slm.world.generator import WorldGenerator
    from terrain_slm.world.tiles import TileBuilder
    from terrain_slm.world.world_config import WorldConfig
    dev = "cuda:1" if torch.cuda.device_count() > 1 else "cuda"
    gen = WorldGenerator(v4, 7, dev)
    api = TileBuilder(gen, WorldConfig.GAME)
    assert gen.detail is not None and gen.biomenet is not None
    small, big = api._blocks(0, 0, 64, 64, 2), api._blocks(-32, -32, 96, 96, 2)
    for k in range(4):   # height, biome, water, wet
        assert torch.equal(small[k], big[k][32:96, 32:96]), k
    assert gen.detail_failures == 0
