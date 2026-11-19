"""`controls2._exp2` (the EXP-2 world layout) as one CUDA kernel (csrc/controls2.cu), bit-identical to the torch version.

The torch version is ~20 hash-noise fields of ~50 tiny elementwise ops each plus 12 shifted continent evaluations:
~1,500 memory-bound kernels per call (~60-85 % of a tile's build time, even replayed from CUDA graphs). Everything in it
is pointwise in the cell coordinates, so one thread computes one cell end to end in registers and writes every field
once. v4's `procedural_controls` (its own native kernel) stays an input: `base`.

    c = controls2_native(base, ci0, cj0, hc, wc, seed, layout, device)    # == controls2._exp2(base, ci, cj, seed, layout)

Bit-exactness rules: see the header of csrc/controls2.cu. Every Python scalar the torch code combines with a tensor is
computed here with Python's own double arithmetic from the controls2 / generator constants and rounded to fp32 (or to
the fp32 reciprocal for `tensor / scalar`), in the K table; `_check_constants` fails loudly when a constant drifts from
the value the kernel was written for (the formula itself is pinned by tests/test_cuda_controls2.py).
"""
from __future__ import annotations

import ctypes
import math
import re

import numpy as np
import torch

from terrain_slm.kernels import native

_MASK32 = 0xFFFFFFFF
NROWS, NK = 72, 160
NROWS_V4, NKV4 = 16, 32
LAYOUTS = ("belts", "zones", "latitude")       # kernel layout ids 0, 1, 2

# Output channels in the kernel's O_* order; "gates" is the last 7.
_FIELDS = ("wild", "hilly", "trend_sea", "trend", "lat", "belt", "wind_u", "wind_v", "coast", "land", "trench", "ridge",
           "canyon", "karst", "landform", "karst2")
_N_GATES = 7

# fbm rows (name, period cells, stream, octaves) in the kernel's R_* order.
def _rows(C2, G) -> tuple:
    return (("CONT", 900.0, 1, 4), ("BASIN", C2.BASIN_PERIOD_CELLS, 44, 2), ("HILLY", G.HILL_PERIOD_CELLS, 9, 3),
            ("MARGIN", C2.TRENCH_PERIOD_CELLS, 39, 2), ("THETA", 2 * C2.TRENCH_PERIOD_CELLS, 43, 2),
            ("RIDGE", C2.RIDGE_PERIOD_CELLS, 40, 1), ("AHILLS", C2.HILLS_PERIOD_CELLS, 41, 3),
            ("CANYON", C2.CANYON_PERIOD_CELLS, 42, 3), ("WARP_BELT", C2.BELT_WARP_PERIOD_CELLS, 30, 2),
            ("WARP_LAT", 1200.0, 30, 2), ("ZONE", C2.ZONE_PERIOD_CELLS, 46, 3), ("WU", 800.0, 31, 2),
            ("WV", 800.0, 32, 2), ("COAST", C2.COAST_PERIOD_CELLS, 33, 3), ("ISL", C2.ISLAND_PERIOD_CELLS, 34, 3),
            ("SKER", C2.SKERRY_PERIOD_CELLS, 35, 3), ("JIT", C2.VOLCANO_RADIUS_CELLS, 37, 3), ("LAG", 400.0, 38, 2),
            ("KARST", C2.KARST_PERIOD_CELLS, 45, 2), ("LF", C2.LANDFORM_PERIOD_CELLS, 61, 3),
            ("S_ROLL", C2.LANDFORM_STYLE_PERIOD_CELLS, 62, 2), ("S_MOR", C2.LANDFORM_STYLE_PERIOD_CELLS, 63, 2),
            ("S_PLAT", C2.LANDFORM_STYLE_PERIOD_CELLS, 64, 2), ("ROLL_B", C2.ROLL_BROAD_PERIOD_CELLS, 65, 2),
            ("ROLL_F", C2.ROLL_FINE_PERIOD_CELLS, 66, 2), ("DRUMLIN", C2.DRUMLIN_PERIOD_CELLS, 67, 2),
            ("PLATEAU", C2.PLATEAU_PERIOD_CELLS, 68, 2), ("CONES", C2.KARST_CONE_PERIOD_CELLS, 69, 2))


