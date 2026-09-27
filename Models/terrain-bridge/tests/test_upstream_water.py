"""Model-supplied rivers: the bridge maps the upstream water-surface plane through the
height curve and keeps the ocean rule; a 3-plane payload parses."""
import dataclasses

import numpy as np
import pytest

from bridge import water as water_module
from bridge.config import BridgeConfig
from bridge.height_mapping import HeightCurve


def _cfg(**kw):
    return dataclasses.replace(BridgeConfig.from_env(seed=1), **kw)


def test_build_selects_upstream_water():
    assert isinstance(water_module.build(_cfg()), water_module.UpstreamWater)


def test_river_levels_follow_the_curve_and_sea_rule_still_applies():
    cfg = _cfg()
    w = water_module.UpstreamWater(cfg)
    curve = HeightCurve.from_config(cfg)
    elev = np.array([[200, 200, -50], [200, 200, 200]], dtype=np.int16)
    surface = np.full_like(elev, w.WATER_NONE)
    surface[0, 1] = 212  # 12 m of water over a 200 m bed
    heights, levels, report = w.planes((0, 0, 3, 2), elev, surface)
    assert levels[0, 1] == curve.to_block_height(np.array([212.0]))[0]
    assert levels[0, 1] > heights[0, 1]
    assert levels[0, 0] == -1 and levels[1, 1] == -1  # dry
    assert levels[0, 2] == cfg.sea_level  # ocean untouched
    assert report["wet_columns"] == 1


def test_surface_at_or_below_ground_stays_dry():
    w = water_module.UpstreamWater(_cfg())
    elev = np.array([[300]], dtype=np.int16)
    _, levels, _ = w.planes((0, 0, 1, 1), elev, np.array([[299]], dtype=np.int16))
    assert levels[0, 0] == -1


def test_three_plane_payload_parses(monkeypatch):
    from bridge import upstream_client as uc

    h = w = 2
    planes = [np.arange(4, dtype="<i2").reshape(2, 2) + 10 * k for k in range(3)]

    class R:
        status_code = 200
        headers = {"X-Height": "2", "X-Width": "2", "X-Dtype": "int16-le"}
        content = b"".join(p.tobytes() for p in planes)

    client = uc.UpstreamClient(_cfg())
    monkeypatch.setattr(client._session, "get", lambda *a, **k: R())
    elev, biome, surf, river = client.fetch_tile_with_water(0, 0, 2, 2)
    assert np.array_equal(elev, planes[0]) and np.array_equal(biome, planes[1]) and np.array_equal(surf, planes[2])
    assert river is None  # a three-plane upstream has no 3D river planes


def test_downscale_is_sent_and_namespaced(monkeypatch, tmp_path):
    from bridge import upstream_client as uc
    from bridge.cache import TileCache

    cfg = _cfg(scale=1, downscale=2)
    sent = {}

    class R:
        status_code = 200
        headers = {"X-Height": "1", "X-Width": "1", "X-Dtype": "int16-le"}
        content = np.zeros(3, dtype="<i2").tobytes()

    client = uc.UpstreamClient(cfg)
    monkeypatch.setattr(client._session, "get", lambda url, params, timeout: sent.update(params) or R())
    client.fetch_tile_with_water(0, 0, 1, 1)
    assert sent["downscale"] == 2 and sent["scale"] == 1
    base = dataclasses.replace(_cfg(), cache_dir=str(tmp_path))
    assert TileCache(dataclasses.replace(base, scale=1, downscale=2)).root != TileCache(dataclasses.replace(base, scale=1)).root


def test_downscale_requires_scale_one():
    with pytest.raises(ValueError):
        _cfg(scale=2, downscale=2)


def test_six_plane_payload_passes_river_planes_through(monkeypatch):
    from bridge import upstream_client as uc

    planes = [np.arange(4, dtype="<i2").reshape(2, 2) + 10 * k for k in range(6)]
    sent = {}

    class R:
        status_code = 200
        headers = {"X-Height": "2", "X-Width": "2", "X-Dtype": "int16-le"}
        content = b"".join(p.tobytes() for p in planes)

    client = uc.UpstreamClient(_cfg())

    def get(url, params=None, **k):
        sent.update(params or {})
        return R()

    monkeypatch.setattr(client._session, "get", get)
    *_, river = client.fetch_tile_with_water(0, 0, 2, 2)
    assert sent.get("river3d") == 1
    for want, have in zip(planes[3:], river):
        assert np.array_equal(want, have)


def test_water_under_an_overhang_survives_the_mapping():
    """A wet column under an overhang reports the bank's full height, above its own water;
    its tunnel floor marks it as water all the same."""
    from bridge.water import UpstreamWater

    w = UpstreamWater(_cfg())
    curve = w._curve
    s = w._sea_level
    ground = curve.to_elevation(np.array([[s + 10.5, s + 30.5]]))   # open channel, overhang column
    surface = curve.to_elevation(np.array([[s + 15.5, s + 15.5]]))
    floor = np.array([[-1, s + 9]], dtype=np.int16)
    river = (floor, np.array([[-1, s + 18]], dtype=np.int16), np.array([[2, 2]], dtype=np.int16))
    heights, water, _ = w.planes((0, 0, 1, 2), ground, surface.astype(np.int16), river)
    assert heights[0, 1] > water[0, 1] > 0      # the overhang keeps its height and its water
    assert water[0, 0] > heights[0, 0]


def test_lod_tiles_ask_for_proportionally_coarser_blocks_and_cache_apart(monkeypatch):
    from bridge import upstream_client as uc
    from bridge.tiling import TileId

    planes = [np.zeros((2, 2), dtype="<i2") for _ in range(6)]
    sent = {}

    class R:
        status_code = 200
        headers = {"X-Height": "2", "X-Width": "2", "X-Dtype": "int16-le"}
        content = b"".join(p.tobytes() for p in planes)

    client = uc.UpstreamClient(_cfg(scale=1, downscale=2))

    def get(url, params=None, **k):
        sent.update(params or {})
        return R()

    monkeypatch.setattr(client._session, "get", get)
    client.fetch_tile_with_water(0, 0, 2, 2, lod=8)
    assert sent["downscale"] == 16
    base = TileId(seed=1, tile_x=2, tile_z=3, scale=1)
    assert base.cache_key() == "s1_x2_z3_sc1"          # existing caches keep their keys
    assert TileId(seed=1, tile_x=2, tile_z=3, scale=1, lod=8).cache_key() == "s1_x2_z3_sc1_lod8"
