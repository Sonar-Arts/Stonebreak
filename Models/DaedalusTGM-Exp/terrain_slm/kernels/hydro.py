"""Native drainage routing (csrc/hydro.cpp): bit-identical drop-ins for the numba / numpy reference.

  fill(z, invalid, epsilon, epsilon_field)    data.hydrology.fill.fill_depressions (no max_raise)
  d8(filled, invalid)                         data.hydrology.flow.d8_receivers
  flow_accumulation(recv, valid, weights)     data.hydrology.flow.flow_accumulation
  route_window(height, precip_mm, flat_noise) generator.route_window (fill + D8 in one call)
  accumulate_core(recv, invalid, weights, inflow_logacc)
                                              the numpy part of generator.accumulate_drainage
  break_seam_cycles(recv, owner)              generator.break_seam_cycles (in place)

Every float64 operation, comparison and summation order is the reference's (tests/test_hydro_native.py
pins exact equality), so routing -- and the rivers built on it -- does not change. `available()` is
False without a C++ compiler or with TGM_KERNELS=0; callers then keep the reference path.
"""
from __future__ import annotations

import ctypes

import numpy as np

_LIB = None
_FAILED = False

_P = ctypes.c_void_p
_I = ctypes.c_int64


def _lib():
    global _LIB, _FAILED
    if _LIB is None and not _FAILED:
        try:
            from terrain_slm.kernels import native
            if not native.cpu_available():
                _FAILED = True
                return None
            lib = native.cpu_library("hydro")
            lib.tgm_route.argtypes = [_P, _P, _I, _I, _P, ctypes.c_double, _P, _P]
            lib.tgm_fill.argtypes = [_P, _P, _I, _I, _P, ctypes.c_double, _P]
            lib.tgm_d8.argtypes = [_P, _P, _I, _I, _P]
            lib.tgm_flow_acc.argtypes = [_P, _P, _I, _P]
            lib.tgm_flow_acc.restype = _I
            lib.tgm_d8_class.argtypes = [_P, _I, _I, _P]
            lib.tgm_break_seam_cycles.argtypes = [_P, _P, _I]
            _LIB = lib
        except Exception:   # noqa: BLE001 - no compiler / build failure: reference path
            _FAILED = True
    return _LIB


def available() -> bool:
    return _lib() is not None


def _ptr(a: np.ndarray):
    return a.ctypes.data_as(_P)


def _f64(a) -> np.ndarray:
    return np.ascontiguousarray(a, dtype=np.float64)


def _u8(a) -> np.ndarray:
    return np.ascontiguousarray(a, dtype=np.bool_).view(np.uint8)


def _eps_field(epsilon_field, shape) -> np.ndarray | None:
    if epsilon_field is None:
        return None
    # fill_depressions: np.maximum(epsilon_field, 1e-9) as a flat float64 array
    eps = np.ascontiguousarray(np.maximum(epsilon_field, 1e-9), dtype=np.float64).reshape(-1)
    if eps.size != shape[0] * shape[1]:
        raise ValueError(f"epsilon_field has {eps.size} values, expected {shape[0] * shape[1]}")
    return eps


def fill(z: np.ndarray, invalid: np.ndarray, epsilon: float, epsilon_field: np.ndarray | None = None) -> np.ndarray:
    """fill_depressions(z, epsilon, invalid, max_raise=None, epsilon_field) for a validated 2D z."""
    z64 = _f64(z)
    inv = _u8(invalid)
    h, w = z64.shape
    eps = _eps_field(epsilon_field, z64.shape)
    out = np.empty((h, w), dtype=np.float64)
    _lib().tgm_fill(_ptr(z64), _ptr(inv), h, w, None if eps is None else _ptr(eps), float(epsilon), _ptr(out))
    return out


