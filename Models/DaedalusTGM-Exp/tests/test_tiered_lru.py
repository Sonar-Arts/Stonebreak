"""The generator's two-tier LRU (GPU tier + host spill, VRAM audit 2026-11-18): values come back
bit-identical and on their original device, capacity and membership span both tiers."""
import os

import pytest
import torch

from terrain_slm.world.generator import _LRU, _Spilled

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
    host = lambda t: t.slot if isinstance(t, _Spilled) else t      # CUDA entries spill into pinned slots
    assert all(host(t).device.type == "cpu" for t in [lru.host[k] for k in (2, 3)])
    assert all(host(t).is_pinned() for t in [lru.host[k] for k in (2, 3)] if isinstance(t, _Spilled))
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


@pytest.mark.skipif(not torch.cuda.is_available(), reason="no CUDA")
def test_async_spill_round_trips_under_churn():
    """Many spills and reloads through recycled pinned slots, values checked after every reload (stream order is all
    that keeps a reused slot from being overwritten before its reload ran)."""
    lru = _LRU(3, 6)
    ref = {}
    g = torch.Generator().manual_seed(0)
    for step in range(400):
        k = int(torch.randint(0, 12, (1,), generator=g))
        if k not in ref:
            ref[k] = torch.randn(7, 33, generator=g).cuda()
        got = lru.get_or(k, lambda k=k: ref[k].clone())
        assert torch.equal(got, ref[k]), (step, k)
        if k not in lru:
            ref.pop(k, None)


def test_pinned_budget_default_scales_with_ram(monkeypatch):
    """review-python MEDIUM-2: 1/64 of physical RAM, 128..1024 MiB (was a fixed 1 GiB per GPU)."""
    import os
    from terrain_slm.world import generator as G
    for ram_gb, mb in ((4, 128), (16, 256), (32, 512), (64, 1024), (256, 1024)):
        pages = ram_gb * 2**30 // 4096
        monkeypatch.setattr(os, "sysconf", lambda k, p=pages: 4096 if k == "SC_PAGE_SIZE" else p)
        assert G._default_pinned_mb() == mb, ram_gb


@pytest.mark.skipif(not torch.cuda.is_available(), reason="needs CUDA")
def test_idle_release_frees_only_fully_free_slabs():
    """The service's idle release drops spill slabs no entry uses; a slab holding a live entry stays, and the entry
    still comes back bit-identical."""
    from terrain_slm.world import generator as G
    dev = torch.device(os.environ.get("TGM_TEST_DEVICE", "cuda:0"))
    pool = G._pinned(dev)
    before = pool.bytes
    nbytes = 4 * 1237                                # an odd size: its own pool, no other test's slots
    t = torch.arange(1237, dtype=torch.float32, device=dev)
    held = G._Spilled(t)                             # takes a slot of a new slab
    spare = [G._Spilled(t * 2) for _ in range(pool.SLAB)]   # fills that slab and starts a second one
    for s in spare:
        s.release()                                  # the second slab is now entirely free; the first is not
    assert pool.bytes == before + 2 * pool.SLAB * nbytes
    dropped = G.release_pinned()
    assert dropped >= pool.SLAB * nbytes             # (other pools' free slabs may go too)
    # This pool lost at least our free slab and at most everything released (free slabs that earlier tests' generators
    # left in this or another device's pool go too: release_pinned is global, so `dropped` is not this pool's alone).
    own = before + 2 * pool.SLAB * nbytes - pool.bytes
    assert pool.SLAB * nbytes <= own <= dropped
    assert len(pool._free[nbytes]) == pool.SLAB - 1  # the held slab's other slots stay available
    assert torch.equal(held.back(dev), t)
    G.release_pinned()
    assert nbytes not in pool._free or not pool._free[nbytes]
