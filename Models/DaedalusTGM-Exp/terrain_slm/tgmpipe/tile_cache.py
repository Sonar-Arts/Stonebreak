"""On-disk cache of finished tiles, exactly as they go on the wire.

Tiles live under `<root>/<namespace>/`, the namespace a fingerprint of everything that decides
their content: the world config, the model's identity (checkpoint steps) and the generator's
own source code. Retraining, re-tuning the world or editing the pipeline therefore starts a new
namespace instead of serving stale terrain. One byte budget covers the whole root, so namespaces
left behind by old versions age out on their own (least recently used file first).
"""
from __future__ import annotations

import hashlib
import os
import threading
import time
from pathlib import Path

import numpy as np

from terrain_slm.tgmpipe.protocol import PLANES
from terrain_slm.tgmpipe.scheduler import TileKey
from terrain_slm.world import surface_channels as SC

#: Bumped when the file layout changes. 2 (2026-11-19): the v3 surface block (world/surface_channels.pack) follows
#: the int16 planes.
SCHEMA = 2
_HEAD = np.dtype("<u4")


#: Source files that decide a tile's numbers: Python, the native kernels' CUDA / C++ sources (kernels/csrc: conv,
#: hconv, controls2, blend, river, ...; their NVRTC keys change but the model_id markers :kc1/:kd1/:ks1 do not) and the
#: tuned launch configs (kernels/cuda_configs*.json). review-python MEDIUM-1 (2026-11-19, FX1).
SOURCE_SUFFIXES = (".py", ".cu", ".cuh", ".cpp", ".cc", ".c", ".h", ".hpp", ".json")


def _engine_on() -> bool:
    """The generator runs its networks on the Cenda engine / native pipeline (generator2.CENDA_ENGINE's rule)."""
    return (os.environ.get("TGM_ENGINE", "") == "cenda"
            or os.environ.get("TGM_NATIVE_PIPELINE", "0") not in ("0", "", "false", "off"))


def _native_pipeline_on() -> bool:
    return os.environ.get("TGM_NATIVE_PIPELINE", "0") not in ("0", "", "false", "off")


def source_fingerprint(package_dir: Path, skip=None) -> str:
    """Hash of the generator's source: every source file (`SOURCE_SUFFIXES`) under the package except the named
    top-level folders (training, evaluation and the service itself do not change a tile's content).

    `skip=None` (the service): `train`, `eval`, `tgmpipe`, plus `export` unless the generator runs on the Cenda engine
    or the native pipeline (TGM_ENGINE=cenda / TGM_NATIVE_PIPELINE, the only paths that import it); with the native
    pipeline the game's libstonebreak_terrain stage sources (`stonebreak-game/native/src/terrain`, when this is a
    checkout) count too. An explicit `skip` is used as given and adds no native sources."""
    native_dir = None
    if skip is None:
        skip = ("train", "eval", "tgmpipe") if _engine_on() else ("train", "eval", "export", "tgmpipe")
        if _native_pipeline_on():
            native_dir = Path(package_dir).resolve().parents[2] / "stonebreak-game" / "native" / "src" / "terrain"
    h = hashlib.sha1()
    files = [p for p in Path(package_dir).rglob("*") if p.suffix in SOURCE_SUFFIXES and p.is_file()]
    for path in sorted(files):
        rel = path.relative_to(package_dir)
        if rel.parts[0] in skip or "__pycache__" in rel.parts:
            continue
        h.update(str(rel).encode())
        h.update(path.read_bytes())
    if native_dir is not None and native_dir.is_dir():
        for path in sorted(native_dir.rglob("*")):
            if path.is_file() and path.suffix in SOURCE_SUFFIXES:
                h.update(("native/" + str(path.relative_to(native_dir))).encode())
                h.update(path.read_bytes())
    return h.hexdigest()[:12]


def namespace(world_fingerprint: str, model_id: str, source: str) -> str:
    raw = f"v{SCHEMA}|{world_fingerprint}|{model_id}|{source}"
    return hashlib.sha1(raw.encode()).hexdigest()[:16]


class TileCache:
    def __init__(self, root: Path, namespace: str | list[str], max_bytes: int):
        """`namespace`: one, or one per world (TileKey.world indexes the list)."""
        self.root = Path(root)
        names = [namespace] if isinstance(namespace, str) else list(namespace)
        self.dirs = [self.root / n for n in names]
        self.dir = self.dirs[0]
        for d in self.dirs:
            d.mkdir(parents=True, exist_ok=True)
        self._max_bytes = max_bytes
        self._lock = threading.Lock()
        # Size index over the whole root, built once; kept current by put() and eviction.
        self._files: dict[Path, tuple[float, int]] = {}
        for entry in self.root.glob("*/*.tile"):
            try:
                st = entry.stat()
            except FileNotFoundError:
                continue
            self._files[entry] = (st.st_mtime, st.st_size)
        self._bytes = sum(size for _, size in self._files.values())

    def _path(self, key: TileKey) -> Path:
        return self.dirs[key.world] / f"s{key.seed}_x{key.tile_x}_z{key.tile_z}_l{key.lod}.tile"

    def get(self, key: TileKey) -> np.ndarray | None:
        """(PLANES, rows, cols) int16 as a TilePlanes (`.surface`), or None."""
        path = self._path(key)
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            return None
        rows, cols = np.frombuffer(data[:8], dtype=_HEAD) if len(data) >= 8 else (0, 0)
        n = 2 * PLANES * int(rows) * int(cols)
        surface = None
        if rows != 0 and len(data) > 8 + n:
            try:
                surface, used = SC.unpack(data[8 + n:], int(rows), int(cols))
                if used != len(data) - 8 - n:
                    surface = None
            except (IndexError, ValueError, UnicodeDecodeError):
                surface = None
        if surface is None:
            path.unlink(missing_ok=True)  # torn or foreign file: regenerate rather than trust it
            return None
        body = np.frombuffer(data[8 : 8 + n], dtype="<i2")
        try:
            os.utime(path)
        except FileNotFoundError:
            pass
        with self._lock:
            if path in self._files:
                self._files[path] = (time.time(), self._files[path][1])
        return SC.TilePlanes(body.reshape(PLANES, int(rows), int(cols)), surface)

    def put(self, key: TileKey, planes: np.ndarray) -> None:
        path = self._path(key)
        payload = (np.array(planes.shape[1:], dtype=_HEAD).tobytes() + np.asarray(planes).astype("<i2", copy=False).tobytes()
                   + SC.pack(SC.surface_of(planes)))
        tmp = path.with_suffix(f".{threading.get_ident()}.tmp")
        tmp.write_bytes(payload)
        tmp.replace(path)
        with self._lock:
            old = self._files.get(path)
            self._bytes += len(payload) - (old[1] if old else 0)
            self._files[path] = (os.path.getmtime(path), len(payload))
            if self._bytes > self._max_bytes:
                self._evict()

    def _evict(self) -> None:
        for path, (_, size) in sorted(self._files.items(), key=lambda e: e[1][0]):
            if self._bytes <= self._max_bytes * 0.9:
                break
            path.unlink(missing_ok=True)
            del self._files[path]
            self._bytes -= size
        for d in self.root.iterdir():
            if d.is_dir() and d not in self.dirs and not any(d.iterdir()):
                d.rmdir()

    def stats(self) -> dict:
        with self._lock:
            return {"namespace": self.dir.name, "namespaces": [d.name for d in self.dirs],
                    "files": len(self._files), "bytes": self._bytes,
                    "max_bytes": self._max_bytes}
