// river/scale.py BlockScale in C++: metres <-> blocks through the current world's height curve.
//
//   scale::BlockScale s = scale::get(g);              // g.call("block_scale"): the world in g.st.raw["world"]
//   Tensor b = scale::to_blocks(g, s, metres);        // BlockScale.to_blocks (fp32, torch-exact)
//   Tensor m = scale::to_metres(g, s, blocks, 0.5f);  // BlockScale.to_metres(blocks.float() + 0.5)
//   Tensor r = scale::rate(g, s, metres);             // BlockScale.rate
//
// Header-only (kernel source included), so any stage can use it without linking stages/tiles; the method
// "block_scale" itself is registered by stages/tiles/block_scale.cpp (else it goes to the Python host).
// `_interp` = torch: x.clamp(xp[0], xp[-1]); i = searchsorted(xp, x).clamp(1, N - 1);
//                    f0 + (f1 - f0) * (xc - x0) / (x1 - x0)     (fp32, one rounding per op, in that order)
#pragma once

#include "gen/gen.hpp"

namespace sbtg::scale {

inline const char kScaleSource[] = R"SBTG(
#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn
// torch.clamp(x, lo, hi) (NaN propagates)
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) {
    if (x != x) return x;
    return x < lo ? lo : (x > hi ? hi : x);
}
__device__ __forceinline__ float interp(float x, const float* xp, const float* fp, int N) {
    float xc = t_clamp(x, xp[0], xp[N - 1]);
    // torch searchsorted (left): first i with !(xp[i] < x) ... torch's lower_bound: !(mid >= val) -> go right
    int lo = 0, hi = N;
    while (lo < hi) {
        int mid = lo + ((hi - lo) >> 1);
        if (!(xp[mid] >= xc)) lo = mid + 1; else hi = mid;
    }
    int i = lo < 1 ? 1 : (lo > N - 1 ? N - 1 : lo);
    float x0 = xp[i - 1], x1 = xp[i], f0 = fp[i - 1], f1 = fp[i];
    return ADD(f0, DIV(MUL(SUB(f1, f0), SUB(xc, x0)), SUB(x1, x0)));
}
struct InterpArgs {
    const void* x;
    float* y;
    const float* xp;
    const float* fp;
    const float* xp2;    // rate: the inverse table is not needed; to_blocks twice
    long long n;
    int N, dtype, mode;  // dtype: 0 f32, 2 i32, 3 i64, 6 i16; mode 0 interp(x + pre), 1 rate
    float pre;
};
__device__ __forceinline__ float load(const void* p, long long i, int dt) {
    switch (dt) {
    case 2: return (float)((const int*)p)[i];
    case 3: return (float)((const long long*)p)[i];
    case 6: return (float)((const short*)p)[i];
    default: return ((const float*)p)[i];
    }
}
extern "C" __global__ void sbtg_scale_interp(const InterpArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= a.n) return;
    float x = load(a.x, i, a.dtype);
    if (a.mode == 0) {
        if (a.pre != 0.0f) x = ADD(x, a.pre);
        a.y[i] = interp(x, a.xp, a.fp, a.N);
        return;
    }
    // rate: 1.0 / (to_blocks(e + 1.0) - to_blocks(e - 1.0)).clamp_min(1e-4) * 2.0
    float d = SUB(interp(ADD(x, 1.0f), a.xp, a.fp, a.N), interp(SUB(x, 1.0f), a.xp, a.fp, a.N));
    if (!(d != d) && d < 1e-4f) d = 1e-4f;
    a.y[i] = MUL(DIV(1.0f, d), 2.0f);
}
)SBTG";
inline ::sbtg::ModuleRegistry kScaleModule{"tiles_scale", kScaleSource};

struct BlockScale {
    Tensor elev, blocks;        // (N,) fp32 on the device, increasing
    double horizontal_m = 60.0;
    int64_t sea_level = 64, world_height = 256, min_y = 0;
    double px_per_block() const { return horizontal_m / 30.0; }
};

namespace detail {
struct InterpArgs {
    const void* x;
    float* y;
    const float* xp;
    const float* fp;
    const float* xp2;
    long long n;
    int N, dtype, mode;
    float pre;
};
inline double scalar(Gpu& gpu, const Tensor& t) {
    Tensor h = t.host ? t : gpu.to_host(t);
    if (!t.host) gpu.sync();
    switch (h.dtype) {
    case DType::F64: return h.data<double>()[0];
    case DType::F32: return h.data<float>()[0];
    case DType::I64: return static_cast<double>(h.data<int64_t>()[0]);
    case DType::I32: return h.data<int32_t>()[0];
    default: throw std::invalid_argument("block_scale scalar of dtype " + std::string(dtype_name(h.dtype)));
    }
}
inline Tensor run(Gen& g, const Tensor& x, const Tensor& xp, const Tensor& fp, int mode, float pre) {
    int dt = 0;
    switch (x.dtype) {
    case DType::F32: dt = 0; break;
    case DType::I32: dt = 2; break;
    case DType::I64: dt = 3; break;
    case DType::I16: dt = 6; break;
    default: throw std::invalid_argument("scale interp of " + x.str());
    }
    Tensor y = g.gpu.empty_shape(DType::F32, x.ndim, x.shape);
    InterpArgs a{x.ptr, y.f32(), xp.f32(), fp.f32(), nullptr, x.numel(), static_cast<int>(xp.numel()), dt, mode, pre};
    g.gpu.launch(g.gpu.kernel("tiles_scale", "sbtg_scale_interp"), x.numel(), a);
    return y;
}
} // namespace detail

// The current world's scale (method "block_scale": native in stages/tiles, else the Python host's gen.scale).
inline BlockScale get(Gen& g) {
    Fields f = g.call("block_scale", {});
    BlockScale s;
    s.elev = f["elev"];
    s.blocks = f["blocks"];
    if (g.st.raw.has("world")) {   // the scalars without a device readback: the world config they come from
        const Json& w = g.st.raw["world"];
        s.horizontal_m = w["horizontal_m"].num();
        s.sea_level = w["sea_level"].i64();
        s.world_height = w["world_height"].i64();
        s.min_y = w.i64_or("min_y", 0);
        return s;
    }
    s.horizontal_m = detail::scalar(g.gpu, f["horizontal_m"]);
    s.sea_level = static_cast<int64_t>(detail::scalar(g.gpu, f["sea_level"]));
    s.world_height = static_cast<int64_t>(detail::scalar(g.gpu, f["world_height"]));
    s.min_y = static_cast<int64_t>(detail::scalar(g.gpu, f["min_y"]));
    return s;
}

// BlockScale.to_blocks(e) (e fp32).
inline Tensor to_blocks(Gen& g, const BlockScale& s, const Tensor& e) { return detail::run(g, e, s.elev, s.blocks, 0, 0.0f); }
// BlockScale.to_metres(y.float() + pre) (y any int / fp32 tensor; pre = 0: plain to_metres(y)).
inline Tensor to_metres(Gen& g, const BlockScale& s, const Tensor& y, float pre = 0.0f) {
    return detail::run(g, y, s.blocks, s.elev, 0, pre);
}
// BlockScale.rate(e).
inline Tensor rate(Gen& g, const BlockScale& s, const Tensor& e) { return detail::run(g, e, s.elev, s.blocks, 1, 0.0f); }

} // namespace sbtg::scale
