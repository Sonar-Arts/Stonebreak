// Stage "controls" (part 1): configuration, the EXP-2 layout (_layout, _controls_at), controls(),
// continent_land_cells, climate_native, detail_gates.
#include "stages/controls/controls.hpp"

#include <algorithm>
#include <cstring>

namespace sbtg {

namespace {
const char kCtlSrc[] =
#include "controls.cu.inc"
    ;
SBTG_MODULE("controls", kCtlSrc);
const char kC2Src[] =
#include "csrc_controls2.cu.inc"
    ;
SBTG_MODULE("csrc_controls2", kC2Src);
const char kPcSrc[] =
#include "csrc_controls.cu.inc"
    ;
SBTG_MODULE("csrc_controls", kPcSrc);
const char kBlendSrc[] =
#include "csrc_blend.cu.inc"
    ;
SBTG_MODULE("csrc_blend", kBlendSrc);

// continent_land_cells as a kernel over csrc/controls2.cu's own device functions (the same text is compiled: the
// continent / basin math is THE kernel's, which tests/test_cuda_controls2.py pins against the torch definition).
const char kContLandSrc[] = R"CU(
struct ContLandArgs {
    Controls2Args p;
    const float* trend;      // v2: the layout trend (cells), else unused
    float* out;
    float* c2out;            // v2 == 2 (controls2.continent2): the continent field, out = its land weight
    const float* link;       // v2 (BV sea link): super-cell grid, 1 = linked to the open ocean; nullptr = no gate
    int ci0, cj0, hc, wc, v2;
    float sea_trend;
    int link_a0, link_b0, link_nb, link_k;
};
extern "C" __global__ void controls_continent_land(const __grid_constant__ ContLandArgs a) {
    const int n = a.hc * a.wc;
    const int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= n) return;
    const float ci = (float)(a.ci0 + idx / a.wc), cj = (float)(a.cj0 + idx % a.wc);
    float land_v4;
    const float c2 = continent2(a.p, ci, cj, &land_v4);
    if (!a.v2) {
        a.out[idx] = land_v4;
        return;
    }
    const float land = ss(M(A(c2, a.p.k[K_C035]), a.p.k[K_INV_LAND]));
    if (a.v2 == 2) {
        a.c2out[idx] = c2;
        a.out[idx] = land;
        return;
    }
    bool drowned = a.trend[idx] < a.sea_trend;
    if (a.link != nullptr) {   // generator2.sea_link_cells: the cell's super cell (floor division) must be linked
        const int ci_i = a.ci0 + idx / a.wc, cj_i = a.cj0 + idx % a.wc, k = a.link_k;
        const int sa = (ci_i >= 0 ? ci_i / k : -((-ci_i + k - 1) / k)) - a.link_a0;
        const int sb = (cj_i >= 0 ? cj_i / k : -((-cj_i + k - 1) / k)) - a.link_b0;
        drowned = drowned && a.link[sa * a.link_nb + sb] > 0.5f;
    }
    a.out[idx] = drowned ? 0.0f : land;
}
// BV sea link (generator2.sea_link_cells): super-cell centres, the open-ocean / passable flags there, growth steps.
struct SeaLinkArgs {
    Controls2Args p;
    float* si;
    float* sj;
    const float* trend;      // the layout trend at (si, sj) (_controls_at), nullptr in the coordinate pass
    float* through;
    float* conn;
    int a0, b0, na, nb, k, pass;
    float link_trend, sea_land;
};
extern "C" __global__ void controls_sea_link(const __grid_constant__ SeaLinkArgs a) {
    const int n = a.na * a.nb;
    const int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= n) return;
    if (a.pass == 0) {       // torch: arange(a0, a1, f32) * K + (K - 1) / 2, rounded op by op
        const float half = 0.5f * (float)(a.k - 1);
        a.si[idx] = __fadd_rn(__fmul_rn((float)(a.a0 + idx / a.nb), (float)a.k), half);
        a.sj[idx] = __fadd_rn(__fmul_rn((float)(a.b0 + idx % a.nb), (float)a.k), half);
        return;
    }
    float land_v4;
    const float c2 = continent2(a.p, a.si[idx], a.sj[idx], &land_v4);
    const bool open = ss(M(A(c2, a.p.k[K_C035]), a.p.k[K_INV_LAND])) < a.sea_land;
    a.through[idx] = (a.trend[idx] < a.link_trend || open) ? 1.0f : 0.0f;
    a.conn[idx] = open ? 1.0f : 0.0f;
}
struct SeaGrowArgs {
    const float* src;
    const float* through;    // nullptr = plain 3x3 dilation
    float* dst;
    int H, W;
};
extern "C" __global__ void controls_sea_grow(const __grid_constant__ SeaGrowArgs a) {
    const int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= a.H * a.W) return;
    const int y = idx / a.W, x = idx % a.W;
    float m = 0.0f;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            const int yy = y + dy, xx = x + dx;
            if (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W && a.src[yy * a.W + xx] > 0.5f) m = 1.0f;
        }
    }
    a.dst[idx] = a.through != nullptr ? m * a.through[idx] : m;
}
)CU";
const std::string kC2xSrc = std::string(kC2Src) + kContLandSrc;
SBTG_MODULE("controls_c2x", kC2xSrc.c_str());

