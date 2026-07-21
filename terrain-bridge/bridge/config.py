"""Env-driven configuration for the terrain bridge.

See plan.md section 4 for where the default scale/meters-per-block/world-height/
sea-level numbers come from, and section 5 Phase 1 item 4 for why seed is pinned
for the lifetime of the process rather than accepted per-request.
"""
from __future__ import annotations

import os
from dataclasses import dataclass


def _env_int(name: str, default: int) -> int:
    val = os.environ.get(name)
    return int(val) if val is not None else default


def _env_float(name: str, default: float) -> float:
    val = os.environ.get(name)
    return float(val) if val is not None else default


@dataclass(frozen=True)
class BridgeConfig:
    upstream_url: str
    seed: int
    scale: int
    tile_size_blocks: int
    meters_per_block: float
    world_height: int
    sea_level: int
    noise_scale: float
    cache_dir: str
    cache_max_bytes: int
    upstream_timeout_s: float

    @staticmethod
    def from_env() -> "BridgeConfig":
        seed_env = os.environ.get("TERRAIN_BRIDGE_SEED")
        if seed_env is None:
            raise RuntimeError(
                "TERRAIN_BRIDGE_SEED is required. This bridge pins one seed for its "
                "whole lifetime (plan.md Phase 1 item 4) — start a separate bridge "
                "instance, pointed at a separate upstream instance started with a "
                "matching --seed, for a different seed."
            )
        return BridgeConfig(
            upstream_url=os.environ.get("TERRAIN_BRIDGE_UPSTREAM_URL", "http://localhost:8000"),
            seed=int(seed_env),
            scale=_env_int("TERRAIN_BRIDGE_SCALE", 2),
            tile_size_blocks=_env_int("TERRAIN_BRIDGE_TILE_SIZE", 256),
            # Default matches plan.md section 4's recommendation: 30m model @ scale=2.
            meters_per_block=_env_float("TERRAIN_BRIDGE_METERS_PER_BLOCK", 15.0),
            world_height=_env_int("TERRAIN_BRIDGE_WORLD_HEIGHT", 1024),
            sea_level=_env_int("TERRAIN_BRIDGE_SEA_LEVEL", 320),
            noise_scale=_env_float("TERRAIN_BRIDGE_NOISE_SCALE", 1.0),
            cache_dir=os.environ.get("TERRAIN_BRIDGE_CACHE_DIR", "./tile_cache"),
            cache_max_bytes=_env_int("TERRAIN_BRIDGE_CACHE_MAX_BYTES", 2 * 1024**3),
            upstream_timeout_s=_env_float("TERRAIN_BRIDGE_UPSTREAM_TIMEOUT_S", 30.0),
        )
