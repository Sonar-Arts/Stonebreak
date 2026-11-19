"""Native EXP-2 detail sampler (kernels/cuda_detail2.py) against the fp32 torch model.

  * a small random Detail2 with experts (gated at several levels): native vs fp32 vs bf16 autocast, graph == eager,
    determinism, the gate-free and gated graphs;
  * the numeric gate on REAL generator windows (the shipping checkpoint, 2 MeanFlow steps, DETAIL_T_START): decoded
    residual RMS in metres, native vs fp32 and the current bf16-autocast torch path vs fp32. Pass = native median no
    worse than bf16's, and max |x| < DETAIL_X_LIMIT everywhere. Run with -s to see the table.

Needs an sm_80+ CUDA device; the real-window gate also the checkpoint (skipped without it)."""
from pathlib import Path

import pytest
import torch

from terrain_slm import kernels as K
from terrain_slm.models import detail as DT
from terrain_slm.models import detail2 as D2

DEV = "cuda:0"
ROOT = Path(__file__).resolve().parents[1]
WORLD = ROOT / "checkpoints" / "exp2" / "world_exp2b"
pytestmark = pytest.mark.skipif(not K.available(DEV), reason="needs an sm_80+ CUDA device (and TGM_KERNELS not 0)")


def fp32_sample(model, cond, noise, steps, t_start=0.0, gates=None):
    """detail2.sample with autocast off (the reference)."""
    x = (1.0 - t_start) * noise
    ts = torch.linspace(t_start, 1.0, steps + 1, device=cond.device)
    mf = getattr(model, "mean_flow", False)
    with torch.no_grad():
        for i in range(steps):
            t = torch.full((1,), float(ts[i]), device=cond.device)
            r = torch.full_like(t, float(ts[i + 1])) if mf else None
            x = x + (ts[i + 1] - ts[i]) * model(x, t, cond, r, gates=gates).float()
    return x


@pytest.fixture(autouse=True)
def _no_tf32():
    old = torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = False
    yield
    torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32 = old


def _small_model():
    cfg = D2.Detail2Config(channels=(32, 64, 96), dropout=0.0)
    m = D2.Detail2(cfg)
    m.adapters["aeolian"] = D2.Expert(cfg, levels=(1,))
    m.adapters["undersea"] = D2.Expert(cfg, levels=(0, 1))      # level 0: the gate is used unpooled
    g = torch.Generator().manual_seed(3)
    with torch.no_grad():                                      # untrained gains are zero: give everything weight
        for p in m.parameters():
            p.copy_(torch.randn(p.shape, generator=g) * (0.5 if p.ndim == 0 else 1.0))
    m = m.to(DEV).eval()
    m.mean_flow = True
    return m


