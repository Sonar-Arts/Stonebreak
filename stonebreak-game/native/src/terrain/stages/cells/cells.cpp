// Stage "cells": the generator's cell-grid methods (world/generator.py WorldGenerator + world/generator2.py
// WorldGenerator2 overrides), bit for bit:
//
//   _relief_window, trend                      relief windows (engine model "relief") cross-faded + landforms
//   _planner_input, _window, _planner_cells    planner windows (engine model "planner", batch PLANNER_BATCH padded)
//   _drainage_inputs, _route_inputs, _route_window, _stitched_route, _hydro_pred, _hydro_window, drainage
//                                              routed drainage (kernels/csrc/hydro.cpp on host worker threads, the
//                                              hydro sidecar on engine model "hydro")
//   cells, overview_cells, swamp_relief_cells  the per-request cell views
//   SR.cell_fields, G2._shelf_remap            module functions other stages call
//
// Every other method (controls, _layout, B.swamp_candidates, and these ones among themselves) is reached through
// g.call, so each one can run natively or in Python on its own. Constants come from the manifest
// (export/native_manifest_cells.py), read from the live Python modules.
#include "stages/cells/hydro_host.hpp"
#include "stages/common/common.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <set>
#include <tuple>

namespace sbtg {

namespace {
const char kSrc[] =
#include "cells.cu.inc"
    ;
SBTG_MODULE("cells", kSrc);
// kernels/csrc/blend.cu (the cross-fade kernel kernels/cuda_blend.py launches; the same file, embedded by the build).
// Registering a label twice (another stage) is harmless: same source.
const char kBlendSrc[] =
#include "csrc_blend.cu.inc"
    ;
SBTG_MODULE("csrc_blend", kBlendSrc);

using cells::Route;
using Key2 = std::pair<int64_t, int64_t>;
constexpr double kPi = 3.141592653589793;   // math.pi

// ---------------------------------------------------------------------------------------------- kernel args
// module "cells" (stages/cells/cuda/cells.cu)
struct OuterArgs {
    const float* a;
    const float* b;
    float* out;
    int H, W;
};
struct AddArgs {
    const float* a;
    const float* b;
    float* out;
    long long n;
};
struct PlannerInputArgs {
    const float* trend;
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    const float* wild;
    float* x;
    long long hw;
    int n_in, c_trend, c_t0, c_tseason, c_precip, c_pcv, c_wild, c_has_trend, c_has_climate, c_has_wild;
    float inv_height, wild_off, inv_wild, inv8, inv300, log800, inv09, inv30;
};
struct NoiseArgs {
    float* out;
    long long i0, j0;
    int h, w;
    uint32_t base;
    float sqrt3;
};
struct RouteInArgs {
    const float* height;
    const float* blurred;
    float* hz;
    float* flat;
    long long n;
    float K, inv_m;
};
struct DrainArgs {
    const double* acc;
    const long long* d8;
    float* out;
    int n;
    float thr;
};
struct HydroInArgs {
    const float* h;
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    float* x;
    int n;
    float inv_height, inv_slope, inv8, inv300, log800, inv09, inv30;
};
struct HydroPostArgs {
    const float* o;
    float* out;
    long long hw;
    int c_logacc, c_river, c_d8, n_d8;
    float logacc_scale;
};
struct OwnedArgs {
    const float* win;
    long long* out;
    int W, EXT;
    int r0, r1, s0, s1;
    int dr, dc;
};
struct ShelfArgs {
    const float* h;
    float* out;
    long long n;
    float k, dm, inv_dm, one_minus_k, k0_minus_k, d0, inv_d0;
    int keep_on;
};
struct SwampWeightArgs {
    const float* blurred;
    float* out;
    long long n;
    float lo, inv_span;
};
struct SwampTableArgs {
    const float* local;
    const float* broad;
    float* out;
    long long n;
    int passes, stairs_on;
    float inv_stair, stair, two_pi, inv_two_pi, keep, floor_m;
};
struct LapseArgs {
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    const float* reg;
    float* out;
    long long n;
    float lapse;
};
struct DistPassArgs {
    const float* src;
    const unsigned char* land;
    float* dst;
    int H, W, cap, axis;
    float big;
};
struct DistFinishArgs {
    const float* d2;
    float* out;
    long long n;
    float cap;
};
struct SeaMaskArgs {
    const float* cells;
    unsigned char* sub;
    unsigned char* land;
    long long n;
};
struct SeaSeedArgs2 {
    const float* trend;
    const float* open_land;
    const float* off_dist;
    const unsigned char* sub;
    unsigned char* seeds;
    long long n;
    float sea_trend, sea_land, min_cells;
};
struct GrowArgs {
    const unsigned char* src;
    const unsigned char* through;
    unsigned char* dst;
    int H, W, r;
};
struct SeaOutArgs {
    const float* dist;
    const unsigned char* sub;
    const unsigned char* coast;
    float* dist_m;
    unsigned char* lake;
    int W, m, hc, wc;
    float cell_m;
};
struct SeaPxArgs {
    const float* d;
    const unsigned char* lake;
    float* od;
    float* ol;
    int h, w, CW;
    long long i1, j1, ci0, cj0;
    double inv_ppc;
};
struct ProfileArgs {
    const float* h;
    const float* dist;
    const float* lake;
    float* out;
    long long n, hw;
    float m0, slope, lake_m0, lake_slope;
    float k, dm, inv_dm, one_minus_k, k0_minus_k, d0, inv_d0;
    int keep_on;
};
// module "sbtg_cells" (cuda/sbtg_cells.cu: the phase-2 planner post-processing and relief window kernels)
constexpr int kMaxW = 32;
struct GatherArgs {
    const float* src;
    float* dst;
    int C, H, W, win, n;
    int offs[2 * kMaxW];
};
struct PostArgs {
    const float* o;
    float* outs[kMaxW];
    float* h0;
    const float* desc_mean;
    const float* desc_std;
    int n, win;
    float height_scale, logacc_scale;
};
struct HillArgs {
    const float* h0;
    const float* T;
    const float* bh0;
    const float* bT;
    float* h1;
    long long count;
    float ht, inv60;
    int on;
};
struct SeaArgs {
    const float* h1;
    const float* T;
    const float* bh1;
    float* outs[kMaxW];
    int n, win;
    int sea_detail;
    float inv150, max_m, floor_m;
    float k, dm, inv_dm, one_minus_k, k0_minus_k, d0, inv_d0;
    int keep_on;
};
struct ReliefCondArgs {
    const float* smooth_full;
    const float* wild;
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    float* cond;
    float* noise;
    int N, n, m;
    long long i0, j0;
    uint32_t base;
    float sqrt3;
    float inv_height, inv_slope, wild_off, inv_wild, inv8, inv300, log800, inv09, inv30;
};
struct ReliefPostArgs {
    const float* smooth_full;
    const float* x;
    float* out;
    int N, n, m;
    float inv300, scale, gain, floor_m;
};
// module "csrc_blend" (kernels/csrc/blend.cu; kernels/cuda_blend.py _Args)
constexpr int kBlendMax = 64;
struct BlendArgs {
    float* out;
    const float* fade;
    const float* win[kBlendMax];
    int C, H, W, EH, EW;
    int n;
    int accumulate;
    int a0[kBlendMax];
    int b0[kBlendMax];
};

// ---------------------------------------------------------------------------------------------- small helpers
int64_t ki(Gen& g, const char* k) { return g.manifest["constants"][k].i64(); }
bool kb(Gen& g, const char* k) { return g.manifest["constants"][k].i64() != 0; }
float kf(Gen& g, const char* k) { return g.cf(k); }
double kd(Gen& g, const char* k) { return g.c(k); }
float inv(float x) { return 1.0f / x; }   // torch: tensor / python scalar = tensor * (1 / float(scalar)) in fp32

uint32_t lowbias32(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
// synth/noise.white_noise's base: hash32((seed * 0x9E3779B1 + stream * 0x85EBCA77) & 0xFFFFFFFF)
uint32_t noise_base(int64_t seed, int64_t stream) {
    uint64_t v = static_cast<uint64_t>(seed) * 0x9E3779B1ULL + static_cast<uint64_t>(stream) * 0x85EBCA77ULL;
    return lowbias32(static_cast<uint32_t>(v & 0xFFFFFFFFULL));
}

Cache::Key key3(Gen& g, int64_t a, int64_t b) { return {g.st.seed, a, b}; }

Fields call(Gen& g, const char* m, std::vector<int64_t> i, std::vector<Tensor> t = {}) {
    Args a;
    a.i = std::move(i);
    a.t = std::move(t);
    return g.call(m, a);
}

// t[a:b] along the leading dim (contiguous view).
Tensor planes(const Tensor& t, int64_t a, int64_t b) {
    if (t.ndim < 1 || a < 0 || b > t.shape[0] || a > b) throw std::out_of_range("planes");
    Tensor v = t;
    v.shape[0] = b - a;
    const size_t per = t.shape[0] ? t.bytes() / static_cast<size_t>(t.shape[0]) : 0;
    v.ptr = static_cast<char*>(t.ptr) + static_cast<size_t>(a) * per;
    return v;
}

Tensor empty2(Gen& g, int64_t h, int64_t w) { return g.gpu.empty(DType::F32, {h, w}); }

Tensor add(Gen& g, const Tensor& a, const Tensor& b) {
    if (a.numel() != b.numel()) throw std::invalid_argument("add: shapes differ");
    Tensor o = g.gpu.empty_like(a);
    AddArgs s{a.f32(), b.f32(), o.f32(), a.numel()};
    g.gpu.launch(g.gpu.kernel("cells", "cells_add"), a.numel(), s);
    return o;
}

Tensor white_noise(Gen& g, int64_t i0, int64_t j0, int64_t h, int64_t w, int64_t stream) {
    Tensor o = empty2(g, h, w);
    NoiseArgs a{o.f32(), i0, j0, static_cast<int>(h), static_cast<int>(w), noise_base(g.st.seed, stream),
                static_cast<float>(std::sqrt(3.0))};
    g.gpu.launch(g.gpu.kernel("cells", "cells_white_noise"), h * w, a);
    return o;
}

void run_model(Gen& g, const char* name, int batch, const void* in0, const void* in1, void* out) {
    ci_model* m = g.model(name);
    if (m == nullptr) throw std::logic_error(std::string("engine model '") + name + "' is not loaded");
    ci_request req{};
    req.batch = batch;
    req.flags = CI_RUN_STREAM;
    req.inputs[0] = in0;
    req.inputs[1] = in1;
    req.outputs[0] = out;
    req.stream = g.gpu.stream;
    g.gpu.check(g.gpu.ci.run(m, &req), (std::string("ci_run(") + name + ")").c_str());
}

// ---------------------------------------------------------------------------------------------- cross-fades
// kernels/cuda_blend.overlap_add: items (window (C, EH, EW), a0, b0) in summation order.
struct Item {
    Tensor win;
    int64_t a0, b0;
};
Tensor overlap_add(Gen& g, int C, int64_t H, int64_t W, const std::vector<Item>& items, const Tensor& fade) {
    const int64_t EH = fade.size(0), EW = fade.size(1);
    std::vector<const Item*> live;
    for (auto& it : items) {
        if (std::max<int64_t>(0, it.a0) >= std::min(H, it.a0 + EH) || std::max<int64_t>(0, it.b0) >= std::min(W, it.b0 + EW))
            continue;
        if (it.win.dtype != DType::F32 || it.win.numel() < C * EH * EW) throw std::invalid_argument("overlap_add window");
        live.push_back(&it);
    }
    if (live.empty()) return g.gpu.zeros(DType::F32, {C, H, W});
    Tensor out = g.gpu.empty(DType::F32, {C, H, W});
    ci_kernel* k = g.gpu.kernel("csrc_blend", "blend_kernel");
    for (size_t start = 0; start < live.size(); start += kBlendMax) {
        BlendArgs a{};
        a.out = out.f32();
        a.fade = fade.f32();
        a.C = C;
        a.H = static_cast<int>(H);
        a.W = static_cast<int>(W);
        a.EH = static_cast<int>(EH);
        a.EW = static_cast<int>(EW);
        const size_t n = std::min<size_t>(kBlendMax, live.size() - start);
        a.n = static_cast<int>(n);
        a.accumulate = start > 0 ? 1 : 0;
        for (size_t q = 0; q < n; ++q) {
            const Item& it = *live[start + q];
            a.win[q] = it.win.f32();
            a.a0[q] = static_cast<int>(it.a0);
            a.b0[q] = static_cast<int>(it.b0);
        }
        g.gpu.launch(k, static_cast<long long>(C) * H * W, a);
    }
    return out;
}

// w2 = v[:, None] * v[None, :] of a manifest profile (cached: the caches are cleared with the layout, it is rebuilt).
Tensor fade2d(Gen& g, const char* name, int64_t id) {
    return g.cache("cells.fade", 8).get_or({id}, [&] {
        std::vector<float> v = g.manifest["constants"][name].f32s();
        const int64_t n = static_cast<int64_t>(v.size());
        Tensor p = g.gpu.upload(v.data(), DType::F32, {n});
        Tensor o = empty2(g, n, n);
        OuterArgs a{p.f32(), p.f32(), o.f32(), static_cast<int>(n), static_cast<int>(n)};
        g.gpu.launch(g.gpu.kernel("cells", "cells_outer"), n * n, a);
        return Fields::of(o);
    }).one();
}

// generator._blend over the relief / drainage canonical windows: fetch(wi, wj) -> (>= C, EXT, EXT).
Tensor blend(Gen& g, int64_t ci0, int64_t cj0, int64_t hc, int64_t wc, int C,
             const std::function<Tensor(int64_t, int64_t)>& fetch) {
    const int64_t ext = ki(g, "RELIEF_EXT"), core = ki(g, "RELIEF_CORE"), ap = ki(g, "RELIEF_APRON");
    const Tensor& w2 = fade2d(g, "CELLS_RELIEF_FADE", 0);
    std::vector<Item> items;
    for (int64_t wi = floordiv(ci0 - ext + ap, core); wi <= floordiv(ci0 + hc - 1 + ap, core); ++wi) {
        for (int64_t wj = floordiv(cj0 - ext + ap, core); wj <= floordiv(cj0 + wc - 1 + ap, core); ++wj) {
            const int64_t a0 = wi * core - ap, b0 = wj * core - ap;
            const int64_t r0 = std::max(ci0, a0), r1 = std::min(ci0 + hc, a0 + ext);
            const int64_t s0 = std::max(cj0, b0), s1 = std::min(cj0 + wc, b0 + ext);
            if (r0 >= r1 || s0 >= s1) continue;
            items.push_back({fetch(wi, wj), a0 - ci0, b0 - cj0});
        }
    }
    return overlap_add(g, C, hc, wc, items, w2);
}

// generator._owned: plane `plane` of the window whose core owns each cell, as int64.
Tensor owned(Gen& g, int64_t ci0, int64_t cj0, int64_t hc, int64_t wc,
             const std::function<Tensor(int64_t, int64_t)>& fetch) {
    const int64_t ext = ki(g, "RELIEF_EXT"), core = ki(g, "RELIEF_CORE"), ap = ki(g, "RELIEF_APRON");
    Tensor out = g.gpu.zeros(DType::I64, {hc, wc});
    for (int64_t wi = floordiv(ci0, core); wi <= floordiv(ci0 + hc - 1, core); ++wi) {
        for (int64_t wj = floordiv(cj0, core); wj <= floordiv(cj0 + wc - 1, core); ++wj) {
            const int64_t a0 = wi * core, b0 = wj * core;
            const int64_t r0 = std::max(ci0, a0), r1 = std::min(ci0 + hc, a0 + core);
            const int64_t s0 = std::max(cj0, b0), s1 = std::min(cj0 + wc, b0 + core);
            Tensor win = fetch(wi, wj);
            const int64_t oa = a0 - ap, ob = b0 - ap;
            OwnedArgs a{win.f32(), out.data<long long>(), static_cast<int>(wc), static_cast<int>(ext),
                        static_cast<int>(r0 - ci0), static_cast<int>(r1 - ci0), static_cast<int>(s0 - cj0),
                        static_cast<int>(s1 - cj0), static_cast<int>(ci0 - oa), static_cast<int>(cj0 - ob)};
            g.gpu.launch(g.gpu.kernel("cells", "cells_owned"), (r1 - r0) * (s1 - s0), a);
        }
    }
    return out;
}

// ---------------------------------------------------------------------------------------------- relief + trend
// generator._relief_window (+ generator2._relief_gain_at): (RELIEF_EXT, RELIEF_EXT) metres.
Fields relief_window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_relief_window");
    const int64_t wi = a.i[0], wj = a.i[1];
    return g.cache("cells._relief_windows", static_cast<size_t>(ki(g, "CELLS_RELIEF_CAP"))).get_or(key3(g, wi, wj), [&] {
        ++g.counters["relief_windows"];
        if (kb(g, "CELLS_RELIEF_AMP_NORM"))
            throw std::logic_error("_relief_window: relief amp_norm is not ported (ReliefConfig.amp_norm)");
        if (kd(g, "CELLS_HILL_RELIEF") > 0.0)
            throw std::logic_error("_relief_window: the hill_relief field gain is not ported (generator2.HILL_RELIEF)");
        const int64_t core = ki(g, "RELIEF_CORE"), ap = ki(g, "RELIEF_APRON"), n = ki(g, "RELIEF_EXT");
        const double preblur = kd(g, "CELLS_RELIEF_PREBLUR");
        const int64_t m = static_cast<int64_t>(std::ceil(3.0 * preblur)), N = n + 2 * m;
        const int64_t ci0 = wi * core - ap, cj0 = wj * core - ap;
        Fields c = call(g, "controls", {ci0 - m, cj0 - m, N, N});
        Tensor smooth = ops::blur(g.gpu, c["trend"], g.taps(preblur));
        Tensor cond = g.gpu.empty(DType::F32, {1, 10, n, n});
        Tensor noise = g.gpu.empty(DType::F32, {1, 1, n, n});
        ReliefCondArgs rc{smooth.f32(), c["wild"].f32(), c["t0"].f32(), c["tseason"].f32(), c["precip"].f32(),
                          c["pcv"].f32(), cond.f32(), noise.f32(), static_cast<int>(N), static_cast<int>(n),
                          static_cast<int>(m), ci0, cj0, noise_base(g.st.seed, ki(g, "RELIEF_NOISE_STREAM")),
                          static_cast<float>(std::sqrt(3.0)), inv(kf(g, "CELLS_HEIGHT_SCALE_M")),
                          inv(kf(g, "CELLS_RELIEF_SLOPE_SCALE_M")), kf(g, "CELLS_WILD_OFFSET"),
                          inv(kf(g, "CELLS_WILD_SCALE")), inv(8.0f), inv(300.0f), static_cast<float>(std::log(800.0)),
                          inv(0.9f), inv(30.0f)};
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_relief_cond"), n * n, rc);
        Tensor x = g.gpu.empty(DType::F32, {1, 1, n, n});
        run_model(g, "relief", 1, cond.ptr, noise.ptr, x.ptr);
        Tensor out = empty2(g, n, n);
        ReliefPostArgs rp{smooth.f32(), x.f32(), out.f32(), static_cast<int>(N), static_cast<int>(n),
                          static_cast<int>(m), inv(300.0f), kf(g, "CELLS_RELIEF_SCALE_M"), kf(g, "CELLS_RELIEF_GAIN"),
                          kf(g, "CELLS_RELIEF_FLOOR_M")};
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_relief_post"), n * n, rp);
        return Fields::of(out);
    });
}
SBTG_METHOD("_relief_window", relief_window);

// generator2.trend: v4's trend (relief windows cross-faded) + the layout's landforms.
Fields trend(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "trend");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    Tensor t;
    if (!kb(g, "CELLS_HAS_RELIEF")) {
        t = call(g, "controls", {ci0, cj0, hc, wc})["trend"];
    } else {
        t = blend(g, ci0, cj0, hc, wc, 1, [&](int64_t wi, int64_t wj) {
            return call(g, "_relief_window", {wi, wj}).one();
        })[0];
    }
    if (kd(g, "CELLS_LANDFORM_GAIN") == 0.0) return Fields::of(t);
    return Fields::of(add(g, t, call(g, "_layout", {ci0, cj0, hc, wc})["landform"]));
}
SBTG_METHOD("trend", trend);

// ---------------------------------------------------------------------------------------------- planner
// generator._planner_input(ci0, cj0, n[, m]) -> (x (1, N_IN, n, m), controls with the trend replaced).
Fields planner_input(Gen& g, const Args& a) {
    if (a.i.size() != 3 && a.i.size() != 4) need(a, 4, 0, 0, "_planner_input");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], n = a.i[2], m = a.i.size() > 3 ? a.i[3] : a.i[2];
    Fields c = call(g, "controls", {ci0, cj0, n, m});
    Tensor tr = call(g, "trend", {ci0, cj0, n, m}).one();
    const int64_t n_in = ki(g, "CELLS_N_IN");
    Tensor x = g.gpu.empty(DType::F32, {1, n_in, n, m});
    PlannerInputArgs p{tr.f32(), c["t0"].f32(), c["tseason"].f32(), c["precip"].f32(), c["pcv"].f32(), c["wild"].f32(),
                       x.f32(), n * m, static_cast<int>(n_in), static_cast<int>(ki(g, "CELLS_IN_TREND")),
                       static_cast<int>(ki(g, "CELLS_IN_T0")), static_cast<int>(ki(g, "CELLS_IN_TSEASON")),
                       static_cast<int>(ki(g, "CELLS_IN_PRECIP")), static_cast<int>(ki(g, "CELLS_IN_PCV")),
                       static_cast<int>(ki(g, "CELLS_IN_WILD")), static_cast<int>(ki(g, "CELLS_IN_HAS_TREND")),
                       static_cast<int>(ki(g, "CELLS_IN_HAS_CLIMATE")), static_cast<int>(ki(g, "CELLS_IN_HAS_WILD")),
                       inv(kf(g, "CELLS_HEIGHT_SCALE_M")), kf(g, "CELLS_WILD_OFFSET"), inv(kf(g, "CELLS_WILD_SCALE")),
                       inv(8.0f), inv(300.0f), static_cast<float>(std::log(800.0)), inv(0.9f), inv(30.0f)};
    g.gpu.launch(g.gpu.kernel("cells", "cells_planner_input"), n * m, p);
    Fields out;
    out.set("0", x);
    for (auto& [name, t] : c.items) out.set("1." + name, name == "trend" ? tr : t);
    return out;
}
SBTG_METHOD("_planner_input", planner_input);

