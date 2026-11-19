"""Cenda inference engine (OpenMason libcenda_kernels ci_*; terrain_slm/export) against the Python paths it replaces.

Bitwise where the engine replays a recorded Python native plan (detail2 incl. every expert subset and mixed-expert
batches, the v4-style relief incl. MeanFlow FiLM tables, the clip-variant SR plan, the climate plan); fp32 / fp16
tolerance where the reference is torch (SR without clip, climate vs fp32, planner vs fp64). Also: batching (a batch of
windows == each window alone), eager == graphs, stream ordering against torch temporaries (regression: torch's default
stream handle is 0), and the generator backend switch.

Needs an sm_80+ CUDA device and a built libcenda_kernels with the engine (OpenMason cenda build; TGM_CENDA_LIB);
skipped otherwise. TGM_TEST_DEVICE picks the card (default cuda:0)."""
import math
import os
from pathlib import Path

import pytest
import torch

from terrain_slm import kernels as K
from terrain_slm.models import detail2 as D2

DEV = os.getenv("TGM_TEST_DEVICE", "cuda:0")
ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2b"


def _engine_ok() -> bool:
    if not K.available(DEV):
        return False
    from terrain_slm.export import cenda_engine as E
    return E.available(DEV)


pytestmark = pytest.mark.skipif(not _engine_ok(), reason="needs an sm_80+ CUDA device and the Cenda inference engine")


@pytest.fixture(autouse=True)
def _no_tf32():
    old = torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = False
    yield
    torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32 = old


@pytest.fixture(scope="module")
def engine():
    from terrain_slm.export import cenda_engine as E
    return E.Engine.get(DEV)


def _small_detail2():
    cfg = D2.Detail2Config(channels=(32, 64, 96), dropout=0.0)
    m = D2.Detail2(cfg)
    m.adapters["aeolian"] = D2.Expert(cfg, levels=(1,))
    m.adapters["undersea"] = D2.Expert(cfg, levels=(0, 1))
    g = torch.Generator().manual_seed(3)
    with torch.no_grad():
        for p in m.parameters():
            p.copy_(torch.randn(p.shape, generator=g) * (0.5 if p.ndim == 0 else 1.0))
    m = m.to(DEV).eval()
    m.mean_flow = True
    return m


