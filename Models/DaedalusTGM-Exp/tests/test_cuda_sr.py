"""SR-30 on the native fp16 sampler (kernels/cuda_sr.py) vs the fp32 model on real 1:2 windows: an order of magnitude
closer than the torch bf16 path it replaces, graph replay == eager launches."""
from __future__ import annotations

from pathlib import Path

import pytest
import torch

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "checkpoints/exp2/world_exp2b"
pytestmark = pytest.mark.skipif(not ((MODEL / "sr30.pt").exists() and torch.cuda.is_available()),
                                reason="no EXP-2 model or no CUDA")


def _fp32(model, cond, noise, steps):
    x = noise
    ts = torch.linspace(0.0, 1.0, steps + 1, device=cond.device)
    for i in range(steps):
        t = torch.full((1,), float(ts[i]), device=cond.device)
        x = x + (ts[i + 1] - ts[i]) * model(x, t, cond).float()
    return x


def test_native_sr_matches_fp32_better_than_bf16():
    from terrain_slm import kernels as K
    from terrain_slm.kernels import cuda_sr as KS
    from terrain_slm.models import sr as SRM
    from terrain_slm.synth import noise as S
    from terrain_slm.world import generator2 as G2
    from terrain_slm.world.generator import GenConfig
    dev = f"cuda:{torch.cuda.device_count() - 1}"
    if not K.available(dev):
        pytest.skip("no native kernels")
    gen = G2.WorldGenerator2(MODEL, 12345, dev, GenConfig())
    assert KS.supported(gen.sr30)
    ext, m = G2.SR_CORE + 2 * G2.SR_APRON, 4
    fs = KS.native_sr_sampler(gen.sr30, ext, ext, G2.SR_STEPS)
    worst_native, worst_ratio = 0.0, 0.0
    for wi, wj in ((0, 0), (1, -1), (3, -6)):
        a0, b0 = wi * G2.SR_CORE - G2.SR_APRON, wj * G2.SR_CORE - G2.SR_APRON
        with torch.no_grad():
            coarse = gen._detail_samples(a0 // 2 - m, b0 // 2 - m, (a0 + ext) // 2 + m, (b0 + ext) // 2 + m)
            cond = SRM.build_cond(SRM.upsample(coarse[None, None]), 30.0)[..., 2 * m : 2 * m + ext, 2 * m : 2 * m + ext]
            cond = cond.contiguous()
            noise = S.white_noise(a0, b0, ext, ext, gen.seed, G2.SR_NOISE_STREAM, dev)[None, None]
            fine = lambda x: SRM.assemble(coarse[None, None][..., m:-m, m:-m], x * G2.SR_GAIN)
            ref = fine(_fp32(gen.sr30, cond, noise, G2.SR_STEPS))
            bf = fine(SRM.sample(gen.sr30, cond.contiguous(memory_format=torch.channels_last), noise, G2.SR_STEPS))
            eager = fs.eager(cond, noise)
            graph = fs(cond, noise)
            assert torch.equal(eager, graph)
            rms = lambda f: float((f - ref).pow(2).mean().sqrt())
            e_nat, e_bf = rms(fine(eager)), rms(bf)
        worst_native = max(worst_native, e_nat)
        worst_ratio = max(worst_ratio, e_nat / max(e_bf, 1e-9))
    assert worst_native < 0.1, worst_native          # metres RMS of the 30 m heights
    assert worst_ratio < 0.3, worst_ratio            # >= ~3x closer to fp32 than bf16 (measured ~10x)
