"""ctypes host of the standalone native terrain pipeline (libstonebreak_terrain `sbtg_terrain_*`): the exact code path
the game runs in-process (tgmpipe/NativeTerrain.java), driven from Python - the TGMPipe service's native mode and the
whole-tile parity tests.

    t = NativeTerrain(native_dir, device=1)            # native_dir = export/native_release output
    t.set_world(0, world.to_dict())
    planes = t.tile(0, seed, tile_x, tile_z, lod)      # surface_channels.TilePlanes (int16 (7, n, n) + .surface)
"""
from __future__ import annotations

import ctypes
import json
from pathlib import Path

import numpy as np

from terrain_slm.export import native_pipeline as NP


class Options(ctypes.Structure):
    _fields_ = [("device", ctypes.c_int32), ("flags", ctypes.c_int32), ("cache_dir", ctypes.c_char_p),
                ("nvrtc_path", ctypes.c_char_p), ("reserved", ctypes.c_int64 * 6)]


def _bind(lib):
    if getattr(lib, "_terrain_bound", False):
        return lib
    V, I32, P = ctypes.c_void_p, ctypes.c_int32, ctypes.POINTER
    lib.sbtg_terrain_open.argtypes = [ctypes.c_char_p, ctypes.c_char_p, P(Options)]
    lib.sbtg_terrain_open.restype = V
    lib.sbtg_terrain_close.argtypes = [V]
    lib.sbtg_terrain_info.argtypes = [V]
    lib.sbtg_terrain_info.restype = ctypes.c_char_p
    lib.sbtg_terrain_warmup.argtypes = [V]
    lib.sbtg_terrain_warmup.restype = I32
    lib.sbtg_terrain_set_world.argtypes = [V, I32, ctypes.c_char_p]
    lib.sbtg_terrain_set_world.restype = I32
    lib.sbtg_terrain_tile.argtypes = [V, I32, ctypes.c_int64, I32, I32, I32, P(V)]
    lib.sbtg_terrain_tile.restype = I32
    lib.sbtg_tile_planes.argtypes = [V, P(I32), P(I32), P(I32)]
    lib.sbtg_tile_planes.restype = P(ctypes.c_int16)
    lib.sbtg_tile_surface_count.argtypes = [V]
    lib.sbtg_tile_surface_count.restype = I32
    lib.sbtg_tile_surface_name.argtypes = [V, I32]
    lib.sbtg_tile_surface_name.restype = ctypes.c_char_p
    lib.sbtg_tile_surface.argtypes = [V, I32]
    lib.sbtg_tile_surface.restype = P(ctypes.c_uint8)
    lib.sbtg_tile_free.argtypes = [V]
    lib.sbtg_terrain_stats.argtypes = [V, ctypes.c_char_p, I32]
    lib.sbtg_terrain_stats.restype = I32
    if hasattr(lib, "sbtg_terrain_set_lanes"):   # lanes (agent PX; additive to ABI 2)
        lib.sbtg_terrain_set_lanes.argtypes = [V, I32]
        lib.sbtg_terrain_set_lanes.restype = I32
        lib.sbtg_terrain_lanes.argtypes = [V]
        lib.sbtg_terrain_lanes.restype = I32
        lib.sbtg_terrain_tile_lane.argtypes = [V, I32, I32, ctypes.c_int64, I32, I32, I32, P(V)]
        lib.sbtg_terrain_tile_lane.restype = I32
    lib._terrain_bound = True
    return lib


class NativeTerrainError(RuntimeError):
    pass


class NativeTerrain:
    def __init__(self, native_dir: Path, device: int, cache_dir: Path | None = None, nvrtc: str | None = None,
                 cenda_lib: str | None = None, warm: bool = True, lanes: int = 1):
        """`lanes` > 1: that many tile builders on their own streams sharing models and window caches (agent PX); a
        library without lanes keeps one. Tiles are bit-identical whatever the lane count or lane."""
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.kernels import native

        self.lib = _bind(NP.library())
        cenda = cenda_lib or E.library().path
        o = Options()
        o.device = device
        self._cache = str(cache_dir or native.cache_dir()).encode()
        o.cache_dir = self._cache
        self._nvrtc = (nvrtc or E.torch_nvrtc() or "").encode() or None
        o.nvrtc_path = self._nvrtc
        h = self.lib.sbtg_terrain_open(str(cenda).encode(), str(native_dir).encode(), ctypes.byref(o))
        if not h:
            raise NativeTerrainError(f"sbtg_terrain_open: {NP._err(self.lib)}")
        self.handle = h
        self.info = json.loads(self.lib.sbtg_terrain_info(h).decode())
        self.model_id = self.info["model_id"]
        self.lanes = 1
        if hasattr(self.lib, "sbtg_terrain_set_lanes"):
            if lanes > 1 and self.lib.sbtg_terrain_lanes(h) == 1:   # ($SBTG_LANES may have set them at open)
                self._check(self.lib.sbtg_terrain_set_lanes(h, lanes), "set_lanes")
            self.lanes = self.lib.sbtg_terrain_lanes(h)
        if warm:
            self._check(self.lib.sbtg_terrain_warmup(h), "warmup")

    def __del__(self):
        h = getattr(self, "handle", None)
        if h:
            self.lib.sbtg_terrain_close(h)
            self.handle = None

    def _check(self, r: int, what: str) -> None:
        if r != 0:
            raise NativeTerrainError(f"sbtg_terrain_{what}: {NP._err(self.lib)}")

    def set_world(self, index: int, world: dict) -> None:
        self._check(self.lib.sbtg_terrain_set_world(self.handle, index, json.dumps(world).encode()), "set_world")

    def tile(self, world: int, seed: int, tile_x: int, tile_z: int, lod: int, lane: int = 0):
        """One tile on `lane` (calls on different lanes run concurrently; ctypes drops the GIL)."""
        from terrain_slm.world import surface_channels as SC
        out = ctypes.c_void_p()
        if lane:
            r = self.lib.sbtg_terrain_tile_lane(self.handle, lane, world, seed, tile_x, tile_z, lod, ctypes.byref(out))
        else:
            r = self.lib.sbtg_terrain_tile(self.handle, world, seed, tile_x, tile_z, lod, ctypes.byref(out))
        self._check(r, "tile")
        try:
            p, r, c = ctypes.c_int32(), ctypes.c_int32(), ctypes.c_int32()
            ptr = self.lib.sbtg_tile_planes(out, ctypes.byref(p), ctypes.byref(r), ctypes.byref(c))
            planes = np.ctypeslib.as_array(ptr, shape=(p.value, r.value, c.value)).copy()
            surface = {}
            for i in range(self.lib.sbtg_tile_surface_count(out)):
                name = self.lib.sbtg_tile_surface_name(out, i).decode()
                sp = self.lib.sbtg_tile_surface(out, i)
                surface[name] = np.ctypeslib.as_array(sp, shape=(r.value, c.value)).copy()
            return SC.TilePlanes(planes, surface)
        finally:
            self.lib.sbtg_tile_free(out)

    def stats(self) -> dict:
        n = self.lib.sbtg_terrain_stats(self.handle, None, 0)
        buf = ctypes.create_string_buffer(n + 1)
        self.lib.sbtg_terrain_stats(self.handle, buf, n + 1)
        return json.loads(buf.value.decode())


