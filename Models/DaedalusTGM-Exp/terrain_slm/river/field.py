"""The data a river pipeline passes between its stages.

Stages are small, single-purpose callables `stage(field, ctx) -> field` (see pipeline.py): each
reads a few fields, fills its own, and never reaches into another stage's internals. Swapping
a stage (learned banks for deterministic ones, hydro-sidecar drainage for planner drainage)
never touches its neighbours.

Two resolutions:
  * RiverField   -- native model pixels (30 m) of one request block plus its halo, metres.
  * BlockColumns -- game block columns (60 m), block units, the TerrainTile contract.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import torch

from terrain_slm.river.scale import BlockScale

NO_TUNNEL = -1   # TerrainTile.NO_TUNNEL
NO_FLOW = -1     # TerrainTile.NO_FLOW
NO_WATER = -1    # TerrainTile.NO_WATER


@dataclass
class RiverConfig:
    """Every knob, in game units (blocks) where it is a size."""
    log_threshold: float = 4.5          # log1p(upslope cells) where a river starts
    width_min_blocks: float = 3.0       # a stream at the threshold
    width_per_log: float = 1.8          # + blocks per unit of log flow above it
    width_max_blocks: float = 16.0      # the largest rivers
    depth_min_blocks: float = 2.0
    depth_per_log: float = 0.6
    depth_max_blocks: float = 6.0
    extra_incision_blocks: float = 3.0  # bed may cut at most depth + this below the ground
    bank_band_blocks: float = 6.0       # how far from the water the bank stage may reshape
    surface_sigma_px: float = 4.0       # water level smoothing along the channel
    ridge_smooth_px: float = 8.0        # centreline detection smoothing
    undercut_min_rise: int = 3          # blocks a bank must stand above the water to be undercut
    undercut_air: int = 2               # air blocks in an undercut
    undercut_cover: int = 1             # ground that must remain over an undercut's roof
    overhang_min_width_blocks: float = 5.0  # only channels at least this wide get lips over water


@dataclass
class Drainage:
    """Per-cell drainage covering the request block plus a cell halo."""
    logacc: torch.Tensor            # (Hc, Wc) log1p(precip-weighted upslope cells)
    origin: tuple[int, int]         # absolute cell coordinates of [0, 0]
    d8: torch.Tensor | None = None  # (Hc, Wc) long, 0..7 neighbour, 8 terminal (hydro sidecar)


@dataclass
class RiverContext:
    scale: BlockScale
    cfg: RiverConfig
    drainage: Drainage
    px_origin: tuple[int, int]      # absolute native pixel of the block's [0, 0]
    seed: int = 0
    stats: dict = field(default_factory=dict)


@dataclass
class RiverField:
    ground: torch.Tensor                    # (H, W) m, terrain before rivers
    flow: torch.Tensor | None = None        # smoothed log flow at pixels
    line: torch.Tensor | None = None        # bool centrelines
    channel: torch.Tensor | None = None     # bool water footprint
    edge_px: torch.Tensor | None = None     # px from the channel edge, inside the channel
    half_px: torch.Tensor | None = None     # channel half width, px
    depth_blocks: torch.Tensor | None = None
    level: torch.Tensor | None = None       # m, water surface (valid on and near the channel)
    band: torch.Tensor | None = None        # bool bank zone beside the channel
    carved: torch.Tensor | None = None      # m, ground after banks and bed
    wet: torch.Tensor | None = None         # bool, water that survived containment
    top: torch.Tensor | None = None         # m, water surface where wet

    @property
    def has_river(self) -> bool:
        return self.channel is not None and bool(self.channel.any())


@dataclass
class BlockColumns:
    """The TerrainTile contract for one block (all (h, w), block units)."""
    height: torch.Tensor        # int, first non-ground y... ground occupies y < height
    water: torch.Tensor         # int, first non-water y, NO_WATER where dry
    floor: torch.Tensor         # int, tunnel floor (stone), NO_TUNNEL
    roof: torch.Tensor          # int, tunnel roof (stone), NO_TUNNEL
    flow: torch.Tensor          # int, octant 0..7 (0 = +x, toward +z), NO_FLOW
    wet: torch.Tensor           # bool
