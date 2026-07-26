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


def _env_bool(name: str, default: bool) -> bool:
    val = os.environ.get(name)
    if val is None:
        return default
    return val.strip().lower() not in ("0", "false", "no", "off", "")


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

    # Horizontal cell size (native_resolution / scale). Distinct from
    # meters_per_block even though both are 15.0 at scale=2 -- after the Phase 5
    # curve the vertical mapping is non-linear and the two diverge. Drainage area
    # in hydrology/depth.py needs this one, not the vertical rate.
    horizontal_meters_per_block: float = 15.0

    # Elevation -> block height curve knots (see height_mapping.HeightCurve).
    # Defaults chosen in `Dev Working/Rivers and lakes plan.md` Phase 5.
    ocean_meters_per_block: float = 12.0
    lowland_meters_per_block: float = 4.0
    midland_meters_per_block: float = 10.0
    highland_meters_per_block: float = 24.0
    lowland_top_m: float = 600.0
    highland_base_m: float = 2000.0
    shore_blend_m: float = 60.0
    midland_blend_m: float = 150.0
    highland_blend_m: float = 300.0

    # Inland water (Phase 8). Off leaves the bridge exactly as it was through Phase 7 --
    # a sea-level-only water plane, no `hydrology` import, and therefore no numba/LLVM in
    # the service's dependency footprint (plan section 4.1). On, the first tile in an
    # unsolved L1 macro-tile pays that tile's solve (~17 s, one per 256 terrain tiles)
    # and the first tile in an unsolved L0 region pays the region's (~200 s, one per
    # 4096); both are cached to disk and neither recurs.
    hydrology_enabled: bool = True

    # The two knobs plan sections 14.11 and 15.15 both flag as most likely to want
    # retuning once water is actually visible in-game. Their defaults are the ones the
    # already-solved regions and tiles were built under, so leaving them alone changes
    # no namespace.
    l0_max_raise_m: float = 100.0
    river_threshold_cells: float = 10_000.0

    # Carve shape -- see hydrology/carve.py's CarveParams for what each one buys.
    bank_margin_blocks: int = 48
    min_water_blocks: int = 1

    # Cold-start bound (Rivers and lakes plan.md section 16.10 / 18.5). A cold L1 solve
    # is ~17 s and a cold L0 region ~200-290 s; the worst case, an L1 tile whose halo
    # straddles four unsolved L0 regions, is ~1160 s. That is far too long to hold one
    # HTTP request open. `/generate_heightmap` instead blocks at most `max_wait_s` on an
    # unfinished tile, then answers 503 with `Retry-After: solve_retry_after_s` -- the
    # job itself keeps running on the queue regardless of who is or isn't still waiting
    # on it, so a client that polls again lands on the same in-flight job rather than
    # starting a second one. The Java client's patient poll budget is a separate,
    # generous knob (`hydrologySolveGraceMs`) that covers the whole worst case; this one
    # only bounds a single request/response round trip.
    max_wait_s: float = 20.0
    solve_retry_after_s: int = 5

    @staticmethod
    def from_env(seed: int | None = None) -> "BridgeConfig":
        """Read every knob from the environment.

        `seed` is normally required and comes from the environment, because a
        running bridge pins one seed for its whole lifetime. Passing it explicitly
        is for offline tools that want the *rest* of the configuration -- reading a
        tile cache back through the elevation curve, say, where the seed is a
        property of the tile being read rather than of the process.
        """
        if seed is not None:
            return BridgeConfig._build(seed)
        seed_env = os.environ.get("TERRAIN_BRIDGE_SEED")
        if seed_env is None:
            raise RuntimeError(
                "TERRAIN_BRIDGE_SEED is required. This bridge pins one seed for its "
                "whole lifetime (plan.md Phase 1 item 4) — start a separate bridge "
                "instance, pointed at a separate upstream instance started with a "
                "matching --seed, for a different seed."
            )
        return BridgeConfig._build(int(seed_env))

    @staticmethod
    def _build(seed: int) -> "BridgeConfig":
        return BridgeConfig(
            upstream_url=os.environ.get("TERRAIN_BRIDGE_UPSTREAM_URL", "http://localhost:8000"),
            seed=seed,
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
            horizontal_meters_per_block=_env_float(
                "TERRAIN_BRIDGE_HORIZONTAL_METERS_PER_BLOCK", 15.0
            ),
            ocean_meters_per_block=_env_float("TERRAIN_BRIDGE_OCEAN_METERS_PER_BLOCK", 12.0),
            lowland_meters_per_block=_env_float("TERRAIN_BRIDGE_LOWLAND_METERS_PER_BLOCK", 4.0),
            midland_meters_per_block=_env_float("TERRAIN_BRIDGE_MIDLAND_METERS_PER_BLOCK", 10.0),
            highland_meters_per_block=_env_float("TERRAIN_BRIDGE_HIGHLAND_METERS_PER_BLOCK", 24.0),
            lowland_top_m=_env_float("TERRAIN_BRIDGE_LOWLAND_TOP_M", 600.0),
            highland_base_m=_env_float("TERRAIN_BRIDGE_HIGHLAND_BASE_M", 2000.0),
            shore_blend_m=_env_float("TERRAIN_BRIDGE_SHORE_BLEND_M", 60.0),
            midland_blend_m=_env_float("TERRAIN_BRIDGE_MIDLAND_BLEND_M", 150.0),
            highland_blend_m=_env_float("TERRAIN_BRIDGE_HIGHLAND_BLEND_M", 300.0),
            hydrology_enabled=_env_bool("TERRAIN_BRIDGE_HYDROLOGY", True),
            l0_max_raise_m=_env_float("TERRAIN_BRIDGE_L0_MAX_RAISE_M", 100.0),
            river_threshold_cells=_env_float("TERRAIN_BRIDGE_RIVER_THRESHOLD_CELLS", 10_000.0),
            bank_margin_blocks=_env_int("TERRAIN_BRIDGE_BANK_MARGIN_BLOCKS", 48),
            min_water_blocks=_env_int("TERRAIN_BRIDGE_MIN_WATER_BLOCKS", 1),
            max_wait_s=_env_float("TERRAIN_BRIDGE_MAX_WAIT_S", 20.0),
            solve_retry_after_s=_env_int("TERRAIN_BRIDGE_SOLVE_RETRY_AFTER_S", 5),
        )
