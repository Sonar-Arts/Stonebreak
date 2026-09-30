"""TGMPipe plumbing, no model needed: wire protocol, scheduler, disk cache, and the
service loop end to end against a fake tile builder."""
from __future__ import annotations

import io
import os
import queue
import threading
import time

import numpy as np
import pytest

from terrain_slm.tgmpipe import protocol as W
from terrain_slm.tgmpipe.scheduler import Scheduler, TileKey
from terrain_slm.tgmpipe.service import Output, Service
from terrain_slm.tgmpipe.tile_cache import TileCache, namespace, source_fingerprint

N = 8


def _planes(seed: int, tile_x: int, tile_z: int) -> np.ndarray:
    base = np.arange(W.PLANES * N * N, dtype=np.int16).reshape(W.PLANES, N, N)
    return (base + seed + 10 * tile_x + 100 * tile_z).astype(np.int16)


# ----------------------------------------------------------------------------- protocol
def test_frames_round_trip():
    buf = io.BytesIO(W.tile(7, -3, -1, 2, 8, 1) + W.cancel(7) + W.hello(W.VERSION, {"world": {"a": 1}}))
    kind, body = W.read_frame(buf)
    assert kind == W.TILE and W.parse_tile(body) == (7, -3, -1, 2, 8, 1)
    kind, body = W.read_frame(buf)
    assert kind == W.CANCEL and W.parse_u32(body) == 7
    kind, body = W.read_frame(buf)
    assert kind == W.HELLO and W.parse_hello(body) == (W.VERSION, {"world": {"a": 1}})
    assert W.read_frame(buf) is None


def test_tile_data_round_trips_rows_as_x():
    planes = _planes(1, 2, 3)
    kind, body = W.read_frame(io.BytesIO(W.tile_data(9, True, -256, 512, planes)))
    req_id, cached, i1, j1, got = W.parse_tile_data(body)
    assert (kind, req_id, cached, i1, j1) == (W.TILE_DATA, 9, True, -256, 512)
    assert np.array_equal(got, planes)


def test_truncated_frame_is_an_error_not_an_end():
    data = W.tile(1, 0, 0, 0, 1, 0)
    with pytest.raises(W.ProtocolError):
        W.read_frame(io.BytesIO(data[:-2]))


# ----------------------------------------------------------------------------- scheduler
def test_scheduler_merges_orders_and_cancels():
    s = Scheduler()
    a, b, c = TileKey(1, 0, 0, 1), TileKey(1, 1, 0, 1), TileKey(2, 0, 0, 1)
    s.submit(1, a, 5)
    s.submit(2, b, 5)
    s.submit(3, a, 5)          # same tile: merged into one job
    s.submit(4, c, 9)
    s.submit(5, c, 0)          # a more urgent request promotes the whole job
    s.submit(6, b, 5)
    s.cancel(2)
    s.cancel(6)                # every waiter of b gone before it started: b never runs
    assert s.depth() == 2
    first = s.take(timeout=0)
    assert first.key == c and s.finish(first) == {4, 5}
    second = s.take(timeout=0)
    assert second.key == a and s.finish(second) == {1, 3}
    assert s.take(timeout=0) is None


def test_a_started_job_finishes_for_the_waiters_left():
    s = Scheduler()
    key = TileKey(1, 0, 0, 1)
    s.submit(1, key, 0)
    s.submit(2, key, 0)
    job = s.take(timeout=0)
    s.cancel(1)
    s.submit(3, key, 0)        # joins the running job instead of queueing a second one
    assert s.depth() == 0
    assert s.finish(job) == {2, 3}


# ----------------------------------------------------------------------------- disk cache
def test_cache_round_trip_eviction_and_torn_files(tmp_path):
    one = _planes(0, 0, 0).nbytes + 8
    cache = TileCache(tmp_path, "ns", max_bytes=int(one * 2.5))
    keys = [TileKey(0, x, 0, 1) for x in range(3)]
    for k in keys[:2]:
        cache.put(k, _planes(*k[:3]))
    time.sleep(0.01)
    assert np.array_equal(cache.get(keys[0]), _planes(0, 0, 0))   # touch 0: 1 is now oldest
    cache.put(keys[2], _planes(0, 2, 0))
    assert cache.get(keys[1]) is None and cache.get(keys[0]) is not None
    torn = cache._path(keys[2])
    torn.write_bytes(torn.read_bytes()[:-3])
    assert cache.get(keys[2]) is None and not torn.exists()


def test_cache_budget_spans_namespaces_and_prunes_empty_ones(tmp_path):
    one = _planes(0, 0, 0).nbytes + 8
    old = TileCache(tmp_path, "old", max_bytes=one * 10)
    old.put(TileKey(0, 0, 0, 1), _planes(0, 0, 0))
    new = TileCache(tmp_path, "new", max_bytes=int(one * 1.5))
    assert new.stats()["files"] == 1
    new.put(TileKey(0, 1, 0, 1), _planes(0, 1, 0))
    assert not (tmp_path / "old").exists()


