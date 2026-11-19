"""ctypes host of libstonebreak_terrain (stonebreak-game/native, C ABI sbtg_*): phase 2 of the native engine - the
terrain pipeline's stages in C++ over Open Mason's engine runtime. The Python service still owns the generator and its
caches; ported stages are called from it (design: Dev Working/docs/DaedalusTGM-Exp-2-Native-Engine.md).

Library: $TGM_TERRAIN_LIB, else stonebreak-game/native/build/{release,agent-terrain} (no sanitizer builds).
"""
from __future__ import annotations

import ctypes
import os
import threading
from pathlib import Path

import torch

ABI_VERSION = 2
_ROOT = Path(__file__).resolve().parents[2]                       # Models/DaedalusTGM-Exp
_NATIVE = Path(os.getenv("TGM_GAME_NATIVE_DIR") or _ROOT.parents[1] / "stonebreak-game" / "native")


class PlannerConfig(ctypes.Structure):
    _fields_ = [("model", ctypes.c_void_p), ("win", ctypes.c_int32), ("n_in", ctypes.c_int32), ("n_out", ctypes.c_int32),
                ("height_scale", ctypes.c_float), ("logacc_scale", ctypes.c_float),
                ("desc_mean", ctypes.c_float * 8), ("desc_std", ctypes.c_float * 8),
                ("k_patch", ctypes.POINTER(ctypes.c_float)), ("r_patch", ctypes.c_int32),
                ("k_hill", ctypes.POINTER(ctypes.c_float)), ("r_hill", ctypes.c_int32),
                ("k_sea", ctypes.POINTER(ctypes.c_float)), ("r_sea", ctypes.c_int32),
                ("hill_transfer", ctypes.c_float), ("sea_detail", ctypes.c_int32), ("sea_detail_max_m", ctypes.c_float),
                ("sea_floor_m", ctypes.c_float), ("shelf_slope", ctypes.c_float), ("shelf_depth_m", ctypes.c_float),
                ("shelf_keep", ctypes.c_float), ("shelf_keep_m", ctypes.c_float), ("blur_fma", ctypes.c_int32),
                ("reserved", ctypes.c_int32 * 8)]


class ReliefConfig(ctypes.Structure):
    _fields_ = [("model", ctypes.c_void_p), ("n", ctypes.c_int32), ("m", ctypes.c_int32),
                ("k_blur", ctypes.POINTER(ctypes.c_float)), ("r_blur", ctypes.c_int32), ("gain", ctypes.c_float),
                ("scale", ctypes.c_float), ("floor_m", ctypes.c_float), ("height_scale", ctypes.c_float),
                ("slope_scale", ctypes.c_float), ("wild_offset", ctypes.c_float), ("wild_scale", ctypes.c_float),
                ("noise_stream", ctypes.c_int64), ("reserved", ctypes.c_int32 * 8)]


class DetailConfig(ctypes.Structure):
    _fields_ = [("model", ctypes.c_void_p)] + [(n, ctypes.c_int32) for n in ("nc", "f", "E", "ms", "S", "n_attempts")] + [
        ("k_attempt", ctypes.POINTER(ctypes.c_float) * 8), ("r_attempt", ctypes.c_int32 * 8),
        ("use_climate", ctypes.c_int32 * 8), ("k_base", ctypes.POINTER(ctypes.c_float)), ("r_base", ctypes.c_int32),
        ("k_river", ctypes.POINTER(ctypes.c_float)), ("r_river", ctypes.c_int32), ("up_fma", ctypes.c_int32)] + [
        (n, ctypes.c_float) for n in ("height_scale", "slope_scale", "wild_offset", "wild_scale", "logacc_scale",
                                      "x_limit", "asinh_m")] + [("noise_stream", ctypes.c_int64),
                                                                ("reserved", ctypes.c_int32 * 8)]


class TerrainError(RuntimeError):
    pass


def library_candidates() -> list[Path]:
    """$TGM_TERRAIN_LIB, else stonebreak-game/native/build/{release,agent-terrain}/src/terrain (sanitizer builds skipped,
    candidates probed out of process: native_libs)."""
    from terrain_slm.export import native_libs as NL
    files = [_NATIVE / "build" / d / "src" / "terrain" / n for d in ("release", "agent-terrain")
             for n in NL.shared_names("stonebreak_terrain")]
    found, skipped = NL.find("TGM_TERRAIN_LIB", files, "sbtg_abi_version")
    _SKIPPED[:] = skipped
    return found


