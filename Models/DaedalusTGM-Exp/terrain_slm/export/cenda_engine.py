"""ctypes binding of the Cenda inference engine (ci_* in OpenMason's libcenda_kernels; header
openmason-engine/cenda/native/kernels/include/cenda/infer.h). Phase 1 of the native engine: TGMPipe's Python generator
runs its networks through the engine instead of torch / the Python-side launch code.

    eng = Engine.get("cuda:1")                      # one runtime per device (process-wide)
    m = eng.load("detail.cim", max_batch=4, contexts=2)
    x = m.flow(cond, noise, gates)                  # torch CUDA tensors in, out; ordered on torch's current stream

Library: $TGM_CENDA_LIB, else ../OpenMason/openmason-engine/cenda/build/{release,agent-infer} (no sanitizer builds).
NVRTC: the one torch ships (nvidia-cuda-nvrtc wheel, the compiler kernels/native.py uses), so engine kernels are the
same machine code as the Python path's. Compiled kernels are cached in the kernel cache (Models/kernel_cache/).
"""
from __future__ import annotations

import ctypes
import json
import os
import threading
from pathlib import Path

import torch

ABI_VERSION = 2
RUN_HOST, RUN_MASK_GIVEN, RUN_MASK_ALL, RUN_SYNC, RUN_STREAM = 1, 2, 4, 8, 16
MODEL_NO_GRAPHS = 1

_ROOT = Path(__file__).resolve().parents[2]                       # Models/DaedalusTGM-Exp
# The OpenMason checkout next to the Stonebreak repo (TGM_CENDA_DIR overrides, e.g. for a package snapshot).
_CENDA = Path(os.getenv("TGM_CENDA_DIR") or _ROOT.parents[2] / "OpenMason" / "openmason-engine" / "cenda")


class RuntimeOptions(ctypes.Structure):
    _fields_ = [("cache_dir", ctypes.c_char_p), ("nvrtc_path", ctypes.c_char_p), ("cuda_path", ctypes.c_char_p),
                ("compile_threads", ctypes.c_int32), ("reserved", ctypes.c_int32 * 7)]


class ModelOptions(ctypes.Structure):
    _fields_ = [("max_batch", ctypes.c_int32), ("contexts", ctypes.c_int32), ("graphs", ctypes.c_int32),
                ("flags", ctypes.c_int32), ("reserved", ctypes.c_int32 * 4)]


class Request(ctypes.Structure):
    _fields_ = [("batch", ctypes.c_int32), ("flags", ctypes.c_int32), ("mask", ctypes.c_uint32),
                ("reserved0", ctypes.c_uint32), ("inputs", ctypes.c_void_p * 4), ("outputs", ctypes.c_void_p * 2),
                ("stream", ctypes.c_void_p), ("out_mask", ctypes.POINTER(ctypes.c_uint32)),
                ("reserved", ctypes.c_int64 * 4)]


class EngineError(RuntimeError):
    pass


def library_candidates() -> list[Path]:
    """$TGM_CENDA_LIB, else the fixed builds of the OpenMason checkout: build/release, then build/agent-infer (the
    engine's dev dir); sanitizer builds are skipped and every candidate is probed out of process (native_libs)."""
    from terrain_slm.export import native_libs as NL
    files = [_CENDA / "build" / d / "native" / "kernels" / n for d in ("release", "agent-infer")
             for n in NL.shared_names("cenda_kernels")]
    found, skipped = NL.find("TGM_CENDA_LIB", files, "ci_abi_version")
    _SKIPPED[:] = skipped
    return found


_SKIPPED: list[str] = []


_LIB = None
_LIB_LOCK = threading.Lock()


