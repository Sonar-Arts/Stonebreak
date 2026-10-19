"""The project's own native kernels for DaedalusTGM-Exp inference (no Triton, no nvcc, no torch extension).

  native.py         build + load: NVRTC -> cubin -> CUDA driver API for csrc/*.cu, the system C++
                    compiler -> ctypes for csrc/*.cpp; both cached in Models/kernel_cache/
  cuda_controls.py  procedural controls as one CUDA kernel, bit-identical to the torch version
  cuda_conv.py      fused implicit-GEMM fp16 tensor-core convolution (csrc/conv.cu): packing, configs
  cuda_unet.py      the detail / relief flow samplers as one CUDA graph of fused convolutions
  hydro.py          drainage routing (priority-flood fill, D8, accumulation, seam cycles) in C++,
                    bit-identical to the numba / numpy reference
  cpu_sampler.py    the detail / relief samplers on the CPU (C++, AVX2 + FMA, fp32, Winograd)
  controls_cpu.py   procedural controls in C++ for the CPU (same op order; CPU expf differs by ~1 ulp)

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
