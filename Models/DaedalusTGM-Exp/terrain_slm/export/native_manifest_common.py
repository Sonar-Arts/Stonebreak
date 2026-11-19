"""Manifest section of stages/common (soft_cap_peaks, regional_elevation)."""
from __future__ import annotations


def contribute(gen, m: dict) -> None:
    from terrain_slm.export.native_manifest import f32, need_taps
    from terrain_slm.world import generator as G

    c = m["constants"]
    c["PEAK_KNEE_M"] = f32(G.PEAK_KNEE_M)
    c["PEAK_SPAN_M"] = f32(G.PEAK_SPAN_M)
    c["LAPSE_REGIONAL_SIGMA_CELLS"] = float(G.LAPSE_REGIONAL_SIGMA_CELLS)
    c["LAPSE_C_PER_M"] = f32(G.LAPSE_C_PER_M)
    need_taps(m, G.LAPSE_REGIONAL_SIGMA_CELLS)