def library() -> ctypes.CDLL:
    global _LIB
    with _LIB_LOCK:
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
            if not hasattr(lib, "ci_abi_version"):
                errors.append(f"{p}: no inference engine (ci_abi_version)")
                continue
            lib.ci_abi_version.restype = ctypes.c_int32
            if lib.ci_abi_version() != ABI_VERSION:
                errors.append(f"{p}: ci ABI {lib.ci_abi_version()} (want {ABI_VERSION})")
                continue
            lib.ci_last_error.restype = ctypes.c_char_p
            lib.ci_cuda_available.argtypes = [ctypes.c_int32]
            lib.ci_cuda_available.restype = ctypes.c_int32
            lib.ci_runtime_create.argtypes = [ctypes.c_int32, ctypes.POINTER(RuntimeOptions)]
            lib.ci_runtime_create.restype = ctypes.c_void_p
            lib.ci_runtime_destroy.argtypes = [ctypes.c_void_p]
            lib.ci_model_load.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ModelOptions)]
            lib.ci_model_load.restype = ctypes.c_void_p
            lib.ci_model_destroy.argtypes = [ctypes.c_void_p]
            lib.ci_model_info.argtypes = [ctypes.c_void_p]
            lib.ci_model_info.restype = ctypes.c_char_p
            lib.ci_model_warmup.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_int32,
                                            ctypes.POINTER(ctypes.c_int32), ctypes.c_int32]
            lib.ci_model_warmup.restype = ctypes.c_int32
            lib.ci_run.argtypes = [ctypes.c_void_p, ctypes.POINTER(Request)]
            lib.ci_run.restype = ctypes.c_int32
            lib.ci_model_stats.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_double), ctypes.c_int32]
            lib.ci_model_stats.restype = ctypes.c_int32
            lib.path = str(p)
            _LIB = lib
            return lib
        raise EngineError("Cenda inference engine not found: " + ("; ".join(errors) or
                          f"no libcenda_kernels under {_CENDA / 'build'} (set TGM_CENDA_LIB)"))


def _err(lib) -> str:
    e = lib.ci_last_error()
    return e.decode(errors="replace") if e else ""


def torch_nvrtc() -> str | None:
    """The NVRTC torch ships (kernels/native.py compiles with it): same compiler -> same machine code."""
    try:
        import nvidia.cuda_nvrtc as pkg
        for d in pkg.__path__:
            for p in sorted(Path(d, "lib").glob("libnvrtc.so*")):
                if "builtins" not in p.name and ".alt." not in p.name:
                    return str(p)
    except ImportError:
        pass
    return None


def available(device) -> bool:
    dev = torch.device(device)
    if dev.type != "cuda" or not torch.cuda.is_available():
        return False
    try:
        lib = library()
    except EngineError:
        return False
    return bool(lib.ci_cuda_available(dev.index if dev.index is not None else torch.cuda.current_device()))


class Engine:
    """One engine runtime per CUDA device (the device's primary context, shared with torch)."""
    _instances: dict = {}
    _lock = threading.Lock()

    @classmethod
    def get(cls, device) -> "Engine":
        dev = torch.device(device)
        idx = dev.index if dev.index is not None else torch.cuda.current_device()
        with cls._lock:
            if idx not in cls._instances:
                cls._instances[idx] = cls(idx)
            return cls._instances[idx]

    def __init__(self, index: int):
        from terrain_slm.kernels import native
        self.lib = library()
        self.index = index
        torch.zeros(1, device=f"cuda:{index}")           # torch's primary context exists before ours retains it
        self._keep = [str(native.cache_dir()).encode(), (torch_nvrtc() or "").encode()]
        opts = RuntimeOptions(cache_dir=self._keep[0], nvrtc_path=self._keep[1] or None, cuda_path=None,
                              compile_threads=0)
        h = self.lib.ci_runtime_create(index, ctypes.byref(opts))
        if not h:
            raise EngineError(f"ci_runtime_create(cuda:{index}): {_err(self.lib)}")
        self.handle = h

    def load(self, path, max_batch: int = 1, contexts: int = 1, graphs: bool = True) -> "Model":
        return Model(self, Path(path), max_batch, contexts, graphs)


