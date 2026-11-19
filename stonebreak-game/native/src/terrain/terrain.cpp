// libstonebreak_terrain: the DaedalusTGM terrain pipeline's stages in C++ over Open Mason's engine runtime (ci_*).
// See include/stonebreak/terrain.h.
#include "stonebreak/terrain.h"

#include "cenda/infer.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace {

thread_local std::string t_error;

const char kCellsSource[] =
#include "sbtg_cells.cu.inc"
    ;
} // namespace
#include "core/gpu.hpp"
SBTG_MODULE("sbtg_cells", kCellsSource);   // the phase-2 stage kernels, for stage code too
namespace {

} // namespace
#include "core/ci.hpp"
using sbtg::Ci;
using sbtg::EngineError;
namespace {

// ---------------------------------------------------------------------------------------------- kernel args
// Field for field the structs of src/terrain/cuda/sbtg_cells.cu.
constexpr int kMaxW = 32;
struct GatherArgs {
    const float* src;
    float* dst;
    int C, H, W, win, n;
    int offs[2 * kMaxW];
};
struct BlurArgs {
    const float* src;
    float* dst;
    const float* k;
    int P, H, W, r, axis, fma;
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

struct ClimateArgs {
    const float* y;
    float* outs[7];
    long long ci0, cj0, gi0, gj0;
    int hc, wc, gh, gw;
    double inv_g;
    float mean[7], std_[7];
};

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

uint32_t lowbias32(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}

// synth/noise.white_noise's per-call base: hash32((seed * 0x9E3779B1 + stream * 0x85EBCA77) & 0xFFFFFFFF), Python ints
// (exact mod 2^32 in uint64 arithmetic, negative seeds included).
uint32_t noise_base(int64_t seed, int64_t stream) {
    uint64_t v = static_cast<uint64_t>(seed) * 0x9E3779B1ULL + static_cast<uint64_t>(stream) * 0x85EBCA77ULL;
    return lowbias32(static_cast<uint32_t>(v & 0xFFFFFFFFULL));
}

} // namespace

struct sbtg_ctx {
    Ci ci;
    ci_runtime* rt = nullptr;

    // Every stage writes context-owned scratch on the caller's stream (review-native F8): calls are serialised, and a
    // call on another stream than the previous one first waits for the previous stream's work (event fence).
    std::mutex call_mu;
    void* last_stream = nullptr;
    bool any_call = false;
    void* fence = nullptr;

    struct Call {
        std::unique_lock<std::mutex> lock;
        Call(sbtg_ctx& c, void* stream) : lock(c.call_mu) {
            if (c.any_call && stream != c.last_stream) {
                if (c.fence == nullptr) {
                    c.fence = c.ci.event_create(c.rt);
                    if (c.fence == nullptr) throw EngineError(std::string("ci_event_create: ") + c.ci.last_error());
                }
                c.check(c.ci.event_record(c.rt, c.fence, c.last_stream), "ci_event_record");
                c.check(c.ci.stream_wait(c.rt, stream, c.fence), "ci_stream_wait");
            }
            c.any_call = true;
            c.last_stream = stream;
        }
    };
    // Constants copied by configure() (legacy stream, possibly pageable memory) are complete before any caller stream
    // reads them.
    void configured() { check(ci.stream_sync(rt, nullptr), "ci_stream_sync(configure)"); }
    std::atomic<int64_t> n_calls{0}, n_windows{0}, n_launches{0}, n_requests{0}, n_relief{0}, n_detail{0};

    ci_kernel *k_gather = nullptr, *k_blur = nullptr, *k_post = nullptr, *k_hill = nullptr, *k_sea = nullptr;

    // planner stage
    bool planner_ready = false;
    sbtg_planner_config pc{};
    std::vector<float> k_patch, k_hill_taps, k_sea_taps;
    float* dev = nullptr;  // arena: constants + workspace
    size_t dev_bytes = 0;
    float *d_k_patch = nullptr, *d_k_hill = nullptr, *d_k_sea = nullptr, *d_mean = nullptr, *d_std = nullptr;
    float *xs = nullptr, *o = nullptr, *tmp = nullptr, *ob = nullptr, *h0 = nullptr, *T = nullptr, *bh0 = nullptr,
          *bT = nullptr, *h1 = nullptr, *bh1 = nullptr;

