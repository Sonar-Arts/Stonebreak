"""Generator backend: the EXP-2 networks on the Cenda inference engine (phase 1 of the native engine;
Dev Working/docs/DaedalusTGM-Exp-2-Native-Engine.md). OFF by default:

    TGM_ENGINE=cenda python -m terrain_slm.tgmpipe ...        (generator2.CENDA_ENGINE)

`install(gen)` is called by WorldGenerator2.__init__ when the flag is on. It exports the generator's networks to .cim
models (cached under Models/kernel_cache/cenda_models/<key>/, the key covers the checkpoints, window shapes, step counts, the
exporter and every kernel source; derived output, like compiled kernels), loads them on the generator's device and reroutes the generator's network calls:

  detail   _sample(self.detail, ...)      -> engine flow model (gated experts, MeanFlow; one graph per expert subset);
                                             the FP8 plan (export_detail2_fp8) when the generator runs the FP8 sampler
  relief   _sample(self.relief, ...)      -> engine flow model (FM or MeanFlow FiLM tables)
  SR-30    _sr_sample(self.sr30, ...)     -> engine flow model (SR window shape only)
  SR-15    _sr_sample(self.sr15, ...)     -> engine flow model (1:1 worlds; same exporter as SR-30)
  planner  self.planner(xs)               -> engine ViT model (fp32, batch 32)
  climate  self.climate_net(x)            -> engine forward model
  hydro    self.hydro(x)                  -> engine forward model (fp16; torch ran it in bf16 autocast)

TGM_ENGINE_NETS=detail,relief,... limits the engine to those networks (parity runs: with only the bit-exact ones, whole
tiles equal the default path's). Anything the engine cannot serve (another window shape or step count, an engine
error) falls through to the
generator's own path, logged once. The engine's numbers: detail / relief / SR are bit-identical to the Python native
samplers they are recorded from (the :kd1 / :kc1 / :ks1 paths); the climate node runs fp16 where torch ran fp32
(~4e-4 RMS in normalised units), the planner fp32 in a different summation order (~3 mm height RMS vs fp64). Tiles
therefore get the model_id marker ":ce1" and their own cache entries.
"""
from __future__ import annotations

import hashlib
import json
import logging
import math
import os
import threading
import time
from pathlib import Path

import torch

log = logging.getLogger("tgmpipe.cenda")

MARKER = ":ce1"
EXPORT_VERSION = 1
# Windows per engine request (activation buffers are sized for it; batched numbers == single-window numbers).
# TGM_ENGINE_BATCH="relief=8,detail=2" overrides. Per window: detail ~294 MiB, relief ~100 MiB, SR ~60 MiB, planner 3.5 MiB.
# Measured 2026-11-19 (kernel time per window, CUPTI): relief 41.6 / 41.1 / 38.5 / 40.7 ms at B = 1 / 2 / 4 / 8 -- these
# window sizes already fill the GPU at B = 1, so batching buys nothing but VRAM: off by default (PREFETCH too).
BATCH = {"relief": 1, "detail": 1, "sr30": 1, "sr15": 1, "planner": 32, "climate": 1, "hydro": 1}
for _kv in filter(None, os.getenv("TGM_ENGINE_BATCH", "").split(",")):
    _k, _v = _kv.split("=")
    BATCH[_k.strip()] = int(_v)
# Detail expert subset per window: "auto" reads it from the gates (one 4-byte readback = a stream sync per window;
# numbers bit-identical to kernels/cuda_detail2.py, :kd1), "enabled" runs every enabled expert on every window (no
# sync; deterministic, but a gated-off expert changes the last fp16 bits of the SiLU the next layer reads).
MASK_MODE = os.getenv("TGM_ENGINE_MASK", "auto")
CONTEXTS = int(os.getenv("TGM_ENGINE_CONTEXTS", "1"))      # concurrent requests per model (the service has one GPU thread)


# Export directories kept in the cache, most recently USED first (FX2, 2026-11-19: was the 4 newest by creation, so
# A/B-ing default / phase 2 / FP8 / dev variants evicted the one in use and a service start re-exported, 18 s instead
# of 7-9 s). An export is ~180 MB: bounded by count and by total size, whichever bites first.
KEEP_EXPORTS = int(os.getenv("TGM_ENGINE_KEEP", "12"))
KEEP_EXPORTS_MB = int(os.getenv("TGM_ENGINE_KEEP_MB", "4096"))


def _models_dir() -> Path:
    """Exported .cim models: derived build output like compiled kernels, so they live in the (gitignored) kernel
    cache: Models/kernel_cache/cenda_models/<key>/ (TGM_ENGINE_CACHE overrides)."""
    env = os.getenv("TGM_ENGINE_CACHE")
    if env:
        return Path(env)
    from terrain_slm.kernels import native
    return native.cache_dir() / "cenda_models"


def _dir_bytes(d: Path) -> int:
    return sum(f.stat().st_size for f in d.rglob("*") if f.is_file())


def _prune(root: Path, keep: Path) -> None:
    """Drops the least recently used export directories beyond KEEP_EXPORTS / KEEP_EXPORTS_MB (never `keep`, the one
    in use; its mtime is refreshed on every use, so it is the most recent)."""
    import shutil
    try:
        os.utime(keep)                                     # this export was just used: most recent
    except OSError:
        pass
    dirs = sorted((d for d in root.iterdir() if d.is_dir() and d != keep), key=lambda d: d.stat().st_mtime, reverse=True)
    budget = KEEP_EXPORTS_MB * 1024 * 1024 - _dir_bytes(keep)
    for i, d in enumerate(dirs):
        size = _dir_bytes(d)
        if i >= KEEP_EXPORTS - 1 or size > budget:
            shutil.rmtree(d, ignore_errors=True)
        else:
            budget -= size


def _file_key(p: Path) -> list:
    p = Path(p)
    if not p.exists():
        return [str(p), None]
    r = p.resolve()
    st = r.stat()
    return [str(r), st.st_size, st.st_mtime_ns]