def _f32(x: float) -> float:
    return float(np.float32(x))


def _inv(x: float) -> float:
    """torch's `tensor / scalar` on CUDA: a multiply by the reciprocal, taken in DOUBLE from the Python scalar and then
    rounded to fp32 (measured on torch 2.13: `t / 0.12 == t * float32(1 / 0.12)`, not `* (float32(1) / float32(0.12))`;
    the two differ for 0.12, which cuda_controls.py's f32 / f32 `_inv` never meets)."""
    return float(np.float32(1.0 / x))


# The values csrc/controls2.cu was written for (formulas included): a drift here must be ported to the kernel.
_EXPECT_C2 = dict(
    BELT_PERIOD_CELLS=50_000.0, BELT_WARP_CELLS=300.0, PHI_HOME_DEG=37.0, COAST_PERIOD_CELLS=500.0, COAST_SHARE_BIAS=-0.6,
    ISLAND_PERIOD_CELLS=40.0, SKERRY_PERIOD_CELLS=12.0, ISLAND_M=650.0, COAST_BAND_M=450.0, HOTSPOT_PERIOD_CELLS=300.0,
    HOTSPOT_CHANCE=0.12, VOLCANO_M=2600.0, VOLCANO_RADIUS_CELLS=45.0, VOLCANO_RISE_M=4500.0, COLD_DEG=(48.0, 62.0),
    FJORD_MOUNTAIN_M=900.0, SHELF_M=-150.0, ABYSS_M=-4200.0, ABYSS_CONTINENT=(-0.45, -0.85), TRENCH_LEVEL=-0.7,
    TRENCH_M=5500.0, TRENCH_SIGMA_CELLS=55.0, TRENCH_PERIOD_CELLS=3000.0, TRENCH_BIAS=-0.35, RIDGE_PERIOD_CELLS=9000.0,
    RIDGE_M=2200.0, RIDGE_SIGMA_CELLS=160.0, RIFT_M=900.0, RIFT_SIGMA_CELLS=12.0, HILLS_M=220.0, HILLS_PERIOD_CELLS=14.0,
    CANYON_M=600.0, CANYON_PERIOD_CELLS=70.0, CANYON_WIDTH=0.30, BASIN_PERIOD_CELLS=10_000.0, BASIN_BIAS=0.05,
    BASIN_SOFT=0.35, BASIN_PUSH=1.6, BASIN_HOME_KEEP_CELLS=4500.0, KARST_PERIOD_CELLS=600.0, KARST_BIAS=-0.9,
    KARST_BIAS_V2=-1.34, KARST_HOME_CELLS=(360.0, 160.0),
    WILD_FLOOR_M=3.5, ROLLING_BOOST=0.25, BIG_OCEAN_RADIUS_CELLS=250.0, CLIMATE_LAYOUTS=LAYOUTS,
    BELT_WARP_STRONG_CELLS=2500.0, BELT_WARP_PERIOD_CELLS=6000.0, ZONE_PERIOD_CELLS=2500.0, ZONE_SPREAD=0.6,
    HOME_CLIMATE_CELLS=1500.0, HOME_ZONE_CELLS=4000.0,
    LANDFORM_PERIOD_CELLS=250.0, LANDFORM_MIX=0.7, LANDFORM_BIAS=1.6, LANDFORM_FLOOR_M=25.0, LANDFORM_SOFT=1.6, LANDFORM_COAST_M=(30.0, 90.0),
    LANDFORM_TOP_M=(600.0, 1200.0), LANDFORM_STYLE_PERIOD_CELLS=300.0, LANDFORM_SHARPNESS=2.5,
    MORAINE_COLD_DEG=(40.0, 55.0), MORAINE_COLD=1.5, MORAINE_BIAS=0.5, ROLL_BROAD_PERIOD_CELLS=72.0, ROLL_BROAD_M=70.0,
    ROLL_FINE_PERIOD_CELLS=18.0, ROLL_FINE_M=65.0, LANDFORM_ROLL_SHARE=1.0, DRUMLIN_PERIOD_CELLS=9.0, DRUMLIN_STRETCH=3.0, MORAINE_M=80.0,
    PLATEAU_M=160.0, PLATEAU_PERIOD_CELLS=40.0, PLATEAU_LEVEL=-0.1, PLATEAU_EDGE=0.45,
    KARST_CONE_PERIOD_CELLS=12.0, KARST_CONE_M=110.0, KARST_CONE_SHARP=1.2, KARST_CONE_LAT_DEG=(25.0, 40.0))
