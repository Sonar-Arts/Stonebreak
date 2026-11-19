"""The native generator (libstonebreak_terrain, C ABI sbtg_gen_*): Python's WorldGenerator2 + TileBuilder, method for
method, in C++ (design: Dev Working/docs/DaedalusTGM-Exp-2-Native-Engine.md, "phase 2 complete").

    ng = NativeGen(gen)                       # binds to the Python generator `gen` (same device, engine runtime)
    ng.route("controls")                      # this method now runs natively ("*" = every implemented one)
    out = ng.call("controls", [ci0, cj0, hc, wc])        # {name: torch tensor}
    ng.parity("controls", [ci0, cj0, hc, wc])            # bitwise report against gen.controls(...)

Methods not routed native go back to `gen` through the python callback (their results are copied into the native
generator), so any single method can be ported, parity-tested and switched on alone. Method names and argument order
are Python's; `PY_CALLS` adapts methods whose Python signature is not (ints..., floats..., tensors...).

The manifest (constants, blur taps, models) is built by `native_manifest.build(gen)`.
"""
from __future__ import annotations

import ctypes
import json
import math

import torch

from terrain_slm.export import native_pipeline as NP

DTYPES = {torch.float32: 0, torch.float64: 1, torch.int32: 2, torch.int64: 3, torch.uint8: 4, torch.bool: 5,
          torch.int16: 6, torch.float16: 7}
TORCH = {v: k for k, v in DTYPES.items()}


#: Generator attributes that change tiles without a seed / layout change (their setters clear every LRU).
KNOBS = ("relief_gain", "hill_transfer", "relief_preblur", "lowland_boost", "hill_relief", "sea_detail", "sr15_gain")


class Tensor(ctypes.Structure):
    _fields_ = [("data", ctypes.c_void_p), ("dtype", ctypes.c_int32), ("ndim", ctypes.c_int32),
                ("shape", ctypes.c_int64 * 4), ("host", ctypes.c_int32), ("reserved", ctypes.c_int32)]


PYFN = ctypes.CFUNCTYPE(ctypes.c_int32, ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int64),
                        ctypes.c_int32, ctypes.POINTER(ctypes.c_double), ctypes.c_int32, ctypes.POINTER(Tensor),
                        ctypes.c_int32, ctypes.c_void_p)

#: Python calls whose signature is not (ints..., floats..., tensors...): method -> fn(gen, ints, floats, tensors).
PY_CALLS = {
    "_flatten_swamps": lambda g, i, f, t: g._flatten_swamps(t[0], *i),
    "climate_native": lambda g, i, f, t: g.climate_native(*i, t[0]),
}


def py_calls() -> dict:
    """PY_CALLS plus every `PY_CALLS` dict of the stage manifest modules (export/native_manifest_<stage>.py)."""
    import importlib
    import pkgutil

    import terrain_slm.export as pkg
    out = dict(PY_CALLS)
    for info in sorted(pkgutil.iter_modules(pkg.__path__), key=lambda i: i.name):
        if info.name.startswith("native_manifest_"):
            out.update(getattr(importlib.import_module(f"terrain_slm.export.{info.name}"), "PY_CALLS", {}))
    return out


def _bind(lib):
    if getattr(lib, "_gen_bound", False):
        return lib
    V, I32, I64, P = ctypes.c_void_p, ctypes.c_int32, ctypes.c_int64, ctypes.POINTER
    lib.sbtg_gen_create.argtypes = [V, ctypes.c_char_p, V]
    lib.sbtg_gen_create.restype = V
    lib.sbtg_gen_destroy.argtypes = [V]
    lib.sbtg_gen_set_state.argtypes = [V, ctypes.c_char_p]
    lib.sbtg_gen_set_python.argtypes = [V, PYFN, V]
    lib.sbtg_gen_set_model.argtypes = [V, ctypes.c_char_p, V]
    lib.sbtg_gen_route.argtypes = [V, ctypes.c_char_p, I32]
    lib.sbtg_gen_clear_caches.argtypes = [V]
    lib.sbtg_gen_call.argtypes = [V, ctypes.c_char_p, P(I64), I32, P(ctypes.c_double), I32, P(Tensor), I32, P(V)]
    lib.sbtg_gen_methods.argtypes = [ctypes.c_char_p, I32]
    lib.sbtg_gen_stats.argtypes = [V, ctypes.c_char_p, I32]
    lib.sbtg_fields_count.argtypes = [V]
    lib.sbtg_fields_name.argtypes = [V, I32]
    lib.sbtg_fields_name.restype = ctypes.c_char_p
    lib.sbtg_fields_get.argtypes = [V, I32, P(Tensor)]
    lib.sbtg_fields_add.argtypes = [V, ctypes.c_char_p, P(Tensor)]
    lib.sbtg_fields_free.argtypes = [V]
    for n in ("sbtg_gen_set_state", "sbtg_gen_set_python", "sbtg_gen_set_model", "sbtg_gen_route",
              "sbtg_gen_clear_caches", "sbtg_gen_call", "sbtg_gen_methods", "sbtg_gen_stats", "sbtg_fields_count",
              "sbtg_fields_get", "sbtg_fields_add"):
        getattr(lib, n).restype = I32
    lib._gen_bound = True
    return lib