def _sources_key() -> str:
    from terrain_slm.export import cenda_export as X
    from terrain_slm.kernels import native
    h = hashlib.sha256()
    for p in sorted(native.CSRC.glob("*.cu*")):
        h.update(p.name.encode())
        h.update(p.read_bytes())
    h.update(Path(X.__file__).read_bytes())
    # Everything the exporter folds into a .cim (review-native F4): the kernels' Python launch code, the model code
    # (folding, FiLM / MeanFlow tables, adapters, layer order) and native.py (source inlining).
    for p in ("cuda_detail2.py", "cuda_unet.py", "cuda_conv.py", "cuda_configs.json", "cuda_sr.py", "native.py",
              # the FP8 detail (export_detail2_fp8): its launch code, configs and the QAD simulation's weight folding
              "cuda_detail2_fp8.py", "cuda_conv_fp8.py", "cuda_configs_fp8.json", "tune_fp8.py"):
        f = native.CSRC.parent / p
        if f.exists():
            h.update(f.read_bytes())
    f = native.CSRC.parents[1] / "quant" / "detail2_sim.py"
    if f.exists():
        h.update(f.read_bytes())
    for f in sorted((native.CSRC.parents[1] / "models").glob("*.py")):
        h.update(f.name.encode())
        h.update(f.read_bytes())
    try:
        h.update(X.cenda_source("ci_vit").encode())
    except OSError:
        pass
    return h.hexdigest()[:24]


class _Proxy(torch.nn.Module):
    """Stands in for a torch module: calls go to the engine, everything else (attributes, .cfg) to the module."""

    def __init__(self, module, call, name: str):
        super().__init__()
        object.__setattr__(self, "_module", module)
        object.__setattr__(self, "_call", call)
        object.__setattr__(self, "_name", name)
        object.__setattr__(self, "_failed", False)

    def __getattr__(self, k):
        return getattr(object.__getattribute__(self, "_module"), k)

    def forward(self, x):
        # No mid-session fallback (review-native F5): planner / climate / hydro numbers differ between the engine and
        # torch, and the model_id marker promises the engine's. An engine error fails the request instead of mixing
        # torch numbers into tiles (and the disk cache) under the same marker.
        return self._call(x)


class CendaBackend:
    def __init__(self, gen):
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.world import generator as G
        from terrain_slm.world import generator2 as G2

        self.gen = gen
        self.engine = E.Engine.get(gen.device)
        self.models: dict = {}
        self.fallbacks: dict = {}
        self.calls: dict = {}
        self.batched: dict = {}           # windows computed in prefetch batches, per network
        self._pf = _Prefetch()
        md = Path(gen.model_dir) if hasattr(gen, "model_dir") else None
        self.specs = {}
        if gen.detail is not None:
            self.specs["detail"] = (G.DETAIL_EXT, gen.cfg.detail_steps, G.DETAIL_T_START)
        if gen.relief is not None:
            self.specs["relief"] = (G.RELIEF_EXT, gen.cfg.relief_steps, 0.0)
        if getattr(gen, "sr30", None) is not None:
            self.specs["sr30"] = (G2.SR_CORE + 2 * G2.SR_APRON, G2.SR_STEPS, 0.0)
        if getattr(gen, "sr15", None) is not None:              # SR-15 (1:1 worlds): same node, its own weights
            self.specs["sr15"] = (G2.SR_CORE + 2 * G2.SR_APRON, G2.SR15_STEPS, 0.0)
        # Detail: exported from the sampler the generator runs -- the FP8 one (generator2.DETAIL_FP8, sm_89+ and the
        # folder's detail_fp8.pt: gen.detail is then the QAD student) or else the fp16 one (generator2 logged why).
        self.detail_fp8 = getattr(getattr(gen, "_detail2_native", None), "__name__", "") == "NativeDetail2SamplerFP8"
        if md is None and getattr(gen, "_cenda_files", None):
            md = Path(gen._cenda_files[0]).parent
        if gen.detail is not None:
            if self.detail_fp8:
                log.info("cenda detail: FP8 plan (kernels/cuda_detail2_fp8.py, e4m3 W8A8)")
            elif md is not None and (md / "detail_fp8.pt").exists():
                log.warning("cenda detail: fp16 plan (the generator does not run the FP8 sampler on %s)", gen.device)
        if getattr(gen, "climate_net", None) is not None:
            self.specs["climate"] = (G2.CLIMATE_WIN, 0, 0.0)
        self.specs["planner"] = (G.WIN, 0, 0.0)
        if getattr(gen, "hydro", None) is not None:
            self.specs["hydro"] = (G.RELIEF_EXT, 0, 0.0)          # drainage windows = relief canonical windows
        nets = [n.strip() for n in os.getenv("TGM_ENGINE_NETS", "").split(",") if n.strip()]
        if nets:                                     # e.g. "detail,relief,sr30": only these on the engine
            self.specs = {k: v for k, v in self.specs.items() if k in nets}
        key = {"v": EXPORT_VERSION, "model_id": gen.model_id, "specs": self.specs, "src": _sources_key(),
               "files": [_file_key(p) for p in getattr(gen, "_cenda_files", [])] +
                        ([_file_key(md / f) for f in ("detail_fp8.pt", "sr15.pt")] if md is not None else []),
               "detail_fp8": self.detail_fp8,
               "experts": sorted(getattr(gen.detail, "adapters", {}).keys()) if gen.detail is not None else [],
               "mf": [bool(getattr(gen.detail, "mean_flow", False)), bool(getattr(gen.relief, "mean_flow", False))]}
        digest = hashlib.sha256(json.dumps(key, sort_keys=True, default=str).encode()).hexdigest()[:20]
        self.dir = _models_dir() / digest
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / "key.json").write_text(json.dumps(key, indent=1, default=str))
        try:
            _prune(self.dir.parent, self.dir)
        except OSError:
            pass
        t0 = time.perf_counter()
        self._export_all()
        t1 = time.perf_counter()
        self._load_all()
        log.info("cenda engine: %d models (%s) exported %.1f s, loaded %.1f s from %s", len(self.models),
                 ", ".join(self.models), t1 - t0, time.perf_counter() - t1, self.dir)

    # ------------------------------------------------------------------ export / load
    def _export_all(self) -> None:
        from terrain_slm.export import cenda_export as X
        gen = self.gen
        dev = gen.device

        def need(name):
            return not (self.dir / f"{name}.cim").exists()

        with torch.no_grad():
            for name, model, fn in (
                    ("detail", gen.detail, lambda m, s: (X.export_detail2_fp8 if self.detail_fp8 else X.export_detail2)(
                        m, s[0], s[0], s[1], s[2], self.dir / "detail.cim", gen.model_id)),
                    ("relief", gen.relief, lambda m, s: X.export_unet(m, s[0], s[0], s[1], s[2], self.dir / "relief.cim",
                                                                      gen.model_id)),
                    ("sr30", getattr(gen, "sr30", None), lambda m, s: X.export_sr(m, s[0], s[0], s[1],
                                                                                   self.dir / "sr30.cim", gen.model_id)),
                    ("sr15", getattr(gen, "sr15", None), lambda m, s: X.export_sr(m, s[0], s[0], s[1],
                                                                                   self.dir / "sr15.cim", gen.model_id)),
                    ("climate", getattr(gen, "climate_net", None),
                     lambda m, s: X.export_climate(m, s[0], s[0], self.dir / "climate.cim", gen.model_id)),
                    ("planner", gen.planner, lambda m, s: X.export_planner(m, s[0], s[0], self.dir / "planner.cim",
                                                                           gen.model_id)),
                    ("hydro", getattr(gen, "hydro", None),
                     lambda m, s: X.export_hydro(m, s[0], s[0], self.dir / "hydro.cim", gen.model_id))):
                if model is None or name not in self.specs or not need(name):
                    continue
                home = next(model.parameters()).device
                if home.type == "cpu":
                    model.to(dev)
                try:
                    fn(model, self.specs[name])
                except Exception as e:  # noqa: BLE001
                    log.warning("cenda export of %s failed (%s): it stays on the generator's own path", name, e)
                finally:
                    if home.type == "cpu":
                        model.to("cpu")
                torch.cuda.empty_cache()

    def _load_all(self) -> None:
        for name in self.specs:
            p = self.dir / f"{name}.cim"
            if not p.exists():
                continue
            batch = BATCH.get(name, 1)
            ctx = 1 if name in ("planner", "climate", "hydro") else CONTEXTS
            try:
                self.models[name] = self.engine.load(p, max_batch=batch, contexts=ctx)
            except Exception as e:  # noqa: BLE001
                log.warning("cenda load of %s failed (%s): it stays on the generator's own path", name, e)

    def warm_up(self) -> None:
        """Every CUDA graph the service will launch, built now (explicit graphs: no capture, safe under any thread)."""
        for name, m in self.models.items():
            if name == "detail":
                on = [i for i, s in enumerate(m.styles)
                      if self.gen.experts_enabled is None or s in self.gen.experts_enabled]
                masks = sorted({sum(1 << on[i] for i in range(len(on)) if k >> i & 1) for k in range(1 << len(on))})
                m.warmup(masks, list(range(1, m.max_batch + 1)))
            else:
                m.warmup([0], list(range(1, m.max_batch + 1)))

    # ------------------------------------------------------------------ routing
    def _note(self, name: str, ok: bool, why: str = "") -> None:
        d = self.calls if ok else self.fallbacks
        d[name] = d.get(name, 0) + 1
        if not ok and d[name] == 1:
            log.warning("cenda %s: generator path used (%s)", name, why)

    def flow(self, name: str, cond, noise, steps, t_start=0.0, gates=None):
        """The engine's flow model `name` for this call, or None when it does not match (shape / steps / t_start)."""
        m = self.models.get(name)
        if m is None:
            return None
        n, st, t0 = self.specs[name]
        if tuple(cond.shape[-2:]) != (n, n) or steps != st or t_start != t0 or cond.shape[0] > m.max_batch:
            self._note(name, False, f"shape {tuple(cond.shape)} steps {steps} t_start {t_start} != export {self.specs[name]}")
            return None
        try:
            if name == "detail" and gates is not None and MASK_MODE == "enabled":
                en = self.gen.experts_enabled
                mk = m.mask_of([x for x in m.styles if en is None or x in en])
                out = m.flow(cond, noise, gates, mask=mk)                        # no readback, no host sync
            else:
                out = m.flow(cond, noise, gates)                             # mask read from the gates (one sync)
        except Exception as e:  # noqa: BLE001
            self._note(name, False, str(e))
            return None
        self._note(name, True)
        return out


