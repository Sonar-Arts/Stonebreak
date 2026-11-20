// Stage "controls" (part 2): the climate node -- generator2.climate_cells (CANONICAL_CLIMATE), _climate_grid,
// _climate_window, _continentality, _land_lattice.
#include "stages/controls/controls.hpp"

#include <cstring>

namespace sbtg {
namespace {

using controls::CfgRef;

struct PointsArgs {
    float* ci;
    float* cj;
    int M, mode;
    float off_i, off_j, s, g;
};
struct Gt0Args {
    const float* src;
    float* dst;
    long long n;
};
struct PoolArgs {
    const float* trend;
    const float* belt;
    const float* wu;
    const float* wv;
    float* grid;
    float* inner;
    int m, k, n, e;
    float eps, inv1000;
};
struct EdtColArgs {
    const float* grid;
    int* g;
    int m, n, e;
};
struct CoastArgs {
    const int* g;
    float* coast;
    int n;
    double grid_km, div;
};
struct ContArgs {
    const float* blurred;
    float* out;
    int n, win, reach;
    float inv_step, inv_n1;
};
struct InputArgs {
    const float* grid;
    const float* inner;
    const float* l250;
    const float* l1000;
    const float* l2500;
    const float* coast;
    float* x;
    int m, n, e, n_near, n_far;
    float near_km[8], far_km[8];
    float wind_scale, lw0, lw1;
    float eps, calm_inv, inv_km, inv_m1, inv_near, inv_far, inv5;
    float forcing0, forcing1;
};
struct FadeArgs {
    float* w2;
    int win, ramp;
    float inv_ramp, half_pi;
};
constexpr int kBlendMaxW = 64;
struct BlendArgs {   // csrc/blend.cu (kernels/cuda_blend.py _Args)
    float* out;
    const float* fade;
    const float* win[kBlendMaxW];
    int C, H, W, EH, EW;
    int n, accumulate;
    int a0[kBlendMaxW];
    int b0[kBlendMaxW];
};
struct ClimateArgs {   // cuda/sbtg_cells.cu sbtg_climate_cells
    const float* y;
    float* outs[7];
    long long ci0, cj0, gi0, gj0;
    int hc, wc, gh, gw;
    double inv_g;
    float mean[7], std_[7];
};

// Python float `a // b` (CPython float_floor_div).
double py_floordiv(double vx, double wx) {
    double mod = std::fmod(vx, wx);
    double div = (vx - mod) / wx;
    if (mod != 0.0) {
        if ((wx < 0) != (mod < 0)) div -= 1.0;
    }
    double fl;
    if (div != 0.0) {
        fl = std::floor(div);
        if (div - fl > 0.5) fl += 1.0;
    } else {
        fl = std::copysign(0.0, vx / wx);
    }
    return fl;
}

// (lattice step, reach, n) of _continentality for a blur of `km`
void cont_geometry(const controls::Cfg& c, double km, double& sigma, int64_t& step, int64_t& reach, int64_t& n) {
    sigma = km / c.grid_km;
    step = std::max<int64_t>(1, static_cast<int64_t>(py_floordiv(sigma, 3.0)));
    reach = static_cast<int64_t>(std::ceil(3 * sigma / static_cast<double>(step))) + 1;
    n = c.win / step + 2 * reach + 1;
}

// ------------------------------------------------------------------------------------------------ _land_lattice
Fields land_lattice(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "_land_lattice");
    const int64_t gi0 = a.i[0], gj0 = a.i[1], n = a.i[2], step = a.i[3];
    CfgRef c = controls::cfg(g);
    Tensor ci = g.gpu.empty(DType::F32, {n, n}), cj = g.gpu.empty(DType::F32, {n, n});
    PointsArgs p{ci.f32(), cj.f32(), static_cast<int>(n), 1, static_cast<float>(gi0), static_cast<float>(gj0),
                 static_cast<float>(step), c->grid_cells_f};
    g.gpu.launch(g.gpu.kernel("controls", "controls_points"), n * n, p);
    Tensor trend = g.call("_controls_at", {{}, {}, {ci, cj}})["trend"];
    Tensor out = g.gpu.empty(DType::F32, {n, n});
    Gt0Args q{trend.f32(), out.f32(), n * n};
    g.gpu.launch(g.gpu.kernel("controls", "controls_gt0"), n * n, q);
    return Fields::of(out);
}
SBTG_METHOD("_land_lattice", land_lattice);

// ------------------------------------------------------------------------------------------------ _continentality
Fields continentality(Gen& g, const Args& a) {
    need(a, 2, 1, 0, "_continentality");
    const int64_t a0 = a.i[0], b0 = a.i[1];
    CfgRef c = controls::cfg(g);
    double sigma;
    int64_t step, reach, n;
    cont_geometry(*c, a.f[0], sigma, step, reach, n);
    Tensor land = g.call("_land_lattice", {{a0 - reach * step, b0 - reach * step, n, step}, {}, {}}).one();
    Tensor blurred = ops::blur(g.gpu, land, g.taps(sigma / static_cast<double>(step)));
    const int64_t W = c->win;
    Tensor out = g.gpu.empty(DType::F32, {W, W});
    ContArgs p{blurred.f32(), out.f32(), static_cast<int>(n), static_cast<int>(W), static_cast<int>(reach),
               static_cast<float>(1.0 / static_cast<double>(step)), static_cast<float>(1.0 / static_cast<double>(n - 1))};
    g.gpu.launch(g.gpu.kernel("controls", "controls_cont_sample"), W * W, p);
    return Fields::of(out);
}
SBTG_METHOD("_continentality", continentality);

// ------------------------------------------------------------------------------------------------ _climate_window
Fields climate_window_run(Gen& g, int64_t wi, int64_t wj) {
    CfgRef c = controls::cfg(g);
    const int64_t a0 = wi * c->core - c->apron, b0 = wj * c->core - c->apron;
    const int n = c->win, k = c->sub, e = c->fetch;
    const int m = n + 2 * e, M = m * k;
    Gpu& gpu = g.gpu;
    // controls at the window's sub-points (+ the fetch margin)
    Tensor ci = gpu.empty(DType::F32, {M, M}), cj = gpu.empty(DType::F32, {M, M});
    PointsArgs pts{ci.f32(), cj.f32(), M, 0, static_cast<float>(a0 - e), static_cast<float>(b0 - e),
                   static_cast<float>(1.0 / k), c->grid_cells_f};
    gpu.launch(gpu.kernel("controls", "controls_points"), static_cast<long long>(M) * M, pts);
    Fields ctl = g.call("_controls_at", {{}, {}, {ci, cj}});
    // pooled grid (land_x, elev_x) and the inner pooled belt / wind
    Tensor grid = gpu.empty(DType::F32, {2, m, m}), inner = gpu.empty(DType::F32, {3, n, n});
    PoolArgs pl{ctl["trend"].f32(), ctl["belt"].f32(), ctl["wind_u"].f32(), ctl["wind_v"].f32(), grid.f32(),
                inner.f32(), m, k, n, e, static_cast<float>(1e-3), static_cast<float>(1.0 / 1000.0)};
    gpu.launch(gpu.kernel("controls", "controls_cw_pool"), static_cast<long long>(m) * m, pl);
    // coast: signed EDT distance (scipy, fp64)
    Tensor gb = gpu.empty(DType::I32, {2, n, n});
    EdtColArgs ec{grid.f32(), gb.i32(), m, n, e};
    gpu.launch(gpu.kernel("controls", "controls_edt_cols"), 2LL * n * n, ec);
    Tensor coast = gpu.empty(DType::F32, {n, n});
    CoastArgs ca{gb.i32(), coast.f32(), n, c->grid_km, 50.0};
    gpu.launch(gpu.kernel("controls", "controls_edt_coast"), static_cast<long long>(n) * n, ca);
    // continentality at 250 / 1000 / 2500 km
    Tensor lk[3];
    for (int q = 0; q < 3; ++q) lk[q] = g.call("_continentality", {{a0, b0}, {c->kms[q]}, {}}).one();
    // the node's input stack and the network
    Tensor x = gpu.empty(DType::F32, {1, 15, n, n});
    InputArgs in{};
    in.grid = grid.f32();
    in.inner = inner.f32();
    in.l250 = lk[0].f32();
    in.l1000 = lk[1].f32();
    in.l2500 = lk[2].f32();
    in.coast = coast.f32();
    in.x = x.f32();
    in.m = m;
    in.n = n;
    in.e = e;
    in.n_near = c->n_near;
    in.n_far = c->n_far;
    for (int q = 0; q < 8; ++q) {
        in.near_km[q] = c->near_km[q];
        in.far_km[q] = c->far_km[q];
    }
    in.wind_scale = c->wind_scale;
    in.lw0 = c->lw0;
    in.lw1 = c->lw1;
    in.eps = c->up_eps;
    in.calm_inv = c->calm_inv;
    in.inv_km = c->inv_grid_km;
    in.inv_m1 = static_cast<float>(1.0 / (m - 1));
    in.inv_near = c->inv_near;
    in.inv_far = c->inv_far;
    in.inv5 = static_cast<float>(1.0 / 5.0);
    in.forcing0 = c->forcing0;
    in.forcing1 = c->forcing1;
    gpu.launch(gpu.kernel("controls", "controls_cw_input"), static_cast<long long>(n) * n, in);
    ci_model* model = g.model("climate");
    if (model == nullptr) throw std::logic_error("_climate_window: no engine model 'climate'");
    Tensor out = gpu.empty(DType::F32, {1, c->n_out, n, n});
    ci_request req{};
    req.batch = 1;
    req.flags = CI_RUN_STREAM;
    req.inputs[0] = x.ptr;
    req.outputs[0] = out.ptr;
    req.stream = gpu.stream;
    gpu.check(gpu.ci.run(model, &req), "ci_run(climate)");
    return Fields::of(out.view({c->n_out, n, n}));
}

Fields climate_window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_climate_window");
    const int64_t wi = a.i[0], wj = a.i[1];
    return g.cache("_climate_windows", 128).get_or({g.st.seed, wi, wj}, [&] {
        ++g.counters["climate_windows"];
        return climate_window_run(g, wi, wj);
    });
}
SBTG_METHOD("_climate_window", climate_window);