_SKIPPED: list[str] = []


_LIB = None
_LOCK = threading.Lock()


def library() -> ctypes.CDLL:
    global _LIB
    with _LOCK:
        if _LIB is not None:
            return _LIB
        cands = library_candidates()
        errors = list(_SKIPPED)
        for p in cands:
            try:
                lib = ctypes.CDLL(str(p))
            except OSError as e:
                errors.append(f"{p}: {e}")
                continue
            lib.sbtg_abi_version.restype = ctypes.c_int32
            if lib.sbtg_abi_version() != ABI_VERSION:
                errors.append(f"{p}: sbtg ABI {lib.sbtg_abi_version()} (want {ABI_VERSION})")
                continue
            lib.sbtg_last_error.restype = ctypes.c_char_p
            lib.sbtg_create.argtypes = [ctypes.c_char_p, ctypes.c_void_p]
            lib.sbtg_create.restype = ctypes.c_void_p
            lib.sbtg_destroy.argtypes = [ctypes.c_void_p]
            lib.sbtg_planner_configure.argtypes = [ctypes.c_void_p, ctypes.POINTER(PlannerConfig)]
            lib.sbtg_planner_configure.restype = ctypes.c_int32
            lib.sbtg_planner_windows.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int32,
                                                 ctypes.c_int32, ctypes.POINTER(ctypes.c_int32), ctypes.c_int32,
                                                 ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p, ctypes.c_void_p]
            lib.sbtg_planner_windows.restype = ctypes.c_int32
            lib.sbtg_relief_configure.argtypes = [ctypes.c_void_p, ctypes.POINTER(ReliefConfig)]
            lib.sbtg_relief_configure.restype = ctypes.c_int32
            lib.sbtg_relief_window.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p), ctypes.c_int64,
                                               ctypes.c_int64, ctypes.c_int64, ctypes.c_void_p, ctypes.c_void_p]
            lib.sbtg_relief_window.restype = ctypes.c_int32
            lib.sbtg_climate_cells.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int32, ctypes.c_int32,
                                               ctypes.c_int64, ctypes.c_int64, ctypes.c_int64, ctypes.c_int64,
                                               ctypes.c_int32, ctypes.c_int32, ctypes.c_double,
                                               ctypes.POINTER(ctypes.c_float), ctypes.POINTER(ctypes.c_float),
                                               ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
            lib.sbtg_climate_cells.restype = ctypes.c_int32
            lib.sbtg_detail_configure.argtypes = [ctypes.c_void_p, ctypes.POINTER(DetailConfig)]
            lib.sbtg_detail_configure.restype = ctypes.c_int32
            lib.sbtg_detail_window.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p), ctypes.c_int64,
                                               ctypes.c_int64, ctypes.c_int64, ctypes.c_float, ctypes.c_void_p,
                                               ctypes.POINTER(ctypes.c_int32), ctypes.c_void_p]
            lib.sbtg_detail_window.restype = ctypes.c_int32
            lib.sbtg_stats.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_double), ctypes.c_int32]
            lib.sbtg_stats.restype = ctypes.c_int32
            lib.path = str(p)
            _LIB = lib
            return lib
        raise TerrainError("libstonebreak_terrain not found: " + ("; ".join(errors) or
                           f"no build under {_NATIVE / 'build'} (cmake --build ... --target stonebreak_terrain)"))


def _err(lib) -> str:
    e = lib.sbtg_last_error()
    return e.decode(errors="replace") if e else ""


def _taps(sigma: float, device) -> torch.Tensor:
    from terrain_slm.data import descriptors as D
    return D.gaussian_kernel1d(sigma, device).float().cpu().contiguous()


