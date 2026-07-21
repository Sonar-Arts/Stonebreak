"""Disk LRU cache for finished (block_height, biome) tile payloads.

Caches the already-converted, servable payload rather than raw upstream
elevation — conversion is a cheap, pure function (height_mapping.py), so
there is nothing to gain from caching pre-conversion, and caching post-
conversion means a cache hit is a straight file read with no recompute.

Tiles are stored under a subdirectory fingerprinted by every knob that
affects the output (scale, tile size, meters/block, sea level, world height,
noise). A config change therefore starts a fresh cache namespace instead of
silently serving tiles that no longer match the current mapping.
"""
from __future__ import annotations

import hashlib
import os
import threading
from pathlib import Path

import numpy as np

from .config import BridgeConfig
from .tiling import TileId


def _config_fingerprint(cfg: BridgeConfig) -> str:
    raw = (
        f"{cfg.scale}|{cfg.tile_size_blocks}|{cfg.meters_per_block}|"
        f"{cfg.sea_level}|{cfg.world_height}|{cfg.noise_scale}"
    )
    return hashlib.sha1(raw.encode()).hexdigest()[:12]


class TileCache:
    def __init__(self, cfg: BridgeConfig):
        self._root = Path(cfg.cache_dir) / _config_fingerprint(cfg)
        self._root.mkdir(parents=True, exist_ok=True)
        self._max_bytes = cfg.cache_max_bytes
        self._lock = threading.Lock()

    def _path(self, tile: TileId) -> Path:
        return self._root / f"{tile.cache_key()}.bin"

    def get(self, tile: TileId) -> tuple[np.ndarray, np.ndarray] | None:
        path = self._path(tile)
        try:
            data = path.read_bytes()
        except FileNotFoundError:
            return None
        os.utime(path, None)  # touch for LRU ordering
        h, w = np.frombuffer(data[:8], dtype="<u4")
        h, w = int(h), int(w)
        body = data[8:]
        n = h * w * 2
        block_height = np.frombuffer(body[:n], dtype="<i2").reshape(h, w)
        biome = np.frombuffer(body[n:], dtype="<i2").reshape(h, w)
        return block_height, biome

    def put(self, tile: TileId, block_height: np.ndarray, biome: np.ndarray) -> None:
        h, w = block_height.shape
        header = np.array([h, w], dtype="<u4").tobytes()
        payload = header + block_height.astype("<i2").tobytes() + biome.astype("<i2").tobytes()
        path = self._path(tile)
        tmp = path.with_suffix(".tmp")
        tmp.write_bytes(payload)
        tmp.replace(path)  # atomic rename on the same filesystem
        with self._lock:
            self._evict_if_needed()

    def _evict_if_needed(self) -> None:
        entries = [(p, p.stat()) for p in self._root.glob("*.bin")]
        total = sum(s.st_size for _, s in entries)
        if total <= self._max_bytes:
            return
        entries.sort(key=lambda e: e[1].st_mtime)  # oldest access first
        for p, s in entries:
            if total <= self._max_bytes:
                break
            try:
                p.unlink()
                total -= s.st_size
            except FileNotFoundError:
                pass

    def stats(self) -> dict:
        entries = list(self._root.glob("*.bin"))
        return {
            "tiles": len(entries),
            "bytes": sum(p.stat().st_size for p in entries),
            "max_bytes": self._max_bytes,
        }
