"""Sync-free host -> device helpers (world/devcopy.py) and the host-int hashes (2026-11-19) change no value."""
from __future__ import annotations

import math

import numpy as np
import pytest
import torch

from terrain_slm.synth import noise as S
from terrain_slm.world.devcopy import const, to_device

DEVICES = ["cpu"] + (["cuda"] if torch.cuda.is_available() else [])


def _white_noise_ref(i0, j0, h, w, seed, stream, device):
    """synth.noise.white_noise as it was (base hash through a device tensor)."""
    ii = torch.arange(i0, i0 + h, device=device, dtype=torch.int64).view(-1, 1)
    jj = torch.arange(j0, j0 + w, device=device, dtype=torch.int64).view(1, -1)
    base = S._hash32(torch.tensor((seed * 0x9E3779B1 + stream * 0x85EBCA77) & S._MASK32, device=device))
    key = S._hash32(S._hash32(ii ^ base) ^ jj)
    acc = torch.zeros((h, w), device=device, dtype=torch.float32)
    for k in range(4):
        acc = acc + S._hash32(key ^ (0x27D4EB2F * (k + 1) & S._MASK32)).to(torch.float32) * (1.0 / 4294967296.0)
    return (acc - 2.0) * math.sqrt(3.0)


@pytest.mark.parametrize("device", DEVICES)
def test_white_noise_unchanged(device):
    for seed, stream, i0, j0 in ((0, 0, 0, 0), (12345, 8191, -300, 77), (2**40 + 5, 30011, 10**6, -10**6)):
        a = S.white_noise(i0, j0, 33, 41, seed, stream, device)
        assert torch.equal(a, _white_noise_ref(i0, j0, 33, 41, seed, stream, device))


def test_hash32_int_matches_tensor_hash():
    rng = np.random.default_rng(0)
    for v in [0, 1, 0xFFFFFFFF] + [int(x) for x in rng.integers(0, 2**32, 200)]:
        assert S.hash32_int(v) == int(S._hash32(torch.tensor(v)))


@pytest.mark.parametrize("device", DEVICES)
def test_to_device_and_const(device):
    a = np.random.default_rng(1).standard_normal((17, 19)).astype(np.float32)
    ro = a.copy()
    ro.setflags(write=False)
    for x in (a, ro, torch.from_numpy(a.copy())):
        t = to_device(x, device)
        assert t.device.type == torch.device(device).type and torch.equal(t.cpu(), torch.from_numpy(a))
    assert to_device(a, device, torch.float64).dtype == torch.float64
    c = const([3, 1, 2], device, torch.int16)
    assert c.dtype == torch.int16 and c.tolist() == [3, 1, 2] and const([3, 1, 2], device, torch.int16) is c
