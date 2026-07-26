"""Derive water bodies from a diffusion-model elevation map.

The terrain-diffusion model emits one channel: elevation in metres. Rather than
retrain it to emit water, this package recovers rivers and lakes from the DEM with
a standard hydrological flow model -- cheap, deterministic, and consistent with the
terrain the model already produced.

Two mechanisms, because rivers and lakes are two different things:

  lakes   fill the DEM's closed depressions; the fill delta *is* the standing-water
          depth, with a flat surface at the spill elevation. No tuning constant.
  rivers  route D8 flow over the filled surface, accumulate upslope area, and turn
          drainage area into a channel depth via hydraulic geometry.

Three resolutions, coarsest first: `basins.py` (L0, 240 m, continental drainage),
`local.py` (L1, native 30 m, streams and small lakes), and `carve.py` (L2, block
resolution -- the bed, the per-column water level, and the containment invariant).

Since Phase 8 the running bridge service *does* import this, through `bridge/water.py`
and only when `TERRAIN_BRIDGE_HYDROLOGY` is on: the carve has to run in the tile request
path. With it off the import never happens and the service's dependency set stays exactly
as lean as it was. See requirements-hydro.txt.

Convention shared with upstream terrain-diffusion: elevation <= 0 or NaN is ocean.
"""
from .fill import cap_basins, fill_depressions
from .flats import resolve_flats
from .flow import d8_receivers, flow_accumulation, topological_levels
from .depth import WaterMap, water_depth
from .basins import (
    L0,
    L0Cache,
    L0Geometry,
    L0Params,
    L0RegionId,
    L0Solution,
    inflow_edges,
    solve_region,
)
from .local import (
    L1,
    L0Mosaic,
    L1Cache,
    L1Geometry,
    L1Params,
    L1Solution,
    L1TileId,
    channel_width_m,
    solve_tile,
)
from .carve import (
    CARVE,
    CarveParams,
    CarveResult,
    ChannelField,
    L1Mosaic,
    carve_tile,
    channel_field,
    containment_violations,
)
from .io import load_dem, save_map, stitch_tile_cache

__all__ = [
    "L0",
    "L0Cache",
    "L0Geometry",
    "L0Mosaic",
    "L0Params",
    "L0RegionId",
    "L0Solution",
    "inflow_edges",
    "solve_region",
    "L1",
    "L1Cache",
    "L1Geometry",
    "L1Params",
    "L1Solution",
    "L1TileId",
    "channel_width_m",
    "solve_tile",
    "CARVE",
    "CarveParams",
    "CarveResult",
    "ChannelField",
    "L1Mosaic",
    "carve_tile",
    "channel_field",
    "containment_violations",
    "cap_basins",
    "fill_depressions",
    "resolve_flats",
    "d8_receivers",
    "flow_accumulation",
    "topological_levels",
    "water_depth",
    "WaterMap",
    "load_dem",
    "save_map",
    "stitch_tile_cache",
]
