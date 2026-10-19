"""Build and load the project's own native kernels (kernels/csrc/): no Triton, no nvcc, no torch extension.

CUDA  -- `.cu` sources are compiled at run time by NVRTC (the libnvrtc that ships in torch's
         `nvidia-cuda-nvrtc` wheel) to a cubin for the device's exact architecture, loaded through the
         CUDA driver API (libcuda) into the primary context torch uses, and launched with
         `cuLaunchKernel` on torch's current stream -- so the launches are captured by torch's CUDA
         graphs like any other kernel.
CPU   -- `.cpp` sources are compiled by the system C++ compiler (`$CXX`, else c++ / g++ / clang++) into
         a shared library loaded with ctypes.

Both land in the kernel cache (`$TGM_KERNEL_CACHE`, default `Models/kernel_cache/`), keyed by a hash
of the source, the options and the target (GPU architecture / compiler + CPU flags), and are written
atomically, so concurrent processes and later runs reuse them. The cache only ever holds build
output: deleting it costs one rebuild.
"""
from __future__ import annotations

import ctypes
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path

CSRC = Path(__file__).resolve().parent / "csrc"
_LOCK = threading.RLock()


def cache_dir() -> Path:
    env = os.getenv("TGM_KERNEL_CACHE")
    if env:
        d = Path(env)
    else:
        from terrain_slm.paths import MODELS_DIR
        d = MODELS_DIR / "kernel_cache"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _digest(*parts) -> str:
    h = hashlib.sha256()
    for p in parts:
        h.update(p if isinstance(p, bytes) else str(p).encode())
        h.update(b"\0")
    return h.hexdigest()[:20]


def _read_source(path: Path, seen=None) -> str:
    """Source with local `#include "..."` files inlined (NVRTC gets one self-contained program, and
    the cache key covers every included file)."""
    seen = set() if seen is None else seen
    out = []
    for line in path.read_text().splitlines():
        s = line.strip()
        if s.startswith('#include "'):
            inc = (path.parent / s.split('"')[1]).resolve()
            if inc not in seen:
                seen.add(inc)
                out.append(_read_source(inc, seen))
            continue
        out.append(line)
    return "\n".join(out) + "\n"


def _write_atomic(path: Path, data: bytes) -> None:
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name + ".")
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def disabled() -> bool:
    return os.getenv("TGM_KERNELS", "1").strip().lower() in ("0", "false", "off", "no")


# =============================================================================== CPU (C++ / ctypes)
_CPU_LIBS: dict = {}


def _cpu_flags() -> tuple[str, ...]:
    try:
        flags = Path("/proc/cpuinfo").read_text().split("flags", 1)[1].split("\n", 1)[0].split()
    except (OSError, IndexError):
        flags = []
    if "avx2" in flags and "fma" in flags:
        return ("-march=x86-64-v3", "-DTGM_AVX2=1")
    return ("-march=x86-64-v2",) if "sse4_2" in flags else ()


def compiler() -> str | None:
    for c in (os.getenv("CXX"), "c++", "g++", "clang++"):
        if c and shutil.which(c):
            return shutil.which(c)
    return None


def cpu_available() -> bool:
    return not disabled() and compiler() is not None


def cpu_library(name: str, extra_flags: tuple[str, ...] = ()) -> ctypes.CDLL:
    """Compile (once, cached) and load `csrc/<name>.cpp` as a shared library."""
    with _LOCK:
        if name in _CPU_LIBS:
            return _CPU_LIBS[name]
        cxx = compiler()
        if cxx is None:
            raise RuntimeError("no C++ compiler found (set $CXX)")
        src = _read_source(CSRC / f"{name}.cpp")
        flags = ("-O3", "-std=c++17", "-shared", "-fPIC", "-fno-math-errno", "-ffp-contract=off",
                 "-pthread", "-fvisibility=hidden") + _cpu_flags() + tuple(extra_flags)
        ver = subprocess.run([cxx, "--version"], capture_output=True, text=True).stdout.split("\n", 1)[0]
        out = cache_dir() / f"{name}-{_digest(src, flags, ver)}.so"
        if not out.exists():
            with tempfile.TemporaryDirectory(dir=cache_dir()) as td:
                tsrc = Path(td) / f"{name}.cpp"
                tsrc.write_text(src)
                tlib = Path(td) / "lib.so"
                r = subprocess.run([cxx, *flags, str(tsrc), "-o", str(tlib)], capture_output=True, text=True)
                if r.returncode != 0:
                    raise RuntimeError(f"compiling {name}.cpp failed:\n{r.stderr[-4000:]}")
                os.replace(tlib, out)
        lib = ctypes.CDLL(str(out))
        _CPU_LIBS[name] = lib
        return lib


