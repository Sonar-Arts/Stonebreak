"""Swamps are an overlay (biomes.apply_swamps), never a climate slice of the rule classifier.

In-game review (2026-10-18): swamps grew in cold lowland beside snow biomes, and formed a band around
every jungle (the rule's 20-26 C slice of dense forest)."""
import torch
import torch.nn.functional as F

from terrain_slm import biomes as B

SNOWY = (3, 16, 116, 32, 33, 48)


def _sweep(t_lo=-12.0, t_hi=32.0, n=240, elev_m=60.0):
    """Flat lowland: temperature along columns, precipitation 200..3000 mm along rows."""
    temp = torch.linspace(t_lo, t_hi, n).view(1, -1).expand(n, n)
    precip = torch.linspace(200.0, 3000.0, n).view(-1, 1).expand(n, n)
    climate = torch.stack([temp, torch.full((n, n), 700.0), precip, torch.full((n, n), 30.0)]).float()
    elev = torch.full((n, n), elev_m)
    rule = B._classify_biome(elev, climate, 0, 0, elev_padded=F.pad(elev[None, None], (1, 1, 1, 1), mode="replicate")[0, 0],
                             pixel_size_m=60.0)
    swamped = B.apply_swamps(rule, B.smooth_mask(B.swamp_candidates(elev, elev, climate, 0, 0), 7))
    return temp, rule, swamped


def test_rule_classifier_never_emits_swamp():
    _, rule, _ = _sweep()
    assert not bool((rule == B.SWAMP_ID).any())


def test_swamps_stay_clear_of_the_snow_line():
    temp, _, swamped = _sweep()
    sw = swamped == B.SWAMP_ID
    assert bool(sw.any())
    assert float(temp[sw].min()) >= B.SWAMP_MIN_TEMP_C - 1.0   # classifier temperature noise is +-0.6 C
    snowy = torch.isin(swamped, torch.tensor(SNOWY, dtype=swamped.dtype))
    near_snow = F.max_pool2d(snowy.float()[None, None], 33, stride=1, padding=16)[0, 0] > 0
    assert not bool((sw & near_snow).any())


def test_swamps_are_not_a_band_between_forest_and_jungle():
    temp, _, swamped = _sweep(12.0, 32.0)
    sw = swamped == B.SWAMP_ID
    temperate = sw[:, temp[0] < 20.0].float().mean()
    warm = sw[:, (temp[0] >= 20.0) & (temp[0] < 26.0)].float().mean()
    hot = sw[:, temp[0] >= 26.0].float().mean()
    assert min(temperate, warm, hot) > 0.1, (temperate, warm, hot)


def test_overlay_only_replaces_host_biomes():
    biome = torch.tensor([[8, 108, 23, 15, 16, 3, 5, 1]], dtype=torch.int16)
    out = B.apply_swamps(biome, torch.ones_like(biome, dtype=torch.bool))
    assert out.tolist() == [[6, 6, 6, 15, 16, 3, 5, 1]]


def test_swamps_need_low_ground_regionally_and_per_column():
    n = 64
    climate = torch.stack([torch.full((n, n), 22.0), torch.full((n, n), 700.0), torch.full((n, n), 3000.0),
                           torch.full((n, n), 30.0)])
    low = B.swamp_candidates(torch.full((n, n), 60.0), torch.full((n, n), 60.0), climate, 0, 0)
    high = B.swamp_candidates(torch.full((n, n), 400.0), torch.full((n, n), 400.0), climate, 0, 0)
    hill = B.swamp_candidates(torch.full((n, n), 400.0), torch.full((n, n), 60.0), climate, 0, 0)
    assert bool(low.any()) and not bool(high.any()) and not bool(hill.any())


def test_candidates_are_request_independent():
    n = 96
    climate = torch.stack([torch.full((n, n), 22.0), torch.full((n, n), 700.0),
                           torch.linspace(800.0, 3000.0, n).view(1, -1).expand(n, n), torch.full((n, n), 30.0)])
    elev = torch.full((n, n), 50.0)
    whole = B.swamp_candidates(elev, elev, climate, 100, 200)
    part = B.swamp_candidates(elev[20:60, 30:90], elev[20:60, 30:90], climate[:, 20:60, 30:90], 120, 230)
    assert torch.equal(whole[20:60, 30:90], part)
