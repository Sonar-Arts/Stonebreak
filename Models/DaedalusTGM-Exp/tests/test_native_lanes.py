"""Lanes of the standalone native pipeline (agent PX, libstonebreak_terrain sbtg_terrain_set_lanes / _tile_lane) and the
height-only planner window tier: tiles are bit-identical to the sequential pipeline whatever the lane count, the lane
that builds a tile, the request order or the concurrency (the lanes share models and window caches; a window in flight
in one lane is waited for by the others) - the request-independence invariant, checked over 1:4 / 1:2 / 1:1 worlds with
one seed (the shared caches are keyed by seed, never by world), lods 1 / 2 / 4 / 8 / 16 and neighbouring tiles.

Needs CUDA, the Cenda engine and the default release's native export (checkpoints/<release>/native) and a
libstonebreak_terrain with lanes; skipped otherwise. TGM_TEST_DEVICE picks the card (default cuda:1)."""
import os
import random
import threading
from pathlib import Path

import numpy as np
import pytest
import torch

DEV = os.getenv("TGM_TEST_DEVICE", "cuda:1")
ROOT = Path(__file__).resolve().parents[1]


def _native_dir() -> Path:
    from terrain_slm.model_files import DEFAULT_MODEL
    return ROOT / DEFAULT_MODEL / "native"


def _ok() -> str | None:
    if not torch.cuda.is_available() or not (_native_dir() / "manifest.json").exists():
        return "needs CUDA and the default release's native export"
    try:
        from terrain_slm.export import cenda_engine as E
        from terrain_slm.export import native_pipeline as NP
        if not E.available(DEV):
            return "Cenda engine unavailable"
        if not hasattr(NP.library(), "sbtg_terrain_set_lanes"):
            return "libstonebreak_terrain without lanes"
    except Exception as e:  # noqa: BLE001
        return f"native library unavailable: {e}"
    return None


_WHY = _ok()
pytestmark = pytest.mark.skipif(_WHY is not None, reason=_WHY or "")

SEED = -4242
# (world index, tile_x, tile_z, lod): neighbours at 1:4 / 1:2 / 1:1, coarse far-LOD and overview tiles.
TILES = ([(0, x, z, 1) for x in (0, 1) for z in (0, 1)] + [(1, x, 0, 1) for x in (0, 1)] + [(2, 0, z, 1) for z in (0, 1)]
         + [(0, 0, 0, 2), (0, 1, 0, 2), (0, 0, 0, 4), (1, 0, 0, 2), (1, 0, 0, 8), (0, 0, 0, 16), (2, 0, 0, 16)])


def _worlds():
    from tests.test_native_tiles import _world
    return [_world("1:4", 2).to_dict(), _world("1:2", 2).to_dict(), _world("1:1", 1).to_dict()]


def _open(lanes: int):
    from terrain_slm.export import native_terrain as NT
    t = NT.NativeTerrain(_native_dir(), torch.device(DEV).index, lanes=lanes)
    for i, w in enumerate(_worlds()):
        t.set_world(i, w)
    return t


def _key(p):
    return np.asarray(p).view(np.ndarray).tobytes() + b"".join(
        k.encode() + np.asarray(v).tobytes() for k, v in sorted(p.surface.items()))


def _build(t, tiles, lanes: int) -> dict:
    out, lock, errs = {}, threading.Lock(), []
    todo = list(tiles)

    def work(lane):
        try:
            while True:
                with lock:
                    if not todo:
                        return
                    w, x, z, lod = todo.pop()
                p = t.tile(w, SEED, x, z, lod, lane)
                with lock:
                    out[(w, x, z, lod)] = _key(p)
        except Exception as e:  # noqa: BLE001
            errs.append(e)

    th = [threading.Thread(target=work, args=(i,)) for i in range(lanes)]
    [x.start() for x in th]
    [x.join() for x in th]
    if errs:
        raise errs[0]
    return out


@pytest.fixture(scope="module")
def reference():
    """The sequential pipeline without the height tier (the behaviour before lanes), fixed order."""
    old = os.environ.get("SBTG_PLANNER_H_CAP")
    os.environ["SBTG_PLANNER_H_CAP"] = "0"
    try:
        t = _open(1)
        assert t.lanes == 1
        ref = _build(t, list(reversed(TILES)), 1)
        del t
    finally:
        if old is None:
            os.environ.pop("SBTG_PLANNER_H_CAP", None)
        else:
            os.environ["SBTG_PLANNER_H_CAP"] = old
    return ref


@pytest.mark.parametrize("lanes,order", [(1, 3), (2, 7), (3, 11)])
def test_lanes_bitwise_any_order(reference, lanes, order):
    t = _open(lanes)
    assert t.lanes == lanes
    tiles = list(TILES)
    random.Random(order).shuffle(tiles)
    got = _build(t, tiles, lanes)
    bad = [k for k in reference if got.get(k) != reference[k]]
    assert not bad, f"{len(bad)} tiles differ with {lanes} lanes: {bad[:5]}"


def test_lanes_repeat_same_tiles_concurrently(reference):
    """Every lane asks for the SAME tiles at once (in-flight dedupe: waiting on another lane's windows)."""
    t = _open(3)
    tiles = TILES[:6]
    outs = [None] * 3

    def run(i):
        outs[i] = {k: _key(t.tile(*k[:1], SEED, *k[1:], lane=i)) for k in tiles}

    th = [threading.Thread(target=run, args=(i,)) for i in range(3)]
    [x.start() for x in th]
    [x.join() for x in th]
    for o in outs:
        assert o == {k: reference[k] for k in tiles}


def test_set_lanes_rules():
    from terrain_slm.export import native_terrain as NT
    t = _open(2)
    assert t.lanes == 2
    with pytest.raises(NT.NativeTerrainError):     # only once, before the warm-up and the first tile
        t._check(t.lib.sbtg_terrain_set_lanes(t.handle, 3), "set_lanes")
    with pytest.raises(NT.NativeTerrainError):
        t.tile(0, SEED, 0, 0, 1, lane=5)
