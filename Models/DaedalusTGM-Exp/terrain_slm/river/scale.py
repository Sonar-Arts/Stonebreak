"""Block scale: metres <-> game blocks, the unit every river stage sizes itself in.

The game decides blocks through its height curve (vertical, metres per block varies by band) and
its horizontal scale (60 m blocks), both carried by a `WorldConfig`. Rivers are specified in
blocks (a stream is 3 blocks wide and 2 deep, whatever band it runs through), so each stage
converts through this.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import torch

from terrain_slm.world.world_config import WorldConfig

NATIVE_M = 30.0  # metres per model pixel


@dataclass(frozen=True)
class BlockScale:
    """Continuous metres <-> blocks lookup on a device, from a HeightCurve."""

    elev: torch.Tensor      # (N,) metres, increasing
    blocks: torch.Tensor    # (N,) continuous block height, increasing
    horizontal_m: float     # metres per block horizontally
    sea_level: int
    world_height: int

    @staticmethod
    def from_world(world: WorldConfig, device) -> "BlockScale":
        curve = world.curve()
        e = np.arange(-4000.0, 9000.0, 2.0)
        y = curve.to_block_height_exact(e)
        return BlockScale(torch.tensor(e, dtype=torch.float32, device=device),
                          torch.tensor(y, dtype=torch.float32, device=device),
                          world.horizontal_m, world.sea_level, world.world_height)

    @staticmethod
    def game_default(device) -> "BlockScale":
        """The game's scale, for work that runs without the game (training, tests, evaluation)."""
        return BlockScale.from_world(WorldConfig.GAME, device)

    @property
    def px_per_block(self) -> float:
        return self.horizontal_m / NATIVE_M

    def to_blocks(self, e: torch.Tensor) -> torch.Tensor:
        """Continuous (unfloored) block height of elevation `e` metres."""
        return _interp(e, self.elev, self.blocks)

    def to_metres(self, y: torch.Tensor) -> torch.Tensor:
        return _interp(y, self.blocks, self.elev)

    def rate(self, e: torch.Tensor) -> torch.Tensor:
        """Metres per block at elevation `e`."""
        return 1.0 / (self.to_blocks(e + 1.0) - self.to_blocks(e - 1.0)).clamp_min(1e-4) * 2.0


def _interp(x: torch.Tensor, xp: torch.Tensor, fp: torch.Tensor) -> torch.Tensor:
    xc = x.clamp(xp[0], xp[-1])
    i = torch.searchsorted(xp, xc.contiguous()).clamp(1, len(xp) - 1)
    x0, x1, f0, f1 = xp[i - 1], xp[i], fp[i - 1], fp[i]
    return f0 + (f1 - f0) * (xc - x0) / (x1 - x0)
