import numpy as np
import pytest

from bridge.coarse import CoarseElevation
from bridge.config import BridgeConfig
from bridge.height_mapping import HeightCurve


def _cfg(tmp_path, chunk_blocks=256, cell_blocks=16, scale=2):
    return BridgeConfig(
        upstream_url="http://localhost:8000",
        seed=7,
        scale=scale,
        tile_size_blocks=256,
        meters_per_block=15.0,
        world_height=1024,
        sea_level=320,
        noise_scale=1.0,
        cache_dir=str(tmp_path),
        cache_max_bytes=10_000_000,
        upstream_timeout_s=5.0,
        coarse_chunk_blocks=chunk_blocks,
        coarse_cell_blocks=cell_blocks,
    )


class FakeClient:
    """Stands in for UpstreamClient, recording the exact request shapes asked for.

    Elevation is a pure function of native pixel coordinates, so a test can
    assert that a chunk's contents depend only on its ID.
    """

    def __init__(self):
        self.calls = []

    def fetch_native(self, i1, j1, i2, j2, timeout_s=None):
        self.calls.append((i1, j1, i2, j2))
        i = np.arange(i1, i2, dtype=np.float64)[:, None]
        j = np.arange(j1, j2, dtype=np.float64)[None, :]
        return (3.0 * i + j).astype(np.int16)


def test_chunk_shape_and_dtype(tmp_path):
    client = FakeClient()
    coarse = CoarseElevation(_cfg(tmp_path), client)
    cells = coarse.chunk(0, 0)
    assert coarse.cells_per_chunk == 256 // 16
    assert cells.shape == (16, 16)
    assert cells.dtype == np.dtype("<f4")


def test_request_shape_is_canonical_per_chunk(tmp_path):
    """Upstream is only deterministic for an identical request shape (tiling.py),
    so a chunk must always be fetched through the same box."""
    client = FakeClient()
    coarse = CoarseElevation(_cfg(tmp_path), client)
    coarse.chunk(2, -3)
    # 256-block chunk at scale 2 = 128 native px, floor-divided for negatives.
    assert client.calls == [(256, -384, 384, -256)]


def test_negative_chunks_tile_contiguously(tmp_path):
    client = FakeClient()
    coarse = CoarseElevation(_cfg(tmp_path), client)
    coarse.chunk(-1, 0)
    coarse.chunk(0, 0)
    (i1a, _, i2a, _), (i1b, _, i2b, _) = client.calls
    assert i2a == i1b, "chunk -1 must end exactly where chunk 0 begins"
    assert i2a - i1a == i2b - i1b


def test_second_call_is_served_from_disk(tmp_path):
    client = FakeClient()
    coarse = CoarseElevation(_cfg(tmp_path), client)
    first = coarse.chunk(1, 1)
    assert len(client.calls) == 1

    reopened = CoarseElevation(_cfg(tmp_path), client)
    second = reopened.chunk(1, 1)
    assert len(client.calls) == 1, "a cached chunk must not re-hit the GPU"
    np.testing.assert_array_equal(first, second)


def test_values_are_fractional_block_heights(tmp_path):
    """Whole-block quantisation makes 40% of land perfectly flat and turns the
    caller's downhill routing into a distance field, so these must stay
    fractional and must come from the same curve the tile path uses."""
    cfg = _cfg(tmp_path)
    client = FakeClient()
    coarse = CoarseElevation(cfg, client)
    cells = coarse.chunk(0, 0)

    assert np.any(cells != np.floor(cells)), "values were quantised to whole blocks"

    # Cell (0,0) pools an 8x8 native block; the curve is non-linear, so the mean
    # has to be taken in metres and mapped afterwards.
    raw = client.fetch_native(0, 0, 128, 128).astype(np.float64)
    expected = HeightCurve.from_config(cfg).to_block_height_exact(raw[:8, :8].mean())
    assert cells[0, 0] == pytest.approx(expected, abs=1e-3)


def test_cell_must_divide_chunk_and_align_to_native_pixels(tmp_path):
    with pytest.raises(ValueError, match="multiple of cell"):
        CoarseElevation(_cfg(tmp_path, chunk_blocks=100, cell_blocks=16), FakeClient())
    # 15 blocks is not a whole number of native pixels at scale 2, so cells
    # could not pool cleanly.
    with pytest.raises(ValueError, match="whole number of native pixels"):
        CoarseElevation(_cfg(tmp_path, chunk_blocks=300, cell_blocks=15), FakeClient())


def test_upstream_shape_mismatch_is_loud(tmp_path):
    class ShortClient(FakeClient):
        def fetch_native(self, i1, j1, i2, j2, timeout_s=None):
            return np.zeros((4, 4), dtype=np.int16)

    coarse = CoarseElevation(_cfg(tmp_path), ShortClient())
    with pytest.raises(ValueError, match="expected"):
        coarse.chunk(0, 0)
