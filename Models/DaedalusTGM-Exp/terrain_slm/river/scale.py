"""Block scale: metres <-> game blocks, the unit every river stage sizes itself in.

The game decides blocks through the bridge's HeightCurve (vertical, metres per block varies by
band) and TerrainScale (horizontal, 60 m blocks). Rivers are specified in blocks (a stream is
3 blocks wide and 2 deep, whatever band it runs through), so each stage converts through this.
"""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass

import numpy as np
import torch

from terrain_slm.paths import BRIDGE_DIR

NATIVE_M = 30.0  # metres per model pixel

# The game's TerrainScale values (TerrainScale.CURVE_RATES etc.), used when no curve is supplied
# (training, tests, offline evaluation). The model server always passes the live curve.
GAME_ENV = {
    "TERRAIN_BRIDGE_WORLD_HEIGHT": "256", "TERRAIN_BRIDGE_SEA_LEVEL": "64",
    "TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK": "48", "TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK": "16",
    "TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK": "24", "TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK": "38",
    "TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK": "60",
}


def bridge_curve(env: dict | None = None):
    """The bridge's HeightCurve for `env` (default: the process environment over GAME_ENV)."""
    if str(BRIDGE_DIR) not in sys.path:
        sys.path.insert(0, str(BRIDGE_DIR))
    from bridge.config import BridgeConfig
    from bridge.height_mapping import HeightCurve
    saved = dict(os.environ)
    try:
        for k, v in (env or GAME_ENV).items():
            os.environ.setdefault(k, v) if env is None else os.environ.__setitem__(k, v)
        return HeightCurve.from_config(BridgeConfig.from_env(seed=0))
    finally:
        os.environ.clear()
        os.environ.update(saved)


@dataclass(frozen=True)
class BlockScale:
    """Continuous metres <-> blocks lookup on a device, from a HeightCurve."""

    elev: torch.Tensor      # (N,) metres, increasing
    blocks: torch.Tensor    # (N,) continuous block height, increasing
    horizontal_m: float     # metres per block horizontally
    sea_level: int
    world_height: int

    @staticmethod
    def from_curve(curve, device, horizontal_m: float | None = None) -> "BlockScale":
        e = np.arange(-4000.0, 9000.0, 2.0)
        y = curve.to_block_height_exact(e)
        hm = horizontal_m or float(os.environ.get("TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK",
                                                  GAME_ENV["TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK"]))
        return BlockScale(torch.tensor(e, dtype=torch.float32, device=device),
                          torch.tensor(y, dtype=torch.float32, device=device),
                          hm, curve.sea_level, curve.world_height)

    @staticmethod
    def game_default(device) -> "BlockScale":
        return BlockScale.from_curve(bridge_curve(), device, float(GAME_ENV["TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK"]))

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
