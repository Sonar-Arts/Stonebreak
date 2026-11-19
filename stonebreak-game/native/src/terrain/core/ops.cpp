#include "core/ops.hpp"

namespace sbtg {
namespace {
const char kCoreSource[] =
#include "sbtg_core.cu.inc"
    ;
} // namespace
SBTG_MODULE("sbtg_core", kCoreSource);
} // namespace sbtg

namespace sbtg::ops {

namespace {
struct Copy2dArgs {
    const char* src;
    char* dst;
    long long P;
    int SH, SW, sr0, sc0;
    int DH, DW, dr0, dc0;
    int h, w, es;
};
struct RepeatArgs {
    const char* src;
    char* dst;
    long long P;
    int h, w, ky, kx, es;
};
struct AvgPoolArgs {
    const float* src;
    float* dst;
    long long P;
    int H, W, k;
};
struct MaxPoolArgs {
    const float* src;
    float* dst;
    long long* idx;
    long long P;
    int H, W, k;
};
struct BlurArgs {
    const float* src;
    float* dst;
    const float* k;
    long long P;
    int H, W, r, axis, fma;
};
struct BilinearArgs {
    const float* src;
    float* dst;
    long long P;
    int h, w, H, W;
    float sy, sx;
    int fm;
};
struct CastArgs {
    const void* src;
    void* dst;
    long long n;
    int from, to;
};

Tensor like_planes(Gpu& g, const Tensor& x, DType dt, int H, int W) {
    std::array<int64_t, 4> s = x.shape;
    s[static_cast<size_t>(x.ndim - 2)] = H;
    s[static_cast<size_t>(x.ndim - 1)] = W;
    return g.empty_shape(dt, x.ndim, s, false);
}
} // namespace

Tensor crop(Gpu& g, const Tensor& x, int r0, int c0, int h, int w) {
    long long P;
    int H, W;
    phw(x, P, H, W);
    if (r0 < 0 || c0 < 0 || h < 0 || w < 0 || r0 + h > H || c0 + w > W)
        throw std::out_of_range("crop [" + std::to_string(r0) + "+" + std::to_string(h) + ", " + std::to_string(c0) +
                                "+" + std::to_string(w) + "] of " + x.str());
    Tensor o = like_planes(g, x, x.dtype, h, w);
    Copy2dArgs a{static_cast<const char*>(x.ptr), static_cast<char*>(o.ptr), P, H, W, r0, c0, h, w, 0, 0, h, w,
                 static_cast<int>(dtype_size(x.dtype))};
    g.launch(g.kernel("sbtg_core", "sbtg_copy2d"), P * h * w, a);
    return o;
}

void paste(Gpu& g, Tensor& dst, const Tensor& src, int r0, int c0) {
    long long P, Ps;
    int H, W, h, w;
    phw(dst, P, H, W);
    phw(src, Ps, h, w);
    if (P != Ps || src.dtype != dst.dtype) throw std::invalid_argument("paste: " + src.str() + " into " + dst.str());
    if (r0 < 0 || c0 < 0 || r0 + h > H || c0 + w > W) throw std::out_of_range("paste outside " + dst.str());
    Copy2dArgs a{static_cast<const char*>(src.ptr), static_cast<char*>(dst.ptr), P, h, w, 0, 0, H, W, r0, c0, h, w,
                 static_cast<int>(dtype_size(src.dtype))};
    g.launch(g.kernel("sbtg_core", "sbtg_copy2d"), P * h * w, a);
}

Tensor repeat2d(Gpu& g, const Tensor& x, int ky, int kx) {
    long long P;
    int H, W;
    phw(x, P, H, W);
    Tensor o = like_planes(g, x, x.dtype, H * ky, W * kx);
    RepeatArgs a{static_cast<const char*>(x.ptr), static_cast<char*>(o.ptr), P, H, W, ky, kx,
                 static_cast<int>(dtype_size(x.dtype))};
    g.launch(g.kernel("sbtg_core", "sbtg_repeat2d"), static_cast<long long>(o.numel()), a);
    return o;
}

Tensor avg_pool(Gpu& g, const Tensor& x, int k) {
    long long P;
    int H, W;
    phw(x, P, H, W);
    x.check(DType::F32);
    Tensor o = like_planes(g, x, DType::F32, H / k, W / k);
    AvgPoolArgs a{x.f32(), o.f32(), P, H, W, k};
    g.launch(g.kernel("sbtg_core", "sbtg_avgpool"), static_cast<long long>(o.numel()), a);
    return o;
}

Tensor max_pool(Gpu& g, const Tensor& x, int k, Tensor* indices) {
    long long P;
    int H, W;
    phw(x, P, H, W);
    x.check(DType::F32);
    Tensor o = like_planes(g, x, DType::F32, H / k, W / k);
    long long* ip = nullptr;
    if (indices != nullptr) {
        *indices = like_planes(g, x, DType::I64, H / k, W / k);
        ip = reinterpret_cast<long long*>(indices->i64());
    }
    MaxPoolArgs a{x.f32(), o.f32(), ip, P, H, W, k};
    g.launch(g.kernel("sbtg_core", "sbtg_maxpool"), static_cast<long long>(o.numel()), a);
    return o;
}

Tensor blur(Gpu& g, const Tensor& x, const Tensor& taps, int fma) {
    long long P;
    int H, W;
    phw(x, P, H, W);
    x.check(DType::F32);
    const int r = static_cast<int>((taps.numel() - 1) / 2);
    if (r >= H || r >= W) throw std::invalid_argument("blur: radius " + std::to_string(r) + " >= size of " + x.str());
    Tensor tmp = g.empty_like(x), o = g.empty_like(x);
    BlurArgs a{x.f32(), tmp.f32(), taps.f32(), P, H, W, r, 0, fma};
    g.launch(g.kernel("sbtg_core", "sbtg_blur"), static_cast<long long>(x.numel()), a);
    BlurArgs b{tmp.f32(), o.f32(), taps.f32(), P, H, W, r, 1, fma};
    g.launch(g.kernel("sbtg_core", "sbtg_blur"), static_cast<long long>(x.numel()), b);
    return o;
}

Tensor bilinear(Gpu& g, const Tensor& x, int Ho, int Wo, double scale_factor) {
    long long P;
    int H, W;
    phw(x, P, H, W);
    x.check(DType::F32);
    float sy, sx;
    if (scale_factor > 0.0) {
        sy = sx = static_cast<float>(1.0 / scale_factor);
    } else {
        sy = static_cast<float>(H) / static_cast<float>(Ho);
        sx = static_cast<float>(W) / static_cast<float>(Wo);
    }
    Tensor o = like_planes(g, x, DType::F32, Ho, Wo);
    BilinearArgs a{x.f32(), o.f32(), P, H, W, Ho, Wo, sy, sx, 1};
    g.launch(g.kernel("sbtg_core", "sbtg_bilinear"), static_cast<long long>(o.numel()), a);
    return o;
}

Tensor cast(Gpu& g, const Tensor& x, DType to) {
    if (x.dtype == to) return x;
    Tensor o = g.empty_shape(to, x.ndim, x.shape, false);
    CastArgs a{x.ptr, o.ptr, x.numel(), static_cast<int>(x.dtype), static_cast<int>(to)};
    g.launch(g.kernel("sbtg_core", "sbtg_cast"), x.numel(), a);
    return o;
}

Tensor stack(Gpu& g, const std::vector<Tensor>& xs) {
    if (xs.empty()) throw std::invalid_argument("stack of nothing");
    const Tensor& f = xs[0];
    if (f.ndim > 3) throw std::invalid_argument("stack: inputs must have <= 3 dims");
    std::array<int64_t, 4> s{static_cast<int64_t>(xs.size()), 1, 1, 1};
    for (int i = 0; i < f.ndim; ++i) s[static_cast<size_t>(i + 1)] = f.shape[static_cast<size_t>(i)];
    Tensor o = g.empty_shape(f.dtype, f.ndim + 1, s, false);
    for (size_t k = 0; k < xs.size(); ++k) {
        if (xs[k].bytes() != f.bytes() || xs[k].dtype != f.dtype) throw std::invalid_argument("stack: shapes differ");
        Tensor dst = o[static_cast<int64_t>(k)];
        g.copy(dst, xs[k]);
    }
    return o;
}

} // namespace sbtg::ops