def _flow_batch(self, name: str, items: list) -> list:
    """Windows' recorded (cond, noise, gates, steps, t_start) through the engine in batches of up to max_batch; windows
    with different expert subsets go in different launches (grouped here by one device -> host read of every window's
    subset, so the engine needs no per-batch readback). Returns one (1, 1, H, W) tensor per item."""
    m = self.models[name]
    n, st, t0 = self.specs[name]
    for cond, noise, gates, steps, ts in items:
        if tuple(cond.shape) != (1, cond.shape[1], n, n) or steps != st or ts != t0:
            raise ValueError(f"window {tuple(cond.shape)} steps {steps} t_start {ts} != export {self.specs[name]}")
    masks = [0] * len(items)
    if name == "detail" and any(it[2] is not None for it in items):
        ref = next(it[2] for it in items if it[2] is not None)
        g = torch.cat([it[2] if it[2] is not None else torch.zeros_like(ref) for it in items])
        idx = [i for i, s_ in enumerate(D2_STYLES()) if s_ in m.styles]
        on = (g[:, idx] > 0).flatten(2).any(-1).cpu().tolist()       # one sync for every window
        masks = [sum(1 << m.styles.index(D2_STYLES()[idx[j]]) for j, v in enumerate(row) if v) for row in on]
    outs = [None] * len(items)
    groups: dict = {}
    for i, mk in enumerate(masks):
        groups.setdefault(mk, []).append(i)
    for mk, ids in groups.items():
        for c in range(0, len(ids), m.max_batch):
            part = ids[c : c + m.max_batch]
            cond = torch.cat([items[i][0] for i in part])
            noise = torch.cat([items[i][1] for i in part])
            gates = None
            if name == "detail" and items[part[0]][2] is not None:
                gates = torch.cat([items[i][2] for i in part])
            x = m.flow(cond, noise, gates, mask=mk if name == "detail" else None)
            for j, i in enumerate(part):
                outs[i] = x[j : j + 1]
    self._note(name, True)
    return outs


def D2_STYLES():
    from terrain_slm.models import detail2 as D2
    return D2.STYLES


CendaBackend.flow_batch = _flow_batch


# ================================================================================================ window batching
class _Collected(Exception):
    """Raised by the network hook in a prefetch's first pass: the window's inputs are recorded, its work stops."""


