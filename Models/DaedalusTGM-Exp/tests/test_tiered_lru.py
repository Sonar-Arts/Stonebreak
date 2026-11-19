"""The generator's two-tier LRU (GPU tier + host spill, VRAM audit 2026-11-18): values come back
bit-identical and on their original device, capacity and membership span both tiers."""
import pytest
import torch

from terrain_slm.world.generator import _LRU

DEVICES = ["cpu"] + (["cuda"] if torch.cuda.is_available() else [])


@pytest.mark.parametrize("device", DEVICES)
def test_spilled_entries_return_identical_on_their_device(device):
    lru = _LRU(2, 3)
    vals = {k: torch.randn(4, 5, device=device) for k in range(5)}
    nested = {"a": (torch.arange(3, device=device), 7), "b": [torch.ones(2, device=device)]}
    for k, v in vals.items():
        lru.get_or(k, lambda v=v: v.clone())
    lru.get_or("n", lambda: nested)
    assert len(lru) == 5 and lru.cap == 5          # 2 on the device, 3 spilled
    assert 0 not in lru and 1 in lru and 2 in lru       # 6 inserts, cap 5: only the oldest dropped
    assert all(t.device.type == "cpu" for t in [lru.host[k] for k in (2, 3)])
    for k in (2, 3, 4):
        got = lru.get_or(k, lambda: pytest.fail("recomputed a cached entry"))
        assert got.device == vals[k].device and torch.equal(got, vals[k])
    back = lru.get_or("n", lambda: pytest.fail("recomputed"))
    assert torch.equal(back["a"][0], nested["a"][0]) and back["a"][1] == 7
    assert back["a"][0].device == nested["a"][0].device


def test_gpu_only_lru_drops_like_before():
    lru = _LRU(2)
    for k in range(4):
        lru.get_or(k, lambda k=k: torch.tensor(k))
    assert len(lru) == 2 and 2 in lru and 3 in lru and 0 not in lru
    calls = []
    lru.get_or(0, lambda: calls.append(1) or torch.tensor(0))
    assert calls == [1]


def test_host_values_without_tensors():
    lru = _LRU(1, 1)
    lru.get_or("a", lambda: ("x", 1))
    lru.get_or("b", lambda: ("y", 2))
    assert lru.get_or("a", lambda: pytest.fail("recomputed")) == ("x", 1)