def test_namespace_moves_with_generator_source(tmp_path):
    pkg = tmp_path / "pkg"
    (pkg / "world").mkdir(parents=True)
    (pkg / "tgmpipe").mkdir()
    (pkg / "world" / "a.py").write_text("x = 1\n")
    (pkg / "tgmpipe" / "b.py").write_text("y = 1\n")
    before = source_fingerprint(pkg)
    (pkg / "tgmpipe" / "b.py").write_text("y = 2\n")
    assert source_fingerprint(pkg) == before, "the service's own code does not change tiles"
    (pkg / "world" / "a.py").write_text("x = 2\n")
    assert source_fingerprint(pkg) != before
    assert namespace("w", "m", "s1") != namespace("w", "m", "s2")


# ----------------------------------------------------------------------------- service loop
class FakeBuilder:
    def __init__(self):
        self.built = []
        self.gate = threading.Event()
        self.gate.set()

    def bounds(self, tile_x, tile_z):
        return tile_x * N, tile_z * N, (tile_x + 1) * N, (tile_z + 1) * N

    def build(self, seed, tile_x, tile_z, lod):
        self.gate.wait(5)
        if tile_x == 99:
            raise RuntimeError("boom")
        self.built.append((seed, tile_x, tile_z, lod))
        return _planes(seed, tile_x, tile_z)


class Pipe:
    """The game's end: frames the service sent, decoded as they arrive."""

    def __init__(self):
        r, w = os.pipe()
        self.out = Output(os.fdopen(w, "wb"))
        self._in = os.fdopen(r, "rb")
        self.frames: queue.Queue = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        while (f := W.read_frame(self._in)) is not None:
            self.frames.put(f)

    def next(self, timeout=5.0):
        return self.frames.get(timeout=timeout)


@pytest.fixture
def svc(tmp_path):
    pipe = Pipe()
    builder = FakeBuilder()
    service = Service(builder, TileCache(tmp_path, "ns", 1 << 30), pipe.out, {"name": "fake"})
    service.start()
    yield service, builder, pipe
    service.close()


def _ask(service, req_id, seed, x, z, lod=1, priority=0):
    service.dispatch(W.TILE, W.tile(req_id, seed, x, z, lod, priority)[5:])


def test_service_answers_from_the_gpu_then_from_disk(svc):
    service, builder, pipe = svc
    _ask(service, 1, 42, -1, 2)
    kind, body = pipe.next()
    req_id, cached, i1, j1, planes = W.parse_tile_data(body)
    assert (kind, req_id, cached, i1, j1) == (W.TILE_DATA, 1, False, -N, 2 * N)
    assert np.array_equal(planes, _planes(42, -1, 2))
    deadline = time.monotonic() + 5
    while service.cache.get(TileKey(42, -1, 2, 1)) is None and time.monotonic() < deadline:
        time.sleep(0.01)
    _ask(service, 2, 42, -1, 2)
    _, body = pipe.next()
    assert W.parse_tile_data(body)[:2] == (2, True)
    assert builder.built == [(42, -1, 2, 1)]


def test_seeds_are_per_request_and_errors_reach_every_waiter(svc):
    service, builder, pipe = svc
    builder.gate.clear()                       # hold the GPU so both requests queue together
    _ask(service, 1, 99, 99, 0)
    _ask(service, 2, 99, 99, 0)
    _ask(service, 3, 5, 0, 0)
    _ask(service, 4, 6, 0, 0)
    builder.gate.set()
    got = {}
    for _ in range(4):
        kind, body = pipe.next()
        req_id = W.parse_u32(body[:4])
        got[req_id] = kind
    assert got == {1: W.TILE_ERROR, 2: W.TILE_ERROR, 3: W.TILE_DATA, 4: W.TILE_DATA}
    assert {b[0] for b in builder.built} == {5, 6}


def test_cancelled_requests_are_never_built_or_answered(svc):
    service, builder, pipe = svc
    builder.gate.clear()
    _ask(service, 1, 1, 0, 0)                  # taken by the GPU, held at the gate
    time.sleep(0.1)
    _ask(service, 2, 1, 1, 0)
    service.dispatch(W.CANCEL, W.cancel(2)[5:])
    _ask(service, 3, 1, 2, 0)
    builder.gate.set()
    answered = sorted(W.parse_u32(pipe.next()[1][:4]) for _ in range(2))
    assert answered == [1, 3]
    time.sleep(0.1)
    assert pipe.frames.empty() and (1, 1, 0, 1) not in builder.built


def test_status_reports_queue_and_cache(svc):
    service, _, pipe = svc
    service.dispatch(W.STATUS, (77).to_bytes(4, "little"))
    kind, body = pipe.next()
    assert kind == W.STATUS_REPLY and W.parse_u32(body[:4]) == 77
    import json
    status = json.loads(body[4:])
    assert status["name"] == "fake" and status["queue"] == 0 and "cache" in status