Cache& windows(Gen& g) { return g.cache("cells._windows", static_cast<size_t>(ki(g, "CELLS_WINDOWS_CAP"))); }

// The height-only planner window tier (see planner_height in stages/common/util.hpp): channel 0 of each window, 16 KB
// instead of 180 KB, all on the device. Capacity: $SBTG_PLANNER_H_CAP, else the manifest's CELLS_WINDOWS_H_CAP, else
// 32768 windows (512 MiB); 0 = off (every consumer reads _planner_cells, as before).
size_t height_cap(Gen& g) {
    if (const char* e = std::getenv("SBTG_PLANNER_H_CAP")) return static_cast<size_t>(std::max(0L, std::atol(e)));
    const Json& c = g.manifest["constants"];
    return c.has("CELLS_WINDOWS_H_CAP") ? static_cast<size_t>(c["CELLS_WINDOWS_H_CAP"].i64()) : 32768;
}
Cache& hwindows(Gen& g) { return g.cache("cells._windows_h", height_cap(g)); }

// generator._run_planner_windows' batch: the canonical planner windows `keys` (claimed by this lane, compact), the
// network at batch <= PLANNER_BATCH. Each window's numbers are independent of its batch (no cross-window op; the
// engine plan is per-window indexed: bitwise for every batch size), so the batch size / composition is free.
// height_only: the results go to the height tier (channel 0) instead of the 11-channel window cache - same numbers.
void run_claimed_windows(Gen& g, const std::vector<Key2>& keys, bool height_only) {
    if (keys.empty()) return;
    const int64_t S = ki(g, "STRIDE"), W = ki(g, "WIN"), B = ki(g, "PLANNER_BATCH");
    if (B > kMaxW) throw std::logic_error("PLANNER_BATCH > 32");
    int64_t i0 = keys[0].first, j0 = keys[0].second, i1 = i0, j1 = j0;
    for (auto& k : keys) {
        i0 = std::min(i0, k.first);
        j0 = std::min(j0, k.second);
        i1 = std::max(i1, k.first);
        j1 = std::max(j1, k.second);
    }
    i0 *= S;
    j0 *= S;
    const int64_t hn = i1 * S + W - i0, wn = j1 * S + W - j0;
    g.counters["planner_windows"] += static_cast<long long>(keys.size());
    if (g.profile > 0) {   // diagnostics: windows computed more than once (evicted, then needed again)
        static std::mutex mu;
        static std::set<Cache::Key> seen;
        std::lock_guard<std::mutex> l(mu);
        for (auto& k : keys)
            if (!seen.insert(key3(g, k.first, k.second)).second) ++g.counters["planner_windows_again"];
    }
    Fields pin = call(g, "_planner_input", {i0, j0, hn, wn});
    const Tensor& xa = pin["0"];
    const Tensor& tr = pin["1.trend"];
    const int64_t n_in = ki(g, "CELLS_N_IN"), n_out = ki(g, "CELLS_N_OUT");
    if (n_out != 11) throw std::logic_error("planner N_OUT != 11");
    const Tensor mean = g.cache("cells.desc", 2).get_or({0}, [&] {
        std::vector<float> v = g.manifest["constants"]["CELLS_DESC_MEAN"].f32s();
        std::vector<float> s = g.manifest["constants"]["CELLS_DESC_STD"].f32s();
        v.insert(v.end(), s.begin(), s.end());
        return Fields::of(g.gpu.upload(v.data(), DType::F32, {static_cast<int64_t>(v.size())}));
    }).one();
    const float* d_mean = mean.f32();
    const float* d_std = mean.f32() + 8;
    const float ht = kf(g, "CELLS_HILL_TRANSFER");
    const bool sea_detail = kb(g, "CELLS_SEA_DETAIL");
    const Tensor& k_patch = g.taps(kd(g, "CELLS_PATCH_BLUR_CELLS"));
    Tensor xs = g.gpu.empty(DType::F32, {B, n_in, W, W});
    Tensor o = g.gpu.empty(DType::F32, {B, n_out, W, W});
    Cache& cache = height_only ? hwindows(g) : windows(g);
    const long long ww = W * W;
    for (size_t start = 0; start < keys.size(); start += static_cast<size_t>(B)) {
        const size_t n = std::min(static_cast<size_t>(B), keys.size() - start);
        const int ni = static_cast<int>(n);
        GatherArgs ga{xa.f32(), xs.f32(), static_cast<int>(n_in), static_cast<int>(hn), static_cast<int>(wn),
                      static_cast<int>(W), ni, {}};
        for (size_t q = 0; q < n; ++q) {
            ga.offs[2 * q] = static_cast<int>(keys[start + q].first * S - i0);
            ga.offs[2 * q + 1] = static_cast<int>(keys[start + q].second * S - j0);
        }
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_gather"), ww * n_in * ni, ga);
        // Python pads to PLANNER_BATCH with zeros; the network runs at batch n here (same numbers per window, a
        // partial batch no longer costs a full one).
        run_model(g, "planner", ni, xs.ptr, nullptr, o.ptr);
        // D.blur(out, PATCH_BLUR_CELLS)[:n] -> height / desc / river / logacc -> generator2._sea_merge
        Tensor ob = ops::blur(g.gpu, planes(o, 0, static_cast<int64_t>(n)).view({static_cast<int64_t>(n) * n_out, W, W}),
                              k_patch);
        std::vector<Tensor> outs(n);
        Tensor h0 = g.gpu.empty(DType::F32, {static_cast<int64_t>(n), W, W});
        PostArgs pa{ob.f32(), {}, h0.f32(), d_mean, d_std, ni, static_cast<int>(W), kf(g, "CELLS_HEIGHT_SCALE_M"),
                    kf(g, "CELLS_LOGACC_SCALE")};
        for (size_t q = 0; q < n; ++q) {
            outs[q] = g.gpu.empty(DType::F32, {n_out, W, W});
            pa.outs[q] = outs[q].f32();
        }
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_planner_post"), ww * n_out * ni, pa);
        Tensor T = g.gpu.empty(DType::F32, {static_cast<int64_t>(n), W, W});
        GatherArgs gt{tr.f32(), T.f32(), 1, static_cast<int>(hn), static_cast<int>(wn), static_cast<int>(W), ni, {}};
        std::memcpy(gt.offs, ga.offs, sizeof ga.offs);
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_gather"), ww * ni, gt);
        Tensor h1 = g.gpu.empty_like(h0);
        if (ht > 0.0f) {
            const Tensor& k_hill = g.taps(kd(g, "CELLS_HILL_TRANSFER_SIGMA_CELLS"));
            Tensor bh0 = ops::blur(g.gpu, h0, k_hill), bT = ops::blur(g.gpu, T, k_hill);
            HillArgs ha{h0.f32(), T.f32(), bh0.f32(), bT.f32(), h1.f32(), ww * ni, ht, inv(60.0f), 1};
            g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_hill"), ww * ni, ha);
        } else {
            HillArgs ha{h0.f32(), T.f32(), h0.f32(), T.f32(), h1.f32(), ww * ni, ht, inv(60.0f), 0};
            g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_hill"), ww * ni, ha);
        }
        Tensor bh1 = sea_detail ? ops::blur(g.gpu, h1, g.taps(kd(g, "CELLS_SEA_DETAIL_SIGMA_CELLS"))) : h1;
        SeaArgs sa{};
        sa.h1 = h1.f32();
        sa.T = T.f32();
        sa.bh1 = bh1.f32();
        for (size_t q = 0; q < n; ++q) sa.outs[q] = outs[q].f32();
        sa.n = ni;
        sa.win = static_cast<int>(W);
        sa.sea_detail = sea_detail ? 1 : 0;
        sa.inv150 = inv(150.0f);
        sa.max_m = kf(g, "CELLS_SEA_DETAIL_MAX_M");
        sa.floor_m = -5.0f;          // generator2._sea_merge: torch.minimum(trend + local, full_like(trend, -5.0))
        sa.k = 1.0f;                 // no shelf remap in the planner stage (generator2._sea_merge since 2026-11-19)
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_sea"), ww * ni, sa);
        for (size_t q = 0; q < n; ++q) {
            Tensor v = outs[q];
            cache.put(key3(g, keys[start + q].first, keys[start + q].second),
                      Fields::of(height_only ? g.gpu.clone(v[0]) : v));
        }
    }
}

