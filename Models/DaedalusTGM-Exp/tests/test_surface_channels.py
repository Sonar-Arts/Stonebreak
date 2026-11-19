"""TGMPipe v3 surface channels (world/surface_channels.py): encoding, wire and disk-cache round trips."""
import io

import numpy as np
import pytest

from terrain_slm.tgmpipe import protocol as W
from terrain_slm.tgmpipe.scheduler import TileKey
from terrain_slm.tgmpipe.tile_cache import TileCache
from terrain_slm.world import surface_channels as SC


def _planes(rows=4, cols=5):
    return (np.arange(W.PLANES * rows * cols, dtype=np.int16) - 30).reshape(W.PLANES, rows, cols)


def test_encode_maps_nan_to_no_data_and_clips():
    v = np.array([[0.0, 0.5, 1.0, np.nan, 2.0, -1.0]], np.float32)
    e = SC.encode("canopy_cover", v)
    assert e.tolist() == [[0, 127, 254, 255, 254, 0]]
    h = SC.encode("canopy_height", np.array([12.3, 70.0], np.float32))
    assert h.tolist() == [49, 254]   # 0.25 m steps, clipped at 63.5 m
    d = SC.decode("canopy_cover", e)
    assert np.isnan(d[0, 3]) and d[0, 2] == pytest.approx(1.0)


def test_registry_slots_unique_and_bounded():
    slots = [c.slot for c in SC.CHANNELS]
    assert len(slots) == len(set(slots)) and max(slots) < SC.MAX_SLOTS
    assert SC.BY_NAME["canopy_cover"].slot == 0 and SC.BY_NAME["boulder_size"].slot == 11


def test_wire_round_trip_with_and_without_channels():
    planes = _planes()
    surf = {"clearing": np.full((4, 5), 7, np.uint8), "canopy_cover": np.arange(20, dtype=np.uint8).reshape(4, 5)}
    kind, body = W.read_frame(io.BytesIO(W.tile_data(3, False, -8, 16, SC.TilePlanes(planes, surf))))
    rid, cached, i1, j1, got = W.parse_tile_data(body)
    assert (rid, cached, i1, j1) == (3, False, -8, 16)
    assert np.array_equal(got, planes)
    assert list(got.surface) == ["clearing", "canopy_cover"]
    assert np.array_equal(got.surface["canopy_cover"], surf["canopy_cover"])
    # a plain array carries an empty surface block (count 0)
    kind, body = W.read_frame(io.BytesIO(W.tile_data(4, True, 0, 0, planes)))
    assert W.parse_tile_data(body)[4].surface == {}
    assert len(body) == 17 + 2 * planes.size + 1


def test_unknown_channel_names_survive_the_wire():
    surf = {"some_future_channel": np.ones((4, 5), np.uint8)}
    _, body = W.read_frame(io.BytesIO(W.tile_data(1, False, 0, 0, SC.TilePlanes(_planes(), surf))))
    assert np.array_equal(W.parse_tile_data(body)[4].surface["some_future_channel"], surf["some_future_channel"])


def test_cache_round_trip_keeps_surface(tmp_path):
    cache = TileCache(tmp_path, "ns", 1 << 30)
    key = TileKey(1, 2, 3, 1)
    surf = {"ground_grass": np.full((4, 5), 200, np.uint8)}
    cache.put(key, SC.TilePlanes(_planes(), surf))
    got = cache.get(key)
    assert np.array_equal(got, _planes()) and np.array_equal(got.surface["ground_grass"], surf["ground_grass"])
    cache.put(TileKey(1, 2, 4, 1), _planes())
    assert cache.get(TileKey(1, 2, 4, 1)).surface == {}
    # a schema-1 file (planes only, no surface block) is foreign: dropped, not trusted
    p = cache._path(TileKey(1, 9, 9, 1))
    p.write_bytes(np.array([4, 5], "<u4").tobytes() + _planes().astype("<i2").tobytes())
    assert cache.get(TileKey(1, 9, 9, 1)) is None and not p.exists()