class _Prefetch(threading.local):
    def __init__(self):
        self.mode = {}        # network -> "collect" | "replay"
        self.current = {}     # network -> window key being run
        self.items = {}       # network -> {key: inputs} (collect)
        self.results = {}     # network -> {key: output} (replay; consumed by the window's first call)


def _hook(be, name: str, key_inputs):
    """In the network hook: (handled, value). Collect mode records the window's inputs and aborts it; replay mode
    hands back the batched result for the window's FIRST call (a guard retry runs normally)."""
    st = be._pf
    mode = st.mode.get(name)
    if mode == "collect":
        st.items[name][st.current[name]] = key_inputs
        raise _Collected()
    if mode == "replay":
        out = st.results[name].pop(st.current[name], None)
        if out is not None:
            return True, out
    return False, None


def _prefetch(be, name: str, keys: list, cached, run_window, batch_fn) -> None:
    """Compute the windows `keys` (not yet `cached`) in engine batches: pass 1 runs each window's own code until its
    network call and records the inputs; `batch_fn(list of inputs)` returns their outputs; pass 2 runs the windows
    again (their caches fill), each first network call answered from the batch. Batched numbers equal single-window
    numbers bit for bit (no split-K; windows with different expert subsets never share a launch), so tiles are
    unchanged."""
    st = be._pf
    missing = [k for k in keys if not cached(k)]
    if len(missing) < 2 or st.mode.get(name) is not None:
        return
    st.mode[name], st.items[name] = "collect", {}
    try:
        for k in missing:
            st.current[name] = k
            try:
                run_window(k)
            except _Collected:
                pass
        got = [k for k in missing if k in st.items[name]]
        outs = batch_fn([st.items[name][k] for k in got]) if len(got) > 1 else None
    except Exception as e:  # noqa: BLE001 - prefetch is an optimisation: the windows then run one by one
        log.warning("cenda %s prefetch skipped: %s", name, e)
        outs = None
    finally:
        st.mode[name] = None
        st.items[name] = {}
    if outs is None:
        return
    be.batched[name] = be.batched.get(name, 0) + len(got)
    st.mode[name], st.results[name] = "replay", dict(zip(got, outs))
    try:
        for k in got:
            st.current[name] = k
            run_window(k)
    finally:
        st.mode[name] = None
        st.results[name] = {}


PREFETCH = os.getenv("TGM_ENGINE_PREFETCH", "0") not in ("0", "false", "off")   # window batching (see BATCH)


#: Networks whose engine numbers differ from torch's (the others are bit-identical to the Python native samplers).
NON_BITWISE = ("planner", "climate", "hydro")


def marker(gen, be) -> str:
    """":ce1" (":ce1m" in MASK_MODE enabled) when every non-bit-identical network the generator has runs on the engine;
    otherwise ":ce1." + the initials of those that do (review-native F5: an export / load failure at start-up must not
    serve torch numbers under the full marker)."""
    has = [n for n in NON_BITWISE if getattr(gen, {"climate": "climate_net"}.get(n, n), None) is not None]
    on = [n for n in has if n in be.models]
    tag = MARKER + ("m" if MASK_MODE == "enabled" else "")
    return tag if on == has else tag + "." + "".join(n[0] for n in on)