// Splits `keys` into spatially compact groups (the planner input is computed over a group's bounding box; a sparse
// set - e.g. the ring of windows around a cached area - would waste most of it) without dropping to batch 1:
// sorted, then halved along the longer bounding-box axis until each group is compact.
void run_grouped(Gen& g, std::vector<Key2> keys, bool height_only) {
    if (keys.empty()) return;
    const int64_t S = ki(g, "STRIDE"), W = ki(g, "WIN");
    int64_t i0 = keys[0].first, j0 = keys[0].second, i1 = i0, j1 = j0;
    for (auto& k : keys) {
        i0 = std::min(i0, k.first);
        j0 = std::min(j0, k.second);
        i1 = std::max(i1, k.first);
        j1 = std::max(j1, k.second);
    }
    const int64_t hn = (i1 - i0) * S + W, wn = (j1 - j0) * S + W;
    if (keys.size() == 1 || hn * wn <= 16 * static_cast<int64_t>(keys.size()) * W * W) {
        run_claimed_windows(g, keys, height_only);
        return;
    }
    const bool rows = (i1 - i0) >= (j1 - j0);
    std::sort(keys.begin(), keys.end(), [rows](const Key2& a, const Key2& b) {
        return rows ? std::tie(a.first, a.second) < std::tie(b.first, b.second)
                    : std::tie(a.second, a.first) < std::tie(b.second, b.first);
    });
    const size_t h = keys.size() / 2;
    run_grouped(g, std::vector<Key2>(keys.begin(), keys.begin() + static_cast<std::ptrdiff_t>(h)), height_only);
    run_grouped(g, std::vector<Key2>(keys.begin() + static_cast<std::ptrdiff_t>(h), keys.end()), height_only);
}

