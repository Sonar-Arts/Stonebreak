"""The game's own FastNoise2 noise (libcenda_kernels, Open Mason engine) from Python, via ctypes.

Mirrors stonebreak-game `TerrainNoise.CendaChannel` exactly: same node constructor
(`ck_noise_simplex_fbm(octaves, lacunarity, gain, frequency)`), same seed collapse
(`Long.hashCode`), FastNoise2's X axis carrying world Z, whole-block offsets. So a distillation fit
measures the noise the game will actually sample, not a look-alike.

The library is the one the engine-core jar extracted (`~/.cache/openmason/natives/<hash>/`), or
`CENDA_KERNELS_PATH`.
"""
from __future__ import annotations

import ctypes
import os
from pathlib import Path

import numpy as np


def _find_lib() -> Path:
    env = os.getenv("CENDA_KERNELS_PATH")
    if env:
        return Path(env)
    hits = sorted(Path.home().glob(".cache/openmason/natives/*/libcenda_kernels.so"), key=lambda p: p.stat().st_mtime)
    if not hits:
        raise FileNotFoundError("libcenda_kernels.so not found (run the game or a test once, or set CENDA_KERNELS_PATH)")
    return hits[-1]


_LIB = ctypes.CDLL(str(_find_lib()))
_LIB.ck_noise_simplex_fbm.restype = ctypes.c_void_p
_LIB.ck_noise_simplex_fbm.argtypes = [ctypes.c_int, ctypes.c_float, ctypes.c_float, ctypes.c_float]
_LIB.ck_noise_destroy.argtypes = [ctypes.c_void_p]
_LIB.ck_gen_grid_2d.restype = ctypes.c_int
_LIB.ck_gen_grid_2d.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_float, ctypes.c_float,
                                ctypes.c_int, ctypes.c_int, ctypes.c_float, ctypes.c_float, ctypes.c_int]


def java_long_hash(seed: int) -> int:
    """Java `Long.hashCode(seed)` as a signed int32."""
    s = seed & 0xFFFFFFFFFFFFFFFF
    h = (s ^ (s >> 32)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= 1 << 31 else h


class Fbm:
    """One FastNoise2 simplex fbm node (frequency inside the node, positions in blocks)."""

    def __init__(self, octaves: int, gain: float, frequency: float, lacunarity: float = 2.0):
        self.node = _LIB.ck_noise_simplex_fbm(int(octaves), float(lacunarity), float(gain), float(frequency))
        if not self.node:
            raise RuntimeError("ck_noise_simplex_fbm failed")

    def __del__(self):
        node = getattr(self, "node", None)
        if node:
            _LIB.ck_noise_destroy(node)
            self.node = None

    def grid(self, seed: int, x0: int, z0: int, nx: int, nz: int, step: int,
             x_off: int = 0, z_off: int = 0) -> np.ndarray:
        """(nx, nz) float32, [ix, iz] = noise at block (x0 + ix*step, z0 + iz*step); `seed` is the full
        channel seed (world seed + channel offset), collapsed as the game does."""
        out = np.empty(nx * nz, dtype=np.float32)
        rc = _LIB.ck_gen_grid_2d(self.node, out.ctypes.data, float(z0 + z_off), float(x0 + x_off),
                                 nz, nx, float(step), float(step), java_long_hash(seed))
        if rc != 0:
            raise RuntimeError(f"ck_gen_grid_2d failed ({rc})")
        return out.reshape(nx, nz)
