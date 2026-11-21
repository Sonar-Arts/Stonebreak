// Stage "detail": the detail sampler's windows and everything built from them (port of world/generator.py,
// world/generator2.py and world/tiles.py, bit for bit):
//
//   _detail_window(wi, wj)            canonical 60 m window: planner cells -> base, cond, noise, the engine's Detail2
//                                     (FP8 or fp16, whichever the backend exported), guard + retries, decode;
//                                     generator2's lowland boost on top
//   _detail_samples(s1, t1, s2, t2)   windows cross-faded (kernels/csrc/blend.cu)
//   _native_detail / native           30 m pixels (nearest / bilinear / SR-30 through fine_detail)
//   fine_detail                       SR-30 at 1:2 and 1:1 (generator2), else nothing
//   _sr_window / _sr15_window         SR windows (engine models sr30 / sr15; models/sr.py upsample, cond, assemble)
//   _sr_blend(i1, j1, i2, j2, which)  SR windows cross-faded (which 0 = _sr_window, 1 = _sr15_window)
//   native_fine                       1:1 blocks (SR-15, else the bilinear stand-in)
//   TileBuilder._carve                carve-node codes (world/carve_node.py) per block; d in Args.f
//
// Dependencies (_planner_cells, controls, drainage, detail_gates, ...) go through g.call. Constants: manifest
// "dt.*" (terrain_slm/export/native_manifest_detail.py).
#include "stages/common/util.hpp"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>