def to_c(t: torch.Tensor) -> Tensor:
    if t.dim() > 4:
        raise ValueError(f"native tensors have <= 4 dims, got {tuple(t.shape)}")
    if t.dtype not in DTYPES:
        raise ValueError(f"dtype {t.dtype} has no native code")
    c = Tensor()
    c.data = t.data_ptr()
    c.dtype = DTYPES[t.dtype]
    c.ndim = t.dim()
    for k in range(4):
        c.shape[k] = t.shape[k] if k < t.dim() else 1
    c.host = 0 if t.is_cuda else 1
    return c


def implemented() -> list[str]:
    lib = _bind(NP.library())
    n = lib.sbtg_gen_methods(None, 0)
    buf = ctypes.create_string_buffer(n + 1)
    lib.sbtg_gen_methods(buf, n + 1)
    return [s for s in buf.value.decode().split("\n") if s]


class NativeGenError(RuntimeError):
    pass


class NativeGen:
    def __init__(self, gen, manifest: dict | None = None, engine=None):
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.export import native_manifest as NM

        self.gen = gen
        self.engine = engine or (gen._cenda.engine if getattr(gen, "_cenda", None) is not None else
                                 E.Engine.get(gen.device))
        self.tp = NP.TerrainPipeline(self.engine)
        self.lib = _bind(self.tp.lib)
        self.device = torch.device(gen.device)
        self.manifest = manifest if manifest is not None else NM.build(gen)
        stream = torch.cuda.current_stream(self.device).cuda_stream
        h = self.lib.sbtg_gen_create(self.tp.handle, json.dumps(self.manifest).encode(), ctypes.c_void_p(stream))
        if not h:
            raise NativeGenError(f"sbtg_gen_create: {NP._err(self.lib)}")
        self.handle = h
        self.py_calls = py_calls()
        self._keep = []                      # tensors handed to C++ during the current callback
        self._errors = []
        self._cb = PYFN(self._python)        # keep the trampoline alive
        self._check(self.lib.sbtg_gen_set_python(h, self._cb, None), "set_python")
        be = getattr(gen, "_cenda", None)
        if be is not None:                   # share the engine models the backend already loaded
            for name, m in be.models.items():
                self.lib.sbtg_gen_set_model(h, name.encode(), ctypes.c_void_p(m.handle))
        self.sync_state()

    def __del__(self):
        h = getattr(self, "handle", None)
        if h:
            self.lib.sbtg_gen_destroy(h)
            self.handle = None

    def _check(self, r: int, what: str) -> None:
        if r != 0:
            extra = ("; python: " + " | ".join(self._errors)) if self._errors else ""
            self._errors.clear()
            raise NativeGenError(f"sbtg {what}: {NP._err(self.lib)}{extra}")

    # ---------------------------------------------------------------- state / routing
    def state(self, world=None) -> dict:
        """The per-world host state. `world`: the WorldConfig being built (TileBuilder.world), sent as "world"
        (WorldConfig.to_dict(): the native side derives the block scale and tile geometry from it alone)."""
        g = self.gen
        sc = getattr(g, "scale", None)
        st = {"seed": int(g.seed), "climate_layout": getattr(g, "climate_layout", "belts"),
              "sea_shelf": bool(getattr(g, "sea_shelf", False)),
              "px_per_block": float(sc.px_per_block) if sc is not None else 0.0,
              "block_scale": _scale_dict(sc)}
        st["look_build"] = bool(getattr(g, "look_build", False))     # FX4 block look (generator2.use_block_look)
        # Generator knobs (A/B setters such as use_relief_gain): a change clears the native caches, as Python's
        # setters clear its LRUs.
        st["knobs"] = {k: float(getattr(g, k)) for k in KNOBS if isinstance(getattr(g, k, None), (int, float))}
        if "lowland_boost" in st["knobs"]:
            st["lowland_boost"] = st["knobs"]["lowland_boost"]
        if world is not None:
            st["world"] = world.to_dict()
            st["world_fingerprint"] = world.fingerprint()
        return st

    def sync_state(self, world=None) -> None:
        if world is not None:
            self.gen._native_world = world     # PY_CALLS adapters build the matching Python BlockScale / TileBuilder
        self._check(self.lib.sbtg_gen_set_state(self.handle, json.dumps(self.state(world)).encode()), "set_state")

    def route(self, method: str = "*", native: bool = True) -> None:
        self._check(self.lib.sbtg_gen_route(self.handle, method.encode(), int(native)), f"route {method}")

    def clear_caches(self) -> None:
        self._check(self.lib.sbtg_gen_clear_caches(self.handle), "clear_caches")

    def stats(self) -> dict:
        n = self.lib.sbtg_gen_stats(self.handle, None, 0)
        buf = ctypes.create_string_buffer(n + 1)
        self.lib.sbtg_gen_stats(self.handle, buf, n + 1)
        return json.loads(buf.value.decode())

    # ---------------------------------------------------------------- calls
    def call(self, method: str, ints=(), floats=(), tensors=()) -> dict:
        """The native generator's `method` -> {name: torch tensor} (copies; '' for a single tensor)."""
        ia = (ctypes.c_int64 * max(1, len(ints)))(*[int(v) for v in ints])
        fa = (ctypes.c_double * max(1, len(floats)))(*[float(v) for v in floats])
        ts = [t.contiguous() for t in tensors]
        ta = (Tensor * max(1, len(ts)))(*[to_c(t) for t in ts])
        out = ctypes.c_void_p()
        with torch.cuda.device(self.device):
            self._check(self.lib.sbtg_gen_call(self.handle, method.encode(), ia, len(ints), fa, len(floats), ta,
                                               len(ts), ctypes.byref(out)), method)
            try:
                return self._fields(out)
            finally:
                self.lib.sbtg_fields_free(out)

    def _fields(self, f) -> dict:
        from terrain_slm.export import cenda_engine as E
        lib, elib = self.lib, E.library()
        res = {}
        stream = torch.cuda.current_stream(self.device).cuda_stream
        for i in range(lib.sbtg_fields_count(f)):
            name = lib.sbtg_fields_name(f, i).decode()
            c = Tensor()
            self._check(lib.sbtg_fields_get(f, i, ctypes.byref(c)), "fields_get")
            shape = [c.shape[k] for k in range(c.ndim)]
            t = torch.empty(shape, dtype=TORCH[c.dtype], device="cpu" if c.host else self.device)
            nbytes = t.numel() * t.element_size()
            if nbytes:
                r = elib.ci_memcpy(ctypes.c_void_p(self.engine.handle), ctypes.c_void_p(t.data_ptr()),
                                   ctypes.c_void_p(c.data), ctypes.c_size_t(nbytes), ctypes.c_void_p(stream))
                if r != 0:
                    raise NativeGenError(f"ci_memcpy: {E._err(elib)}")
            res[name] = t
        return res

    def _python(self, user, method, ia, n_i, fa, n_f, ta, n_t, out) -> int:
        try:
            m = method.decode()
            ints = [ia[k] for k in range(n_i)]
            floats = [fa[k] for k in range(n_f)]
            tensors = [_from_c(ta[k], self.device) for k in range(n_t)]
            fn = self.py_calls.get(m)
            with torch.no_grad():
                r = fn(self.gen, ints, floats, tensors) if fn is not None else getattr(self.gen, m)(*ints, *floats, *tensors)
            for name, t in _flatten(r):
                t = t.contiguous()
                self._keep.append(t)
                if self.lib.sbtg_fields_add(ctypes.c_void_p(out), name.encode(), ctypes.byref(to_c(t))) != 0:
                    raise NativeGenError(f"fields_add {name}: {NP._err(self.lib)}")
            return 0
        except Exception as e:  # noqa: BLE001 - reported through the native call's error
            import traceback
            self._errors.append(f"{method.decode()}: {e!r}\n{traceback.format_exc(limit=6)}")
            return 1
        finally:
            # The copies run on the generator's stream (torch's current stream): torch may reuse this memory as soon
            # as the references drop, in stream order.
            self._keep.clear()

    # ---------------------------------------------------------------- parity
    def parity(self, method: str, ints=(), floats=(), tensors=(), native: bool = True) -> dict:
        """Runs `method` natively and in Python; per field: shape, bitwise equality, max |diff| (NaN-aware)."""
        if native:
            self.route(method, True)
        try:
            got = self.call(method, ints, floats, tensors)
        finally:
            if native:
                self.route(method, False)
        fn = self.py_calls.get(method)
        with torch.no_grad():
            ref = fn(self.gen, list(ints), list(floats), list(tensors)) if fn is not None else \
                getattr(self.gen, method)(*ints, *floats, *tensors)
        ref = dict(_flatten(ref))
        rep = {}
        for k in sorted(set(got) | set(ref)):
            a, b = got.get(k), ref.get(k)
            if a is None or b is None:
                rep[k] = {"missing": "native" if a is None else "python"}
                continue
            b = b.to(a.device)
            if a.shape != b.shape or a.dtype != b.dtype:
                rep[k] = {"shape": [tuple(a.shape), tuple(b.shape)], "dtype": [str(a.dtype), str(b.dtype)]}
                continue
            same = bool(torch.equal(a.view(torch.uint8) if a.is_floating_point() else a,
                                    b.view(torch.uint8) if b.is_floating_point() else b)) if a.numel() else True
            d = 0.0
            if a.numel() and not same:
                if a.is_floating_point():
                    nan = torch.isnan(a) ^ torch.isnan(b)
                    diff = (a.double() - b.double()).abs().nan_to_num(0.0)
                    d = float(diff.max()) if not bool(nan.any()) else math.inf
                else:
                    d = int((a != b).sum())
            rep[k] = {"bitwise": same, "max_diff": d, "shape": tuple(a.shape)}
        return rep