using controls::Cfg;
using controls::CfgRef;

// ---- host twins of the kernel argument structs (field for field)
struct Controls2Args {   // csrc/controls2.cu (kernels/cuda_controls2.py Controls2Args)
    float* out;
    const float* trend_v4;
    const float* wild_v4;
    int32_t ci0, cj0, hc, wc;
    int32_t layout;
    uint32_t hot_base;
    uint32_t bases[controls::kMaxRows];
    float invp[controls::kMaxRows];
    float k[controls::kMaxK];
    const float* ci_at;
    const float* cj_at;
    uint32_t bases_v4[controls::kMaxRowsV4];
    float invp_v4[controls::kMaxRowsV4];
    float kv4[controls::kMaxKV4];
};
static_assert(sizeof(Controls2Args) == 1536, "Controls2Args layout");
struct ContLandArgs {
    Controls2Args p;
    const float* trend;
    float* out;
    float* c2out;
    const float* link;
    int32_t ci0, cj0, hc, wc, v2;
    float sea_trend;
    int32_t link_a0, link_b0, link_nb, link_k;
};
struct SeaLinkArgs {
    Controls2Args p;
    float* si;
    float* sj;
    const float* trend;
    float* through;
    float* conn;
    int32_t a0, b0, na, nb, k, pass;
    float link_trend, sea_land;
};
struct SeaGrowArgs {
    const float* src;
    const float* through;
    float* dst;
    int32_t H, W;
};
struct PcArgs {          // csrc/controls.cu ControlsArgs (kernels/cuda_controls.py)
    float* out;
    const uint32_t* bases;
    const float* invp;
    const float* arch;
    const float* k;
    int32_t ci0, cj0, hc, wc;
    int32_t r[9];
};
struct GatesArgs {
    const float* gates;
    const float* aridity;
    const float* trend;
    float* out;
    long long n;
    int S, aeolian;
    int enabled[8];
    float a0, inv_a, r0, inv_r;
};
struct LapseArgs {
    float* t0;
    const float* elev;
    long long n;
    float lapse;
};

// csrc/controls2.cu's output channels (O_*), checked against cuda_controls2._FIELDS at load (build_cfg)
const char* const kF[] = {"wild", "hilly", "trend_sea", "trend", "lat", "belt", "wind_u", "wind_v", "coast",
                          "land", "trench", "ridge", "canyon", "karst", "landform", "karst2"};

int layout_id(const std::string& s) {
    if (s == "belts") return 0;
    if (s == "zones") return 1;
    if (s == "latitude") return 2;
    throw std::invalid_argument("climate layout '" + s + "'");
}

template <typename T, size_t N>
void fill_f(T (&dst)[N], const Json& j, const char* what) {
    std::vector<float> v = j.f32s();
    if (v.size() > N) throw std::length_error(std::string("manifest table too long: ") + what);
    for (size_t i = 0; i < v.size(); ++i) dst[i] = static_cast<T>(v[i]);
}
template <size_t N>
int fill_i(int (&dst)[N], const Json& j, const char* what) {
    std::vector<int64_t> v = j.i64s();
    if (v.size() > N) throw std::length_error(std::string("manifest table too long: ") + what);
    for (size_t i = 0; i < v.size(); ++i) dst[i] = static_cast<int>(v[i]);
    return static_cast<int>(v.size());
}

