// generator._flatten_swamps / generator.river_field and the native river stages (river/geometry.py, banks.py,
// contain.py): Centrelines -> ChannelGeometry -> WaterSurface -> (Learned|Levee)Banks -> BankGuard -> UBed -> NoSpill
// -> MonotoneTop, bit for bit (the learned bank network excepted: see river.bank_net).
#include "stages/river/river.hpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <functional>

namespace sbtg::river {

namespace {
const char kOps[] =
#include "river_ops.cu.inc"
    ;
SBTG_MODULE("river_ops", kOps);
// kernels/csrc/river.cu (the DaedalusTGM source itself): mono_step / grow_step of kernels/cuda_river.py
const char kCsrcRiver[] =
#include "csrc_river.cu.inc"
    ;
SBTG_MODULE("csrc_river", kCsrcRiver);

// ------------------------------------------------------------------ kernel argument structs (twins of river_ops.cu)
struct FlattenArgs { const float* h; const float* w; const float* t; float* out; long long n; };
struct AnyArgs { const float* x; int* flag; long long n; };
struct AnyU8Args { const uint8_t* x; int* flag; long long n; };
struct RidgeArgs {
    const float* s; const float* up; uint8_t* weak; float* grown; float* wk; int H, W;
    float thr_lo, thr, inv_w, inv_h, half_pi;
};
struct MaskArgs { const uint8_t* in; uint8_t* out; int H, W; };
struct RegrowArgs { const uint8_t* tips; const uint8_t* kept; const float* line; uint8_t* tips_out; uint8_t* kept_out; int H, W; };
struct GtArgs { const float* x; uint8_t* out; long long n; };
struct EdtArgs { const uint8_t* m; const int* row; int* out; int H, W, R; };
struct ChebArgs { const uint8_t* m; const int* row; int* out; int H, W, cap, target; };
struct ExLineArgs { const float* flow; const uint8_t* line; float* out; long long n; float thr; };
struct MaxSepArgs { const float* in; float* out; int H, W, r, axis; };
struct ChannelArgs {
    const float* line_ex; const uint8_t* line; const int* d2; uint8_t* channel; float* half_px; float* depth;
    long long n; int max_half; float wmin, wper, wmax, dmin, dper, dmax, ppb;
};
struct EdgeArgs { const uint8_t* channel; const int* cheb; float* out; long long n; int cap; };
struct SurfPlanesArgs { const float* g; const uint8_t* line; const uint8_t* channel; float* out; long long n; };
struct RatioArgs { const float* b; float* out0; float* out1; long long n; };
struct MonoPrepArgs { const float* level; const uint8_t* line; const float* flow; float* lv; uint8_t* ups; int H, W; };
struct WhereArgs { const uint8_t* m; const float* a; const float* b; float* out; long long n; };
struct PropInitArgs { const float* values; const uint8_t* seed; float* v; float* done; long long n; };
struct PropStepArgs { const float* v; const float* done; const uint8_t* region; float* v_out; float* done_out; int H, W; };
struct Level0Args {
    const float* across; const float* done; const float* in_chan; const uint8_t* channel; float* level0; float* planes;
    long long n;
};
struct SpreadArgs { const float* level0; const float* b; const uint8_t* channel; float* out; long long n; };
struct BandArgs { const int* d2; const uint8_t* channel; uint8_t* band; long long n; int r; };
struct BankRelArgs {
    const float* base; const float* level; const float* rate; const uint8_t* band; const uint8_t* channel;
    float* planes; float* out; uint8_t* done; long long n;
};
struct FillArgs { const float* b; float* out; uint8_t* done; long long n; };
struct BankXArgs {
    const float* out; const uint8_t* band; const uint8_t* channel; const int* cheb_dry; const int* cheb_wet; float* x;
    int H, W, Hp, Wp, cap; float inv_rel;
};
struct BankApplyArgs {
    const float* pred; const float* base; const float* level; const float* rate; const uint8_t* band;
    const uint8_t* channel; const int* cheb_dry; const int* cheb_wet; float* carved; int H, W, Wp, cap;
    float rel, band_px, inv_rim;
};
struct LeveeArgs { const float* base; const float* level; const uint8_t* band; float* carved; long long n; };
struct GuardPreArgs { const uint8_t* channel; const float* ground; const float* level; uint8_t* ring; float* water; int H, W; };
struct GuardApplyArgs { const uint8_t* ring; const float* water; const float* rate; float* carved; long long n; float margin; };
struct UBedArgs {
    const float* level; const float* rate; const float* edge; const float* half; const float* depth; const float* ground;
    const uint8_t* channel; float* carved; long long n; float extra;
};
struct NoSpillWallArgs {
    const float* carved; const uint8_t* channel; const float* ground; const float* sea; float* negwall; uint8_t* wet;
    long long n;
};
struct NoSpillArgs { const float* negwall; const float* level; const uint8_t* wet; float* carved; float* top; int H, W; float min_water; };
struct MTopApplyArgs { const uint8_t* wet; const float* mono; const float* rate; float* carved; float* top; long long n; };
// csrc_river (kernels/cuda_river.py _Mono / _Grow)
struct MonoArgs { const float* lv; const uint8_t* ups; float* out; int* changed; int H, W, step; };
struct GrowArgs { const float* grown; const float* weak; float* out; int* changed; int H, W, step; };

constexpr double kNativeM = 30.0;   // river/scale.py NATIVE_M

void hash_json(const Json& j, size_t& h) {
    auto mix = [&h](size_t v) { h ^= v + 0x9e3779b97f4a7c15ull + (h << 6) + (h >> 2); };
    mix(static_cast<size_t>(j.kind()));
    switch (j.kind()) {
    case Json::Bool: mix(j.boolean() ? 1 : 0); break;
    case Json::Number: mix(std::hash<std::string>{}(j.text())); break;
    case Json::String: mix(std::hash<std::string>{}(j.str())); break;
    case Json::Array:
        for (const Json& x : j.items()) hash_json(x, h);
        break;
    case Json::Object:
        for (const auto& [k, v] : j.members()) {
            mix(std::hash<std::string>{}(k));
            hash_json(v, h);
        }
        break;
    default: break;
    }
}
} // namespace

// ------------------------------------------------------------------ shared helpers
Scale get_scale(Gen& g) {
    Scale sc;
    sc.raw = g.call("block_scale", {});
    sc.s.elev = sc.raw["elev"];
    sc.s.blocks = sc.raw["blocks"];
    const Json& raw = g.st.raw;
    if (raw.has("world") && raw["world"].kind() == Json::Object) {
        const Json& w = raw["world"];
        sc.s.horizontal_m = w["horizontal_m"].num();
        sc.s.sea_level = w["sea_level"].i64();
        sc.s.world_height = w["world_height"].i64();
        sc.s.min_y = w.i64_or("min_y", 0);
    } else if (raw.has("block_scale") && raw["block_scale"].has("horizontal_m")) {
        const Json& b = raw["block_scale"];
        sc.s.horizontal_m = b["horizontal_m"].num();
        sc.s.sea_level = b["sea_level"].i64();
        sc.s.world_height = b["world_height"].i64();
        sc.s.min_y = b.i64_or("min_y", 0);
    } else {
        sc.s = scale::get(g);
    }
    return sc;
}

int64_t state_key(const Gen& g) {
    size_t h = std::hash<std::string>{}(g.st.climate_layout);
    h ^= (g.st.sea_shelf ? 0x51ull : 0x17ull) + (h << 6);
    hash_json(g.st.raw, h);
    return static_cast<int64_t>(h);
}

Tensor edt2(Gen& g, const Tensor& m, int R) {
    const int H = static_cast<int>(m.size(0)), W = static_cast<int>(m.size(1));
    Tensor row = i32(g, H, W), out = i32(g, H, W);
    g.gpu.launch(K(g, "rv_edt_row"), m.numel(), EdtArgs{u8p(m), nullptr, row.i32(), H, W, R});
    g.gpu.launch(K(g, "rv_edt_col"), m.numel(), EdtArgs{u8p(m), row.i32(), out.i32(), H, W, R});
    return out;
}

Tensor cheb(Gen& g, const Tensor& m, int target, int cap) {
    const int H = static_cast<int>(m.size(0)), W = static_cast<int>(m.size(1));
    Tensor row = i32(g, H, W), out = i32(g, H, W);
    g.gpu.launch(K(g, "rv_cheb_row"), m.numel(), ChebArgs{u8p(m), nullptr, row.i32(), H, W, cap, target});
    g.gpu.launch(K(g, "rv_cheb_col"), m.numel(), ChebArgs{u8p(m), row.i32(), out.i32(), H, W, cap, target});
    return out;
}

// The fixed point is reached well before the cap in practice; running every step (no convergence readback, unlike
// kernels/cuda_river.py's check every 8 steps) gives the same numbers: a step at the fixed point changes nothing.
Tensor monotone_downstream(Gen& g, const Tensor& level, const Tensor& line, const Tensor& flow, int iters) {
    const int H = static_cast<int>(level.size(0)), W = static_cast<int>(level.size(1));
    Tensor lv = f32(g, H, W), ups = g.gpu.empty(DType::U8, {H, W});
    g.gpu.launch(K(g, "rv_mono_prep"), level.numel(),
                 MonoPrepArgs{level.f32(), u8p(line), flow.f32(), lv.f32(), u8p(ups), H, W});
    if (iters > 0) {
        Tensor b = f32(g, H, W);
        Tensor changed = g.gpu.zeros(DType::I32, {iters});
        ci_kernel* k = g.gpu.kernel("csrc_river", "mono_step");
        for (int s = 0; s < iters; ++s) {
            g.gpu.launch(k, level.numel(), MonoArgs{lv.f32(), u8p(ups), b.f32(), changed.i32(), H, W, s});
            std::swap(lv, b);
        }
    }
    Tensor out = f32(g, H, W);
    g.gpu.launch(K(g, "rv_where"), level.numel(), WhereArgs{u8p(line), lv.f32(), level.f32(), out.f32(), level.numel()});
    return out;
}

bool any(Gen& g, const Tensor& m) {
    Tensor flag = g.gpu.zeros(DType::I32, {1});
    g.gpu.launch(K(g, "rv_any_u8"), m.numel(), AnyU8Args{u8p(m), flag.i32(), m.numel()});
    Tensor h = g.gpu.to_host(flag);
    g.gpu.sync();
    return h.data<int32_t>()[0] != 0;
}

namespace {

// ================================================================== _flatten_swamps
// generator2._flatten_swamps(heights, i1, j1): swamp_relief, then generator2.shelf when the world has the shelf.
Fields flatten_swamps(Gen& g, const Args& a) {
    need(a, 2, 0, 1, "_flatten_swamps");
    const Tensor& heights = a.t[0];
    heights.check(DType::F32);
    const int64_t i1 = a.i[0], j1 = a.i[1];
    const int64_t h = heights.size(-2), w = heights.size(-1);
    const int64_t CP = g.manifest["constants"]["CP"].i64();
    const int64_t ci0 = floordiv(i1, CP) - 1, cj0 = floordiv(j1, CP) - 1;
    const int64_t hc = floordiv(i1 + h - 1, CP) + 2 - ci0, wc = floordiv(j1 + w - 1, CP) + 2 - cj0;
    Fields sr = g.call("swamp_relief_cells", {{ci0, cj0, hc, wc}, {}, {}});
    const Tensor& weight = sr["0"];
    const Tensor& table = sr["1"];
    Tensor flag = g.gpu.zeros(DType::I32, {1});
    g.gpu.launch(K(g, "rv_any_pos"), weight.numel(), AnyArgs{weight.f32(), flag.i32(), weight.numel()});
    Tensor fh = g.gpu.to_host(flag);
    g.gpu.sync();
    Tensor out = heights;
    if (fh.data<int32_t>()[0] != 0) {
        const int oi = static_cast<int>(i1 - ci0 * CP), oj = static_cast<int>(j1 - cj0 * CP);
        auto up = [&](const Tensor& c) {
            Tensor u = ops::bilinear(g.gpu, c, static_cast<int>(hc * CP), static_cast<int>(wc * CP));
            return ops::crop(g.gpu, u, oi, oj, static_cast<int>(h), static_cast<int>(w));
        };
        Tensor wu = up(weight), tu = up(table);
        out = g.gpu.empty_like(heights);
        g.gpu.launch(K(g, "rv_swamp_flatten"), heights.numel(),
                     FlattenArgs{heights.f32(), wu.f32(), tu.f32(), out.f32(), heights.numel()});
    }
    // generator2._flatten_swamps: self.shelf(heights, i1, j1, CP) -- the coastal shelf of v2 worlds (distance-aware
    // profile + lake-bed cap, or the pointwise remap with SEA_PROFILE off: the method decides); a no-op without it.
    if (g.st.sea_shelf) out = g.call("shelf", {{i1, j1, CP}, {}, {out}}).one();
    return Fields::of(out);
}
SBTG_METHOD("_flatten_swamps", flatten_swamps);

// ================================================================== the native river stages
struct Field {
    Tensor ground, flow, line, channel, edge, half, depth, level, band, carved, wet, top;
};

double cfgd(Gen& g, const char* k) { return g.c(std::string("RIVER_CFG_") + k); }

void centrelines(Gen& g, Field& f, const Tensor& logacc, int64_t oi, int64_t oj) {
    const int64_t CP = g.manifest["constants"]["CP"].i64();
    const int hc = static_cast<int>(logacc.size(0)), wc = static_cast<int>(logacc.size(1));
    const int Hu = static_cast<int>(hc * CP), Wu = static_cast<int>(wc * CP);
    Tensor up = ops::bilinear(g.gpu, logacc, Hu, Wu);
    Tensor s = ops::blur(g.gpu, up, g.taps(cfgd(g, "ridge_smooth_px")));
    const double thr = cfgd(g, "log_threshold");
    const double thr_lo = thr - g.c("RIVER_HYSTERESIS");
    Tensor weak = g.gpu.empty(DType::U8, {Hu, Wu});
    Tensor grown = f32(g, Hu, Wu), wk = f32(g, Hu, Wu);
    const float inv_w = 1.0f / static_cast<float>(std::max(1, Wu - 1));
    const float inv_h = 1.0f / static_cast<float>(std::max(1, Hu - 1));
    const float half_pi = static_cast<float>(g.c("RIVER_PI") / 2);
    g.gpu.launch(K(g, "rv_ridge"), up.numel(),
                 RidgeArgs{s.f32(), up.f32(), u8p(weak), grown.f32(), wk.f32(), Hu, Wu, static_cast<float>(thr_lo),
                           static_cast<float>(thr), inv_w, inv_h, half_pi});
    const int reach = static_cast<int>(g.c("RIVER_REACH_PX"));
    if (reach > 0) {
        Tensor b = f32(g, Hu, Wu);
        Tensor changed = g.gpu.zeros(DType::I32, {reach});
        ci_kernel* k = g.gpu.kernel("csrc_river", "grow_step");
        for (int st = 0; st < reach; ++st) {
            g.gpu.launch(k, up.numel(), GrowArgs{grown.f32(), wk.f32(), b.f32(), changed.i32(), Hu, Wu, st});
            std::swap(grown, b);
        }
    }
    // prune_spurs(grown > 0, spur_px)
    const int spur = static_cast<int>(g.c("RIVER_SPUR_PX"));
    Tensor kept = g.gpu.empty(DType::BOOL, {Hu, Wu}), tmp = g.gpu.empty(DType::BOOL, {Hu, Wu});
    g.gpu.launch(K(g, "rv_gt0"), up.numel(), GtArgs{grown.f32(), u8p(kept), up.numel()});
    if (spur > 0) {
        for (int st = 0; st < spur; ++st) {
            g.gpu.launch(K(g, "rv_peel"), up.numel(), MaskArgs{u8p(kept), u8p(tmp), Hu, Wu});
            std::swap(kept, tmp);
        }
        Tensor tips = g.gpu.empty(DType::U8, {Hu, Wu}), tips2 = g.gpu.empty(DType::U8, {Hu, Wu});
        g.gpu.launch(K(g, "rv_tips"), up.numel(), MaskArgs{u8p(kept), u8p(tips), Hu, Wu});
        for (int st = 0; st < spur; ++st) {
            g.gpu.launch(K(g, "rv_regrow"), up.numel(),
                         RegrowArgs{u8p(tips), u8p(kept), grown.f32(), u8p(tips2), u8p(tmp), Hu, Wu});
            std::swap(tips, tips2);
            std::swap(kept, tmp);
        }
    }
    const int h = static_cast<int>(f.ground.size(0)), w = static_cast<int>(f.ground.size(1));
    f.line = ops::crop(g.gpu, kept, static_cast<int>(oi), static_cast<int>(oj), h, w);
    f.flow = ops::crop(g.gpu, s, static_cast<int>(oi), static_cast<int>(oj), h, w);
}

void channel_geometry(Gen& g, Field& f, double ppb) {
    const int H = static_cast<int>(f.ground.size(0)), W = static_cast<int>(f.ground.size(1));
    const long long n = f.ground.numel();
    const int max_half = static_cast<int>(std::ceil(cfgd(g, "width_max_blocks") * ppb / 2));
    Tensor ex = f32(g, H, W), tmp = f32(g, H, W), lex = f32(g, H, W);
    g.gpu.launch(K(g, "rv_ex_line"), n,
                 ExLineArgs{f.flow.f32(), u8p(f.line), ex.f32(), n, static_cast<float>(cfgd(g, "log_threshold"))});
    g.gpu.launch(K(g, "rv_maxpool_sep"), n, MaxSepArgs{ex.f32(), tmp.f32(), H, W, max_half, 0});
    g.gpu.launch(K(g, "rv_maxpool_sep"), n, MaxSepArgs{tmp.f32(), lex.f32(), H, W, max_half, 1});
    Tensor d2 = edt2(g, f.line, max_half);
    f.channel = mask(g, H, W);
    f.half = f32(g, H, W);
    f.depth = f32(g, H, W);
    g.gpu.launch(K(g, "rv_channel"), n,
                 ChannelArgs{lex.f32(), u8p(f.line), d2.i32(), u8p(f.channel), f.half.f32(), f.depth.f32(), n, max_half,
                             static_cast<float>(cfgd(g, "width_min_blocks")), static_cast<float>(cfgd(g, "width_per_log")),
                             static_cast<float>(cfgd(g, "width_max_blocks")), static_cast<float>(cfgd(g, "depth_min_blocks")),
                             static_cast<float>(cfgd(g, "depth_per_log")), static_cast<float>(cfgd(g, "depth_max_blocks")),
                             static_cast<float>(ppb)});
    const int cap = max_half + 1;
    Tensor cd = cheb(g, f.channel, 0, cap);
    f.edge = f32(g, H, W);
    g.gpu.launch(K(g, "rv_edge"), n, EdgeArgs{u8p(f.channel), cd.i32(), f.edge.f32(), n, cap});
}

void water_surface(Gen& g, Field& f, double ppb) {
    const int H = static_cast<int>(f.ground.size(0)), W = static_cast<int>(f.ground.size(1));
    const long long n = f.ground.numel();
    Tensor planes = g.gpu.empty(DType::F32, {4, H, W});
    g.gpu.launch(K(g, "rv_surf_planes"), n, SurfPlanesArgs{f.ground.f32(), u8p(f.line), u8p(f.channel), planes.f32(), n});
    Tensor b = ops::blur(g.gpu, planes, g.taps(cfgd(g, "surface_sigma_px")));
    Tensor on_line = f32(g, H, W), in_chan = f32(g, H, W);
    g.gpu.launch(K(g, "rv_ratio"), n, RatioArgs{b.f32(), on_line.f32(), in_chan.f32(), n});
    const int iters = static_cast<int>(g.c("RIVER_SURFACE_MONO_ITERS"));
    if (iters > 0) on_line = monotone_downstream(g, on_line, f.line, f.flow, iters);
    // propagate(on_line, line, channel, steps); reached = propagate(ones, ...) > 0.5 is exactly the `done` mask
    const int steps = static_cast<int>(std::ceil(cfgd(g, "width_max_blocks") * ppb / 2)) + 2;
    Tensor v = f32(g, H, W), done = f32(g, H, W), v2 = f32(g, H, W), done2 = f32(g, H, W);
    g.gpu.launch(K(g, "rv_prop_init"), n, PropInitArgs{on_line.f32(), u8p(f.line), v.f32(), done.f32(), n});
    for (int s = 0; s < steps; ++s) {
        g.gpu.launch(K(g, "rv_prop_step"), n,
                     PropStepArgs{v.f32(), done.f32(), u8p(f.channel), v2.f32(), done2.f32(), H, W});
        std::swap(v, v2);
        std::swap(done, done2);
    }
    Tensor level0 = f32(g, H, W), pl = g.gpu.empty(DType::F32, {2, H, W});
    g.gpu.launch(K(g, "rv_level0"), n,
                 Level0Args{v.f32(), done.f32(), in_chan.f32(), u8p(f.channel), level0.f32(), pl.f32(), n});
    Tensor sb = ops::blur(g.gpu, pl, g.taps(cfgd(g, "bank_band_blocks") * ppb));
    f.level = f32(g, H, W);
    g.gpu.launch(K(g, "rv_spread"), n, SpreadArgs{level0.f32(), sb.f32(), u8p(f.channel), f.level.f32(), n});
}

// LearnedBanks / LeveeBanks. Returns the scale's rate(level) when it computed it (UBed reuses it: same level).
Tensor banks(Gen& g, Field& f, const Scale& sc, bool has_river) {
    const int H = static_cast<int>(f.ground.size(0)), W = static_cast<int>(f.ground.size(1));
    const long long n = f.ground.numel();
    const double ppb = sc.ppb();
    const Tensor base = f.carved;
    f.band = mask(g, H, W);
    if (!has_river) {
        g.gpu.memset0(f.band);
        return {};
    }
    if (!g.manifest["constants"]["RIVER_LEARNED_BANKS"].boolean()) {
        const int r = static_cast<int>(g.c("RIVER_LEVEE_PX"));
        Tensor d2 = edt2(g, f.channel, r);
        g.gpu.launch(K(g, "rv_band"), n, BandArgs{d2.i32(), u8p(f.channel), u8p(f.band), n, r});
        f.carved = f32(g, H, W);
        g.gpu.launch(K(g, "rv_levee"), n, LeveeArgs{base.f32(), f.level.f32(), u8p(f.band), f.carved.f32(), n});
        return {};
    }
    const int r = static_cast<int>(std::nearbyint(cfgd(g, "bank_band_blocks") * ppb));   // Python round(): half-even
    Tensor d2 = edt2(g, f.channel, r);
    g.gpu.launch(K(g, "rv_band"), n, BandArgs{d2.i32(), u8p(f.channel), u8p(f.band), n, r});
    Tensor rate = scale::rate(g, sc.s, f.level);
    Tensor planes = g.gpu.empty(DType::F32, {2, H, W}), out = f32(g, H, W), done = g.gpu.empty(DType::U8, {H, W});
    g.gpu.launch(K(g, "rv_bank_rel"), n,
                 BankRelArgs{base.f32(), f.level.f32(), rate.f32(), u8p(f.band), u8p(f.channel), planes.f32(), out.f32(),
                             u8p(done), n});
    for (const Json& s : g.manifest["constants"]["RIVER_FILL_SIGMAS"].items()) {
        Tensor b = ops::blur(g.gpu, planes, g.taps(s.num()));
        g.gpu.launch(K(g, "rv_fill_step"), n, FillArgs{b.f32(), out.f32(), u8p(done), n});
    }
    const int cap = static_cast<int>(g.c("RIVER_SD_CAP_PX"));
    Tensor cdry = cheb(g, f.channel, 0, cap), cwet = cheb(g, f.channel, 1, cap);
    const Json& bank = g.manifest["river_bank"];
    const int mult = 1 << (static_cast<int>(bank["channels"].size()) - 1);
    const int Hp = H + (mult - H % mult) % mult, Wp = W + (mult - W % mult) % mult;
    Tensor x = g.gpu.empty(DType::F32, {1, 4, Hp, Wp});
    const float inv_rel = 1.0f / static_cast<float>(g.c("RIVER_REL_SCALE_BLOCKS"));
    g.gpu.launch(K(g, "rv_bank_x"), static_cast<long long>(Hp) * Wp,
                 BankXArgs{out.f32(), u8p(f.band), u8p(f.channel), cdry.i32(), cwet.i32(), x.f32(), H, W, Hp, Wp, cap,
                           inv_rel});
    Tensor pred = g.call("river.bank_net", {{}, {}, {x}}).one();
    const double rim = ppb * 1.5;
    f.carved = f32(g, H, W);
    g.gpu.launch(K(g, "rv_bank_apply"), n,
                 BankApplyArgs{pred.f32(), base.f32(), f.level.f32(), rate.f32(), u8p(f.band), u8p(f.channel), cdry.i32(),
                               cwet.i32(), f.carved.f32(), H, W, Wp, cap,
                               static_cast<float>(g.c("RIVER_REL_SCALE_BLOCKS")),
                               static_cast<float>(cfgd(g, "bank_band_blocks") * ppb), 1.0f / static_cast<float>(rim)});
    return rate;
}

void bank_guard(Gen& g, Field& f, const Scale& sc) {
    const int H = static_cast<int>(f.ground.size(0)), W = static_cast<int>(f.ground.size(1));
    const long long n = f.ground.numel();
    Tensor ring = g.gpu.empty(DType::U8, {H, W}), water = f32(g, H, W);
    g.gpu.launch(K(g, "rv_guard_pre"), n,
                 GuardPreArgs{u8p(f.channel), f.ground.f32(), f.level.f32(), u8p(ring), water.f32(), H, W});
    Tensor rate = scale::rate(g, sc.s, water);
    g.gpu.launch(K(g, "rv_guard_apply"), n,
                 GuardApplyArgs{u8p(ring), water.f32(), rate.f32(), f.carved.f32(), n,
                                static_cast<float>(g.c("RIVER_GUARD_MARGIN_BLOCKS"))});
}

void ubed(Gen& g, Field& f, const Scale& sc, Tensor rate) {
    const long long n = f.ground.numel();
    if (!rate.defined()) rate = scale::rate(g, sc.s, f.level);
    g.gpu.launch(K(g, "rv_ubed"), n,
                 UBedArgs{f.level.f32(), rate.f32(), f.edge.f32(), f.half.f32(), f.depth.f32(), f.ground.f32(),
                          u8p(f.channel), f.carved.f32(), n, static_cast<float>(cfgd(g, "extra_incision_blocks"))});
}

void nospill(Gen& g, Field& f, const Scale& sc, bool has_river) {
    const int H = static_cast<int>(f.ground.size(0)), W = static_cast<int>(f.ground.size(1));
    const long long n = f.ground.numel();
    f.wet = mask(g, H, W);
    f.top = f32(g, H, W);
    if (!has_river) {
        g.gpu.memset0(f.wet);
        f.top = g.gpu.full(std::nanf(""), {H, W});
        return;
    }
    Tensor sea_b = g.gpu.full(static_cast<float>(sc.s.sea_level), {1});
    Tensor sea = scale::to_metres(g, sc.s, sea_b);
    Tensor negwall = f32(g, H, W);
    g.gpu.launch(K(g, "rv_nospill_wall"), n,
                 NoSpillWallArgs{f.carved.f32(), u8p(f.channel), f.ground.f32(), sea.f32(), negwall.f32(), u8p(f.wet), n});
    g.gpu.launch(K(g, "rv_nospill"), n,
                 NoSpillArgs{negwall.f32(), f.level.f32(), u8p(f.wet), f.carved.f32(), f.top.f32(), H, W,
                             static_cast<float>(g.c("RIVER_MIN_WATER_M"))});
}

void monotone_top(Gen& g, Field& f, const Scale& sc) {
    const long long n = f.ground.numel();
    const int H = static_cast<int>(f.ground.size(0)), W = static_cast<int>(f.ground.size(1));
    Tensor top0 = f32(g, H, W);
    g.gpu.launch(K(g, "rv_where"), n, WhereArgs{u8p(f.wet), f.top.f32(), f.carved.f32(), top0.f32(), n});
    Tensor mono = monotone_downstream(g, top0, f.wet, f.flow, static_cast<int>(g.c("RIVER_TOP_MONO_ITERS")));
    Tensor rate = scale::rate(g, sc.s, mono);
    g.gpu.launch(K(g, "rv_mtop_apply"), n,
                 MTopApplyArgs{u8p(f.wet), mono.f32(), rate.f32(), f.carved.f32(), f.top.f32(), n});
}

// ================================================================== river_field
Fields compute_river_field(Gen& g, int64_t i1, int64_t j1, int64_t i2, int64_t j2) {
    const int64_t CP = g.manifest["constants"]["CP"].i64();
    const int64_t hp = static_cast<int64_t>(g.c("RIVER_HALO_PX"));
    const int64_t ch = static_cast<int64_t>(g.c("RIVER_CELL_HALO"));
    const int64_t a1 = i1 - hp, b1 = j1 - hp, a2 = i2 + hp, b2 = j2 + hp;
    Tensor heights = soft_cap_peaks(g, g.call("native", {{a1, b1, a2, b2}, {}, {}}).one());
    heights = g.call("_flatten_swamps", {{a1, b1}, {}, {heights}}).one();
    const int64_t ci0 = floordiv(a1, CP) - ch, cj0 = floordiv(b1, CP) - ch;
    const int64_t ci1 = ceildiv(a2, CP) + ch, cj1 = ceildiv(b2, CP) + ch;
    // cells(...)["logacc" / "d8"] are drainage(...)'s whenever drainage exists (hydro sidecar / routed); cells only
    // without it (the planner's logacc, no D8).
    Fields dr = g.call("drainage", {{ci0, cj0, ci1 - ci0, cj1 - cj0}, {}, {}});
    Tensor logacc, d8;
    if (dr.has("logacc")) {
        logacc = dr["logacc"];
        if (dr.has("d8")) d8 = dr["d8"];
    } else {
        Fields c = g.call("cells", {{ci0, cj0, ci1 - ci0, cj1 - cj0}, {}, {}});
        logacc = c["logacc"];
        if (c.has("d8")) d8 = c["d8"];
    }
    const Scale sc = get_scale(g);
    const double ppb = sc.s.horizontal_m / kNativeM;
    static const bool prof = std::getenv("SBTG_RIVER_PROFILE") != nullptr;
    auto t0 = std::chrono::steady_clock::now();
    auto lap = [&](const char* what) {
        if (!prof) return;
        g.gpu.sync();
        auto t1 = std::chrono::steady_clock::now();
        std::fprintf(stderr, "[river] %-12s %7.2f ms\n", what, std::chrono::duration<double, std::milli>(t1 - t0).count());
        t0 = t1;
    };
    lap("deps");
    Field f;
    f.ground = heights;
    f.carved = heights;
    centrelines(g, f, logacc, a1 - ci0 * CP, b1 - cj0 * CP);
    lap("centrelines");
    channel_geometry(g, f, ppb);
    lap("channel");
    water_surface(g, f, ppb);
    lap("surface");
    const bool has_river = any(g, f.channel);
    Tensor rate = banks(g, f, sc, has_river);
    lap("banks");
    if (has_river) {
        bank_guard(g, f, sc);
        ubed(g, f, sc, rate);
    }
    nospill(g, f, sc, has_river);
    if (has_river) monotone_top(g, f, sc);
    lap("guard..top");

    const int H = static_cast<int>(heights.size(0)), W = static_cast<int>(heights.size(1));
    const int h = static_cast<int>(i2 - i1), w = static_cast<int>(j2 - j1);
    auto crop = [&](const Tensor& t) {
        (void)H;
        (void)W;
        return ops::crop(g.gpu, t, static_cast<int>(hp), static_cast<int>(hp), h, w);
    };
    Fields out;
    out.set("0.ground", crop(f.ground));
    out.set("0.flow", crop(f.flow));
    out.set("0.line", crop(f.line));
    out.set("0.channel", crop(f.channel));
    out.set("0.edge_px", crop(f.edge));
    out.set("0.half_px", crop(f.half));
    out.set("0.depth_blocks", crop(f.depth));
    out.set("0.level", crop(f.level));
    out.set("0.band", crop(f.band));
    out.set("0.carved", crop(f.carved));
    out.set("0.wet", crop(f.wet));
    out.set("0.top", crop(f.top));
    // RiverContext: Python's flattening keeps its ints (px_origin, seed, the integer stats of a riverless field); the
    // drainage D8 goes to river.columns through a stage cache (river_d8) instead.
    if (!has_river) {
        out.set("1.stats.guard_raised_px", host_i64(g, 0));
        out.set("1.stats.incised_px", host_i64(g, 0));
    }
    if (d8.defined()) g.cache("river_d8", 4).put(d8_key(g, i1, j1, i2, j2), Fields::of(d8));
    out.set("1.px_origin.0", host_i64(g, i1));
    out.set("1.px_origin.1", host_i64(g, j1));
    out.set("1.seed", host_i64(g, g.st.seed));
    return out;
}

} // namespace

Cache::Key d8_key(const Gen& g, int64_t i1, int64_t j1, int64_t i2, int64_t j2) {
    return {g.st.seed, state_key(g), i1, j1, i2, j2};
}

namespace {

Fields river_field(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "river_field");
    const int64_t i1 = a.i[0], j1 = a.i[1], i2 = a.i[2], j2 = a.i[3];
    if (i2 <= i1 || j2 <= j1) throw std::invalid_argument("river_field: empty frame");
    // Values are pure functions of the key (state, frame, the bank network's routing): river.columns re-reads the
    // field river_field just built for the same tile.
    Cache::Key key{g.st.seed, state_key(g), i1, j1, i2, j2, g.native("river.bank_net") ? 1 : 0};
    return g.cache("river_field", 2).get_or(key, [&] { return compute_river_field(g, i1, j1, i2, j2); });
}
SBTG_METHOD("river_field", river_field);

} // namespace
} // namespace sbtg::river
