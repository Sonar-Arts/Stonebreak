// RiverPipeline.columns (river/pipeline.py + river/blocks.py): the river field pooled (or repeated, 1:1) to block
// columns, then BlockQuantize -> FlowOctants -> (FlattenAcross -> MonotoneBlocks) x 2 -> BlockContainment ->
// Undercuts -> Overhangs, bit for bit.
//
//   "river.columns" (i1, j1, i2, j2; d; [ground_fine])  =  gen.river.columns(*gen.river_field(i1, j1, i2, j2), d, gf)
#include "stages/river/river.hpp"

namespace sbtg::river {

namespace {
struct PoolArgs {
    const uint8_t* wet_n; const float* top; const float* carved; const float* half_px; const uint8_t* channel;
    const float* flow; float* ground; float* top_m; uint8_t* wet; float* half_b; float* flow_log; int H, W, d;
    float inv_d;
};
struct UnpoolArgs {
    const uint8_t* wet_n; const float* top; const float* carved; const float* half_px; const uint8_t* channel;
    const float* flow; const float* ground_fine; float* ground; float* top_m; uint8_t* wet; float* half_b;
    float* flow_log; int H, W, k; float kf;
};
struct QuantInArgs { const float* ground; const float* top; const uint8_t* wet; float* out; long long n; };
struct QuantArgs {
    const float* tb; const float* tw; const uint8_t* wet; int64_t* height; int64_t* water; int64_t* floor_;
    int64_t* roof; int64_t* flow; uint8_t* wet_out; long long n; float lo, hi;
};
struct FlowPlanesArgs { const float* top; const uint8_t* wet; const float* flow_log; float* out; long long n; };
struct FlowRatioArgs { const float* b; float* s; float* fs; long long n; };
struct FlowOctArgs {
    const float* s; const float* fs; const uint8_t* wet; const int64_t* d8; long long table[9]; int64_t* flow;
    float* uxz; int H, W, dh, dw, has_acc; long long bo0, bo1, do0, do1; int d_int, k; int cp; float inv_q, q;
};
struct ConsensusArgs { const int64_t* flow_in; const float* uxz; const uint8_t* wet; int64_t* flow; int H, W, C; float inv_q; };
struct FlattenStepArgs { const int64_t* lv; int64_t* out; const uint8_t* wet; const int64_t* oct; int H, W, side; int di[8], dj[8]; };
struct LvInitArgs { const uint8_t* wet; const int64_t* water; int64_t* lv; long long n; };
struct LvFinArgs { const uint8_t* wet; const int64_t* lv; int64_t* water; int64_t* height; long long n; };
struct MonoBlocksArgs { const int64_t* lv; int64_t* out; const uint8_t* wet; const int64_t* flow; int H, W; int di[8], dj[8]; };
struct ContainArgs { const uint8_t* wet; const int64_t* water; int64_t* height_in; int64_t* height; int H, W; long long sea; };
struct UndercutArgs {
    const uint8_t* wet; const int64_t* water; const int64_t* height; int64_t* floor_; int64_t* roof; int H, W;
    long long o0, o1; uint32_t base; float inv_period; int min_rise, air, cover;
};
struct OverhangArgs {
    const uint8_t* wet; const int64_t* water; const int64_t* height_in; const int64_t* roof_in; const float* half_b;
    int64_t* height; int64_t* floor_; int64_t* roof; int H, W; float min_width;
};

uint32_t lowbias32(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}

struct Src {
    Tensor ground, top, wet, half, flow_log;
    int64_t o0 = 0, o1 = 0;
    double d = 1.0;
};
struct Cols {
    Tensor height, water, floor_, roof, flow, wet;
};

void dir_table(Gen& g, int di[8], int dj[8]) {
    const Json& a = g.constants()["RIVER_OCT_DI"];
    const Json& b = g.constants()["RIVER_OCT_DJ"];
    for (size_t k = 0; k < 8; ++k) {
        di[k] = a[k].i32();
        dj[k] = b[k].i32();
    }
}

void flatten_across(Gen& g, Cols& c) {
    const int H = static_cast<int>(c.height.size(0)), W = static_cast<int>(c.height.size(1));
    const long long n = c.height.numel();
    Tensor lv = i64(g, H, W), b = i64(g, H, W);
    g.gpu.launch(K(g, "rv_lv_init"), n, LvInitArgs{u8p(c.wet), c.water.i64(), lv.i64(), n});
    const int steps = static_cast<int>(std::ceil(g.c("RIVER_CFG_width_max_blocks"))) + 1;
    FlattenStepArgs fa{nullptr, nullptr, u8p(c.wet), c.flow.i64(), H, W, 0, {}, {}};
    dir_table(g, fa.di, fa.dj);
    // Every step runs (Python stops at the fixed point; a step there changes nothing on integers).
    for (int side : {2, 6}) {
        fa.side = side;
        for (int s = 0; s < steps; ++s) {
            fa.lv = lv.i64();
            fa.out = b.i64();
            g.gpu.launch(K(g, "rv_flatten_step"), n, fa);
            std::swap(lv, b);
        }
    }
    g.gpu.launch(K(g, "rv_lv_fin"), n, LvFinArgs{u8p(c.wet), lv.i64(), c.water.i64(), c.height.i64(), n});
}

void monotone_blocks(Gen& g, Cols& c) {
    const int H = static_cast<int>(c.height.size(0)), W = static_cast<int>(c.height.size(1));
    const long long n = c.height.numel();
    Tensor lv = i64(g, H, W), b = i64(g, H, W);
    g.gpu.launch(K(g, "rv_lv_init"), n, LvInitArgs{u8p(c.wet), c.water.i64(), lv.i64(), n});
    MonoBlocksArgs ma{nullptr, nullptr, u8p(c.wet), c.flow.i64(), H, W, {}, {}};
    dir_table(g, ma.di, ma.dj);
    const int iters = static_cast<int>(g.c("RIVER_BLOCK_MONO_ITERS"));
    for (int s = 0; s < iters; ++s) {
        ma.lv = lv.i64();
        ma.out = b.i64();
        g.gpu.launch(K(g, "rv_mono_blocks"), n, ma);
        std::swap(lv, b);
    }
    // (Python skips this when no wet column flows; FlattenAcross already left every wet column at most water - 1.)
    g.gpu.launch(K(g, "rv_lv_fin"), n, LvFinArgs{u8p(c.wet), lv.i64(), c.water.i64(), c.height.i64(), n});
}

Fields columns(Gen& g, const Args& a) {
    if (a.i.size() != 4 || a.f.size() != 1 || a.t.size() > 1)
        throw std::invalid_argument("river.columns: expects 4 ints, 1 float, 0 or 1 tensors");
    const int64_t i1 = a.i[0], j1 = a.i[1];
    const double d = a.f[0];
    const int64_t CP = g.manifest["constants"]["CP"].i64();
    Fields fld = g.call("river_field", {{a.i[0], a.i[1], a.i[2], a.i[3]}, {}, {}});
    const Tensor& carved = fld["0.carved"];
    const Tensor& wet_n = fld["0.wet"];
    const Tensor& top_n = fld["0.top"];
    const Tensor& half_px = fld["0.half_px"];
    const Tensor& channel = fld["0.channel"];
    const Tensor& flow = fld["0.flow"];
    const int Hn = static_cast<int>(carved.size(0)), Wn = static_cast<int>(carved.size(1));
    const int64_t hp = static_cast<int64_t>(g.c("RIVER_HALO_PX"));
    const int64_t do0 = floordiv(i1 - hp, CP) - static_cast<int64_t>(g.c("RIVER_CELL_HALO"));
    const int64_t do1 = floordiv(j1 - hp, CP) - static_cast<int64_t>(g.c("RIVER_CELL_HALO"));
    // The drainage D8 of the field's cell frame (pipeline.RiverPipeline.run's Drainage): left by a native river_field,
    // else drainage(...) / cells(...) as river_field reads it.
    Tensor d8;
    const Cache::Key dk = d8_key(g, a.i[0], a.i[1], a.i[2], a.i[3]);
    Cache& dc = g.cache("river_d8", 4);
    if (dc.contains(dk)) {
        d8 = dc.get_or(dk, [] { return Fields{}; }).one();
    } else {
        const int64_t i2 = a.i[2], j2 = a.i[3];
        const int64_t c1 = ceildiv(i2 + hp, CP) + static_cast<int64_t>(g.c("RIVER_CELL_HALO"));
        const int64_t e1 = ceildiv(j2 + hp, CP) + static_cast<int64_t>(g.c("RIVER_CELL_HALO"));
        Fields dr = g.call("drainage", {{do0, do1, c1 - do0, e1 - do1}, {}, {}});
        if (!dr.has("logacc")) dr = g.call("cells", {{do0, do1, c1 - do0, e1 - do1}, {}, {}});
        if (dr.has("d8")) d8 = dr["d8"];
    }

    Src src;
    int d_int = 0, k = 0;
    int H, W;
    if (d < 1.0) {
        k = static_cast<int>(std::nearbyint(1.0 / d));
        if (a.t.empty()) throw std::invalid_argument("river.columns: d < 1 needs ground_fine");
        H = Hn * k;
        W = Wn * k;
        const Tensor& gf = a.t[0];
        if (gf.size(0) != H || gf.size(1) != W) throw std::invalid_argument("river.columns: ground_fine " + gf.str());
        src.o0 = i1 * k;
        src.o1 = j1 * k;
        src.ground = f32(g, H, W);
        src.top = f32(g, H, W);
        src.wet = mask(g, H, W);
        src.half = f32(g, H, W);
        src.flow_log = f32(g, H, W);
        g.gpu.launch(K(g, "rv_unpool"), static_cast<long long>(H) * W,
                     UnpoolArgs{u8p(wet_n), top_n.f32(), carved.f32(), half_px.f32(), u8p(channel), flow.f32(),
                                gf.f32(), src.ground.f32(), src.top.f32(), u8p(src.wet), src.half.f32(),
                                src.flow_log.f32(), H, W, k, static_cast<float>(k)});
    } else {
        d_int = static_cast<int>(d);
        if (d_int != d) throw std::invalid_argument("river.columns: d must be an integer >= 1 or 1/k");
        H = Hn / d_int;
        W = Wn / d_int;
        src.o0 = floordiv(i1, d_int);
        src.o1 = floordiv(j1, d_int);
        src.ground = f32(g, H, W);
        src.top = f32(g, H, W);
        src.wet = mask(g, H, W);
        src.half = f32(g, H, W);
        src.flow_log = f32(g, H, W);
        g.gpu.launch(K(g, "rv_pool"), static_cast<long long>(H) * W,
                     PoolArgs{u8p(wet_n), top_n.f32(), carved.f32(), half_px.f32(), u8p(channel), flow.f32(),
                              src.ground.f32(), src.top.f32(), u8p(src.wet), src.half.f32(), src.flow_log.f32(), H, W,
                              d_int, 1.0f / static_cast<float>(d_int)});
    }
    const long long n = static_cast<long long>(H) * W;
    const Scale sc = get_scale(g);

    // ---- BlockQuantize
    Tensor qin = g.gpu.empty(DType::F32, {2, H, W});
    g.gpu.launch(K(g, "rv_quant_in"), n, QuantInArgs{src.ground.f32(), src.top.f32(), u8p(src.wet), qin.f32(), n});
    Tensor tb = scale::to_blocks(g, sc.s, qin);
    Cols c{i64(g, H, W), i64(g, H, W), i64(g, H, W), i64(g, H, W), i64(g, H, W), mask(g, H, W)};
    g.gpu.launch(K(g, "rv_quantize"), n,
                 QuantArgs{tb.f32(), tb.f32() + n, u8p(src.wet), c.height.i64(), c.water.i64(), c.floor_.i64(),
                           c.roof.i64(), c.flow.i64(), u8p(c.wet), n, static_cast<float>(sc.s.min_y),
                           static_cast<float>(sc.s.world_height - 1)});

    // ---- FlowOctants
    Tensor fp = g.gpu.empty(DType::F32, {3, H, W});
    g.gpu.launch(K(g, "rv_flow_planes"), n, FlowPlanesArgs{src.top.f32(), u8p(c.wet), src.flow_log.f32(), fp.f32(), n});
    Tensor fb = ops::blur(g.gpu, fp, g.taps(g.c("RIVER_FLOW_SMOOTH_BLOCKS")));
    Tensor s = f32(g, H, W), fs = f32(g, H, W);
    g.gpu.launch(K(g, "rv_flow_ratio"), n, FlowRatioArgs{fb.f32(), s.f32(), fs.f32(), n});
    const double pi = g.c("RIVER_PI");
    const float q = static_cast<float>(pi / 4);
    const float inv_q = 1.0f / q;
    Tensor flow1 = i64(g, H, W), uxz = g.gpu.empty(DType::F32, {2, H, W});
    FlowOctArgs fo{s.f32(), fs.f32(), u8p(c.wet), d8.defined() ? d8.i64() : nullptr, {}, flow1.i64(), uxz.f32(), H, W,
                   d8.defined() ? static_cast<int>(d8.size(0)) : 0, d8.defined() ? static_cast<int>(d8.size(1)) : 0, 1,
                   src.o0, src.o1, do0, do1, d_int, k, static_cast<int>(CP), inv_q, q};
    const Json& tab = g.constants()["RIVER_D8_OCTANT"];
    if (tab.size() != 9) throw std::logic_error("RIVER_D8_OCTANT: 9 entries expected");
    for (size_t e = 0; e < 9; ++e) fo.table[e] = tab[e].i64();
    g.gpu.launch(K(g, "rv_flow_oct"), n, fo);
    g.gpu.launch(K(g, "rv_consensus"), n,
                 ConsensusArgs{flow1.i64(), uxz.f32(), u8p(c.wet), c.flow.i64(), H, W,
                               static_cast<int>(g.c("RIVER_FLOW_CONSENSUS")), inv_q});

    // ---- (FlattenAcross -> MonotoneBlocks) x 2
    for (int r = 0; r < 2; ++r) {
        flatten_across(g, c);
        monotone_blocks(g, c);
    }

    // ---- BlockContainment
    g.gpu.launch(K(g, "rv_contain"), n,
                 ContainArgs{u8p(c.wet), c.water.i64(), c.height.i64(), c.height.i64(), H, W, sc.s.sea_level});

    // ---- Undercuts
    const uint64_t seed = static_cast<uint64_t>(g.st.seed);
    const uint64_t stream = static_cast<uint64_t>(static_cast<int64_t>(g.c("RIVER_UNDERCUT_STREAM")));
    const uint32_t base = lowbias32(static_cast<uint32_t>((seed * 0x9E3779B1ull + stream * 0x632BE5ABull) & 0xFFFFFFFFull));
    g.gpu.launch(K(g, "rv_undercut"), n,
                 UndercutArgs{u8p(c.wet), c.water.i64(), c.height.i64(), c.floor_.i64(), c.roof.i64(), H, W, src.o0,
                              src.o1, base, 1.0f / static_cast<float>(g.c("RIVER_UNDERCUT_NOISE_BLOCKS")),
                              static_cast<int>(g.c("RIVER_CFG_undercut_min_rise")),
                              static_cast<int>(g.c("RIVER_CFG_undercut_air")),
                              static_cast<int>(g.c("RIVER_CFG_undercut_cover"))});

    // ---- Overhangs (reads neighbours' height / roof of dry columns, writes wet columns only: in place is safe)
    g.gpu.launch(K(g, "rv_overhang"), n,
                 OverhangArgs{u8p(c.wet), c.water.i64(), c.height.i64(), c.roof.i64(), src.half.f32(), c.height.i64(),
                              c.floor_.i64(), c.roof.i64(), H, W,
                              static_cast<float>(g.c("RIVER_CFG_overhang_min_width_blocks"))});

    Fields out;
    out.set("height", c.height);
    out.set("water", c.water);
    out.set("floor", c.floor_);
    out.set("roof", c.roof);
    out.set("flow", c.flow);
    out.set("wet", c.wet);
    return out;
}
SBTG_METHOD("river.columns", columns);

} // namespace
} // namespace sbtg::river
