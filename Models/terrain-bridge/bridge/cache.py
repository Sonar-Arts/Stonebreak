"""Disk LRU cache for finished (block_height, biome, water_level) tile payloads.

Caches the already-converted, servable payload rather than raw upstream
elevation — conversion is a cheap, pure function (height_mapping.py), so
there is nothing to gain from caching pre-conversion, and caching post-
conversion means a cache hit is a straight file read with no recompute.
Since Phase 8 that argument is stronger, not weaker: the water plane is the
output of a carve against an L1 solve, which is emphatically not cheap.

Tiles are stored under a subdirectory fingerprinted by every knob that
affects the output (scale, tile size, meters/block, sea level, world height,
noise, the elevation curve, and — via `extra` — the hydrology knobs, which
live in `hydrology/` and are hashed there so this module needs no import from
it). A config change therefore starts a fresh cache namespace instead of
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


# Bumped whenever the *shape* of the mapping changes in a way the numeric knobs
# below do not capture -- otherwise a new curve form with unchanged knob values
# would keep serving tiles built by the old one.
#   1: linear elevation -> block height
#   2: integrated rate curve (height_mapping.HeightCurve)
#   3: third plane, per-column water level (Phase 8)
#   4: 3D river planes: tunnel floor, tunnel roof, flow octant (protocol v3)
SCHEMA_VERSION = 4

#: Planes in a cached payload, in order: block height, biome id, water level, river tunnel
#: floor, river tunnel roof, river flow octant (the last three -1 where there is none).
PLANES = 6
#: The river planes' "nothing here" value (TerrainTile.NO_TUNNEL / NO_FLOW).
NO_RIVER = -1


def no_river_planes(shape) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    return tuple(np.full(shape, NO_RIVER, dtype=np.int16) for _ in range(3))


def _config_fingerprint(cfg: BridgeConfig, extra: str = "") -> str:
    raw = (
        f"v{SCHEMA_VERSION}|{cfg.scale}|{cfg.tile_size_blocks}|{cfg.meters_per_block}|"
        f"{cfg.sea_level}|{cfg.world_height}|{cfg.noise_scale}|"
        f"{cfg.horizontal_meters_per_block}|"
        f"{cfg.ocean_meters_per_block}|{cfg.lowland_meters_per_block}|"
        f"{cfg.midland_meters_per_block}|{cfg.highland_meters_per_block}|"
        f"{cfg.lowland_top_m}|{cfg.highland_base_m}|"
        f"{cfg.shore_blend_m}|{cfg.midland_blend_m}|{cfg.highland_blend_m}|"
        f"water={extra}"
    )
    if cfg.downscale != 1:
        # Appended only when set, so existing namespaces keep their hashes.
        raw += f"|downscale={cfg.downscale}"
    if cfg.upstream_id:
        # Appended only when set, so the stock model's namespace keeps its old hash.
        raw += f"|upstream={cfg.upstream_id}"
    return hashlib.sha1(raw.encode()).hexdigest()[:12]


class TileCache:
    def __init__(self, cfg: BridgeConfig, extra_fingerprint: str = ""):
        self._root = Path(cfg.cache_dir) / _config_fingerprint(cfg, extra_fingerprint)
        self._root.mkdir(parents=True, exist_ok=True)
        self._max_bytes = cfg.cache_max_bytes
        self._lock = threading.Lock()

    @property
    def root(self) -> Path:
        return self._root

    def _path(self, tile: TileId) -> Path:
        return self._root / f"{tile.cache_key()}.bin"

    def get(self, tile: TileId) -> tuple[np.ndarray, ...] | None:
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
        if len(body) != n * PLANES:
            # Belt and braces behind the fingerprint: a payload of the wrong plane count
            # would otherwise be sliced into plausible garbage terrain.
            raise ValueError(
                f"cached tile {tile.cache_key()} is {len(body)} bytes for {h}x{w}, "
                f"expected {n * PLANES} ({PLANES} int16 planes)"
            )
        return tuple(
            np.frombuffer(body[k * n: (k + 1) * n], dtype="<i2").reshape(h, w)
            for k in range(PLANES)
        )

    def put(
        self,
        tile: TileId,
        block_height: np.ndarray,
        biome: np.ndarray,
        water_level: np.ndarray,
        river: tuple[np.ndarray, np.ndarray, np.ndarray] | None = None,
    ) -> None:
        h, w = block_height.shape
        river = river if river is not None else no_river_planes((h, w))
        header = np.array([h, w], dtype="<u4").tobytes()
        payload = header + b"".join(
            plane.astype("<i2").tobytes() for plane in (block_height, biome, water_level, *river)
        )
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
