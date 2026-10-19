"""GPU job queue: one job per distinct tile, however many requests wait on it.

The generator runs one tile at a time on its GPU, so requests queue here rather than contend
for it. A job is ordered by the most urgent priority any of its requests asked for (then first
come, first served); a request cancelled before its job starts leaves the job, and a job nobody
waits on any more is dropped without running. A job that is already running always finishes --
its tile is cached for whoever asks next.
"""
from __future__ import annotations

import heapq
import itertools
import threading
from dataclasses import dataclass, field
from typing import NamedTuple


class TileKey(NamedTuple):
    seed: int
    tile_x: int
    tile_z: int
    lod: int


@dataclass
class Job:
    key: TileKey
    priority: int
    waiters: set[int] = field(default_factory=set)
    started: bool = False


class Scheduler:
    def __init__(self):
        self._cond = threading.Condition()
        self._heap: list[tuple[int, int, TileKey]] = []  # lazy: stale entries are skipped on pop
        self._seq = itertools.count()
        self._jobs: dict[TileKey, Job] = {}
        self._request_key: dict[int, TileKey] = {}
        self._closed = False

    def submit(self, req_id: int, key: TileKey, priority: int) -> None:
        with self._cond:
            job = self._jobs.get(key)
            if job is None:
                job = self._jobs[key] = Job(key, priority)
                heapq.heappush(self._heap, (priority, next(self._seq), key))
            elif not job.started and priority < job.priority:
                job.priority = priority
                heapq.heappush(self._heap, (priority, next(self._seq), key))
            job.waiters.add(req_id)
            self._request_key[req_id] = key
            self._cond.notify()

    def cancel(self, req_id: int) -> None:
        with self._cond:
            key = self._request_key.pop(req_id, None)
            job = self._jobs.get(key) if key is not None else None
            if job is None:
                return
            job.waiters.discard(req_id)
            if not job.waiters and not job.started:
                del self._jobs[key]

    def take(self, timeout: float | None = None) -> Job | None:
        """The most urgent waiting job, marked started; None on timeout or after close()."""
        with self._cond:
            while True:
                while self._heap:
                    priority, _, key = heapq.heappop(self._heap)
                    job = self._jobs.get(key)
                    if job is not None and not job.started and job.priority == priority:
                        job.started = True
                        return job
                if self._closed or not self._cond.wait(timeout):
                    return None

    def finish(self, job: Job) -> set[int]:
        """Retires a started job; returns the requests still waiting for its answer."""
        with self._cond:
            self._jobs.pop(job.key, None)
            for req_id in job.waiters:
                self._request_key.pop(req_id, None)
            return set(job.waiters)

    def depth(self) -> int:
        with self._cond:
            return sum(1 for j in self._jobs.values() if not j.started)

    def close(self) -> None:
        with self._cond:
            self._closed = True
            self._cond.notify_all()