namespace sbtg {
namespace {

const char kSrc[] =
#include "detail_ops.cu.inc"
    ;
SBTG_MODULE("detail_ops", kSrc);
const char kBlendSrc[] =
#include "csrc_blend.cu.inc"
    ;
SBTG_MODULE("csrc_blend", kBlendSrc);

// ------------------------------------------------------------------------------------------- kernel arg twins
struct OuterArgs {
    const float* v;
    float* o;
    int n;
};
struct AddZeroArgs {
    const float* base;
    float* out;
    long long n;
};
struct AbsMaxArgs {
    const float* x;
    uint32_t* mx;
    long long n;
};
struct LowlandArgs {
    const float* win;
    const float* reg;
    float* out;
    long long n;
    float boost, top0, inv_span, inv5, inv_d0;
};
struct NoiseArgs {
    float* o;
    long long i0, j0;
    int h, w;
    uint32_t base;
    float sqrt3;
};
struct Bicubic2Args {
    const float* src;
    float* dst;
    int h, w, fm;
};
struct SrCondArgs {
    const float* up;
    float* cond;
    int U, E, o, R;
    float inv_rel, inv_2px, area;
};
struct SrAsmArgs {
    const float* coarse;
    const float* up;
    const float* x;
    float* out;
    int h, w;
    float gain, asinh_m;
};
struct FineFixArgs {
    const float* up;
    const float* px;
    float* out;
    int h, w;
};
struct CellUpArgs {
    const float* cell;
    float* out;
    int wc, H, W, oi, oj, f;
};
struct AridArgs {
    const float* precip;
    const float* t0;
    float* out;
    long long n;
};
struct PoolArgs {
    const float* x;
    const float* x2;
    float* out;
    int H, W, k, edge, in, neg, vd;
};
struct GradArgs {
    const float* vd;
    float* out;
    int H, W;
};
struct SteepArgs {
    const float* z;
    const float* seapool;
    const float* cliff;
    float* steep;
    float* steep_cliff;
    long long n;
    float cliff_m;
};
struct CarveArgs {
    const float* z;
    const float* vd150;
    const float* vd600;
    const float* vd2000;
    const float* J;
    const float* arid;
    const float* river;
    const float* fjord;
    const float* karst;
    const float* zmax;
    const float* nzmax;
    const float* near;
    const float* expo;
    int64_t* out;
    long long i1, j1, s1, t1;
    int mode, per, d, ds;
    int H, W, n_rows, n_cols;
    float d0, inv_dspan, slot, arid_max, gorge, rift, rift_coh, humid, tower_m, inv_cliff_dens, pi, inv_pi, eps;
};
struct HillBandArgs {
    const float* win;
    const float* b0;
    const float* b1;
    const float* reg;
    float* out;
    long long n;
    float gain, top0, inv_top, c0, inv_c;
};
struct SrLookArgs {
    const float* fine;
    const float* lp;
    float* out;
    long long n;
    float k;
};
// module "sbtg_cells" (cuda/sbtg_cells.cu, terrain.cpp): the phase-2 detail kernels, field for field
struct UpArgs {
    const float* src;
    float* dst;
    int C, h, w, f, fm;
};
struct DetailCondArgs {
    const float* base_full;
    const float* wild;
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    const float* logacc;
    const float* river;
    const float* gates;
    float* cond;
    float* gates_up;
    float* noise;
    float* base;
    int nc, f, E, ms, S, fm;
    long long si0, sj0;
    uint32_t base_hash;
    float sqrt3;
    float has_climate, has_flow;
    float inv_height, inv_slope, wild_off, inv_wild, inv8, inv300, log800, inv09, inv30, inv_logacc;
};
struct DetailOutArgs {
    const float* x;
    const float* base;
    float* out;
    int* bad;
    long long n;
    float limit, asinh_m;
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

// ------------------------------------------------------------------------------------------- helpers
int64_t ki(Gen& g, const std::string& k) { return g.manifest["constants"][k].i64(); }
float kf(Gen& g, const std::string& k) { return g.cf(k); }
double kd(Gen& g, const std::string& k) { return g.c(k); }
float inv(float x) { return 1.0f / x; }   // torch: tensor / python scalar = tensor * (1 / float(scalar)) in fp32
int I(int64_t v) { return static_cast<int>(v); }

uint32_t lowbias32(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
uint32_t noise_base(int64_t seed, int64_t stream) {
    uint64_t v = static_cast<uint64_t>(seed) * 0x9E3779B1ULL + static_cast<uint64_t>(stream) * 0x85EBCA77ULL;
    return lowbias32(static_cast<uint32_t>(v & 0xFFFFFFFFULL));
}

Fields callm(Gen& g, const char* m, std::vector<int64_t> i, std::vector<double> f = {}) {
    Args a;
    a.i = std::move(i);
    a.f = std::move(f);
    return g.call(m, a);
}
Tensor one(const Fields& f, const char* what) {
    if (f.empty()) throw std::logic_error(std::string(what) + " returned nothing");
    return f.one();
}
template <typename A>
void run(Gen& g, const char* name, long long n, const A& a) {
    g.gpu.launch(g.gpu.kernel("detail_ops", name), n, a);
}
double px_per_block(Gen& g) { return g.st.raw.num_or("px_per_block", 0.0); }
// FX4 block look of the current build (NativeGen state "look_build" = generator2.look_build).
bool look_build(Gen& g) { return g.st.look_build; }
// A generator knob (NativeGen state "knobs") as Python's double, else the manifest's build-time value.
double knob(Gen& g, const char* name, const std::string& fallback) {
    if (g.st.raw.has("knobs") && g.st.raw["knobs"].has(name)) return g.st.raw["knobs"][name].num();
    return g.c(fallback);
}

// Python repr of a float (the warnings print the retry sigma as Python does).
std::string py_repr(double v) {
    char s[64];
    for (int p = 1; p <= 17; ++p) {
        std::snprintf(s, sizeof s, "%.*g", p, v);
        if (std::strtod(s, nullptr) == v) break;
    }
    std::string r = s;
    if (r.find_first_of(".en") == std::string::npos) r += ".0";
    return r;
}

// generator._fade / generator2._sr_blend weights: the outer product of the manifest's 1-D factor (cached).
Tensor fade2(Gen& g, const char* table) {
    const int64_t id = std::string(table) == "detail_fade" ? 1 : 2;
    return g.cache("detail.fade", 4).get_or({0, id}, [&]() {
        std::vector<float> v = g.manifest["detail_tables"][table].f32s();
        const int64_t n = static_cast<int64_t>(v.size());
        Tensor dv = g.gpu.upload(v.data(), DType::F32, {n});
        Tensor o = g.gpu.empty(DType::F32, {n, n});
        OuterArgs a{dv.f32(), o.f32(), I(n)};
        run(g, "dk_outer", n * n, a);
        return Fields::of(o);
    }).one();
}

struct Item {
    Tensor win;
    int64_t a0, b0;
};
// kernels/cuda_blend.overlap_add: items in summation order, out (C, H, W).
Tensor overlap_add(Gen& g, int C, int64_t H, int64_t W, const std::vector<Item>& items, const Tensor& fade) {
    const int64_t EH = fade.size(0), EW = fade.size(1);
    std::vector<const Item*> live;
    for (auto& it : items) {
        if (std::max<int64_t>(0, it.a0) >= std::min(H, it.a0 + EH) || std::max<int64_t>(0, it.b0) >= std::min(W, it.b0 + EW))
            continue;
        if (it.win.dtype != DType::F32 || it.win.numel() != C * EH * EW) throw std::invalid_argument("overlap_add window");
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
        a.H = I(H);
        a.W = I(W);
        a.EH = I(EH);
        a.EW = I(EW);
        const size_t n = std::min<size_t>(kBlendMax, live.size() - start);
        a.n = static_cast<int>(n);
        a.accumulate = start > 0 ? 1 : 0;
        for (size_t q = 0; q < n; ++q) {
            const Item& it = *live[start + q];
            a.win[q] = it.win.f32();
            a.a0[q] = I(it.a0);
            a.b0[q] = I(it.b0);
        }
        g.gpu.launch(k, static_cast<long long>(C) * H * W, a);
    }
    return out;
}

Tensor white_noise(Gen& g, int64_t i0, int64_t j0, int64_t h, int64_t w, int64_t stream) {
    Tensor o = g.gpu.empty(DType::F32, {h, w});
    NoiseArgs a{o.f32(), i0, j0, I(h), I(w), noise_base(g.st.seed, stream), static_cast<float>(std::sqrt(3.0))};
    run(g, "dk_white_noise", h * w, a);
    return o;
}

// One engine flow request (batch 1) on the generator's stream. `mask`: in/out expert mask (-1 = read from the gates).
Tensor run_flow(Gen& g, const char* name, const Tensor& cond, const Tensor& noise, const Tensor* gates, int64_t& mask) {
    ci_model* m = g.model(name);
    if (m == nullptr) throw std::logic_error(std::string("engine model '") + name + "' is not loaded");
    Tensor x = g.gpu.empty(DType::F32, {noise.size(-2), noise.size(-1)});
    ci_request req{};
    req.batch = 1;
    req.flags = CI_RUN_STREAM;
    if (mask >= 0) {
        req.flags |= CI_RUN_MASK_GIVEN;
        req.mask = static_cast<uint32_t>(mask);
    }
    req.inputs[0] = cond.ptr;
    req.inputs[1] = noise.ptr;
    req.inputs[2] = gates != nullptr ? gates->ptr : nullptr;
    req.outputs[0] = x.ptr;
    req.stream = g.gpu.stream;
    uint32_t got = 0;
    req.out_mask = &got;
    g.gpu.check(g.gpu.ci.run(m, &req), (std::string("ci_run(") + name + ")").c_str());
    mask = static_cast<int64_t>(got);
    return x;
}

std::mutex g_fail_mu;
std::map<const Gen*, long long> g_failures;

// ------------------------------------------------------------------------------------------- detail window
// generator._detail_window's run() (the value before generator2's lowland boost; cached).
Tensor detail_window_raw(Gen& g, int64_t wi, int64_t wj) {
    const int64_t spc = ki(g, "dt.SPC"), E = ki(g, "dt.EXT"), mc = ki(g, "dt.MARGIN_CELLS");
    const int64_t si0 = wi * ki(g, "dt.CORE") - ki(g, "dt.APRON"), sj0 = wj * ki(g, "dt.CORE") - ki(g, "dt.APRON");
    const int64_t nc = E / spc + 2 * mc;
    const int64_t ci0 = floordiv(si0, spc) - mc, cj0 = floordiv(sj0, spc) - mc;
    const int64_t ms = mc * spc, F = nc * spc;
    Tensor raw = planner_height(g, ci0, cj0, nc, nc);
    Fields ctrl = callm(g, "controls", {ci0, cj0, nc, nc});
    Fields dr = callm(g, "drainage", {ci0, cj0, nc, nc});
    const bool flow = dr.has("logacc");
    Tensor river;
    if (flow) river = ops::blur(g.gpu, dr["river"], g.taps(kd(g, "dt.RIVER_SIGMA")));
    Fields gf = callm(g, "detail_gates", {ci0, cj0, nc, ms});
    Tensor gates;
    if (!gf.empty()) gates = gf.one();
    const int fm = I(ki(g, "dt.UP_FMA"));
    const float limit = kf(g, "dt.X_LIMIT"), asinh_m = kf(g, "dt.ASINH_M");
    Tensor out = g.gpu.empty(DType::F32, {E, E});
    Tensor flag = g.gpu.empty(DType::I32, {2});
    Tensor hflag = g.gpu.empty(DType::I32, {2}, true);
    int64_t mask = -1;
    Tensor base;
    const int n_att = I(ki(g, "dt.RETRY_N"));
    for (int at = 0; at < n_att; ++at) {
        const double sigma = kd(g, "dt.RETRY_SIGMA" + std::to_string(at));
        const bool clim = ki(g, "dt.RETRY_CLIMATE" + std::to_string(at)) != 0;
        Tensor rawb = ops::blur(g.gpu, raw, g.taps(sigma));
        Tensor up = g.gpu.empty(DType::F32, {F, F});
        UpArgs u{rawb.f32(), up.f32(), 1, I(nc), I(nc), I(spc), fm};
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_up"), F * F, u);
        Tensor base_full = ops::blur(g.gpu, up, g.taps(kd(g, "dt.BASE_SIGMA")));
        Tensor cond = g.gpu.empty(DType::F32, {ki(g, "dt.N_COND"), E, E});
        Tensor noise = g.gpu.empty(DType::F32, {E, E});
        base = g.gpu.empty(DType::F32, {E, E});
        DetailCondArgs a{};
        a.base_full = base_full.f32();
        a.wild = ctrl["wild"].f32();
        a.t0 = ctrl["t0"].f32();
        a.tseason = ctrl["tseason"].f32();
        a.precip = ctrl["precip"].f32();
        a.pcv = ctrl["pcv"].f32();
        a.logacc = flow ? dr["logacc"].f32() : nullptr;
        a.river = flow ? river.f32() : nullptr;
        a.gates = nullptr;            // the gates come from detail_gates (already at samples)
        a.cond = cond.f32();
        a.gates_up = nullptr;
        a.noise = noise.f32();
        a.base = base.f32();
        a.nc = I(nc);
        a.f = I(spc);
        a.E = I(E);
        a.ms = I(ms);
        a.S = I(ki(g, "dt.STYLES"));
        a.fm = fm;
        a.si0 = si0;
        a.sj0 = sj0;
        a.base_hash = noise_base(g.st.seed, ki(g, "dt.NOISE_STREAM"));
        a.sqrt3 = static_cast<float>(std::sqrt(3.0));
        a.has_climate = clim ? 1.0f : 0.0f;
        a.has_flow = flow ? 1.0f : 0.0f;
        a.inv_height = inv(kf(g, "dt.HEIGHT_SCALE_M"));
        a.inv_slope = inv(kf(g, "dt.SLOPE_SCALE_M"));
        a.wild_off = kf(g, "dt.WILD_OFF");
        a.inv_wild = inv(kf(g, "dt.WILD_SCALE"));
        a.inv8 = 1.0f / 8.0f;
        a.inv300 = 1.0f / 300.0f;
        a.log800 = static_cast<float>(std::log(800.0));
        a.inv09 = 1.0f / 0.9f;
        a.inv30 = 1.0f / 30.0f;
        a.inv_logacc = inv(kf(g, "dt.LOGACC_SCALE"));
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_detail_cond"), E * E, a);
        Tensor x = run_flow(g, "detail", cond, noise, gates.defined() ? &gates : nullptr, mask);
        g.gpu.memset0(flag);
        DetailOutArgs o{x.f32(), base.f32(), out.f32(), flag.i32(), E * E, limit, asinh_m};
        g.gpu.launch(g.gpu.kernel("sbtg_cells", "sbtg_detail_out"), E * E, o);
        g.gpu.copy(hflag, flag);
        g.gpu.sync();                 // the guard's one readback per attempt
        if (hflag.i32()[0] == 0) return out;
        // Failed: the warning's max |x| (rare path: its own readback).
        AbsMaxArgs am{x.f32(), reinterpret_cast<uint32_t*>(flag.i32()) + 1, E * E};
        run(g, "dk_absmax", E * E, am);
        g.gpu.copy(hflag, flag);
        g.gpu.sync();
        float mx;
        uint32_t bits = static_cast<uint32_t>(hflag.i32()[1]);
        std::memcpy(&mx, &bits, 4);
        {
            std::lock_guard<std::mutex> l(g_fail_mu);
            ++g_failures[&g];
        }
        std::printf("[detail] WARNING: window (%lld,%lld) seed %lld unstable (base blur %s cells, climate %s, max |x| "
                    "%.3g); retrying\n",
                    static_cast<long long>(wi), static_cast<long long>(wj), static_cast<long long>(g.st.seed),
                    py_repr(sigma).c_str(), clim ? "on" : "off", static_cast<double>(mx));
        std::fflush(stdout);
    }
    std::printf("[detail] WARNING: window (%lld,%lld) seed %lld fell back to the smooth base\n",
                static_cast<long long>(wi), static_cast<long long>(wj), static_cast<long long>(g.st.seed));
    std::fflush(stdout);
    AddZeroArgs z{base.f32(), out.f32(), E * E};
    run(g, "dk_add_zero", E * E, z);
    return out;
}

Fields detail_window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_detail_window");
    const int64_t wi = a.i[0], wj = a.i[1];
    Tensor win = g.cache("detail._detail_windows", 512).get_or({g.st.seed, wi, wj}, [&]() {
        ++g.counters["detail_windows"];
        return Fields::of(detail_window_raw(g, wi, wj));
    }).one();
    if (look_build(g)) {             // FX4 hill band (generator2._hill_band), before the lowland boost
        const float hg = static_cast<float>(knob(g, "look_hill_gain", "dt.LOOK_HILL_GAIN"));
        if (hg > 0.0f) {
            Tensor b0 = ops::blur(g.gpu, win, g.taps(kd(g, "dt.LOOK_HILL_S0")));
            Tensor b1 = ops::blur(g.gpu, win, g.taps(kd(g, "dt.LOOK_HILL_S1")));
            Tensor reg = ops::blur(g.gpu, win, g.taps(kd(g, "dt.LOOK_HILL_SREG")));
            Tensor o = g.gpu.empty_like(win);
            HillBandArgs h{win.f32(), b0.f32(), b1.f32(), reg.f32(), o.f32(), win.numel(), hg,
                           kf(g, "dt.LOOK_HILL_TOP0"), inv(kf(g, "dt.LOOK_HILL_TOP_SPAN")), kf(g, "dt.LOOK_HILL_COAST0"),
                           inv(kf(g, "dt.LOOK_HILL_COAST_SPAN"))};
            run(g, "dk_hill_band", win.numel(), h);
            win = o;
        }
    }
    const float boost = g.st.raw.has("lowland_boost") ? g.st.raw["lowland_boost"].f32() : kf(g, "dt.LOWLAND_BOOST");
    if (!(boost > 0.0f)) return Fields::of(win);
    Tensor reg = ops::blur(g.gpu, win, g.taps(kd(g, "dt.LOWLAND_SIGMA")));
    Tensor out = g.gpu.empty_like(win);
    LowlandArgs l{win.f32(), reg.f32(), out.f32(), win.numel(), boost, kf(g, "dt.LOWLAND_TOP0"),
                  inv(kf(g, "dt.LOWLAND_SPAN")), inv(5.0f), inv(kf(g, "dt.LOWLAND_D0"))};
    run(g, "dk_lowland", win.numel(), l);
    return Fields::of(out);
}
SBTG_METHOD("_detail_window", detail_window);

// generator._detail_samples: windows cross-faded over 60 m samples [s1, s2) x [t1, t2).
Fields detail_samples(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "_detail_samples");
    const int64_t s1 = a.i[0], t1 = a.i[1], s2 = a.i[2], t2 = a.i[3];
    const int64_t E = ki(g, "dt.EXT"), C = ki(g, "dt.CORE"), A = ki(g, "dt.APRON");
    Tensor fade = fade2(g, "detail_fade");
    std::vector<Item> items;
    for (int64_t wi = floordiv(s1 - E + A, C); wi <= floordiv(s2 - 1 + A, C); ++wi)
        for (int64_t wj = floordiv(t1 - E + A, C); wj <= floordiv(t2 - 1 + A, C); ++wj) {
            const int64_t a0 = wi * C - A, b0 = wj * C - A;
            if (std::max(s1, a0) >= std::min(s2, a0 + E) || std::max(t1, b0) >= std::min(t2, b0 + E)) continue;
            items.push_back({one(callm(g, "_detail_window", {wi, wj}), "_detail_window"), a0 - s1, b0 - t1});
        }
    return Fields::of(overlap_add(g, 1, s2 - s1, t2 - t1, items, fade)[0]);
}
SBTG_METHOD("_detail_samples", detail_samples);

// generator._native_detail: 30 m pixels from the samples (SR-30 when fine_detail answers).
Fields native_detail(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "_native_detail");
    const int64_t i1 = a.i[0], j1 = a.i[1], i2 = a.i[2], j2 = a.i[3];
    Fields fine = callm(g, "fine_detail", {i1, j1, i2, j2});
    if (!fine.empty()) return fine;
    const int64_t ds = ki(g, "dt.DS");
    int64_t s1, t1, s2, t2;
    Tensor px;
    if (px_per_block(g) < static_cast<double>(ds)) {
        s1 = floordiv(i1, ds) - 1;
        t1 = floordiv(j1, ds) - 1;
        s2 = ceildiv(i2, ds) + 1;
        t2 = ceildiv(j2, ds) + 1;
        Tensor samp = one(callm(g, "_detail_samples", {s1, t1, s2, t2}), "_detail_samples");
        px = ops::bilinear(g.gpu, samp, I((s2 - s1) * ds), I((t2 - t1) * ds), static_cast<double>(ds));
    } else {
        s1 = floordiv(i1, ds);
        t1 = floordiv(j1, ds);
        s2 = ceildiv(i2, ds);
        t2 = ceildiv(j2, ds);
        Tensor samp = one(callm(g, "_detail_samples", {s1, t1, s2, t2}), "_detail_samples");
        px = ops::repeat2d(g.gpu, samp, I(ds), I(ds));
    }
    return Fields::of(ops::crop(g.gpu, px, I(i1 - s1 * ds), I(j1 - t1 * ds), I(i2 - i1), I(j2 - j1)));
}
SBTG_METHOD("_native_detail", native_detail);

// generator.native (the EXP-2 / v4 detail path; the v3 region path has no detail sampler and is not ported).
Fields native(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "native");
    if (ki(g, "dt.HAS_DETAIL") == 0) throw std::logic_error("native: the region path (no detail model) is not ported");
    return callm(g, "_native_detail", a.i);
}
SBTG_METHOD("native", native);