// generator._run_planner_windows: the canonical planner windows `keys` not cached yet. With lanes, a window another
// lane is computing is left to it (claims; the consumer waits for it in the cache).
void run_planner_windows(Gen& g, const std::vector<Key2>& keys, bool height_only = false) {
    Cache& cache = height_only ? hwindows(g) : windows(g);
    std::vector<Key2> mine;
    mine.reserve(keys.size());
    for (auto& k : keys)
        if (cache.claim(key3(g, k.first, k.second))) mine.push_back(k);
    try {
        run_grouped(g, mine, height_only);
    } catch (...) {
        for (auto& k : mine) cache.release(key3(g, k.first, k.second));
        throw;
    }
}

// generator._window: (11, WIN, WIN) = [height, desc (8), river, logacc] of canonical window (wi, wj).
Fields window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_window");
    const Cache::Key k = key3(g, a.i[0], a.i[1]);
    Cache& cache = windows(g);
    if (!cache.contains(k)) run_planner_windows(g, {{a.i[0], a.i[1]}});
    return cache.get_or(k, [&]() -> Fields { throw std::logic_error("planner window evicted while computed"); });
}
SBTG_METHOD("_window", window);

// generator._planner_cells: blended planner output (11, hc, wc).
Fields planner_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "_planner_cells");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    const int64_t S = ki(g, "STRIDE"), W = ki(g, "WIN");
    std::vector<int64_t> wis, wjs;
    for (int64_t wi = floordiv(ci0 - W, S) + 1; wi <= floordiv(ci0 + hc - 1, S); ++wi) wis.push_back(wi);
    for (int64_t wj = floordiv(cj0 - W, S) + 1; wj <= floordiv(cj0 + wc - 1, S); ++wj) wjs.push_back(wj);
    if (g.native("_window")) {       // batch every missing window first, a band of rows at a time
        Cache& cache = windows(g);
        const size_t band = std::max<size_t>(1, (cache.cap() / 2) / std::max<size_t>(1, wjs.size()));
        for (size_t r = 0; r < wis.size(); r += band) {
            std::vector<Key2> keys;
            for (size_t q = r; q < std::min(wis.size(), r + band); ++q)
                for (int64_t wj : wjs)
                    if (!cache.contains(key3(g, wis[q], wj))) keys.emplace_back(wis[q], wj);
            run_planner_windows(g, keys);
        }
    }
    std::vector<Item> items;
    for (int64_t wi : wis) {
        for (int64_t wj : wjs) {
            const int64_t a0 = wi * S, b0 = wj * S;
            const int64_t r0 = std::max(ci0, a0), r1 = std::min(ci0 + hc, a0 + W);
            const int64_t s0 = std::max(cj0, b0), s1 = std::min(cj0 + wc, b0 + W);
            if (r0 >= r1 || s0 >= s1) continue;
            items.push_back({call(g, "_window", {wi, wj}).one(), a0 - ci0, b0 - cj0});
        }
    }
    return Fields::of(overlap_add(g, 11, hc, wc, items, fade2d(g, "CELLS_PLANNER_FADE", 1)));
}
SBTG_METHOD("_planner_cells", planner_cells);

// Channel 0 of _planner_cells from the height tier (native-only method; planner_height() falls back to _planner_cells
// when it is not routed). Same window list, same summation order (overlap_add is per channel), so bit for bit
// _planner_cells(...)[0].
Fields planner_height_m(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "_planner_height");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    if (height_cap(g) == 0 || !g.native("_planner_cells") || !g.native("_window"))
        return Fields::of(call(g, "_planner_cells", {ci0, cj0, hc, wc}).one()[0]);
    const int64_t S = ki(g, "STRIDE"), W = ki(g, "WIN");
    std::vector<int64_t> wis, wjs;
    for (int64_t wi = floordiv(ci0 - W, S) + 1; wi <= floordiv(ci0 + hc - 1, S); ++wi) wis.push_back(wi);
    for (int64_t wj = floordiv(cj0 - W, S) + 1; wj <= floordiv(cj0 + wc - 1, S); ++wj) wjs.push_back(wj);
    Cache& hcache = hwindows(g);
    Cache& full = windows(g);
    const size_t band = std::max<size_t>(1, (hcache.cap() / 2) / std::max<size_t>(1, wjs.size()));
    for (size_t r = 0; r < wis.size(); r += band) {
        std::vector<Key2> keys;
        for (size_t q = r; q < std::min(wis.size(), r + band); ++q)
            for (int64_t wj : wjs) {
                const Cache::Key k = key3(g, wis[q], wj);
                if (!hcache.contains(k) && !full.contains(k)) keys.emplace_back(wis[q], wj);
            }
        run_planner_windows(g, keys, true);
    }
    std::vector<Item> items;
    for (int64_t wi : wis) {
        for (int64_t wj : wjs) {
            const int64_t a0 = wi * S, b0 = wj * S;
            const int64_t r0 = std::max(ci0, a0), r1 = std::min(ci0 + hc, a0 + W);
            const int64_t s0 = std::max(cj0, b0), s1 = std::min(cj0 + wc, b0 + W);
            if (r0 >= r1 || s0 >= s1) continue;
            const Cache::Key k = key3(g, wi, wj);
            if (!hcache.contains(k) && !full.contains(k)) run_planner_windows(g, {{wi, wj}}, true);   // evicted meanwhile
            Tensor h = hcache.get_or(k, [&] { return Fields::of(g.gpu.clone(call(g, "_window", {wi, wj}).one()[0])); }).one();
            items.push_back({h, a0 - ci0, b0 - cj0});
        }
    }
    return Fields::of(overlap_add(g, 1, hc, wc, items, fade2d(g, "CELLS_PLANNER_FADE", 1))[0]);
}
SBTG_METHOD("_planner_height", planner_height_m);