    // relief stage
    bool relief_ready = false;
    sbtg_relief_config rc{};
    float* rdev = nullptr;
    float *r_taps = nullptr, *r_smooth = nullptr, *r_tmp = nullptr, *r_cond = nullptr, *r_noise = nullptr, *r_x = nullptr;
    ci_kernel *k_rcond = nullptr, *k_rpost = nullptr;

    ~sbtg_ctx() {
        if (dev != nullptr && ci.free_ != nullptr) ci.free_(rt, dev);
        if (rdev != nullptr && ci.free_ != nullptr) ci.free_(rt, rdev);
        if (ddev != nullptr && ci.free_ != nullptr) ci.free_(rt, ddev);
        if (h_bad != nullptr && ci.host_free != nullptr) ci.host_free(rt, h_bad);
        if (fence != nullptr && ci.event_destroy != nullptr) ci.event_destroy(rt, fence);
    }

    void relief_configure(const sbtg_relief_config& c) {
        if (c.model == nullptr || c.n <= 0 || c.m < 0 || c.k_blur == nullptr || c.r_blur > c.m || c.r_blur < 0)
            throw std::invalid_argument("relief config");
        rc = c;
        const size_t N = static_cast<size_t>(c.n + 2 * c.m), nn = static_cast<size_t>(c.n) * static_cast<size_t>(c.n);
        const size_t taps = 256;
        if (static_cast<size_t>(2 * c.r_blur + 1) > taps) throw std::invalid_argument("relief blur too wide");
        size_t total = taps + 2 * N * N + 10 * nn + 2 * nn;
        if (rdev != nullptr) ci.free_(rt, rdev);
        rdev = static_cast<float*>(ci.malloc_(rt, total * sizeof(float)));
        if (rdev == nullptr) throw EngineError(std::string("ci_malloc: ") + ci.last_error());
        r_taps = rdev;
        r_smooth = rdev + taps;
        r_tmp = r_smooth + N * N;
        r_cond = r_tmp + N * N;
        r_noise = r_cond + 10 * nn;
        r_x = r_noise + nn;
        check(ci.memcpy_(rt, r_taps, c.k_blur, sizeof(float) * static_cast<size_t>(2 * c.r_blur + 1), nullptr),
              "ci_memcpy(relief taps)");
        rc.k_blur = nullptr;
        k_blur = kernel("sbtg_blur");
        k_rcond = kernel("sbtg_relief_cond");
        k_rpost = kernel("sbtg_relief_post");
        relief_ready = true;
    }

    // detail stage
    bool detail_ready = false;
    sbtg_detail_config dc{};
    float* ddev = nullptr;
    int* d_bad = nullptr;
    int* h_bad = nullptr;
    float *d_taps = nullptr, *d_rawb = nullptr, *d_up = nullptr, *d_base_full = nullptr, *d_btmp = nullptr,
          *d_river = nullptr, *d_cond = nullptr, *d_gates = nullptr, *d_noise = nullptr, *d_base = nullptr,
          *d_x = nullptr, *d_ctmp = nullptr;
    int tap_off[8] = {0}, tap_r[8] = {0}, n_attempts = 0, base_off = 0, base_r = 0, river_off = 0, river_r = 0;
    ci_kernel *k_up = nullptr, *k_dcond = nullptr, *k_dout = nullptr;