// generator2.fine_detail: SR-30 at 1:2 / 1:1 (px_per_block < DS), else nothing.
Fields fine_detail(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "fine_detail");
    if (ki(g, "dt.HAS_SR30") == 0 || px_per_block(g) >= static_cast<double>(ki(g, "dt.DS"))) return Fields{};
    return callm(g, "_sr_blend", {a.i[0], a.i[1], a.i[2], a.i[3], 0});
}
SBTG_METHOD("fine_detail", fine_detail);

// ------------------------------------------------------------------------------------------- SR
// models/sr: upsample + build_cond (cropped), noise, the engine network * gain, assemble over coarse[m:-m, m:-m].
// look (FX4 block look): sampled at gain 1, then generator2._sr_gain_look(fine, coarse, gain): the detail above a
// Gaussian low-pass x gain (k = fp32(gain - 1.0), the Python double difference), 2x2 means restored.
Tensor sr_window(Gen& g, const Tensor& coarse, int64_t a0, int64_t b0, float px_m, int64_t stream, const char* model,
                 float gain, bool look = false, float look_k = 0.0f) {
    const int64_t ext = ki(g, "dt.SR_CORE") + 2 * ki(g, "dt.SR_APRON"), m = ki(g, "dt.SR_CTX");
    const int64_t hc = coarse.size(0), wc = coarse.size(1);
    if (hc != wc || hc * 2 - 4 * m != ext) throw std::logic_error("sr window: coarse shape");
    const int fm = I(ki(g, "dt.BICUBIC_FMA"));
    Tensor up = g.gpu.empty(DType::F32, {2 * hc, 2 * wc});
    Bicubic2Args b{coarse.f32(), up.f32(), I(hc), I(wc), fm};
    run(g, "dk_bicubic2", up.numel(), b);
    Tensor cond = g.gpu.empty(DType::F32, {ki(g, "dt.SR_N_COND"), ext, ext});
    const int R = I(ki(g, "dt.SR_POOL") / 2);
    SrCondArgs c{up.f32(), cond.f32(), I(2 * hc), I(ext), I(2 * m), R, inv(kf(g, "dt.SR_REL_M")), inv(2.0f * px_m),
                 static_cast<float>((2 * R + 1) * (2 * R + 1))};
    run(g, "dk_sr_cond", ext * ext, c);
    Tensor noise = white_noise(g, a0, b0, ext, ext, stream);
    int64_t mask = -1;
    Tensor x = run_flow(g, model, cond, noise, nullptr, mask);
    Tensor cc = ops::crop(g.gpu, coarse, I(m), I(m), I(hc - 2 * m), I(wc - 2 * m));
    Tensor up2 = g.gpu.empty(DType::F32, {ext, ext});
    Bicubic2Args b2{cc.f32(), up2.f32(), I(hc - 2 * m), I(wc - 2 * m), fm};
    run(g, "dk_bicubic2", up2.numel(), b2);
    Tensor out = g.gpu.empty(DType::F32, {ext, ext});
    SrAsmArgs s{cc.f32(), up2.f32(), x.f32(), out.f32(), I(hc - 2 * m), I(wc - 2 * m), look ? 1.0f : gain,
                kf(g, "dt.SR_ASINH_M")};
    run(g, "dk_sr_assemble", (hc - 2 * m) * (wc - 2 * m), s);
    if (!look) return out;
    Tensor lp = ops::blur(g.gpu, out, g.taps(kd(g, "dt.SR_LOOK_SIGMA")));
    Tensor f = g.gpu.empty_like(out);
    SrLookArgs l{out.f32(), lp.f32(), f.f32(), out.numel(), look_k};
    run(g, "dk_sr_look", out.numel(), l);
    Tensor res = g.gpu.empty_like(out);
    FineFixArgs fx{f.f32(), cc.f32(), res.f32(), I(hc - 2 * m), I(wc - 2 * m)};
    run(g, "dk_fine_fix", (hc - 2 * m) * (wc - 2 * m), fx);
    return res;
}