Cfg build_cfg(Gen& g) {
    Cfg c;
    std::memset(&c, 0, sizeof c);
    const Json& t = g.manifest["tables"];
    const Json& k = g.manifest["constants"];
    auto F = [&](const char* n) { return k[n].f32(); };
    auto D = [&](const char* n) { return k[n].num(); };
    auto I = [&](const char* n) { return static_cast<int>(k[n].i64()); };
    c.pc_n = fill_i(c.pc_streams, t["pc_streams"], "pc_streams");
    fill_f(c.pc_invp, t["pc_invp"], "pc_invp");
    fill_i(c.pc_rows, t["pc_rows"], "pc_rows");
    fill_f(c.pc_k, t["pc_k"], "pc_k");
    fill_f(c.pc_arch, t["pc_arch"], "pc_arch");
    c.c2_n = fill_i(c.c2_streams, t["c2_streams"], "c2_streams");
    fill_f(c.c2_invp, t["c2_invp"], "c2_invp");
    c.c2v_n = fill_i(c.c2v_streams, t["c2_v4_streams"], "c2_v4_streams");
    fill_f(c.c2v_invp, t["c2_v4_invp"], "c2_v4_invp");
    fill_f(c.c2_kv4, t["c2_kv4"], "c2_kv4");
    c.c2_nkv4 = static_cast<int>(t["c2_kv4"].size());
    const char* layouts[3] = {"c2_k_belts", "c2_k_zones", "c2_k_latitude"};
    for (int l = 0; l < 3; ++l) {
        fill_f(c.c2_k[l], t[layouts[l]], layouts[l]);
        c.c2_nk = static_cast<int>(t[layouts[l]].size());
    }
    c.k_cos_th = I("C2_K_COS_TH");
    c.k_sin_th = I("C2_K_SIN_TH");
    c.k_cos_flow = I("C2_K_COS_FLOW");
    c.k_sin_flow = I("C2_K_SIN_FLOW");
    c.hot_stream = I("C2_HOT_STREAM");
    c.salt_th = I("C2_SEED_ANGLE_SALT");
    c.salt_flow = I("C2_FLOW_ANGLE_SALT");
    c.n_fields = I("C2_N_FIELDS");
    {
        const Json& fl = t["c2_fields"];
        bool same = fl.size() == sizeof kF / sizeof kF[0];
        for (size_t i = 0; same && i < fl.size(); ++i) same = fl[i].str() == kF[i];
        if (!same || c.n_fields != static_cast<int>(fl.size()))
            throw std::logic_error("stages/controls: controls2 output fields changed (cuda_controls2._FIELDS)");
    }
    c.n_gates = I("C2_N_GATES");
    c.c035 = F("C2_C035");
    c.inv_land = F("C2_INV_LAND");
    c.sea_seed_trend = F("SEA_SEED_TREND_M");
    c.sea_link = static_cast<int>(k.num_or("SEA_LINK", 0.0));
    c.sea_link_cells = static_cast<int>(k.num_or("SEA_LINK_CELLS", 4.0));
    c.sea_link_steps = static_cast<int>(k.num_or("SEA_LINK_STEPS", 64.0));
    c.sea_link_trend = c.sea_link ? F("SEA_LINK_TREND_M") : 0.0f;
    c.sea_link_sea_land = c.sea_link ? F("SEA_LINK_SEA_LAND") : 0.5f;
    c.has_climate = I("HAS_CLIMATE");
    c.canonical = I("CANONICAL_CLIMATE");
    c.grid_cells = D("CLIMATE_GRID_CELLS");
    c.grid_cells_f = F("CLIMATE_GRID_CELLS_F");
    c.win = I("CLIMATE_WIN");
    c.core = I("CLIMATE_CORE");
    c.apron = I("CLIMATE_APRON");
    c.sub = I("CLIMATE_SUB");
    c.fetch = I("CLIMATE_FETCH_CELLS");
    c.grid_km = D("GRID_KM");
    c.inv_grid_km = F("INV_GRID_KM");
    c.wind_scale = F("WIND_SCALE");
    c.lw0 = F("LAND_WIND0");
    c.lw1 = F("LAND_WIND1");
    c.calm_inv = F("CALM_INV");
    c.up_eps = F("UPWIND_EPS");
    fill_f(c.near_km, t["upwind_near_km"], "upwind_near_km");
    c.n_near = static_cast<int>(t["upwind_near_km"].size());
    fill_f(c.far_km, t["upwind_far_km"], "upwind_far_km");
    c.n_far = static_cast<int>(t["upwind_far_km"].size());
    c.inv_near = F("UPWIND_INV_NEAR");
    c.inv_far = F("UPWIND_INV_FAR");
    c.forcing0 = F("CLIMATE_FORCING0");
    c.forcing1 = F("CLIMATE_FORCING1");
    c.n_out = I("CLIMATE_N_OUT");
    fill_f(c.mean, t["climate_out_mean"], "climate_out_mean");
    fill_f(c.stdv, t["climate_out_std"], "climate_out_std");
    {
        const Json& kms = t["continentality_km"];
        if (kms.size() != 3) throw std::invalid_argument("continentality_km: 3 entries expected");
        for (size_t i = 0; i < 3; ++i) c.kms[i] = kms[i].num();
    }
    c.aeolian = I("AEOLIAN_GATE");
    c.a0 = F("AEOLIAN_A0");
    c.inv_a = F("AEOLIAN_INV_A");
    c.r0 = F("AEOLIAN_R0");
    c.inv_r = F("AEOLIAN_INV_R");
    fill_i(c.enabled, t["experts_enabled"], "experts_enabled");
    c.prune = I("EXPERTS_PRUNE");
    c.detail_has_experts = I("DETAIL_HAS_EXPERTS");
    c.spc = I("SAMPLES_PER_CELL");
    c.detail_ext = I("DETAIL_EXT");
    c.cp = I("CP");
    c.lapse = F("LAPSE_C_PER_M");
    c.rd_lattice = I("RED_DESERT_LATTICE");
    c.rd_max_steps = I("RED_DESERT_MAX_STEPS");
    c.rd_octaves = I("RED_DESERT_OCTAVES");
    c.rd_stream = I("RED_DESERT_STREAM");
    c.rd_period = D("RED_DESERT_PERIOD_CELLS");
    c.rd_threshold = F("RED_DESERT_THRESHOLD");
    c.rd_softness = F("RED_DESERT_SOFTNESS");
    c.rd_warp_cells = F("RED_DESERT_WARP_CELLS");
    c.rd_warp_period = D("RED_DESERT_WARP_PERIOD");
    c.rd_jitter = F("RED_DESERT_JITTER");
    c.rd_jitter_period = D("RED_DESERT_JITTER_PERIOD");
    c.zone_cells = D("CLIMATE_ZONE_CELLS");
    c.n_arch = I("N_ARCHETYPES");
    fill_f(c.rd_logp, t["red_logp"], "red_logp");
    return c;
}

