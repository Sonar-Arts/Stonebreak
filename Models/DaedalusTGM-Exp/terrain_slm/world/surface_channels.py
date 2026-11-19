"""Per-block SURFACE CHANNELS: the generic, named uint8 fields a tile carries next to its int16 planes (TGMPipe v3).

Contract (agent V, 2026-11-19; mirrored by Java `diffusion/SurfaceChannel` + `TerrainTile.surface*` and native
`sbw_column_profile.surface` / `SBW_SURF_*` in worldgen.h):

- Every channel is one byte per block column, same grid as the int16 planes (row = x - i1, col = z - j1).
- Byte 0..254 encodes `physical = offset + byte * scale` (per channel, below); **255 = NO DATA** (sea, lake, river
  water, a tile built without the producing node, an overview tile): every consumer must then behave exactly as it
  did before the channel existed. Producers write NaN for "no data"; `encode` turns NaN into 255.
- On the wire (TILE_DATA after the 7 int16 planes): `u8 count`, then per channel `u8 name_len, name (ASCII),
  f32 scale, f32 offset`, then `count` planes of rows x cols uint8, in the header's order. The order is not fixed:
  readers look channels up BY NAME and ignore names they do not know (forward compatible).
- Native: the game copies known channels into `sbw_column_profile.surface[slot][1024]` (column-cube layout
  [lz * 32 + lx]); `slot` is fixed per name (SLOTS below = `SBW_SURF_*`); unknown/absent = 255 everywhere.
- Adding a channel: add a `Channel` here (name, slot, scale, offset, unit, owner), a provider in the tile builder
  (TileBuilder.surface_providers) that returns {name: float tensor (tile + margin cropped to the tile), NaN = no
  data}, the Java enum constant, and the native `SBW_SURF_*` slot. The wire format never changes for a new channel.
"""
from __future__ import annotations

import struct
from dataclasses import dataclass

import numpy as np

NO_DATA = 255
MAX_SLOTS = 16   # native sbw_column_profile.surface[MAX_SLOTS][1024]


@dataclass(frozen=True)
class Channel:
    name: str
    slot: int          # native slot (SBW_SURF_*); fixed forever once shipped
    scale: float       # physical = offset + byte * scale
    offset: float
    unit: str
    owner: str
    doc: str


CHANNELS: tuple[Channel, ...] = (
    # --- vegetation node (agent V): ETH canopy height 2020 + ESA WorldCover 2021, natural land only
    Channel("canopy_cover", 0, 1 / 254, 0.0, "fraction", "V",
            "share of the ground under tree canopy >= 5 m (expected value over ~300 m)"),
    Channel("canopy_height", 1, 0.25, 0.0, "m", "V",
            "mean height of the canopy trees (>= 5 m) where trees grow; 0..63.5 m"),
    Channel("clearing", 2, 1 / 254, 0.0, "fraction", "V",
            "share of the neighbourhood (~300 m) that is open clearing (cover < 25 %) - drives gap patches"),
    Channel("ground_grass", 3, 1 / 254, 0.0, "fraction", "V", "grassland / herbaceous share of the ground"),
    Channel("ground_shrub", 4, 1 / 254, 0.0, "fraction", "V", "shrubland share of the ground (bushes)"),
    Channel("ground_moss", 5, 1 / 254, 0.0, "fraction", "V", "moss / lichen share of the ground"),
    Channel("ground_bare", 6, 1 / 254, 0.0, "fraction", "V", "bare / sparsely vegetated share of the ground"),
    Channel("ground_wetland", 7, 1 / 254, 0.0, "fraction", "V", "herbaceous wetland / mangrove share of the ground"),
    # --- land surface materials + boulders (agent L: surface-detail node, world/surface_node.py, 1 m lidar labels of
    #     data/surface_labels.py)
    Channel("rock_exposure", 8, 1 / 254, 0.0, "fraction", "L",
            "share of the block's ground that is bare rock wall / outcrop face (1 m lidar slope >= 45 deg)"),
    Channel("scree", 9, 1 / 254, 0.0, "fraction", "L",
            "share that is rough talus / block field (1 m slope 28-45 deg with >= 0.12 m RMS 1-5 m roughness)"),
    Channel("boulder_density", 10, 1 / 254, 0.0, "per 100 m^2", "L",
            "expected boulders (1.6-6.8 m across, >= 0.6 m tall) per 100 m^2 of ground, capped at 1 "
            "(= 2.25 per 15 m block, 36 per 60 m block)"),
    Channel("boulder_size", 11, 0.05, 0.0, "m", "L", "typical boulder diameter where boulders occur; 0..12.7 m"),
    Channel("surface_roughness", 12, 0.01, 0.0, "m", "L",
            "RMS of the ground's relief finer than ~10 m inside the block (0..2.54 m)"),
    # slots 13..15 free
)
BY_NAME = {c.name: c for c in CHANNELS}
assert len({c.slot for c in CHANNELS}) == len(CHANNELS) and all(0 <= c.slot < MAX_SLOTS for c in CHANNELS)


def encode(name: str, values) -> np.ndarray:
    """Physical values (array / tensor, NaN = no data) -> uint8 bytes (255 = no data)."""
    c = BY_NAME[name]
    v = values.detach().float().cpu().numpy() if hasattr(values, "detach") else np.asarray(values, np.float32)
    q = np.rint((v - c.offset) / c.scale)
    out = np.clip(np.nan_to_num(q, nan=NO_DATA), 0, 254).astype(np.uint8)
    out[np.isnan(v)] = NO_DATA
    return out


def decode(name: str, data: np.ndarray) -> np.ndarray:
    c = BY_NAME[name]
    out = c.offset + data.astype(np.float32) * c.scale
    out[data == NO_DATA] = np.nan
    return out


def pack(channels: dict[str, np.ndarray]) -> bytes:
    """Wire/cache encoding of a tile's surface block (`channels`: name -> uint8 (rows, cols), insertion order)."""
    parts = [bytes([len(channels)])]
    for name, data in channels.items():
        c = BY_NAME.get(name)
        nb = name.encode("ascii")
        parts.append(bytes([len(nb)]) + nb + struct.pack("<ff", c.scale if c else 1.0, c.offset if c else 0.0))
    for data in channels.values():
        parts.append(np.ascontiguousarray(data, dtype=np.uint8).tobytes())
    return b"".join(parts)


def unpack(buf: bytes, rows: int, cols: int) -> tuple[dict[str, np.ndarray], int]:
    """(name -> uint8 (rows, cols), bytes consumed) from `buf` (a surface block, see pack)."""
    n, pos, names = buf[0], 1, []
    for _ in range(n):
        k = buf[pos]
        names.append(buf[pos + 1 : pos + 1 + k].decode("ascii"))
        pos += 1 + k + 8
    out = {}
    for name in names:
        out[name] = np.frombuffer(buf, np.uint8, rows * cols, pos).reshape(rows, cols)
        pos += rows * cols
    return out, pos


class TilePlanes(np.ndarray):
    """A tile's int16 planes (PLANES, rows, cols) carrying its surface channels as `.surface` (name -> uint8
    (rows, cols)); a plain ndarray everywhere else (cache, wire, tests compare it as one)."""

    def __new__(cls, planes: np.ndarray, surface: dict | None = None):
        obj = np.asarray(planes).view(cls)
        obj.surface = dict(surface or {})
        return obj

    def __array_finalize__(self, obj):
        self.surface = getattr(obj, "surface", {})


def surface_of(planes) -> dict:
    return getattr(planes, "surface", None) or {}