    void detail_configure(const sbtg_detail_config& c) {
        if (c.model == nullptr || c.nc <= 0 || c.f <= 0 || c.E <= 0 || c.n_attempts < 1 || c.n_attempts > 8)
            throw std::invalid_argument("detail config");
        dc = c;
        std::vector<float> taps;
        auto add = [&](const float* t, int r, int& off, int& rr) {
            off = static_cast<int>(taps.size());
            rr = r;
            taps.insert(taps.end(), t, t + 2 * r + 1);
        };
        n_attempts = c.n_attempts;
        for (int k = 0; k < c.n_attempts; ++k) add(c.k_attempt[k], c.r_attempt[k], tap_off[k], tap_r[k]);
        add(c.k_base, c.r_base, base_off, base_r);
        add(c.k_river, c.r_river, river_off, river_r);
        const size_t nc2 = static_cast<size_t>(c.nc) * static_cast<size_t>(c.nc), F = static_cast<size_t>(c.nc) * static_cast<size_t>(c.f),
                     ee = static_cast<size_t>(c.E) * static_cast<size_t>(c.E);
        size_t total = 256 + 2 * nc2 + 3 * F * F + nc2 + 22 * ee + static_cast<size_t>(c.S) * ee + 3 * ee + nc2;
        if (taps.size() > 256) throw std::invalid_argument("detail blur taps too long");
        if (ddev != nullptr) ci.free_(rt, ddev);
        ddev = static_cast<float*>(ci.malloc_(rt, total * sizeof(float) + 64));
        if (ddev == nullptr) throw EngineError(std::string("ci_malloc: ") + ci.last_error());
        if (h_bad == nullptr) h_bad = static_cast<int*>(ci.host_alloc(rt, 64));
        if (h_bad == nullptr) throw EngineError(std::string("ci_host_alloc: ") + ci.last_error());
        float* p = ddev;
        d_taps = p; p += 256;
        d_rawb = p; p += nc2;
        d_ctmp = p; p += nc2;
        d_up = p; p += F * F;
        d_btmp = p; p += F * F;
        d_base_full = p; p += F * F;
        d_river = p; p += nc2;
        d_cond = p; p += 22 * ee;
        d_gates = p; p += static_cast<size_t>(c.S) * ee;
        d_noise = p; p += ee;
        d_base = p; p += ee;
        d_x = p; p += ee;
        d_bad = reinterpret_cast<int*>(p);
        check(ci.memcpy_(rt, d_taps, taps.data(), taps.size() * sizeof(float), nullptr), "ci_memcpy(detail taps)");
        for (int k = 0; k < 8; ++k) {
            dc.k_attempt[k] = nullptr;
        }
        dc.k_base = dc.k_river = nullptr;
        k_blur = kernel("sbtg_blur");
        k_up = kernel("sbtg_up");
        k_dcond = kernel("sbtg_detail_cond");
        k_dout = kernel("sbtg_detail_out");
        detail_ready = true;
    }

    void blur_into(const float* src, float* dst, float* tmp_buf, int taps_off, int r, int planes, int h, int w,
                   void* stream) {
        BlurArgs a{src, tmp_buf, d_taps + taps_off, planes, h, w, r, 0, 1};
        launch(k_blur, static_cast<long long>(planes) * h * w, a, stream);
        BlurArgs b{tmp_buf, dst, d_taps + taps_off, planes, h, w, r, 1, 1};
        launch(k_blur, static_cast<long long>(planes) * h * w, b, stream);
    }