_EXPECT_G = dict(HOME_RADIUS_CELLS=150.0, _FBM2_STD=0.27, _FBM3_STD=0.30, HILL_PERIOD_CELLS=350.0, HILL_BIAS=0.8,
                 HILL_SOFTNESS=2.4,
                 # v4's procedural_controls_at (controls2_at_kernel's v4_base)
                 RANGE_PERIOD_CELLS=560.0, RANGE_WARP_CELLS=60.0, RANGE_SPINE_WIDTH=0.5, RANGE_ALONG_PERIOD=900.0,
                 RANGE_ALONG_BIAS=0.2, RANGE_SPINE_M=2800.0, RANGE_SPUR_M=900.0, RANGE_FOOTHILL_M=500.0,
                 HILL_PLAINS_M=100.0, HILL_COUNTRY_M=360.0, HILL_HOME_MIN=0.55, HILL_ROUGH=0.55)
_EXPECT_D2 = dict(STYLES=("glacial_fjord", "volcanic", "drowned_archipelago", "skerries", "lagoon_barrier", "aeolian",
                          "undersea"), SEA_GATE_MIN_M=-2000.0)


def _check_constants(C2, G, D2) -> None:
    """The kernel bakes controls2's / the generator's constants; fail loudly if they drift apart."""
    bad = {}
    for mod, expect in ((C2, _EXPECT_C2), (G, _EXPECT_G), (D2, _EXPECT_D2)):
        for k, v in expect.items():
            if getattr(mod, k, None) != v:
                bad[f"{mod.__name__}.{k}"] = getattr(mod, k, None)
    if bad:
        raise RuntimeError(f"kernels/cuda_controls2.py (csrc/controls2.cu) is out of date with the controls2 constants: {bad}")


