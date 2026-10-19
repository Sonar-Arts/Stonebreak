"""Native drainage routing (kernels/csrc/hydro.cpp) is bit-identical to the numba / numpy reference:
depression fill, D8 receivers, flow accumulation, the generator's route / accumulate / seam-cycle steps.
Rivers hang on every tie-break, so the assertions are exact equality (values and dtypes)."""
import numpy as np
import pytest
import torch

from terrain_slm.data.hydrology import fill as FL
from terrain_slm.data.hydrology import flow as FW
from terrain_slm.kernels import hydro as H

pytestmark = pytest.mark.skipif(not H.available(), reason="native routing needs a C++ compiler (and TGM_KERNELS not 0)")


def _same(a, b):
    a, b = np.asarray(a), np.asarray(b)
    assert a.dtype == b.dtype and a.shape == b.shape, (a.dtype, b.dtype, a.shape, b.shape)
    # NaN-aware bitwise equality (ocean cells keep NaN elevations)
    if a.dtype.kind == "f":
        assert np.array_equal(a.view(np.uint64 if a.itemsize == 8 else np.uint32),
                              b.view(np.uint64 if b.itemsize == 8 else np.uint32)), \
            f"{int((a != b).sum())} values differ"
    else:
        assert np.array_equal(a, b), f"{int((a != b).sum())} values differ"


def _ref_fill(z, **kw):
    orig = FL._native
    FL._native = lambda: None
    try:
        return FL.fill_depressions(z, **kw)
    finally:
        FL._native = orig


def _fields(seed):
    """Terrain with plateaus (exact ties), closed pits, sea, NaNs and quantised flats."""
    rng = np.random.default_rng(seed)
    out = []
    for h, w in ((64, 64), (97, 33), (1, 50), (50, 1), (2, 2), (3, 7), (200, 150)):
        base = rng.normal(0, 1, (h, w)).cumsum(0).cumsum(1) * 5.0
        out.append(base)
        out.append(np.round(base / 20.0) * 20.0)            # big flats: ties everywhere
        z = np.round(rng.uniform(-50, 300, (h, w)) / 50) * 50
        out.append(z)                                         # sea (<=0) mixed in
        zn = base.copy()
        zn[rng.random((h, w)) < 0.05] = np.nan
        out.append(zn)
    out.append(np.full((40, 40), -5.0))                       # all ocean
    out.append(np.full((40, 40), 10.0))                       # one flat plateau
    return out


@pytest.mark.parametrize("seed", [0, 1, 2])
def test_fill_and_d8_match_reference(seed):
    rng = np.random.default_rng(100 + seed)
    for z in _fields(seed):
        inv = FL.ocean_mask(z)
        for eps_field in (None, 1e-3 * (1.0 + 0.9 * np.tanh(rng.normal(0, 1, z.shape)))):
            ref = _ref_fill(z, invalid=inv, epsilon_field=eps_field)
            got = FL.fill_depressions(z, invalid=inv, epsilon_field=eps_field)
            _same(got, ref)
            _same(FW.d8_receivers(got, inv), FW.d8_receivers_reference(ref, inv))


@pytest.mark.parametrize("seed", [0, 1])
def test_flow_accumulation_matches_reference(seed):
    rng = np.random.default_rng(200 + seed)
    for z in _fields(seed):
        inv = FL.ocean_mask(z)
        recv = FW.d8_receivers_reference(_ref_fill(z, invalid=inv), inv)
        for weights in (None, rng.uniform(0, 3, z.shape)):
            _same(FW.flow_accumulation(recv, ~inv, weights=weights),
                  FW.flow_accumulation_reference(recv, ~inv, weights=weights))


def test_cycle_raises_like_reference():
    recv = np.array([1, 0, -1, 2], dtype=np.int64)
    valid = np.ones((2, 2), dtype=bool)
    with pytest.raises(RuntimeError, match="not acyclic"):
        FW.flow_accumulation_reference(recv, valid)
    with pytest.raises(RuntimeError, match="not acyclic"):
        FW.flow_accumulation(recv, valid)


def _window_inputs(seed, n=448):
    """Generator-shaped route inputs: coarse height with coasts, plains and relief, precipitation, noise."""
    g = torch.Generator().manual_seed(seed)
    x = torch.randn(1, 1, n, n, generator=g)
    k = torch.ones(1, 1, 9, 9) / 81
    smooth = x
    for _ in range(6):
        smooth = torch.nn.functional.conv2d(smooth, k, padding=4)
    height = (smooth[0, 0] * 4000.0 + torch.linspace(-300, 900, n)[None, :]).float()
    height = torch.where(height.abs() < 40, torch.round(height / 10) * 10, height)   # flats near the coast
    precip = (torch.rand(n, n, generator=g) * 2500.0 - 100.0).float()
    flat = torch.randn(n, n, generator=g).float()
    return height, precip, flat


