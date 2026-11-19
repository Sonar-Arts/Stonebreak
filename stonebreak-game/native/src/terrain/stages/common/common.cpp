// Small generator methods shared by several stages: soft_cap_peaks, regional_elevation.
#include "stages/common/common.hpp"

namespace sbtg {

namespace {
const char kSrc[] =
#include "common.cu.inc"
    ;
SBTG_MODULE("common", kSrc);

struct SoftCapArgs {
    const float* src;
    float* dst;
    long long n;
    float knee, inv_span;
};

// generator.regional_elevation(i1, j1, i2, j2): planner height blurred by LAPSE_REGIONAL_SIGMA_CELLS, bilinear to pixels.
Fields regional_elevation(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "regional_elevation");
    const int64_t i1 = a.i[0], j1 = a.i[1], i2 = a.i[2], j2 = a.i[3];
    const int64_t CP = g.manifest["constants"]["CP"].i64();
    const double sigma = g.c("LAPSE_REGIONAL_SIGMA_CELLS");
    const int64_t m = static_cast<int64_t>(std::ceil(3.0 * sigma)) + 1;
    const int64_t ci0 = floordiv(i1, CP) - m, cj0 = floordiv(j1, CP) - m;
    const int64_t hc = floordiv(i2 - 1, CP) + 1 + m - ci0, wc = floordiv(j2 - 1, CP) + 1 + m - cj0;
    Tensor cells = g.call("_planner_cells", {{ci0, cj0, hc, wc}, {}, {}}).one()[0];
    Tensor reg = ops::blur(g.gpu, soft_cap_peaks(g, cells), g.taps(sigma));
    Tensor up = ops::bilinear(g.gpu, reg, static_cast<int>(hc * CP), static_cast<int>(wc * CP));
    return Fields::of(ops::crop(g.gpu, up, static_cast<int>(i1 - ci0 * CP), static_cast<int>(j1 - cj0 * CP),
                                static_cast<int>(i2 - i1), static_cast<int>(j2 - j1)));
}
SBTG_METHOD("regional_elevation", regional_elevation);

} // namespace

Tensor soft_cap_peaks(Gen& g, const Tensor& e) {
    e.check(DType::F32);
    Tensor o = g.gpu.empty_like(e);
    SoftCapArgs s{e.f32(), o.f32(), e.numel(), g.cf("PEAK_KNEE_M"), 1.0f / g.cf("PEAK_SPAN_M")};
    g.gpu.launch(g.gpu.kernel("common", "sbtg_soft_cap"), e.numel(), s);
    return o;
}

} // namespace sbtg
