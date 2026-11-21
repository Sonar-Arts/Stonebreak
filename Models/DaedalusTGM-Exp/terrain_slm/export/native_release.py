"""The native terrain pipeline's release export: everything libstonebreak_terrain needs to build tiles WITHOUT Python
(sbtg_terrain_open; phase 3 = the game's JVM), written to one directory:

    <out>/manifest.json      native_manifest.build(gen) + "models" with relative .cim paths + export identity
    <out>/<net>.cim          the engine networks (detail [FP8 or fp16], relief, sr30, sr15, planner, climate, hydro, ...)

    python -m terrain_slm.export.native_release checkpoints/exp2/world_exp2e [--out DIR] [--device cuda:0]

Default DIR: <model_dir>/native (the release folder carries it; the game looks there). The export is a pure function
of the model folder and the generator source: `export_key` (in the manifest) names both, and `--check` reports
whether an existing export is current (exit 0) or stale (exit 3) without writing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import time
from pathlib import Path

#: Bump when the manifest layout changes (the C++ reads "version" too).
RELEASE_VERSION = 1
#: model_id marker of tiles built by the native pipeline (their own TGMPipe / game cache namespace).
NATIVE_MARKER = ":nt1"


def export_key(model_dir: Path) -> str:
    """Identity of an export: the model folder's checkpoints (resolved paths, sizes, mtimes) + every generator source
    file (the Python the manifest is read from and the exporter) + the native library's stage sources."""
    from terrain_slm.tgmpipe.tile_cache import source_fingerprint
    pkg = Path(__file__).resolve().parents[1]
    h = hashlib.sha1()
    h.update(f"v{RELEASE_VERSION}".encode())
    for p in sorted(Path(model_dir).glob("*.pt")) + sorted(Path(model_dir).glob("*.json")):
        r = p.resolve()
        st = r.stat()
        h.update(f"{p.name}|{r}|{st.st_size}|{st.st_mtime_ns}".encode())
    h.update(source_fingerprint(pkg, skip=("train", "eval", "tgmpipe")).encode())
    native = pkg.parents[2] / "stonebreak-game" / "native" / "src" / "terrain"
    if native.is_dir():
        for p in sorted(native.rglob("*")):
            if p.is_file() and p.suffix in (".cpp", ".hpp", ".cu", ".h"):
                h.update(str(p.relative_to(native)).encode())
                h.update(p.read_bytes())
    return h.hexdigest()[:16]


def write(gen, out_dir: Path, key: str) -> Path:
    """Writes the release export of the loaded generator `gen` (with its engine backend) into `out_dir`."""
    from terrain_slm.export import native_manifest as NM

    be = getattr(gen, "_cenda", None)
    if be is None:
        raise RuntimeError("the generator has no engine backend (TGM_ENGINE=cenda / TGM_NATIVE_PIPELINE)")
    out_dir = Path(out_dir)
    tmp = out_dir.with_name(out_dir.name + ".tmp")
    shutil.rmtree(tmp, ignore_errors=True)
    tmp.mkdir(parents=True)
    m = NM.build(gen)
    models = {}
    for name, model in be.models.items():
        shutil.copyfile(model.path, tmp / f"{name}.cim")
        entry = {"path": f"{name}.cim", "max_batch": int(model.max_batch)}
        if name == "detail":
            on = [i for i, s in enumerate(model.styles)
                  if gen.experts_enabled is None or s in gen.experts_enabled]
            entry["warm_masks"] = sorted({sum(1 << on[i] for i in range(len(on)) if k >> i & 1)
                                          for k in range(1 << len(on))})
        models[name] = entry
    m["models"] = models
    m["release_version"] = RELEASE_VERSION
    m["export_key"] = key
    m["model_id"] = gen.model_id + NATIVE_MARKER
    m["default_climate_layout"] = getattr(gen, "default_climate_layout", getattr(gen, "climate_layout", "belts"))
    m["created"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    (tmp / "manifest.json").write_text(json.dumps(m, indent=1))
    if out_dir.exists():
        shutil.rmtree(out_dir)
    tmp.rename(out_dir)
    return out_dir / "manifest.json"


def current(out_dir: Path, key: str) -> bool:
    p = Path(out_dir) / "manifest.json"
    try:
        return json.loads(p.read_text()).get("export_key") == key
    except (OSError, ValueError):
        return False


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m terrain_slm.export.native_release")
    ap.add_argument("model_dir")
    ap.add_argument("--out", default=None, help="default: <model_dir>/native")
    ap.add_argument("--device", default="cuda:0")
    ap.add_argument("--layout", default="belts", help="default climate layout of worlds that name none")
    ap.add_argument("--check", action="store_true", help="exit 0 if the export is current, 3 if stale; no writing")
    a = ap.parse_args(argv)
    model_dir = Path(a.model_dir)
    if not model_dir.is_absolute():
        from terrain_slm.paths import MODEL_DIR
        model_dir = MODEL_DIR / model_dir
    out = Path(a.out) if a.out else model_dir / "native"
    key = export_key(model_dir)
    if a.check:
        ok = current(out, key)
        print(f"{out}: {'current' if ok else 'stale'} (key {key})")
        return 0 if ok else 3
    os.environ.setdefault("TGM_ENGINE", "cenda")
    from terrain_slm.world import generator2 as G2
    gen = G2.WorldGenerator2(model_dir, 0, a.device)
    gen.use_climate_layout(a.layout)
    gen.default_climate_layout = a.layout
    p = write(gen, out, key)
    print(f"wrote {p} (key {key})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