Fields sr30_window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_sr_window");
    const int64_t wi = a.i[0], wj = a.i[1];
    const bool look = look_build(g);   // generator2.use_block_look: one LRU per mode
    return g.cache(look ? "detail._sr_windows_look" : "detail._sr_windows", 256).get_or({g.st.seed, wi, wj}, [&]() {
        const int64_t ext = ki(g, "dt.SR_CORE") + 2 * ki(g, "dt.SR_APRON"), m = ki(g, "dt.SR_CTX");
        const int64_t a0 = wi * ki(g, "dt.SR_CORE") - ki(g, "dt.SR_APRON"), b0 = wj * ki(g, "dt.SR_CORE") - ki(g, "dt.SR_APRON");
        Tensor coarse = one(callm(g, "_detail_samples", {floordiv(a0, 2) - m, floordiv(b0, 2) - m,
                                                         floordiv(a0 + ext, 2) + m, floordiv(b0 + ext, 2) + m}),
                            "_detail_samples");
        return Fields::of(sr_window(g, coarse, a0, b0, 30.0f, ki(g, "dt.SR_NOISE_STREAM"), "sr30", kf(g, "dt.SR_GAIN"),
                                    look, kf(g, "dt.SR_LOOK_K")));
    });
}
SBTG_METHOD("_sr_window", sr30_window);

