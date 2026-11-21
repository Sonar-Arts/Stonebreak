// models/biomenet.py BN.classify (the biome sidecar): build_input + the engine's BiomeNet plan for the frame size
// ("biome_<H>x<W>", export/cenda_export.py export_biome, the same .cim the Python generator's proxy runs) + argmax.
#include "stages/tiles/tiles.hpp"

namespace sbtg::tiles {

namespace {
const char kSrc[] =
#include "tiles_biomenet.cu.inc"
    ;
SBTG_MODULE("tiles_biomenet", kSrc);

struct FnlCfg {
    int32_t seed;
    float freq;
    int32_t octaves;
    float lac, gain, weighted, bounding;
};
struct BuildArgs {
    const float* elev;
    const float* clim;
    const float* noise;
    float* x;
    int h, w, C, extras;
};
struct ArgmaxArgs {
    const float* logits;
    int16_t* out;
    const int16_t* ids;
    long long P;
    int K;
};

// classifier_noise's raw fields: x = np.arange(j0, j0 + w, dtype=float32) (numpy's float fill: start + i * delta,
// delta = float32(j0 + 1) - float32(j0)), y likewise from i0. Computed on the host coordinates, noise on the GPU.
Tensor bn_noise(Gen& g, int64_t i0, int64_t j0, int h, int w) {
    auto axis = [](int64_t s, int n) {
        std::vector<float> v(static_cast<size_t>(n));
        const float s0 = static_cast<float>(static_cast<double>(s));
        const float s1 = static_cast<float>(static_cast<double>(s + 1));
        const float delta = s1 - s0;
        for (int i = 0; i < n; ++i) v[static_cast<size_t>(i)] = i == 0 ? s0 : (i == 1 ? s1 : s0 + static_cast<float>(i) * delta);
        return v;
    };
    std::vector<float> xs = axis(j0, w), ys = axis(i0, h);
    bool plain = true;   // integral steps from the origin: biomes._noise's own (step 1) fields, cached
    for (int i = 0; i < w && plain; ++i) plain = xs[static_cast<size_t>(i)] == static_cast<float>(static_cast<double>(j0)) + static_cast<float>(i);
    for (int i = 0; i < h && plain; ++i) plain = ys[static_cast<size_t>(i)] == static_cast<float>(static_cast<double>(i0)) + static_cast<float>(i);
    if (plain) return noise(g, i0, j0, h, w, 1, 5);
    Tensor xt = g.gpu.upload(xs.data(), DType::F32, {w}), yt = g.gpu.upload(ys.data(), DType::F32, {h});
    return noise_at(g, static_cast<double>(i0), static_cast<double>(j0), h, w, 1.0, 5, &xt, &yt);
}

} // namespace

Tensor bn_classify(Gen& g, const Tensor& elev, const Tensor& climate, int64_t i0, int64_t j0) {
    const int h = static_cast<int>(elev.size(-2)), w = static_cast<int>(elev.size(-1));
    const bool extras = g.manifest["tiles"].bool_or("biomenet_extras", false);
    const int C = static_cast<int>(climate.size(0));
    if (extras && C < 7) throw std::invalid_argument("this biome sidecar reads the 7-channel EXP-2 climate");
    const int n_in = static_cast<int>(g.manifest["tiles"]["biomenet_n_in"].i64());
    const std::string name = "biome_" + std::to_string(h) + "x" + std::to_string(w);
    ci_model* m = g.model(name);
    if (m == nullptr) throw std::logic_error("no engine model '" + name + "' (cenda_backend exports the tile frames)");
    Tensor nz = bn_noise(g, i0, j0, h, w);
    Tensor x = g.gpu.empty(DType::F32, {n_in, h, w});
    BuildArgs a{elev.f32(), climate.f32(), nz.f32(), x.f32(), h, w, C, extras ? 1 : 0};
    g.gpu.launch(g.gpu.kernel("tiles_biomenet", "tiles_bn_input"), static_cast<long long>(h) * w, a);
    const int K = static_cast<int>(g.manifest["tiles"]["biomenet_classes"].size());
    Tensor logits = g.gpu.empty(DType::F32, {K, h, w});
    ci_request req{};
    req.batch = 1;
    req.flags = CI_RUN_STREAM;
    req.inputs[0] = x.ptr;
    req.outputs[0] = logits.ptr;
    req.stream = g.gpu.stream;
    g.gpu.check(g.gpu.ci.run(m, &req), "ci_run(biome)");
    std::vector<int16_t> ids;
    for (const Json& v : g.manifest["tiles"]["biomenet_classes"].items()) ids.push_back(static_cast<int16_t>(v.i64()));
    Tensor idt = g.gpu.upload(ids.data(), DType::I16, {K});
    Tensor out = g.gpu.empty(DType::I16, {h, w});
    ArgmaxArgs r{logits.f32(), out.data<int16_t>(), idt.data<int16_t>(), static_cast<long long>(h) * w, K};
    g.gpu.launch(g.gpu.kernel("tiles_biomenet", "tiles_bn_argmax"), r.P, r);
    return out;
}

namespace {
// BN.classify(model, elev_m, climate, i0, j0): ints [i0, j0], tensors [elev_m, climate].
Fields m_bn_classify(Gen& g, const Args& a) {
    need(a, 2, 0, 2, "BN.classify");
    return Fields::of(bn_classify(g, a.t[0], a.t[1], a.i[0], a.i[1]));
}
SBTG_METHOD("BN.classify", m_bn_classify);
} // namespace

} // namespace sbtg::tiles