// controls2's per-(seed, layout) argument template (kernels/cuda_controls2.py _tables).
Controls2Args c2_args(const Cfg& c, int64_t seed, int layout) {
    Controls2Args a;
    std::memset(&a, 0, sizeof a);
    for (int r = 0; r < c.c2_n; ++r) {
        a.bases[r] = controls::stream_base(seed, c.c2_streams[r]);
        a.invp[r] = c.c2_invp[r];
    }
    for (int r = 0; r < c.c2v_n; ++r) {
        a.bases_v4[r] = controls::stream_base(seed, c.c2v_streams[r]);
        a.invp_v4[r] = c.c2v_invp[r];
    }
    for (int i = 0; i < c.c2_nk; ++i) a.k[i] = c.c2_k[layout][i];
    for (int i = 0; i < c.c2_nkv4; ++i) a.kv4[i] = c.c2_kv4[i];
    const double th = controls::py_uniform(seed, c.salt_th, 2 * M_PI);
    const double fl = controls::py_uniform(seed, c.salt_flow, M_PI);
    a.k[c.k_cos_th] = static_cast<float>(std::cos(th));
    a.k[c.k_sin_th] = static_cast<float>(std::sin(th));
    a.k[c.k_cos_flow] = static_cast<float>(std::cos(fl));
    a.k[c.k_sin_flow] = static_cast<float>(std::sin(fl));
    a.hot_base = controls::stream_base(seed, c.hot_stream);
    a.layout = layout;
    return a;
}

// The template for the generator's current seed and layout (cached: the angles' MT19937 seeding costs ~10 us).
Controls2Args c2_template(Gen& g, const Cfg& c) {
    const int layout = layout_id(g.st.climate_layout);
    Fields f = g.cache("controls.c2args", 16).get_or({g.st.seed, layout}, [&] {
        Controls2Args a = c2_args(c, g.st.seed, layout);
        Tensor t = g.gpu.empty(DType::U8, {static_cast<int64_t>(sizeof a)}, true);
        std::memcpy(t.ptr, &a, sizeof a);
        return Fields::of(t);
    });
    Controls2Args a;
    std::memcpy(&a, f.one().ptr, sizeof a);
    return a;
}

// v4 procedural_controls' device tables for the current seed: bases (u32), invp, arch, k (one upload, cached).
Tensor pc_tables(Gen& g, const Cfg& c) {
    const int64_t seed = g.st.seed;
    return g.cache("controls.pc_tables", 64).get_or({seed}, [&] {
        std::vector<uint32_t> buf(static_cast<size_t>(c.pc_n) * 2 + 28 + 16);
        for (int r = 0; r < c.pc_n; ++r) buf[static_cast<size_t>(r)] = controls::stream_base(seed, c.pc_streams[r]);
        std::memcpy(&buf[static_cast<size_t>(c.pc_n)], c.pc_invp, sizeof(float) * static_cast<size_t>(c.pc_n));
        std::memcpy(&buf[static_cast<size_t>(c.pc_n) * 2], c.pc_arch, sizeof(float) * 28);
        std::memcpy(&buf[static_cast<size_t>(c.pc_n) * 2 + 28], c.pc_k, sizeof(float) * 16);
        return Fields::of(g.gpu.upload(buf.data(), DType::I32, {static_cast<int64_t>(buf.size())}));
    }).one();
}

