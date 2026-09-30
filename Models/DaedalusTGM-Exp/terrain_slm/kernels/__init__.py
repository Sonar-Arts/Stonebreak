"""Custom GPU kernels (Triton) for DaedalusTGM-Exp inference.

  controls.py   procedural controls as one kernel, bit-identical to the torch version
  conv.py       fused implicit-GEMM convolution (prologue/epilogue fusion of the UNet glue)
  unet.py       the detail / relief flow samplers as one CUDA graph of fused convolutions

On by default on CUDA devices with Triton; `TGM_KERNELS=0` (or `GenConfig.fused_kernels=False`)
runs the plain torch path everywhere. The controls kernel is exact, so it never changes a tile;
the fused samplers are numerically close to (not identical with) the autocast samplers, so the
generator marks its model_id when they are in use (tile caches keep the two apart).
"""
from __future__ import annotations

import os

import torch


def available(device) -> bool:
    """Triton kernels usable on `device`: a CUDA device, Triton importable, not disabled by env."""
    if os.getenv("TGM_KERNELS", "1").strip().lower() in ("0", "false", "off", "no"):
        return False
    dev = torch.device(device)
    if dev.type != "cuda" or not torch.cuda.is_available():
        return False
    try:
        import triton  # noqa: F401
    except ImportError:
        return False
    # bf16 tensor-core mma (tl.dot on bf16) needs sm_80+.
    return torch.cuda.get_device_capability(dev) >= (8, 0)