# =============================================================================== CUDA (NVRTC + driver API)
_nvrtc = None
_cuda = None


def _load_nvrtc():
    global _nvrtc
    if _nvrtc is None:
        cands = []
        try:
            import nvidia.cuda_nvrtc as pkg
            for p in pkg.__path__:
                cands += sorted(Path(p, "lib").glob("libnvrtc.so*"))
        except ImportError:
            pass
        cands += ["libnvrtc.so.12", "libnvrtc.so.13", "libnvrtc.so"]
        err = None
        for c in cands:
            if "builtins" in str(c) or ".alt." in str(c):
                continue
            try:
                _nvrtc = ctypes.CDLL(str(c))
                break
            except OSError as e:
                err = e
        if _nvrtc is None:
            raise RuntimeError(f"libnvrtc not found ({err})")
        _nvrtc.nvrtcGetErrorString.restype = ctypes.c_char_p
    return _nvrtc


def _load_cuda():
    global _cuda
    if _cuda is None:
        _cuda = ctypes.CDLL("libcuda.so.1")
        _cuda.cuGetErrorString.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_char_p)]
        _cuda.cuLaunchKernel.argtypes = [ctypes.c_void_p] + [ctypes.c_uint] * 7 + [ctypes.c_void_p] * 3
        _cuda.cuModuleGetFunction.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p, ctypes.c_char_p]
        _cuda.cuModuleLoadData.argtypes = [ctypes.POINTER(ctypes.c_void_p), ctypes.c_char_p]
        _cuda.cuFuncSetAttribute.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
        _cuda.cuCtxGetCurrent.argtypes = [ctypes.POINTER(ctypes.c_void_p)]
    return _cuda


def _check_nvrtc(res: int, prog=None) -> None:
    if res != 0:
        msg = _load_nvrtc().nvrtcGetErrorString(res).decode()
        if prog is not None:
            n = ctypes.c_size_t()
            _nvrtc.nvrtcGetProgramLogSize(prog, ctypes.byref(n))
            buf = ctypes.create_string_buffer(n.value)
            _nvrtc.nvrtcGetProgramLog(prog, buf)
            msg += "\n" + buf.value.decode(errors="replace")[-6000:]
        raise RuntimeError(f"NVRTC: {msg}")


def _check(res: int, what: str) -> None:
    if res != 0:
        s = ctypes.c_char_p()
        _load_cuda().cuGetErrorString(res, ctypes.byref(s))
        raise RuntimeError(f"CUDA driver {what}: {s.value.decode() if s.value else res}")


def cuda_available(device) -> bool:
    """Native CUDA kernels usable on `device`: a CUDA device of sm_80+ (fp16 mma, cp.async), NVRTC
    and libcuda loadable, not disabled by TGM_KERNELS=0."""
    import torch
    if disabled():
        return False
    dev = torch.device(device)
    if dev.type != "cuda" or not torch.cuda.is_available():
        return False
    if torch.cuda.get_device_capability(dev) < (8, 0):
        return False
    try:
        _load_nvrtc()
        _load_cuda()
    except (RuntimeError, OSError):
        return False
    return True


def _compile_cubin(src: str, name: str, names: list[str], opts: list[str], cc: tuple[int, int]) -> tuple[bytes, dict, bool]:
    nv = _load_nvrtc()
    n_arch = ctypes.c_int()
    nv.nvrtcGetNumSupportedArchs(ctypes.byref(n_arch))
    archs = (ctypes.c_int * n_arch.value)()
    nv.nvrtcGetSupportedArchs(archs)
    want = cc[0] * 10 + cc[1]
    real = want in list(archs)
    # A GPU newer than this NVRTC: PTX for the newest known virtual arch, JIT-compiled by the driver.
    arch = f"--gpu-architecture=sm_{want}" if real else f"--gpu-architecture=compute_{max(a for a in archs if a <= want)}"
    prog = ctypes.c_void_p()
    _check_nvrtc(nv.nvrtcCreateProgram(ctypes.byref(prog), src.encode(), f"{name}.cu".encode(), 0, None, None))
    try:
        for n in names:
            _check_nvrtc(nv.nvrtcAddNameExpression(prog, n.encode()))
        all_opts = [arch, "-std=c++17", "--device-as-default-execution-space"] + opts
        carr = (ctypes.c_char_p * len(all_opts))(*[o.encode() for o in all_opts])
        _check_nvrtc(nv.nvrtcCompileProgram(prog, len(all_opts), carr), prog)
        lowered = {}
        for n in names:
            p = ctypes.c_char_p()
            _check_nvrtc(nv.nvrtcGetLoweredName(prog, n.encode(), ctypes.byref(p)))
            lowered[n] = p.value.decode()
        size = ctypes.c_size_t()
        if real:
            _check_nvrtc(nv.nvrtcGetCUBINSize(prog, ctypes.byref(size)))
            buf = ctypes.create_string_buffer(size.value)
            _check_nvrtc(nv.nvrtcGetCUBIN(prog, buf))
        else:
            _check_nvrtc(nv.nvrtcGetPTXSize(prog, ctypes.byref(size)))
            buf = ctypes.create_string_buffer(size.value)
            _check_nvrtc(nv.nvrtcGetPTX(prog, buf))
        return buf.raw, lowered, real
    finally:
        nv.nvrtcDestroyProgram(ctypes.byref(prog))