// ---------------------------------------------------------------------------------------------- drainage
// generator._drainage_inputs -> (planner height (EXT, EXT), controls).
Fields drainage_inputs(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_drainage_inputs");
    const int64_t core = ki(g, "RELIEF_CORE"), ap = ki(g, "RELIEF_APRON"), n = ki(g, "RELIEF_EXT");
    const int64_t ci0 = a.i[0] * core - ap, cj0 = a.i[1] * core - ap;
    Tensor height = g.cache("cells._drainage_in", static_cast<size_t>(ki(g, "CELLS_DRAINAGE_IN_CAP")))
                        .get_or(key3(g, a.i[0], a.i[1]), [&] {
                            if (g.native("_planner_height")) return Fields::of(planner_height(g, ci0, cj0, n, n));
                            Tensor pc = call(g, "_planner_cells", {ci0, cj0, n, n}).one();
                            return Fields::of(g.gpu.clone(pc[0]));
                        })
                        .one();
    Fields c = call(g, "controls", {ci0, cj0, n, n});
    Fields out;
    out.set("0", height);
    for (auto& [name, t] : c.items) out.set("1." + name, t);
    return out;
}
SBTG_METHOD("_drainage_inputs", drainage_inputs);

// generator._route_inputs -> (height + jitter on land, precip, jitter / ROUTE_NOISE_M).
Fields route_inputs(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_route_inputs");
    const int64_t core = ki(g, "RELIEF_CORE"), ap = ki(g, "RELIEF_APRON"), n = ki(g, "RELIEF_EXT");
    const int64_t ci0 = a.i[0] * core - ap, cj0 = a.i[1] * core - ap;
    Fields di = call(g, "_drainage_inputs", {a.i[0], a.i[1]});
    const Tensor& height = di["0"];
    const double M = kd(g, "CELLS_ROUTE_NOISE_M"), sigma = kd(g, "CELLS_ROUTE_NOISE_SIGMA");
    Tensor jb = ops::blur(g.gpu, white_noise(g, ci0, cj0, n, n, ki(g, "CELLS_ROUTE_NOISE_STREAM")), g.taps(sigma));
    // jit * (ROUTE_NOISE_M * 2.0 * ROUTE_NOISE_SIGMA * math.sqrt(math.pi)): the factor in float64, then one fp32 mul
    const double K = M * 2.0 * sigma * std::sqrt(kPi);
    Tensor hz = empty2(g, n, n), flat = empty2(g, n, n);
    RouteInArgs r{height.f32(), jb.f32(), hz.f32(), flat.f32(), n * n, static_cast<float>(K), inv(static_cast<float>(M))};
    g.gpu.launch(g.gpu.kernel("cells", "cells_route_inputs"), n * n, r);
    Fields out;
    out.set("0", hz);
    out.set("1", di["1.precip"]);
    out.set("2", flat);
    return out;
}
SBTG_METHOD("_route_inputs", route_inputs);

// Host routes ride in pageable memory owned by a Route; the tensors' deleter keeps it alive (and gives it back to
// route_of without a copy).
struct RouteKeep {
    std::shared_ptr<const Route> r;
    void operator()(Block* b) const { delete b; }
};
Tensor host_view(const std::shared_ptr<const Route>& r, const void* p, DType dt, int ndim, const int64_t* shape) {
    Tensor t;
    t.ptr = const_cast<void*>(p);
    t.dtype = dt;
    t.ndim = ndim;
    for (int i = 0; i < ndim; ++i) t.shape[static_cast<size_t>(i)] = shape[i];
    t.host = true;
    t.mem = std::shared_ptr<Block>(new Block(), RouteKeep{r});
    t.mem->ptr = t.ptr;
    t.mem->bytes = t.bytes();
    t.mem->host = true;
    return t;
}
Fields route_fields(const std::shared_ptr<const Route>& r) {
    const int64_t nn[1] = {r->h * r->w}, hw[2] = {r->h, r->w};
    Fields f;
    f.set("0", host_view(r, r->recv.data(), DType::I64, 1, nn));
    f.set("1", host_view(r, r->invalid.data(), DType::BOOL, 2, hw));
    f.set("2", host_view(r, r->weights.data(), DType::F64, 2, hw));
    return f;
}
// The Route behind a route_window / _stitched_route result (zero-copy for native results; a Python result arrives as
// device tensors and is copied back).
std::shared_ptr<const Route> route_of(Gen& g, const Fields& f) {
    const Tensor& recv = f["0"];
    if (auto* k = std::get_deleter<RouteKeep>(recv.mem)) return k->r;
    const Tensor& invalid = f["1"];
    const Tensor& weights = f["2"];
    auto r = std::make_shared<Route>();
    r->h = invalid.size(0);
    r->w = invalid.size(1);
    const size_t n = static_cast<size_t>(r->h * r->w);
    r->recv.resize(n);
    r->invalid.resize(n);
    r->weights.resize(n);
    auto fetch = [&](const Tensor& t, void* dst, size_t bytes) {
        if (t.bytes() != bytes) throw std::invalid_argument("route field size");
        Tensor h = t.host ? t : g.gpu.to_host(t);
        g.gpu.sync();
        std::memcpy(dst, h.ptr, bytes);
    };
    fetch(recv, r->recv.data(), n * sizeof(int64_t));
    fetch(invalid, r->invalid.data(), n);
    fetch(weights, r->weights.data(), n * sizeof(double));
    return r;
}

Cache& route_cache(Gen& g) { return g.cache("cells._route_windows", static_cast<size_t>(ki(g, "CELLS_ROUTE_CAP"))); }
Cache& hydro_cache(Gen& g) { return g.cache("cells._hydro_windows", static_cast<size_t>(ki(g, "CELLS_HYDRO_CAP"))); }

// generator._route_window -> (recv (n*n) int64, invalid (n, n) bool, weights (n, n) float64), host memory.
Fields route_window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_route_window");
    return route_cache(g).get_or(key3(g, a.i[0], a.i[1]), [&] {
        ++g.counters["route_windows"];
        Fields ri = call(g, "_route_inputs", {a.i[0], a.i[1]});
        Tensor h = g.gpu.to_host(ri["0"]), p = g.gpu.to_host(ri["1"]), fl = g.gpu.to_host(ri["2"]);
        g.gpu.sync();
        auto r = std::make_shared<const Route>(cells::route_window(h.f32(), p.f32(), fl.f32(), h.size(0), h.size(1),
                                                                   kd(g, "CELLS_DEFAULT_EPSILON")));
        return route_fields(r);
    });
}
SBTG_METHOD("_route_window", route_window);

// generator._stitched_route: the receiver graph over window (wi, wj) with every cell routed by its core's owner.
Fields stitched_route(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_stitched_route");
    std::map<Key2, std::shared_ptr<const Route>> got;
    auto r = std::make_shared<const Route>(cells::stitch_routes(
        a.i[0], a.i[1], ki(g, "RELIEF_EXT"), ki(g, "RELIEF_CORE"), ki(g, "RELIEF_APRON"),
        [&](int64_t oi, int64_t oj) -> const Route& {
            auto& slot = got[{oi, oj}];
            if (!slot) slot = route_of(g, call(g, "_route_window", {oi, oj}));
            return *slot;
        }));
    return route_fields(r);
}
SBTG_METHOD("_stitched_route", stitched_route);

// generator._hydro_pred: the hydro sidecar's (3, EXT, EXT) [logacc, river probability, D8 class], or nothing.
Fields hydro_pred(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_hydro_pred");
    if (!kb(g, "CELLS_HAS_HYDRO")) return {};
    Fields di = call(g, "_drainage_inputs", {a.i[0], a.i[1]});
    const Tensor& h = di["0"];
    const int64_t n = h.size(0), nn = n * n;
    Tensor x = g.gpu.empty(DType::F32, {1, 7, n, n});
    HydroInArgs hi{h.f32(), di["1.t0"].f32(), di["1.tseason"].f32(), di["1.precip"].f32(), di["1.pcv"].f32(), x.f32(),
                   static_cast<int>(n), inv(kf(g, "CELLS_HEIGHT_SCALE_M")), inv(kf(g, "CELLS_HYDRO_SLOPE_SCALE_M")),
                   inv(8.0f), inv(300.0f), static_cast<float>(std::log(800.0)), inv(0.9f), inv(30.0f)};
    g.gpu.launch(g.gpu.kernel("cells", "cells_hydro_input"), nn, hi);
    const int64_t n_out = ki(g, "CELLS_HYDRO_N_OUT");
    Tensor o = g.gpu.empty(DType::F32, {1, n_out, n, n});
    run_model(g, "hydro", 1, x.ptr, nullptr, o.ptr);
    Tensor out = g.gpu.empty(DType::F32, {3, n, n});
    HydroPostArgs hp{o.f32(), out.f32(), nn, static_cast<int>(ki(g, "CELLS_HYDRO_OUT_LOGACC")),
                     static_cast<int>(ki(g, "CELLS_HYDRO_OUT_RIVER")), static_cast<int>(ki(g, "CELLS_HYDRO_OUT_D8")),
                     static_cast<int>(ki(g, "CELLS_HYDRO_N_D8")), kf(g, "CELLS_LOGACC_SCALE")};
    g.gpu.launch(g.gpu.kernel("cells", "cells_hydro_post"), nn, hp);
    return Fields::of(out);
}
SBTG_METHOD("_hydro_pred", hydro_pred);

// An event after the work enqueued so far (a worker waits on it), or nullptr after a full sync.
struct Fence {
    Gen& g;
    void* ev = nullptr;
    explicit Fence(Gen& gg) : g(gg) {
        ev = g.gpu.ci.event_create(g.gpu.rt);
        if (ev == nullptr || g.gpu.ci.event_record(g.gpu.rt, ev, g.gpu.stream) != 0) {
            if (ev != nullptr) g.gpu.ci.event_destroy(g.gpu.rt, ev);
            ev = nullptr;
            g.gpu.sync();
        }
    }
    Fence(const Fence&) = delete;
    Fence& operator=(const Fence&) = delete;
    ~Fence() {
        if (ev != nullptr) g.gpu.ci.event_destroy(g.gpu.rt, ev);
    }
    void wait() const {
        if (ev != nullptr && g.gpu.ci.event_sync(g.gpu.rt, ev) != 0)
            throw EngineError(std::string("ci_event_sync: ") + g.gpu.ci.last_error());
    }
};

