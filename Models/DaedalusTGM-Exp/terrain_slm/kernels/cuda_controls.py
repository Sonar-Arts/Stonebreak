"""`generator.procedural_controls` as one CUDA kernel (csrc/controls.cu), bit-identical to the torch version.

The torch version is ~40 hash-noise evaluations of ~50 elementwise ops each: ~2000 kernel launches
of tiny tensors, ~28 ms per call, called for every relief, planner, hydro and detail window.
Everything in it is pointwise in the cell coordinates, so one thread computes one cell end to end in
registers and writes the six fields once.

Bit-exactness matters (the fields feed the relief sampler, the planner and the discrete drainage
router, where one ulp can move a river): see the header of csrc/controls.cu for the rules; the host
side keeps torch's `tensor / scalar` = multiply by the fp32 reciprocal (`_inv`).
tests/test_cuda_kernels.py pins equality.
"""
from __future__ import annotations

import ctypes
import math

import numpy as np
import torch

from terrain_slm.kernels import native
from terrain_slm.synth import noise as S

_MASK32 = 0xFFFFFFFF
FIELDS = ("wild", "trend", "t0", "tseason", "precip", "pcv")

# fbm table rows (period cells, stream, octaves) in the order the kernel reads them.
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


def _f32(x: float) -> float:
    return float(np.float32(x))


def _inv(x: float) -> float:
    """torch's `tensor / scalar` on CUDA: multiply by float32(1) / float32(scalar)."""
    return float(np.float32(1.0) / np.float32(x))


# Order = the K_* enum in csrc/controls.cu.
_CONSTANTS = (_inv(1.5), _inv(1.75), _inv(1.875), _inv(2 * 150.0**2), _inv(0.2), _inv(0.27), _inv(0.30), _inv(0.5),
              _inv(2.4 * 0.5), _inv(1.6 * 0.5), _inv(0.6), _inv(1.2), _inv(2.4),
              _f32(math.log(40.0) - math.log(2.0)), _f32(math.log(2.0)))


def _check_constants(G) -> None:
    """The kernel bakes the generator's constants; fail loudly if they drift apart."""
    expect = dict(RANGE_PERIOD_CELLS=560.0, RANGE_WARP_CELLS=60.0, RANGE_SPINE_WIDTH=0.5, RANGE_ALONG_PERIOD=900.0,
                  RANGE_ALONG_BIAS=0.2, RANGE_SPINE_M=2800.0, RANGE_SPUR_M=900.0, RANGE_FOOTHILL_M=500.0,
                  _FBM2_STD=0.27, _FBM3_STD=0.30, HILL_PERIOD_CELLS=350.0, HILL_BIAS=0.8, HILL_SOFTNESS=2.4,
                  HILL_PLAINS_M=100.0, HILL_COUNTRY_M=360.0, HILL_HOME_MIN=0.55, HILL_ROUGH=0.55,
                  HOME_RADIUS_CELLS=150.0, CLIMATE_ZONE_CELLS=250.0, CLIMATE_SHARPNESS=10.0)
    bad = {k: getattr(G, k) for k, v in expect.items() if getattr(G, k) != v}
    if bad or len(G.CLIMATE_ARCHETYPES) != 7:
        raise RuntimeError(f"kernels/cuda_controls.py is out of date with generator constants: {bad}")


class ControlsArgs(ctypes.Structure):
    _fields_ = [(n, ctypes.c_uint64) for n in ("out", "bases", "invp", "arch", "k")] + [
        (n, ctypes.c_int32) for n in ("ci0", "cj0", "hc", "wc", "r_cont", "r_wi", "r_wj", "r_range", "r_spur",
                                      "r_along", "r_hills", "r_hilly", "r_clim")]


_TABLES: dict = {}


def _tables(seed: int, device) -> tuple:
    key = (seed, str(device))
    if key not in _TABLES:
        from terrain_slm.world import generator as G
        _check_constants(G)
        bases, invp, rows = [], [], {}
        for name, period, stream, octaves in _FBM:
            rows[name] = len(bases)
            for o in range(octaves):
                s = stream * 16 + o
                b = S._hash32(torch.tensor((seed * 0x9E3779B1 + s * 0x632BE5AB) & _MASK32))
                bases.append(int(b))
                invp.append(_inv(period / 2**o))
        arch = torch.tensor([a[1:] for a in G.CLIMATE_ARCHETYPES], dtype=torch.float32, device=device)
        arch[:, 2] = torch.log(arch[:, 2])                       # on device: CUDA logf, as the torch path
        bt = torch.tensor(np.array(bases, dtype=np.uint32).view(np.int32), device=device)   # uint32 bits
        consts = torch.tensor(_CONSTANTS, dtype=torch.float32, device=device)
        _TABLES[key] = (bt, torch.tensor(invp, dtype=torch.float32, device=device), rows, arch.contiguous(), consts)
        if len(_TABLES) > 64:
            _TABLES.pop(next(iter(_TABLES)))
    return _TABLES[key]


def procedural_controls(ci0: int, cj0: int, hc: int, wc: int, seed: int, device) -> dict:
    """Same contract and values as `generator.procedural_controls` (CUDA only)."""
    bases, invp, rows, arch, consts = _tables(seed, device)
    out = torch.empty(len(FIELDS), hc, wc, device=device, dtype=torch.float32)
    mod = native.cuda_module("controls", ["controls_kernel"], out.device, opts=("--fmad=false", "-ftz=false",
                                                                                 "-prec-div=true", "-prec-sqrt=true"))
    args = ControlsArgs(out.data_ptr(), bases.data_ptr(), invp.data_ptr(), arch.data_ptr(), consts.data_ptr(),
                        ci0, cj0, hc, wc, rows["continent"], rows["warp_i"], rows["warp_j"], rows["range"],
                        rows["spur"], rows["along"], rows["hills"], rows["hilly"], rows["climate0"])
    n = hc * wc
    if n:
        with torch.cuda.device(out.device):
            mod.launch("controls_kernel", ((n + 127) // 128,), (128,), args)
    return {name: out[k] for k, name in enumerate(FIELDS)}