def test_detail2_bitwise_every_subset_and_mixed_batches(engine, tmp_path):
    from terrain_slm.export import cenda_export as X
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    m = _small_detail2()
    h, w = 64, 96
    X.export_detail2(m, h, w, 2, 0.0, tmp_path / "d.cim")
    ns = NativeDetail2Sampler(m, h, w, steps=2)
    gd = torch.Generator(device=DEV).manual_seed(5)
    cond = torch.randn(4, D2.N_COND, h, w, device=DEV, generator=gd)
    noise = torch.randn(4, 1, h, w, device=DEV, generator=gd)
    gates = torch.zeros(4, len(D2.STYLES), h, w, device=DEV)
    ia, iu = D2.STYLES.index("aeolian"), D2.STYLES.index("undersea")
    gates[1, ia, :, : w // 2] = torch.rand(h, w // 2, device=DEV, generator=gd)
    gates[2, iu, h // 3:] = 0.7
    gates[3, ia] = 0.4
    gates[3, iu, : h // 2] = 0.9                                 # windows 0..3: none, aeolian, undersea, both
    refs = [ns(cond[i:i + 1], noise[i:i + 1], gates[i:i + 1]) for i in range(4)]
    for graphs in (False, True):
        mod = engine.load(tmp_path / "d.cim", max_batch=4, contexts=2, graphs=graphs)
        for i in range(4):
            got, mask = mod.flow(cond[i:i + 1], noise[i:i + 1], gates[i:i + 1], return_mask=True)
            assert torch.equal(got, refs[i]), (graphs, i)
            assert mask == mod.mask_of(ns.active_for(gates[i:i + 1])), (i, mask)
        assert torch.equal(mod.flow(cond[:1], noise[:1], None), refs[0])
        got = mod.flow(cond, noise, gates)                       # mixed expert subsets in one batch
        for i in range(4):
            assert torch.equal(got[i:i + 1], refs[i]), (graphs, "batch", i)
        # inline temporaries: the engine must order itself after torch's pending work (default stream = handle 0)
        got = mod.flow(torch.cat([cond[2:3], cond[1:2]]), torch.cat([noise[2:3], noise[1:2]]),
                       torch.cat([gates[2:3], gates[1:2]]))
        assert torch.equal(got[0:1], refs[2]) and torch.equal(got[1:2], refs[1])


def test_relief_fm_bitwise_and_meanflow(engine, tmp_path):
    from terrain_slm.export import cenda_export as X
    from terrain_slm.kernels.cuda_unet import NativeSampler
    from terrain_slm.models import relief as RL
    cfgd = dict(channels=(32, 64, 96, 128), blocks=2, temb=64)
    torch.manual_seed(1)
    m = RL.Relief(RL.ReliefConfig(**cfgd))
    with torch.no_grad():
        for p in m.parameters():
            p.mul_(0.0).add_(torch.randn(p.shape) * 0.05)
    m = m.to(DEV).eval()
    n = 128
    g = torch.Generator(device=DEV).manual_seed(1)
    cond = torch.randn(2, RL.N_COND, n, n, device=DEV, generator=g) * 0.5
    noise = torch.randn(2, 1, n, n, device=DEV, generator=g)
    X.export_unet(m, n, n, 16, 0.0, tmp_path / "r.cim")
    mod = engine.load(tmp_path / "r.cim", max_batch=2)
    ns = NativeSampler(m, n, n, 16)
    ref = [ns(cond[i:i + 1], noise[i:i + 1]) for i in range(2)]
    assert torch.equal(mod.flow(cond[:1], noise[:1]), ref[0])
    got = mod.flow(cond, noise)
    assert torch.equal(got[:1], ref[0]) and torch.equal(got[1:], ref[1])
    # MeanFlow student: temb_h = 0 is its teacher (same numbers); perturbed, the engine replays the e(t, r) plan
    mf = RL.Relief(RL.ReliefConfig(**cfgd, mean_flow=True)).to(DEV).eval()
    mf.load_state_dict(m.state_dict(), strict=False)
    with torch.no_grad():
        for p in mf.temb_h.parameters():
            p.zero_()
    for steps in (1, 2):
        X.export_unet(mf, n, n, steps, 0.0, tmp_path / f"mf{steps}.cim")
        mm = engine.load(tmp_path / f"mf{steps}.cim")
        assert torch.equal(mm.flow(cond[:1], noise[:1]), NativeSampler(m, n, n, steps)(cond[:1], noise[:1]))
    g2 = torch.Generator().manual_seed(4)
    with torch.no_grad():
        for p in mf.temb_h.parameters():
            p.copy_((torch.randn(p.shape, generator=g2) * 0.03).to(DEV))
    X.export_unet(mf, n, n, 2, 0.0, tmp_path / "mf2p.cim")
    mm = engine.load(tmp_path / "mf2p.cim")
    got = mm.flow(cond[:1], noise[:1])
    assert torch.equal(got, X.unet_sampler(mf, n, n, 2)(cond[:1], noise[:1]))
    assert not torch.equal(got, NativeSampler(m, n, n, 2)(cond[:1], noise[:1]))
    with torch.no_grad():                                         # fp32 MeanFlow reference
        x = noise[:1].clone()
        ts = torch.linspace(0, 1, 3, device=DEV)
        for i in range(2):
            t = torch.full((1,), float(ts[i]), device=DEV)
            x = x + (ts[i + 1] - ts[i]) * mf(x, t, cond[:1], torch.full_like(t, float(ts[i + 1]))).float()
    assert float((got - x).pow(2).mean().sqrt()) < 0.01 * float(x.std())


@pytest.mark.skipif(not (WORLD / "sr30.pt").exists(), reason="needs checkpoints/exp2/world_exp2b")
def test_sr_engine_vs_fp32(engine, tmp_path):
    from terrain_slm.export import cenda_export as X
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    from terrain_slm.models import sr as SRM
    k = torch.load(WORLD / "sr30.pt", map_location=DEV, weights_only=False)
    c = dict(k["config"])
    c["channels"] = tuple(c["channels"])
    sr = SRM.SR(SRM.SRConfig(**c)).to(DEV).eval()
    sr.load_state_dict(k["model"])
    N = 128
    g = torch.Generator(device=DEV).manual_seed(3)
    coarse = torch.cumsum(torch.cumsum(torch.randn(1, 1, N // 2 + 8, N // 2 + 8, device=DEV, generator=g), -1), -2) * 3
    cond = SRM.build_cond(SRM.upsample(coarse), 30.0)[..., 8:8 + N, 8:8 + N].contiguous()
    noise = torch.randn(1, 1, N, N, device=DEV, generator=g)
    with torch.no_grad():
        X.export_sr(sr, N, N, 8, tmp_path / "sr.cim")
        got = engine.load(tmp_path / "sr.cim").flow(cond, noise)
        x = noise.clone()
        ts = torch.linspace(0, 1, 9, device=DEV)
        for i in range(8):
            x = x + (ts[i + 1] - ts[i]) * sr(x, torch.full((1,), float(ts[i]), device=DEV), cond).float()
        auto = SRM.sample(sr, cond, noise, 8)
        rms = lambda a: float((SRM.decode(a) - SRM.decode(x)).pow(2).mean().sqrt())
        assert rms(got) < 0.25 * rms(auto), (rms(got), rms(auto))
        d = X.sr_as_detail2(sr)                                   # the recorded plan itself (clip variant): bitwise
        X.export_detail2(d, N, N, 8, 0.0, tmp_path / "src.cim", mean_flow=False, clip=True)
        assert torch.equal(engine.load(tmp_path / "src.cim").flow(cond, noise),
                           NativeDetail2Sampler(d, N, N, 8, 0.0, mean_flow=False)(cond, noise))


@pytest.mark.skipif(not (WORLD / "climate.pt").exists(), reason="needs checkpoints/exp2/world_exp2b")
def test_climate_engine(engine, tmp_path):
    from terrain_slm.export import cenda_export as X
    from terrain_slm.models import climatenet as CN
    k = torch.load(WORLD / "climate.pt", map_location=DEV, weights_only=False)
    c = dict(k["config"])
    c["channels"] = tuple(c["channels"])
    m = CN.ClimateNet(CN.ClimateConfig(**c)).to(DEV).eval()
    m.load_state_dict(k["model"])
    x = torch.randn(2, 15, 96, 96, device=DEV, generator=torch.Generator(device=DEV).manual_seed(2)) * 0.7
    with torch.no_grad():
        ref = m(x)
        cp = X._ClimatePlan(m, 96, 96)
        py = cp(x[:1])
        X.export_climate(m, 96, 96, tmp_path / "c.cim")
        got = engine.load(tmp_path / "c.cim", max_batch=2).forward(x[:, :m.cfg.n_in].contiguous())
        assert torch.equal(got[:1], py)
        with torch.autocast("cuda", dtype=torch.bfloat16):
            auto = m(x).float()
    rms = lambda a: float((a - ref).pow(2).mean().sqrt())
    assert rms(got) < 0.2 * rms(auto), (rms(got), rms(auto))


@pytest.mark.skipif(not (WORLD / "planner.pt").exists(), reason="needs checkpoints/exp2/world_exp2b")
def test_planner_engine_fp32(engine, tmp_path):
    from terrain_slm.export import cenda_export as X
    from terrain_slm.models import planner as P
    pk = torch.load(WORLD / "planner.pt", map_location=DEV, weights_only=False)
    m = P.Planner(P.PlannerConfig(**pk["config"])).to(DEV).eval()
    m.load_state_dict(pk["model"])
    g = torch.Generator(device=DEV).manual_seed(0)
    x = torch.randn(32, P.N_IN, 64, 64, device=DEV, generator=g) * 0.5
    x[:, P.IN_HAS_TREND:] = 1.0
    with torch.no_grad():
        X.export_planner(m, 64, 64, tmp_path / "p.cim")
        mod = engine.load(tmp_path / "p.cim", max_batch=32)
        got = mod.forward(x)
        assert torch.equal(mod.forward(x[5:6].contiguous()), got[5:6])
        ref = m(x)
        md = P.Planner(m.cfg).to(DEV).double().eval()
        md.load_state_dict(m.state_dict())
        r64 = md(x.double())
    e_eng = float((got.double() - r64)[:, 0].pow(2).mean().sqrt()) * 1000      # height, metres
    e_t32 = float((ref.double() - r64)[:, 0].pow(2).mean().sqrt()) * 1000
    assert e_eng < max(4 * e_t32, 0.02), (e_eng, e_t32)


def _capture(seeds=(12345, 777), grid=((-1, -1), (0, 0), (0, 1), (1, 1))):
    import test_cuda_detail2 as T
    old = T.DEV
    T.DEV = DEV
    try:
        return T.capture_windows(seeds=seeds, grid=grid, device=DEV)
    finally:
        T.DEV = old


@pytest.mark.skipif(not (WORLD / "detail.pt").exists(), reason="needs checkpoints/exp2/world_exp2b")
def test_real_detail_windows(engine, tmp_path):
    from terrain_slm.export import cenda_export as X
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    windows, det = _capture()
    cond, noise, gates, st, t0 = windows[0]
    n = cond.shape[-1]
    X.export_detail2(det, n, n, st, t0, tmp_path / "det.cim")
    mod = engine.load(tmp_path / "det.cim", max_batch=2)
    ns = NativeDetail2Sampler(det, n, n, st, t0)
    refs = []
    for c, z, g, _s, _t in windows:
        ref = ns(c, z, g)
        refs.append(ref)
        assert torch.equal(mod.flow(c, z, g), ref)
    for i in range(0, len(windows) - 1, 2):
        a, b = windows[i], windows[i + 1]
        cat = lambda k: None if a[k] is None else torch.cat([a[k], b[k]])
        got = mod.flow(cat(0), cat(1), cat(2))
        assert torch.equal(got[:1], refs[i]) and torch.equal(got[1:], refs[i + 1])



def test_concurrent_requests_bitwise(engine, tmp_path):
    """Threads on their own torch streams share one model (2 contexts): every result equals its reference. (The
    engine builds graphs node by node, never by stream capture, so the service's threads cannot break it.)"""
    import threading
    from terrain_slm.export import cenda_export as X
    m = _small_detail2()
    h, w = 64, 64
    X.export_detail2(m, h, w, 2, 0.0, tmp_path / "d.cim")
    mod = engine.load(tmp_path / "d.cim", max_batch=2, contexts=2)
    gd = torch.Generator(device=DEV).manual_seed(9)
    cond = torch.randn(2, D2.N_COND, h, w, device=DEV, generator=gd)
    noise = torch.randn(2, 1, h, w, device=DEV, generator=gd)
    gates = torch.zeros(2, len(D2.STYLES), h, w, device=DEV)
    gates[0, D2.STYLES.index("aeolian")] = 0.5
    ref = mod.flow(cond, noise, gates)
    errors = []

    def work():
        s = torch.cuda.Stream(DEV)
        for _ in range(20):
            with torch.cuda.stream(s):
                if not torch.equal(mod.flow(cond, noise, gates), ref):
                    errors.append("mismatch")
    ts = [threading.Thread(target=work) for _ in range(4)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert not errors
    st = mod.stats()
    assert st["windows"] >= 162 and st["split_batches"] >= 81     # mixed subsets always split


@pytest.mark.skipif(not (WORLD / "planner.pt").exists(), reason="needs checkpoints/exp2/world_exp2b")
def test_generator_backend_switch(tmp_path, monkeypatch):
    """TGM_ENGINE=cenda (generator2.CENDA_ENGINE): every network routed through the engine, model_id marker :ce1."""
    from terrain_slm.world import generator2 as G2
    monkeypatch.setenv("TGM_ENGINE_CACHE", str(tmp_path / "cenda_models"))
    monkeypatch.setattr(G2, "CENDA_ENGINE", True)
    gen = G2.WorldGenerator2(WORLD, 4242, DEV)
    assert gen._cenda is not None and ":ce1" in gen.model_id
    assert {"detail", "relief", "planner"} <= set(gen._cenda.models)
    h = gen.native(0, 0, 64, 64)
    assert torch.isfinite(h).all()
    assert not gen._cenda.fallbacks, gen._cenda.fallbacks
    assert gen._cenda.calls.get("detail", 0) > 0 and gen._cenda.calls.get("planner", 0) > 0
    monkeypatch.setattr(G2, "CENDA_ENGINE", False)
    assert ":ce1" not in G2.WorldGenerator2(WORLD, 4242, DEV).model_id


# ----------------------------------------------------------------------------- phase 2 (libstonebreak_terrain)
def _terrain_ok() -> bool:
    try:
        from terrain_slm.export import native_pipeline as NP
        NP.library()
        return True
    except Exception:  # noqa: BLE001
        return False


@pytest.fixture(scope="module")
def phase2_gen():
    if not (WORLD / "planner.pt").exists() or not _terrain_ok():
        pytest.skip("needs world_exp2b and a built libstonebreak_terrain (stonebreak-game/native, target stonebreak_terrain)")
    import terrain_slm.export.cenda_backend as CB
    from terrain_slm.world import generator2 as G2
    old = G2.CENDA_ENGINE, CB.NATIVE_PIPELINE
    G2.CENDA_ENGINE, CB.NATIVE_PIPELINE = True, False
    try:
        gen = G2.WorldGenerator2(WORLD, 777, DEV)
    finally:
        G2.CENDA_ENGINE, CB.NATIVE_PIPELINE = old
    return gen, CB.Phase2(gen, gen._cenda)


def test_phase2_planner_stage_bitwise(phase2_gen):
    """C++ planner post-processing + sea merge == generator._run_planner_windows' (same network output); the startup
    guard agrees."""
    from terrain_slm.export.cenda_backend import _python_planner_post
    from terrain_slm.world import generator as G
    gen, p2 = phase2_gen
    assert "planner" not in p2.disabled, p2.disabled
    keys = [(wi, wj) for wi in range(2, 6) for wj in range(-3, 1)]
    S, W = G.STRIDE, G.WIN
    i0, j0 = min(k[0] for k in keys) * S, min(k[1] for k in keys) * S
    hn, wn = max(k[0] for k in keys) * S + W - i0, max(k[1] for k in keys) * S + W - j0
    xa, ca = gen._planner_input(i0, j0, hn, wn)
    offs = [(wi * S - i0, wj * S - j0) for wi, wj in keys]
    o = torch.randn(len(keys), 11, W, W, device=DEV, generator=torch.Generator(device=DEV).manual_seed(4))
    tr = torch.stack([ca["trend"][a:a + W, c:c + W] for a, c in offs])
    ref = _python_planner_post(gen, o, tr)
    outs = [torch.empty(11, W, W, device=DEV) for _ in keys]
    p2.tp.planner_windows(xa, ca["trend"], offs, outs, override=o)
    assert all(torch.equal(outs[i], ref[i]) for i in range(len(keys)))


def test_phase2_relief_and_climate_bitwise(phase2_gen):
    gen, p2 = phase2_gen
    for wi, wj in ((0, 0), (-2, 3)):
        assert torch.equal(gen._relief_window(wi, wj), p2.relief_window(wi, wj)), (wi, wj)
    for box in ((0, 0, 200, 150), (-300, 517, 64, 96), (12345, -9876, 1, 33)):
        a, b = gen.climate_cells(*box), p2.climate_cells(*box)
        assert list(a) == list(b) and all(torch.equal(a[k], b[k]) for k in a), box


def test_phase2_detail_window_bitwise(phase2_gen):
    """C++ detail window (blurs, torch-exact bilinear up_cells, cond / gates / noise, engine Detail2 with experts,
    guard, decode) == generator._detail_window on the same engine network."""
    gen, p2 = phase2_gen
    for wi, wj in ((0, 0), (1, -1)):
        assert torch.equal(gen._detail_window(wi, wj), p2.detail_window(wi, wj)), (wi, wj)


# ----------------------------------------------------------------------------- FP8 detail on the engine (agent "detail")
WORLD_FP8 = ROOT / "checkpoints" / "exp2" / "world_exp2e"


def _fp8_ok() -> bool:
    return ((WORLD_FP8 / "detail_fp8.pt").exists() and torch.cuda.is_available()
            and torch.cuda.get_device_capability(torch.device(DEV)) >= (8, 9))


@pytest.fixture(scope="module")
def fp8_gen():
    if not _fp8_ok():
        pytest.skip("needs world_exp2e (detail_fp8.pt) and an sm_89+ device")
    from terrain_slm.world import generator2 as G2
    old = G2.CENDA_ENGINE
    G2.CENDA_ENGINE = True                    # the backend exports the FP8 plan (and sr15) and routes the generator
    try:
        gen = G2.WorldGenerator2(WORLD_FP8, 4242, DEV)
    finally:
        G2.CENDA_ENGINE = old
    return gen


def _fp8_sampler(gen):
    from terrain_slm.kernels.cuda_detail2_fp8 import NativeDetail2SamplerFP8
    from terrain_slm.world import generator as G
    gen.detail.to(DEV)
    return NativeDetail2SamplerFP8(gen.detail, G.DETAIL_EXT, G.DETAIL_EXT, gen.cfg.detail_steps, G.DETAIL_T_START)


def test_detail_fp8_export_bitwise(fp8_gen, engine, tmp_path):
    """export_detail2_fp8 (every expert subset merged into one tagged plan, verified at export) == the Python FP8
    sampler: random subsets, real windows, eager and graphs, a mixed batch."""
    from terrain_slm.export import cenda_export as X
    from terrain_slm.world import generator as G
    gen = fp8_gen
    ns = _fp8_sampler(gen)
    X.export_detail2_fp8(gen.detail, G.DETAIL_EXT, G.DETAIL_EXT, gen.cfg.detail_steps, G.DETAIL_T_START,
                         tmp_path / "d8.cim", verify=True)
    n = G.DETAIL_EXT
    gd = torch.Generator(device=DEV).manual_seed(9)
    windows = []
    for act in ((), ("undersea",), ("aeolian", "skerries"), ("skerries", "aeolian", "undersea"), tuple(ns.experts)):
        c = torch.randn(1, ns.n_cond, n, n, device=DEV, generator=gd)
        z = torch.randn(1, 1, n, n, device=DEV, generator=gd)
        g = torch.zeros(1, len(D2.STYLES), n, n, device=DEV)
        for s in act:
            g[0, D2.STYLES.index(s), 37:300] = torch.rand(263, n, device=DEV, generator=gd) + 0.01
        windows.append((c, z, g))
    caps = []
    s0 = gen._sample

    def cap(model, cond, noise, steps, t_start=0.0, gates=None):
        if model is gen.detail:
            caps.append((cond.clone(), noise.clone(), None if gates is None else gates.clone()))
        return s0(model, cond, noise, steps, t_start, gates=gates)
    gen._sample = cap
    try:
        for seed, (wi, wj) in ((4242, (0, 0)), (-77, (3, -2)), (123456789, (-900, 1200))):
            gen.use_seed(seed)
            gen._detail_windows.pop((seed, wi, wj), None)
            gen._detail_window(wi, wj)
    finally:
        gen._sample = s0
    windows += caps
    refs = [ns(c, z, g) for c, z, g in windows]
    for graphs in (True, False):
        mod = engine.load(tmp_path / "d8.cim", max_batch=2, graphs=graphs)
        for (c, z, g), ref in zip(windows, refs):
            got, mask = mod.flow(c, z, g, return_mask=True)
            assert torch.equal(got.view(torch.int32), ref.view(torch.int32)), (graphs, ns.active_for(g))
            assert mask == mod.mask_of(ns.active_for(g))
        got = mod.flow(torch.cat([windows[1][0], windows[3][0]]), torch.cat([windows[1][1], windows[3][1]]),
                       torch.cat([windows[1][2], windows[3][2]]))
        assert torch.equal(got[:1], refs[1]) and torch.equal(got[1:], refs[3])


def test_backend_serves_fp8_detail_and_sr15(fp8_gen):
    """The backend exports the detail from the FP8 sampler the generator runs (and SR-15): generator windows on the
    engine == the Python FP8 / native SR samplers."""
    from terrain_slm.kernels import cuda_sr as KS
    from terrain_slm.models import sr as SRM
    from terrain_slm.world import generator2 as G2
    gen = fp8_gen
    be = gen._cenda
    assert be.detail_fp8 and "detail" in be.models and ":kd8-" in gen.model_id
    ns = _fp8_sampler(gen)
    caps = []
    s0 = gen._sample

    def cap(model, cond, noise, steps, t_start=0.0, gates=None):
        x = s0(model, cond, noise, steps, t_start, gates=gates)
        if model is gen.detail:
            caps.append((cond.clone(), noise.clone(), None if gates is None else gates.clone(), x.clone()))
        return x
    gen._sample = cap
    try:
        gen.use_seed(31)
        gen._detail_window(7, -4)
    finally:
        gen._sample = s0
    assert caps and all(torch.equal(ns(c, z, g), x) for c, z, g, x in caps)
    assert be.calls.get("detail", 0) > 0 and "detail" not in be.fallbacks
    if gen.sr15 is not None and gen._sr15_native:
        assert "sr15" in be.models
        n = G2.SR_CORE + 2 * G2.SR_APRON
        gd = torch.Generator(device=DEV).manual_seed(2)
        c = torch.randn(1, SRM.N_COND, n, n, device=DEV, generator=gd)
        z = torch.randn(1, 1, n, n, device=DEV, generator=gd)
        ref = KS.native_sr_sampler(gen.sr15, n, n, G2.SR15_STEPS).eager(c, z)
        assert torch.equal(gen._sr_sample(gen.sr15, c, z, G2.SR15_STEPS), ref)
        assert be.calls.get("sr15", 0) > 0
