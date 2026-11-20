// The surface providers, natively: world/vegetation_node.py ("TileBuilder.surface.vegetation") and
// world/surface_node.py ("TileBuilder.surface.surface"). Their networks are the engine's "veg_<H>x<W>" /
// "surface_<H>x<W>" models (export/cenda_export.py export_vegnet / export_surfacenet), the same plans the Python
// generator's proxies run under TGM_ENGINE=cenda.
//
// Args (stages/tiles/surface.hpp surface_args): ints [i1, j1, mb], floats [d], tensors [ground, climate, regional,
// height, water, wet, biome] -> {channel: fp32 (rows, cols), NaN = no data}, in the Python providers' order.
#include "stages/tiles/surface.hpp"

#include <cmath>

namespace sbtg::tiles {

namespace {
const char kSrc[] =
#include "tiles_nodes.cu.inc"
    ;
SBTG_MODULE("tiles_nodes", kSrc);

ci_kernel* nk(Gen& g, const char* name) { return g.gpu.kernel("tiles_nodes", name); }

struct BoxArgs {
    const float* src;
    float* dst;
    int H, W, k;
};
struct WaterArgs {
    const int64_t* height;
    const int64_t* level;
    const bool* wet;
    float* out;
    long long n, sea;
};
struct GeArgs {
    float* x;
    long long n;
};
struct VegInputArgs {
    const float* e;
    const float* es;
    const float* b9;
    const float* b17;
    const float* clim;
    const float* reg;
    const float* w;
    const float* wb9;
    float* x;
    int H, W;
    float lapse;
};
struct SigArgs {
    float* x;
    long long n;
};
struct NodeOutArgs {
    const float* src;
    const float* water;
    const bool* mask;
    float* out;
    int H, W, mb, h, w, ch;
    float scale;
    int apply_scale;
};
struct SurfInputArgs {
    const float* z;
    const float* b5;
    const float* temp;
    const float* precip;
    float* x;
    int H, W;
};
struct ActArgs {
    const float* raw;
    float* out;
    int H, W, F, S, fine, fractions;
};
struct SurfMaskArgs {
    const int64_t* height;
    const int64_t* level;
    const bool* wet;
    bool* out;
    int W, mb, h, w;
    long long sea;
};

int Hh(const Tensor& t) { return static_cast<int>(t.size(-2)); }
int Ww(const Tensor& t) { return static_cast<int>(t.size(-1)); }
Tensor i64(Gen& g, const Tensor& t) { return t.dtype == DType::I64 ? t : ops::cast(g.gpu, t, DType::I64); }

Tensor box(Gen& g, const Tensor& x, int k) {
    Tensor y = g.gpu.empty_like(x);
    BoxArgs a{x.f32(), y.f32(), Hh(x), Ww(x), k};
    g.gpu.launch(nk(g, "tiles_box"), x.numel(), a);
    return y;
}

// One engine "tensor" model run: x (n_in, H, W) -> (n_out, H, W).
Tensor run(Gen& g, const std::string& net, const Tensor& x, int n_out) {
    const int H = Hh(x), W = Ww(x);
    const std::string name = net + "_" + std::to_string(H) + "x" + std::to_string(W);
    ci_model* m = g.model(name);
    if (m == nullptr) throw std::logic_error("no engine model '" + name + "' (cenda_backend exports the tile frames)");
    Tensor y = g.gpu.empty(DType::F32, {n_out, H, W});
    ci_request req{};
    req.batch = 1;
    req.flags = CI_RUN_STREAM;
    req.inputs[0] = x.ptr;
    req.outputs[0] = y.ptr;
    req.stream = g.gpu.stream;
    g.gpu.check(g.gpu.ci.run(m, &req), ("ci_run(" + name + ")").c_str());
    return y;
}

// floor(to_blocks(x)).clamp(min_y, top) + 0.5 -> to_metres (the nodes' quantised mid-block metres)
Tensor quantised(Gen& g, const Tensor& x) {
    const scale::BlockScale s = scale::get(g);
    Tensor b = scale::to_blocks(g, s, x);
    struct QuantArgs {
        const float* x;
        void* y;
        long long n;
        float lo, hi, add;
        int mode;
    } a{b.f32(), nullptr, b.numel(), static_cast<float>(s.min_y), static_cast<float>(s.world_height - 1), 0.5f, 0};
    Tensor q = g.gpu.empty_like(b);
    a.y = q.ptr;
    g.gpu.launch(g.gpu.kernel("tiles_tile", "tiles_quant"), b.numel(), a);
    return scale::to_metres(g, s, q);
}

// vegnet.predict(model, elev, climate, regional, water): sigmoid(logits), (N_OUT, H, W)
Tensor veg_predict(Gen& g, const Tensor& elev, const Tensor& clim, const Tensor& reg, const Tensor& water) {
    const Json& t = g.manifest["tiles"]["veg"];
    const int n_in = static_cast<int>(t["n_in"].i64()), n_out = static_cast<int>(t["outputs"].size());
    Tensor es = box(g, elev, 5), b9 = box(g, elev, 9), b17 = box(g, elev, 17), wb9 = box(g, water, 9);
    const int H = Hh(elev), W = Ww(elev);
    Tensor x = g.gpu.empty(DType::F32, {n_in, H, W});
    VegInputArgs a{elev.f32(), es.f32(), b9.f32(), b17.f32(), clim.f32(), reg.f32(), water.f32(), wb9.f32(), x.f32(),
                   H, W, t["LAPSE_C_PER_M"].f32()};
    g.gpu.launch(nk(g, "tiles_veg_input"), static_cast<long long>(H) * W, a);
    Tensor y = run(g, "veg", x, n_out);
    SigArgs sa{y.f32(), y.numel()};
    g.gpu.launch(nk(g, "tiles_sigmoid"), y.numel(), sa);
    return y;
}

// world/vegetation_node.py channels(model, builder, ctx)
Fields vegetation(Gen& g, const Args& a) {
    need(a, 3, 1, 7, "TileBuilder.surface.vegetation");
    const double d = a.f[0];
    const int mb = static_cast<int>(a.i[2]);
    const Tensor &ground = a.t[0], &climate = a.t[1], &regional = a.t[2];
    Fields out;
    if (!(d == 1.0 || d == 2.0) || climate.size(0) < 7) return out;
    const World wd = world_of(g);
    const int di = static_cast<int>(d);
    Tensor elev = quantised(g, ops::avg_pool(g.gpu, ground, di));
    Tensor height = i64(g, a.t[3]), level = i64(g, a.t[4]);
    const int H = Hh(height), W = Ww(height);
    Tensor water = g.gpu.empty(DType::F32, {H, W});
    WaterArgs wa{height.i64(), level.i64(), a.t[5].data<bool>(), water.f32(), water.numel(), wd.sea_level};
    g.gpu.launch(nk(g, "tiles_node_water"), water.numel(), wa);
    Tensor pred;
    if (di == 2) {
        pred = veg_predict(g, elev, climate, regional, water);
    } else {   // 1:2: the node runs on 60 m (2x2-pooled) blocks, bilinear back up
        Tensor hw = ops::avg_pool(g.gpu, water, 2);
        GeArgs ga{hw.f32(), hw.numel()};
        g.gpu.launch(nk(g, "tiles_ge_half"), hw.numel(), ga);
        Tensor p60 = veg_predict(g, ops::avg_pool(g.gpu, elev, 2), ops::avg_pool(g.gpu, climate, 2),
                                 ops::avg_pool(g.gpu, regional, 2), hw);
        pred = ops::bilinear(g.gpu, p60, Hh(p60) * 2, Ww(p60) * 2, 2.0);
    }
    const int h = H - 2 * mb, w = W - 2 * mb;
    for (const Json& c : g.manifest["tiles"]["veg"]["channels"].items()) {
        Tensor o = g.gpu.empty(DType::F32, {h, w});
        NodeOutArgs na{pred.f32(), water.f32(), nullptr, o.f32(), Hh(pred), Ww(pred), mb, h, w,
                       static_cast<int>(c["index"].i64()), c["scale"].f32(), 1};
        g.gpu.launch(nk(g, "tiles_node_out"), static_cast<long long>(h) * w, na);
        out.set(c["channel"].str(), o);
    }
    return out;
}
SBTG_METHOD("TileBuilder.surface.vegetation", vegetation);

// world/surface_node.py fields(model, builder, ctx)
Fields surface(Gen& g, const Args& a) {
    need(a, 3, 1, 7, "TileBuilder.surface.surface");
    const double d = a.f[0];
    const int mb = static_cast<int>(a.i[2]);
    const Tensor &ground = a.t[0], &clim = a.t[1];
    Fields out;
    int pool = 0;
    if (d == 2.0) pool = 0;
    else if (d == 1.0) pool = 2;
    else if (d == 0.5) pool = 4;
    else return out;
    const World wd = world_of(g);
    const Json& t = g.manifest["tiles"]["surface"];
    Tensor z60 = ops::avg_pool(g.gpu, ground, d == 0.5 ? 4 : 2);
    Tensor temp = clim[0], precip = clim[2];
    if (pool) {
        temp = ops::avg_pool(g.gpu, temp, pool);
        precip = ops::avg_pool(g.gpu, precip, pool);
    }
    const int H = Hh(z60), W = Ww(z60);
    Tensor b5 = box(g, z60, 5);
    Tensor x = g.gpu.empty(DType::F32, {static_cast<int64_t>(t["n_in"].i64()), H, W});
    SurfInputArgs sa{z60.f32(), b5.f32(), temp.f32(), precip.f32(), x.f32(), H, W};
    g.gpu.launch(nk(g, "tiles_surf_input"), static_cast<long long>(H) * W, sa);
    const int F = static_cast<int>(t["n_fields"].i64()), S = static_cast<int>(t["sub"].i64());
    Tensor raw = run(g, "surface", x, F + F * S * S);
    Tensor per;
    if (d == 2.0) {
        per = g.gpu.empty(DType::F32, {F, H, W});
        ActArgs aa{raw.f32(), per.f32(), H, W, F, S, 0, static_cast<int>(t["fractions"].i64())};
        g.gpu.launch(nk(g, "tiles_surf_activate"), per.numel(), aa);
    } else {
        Tensor p15 = g.gpu.empty(DType::F32, {F, static_cast<int64_t>(H) * S, static_cast<int64_t>(W) * S});
        ActArgs aa{raw.f32(), p15.f32(), H, W, F, S, 1, static_cast<int>(t["fractions"].i64())};
        g.gpu.launch(nk(g, "tiles_surf_activate"), p15.numel(), aa);
        per = d == 1.0 ? ops::avg_pool(g.gpu, p15, 2) : p15;
    }
    Tensor height = i64(g, a.t[3]), level = i64(g, a.t[4]);
    const int Hf = Hh(height), Wf = Ww(height), h = Hf - 2 * mb, w = Wf - 2 * mb;
    if (Hh(per) != Hf || Ww(per) != Wf) throw std::runtime_error("surface node frame differs from the tile frame");
    Tensor mask = g.gpu.empty(DType::BOOL, {h, w});
    SurfMaskArgs ma{height.i64(), level.i64(), a.t[5].data<bool>(), mask.data<bool>(), Wf, mb, h, w, wd.sea_level};
    g.gpu.launch(nk(g, "tiles_surf_mask"), static_cast<long long>(h) * w, ma);
    for (const Json& c : t["channels"].items()) {
        Tensor o = g.gpu.empty(DType::F32, {h, w});
        NodeOutArgs na{per.f32(), nullptr, mask.data<bool>(), o.f32(), Hf, Wf, mb, h, w,
                       static_cast<int>(c["index"].i64()), 1.0f, 0};
        g.gpu.launch(nk(g, "tiles_node_out"), static_cast<long long>(h) * w, na);
        out.set(c["channel"].str(), o);
    }
    return out;
}
SBTG_METHOD("TileBuilder.surface.surface", surface);

} // namespace
} // namespace sbtg::tiles