// generator._drainage_fields (the device half of accumulate_drainage): (3, n, n) [log1p(acc), river, D8 class] from
// pinned host acc (float64) / d8 (int64), uploaded asynchronously.
Tensor drainage_fields(Gen& g, const Tensor& acc, const Tensor& d8, int64_t n) {
    Tensor a = g.gpu.to_device(acc), d = g.gpu.to_device(d8);
    Tensor out = g.gpu.empty(DType::F32, {3, n, n});
    DrainArgs da{a.data<double>(), d.data<long long>(), out.f32(), static_cast<int>(n), kf(g, "CELLS_RIVER_MIN_UPSLOPE")};
    g.gpu.launch(g.gpu.kernel("cells", "cells_drain_fields"), n * n, da);
    return out;
}

// generator._hydro_window: (3, EXT, EXT) drainage of one canonical window (routed over the stitched graph).
Fields hydro_window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_hydro_window");
    return hydro_cache(g).get_or(key3(g, a.i[0], a.i[1]), [&] {
        ++g.counters["hydro_windows"];
        Fields pred = call(g, "_hydro_pred", {a.i[0], a.i[1]});
        if (!kb(g, "CELLS_ROUTED_DRAINAGE") && !pred.empty()) return pred;
        auto r = route_of(g, call(g, "_stitched_route", {a.i[0], a.i[1]}));
        Tensor inflow;
        if (!pred.empty()) {
            inflow = g.gpu.to_host(pred.one()[0]);
            g.gpu.sync();
        }
        // Host buffers are written only after a fence past every earlier upload (the pool may hand back a block
        // whose asynchronous host -> device copy is still queued).
        Tensor acc = g.gpu.empty(DType::F64, {r->h * r->w}, true), d8 = g.gpu.empty(DType::I64, {r->h * r->w}, true);
        Fence(g).wait();
        cells::accumulate_core(*r, inflow.defined() ? inflow.f32() : nullptr, acc.data<double>(), d8.data<int64_t>());
        return Fields::of(drainage_fields(g, acc, d8, r->h));
    });
}
SBTG_METHOD("_hydro_window", hydro_window);

// Waits for every future (so no job outlives its inputs), then rethrows the first failure.
void join(std::vector<std::future<void>>& futs) {
    std::exception_ptr first;
    for (auto& f : futs) {
        try {
            if (f.valid()) f.get();
        } catch (...) {
            if (!first) first = std::current_exception();
        }
    }
    if (first) std::rethrow_exception(first);
}

// generator._prefetch_drainage: the hydro windows `keys` not cached yet (and the route windows they stitch), with the
// host work (fill / D8 / accumulation, ~25-40 ms a window) on worker threads while this thread queues the next
// window's GPU work. Fills the native caches only, so it runs when those methods are native; numbers are unchanged
// (every window is a pure function of its inputs).
void prefetch_drainage(Gen& g, const std::vector<Key2>& keys) {
    if (!kb(g, "CELLS_ROUTED_DRAINAGE") || !kb(g, "CELLS_PARALLEL_HOST_WORK")) return;
    if (!g.native("_hydro_window") || !g.native("_route_window")) return;
    Cache& hyd = hydro_cache(g);
    Cache& rte = route_cache(g);
    const int64_t core = ki(g, "RELIEF_CORE"), ap = ki(g, "RELIEF_APRON"), n = ki(g, "RELIEF_EXT");
    const double eps0 = kd(g, "CELLS_DEFAULT_EPSILON");
    std::vector<Key2> todo;
    std::set<Key2> seen;
    for (auto& k : keys)
        if (seen.insert(k).second && !hyd.contains(key3(g, k.first, k.second))) todo.push_back(k);
    todo.resize(std::min(todo.size(), std::max<size_t>(1, hyd.cap() / 2)));
    if (todo.empty()) return;
    std::vector<Key2> routes;
    seen.clear();
    for (auto& [wi, wj] : todo) {
        const int64_t ci0 = wi * core - ap, cj0 = wj * core - ap;
        for (int64_t oi = floordiv(ci0, core); oi <= floordiv(ci0 + n - 1, core); ++oi)
            for (int64_t oj = floordiv(cj0, core); oj <= floordiv(cj0 + n - 1, core); ++oj)
                if (seen.insert({oi, oj}).second && !rte.contains(key3(g, oi, oj))) routes.emplace_back(oi, oj);
    }
    routes.resize(std::min(routes.size(), std::max<size_t>(1, rte.cap() / 2)));
    {   // lanes: a route window another lane is computing is left to it
        std::vector<Key2> mine;
        for (auto& k : routes)
            if (rte.claim(key3(g, k.first, k.second))) mine.push_back(k);
        routes.swap(mine);
    }
    struct Unclaim {   // claims not filled (a failure) are released
        Gen& g;
        Cache& c;
        std::vector<Key2>& keys;
        ~Unclaim() {
            for (auto& k : keys) c.release(key3(g, k.first, k.second));
        }
    } unclaim_routes{g, rte, routes};
    cells::HostPool& pool = cells::HostPool::get();
    if (routes.size() == 1) {   // not batched below: computed by the method (its own claim)
        rte.release(key3(g, routes[0].first, routes[0].second));
        routes.clear();
    }
    if (routes.size() > 1) {
        g.counters["route_windows"] += static_cast<long long>(routes.size());
        std::vector<std::shared_ptr<Route>> res(routes.size());
        std::vector<std::unique_ptr<Fence>> fences;
        std::vector<std::future<void>> futs;
        for (size_t q = 0; q < routes.size(); ++q) {
            Fields ri = call(g, "_route_inputs", {routes[q].first, routes[q].second});
            Tensor h = g.gpu.to_host(ri["0"]), p = g.gpu.to_host(ri["1"]), fl = g.gpu.to_host(ri["2"]);
            fences.push_back(std::make_unique<Fence>(g));
            const Fence* fe = fences.back().get();
            auto& slot = res[q];
            futs.push_back(pool.submit([fe, h, p, fl, &slot, eps0] {
                fe->wait();
                slot = std::make_shared<Route>(cells::route_window(h.f32(), p.f32(), fl.f32(), h.size(0), h.size(1), eps0));
            }));
        }
        join(futs);
        for (size_t q = 0; q < routes.size(); ++q) {
            std::shared_ptr<const Route> r = res[q];
            rte.put(key3(g, routes[q].first, routes[q].second), route_fields(r));
        }
    }
    if (todo.size() < 2) return;
    {
        std::vector<Key2> mine;
        for (auto& k : todo)
            if (hyd.claim(key3(g, k.first, k.second))) mine.push_back(k);
        todo.swap(mine);
    }
    Unclaim unclaim_hydro{g, hyd, todo};
    if (todo.size() < 2) {   // not batched: computed by the method (its own claim)
        for (auto& k : todo) hyd.release(key3(g, k.first, k.second));
        todo.clear();
        return;
    }
    struct Job {
        std::map<Key2, std::shared_ptr<const Route>> got;
        Tensor inflow;
        std::unique_ptr<Fence> fence;   // after this job's inputs AND every earlier upload from host memory
        Tensor acc, d8;                  // pinned outputs (written by the worker after the fence)
    };
    std::vector<Job> jobs(todo.size());
    std::vector<std::future<void>> futs;
    g.counters["hydro_windows"] += static_cast<long long>(todo.size());
    for (size_t q = 0; q < todo.size(); ++q) {
        const auto [wi, wj] = todo[q];
        Job& j = jobs[q];
        Fields pred = call(g, "_hydro_pred", {wi, wj});
        const int64_t ci0 = wi * core - ap, cj0 = wj * core - ap;
        for (int64_t oi = floordiv(ci0, core); oi <= floordiv(ci0 + n - 1, core); ++oi)   // _stitched_route's order
            for (int64_t oj = floordiv(cj0, core); oj <= floordiv(cj0 + n - 1, core); ++oj)
                if (!j.got.count({oi, oj})) j.got[{oi, oj}] = route_of(g, call(g, "_route_window", {oi, oj}));
        if (!pred.empty()) j.inflow = g.gpu.to_host(pred.one()[0]);
        j.acc = g.gpu.empty(DType::F64, {n * n}, true);
        j.d8 = g.gpu.empty(DType::I64, {n * n}, true);
        j.fence = std::make_unique<Fence>(g);
        futs.push_back(pool.submit([&j, wi = wi, wj = wj, n, core, ap] {
            j.fence->wait();
            Route r = cells::stitch_routes(wi, wj, n, core, ap,
                                           [&j](int64_t oi, int64_t oj) -> const Route& { return *j.got.at({oi, oj}); });
            cells::accumulate_core(r, j.inflow.defined() ? j.inflow.f32() : nullptr, j.acc.data<double>(),
                                  j.d8.data<int64_t>());
        }));
    }
    join(futs);
    for (size_t q = 0; q < todo.size(); ++q) {
        Job& j = jobs[q];
        hyd.put(key3(g, todo[q].first, todo[q].second), Fields::of(drainage_fields(g, j.acc, j.d8, n)));
    }
}