Fields sr15_window(Gen& g, const Args& a) {
    need(a, 2, 0, 0, "_sr15_window");
    const int64_t wi = a.i[0], wj = a.i[1];
    const bool look = look_build(g);
    return g.cache(look ? "detail._sr15_windows_look" : "detail._sr15_windows", 256).get_or({g.st.seed, wi, wj}, [&]() {
        const int64_t ext = ki(g, "dt.SR_CORE") + 2 * ki(g, "dt.SR_APRON"), m = ki(g, "dt.SR_CTX");
        const int64_t a0 = wi * ki(g, "dt.SR_CORE") - ki(g, "dt.SR_APRON"), b0 = wj * ki(g, "dt.SR_CORE") - ki(g, "dt.SR_APRON");
        Tensor coarse = one(callm(g, "native", {floordiv(a0, 2) - m, floordiv(b0, 2) - m, floordiv(a0 + ext, 2) + m,
                                                floordiv(b0 + ext, 2) + m}),
                            "native");
        const double gain = knob(g, "sr15_gain", "dt.SR15_GAIN_D");      // generator2 self.sr15_gain (a knob)
        return Fields::of(sr_window(g, coarse, a0, b0, 15.0f, ki(g, "dt.SR15_NOISE_STREAM"), "sr15",
                                    static_cast<float>(gain), look, static_cast<float>(gain - 1.0)));
    });
}
SBTG_METHOD("_sr15_window", sr15_window);

