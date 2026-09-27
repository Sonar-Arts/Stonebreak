"""DEM hydrology primitives: depression fill and D8 flow accumulation.

Offline only: the training-data build (`terrain_slm/data/build.py`) derives its drainage
targets with these. The running service never imports them (rivers come from the model).

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