def _constants(C2, G, D2, layout: str, seed: int) -> dict:
    """K table by name (csrc/controls2.cu's K_* enum): every scalar as torch sees it."""
    a0, a1 = C2.ABYSS_CONTINENT
    R = C2.BIG_OCEAN_RADIUS_CELLS
    r_lat = C2.HOME_ZONE_CELLS if layout == "zones" else C2.HOME_CLIMATE_CELLS
    th = C2._seed_angle(seed)
    fl = C2._flow_angle(seed)
    k = dict(
        INV2=_inv(1.5), INV3=_inv(1.75), INV4=_inv(1.875), INV_STD2=_inv(G._FBM2_STD), INV_STD3=_inv(G._FBM3_STD),
        INV_HOME=_inv(2 * G.HOME_RADIUS_CELLS ** 2), C07=_f32(0.7), C035=_f32(0.35), INV_LAND=_inv(0.2),
        BASIN_BIAS=_f32(C2.BASIN_BIAS), INV_BASIN_SOFT=_inv(C2.BASIN_SOFT),
        INV_BASIN_HOME=_inv(2 * C2.BASIN_HOME_KEEP_CELLS ** 2), BASIN_PUSH=_f32(C2.BASIN_PUSH),
        EPS=_f32(1e-6),
        LOG2=_f32(math.log(2.0)), ROLLING_BOOST=_f32(C2.ROLLING_BOOST), LOG_WILD_FLOOR=_f32(math.log(C2.WILD_FLOOR_M)),
        HILL_BIAS=_f32(G.HILL_BIAS), INV_HILL_SOFT=_inv(G.HILL_SOFTNESS),
        ABYSS0=_f32(a0), INV_ABYSS_DOWN=_inv(a1 - a0), INV_ABYSS_UP=_inv(a0 - a1), ABYSS_SPAN_M=_f32(C2.ABYSS_M - C2.SHELF_M),
        SHELF_M=_f32(C2.SHELF_M),
        **{f"RING_DI{q}": _f32(R * math.sin(2 * math.pi * q / 8)) for q in range(8)},
        **{f"RING_DJ{q}": _f32(R * math.cos(2 * math.pi * q / 8)) for q in range(8)},
        C03=_f32(0.3), INV03=_inv(0.3), TRENCH_LEVEL=_f32(C2.TRENCH_LEVEL), TRENCH_BIAS=_f32(C2.TRENCH_BIAS),
        INV05=_inv(0.5), PI=_f32(math.pi), INV_TRENCH_SIGMA=_inv(C2.TRENCH_SIGMA_CELLS),
        C07B=_f32(0.7), INV02=_inv(0.2), INV_RIDGE_SIGMA=_inv(C2.RIDGE_SIGMA_CELLS), INV_RIFT_SIGMA=_inv(C2.RIFT_SIGMA_CELLS),
        HILLS_M=_f32(C2.HILLS_M), C06=_f32(0.6), C04=_f32(0.4),
        INV012=_inv(0.12), C075=_f32(0.75), INV_CANYON_WIDTH=_inv(C2.CANYON_WIDTH), NEG_TRENCH_M=_f32(-C2.TRENCH_M),
        RIDGE_M=_f32(C2.RIDGE_M), RIFT_M=_f32(C2.RIFT_M), CANYON_M=_f32(C2.CANYON_M),
        COLD0=_f32(C2.COLD_DEG[0]), INV_COLD_SPAN=_inv(C2.COLD_DEG[1] - C2.COLD_DEG[0]),
        COAST_BIAS=_f32(C2.COAST_SHARE_BIAS), INV08=_inv(0.8), INV_COAST_BAND=_inv(C2.COAST_BAND_M),
        ISLAND_M=_f32(C2.ISLAND_M),
        C02=_f32(0.2), C035B=_f32(0.35), VOLCANO_M=_f32(C2.VOLCANO_M), VOLCANO_RISE_M=_f32(C2.VOLCANO_RISE_M),
        INV_HOT_P=_inv(C2.HOTSPOT_PERIOD_CELLS), HOT_P=_f32(C2.HOTSPOT_PERIOD_CELLS), HOT_CHANCE=_f32(C2.HOTSPOT_CHANCE),
        INV_VOLC_R=_inv(2 * C2.VOLCANO_RADIUS_CELLS ** 2),
        INV1500=_inv(1500.0), FJORD_M=_f32(C2.FJORD_MOUNTAIN_M), INV800=_inv(800.0), INV40=_inv(40.0), INV06=_inv(0.6),
        INV30=_inv(30.0), SEA_GATE_MIN=_f32(D2.SEA_GATE_MIN_M), INV300=_inv(300.0), KARST_BIAS=_f32(C2.KARST_BIAS),
        INV_HOME_LAT=_inv(2 * r_lat ** 2),
        LAT_OFF=_f32(math.asin(C2.PHI_HOME_DEG / 90.0) / (2 * math.pi) * C2.BELT_PERIOD_CELLS),
        TWO_PI=_f32(2 * math.pi), INV_BELT_PERIOD=_inv(C2.BELT_PERIOD_CELLS), BELT_WARP_M=_f32(C2.BELT_WARP_CELLS),
        STRONG_WARP_M=_f32(C2.BELT_WARP_STRONG_CELLS), ZONE_SPREAD=_f32(C2.ZONE_SPREAD),
        PHI_HOME=_f32(C2.PHI_HOME_DEG), COS_TH=_f32(math.cos(th)), SIN_TH=_f32(math.sin(th)),
        INV10=_inv(10.0), INV8=_inv(8.0),
        DEG2RAD=_f32(0.017453292519943295769236907684886127134428718885417),   # torch.deg2rad's M_PI_180
        LF_C0=_f32(C2.LANDFORM_COAST_M[0]), INV_LF_C=_inv(C2.LANDFORM_COAST_M[1]), LF_TOP0=_f32(C2.LANDFORM_TOP_M[0]),
        INV_LF_TOP=_inv(C2.LANDFORM_TOP_M[1] - C2.LANDFORM_TOP_M[0]), LF_MIX=_f32(C2.LANDFORM_MIX),
        LF_BIAS=_f32(C2.LANDFORM_BIAS), INV_LF_SOFT=_inv(C2.LANDFORM_SOFT), MOR_COLD0=_f32(C2.MORAINE_COLD_DEG[0]),
        INV_MOR_COLD=_inv(C2.MORAINE_COLD_DEG[1] - C2.MORAINE_COLD_DEG[0]), MOR_COLD=_f32(C2.MORAINE_COLD),
        MOR_BIAS=_f32(C2.MORAINE_BIAS), LF_SHARP=_f32(C2.LANDFORM_SHARPNESS), COS_FLOW=_f32(math.cos(fl)),
        SIN_FLOW=_f32(math.sin(fl)), INV_STRETCH=_inv(C2.DRUMLIN_STRETCH), PLAT_LEVEL=_f32(C2.PLATEAU_LEVEL),
        INV_CONE_SHARP=_inv(C2.KARST_CONE_SHARP), ROLL_B_M=_f32(C2.ROLL_BROAD_M), PLAT_M=_f32(C2.PLATEAU_M),
        ROLL_F_M=_f32(C2.ROLL_FINE_M), MOR_M=_f32(C2.MORAINE_M), INV_PLAT_EDGE=_inv(C2.PLATEAU_EDGE),
        CONE_M=_f32(C2.KARST_CONE_M), LF_GAIN=_f32(C2.LANDFORM_GAIN), CONE_LAT0=_f32(C2.KARST_CONE_LAT_DEG[0]),
        INV_CONE_LAT=_inv(C2.KARST_CONE_LAT_DEG[1] - C2.KARST_CONE_LAT_DEG[0]), ROLL_SHARE=_f32(C2.LANDFORM_ROLL_SHARE),
        LF_FLOOR=_f32(C2.LANDFORM_FLOOR_M),
        KARST2_BIAS=_f32(C2.KARST_BIAS_V2), KARST_HOME0=_f32(C2.KARST_HOME_CELLS[0]),
        INV_KARST_HOME=_inv(C2.KARST_HOME_CELLS[1]),
    )
    return k