def _from_c(c: Tensor, device) -> torch.Tensor:
    """A torch view of a native tensor handed to the python callback (valid during the callback)."""
    shape = [c.shape[k] for k in range(c.ndim)]
    dt = TORCH[c.dtype]
    n = 1
    for s in shape:
        n *= s
    if n == 0:
        return torch.empty(shape, dtype=dt, device="cpu" if c.host else device)
    # Wrap the pointer through __cuda_array_interface__ / numpy (no copy).
    if c.host:
        import numpy as np
        np_dt = torch.empty(0, dtype=dt).numpy().dtype
        buf = (ctypes.c_byte * (n * np_dt.itemsize)).from_address(c.data)
        return torch.from_numpy(np.frombuffer(buf, dtype=np_dt).reshape(shape))
    holder = _CudaArray(c.data, shape, dt)
    return torch.as_tensor(holder, device=device)


class _CudaArray:
    _TYPESTR = {torch.float32: "<f4", torch.float64: "<f8", torch.int32: "<i4", torch.int64: "<i8",
                torch.uint8: "|u1", torch.bool: "|b1", torch.int16: "<i2", torch.float16: "<f2"}

    def __init__(self, ptr, shape, dtype):
        self.__cuda_array_interface__ = {"data": (ptr, False), "shape": tuple(shape),
                                         "typestr": self._TYPESTR[dtype], "version": 2, "strides": None,
                                         "stream": None}


