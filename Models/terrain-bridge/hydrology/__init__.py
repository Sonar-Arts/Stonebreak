"""DEM hydrology primitives: depression fill and D8 flow accumulation.

The running bridge does not import this package -- rivers come from the terrain model
with each tile (`bridge/water.py`'s `UpstreamWater`). What remains is used offline by
DaedalusTGM-Exp's training-data build (`terrain_slm/data/build.py`, drainage targets),
which puts this directory on `sys.path`.

Convention: elevation <= 0 or NaN is ocean.
"""
from .fill import cap_basins, fill_depressions, ocean_mask
from .flow import d8_receivers, flow_accumulation, topological_levels

__all__ = [
    "cap_basins",
    "fill_depressions",
    "ocean_mask",
    "d8_receivers",
    "flow_accumulation",
    "topological_levels",
]
