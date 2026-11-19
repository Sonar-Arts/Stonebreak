"""Manifest section + Python adapters of stages/cells (the generator's cell-grid methods: relief windows / trend,
planner windows / cells, routed drainage, cells / overview_cells / swamp_relief_cells, generator2._shelf_remap).

Every constant is read from the live Python modules and the generator instance (relief gain / preblur, hill transfer,
sea detail, cache capacities): a generator knob changed after the native generator was built (use_relief_gain & co.)
needs a new manifest, like the Python generator clears its caches."""
from __future__ import annotations


def _np(x):
    import numpy as np
    import torch
    return torch.from_numpy(np.ascontiguousarray(x))


#: Methods whose Python results are numpy (the routed drainage's host arrays) or that are module functions.
PY_CALLS = {
    "_route_window": lambda gen, i, f, t: tuple(_np(x) for x in gen._route_window(*i)),
    "_stitched_route": lambda gen, i, f, t: tuple(_np(x) for x in gen._stitched_route(*i)),
    "G2._shelf_remap": lambda gen, i, f, t: _shelf_remap(t[0]),
    "SR.cell_fields": lambda gen, i, f, t: _cell_fields(t[0], t[1], t[2], i[0], i[1], i[2]),
    "B.swamp_candidates": lambda gen, i, f, t: _swamp_candidates(t[0], t[1], t[2], i[0], i[1], i[2]),
    # FX3 coastal shelf (generator2): shelf(heights[, i1, j1, ppc]); sea fields with the generator's device
    "shelf": lambda gen, i, f, t: gen.shelf(t[0], *i),
    "sea_fields_cells": lambda gen, i, f, t: tuple(x.contiguous() for x in gen.sea_fields_cells(*i, gen.device)),
    "sea_fields_px": lambda gen, i, f, t: tuple(x.contiguous() for x in gen.sea_fields_px(*i, gen.device)),
    # controls2.continent2 over cells [ci0, ci0+hc) x [cj0, cj0+wc) (the grids sea_fields_cells builds) -> (c2, land)
    "C2.continent2": lambda gen, i, f, t: _continent2(gen, *i),
}


def _continent2(gen, ci0, cj0, hc, wc):
    import torch
    from terrain_slm.world import controls2 as C2
    ci = torch.arange(ci0, ci0 + hc, device=gen.device, dtype=torch.float32).view(-1, 1).expand(hc, wc)
    cj = torch.arange(cj0, cj0 + wc, device=gen.device, dtype=torch.float32).view(1, -1).expand(hc, wc)
    return C2.continent2(ci, cj, seed=gen.seed)


def _shelf_remap(h):
    from terrain_slm.world import generator2 as G2
    return G2._shelf_remap(h)


def _cell_fields(height, regional, climate, ci0, cj0, bpc):
    from terrain_slm.world import swamp_relief as SR
    return SR.cell_fields(height, regional, climate, ci0, cj0, bpc)


def _swamp_candidates(elev, regional, climate, i0, j0, step):
    from terrain_slm import biomes as B
    return B.swamp_candidates(elev, regional, climate, i0, j0, step=step)


def _planner_fade(gen):
    """generator._planner_cells' 1-D sin^2 profile (w2 = w1[:, None] * w1[None, :]), on the generator's device."""
    import math

    import torch
    from terrain_slm.world import generator as G
    u = torch.arange(G.WIN, device=gen.device, dtype=torch.float32)
    w1 = torch.sin(math.pi * (u + 0.5) / G.WIN) ** 2
    return w1


def _relief_fade(gen):
    """generator._fade(RELIEF_EXT, RELIEF_APRON)'s 1-D profile: the row of w2 through a weight-1 cell (exact)."""
    import torch
    from terrain_slm.world import generator as G
    w2 = gen._fade(G.RELIEF_EXT, G.RELIEF_APRON, gen.device)
    r = int(torch.argmax(torch.diagonal(w2)))
    if float(w2[r, r]) != 1.0:
        raise RuntimeError("relief fade has no full-weight cell")
    w1 = w2[r].clone()
    if not torch.equal(w1[:, None] * w1[None, :], w2):
        raise RuntimeError("relief fade is not an outer product")
    return w1


