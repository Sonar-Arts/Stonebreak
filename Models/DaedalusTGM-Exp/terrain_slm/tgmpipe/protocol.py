"""TGMPipe wire protocol: length-prefixed binary frames over the service's stdin/stdout.

Mirror of stonebreak-game's `TGMPipeProtocol` -- change both together and bump VERSION.

Frame: u32 length (of everything after it), u8 type, body. Little-endian throughout.

Game -> service
  HELLO      u16 version, UTF-8 JSON {"world": WorldConfig}
  TILE       u32 id, i64 seed, i32 tile_x, i32 tile_z, u8 lod, u8 priority (lower = sooner)
  CANCEL     u32 id -- the game no longer wants that request's answer
  STATUS     u32 token

Service -> game
  READY        UTF-8 JSON {"name", "model_id", "device", "cache", ...}, once, after HELLO
  TILE_DATA    u32 id, u8 flags (bit 0 = from the disk cache), u16 rows, u16 cols, i32 i1, i32 j1,
               then PLANES int16 planes of rows x cols, row-major (row = x - i1, col = z - j1):
               block height, biome id, water level, river tunnel floor, roof, flow octant
  TILE_ERROR   u32 id, UTF-8 message
  STATUS_REPLY u32 token, UTF-8 JSON
  FATAL        UTF-8 message; the service exits after sending it

Every TILE is answered by exactly one TILE_DATA or TILE_ERROR, unless it was cancelled first.
"""
from __future__ import annotations

import json
import struct
from typing import BinaryIO

VERSION = 1
PLANES = 6

HELLO, TILE, CANCEL, STATUS = 0x01, 0x02, 0x03, 0x04
READY, TILE_DATA, TILE_ERROR, STATUS_REPLY, FATAL = 0x81, 0x82, 0x83, 0x84, 0x8F

FLAG_CACHED = 0x01
MAX_FRAME = 64 << 20

_LEN = struct.Struct("<I")
_TILE = struct.Struct("<IqiiBB")
_TILE_HEAD = struct.Struct("<IBHHii")
_U32 = struct.Struct("<I")
_U16 = struct.Struct("<H")


class ProtocolError(RuntimeError):
    pass


def read_frame(stream: BinaryIO) -> tuple[int, bytes] | None:
    """(type, body), or None at a clean end of stream (the game closed its end)."""
    head = _read_exact(stream, _LEN.size, allow_eof=True)
    if head is None:
        return None
    (n,) = _LEN.unpack(head)
    if not 1 <= n <= MAX_FRAME:
        raise ProtocolError(f"bad frame length {n}")
    frame = _read_exact(stream, n)
    return frame[0], frame[1:]


def _read_exact(stream: BinaryIO, n: int, allow_eof: bool = False) -> bytes | None:
    buf = bytearray()
    while len(buf) < n:
        chunk = stream.read(n - len(buf))
        if not chunk:
            if allow_eof and not buf:
                return None
            raise ProtocolError(f"stream ended mid-frame ({len(buf)} of {n} bytes)")
        buf += chunk
    return bytes(buf)


def frame(kind: int, *parts: bytes) -> bytes:
    body = b"".join(parts)
    return _LEN.pack(1 + len(body)) + bytes([kind]) + body


# ----------------------------------------------------------------------------- game -> service
def parse_hello(body: bytes) -> tuple[int, dict]:
    (version,) = _U16.unpack_from(body)
    return version, json.loads(body[_U16.size:].decode())


def parse_tile(body: bytes) -> tuple[int, int, int, int, int, int]:
    """(id, seed, tile_x, tile_z, lod, priority)"""
    return _TILE.unpack(body)


def parse_u32(body: bytes) -> int:
    return _U32.unpack(body)[0]


def hello(version: int, payload: dict) -> bytes:
    return frame(HELLO, _U16.pack(version), json.dumps(payload).encode())


def tile(req_id: int, seed: int, tile_x: int, tile_z: int, lod: int, priority: int) -> bytes:
    return frame(TILE, _TILE.pack(req_id, seed, tile_x, tile_z, lod, priority))


def cancel(req_id: int) -> bytes:
    return frame(CANCEL, _U32.pack(req_id))


# ----------------------------------------------------------------------------- service -> game
def ready(payload: dict) -> bytes:
    return frame(READY, json.dumps(payload).encode())


def tile_data(req_id: int, cached: bool, i1: int, j1: int, planes) -> bytes:
    """`planes`: int16 array (PLANES, rows, cols)."""
    k, rows, cols = planes.shape
    if k != PLANES:
        raise ValueError(f"expected {PLANES} planes, got {k}")
    head = _TILE_HEAD.pack(req_id, FLAG_CACHED if cached else 0, rows, cols, i1, j1)
    return frame(TILE_DATA, head, planes.astype("<i2", copy=False).tobytes())


def parse_tile_data(body: bytes):
    """(id, cached, i1, j1, planes (PLANES, rows, cols) int16) -- for tests and tools."""
    import numpy as np

    req_id, flags, rows, cols, i1, j1 = _TILE_HEAD.unpack_from(body)
    planes = np.frombuffer(body[_TILE_HEAD.size:], dtype="<i2")
    if planes.size != PLANES * rows * cols:
        raise ProtocolError(f"tile body holds {planes.size} values, expected {PLANES}x{rows}x{cols}")
    return req_id, bool(flags & FLAG_CACHED), i1, j1, planes.reshape(PLANES, rows, cols)


def tile_error(req_id: int, message: str) -> bytes:
    return frame(TILE_ERROR, _U32.pack(req_id), message.encode()[:4096])


def status_reply(token: int, payload: dict) -> bytes:
    return frame(STATUS_REPLY, _U32.pack(token), json.dumps(payload).encode())


def fatal(message: str) -> bytes:
    return frame(FATAL, message.encode()[:4096])
