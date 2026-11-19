"""The project's own native kernels for DaedalusTGM-Exp inference (no Triton, no nvcc, no torch extension).

  native.py         build + load: NVRTC -> cubin -> CUDA driver API for csrc/*.cu, the system C++
                    compiler -> ctypes for csrc/*.cpp; both cached in Models/kernel_cache/
  cuda_controls.py  procedural controls as one CUDA kernel, bit-identical to the torch version
  cuda_conv.py      fused implicit-GEMM fp16 tensor-core convolution (csrc/conv.cu): packing, configs
  cuda_unet.py      the detail / relief flow samplers as one CUDA graph of fused convolutions
  cuda_detail2.py   EXP-2's Detail2 (MP layers folded, MeanFlow, gated experts) on the same kernels
                    (+ csrc/pixnorm.cu); generator2.NATIVE_DETAIL, model_id marker :kd1
  cuda_conv_fp8.py  e4m3 W8A8 twins of the conv kernels (csrc/conv_fp8.cu: mma m16n8k32 e4m3, fp32 accumulate)
  cuda_detail2_fp8.py  Detail2 at mixed FP8 / fp16 (a QAD student, train/train_qad.py); generator2.DETAIL_FP8
                    (ON since 2026-11-20, sm_89+), model_id marker :kd8; tune_fp8.py writes cuda_configs_fp8.json
  cuda_sr.py        SR-30 / SR-15 (models/sr.py) on the Detail2 sampler through a Detail2-shaped view; marker :ks1
  cuda_blend.py     the generator's window cross-fades as one launch (csrc/blend.cu), bit-identical to the torch loop
  cuda_river.py     the river pipeline's fixed-point loops one launch per step (csrc/river.cu), bit-identical
  hydro.py          drainage routing (priority-flood fill, D8, accumulation, seam cycles) in C++,
                    bit-identical to the numba / numpy reference
  cpu_sampler.py    the detail / relief samplers on the CPU (C++, AVX2 + FMA, fp32, Winograd)
  controls_cpu.py   procedural controls in C++ for the CPU (same op order; CPU expf differs by ~1 ulp)

The CPU kernels are EXPERIMENTAL and not yet optimized (minutes per tile). The game disables Daedalus
on machines that would run on them (DaedalusRequirements.java in stonebreak-game).

On by default; `TGM_KERNELS=0` (or `GenConfig.fused_kernels=False` for the samplers) runs the plain
torch / numpy paths everywhere. Controls and routing are exact, so they never change a tile; the
native samplers differ (closer to fp32) from the autocast samplers, so the generator marks its
model_id when they are in use (tile caches keep the two apart).
"""
from __future__ import annotations

from terrain_slm.kernels import native


def available(device) -> bool:
    """Native CUDA kernels usable on `device` (an sm_80+ CUDA device, NVRTC + libcuda, not disabled)."""
    return native.cuda_available(device)


def cpu_available(device) -> bool:
    """Native CPU kernels usable on `device` (the CPU, a C++ compiler, not disabled)."""
    import torch
    return torch.device(device).type == "cpu" and native.cpu_available()