def test_small_model_tracks_fp32_far_better_than_autocast():
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    m = _small_model()
    assert NativeDetail2Sampler.supported(m)
    gd = torch.Generator(device=DEV).manual_seed(5)
    h, w = 64, 96
    cond = torch.randn(1, D2.N_COND, h, w, device=DEV, generator=gd)
    noise = torch.randn(1, 1, h, w, device=DEV, generator=gd)
    gates = torch.zeros(1, len(D2.STYLES), h, w, device=DEV)
    gates[:, D2.STYLES.index("aeolian"), :, : w // 2] = torch.rand(h, w // 2, device=DEV, generator=gd)
    gates[:, D2.STYLES.index("undersea"), h // 3 :] = 0.7
    ns = NativeDetail2Sampler(m, h, w, steps=2)
    outs = {}
    for name, gt in (("plain", None), ("gated", gates), ("one", gates * (torch.arange(len(D2.STYLES), device=DEV)
                                                                   == D2.STYLES.index("aeolian"))[None, :, None, None])):
        got = ns(cond, noise, gt)
        ref = fp32_sample(m, cond, noise, 2, gates=gt)
        auto = D2.sample(m, cond, noise, steps=2, mean_flow=True, gates=gt)
        e_nat = float((got - ref).pow(2).mean().sqrt())
        e_auto = float((auto - ref).pow(2).mean().sqrt())
        assert torch.isfinite(got).all()
        assert e_nat < 0.35 * e_auto, (name, e_nat, e_auto)
        assert e_nat < 0.005 * float(ref.std()), (name, e_nat)
        assert torch.equal(ns.eager(cond, noise, gt), got), name
        outs[name] = got
    assert set(ns._graphs) == {(), ("aeolian", "undersea"), ("aeolian",)}
    assert not torch.equal(outs["plain"], outs["gated"])
    # replays are deterministic and independent of what ran before
    assert torch.equal(ns(cond, noise, None), outs["plain"])
    assert torch.equal(ns(cond, noise, gates), outs["gated"])
    # an all-zero gate tensor is the gate-free network (experts skipped, as Detail2.forward does)
    assert torch.equal(ns(cond, noise, torch.zeros_like(gates)), outs["plain"])


def test_unpacked_expert_returns_none():
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    m = _small_model()
    ns = NativeDetail2Sampler(m, 32, 32, steps=1, experts=("undersea",))
    gates = torch.zeros(1, len(D2.STYLES), 32, 32, device=DEV)
    gates[:, D2.STYLES.index("aeolian")] = 1.0
    cond, noise = torch.randn(1, D2.N_COND, 32, 32, device=DEV), torch.randn(1, 1, 32, 32, device=DEV)
    assert ns(cond, noise, gates) is None
    gates[:, D2.STYLES.index("glacial_fjord")] = 1.0         # not an adapter of this model: ignored, as in torch
    gates[:, D2.STYLES.index("aeolian")] = 0.0
    assert ns(cond, noise, gates) is not None


def test_pixnorm_kernel_matches_torch():
    from terrain_slm.kernels import cuda_conv as C
    from terrain_slm.models.mp import pixel_norm
    for c in (96, 384, 512):
        x = (torch.randn(1, 17, 23, c, device=DEV) * 3).half()
        ref = pixel_norm(x.float().permute(0, 3, 1, 2)).permute(0, 2, 3, 1)
        raw, silu = x.clone(), torch.empty_like(x)
        lc = C.make_pixnorm(raw, silu)
        C.bind([lc], DEV)
        lc()
        assert float((raw.float() - ref).abs().max()) <= 2e-3 * float(ref.abs().max()), c
        assert float((silu.float() - torch.nn.functional.silu(ref)).abs().max()) < 1e-2, c


# ----------------------------------------------------------------------------- real windows (the shipping gate)
def capture_windows(seeds=(12345, 777, 4242), grid=((-1, -1), (-1, 0), (0, -1), (0, 0), (0, 1), (1, 0), (1, 1)),
                    device=DEV):
    """(cond, noise, gates, model, steps, t_start) of the generator's own detail windows (first guard attempt)."""
    from terrain_slm.world import generator as G
    from terrain_slm.world import generator2 as G2
    gen = G2.WorldGenerator2(WORLD, seeds[0], device)
    got = []

    def spy(model, cond, noise, steps, t_start=0.0, gates=None, **kw):
        if model is gen.detail:
            got.append((cond.clone(), noise.clone(), None if gates is None else gates.clone(), steps, t_start))
            return torch.zeros_like(noise)          # the guard accepts it; nothing downstream is built here
        return G.WorldGenerator._sample(gen, model, cond, noise, steps, t_start, gates=gates, **kw)

    gen._sample = spy
    for seed in seeds:
        gen.use_seed(seed)
        for wi, wj in grid:
            gen._detail_window(wi, wj)
    det = gen.detail.to(device)
    return got, det


def window_stats(det, windows, native_cls=None, steps=None):
    """Per window: decoded-residual RMS (m) of native and bf16 vs fp32, and native's max |x|."""
    from terrain_slm.kernels.cuda_detail2 import NativeDetail2Sampler
    native_cls = native_cls or NativeDetail2Sampler
    rows, samplers = [], {}
    for cond, noise, gates, st, t0 in windows:
        st = steps or st
        key = (tuple(cond.shape), st, t0)
        if key not in samplers:
            samplers[key] = native_cls(det, cond.shape[-2], cond.shape[-1], st, t0)
        ns = samplers[key]
        nat = ns(cond, noise, gates)
        ref = fp32_sample(det, cond, noise, st, t0, gates=gates)
        auto = D2.sample(det, cond.contiguous(memory_format=torch.channels_last), noise, steps=st, t_start=t0,
                         mean_flow=det.mean_flow, gates=gates)
        dec = lambda x: DT.decode(x.clamp(-7.0, 7.0))
        rms = lambda a: float((dec(a) - dec(ref)).pow(2).mean().sqrt())
        active = ns.active_for(gates)
        rows.append({"native_m": rms(nat), "bf16_m": rms(auto), "max_abs": float(nat.nan_to_num(1e9).abs().max()),
                     "ref_max_abs": float(ref.abs().max()), "experts": "+".join(active or ()) or "-",
                     "res_std_m": float(dec(ref).std())})
    return rows


def summarize(rows) -> dict:
    import numpy as np
    out = {}
    for k in ("native_m", "bf16_m"):
        v = np.array([r[k] for r in rows])
        out[k] = {"median": float(np.median(v)), "p90": float(np.percentile(v, 90)), "max": float(v.max())}
    out["max_abs"] = max(r["max_abs"] for r in rows)
    out["windows"] = len(rows)
    out["gated"] = sum(r["experts"] != "-" for r in rows)
    return out


@pytest.mark.skipif(not (WORLD / "detail.pt").exists(), reason="needs checkpoints/exp2/world_exp2b")
def test_real_windows_numeric_gate():
    windows, det = capture_windows()
    assert windows and isinstance(det, D2.Detail2)
    rows = window_stats(det, windows)
    s = summarize(rows)
    print("\nwindow  experts                native_m  bf16_m   max|x|  res_std_m")
    for i, r in enumerate(rows):
        print(f"{i:6d}  {r['experts']:<22s} {r['native_m']:8.3f} {r['bf16_m']:8.3f} {r['max_abs']:7.3f} {r['res_std_m']:9.2f}")
    print("summary", s)
    assert s["native_m"]["median"] <= s["bf16_m"]["median"], s
    assert s["max_abs"] < 7.0, s