# controls2_at_kernel: v4's procedural_controls_at rows (V_* in csrc/controls2.cu) and constants (KV4_*)
def _rows_v4(G) -> tuple:
    return (("WARP_I", 400.0, 4, 2), ("WARP_J", 400.0, 5, 2), ("RANGE", G.RANGE_PERIOD_CELLS, 2, 2), ("SPUR", 130.0, 6, 2),
            ("ALONG", G.RANGE_ALONG_PERIOD, 7, 2), ("HILLS", 70.0, 8, 4))


def _constants_v4(G) -> dict:
    return dict(
        WARP=_f32(G.RANGE_WARP_CELLS), INV_SPINE=_inv(G.RANGE_SPINE_WIDTH), INV_FOOT=_inv(2.4 * G.RANGE_SPINE_WIDTH),
        INV_BODY=_inv(1.6 * G.RANGE_SPINE_WIDTH), INV_SPUR=_inv(0.6), ALONG_BIAS=_f32(G.RANGE_ALONG_BIAS),
        INV_ALONG=_inv(1.2), HOME_MIN=_f32(G.HILL_HOME_MIN), SPINE_M=_f32(G.RANGE_SPINE_M),
        SPUR_M=_f32(G.RANGE_SPUR_M), FOOT_M=_f32(G.RANGE_FOOTHILL_M), PLAINS_M=_f32(G.HILL_PLAINS_M),
        HILL_SPAN=_f32(G.HILL_COUNTRY_M - G.HILL_PLAINS_M), FOOT_HILL_M=_f32(200.0), ROUGH0=_f32(0.14),
        ROUGH_HILLY=_f32(G.HILL_ROUGH), ROUGH_SPINE=_f32(0.9), ROUGH_SPUR=_f32(0.45), ROUGH_FOOT=_f32(0.25),
        ROUGH_HILLS=_f32(0.25), ROUGH_HILLS0=_f32(0.3), WILD_K=_f32(math.log(40.0) - math.log(2.0)))