def d8(filled: np.ndarray, invalid: np.ndarray) -> np.ndarray:
    z = _f64(filled)
    inv = _u8(invalid)
    h, w = z.shape
    recv = np.empty(h * w, dtype=np.int64)
    _lib().tgm_d8(_ptr(z), _ptr(inv), h, w, _ptr(recv))
    return recv


def flow_accumulation(recv: np.ndarray, valid: np.ndarray, weights: np.ndarray | None = None) -> np.ndarray:
    h, w = valid.shape
    n = h * w
    if recv.shape != (n,):
        raise ValueError(f"recv has {recv.shape}, expected ({n},) for a {h}x{w} grid")
    if weights is None:
        acc = valid.astype(np.float64).ravel().copy()
    else:
        acc = np.where(valid, np.asarray(weights, dtype=np.float64), 0.0).ravel().copy()
    r = np.ascontiguousarray(recv, dtype=np.int64)
    v = _u8(valid)
    processed = _lib().tgm_flow_acc(_ptr(r), _ptr(v), n, _ptr(acc))
    n_land = int(np.count_nonzero(v))
    if processed != n_land:
        raise RuntimeError(
            f"flow graph is not acyclic: {n_land - processed} of {n_land} land cells "
            "were never drained. The depression fill did not produce a strictly "
            "descending surface."
        )
    return acc.reshape(valid.shape)


def route_window(height, precip_mm, flat_noise=None):
    """generator.route_window, natively: (recv (H*W,) int64, invalid (H, W) bool, weights (H, W) float64)."""
    from terrain_slm.data.hydrology.fill import DEFAULT_EPSILON, ocean_mask

    z = np.ascontiguousarray(height.detach().double().cpu().numpy())
    invalid = ocean_mask(z)
    eps = None
    if flat_noise is not None:
        u = np.tanh(flat_noise.detach().double().cpu().numpy())
        eps = _eps_field(DEFAULT_EPSILON * (1.0 + 0.9 * u), z.shape)
    h, w = z.shape
    filled = np.empty((h, w), dtype=np.float64)
    recv = np.empty(h * w, dtype=np.int64)
    _lib().tgm_route(_ptr(z), _ptr(_u8(invalid)), h, w, None if eps is None else _ptr(eps), DEFAULT_EPSILON,
                     _ptr(filled), _ptr(recv))
    weights = np.clip(precip_mm.detach().double().cpu().numpy(), 0.0, None) / 1000.0
    return recv, invalid, weights


def accumulate_core(recv: np.ndarray, invalid: np.ndarray, weights: np.ndarray, inflow_logacc=None):
    """The numpy half of generator.accumulate_drainage: (acc (H, W) float64, d8 class (H, W) int64)."""
    h, w = invalid.shape
    if inflow_logacc is not None:
        ring = np.zeros((h, w), dtype=bool)
        ring[0, :] = ring[-1, :] = ring[:, 0] = ring[:, -1] = True
        inward = ring.ravel() & (recv >= 0)
        extra = np.expm1(inflow_logacc.detach().double().cpu().numpy().clip(0.0, 30.0)).ravel()
        wv = weights.ravel().copy()
        wv[inward] = np.maximum(wv[inward], extra[inward])
        weights = wv.reshape(h, w)
    acc = flow_accumulation(recv, ~invalid, weights=weights)
    r = np.ascontiguousarray(recv, dtype=np.int64)
    d8c = np.empty(h * w, dtype=np.int64)
    _lib().tgm_d8_class(_ptr(r), h, w, _ptr(d8c))
    return acc, d8c.reshape(h, w)


def break_seam_cycles(recv: np.ndarray, owner: np.ndarray) -> np.ndarray:
    """generator.break_seam_cycles: in place on `recv` (contiguous int64), returned."""
    assert recv.dtype == np.int64 and recv.flags.c_contiguous, "recv must be a contiguous int64 array (edited in place)"
    o = np.ascontiguousarray(owner, dtype=np.int64)
    _lib().tgm_break_seam_cycles(_ptr(recv), _ptr(o), recv.size)
    return recv
