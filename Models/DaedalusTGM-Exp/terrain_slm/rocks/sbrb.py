"""SBRB v1 rock shape bank - writer + validating reader (format owned by agent P: stonebreak-game/native/src/rocks.hpp,
`Dev Working/docs/exp2-work/P-boulders.md`). Mirrors `sbw::parseRockBank` rule for rule.

A shape here is a numpy bool array `occ[x, y, z]` (y = UP, y = 0 the bottom) plus optional `hint[x, y, z]` uint8
(0 body, 1 moss hint, 2 rough hint).
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field

import numpy as np
from scipy import ndimage

MAGIC = b"SBRB"
VERSION = 1
HEADER = 32
ENTRY = 16
MAX_DXZ = 15
MAX_DY = 20
CLASSES = 5
FLAG_FLAT_TOP, FLAG_MATERIAL, FLAG_SLAB = 0x1, 0x2, 0x4
CLASS_NAMES = ["pebble", "small", "medium", "large", "erratic"]


def fnv1a64(b: bytes) -> int:
    """FNV-1a 64 (vectorised over 8-byte-independent steps is not possible; plain loop on a memoryview)."""
    h = 0xCBF29CE484222325
    for x in b:
        h = ((h ^ x) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


@dataclass
class Shape:
    cls: int
    occ: np.ndarray                    # bool [dx, dy, dz]
    sink: int = 0
    flags: int = 0
    hint: np.ndarray | None = None     # uint8 [dx, dy, dz] or None
    meta: dict = field(default_factory=dict)

    @property
    def dims(self):
        return self.occ.shape


def _order(a: np.ndarray) -> np.ndarray:
    """[x, y, z] -> flat in SBRB order: index (y * dz + z) * dx + x."""
    return np.transpose(a, (1, 2, 0)).reshape(-1)


def _unorder(flat: np.ndarray, dx: int, dy: int, dz: int) -> np.ndarray:
    return np.transpose(flat.reshape(dy, dz, dx), (2, 0, 1))


def validate_shape(s: Shape) -> str | None:
    dx, dy, dz = s.occ.shape
    if not (0 <= s.cls < CLASSES) or not (1 <= dx <= MAX_DXZ and 1 <= dz <= MAX_DXZ and 1 <= dy <= MAX_DY):
        return "bad class / dims"
    if not (0 <= s.sink < dy):
        return "bad sink"
    if not s.occ[:, 0, :].any():
        return "empty bottom row"
    _, n = ndimage.label(s.occ)  # default structure = 6-connectivity
    if n != 1:
        return "not one 6-connected component"
    if s.hint is not None and s.hint.shape != s.occ.shape:
        return "hint shape"
    return None


def write_bank(shapes: list[Shape], bank_id: int) -> bytes:
    if not 1 <= len(shapes) <= 65535:
        raise ValueError("shape count")
    table = bytearray()
    payload = bytearray()
    base = HEADER + ENTRY * len(shapes)
    any_mat = False
    for k, s in enumerate(shapes):
        err = validate_shape(s)
        if err:
            raise ValueError(f"shape {k}: {err}")
        dx, dy, dz = s.occ.shape
        flags = s.flags & ~FLAG_MATERIAL
        body = np.packbits(_order(s.occ).astype(np.uint8), bitorder="little").tobytes()
        if s.hint is not None:
            flags |= FLAG_MATERIAL
            any_mat = True
            h = _order(np.where(s.occ, s.hint, 0).astype(np.uint8) & 3)
            h = np.pad(h, (0, (-len(h)) % 4)).reshape(-1, 4)
            body += (h[:, 0] | (h[:, 1] << 2) | (h[:, 2] << 4) | (h[:, 3] << 6)).astype(np.uint8).tobytes()
        table += struct.pack("<BBBBBBHII", s.cls, dx, dy, dz, s.sink, flags, 0, base + len(payload), len(body))
        payload += body
    content = bytes(table + payload)
    header = struct.pack("<4sHHIIIIQ", MAGIC, VERSION, HEADER, len(shapes), 1 if any_mat else 0, bank_id, 0,
                         fnv1a64(content))
    return header + content


def read_bank(data: bytes) -> tuple[int, list[Shape]]:
    """Parse + validate like sbw::parseRockBank; raises ValueError with the native error text."""
    if len(data) < HEADER or data[:4] != MAGIC:
        raise ValueError("not an SBRB rock bank")
    _, version, hsize, count, _flags, bank_id, _r, h = struct.unpack_from("<4sHHIIIIQ", data, 0)
    if version != 1:
        raise ValueError(f"unsupported SBRB version {version}")
    if hsize < HEADER or hsize > len(data):
        raise ValueError("bad SBRB header size")
    if fnv1a64(data[hsize:]) != h:
        raise ValueError("SBRB content hash mismatch")
    if count == 0 or count > 65535 or hsize + count * ENTRY > len(data):
        raise ValueError("bad SBRB shape count")
    shapes = []
    for k in range(count):
        cls, dx, dy, dz, sink, flags, _, off, plen = struct.unpack_from("<BBBBBBHII", data, hsize + k * ENTRY)
        n = dx * dy * dz
        mat = bool(flags & FLAG_MATERIAL)
        need = (n + 7) // 8 + ((n + 3) // 4 if mat else 0)
        if plen < need or off + plen > len(data) or off < hsize + count * ENTRY:
            raise ValueError(f"SBRB shape {k}: payload out of range")
        p = np.frombuffer(data, np.uint8, count=plen, offset=off)
        occ = _unorder(np.unpackbits(p[:(n + 7) // 8], bitorder="little")[:n].astype(bool), dx, dy, dz)
        hint = None
        if mat:
            m = p[(n + 7) // 8:(n + 7) // 8 + (n + 3) // 4]
            v = np.stack([(m >> (2 * i)) & 3 for i in range(4)], 1).reshape(-1)[:n]
            v = np.where(v == 3, 0, v).astype(np.uint8)
            hint = _unorder(v, dx, dy, dz)
        s = Shape(cls, occ, sink, flags, hint)
        err = validate_shape(s)
        if err:
            raise ValueError(f"SBRB shape {k}: {err}")
        shapes.append(s)
    return bank_id, shapes
