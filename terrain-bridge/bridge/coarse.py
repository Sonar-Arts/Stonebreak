"""Coarse elevation chunks — the field the river walker descends.

Rivers are planned by a budgeted downhill walk (see the game side's
`river_plan.hpp`). That walk needs elevation over kilometres, which is far more
than the 3x3 tile window the water kernel sees, but it needs it *coarsely*: one
value per 16 blocks is plenty for routing a channel whose step is 20 blocks.
This module serves exactly that and nothing else.

Three decisions worth knowing before touching it:

**Chunks, not bounding boxes.** `tiling.py` records that upstream's determinism
guarantee holds only for an *identical* request shape — the same ground fetched
through a small vs. large box differs by ~1 m on a few pixels. The walker
samples across chunk edges constantly, so an ad hoc box would let a route bend
depending on which request happened to serve a sample, and a bent route is a
seam. A chunk therefore has one canonical shape, derived from its ID alone,
exactly like a tile.

**Block heights, not metres.** The values are fractional block heights, run
through the same `HeightCurve` the tile path uses. Two reasons: the walker's
output is a water surface that has to be comparable to `TerrainTile`'s block
heights, and this is the only place that owns the curve — a second copy on the
game side would be a silent divergence. Fractional matters just as much:
`hydrology/README.md` measured 40 % of land as perfectly flat once quantised to
whole blocks (15 m each), which degenerates downhill routing into a distance
field and produces herringbone chevrons instead of rivers.

**Averaged in metres, then mapped.** The curve is non-linear, so pooling has to
happen on the elevation side to mean anything. What comes back is the block
height *of the mean elevation* over the cell, which is the trend surface a
router wants, rather than the mean of a non-linear function of it.

Cost: one chunk is a single square native-resolution fetch (1024^2 px at the
default 2048-block chunk and scale 2), roughly a second or two of GPU, cached
to disk forever after. Square is deliberate — a long thin window re-pays
upstream's decoder overlap on its long edge and collapses from ~1000k px/s to
~70k.
"""
from __future__ import annotations

import hashlib
import logging
import threading
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from .config import BridgeConfig
from .height_mapping import HeightCurve
from .upstream_client import UpstreamClient

log = logging.getLogger("terrain_bridge.coarse")

# Bumped whenever the stored payload's meaning changes (cell layout, dtype, or
# what the values represent). Part of the cache namespace, so a bump orphans the
# old chunks rather than silently mixing them with new ones.
SCHEMA_VERSION = 1


@dataclass(frozen=True)
class CoarseChunkId:
    seed: int
    chunk_x: int
    chunk_z: int

    def cache_key(self) -> str:
        return f"s{self.seed}_cx{self.chunk_x}_cz{self.chunk_z}"


def _fingerprint(cfg: BridgeConfig) -> str:
    """Everything that changes a chunk's contents, hashed into its directory."""
    raw = (
        f"v{SCHEMA_VERSION}|{cfg.scale}|{cfg.coarse_chunk_blocks}|{cfg.coarse_cell_blocks}|"
        f"{cfg.sea_level}|{cfg.world_height}|"
        f"{cfg.ocean_meters_per_block}|{cfg.lowland_meters_per_block}|"
        f"{cfg.midland_meters_per_block}|{cfg.highland_meters_per_block}|"
        f"{cfg.lowland_top_m}|{cfg.highland_base_m}|"
        f"{cfg.shore_blend_m}|{cfg.midland_blend_m}|{cfg.highland_blend_m}"
    )
    return hashlib.sha1(raw.encode()).hexdigest()[:12]


class CoarseElevation:
    """Chunk store: disk-cached, generating on miss. Thread-safe."""

    def __init__(self, cfg: BridgeConfig, client: UpstreamClient):
        self._cfg = cfg
        self._client = client
        self._curve = HeightCurve.from_config(cfg)
        self._root = Path(cfg.cache_dir) / f"coarse_{_fingerprint(cfg)}"
        self._root.mkdir(parents=True, exist_ok=True)
        # One chunk at a time. A chunk is a big GPU request and the tile queue is
        # already competing for the same device; letting several stack up turns a
        # 2 s fetch into a stall for everything.
        self._lock = threading.Lock()

        chunk, cell = cfg.coarse_chunk_blocks, cfg.coarse_cell_blocks
        if chunk % cell != 0:
            raise ValueError(f"coarse chunk {chunk} is not a multiple of cell {cell}")
        if cell % cfg.scale != 0:
            raise ValueError(
                f"coarse cell {cell} blocks is not a multiple of scale {cfg.scale}; "
                "a cell must be a whole number of native pixels to pool cleanly"
            )
        self._cells = chunk // cell
        self._px_per_cell = cell // cfg.scale

    @property
    def root(self) -> Path:
        return self._root

    @property
    def cells_per_chunk(self) -> int:
        return self._cells

    def _path(self, chunk: CoarseChunkId) -> Path:
        return self._root / f"{chunk.cache_key()}.f32"

    def chunk(self, chunk_x: int, chunk_z: int) -> np.ndarray:
        """Fractional block heights, (cells, cells) float32, row = X, col = Z."""
        cid = CoarseChunkId(self._cfg.seed, chunk_x, chunk_z)
        path = self._path(cid)
        if path.exists():
            try:
                data = np.fromfile(path, dtype="<f4")
                if data.size == self._cells * self._cells:
                    return data.reshape(self._cells, self._cells)
                log.warning("coarse chunk %s has %d values, expected %d; regenerating",
                            cid.cache_key(), data.size, self._cells * self._cells)
            except OSError as e:
                log.warning("coarse chunk %s unreadable (%s); regenerating", cid.cache_key(), e)

        with self._lock:
            # Another thread may have finished it while we waited for the lock.
            if path.exists():
                data = np.fromfile(path, dtype="<f4")
                if data.size == self._cells * self._cells:
                    return data.reshape(self._cells, self._cells)
            cells = self._generate(chunk_x, chunk_z)
            tmp = path.with_suffix(".tmp")
            cells.tofile(tmp)
            tmp.replace(path)
            return cells

    def _generate(self, chunk_x: int, chunk_z: int) -> np.ndarray:
        """The canonical request for this chunk. Shape depends on the ID alone."""
        chunk_blocks = self._cfg.coarse_chunk_blocks
        scale = self._cfg.scale
        # Native pixels, floor-divided so negative chunks tile contiguously.
        i1 = (chunk_x * chunk_blocks) // scale
        j1 = (chunk_z * chunk_blocks) // scale
        span = chunk_blocks // scale
        elev_m = self._client.fetch_native(i1, j1, i1 + span, j1 + span)
        if elev_m.shape != (span, span):
            raise ValueError(
                f"upstream returned {elev_m.shape} for coarse chunk "
                f"({chunk_x},{chunk_z}), expected {(span, span)}"
            )

        # Pool in METRES — the curve is non-linear, so averaging after mapping
        # would not be the height of the average elevation.
        n, k = self._cells, self._px_per_cell
        pooled = elev_m.astype(np.float64).reshape(n, k, n, k).mean(axis=(1, 3))
        blocks = self._curve.to_block_height_exact(pooled)
        # Clamped like the tile path, but left fractional.
        blocks = np.clip(blocks, 0.0, self._cfg.world_height - 1)
        return blocks.astype("<f4")