class Model:
    def __init__(self, engine: Engine, path: Path, max_batch: int, contexts: int, graphs: bool):
        self.engine, self.lib = engine, engine.lib
        opts = ModelOptions(max_batch=max_batch, contexts=contexts, graphs=1 if graphs else 0,
                            flags=0 if graphs else MODEL_NO_GRAPHS)
        h = self.lib.ci_model_load(engine.handle, str(path).encode(), ctypes.byref(opts))
        if not h:
            raise EngineError(f"ci_model_load({path}): {_err(self.lib)}")
        self.handle = h
        self.path = path
        self.info = json.loads(self.lib.ci_model_info(h).decode())
        self.device = torch.device("cuda", engine.index)
        self.styles = tuple(self.info["styles"])
        self.max_batch = self.info["max_batch"]

    def __del__(self):
        h = getattr(self, "handle", None)
        if h:
            self.lib.ci_model_destroy(h)
            self.handle = None

    def warmup(self, masks=None, batches=None) -> None:
        m = None if masks is None else (ctypes.c_uint32 * len(masks))(*masks)
        b = None if batches is None else (ctypes.c_int32 * len(batches))(*batches)
        r = self.lib.ci_model_warmup(self.handle, m, 0 if masks is None else len(masks), b,
                                     0 if batches is None else len(batches))
        if r != 0:
            raise EngineError(f"ci_model_warmup: {_err(self.lib)} ({r})")

    def stats(self) -> dict:
        v = (ctypes.c_double * 8)()
        n = self.lib.ci_model_stats(self.handle, v, 8)
        keys = ("requests", "windows", "graph_launches", "eager_launches", "mask_readbacks", "graphs", "split_batches")
        return {k: v[i] for i, k in enumerate(keys[:n])}

    def mask_of(self, styles) -> int:
        return sum(1 << self.styles.index(s) for s in styles)

    def _run(self, inputs, out, mask: int | None, all_experts: bool, sync: bool) -> int:
        req = Request()
        req.batch = out.shape[0]
        flags = RUN_STREAM                    # ordered on torch's current stream (0 = the legacy default stream)
        if all_experts:
            flags |= RUN_MASK_ALL
        elif mask is not None:
            flags |= RUN_MASK_GIVEN
            req.mask = mask
        if sync:
            flags |= RUN_SYNC
        req.flags = flags
        for i, t in enumerate(inputs):
            req.inputs[i] = None if t is None else t.data_ptr()
        req.outputs[0] = out.data_ptr()
        req.stream = torch.cuda.current_stream(self.device).cuda_stream
        got = ctypes.c_uint32(0)
        req.out_mask = ctypes.pointer(got)
        r = self.lib.ci_run(self.handle, ctypes.byref(req))
        if r != 0:
            raise EngineError(f"ci_run({self.path.name}): {_err(self.lib)} ({r})")
        return got.value

    @staticmethod
    def _f32(t: torch.Tensor | None) -> torch.Tensor | None:
        return None if t is None else t.float().contiguous()

    def flow(self, cond: torch.Tensor, noise: torch.Tensor, gates: torch.Tensor | None = None, *,
             mask: int | None = None, all_experts: bool = False, sync: bool = False,
             return_mask: bool = False):
        """cond (B, n_cond, H, W), noise (B, 1, H, W), gates (B, n_styles_total, H, W) or None -> x (B, 1, H, W) fp32,
        on torch's current stream (no host sync unless the expert mask is read from the gates)."""
        b, _, h, w = noise.shape
        assert (h, w) == (self.info["H"], self.info["W"]) and b <= self.max_batch, (noise.shape, self.info)
        with torch.cuda.device(self.device):
            out = torch.empty(b, 1, h, w, device=self.device, dtype=torch.float32)
            ins = (self._f32(cond), self._f32(noise), self._f32(gates))
            got = self._run(ins, out, mask, all_experts, sync)
        return (out, got) if return_mask else out

    def forward(self, x: torch.Tensor, sync: bool = False) -> torch.Tensor:
        b, _, h, w = x.shape
        assert (h, w) == (self.info["H"], self.info["W"]) and b <= self.max_batch
        with torch.cuda.device(self.device):
            out = torch.empty(b, self.info["n_out"], h, w, device=self.device, dtype=torch.float32)
            self._run((self._f32(x),), out, None, False, sync)
        return out
