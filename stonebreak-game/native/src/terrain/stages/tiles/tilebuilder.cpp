// world/tiles.py TileBuilder: _classify, _blocks, _blocks_fine, _overview, _planes, build (registered "TileBuilder.*").
// The world comes from the host state (g.st.raw["world"]); every other generator method through g.call().
#include "stages/common/common.hpp"
#include "stages/tiles/surface.hpp"
#include "stages/tiles/tiles.hpp"

#include <cmath>

namespace sbtg::tiles {

namespace {
const char kSrc[] =
#include "tiles_tile.cu.inc"
    ;
SBTG_MODULE("tiles_tile", kSrc);

ci_kernel* tk(Gen& g, const char* name) { return g.gpu.kernel("tiles_tile", name); }

struct QuantArgs {
    const float* x;
    void* y;
    long long n;
    float lo, hi, add;
    int mode;
};
struct FineGroundArgs {
    const float* carved;
    const float* base;
    const float* fine;
    float* ground;
    int H, W, k;
    float inv_keep;
};
struct FlattenArgs {
    const float* h;
    const float* w;
    const float* tb;
    float* y;
    long long n;
};
struct OverviewArgs {
    const float* peak;
    const int64_t* idx;
    const int64_t* d8;
    const float* elev;
    bool* wet;
    int64_t* flow;
    long long n;
    float thr;
    long long no_flow;
    long long oct[9];
};
struct OvLakeArgs {
    float* elev;
    const int64_t* hb;
    const float* seap;
    long long n, sea_level;
    float lake;
};
struct BoolToF32Args {
    const bool* x;
    float* y;
    long long n;
};
struct I64Args {
    const int64_t* x;
    int64_t* y;
    long long n, add;
};
Tensor i64_op(Gen& g, const Tensor* x, int64_t add, int64_t h, int64_t w) {
    Tensor y = g.gpu.empty(DType::I64, {h, w});
    I64Args a{x ? x->i64() : nullptr, y.i64(), y.numel(), add};
    g.gpu.launch(g.gpu.kernel("tiles_tile", "tiles_i64"), y.numel(), a);
    return y;
}
struct PlaneIn {
    const void* p;
    int SW, R0, C0, dt;
};
struct PlanesArgs {
    PlaneIn height, biome, level, wet, floor_, roof, flow, carve;
    int16_t* out;
    int h, w, has_carve;
    long long bottom, top, sea;
};

int Hh(const Tensor& t) { return static_cast<int>(t.size(-2)); }
int Ww(const Tensor& t) { return static_cast<int>(t.size(-1)); }

// floor(to_blocks(metres)).clamp(min_y, top) + add  (float), or .long() (mode 1)
Tensor quant(Gen& g, const scale::BlockScale& s, const Tensor& metres, int mode, float add = 0.5f) {
    Tensor b = scale::to_blocks(g, s, metres);
    Tensor y = g.gpu.empty_shape(mode ? DType::I64 : DType::F32, b.ndim, b.shape);
    QuantArgs a{b.f32(), y.ptr, b.numel(), static_cast<float>(s.min_y), static_cast<float>(s.world_height - 1), add,
                mode};
    g.gpu.launch(tk(g, "tiles_quant"), b.numel(), a);
    return y;
}
// sc.to_metres(floor(sc.to_blocks(x)).clamp(min_y, top) + 0.5): the sidecar's quantised mid-block metres
Tensor quantised_metres(Gen& g, const scale::BlockScale& s, const Tensor& x) {
    return scale::to_metres(g, s, quant(g, s, x, 0));
}

Tensor half(Gen& g, const Tensor& x) { return ops::avg_pool(g.gpu, x, 2); }

Tensor crop_frame(Gen& g, const Tensor& x, int mb) {
    return ops::crop(g.gpu, x, mb, mb, Hh(x) - 2 * mb, Ww(x) - 2 * mb);
}

// ---- FX4 block look (world/tiles.py end of file)
struct DryArgs {
    const int64_t* height;
    const int64_t* water;
    const int64_t* floor_;
    const int64_t* roof;
    const int64_t* flow;
    const bool* wet;
    const bool* dry;
    bool* ok;
    int H, W;
    long long sea, no_flow, no_tunnel;
};
struct DespeckleArgs {
    const int64_t* src;
    int64_t* dst;
    const bool* ok;
    int H, W, mode;
    long long up, down;
};
struct AlpineArgs {
    const float* regional;
    const float* ground;
    const float* t0;
    float* y;
    long long n;
    float l0, inv_span, gain1, base, lapse;
    int mode;
};

// TileBuilder._look of the current build (TileBuilder.build sets it with gen.use_block_look: the state's look_build).
bool look(const Gen& g) { return g.st.look_build; }

Tensor as_i64(Gen& g, const Tensor& t) { return t.dtype == DType::I64 ? t : ops::cast(g.gpu, t, DType::I64); }

// despeckle's passes over int64 heights `h` given its `ok` mask.
Tensor despeckle_ok(Gen& g, const Tensor& h, const Tensor& ok) {
    const int H = Hh(h), W = Ww(h);
    const long long up = ci(g, "T.DESPIKE_UP"), down = ci(g, "T.DESPIKE_DOWN");
    Tensor cur = g.gpu.empty_like(h);
    DespeckleArgs a0{h.i64(), cur.i64(), ok.data<bool>(), H, W, 0, up, down};
    g.gpu.launch(tk(g, "tiles_despeckle"), h.numel(), a0);
    for (int64_t p = 0; p < ci(g, "T.DESPECKLE_PASSES"); ++p) {
        Tensor nxt = g.gpu.empty_like(h);
        DespeckleArgs a{cur.i64(), nxt.i64(), ok.data<bool>(), H, W, 1, up, down};
        g.gpu.launch(tk(g, "tiles_despeckle"), h.numel(), a);
        cur = nxt;
    }
    return cur;
}

// despeckle(cols.height, TileBuilder._dry(cols)) -> heights of cols.height's dtype
Tensor despeckle(Gen& g, const World& wd, const Fields& rc) {
    Tensor h = as_i64(g, rc["height"]);
    Tensor wa = as_i64(g, rc["water"]), fl = as_i64(g, rc["floor"]), ro = as_i64(g, rc["roof"]),
           fw = as_i64(g, rc["flow"]);
    const int H = Hh(h), W = Ww(h);
    Tensor ok = g.gpu.empty(DType::BOOL, {H, W});
    DryArgs da{h.i64(), wa.i64(), fl.i64(), ro.i64(), fw.i64(), rc["wet"].data<bool>(), nullptr, ok.data<bool>(), H, W,
               wd.sea_level, ci(g, "NO_FLOW"), ci(g, "NO_TUNNEL")};
    g.gpu.launch(tk(g, "tiles_dry_ok"), h.numel(), da);
    Tensor cur = despeckle_ok(g, h, ok);
    return rc["height"].dtype == DType::I64 ? cur : ops::cast(g.gpu, cur, rc["height"].dtype);
}

// alpine_elevation(regional, ground) (mode 0), or the overview's t0 - LAPSE * (alpine(reg+, ground) - reg+) (mode 1)
Tensor alpine(Gen& g, const Tensor& regional, const Tensor& ground, const Tensor* t0) {
    Tensor y = g.gpu.empty_like(regional);
    AlpineArgs a{regional.f32(), ground.f32(), t0 ? t0->f32() : nullptr, y.f32(), y.numel(), g.cf("T.ALPINE_LOCAL_M0"),
                 inv_f32(g.c("T.ALPINE_LOCAL_M1") - g.c("T.ALPINE_LOCAL_M0")),
                 static_cast<float>(g.c("T.ALPINE_LAPSE_GAIN") - 1.0), g.cf("T.ALPINE_BASE_M"), g.cf("LAPSE_C_PER_M"),
                 t0 ? 1 : 0};
    g.gpu.launch(tk(g, "tiles_alpine_elev"), y.numel(), a);
    return y;
}

// Python's `d` as it reaches the builder: an int, or 0.5 (1:1 lod 1).
struct D {
    double v;
    bool integral() const { return v == std::floor(v); }
    int64_t i() const { return static_cast<int64_t>(v); }
};

bool has_biomenet(const Gen& g) { return g.manifest["tiles"].bool_or("biomenet", false); }

// TileBuilder._classify(elev, climate, regional, i1, j1, px_m, sidecar, d); `side` = the sidecar's classes over the
// frame (undefined: the rule classifier alone).
Tensor classify(Gen& g, const Tensor& elev, const Tensor& climate, const Tensor& regional, int64_t i1, int64_t j1,
                double px_m, const Tensor* side, double d) {
    const int mb = static_cast<int>(ci(g, "RP.BLOCK_MARGIN"));
    const int H = Hh(elev), W = Ww(elev), h = H - 2 * mb, w = W - 2 * mb;
    const Frame full{H, W, 0, 0};
    Tensor biome;
    if (side != nullptr) {
        Tensor rule_m = classify_biome(g, elev, climate, full, elev, full, H, W, i1 - mb, j1 - mb, px_m);
        Tensor clean = majority(g, majority(g, rule_m, 9), 5);
        biome = g.gpu.empty(DType::I16, {h, w});
        struct SidecarMixArgs {
            const int16_t* side;
            const int16_t* rule;
            const int16_t* clean;
            int16_t* out;
            int H, W, mb, h, w;
            BiomeK k;
        } a{side->data<int16_t>(), rule_m.data<int16_t>(), clean.data<int16_t>(), biome.data<int16_t>(), H, W, mb,
            h, w, biome_k(g)};
        g.gpu.launch(kern(g, "tiles_sidecar_mix"), static_cast<long long>(h) * w, a);
    } else {
        const Frame inner{H, W, mb, mb};
        biome = classify_biome(g, elev, climate, inner, elev, inner, h, w, i1, j1, px_m);
    }
    Tensor cand = swamp_candidates(g, elev, regional, climate, full, H, W, i1 - mb, j1 - mb, 1);
    Tensor swamps = smooth_mask(g, cand, static_cast<int>(ci(g, "T.SWAMP_SMOOTH")));
    apply_swamps(g, biome, swamps, mb, mb);
    if (look(g)) {   // FX4: treeline / alpine rock band (60 m sample coordinates for the zone noise)
        const double u = px_m / (2.0 * g.c("NATIVE_M"));
        alpine_zones(g, biome, elev, climate, Frame{H, W, mb, mb}, static_cast<double>(i1) * u,
                     static_cast<double>(j1) * u, u);
    }
    return red_deserts(g, biome, i1, j1, d);
}

// river.columns(field, ctx, d[, ground_fine]) of river_field(n1, m1, n2, m2) (stages/river's convention: ints = the
// river_field request, floats [d], tensors [ground_fine]): height, water, floor, roof, flow, wet.
Fields river_columns(Gen& g, int64_t n1, int64_t m1, int64_t n2, int64_t m2, double d, const Tensor* ground_fine) {
    Args a{{n1, m1, n2, m2}, {d}, {}};
    if (ground_fine != nullptr) a.t.push_back(*ground_fine);
    return g.call("river.columns", a);
}

struct Blocks {
    Tensor height_f, water_f, wet_f, floor_f, roof_f, flow_f;   // frames (int64 / bool)
    Tensor biome;                                               // tile (int16)
    int mb = 0;
    SurfaceCtx ctx;                                             // the surface providers' frame
    bool has_ctx = false;
};

Fields blocks_fields(Gen& g, const Blocks& b) {
    Fields f;
    auto crop64 = [&](const Tensor& t) { return crop_frame(g, t, b.mb); };
    f.set("0", crop64(b.height_f));
    f.set("1", b.biome);
    f.set("2", crop64(b.water_f));
    f.set("3", crop64(b.wet_f));
    f.set("4.0", crop64(b.floor_f));
    f.set("4.1", crop64(b.roof_f));
    f.set("4.2", crop64(b.flow_f));
    return f;
}

// TileBuilder._blocks(i1, j1, i2, j2, d), d integer.
Blocks blocks(Gen& g, const World& wd, int64_t i1, int64_t j1, int64_t i2, int64_t j2, int64_t d) {
    const int64_t mb = ci(g, "RP.BLOCK_MARGIN");
    const int64_t n1 = (i1 - mb) * d, m1 = (j1 - mb) * d, n2 = (i2 + mb) * d, m2 = (j2 + mb) * d;
    const scale::BlockScale s = scale::get(g);
    Fields fld = g.call("river_field", {{n1, m1, n2, m2}, {}, {}});
    Fields rc = river_columns(g, n1, m1, n2, m2, static_cast<double>(d), nullptr);
    if (look(g)) rc.set("height", despeckle(g, wd, rc));
    const Tensor& height = rc["height"];
    const int H = Hh(height), W = Ww(height);
    Tensor elev = scale::to_metres(g, s, height, 0.5f);
    Tensor sea = sea_mask_for_samples(g, i1 - mb, j1 - mb, H, W, d);
    elev = lift_lakes(g, elev, height, rc["wet"], sea, wd.sea_level);
    Tensor regional = g.call("regional_elevation", {{n1, m1, n2, m2}, {}, {}}).one();
    if (!ci(g, "T.REGIONAL_LAPSE")) throw std::logic_error("tiles.REGIONAL_LAPSE off is not ported");
    Tensor lapse_elev = look(g) ? alpine(g, regional, fld["0.carved"], nullptr) : regional;
    Tensor climate =
        ops::avg_pool(g.gpu, g.call("climate_native", {{n1, m1, n2, m2}, {}, {lapse_elev}}).one(), static_cast<int>(d));
    Tensor side;
    const bool sidecar = has_biomenet(g) && (d == 1 || d == 2);
    if (sidecar) {
        Tensor pre = quantised_metres(g, s, ops::avg_pool(g.gpu, fld["0.ground"], static_cast<int>(d)));
        if (d == 2) {
            side = g.call("BN.classify", {{i1 - mb, j1 - mb}, {}, {pre, climate}}).one();
        } else {
            Tensor b60 = g.call("BN.classify", {{floordiv(i1 - mb, 2), floordiv(j1 - mb, 2)}, {}, {half(g, pre), half(g, climate)}})
                             .one();
            side = ops::repeat2d(g.gpu, b60, 2, 2);
        }
    }
    Tensor reg = ops::avg_pool(g.gpu, regional, static_cast<int>(d));
    Blocks b;
    b.mb = static_cast<int>(mb);
    b.biome = classify(g, elev, climate, reg, i1, j1, g.c("NATIVE_M") * static_cast<double>(d), sidecar ? &side : nullptr,
                       static_cast<double>(d));
    b.height_f = height;
    b.water_f = rc["water"];
    b.wet_f = rc["wet"];
    b.floor_f = rc["floor"];
    b.roof_f = rc["roof"];
    b.flow_f = rc["flow"];
    b.ctx = SurfaceCtx{static_cast<double>(d), i1, j1, static_cast<int>(mb), fld["0.ground"], climate, reg, height,
                       rc["water"], rc["wet"], b.biome};
    b.has_ctx = true;
    return b;
}

// generator2.shelf(heights, i1, j1, ppc) (v2 worlds' coastal shelf; FX3's distance-aware profile): the generator
// method "shelf" (tensors [heights], ints [i1, j1, ppc]); unchanged without the world's shelf (state sea_shelf).
Tensor shelf(Gen& g, const Tensor& x, int64_t i1, int64_t j1, int64_t ppc) {
    if (!g.st.sea_shelf) return x;
    Fields f = g.call("shelf", {{i1, j1, ppc}, {}, {x}});
    return f.one();
}

// TileBuilder._blocks_fine(i1, j1, i2, j2): 1:1, lod 1.
Blocks blocks_fine(Gen& g, const World& wd, int64_t i1, int64_t j1, int64_t i2, int64_t j2) {
    const int64_t mb = ci(g, "RP.BLOCK_MARGIN"), k = ci(g, "T.FINE_K");
    const int64_t b1 = i1 - mb, c1 = j1 - mb, b2 = i2 + mb, c2 = j2 + mb;
    auto mod = [&](int64_t v) { return v - floordiv(v, k) * k; };
    if (mod(b1) || mod(c1) || mod(b2) || mod(c2)) throw std::invalid_argument("1:1 tile frame is not pixel-aligned");
    const int64_t n1 = floordiv(b1, k), m1 = floordiv(c1, k), n2 = floordiv(b2, k), m2 = floordiv(c2, k);
    const scale::BlockScale s = scale::get(g);
    Fields fld = g.call("river_field", {{n1, m1, n2, m2}, {}, {}});
    const Tensor& carved = fld["0.carved"];
    const int64_t CP = ci(g, "CP");
    Tensor base = shelf(g, soft_cap_peaks(g, g.call("native", {{n1, m1, n2, m2}, {}, {}}).one()), n1, m1, CP);
    Tensor fine = shelf(g, soft_cap_peaks(g, g.call("native_fine", {{b1, c1, b2, c2}, {}, {}}).one()), b1, c1, CP * k);
    const int Hp = Hh(carved), Wp = Ww(carved);
    Tensor ground = g.gpu.empty(DType::F32, {Hp * k, Wp * k});
    FineGroundArgs fa{carved.f32(), base.f32(), fine.f32(), ground.f32(), Hp, Wp, static_cast<int>(k),
                      inv_f32(g.c("T.FINE_KEEP_M"))};
    g.gpu.launch(tk(g, "tiles_fine_ground"), ground.numel(), fa);
    Fields rc = river_columns(g, n1, m1, n2, m2, 1.0 / static_cast<double>(k), &ground);
    if (look(g)) rc.set("height", despeckle(g, wd, rc));
    const Tensor& height = rc["height"];
    Tensor elev = scale::to_metres(g, s, height, 0.5f);
    Tensor sea = ops::repeat2d(g.gpu, sea_mask_for_samples(g, n1, m1, n2 - n1, m2 - m1, 1), static_cast<int>(k),
                               static_cast<int>(k));
    elev = lift_lakes(g, elev, height, rc["wet"], sea, wd.sea_level);
    Tensor regional30 = g.call("regional_elevation", {{n1, m1, n2, m2}, {}, {}}).one();
    Tensor lapse30 = look(g) ? alpine(g, regional30, fld["0.carved"], nullptr) : regional30;
    Tensor climate30 = g.call("climate_native", {{n1, m1, n2, m2}, {}, {lapse30}}).one();
    const double kf = static_cast<double>(k);
    Tensor climate = ops::bilinear(g.gpu, climate30, Hp * static_cast<int>(k), Wp * static_cast<int>(k), kf);
    Tensor regional = ops::bilinear(g.gpu, regional30, Hp * static_cast<int>(k), Wp * static_cast<int>(k), kf);
    Tensor side;
    const bool sidecar = has_biomenet(g);
    if (sidecar) {
        Tensor pre = quantised_metres(g, s, fld["0.ground"]);
        Tensor b60 = g.call("BN.classify", {{floordiv(b1, 2 * k), floordiv(c1, 2 * k)}, {}, {half(g, pre), half(g, climate30)}})
                         .one();
        side = ops::repeat2d(g.gpu, b60, static_cast<int>(2 * k), static_cast<int>(2 * k));
    }
    Blocks b;
    b.mb = static_cast<int>(mb);
    b.biome = classify(g, elev, climate, regional, i1, j1, g.c("NATIVE_M") / kf, sidecar ? &side : nullptr, 1.0 / kf);
    b.height_f = height;
    b.water_f = rc["water"];
    b.wet_f = rc["wet"];
    b.floor_f = rc["floor"];
    b.roof_f = rc["roof"];
    b.flow_f = rc["flow"];
    b.ctx = SurfaceCtx{0.5, i1, j1, static_cast<int>(mb), ground, climate, regional, height, rc["water"], rc["wet"],
                       b.biome};
    b.has_ctx = true;
    return b;
}

// TileBuilder._overview(i1, j1, i2, j2, d): whole cells per sample. Returns tile-sized planes (no frame).
Fields overview(Gen& g, const World& wd, int64_t i1, int64_t j1, int64_t i2, int64_t j2, int64_t d) {
    const int64_t k = d / ci(g, "CELL_PX");
    const int64_t h = i2 - i1, w = j2 - j1;
    const int64_t ci0 = (i1 - 1) * k, cj0 = (j1 - 1) * k, hc = (h + 2) * k, wc = (w + 2) * k;
    const scale::BlockScale s = scale::get(g);
    Fields c = g.call("overview_cells", {{ci0, cj0, hc, wc}, {}, {}});
    const int K = static_cast<int>(k);
    Tensor climate_c = c["climate"];
    if (look(g)) {   // FX4: the alpine climate of full tiles (cell height for the column's own elevation)
        climate_c = g.gpu.clone(climate_c);
        Tensor t0 = climate_c[0];
        Tensor t = alpine(g, c["regional"], c["height"], &t0);
        g.gpu.copy(t0, t);
    }
    Fields sr = g.call("swamp_relief_cells", {{ci0, cj0, hc, wc}, {}, {}});
    const Tensor& ch = c["height"];
    Tensor flat = g.gpu.empty_like(ch);
    FlattenArgs fa{ch.f32(), sr["0"].f32(), sr["1"].f32(), flat.f32(), ch.numel()};
    g.gpu.launch(tk(g, "tiles_sr_flatten"), ch.numel(), fa);
    Tensor elev = ops::avg_pool(g.gpu, flat, K);
    Tensor idx;
    Tensor peak = ops::max_pool(g.gpu, c["logacc"], K, &idx);
    const int Ho = Hh(elev), Wo = Ww(elev);
    Tensor wet = g.gpu.empty(DType::BOOL, {Ho, Wo});
    Tensor flow = g.gpu.empty(DType::I64, {Ho, Wo});
    OverviewArgs oa{};
    oa.peak = peak.f32();
    oa.idx = idx.i64();
    oa.d8 = c.has("d8") ? c["d8"].i64() : nullptr;
    oa.elev = elev.f32();
    oa.wet = wet.data<bool>();
    oa.flow = flow.i64();
    oa.n = wet.numel();
    oa.thr = g.cf("G.river_log_threshold");
    oa.no_flow = ci(g, "NO_FLOW");
    const Json& oct = g.manifest["tiles"]["D8_OCTANT"];
    for (int q = 0; q < 9; ++q) oa.oct[q] = oct[static_cast<size_t>(q)].i64();
    g.gpu.launch(tk(g, "tiles_overview_flow"), oa.n, oa);
    Tensor hb = quant(g, s, elev, 1);
    Tensor elev_m = scale::to_metres(g, s, hb, 0.5f);
    Tensor sea = sea_cells(g, ch, land_cells(g, ci0, cj0, hc, wc));
    Tensor seaf = g.gpu.empty(DType::F32, {sea.size(0), sea.size(1)});
    BoolToF32Args bf{sea.data<bool>(), seaf.f32(), sea.numel()};
    g.gpu.launch(tk(g, "tiles_bool_f32"), sea.numel(), bf);
    Tensor seap = ops::max_pool(g.gpu, seaf, K);
    OvLakeArgs la{elev_m.f32(), hb.i64(), seap.f32(), elev_m.numel(), wd.sea_level, g.cf("O.LAKE_CLASSIFY_ELEV_M")};
    g.gpu.launch(tk(g, "tiles_overview_lake"), elev_m.numel(), la);
    Tensor clim = ops::avg_pool(g.gpu, climate_c, K);
    Tensor reg = ops::avg_pool(g.gpu, c["regional"], K);
    const int hh = static_cast<int>(h), ww = static_cast<int>(w);
    const Frame inner{Ho, Wo, 1, 1};
    Tensor biome = classify_biome(g, elev_m, clim, inner, elev_m, Frame{Ho, Wo, 1, 1}, hh, ww, i1, j1,
                                  g.c("NATIVE_M") * static_cast<double>(d));
    const int64_t lod = static_cast<int64_t>(std::llround(static_cast<double>(d) / wd.downscale));
    Tensor sw = swamp_candidates(g, elev_m, reg, clim, inner, hh, ww, i1 * lod, j1 * lod, lod);
    apply_swamps(g, biome, sw, 0, 0);
    if (look(g)) {
        const double hd = static_cast<double>(d) / 2.0;
        alpine_zones(g, biome, elev_m, clim, inner, static_cast<double>(i1 * d) / 2.0, static_cast<double>(j1 * d) / 2.0,
                     hd);
    }
    biome = red_deserts(g, biome, i1, j1, static_cast<double>(d));
    Fields f;
    Tensor hbc = crop_frame(g, hb, 1);
    f.set("0", hbc);
    f.set("1", biome);
    f.set("2", i64_op(g, &hbc, 1, h, w));
    f.set("3", crop_frame(g, wet, 1));
    const int64_t no_tunnel = ci(g, "NO_TUNNEL");
    f.set("4.0", i64_op(g, nullptr, no_tunnel, h, w));
    f.set("4.1", i64_op(g, nullptr, no_tunnel, h, w));
    f.set("4.2", crop_frame(g, flow, 1));
    return f;
}

PlaneIn pin(const Tensor& t) {
    int dt = 3;
    if (t.dtype == DType::I16) dt = 6;
    else if (t.dtype == DType::BOOL) dt = 5;
    else if (t.dtype == DType::I32) dt = 2;
    else if (t.dtype != DType::I64) throw std::invalid_argument("_planes input " + t.str());
    return PlaneIn{t.ptr, Ww(t), 0, 0, dt};
}

// TileBuilder._planes(height, biome, level, wet, (floor, roof, flow), carve) -> int16 (PLANES, h, w).
Tensor planes(Gen& g, const World& wd, const Tensor& height, const Tensor& biome, const Tensor& level,
              const Tensor& wet, const Tensor& fl, const Tensor& roof, const Tensor& flow, const Tensor* carve) {
    const int h = Hh(height), w = Ww(height);
    const int64_t P = ci(g, "T.PLANES");
    if (P != 7) throw std::logic_error("tiles.PLANES changed: update tiles_planes");
    Tensor out = g.gpu.empty(DType::I16, {P, h, w});
    PlanesArgs a{pin(height), pin(biome), pin(level), pin(wet), pin(fl), pin(roof), pin(flow),
                 carve ? pin(*carve) : pin(height), out.data<int16_t>(), h, w, carve ? 1 : 0, wd.min_y,
                 wd.world_height - 1, wd.sea_level};
    g.gpu.launch(tk(g, "tiles_planes"), static_cast<long long>(h) * w, a);
    return out;
}

// ---------------------------------------------------------------- registered methods
World world_checked(Gen& g) { return world_of(g); }

Fields m_blocks(Gen& g, const Args& a) {
    need(a, 4, 1, 0, "TileBuilder._blocks");
    const World wd = world_checked(g);
    return blocks_fields(g, blocks(g, wd, a.i[0], a.i[1], a.i[2], a.i[3], static_cast<int64_t>(a.f[0])));
}
SBTG_METHOD("TileBuilder._blocks", m_blocks);

Fields m_blocks_fine(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "TileBuilder._blocks_fine");
    const World wd = world_checked(g);
    return blocks_fields(g, blocks_fine(g, wd, a.i[0], a.i[1], a.i[2], a.i[3]));
}
SBTG_METHOD("TileBuilder._blocks_fine", m_blocks_fine);

Fields overview_full(Gen& g, const World& wd, int64_t i1, int64_t j1, int64_t i2, int64_t j2, int64_t d) {
    return overview(g, wd, i1, j1, i2, j2, d);
}

Fields m_overview(Gen& g, const Args& a) {
    need(a, 4, 1, 0, "TileBuilder._overview");
    const World wd = world_checked(g);
    return overview_full(g, wd, a.i[0], a.i[1], a.i[2], a.i[3], static_cast<int64_t>(a.f[0]));
}
SBTG_METHOD("TileBuilder._overview", m_overview);

// tensors [height, biome, level, wet, floor, roof, flow(, carve)]
Fields m_planes(Gen& g, const Args& a) {
    if (a.t.size() != 7 && a.t.size() != 8) throw std::invalid_argument("TileBuilder._planes: 7 or 8 tensors");
    const World wd = world_checked(g);
    return Fields::of(planes(g, wd, a.t[0], a.t[1], a.t[2], a.t[3], a.t[4], a.t[5], a.t[6],
                             a.t.size() == 8 ? &a.t[7] : nullptr));
}
SBTG_METHOD("TileBuilder._planes", m_planes);

// FX4 module functions of world/tiles.py (unit parity): "T.despeckle" tensors [height, dry];
// "T.alpine_elevation" tensors [regional, ground]; "T.alpine_zones" tensors [biome, elev, temp, tseason],
// floats [i0, j0, step].
Fields m_despeckle(Gen& g, const Args& a) {
    need(a, 0, 0, 2, "T.despeckle");
    Tensor h = as_i64(g, a.t[0]);
    const int H = Hh(h), W = Ww(h);
    Tensor ok = g.gpu.empty(DType::BOOL, {H, W});
    DryArgs da{};
    da.dry = a.t[1].data<bool>();
    da.ok = ok.data<bool>();
    da.H = H;
    da.W = W;
    g.gpu.launch(tk(g, "tiles_dry_ok"), h.numel(), da);
    Tensor out = despeckle_ok(g, h, ok);
    return Fields::of(a.t[0].dtype == DType::I64 ? out : ops::cast(g.gpu, out, a.t[0].dtype));
}
SBTG_METHOD("T.despeckle", m_despeckle);

Fields m_alpine_elevation(Gen& g, const Args& a) {
    need(a, 0, 0, 2, "T.alpine_elevation");
    return Fields::of(alpine(g, a.t[0], a.t[1], nullptr));
}
SBTG_METHOD("T.alpine_elevation", m_alpine_elevation);

Fields m_alpine_zones(Gen& g, const Args& a) {
    need(a, 0, 3, 4, "T.alpine_zones");
    Tensor b = g.gpu.clone(a.t[0]);
    Tensor clim = ops::stack(g.gpu, {a.t[2], a.t[3]});
    alpine_zones(g, b, a.t[1], clim, Frame{Hh(b), Ww(b), 0, 0}, a.f[0], a.f[1], a.f[2]);
    return Fields::of(b);
}
SBTG_METHOD("T.alpine_zones", m_alpine_zones);

// TileBuilder.build(seed, tile_x, tile_z, lod) -> {"planes": int16 (PLANES, n, n), "surface.<name>": uint8}.
Fields m_build(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "TileBuilder.build");
    const int64_t seed = a.i[0], tx = a.i[1], tz = a.i[2], lod = a.i[3];
    if (lod < 1 || (lod & (lod - 1)) != 0) throw std::invalid_argument("lod must be a power of two");
    const World wd = world_checked(g);
    // TileBuilder.build's per-build state (seed, the world's climate layout, the v2 shelf), as the Python sets it.
    const std::string layout = !wd.climate_layout.empty() ? wd.climate_layout
                                                          : g.manifest["tiles"].str_or("default_climate_layout", "belts");
    if (layout != g.st.climate_layout) {
        g.st.climate_layout = layout;
        g.clear_caches();
    }
    g.st.seed = seed;
    g.st.sea_shelf = wd.curve_version >= 2;
    // FX4 block look: v2 worlds of generators with block_look (gen.use_block_look(on and block_look))
    g.st.look_build = wd.curve_version >= 2 && g.manifest["tiles"].i64_or("block_look", 0) > 0;
    const int64_t n = wd.tile_size;
    const int64_t i1 = tx * n, j1 = tz * n, i2 = (tx + 1) * n, j2 = (tz + 1) * n;
    const double dd = wd.downscale * static_cast<double>(lod);
    const D d{dd};
    const int64_t opx = ci(g, "T.OVERVIEW_MIN_PX");
    const bool ov = d.integral() && d.i() >= opx && d.i() % opx == 0;
    Fields bf;
    Blocks b;
    bool have_blocks = false;
    if (dd < 1) {
        b = blocks_fine(g, wd, i1, j1, i2, j2);
        have_blocks = true;
    } else if (ov) {
        bf = overview_full(g, wd, i1, j1, i2, j2, d.i());
    } else {
        b = blocks(g, wd, i1, j1, i2, j2, d.i());
        have_blocks = true;
    }
    if (have_blocks) bf = blocks_fields(g, b);
    Tensor carve;
    bool has_carve = false;
    if (!ov) {
        Fields cf = g.call("TileBuilder._carve", {{i1, j1, i2, j2}, {dd}, {}});
        if (!cf.empty()) {
            carve = cf.one();
            has_carve = true;
        }
    }
    Fields out;
    out.set("planes", planes(g, wd, bf["0"], bf["1"], bf["2"], bf["3"], bf["4.0"], bf["4.1"], bf["4.2"],
                             has_carve ? &carve : nullptr));
    if (have_blocks && !ov && b.has_ctx) {
        for (auto& [name, t] : surface_channels(g, wd, b.ctx)) out.set("surface." + name, t);
    }
    return out;
}
SBTG_METHOD("TileBuilder.build", m_build);

} // namespace
} // namespace sbtg::tiles