def _flatten(r):
    """Python method result -> [(name, tensor)]: dict items (None dropped, numbers as 1-element tensors), a tensor as
    '', a tuple / list as '0', '1', ...; nested dicts / tuples join names with '.'."""
    out = []

    def add(name, v):
        if v is None:
            return
        if isinstance(v, torch.Tensor):
            out.append((name, v))
        elif isinstance(v, dict):
            for k, x in v.items():
                add(f"{name}.{k}" if name else str(k), x)
        elif isinstance(v, (tuple, list)):
            for k, x in enumerate(v):
                add(f"{name}.{k}" if name else str(k), x)
        elif isinstance(v, bool):
            out.append((name, torch.tensor([v])))
        elif isinstance(v, int):
            out.append((name, torch.tensor([v], dtype=torch.int64)))
        elif isinstance(v, float):
            out.append((name, torch.tensor([v], dtype=torch.float64)))
        elif hasattr(v, "__dict__"):                 # dataclasses (RiverField, RiverContext, ...): their tensors
            for k, x in vars(v).items():
                if isinstance(x, (torch.Tensor, dict, tuple, list, int, float, bool)) or x is None:
                    add(f"{name}.{k}" if name else k, x)
    add("", r)
    return out


def _scale_dict(sc) -> dict:
    if sc is None:
        return {}
    out = {}
    for k, v in vars(sc).items():
        if isinstance(v, (int, float, bool, str)):
            out[k] = v
        elif isinstance(v, torch.Tensor) and v.numel() <= 4096:
            out[k] = v.detach().cpu().double().flatten().tolist()
    return out
