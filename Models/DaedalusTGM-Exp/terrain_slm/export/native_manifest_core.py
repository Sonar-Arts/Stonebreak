"""Manifest section of the core generator constants (window geometry shared by every stage)."""
from __future__ import annotations


def contribute(gen, m: dict) -> None:
    from terrain_slm.world import generator as G

    c = m["constants"]
    for name in ("CP", "RELIEF_EXT", "RELIEF_CORE", "RELIEF_APRON", "DETAIL_EXT", "DETAIL_CORE", "DETAIL_APRON",
                 "DETAIL_MARGIN_CELLS", "WIN", "STRIDE", "PLANNER_BATCH"):
        v = getattr(G, name, None)
        if v is not None:
            c[name] = v