class TerrainPipeline:
    """One sbtg context bound to the generator's engine runtime."""

    def __init__(self, engine):
        from terrain_slm.export import cenda_engine as E
        self.lib = library()
        self.engine = engine
        h = self.lib.sbtg_create(E.library().path.encode(), ctypes.c_void_p(engine.handle))
        if not h:
            raise TerrainError(f"sbtg_create: {_err(self.lib)}")
        self.handle = h
        self.device = torch.device("cuda", engine.index)
        self._keep = []

    def __del__(self):
        h = getattr(self, "handle", None)
        if h:
            self.lib.sbtg_destroy(h)
            self.handle = None

    def configure_planner(self, model, *, win, n_in, height_scale, logacc_scale, desc_mean, desc_std, sigma_patch,
                          sigma_hill, sigma_sea, hill_transfer, sea_detail, sea_detail_max_m, sea_floor_m, shelf_slope,
                          shelf_depth_m, shelf_keep, shelf_keep_m, blur_fma=1) -> None:
        taps = [_taps(s, self.device) for s in (sigma_patch, sigma_hill, sigma_sea)]
        self._keep = taps
        ptr = lambda t: t.numpy().ctypes.data_as(ctypes.POINTER(ctypes.c_float))
        c = PlannerConfig(model=model.handle, win=win, n_in=n_in, n_out=11, height_scale=height_scale,
                          logacc_scale=logacc_scale, k_patch=ptr(taps[0]), r_patch=(taps[0].numel() - 1) // 2,
                          k_hill=ptr(taps[1]), r_hill=(taps[1].numel() - 1) // 2, k_sea=ptr(taps[2]),
                          r_sea=(taps[2].numel() - 1) // 2, hill_transfer=hill_transfer, sea_detail=int(sea_detail),
                          sea_detail_max_m=sea_detail_max_m, sea_floor_m=sea_floor_m, shelf_slope=shelf_slope,
                          shelf_depth_m=shelf_depth_m, shelf_keep=shelf_keep, shelf_keep_m=shelf_keep_m,
                          blur_fma=int(blur_fma))
        c.desc_mean[:] = [float(v) for v in desc_mean]
        c.desc_std[:] = [float(v) for v in desc_std]
        r = self.lib.sbtg_planner_configure(self.handle, ctypes.byref(c))
        if r != 0:
            raise TerrainError(f"sbtg_planner_configure: {_err(self.lib)} ({r})")
        self.win = win

    def planner_windows(self, inp: torch.Tensor, trend: torch.Tensor, offs, outs: list, override=None) -> None:
        """inp (1 or none, n_in, H, W) fp32, trend (H, W) fp32 on the device; offs [(row, col)]; outs: n tensors
        (11, win, win) fp32 written in place, ordered on torch's current stream."""
        inp = inp.reshape(inp.shape[-3:]).float().contiguous()
        trend = trend.float().contiguous()
        n = len(offs)
        o = (ctypes.c_int32 * (2 * n))(*[int(v) for rc in offs for v in rc])
        po = (ctypes.c_void_p * n)(*[t.data_ptr() for t in outs])
        stream = torch.cuda.current_stream(self.device).cuda_stream
        r = self.lib.sbtg_planner_windows(self.handle, inp.data_ptr(), trend.data_ptr(), inp.shape[-2], inp.shape[-1],
                                          o, n, po, None if override is None else override.data_ptr(), stream)
        if r != 0:
            raise TerrainError(f"sbtg_planner_windows: {_err(self.lib)} ({r})")

    def configure_relief(self, model, *, n, m, sigma, gain, scale, floor_m, height_scale, slope_scale, wild_offset,
                         wild_scale, noise_stream) -> None:
        taps = _taps(sigma, self.device)
        self._relief_taps = taps
        c = ReliefConfig(model=model.handle, n=n, m=m, k_blur=taps.numpy().ctypes.data_as(ctypes.POINTER(ctypes.c_float)),
                         r_blur=(taps.numel() - 1) // 2, gain=gain, scale=scale, floor_m=floor_m,
                         height_scale=height_scale, slope_scale=slope_scale, wild_offset=wild_offset,
                         wild_scale=wild_scale, noise_stream=noise_stream)
        r = self.lib.sbtg_relief_configure(self.handle, ctypes.byref(c))
        if r != 0:
            raise TerrainError(f"sbtg_relief_configure: {_err(self.lib)} ({r})")

    def relief_window(self, fields: list, ci0: int, cj0: int, seed: int, out: torch.Tensor) -> None:
        """fields: trend, wild, t0, tseason, precip, pcv over the window + margin (N, N), fp32 on the device."""
        fs = [f.float().contiguous() for f in fields]
        arr = (ctypes.c_void_p * 6)(*[f.data_ptr() for f in fs])
        stream = torch.cuda.current_stream(self.device).cuda_stream
        r = self.lib.sbtg_relief_window(self.handle, arr, ci0, cj0, seed, out.data_ptr(), stream)
        if r != 0:
            raise TerrainError(f"sbtg_relief_window: {_err(self.lib)} ({r})")

    def configure_detail(self, model, *, nc, f, E, ms, S, attempts, sigma_base, sigma_river, up_fma, height_scale,
                         slope_scale, wild_offset, wild_scale, logacc_scale, x_limit, asinh_m, noise_stream) -> None:
        """attempts: [(planner-height blur sigma, use_climate)] (generator.DETAIL_RETRY)."""
        taps = [_taps(sg, self.device) for sg, _ in attempts] + [_taps(sigma_base, self.device), _taps(sigma_river, self.device)]
        self._detail_taps = taps
        ptr = lambda t: t.numpy().ctypes.data_as(ctypes.POINTER(ctypes.c_float))
        c = DetailConfig(model=model.handle, nc=nc, f=f, E=E, ms=ms, S=S, n_attempts=len(attempts),
                         k_base=ptr(taps[-2]), r_base=(taps[-2].numel() - 1) // 2, k_river=ptr(taps[-1]),
                         r_river=(taps[-1].numel() - 1) // 2, up_fma=up_fma, height_scale=height_scale,
                         slope_scale=slope_scale, wild_offset=wild_offset, wild_scale=wild_scale,
                         logacc_scale=logacc_scale, x_limit=x_limit, asinh_m=asinh_m, noise_stream=noise_stream)
        for k, (_sg, clim) in enumerate(attempts):
            c.k_attempt[k] = ptr(taps[k])
            c.r_attempt[k] = (taps[k].numel() - 1) // 2
            c.use_climate[k] = int(bool(clim))
        r = self.lib.sbtg_detail_configure(self.handle, ctypes.byref(c))
        if r != 0:
            raise TerrainError(f"sbtg_detail_configure: {_err(self.lib)} ({r})")

    def detail_window(self, fields: list, si0: int, sj0: int, seed: int, has_flow: float, out: torch.Tensor) -> int:
        """fields: raw height, wild, t0, tseason, precip, pcv, logacc|None, river|None, gates|None (nc, nc) on the
        device -> out (E, E); returns the attempt that passed the guard (-1: the smooth base)."""
        fs = [None if f is None else f.float().contiguous() for f in fields]
        arr = (ctypes.c_void_p * 9)(*[None if f is None else f.data_ptr() for f in fs])
        got = ctypes.c_int32(0)
        stream = torch.cuda.current_stream(self.device).cuda_stream
        r = self.lib.sbtg_detail_window(self.handle, arr, si0, sj0, seed, has_flow, out.data_ptr(), ctypes.byref(got),
                                        stream)
        if r != 0:
            raise TerrainError(f"sbtg_detail_window: {_err(self.lib)} ({r})")
        return got.value

    CLIMATE_KEYS = ("t0", "tseason", "precip", "pcv", "aridity", "soil", "snow")

    def climate_cells(self, grid: torch.Tensor, gi0: int, gj0: int, ci0: int, cj0: int, hc: int, wc: int,
                      grid_cells: float) -> dict:
        from terrain_slm.models import climatenet as CN
        if not hasattr(self, "_cn"):
            mean = (ctypes.c_float * 7)(*[CN.OUT_NORM[k][0] for k in CN.OUT_NAMES])
            std = (ctypes.c_float * 7)(*[CN.OUT_NORM[k][1] for k in CN.OUT_NAMES])
            self._cn = (mean, std)
        grid = grid.float().contiguous()
        outs = [torch.empty(hc, wc, device=grid.device) for _ in range(7)]
        po = (ctypes.c_void_p * 7)(*[t.data_ptr() for t in outs])
        stream = torch.cuda.current_stream(self.device).cuda_stream
        r = self.lib.sbtg_climate_cells(self.handle, grid.data_ptr(), grid.shape[-2], grid.shape[-1], gi0, gj0, ci0, cj0,
                                        hc, wc, grid_cells, self._cn[0], self._cn[1], po, stream)
        if r != 0:
            raise TerrainError(f"sbtg_climate_cells: {_err(self.lib)} ({r})")
        return dict(zip(self.CLIMATE_KEYS, outs))

    def stats(self) -> dict:
        v = (ctypes.c_double * 6)()
        n = self.lib.sbtg_stats(self.handle, v, 6)
        return dict(zip(("planner_calls", "planner_windows", "launches", "engine_requests", "relief_windows",
                         "detail_attempts"), v[:n]))