// generator2._sr_blend(i1, j1, i2, j2, window): which 0 = _sr_window, 1 = _sr15_window.
Fields sr_blend(Gen& g, const Args& a) {
    need(a, 5, 0, 0, "_sr_blend");
    const int64_t i1 = a.i[0], j1 = a.i[1], i2 = a.i[2], j2 = a.i[3];
    const char* window = a.i[4] ? "_sr15_window" : "_sr_window";
    const int64_t core = ki(g, "dt.SR_CORE"), ap = ki(g, "dt.SR_APRON"), ext = core + 2 * ap;
    Tensor fade = fade2(g, "sr_fade");
    std::vector<Item> items;
    for (int64_t wi = floordiv(i1 - ext + ap, core); wi <= floordiv(i2 - 1 + ap, core); ++wi)
        for (int64_t wj = floordiv(j1 - ext + ap, core); wj <= floordiv(j2 - 1 + ap, core); ++wj) {
            const int64_t a0 = wi * core - ap, b0 = wj * core - ap;
            if (std::max(i1, a0) >= std::min(i2, a0 + ext) || std::max(j1, b0) >= std::min(j2, b0 + ext)) continue;
            items.push_back({one(callm(g, window, {wi, wj}), window), a0 - i1, b0 - j1});
        }
    return Fields::of(overlap_add(g, 1, i2 - i1, j2 - j1, items, fade)[0]);
}
SBTG_METHOD("_sr_blend", sr_blend);

