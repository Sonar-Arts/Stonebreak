// biomes.py (rule classifier, swamp overlay, majority filters, classifier noise) and world/ocean.py on the GPU, plus
// their registered methods (dotted names "B.*" / "O.*": module functions, adapters in native_manifest_tiles.py).
#include "stages/tiles/tiles.hpp"

#include <cmath>
#include <cstring>
#include <mutex>

namespace sbtg::tiles {

namespace {
const char kSrc[] =
#include "tiles_biomes.cu.inc"
    ;
SBTG_MODULE("tiles_biomes", kSrc);

struct FnlCfg {
    int32_t seed;
    float freq;
    int32_t octaves;
    float lac, gain, weighted, bounding;
};
struct NoiseArgs {
    float* out;
    const float* xs;
    const float* ys;
    long long hw;
    int h, w, nf;
    float i0, j0, step;
    FnlCfg cfg[8];
};
struct ClassifyArgs {
    const float* elev;
    const float* clim;
    const float* epad;
    const float* noise;
    int16_t* out;
    Frame f, fpad;
    int h, w, C;
    float inv_px;
    BiomeK k;
};
struct SwampArgs {
    const float* elev;
    const float* regional;
    const float* clim;
    const float* noise;
    bool* out;
    Frame f;
    int h, w, C;
    BiomeK k;
};
struct MajorityArgs {
    const int16_t* src;
    int16_t* dst;
    int H, W, size;
};
struct SmoothArgs {
    const bool* src;
    bool* dst;
    int H, W, size;
};
struct ApplySwampsArgs {
    int16_t* biome;
    const bool* swamps;
    int h, w, SW, R0, C0;
    BiomeK k;
};
struct DesertCoordArgs {
    float* ci;
    float* cj;
    long long i1, j1;
    int h, w, dint;
    long long d_i;
    float d_f, half_d, inv_cell;
};
struct RedArgs {
    int16_t* biome;
    const bool* red;
    long long n;
    int16_t desert, red_id;
};
struct GrowArgs {
    const bool* m;
    const bool* through;
    bool* out;
    int H, W, r;
    int and_through;
};
struct SeaSeedArgs {
    const float* height;
    const float* land;
    bool* sub;
    bool* seed;
    long long n;
    float sea_land;
};
struct SeaGatherArgs {
    const bool* sea;
    bool* out;
    long long s1, t1, d, cp, ci0, cj0, ci1, cj1;
    int h, w, k, SW;
};
struct OceanPrepArgs {
    const int64_t* height;
    const bool* wet;
    const bool* sea;
    bool* sub;
    bool* through;
    bool* seed;
    long long n, sea_level;
};
struct ClassifyElevArgs {
    float* elev;
    const bool* sub;
    const bool* ocean;
    long long n;
    float lake;
};

const char* kNoiseNames[6] = {"_TEMP_NOISE", "_TEMP_NOISE_FINE", "_PRECIP_NOISE", "_SNOW_NOISE", "_SNOW_NOISE_FINE",
                              "_SWAMP_NOISE"};

int H_(const Tensor& t) { return static_cast<int>(t.size(-2)); }
int W_(const Tensor& t) { return static_cast<int>(t.size(-1)); }
} // namespace

float inv_f32(double v) {
    const float f = static_cast<float>(v);
    return 1.0f / f;
}

ci_kernel* kern(Gen& g, const char* name) { return g.gpu.kernel("tiles_biomes", name); }

BiomeK biome_k(Gen& g) {
    const Json& t = g.manifest["tiles"];
    const Json& id = t["biome_id"];
    const Json& c = g.manifest["constants"];
    auto f = [&](const char* n) { return c[n].f32(); };
    auto d = [&](const char* n) { return c[n].num(); };
    BiomeK k{};
    k.hyper_arid = f("B.HYPER_ARID_PRECIP_MM");
    k.snow_cover_we = f("B.SNOW_COVER_M_WE");
    k.snow_cover_tmax = f("B.SNOW_COVER_MAX_TEMP_C");
    k.sw_min_moist = f("B.SWAMP_MIN_MOISTURE");
    k.sw_inv_moist_span = inv_f32(d("B.SWAMP_FULL_MOISTURE") - d("B.SWAMP_MIN_MOISTURE"));
    k.sw_min_temp = f("B.SWAMP_MIN_TEMP_C");
    k.sw_snow_margin = f("B.SWAMP_SNOW_MARGIN_C");
    k.sw_max_reg = f("B.SWAMP_MAX_REGIONAL_M");
    k.sw_max_elev = f("B.SWAMP_MAX_ELEV_M");
    k.sw_dry = f("B.SWAMP_PATCH_DRY");
    k.sw_wet_minus_dry = static_cast<float>(d("B.SWAMP_PATCH_WET") - d("B.SWAMP_PATCH_DRY"));
    k.soil0 = static_cast<float>(t["SOIL_SWAMP_M3"][0].num());
    k.inv_soil_span = inv_f32(t["SOIL_SWAMP_M3"][1].num() - t["SOIL_SWAMP_M3"][0].num());
    k.soil_min = f("B.SOIL_SWAMP_MIN_M3");
    k.bog_minus_min = static_cast<float>(d("B.SWAMP_BOG_MIN_TEMP_C") - d("B.SWAMP_MIN_TEMP_C"));
    auto b = [&](const char* n) { return static_cast<int16_t>(id[n].i64()); };
    k.id_plains = b("plains");
    k.id_snowy_plains = b("snowy_plains");
    k.id_desert = b("desert");
    k.id_swamp = static_cast<int16_t>(c["B.SWAMP_ID"].i64());
    k.id_forest = b("forest");
    k.id_taiga = b("taiga");
    k.id_snowy_taiga = b("snowy_taiga");
    k.id_savanna = b("savanna");
    k.id_windswept = b("windswept_hills");
    k.id_jungle = b("jungle");
    k.id_grove = b("grove");
    k.id_snowy_slopes = b("snowy_slopes");
    k.id_frozen_peaks = b("frozen_peaks");
    k.id_stony_peaks = b("stony_peaks");
    k.id_forest_sparse = b("forest_sparse");
    k.id_taiga_sparse = b("taiga_sparse");
    k.id_snowy_taiga_sparse = b("snowy_taiga_sparse");
    const Json& o = t["OCEAN_IDS"];   // (warm, normal, cold, frozen), the classifier's literal order
    k.ocean_warm = static_cast<int16_t>(o[0].i64());
    k.ocean_normal = static_cast<int16_t>(o[1].i64());
    k.ocean_cold = static_cast<int16_t>(o[2].i64());
    k.ocean_frozen = static_cast<int16_t>(o[3].i64());
    // The swamp hosts are a table too: the kernel reads forest / forest_sparse / jungle; refuse another table.
    const Json& hosts = t["SWAMP_HOST_IDS"];
    if (hosts.size() != 3 || hosts[0].i64() != k.id_forest || hosts[1].i64() != k.id_forest_sparse ||
        hosts[2].i64() != k.id_jungle)
        throw std::logic_error("biomes.SWAMP_HOST_IDS changed: update tiles_apply_swamps");
    return k;
}

// ---------------------------------------------------------------- noise
Tensor noise(Gen& g, int64_t i0, int64_t j0, int h, int w, int64_t step, int nf) {
    return noise_at(g, static_cast<double>(i0), static_cast<double>(j0), h, w, static_cast<double>(step), nf, nullptr,
                    nullptr);
}

namespace {
int64_t dbits(double v) {
    int64_t b;
    std::memcpy(&b, &v, sizeof b);
    return b;
}
} // namespace

Tensor noise_at(Gen& g, double i0, double j0, int h, int w, double step, int nf, const Tensor* xs, const Tensor* ys) {
    std::vector<int64_t> key{g.st.seed, dbits(i0), dbits(j0), h, w, dbits(step), nf, xs != nullptr ? 1 : 0};
    return g.cache("tiles.noise", 24).get_or(key, [&] {
        NoiseArgs a{};
        a.xs = xs ? xs->f32() : nullptr;
        a.ys = ys ? ys->f32() : nullptr;
        Tensor out = g.gpu.empty(DType::F32, {nf, h, w});
        a.out = out.f32();
        a.hw = static_cast<long long>(h) * w;
        a.h = h;
        a.w = w;
        a.nf = nf;
        a.i0 = static_cast<float>(i0);   // numpy: python int / float -> float32
        a.j0 = static_cast<float>(j0);
        a.step = static_cast<float>(step);
        const Json& nz = g.manifest["tiles"]["noise"];
        for (int k = 0; k < nf; ++k) {
            const Json& c = nz[kNoiseNames[k]];
            FnlCfg& f = a.cfg[k];
            f.seed = static_cast<int32_t>(c["seed"].i64());
            f.freq = c["frequency"].f32();
            f.octaves = static_cast<int32_t>(c["octaves"].i64());
            f.lac = c["lacunarity"].f32();
            f.gain = c["gain"].f32();
            f.weighted = c["weighted"].f32();
            // FastNoiseLite::CalculateFractalBounding (float ops)
            float gain = f.gain < 0 ? -f.gain : f.gain, amp = gain, af = 1.0f;
            for (int i = 1; i < f.octaves; ++i) {
                af += amp;
                amp *= gain;
            }
            f.bounding = 1 / af;
        }
        g.gpu.launch(kern(g, "tiles_noise"), a.hw * nf, a);
        return Fields::of(out);
    }).one();
}

// ---------------------------------------------------------------- classifier, swamps, filters
Tensor classify_biome(Gen& g, const Tensor& elev, const Tensor& clim, const Frame& fe, const Tensor& epad,
                      const Frame& fpad, int h, int w, int64_t i0, int64_t j0, double pixel_size_m) {
    Tensor nz = noise(g, i0, j0, h, w, 1, 6);
    Tensor out = g.gpu.empty(DType::I16, {h, w});
    ClassifyArgs a{elev.f32(), clim.f32(), epad.f32(), nz.f32(), out.data<int16_t>(), fe, fpad, h, w,
                   static_cast<int>(clim.size(0)), inv_f32(pixel_size_m), biome_k(g)};
    g.gpu.launch(kern(g, "tiles_classify"), static_cast<long long>(h) * w, a);
    return out;
}

Tensor swamp_candidates(Gen& g, const Tensor& elev, const Tensor& regional, const Tensor& clim, const Frame& f, int h,
                        int w, int64_t i0, int64_t j0, int64_t step) {
    Tensor nz = noise(g, i0, j0, h, w, step, 6);
    Tensor out = g.gpu.empty(DType::BOOL, {h, w});
    SwampArgs a{elev.f32(), regional.f32(), clim.f32(), nz.f32(), out.data<bool>(), f, h, w,
                static_cast<int>(clim.size(0)), biome_k(g)};
    g.gpu.launch(kern(g, "tiles_swamp_candidates"), static_cast<long long>(h) * w, a);
    return out;
}

Tensor majority(Gen& g, const Tensor& ids, int size) {
    ids.check(DType::I16);
    Tensor out = g.gpu.empty_like(ids);
    MajorityArgs a{ids.data<int16_t>(), out.data<int16_t>(), H_(ids), W_(ids), size};
    g.gpu.launch(kern(g, "tiles_majority"), ids.numel(), a);
    return out;
}

Tensor smooth_mask(Gen& g, const Tensor& mask, int size) {
    mask.check(DType::BOOL);
    Tensor out = g.gpu.empty_like(mask);
    SmoothArgs a{mask.data<bool>(), out.data<bool>(), H_(mask), W_(mask), size};
    g.gpu.launch(kern(g, "tiles_smooth_mask"), mask.numel(), a);
    return out;
}

void apply_swamps(Gen& g, Tensor& biome, const Tensor& swamps, int R0, int C0) {
    ApplySwampsArgs a{biome.data<int16_t>(), swamps.data<bool>(), H_(biome), W_(biome), W_(swamps), R0, C0, biome_k(g)};
    g.gpu.launch(kern(g, "tiles_apply_swamps"), biome.numel(), a);
}

Tensor red_deserts(Gen& g, const Tensor& biome, int64_t i1, int64_t j1, double d) {
    const int h = H_(biome), w = W_(biome);
    {   // `if not bool(desert.any()): return biome` (Python's one readback here)
        struct AnyArgs {
            const int16_t* biome;
            int32_t* flag;
            long long n;
            int16_t id;
        };
        Tensor flag = g.gpu.zeros(DType::I32, {1});
        AnyArgs aa{biome.data<int16_t>(), flag.i32(), biome.numel(), biome_k(g).id_desert};
        g.gpu.launch(kern(g, "tiles_any_id"), biome.numel(), aa);
        Tensor hf = g.gpu.to_host(flag);
        g.gpu.sync();
        if (hf.i32()[0] == 0) return biome;
    }
    Tensor cif = g.gpu.empty(DType::F32, {h, w}), cjf = g.gpu.empty(DType::F32, {h, w});
    const bool dint = d == std::floor(d);
    DesertCoordArgs a{cif.f32(), cjf.f32(), i1, j1, h, w, dint ? 1 : 0, static_cast<long long>(d),
                      static_cast<float>(d), static_cast<float>(d / 2), inv_f32(static_cast<double>(ci(g, "CELL_PX")))};
    g.gpu.launch(kern(g, "tiles_desert_coords"), static_cast<long long>(h) * w, a);
    // red_desert_cells is a pure function of each point: evaluated at every sample (Python: at the desert ones).
    Tensor red = g.call("G.red_desert_cells", {{}, {}, {cif, cjf}}).one();
    Tensor out = g.gpu.clone(biome);
    RedArgs r{out.data<int16_t>(), red.data<bool>(), out.numel(), biome_k(g).id_desert,
              static_cast<int16_t>(g.manifest["constants"]["B.RED_DESERT_ID"].i64())};
    g.gpu.launch(kern(g, "tiles_red_apply"), out.numel(), r);
    return out;
}

// FX4 alpine_zones(biome, elev, temp, tseason, i0, j0, step) in place; elev / climate on frame f (temp = plane 0,
// tseason = plane 1 of `clim`).
void alpine_zones(Gen& g, Tensor& biome, const Tensor& elev, const Tensor& clim, const Frame& f, double i0, double j0,
                  double step) {
    struct AlpineZoneArgs {
        int16_t* biome;
        const float* elev;
        const float* temp;
        const float* tseason;
        const float* noise;
        Frame f;
        int h, w, n_tree, n_snowy;
        int16_t tree[16], snowy[16];
        int16_t rock, snow;
        float noise_c, gs_days, min_m, min_noise_m;
    } a{};
    const int h = H_(biome), w = W_(biome);
    Tensor nz = noise_at(g, i0, j0, h, w, step, 5, nullptr, nullptr);
    const long long plane = static_cast<long long>(f.SH) * f.SW;
    a.biome = biome.data<int16_t>();
    a.elev = elev.f32();
    a.temp = clim.f32();
    a.tseason = clim.f32() + plane;
    a.noise = nz.f32();
    a.f = f;
    a.h = h;
    a.w = w;
    const Json& t = g.manifest["tiles"];
    const Json& tree = t["ALPINE_TREE_IDS"];
    const Json& snowy = t["ALPINE_SNOWY_TREE_IDS"];
    if (tree.size() > 16 || snowy.size() > 16) throw std::logic_error("ALPINE_*_IDS longer than 16");
    a.n_tree = static_cast<int>(tree.size());
    a.n_snowy = static_cast<int>(snowy.size());
    for (size_t q = 0; q < tree.size(); ++q) a.tree[q] = static_cast<int16_t>(tree[q].i64());
    for (size_t q = 0; q < snowy.size(); ++q) a.snowy[q] = static_cast<int16_t>(snowy[q].i64());
    a.rock = static_cast<int16_t>(ci(g, "T.ALPINE_ROCK_ID"));
    a.snow = static_cast<int16_t>(ci(g, "T.ALPINE_SNOW_ID"));
    a.noise_c = g.cf("T.TREELINE_NOISE_C");
    a.gs_days = g.cf("T.TREELINE_GS_DAYS");
    a.min_m = g.cf("T.ALPINE_MIN_M");
    a.min_noise_m = g.cf("T.ALPINE_MIN_NOISE_M");
    g.gpu.launch(kern(g, "tiles_alpine_zones"), static_cast<long long>(h) * w, a);
}

// ---------------------------------------------------------------- ocean
Tensor dilate(Gen& g, const Tensor& m, int r) {
    if (r <= 0) return m;
    Tensor out = g.gpu.empty_like(m);
    GrowArgs a{m.data<bool>(), nullptr, out.data<bool>(), H_(m), W_(m), r, 0};
    g.gpu.launch(kern(g, "tiles_dilate"), m.numel(), a);
    return out;
}

Tensor grow(Gen& g, const Tensor& seed, const Tensor& through, int64_t steps) {
    // out = seed & through, then `steps` x (dilate(out, 1) & through). Python stops at a fixed point: iterating past
    // it changes nothing, so no readback here.
    GrowArgs a0{seed.data<bool>(), through.data<bool>(), nullptr, H_(seed), W_(seed), 0, 1};
    Tensor cur = g.gpu.empty_like(seed);
    a0.out = cur.data<bool>();
    g.gpu.launch(kern(g, "tiles_dilate"), seed.numel(), a0);   // r = 0: seed & through
    Tensor nxt = g.gpu.empty_like(seed);
    for (int64_t s = 0; s < steps; ++s) {
        GrowArgs a{cur.data<bool>(), through.data<bool>(), nxt.data<bool>(), H_(seed), W_(seed), 1, 1};
        g.gpu.launch(kern(g, "tiles_dilate"), seed.numel(), a);
        std::swap(cur, nxt);
    }
    return cur;
}

Tensor sea_cells(Gen& g, const Tensor& height_m, const Tensor& land) {
    Tensor sub = g.gpu.empty(DType::BOOL, {height_m.size(-2), height_m.size(-1)});
    Tensor seed = g.gpu.empty_like(sub);
    SeaSeedArgs a{height_m.f32(), land.f32(), sub.data<bool>(), seed.data<bool>(), sub.numel(),
                  g.cf("O.SEA_LAND")};
    g.gpu.launch(kern(g, "tiles_sea_seed"), sub.numel(), a);
    return dilate(g, grow(g, seed, sub, ci(g, "O.SEA_GROW_CELLS")), static_cast<int>(ci(g, "O.SEA_COAST_CELLS")));
}

Tensor land_cells(Gen& g, int64_t ci0, int64_t cj0, int64_t hc, int64_t wc) {
    return g.call("O.land_cells", {{ci0, cj0, hc, wc}, {}, {}}).one();
}

Tensor sea_mask_for_samples(Gen& g, int64_t s1, int64_t t1, int64_t h, int64_t w, int64_t d) {
    const int64_t CP = ci(g, "CP"), m = ci(g, "O.SEA_CELL_MARGIN");
    const int64_t ci0 = floordiv(s1 * d, CP) - m, cj0 = floordiv(t1 * d, CP) - m;
    const int64_t ci1 = floordiv((s1 + h) * d - 1, CP) + 1 + m, cj1 = floordiv((t1 + w) * d - 1, CP) + 1 + m;
    Tensor cells = planner_height(g, ci0, cj0, ci1 - ci0, cj1 - cj0);
    Tensor sea = sea_cells(g, cells, land_cells(g, ci0, cj0, ci1 - ci0, cj1 - cj0));
    Tensor out = g.gpu.empty(DType::BOOL, {h, w});
    const int k = static_cast<int>(std::max<int64_t>(1, ceildiv(d, CP)));
    SeaGatherArgs a{sea.data<bool>(), out.data<bool>(), s1, t1, d, CP, ci0, cj0, ci1, cj1, static_cast<int>(h),
                    static_cast<int>(w), k, static_cast<int>(cj1 - cj0)};
    g.gpu.launch(kern(g, "tiles_sea_gather"), h * w, a);
    return out;
}

Tensor lift_lakes(Gen& g, const Tensor& elev, const Tensor& height, const Tensor& wet, const Tensor& sea,
                  int64_t sea_level) {
    Tensor h64 = height.dtype == DType::I64 ? height : ops::cast(g.gpu, height, DType::I64);
    Tensor sub = g.gpu.empty(DType::BOOL, {H_(elev), W_(elev)});
    Tensor through = g.gpu.empty_like(sub), seed = g.gpu.empty_like(sub);
    OceanPrepArgs p{h64.i64(), wet.data<bool>(), sea.data<bool>(), sub.data<bool>(), through.data<bool>(),
                    seed.data<bool>(), sub.numel(), sea_level};
    g.gpu.launch(kern(g, "tiles_ocean_prep"), sub.numel(), p);
    Tensor ocean = grow(g, seed, through, ci(g, "O.SEA_REACH_SAMPLES"));
    Tensor out = g.gpu.clone(elev);
    ClassifyElevArgs c{out.f32(), sub.data<bool>(), ocean.data<bool>(), out.numel(), g.cf("O.LAKE_CLASSIFY_ELEV_M")};
    g.gpu.launch(kern(g, "tiles_classify_elev"), out.numel(), c);
    return out;
}

// ---------------------------------------------------------------- registered methods (unit parity)
namespace {

// B._classify_biome(elev, climate, i0, j0, elev_padded, pixel_size_m): tensors [elev, climate, elev_padded],
// ints [i0, j0], floats [pixel_size_m]. (The kernel reads elev itself from its own frame.)
Fields m_classify_biome(Gen& g, const Args& a) {
    need(a, 2, 1, 3, "B._classify_biome");
    const Tensor &e = a.t[0], &c = a.t[1], &ep = a.t[2];
    const int h = H_(e), w = W_(e);
    return Fields::of(classify_biome(g, e, c, Frame{h, w, 0, 0}, ep, Frame{H_(ep), W_(ep), 1, 1}, h, w, a.i[0],
                                     a.i[1], a.f[0]));
}
SBTG_METHOD("B._classify_biome", m_classify_biome);

// B.swamp_candidates(elev, regional, climate, i0, j0, step): tensors [elev, regional, climate], ints [i0, j0, step].
Fields m_swamp_candidates(Gen& g, const Args& a) {
    need(a, 3, 0, 3, "B.swamp_candidates");
    const int h = H_(a.t[0]), w = W_(a.t[0]);
    return Fields::of(swamp_candidates(g, a.t[0], a.t[1], a.t[2], Frame{h, w, 0, 0}, h, w, a.i[0], a.i[1], a.i[2]));
}
SBTG_METHOD("B.swamp_candidates", m_swamp_candidates);

Fields m_majority(Gen& g, const Args& a) {
    need(a, 1, 0, 1, "B.majority");
    return Fields::of(majority(g, a.t[0], static_cast<int>(a.i[0])));
}
SBTG_METHOD("B.majority", m_majority);

Fields m_smooth_mask(Gen& g, const Args& a) {
    need(a, 1, 0, 1, "B.smooth_mask");
    return Fields::of(smooth_mask(g, a.t[0], static_cast<int>(a.i[0])));
}
SBTG_METHOD("B.smooth_mask", m_smooth_mask);

Fields m_apply_swamps(Gen& g, const Args& a) {
    need(a, 0, 0, 2, "B.apply_swamps");
    Tensor b = g.gpu.clone(a.t[0]);
    apply_swamps(g, b, a.t[1], 0, 0);
    return Fields::of(b);
}
SBTG_METHOD("B.apply_swamps", m_apply_swamps);

// O.sea_mask_for_samples(gen, s1, t1, h, w, d)
Fields m_sea_mask(Gen& g, const Args& a) {
    need(a, 5, 0, 0, "O.sea_mask_for_samples");
    return Fields::of(sea_mask_for_samples(g, a.i[0], a.i[1], a.i[2], a.i[3], a.i[4]));
}
SBTG_METHOD("O.sea_mask_for_samples", m_sea_mask);

Fields m_sea_cells(Gen& g, const Args& a) {
    need(a, 0, 0, 2, "O.sea_cells");
    return Fields::of(sea_cells(g, a.t[0], a.t[1]));
}
SBTG_METHOD("O.sea_cells", m_sea_cells);

// O.land_cells(gen, ci0, cj0, hc, wc, device): the generator's continent_land_cells (its port, or the Python host).
Fields m_land_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "O.land_cells");
    return g.call("continent_land_cells", a);
}
SBTG_METHOD("O.land_cells", m_land_cells);

// TileBuilder._red_deserts(biome, i1, j1, d): tensors [biome], ints [i1, j1], floats [d].
Fields m_red_deserts(Gen& g, const Args& a) {
    need(a, 2, 1, 1, "TileBuilder._red_deserts");
    return Fields::of(red_deserts(g, a.t[0], a.i[0], a.i[1], a.f[0]));
}
SBTG_METHOD("TileBuilder._red_deserts", m_red_deserts);

} // namespace
} // namespace sbtg::tiles