    int detail_window(const float* const f[9], int64_t si0, int64_t sj0, int64_t seed, float has_flow, float* out,
                      void* stream) {
        if (!detail_ready) throw std::logic_error("detail not configured");
        for (int k = 0; k < 6; ++k)
            if (f[k] == nullptr) throw std::invalid_argument("detail field missing");
        const int nc = dc.nc, F = dc.nc * dc.f, E = dc.E;
        const long long FF = static_cast<long long>(F) * F,
                        ee = static_cast<long long>(E) * E;
        const float* river = nullptr;
        if (f[7] != nullptr) {
            blur_into(f[7], d_river, d_ctmp, river_off, river_r, 1, nc, nc, stream);
            river = d_river;
        }
        for (int at = 0; at < n_attempts; ++at) {
            blur_into(f[0], d_rawb, d_ctmp, tap_off[at], tap_r[at], 1, nc, nc, stream);
            UpArgs u{d_rawb, d_up, 1, nc, nc, dc.f, dc.up_fma};
            launch(k_up, FF, u, stream);
            blur_into(d_up, d_base_full, d_btmp, base_off, base_r, 1, F, F, stream);
            DetailCondArgs a{};
            a.base_full = d_base_full;
            a.wild = f[1];
            a.t0 = f[2];
            a.tseason = f[3];
            a.precip = f[4];
            a.pcv = f[5];
            a.logacc = f[6];
            a.river = river;
            a.gates = f[8];
            a.cond = d_cond;
            a.gates_up = d_gates;
            a.noise = d_noise;
            a.base = d_base;
            a.nc = nc;
            a.f = dc.f;
            a.E = E;
            a.ms = dc.ms;
            a.S = dc.S;
            a.fm = dc.up_fma;
            a.si0 = si0;
            a.sj0 = sj0;
            a.base_hash = noise_base(seed, dc.noise_stream);
            a.sqrt3 = static_cast<float>(std::sqrt(3.0));
            a.has_climate = dc.use_climate[at] ? 1.0f : 0.0f;
            a.has_flow = has_flow;
            a.inv_height = 1.0f / dc.height_scale;
            a.inv_slope = 1.0f / dc.slope_scale;
            a.wild_off = dc.wild_offset;
            a.inv_wild = 1.0f / dc.wild_scale;
            a.inv8 = 1.0f / 8.0f;
            a.inv300 = 1.0f / 300.0f;
            a.log800 = static_cast<float>(std::log(800.0));
            a.inv09 = 1.0f / 0.9f;
            a.inv30 = 1.0f / 30.0f;
            a.inv_logacc = 1.0f / dc.logacc_scale;
            launch(k_dcond, ee, a, stream);
            ci_request req{};
            req.batch = 1;
            req.flags = CI_RUN_STREAM;
            req.inputs[0] = d_cond;
            req.inputs[1] = d_noise;
            req.inputs[2] = f[8] != nullptr ? d_gates : nullptr;
            req.outputs[0] = d_x;
            req.stream = stream;
            check(ci.run(static_cast<ci_model*>(dc.model), &req), "ci_run(detail)");
            n_requests++;
            check(ci.memset_(rt, d_bad, 0, sizeof(int), stream), "ci_memset");
            DetailOutArgs oa{d_x, d_base, out, d_bad, ee, dc.x_limit, dc.asinh_m};
            launch(k_dout, ee, oa, stream);
            check(ci.memcpy_(rt, h_bad, d_bad, sizeof(int), stream), "ci_memcpy(guard)");
            check(ci.stream_sync(rt, stream), "ci_stream_sync(guard)");   // the guard's one sync per attempt
            n_detail++;
            if (*h_bad == 0) return at;
        }
        // every attempt failed: the smooth base of the last attempt alone (generator: res = zeros)
        check(ci.memcpy_(rt, out, d_base, static_cast<size_t>(ee) * sizeof(float), stream), "ci_memcpy(base)");
        return -1;
    }

    ci_kernel* k_climate = nullptr;

    void climate_cells(const float* y, int gh, int gw, int64_t gi0, int64_t gj0, int64_t ci0, int64_t cj0, int hc,
                       int wc, double grid_cells, const float* mean, const float* stdv, float* const outs[7],
                       void* stream) {
        if (k_climate == nullptr) k_climate = kernel("sbtg_climate_cells");
        ClimateArgs a{};
        a.y = y;
        for (int c = 0; c < 7; ++c) {
            a.outs[c] = outs[c];
            a.mean[c] = mean[c];
            a.std_[c] = stdv[c];
        }
        a.ci0 = ci0;
        a.cj0 = cj0;
        a.gi0 = gi0;
        a.gj0 = gj0;
        a.hc = hc;
        a.wc = wc;
        a.gh = gh;
        a.gw = gw;
        a.inv_g = 1.0 / grid_cells;
        launch(k_climate, static_cast<long long>(hc) * wc, a, stream);
    }