// ------------------------------------------------------------------------------------------------ _climate_grid
Tensor fade(Gen& g, const controls::Cfg& c) {
    return g.cache("controls.fade", 1).get_or({g.st.seed}, [&] {
        Tensor w2 = g.gpu.empty(DType::F32, {c.win, c.win});
        const int ramp = 2 * c.apron;
        FadeArgs p{w2.f32(), c.win, ramp, static_cast<float>(1.0 / ramp), static_cast<float>(0.5 * M_PI)};
        g.gpu.launch(g.gpu.kernel("controls", "controls_fade"), static_cast<long long>(c.win) * c.win, p);
        return Fields::of(w2);
    }).one();
}

Fields climate_grid(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "_climate_grid");
    const int64_t gi0 = a.i[0], gj0 = a.i[1], gh = a.i[2], gw = a.i[3];
    CfgRef c = controls::cfg(g);
    const int64_t W = c->win, A = c->apron, Cr = c->core;
    Tensor w2 = fade(g, *c);
    struct Item {
        Tensor t;
        int a0, b0;
    };
    std::vector<Item> items;
    for (int64_t wi = floordiv(gi0 - W + A, Cr); wi <= floordiv(gi0 + gh - 1 + A, Cr); ++wi)
        for (int64_t wj = floordiv(gj0 - W + A, Cr); wj <= floordiv(gj0 + gw - 1 + A, Cr); ++wj) {
            const int64_t a0 = wi * Cr - A, b0 = wj * Cr - A;
            const int64_t r0 = std::max(gi0, a0), r1 = std::min(gi0 + gh, a0 + W);
            const int64_t s0 = std::max(gj0, b0), s1 = std::min(gj0 + gw, b0 + W);
            if (r0 >= r1 || s0 >= s1) continue;
            items.push_back({g.call("_climate_window", {{wi, wj}, {}, {}}).one(), static_cast<int>(a0 - gi0),
                             static_cast<int>(b0 - gj0)});
        }
    const int C = c->n_out;
    Tensor out = g.gpu.empty(DType::F32, {C, gh, gw});
    if (items.empty()) {
        g.gpu.memset0(out);
        return Fields::of(out);
    }
    for (size_t start = 0; start < items.size(); start += kBlendMaxW) {
        BlendArgs b;
        std::memset(&b, 0, sizeof b);
        b.out = out.f32();
        b.fade = w2.f32();
        b.C = C;
        b.H = static_cast<int>(gh);
        b.W = static_cast<int>(gw);
        b.EH = b.EW = static_cast<int>(W);
        const size_t cnt = std::min<size_t>(kBlendMaxW, items.size() - start);
        b.n = static_cast<int>(cnt);
        b.accumulate = start > 0 ? 1 : 0;
        for (size_t q = 0; q < cnt; ++q) {
            b.win[q] = items[start + q].t.f32();
            b.a0[q] = items[start + q].a0;
            b.b0[q] = items[start + q].b0;
        }
        g.gpu.launch(g.gpu.kernel("csrc_blend", "blend_kernel"), static_cast<long long>(C) * gh * gw, b, 256);
    }
    return Fields::of(out);
}
SBTG_METHOD("_climate_grid", climate_grid);