def _kernel_enums() -> tuple[list[str], dict]:
    """(K_* names in order, R_* values) parsed from csrc/controls2.cu: the host tables are checked against them."""
    src = _src()
    body = src.split("enum {\n    K_INV2", 1)[1].split("K_COUNT", 1)[0]
    knames = ["INV2"] + [t.strip()[2:] for t in body.replace("\n", " ").split(",") if t.strip()]
    rows = {m.group(1): int(m.group(2)) for m in re.finditer(r"R_(\w+) = (\d+)", src)}
    return knames, rows


class Controls2Args(ctypes.Structure):
    _fields_ = ([(n, ctypes.c_uint64) for n in ("out", "trend_v4", "wild_v4")]
                + [(n, ctypes.c_int32) for n in ("ci0", "cj0", "hc", "wc", "layout")]
                + [("hot_base", ctypes.c_uint32), ("bases", ctypes.c_uint32 * NROWS), ("invp", ctypes.c_float * NROWS),
                   ("k", ctypes.c_float * NK),
                   ("ci_at", ctypes.c_uint64), ("cj_at", ctypes.c_uint64), ("bases_v4", ctypes.c_uint32 * NROWS_V4),
                   ("invp_v4", ctypes.c_float * NROWS_V4), ("kv4", ctypes.c_float * NKV4)])


_TABLES: dict = {}


_SRC = None
_SRC_LOCK = __import__("threading").Lock()


def _src() -> str:
    """csrc/controls2.cu as this process compiles it: read once, then the same text feeds NVRTC (`_module`) and the
    host-table checks (review-native F7: re-reading the file per (seed, layout) made a mid-session edit either fail
    the check or pass it against an enum layout the loaded kernel does not have)."""
    global _SRC
    with _SRC_LOCK:
        if _SRC is None:
            _SRC = native._read_source(native.CSRC / "controls2.cu")
        return _SRC


def _hash32_int(x: int) -> int:
    from terrain_slm.world import generator as G
    return G._hash32_int(x)


