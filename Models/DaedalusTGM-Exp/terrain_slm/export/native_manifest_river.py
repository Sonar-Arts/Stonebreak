"""Manifest section of stages/river: the river pipeline (river/*: native stages + block stages), the swamp flattening
of river_field (world/swamp_relief.py upsample / flatten) and the learned bank model's weights.

Every knob is read from the LIVE Python objects (gen.river_cfg, the pipeline's stage instances, the river modules'
constants), never copied. The bank model (`gen.banks`, river/banks.py BankNet) ships as raw fp32 tensors (base64) in
"river_bank": the C++ runs its own fp32 forward (torch's fp32 conv runs TF32 on cuDNN, whose summation order is not
reproducible, so that one network is close, not bitwise; see tests/test_native_river.py).
"""
from __future__ import annotations

import base64
import math

#: The stage lists the C++ implements (river/pipeline.default_pipeline), checked at manifest build and in C++.
NATIVE_STAGES = ("centrelines", "channel", "surface", "banks", "guard", "bed", "contain", "monotone_top")
BLOCK_STAGES = ("quantize", "flow", "flatten", "monotone_blocks", "flatten", "monotone_blocks", "block_contain",
                "undercuts", "overhangs")


def _d(x):
    """The `d` the Python passes to RiverPipeline.columns: an int at 1:4 / 1:2 (whole pixels per block), 0.5 at 1:1."""
    x = float(x)
    return int(x) if x == int(x) else x


def _columns(gen, i, f, t):
    field, ctx = gen.river_field(*i)
    return gen.river.columns(field, ctx, _d(f[0]), t[0] if t else None)


def _shelf(gen, i, f, t):
    """generator2.shelf(heights[, i1, j1, ppc]) (the cells stage's adapter has the same contract)."""
    return gen.shelf(t[0], *i)


PY_CALLS = {
    "river.columns": _columns,
    "river.bank_net": lambda gen, i, f, t: gen.banks(t[0].float()),
    "shelf": _shelf,
}


def contribute(gen, m: dict) -> None:
    from terrain_slm.export.native_manifest import f32, need_taps
    from terrain_slm.river import banks as RB
    from terrain_slm.river import blocks as RK
    from terrain_slm.river import contain as RC
    from terrain_slm.river import pipeline as RP

    pipe = gen.river
    names = tuple(s.name for s in pipe.native)
    bnames = tuple(s.name for s in pipe.blocks)
    if names != NATIVE_STAGES or bnames != BLOCK_STAGES:
        raise ValueError(f"river pipeline {pipe.describe()} is not the one stages/river implements")
    st = {s.name: s for s in pipe.native}
    learned = isinstance(st["banks"], RB.LearnedBanks)
    c = m["constants"]
    cfg = gen.river_cfg
    for k, v in vars(cfg).items():                 # RiverConfig: Python doubles / ints, as the stages use them
        c[f"RIVER_CFG_{k}"] = v
    c["RIVER_HALO_PX"] = RP.HALO_PX
    c["RIVER_CELL_HALO"] = RP.CELL_HALO
    c["RIVER_BLOCK_MARGIN"] = RP.BLOCK_MARGIN
    c["RIVER_HYSTERESIS"] = float(st["centrelines"].hysteresis)
    c["RIVER_REACH_PX"] = int(st["centrelines"].reach_px)
    c["RIVER_SPUR_PX"] = int(st["centrelines"].spur_px)
    c["RIVER_SURFACE_MONO_ITERS"] = int(st["surface"].monotone_iters)
    c["RIVER_TOP_MONO_ITERS"] = int(st["monotone_top"].iters)
    mb = [s for s in pipe.blocks if s.name == "monotone_blocks"]
    if len({s.iters for s in mb}) != 1:
        raise ValueError("MonotoneBlocks instances with different iteration caps")
    c["RIVER_BLOCK_MONO_ITERS"] = int(mb[0].iters)
    c["RIVER_FLOW_SMOOTH_BLOCKS"] = float(RK.FlowOctants.SMOOTH_BLOCKS)
    c["RIVER_FLOW_CONSENSUS"] = int(RK.FlowOctants.CONSENSUS)
    c["RIVER_MIN_WATER_M"] = float(RC.MIN_WATER_M)
    c["RIVER_REL_SCALE_BLOCKS"] = float(RB.REL_SCALE_BLOCKS)
    c["RIVER_SD_CAP_PX"] = int(RB.SD_CAP_PX)
    c["RIVER_FILL_SIGMAS"] = [float(s) for s in RB.FILL_SIGMAS]
    c["RIVER_GUARD_MARGIN_BLOCKS"] = float(RB.GUARD_MARGIN_BLOCKS)
    c["RIVER_LEVEE_PX"] = int(RB.LeveeBanks.LEVEE_PX)
    c["RIVER_LEARNED_BANKS"] = bool(learned)
    c["RIVER_UNDERCUT_NOISE_BLOCKS"] = float(RK.UNDERCUT_NOISE_BLOCKS)
    c["RIVER_UNDERCUT_STREAM"] = int(RK.UNDERCUT_STREAM)
    # D8 class -> octant (FlowOctants' table, class 8 = terminal -> NO_FLOW), and octant k -> (di, dj) of the block
    # stages (int(round(cos)), int(round(sin)) of k pi / 4, Python doubles).
    c["RIVER_D8_OCTANT"] = [round(math.atan2(dc, drw) / (math.pi / 4)) % 8 for drw, dc in RK.D8_OFFSETS] + [RK.NO_FLOW]
    c["RIVER_OCT_DI"] = [int(round(math.cos(k * math.pi / 4))) for k in range(8)]
    c["RIVER_OCT_DJ"] = [int(round(math.sin(k * math.pi / 4))) for k in range(8)]
    c["RIVER_PI"] = math.pi
    # Blur sigmas: the fixed ones, and every pixels-per-block the worlds use (0.5 / 1 / 2 / 4) for the scaled ones.
    sig = {float(cfg.ridge_smooth_px), float(cfg.surface_sigma_px), float(RK.FlowOctants.SMOOTH_BLOCKS)}
    sig |= {float(s) for s in RB.FILL_SIGMAS}
    for ppb in (0.25, 0.5, 1.0, 2.0, 4.0):
        sig.add(cfg.bank_band_blocks * ppb)
    for s in sorted(sig):
        need_taps(m, s)
    if learned:
        m["river_bank"] = _bank(st["banks"].model)


def _bank(model) -> dict:
    """BankNet config + its tensors (fp32, little endian, base64)."""
    out = {"channels": [int(x) for x in model.cfg.channels], "blocks": int(model.cfg.blocks), "params": {}}
    for name, t in model.state_dict().items():
        a = t.detach().float().contiguous().cpu().numpy().astype("<f4")
        out["params"][name] = {"shape": list(a.shape), "b64": base64.b64encode(a.tobytes()).decode()}
    return out