class CudaModule:
    """One NVRTC program (csrc/<name>.cu) with a list of kernel name expressions (template
    instantiations), compiled for one device and loaded into its primary context."""

    def __init__(self, name: str, names: list[str], device, opts: tuple[str, ...] = ()):
        import torch
        self.device = torch.device(device)
        cc = torch.cuda.get_device_capability(self.device)
        src = _read_source(CSRC / f"{name}.cu")
        names = sorted(set(names))
        key = _digest(src, names, opts, cc, "nvrtc-v1")
        cdir = cache_dir()
        bin_path, meta_path = cdir / f"{name}-{key}.bin", cdir / f"{name}-{key}.json"
        with _LOCK:
            if bin_path.exists() and meta_path.exists():
                image, lowered = bin_path.read_bytes(), json.loads(meta_path.read_text())
            else:
                image, lowered, _ = _compile_cubin(src, name, names, list(opts), cc)
                _write_atomic(bin_path, image)
                _write_atomic(meta_path, json.dumps(lowered).encode())
            cu = _load_cuda()
            with torch.cuda.device(self.device):
                torch.cuda.current_stream(self.device)   # makes torch's primary context current on this thread
                torch.zeros(1, device=self.device)
                mod = ctypes.c_void_p()
                _check(cu.cuModuleLoadData(ctypes.byref(mod), image if image.endswith(b"\0") else image + b"\0"),
                       f"cuModuleLoadData({name})")
            self._mod = mod
            self._fns = {}
            for n, low in lowered.items():
                f = ctypes.c_void_p()
                _check(cu.cuModuleGetFunction(ctypes.byref(f), mod, low.encode()), f"cuModuleGetFunction({n})")
                self._fns[n] = f
            self._smem_set = {}

    def function(self, name: str) -> ctypes.c_void_p:
        return self._fns[name]

    def launch(self, name: str, grid, block, args: ctypes.Structure, smem: int = 0, stream=None) -> None:
        """Launch kernel `name` with one by-value struct argument `args`."""
        import torch
        f = self._fns[name]
        cu = _load_cuda()
        if smem > 48 * 1024 and self._smem_set.get(name, 0) < smem:
            _check(cu.cuFuncSetAttribute(f, 8, smem), "cuFuncSetAttribute(max dynamic smem)")   # 8 = MAX_DYNAMIC_SHARED_SIZE_BYTES
            self._smem_set[name] = smem
        if stream is None:
            stream = torch.cuda.current_stream(self.device).cuda_stream
        params = (ctypes.c_void_p * 1)(ctypes.addressof(args))
        g = tuple(grid) + (1,) * (3 - len(grid))
        b = tuple(block) + (1,) * (3 - len(block))
        _check(cu.cuLaunchKernel(f, g[0], g[1], g[2], b[0], b[1], b[2], smem, stream, params, None),
               f"cuLaunchKernel({name})")


_MODULES: dict = {}


def cuda_module(name: str, names: list[str], device, opts: tuple[str, ...] = ()) -> CudaModule:
    import torch
    dev = torch.device(device)
    if dev.index is None:
        dev = torch.device("cuda", torch.cuda.current_device())
    key = (name, tuple(sorted(set(names))), dev.index, tuple(opts))
    with _LOCK:
        if key not in _MODULES:
            _MODULES[key] = CudaModule(name, names, dev, opts)
        return _MODULES[key]