    void relief_window(const float* const f[6], int64_t ci0, int64_t cj0, int64_t seed, float* out, void* stream) {
        if (!relief_ready) throw std::logic_error("relief not configured");
        for (int k = 0; k < 6; ++k)
            if (f[k] == nullptr) throw std::invalid_argument("relief field missing");
        const int n = rc.n, N = rc.n + 2 * rc.m;
        const long long NN = static_cast<long long>(N) * N, nn = static_cast<long long>(n) * n;
        BlurArgs b0{f[0], r_tmp, r_taps, 1, N, N, rc.r_blur, 0, 1};
        launch(k_blur, NN, b0, stream);
        BlurArgs b1{r_tmp, r_smooth, r_taps, 1, N, N, rc.r_blur, 1, 1};
        launch(k_blur, NN, b1, stream);
        ReliefCondArgs a{r_smooth, f[1], f[2], f[3], f[4], f[5], r_cond, r_noise, N, n, rc.m, ci0, cj0,
                         noise_base(seed, rc.noise_stream), static_cast<float>(std::sqrt(3.0)),
                         1.0f / rc.height_scale, 1.0f / rc.slope_scale, rc.wild_offset, 1.0f / rc.wild_scale,
                         1.0f / 8.0f, 1.0f / 300.0f, static_cast<float>(std::log(800.0)), 1.0f / 0.9f, 1.0f / 30.0f};
        launch(k_rcond, nn, a, stream);
        ci_request req{};
        req.batch = 1;
        req.flags = CI_RUN_STREAM;
        req.inputs[0] = r_cond;
        req.inputs[1] = r_noise;
        req.outputs[0] = r_x;
        req.stream = stream;
        check(ci.run(static_cast<ci_model*>(rc.model), &req), "ci_run(relief)");
        n_requests++;
        ReliefPostArgs p{r_smooth, r_x, out, N, n, rc.m, 1.0f / 300.0f, rc.scale, rc.gain, rc.floor_m};
        launch(k_rpost, nn, p, stream);
        n_relief++;
    }

    void check(int32_t r, const char* what) {
        if (r != 0) throw EngineError(std::string(what) + ": " + ci.last_error());
    }
    ci_kernel* kernel(const char* name) {
        ci_kernel* k = ci.kernel_get(rt, "sbtg_cells", kCellsSource, name);
        if (k == nullptr) throw EngineError(std::string("compiling ") + name + ": " + ci.last_error());
        return k;
    }
    template <typename A>
    void launch(ci_kernel* k, long long threads, const A& args, void* stream, unsigned block = 256) {
        if (threads <= 0) return;
        uint32_t grid[3] = {static_cast<uint32_t>((threads + block - 1) / block), 1, 1};
        check(ci.launch(rt, k, grid, block, 0, &args, sizeof(A), stream), "ci_launch");
        n_launches++;
    }

    void blur(const float* src, float* dst, const float* taps, int r, int planes, int h, int w, void* stream) {
        BlurArgs a{src, tmp, taps, planes, h, w, r, 0, pc.blur_fma};
        launch(k_blur, static_cast<long long>(planes) * h * w, a, stream);
        BlurArgs b{tmp, dst, taps, planes, h, w, r, 1, pc.blur_fma};
        launch(k_blur, static_cast<long long>(planes) * h * w, b, stream);
    }