// ------------------------------------------------------------------------------------------------ _layout
Fields layout(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "_layout");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    CfgRef c = controls::cfg(g);
    // v4's base (generator.procedural_controls on its native kernel): wild, trend, t0, tseason, precip, pcv
    Tensor base = g.gpu.empty(DType::F32, {6, hc, wc});
    Tensor tab = pc_tables(g, *c);
    const int64_t n = hc * wc;
    if (n > 0) {
        const uint32_t* b = static_cast<const uint32_t*>(tab.ptr);
        const float* f = static_cast<const float*>(tab.ptr);
        PcArgs p{base.f32(), b, f + c->pc_n, f + 2 * c->pc_n, f + 2 * c->pc_n + 28,
                 static_cast<int32_t>(ci0), static_cast<int32_t>(cj0), static_cast<int32_t>(hc),
                 static_cast<int32_t>(wc), {}};
        for (int i = 0; i < 9; ++i) p.r[i] = c->pc_rows[i];
        g.gpu.launch(g.gpu.kernel("csrc_controls", "controls_kernel"), n, p, 128);
    }
    const int nf = c->n_fields + c->n_gates;
    Tensor out = g.gpu.empty(DType::F32, {nf, hc, wc});
    if (n > 0) {
        Controls2Args p = c2_template(g, *c);
        p.out = out.f32();
        p.trend_v4 = base[1].f32();
        p.wild_v4 = base[0].f32();
        p.ci0 = static_cast<int32_t>(ci0);
        p.cj0 = static_cast<int32_t>(cj0);
        p.hc = static_cast<int32_t>(hc);
        p.wc = static_cast<int32_t>(wc);
        g.gpu.launch(g.gpu.kernel("csrc_controls2", "controls2_kernel"), n, p, 128);
    }
    // controls2_native's dict: {**base (wild replaced, hilly added), trend_v4, trend_sea, trend (replaced), lat, ...}
    auto f = [&](const char* name) {
        for (int i = 0; i < static_cast<int>(sizeof kF / sizeof kF[0]); ++i)
            if (std::strcmp(kF[i], name) == 0) return out[i];
        throw std::logic_error(name);
    };
    Fields r;
    r.set("wild", f("wild"));
    r.set("trend", f("trend"));
    r.set("t0", base[2]);
    r.set("tseason", base[3]);
    r.set("precip", base[4]);
    r.set("pcv", base[5]);
    r.set("hilly", f("hilly"));
    r.set("trend_v4", base[1]);
    r.set("trend_sea", f("trend_sea"));
    r.set("lat", f("lat"));
    r.set("belt", f("belt"));
    r.set("wind_u", f("wind_u"));
    r.set("wind_v", f("wind_v"));
    {
        const int64_t s[3] = {c->n_gates, hc, wc};
        r.set("gates", controls::planes(out, c->n_fields, c->n_gates, 3, s));
    }
    for (const char* k : {"coast", "land", "trench", "ridge", "canyon", "karst", "landform", "karst2"}) r.set(k, f(k));
    return r;
}
SBTG_METHOD("_layout", layout);

// ------------------------------------------------------------------------------------------------ _controls_at
Fields controls_at(Gen& g, const Args& a) {
    need(a, 0, 0, 2, "_controls_at");
    CfgRef c = controls::cfg(g);
    Tensor ci = a.t[0].dtype == DType::F32 ? a.t[0] : ops::cast(g.gpu, a.t[0], DType::F32);
    Tensor cj = a.t[1].dtype == DType::F32 ? a.t[1] : ops::cast(g.gpu, a.t[1], DType::F32);
    if (ci.host) ci = g.gpu.to_device(ci);
    if (cj.host) cj = g.gpu.to_device(cj);
    const int64_t n = ci.numel();
    if (cj.numel() != n) throw std::invalid_argument("_controls_at: ci / cj sizes differ");
    if (ci.ndim > 3) throw std::invalid_argument("_controls_at: at most 3-D coordinates");
    const int nf = c->n_fields + c->n_gates;
    Tensor out = g.gpu.empty(DType::F32, {nf + 1, n});
    if (n > 0) {
        Controls2Args p = c2_template(g, *c);
        p.out = out.f32();
        p.ci_at = ci.f32();
        p.cj_at = cj.f32();
        p.ci0 = p.cj0 = 0;
        p.hc = 1;
        p.wc = static_cast<int32_t>(n);
        g.gpu.launch(g.gpu.kernel("csrc_controls2", "controls2_at_kernel"), n, p, 128);
    }
    std::array<int64_t, 4> sh{};
    for (int i = 0; i < ci.ndim; ++i) sh[static_cast<size_t>(i)] = ci.shape[static_cast<size_t>(i)];
    auto plane = [&](int i) { return controls::reshaped(out[i], ci.ndim, sh.data()); };
    auto f = [&](const char* name) {
        for (int i = 0; i < static_cast<int>(sizeof kF / sizeof kF[0]); ++i)
            if (std::strcmp(kF[i], name) == 0) return plane(i);
        throw std::logic_error(name);
    };
    Fields r;
    r.set("wild", f("wild"));
    r.set("trend", f("trend"));
    r.set("hilly", f("hilly"));
    r.set("trend_v4", plane(nf));
    r.set("trend_sea", f("trend_sea"));
    r.set("lat", f("lat"));
    r.set("belt", f("belt"));
    r.set("wind_u", f("wind_u"));
    r.set("wind_v", f("wind_v"));
    {
        int64_t s[4] = {c->n_gates, 1, 1, 1};
        for (int i = 0; i < ci.ndim; ++i) s[i + 1] = ci.shape[static_cast<size_t>(i)];
        r.set("gates", controls::planes(out, c->n_fields, c->n_gates, ci.ndim + 1, s));
    }
    for (const char* k : {"coast", "land", "trench", "ridge", "canyon", "karst", "landform", "karst2"}) r.set(k, f(k));
    return r;
}
SBTG_METHOD("_controls_at", controls_at);

// ------------------------------------------------------------------------------------------------ controls
Fields controls_m(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "controls");
    CfgRef c = controls::cfg(g);
    Fields r = g.call("_layout", a);
    if (!c->has_climate && !c->prune) return r;
    const Tensor& gates = r["gates"];
    const Tensor* arid = nullptr;
    if (c->has_climate) {
        Fields cc = g.call("climate_cells", a);
        for (auto& [k, t] : cc.items) r.set(k, t);
        arid = &r["aridity"];
    }
    Tensor out = g.gpu.empty_like(gates);
    const int64_t n = gates.numel() / gates.size(0);
    GatesArgs p{gates.f32(), arid != nullptr ? arid->f32() : nullptr, r["trend"].f32(), out.f32(), n,
                static_cast<int>(gates.size(0)), c->aeolian, {}, c->a0, c->inv_a, c->r0, c->inv_r};
    for (int i = 0; i < 8; ++i) p.enabled[i] = c->prune ? c->enabled[i] : 1;
    g.gpu.launch(g.gpu.kernel("controls", "controls_gates"), n * p.S, p);
    r.set("gates", out);
    return r;
}
SBTG_METHOD("controls", controls_m);

