"""Manifest section + Python adapters of stages/detail (the detail sampler windows and their cross-fade, the native /
fine pixels with SR-30 and SR-15, TileBuilder._carve + world/carve_node.py). Every constant is read from the live
Python modules at build time; numbers torch uses as fp32 go through `f32`.

Literals the Python code has inline (not module constants) are mirrored here with the line they come from:
  generator._detail_window      river blur sigma 0.7 (D.blur(dr["river"], 0.7))
  generator2._sr_window / _sr15 m = 4 samples of bicubic context; px 30.0 / 15.0 m (SRM.build_cond(up, px))
  models/sr.build_cond          avg_pool2d(pad replicate 15, kernel 31), slope pad 1
"""
from __future__ import annotations

import math


def _sr_fade(ext: int, apron: int, device) -> list[float]:
    """generator2._sr_blend's 1-D fade (constant per 2x2 block), computed by torch exactly as there."""
    import torch
    ramp = 2 * apron
    t = (torch.arange(ramp // 2, device=device, dtype=torch.float32) + 0.5) / (ramp // 2)
    w1 = torch.ones(ext // 2, device=device)
    w1[: ramp // 2] = torch.sin(0.5 * math.pi * t) ** 2
    w1[-(ramp // 2):] = torch.cos(0.5 * math.pi * t) ** 2
    return w1.repeat_interleave(2).cpu().tolist()


def _fade1(ext: int, apron: int, device) -> list[float]:
    """generator._fade's 1-D factor (the 2-D fade is its outer product)."""
    import torch
    ramp = 2 * apron
    t = (torch.arange(ramp, device=device, dtype=torch.float32) + 0.5) / ramp
    wv = torch.ones(ext, device=device)
    wv[:ramp] = torch.sin(0.5 * math.pi * t) ** 2
    wv[-ramp:] = torch.cos(0.5 * math.pi * t) ** 2
    return wv.cpu().tolist()


def contribute(gen, m: dict) -> None:
    from terrain_slm.data import carve_labels as CL
    from terrain_slm.export.native_manifest import f32, need_taps
    from terrain_slm.models import detail as DT
    from terrain_slm.models import detail2 as D2
    from terrain_slm.models import planner as P
    from terrain_slm.models import sr as SRM
    from terrain_slm.world import carve_node as CN
    from terrain_slm.world import generator as G
    from terrain_slm.world import generator2 as G2

    dev = m["_device"]
    c = m["constants"]
    # ---- detail window (generator._detail_window, models/detail.py)
    c["dt.DS"] = DT.DS
    c["dt.SPC"] = DT.SAMPLES_PER_CELL
    c["dt.EXT"] = G.DETAIL_EXT
    c["dt.CORE"] = G.DETAIL_CORE
    c["dt.APRON"] = G.DETAIL_APRON
    c["dt.MARGIN_CELLS"] = G.DETAIL_MARGIN_CELLS
    c["dt.N_COND"] = DT.N_COND
    c["dt.STYLES"] = len(D2.STYLES)
    c["dt.RETRY_N"] = len(G.DETAIL_RETRY)
    for k, (sigma, clim) in enumerate(G.DETAIL_RETRY):
        c[f"dt.RETRY_SIGMA{k}"] = float(sigma)
        c[f"dt.RETRY_CLIMATE{k}"] = int(bool(clim))
        need_taps(m, sigma)
    c["dt.BASE_SIGMA"] = float(DT.BASE_SIGMA)
    need_taps(m, DT.BASE_SIGMA)
    c["dt.RIVER_SIGMA"] = 0.7
    need_taps(m, 0.7)
    c["dt.X_LIMIT"] = f32(G.DETAIL_X_LIMIT)
    c["dt.ASINH_M"] = f32(DT.ASINH_M)
    c["dt.NOISE_STREAM"] = G.DETAIL_NOISE_STREAM
    c["dt.HEIGHT_SCALE_M"] = f32(P.HEIGHT_SCALE_M)
    c["dt.SLOPE_SCALE_M"] = f32(DT.SLOPE_SCALE_M)
    c["dt.WILD_OFF"] = f32(P.WILD_NORM[0])
    c["dt.WILD_SCALE"] = f32(P.WILD_NORM[1])
    c["dt.LOGACC_SCALE"] = f32(P.LOGACC_SCALE)
    c["dt.STEPS"] = int(gen.cfg.detail_steps)
    try:
        from terrain_slm.export.cenda_backend import Phase2
        c["dt.UP_FMA"] = int(Phase2.DETAIL_UP_FMA)
    except ImportError:
        c["dt.UP_FMA"] = 1
    c["dt.HAS_DETAIL"] = int(gen.detail is not None)
    # generator2 lowland boost (the knob's value at build time; NativeGen state "lowland_boost" overrides it)
    c["dt.LOWLAND_BOOST"] = f32(getattr(gen, "lowland_boost", 0.0))
    c["dt.LOWLAND_SIGMA"] = float(G2.LOWLAND_SIGMA_SAMPLES)
    need_taps(m, G2.LOWLAND_SIGMA_SAMPLES)
    c["dt.LOWLAND_TOP0"] = f32(G2.LOWLAND_TOP_M[0])
    c["dt.LOWLAND_SPAN"] = f32(G2.LOWLAND_TOP_M[1] - G2.LOWLAND_TOP_M[0])
    c["dt.LOWLAND_D0"] = f32(G2.LOWLAND_D0_M)
    # ---- SR (generator2._sr_window / _sr15_window / _sr_blend, models/sr.py)
    c["dt.SR_CORE"] = G2.SR_CORE
    c["dt.SR_APRON"] = G2.SR_APRON
    c["dt.SR_CTX"] = 4
    c["dt.SR_GAIN"] = f32(G2.SR_GAIN)
    c["dt.SR_NOISE_STREAM"] = G2.SR_NOISE_STREAM
    c["dt.SR15_NOISE_STREAM"] = G2.SR15_NOISE_STREAM
    c["dt.SR15_GAIN"] = f32(getattr(gen, "sr15_gain", G2.SR15_GAIN))
    c["dt.SR15_GAIN_D"] = float(getattr(gen, "sr15_gain", G2.SR15_GAIN))    # Python double (look: gain - 1.0)
    c["dt.SR_ASINH_M"] = f32(SRM.ASINH_M)
    c["dt.SR_REL_M"] = f32(SRM.REL_M)
    c["dt.SR_POOL"] = 31
    c["dt.SR_N_COND"] = SRM.N_COND
    c["dt.HAS_SR30"] = int(getattr(gen, "sr30", None) is not None)
    c["dt.HAS_SR15"] = int(getattr(gen, "sr15", None) is not None)
    c["dt.SR15_NATIVE"] = int(bool(getattr(gen, "_sr15_native", False)))
    c["dt.BICUBIC_FMA"] = 2               # detail_ops.cu cubic_interp: torch's contraction (measured)
    # ---- FX4 block look (generator2: _hill_band in _detail_window, _sr_gain_look in the SR windows; mode =
    # NativeGen state "look_build"; gains from the generator instance, state "knobs" override)
    c["dt.LOOK_HILL_GAIN"] = f32(getattr(gen, "look_hill_gain", 0.0))
    c["dt.LOOK_HILL_S0"] = float(G2.LOOK_HILL_BAND[0])
    c["dt.LOOK_HILL_S1"] = float(G2.LOOK_HILL_BAND[1])
    c["dt.LOOK_HILL_SREG"] = float(G2.LOOK_HILL_REGIONAL_SAMPLES)
    for s_ in (G2.LOOK_HILL_BAND[0], G2.LOOK_HILL_BAND[1], G2.LOOK_HILL_REGIONAL_SAMPLES, G2.SR_LOOK_SIGMA_PX):
        need_taps(m, s_)
    c["dt.LOOK_HILL_TOP0"] = f32(G2.LOOK_HILL_TOP_M[0])
    c["dt.LOOK_HILL_TOP_SPAN"] = f32(G2.LOOK_HILL_TOP_M[1] - G2.LOOK_HILL_TOP_M[0])
    c["dt.LOOK_HILL_COAST0"] = f32(G2.LOOK_HILL_COAST_M[0])
    c["dt.LOOK_HILL_COAST_SPAN"] = f32(G2.LOOK_HILL_COAST_M[1] - G2.LOOK_HILL_COAST_M[0])
    c["dt.SR_LOOK_SIGMA"] = float(G2.SR_LOOK_SIGMA_PX)
    c["dt.SR_LOOK_K"] = f32(G2.SR_GAIN - 1.0)                 # (gain - 1.0) in Python double, then fp32
    # ---- carve (tiles.TileBuilder._carve, world/carve_node.py, data/carve_labels.py)
    c["dt.CARVE_MARGIN"] = CN.MARGIN_SAMPLES
    for s in CL.SCALES_M:
        c[f"dt.CARVE_K{int(s)}"] = max(3, int(round(s / CL.PX_M)) | 1)
    sig = CL.strike.__defaults__[0]
    c["dt.CARVE_STRIKE_SIGMA"] = float(sig)
    need_taps(m, sig)
    c["dt.CARVE_D0"] = f32(CN.DENSITY_VD600_M[0])
    c["dt.CARVE_DSPAN"] = f32(CN.DENSITY_VD600_M[1] - CN.DENSITY_VD600_M[0])
    c["dt.CARVE_SLOT"] = f32(CN.SLOT_VD150_M)
    c["dt.CARVE_ARID"] = f32(CN.ARID)
    c["dt.CARVE_GORGE"] = f32(CN.GORGE_VD2000_M)
    c["dt.CARVE_RIFT"] = f32(CN.RIFT_VD2000_M)
    c["dt.CARVE_RIFT_COH"] = f32(CN.RIFT_COHERENCE)
    c["dt.CARVE_CLIFF_R"] = CN.SEA_CLIFF_SAMPLES
    c["dt.CARVE_CLIFF_M"] = f32(CN.SEA_CLIFF_M)
    c["dt.CARVE_REACH"] = CN.SEA_STACK_REACH_SAMPLES
    c["dt.CARVE_CLIFF_DENS"] = f32(CN.SEA_CLIFF_DENSITY_M)
    c["dt.CARVE_HUMID"] = f32(CN.TOWER_HUMID)
    c["dt.CARVE_TOWER_R"] = CN.TOWER_RELIEF_SAMPLES
    c["dt.CARVE_TOWER_M"] = f32(CN.TOWER_RELIEF_M)
    from terrain_slm.world import controls2 as C2
    c["dt.KARST_V2"] = int(bool(getattr(C2, "KARST_V2", False)))   # tiles._carve: karst2 on sea-shelf (v2) builds
    c["dt.STYLE_FJORD"] = D2.STYLES.index("glacial_fjord")
    c["dt.STYLE_DROWNED"] = D2.STYLES.index("drowned_archipelago")
    # ---- tables
    t = m.setdefault("detail_tables", {})
    t["detail_fade"] = _fade1(G.DETAIL_EXT, G.DETAIL_APRON, dev)
    t["sr_fade"] = _sr_fade(G2.SR_CORE + 2 * G2.SR_APRON, G2.SR_APRON, dev)


def _sr_blend(gen, i, f, t):
    window = gen._sr15_window if i[4] else gen._sr_window
    return gen._sr_blend(i[0], i[1], i[2], i[3], window)


def _carve(gen, i, f, t):
    """TileBuilder._carve for the generator `gen` (it only reads the generator: no TileBuilder construction, which
    would set the generator's block scale). d: Python's int when integral (as TileBuilder.build passes it)."""
    from terrain_slm.world.tiles import TileBuilder
    tb = TileBuilder.__new__(TileBuilder)
    tb.gen = gen
    d = f[0]
    d = int(d) if d == int(d) else d
    return tb._carve(i[0], i[1], i[2], i[3], d)


def _failures(gen, i, f, t):
    import torch
    return torch.tensor([int(getattr(gen, "detail_failures", 0))], dtype=torch.int64)


PY_CALLS = {
    "_sr_blend": _sr_blend,            # (i1, j1, i2, j2, which): which 0 = _sr_window (SR-30), 1 = _sr15_window
    "TileBuilder._carve": _carve,      # ints (i1, j1, i2, j2), floats (d,)
    "_detail_failures": _failures,
}