    void configure(const sbtg_planner_config& c) {
        if (c.win <= 0 || c.n_in <= 0 || c.n_out != 11 || c.model == nullptr) throw std::invalid_argument("planner config");
        if (c.k_patch == nullptr || c.k_hill == nullptr || c.k_sea == nullptr) throw std::invalid_argument("blur taps");
        pc = c;
        k_patch.assign(c.k_patch, c.k_patch + 2 * c.r_patch + 1);
        k_hill_taps.assign(c.k_hill, c.k_hill + 2 * c.r_hill + 1);
        k_sea_taps.assign(c.k_sea, c.k_sea + 2 * c.r_sea + 1);
        pc.k_patch = pc.k_hill = pc.k_sea = nullptr;
        if (c.r_patch >= c.win || c.r_hill >= c.win || c.r_sea >= c.win) throw std::invalid_argument("blur radius >= window");
        const size_t ww = static_cast<size_t>(c.win) * static_cast<size_t>(c.win);
        const size_t consts = 256;   // floats: taps (<= 3 x 61) + mean + std
        const size_t big = kMaxW * 11 * ww, one = kMaxW * ww;
        size_t total = consts + static_cast<size_t>(c.n_in) * kMaxW * ww + 3 * big + 6 * one;
        if (dev != nullptr) ci.free_(rt, dev);
        dev = static_cast<float*>(ci.malloc_(rt, total * sizeof(float)));
        if (dev == nullptr) throw EngineError(std::string("ci_malloc: ") + ci.last_error());
        dev_bytes = total * sizeof(float);
        std::vector<float> host(consts, 0.0f);
        size_t at = 0;
        auto put = [&](const std::vector<float>& v) {
            if (at + v.size() > consts) throw std::invalid_argument("blur kernels too long");
            std::copy(v.begin(), v.end(), host.begin() + static_cast<long>(at));
            float* p = dev + at;
            at += v.size();
            return p;
        };
        d_k_patch = put(k_patch);
        d_k_hill = put(k_hill_taps);
        d_k_sea = put(k_sea_taps);
        d_mean = put(std::vector<float>(c.desc_mean, c.desc_mean + 8));
        d_std = put(std::vector<float>(c.desc_std, c.desc_std + 8));
        check(ci.memcpy_(rt, dev, host.data(), consts * sizeof(float), nullptr), "ci_memcpy(constants)");
        float* p = dev + consts;
        xs = p;
        p += static_cast<size_t>(c.n_in) * kMaxW * ww;
        o = p;
        p += big;
        tmp = p;
        p += big;
        ob = p;
        p += big;
        h0 = p;
        p += one;
        T = p;
        p += one;
        bh0 = p;
        p += one;
        bT = p;
        p += one;
        h1 = p;
        p += one;
        bh1 = p;
        k_gather = kernel("sbtg_gather");
        k_blur = kernel("sbtg_blur");
        k_post = kernel("sbtg_planner_post");
        k_hill = kernel("sbtg_hill");
        k_sea = kernel("sbtg_sea");
        planner_ready = true;
    }

