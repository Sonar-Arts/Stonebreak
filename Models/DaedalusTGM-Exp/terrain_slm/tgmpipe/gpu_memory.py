"""The service's GPU memory for the game's F3 debug screen (STATUS reply, key "vram").

Torch's allocator only knows its own tensors; what the process really holds on the card also includes
the CUDA context, the loaded native kernels (kernels/native.py modules) and the allocator's cache. NVML
reports that per process -- read here through ctypes on libnvidia-ml (no pynvml dependency), matching
the CUDA device to the NVML one by PCI bus id (the two can number GPUs differently). Any part that is
unavailable (no NVML, CPU service) is simply left out; the game shows what it gets.
"""
from __future__ import annotations

import ctypes
import os
import threading

_LOCK = threading.Lock()
_NVML = None          # loaded + initialised library, False when unavailable
_HANDLES: dict = {}   # CUDA device index -> NVML handle


class _ProcessInfo(ctypes.Structure):   # nvmlProcessInfo_t (v2 / v3 layout)
    _fields_ = [("pid", ctypes.c_uint), ("usedGpuMemory", ctypes.c_ulonglong),
                ("gpuInstanceId", ctypes.c_uint), ("computeInstanceId", ctypes.c_uint)]


class _Memory(ctypes.Structure):        # nvmlMemory_t
    _fields_ = [("total", ctypes.c_ulonglong), ("free", ctypes.c_ulonglong), ("used", ctypes.c_ulonglong)]


def _nvml():
    global _NVML
    if _NVML is None:
        try:
            lib = ctypes.CDLL("libnvidia-ml.so.1")
            _NVML = lib if lib.nvmlInit_v2() == 0 else False
        except OSError:
            _NVML = False
    return _NVML or None


def _handle(torch, dev):
    if dev.index in _HANDLES:
        return _HANDLES[dev.index]
    lib = _nvml()
    if lib is None:
        return None
    p = torch.cuda.get_device_properties(dev)
    bus = f"{p.pci_domain_id:08x}:{p.pci_bus_id:02x}:{p.pci_device_id:02x}.0".encode()
    h = ctypes.c_void_p()
    _HANDLES[dev.index] = h if lib.nvmlDeviceGetHandleByPciBusId_v2(bus, ctypes.byref(h)) == 0 else None
    return _HANDLES[dev.index]


def _process_bytes(lib, h) -> int | None:
    pid = os.getpid()
    for fn in ("nvmlDeviceGetComputeRunningProcesses_v3", "nvmlDeviceGetComputeRunningProcesses_v2"):
        f = getattr(lib, fn, None)
        if f is None:
            continue
        n = ctypes.c_uint(64)
        buf = (_ProcessInfo * 64)()
        if f(h, ctypes.byref(n), buf) != 0:
            continue
        for i in range(min(n.value, 64)):
            if buf[i].pid == pid:
                return int(buf[i].usedGpuMemory)
        return None
    return None


def snapshot(device) -> dict:
    """{"device", and on CUDA: "gpu", "process_bytes" (NVML, everything this process holds on the card),
    "allocated_bytes" / "reserved_bytes" / "peak_bytes" (torch's allocator), "device_used_bytes" /
    "device_total_bytes" (the whole card, every process)}."""
    import torch
    dev = torch.device(device)
    out = {"device": str(dev)}
    if dev.type != "cuda":
        return out
    if dev.index is None:
        dev = torch.device("cuda", torch.cuda.current_device())
    out["gpu"] = torch.cuda.get_device_name(dev)
    out["allocated_bytes"] = int(torch.cuda.memory_allocated(dev))
    out["reserved_bytes"] = int(torch.cuda.memory_reserved(dev))
    out["peak_bytes"] = int(torch.cuda.max_memory_allocated(dev))
    with _LOCK:
        lib, h = _nvml(), _handle(torch, dev)
        if lib is not None and h is not None:
            used = _process_bytes(lib, h)
            if used is not None:
                out["process_bytes"] = used
            mem = _Memory()
            if lib.nvmlDeviceGetMemoryInfo(h, ctypes.byref(mem)) == 0:
                out["device_used_bytes"], out["device_total_bytes"] = int(mem.used), int(mem.total)
    return out