// generator.drainage: per-cell (logacc, river, d8) cross-faded / owned from the hydro windows.
Fields drainage(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "drainage");
    if (!kb(g, "CELLS_HAS_HYDRO") && !kb(g, "CELLS_ROUTED_DRAINAGE")) return {};
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    const int64_t ext = ki(g, "RELIEF_EXT"), core = ki(g, "RELIEF_CORE"), ap = ki(g, "RELIEF_APRON");
    std::vector<Key2> keys;
    for (int64_t wi = floordiv(ci0 - ext + ap, core); wi <= floordiv(ci0 + hc - 1 + ap, core); ++wi)
        for (int64_t wj = floordiv(cj0 - ext + ap, core); wj <= floordiv(cj0 + wc - 1 + ap, core); ++wj)
            keys.emplace_back(wi, wj);
    prefetch_drainage(g, keys);
    auto win = [&](int64_t wi, int64_t wj) { return call(g, "_hydro_window", {wi, wj}).one(); };
    Tensor f = blend(g, ci0, cj0, hc, wc, 2, win);
    Tensor d8 = owned(g, ci0, cj0, hc, wc, [&](int64_t wi, int64_t wj) { return win(wi, wj)[2]; });
    Fields out;
    out.set("logacc", f[0]);
    out.set("river", f[1]);
    out.set("d8", d8);
    return out;
}
SBTG_METHOD("drainage", drainage);

// ---------------------------------------------------------------------------------------------- per-request views
// generator.cells: blended planner fields + controls (+ trend_raw, the relief trend) + drainage.
Fields cells_(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "cells");
    if (kb(g, "CELLS_HAS_DESCGIT"))   // v4: desc = sampled_desc (the MaskGIT descriptor sampler), not ported
        throw std::logic_error("cells: the descgit descriptor sampler (v4) is not ported");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    Tensor out = call(g, "_planner_cells", {ci0, cj0, hc, wc}).one();
    Fields ctrl = call(g, "controls", {ci0, cj0, hc, wc});
    Tensor tr = call(g, "trend", {ci0, cj0, hc, wc}).one();
    Fields res;
    res.set("height", out[0]);
    res.set("desc", planes(out, 1, 9));
    res.set("river", out[9]);
    res.set("logacc", out[10]);
    for (auto& [name, t] : ctrl.items) res.set(name, t);
    res.set("trend_raw", ctrl["trend"]);
    res.set("trend", tr);
    Fields dr = call(g, "drainage", {ci0, cj0, hc, wc});
    for (auto& [name, t] : dr.items) res.set(name, t);
    return res;
}
SBTG_METHOD("cells", cells_);

Tensor lapse_climate(Gen& g, const Fields& c, const Tensor& reg) {
    const int64_t n = reg.numel();
    Tensor out = g.gpu.empty(DType::F32, {4, reg.size(0), reg.size(1)});
    LapseArgs la{c["t0"].f32(), c["tseason"].f32(), c["precip"].f32(), c["pcv"].f32(), reg.f32(), out.f32(), n,
                 kf(g, "LAPSE_C_PER_M")};
    g.gpu.launch(g.gpu.kernel("cells", "cells_lapse"), n, la);
    return out;
}

// generator2.overview_cells (generator.overview_cells + the coastal shelf of sea_shelf worlds).
Fields overview_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "overview_cells");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    Tensor height = soft_cap_peaks(g, planner_height(g, ci0, cj0, hc, wc));
    Fields dr = call(g, "drainage", {ci0, cj0, hc, wc});
    Tensor logacc = dr.empty() ? call(g, "_planner_cells", {ci0, cj0, hc, wc}).one()[10] : dr["logacc"];
    Fields c = call(g, "controls", {ci0, cj0, hc, wc});
    const double sigma = kd(g, "LAPSE_REGIONAL_SIGMA_CELLS");
    const int64_t m = static_cast<int64_t>(std::ceil(3.0 * sigma)) + 1;
    Tensor big = planner_height(g, ci0 - m, cj0 - m, hc + 2 * m, wc + 2 * m);
    Tensor reg = ops::crop(g.gpu, ops::blur(g.gpu, soft_cap_peaks(g, big), g.taps(sigma)), static_cast<int>(m),
                           static_cast<int>(m), static_cast<int>(hc), static_cast<int>(wc));
    if (g.st.sea_shelf) height = call(g, "shelf", {ci0, cj0, 1}, {height}).one();   // generator2: shelf(h, ci0, cj0, 1)
    Fields out;
    out.set("height", height);
    out.set("logacc", logacc);
    if (dr.has("d8")) out.set("d8", dr["d8"]);
    out.set("climate", lapse_climate(g, c, reg));
    out.set("regional", reg);
    return out;
}
SBTG_METHOD("overview_cells", overview_cells);

// generator2._shelf_remap(h) with the module defaults (SEA_SHELF_*).
Fields shelf_remap(Gen& g, const Args& a) {
    need(a, 0, 0, 1, "G2._shelf_remap");
    const Tensor& h = a.t[0];
    const double k = kd(g, "CELLS_SEA_SHELF_SLOPE"), dm = kd(g, "CELLS_SEA_SHELF_DEPTH_M");
    const double k0 = kd(g, "CELLS_SEA_SHELF_KEEP"), d0 = kd(g, "CELLS_SEA_SHELF_KEEP_M");
    if (k >= 1.0) return Fields::of(h);
    Tensor o = g.gpu.empty_like(h);
    ShelfArgs s{h.f32(), o.f32(), h.numel(), static_cast<float>(k), static_cast<float>(dm),
                inv(static_cast<float>(dm)), static_cast<float>(1.0 - k), static_cast<float>(k0 - k),
                static_cast<float>(d0), d0 > 0.0 ? inv(static_cast<float>(d0)) : 0.0f, (k0 != k && d0 > 0.0) ? 1 : 0};
    g.gpu.launch(g.gpu.kernel("cells", "cells_shelf"), h.numel(), s);
    return Fields::of(o);
}
SBTG_METHOD("G2._shelf_remap", shelf_remap);

// world/swamp_relief.cell_fields(height, regional, climate, ci0, cj0, blocks_per_cell) -> (weight, table).
Fields swamp_cell_fields(Gen& g, const Args& a) {
    need(a, 3, 0, 3, "SR.cell_fields");
    const Tensor& height = a.t[0];
    const int64_t ci0 = a.i[0], cj0 = a.i[1], bpc = a.i[2], half = floordiv(bpc, 2);
    Tensor cand = call(g, "B.swamp_candidates", {ci0 * bpc + half, cj0 * bpc + half, bpc}, {height, a.t[1], a.t[2]}).one();
    Tensor blurred = ops::blur(g.gpu, ops::cast(g.gpu, cand, DType::F32), g.taps(kd(g, "CELLS_SWAMP_WEIGHT_SIGMA")));
    const int64_t n = height.numel();
    Tensor weight = g.gpu.empty_like(blurred);
    const double lo = kd(g, "CELLS_SWAMP_EDGE_LO"), hi = kd(g, "CELLS_SWAMP_EDGE_HI");
    SwampWeightArgs sw{blurred.f32(), weight.f32(), n, static_cast<float>(lo), inv(static_cast<float>(hi - lo))};
    g.gpu.launch(g.gpu.kernel("cells", "cells_swamp_weight"), n, sw);
    Tensor local = ops::blur(g.gpu, height, g.taps(kd(g, "CELLS_SWAMP_TABLE_SIGMA")));
    Tensor broad = ops::blur(g.gpu, height, g.taps(kd(g, "CELLS_SWAMP_BASE_SIGMA")));
    Tensor table = g.gpu.empty_like(broad);
    const double stair = kd(g, "CELLS_SWAMP_STAIR_M"), two_pi = 2.0 * kPi;
    SwampTableArgs st{local.f32(), broad.f32(), table.f32(), n, static_cast<int>(ki(g, "CELLS_SWAMP_STAIR_PASSES")),
                      stair > 0.0 ? 1 : 0, stair > 0.0 ? inv(static_cast<float>(stair)) : 0.0f,
                      static_cast<float>(stair), static_cast<float>(two_pi), inv(static_cast<float>(two_pi)),
                      kf(g, "CELLS_SWAMP_SLOPE_KEEP"), kf(g, "CELLS_SWAMP_TABLE_FLOOR_M")};
    g.gpu.launch(g.gpu.kernel("cells", "cells_swamp_table"), n, st);
    Fields out;
    out.set("0", weight.view({height.size(0), height.size(1)}));
    out.set("1", table.view({height.size(0), height.size(1)}));
    return out;
}
SBTG_METHOD("SR.cell_fields", swamp_cell_fields);

// generator.swamp_relief_cells -> (weight, table m) for cells [ci0, ci0+hc) x [cj0, cj0+wc).
Fields swamp_relief_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "swamp_relief_cells");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    const int64_t m = ki(g, "CELLS_SWAMP_MARGIN_CELLS");
    const double sigma = kd(g, "LAPSE_REGIONAL_SIGMA_CELLS");
    const int64_t mr = static_cast<int64_t>(std::ceil(3.0 * sigma)) + 1;
    Tensor cells = soft_cap_peaks(g, planner_height(g, ci0 - m - mr, cj0 - m - mr, hc + 2 * (m + mr), wc + 2 * (m + mr)));
    Fields ctrl = call(g, "controls", {ci0 - m, cj0 - m, hc + 2 * m, wc + 2 * m});
    const int H = static_cast<int>(hc + 2 * m), W = static_cast<int>(wc + 2 * m);
    Tensor reg = ops::crop(g.gpu, ops::blur(g.gpu, cells, g.taps(sigma)), static_cast<int>(mr), static_cast<int>(mr), H, W);
    Tensor height = ops::crop(g.gpu, cells, static_cast<int>(mr), static_cast<int>(mr), H, W);
    Tensor climate = lapse_climate(g, ctrl, reg);
    // max(1, round(CP / scale.px_per_block)) (Python's round: half to even)
    const double ppb = g.st.raw.num_or("px_per_block", 0.0);
    if (!(ppb > 0.0)) throw std::logic_error("swamp_relief_cells: no px_per_block in the generator state");
    const int64_t bpc = std::max<int64_t>(1, static_cast<int64_t>(std::nearbyint(static_cast<double>(ki(g, "CP")) / ppb)));
    Fields wt = call(g, "SR.cell_fields", {ci0 - m, cj0 - m, bpc}, {height, reg, climate});
    Fields out;
    out.set("0", ops::crop(g.gpu, wt["0"], static_cast<int>(m), static_cast<int>(m), static_cast<int>(hc), static_cast<int>(wc)));
    out.set("1", ops::crop(g.gpu, wt["1"], static_cast<int>(m), static_cast<int>(m), static_cast<int>(hc), static_cast<int>(wc)));
    return out;
}
SBTG_METHOD("swamp_relief_cells", swamp_relief_cells);