    void planner_windows(const float* input, const float* trend, int H, int W, const int32_t* offs, int n,
                         float* const* outs, const float* override_out, void* stream) {
        if (!planner_ready) throw std::logic_error("planner not configured");
        if (n < 1 || n > kMaxW) throw std::invalid_argument("planner_windows: 1..32 windows per call");
        const int win = pc.win;
        for (int b = 0; b < n; ++b)
            if (offs[2 * b] < 0 || offs[2 * b + 1] < 0 || offs[2 * b] + win > H || offs[2 * b + 1] + win > W)
                throw std::invalid_argument("planner window outside its input");
        const long long ww = static_cast<long long>(win) * win;
        GatherArgs g{input, xs, pc.n_in, H, W, win, n, {}};
        std::memcpy(g.offs, offs, sizeof(int32_t) * 2 * static_cast<size_t>(n));
        const float* out = override_out;
        if (out == nullptr) {
            launch(k_gather, ww * pc.n_in * n, g, stream);
            ci_request req{};
            req.batch = n;
            req.flags = CI_RUN_STREAM;
            req.inputs[0] = xs;
            req.outputs[0] = o;
            req.stream = stream;
            check(ci.run(static_cast<ci_model*>(pc.model), &req), "ci_run(planner)");
            n_requests++;
            out = o;
        }
        blur(out, ob, d_k_patch, pc.r_patch, n * 11, win, win, stream);
        PostArgs pa{ob, {}, h0, d_mean, d_std, n, win, pc.height_scale, pc.logacc_scale};
        for (int b = 0; b < n; ++b) pa.outs[b] = outs[b];
        launch(k_post, ww * 11 * n, pa, stream);
        GatherArgs gt{trend, T, 1, H, W, win, n, {}};
        std::memcpy(gt.offs, offs, sizeof(int32_t) * 2 * static_cast<size_t>(n));
        launch(k_gather, ww * n, gt, stream);
        const bool hill = pc.hill_transfer > 0.0f;
        if (hill) {
            blur(h0, bh0, d_k_hill, pc.r_hill, n, win, win, stream);
            blur(T, bT, d_k_hill, pc.r_hill, n, win, win, stream);
        }
        HillArgs ha{h0, T, bh0, bT, h1, ww * n, pc.hill_transfer, 1.0f / 60.0f, hill ? 1 : 0};
        launch(k_hill, ww * n, ha, stream);
        if (pc.sea_detail) blur(h1, bh1, d_k_sea, pc.r_sea, n, win, win, stream);
        SeaArgs sa{};
        sa.h1 = h1;
        sa.T = T;
        sa.bh1 = bh1;
        for (int b = 0; b < n; ++b) sa.outs[b] = outs[b];
        sa.n = n;
        sa.win = win;
        sa.sea_detail = pc.sea_detail;
        sa.inv150 = 1.0f / 150.0f;
        sa.max_m = pc.sea_detail_max_m;
        sa.floor_m = pc.sea_floor_m;
        sa.k = pc.shelf_slope;
        sa.dm = pc.shelf_depth_m;
        sa.inv_dm = 1.0f / pc.shelf_depth_m;
        sa.one_minus_k = static_cast<float>(1.0 - static_cast<double>(pc.shelf_slope));
        sa.k0_minus_k = static_cast<float>(static_cast<double>(pc.shelf_keep) - static_cast<double>(pc.shelf_slope));
        sa.d0 = pc.shelf_keep_m;
        sa.inv_d0 = pc.shelf_keep_m > 0.0f ? 1.0f / pc.shelf_keep_m : 0.0f;
        sa.keep_on = (pc.shelf_keep != pc.shelf_slope && pc.shelf_keep_m > 0.0f) ? 1 : 0;
        launch(k_sea, ww * n, sa, stream);
        n_calls++;
        n_windows += n;
    }
};

namespace {
int32_t fail(int32_t code, const char* msg) {
    t_error = msg;
    return code;
}
int32_t translate(const std::exception& e) {
    if (dynamic_cast<const EngineError*>(&e)) return fail(SBTG_ERR_ENGINE, e.what());
    if (dynamic_cast<const std::logic_error*>(&e) && !dynamic_cast<const std::invalid_argument*>(&e))
        return fail(SBTG_ERR_STATE, e.what());
    return fail(SBTG_ERR_ARG, e.what());
}
} // namespace

namespace sbtg {
const Ci* ctx_ci(const sbtg_ctx* ctx) { return &ctx->ci; }
ci_runtime* ctx_rt(const sbtg_ctx* ctx) { return ctx->rt; }
void set_error(const std::string& msg) { t_error = msg; }
} // namespace sbtg

#if !defined(_WIN32)
#pragma GCC visibility push(default)   // the C ABI only (everything else stays hidden)
#endif
extern "C" {

int32_t sbtg_abi_version(void) { return SBTG_ABI_VERSION; }

const char* sbtg_last_error(void) { return t_error.c_str(); }

sbtg_ctx* sbtg_create(const char* cenda_lib, void* ci_rt) {
    if (cenda_lib == nullptr || ci_rt == nullptr) {
        t_error = "sbtg_create: null library path or runtime";
        return nullptr;
    }
    try {
        auto* c = new sbtg_ctx();
        try {
            c->ci.bind(cenda_lib);
            c->rt = static_cast<ci_runtime*>(ci_rt);
        } catch (...) {
            delete c;
            throw;
        }
        t_error.clear();
        return c;
    } catch (const std::exception& e) {
        t_error = e.what();
        return nullptr;
    }
}

void sbtg_destroy(sbtg_ctx* ctx) { delete ctx; }

int32_t sbtg_planner_configure(sbtg_ctx* ctx, const sbtg_planner_config* cfg) {
    if (ctx == nullptr || cfg == nullptr) return fail(SBTG_ERR_ARG, "null context or config");
    try {
        std::lock_guard<std::mutex> lock(ctx->call_mu);
        ctx->configure(*cfg);
        ctx->configured();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_planner_windows(sbtg_ctx* ctx, const float* input, const float* trend, int32_t H, int32_t W,
                             const int32_t* offs, int32_t n, float* const* outs, const float* override_out,
                             void* stream) {
    if (ctx == nullptr || input == nullptr || trend == nullptr || offs == nullptr || outs == nullptr)
        return fail(SBTG_ERR_ARG, "null argument");
    try {
        sbtg_ctx::Call guard(*ctx, stream);
        ctx->planner_windows(input, trend, H, W, offs, n, outs, override_out, stream);
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_relief_configure(sbtg_ctx* ctx, const sbtg_relief_config* cfg) {
    if (ctx == nullptr || cfg == nullptr) return fail(SBTG_ERR_ARG, "null context or config");
    try {
        std::lock_guard<std::mutex> lock(ctx->call_mu);
        ctx->relief_configure(*cfg);
        ctx->configured();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_relief_window(sbtg_ctx* ctx, const float* const fields[6], int64_t ci0, int64_t cj0, int64_t seed,
                           float* out, void* stream) {
    if (ctx == nullptr || fields == nullptr || out == nullptr) return fail(SBTG_ERR_ARG, "null argument");
    try {
        sbtg_ctx::Call guard(*ctx, stream);
        ctx->relief_window(fields, ci0, cj0, seed, out, stream);
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_climate_cells(sbtg_ctx* ctx, const float* grid, int32_t gh, int32_t gw, int64_t gi0, int64_t gj0,
                           int64_t ci0, int64_t cj0, int32_t hc, int32_t wc, double grid_cells, const float mean[7],
                           const float stdv[7], float* const outs[7], void* stream) {
    if (ctx == nullptr || grid == nullptr || outs == nullptr || mean == nullptr || stdv == nullptr)
        return fail(SBTG_ERR_ARG, "null argument");
    try {
        sbtg_ctx::Call guard(*ctx, stream);
        ctx->climate_cells(grid, gh, gw, gi0, gj0, ci0, cj0, hc, wc, grid_cells, mean, stdv, outs, stream);
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_detail_configure(sbtg_ctx* ctx, const sbtg_detail_config* cfg) {
    if (ctx == nullptr || cfg == nullptr) return fail(SBTG_ERR_ARG, "null context or config");
    try {
        std::lock_guard<std::mutex> lock(ctx->call_mu);
        ctx->detail_configure(*cfg);
        ctx->configured();
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_detail_window(sbtg_ctx* ctx, const float* const fields[9], int64_t si0, int64_t sj0, int64_t seed,
                           float has_flow, float* out, int32_t* attempt, void* stream) {
    if (ctx == nullptr || fields == nullptr || out == nullptr || attempt == nullptr)
        return fail(SBTG_ERR_ARG, "null argument");
    try {
        sbtg_ctx::Call guard(*ctx, stream);
        *attempt = ctx->detail_window(fields, si0, sj0, seed, has_flow, out, stream);
        return SBTG_OK;
    } catch (const std::exception& e) {
        return translate(e);
    }
}

int32_t sbtg_stats(const sbtg_ctx* ctx, double* values, int32_t n) {
    if (ctx == nullptr || values == nullptr) return 0;
    double v[] = {static_cast<double>(ctx->n_calls.load()), static_cast<double>(ctx->n_windows.load()),
                  static_cast<double>(ctx->n_launches.load()), static_cast<double>(ctx->n_requests.load()),
                  static_cast<double>(ctx->n_relief.load()), static_cast<double>(ctx->n_detail.load())};
    int32_t k = std::min<int32_t>(n, 6);
    for (int32_t i = 0; i < k; ++i) values[i] = v[i];
    return k;
}

} // extern "C"
#if !defined(_WIN32)
#pragma GCC visibility pop
#endif
