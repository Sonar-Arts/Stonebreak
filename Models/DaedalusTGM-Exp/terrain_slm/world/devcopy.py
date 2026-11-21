"""Host -> device copies and small constant tensors without a device sync (2026-11-19, pipeline speed).

`torch.from_numpy(a).to(device)` / `torch.tensor([...], device=device)` copy from pageable host memory, which torch
completes with a stream synchronisation: the host waits for every kernel queued before it. On a GPU shared with the
game's rendering each such wait costs a time slice, and a tile made a few dozen of them. Here the bytes go through
pinned memory with a non-blocking copy (ordered on the stream like any kernel; torch keeps the pinned block alive
until the copy ran), and small constant tables are made once per device. Values are bit-identical.
"""
from __future__ import annotations

import threading
import warnings

import numpy as np
import torch

_CONSTS: dict = {}
_LOCK = threading.Lock()


def to_device(x, device, dtype: torch.dtype | None = None) -> torch.Tensor:
    """`torch.from_numpy(x).to(device, dtype)` (x: numpy array or CPU tensor) without a device sync on CUDA."""
    if isinstance(x, np.ndarray):
        x = np.ascontiguousarray(x)
        if not x.flags.writeable:            # memoised read-only arrays (biomes._noise): pin_memory copies anyway
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                t = torch.from_numpy(x)
        else:
            t = torch.from_numpy(x)
    else:
        t = x
    dev = torch.device(device)
    if dev.type != "cuda":
        return t.to(device=dev, dtype=dtype) if dtype is not None else t.to(dev)
    return t.pin_memory().to(device=dev, dtype=dtype or t.dtype, non_blocking=True)


def const(values, device, dtype: torch.dtype) -> torch.Tensor:
    """`torch.tensor(values, device=device, dtype=dtype)`, made once per (values, device, dtype) and shared: callers
    must not write into it."""
    key = (repr(values), str(torch.device(device)), dtype)
    t = _CONSTS.get(key)
    if t is None:
        with _LOCK:
            t = _CONSTS.get(key)
            if t is None:
                t = _CONSTS[key] = to_device(torch.tensor(values, dtype=dtype), device)
    return t