def _tables(seed: int, layout: str) -> Controls2Args:
    """Per (seed, layout) argument template (bases, reciprocal periods, constants); out / inputs / window filled per call."""
    from terrain_slm.world import controls2 as C2
    key = (seed, layout, C2.LANDFORM_GAIN)            # the gain is a runtime knob (A/B), not a baked constant
    t = _TABLES.get(key)
    if t is None:
        from terrain_slm.models import detail2 as D2
        from terrain_slm.world import controls2 as C2
        from terrain_slm.world import generator as G
        _check_constants(C2, G, D2)
        if layout not in LAYOUTS:
            raise ValueError(f"climate layout {layout!r} not in {LAYOUTS}")
        knames, rpos = _kernel_enums()
        kv = _constants(C2, G, D2, layout, seed)
        if knames != list(kv) or len(knames) > NK:
            raise RuntimeError(f"csrc/controls2.cu K table out of sync with cuda_controls2._constants: {knames} vs {list(kv)}")
        t = Controls2Args()
        row = 0
        for name, period, stream, octaves in _rows(C2, G):
            if rpos.get(name) != row:
                raise RuntimeError(f"csrc/controls2.cu R_{name} = {rpos.get(name)}, host table has {row}")
            for o in range(octaves):
                s = stream * 16 + o
                t.bases[row] = _hash32_int((seed * 0x9E3779B1 + s * 0x632BE5AB) & _MASK32)
                t.invp[row] = _inv(period / 2 ** o)
                row += 1
        if rpos.get("END") != row or row > NROWS:
            raise RuntimeError(f"csrc/controls2.cu R_END = {rpos.get('END')}, host table has {row}")
        for i, name in enumerate(knames):
            t.k[i] = kv[name]
        vrows = {m.group(1): int(m.group(2)) for m in re.finditer(r"V_(\w+) = (\d+)", _src())}
        row = 0
        for name, period, stream, octaves in _rows_v4(G):
            if vrows.get(name) != row:
                raise RuntimeError(f"csrc/controls2.cu V_{name} = {vrows.get(name)}, host table has {row}")
            for o in range(octaves):
                s = stream * 16 + o
                t.bases_v4[row] = _hash32_int((seed * 0x9E3779B1 + s * 0x632BE5AB) & _MASK32)
                t.invp_v4[row] = _inv(period / 2 ** o)
                row += 1
        if vrows.get("END") != row or row > NROWS_V4:
            raise RuntimeError(f"csrc/controls2.cu V_END = {vrows.get('END')}, host table has {row}")
        kv4 = _constants_v4(G)
        body = _src().split("enum {\n    KV4_WARP", 1)[1].split("KV4_COUNT\n", 1)[0]
        kv4names = ["WARP"] + [x.strip()[4:] for x in body.replace("\n", " ").split(",") if x.strip()]
        if kv4names != list(kv4) or len(kv4names) > NKV4:
            raise RuntimeError(f"csrc/controls2.cu KV4 table out of sync: {kv4names} vs {list(kv4)}")
        for i, name in enumerate(kv4names):
            t.kv4[i] = kv4[name]
        t.hot_base = _hash32_int((seed * 0x9E3779B1 + 36 * 0x632BE5AB) & _MASK32)
        t.layout = LAYOUTS.index(layout)
        _TABLES[key] = t
        if len(_TABLES) > 64:
            _TABLES.pop(next(iter(_TABLES)))
    return t