// ------------------------------------------------------------------------------------------------ continent_land_cells
// generator2.sea_link_cells before its gather: the (na, nb) super grid (1 = linked to the open ocean, one super cell of
// slack applied) covering cells [ci0, ci0+hc) x [cj0, cj0+wc) with SEA_LINK_STEPS + 2 super cells of context.
Tensor sea_link_grid(Gen& g, const Cfg& c, int64_t ci0, int64_t cj0, int64_t hc, int64_t wc, int64_t& a0,
                     int64_t& b0, int64_t& nb) {
    const int64_t K = c.sea_link_cells, S = c.sea_link_steps, m = S + 2;
    auto fdiv = [](int64_t x, int64_t k) { return x >= 0 ? x / k : -((-x + k - 1) / k); };
    a0 = fdiv(ci0, K) - m;
    b0 = fdiv(cj0, K) - m;
    const int64_t na = fdiv(ci0 + hc - 1, K) + 1 + m - a0;
    nb = fdiv(cj0 + wc - 1, K) + 1 + m - b0;
    const int64_t n = na * nb;
    Tensor si = g.gpu.empty(DType::F32, {na, nb}), sj = g.gpu.empty(DType::F32, {na, nb});
    Tensor through = g.gpu.empty(DType::F32, {na, nb}), conn = g.gpu.empty(DType::F32, {na, nb});
    Tensor nxt = g.gpu.empty(DType::F32, {na, nb});
    SeaLinkArgs p;
    std::memset(&p, 0, sizeof p);
    p.p = c2_template(g, c);
    p.si = si.f32();
    p.sj = sj.f32();
    p.through = through.f32();
    p.conn = conn.f32();
    p.a0 = static_cast<int32_t>(a0);
    p.b0 = static_cast<int32_t>(b0);
    p.na = static_cast<int32_t>(na);
    p.nb = static_cast<int32_t>(nb);
    p.k = static_cast<int32_t>(K);
    p.link_trend = c.sea_link_trend;
    p.sea_land = c.sea_link_sea_land;
    p.pass = 0;
    g.gpu.launch(g.gpu.kernel("controls_c2x", "controls_sea_link"), n, p, 128);
    Tensor trend = g.call("_controls_at", {{}, {}, {si, sj}})["trend"];
    p.trend = trend.f32();
    p.pass = 1;
    g.gpu.launch(g.gpu.kernel("controls_c2x", "controls_sea_link"), n, p, 128);
    SeaGrowArgs gr{conn.f32(), through.f32(), nxt.f32(), static_cast<int32_t>(na), static_cast<int32_t>(nb)};
    for (int64_t s = 0; s < S; ++s) {
        gr.src = conn.f32();
        gr.dst = nxt.f32();
        g.gpu.launch(g.gpu.kernel("controls_c2x", "controls_sea_grow"), n, gr, 128);
        std::swap(conn, nxt);
    }
    gr.src = conn.f32();
    gr.through = nullptr;        // the slack: one plain dilation
    gr.dst = nxt.f32();
    g.gpu.launch(g.gpu.kernel("controls_c2x", "controls_sea_grow"), n, gr, 128);
    return nxt;
}

Fields continent_land_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "continent_land_cells");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    CfgRef c = controls::cfg(g);
    const bool v2 = g.st.sea_shelf;
    Tensor trend;
    // v2: the layout trend (controls()["trend"] is the layout's: controls() only adds the climate keys and gates)
    if (v2) trend = g.call("_layout", a)["trend"];
    Tensor out = g.gpu.empty(DType::F32, {hc, wc});
    const int64_t n = hc * wc;
    Tensor link;                 // BV: generator2.sea_link_cells' super grid (v2 worlds, SEA_LINK)
    int64_t la0 = 0, lb0 = 0, lnb = 0;
    const bool use_link = v2 && c->sea_link && n > 0;
    if (use_link) link = sea_link_grid(g, *c, ci0, cj0, hc, wc, la0, lb0, lnb);
    if (n > 0) {
        ContLandArgs p;
        std::memset(&p, 0, sizeof p);
        p.p = c2_template(g, *c);
        p.trend = v2 ? trend.f32() : nullptr;
        p.link = use_link ? link.f32() : nullptr;
        p.link_a0 = static_cast<int32_t>(la0);
        p.link_b0 = static_cast<int32_t>(lb0);
        p.link_nb = static_cast<int32_t>(lnb);
        p.link_k = c->sea_link_cells;
        p.out = out.f32();
        p.ci0 = static_cast<int32_t>(ci0);
        p.cj0 = static_cast<int32_t>(cj0);
        p.hc = static_cast<int32_t>(hc);
        p.wc = static_cast<int32_t>(wc);
        p.v2 = v2 ? 1 : 0;
        p.sea_trend = c->sea_seed_trend;
        g.gpu.launch(g.gpu.kernel("controls_c2x", "controls_continent_land"), n, p, 128);
    }
    return Fields::of(out);
}
SBTG_METHOD("continent_land_cells", continent_land_cells);

