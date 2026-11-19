// Host side of the core kernels (core/cuda/sbtg_core.cu): torch-exact building blocks every stage uses.
// Shapes follow torch: planes are the leading dims flattened (P), the last two are (H, W).
#pragma once

#include "core/gpu.hpp"

namespace sbtg::ops {

// (P, H, W) view sizes of a tensor: P = product of the leading dims (1 for a 2-D tensor).
inline void phw(const Tensor& t, long long& P, int& H, int& W) {
    if (t.ndim < 2) throw std::invalid_argument("expected >= 2 dims, got " + t.str());
    H = static_cast<int>(t.size(-2));
    W = static_cast<int>(t.size(-1));
    P = t.numel() / (static_cast<long long>(H) * W);
}

// x[..., r0:r0+h, c0:c0+w] as a new contiguous tensor (bounds checked).
Tensor crop(Gpu& g, const Tensor& x, int r0, int c0, int h, int w);
// dst[..., r0:r0+h, c0:c0+w] = src (src (..., h, w)).
void paste(Gpu& g, Tensor& dst, const Tensor& src, int r0, int c0);
// x.repeat_interleave(ky, -2).repeat_interleave(kx, -1)
Tensor repeat2d(Gpu& g, const Tensor& x, int ky, int kx);
// F.avg_pool2d(x, k) / F.max_pool2d(x, k[, return_indices]) over the last two dims (float32).
Tensor avg_pool(Gpu& g, const Tensor& x, int k);
Tensor max_pool(Gpu& g, const Tensor& x, int k, Tensor* indices = nullptr);
// data/descriptors.blur(x, sigma): taps = the manifest's gaussian_kernel1d(sigma) (Gen::taps), FMA accumulation.
Tensor blur(Gpu& g, const Tensor& x, const Tensor& taps, int fma = 1);
// F.interpolate(x, size=(H, W) or scale_factor=f, mode="bilinear", align_corners=False). Pass scale_factor > 0 to
// mirror a scale_factor= call (torch then uses 1/f as the source scale), else size= semantics (in/out in fp32).
Tensor bilinear(Gpu& g, const Tensor& x, int H, int W, double scale_factor = 0.0);
// x.to(dtype) (float -> int truncates toward zero, like torch).
Tensor cast(Gpu& g, const Tensor& x, DType to);
// Stacks same-shape tensors along a new leading dim.
Tensor stack(Gpu& g, const std::vector<Tensor>& xs);

} // namespace sbtg::ops
