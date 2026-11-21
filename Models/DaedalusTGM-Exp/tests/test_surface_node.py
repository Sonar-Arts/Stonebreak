"""Surface-detail node (agent L): lidar label rules, the node's window independence, the tile provider's frames."""
from __future__ import annotations

from types import SimpleNamespace

import numpy as np
import torch

from terrain_slm.data import surface_labels as SL
from terrain_slm.models import surfacenet as SN
from terrain_slm.world import surface_node as SNode


def _labels(z: np.ndarray):
    valid = np.ones_like(z, bool)
    f = SL.pixel_fields(z, valid)
    bl = SL.boulders(f, valid)
    return f, bl, SL.cell_labels(f, valid, bl, SL.CELL15), SL.cell_labels(f, valid, bl, SL.CELL60)


def test_flat_ground_is_smooth_with_no_rock_or_boulders():
    z = np.full((240, 240), 100.0, np.float32)
    _, bl, l15, l60 = _labels(z)
    assert len(bl) == 0
    assert np.nanmax(l15["rock_exposure"]) == 0 and np.nanmax(l15["scree"]) == 0
    assert np.nanmin(l60["smooth"]) == 1.0


def test_cliff_is_rock_and_boulders_are_found_and_sized():
    z = np.full((240, 240), 100.0, np.float32)
    z[:, 120:] += np.clip((np.arange(120) - 0) * 2.0, 0, 60)[None, :]   # 63 deg wall, 30 m high, then a plateau
    rng = np.random.default_rng(0)
    yy, xx = np.mgrid[0:240, 0:240]
    centres = [(r, c) for r in range(20, 230, 25) for c in range(15, 100, 25)]
    for r, c in centres:                                               # 3 m wide, 1.5 m tall bumps on the flat
        z += 1.5 * np.exp(-((yy - r) ** 2 + (xx - c) ** 2) / (2 * 1.0 ** 2))
    f, bl, l15, l60 = _labels(z)
    wall = l60["rock_exposure"][:, 2]                                   # 60 m cells 120..180 hold the wall
    assert np.all(wall > 0.3)
    assert len(bl) >= 0.8 * len(centres)
    assert np.all((bl[:, 2] > 1.5) & (bl[:, 2] < 7.0))
    assert np.nanmean(l60["boulder_density"][:, :2]) > 0.05


def test_node_output_is_window_independent():
    torch.manual_seed(0)
    m = SN.SurfaceNet().eval()
    z = torch.randn(48, 48).cumsum(0).cumsum(1) * 3.0
    t, p = torch.full_like(z, 5.0), torch.full_like(z, 900.0)
    a60, a15 = SN.predict(m, z, t, p)
    o = 10
    b60, b15 = SN.predict(m, z[o:, o:], t[o:, o:], p[o:, o:])
    r = SN.RF + 1
    assert torch.allclose(a60[:, o + r : -r, o + r : -r], b60[:, r:-r, r:-r], atol=1e-5)
    assert torch.allclose(a15[:, 4 * (o + r) : -4 * r, 4 * (o + r) : -4 * r], b15[:, 4 * r : -4 * r, 4 * r : -4 * r], atol=1e-5)


def test_provider_frames_match_the_tile_at_every_scale():
    torch.manual_seed(0)
    gen = SimpleNamespace(surfacenet=SN.SurfaceNet().eval())
    prov = SNode.provider(gen)
    builder = SimpleNamespace(world=SimpleNamespace(sea_level=64))
    mb, n = 24, 64
    for d in (2, 1, 0.5):
        blocks = n + 2 * mb
        g_px = int(blocks * d) if d >= 1 else blocks        # ground: native 30 m px (blocks x d), 15 m blocks at 1:1
        ctx = {"d": d, "mb": mb, "ground": torch.rand(g_px, g_px) * 50 + 200,
               "climate": torch.rand(7, blocks, blocks), "height": torch.full((blocks, blocks), 100),
               "wet": torch.zeros(blocks, blocks, dtype=torch.bool), "water": torch.full((blocks, blocks), -1)}
        ctx["height"][mb, mb] = 10                            # one sea column: no data
        out = prov(builder, ctx)
        assert set(out) == set(SNode.CHANNEL_OF)
        for v in out.values():
            assert v.shape == (n, n)
            assert torch.isnan(v[0, 0]) and torch.isfinite(v[1:, 1:]).all()