def install(gen) -> CendaBackend | None:
    """Route `gen`'s networks through the engine (see module doc). Returns the backend, or None (logged) when the
    engine is unavailable on the generator's device."""
    from terrain_slm.export import cenda_engine as E
    if not E.available(gen.device):
        log.warning("TGM_ENGINE=cenda but the Cenda inference engine is unavailable on %s: torch path", gen.device)
        return None
    try:
        be = CendaBackend(gen)
    except Exception as e:  # noqa: BLE001
        log.warning("cenda engine setup failed (%s): torch path", e)
        return None
    if not be.models:
        return None
    gen._cenda = be
    gen.model_id += marker(gen, be)

    from terrain_slm.world import generator as G
    from terrain_slm.world import generator2 as G2
    sample0, sr0, warm0 = gen._sample, getattr(gen, "_sr_sample", None), gen.warm_up

    def _sample(model, cond, noise, steps, t_start=0.0, gates=None):
        name = "detail" if model is gen.detail else "relief" if model is gen.relief else None
        if name is not None and name in be.models:
            g = gates if name == "detail" else None
            done, x = _hook(be, name, (cond, noise, g, steps, t_start))
            if done:
                return x
            x = be.flow(name, cond, noise, steps, t_start, g)
            if x is not None:
                return x
        return sample0(model, cond, noise, steps, t_start, gates=gates)

    gen._sample = _sample
    if sr0 is not None and ("sr30" in be.models or "sr15" in be.models):
        def _sr_sample(model, cond, noise, steps):
            # SR-15 too (generator2._sr15_window calls this only on its native path, which the engine replays bitwise;
            # without it the engine's `_sr_native = None` below would send SR-15 to torch under the :ks15 marker).
            name = "sr30" if model is gen.sr30 else "sr15" if model is getattr(gen, "sr15", None) else None
            if name is not None and name in be.models:
                done, x = _hook(be, name, (cond, noise, None, steps, 0.0))
                if done:
                    return x
                x = be.flow(name, cond, noise, steps)
                if x is not None:
                    return x
            return sr0(model, cond, noise, steps)
        gen._sr_sample = _sr_sample

    # ---- window batching (PREFETCH_BATCH: the windows a blend is about to read, computed in engine batches)
    def keys_of(i1, j1, i2, j2, ext, core, apron):
        out = []
        for wi in range(math.floor((i1 - ext + apron) / core), math.floor((i2 - 1 + apron) / core) + 1):
            for wj in range(math.floor((j1 - ext + apron) / core), math.floor((j2 - 1 + apron) / core) + 1):
                a0, b0 = wi * core - apron, wj * core - apron
                if max(i1, a0) < min(i2, a0 + ext) and max(j1, b0) < min(j2, b0 + ext):
                    out.append((wi, wj))
        return out

    if PREFETCH and "relief" in be.models and gen.relief is not None:
        trend0 = gen.trend

        def trend(ci0, cj0, hc, wc):
            keys = keys_of(ci0, cj0, ci0 + hc, cj0 + wc, G.RELIEF_EXT, G.RELIEF_CORE, G.RELIEF_APRON)
            _prefetch(be, "relief", keys, lambda k: (gen.seed, *k) in gen._relief_windows,
                      lambda k: gen._relief_window(*k), lambda items: be.flow_batch("relief", items))
            return trend0(ci0, cj0, hc, wc)
        gen.trend = trend
    if PREFETCH and "detail" in be.models and gen.detail is not None:
        ds0 = gen._detail_samples

        def _detail_samples(s1, t1, s2, t2):
            keys = keys_of(s1, t1, s2, t2, G.DETAIL_EXT, G.DETAIL_CORE, G.DETAIL_APRON)
            _prefetch(be, "detail", keys, lambda k: (gen.seed, *k) in gen._detail_windows,
                      lambda k: gen._detail_window(*k), lambda items: be.flow_batch("detail", items))
            return ds0(s1, t1, s2, t2)
        gen._detail_samples = _detail_samples
    if PREFETCH and "sr30" in be.models and hasattr(gen, "_sr_blend"):
        srb0 = gen._sr_blend

        def _sr_blend(i1, j1, i2, j2, window):
            if window == gen._sr_window:
                ext = G2.SR_CORE + 2 * G2.SR_APRON
                keys = keys_of(i1, j1, i2, j2, ext, G2.SR_CORE, G2.SR_APRON)
                _prefetch(be, "sr30", keys, lambda k: (gen.seed, *k) in gen._sr_windows,
                          lambda k: gen._sr_window(*k), lambda items: be.flow_batch("sr30", items))
            return srb0(i1, j1, i2, j2, window)
        gen._sr_blend = _sr_blend
    if "planner" in be.models:
        pm = be.models["planner"]

        def planner(xs):
            out = torch.empty(xs.shape[0], pm.info["n_out"], *xs.shape[-2:], device=xs.device)
            for i in range(0, xs.shape[0], pm.max_batch):
                out[i : i + pm.max_batch] = pm.forward(xs[i : i + pm.max_batch])
            be._note("planner", True)
            return out
        gen.planner = _Proxy(gen.planner, planner, "planner")
    if "climate" in be.models and getattr(gen, "climate_net", None) is not None:
        cm = be.models["climate"]
        n_in = cm.info["n_in"]

        def climate(x):
            if tuple(x.shape[-2:]) != (cm.info["H"], cm.info["W"]):
                raise ValueError(f"climate window {tuple(x.shape)}")
            be._note("climate", True)
            return cm.forward(x[:, :n_in])
        gen.climate_net = _Proxy(gen.climate_net, climate, "climate")
    if "hydro" in be.models and getattr(gen, "hydro", None) is not None:
        hm = be.models["hydro"]

        def hydro(x):                      # generator._hydro_pred wraps the call in bf16 autocast: the engine is fp16
            if tuple(x.shape[-2:]) != (hm.info["H"], hm.info["W"]):
                raise ValueError(f"hydro window {tuple(x.shape)}")
            be._note("hydro", True)
            return hm.forward(x.float())
        gen.hydro = _Proxy(gen.hydro, hydro, "hydro")
    _install_biome(gen, be)                 # the biome sidecar (agent "tiles"): own marker ":cb1"
    # The engine replaces the Python native samplers: skip building / capturing theirs.
    if "detail" in be.models and hasattr(gen, "_detail2_native"):
        gen._detail2_native = None
    if "sr30" in be.models and hasattr(gen, "_sr_native") and (getattr(gen, "sr15", None) is None or "sr15" in be.models):
        gen._sr_native = None

    if NATIVE_PIPELINE:
        install_phase2(gen, be)

    def warm_up(report=lambda what: None):
        warm0(report)
        report("cenda engine graphs")
        be.warm_up()
        # The engine holds its own fp16 copy: the fp32 torch detail waits in host RAM for fallbacks (generator2._sample
        # borrows the GPU for a CPU-resident detail model, as with the Python native sampler).
        if "detail" in be.models and gen.detail is not None and str(gen.device).startswith("cuda"):
            gen.detail.to("cpu")
            torch.cuda.empty_cache()
    gen.warm_up = warm_up
    return be


# ================================================================================================ phase 2 (C++ stages)
NATIVE_PIPELINE = os.getenv("TGM_NATIVE_PIPELINE", "0") not in ("0", "false", "off")   # implies the engine backend
P2_MARKER = ":p2"


def _python_planner_post(gen, o: torch.Tensor, trend: torch.Tensor) -> torch.Tensor:
    """generator._run_planner_windows' post-processing of a planner output batch (the reference for the guard)."""
    from terrain_slm.data import descriptors as D
    from terrain_slm.models import planner as P
    from terrain_slm.world import generator as G
    out = D.blur(o, G.PATCH_BLUR_CELLS)
    height = out[:, P.OUT_HEIGHT] * P.HEIGHT_SCALE_M
    desc = out[:, P.OUT_DESC] * gen.desc_std.view(-1, 1, 1) + gen.desc_mean.view(-1, 1, 1)
    river = torch.sigmoid(out[:, P.OUT_RIVER])
    logacc = out[:, P.OUT_LOGACC] * P.LOGACC_SCALE
    height = gen._sea_merge(height, trend)
    return torch.cat([height[:, None], desc, river[:, None], logacc[:, None]], dim=1)