def contribute(gen, m: dict) -> None:
    from terrain_slm.data import descriptors as D  # noqa: F401
    from terrain_slm.data.hydrology import fill as FILL
    from terrain_slm.export.native_manifest import f32, need_taps
    from terrain_slm.models import hydro as HY
    from terrain_slm.models import planner as P
    from terrain_slm.models import relief as RL
    from terrain_slm.world import controls2 as C2
    from terrain_slm.world import generator as G
    from terrain_slm.world import generator2 as G2
    from terrain_slm.world import swamp_relief as SR

    c = m["constants"]
    is2 = isinstance(gen, G2.WorldGenerator2)
    # cache capacities (Python's total: GPU + host share)
    c["CELLS_RELIEF_CAP"] = gen._relief_windows.cap
    c["CELLS_WINDOWS_CAP"] = gen._windows.cap
    c["CELLS_DRAINAGE_IN_CAP"] = gen._drainage_in.cap
    c["CELLS_ROUTE_CAP"] = gen._route_windows.cap
    c["CELLS_HYDRO_CAP"] = gen._hydro_windows.cap
    # relief windows
    c["CELLS_HAS_RELIEF"] = int(gen.relief is not None)
    c["CELLS_RELIEF_AMP_NORM"] = int(gen.relief is not None and bool(getattr(gen.relief.cfg, "amp_norm", False)))
    c["CELLS_HILL_RELIEF"] = float(getattr(gen, "hill_relief", 0.0))
    c["CELLS_RELIEF_PREBLUR"] = float(gen.relief_preblur)
    need_taps(m, gen.relief_preblur)
    c["RELIEF_NOISE_STREAM"] = G.RELIEF_NOISE_STREAM
    c["CELLS_RELIEF_SCALE_M"] = f32(RL.RELIEF_SCALE_M)
    c["CELLS_RELIEF_SLOPE_SCALE_M"] = f32(RL.SLOPE_SCALE_M)
    c["CELLS_RELIEF_GAIN"] = f32(gen.relief_gain)
    c["CELLS_RELIEF_FLOOR_M"] = f32(G.RELIEF_FLOOR_M)
    c["CELLS_RELIEF_FADE"] = [float(v) for v in _relief_fade(gen).cpu().tolist()]
    c["CELLS_LANDFORM_GAIN"] = float(C2.LANDFORM_GAIN) if is2 else 0.0
    # planner
    c["CELLS_HEIGHT_SCALE_M"] = f32(P.HEIGHT_SCALE_M)
    c["CELLS_WILD_OFFSET"] = f32(P.WILD_NORM[0])
    c["CELLS_WILD_SCALE"] = f32(P.WILD_NORM[1])
    c["CELLS_LOGACC_SCALE"] = f32(P.LOGACC_SCALE)
    c["CELLS_N_IN"] = P.N_IN
    c["CELLS_N_OUT"] = P.N_OUT
    for name in ("IN_TREND", "IN_T0", "IN_TSEASON", "IN_PRECIP", "IN_PCV", "IN_WILD", "IN_HAS_TREND",
                 "IN_HAS_CLIMATE", "IN_HAS_WILD"):
        c["CELLS_" + name] = getattr(P, name)
    if tuple(P.CLIMATE_NAMES) != ("t0", "tseason", "precip", "pcv"):
        raise RuntimeError(f"planner climate inputs changed: {P.CLIMATE_NAMES}")
    c["CELLS_DESC_MEAN"] = [float(v) for v in gen.desc_mean.float().cpu().tolist()]
    c["CELLS_DESC_STD"] = [float(v) for v in gen.desc_std.float().cpu().tolist()]
    c["CELLS_PATCH_BLUR_CELLS"] = float(G.PATCH_BLUR_CELLS)
    need_taps(m, G.PATCH_BLUR_CELLS)
    c["CELLS_HILL_TRANSFER"] = f32(getattr(gen, "hill_transfer", 0.0)) if is2 else 0.0
    c["CELLS_HILL_TRANSFER_SIGMA_CELLS"] = float(G2.HILL_TRANSFER_SIGMA_CELLS)
    need_taps(m, G2.HILL_TRANSFER_SIGMA_CELLS)
    c["CELLS_SEA_DETAIL"] = int(bool(getattr(gen, "sea_detail", False))) if is2 else 0
    c["CELLS_SEA_DETAIL_SIGMA_CELLS"] = float(G2.SEA_DETAIL_SIGMA_CELLS)
    need_taps(m, G2.SEA_DETAIL_SIGMA_CELLS)
    c["CELLS_SEA_DETAIL_MAX_M"] = f32(G2.SEA_DETAIL_MAX_M)
    c["CELLS_HAS_DESCGIT"] = int(getattr(gen, "descgit", None) is not None)   # v4 sampled descriptors: not ported
    c["CELLS_PLANNER_FADE"] = [float(v) for v in _planner_fade(gen).cpu().tolist()]
    # drainage
    c["CELLS_ROUTED_DRAINAGE"] = int(bool(gen.cfg.routed_drainage))
    c["CELLS_PARALLEL_HOST_WORK"] = int(bool(G.PARALLEL_HOST_WORK))
    c["CELLS_HAS_HYDRO"] = int(gen.hydro is not None)
    c["CELLS_ROUTE_NOISE_M"] = float(G.ROUTE_NOISE_M)
    c["CELLS_ROUTE_NOISE_SIGMA"] = float(G.ROUTE_NOISE_SIGMA)
    c["CELLS_ROUTE_NOISE_STREAM"] = G.ROUTE_NOISE_STREAM
    need_taps(m, G.ROUTE_NOISE_SIGMA)
    c["CELLS_DEFAULT_EPSILON"] = float(FILL.DEFAULT_EPSILON)
    c["CELLS_RIVER_MIN_UPSLOPE"] = f32(P.RIVER_MIN_UPSLOPE_CELLS)
    c["CELLS_HYDRO_SLOPE_SCALE_M"] = f32(HY.SLOPE_SCALE_M)
    c["CELLS_HYDRO_N_OUT"] = HY.N_OUT
    c["CELLS_HYDRO_OUT_LOGACC"] = HY.OUT_LOGACC
    c["CELLS_HYDRO_OUT_RIVER"] = HY.OUT_RIVER
    c["CELLS_HYDRO_OUT_D8"] = HY.OUT_D8.start
    c["CELLS_HYDRO_N_D8"] = HY.OUT_D8.stop - HY.OUT_D8.start
    # generator2._shelf_remap defaults
    c["CELLS_SEA_SHELF_SLOPE"] = float(G2.SEA_SHELF_SLOPE)
    c["CELLS_SEA_SHELF_DEPTH_M"] = float(G2.SEA_SHELF_DEPTH_M)
    c["CELLS_SEA_SHELF_KEEP"] = float(G2.SEA_SHELF_KEEP)
    c["CELLS_SEA_SHELF_KEEP_M"] = float(G2.SEA_SHELF_KEEP_M)
    # FX3: distance-aware coastal shelf + lake-bed cap (generator2.shelf / sea_fields_cells / sea_fields_px)
    from terrain_slm.world import ocean as O
    c["CELLS_SEA_PROFILE"] = int(bool(getattr(G2, "SEA_PROFILE", False)))
    c["CELLS_SEA_PROFILE_VERSION"] = int(getattr(G2, "SEA_PROFILE_VERSION", 0))
    if hasattr(G2, "SEA_PROFILE_M0"):
        c["CELLS_SEA_PROFILE_M0"] = f32(G2.SEA_PROFILE_M0)
        c["CELLS_SEA_PROFILE_SLOPE"] = f32(G2.SEA_PROFILE_SLOPE)
        c["CELLS_SEA_PROFILE_MAX_CELLS"] = int(G2.SEA_PROFILE_MAX_CELLS)
        c["CELLS_SEA_PROFILE_COMPRESS"] = float(G2.SEA_PROFILE_COMPRESS)
        c["CELLS_SEA_PROFILE_RETURN_M"] = float(G2.SEA_PROFILE_RETURN_M)
        c["CELLS_SEA_PROFILE_KEEP_M"] = float(G2.SEA_PROFILE_KEEP_M)
        c["CELLS_LAKE_DEPTH_M0"] = f32(G2.LAKE_DEPTH_M0)
        c["CELLS_LAKE_DEPTH_SLOPE"] = f32(G2.LAKE_DEPTH_SLOPE)
        c["CELLS_LAKE_SEED_MIN_CELLS"] = int(G2.LAKE_SEED_MIN_CELLS)
        c["CELLS_SEA_CELL_M"] = f32(G2.CP * G2.NATIVE_PX_M)
    c["CELLS_SEA_SEED_TREND_M"] = f32(G2.SEA_SEED_TREND_M)
    c["CELLS_SEA_CELL_MARGIN"] = int(O.SEA_CELL_MARGIN)
    c["CELLS_SEA_GROW_CELLS"] = int(O.SEA_GROW_CELLS)
    c["CELLS_SEA_COAST_CELLS"] = int(O.SEA_COAST_CELLS)
    c["CELLS_SEA_LAND"] = f32(O.SEA_LAND)
    # swamp relief (world/swamp_relief.py)
    c["CELLS_SWAMP_MARGIN_CELLS"] = SR.MARGIN_CELLS
    c["CELLS_SWAMP_WEIGHT_SIGMA"] = float(SR.SWAMP_WEIGHT_SIGMA_CELLS)
    c["CELLS_SWAMP_EDGE_LO"] = float(SR.SWAMP_WEIGHT_EDGES[0])
    c["CELLS_SWAMP_EDGE_HI"] = float(SR.SWAMP_WEIGHT_EDGES[1])
    c["CELLS_SWAMP_TABLE_SIGMA"] = float(SR.SWAMP_TABLE_SIGMA_CELLS)
    c["CELLS_SWAMP_BASE_SIGMA"] = float(SR.SWAMP_BASE_SIGMA_CELLS)
    c["CELLS_SWAMP_SLOPE_KEEP"] = f32(SR.SWAMP_SLOPE_KEEP)
    c["CELLS_SWAMP_STAIR_M"] = float(SR.SWAMP_STAIR_M)
    c["CELLS_SWAMP_STAIR_PASSES"] = int(SR.SWAMP_STAIR_PASSES)
    c["CELLS_SWAMP_TABLE_FLOOR_M"] = f32(SR.SWAMP_TABLE_FLOOR_M)
    for s in (SR.SWAMP_WEIGHT_SIGMA_CELLS, SR.SWAMP_TABLE_SIGMA_CELLS, SR.SWAMP_BASE_SIGMA_CELLS,
              G.LAPSE_REGIONAL_SIGMA_CELLS):
        need_taps(m, s)
    c.setdefault("LAPSE_C_PER_M", f32(G.LAPSE_C_PER_M))
    c.setdefault("LAPSE_REGIONAL_SIGMA_CELLS", float(G.LAPSE_REGIONAL_SIGMA_CELLS))
