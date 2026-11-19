"""Per-stage timing of tile generation inside the REAL service (benchmarks only; off by default).

    TGM_STAGE_PROFILE=1 python -m terrain_slm.tgmpipe ...      (eval/pipe_bench.py sets it with --stages)

`install(gen, builders)` wraps the generator's stage methods, the tile builder's helpers, the river pipeline's
stages and a few module functions with timers. Times are EXCLUSIVE (a stage's own time without the stages it
calls), so the table adds up to the tile time; every boundary synchronises the device, so the numbers attribute GPU
work to the stage that launched it (and the run is a little slower than an unprofiled one: compare end-to-end
times from unprofiled runs). The STATUS reply carries `stages` = {name: [calls, seconds]}.

Nothing here changes a value: wrappers only call through.
"""
from __future__ import annotations

import functools
import os
import threading
import time

ENABLED = os.getenv("TGM_STAGE_PROFILE", "") not in ("", "0")

_lock = threading.Lock()
_totals: dict[str, list] = {}
_tls = threading.local()
_device = None


def _sync() -> None:
    if _device is not None:
        import torch
        torch.cuda.synchronize(_device)


def _enter(name: str) -> None:
    _sync()
    now = time.perf_counter()
    stack = getattr(_tls, "stack", None)
    if stack is None:
        stack = _tls.stack = []
    if stack:
        top = stack[-1]
        top[1] += now - top[2]          # parent's exclusive time up to here
    stack.append([name, 0.0, now])


def _exit() -> None:
    _sync()
    now = time.perf_counter()
    stack = _tls.stack
    name, own, start = stack.pop()
    own += now - start
    with _lock:
        t = _totals.setdefault(name, [0, 0.0])
        t[0] += 1
        t[1] += own
    if stack:
        stack[-1][2] = now              # parent resumes


class stage:
    """`with stage("name"): ...` (a no-op unless profiling is on)."""

    def __init__(self, name: str):
        self.name = name

    def __enter__(self):
        if ENABLED:
            _enter(self.name)

    def __exit__(self, *exc):
        if ENABLED:
            _exit()
        return False


_tiles: list = []                       # per tile: {stage: seconds} spent inside that tile's build


def _wrap(fn, name: str):
    @functools.wraps(fn)
    def inner(*a, **k):
        outer = name == "tile" and not getattr(_tls, "stack", None)
        if outer:
            before = {kk: v[1] for kk, v in snapshot().items()}
        _enter(name)
        try:
            return fn(*a, **k)
        finally:
            _exit()
            if outer:
                after = snapshot()
                with _lock:
                    _tiles.append({kk: round(v[1] - before.get(kk, 0.0), 4) for kk, v in after.items()
                                   if v[1] - before.get(kk, 0.0) > 0})
    inner.__stageprof__ = True
    return inner


def _patch(owner, attr: str, name: str) -> None:
    fn = getattr(owner, attr, None)
    if fn is None or getattr(fn, "__stageprof__", False):
        return
    setattr(owner, attr, _wrap(fn, name))


class _Stage:
    def __init__(self, inner, name):
        self.inner, self.name, self._label = inner, inner.name if hasattr(inner, "name") else name, name

    def __call__(self, *a, **k):
        _enter(self._label)
        try:
            return self.inner(*a, **k)
        finally:
            _exit()


def snapshot() -> dict:
    with _lock:
        return {k: [v[0], round(v[1], 4)] for k, v in _totals.items()}


def tiles() -> list:
    with _lock:
        return list(_tiles)


def reset() -> None:
    with _lock:
        _totals.clear()
        _tiles.clear()


