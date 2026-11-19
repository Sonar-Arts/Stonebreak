"""ERA5 grid sampling for data v2's climate cells: orientation (north row 0), 0-east columns, longitude wrap."""
import numpy as np

from terrain_slm.data import climate_era5 as C


def test_sample_orientation_and_wrap():
    lat = 90.0 - np.arange(721) * 0.25
    lon = np.arange(1440) * 0.25
    field = (lat[:, None] * 1000 + lon[None, :]).astype(np.float32)   # encodes its own coordinates
    out = C.sample(field, np.array([45.0, -30.0]), np.array([10.0, -120.0]))
    assert np.allclose(out[0, 0], 45_000 + 10.0)
    assert np.allclose(out[1, 1], -30_000 + 240.0)                     # -120 E == 240 E
    edge = C.sample(field, np.array([0.0]), np.array([359.875]))       # between 359.75 and 0 (=360)
    assert np.allclose(edge[0, 0], 0.5 * (359.75 + 0.0))              # wraps onto column 0, not off the grid


def test_region_cells_lapse_and_names():
    shape = (721, 1440)
    clim = {k: np.full(shape, v, np.float32) for k, v in
            dict(t_mean=10.0, orog=1000.0, tseason=500.0, precip=800.0, pcv=40.0, aridity=0.7,
                 soil=0.3, snow=0.01, u10=3.0, v10=-1.0).items()}
    cells = C.region_cells(clim, np.array([46.0]), np.array([8.0]))
    assert set(cells) >= {"t0", "tseason", "precip", "pcv"}                # v4's names, so old trainers read it
    assert np.allclose(cells["t0"], 10.0 + C.LAPSE_C_PER_M * 1000.0)