def controls2_native(base: dict, ci0: int, cj0: int, hc: int, wc: int, seed: int, layout: str = "belts",
                     device=None) -> dict:
    """Same keys, shapes and values as `controls2._exp2(base, ci, cj, seed, layout)` with ci / cj the aranges of
    `controls2.controls2` (CUDA only). `base` = `generator.procedural_controls(ci0, cj0, hc, wc, seed, device)`."""
    device = torch.device(device) if device is not None else base["trend"].device
    t = _tables(seed, layout)
    trend_v4 = base["trend"].contiguous()
    wild_v4 = base["wild"].contiguous()
    nf = len(_FIELDS) + _N_GATES
    out = torch.empty(nf, hc, wc, device=device, dtype=torch.float32)
    n = hc * wc
    if n:
        args = Controls2Args.from_buffer_copy(t)         # the template: bases, invp, k, layout, hot_base
        args.out, args.trend_v4, args.wild_v4 = out.data_ptr(), trend_v4.data_ptr(), wild_v4.data_ptr()
        args.ci0, args.cj0, args.hc, args.wc = ci0, cj0, hc, wc
        with torch.cuda.device(out.device):
            _module(out.device).launch("controls2_kernel", ((n + 127) // 128,), (128,), args)
    f = {name: out[i] for i, name in enumerate(_FIELDS)}
    gates = out[len(_FIELDS):]
    # controls2._exp2's dict: {**base (wild / hilly replaced), trend_v4, trend_sea, trend (replaced), lat, belt, ...}
    nb = {**base, "wild": f["wild"], "hilly": f["hilly"]}
    return {**nb, "trend_v4": base["trend"], "trend_sea": f["trend_sea"], "trend": f["trend"], "lat": f["lat"],
            "belt": f["belt"], "wind_u": f["wind_u"], "wind_v": f["wind_v"], "gates": gates, "coast": f["coast"],
            "land": f["land"], "trench": f["trench"], "ridge": f["ridge"], "canyon": f["canyon"], "karst": f["karst"],
            "landform": f["landform"], "karst2": f["karst2"]}


def _module(device):
    return native.cuda_module("controls2", ["controls2_kernel", "controls2_at_kernel"], device,
                              opts=("--fmad=false", "-ftz=false", "-prec-div=true", "-prec-sqrt=true"),
                              source=_src())


#: v4 climate-archetype keys `controls2.controls2_at` carries from v4's base that the native version leaves out: its
#: users (generator2's climate node: trend, belt, wind) never read them, and they would need torch's softmax / sum
#: reduction order reproduced.
AT_OMITTED = ("t0", "tseason", "precip", "pcv")


def controls2_at_native(ci: torch.Tensor, cj: torch.Tensor, seed: int, layout: str = "belts") -> dict:
    """`controls2.controls2_at(ci, cj, seed, layout)` (arbitrary float cell coordinates, any shape, CUDA) as one kernel,
    v4's base included: the same keys, order and values (bit-identical), minus AT_OMITTED."""
    shape = ci.shape
    ci = ci.to(torch.float32).contiguous()
    cj = cj.to(torch.float32).expand(shape).contiguous()
    t = _tables(seed, layout)
    nf = len(_FIELDS) + _N_GATES
    n = ci.numel()
    out = torch.empty(nf + 1, n, device=ci.device, dtype=torch.float32)
    if n:
        args = Controls2Args.from_buffer_copy(t)
        args.out, args.ci_at, args.cj_at = out.data_ptr(), ci.data_ptr(), cj.data_ptr()
        args.trend_v4 = args.wild_v4 = 0
        args.ci0, args.cj0, args.hc, args.wc = 0, 0, 1, n
        with torch.cuda.device(out.device):
            _module(out.device).launch("controls2_at_kernel", ((n + 127) // 128,), (128,), args)
    out = out.view(nf + 1, *shape)
    f = {name: out[i] for i, name in enumerate(_FIELDS)}
    return {"wild": f["wild"], "trend": f["trend"], "hilly": f["hilly"], "trend_v4": out[nf],
            "trend_sea": f["trend_sea"], "lat": f["lat"], "belt": f["belt"], "wind_u": f["wind_u"], "wind_v": f["wind_v"],
            "gates": out[len(_FIELDS):nf], "coast": f["coast"], "land": f["land"], "trench": f["trench"],
            "ridge": f["ridge"], "canyon": f["canyon"], "karst": f["karst"], "landform": f["landform"],
            "karst2": f["karst2"]}


def controls2(ci0: int, cj0: int, hc: int, wc: int, seed: int, device, layout: str = "belts") -> dict:
    """`controls2.controls2` with the EXP-2 layout on the native kernel (v4's base from its own native kernel)."""
    from terrain_slm.world import generator as G
    base = G.procedural_controls(ci0, cj0, hc, wc, seed, device)
    return controls2_native(base, ci0, cj0, hc, wc, seed, layout, device)