// ------------------------------------------------------------------------------------------------ climate_cells
Fields climate_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "climate_cells");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    CfgRef c = controls::cfg(g);
    if (!c->canonical) throw std::logic_error("climate_cells: only the CANONICAL_CLIMATE sampling is native");
    const double gc = c->grid_cells;
    const int64_t gi0 = static_cast<int64_t>(std::floor(static_cast<double>(ci0) / gc)) - 1;
    const int64_t gj0 = static_cast<int64_t>(std::floor(static_cast<double>(cj0) / gc)) - 1;
    const int64_t gi1 = static_cast<int64_t>(std::floor(static_cast<double>(ci0 + hc) / gc)) + 2;
    const int64_t gj1 = static_cast<int64_t>(std::floor(static_cast<double>(cj0 + wc) / gc)) + 2;
    const int64_t gh = gi1 - gi0, gw = gj1 - gj0;
    Tensor y = g.call("_climate_grid", {{gi0, gj0, gh, gw}, {}, {}}).one();
    Tensor out = g.gpu.empty(DType::F32, {7, hc, wc});
    if (hc * wc > 0) {
        ClimateArgs p{};
        p.y = y.f32();
        for (int q = 0; q < 7; ++q) {
            p.outs[q] = out[q].f32();
            p.mean[q] = c->mean[q];
            p.std_[q] = c->stdv[q];
        }
        p.ci0 = ci0;
        p.cj0 = cj0;
        p.gi0 = gi0;
        p.gj0 = gj0;
        p.hc = static_cast<int>(hc);
        p.wc = static_cast<int>(wc);
        p.gh = static_cast<int>(gh);
        p.gw = static_cast<int>(gw);
        p.inv_g = 1.0 / gc;
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_climate_cells"), hc * wc, p);
    }
    Fields r;
    const char* names[7] = {"t0", "tseason", "precip", "pcv", "aridity", "soil", "snow"};
    for (int q = 0; q < 7; ++q) r.set(names[q], out[q]);
    return r;
}
SBTG_METHOD("climate_cells", climate_cells);

} // namespace
} // namespace sbtg
