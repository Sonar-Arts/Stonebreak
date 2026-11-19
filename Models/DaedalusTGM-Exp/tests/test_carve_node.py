"""Carve node v1: codes pack/unpack, flat ground and sea get no ravines, a canyon gets density along its axis."""
import math

import torch

from terrain_slm.world import carve_node as CN

DEV = "cuda:0" if torch.cuda.is_available() else "cpu"


def test_pack_roundtrip():
    d, s, k, p = (torch.tensor([v]) for v in (13, 3, 9, 27))
    u = CN.unpack(CN.pack(d, s, k, p))
    assert [int(u[n]) for n in ("density", "style", "strike", "depth")] == [13, 3, 9, 27]


def test_flat_and_sea_get_nothing_and_a_canyon_gets_ravines_along_it():
    n, m = 96, CN.MARGIN_SAMPLES
    yy, xx = torch.meshgrid(torch.arange(n + 2 * m, device=DEV), torch.arange(n + 2 * m, device=DEV), indexing="ij")
    plateau = torch.full((n + 2 * m, n + 2 * m), 800.0, device=DEV)
    canyon = plateau - 300.0 * torch.exp(-((xx - (n + 2 * m) / 2) / 3.0) ** 2)   # N-S canyon, ~360 m wide
    arid = torch.full_like(plateau, 0.05)
    river = torch.zeros_like(plateau)
    flat = CN.unpack(CN.carve_codes(plateau, arid, river, None))
    assert int(flat["density"].max()) == 0
    sea = CN.unpack(CN.carve_codes(plateau * 0 - 50.0, arid, river, None))
    assert int(sea["density"].max()) == 0
    c = CN.unpack(CN.carve_codes(canyon, arid, river, None))
    centre = n // 2
    assert int(c["density"][:, centre].min()) > 5                       # along the canyon
    assert int(c["density"][:, 5].max()) == 0                           # away from it
    # A canyon along i (world X): theta is measured from +j (world Z) toward +i, so its axis is pi/2 -> code 8.
    assert int(torch.mode(c["strike"][:, centre]).values) == 8


def _coast(rise_per_sample: float):
    """Sea (-40 m) west of column c0, land rising `rise_per_sample` metres per 60 m sample east of it."""
    n, m = 64, CN.MARGIN_SAMPLES
    size = n + 2 * m
    xx = torch.arange(size, device=DEV, dtype=torch.float32).view(1, -1).expand(size, size)
    c0 = size // 2
    h = torch.where(xx < c0, torch.full_like(xx, -40.0), (xx - c0 + 1) * rise_per_sample)
    return h, c0 - m


def test_steep_coast_gets_sea_stacks_and_a_beach_does_not():
    arid, river = None, None
    for rise, expect in ((80.0, True), (4.0, False)):          # ~53 deg cliff vs a 4 deg beach
        h, c0 = _coast(rise)
        arid = torch.full_like(h, 0.5)
        river = torch.zeros_like(h)
        u = CN.unpack(CN.carve_codes(h, arid, river, None))
        stacks = u["style"] == CN.CARVE_STYLES.index("sea_stacks")
        if expect:
            assert bool(stacks[:, c0 : c0 + CN.SEA_STACK_REACH_SAMPLES].all())       # the land band behind the shore
            assert not bool(stacks[:, c0 + CN.SEA_STACK_REACH_SAMPLES + 2 :].any())  # not far inland
            assert not bool(stacks[:, : c0].any())                                    # nor on the sea
            assert int(u["density"][:, c0].min()) >= 1
        else:
            assert not bool(stacks.any())


def test_humid_rugged_karst_gets_towers_and_dry_or_flat_karst_keeps_sinkholes():
    n, m = 64, CN.MARGIN_SAMPLES
    size = n + 2 * m
    yy, xx = torch.meshgrid(torch.arange(size, device=DEV), torch.arange(size, device=DEV), indexing="ij")
    # Rugged egg-box relief, ~250 m amplitude at ~500 m wavelength, all above the sea.
    rugged = 400.0 + 250.0 * torch.sin(xx * 0.75) * torch.sin(yy * 0.75)
    flat = torch.full_like(rugged, 400.0)
    river = torch.zeros_like(rugged)
    gates = {"drowned_archipelago": torch.ones_like(rugged)}
    karst, towers = CN.CARVE_STYLES.index("karst"), CN.CARVE_STYLES.index("towers")
    wet = CN.unpack(CN.carve_codes(rugged, torch.full_like(rugged, 1.5), river, gates))["style"]
    assert bool((wet == towers).all())
    dry = CN.unpack(CN.carve_codes(rugged, torch.full_like(rugged, 0.4), river, gates))["style"]
    assert bool((dry == karst).all())
    smooth = CN.unpack(CN.carve_codes(flat, torch.full_like(flat, 1.5), river, gates))["style"]
    assert bool((smooth == karst).all())
    no_gate = CN.unpack(CN.carve_codes(rugged, torch.full_like(rugged, 1.5), river, None))["style"]
    assert not bool((no_gate == towers).any())


def test_style_ids_are_stable():
    assert CN.CARVE_STYLES[:6] == ("ravine", "slot_canyon", "gorge", "rift", "karst", "none")
    assert CN.CARVE_STYLES.index("sea_stacks") == 6 and CN.CARVE_STYLES.index("towers") == 7
