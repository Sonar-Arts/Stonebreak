"""The EXP-2 layout kernel (kernels/cuda_controls2.py, csrc/controls2.cu) against its torch definition controls2._exp2:
every output key bit-identical (torch.equal) for every climate layout, several seeds, near and far windows, odd shapes.
Needs an sm_80+ CUDA device; runs on cuda:0 only."""
import time

import pytest
import torch

from terrain_slm import kernels as K

DEV = "cuda:0"
pytestmark = pytest.mark.skipif(not torch.cuda.is_available() or not K.available(DEV),
                                reason="needs an sm_80+ CUDA device (and TGM_KERNELS not 0)")

SEEDS = (1, 7, 123456789, -885965300526)
WINDOWS = ((0, 0, 96, 96), (-9000, 4000, 64, 288), (5, -3, 1, 1), (123, -77, 3, 7), (-500, 300, 200, 200),
           (8445, -554, 128, 128))   # the last: a canyon slope where `deep / 0.12` needs torch's double reciprocal


def _reference(ci0, cj0, hc, wc, seed, layout):
    from terrain_slm.world import controls2 as C2
    from terrain_slm.world import generator as G
    base = G.procedural_controls(ci0, cj0, hc, wc, seed, DEV)
    ci = torch.arange(ci0, ci0 + hc, device=DEV, dtype=torch.float32).view(-1, 1).expand(hc, wc)
    cj = torch.arange(cj0, cj0 + wc, device=DEV, dtype=torch.float32).view(1, -1).expand(hc, wc)
    return base, C2._exp2(base, ci, cj, seed, layout)


def _assert_equal(ref, got, what):
    assert list(got) == list(ref), f"{what}: keys {list(got)} != {list(ref)}"
    for k in ref:
        assert got[k].shape == ref[k].shape and got[k].dtype == ref[k].dtype, (what, k, got[k].shape, ref[k].shape)
        if not torch.equal(ref[k], got[k]):
            d = (ref[k] - got[k]).abs()
            pytest.fail(f"{what} {k}: {int((ref[k] != got[k]).sum())} values differ (max {float(d.max()):.3g})")


@pytest.mark.parametrize("layout", ("belts", "zones", "latitude"))
@pytest.mark.parametrize("seed", SEEDS)
@pytest.mark.parametrize("ci0,cj0,hc,wc", WINDOWS)
def test_controls2_kernel_is_bit_identical_to_torch(ci0, cj0, hc, wc, seed, layout):
    from terrain_slm.kernels import cuda_controls2 as KC2
    base, ref = _reference(ci0, cj0, hc, wc, seed, layout)
    got = KC2.controls2_native(base, ci0, cj0, hc, wc, seed, layout, DEV)
    _assert_equal(ref, got, f"{layout} seed {seed} window {(ci0, cj0, hc, wc)}")


def test_layouts_cover_every_climate_layout():
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.world import controls2 as C2
    assert tuple(C2.CLIMATE_LAYOUTS) == KC2.LAYOUTS


def test_controls2_kernel_matches_the_graphed_path():
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.world import controls2 as C2
    g = C2.GraphedControls2(torch.device(DEV), 2)
    for layout in C2.CLIMATE_LAYOUTS:
        _assert_equal(g(-37, 911, 80, 48, 5, layout), KC2.controls2(-37, 911, 80, 48, 5, DEV, layout), f"graphed {layout}")


def test_controls2_kernel_is_request_independent():
    from terrain_slm.kernels import cuda_controls2 as KC2
    big = KC2.controls2(-50, -50, 150, 150, 3, DEV)
    for n in (1, 2, 17, 63, 100):
        small = KC2.controls2(-50, -50, n, n, 3, DEV)
        for k in big:
            assert torch.equal(small[k], big[k][..., :n, :n]), (n, k)


def test_constant_drift_fails_loudly(monkeypatch):
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.world import controls2 as C2
    monkeypatch.setattr(C2, "TRENCH_M", C2.TRENCH_M + 1.0)
    monkeypatch.setattr(KC2, "_TABLES", {})
    with pytest.raises(RuntimeError, match="out of date"):
        KC2.controls2(0, 0, 4, 4, 1, DEV)


def _time(fn, reps):
    fn()
    torch.cuda.synchronize(DEV)
    t = time.perf_counter()
    for _ in range(reps):
        fn()
    torch.cuda.synchronize(DEV)
    return (time.perf_counter() - t) / reps * 1e3