// generator2.native_fine: SR-15 (or generator.native_fine's bilinear, mean-preserving stand-in without sr15.pt).
Fields native_fine(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "native_fine");
    const int64_t k1 = a.i[0], l1 = a.i[1], k2 = a.i[2], l2 = a.i[3];
    if (ki(g, "dt.HAS_SR15") != 0) return callm(g, "_sr_blend", {k1, l1, k2, l2, 1});
    const int64_t i1 = floordiv(k1, 2) - 1, j1 = floordiv(l1, 2) - 1, i2 = ceildiv(k2, 2) + 1, j2 = ceildiv(l2, 2) + 1;
    Tensor px = one(callm(g, "native", {i1, j1, i2, j2}), "native");
    const int64_t h = px.size(0), w = px.size(1);
    Tensor up = ops::bilinear(g.gpu, px, I(2 * h), I(2 * w), 2.0);
    Tensor out = g.gpu.empty(DType::F32, {2 * h, 2 * w});
    FineFixArgs f{up.f32(), px.f32(), out.f32(), I(h), I(w)};
    run(g, "dk_fine_fix", h * w, f);
    return Fields::of(ops::crop(g.gpu, out, I(k1 - 2 * i1), I(l1 - 2 * j1), I(k2 - k1), I(l2 - l1)));
}
SBTG_METHOD("native_fine", native_fine);

// ------------------------------------------------------------------------------------------- carve
Tensor pool(Gen& g, const Tensor& x, int k, int edge, int in, int neg = 0, const Tensor* x2 = nullptr, int vd = 0) {
    Tensor o = g.gpu.empty_like(x);
    PoolArgs p{x.f32(), x2 != nullptr ? x2->f32() : nullptr, o.f32(), I(x.size(-2)), I(x.size(-1)), k, edge, in, neg, vd};
    run(g, "dk_pool", x.numel(), p);
    return o;
}
// carve_labels.valley_depth: (closing(z, k) - z).clamp_min(0), closing = -maxpool(pad_rep(-maxpool(pad_rep(z))))
Tensor valley_depth(Gen& g, const Tensor& z, int k) {
    Tensor dil = pool(g, z, k, 0, 0);
    return pool(g, dil, k, 0, 1, 1, &z, 1);
}

