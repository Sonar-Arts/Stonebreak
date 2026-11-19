"""The native generator's manifest: everything libstonebreak_terrain needs from the Python side, as one JSON document
(sbtg_gen_create). Built from a loaded generator (`build(gen)`); written next to the exported .cim models so a host
without Python (phase 3: the game's JVM) can load the same generator (`write(gen, dir)`).

    {"version": 1, "model_id": ..., "constants": {...}, "taps": {repr(sigma): [fp32 taps]},
     "models": {name: {"path": .cim, "max_batch": n, ...}}, ...}

Every number is written with repr() of its fp32 / fp64 value, so C++ reads back the exact value.

Stage modules contribute their own sections: every module `terrain_slm/export/native_manifest_<name>.py` with a
`contribute(gen, manifest: dict) -> None` function is called in name order (they add constants, tables, blur sigmas
via `need_taps(manifest, sigma)`, models). Keep each stage's export next to its port.
"""
from __future__ import annotations

import importlib
import json
import pkgutil
from pathlib import Path

import torch

VERSION = 1


def taps(sigma: float, device) -> list[float]:
    """data/descriptors.gaussian_kernel1d(sigma) on the generator's device (the exact taps D.blur uses there)."""
    from terrain_slm.data import descriptors as D
    return D.gaussian_kernel1d(float(sigma), device).float().cpu().tolist()


def need_taps(m: dict, sigma: float, device=None) -> None:
    key = repr(float(sigma))
    if key not in m["taps"]:
        m["taps"][key] = taps(sigma, device or m["_device"])


def f32(x) -> float:
    """The fp32 value of x as a Python float (repr round-trips it exactly through strtof)."""
    return float(torch.tensor(float(x), dtype=torch.float32))


def build(gen) -> dict:
    m = {"version": VERSION, "model_id": getattr(gen, "model_id", ""), "constants": {}, "taps": {}, "models": {},
         "_device": torch.device(gen.device)}
    import terrain_slm.export as pkg
    for info in sorted(pkgutil.iter_modules(pkg.__path__), key=lambda i: i.name):
        if info.name.startswith("native_manifest_"):
            mod = importlib.import_module(f"terrain_slm.export.{info.name}")
            mod.contribute(gen, m)
    m.pop("_device")
    return m


def write(gen, out_dir: Path) -> Path:
    m = build(gen)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    p = out_dir / "manifest.json"
    p.write_text(json.dumps(m, indent=1))
    return p