def _ref_route(height, precip, flat):
    from terrain_slm.world import generator as G
    of, od = FL._native, FW._native
    FL._native = FW._native = lambda: None
    try:
        return G.route_window(height, precip, flat_noise=flat)
    finally:
        FL._native, FW._native = of, od


def _ref_seam(recv, owner):
    """generator.break_seam_cycles as written (numpy)."""
    n = recv.size
    for _ in range(4):
        live = recv >= 0
        indeg = np.bincount(recv[live], minlength=n)
        frontier = np.flatnonzero(indeg == 0)
        done = np.zeros(n, dtype=bool)
        while frontier.size:
            done[frontier] = True
            tgt = recv[frontier]
            tgt = tgt[tgt >= 0]
            np.subtract.at(indeg, tgt, 1)
            frontier = np.unique(tgt[indeg[tgt] == 0])
        stuck = np.flatnonzero(~done)
        if stuck.size == 0:
            return recv
        cut = stuck[(recv[stuck] >= 0) & (owner[stuck] != owner[recv[stuck].clip(0)])]
        recv[cut] = -1
    return recv


@pytest.mark.parametrize("seed", [0, 1])
def test_route_window_matches_generator(seed):
    height, precip, flat = _window_inputs(seed)
    for fn in (flat, None):
        r_recv, r_inv, r_w = _ref_route(height, precip, fn)
        n_recv, n_inv, n_w = H.route_window(height, precip, fn)
        _same(n_recv, r_recv)
        _same(n_inv, r_inv)
        _same(n_w, r_w)


@pytest.mark.parametrize("seed", [0, 1])
def test_accumulate_core_matches_generator(seed):
    from terrain_slm.data.build import D8_OFFSETS
    height, precip, flat = _window_inputs(seed)
    recv, inv, weights = _ref_route(height, precip, flat)
    h, w = inv.shape
    g = torch.Generator().manual_seed(seed)
    for inflow in (None, torch.rand(h, w, generator=g) * 12.0):
        # reference: the numpy body of generator.accumulate_drainage
        wts = weights
        if inflow is not None:
            ring = np.zeros((h, w), dtype=bool)
            ring[0, :] = ring[-1, :] = ring[:, 0] = ring[:, -1] = True
            inward = ring.ravel() & (recv >= 0)
            extra = np.expm1(inflow.double().numpy().clip(0.0, 30.0)).ravel()
            wv = weights.ravel().copy()
            wv[inward] = np.maximum(wv[inward], extra[inward])
            wts = wv.reshape(h, w)
        ref_acc = FW.flow_accumulation_reference(recv, ~inv, weights=wts)
        idx = np.arange(h * w)
        d8 = np.full(h * w, 8, dtype=np.int64)
        live = recv >= 0
        dr = recv[live] // w - idx[live] // w
        dc = recv[live] % w - idx[live] % w
        for k, (a, b) in enumerate(D8_OFFSETS):
            d8[np.flatnonzero(live)[(dr == a) & (dc == b)]] = k
        acc, d8n = H.accumulate_core(recv, inv, weights, inflow)
        _same(acc, ref_acc)
        _same(d8n, d8.reshape(h, w))


def test_break_seam_cycles_matches_reference():
    rng = np.random.default_rng(5)
    for n, owners in ((5000, 4), (20000, 9), (300, 2)):
        for _ in range(5):
            recv = rng.integers(-1, n, n).astype(np.int64)
            recv[rng.random(n) < 0.3] = -1
            owner = rng.integers(0, owners, n).astype(np.int64)
            ref = _ref_seam(recv.copy(), owner)
            got = H.break_seam_cycles(recv.copy(), owner)
            _same(got, ref)
    # an acyclic graph is untouched
    recv = np.array([1, 2, -1, 2], dtype=np.int64)
    _same(H.break_seam_cycles(recv.copy(), np.zeros(4, dtype=np.int64)), recv)


@pytest.mark.parametrize("epsilon", [0.0, -1e-3, 1e-3])
def test_fill_degenerate_epsilon_matches_reference(epsilon):
    """Zero / negative epsilon and NaN epsilon fields break the monotone queue: the native fill then uses
    its replica of the reference heap and must still agree exactly."""
    rng = np.random.default_rng(9)
    for z in _fields(3)[:12]:
        inv = FL.ocean_mask(z)
        _same(FL.fill_depressions(z, invalid=inv, epsilon=epsilon), _ref_fill(z, invalid=inv, epsilon=epsilon))
        eps = 1e-3 * (1.0 + 0.9 * np.tanh(rng.normal(0, 1, z.shape)))
        eps[rng.random(z.shape) < 0.1] = np.nan
        _same(FL.fill_depressions(z, invalid=inv, epsilon_field=eps), _ref_fill(z, invalid=inv, epsilon_field=eps))
