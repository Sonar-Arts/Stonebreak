"""The Shade is an overlay (biomes.apply_shade) on dry temperate woodland, never a slice of the rule classifier.

The game maps SHADE_ID to Stonebreak's SHADE (DiffusionBiomeMapper); Standard draws it from the dry half of
temperate grassland (BiomeSelector.isShade)."""
import torch
import torch.nn.functional as F

from terrain_slm import biomes as B

SNOWY = (3, 16, 116, 32, 33, 48)


def _sweep(t_lo=-12.0, t_hi=32.0, n=240, p_lo=200.0, p_hi=3000.0):
    """Flat lowland: temperature along columns, precipitation p_lo..p_hi mm along rows."""
    temp = torch.linspace(t_lo, t_hi, n).view(1, -1).expand(n, n)
    precip = torch.linspace(p_lo, p_hi, n).view(-1, 1).expand(n, n)
    climate = torch.stack([temp, torch.full((n, n), 700.0), precip, torch.full((n, n), 30.0)]).float()
    elev = torch.full((n, n), 60.0)
    rule = B._classify_biome(elev, climate, 0, 0, elev_padded=F.pad(elev[None, None], (1, 1, 1, 1), mode="replicate")[0, 0],
                             pixel_size_m=60.0)
    swamped = B.apply_swamps(rule, B.smooth_mask(B.swamp_candidates(elev, elev, climate, 0, 0), 7))
    shaded = B.apply_shade(swamped, B.smooth_mask(B.shade_candidates(elev, climate, 0, 0), 7))
    return temp, rule, swamped, shaded


def test_rule_classifier_never_emits_shade():
    _, rule, _, _ = _sweep()
    assert not bool((rule == B.SHADE_ID).any())


def test_shade_is_temperate_and_clear_of_the_snow_line():
    temp, _, _, shaded = _sweep()
    sh = shaded == B.SHADE_ID
    assert bool(sh.any())
    assert float(temp[sh].min()) >= B.SHADE_MIN_TEMP_C - 1.0   # classifier temperature noise is +-0.6 C
    assert float(temp[sh].max()) < B.SHADE_MAX_TEMP_C + 1.0
    snowy = torch.isin(shaded, torch.tensor(SNOWY, dtype=shaded.dtype))
    near_snow = F.max_pool2d(snowy.float()[None, None], 33, stride=1, padding=16)[0, 0] > 0
    assert not bool((sh & near_snow).any())


def test_shade_never_replaces_a_swamp():
    _, _, swamped, shaded = _sweep()
    assert bool((swamped == B.SWAMP_ID).any())
    assert torch.equal(shaded == B.SWAMP_ID, swamped == B.SWAMP_ID)


def test_overlay_only_replaces_host_biomes():
    biome = torch.tensor([[8, 108, 23, 15, 1, 5, 6, 205]], dtype=torch.int16)
    out = B.apply_shade(biome, torch.ones_like(biome, dtype=torch.bool))
    assert out.tolist() == [[8, 206, 23, 15, 206, 5, 6, 205]]


def test_shade_is_patches_of_its_eligible_ground():
    """Inside the dry temperate band the noise keeps open ground between the woods: neither all nor none."""
    _, _, _, shaded = _sweep(12.5, 19.5, n=400, p_lo=250.0, p_hi=700.0)
    share = float((shaded == B.SHADE_ID).float().mean())
    assert 0.2 < share < 0.8, share


def test_candidates_are_request_independent():
    n = 120
    climate = torch.stack([torch.full((n, n), 16.0), torch.full((n, n), 700.0), torch.full((n, n), 450.0),
                           torch.full((n, n), 30.0)])
    elev = torch.full((n, n), 80.0)
    whole = B.shade_candidates(elev, climate, 100, 200)
    part = B.shade_candidates(elev[20:60, 30:90], climate[:, 20:60, 30:90], 120, 230)
    assert torch.equal(whole[20:60, 30:90], part)