class NativeBuilder:
    """A TileBuilder stand-in for the service: one handshake world on a shared NativeTerrain."""

    def __init__(self, terrain: NativeTerrain, index: int, world):
        self.terrain, self.index, self.world = terrain, index, world
        terrain.set_world(index, world.to_dict())

    def bounds(self, tile_x: int, tile_z: int) -> tuple[int, int, int, int]:
        n = self.world.tile_size
        return tile_x * n, tile_z * n, (tile_x + 1) * n, (tile_z + 1) * n

    def build(self, seed: int, tile_x: int, tile_z: int, lod: int, lane: int = 0):
        if lod < 1 or lod & (lod - 1):
            raise ValueError(f"lod must be a power of two, got {lod}")
        return self.terrain.tile(self.index, seed, tile_x, tile_z, lod, lane)


#: The TGMPipe service's native mode (tgmpipe/service.py): "1" = native tiles (fail the start if unavailable),
#: "auto" (default since 2026-11-19, parity proven) = native when the engine + libstonebreak_terrain are there, else the
#: Python generator, "0" = Python only (the reference path).
SERVICE_MODE = __import__("os").environ.get("TGM_NATIVE_TILES", "auto").strip().lower()

#: Lanes of the service's native pipeline (agent PX): concurrent tile builders sharing models and window caches, one
#: GPU thread each (lane 0 = the express lane for priority-0 tiles, service.py). TGM_NATIVE_LANES=N, or "auto"
#: (default): DEFAULT_LANES on a GPU with AUTO_LANES_MIN_VRAM or more (a second lane costs ~2.5 GB), else 1.
#: TGM_NATIVE_LANES=1 = the sequential pipeline (A/B, fallback).
DEFAULT_LANES = 2
AUTO_LANES_MIN_VRAM = 16 * 2**30


def service_lanes(device: str | None = None) -> int:
    v = __import__("os").environ.get("TGM_NATIVE_LANES", "auto").strip().lower()
    if v not in ("", "auto"):
        return max(1, min(8, int(v)))
    try:
        import torch
        total = torch.cuda.get_device_properties(torch.device(device or "cuda")).total_memory
    except Exception:  # noqa: BLE001
        return 1
    return DEFAULT_LANES if total >= AUTO_LANES_MIN_VRAM else 1


def open_for_service(model_dir: Path, device: str, layout: str, log) -> "NativeTerrain | None":
    """The standalone pipeline for the service: the model's release export (<model_dir>/native, rewritten first when
    missing or stale: that loads the Python generator once), opened on `device`. None (logged) when unavailable and the
    mode is "auto"; raises in mode "1"."""
    import gc
    import os

    import torch

    from terrain_slm.export import native_release as NR
    if SERVICE_MODE in ("0", "", "off", "false"):
        return None
    try:
        if not str(device).startswith("cuda"):
            raise NativeTerrainError(f"native tiles need a CUDA device, not {device}")
        out = Path(model_dir) / "native"
        key = NR.export_key(Path(model_dir))
        if not NR.current(out, key):
            log.info("native tiles: writing the release export %s (key %s)", out, key)
            os.environ.setdefault("TGM_ENGINE", "cenda")
            from terrain_slm.world import generator2 as G2
            gen = G2.WorldGenerator2(Path(model_dir), 0, device)
            gen.use_climate_layout(layout)
            gen.default_climate_layout = layout
            NR.write(gen, out, key)
            del gen
            gc.collect()
            torch.cuda.empty_cache()
        idx = torch.device(device).index           # bare "cuda" has no index: use the current device (was None -> silent
        if idx is None:                              # fallback to the Python generator, 2026-11-19 K4 finding)
            idx = torch.cuda.current_device()
        t = NativeTerrain(out, idx, lanes=service_lanes(device))
        log.info("native tiles: %s on %s, %d lane(s)", t.model_id, device, t.lanes)
        return t
    except Exception as e:  # noqa: BLE001 - mode "auto" falls back to the Python generator
        if SERVICE_MODE == "1":
            raise
        log.warning("native tiles unavailable (%s): Python generator", e)
        return None