// ---------------------------------------------------------------------------------------------- coastal shelf (FX3)
// generator2._distance_cells(land, cap): exact Euclidean distance (cells) to the nearest land cell, capped.
Tensor distance_cells(Gen& g, const Tensor& land, int64_t H, int64_t W, int cap) {
    const float big = static_cast<float>(4 * (cap + 1) * (cap + 1));
    Tensor t1 = empty2(g, H, W), d2 = empty2(g, H, W), out = empty2(g, H, W);
    DistPassArgs p0{nullptr, land.data<unsigned char>(), t1.f32(), static_cast<int>(H), static_cast<int>(W), cap, 0, big};
    g.gpu.launch(g.gpu.kernel("cells", "cells_dist_pass"), H * W, p0);
    DistPassArgs p1{t1.f32(), nullptr, d2.f32(), static_cast<int>(H), static_cast<int>(W), cap, 1, big};
    g.gpu.launch(g.gpu.kernel("cells", "cells_dist_pass"), H * W, p1);
    DistFinishArgs fa{d2.f32(), out.f32(), H * W, static_cast<float>(cap)};
    g.gpu.launch(g.gpu.kernel("cells", "cells_dist_finish"), H * W, fa);
    return out;
}

Tensor grow(Gen& g, const Tensor& src, const Tensor* through, int64_t H, int64_t W, int r) {
    Tensor o = g.gpu.empty(DType::BOOL, {H, W});
    GrowArgs a{src.data<unsigned char>(), through ? through->data<unsigned char>() : nullptr, o.data<unsigned char>(),
               static_cast<int>(H), static_cast<int>(W), r};
    g.gpu.launch(g.gpu.kernel("cells", "cells_grow"), H * W, a);
    return o;
}

// generator2.sea_fields_cells -> (distance from land m (hc, wc), enclosed sub-sea "lake" bool (hc, wc)).
Fields sea_fields_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "sea_fields_cells");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    const int64_t min_cells = ki(g, "CELLS_LAKE_SEED_MIN_CELLS"), max_cells = ki(g, "CELLS_SEA_PROFILE_MAX_CELLS");
    const int64_t m = std::max(ki(g, "CELLS_SEA_CELL_MARGIN") + min_cells + 1, max_cells + 1);
    const int64_t H = hc + 2 * m, W = wc + 2 * m, n = H * W;
    Tensor cells = planner_height(g, ci0 - m, cj0 - m, H, W);
    Tensor trend = call(g, "_layout", {ci0 - m, cj0 - m, H, W})["trend"];
    Tensor open_land = call(g, "C2.continent2", {ci0 - m, cj0 - m, H, W})["1"];
    Tensor sub = g.gpu.empty(DType::BOOL, {H, W}), land = g.gpu.empty(DType::BOOL, {H, W});
    SeaMaskArgs ma{cells.f32(), sub.data<unsigned char>(), land.data<unsigned char>(), n};
    g.gpu.launch(g.gpu.kernel("cells", "cells_sea_masks"), n, ma);
    Tensor off = distance_cells(g, land, H, W, static_cast<int>(min_cells));
    Tensor sea = g.gpu.empty(DType::BOOL, {H, W});
    SeaSeedArgs2 sa{trend.f32(), open_land.f32(), off.f32(), sub.data<unsigned char>(), sea.data<unsigned char>(), n,
                    kf(g, "CELLS_SEA_SEED_TREND_M"), kf(g, "CELLS_SEA_LAND"), static_cast<float>(min_cells)};
    g.gpu.launch(g.gpu.kernel("cells", "cells_sea_seeds"), n, sa);
    for (int64_t k = 0; k < ki(g, "CELLS_SEA_GROW_CELLS"); ++k) sea = grow(g, sea, &sub, H, W, 1);
    Tensor coast = grow(g, sea, nullptr, H, W, static_cast<int>(ki(g, "CELLS_SEA_COAST_CELLS")));
    Tensor dist = distance_cells(g, land, H, W, static_cast<int>(max_cells));
    Tensor dist_m = empty2(g, hc, wc), lake = g.gpu.empty(DType::BOOL, {hc, wc});
    SeaOutArgs oa{dist.f32(), sub.data<unsigned char>(), coast.data<unsigned char>(), dist_m.f32(),
                  lake.data<unsigned char>(), static_cast<int>(W), static_cast<int>(m), static_cast<int>(hc),
                  static_cast<int>(wc), kf(g, "CELLS_SEA_CELL_M")};
    g.gpu.launch(g.gpu.kernel("cells", "cells_sea_out"), hc * wc, oa);
    Fields out;
    out.set("0", dist_m);
    out.set("1", lake);
    return out;
}
SBTG_METHOD("sea_fields_cells", sea_fields_cells);

// generator2.sea_fields_px(i1, j1, h, w, ppc) -> (distance m, lake weight 0..1) at pixels of `ppc` per cell.
Fields sea_fields_px(Gen& g, const Args& a) {
    need(a, 5, 0, 0, "sea_fields_px");
    const int64_t i1 = a.i[0], j1 = a.i[1], h = a.i[2], w = a.i[3], ppc = a.i[4];
    if (ppc == 1) {
        Fields c = call(g, "sea_fields_cells", {i1, j1, h, w});
        Fields out;
        out.set("0", c["0"]);
        out.set("1", ops::cast(g.gpu, c["1"], DType::F32));
        return out;
    }
    const int64_t ci0 = floordiv(i1, ppc) - 1, cj0 = floordiv(j1, ppc) - 1;
    const int64_t ci1 = floordiv(i1 + h - 1, ppc) + 2, cj1 = floordiv(j1 + w - 1, ppc) + 2;
    Fields c = call(g, "sea_fields_cells", {ci0, cj0, ci1 - ci0, cj1 - cj0});
    Tensor od = empty2(g, h, w), ol = empty2(g, h, w);
    SeaPxArgs sp{c["0"].f32(), c["1"].data<unsigned char>(), od.f32(), ol.f32(), static_cast<int>(h), static_cast<int>(w),
                 static_cast<int>(cj1 - cj0), i1, j1, ci0, cj0, 1.0 / static_cast<double>(ppc)};
    g.gpu.launch(g.gpu.kernel("cells", "cells_sea_px"), h * w, sp);
    Fields out;
    out.set("0", od);
    out.set("1", ol);
    return out;
}
SBTG_METHOD("sea_fields_px", sea_fields_px);

// generator2.shelf(heights[, i1, j1, ppc]): unchanged unless the world has the shelf (sea_shelf); with an origin and
// SEA_PROFILE on the distance-aware shelf + lake-bed cap, else the pointwise _shelf_remap.
Fields shelf(Gen& g, const Args& a) {
    if (a.t.size() != 1 || (!a.i.empty() && a.i.size() != 3) || !a.f.empty())
        throw std::invalid_argument("shelf: expects tensor heights and 0 or 3 ints (i1, j1, ppc)");
    const Tensor& h = a.t[0];
    if (!g.st.sea_shelf) return Fields::of(h);
    if (!kb(g, "CELLS_SEA_PROFILE") || a.i.empty()) return call(g, "G2._shelf_remap", {}, {h});
    const int64_t H = h.size(-2), W = h.size(-1);
    Fields sf = call(g, "sea_fields_px", {a.i[0], a.i[1], H, W, a.i[2]});
    const double k = kd(g, "CELLS_SEA_PROFILE_COMPRESS"), dm = kd(g, "CELLS_SEA_PROFILE_RETURN_M");
    const double k0 = 1.0, d0 = kd(g, "CELLS_SEA_PROFILE_KEEP_M");
    Tensor o = g.gpu.empty_like(h);
    ProfileArgs pa{h.f32(), sf["0"].f32(), sf["1"].f32(), o.f32(), h.numel(), H * W, kf(g, "CELLS_SEA_PROFILE_M0"),
                   kf(g, "CELLS_SEA_PROFILE_SLOPE"), kf(g, "CELLS_LAKE_DEPTH_M0"), kf(g, "CELLS_LAKE_DEPTH_SLOPE"),
                   static_cast<float>(k), static_cast<float>(dm), inv(static_cast<float>(dm)), static_cast<float>(1.0 - k),
                   static_cast<float>(k0 - k), static_cast<float>(d0), d0 > 0.0 ? inv(static_cast<float>(d0)) : 0.0f,
                   (k0 != k && d0 > 0.0) ? 1 : 0};
    if (k >= 1.0) throw std::logic_error("shelf: SEA_PROFILE_COMPRESS >= 1 (the identity remap) is not ported");
    g.gpu.launch(g.gpu.kernel("cells", "cells_shelf_profile"), h.numel(), pa);
    return Fields::of(o);
}
SBTG_METHOD("shelf", shelf);

} // namespace
} // namespace sbtg
