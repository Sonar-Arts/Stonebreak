"""Manifest section of stages/controls: the EXP-2 layout (controls2 + v4's procedural_controls kernels), the climate
node's window inputs, climate cells, controls(), continent_land_cells, climate_native, detail_gates, red deserts.

Every table is read from the live Python modules (the kernel launchers' own table builders where they exist), so a
retuned constant reaches C++ through this manifest, never through a copied number. Seed-dependent entries (hash bases,
the belts' pole angle and the ice-flow angle) are computed in C++ per seed; everything else is exported here.
"""
from __future__ import annotations

import math


def _rows_streams(rows) -> tuple[list[int], list[float], dict]:
    """(per-octave stream s = stream * 16 + o, per-octave fp32 1 / period (the launcher's _inv), first row by name)."""
    from terrain_slm.kernels import cuda_controls2 as KC2
    streams, invp, first = [], [], {}
    for name, period, stream, octaves in rows:
        first[name] = len(streams)
        for o in range(octaves):
            streams.append(stream * 16 + o)
            invp.append(KC2._inv(period / 2 ** o))
    return streams, invp, first


def contribute(gen, m: dict) -> None:
    import torch

    from terrain_slm.export.native_manifest import f32, need_taps
    from terrain_slm.kernels import cuda_controls as KC
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.models import climatenet as CN
    from terrain_slm.models import detail as DT
    from terrain_slm.models import detail2 as D2
    from terrain_slm.world import controls2 as C2
    from terrain_slm.world import generator as G
    from terrain_slm.world import generator2 as G2

    c = m["constants"]
    t = m.setdefault("tables", {})

    # ---------------------------------------------------------------- v4 procedural_controls (csrc/controls.cu)
    KC._check_constants(G)
    v4s, v4i = [], []
    v4rows = {}
    for name, period, stream, octaves in KC._FBM:
        v4rows[name] = len(v4s)
        for o in range(octaves):
            v4s.append(stream * 16 + o)
            v4i.append(KC._inv(period / 2 ** o))
    t["pc_streams"] = v4s
    t["pc_invp"] = v4i
    t["pc_rows"] = [v4rows[n] for n in ("continent", "warp_i", "warp_j", "range", "spur", "along", "hills", "hilly",
                                        "climate0")]
    t["pc_k"] = list(KC._CONSTANTS)
    # the archetype table exactly as the launcher builds it (precip column through CUDA logf on the generator's device)
    arch = torch.tensor([a[1:] for a in G.CLIMATE_ARCHETYPES], dtype=torch.float32, device=m["_device"])
    arch[:, 2] = torch.log(arch[:, 2])
    t["pc_arch"] = arch.flatten().cpu().tolist()

    # ---------------------------------------------------------------- EXP-2 layout (csrc/controls2.cu)
    KC2._check_constants(C2, G, D2)
    knames, rpos = KC2._kernel_enums()
    s2, i2, first = _rows_streams(KC2._rows(C2, G))
    for name, row in first.items():
        if rpos.get(name) != row:
            raise RuntimeError(f"controls2 row {name}: kernel {rpos.get(name)}, table {row}")
    t["c2_streams"] = s2
    t["c2_invp"] = i2
    sv, iv, firstv = [], [], {}
    for name, period, stream, octaves in KC2._rows_v4(G):
        firstv[name] = len(sv)
        for o in range(octaves):
            sv.append(stream * 16 + o)
            iv.append(KC2._inv(period / 2 ** o))
    t["c2_v4_streams"] = sv
    t["c2_v4_invp"] = iv
    kv4 = KC2._constants_v4(G)
    t["c2_kv4"] = list(kv4.values())
    seed = int(gen.seed)
    for layout in KC2.LAYOUTS:
        kv = KC2._constants(C2, G, D2, layout, seed)
        if list(kv) != knames:
            raise RuntimeError("controls2 K table out of sync with csrc/controls2.cu")
        t[f"c2_k_{layout}"] = list(kv.values())
    for name in ("COS_TH", "SIN_TH", "COS_FLOW", "SIN_FLOW"):
        c[f"C2_K_{name}"] = knames.index(name)
    c["C2_HOT_STREAM"] = 36                                         # cuda_controls2._tables: hot_base
    c["C2_SEED_ANGLE_SALT"] = 47                                    # controls2._seed_angle
    c["C2_FLOW_ANGLE_SALT"] = 53                                    # controls2._flow_angle
    t["c2_layouts"] = list(KC2.LAYOUTS)
    c["C2_N_FIELDS"] = len(KC2._FIELDS)
    c["C2_N_GATES"] = KC2._N_GATES
    t["c2_fields"] = list(KC2._FIELDS)
    c["C2_INV_LAND"] = KC2._inv(0.2)                               # G._continent: (continent + 0.35) / 0.2
    c["C2_C035"] = f32(0.35)
    c["SEA_SEED_TREND_M"] = f32(G2.SEA_SEED_TREND_M)

    # ---------------------------------------------------------------- climate node windows (generator2)
    has_climate = getattr(gen, "climate_net", None) is not None
    c["HAS_CLIMATE"] = int(has_climate)
    c["CANONICAL_CLIMATE"] = int(bool(G2.CANONICAL_CLIMATE))
    c["CLIMATE_GRID_CELLS"] = float(G2.CLIMATE_GRID_CELLS)
    c["CLIMATE_GRID_CELLS_F"] = f32(G2.CLIMATE_GRID_CELLS)
    c["CLIMATE_WIN"], c["CLIMATE_CORE"], c["CLIMATE_APRON"] = G2.CLIMATE_WIN, G2.CLIMATE_CORE, G2.CLIMATE_APRON
    c["CLIMATE_SUB"] = G2.CLIMATE_SUB
    c["CLIMATE_FETCH_CELLS"] = G2.CLIMATE_FETCH_CELLS
    c["GRID_KM"] = float(G2.GRID_KM)
    c["INV_GRID_KM"] = KC2._inv(G2.GRID_KM)
    c["WIND_SCALE"] = f32(G2.WIND_SCALE)
    c["LAND_WIND0"], c["LAND_WIND1"] = f32(G2.LAND_WIND[0]), f32(G2.LAND_WIND[1])
    c["CALM_INV"] = KC2._inv(CN.CALM_MS)
    c["UPWIND_EPS"] = f32(1e-6)
    t["upwind_near_km"] = [f32(d) for d in CN.UPWIND_NEAR_KM]
    t["upwind_far_km"] = [f32(d) for d in CN.UPWIND_FAR_KM]
    c["UPWIND_INV_NEAR"] = KC2._inv(len(CN.UPWIND_NEAR_KM))
    c["UPWIND_INV_FAR"] = KC2._inv(len(CN.UPWIND_FAR_KM))
    forcing = getattr(gen, "climate_forcing", (0.0, 0.0))
    c["CLIMATE_FORCING0"] = f32(forcing[0] / CN.FORCING_DT_SCALE)
    c["CLIMATE_FORCING1"] = f32(forcing[1] / CN.FORCING_AGE_SCALE)
    c["CLIMATE_N_IN_MAX"] = len(CN.IN_NAMES)
    c["CLIMATE_N_OUT"] = len(CN.OUT_NAMES)
    t["climate_out_mean"] = [f32(CN.OUT_NORM[k][0]) for k in CN.OUT_NAMES]
    t["climate_out_std"] = [f32(CN.OUT_NORM[k][1]) for k in CN.OUT_NAMES]
    # the three continentality blurs of _climate_window (km) and their lattice sigmas
    kms = (250.0, 1000.0, 2500.0)
    t["continentality_km"] = list(kms)
    for km in kms:
        sigma = km / G2.GRID_KM
        step = max(1, int(sigma // 3))
        need_taps(m, sigma / step)

    # ---------------------------------------------------------------- controls(): aeolian gate + expert pruning
    c["AEOLIAN_GATE"] = D2.STYLES.index("aeolian")
    c["AEOLIAN_A0"], c["AEOLIAN_INV_A"] = f32(0.2), KC2._inv(0.15)      # controls2.aeolian_gate
    c["AEOLIAN_R0"], c["AEOLIAN_INV_R"] = f32(400.0), KC2._inv(400.0)
    en = getattr(gen, "experts_enabled", None)
    t["experts_enabled"] = [1] * len(D2.STYLES) if en is None else [int(s in en) for s in D2.STYLES]
    c["EXPERTS_PRUNE"] = int(en is not None)

    # ---------------------------------------------------------------- detail_gates
    det = getattr(gen, "detail", None)
    c["DETAIL_HAS_EXPERTS"] = int(isinstance(det, D2.Detail2) and len(det.adapters) > 0)
    c["SAMPLES_PER_CELL"] = DT.SAMPLES_PER_CELL

    # ---------------------------------------------------------------- red deserts (generator.red_desert_cells)
    c["RED_DESERT_LATTICE"] = G.RED_DESERT_LATTICE
    c["RED_DESERT_MAX_STEPS"] = G.RED_DESERT_MAX_STEPS
    c["RED_DESERT_OCTAVES"] = G.RED_DESERT_OCTAVES
    c["RED_DESERT_STREAM"] = G.RED_DESERT_STREAM
    c["RED_DESERT_PERIOD_CELLS"] = float(G.RED_DESERT_PERIOD_CELLS)
    c["RED_DESERT_THRESHOLD"] = f32(G.RED_DESERT_THRESHOLD)
    c["RED_DESERT_SOFTNESS"] = f32(G.RED_DESERT_SOFTNESS)
    c["RED_DESERT_WARP_CELLS"] = f32(G.RED_DESERT_WARP_CELLS)
    c["RED_DESERT_WARP_PERIOD"] = float(G.RED_DESERT_WARP_PERIOD)
    c["RED_DESERT_JITTER"] = f32(G.RED_DESERT_JITTER)
    c["RED_DESERT_JITTER_PERIOD"] = float(G.RED_DESERT_JITTER_PERIOD)
    c["CLIMATE_ZONE_CELLS"] = float(G.CLIMATE_ZONE_CELLS)
    c["N_ARCHETYPES"] = len(G.CLIMATE_ARCHETYPES)
    # _dryness: log of the archetypes' precipitation (torch.log on the device, like the Python)
    lp = torch.log(torch.tensor([a[3] for a in G.CLIMATE_ARCHETYPES], dtype=torch.float32, device=m["_device"]))
    t["red_logp"] = lp.cpu().tolist()


PY_CALLS = {
    "continent_land_cells": lambda g, i, f, t: g.continent_land_cells(*i, g.device),
    "G.red_desert_cells": lambda g, i, f, t: __import__("terrain_slm.world.generator", fromlist=["x"]).red_desert_cells(
        t[0], t[1], g.seed),
}