// controls2.continent2(ci, cj, seed) over the float32 cell grids of [ci0, ci0+hc) x [cj0, cj0+wc) (the grids
// continent_land_cells builds): "0" = the continent field c2, "1" = its land weight (no trend gate).
Fields continent2_cells(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "C2.continent2");
    const int64_t ci0 = a.i[0], cj0 = a.i[1], hc = a.i[2], wc = a.i[3];
    CfgRef c = controls::cfg(g);
    Tensor c2 = g.gpu.empty(DType::F32, {hc, wc}), land = g.gpu.empty(DType::F32, {hc, wc});
    const int64_t n = hc * wc;
    if (n > 0) {
        ContLandArgs p;
        std::memset(&p, 0, sizeof p);
        p.p = c2_template(g, *c);
        p.out = land.f32();
        p.c2out = c2.f32();
        p.ci0 = static_cast<int32_t>(ci0);
        p.cj0 = static_cast<int32_t>(cj0);
        p.hc = static_cast<int32_t>(hc);
        p.wc = static_cast<int32_t>(wc);
        p.v2 = 2;
        g.gpu.launch(g.gpu.kernel("controls_c2x", "controls_continent_land"), n, p, 128);
    }
    Fields r;
    r.set("0", c2);
    r.set("1", land);
    return r;
}
SBTG_METHOD("C2.continent2", continent2_cells);

// ------------------------------------------------------------------------------------------------ climate_native
// generator.climate_native (+ generator2's aridity / soil / snow when the climate node is there).
Fields climate_native(Gen& g, const Args& a) {
    need(a, 4, 0, 1, "climate_native");
    const int64_t i1 = a.i[0], j1 = a.i[1], i2 = a.i[2], j2 = a.i[3];
    CfgRef c = controls::cfg(g);
    const int64_t CP = c->cp;
    const int64_t ci0 = floordiv(i1, CP) - 1, cj0 = floordiv(j1, CP) - 1;
    const int64_t hc = floordiv(i2 - 1, CP) + 2 - ci0, wc = floordiv(j2 - 1, CP) + 2 - cj0;
    Fields ctrl = g.call("controls", {{ci0, cj0, hc, wc}, {}, {}});
    std::vector<Tensor> planes;
    for (const char* k : {"t0", "tseason", "precip", "pcv"}) planes.push_back(ctrl[k]);
    if (c->has_climate)
        for (const char* k : {"aridity", "soil", "snow"}) planes.push_back(ctrl[k]);
    Tensor up = ops::bilinear(g.gpu, ops::stack(g.gpu, planes), static_cast<int>(hc * CP), static_cast<int>(wc * CP));
    Tensor clim = ops::crop(g.gpu, up, static_cast<int>(i1 - ci0 * CP), static_cast<int>(j1 - cj0 * CP),
                            static_cast<int>(i2 - i1), static_cast<int>(j2 - j1));
    Tensor elev = a.t[0].host ? g.gpu.to_device(a.t[0]) : a.t[0];
    elev = elev.dtype == DType::F32 ? elev : ops::cast(g.gpu, elev, DType::F32);
    const int64_t n = (i2 - i1) * (j2 - j1);
    if (elev.numel() != n) throw std::invalid_argument("climate_native: elev is " + elev.str());
    LapseArgs p{clim.f32(), elev.f32(), n, c->lapse};
    g.gpu.launch(g.gpu.kernel("controls", "controls_lapse"), n, p);
    return Fields::of(clim);
}
SBTG_METHOD("climate_native", climate_native);

// ------------------------------------------------------------------------------------------------ detail_gates
Fields detail_gates(Gen& g, const Args& a) {
    need(a, 4, 0, 0, "detail_gates");
    CfgRef c = controls::cfg(g);
    if (!c->detail_has_experts) return {};
    const int64_t ci0 = a.i[0], cj0 = a.i[1], nc = a.i[2], m = a.i[3];
    Tensor gates = g.call("controls", {{ci0, cj0, nc, nc}, {}, {}})["gates"];
    const int S = static_cast<int>(gates.size(0));
    Tensor up = ops::bilinear(g.gpu, gates, static_cast<int>(nc * c->spc), static_cast<int>(nc * c->spc));
    const int E = c->detail_ext;
    Tensor o = ops::crop(g.gpu, up, static_cast<int>(m), static_cast<int>(m), E, E);
    return Fields::of(o.view({1, S, E, E}));
}
SBTG_METHOD("detail_gates", detail_gates);

} // namespace