// tiles.TileBuilder._carve(i1, j1, i2, j2, d) -> int64 codes (i2 - i1, j2 - j1); nothing without a detail model.
Fields carve(Gen& g, const Args& a) {
    need(a, 4, 1, 0, "TileBuilder._carve");
    if (ki(g, "dt.HAS_DETAIL") == 0) return Fields{};
    const int64_t i1 = a.i[0], j1 = a.i[1], i2 = a.i[2], j2 = a.i[3];
    const double d = a.f[0];
    const int64_t ds = ki(g, "dt.DS"), spc = ki(g, "dt.SPC"), m = ki(g, "dt.CARVE_MARGIN");
    int mode, per = 1, di = 1;
    if (d < 1.0) {
        mode = 1;
        per = static_cast<int>(std::nearbyint(static_cast<double>(ds) / d));   // Python round(): half to even
    } else {
        mode = 0;
        if (d != std::floor(d)) throw std::invalid_argument("TileBuilder._carve: non-integral d >= 1");
        di = static_cast<int>(d);
    }
    auto sample_of = [&](int64_t i) { return mode == 0 ? floordiv(i * di, ds) : floordiv(i, per); };
    auto sample_end = [&](int64_t i) { return mode == 0 ? ceildiv(i * di, ds) : ceildiv(i, per); };
    const int64_t s1 = sample_of(i1) - m, t1 = sample_of(j1) - m, s2 = sample_end(i2) + m, t2 = sample_end(j2) + m;
    Tensor z = one(callm(g, "_detail_samples", {s1, t1, s2, t2}), "_detail_samples");
    const int64_t H = s2 - s1, W = t2 - t1;
    const int64_t ci0 = floordiv(s1, spc), cj0 = floordiv(t1, spc);
    const int64_t hc = ceildiv(s2, spc) - ci0, wc = ceildiv(t2, spc) - cj0;
    Fields ctrl = callm(g, "controls", {ci0, cj0, hc, wc});
    Fields dr = callm(g, "drainage", {ci0, cj0, hc, wc});
    const int oi = I(s1 - ci0 * spc), oj = I(t1 - cj0 * spc);
    auto up = [&](const Tensor& cell) {
        Tensor o = g.gpu.empty(DType::F32, {H, W});
        CellUpArgs c{cell.f32(), o.f32(), I(cell.size(-1)), I(H), I(W), oi, oj, I(spc)};
        run(g, "dk_cell_up", H * W, c);
        return o;
    };
    Tensor arid;
    if (ctrl.has("aridity")) {
        arid = up(ctrl["aridity"]);
    } else {
        Tensor p = up(ctrl["precip"]), t = up(ctrl["t0"]);
        arid = g.gpu.empty(DType::F32, {H, W});
        AridArgs r{p.f32(), t.f32(), arid.f32(), H * W};
        run(g, "dk_arid", H * W, r);
    }
    Tensor river, fjord, karst;
    if (dr.has("river")) river = up(dr["river"]);
    if (ctrl.has("gates")) {
        const Tensor& gt = ctrl["gates"];
        fjord = up(gt[ki(g, "dt.STYLE_FJORD")]);
        // Height-curve v2 worlds read `karst2` (FX3: 1 in 8 of land, none around spawn), v1 worlds `karst`
        // (tiles.py _carve: the generator's per-build sea-shelf flag and controls2.KARST_V2).
        const bool v2 = g.st.sea_shelf && ki(g, "dt.KARST_V2") != 0;
        const char* kname = v2 && ctrl.has("karst2") ? "karst2" : "karst";
        karst = ctrl.has(kname) ? up(ctrl[kname]) : up(gt[ki(g, "dt.STYLE_DROWNED")]);
    }
    Tensor vd150 = valley_depth(g, z, I(ki(g, "dt.CARVE_K150")));
    Tensor vd600 = valley_depth(g, z, I(ki(g, "dt.CARVE_K600")));
    Tensor vd2000 = valley_depth(g, z, I(ki(g, "dt.CARVE_K2000")));
    Tensor gp = g.gpu.empty(DType::F32, {3, H, W});
    GradArgs gr{vd600.f32(), gp.f32(), I(H), I(W)};
    run(g, "dk_grad", H * W, gr);
    Tensor J = ops::blur(g.gpu, gp, g.taps(kd(g, "dt.CARVE_STRIKE_SIGMA")));
    Tensor zmax, nzmax;
    if (karst.defined()) {
        const int k = I(2 * ki(g, "dt.CARVE_TOWER_R") + 1);
        zmax = pool(g, z, k, 1, 0);
        nzmax = pool(g, z, k, 1, 1);
    }
    Tensor seapool = pool(g, z, 3, 1, 2);
    Tensor cliff = pool(g, z, I(2 * ki(g, "dt.CARVE_CLIFF_R") + 1), 1, 3);
    Tensor steep = g.gpu.empty(DType::F32, {H, W}), steep_cliff = g.gpu.empty(DType::F32, {H, W});
    SteepArgs sa{z.f32(), seapool.f32(), cliff.f32(), steep.f32(), steep_cliff.f32(), H * W, kf(g, "dt.CARVE_CLIFF_M")};
    run(g, "dk_steep", H * W, sa);
    const int kr = I(2 * ki(g, "dt.CARVE_REACH") + 1);
    Tensor nearp = pool(g, steep, kr, 1, 0), expo = pool(g, steep_cliff, kr, 1, 0);
    Tensor out = g.gpu.empty(DType::I64, {i2 - i1, j2 - j1});
    CarveArgs c{};
    c.z = z.f32();
    c.vd150 = vd150.f32();
    c.vd600 = vd600.f32();
    c.vd2000 = vd2000.f32();
    c.J = J.f32();
    c.arid = arid.f32();
    c.river = river.defined() ? river.f32() : nullptr;
    c.fjord = fjord.defined() ? fjord.f32() : nullptr;
    c.karst = karst.defined() ? karst.f32() : nullptr;
    c.zmax = zmax.defined() ? zmax.f32() : nullptr;
    c.nzmax = nzmax.defined() ? nzmax.f32() : nullptr;
    c.near = nearp.f32();
    c.expo = expo.f32();
    c.out = out.i64();
    c.i1 = i1;
    c.j1 = j1;
    c.s1 = s1;
    c.t1 = t1;
    c.mode = mode;
    c.per = per;
    c.d = di;
    c.ds = I(ds);
    c.H = I(H);
    c.W = I(W);
    c.n_rows = I(i2 - i1);
    c.n_cols = I(j2 - j1);
    c.d0 = kf(g, "dt.CARVE_D0");
    c.inv_dspan = inv(kf(g, "dt.CARVE_DSPAN"));
    c.slot = kf(g, "dt.CARVE_SLOT");
    c.arid_max = kf(g, "dt.CARVE_ARID");
    c.gorge = kf(g, "dt.CARVE_GORGE");
    c.rift = kf(g, "dt.CARVE_RIFT");
    c.rift_coh = kf(g, "dt.CARVE_RIFT_COH");
    c.humid = kf(g, "dt.CARVE_HUMID");
    c.tower_m = kf(g, "dt.CARVE_TOWER_M");
    c.inv_cliff_dens = inv(kf(g, "dt.CARVE_CLIFF_DENS"));
    const double kPi = 3.141592653589793;   // math.pi
    c.pi = static_cast<float>(kPi);
    c.inv_pi = 1.0f / static_cast<float>(kPi);
    c.eps = static_cast<float>(1e-6);
    run(g, "dk_carve", (i2 - i1) * (j2 - j1), c);
    return Fields::of(out);
}
SBTG_METHOD("TileBuilder._carve", carve);

// The native side's count of guard failures (Python: generator.detail_failures).
Fields detail_failures(Gen& g, const Args&) {
    long long n;
    {
        std::lock_guard<std::mutex> l(g_fail_mu);
        n = g_failures[&g];
    }
    Tensor h = g.gpu.empty(DType::I64, {1}, true);
    h.i64()[0] = n;
    return Fields::of(g.gpu.to_device(h));
}
SBTG_METHOD("_detail_failures", detail_failures);

} // namespace
} // namespace sbtg