def test_controls2_timing_report(capsys):
    """Not an assertion on speed: prints eager / CUDA-graph / native per-call times (base included in all three)."""
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.world import controls2 as C2
    from terrain_slm.world import generator as G
    g = C2.GraphedControls2(torch.device(DEV), 4)
    lines = []
    for hw in (96, 472):
        args = (-1234, 567, hw, hw, 42)
        base = G.procedural_controls(*args, DEV)
        eager = _time(lambda: C2.controls2(*args, DEV, "belts"), 3)
        graphed = _time(lambda: g(*args, "belts"), 5)
        native = _time(lambda: KC2.controls2(*args, DEV, "belts"), 20)
        kernel = _time(lambda: KC2.controls2_native(base, *args, "belts", DEV), 20)
        lines.append(f"controls2 {hw}x{hw}: eager {eager:.2f} ms, graphed {graphed:.2f} ms, native {native:.3f} ms "
                     f"(EXP-2 kernel alone {kernel:.3f} ms)")
    with capsys.disabled():
        print("\n" + "\n".join(lines))


def test_landforms_are_live_and_bit_identical_at_any_gain(monkeypatch):
    """Lowland landform provinces (2026-11-19): the fine field is non-trivial in low country around spawn, the broad
    part moves the trend, and the A/B gain (TERRAIN_SLM_LANDFORM_GAIN) stays bit-identical on the kernel (0 = off)."""
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.world import controls2 as C2
    on = KC2.controls2(-200, -200, 256, 256, 777, DEV)       # 12345 spawn is all karst (cones only)
    lf = on["landform"]
    assert float(lf.abs().max()) > 20.0 and float((lf != 0).float().mean()) > 0.2
    for gain in (0.0, 0.5):
        monkeypatch.setattr(C2, "LANDFORM_GAIN", gain)
        base, ref = _reference(-200, -200, 256, 256, 777, "belts")
        got = KC2.controls2_native(base, -200, -200, 256, 256, 777, "belts", DEV)
        _assert_equal(ref, got, f"landform gain {gain}")
        if gain == 0.0:
            assert float(got["landform"].abs().max()) == 0.0
            low = (on["trend"] > 100) & (on["trend"] < 500)
            assert float((on["trend"] - got["trend"])[low].abs().max()) > 10.0   # the broad part is in the trend


def test_landform_constant_drift_fails_loudly(monkeypatch):
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.world import controls2 as C2
    monkeypatch.setattr(C2, "PLATEAU_M", C2.PLATEAU_M + 1.0)
    monkeypatch.setattr(KC2, "_TABLES", {})
    with pytest.raises(RuntimeError, match="out of date"):
        KC2.controls2(0, 0, 4, 4, 1, DEV)


@pytest.mark.parametrize("layout", ("belts", "zones", "latitude"))
@pytest.mark.parametrize("seed", SEEDS)
def test_controls2_at_kernel_is_bit_identical_to_torch(seed, layout):
    """controls2_at (the climate node's sparse grids, arbitrary float coordinates) on the native kernel, v4's base
    computed in-kernel: every key bit-identical except the v4 climate archetypes it leaves out (AT_OMITTED)."""
    from terrain_slm.kernels import cuda_controls2 as KC2
    from terrain_slm.world import controls2 as C2
    g = torch.Generator().manual_seed(seed & 0xFFFF)
    # random points near and far, plus a climate-window-like sub-sampled grid ((a0 - e + (k + 0.5) / 4) * 116 cells)
    rnd = [((torch.rand(31, 47, generator=g) - 0.5) * span).to(DEV) for span in (2000.0, 2000.0, 6e5, 6e5)]
    sub = (torch.arange(64, dtype=torch.float32) + 0.5) / 4
    gi = ((-61 + sub) * 116.0).view(-1, 1).expand(64, 64).contiguous().to(DEV)
    gj = ((7 - 38 + sub) * 116.0).view(1, -1).expand(64, 64).contiguous().to(DEV)
    for ci, cj in ((rnd[0], rnd[1]), (rnd[2], rnd[3]), (gi, gj), (rnd[0][:1, :1], rnd[1][:1, :1])):
        ref = C2.controls2_at(ci, cj, seed, layout)
        got = KC2.controls2_at_native(ci, cj, seed, layout)
        _assert_equal({k: v for k, v in ref.items() if k not in KC2.AT_OMITTED}, got,
                      f"at {layout} seed {seed} shape {tuple(ci.shape)}")