namespace controls {

CfgRef cfg(Gen& g) {
    CfgRef r;
    r.keep = g.cache("controls.cfg", 1).get_or({g.st.seed}, [&] {
        Cfg c = build_cfg(g);
        Tensor t = g.gpu.empty(DType::U8, {static_cast<int64_t>(sizeof(Cfg))}, true);
        std::memcpy(t.ptr, &c, sizeof c);
        return Fields::of(t);
    });
    r.p = static_cast<const Cfg*>(r.keep.one().ptr);
    return r;
}

// CPython's random: init_by_array over the 32-bit words of |n|, random() from two draws, uniform(a, b).
double py_uniform(int64_t seed, int64_t salt, double upper) {
    constexpr int N = 624, M = 397;
    uint32_t mt[N];
    // |seed * 0x9E3779B1 + salt| (salt >= 0, < 0x9E3779B1) as 32-bit words, little end first (Python ints: exact)
    const bool neg = seed < 0;
    const uint64_t mag = neg ? (~static_cast<uint64_t>(seed) + 1u) : static_cast<uint64_t>(seed);
    const uint64_t K = 0x9E3779B1u;
    const uint64_t lo = (mag & 0xFFFFFFFFu) * K, hi = (mag >> 32) * K;
    uint64_t w[3];
    w[0] = lo & 0xFFFFFFFFu;
    uint64_t carry = (lo >> 32) + (hi & 0xFFFFFFFFu);
    w[1] = carry & 0xFFFFFFFFu;
    w[2] = (carry >> 32) + (hi >> 32);
    const uint64_t sv = static_cast<uint64_t>(salt);
    if (!neg) {   // + salt
        uint64_t t = w[0] + sv;
        w[0] = t & 0xFFFFFFFFu;
        t = w[1] + (t >> 32);
        w[1] = t & 0xFFFFFFFFu;
        w[2] += t >> 32;
    } else if (mag != 0) {   // |-(P) + salt| = P - salt
        int64_t t = static_cast<int64_t>(w[0]) - static_cast<int64_t>(sv);
        uint64_t borrow = t < 0 ? 1u : 0u;
        w[0] = static_cast<uint64_t>(t + (borrow ? (int64_t{1} << 32) : 0));
        t = static_cast<int64_t>(w[1]) - static_cast<int64_t>(borrow);
        borrow = t < 0 ? 1u : 0u;
        w[1] = static_cast<uint64_t>(t + (borrow ? (int64_t{1} << 32) : 0));
        w[2] -= borrow;
    }
    std::vector<uint32_t> key;
    int top = 2;
    while (top > 0 && w[top] == 0) --top;
    for (int q = 0; q <= top; ++q) key.push_back(static_cast<uint32_t>(w[q]));
    mt[0] = 19650218u;
    for (int i = 1; i < N; ++i) mt[i] = 1812433253u * (mt[i - 1] ^ (mt[i - 1] >> 30)) + static_cast<uint32_t>(i);
    int i = 1;
    size_t j = 0;
    const size_t kl = key.size();
    for (size_t k = static_cast<size_t>(N) > kl ? N : kl; k; --k) {
        mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >> 30)) * 1664525u)) + key[j] + static_cast<uint32_t>(j);
        ++i;
        ++j;
        if (i >= N) {
            mt[0] = mt[N - 1];
            i = 1;
        }
        if (j >= kl) j = 0;
    }
    for (int k = N - 1; k; --k) {
        mt[i] = (mt[i] ^ ((mt[i - 1] ^ (mt[i - 1] >> 30)) * 1566083941u)) - static_cast<uint32_t>(i);
        ++i;
        if (i >= N) {
            mt[0] = mt[N - 1];
            i = 1;
        }
    }
    mt[0] = 0x80000000u;
    // first block of genrand_uint32
    static const uint32_t mag01[2] = {0x0u, 0x9908b0dfu};
    int kk;
    uint32_t y;
    for (kk = 0; kk < N - M; ++kk) {
        y = (mt[kk] & 0x80000000u) | (mt[kk + 1] & 0x7fffffffu);
        mt[kk] = mt[kk + M] ^ (y >> 1) ^ mag01[y & 1u];
    }
    for (; kk < N - 1; ++kk) {
        y = (mt[kk] & 0x80000000u) | (mt[kk + 1] & 0x7fffffffu);
        mt[kk] = mt[kk + (M - N)] ^ (y >> 1) ^ mag01[y & 1u];
    }
    y = (mt[N - 1] & 0x80000000u) | (mt[0] & 0x7fffffffu);
    mt[N - 1] = mt[M - 1] ^ (y >> 1) ^ mag01[y & 1u];
    auto temper = [](uint32_t x) {
        x ^= (x >> 11);
        x ^= (x << 7) & 0x9d2c5680u;
        x ^= (x << 15) & 0xefc60000u;
        x ^= (x >> 18);
        return x;
    };
    const uint32_t ra = temper(mt[0]) >> 5, rb = temper(mt[1]) >> 6;
    const double r = (static_cast<double>(ra) * 67108864.0 + static_cast<double>(rb)) * (1.0 / 9007199254740992.0);
    return 0.0 + (upper - 0.0) * r;
}

} // namespace controls
} // namespace sbtg
