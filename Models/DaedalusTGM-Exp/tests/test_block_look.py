"""FX4 block look (2026-11-19): despeckle, alpine climate / treeline zones, the phase-free SR gain and the hill band.
Pure-tensor tests (CPU); the real-tile checks (request independence, v1 worlds unchanged) are in the FX4 report."""
import numpy as np
import pytest
import torch

from terrain_slm.world import tiles as T


def _flat(n=9, h=70):
    return torch.full((n, n), h, dtype=torch.long)


def test_despeckle_removes_single_bump_and_dent():
    h = _flat()
    h[4, 4] = 71
    h[2, 6] = 69
    out = T.despeckle(h, torch.ones_like(h, dtype=torch.bool))
    assert int(out[4, 4]) == 70 and int(out[2, 6]) == 70
    assert out.dtype == h.dtype


def test_despeckle_removes_domino_keeps_larger_patches():
    h = _flat(11)
    h[3, 3] = h[3, 4] = 71                    # domino: removed
    h[7:10, 7:10] = 71                        # 3x3 patch: kept
    out = T.despeckle(h, torch.ones_like(h, dtype=torch.bool))
    assert int(out[3, 3]) == 70 and int(out[3, 4]) == 70
    assert bool((out[7:10, 7:10] == 71).all())


def test_despeckle_single_bump_on_slope():
    # an islet on a gentle slope: 4 face neighbours equal, diagonals one level apart
    h = torch.tensor([[69, 70, 71], [70, 71, 70], [69, 70, 71]], dtype=torch.long)
    big = torch.nn.functional.pad(h[None, None].float(), (2, 2, 2, 2), mode="replicate")[0, 0].long()
    out = T.despeckle(big, torch.ones_like(big, dtype=torch.bool))
    assert int(out[3, 3]) == 70


def test_despeckle_needles_and_holes():
    h = _flat()
    h[4, 4] = 90
    h[2, 2] = 60
    out = T.despeckle(h, torch.ones_like(h, dtype=torch.bool))
    # clamped to max + 1 / min - 1, then (flat ground) the single-bump rule levels it
    assert int(out[4, 4]) == 70 and int(out[2, 2]) == 70
    # on a rock face (uneven neighbours) the needle keeps one block over its highest neighbour
    r = torch.arange(9)[:, None].expand(9, 9) * 2 + 100
    r = r.clone()
    r[4, 4] = 140
    out = T.despeckle(r, torch.ones_like(r, dtype=torch.bool))
    assert int(out[4, 4]) == int(r[5, 4]) + 1


def test_despeckle_never_touches_water_or_its_neighbours():
    h = _flat()
    h[4, 4] = 71
    dry = torch.ones_like(h, dtype=torch.bool)
    dry[5, 5] = False                         # a wet diagonal neighbour
    out = T.despeckle(h, dry)
    assert int(out[4, 4]) == 71
    h2 = _flat()
    h2[0, 4] = 71                             # frame edge: no neighbours there
    assert int(T.despeckle(h2, torch.ones_like(h2, dtype=torch.bool))[0, 4]) == 71


def test_despeckle_is_local_request_independent():
    g = torch.Generator().manual_seed(1)
    h = 70 + torch.randint(0, 3, (64, 64), generator=g)
    dry = torch.rand(64, 64, generator=g) > 0.05
    full = T.despeckle(h, dry)
    sub = T.despeckle(h[10:50, 12:52], dry[10:50, 12:52])
    # reads a 5x5 per column (+ the outer ring never changes): interior 3 columns in agree
    assert torch.equal(full[13:47, 15:49], sub[3:-3, 3:-3])


def test_alpine_elevation_lowland_unchanged_highland_colder():
    reg = torch.tensor([0.0, 300.0, 700.0, 2000.0, 3000.0])
    ground = reg + torch.tensor([50.0, 80.0, 60.0, 500.0, -200.0])
    e = T.alpine_elevation(reg, ground)
    assert torch.equal(e[:3], reg[:3])        # regional below ALPINE_LOCAL_M: per-column relief ignored, no gain
    # highland: the column's own ground, + gain above the base
    want = lambda x: x + (T.ALPINE_LAPSE_GAIN - 1) * max(x - T.ALPINE_BASE_M, 0.0)
    assert e[3].item() == pytest.approx(want(2500.0))
    assert e[4].item() == pytest.approx(want(2800.0))


def test_alpine_zones_treeline():
    n = 16
    biome = torch.full((n, n), 115, dtype=torch.int16)          # taiga_sparse
    biome[:, :4] = 5                                             # desert: never touched
    biome[:, 4:6] = 116                                          # snowy taiga sparse
    temp = torch.full((n, n), -3.0)
    tseason = torch.full((n, n), 500.0)
    high = torch.full((n, n), 2500.0)
    out = T.alpine_zones(biome, high, temp, tseason, 0, 0, 1.0)
    assert bool((out[:, :4] == 5).all())
    assert bool((out[:, 4:6] == T.ALPINE_SNOW_ID).all())
    assert bool((out[:, 6:] == T.ALPINE_ROCK_ID).all())
    # warm or low: unchanged
    assert torch.equal(T.alpine_zones(biome, high, torch.full((n, n), 15.0), tseason, 0, 0, 1.0), biome)
    assert torch.equal(T.alpine_zones(biome, torch.full((n, n), 300.0), temp, tseason, 0, 0, 1.0), biome)


def test_sr_gain_look_exact_means_and_no_phase():
    from terrain_slm.models import sr as SRM
    from terrain_slm.world import generator2 as G2
    g = torch.Generator().manual_seed(0)
    coarse = torch.cumsum(torch.randn(1, 1, 40, 40, generator=g), -1) * 5.0
    fine = SRM.assemble(coarse, torch.zeros(1, 1, 80, 80))
    out = G2._sr_gain_look(fine, coarse, 1.18)
    assert torch.allclose(SRM.block_mean(out), coarse, atol=1e-3)
    d = (out[..., 1:] - out[..., :-1]).abs()[..., 8:-8, 8:-8]
    even, odd = d[..., 0::2].mean().item(), d[..., 1::2].mean().item()
    d0 = (fine[..., 1:] - fine[..., :-1]).abs()[..., 8:-8, 8:-8]
    ratio0 = d0[..., 1::2].mean().item() / d0[..., 0::2].mean().item()
    assert abs(odd / even - 1) <= abs(ratio0 - 1) + 0.02     # the gain adds no 2x2 phase of its own


def test_hill_band_flat_and_coast_unchanged():
    from terrain_slm.world import generator2 as G2
    flat = torch.full((128, 128), 200.0)
    assert torch.allclose(G2._hill_band(flat, 0.8), flat, atol=1e-3)
    sea = torch.full((128, 128), -50.0) + torch.randn(128, 128)
    assert torch.allclose(G2._hill_band(sea, 0.8), sea, atol=1e-4)       # regional < LOOK_HILL_COAST_M: no change
    y = torch.arange(128.0)
    hills = 200.0 + 40.0 * torch.sin(y / 128 * 2 * np.pi * 4)[None, :].expand(128, 128)
    grown = G2._hill_band(hills, 0.8)
    assert (grown - 200).abs().max() > (hills - 200).abs().max() * 1.3   # 2 km hills grow
