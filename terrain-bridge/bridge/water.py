"""The tile server's one door into the hydrology package.

Through Phase 7 nothing under `bridge/` imported `hydrology/`, deliberately: the solver
pulls numba and LLVM, and a tile server that only maps elevation to blocks has no use
for them (plan section 4.1). Phase 8 is where the carve has to run in the request path,
and this module is the whole of that coupling — one lazy import behind one interface, so
the dependency is visible in one place and `TERRAIN_BRIDGE_HYDROLOGY=0` still starts a
bridge that never touches it.

Both implementations answer the same question: given a tile's canonical elevation in
metres, what block heights and what water level does each column get? `SeaLevelWater` is
exactly the rule the game had before this phase — `y < SEA_LEVEL` — restated per column,
which is what makes the Java side's protocol change independent of whether inland water
is switched on.
"""
from __future__ import annotations

import logging
from typing import Protocol

import numpy as np

from .config import BridgeConfig
from .height_mapping import HeightCurve

log = logging.getLogger("terrain_bridge.water")


class WaterSource(Protocol):
    """Elevation in metres -> (block heights, water levels, report)."""

    #: Contribution to the tile cache namespace. Empty when nothing but the curve
    #: decides the output, so enabling hydrology rotates the namespace and disabling
    #: it rotates back to exactly the tiles Phase 7 would have served.
    fingerprint: str

    def planes(
        self, bounds_blocks: tuple[int, int, int, int], elevation_m: np.ndarray
    ) -> tuple[np.ndarray, np.ndarray, dict]:
        ...


class SeaLevelWater:
    """No inland water: the ocean, as a per-column water level.

    `-1` means "this column holds no water", which is the sentinel the wire protocol
    defines. A column whose terrain top *is* sea level stays dry, exactly as
    `y < SEA_LEVEL` left it.
    """

    fingerprint = ""

    def __init__(self, cfg: BridgeConfig):
        self._curve = HeightCurve.from_config(cfg)
        self._sea_level = cfg.sea_level

    def planes(
        self, bounds_blocks: tuple[int, int, int, int], elevation_m: np.ndarray
    ) -> tuple[np.ndarray, np.ndarray, dict]:
        heights = self._curve.to_block_height(elevation_m)
        water = np.where(heights < self._sea_level, self._sea_level, -1).astype(np.int16)
        return heights, water, {}


class HydrologyWater:
    """Rivers and lakes, carved at block resolution from the L1 solve.

    Construction is cheap — no region is solved until a tile actually needs one — but the
    *first* tile over unsolved ground is not: it pays an L1 macro-tile (~17 s, amortised
    over the 256 terrain tiles that tile serves) and, if the L1 tile's own halo crosses
    into an unsolved L0 region, that region too (~200 s, amortised over 4096). Both land
    on the GPU work queue's single consumer, so they serialise with tile generation
    rather than competing with it for the GPU, and both are cached to disk permanently.
    """

    def __init__(self, cfg: BridgeConfig):
        # Local, not module-level: importing `hydrology` pulls numba and LLVM, and a
        # bridge with hydrology off must not pay for them.
        from hydrology.basins import L0Cache, L0Params, upstream_source
        from hydrology.carve import CarveParams, L1Mosaic, carve_tile, fingerprint
        from hydrology.local import L0Mosaic, L1Cache, L1Params

        self._carve_tile = carve_tile
        self._cfg = cfg
        self._curve = HeightCurve.from_config(cfg)

        l0_params = L0Params(max_raise_m=cfg.l0_max_raise_m)
        l1_params = L1Params(
            l0_params=l0_params, river_threshold_cells=cfg.river_threshold_cells
        )
        self._params = CarveParams(
            bank_margin_blocks=cfg.bank_margin_blocks,
            min_water_blocks=cfg.min_water_blocks,
        )

        source = upstream_source(cfg)
        l0 = L0Mosaic(L0Cache(cfg.cache_dir, l0_params), source, cfg.seed)
        self._mosaic = L1Mosaic(L1Cache(cfg.cache_dir, l1_params), source, cfg.seed, l0)

        self.fingerprint = fingerprint(self._params, l1_params)
        log.info(
            "hydrology enabled: carve %s, L1 %s, L0 %s",
            self.fingerprint, l1_params.fingerprint(), l0_params.fingerprint(),
        )

    def planes(
        self, bounds_blocks: tuple[int, int, int, int], elevation_m: np.ndarray
    ) -> tuple[np.ndarray, np.ndarray, dict]:
        result = self._carve_tile(
            bounds_blocks,
            elevation_m,
            curve=self._curve,
            scale=self._cfg.scale,
            sea_level=self._cfg.sea_level,
            mosaic=self._mosaic,
            horizontal_meters_per_block=self._cfg.horizontal_meters_per_block,
            params=self._params,
        )
        return result.block_heights, result.water_levels, result.report


def build(cfg: BridgeConfig) -> WaterSource:
    return HydrologyWater(cfg) if cfg.hydrology_enabled else SeaLevelWater(cfg)
