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

#: Bumped when the file layout changes.
SCHEMA = 1
_HEAD = np.dtype("<u4")


def source_fingerprint(package_dir: Path, skip=("train", "eval", "export", "tgmpipe")) -> str:
    """Hash of the generator's source: every .py under the package except the named top-level
    folders (training, evaluation and the service itself do not change a tile's content)."""
    h = hashlib.sha1()
    for path in sorted(package_dir.rglob("*.py")):
        rel = path.relative_to(package_dir)
        if rel.parts[0] in skip:
            continue
        h.update(str(rel).encode())
        h.update(path.read_bytes())
    return h.hexdigest()[:12]


def namespace(world_fingerprint: str, model_id: str, source: str) -> str:
    raw = f"v{SCHEMA}|{world_fingerprint}|{model_id}|{source}"
    return hashlib.sha1(raw.encode()).hexdigest()[:16]


class TileCache:
    def __init__(self, root: Path, namespace: str, max_bytes: int):
        self.root = Path(root)
        self.dir = self.root / namespace
        self.dir.mkdir(parents=True, exist_ok=True)
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
        return self.dir / f"s{key.seed}_x{key.tile_x}_z{key.tile_z}_l{key.lod}.tile"

    def get(self, key: TileKey) -> np.ndarray | None:
        """(PLANES, rows, cols) int16, or None."""
        path = self._path(key)
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            return None
        rows, cols = np.frombuffer(data[:8], dtype=_HEAD) if len(data) >= 8 else (0, 0)
        if rows == 0 or len(data) != 8 + 2 * PLANES * int(rows) * int(cols):
            path.unlink(missing_ok=True)  # torn or foreign file: regenerate rather than trust it
            return None
        body = np.frombuffer(data[8:], dtype="<i2")
        try:
            os.utime(path)
        except FileNotFoundError:
            pass
        with self._lock:
            if path in self._files:
                self._files[path] = (time.time(), self._files[path][1])
        return body.reshape(PLANES, int(rows), int(cols))

    def put(self, key: TileKey, planes: np.ndarray) -> None:
        path = self._path(key)
        payload = np.array(planes.shape[1:], dtype=_HEAD).tobytes() + planes.astype("<i2", copy=False).tobytes()
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
            if d.is_dir() and d != self.dir and not any(d.iterdir()):
                d.rmdir()

    def stats(self) -> dict:
        with self._lock:
            return {"namespace": self.dir.name, "files": len(self._files), "bytes": self._bytes,
                    "max_bytes": self._max_bytes}