def install(gen, builders=()) -> None:
    """Wrap `gen`'s class hierarchy, its river pipeline, the tile builders' class and module helpers."""
    global _device
    import torch

    from terrain_slm import biomes as B
    from terrain_slm.models import biomenet as BN
    from terrain_slm.world import carve_node as CN
    from terrain_slm.world import generator as G
    from terrain_slm.world import ocean as O
    from terrain_slm.world import swamp_relief as SR
    from terrain_slm.world import tiles as T

    if str(gen.device).startswith("cuda"):
        _device = torch.device(gen.device)
    for cls in type(gen).__mro__:
        if cls.__module__.startswith("terrain_slm.world.generator"):
            for attr, name in (
                    ("controls", "controls"), ("climate_cells", "climate.cells"), ("_climate_window", "climate.window"),
                    ("_continentality", "climate.continentality"),
                    ("_relief_window", "relief.window"), ("_sample", "net.detail"), ("_sr_sample", "net.sr"),
                    ("_blend", "blend.cells"), ("_owned", "blend.owned"),
                    ("_run_planner_windows", "planner.windows"), ("_planner_input", "planner.input"),
                    ("_planner_cells", "planner.blend"), ("_sea_merge", "planner.sea_merge"),
                    ("_hydro_pred", "hydro.net"), ("_route_window", "route.window"),
                    ("_stitched_route", "route.stitch"), ("_hydro_window", "hydro.window"),
                    ("drainage", "drainage"), ("cells", "cells"),
                    ("_detail_window", "detail.window"), ("_detail_samples", "detail.samples"),
                    ("detail_gates", "detail.gates"), ("fine_detail", "sr.blend"), ("_sr_window", "sr.window"),
                    ("_native_detail", "native.detail"), ("river_field", "river.field"),
                    ("swamp_relief_cells", "swamp.cells"), ("_flatten_swamps", "swamp.flatten"),
                    ("regional_elevation", "regional"), ("climate_native", "climate.native")):
                if attr in cls.__dict__:
                    _patch(cls, attr, name)
    for attr in ("route_window", "accumulate_drainage", "break_seam_cycles", "soft_cap_peaks"):
        _patch(G, attr, f"G.{attr}")
    for attr, name in (("_classify_biome", "biomes.rule"), ("swamp_candidates", "biomes.swamp"),
                       ("smooth_mask", "biomes.smooth"), ("majority", "biomes.majority"),
                       ("apply_swamps", "biomes.apply_swamps")):
        _patch(B, attr, name)
    _patch(BN, "classify", "biomes.net")
    _patch(CN, "carve_codes", "carve.codes")
    _patch(O, "sea_mask_for_samples", "ocean.mask")
    _patch(O, "ocean_columns", "ocean.columns")
    _patch(SR, "cell_fields", "swamp.fields")
    for attr, name in (("build", "tile"), ("_blocks", "tile.blocks"), ("_carve", "tile.carve"),
                       ("_planes", "tile.planes"), ("_red_deserts", "tile.red_deserts"), ("_overview", "tile.overview")):
        _patch(T.TileBuilder, attr, name)
    rp = gen.river
    rp.native = [s if isinstance(s, _Stage) else _Stage(s, f"river.{getattr(s, 'name', type(s).__name__)}")
                 for s in rp.native]
    rp.blocks = [s if isinstance(s, _Stage) else _Stage(s, f"blocks.{getattr(s, 'name', type(s).__name__)}")
                 for s in rp.blocks]
    import terrain_slm.river.blocks as RB
    _patch(RB, "pool_to_blocks", "blocks.pool")
    import terrain_slm.river.pipeline as RPM
    _patch(RPM, "pool_to_blocks", "blocks.pool")
    # Samplers: tag native sampler calls by model so "sampler(any)" splits into relief / detail / sr.
    # `_sample` runs detail and relief: the relief sampler's own timer takes its share out of "net.detail".
    for mod_name, cls_name, label in (("terrain_slm.kernels.cuda_unet", "NativeSampler", "net.relief"),):
        try:
            mod = __import__(mod_name, fromlist=[cls_name])
            _patch(getattr(mod, cls_name), "__call__", label)
        except Exception:  # noqa: BLE001
            pass
    from terrain_slm.models import sr as SRM
    _patch(SRM, "sample", "net.sr(torch)")
    from terrain_slm.models import relief as RLM
    if hasattr(RLM, "sample"):
        _patch(RLM, "sample", "net.relief(torch)")
    from terrain_slm.models import planner as P
    _patch(P.Planner, "forward", "net.planner")
    from terrain_slm.models import climatenet as CNN
    _patch(CNN.ClimateNet, "forward", "net.climate")
    from terrain_slm.models import hydro as HY
    _patch(HY.Hydro, "forward", "net.hydro")
    _patch(BN.BiomeNet, "forward", "net.biome")
    from terrain_slm.river import banks as BK
    _patch(BK.BankNet, "forward", "net.bank")
    from terrain_slm.world import controls2 as C2
    _patch(C2, "controls2_at", "controls2_at")
    try:
        from terrain_slm.kernels import cuda_controls2 as KC2
        _patch(KC2, "controls2", "controls2(native)")
    except Exception:  # noqa: BLE001
        pass
