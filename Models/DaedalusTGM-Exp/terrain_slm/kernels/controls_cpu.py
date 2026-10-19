"""`generator.procedural_controls` on the CPU, natively (kernels/csrc/controls_cpu.h).

Mirrors the GPU definition (csrc/controls.cu) op for op -- the same tables, constants, reciprocal
multiplies and reduction orders -- so CPU and GPU worlds start from the same controls up to exp's
last bit (glibc expf here, CUDA expf there). The torch CPU version runs ~2000 small elementwise ops
per call; this is one parallel pass over the cells.
"""
from __future__ import annotations

import ctypes
import math

import numpy as np
import torch

from terrain_slm.synth import noise as S

FIELDS = ("wild", "trend", "t0", "tseason", "precip", "pcv")
_MASK32 = 0xFFFFFFFF
# fbm table rows (name, period cells, stream, octaves), the order csrc/controls.cu reads them in
_FBM = (
    ("continent", 900.0, 1, 4),
    ("warp_i", 400.0, 4, 2),
    ("warp_j", 400.0, 5, 2),
    ("range", 560.0, 2, 2),
    ("spur", 130.0, 6, 2),
    ("along", 900.0, 7, 2),
    ("hills", 70.0, 8, 4),
    ("hilly", 350.0, 9, 3),
) + tuple((f"climate{k}", 250.0, 20 + k, 2) for k in range(7))
_ROWS = ("continent", "warp_i", "warp_j", "range", "spur", "along", "hills", "hilly", "climate0")


def _f32(x: float) -> float:
    return float(np.float32(x))


def _inv(x: float) -> float:
    """torch's CUDA `tensor / scalar`: multiply by float32(1) / float32(scalar)."""
    return float(np.float32(1.0) / np.float32(x))


# order = controls_cpu.h ctl:: enum
K_VALUES = (_inv(1.5), _inv(1.75), _inv(1.875), _inv(2 * 150.0**2), _inv(0.2), _inv(0.27), _inv(0.30), _inv(0.5),
            _inv(2.4 * 0.5), _inv(1.6 * 0.5), _inv(0.6), _inv(1.2), _inv(2.4),
            _f32(math.log(40.0) - math.log(2.0)), _f32(math.log(2.0)))


class ControlsArgs(ctypes.Structure):
    _fields_ = [("out", ctypes.c_void_p), ("bases", ctypes.c_void_p), ("invp", ctypes.c_void_p),
                ("arch", ctypes.c_void_p), ("k", ctypes.c_void_p), ("rows", ctypes.c_void_p),
                ("ci0", ctypes.c_int), ("cj0", ctypes.c_int), ("hc", ctypes.c_int), ("wc", ctypes.c_int)]


def _check_constants(G) -> None:
    """The native code bakes the generator's constants; fail loudly if they drift apart."""
    expect = dict(RANGE_PERIOD_CELLS=560.0, RANGE_WARP_CELLS=60.0, RANGE_SPINE_WIDTH=0.5, RANGE_ALONG_PERIOD=900.0,
                  RANGE_ALONG_BIAS=0.2, RANGE_SPINE_M=2800.0, RANGE_SPUR_M=900.0, RANGE_FOOTHILL_M=500.0,
                  _FBM2_STD=0.27, _FBM3_STD=0.30, HILL_PERIOD_CELLS=350.0, HILL_BIAS=0.8, HILL_SOFTNESS=2.4,
                  HILL_PLAINS_M=100.0, HILL_COUNTRY_M=360.0, HILL_HOME_MIN=0.55, HILL_ROUGH=0.55,
                  HOME_RADIUS_CELLS=150.0, CLIMATE_ZONE_CELLS=250.0, CLIMATE_SHARPNESS=10.0)
    bad = {k: getattr(G, k) for k, v in expect.items() if getattr(G, k) != v}
    if bad or len(G.CLIMATE_ARCHETYPES) != 7:
        raise RuntimeError(f"kernels/csrc/controls_cpu.h is out of date with generator constants: {bad}")


_TABLES: dict = {}
_LIB = None


def _lib():
    global _LIB
    if _LIB is None:
        from terrain_slm.kernels.cpu_sampler import lib
        L = lib()
        if L.tgm_controls_args_size() != ctypes.sizeof(ControlsArgs):
            raise RuntimeError("controls_cpu.h ControlsArgs layout does not match kernels/controls_cpu.py")
        L.tgm_controls.argtypes = [ctypes.POINTER(ControlsArgs)]
        _LIB = L
    return _LIB


def _tables(seed: int):
    if seed not in _TABLES:
        from terrain_slm.world import generator as G
        _check_constants(G)
        bases, invp, rows = [], [], {}
        for name, period, stream, octaves in _FBM:
            rows[name] = len(bases)
            for o in range(octaves):
                s = stream * 16 + o
                bases.append(int(S._hash32(torch.tensor((seed * 0x9E3779B1 + s * 0x632BE5AB) & _MASK32))))
                invp.append(_inv(period / 2**o))
        arch = torch.tensor([a[1:] for a in G.CLIMATE_ARCHETYPES], dtype=torch.float32)
        arch[:, 2] = torch.log(arch[:, 2])
        _TABLES[seed] = (torch.tensor(np.array(bases, dtype=np.uint32).view(np.int32)),
                         torch.tensor(invp, dtype=torch.float32), arch.contiguous(),
                         torch.tensor(K_VALUES, dtype=torch.float32),
                         torch.tensor([rows[r] for r in _ROWS], dtype=torch.int32))
        if len(_TABLES) > 64:
            _TABLES.pop(next(iter(_TABLES)))
    return _TABLES[seed]


def procedural_controls(ci0: int, cj0: int, hc: int, wc: int, seed: int, device="cpu") -> dict:
    """Same contract as `generator.procedural_controls` (CPU tensors)."""
    bases, invp, arch, k, rows = _tables(seed)
    out = torch.empty(len(FIELDS), hc, wc, dtype=torch.float32)
    a = ControlsArgs(out.data_ptr(), bases.data_ptr(), invp.data_ptr(), arch.data_ptr(), k.data_ptr(),
                     rows.data_ptr(), ci0, cj0, hc, wc)
    _lib().tgm_controls(ctypes.byref(a))
    return {name: out[i] for i, name in enumerate(FIELDS)}
