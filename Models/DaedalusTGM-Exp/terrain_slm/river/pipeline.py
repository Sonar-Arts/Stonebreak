"""River pipeline: an ordered list of stages at model resolution, then at block resolution.

    native:  Centrelines -> ChannelGeometry -> WaterSurface -> Banks -> BankGuard -> UBed -> NoSpill
    blocks:  BlockQuantize -> FlowOctants -> FlattenAcross -> BlockContainment -> Undercuts -> Overhangs

Every stage is `stage(field, ctx) -> field` (native) or `stage(cols, src, ctx) -> cols` (blocks),
so a stage is replaced or removed by editing the list, and nothing else changes. The default
list uses the learned bank model when one is loaded and the deterministic levee ring otherwise.
"""
from __future__ import annotations

import torch

from terrain_slm.river.banks import BankGuard, BankNet, LearnedBanks, LeveeBanks
from terrain_slm.river.blocks import (BlockContainment, BlockQuantize, FlattenAcross, FlowOctants, Overhangs,
                                      Undercuts, pool_to_blocks)
from terrain_slm.river.contain import NoSpill
from terrain_slm.river.field import BlockColumns, Drainage, RiverConfig, RiverContext, RiverField
from terrain_slm.river.geometry import CP, Centrelines, ChannelGeometry, UBed, WaterSurface
from terrain_slm.river.scale import BlockScale

# Context a pixel's result depends on, each side: centreline smoothing (3 sigma), the widest
# channel, the bank band, and the bank model's receptive field.
HALO_PX = 128  # + the centreline hysteresis reach (64 px)
CELL_HALO = 4       # extra drainage cells beyond the pixel halo
BLOCK_MARGIN = 24   # block columns of context for the block stages (flow smoothing, the
                    # cross-channel levelling walk of up to width_max_blocks + 1, neighbours)


class RiverPipeline:
    def __init__(self, native: list, blocks: list):
        self.native, self.blocks = native, blocks

    def run(self, ground: torch.Tensor, drainage: Drainage, px_origin: tuple[int, int], scale: BlockScale,
            cfg: RiverConfig, seed: int = 0) -> tuple[RiverField, RiverContext]:
        ctx = RiverContext(scale, cfg, drainage, px_origin, seed)
        f = RiverField(ground=ground)
        f.carved = ground
        for stage in self.native:
            f = stage(f, ctx)
        return f, ctx

    def columns(self, f: RiverField, ctx: RiverContext, d: int) -> BlockColumns:
        """Pool the field into d x d blocks (px_origin must be block-aligned) and run the block stages."""
        origin = (ctx.px_origin[0] // d, ctx.px_origin[1] // d)
        src = pool_to_blocks(f, d, origin, ctx.drainage)
        cols = None
        for stage in self.blocks:
            cols = stage(cols, src, ctx)
        return cols

    def describe(self) -> str:
        return " -> ".join(s.name for s in self.native) + " | " + " -> ".join(s.name for s in self.blocks)


def default_pipeline(bank_model: BankNet | None = None) -> RiverPipeline:
    banks = LearnedBanks(bank_model) if bank_model is not None else LeveeBanks()
    return RiverPipeline(
        native=[Centrelines(), ChannelGeometry(), WaterSurface(), banks, BankGuard(), UBed(), NoSpill()],
        blocks=[BlockQuantize(), FlowOctants(), FlattenAcross(), BlockContainment(), Undercuts(), Overhangs()],
    )
