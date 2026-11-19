// Per-pixel channel normalisation for the EXP-2 detail network (models/mp.py `pixel_norm`, the start of every
// encoder block): x / (1e-4 + |x|_2 / sqrt(C)) over the channels of each NHWC pixel, in place on the fp16 raw
// tensor, plus its SiLU for the block's first convolution. fp32 sum of squares, one warp per pixel, 16 B chunks.
// Compiled by NVRTC (kernels/native.py).

#include "conv_common.cuh"

struct PixNormArgs {
    u16* raw;            // (P, C) NHWC fp16, normalised in place
    u16* out_silu;       // (P, C) fp16: SiLU of the normalised value
    int P, C;
    float inv_sqrt_c;    // sqrt(1 / C), as torch computes it (norm * sqrt(numel(norm) / numel(x)))
};

template <int CPL>   // 16 B chunks per lane: C <= CPL * 256
__global__ void __launch_bounds__(256) pixnorm_kernel(const PixNormArgs a) {
    const int lane = threadIdx.x & 31;
    const long long pix = (long long)blockIdx.x * (blockDim.x >> 5) + (threadIdx.x >> 5);
    if (pix >= a.P) return;
    const int nch = a.C >> 3;
    u16* base = a.raw + pix * a.C;
    uint4 q[CPL];
    float ss = 0.0f;
#pragma unroll
    for (int k = 0; k < CPL; ++k) {
        int c = lane + 32 * k;
        q[k] = c < nch ? *reinterpret_cast<const uint4*>(base + c * 8) : make_uint4(0, 0, 0, 0);
        u32 w[4] = {q[k].x, q[k].y, q[k].z, q[k].w};
#pragma unroll
        for (int e = 0; e < 4; ++e) {
            float lo = from_h((u16)(w[e] & 0xFFFF)), hi = from_h((u16)(w[e] >> 16));
            ss = fmaf(lo, lo, ss);
            ss = fmaf(hi, hi, ss);
        }
    }
#pragma unroll
    for (int o = 16; o > 0; o >>= 1) ss += __shfl_xor_sync(0xffffffffu, ss, o);
    const float norm = 1e-4f + sqrtf(ss) * a.inv_sqrt_c;
#pragma unroll
    for (int k = 0; k < CPL; ++k) {
        int c = lane + 32 * k;
        if (c >= nch) continue;
        u32 w[4] = {q[k].x, q[k].y, q[k].z, q[k].w};
        float v[8];
#pragma unroll
        for (int e = 0; e < 4; ++e) {
            v[2 * e] = from_h((u16)(w[e] & 0xFFFF)) / norm;
            v[2 * e + 1] = from_h((u16)(w[e] >> 16)) / norm;
        }
        *reinterpret_cast<uint4*>(base + c * 8) =
            make_uint4(pack_h2(v[0], v[1]), pack_h2(v[2], v[3]), pack_h2(v[4], v[5]), pack_h2(v[6], v[7]));
        *reinterpret_cast<uint4*>(a.out_silu + pix * a.C + c * 8) =
            make_uint4(pack_h2(silu(v[0]), silu(v[1])), pack_h2(silu(v[2]), silu(v[3])),
                       pack_h2(silu(v[4]), silu(v[5])), pack_h2(silu(v[6]), silu(v[7])));
    }
}