class Phase2:
    """The stages ported to libstonebreak_terrain, attached to a generator that already has the engine backend."""

    def __init__(self, gen, be: CendaBackend):
        from terrain_slm.export import native_pipeline as NP
        self.gen, self.be = gen, be
        self.tp = NP.TerrainPipeline(be.engine)
        self.planner_cfg = None
        self.disabled: dict = {}
        self._configure_planner()
        self._guard_planner()
        self.relief_cfg = None
        if "relief" in be.models and gen.relief is not None:
            self._configure_relief()

    def _configure_planner(self) -> None:
        from terrain_slm.models import planner as P
        from terrain_slm.world import generator as G
        from terrain_slm.world import generator2 as G2
        gen = self.gen
        key = (gen.hill_transfer, gen.sea_detail)
        if key == self.planner_cfg:
            return
        # generator2._sea_merge (2026-11-19 afternoon): hill transfer + sea detail; the coastal shelf moved to the
        # native heights (agent A), so no remap in the planner stage. The startup guard below checks this claim.
        self.tp.configure_planner(self.be.models["planner"], win=G.WIN, n_in=P.N_IN, height_scale=P.HEIGHT_SCALE_M,
                                  logacc_scale=P.LOGACC_SCALE, desc_mean=gen.desc_mean.tolist(),
                                  desc_std=gen.desc_std.tolist(), sigma_patch=G.PATCH_BLUR_CELLS,
                                  sigma_hill=G2.HILL_TRANSFER_SIGMA_CELLS, sigma_sea=G2.SEA_DETAIL_SIGMA_CELLS,
                                  hill_transfer=float(gen.hill_transfer), sea_detail=bool(gen.sea_detail),
                                  sea_detail_max_m=G2.SEA_DETAIL_MAX_M, sea_floor_m=-5.0, shelf_slope=1.0,
                                  shelf_depth_m=G2.SEA_SHELF_DEPTH_M, shelf_keep=G2.SEA_SHELF_KEEP,
                                  shelf_keep_m=G2.SEA_SHELF_KEEP_M, blur_fma=1)
        self.planner_cfg = key

    def _relief_supported(self) -> str | None:
        gen = self.gen
        if getattr(gen.relief.cfg, "amp_norm", False):
            return "relief amp_norm"
        if getattr(gen, "hill_relief", 0) > 0:
            return "hill_relief field gain"
        return None

    def _configure_relief(self) -> None:
        from terrain_slm.models import planner as P
        from terrain_slm.models import relief as RL
        from terrain_slm.world import generator as G
        gen = self.gen
        key = (gen.relief_gain, gen.relief_preblur)
        if key == self.relief_cfg:
            return
        self.tp.configure_relief(self.be.models["relief"], n=G.RELIEF_EXT, m=math.ceil(3 * gen.relief_preblur),
                                 sigma=gen.relief_preblur, gain=float(gen.relief_gain), scale=RL.RELIEF_SCALE_M,
                                 floor_m=G.RELIEF_FLOOR_M, height_scale=P.HEIGHT_SCALE_M, slope_scale=RL.SLOPE_SCALE_M,
                                 wild_offset=P.WILD_NORM[0], wild_scale=P.WILD_NORM[1],
                                 noise_stream=G.RELIEF_NOISE_STREAM)
        self.relief_cfg = key

    def relief_window(self, wi: int, wj: int) -> torch.Tensor:
        """generator._relief_window's value through the C++ stage (fields from the layout + climate cells)."""
        from terrain_slm.models import planner as P
        from terrain_slm.world import generator as G
        gen = self.gen
        self._configure_relief()
        ci0, cj0 = wi * G.RELIEF_CORE - G.RELIEF_APRON, wj * G.RELIEF_CORE - G.RELIEF_APRON
        n, m = G.RELIEF_EXT, math.ceil(3 * gen.relief_preblur)
        a, b, N = ci0 - m, cj0 - m, n + 2 * m
        c = gen._layout(a, b, N, N)                       # trend, wild (the relief reads no gates: controls() not needed)
        if getattr(gen, "climate_net", None) is not None:
            c = {**c, **gen.climate_cells(a, b, N, N)}
        out = torch.empty(n, n, device=c["trend"].device)
        self.tp.relief_window([c["trend"], c["wild"]] + [c[k] for k in P.CLIMATE_NAMES], ci0, cj0, gen.seed, out)
        return out

    def climate_cells(self, ci0: int, cj0: int, hc: int, wc: int) -> dict:
        """generator2.climate_cells (canonical sampling) with the grid from the generator's cached climate windows and
        the bilinear + denorm in one C++ kernel. The cross-fade weights are made once (same expressions)."""
        from terrain_slm.kernels import cuda_blend as KB
        from terrain_slm.models import climatenet as CN
        from terrain_slm.world import generator2 as G2
        gen = self.gen
        g = G2.CLIMATE_GRID_CELLS
        gi0, gj0 = math.floor(ci0 / g) - 1, math.floor(cj0 / g) - 1
        gi1, gj1 = math.floor((ci0 + hc) / g) + 2, math.floor((cj0 + wc) / g) + 2
        gh, gw = gi1 - gi0, gj1 - gj0
        if getattr(self, "_w2", None) is None:
            ramp = 2 * G2.CLIMATE_APRON
            t = (torch.arange(ramp, device=gen.device, dtype=torch.float32) + 0.5) / ramp
            w1 = torch.ones(G2.CLIMATE_WIN, device=gen.device)
            w1[:ramp] = torch.sin(0.5 * math.pi * t) ** 2
            w1[-ramp:] = torch.cos(0.5 * math.pi * t) ** 2
            self._w2 = w1[:, None] * w1[None, :]
        W, A, Cr = G2.CLIMATE_WIN, G2.CLIMATE_APRON, G2.CLIMATE_CORE
        items = []
        for wi in range(math.floor((gi0 - W + A) / Cr), math.floor((gi0 + gh - 1 + A) / Cr) + 1):
            for wj in range(math.floor((gj0 - W + A) / Cr), math.floor((gj0 + gw - 1 + A) / Cr) + 1):
                a0, b0 = wi * Cr - A, wj * Cr - A
                if max(gi0, a0) >= min(gi0 + gh, a0 + W) or max(gj0, b0) >= min(gj0 + gw, b0 + W):
                    continue
                items.append((gen._climate_window(wi, wj), a0 - gi0, b0 - gj0))
        y = KB.overlap_add((len(CN.OUT_NAMES), gh, gw), items, self._w2, gen.device)
        return self.tp.climate_cells(y, gi0, gj0, ci0, cj0, hc, wc, g)

    DETAIL_UP_FMA = 1      # torch's upsample_bilinear2d fused-product pattern (tests/test_cenda_engine.py pins it)

    def _configure_detail(self, up_fma: int | None = None) -> None:
        from terrain_slm.models import detail as DT
        from terrain_slm.models import detail2 as D2
        from terrain_slm.models import planner as P
        from terrain_slm.world import generator as G
        gen = self.gen
        spc, mc = DT.SAMPLES_PER_CELL, G.DETAIL_MARGIN_CELLS
        self.tp.configure_detail(self.be.models["detail"], nc=G.DETAIL_EXT // spc + 2 * mc, f=spc, E=G.DETAIL_EXT,
                                 ms=mc * spc, S=len(D2.STYLES), attempts=list(G.DETAIL_RETRY), sigma_base=DT.BASE_SIGMA,
                                 sigma_river=0.7, up_fma=self.DETAIL_UP_FMA if up_fma is None else up_fma,
                                 height_scale=P.HEIGHT_SCALE_M, slope_scale=DT.SLOPE_SCALE_M,
                                 wild_offset=P.WILD_NORM[0], wild_scale=P.WILD_NORM[1], logacc_scale=P.LOGACC_SCALE,
                                 x_limit=G.DETAIL_X_LIMIT, asinh_m=DT.ASINH_M, noise_stream=G.DETAIL_NOISE_STREAM)
        self.detail_ready = True

    def detail_window(self, wi: int, wj: int) -> torch.Tensor:
        """generator._detail_window's value (lowland boost off) through the C++ stage."""
        from terrain_slm.models import detail as DT
        from terrain_slm.models import detail2 as D2
        from terrain_slm.models import planner as P
        from terrain_slm.world import generator as G
        gen = self.gen
        if not getattr(self, "detail_ready", False):
            self._configure_detail()
        spc, mc = DT.SAMPLES_PER_CELL, G.DETAIL_MARGIN_CELLS
        si0, sj0 = wi * G.DETAIL_CORE - G.DETAIL_APRON, wj * G.DETAIL_CORE - G.DETAIL_APRON
        nc = G.DETAIL_EXT // spc + 2 * mc
        ci0, cj0 = si0 // spc - mc, sj0 // spc - mc
        raw = gen._planner_cells(ci0, cj0, nc, nc)[0]
        ctrl = gen.controls(ci0, cj0, nc, nc)
        dr = gen.drainage(ci0, cj0, nc, nc)
        gates = None
        if isinstance(gen.detail, D2.Detail2) and len(gen.detail.adapters):
            gates = ctrl["gates"]
        fields = [raw, ctrl["wild"]] + [ctrl[k] for k in P.CLIMATE_NAMES] + \
                 ([dr["logacc"], dr["river"]] if dr else [None, None]) + [gates]
        out = torch.empty(G.DETAIL_EXT, G.DETAIL_EXT, device=raw.device)
        got = self.tp.detail_window(fields, si0, sj0, gen.seed, 1.0 if dr else 0.0, out)
        fails = len(G.DETAIL_RETRY) if got < 0 else got
        for k in range(fails):
            blur, clim = G.DETAIL_RETRY[k]
            gen.detail_failures += 1
            print(f"[detail] WARNING: window ({wi},{wj}) seed {gen.seed} unstable (base blur {blur} cells, "
                  f"climate {'on' if clim else 'off'}); retrying", flush=True)
        if got < 0:
            print(f"[detail] WARNING: window ({wi},{wj}) seed {gen.seed} fell back to the smooth base", flush=True)
        return out

    def _guard_planner(self) -> None:
        """The C++ post-processing must equal the generator's own on a synthetic batch (bit for bit), else the
        planner stage stays in Python (shared code changes, e.g. the sea merge, cannot silently desync tiles)."""
        from terrain_slm.world import generator as G
        g = torch.Generator(device=self.gen.device).manual_seed(11)
        n, w = 3, G.WIN
        o = torch.randn(n, 11, w, w, device=self.gen.device, generator=g)
        o[:, 0] = o[:, 0] * 0.6 + 0.1                         # heights around sea level, -600 .. +700 m
        trend = (torch.randn(1, 2 * w, 2 * w, device=self.gen.device, generator=g) * 400.0)[0]
        offs = [(0, 0), (w // 2, w), (w, w // 3)]
        tr = torch.stack([trend[a:a + w, c:c + w] for a, c in offs])
        ref = _python_planner_post(self.gen, o, tr)
        outs = [torch.empty(11, w, w, device=self.gen.device) for _ in offs]
        inp = torch.zeros(1, 11, 2 * w, 2 * w, device=self.gen.device)
        self.tp.planner_windows(inp, trend, offs, outs, override=o)
        ok = all(torch.equal(outs[i], ref[i]) for i in range(n))
        if not ok:
            d = max(float((outs[i] - ref[i]).abs().max()) for i in range(n))
            self.disabled["planner"] = f"post-processing differs from the generator's (max {d:.3g}); planner stage in Python"
            log.warning("phase 2: %s", self.disabled["planner"])

    def install(self) -> None:
        from terrain_slm.world import generator as G
        gen, tp = self.gen, self.tp
        if "planner" in self.disabled:
            return
        rpw0 = gen._run_planner_windows

        def _run_planner_windows(keys):
            if not keys:
                return
            S, W = G.STRIDE, G.WIN
            i0, j0 = min(k[0] for k in keys) * S, min(k[1] for k in keys) * S
            hn = max(k[0] for k in keys) * S + W - i0
            wn = max(k[1] for k in keys) * S + W - j0
            if hn * wn > 16 * len(keys) * W * W:                # scattered: as the generator does
                for k in keys:
                    _run_planner_windows([k])
                return
            try:
                self._configure_planner()
                xa, ca = gen._planner_input(i0, j0, hn, wn)
                for start in range(0, len(keys), G.PLANNER_BATCH):
                    chunk = keys[start : start + G.PLANNER_BATCH]
                    outs = [torch.empty(11, W, W, device=xa.device) for _ in chunk]
                    tp.planner_windows(xa, ca["trend"], [(wi * S - i0, wj * S - j0) for wi, wj in chunk], outs)
                    for v, (wi, wj) in zip(outs, chunk):
                        gen._windows.get_or((gen.seed, wi, wj), lambda v=v: v)
                self.be._note("p2.planner", True)
            except Exception as e:  # noqa: BLE001 - the generator's own stage keeps the tile alive
                self.disabled["planner"] = str(e)
                log.warning("phase 2 planner stage failed, back to Python: %s", e)
                gen._run_planner_windows = rpw0
                rpw0(keys)

        gen._run_planner_windows = _run_planner_windows
        from terrain_slm.world import generator2 as G2
        if "detail" in self.be.models and gen.detail is not None:
            dw0 = gen._detail_window

            def _detail_window(wi, wj):
                if "detail" in self.disabled or getattr(gen, "lowland_boost", 0) > 0:
                    return dw0(wi, wj)

                def run():
                    try:
                        v = self.detail_window(wi, wj)
                        self.be._note("p2.detail", True)
                        return v
                    except Exception as e:  # noqa: BLE001
                        self.disabled["detail"] = str(e)
                        log.warning("phase 2 detail stage failed, back to Python: %s", e)
                        return None
                v = gen._detail_windows.get_or((gen.seed, wi, wj), run)
                if v is None:
                    gen._detail_windows.pop((gen.seed, wi, wj), None)
                    return dw0(wi, wj)
                # generator2._detail_window's post-cache step: the FX4 hill band in block-look builds (same function,
                # applied to the cached base window as generator2 does; no fallback to the Python window any more).
                if getattr(gen, "look_build", False) and getattr(gen, "look_hill_gain", 0) > 0:
                    v = G2._hill_band(v, gen.look_hill_gain)
                return v
            gen._detail_window = _detail_window
        if getattr(gen, "climate_net", None) is not None and G2.CANONICAL_CLIMATE:
            cc0 = gen.climate_cells

            def climate_cells(ci0, cj0, hc, wc):
                if "climate" in self.disabled:
                    return cc0(ci0, cj0, hc, wc)
                try:
                    return self.climate_cells(ci0, cj0, hc, wc)
                except Exception as e:  # noqa: BLE001
                    self.disabled["climate"] = str(e)
                    log.warning("phase 2 climate cells failed, back to Python: %s", e)
                    return cc0(ci0, cj0, hc, wc)
            gen.climate_cells = climate_cells
        if self.relief_cfg is not None and self._relief_supported() is None:
            rw0 = gen._relief_window

            def _relief_window(wi, wj):
                if "relief" in self.disabled or self._relief_supported() is not None:
                    return rw0(wi, wj)

                def run():
                    try:
                        v = self.relief_window(wi, wj)
                        self.be._note("p2.relief", True)
                        return v
                    except Exception as e:  # noqa: BLE001
                        self.disabled["relief"] = str(e)
                        log.warning("phase 2 relief stage failed, back to Python: %s", e)
                        return None
                v = gen._relief_windows.get_or((gen.seed, wi, wj), run)
                if v is None:                                # failed: drop the placeholder, compute in Python
                    gen._relief_windows.pop((gen.seed, wi, wj), None)
                    return rw0(wi, wj)
                return v
            gen._relief_window = _relief_window


def install_phase2(gen, be: CendaBackend):
    try:
        p2 = Phase2(gen, be)
        p2.install()
    except Exception as e:  # noqa: BLE001
        log.warning("phase 2 (libstonebreak_terrain) unavailable: %s", e)
        return None
    gen._phase2 = p2
    gen.model_id += P2_MARKER
    return p2


# ----------------------------------------------------------------------------------------------- sidecar networks
# Small per-frame networks on the engine (agent "tiles", 2026-11-19; export/cenda_export.py export_biome /
# export_vegnet / export_surfacenet): one fp32 model per frame size ("<net>_<H>x<W>": the tile frame tile +
# 2 * BLOCK_MARGIN at 1:4 and its half / quarter at 1:2 / 1:1), shared with libstonebreak_terrain (stages/tiles:
# BN.classify, TileBuilder.surface.vegetation / .surface), so native and Python agree bit for bit. Their numbers differ
# from torch's (cuDNN TF32) at ~1e-5, so each gives tiles its own model_id marker. The frames of the game's 256 tiles
# (and the tests' 64 tiles) are exported up front; another frame size is exported on first use.
BIOME_FRAMES = (320, 160, 80, 128, 64, 32)
SIDECARS = {   # name -> (generator attribute, exporter, model_id marker)
    "biome": ("biomenet", "export_biome", ":cb1"),
    "veg": ("vegnet", "export_vegnet", ":cv1"),
    "surface": ("surfacenet", "export_surfacenet", ":cs1"),
}
_SIDECAR_LOCK = threading.Lock()


def sidecar_model(be: "CendaBackend", net: str, h: int, w: int):
    """The engine model of sidecar `net` for (h, w) frames (exported + loaded on first use)."""
    name = f"{net}_{h}x{w}"
    m = be.models.get(name)
    if m is not None:
        return m
    with _SIDECAR_LOCK:
        m = be.models.get(name)
        if m is None:
            from terrain_slm.export import cenda_export as X
            p = be.dir / f"{name}.cim"
            gen = be.gen
            attr, exporter, _marker = SIDECARS[net]
            if not p.exists():
                mod = getattr(gen, attr)
                mod = getattr(mod, "_module", mod)
                home = next(mod.parameters()).device
                if home.type == "cpu":
                    mod.to(gen.device)
                try:
                    with torch.no_grad():
                        getattr(X, exporter)(mod, h, w, p, gen.model_id)
                finally:
                    if home.type == "cpu":
                        mod.to("cpu")
            m = be.models[name] = be.engine.load(p, max_batch=1, contexts=1)
    return m


def biome_model(be: "CendaBackend", h: int, w: int):
    return sidecar_model(be, "biome", h, w)


def _install_biome(gen, be: "CendaBackend") -> None:
    """Every sidecar network the generator has (biome, vegetation, surface) onto the engine."""
    nets = [n.strip() for n in os.getenv("TGM_ENGINE_NETS", "").split(",") if n.strip()]
    for net, (attr, _exporter, marker) in SIDECARS.items():
        if getattr(gen, attr, None) is None or (nets and net not in nets):
            continue
        try:
            for n in BIOME_FRAMES:
                sidecar_model(be, net, n, n)
        except Exception as e:  # noqa: BLE001
            log.warning("cenda export of the %s sidecar failed (%s): it stays on torch", net, e)
            continue
        if net == "surface":
            from terrain_slm.models import surfacenet as SN
            n60 = getattr(gen, attr).head60.out_channels

            def call(x, net=net, n60=n60):      # SurfaceNet.forward: (head60, pixel_shuffle(head15, SUB))
                be._note(net, True)
                out = sidecar_model(be, net, int(x.shape[-2]), int(x.shape[-1])).forward(x.float())
                return out[:, :n60], torch.nn.functional.pixel_shuffle(out[:, n60:].contiguous(), SN.SUB)
        else:
            def call(x, net=net):
                be._note(net, True)
                return sidecar_model(be, net, int(x.shape[-2]), int(x.shape[-1])).forward(x.float())
        setattr(gen, attr, _Proxy(getattr(gen, attr), call, net))
        gen.model_id += marker
