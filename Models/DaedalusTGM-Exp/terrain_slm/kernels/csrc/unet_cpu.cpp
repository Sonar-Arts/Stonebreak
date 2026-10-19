// CPU sampler for the flow-matching UNets (detail, relief): fp32 NHWC fused convolutions.
//
// Python (kernels/cpu_sampler.py) builds a static plan -- one ConvOp per convolution of an Euler
// step, weights packed into 16-wide output panels, FiLM tables precomputed per step -- over
// preallocated activation buffers, and `tgm_unet_run` executes all steps here with no Python per op.
//
// Buffers are NHWC fp32 with a one-pixel zero border plus XPAD more zero rows / columns at the bottom /
// right ((H+2+XPAD) x (W+2+XPAD) x C), so a 3x3 tap never bounds-checks and a partial Winograd tile at the
// grid's edge reads zeros: outputs are written to the interior only and the margins stay zero.
//
// Convolution = implicit GEMM: a micro-tile of MR consecutive output pixels x 16 output channels
// accumulates over K = (segment, tap, input channel) in that fixed order with one FMA chain per
// output, so every output's summation order is independent of the tiling and of the thread count:
// results are bit-identical run to run and request independent. Epilogues (bias, FiLM, residual,
// SiLU, the Euler update) are fused into the tile's store.
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#if defined(TGM_AVX2)
#include <immintrin.h>
#endif

#include "controls_cpu.h"

#define API extern "C" __attribute__((visibility("default")))

// ----------------------------------------------------------------------------------------- plan
struct ConvOp {
    // pointers first (8-byte), then ints: kernels/cpu_sampler.py mirrors this layout exactly
    const float* in0;        // segment 0 source (padded NHWC)
    const float* in1;        // segment 1 source or null
    const float* w;          // packed weights [phase][panel][K][16]
    const float* bias;       // [npanels * 16], zero padded
    const float* film;       // [steps][2 * co]: (1 + scale), shift; or null
    const float* res;        // residual (padded, co channels, same geometry as the output) or null
    float* out_raw;          // padded, co channels, or null
    float* out_silu;         // padded, co channels, or null
    float* stem;             // Euler: the stem input (padded, stem_c channels); channel 0 = the state x
    const float* dts;        // Euler: [steps] dt
    const float* wvec;       // Euler (co == 1): the weights as one K-major vector [tap][c]
    long long w_phase;       // floats between phases' weight blocks
    int kind;                // 0 conv, 1 up-phase (nearest 2x upsample + 3x3, as four 2x2 phase convs),
                             // 2 Winograd F(4x4, 3x3) (3x3 stride 1, one segment; w = [panel][36][c0][16]),
                             // 3 Winograd F(2x2, 3x3) (the same with 16 transformed matrices),
                             // 4 Winograd F(6x6, 3x3) (64)
    int ks, stride;          // conv geometry
    int co, npanels;
    int gh, gw;              // the grid the micro-tiles run over (conv: output; up-phase: low-res input)
    int nseg;
    int c0, h0, w0;          // segment 0 channels / unpadded dims
    int c1, h1, w1;          // segment 1
    int oh, ow;              // output tensor (unpadded)
    int stem_c;
    int euler;
};

constexpr int XPAD = 8;
inline int pw(int w) { return w + 2 + XPAD; }   // padded row length (and padded height) of a w-wide grid

// ----------------------------------------------------------------------------------------- pool
namespace {

class Pool {
public:
    explicit Pool(int n) : n_(n < 1 ? 1 : n) {
        for (int i = 1; i < n_; ++i) th_.emplace_back([this] { loop(0); });
    }
    ~Pool() {
        {
            std::lock_guard<std::mutex> g(m_);
            stop_ = true;
            gen_.fetch_add(1);
        }
        cv_.notify_all();
        for (auto& t : th_) t.join();
    }
    int size() const { return n_; }
    void run(int ntasks, const std::function<void(int)>& fn) {
        if (n_ == 1 || ntasks <= 1) {
            for (int i = 0; i < ntasks; ++i) fn(i);
            return;
        }
        {
            std::lock_guard<std::mutex> g(m_);
            job_ = &fn;
            ntasks_ = ntasks;
            next_.store(0);
            active_.store(n_ - 1);
            gen_.fetch_add(1);
        }
        cv_.notify_all();
        work();
        // wait for the workers to drain (spin briefly: ops are back to back)
        for (int spin = 0; active_.load(std::memory_order_acquire) != 0; ++spin) {
            if (spin > 2000) {
                std::unique_lock<std::mutex> l(m_);
                done_.wait(l, [this] { return active_.load() == 0; });
                break;
            }
#if defined(TGM_AVX2)
            _mm_pause();
#endif
        }
        job_ = nullptr;
    }

private:
    void work() {
        const std::function<void(int)>& fn = *job_;
        for (int i; (i = next_.fetch_add(1, std::memory_order_relaxed)) < ntasks_;) fn(i);
    }
    // `seen` = the generation at construction: a thread that starts late must not skip the first job.
    void loop(unsigned seen) {
        for (;;) {
            // spin a little for the next op before sleeping
            int spin = 0;
            while (gen_.load(std::memory_order_acquire) == seen && spin < 20000) {
                ++spin;
#if defined(TGM_AVX2)
                _mm_pause();
#endif
            }
            if (gen_.load(std::memory_order_acquire) == seen) {
                std::unique_lock<std::mutex> l(m_);
                cv_.wait(l, [&] { return gen_.load() != seen; });
            }
            seen = gen_.load();
            if (stop_) return;
            work();
            if (active_.fetch_sub(1, std::memory_order_acq_rel) == 1) {
                std::lock_guard<std::mutex> g(m_);
                done_.notify_one();
            }
        }
    }
    int n_;
    std::vector<std::thread> th_;
    std::mutex m_;
    std::condition_variable cv_, done_;
    std::atomic<unsigned> gen_{0};
    std::atomic<int> next_{0}, active_{0};
    int ntasks_ = 0;
    const std::function<void(int)>* job_ = nullptr;
    bool stop_ = false;
};

int default_threads() {
    if (const char* e = std::getenv("TGM_CPU_THREADS")) {
        int n = std::atoi(e);
        if (n > 0) return n;
    }
    unsigned n = std::thread::hardware_concurrency();
    return n ? int(n) : 1;
}

std::mutex g_pool_m;
Pool* g_pool = nullptr;

Pool& pool() {
    std::lock_guard<std::mutex> g(g_pool_m);
    if (!g_pool) g_pool = new Pool(default_threads());
    return *g_pool;
}

// ----------------------------------------------------------------------------------------- math
inline float silu1(float v) { return v / (1.0f + std::exp(-v)); }

#if defined(TGM_AVX2)
// exp(x) for the SiLU: Cephes-style range reduction + degree-6 polynomial, ~1 ulp.
inline __m256 exp8(__m256 x) {
    const __m256 hi = _mm256_set1_ps(88.3762626647949f), lo = _mm256_set1_ps(-87.3365478515625f);
    x = _mm256_min_ps(_mm256_max_ps(x, lo), hi);
    __m256 fx = _mm256_round_ps(_mm256_mul_ps(x, _mm256_set1_ps(1.44269504088896341f)),
                                _MM_FROUND_TO_NEAREST_INT | _MM_FROUND_NO_EXC);
    __m256 r = _mm256_fnmadd_ps(fx, _mm256_set1_ps(0.693359375f), x);
    r = _mm256_fnmadd_ps(fx, _mm256_set1_ps(-2.12194440e-4f), r);
    __m256 y = _mm256_set1_ps(1.9875691500e-4f);
    y = _mm256_fmadd_ps(y, r, _mm256_set1_ps(1.3981999507e-3f));
    y = _mm256_fmadd_ps(y, r, _mm256_set1_ps(8.3334519073e-3f));
    y = _mm256_fmadd_ps(y, r, _mm256_set1_ps(4.1665795894e-2f));
    y = _mm256_fmadd_ps(y, r, _mm256_set1_ps(1.6666665459e-1f));
    y = _mm256_fmadd_ps(y, r, _mm256_set1_ps(5.0000001201e-1f));
    y = _mm256_fmadd_ps(y, _mm256_mul_ps(r, r), _mm256_add_ps(r, _mm256_set1_ps(1.0f)));
    __m256i e = _mm256_slli_epi32(_mm256_add_epi32(_mm256_cvtps_epi32(fx), _mm256_set1_epi32(127)), 23);
    return _mm256_mul_ps(y, _mm256_castsi256_ps(e));
}
inline __m256 silu8(__m256 v) {
    __m256 e = exp8(_mm256_sub_ps(_mm256_setzero_ps(), v));
    return _mm256_div_ps(v, _mm256_add_ps(_mm256_set1_ps(1.0f), e));
}
#endif

// ----------------------------------------------------------------------------------------- tiles
struct Seg {
    const float* base;   // pixel 0 of the micro-tile at tap offset 0
    int ps;              // floats between consecutive pixels of the micro-tile
    int c;               // channels (K per tap)
    int ntaps;
    const long* tap;     // per-tap offsets (floats) from base
};

constexpr int MRMAX = 6;

#if defined(TGM_AVX2)
template <int MR>
inline void tile_mma(const Seg* segs, int nseg, const float* B, float* out /* MR x 16 */) {
    __m256 c0[MR], c1[MR];
    for (int r = 0; r < MR; ++r) c0[r] = c1[r] = _mm256_setzero_ps();
    for (int s = 0; s < nseg; ++s) {
        const Seg& sg = segs[s];
        const int C = sg.c, ps = sg.ps;
        for (int t = 0; t < sg.ntaps; ++t) {
            const float* p = sg.base + sg.tap[t];
            for (int k = 0; k < C; ++k) {
                const __m256 b0 = _mm256_load_ps(B), b1 = _mm256_load_ps(B + 8);
                B += 16;
                for (int r = 0; r < MR; ++r) {
                    const __m256 a = _mm256_broadcast_ss(p + r * ps + k);
                    c0[r] = _mm256_fmadd_ps(a, b0, c0[r]);
                    c1[r] = _mm256_fmadd_ps(a, b1, c1[r]);
                }
            }
        }
    }
    for (int r = 0; r < MR; ++r) {
        _mm256_store_ps(out + r * 16, c0[r]);
        _mm256_store_ps(out + r * 16 + 8, c1[r]);
    }
}
#else
template <int MR>
inline void tile_mma(const Seg* segs, int nseg, const float* B, float* out) {
    float acc[MR][16];
    for (int r = 0; r < MR; ++r)
        for (int j = 0; j < 16; ++j) acc[r][j] = 0.0f;
    for (int s = 0; s < nseg; ++s) {
        const Seg& sg = segs[s];
        for (int t = 0; t < sg.ntaps; ++t) {
            const float* p = sg.base + sg.tap[t];
            for (int k = 0; k < sg.c; ++k) {
                for (int r = 0; r < MR; ++r) {
                    const float a = p[r * sg.ps + k];
                    for (int j = 0; j < 16; ++j) acc[r][j] = std::fma(a, B[j], acc[r][j]);
                }
                B += 16;
            }
        }
    }
    for (int r = 0; r < MR; ++r)
        for (int j = 0; j < 16; ++j) out[r * 16 + j] = acc[r][j];
}
#endif

inline void tile_dispatch(int mr, const Seg* segs, int nseg, const float* B, float* out) {
    switch (mr) {
        case 6: tile_mma<6>(segs, nseg, B, out); break;
        case 5: tile_mma<5>(segs, nseg, B, out); break;
        case 4: tile_mma<4>(segs, nseg, B, out); break;
        case 3: tile_mma<3>(segs, nseg, B, out); break;
        case 2: tile_mma<2>(segs, nseg, B, out); break;
        default: tile_mma<1>(segs, nseg, B, out); break;
    }
}

// Epilogue for one micro-tile: acc (mr x 16, panel `p`), output pixels at `obase` + r * ostep (floats).
inline void epilogue(const ConvOp& op, int step, int p, int mr, const float* acc, long obase, long ostep) {
    const int co0 = p * 16;
    const float* bias = op.bias + co0;
    if (op.euler) {   // co == 1: x += dt * v; x lives in channel 0 of the stem input
        const float dt = op.dts[step];
        for (int r = 0; r < mr; ++r) {
            const long px = (obase + r * ostep) / op.co;   // pixel index in the padded output grid
            float* xp = op.stem + px * op.stem_c;
            const float v = acc[r * 16] + bias[0];
            xp[0] = xp[0] + dt * v;
        }
        return;
    }
    const float* f1 = op.film ? op.film + (long)step * 2 * op.co + co0 : nullptr;
    const float* f0 = op.film ? f1 + op.co : nullptr;
#if defined(TGM_AVX2)
    const __m256 b0 = _mm256_loadu_ps(bias), b1 = _mm256_loadu_ps(bias + 8);
    __m256 s0 = b0, s1 = b0, h0 = b0, h1 = b0;
    if (f1) {
        s0 = _mm256_loadu_ps(f1); s1 = _mm256_loadu_ps(f1 + 8);
        h0 = _mm256_loadu_ps(f0); h1 = _mm256_loadu_ps(f0 + 8);
    }
    for (int r = 0; r < mr; ++r) {
        const long o = obase + r * ostep + co0;
        __m256 v0 = _mm256_add_ps(_mm256_load_ps(acc + r * 16), b0);
        __m256 v1 = _mm256_add_ps(_mm256_load_ps(acc + r * 16 + 8), b1);
        if (f1) {
            v0 = _mm256_add_ps(_mm256_mul_ps(v0, s0), h0);
            v1 = _mm256_add_ps(_mm256_mul_ps(v1, s1), h1);
        }
        if (op.res) {
            v0 = _mm256_add_ps(_mm256_loadu_ps(op.res + o), v0);
            v1 = _mm256_add_ps(_mm256_loadu_ps(op.res + o + 8), v1);
        }
        if (op.out_raw) {
            _mm256_storeu_ps(op.out_raw + o, v0);
            _mm256_storeu_ps(op.out_raw + o + 8, v1);
        }
        if (op.out_silu) {
            _mm256_storeu_ps(op.out_silu + o, silu8(v0));
            _mm256_storeu_ps(op.out_silu + o + 8, silu8(v1));
        }
    }
#else
    for (int r = 0; r < mr; ++r) {
        const long o = obase + r * ostep + co0;
        for (int j = 0; j < 16; ++j) {
            float v = acc[r * 16 + j] + bias[j];
            if (f1) v = v * f1[j] + f0[j];
            if (op.res) v = op.res[o + j] + v;
            if (op.out_raw) op.out_raw[o + j] = v;
            if (op.out_silu) op.out_silu[o + j] = silu1(v);
        }
    }
#endif
}

// 2x2 phase taps of a nearest-2x upsample + 3x3: phase p reads source offset (p - 1 + t) for tap t.
inline int up_off(int phase_bit, int t) { return t - 1 + phase_bit; }


// ----------------------------------------------------------------------------------------- Winograd
// F(4x4, 3x3): each 4x4 output tile reads a 6x6 input patch; U = B^T d B, M = sum_c U V (36 GEMMs,
// V = G g G^T precomputed in float64 by cpu_sampler.py), Y = A^T M A. 36 multiplies per tile and channel
// pair instead of 144. A task = one row of tiles (a run of T of them) x a group of 16-wide output
// panels; every output still comes from one fixed-order sum, independent of T, the grouping and threads.
#if defined(TGM_AVX2)
typedef __m256 vf;
inline vf vld(const float* p) { return _mm256_loadu_ps(p); }
inline void vst(float* p, vf v) { _mm256_storeu_ps(p, v); }
inline vf vadd(vf a, vf b) { return _mm256_add_ps(a, b); }
inline vf vsub(vf a, vf b) { return _mm256_sub_ps(a, b); }
inline vf vmulk(vf a, float k) { return _mm256_mul_ps(a, _mm256_set1_ps(k)); }
constexpr int VW = 8;
#else
struct vf { float v[1]; };
inline vf vld(const float* p) { return vf{{p[0]}}; }
inline void vst(float* p, vf v) { p[0] = v.v[0]; }
inline vf vadd(vf a, vf b) { return vf{{a.v[0] + b.v[0]}}; }
inline vf vsub(vf a, vf b) { return vf{{a.v[0] - b.v[0]}}; }
inline vf vmulk(vf a, float k) { return vf{{a.v[0] * k}}; }
constexpr int VW = 1;
#endif

// B^T x (6 -> 6)
inline void bt6(const vf* x, vf* y) {
    const vf x1_4 = vmulk(x[1], 4.0f), x2_4 = vmulk(x[2], 4.0f);
    const vf x1_2 = vmulk(x[1], 2.0f), x3_2 = vmulk(x[3], 2.0f);
    y[0] = vadd(vsub(vmulk(x[0], 4.0f), vmulk(x[2], 5.0f)), x[4]);
    y[1] = vadd(vsub(vsub(x[3], x1_4), x2_4), x[4]);            // -4x1 - 4x2 + x3 + x4
    y[2] = vadd(vsub(vsub(x1_4, x2_4), x[3]), x[4]);            //  4x1 - 4x2 - x3 + x4
    y[3] = vadd(vsub(vsub(x3_2, x1_2), x[2]), x[4]);            // -2x1 - x2 + 2x3 + x4
    y[4] = vadd(vsub(vsub(x1_2, x[2]), x3_2), x[4]);            //  2x1 - x2 - 2x3 + x4
    y[5] = vadd(vsub(x1_4, vmulk(x[3], 5.0f)), x[5]);           //  4x1 - 5x3 + x5
}

// A^T m (6 -> 4)
inline void at6(const vf* m, vf* o) {
    const vf a = vadd(m[1], m[2]), b = vsub(m[1], m[2]), c = vadd(m[3], m[4]), d = vsub(m[3], m[4]);
    o[0] = vadd(vadd(m[0], a), c);
    o[1] = vadd(b, vmulk(d, 2.0f));
    o[2] = vadd(a, vmulk(c, 4.0f));
    o[3] = vadd(vadd(b, vmulk(d, 8.0f)), m[5]);
}

float* scratch(size_t n) {
    thread_local std::vector<float> buf;
    if (buf.size() < n + 16) buf.assign(n + 16, 0.0f);
    return reinterpret_cast<float*>((reinterpret_cast<uintptr_t>(buf.data()) + 63) & ~uintptr_t(63));
}

// B^T x and A^T m for F(2x2, 3x3) (4 -> 4, 4 -> 2)
inline void bt4(const vf* x, vf* y) {
    y[0] = vsub(x[0], x[2]);
    y[1] = vadd(x[1], x[2]);
    y[2] = vsub(x[2], x[1]);
    y[3] = vsub(x[1], x[3]);
}
inline void at4(const vf* m, vf* o) {
    o[0] = vadd(vadd(m[0], m[1]), m[2]);
    o[1] = vsub(vsub(m[1], m[2]), m[3]);
}

// B^T x and A^T m for F(6x6, 3x3) (8 -> 8, 8 -> 6), points 0, +-1, +-2, +-1/2 (Lavin & Gray)
inline void bt8(const vf* x, vf* y) {
    y[0] = vadd(vsub(x[0], x[6]), vmulk(vsub(x[4], x[2]), 5.25f));
    y[7] = vadd(vsub(x[7], x[1]), vmulk(vsub(x[3], x[5]), 5.25f));
    vf t1 = vsub(vadd(x[2], x[6]), vmulk(x[4], 4.25f));
    vf t2 = vsub(vadd(x[1], x[5]), vmulk(x[3], 4.25f));
    y[1] = vadd(t1, t2);
    y[2] = vsub(t1, t2);
    t1 = vadd(vsub(vmulk(x[2], 0.25f), vmulk(x[4], 1.25f)), x[6]);
    t2 = vadd(vsub(vmulk(x[1], 0.5f), vmulk(x[3], 2.5f)), vmulk(x[5], 2.0f));
    y[3] = vadd(t1, t2);
    y[4] = vsub(t1, t2);
    t1 = vadd(vsub(vmulk(x[2], 4.0f), vmulk(x[4], 5.0f)), x[6]);
    t2 = vadd(vsub(vmulk(x[1], 2.0f), vmulk(x[3], 2.5f)), vmulk(x[5], 0.5f));
    y[5] = vadd(t1, t2);
    y[6] = vsub(t1, t2);
}
inline void at8(const vf* m, vf* o) {
    const vf a = vadd(m[1], m[2]), b = vsub(m[1], m[2]), c = vadd(m[3], m[4]), d = vsub(m[3], m[4]);
    const vf e = vadd(m[5], m[6]), f = vsub(m[5], m[6]);
    o[0] = vadd(vadd(vadd(m[0], a), c), e);
    o[1] = vadd(vadd(b, vmulk(d, 2.0f)), vmulk(f, 0.5f));
    o[2] = vadd(vadd(a, vmulk(c, 4.0f)), vmulk(e, 0.25f));
    o[3] = vadd(vadd(b, vmulk(d, 8.0f)), vmulk(f, 0.125f));
    o[4] = vadd(vadd(a, vmulk(c, 16.0f)), vmulk(e, 0.0625f));
    o[5] = vadd(vadd(vadd(b, vmulk(d, 32.0f)), vmulk(f, 0.03125f)), m[7]);
}

template <int TM> struct Wino;
template <> struct Wino<6> {   // F(6x6, 3x3)
    static constexpr int TI = 8;
    static void bt(const vf* x, vf* y) { bt8(x, y); }
    static void at(const vf* m, vf* o) { at8(m, o); }
};
template <> struct Wino<4> {   // F(4x4, 3x3)
    static constexpr int TI = 6;
    static void bt(const vf* x, vf* y) { bt6(x, y); }
    static void at(const vf* m, vf* o) { at6(m, o); }
};
template <> struct Wino<2> {   // F(2x2, 3x3)
    static constexpr int TI = 4;
    static void bt(const vf* x, vf* y) { bt4(x, y); }
    static void at(const vf* m, vf* o) { at4(m, o); }
};

template <int TM>
void exec_wino(const ConvOp& op, int step) {
    constexpr int TI = Wino<TM>::TI, NX = TI * TI;
    const int C = op.c0, CO = op.co;
    const int th = (op.gh + TM - 1) / TM, tw = (op.gw + TM - 1) / TM;   // edge tiles may be partial (XPAD zeros)
    const int wp = pw(op.w0), owp = pw(op.ow);
    int T = (131072 / (NX * C)) / 6 * 6;   // tiles per task: U (NX x T x C floats) ~ <= 512 KB of L2
    if (T < 6) T = 6;
    if (T > tw) T = tw;
    const int runs = (tw + T - 1) / T;
    const int base_tasks = th * runs;
    int groups = (4 * pool().size() + base_tasks - 1) / base_tasks;
    if (groups < 1) groups = 1;
    if (groups > op.npanels) groups = op.npanels;
    const int per = (op.npanels + groups - 1) / groups;
    groups = (op.npanels + per - 1) / per;
    const long zero = 0;
    auto task = [&](int id) {
        const int g = id % groups;
        const int run = (id / groups) % runs;
        const int ty = id / groups / runs;
        const int t0 = run * T;
        const int nt = tw - t0 < T ? tw - t0 : T;
        float* U = scratch((size_t)NX * T * C + (size_t)NX * T * 16 + TM * TM * 16 + NX * VW);
        float* M = U + (size_t)NX * T * C;
        float* Y = M + (size_t)NX * T * 16;
        float* tt = Y + TM * TM * 16;
        // input transform: U[xi][t][c] = (B^T d B)[xi]
        for (int t = 0; t < nt; ++t) {
            const float* src = op.in0 + ((long)(TM * ty) * wp + (long)TM * (t0 + t)) * C;   // padded patch origin
            for (int c = 0; c < C; c += VW) {
                vf col[TI], rr[TI];
                for (int j = 0; j < TI; ++j) {         // columns: B^T over rows
                    for (int i = 0; i < TI; ++i) col[i] = vld(src + ((long)i * wp + j) * C + c);
                    Wino<TM>::bt(col, rr);
                    for (int i = 0; i < TI; ++i) vst(tt + (i * TI + j) * VW, rr[i]);
                }
                for (int i = 0; i < TI; ++i) {         // rows: (.) B
                    for (int j = 0; j < TI; ++j) col[j] = vld(tt + (i * TI + j) * VW);
                    Wino<TM>::bt(col, rr);
                    for (int j = 0; j < TI; ++j) vst(U + ((long)(i * TI + j) * T + t) * C + c, rr[j]);
                }
            }
        }
        const int p1 = (g + 1) * per < op.npanels ? (g + 1) * per : op.npanels;
        for (int p = g * per; p < p1; ++p) {
            const float* V = op.w + (long)p * NX * C * 16;
            for (int xi = 0; xi < NX; ++xi) {
                for (int t = 0; t < nt; t += MRMAX) {
                    const int mr = nt - t < MRMAX ? nt - t : MRMAX;
                    Seg sg{U + ((long)xi * T + t) * C, C, C, 1, &zero};
                    tile_dispatch(mr, &sg, 1, V + (long)xi * C * 16, M + ((long)xi * T + t) * 16);
                }
            }
            for (int t = 0; t < nt; ++t) {
                for (int h = 0; h < 16; h += VW) {
                    vf mm[TI], sr[TM][TI], o[TM];
                    for (int j = 0; j < TI; ++j) {     // A^T over rows, per column j
                        for (int i = 0; i < TI; ++i) mm[i] = vld(M + ((long)(i * TI + j) * T + t) * 16 + h);
                        Wino<TM>::at(mm, o);
                        for (int r = 0; r < TM; ++r) sr[r][j] = o[r];
                    }
                    for (int r = 0; r < TM; ++r) {
                        Wino<TM>::at(sr[r], o);
                        for (int q = 0; q < TM; ++q) vst(Y + (r * TM + q) * 16 + h, o[q]);
                    }
                }
                const int rows = op.gh - TM * ty < TM ? op.gh - TM * ty : TM;          // clip partial edge tiles
                const int cols = op.gw - TM * (t0 + t) < TM ? op.gw - TM * (t0 + t) : TM;
                for (int r = 0; r < rows; ++r)
                    epilogue(op, step, p, cols, Y + r * TM * 16,
                             ((long)(TM * ty + r + 1) * owp + (long)TM * (t0 + t) + 1) * CO, CO);
            }
        }
    };
    pool().run(th * runs * groups, task);
}

// The output convolution (co == 1, Euler): one dot product per pixel, vectorised over channels.
void exec_dot(const ConvOp& op, int step) {
    const int C = op.c0, wp = pw(op.w0), owp = pw(op.ow);
    const float dt = op.dts[step];
    const float b = op.bias[0];
    auto task = [&](int y) {
        for (int x = 0; x < op.gw; ++x) {
#if defined(TGM_AVX2)
            __m256 acc = _mm256_setzero_ps();
            for (int kh = 0; kh < 3; ++kh)
                for (int kw = 0; kw < 3; ++kw) {
                    const float* a = op.in0 + ((long)(y + kh) * wp + (x + kw)) * C;
                    const float* w = op.wvec + (kh * 3 + kw) * C;
                    for (int c = 0; c < C; c += 8) acc = _mm256_fmadd_ps(_mm256_loadu_ps(a + c), _mm256_loadu_ps(w + c), acc);
                }
            alignas(32) float l[8];
            _mm256_store_ps(l, acc);
            const float v = (((l[0] + l[1]) + (l[2] + l[3])) + ((l[4] + l[5]) + (l[6] + l[7]))) + b;
#else
            float v = 0.0f;
            for (int kh = 0; kh < 3; ++kh)
                for (int kw = 0; kw < 3; ++kw) {
                    const float* a = op.in0 + ((long)(y + kh) * wp + (x + kw)) * C;
                    const float* w = op.wvec + (kh * 3 + kw) * C;
                    for (int c = 0; c < C; ++c) v = std::fma(a[c], w[c], v);
                }
            v = v + b;
#endif
            float* xp = op.stem + ((long)(y + 1) * owp + (x + 1)) * op.stem_c;
            xp[0] = xp[0] + dt * v;
        }
    };
    pool().run(op.gh, task);
}

void exec(const ConvOp& op, int step);

void exec(const ConvOp& op, int step) {
    if (op.kind == 2) return exec_wino<4>(op, step);
    if (op.kind == 3) return exec_wino<2>(op, step);
    if (op.kind == 4) return exec_wino<6>(op, step);
    if (op.euler && op.wvec && op.c0 % 8 == 0 && op.ks == 3 && op.stride == 1) return exec_dot(op, step);
    const int nph = op.kind == 1 ? 4 : 1;
    const int rows = op.gh;
    const int ntasks = nph * rows * op.npanels;
    const int wp0 = pw(op.w0), wp1 = pw(op.w1);
    const int owp = pw(op.ow);
    const long kseg0 = (long)(op.kind == 1 ? 4 : op.ks * op.ks) * op.c0;
    (void)kseg0;
    long taps[2][4][9];
    for (int ph = 0; ph < nph; ++ph) {
        const int py = ph >> 1, px = ph & 1;
        if (op.kind == 0) {
            const int pad = op.ks / 2;
            for (int kh = 0; kh < op.ks; ++kh)
                for (int kw = 0; kw < op.ks; ++kw) {
                    // padded source coordinate = o * stride + k - pad + 1
                    taps[0][ph][kh * op.ks + kw] = ((long)(kh - pad + 1) * wp0 + (kw - pad + 1)) * op.c0;
                    taps[1][ph][kh * op.ks + kw] = ((long)(kh - pad + 1) * wp1 + (kw - pad + 1)) * op.c1;
                }
        } else {
            for (int ty = 0; ty < 2; ++ty)
                for (int tx = 0; tx < 2; ++tx)
                    taps[0][ph][ty * 2 + tx] = ((long)(up_off(py, ty) + 1) * wp0 + (up_off(px, tx) + 1)) * op.c0;
            taps[1][ph][0] = ((long)(py + 1) * wp1 + (px + 1)) * op.c1;
        }
    }
    auto task = [&](int id) {
        const int p = id % op.npanels;
        const int rest = id / op.npanels;
        const int y = rest % rows;
        const int ph = rest / rows;
        const int py = ph >> 1, px = ph & 1;
        const float* B = op.w + ph * op.w_phase + (long)p * 16 * (
            (op.kind == 1 ? 4L : (long)op.ks * op.ks) * op.c0 +
            (op.nseg > 1 ? (op.kind == 1 ? 1L : (long)op.ks * op.ks) * op.c1 : 0L));
        alignas(32) float acc[MRMAX * 16];
        Seg segs[2];
        for (int x = 0; x < op.gw; x += MRMAX) {
            const int mr = op.gw - x < MRMAX ? op.gw - x : MRMAX;
            long obase, ostep;
            if (op.kind == 0) {
                const int s = op.stride;
                // tap offsets are relative to padded source (y * s, x * s) (the "-pad + 1" is in the table)
                segs[0] = {op.in0 + ((long)(y * s) * wp0 + (long)x * s) * op.c0, s * op.c0, op.c0, op.ks * op.ks,
                           taps[0][0]};
                segs[1] = {op.in1 ? op.in1 + ((long)(y * s) * wp1 + (long)x * s) * op.c1 : nullptr, s * op.c1, op.c1,
                           op.ks * op.ks, taps[1][0]};
                obase = ((long)(y + 1) * owp + (x + 1)) * op.co;
                ostep = op.co;
            } else {
                segs[0] = {op.in0 + ((long)y * wp0 + x) * op.c0, op.c0, op.c0, 4, taps[0][ph]};
                segs[1] = {op.in1 ? op.in1 + ((long)(2 * y) * wp1 + 2L * x) * op.c1 : nullptr, 2 * op.c1, op.c1, 1,
                           taps[1][ph]};
                obase = ((long)(2 * y + py + 1) * owp + (2 * x + px + 1)) * op.co;
                ostep = 2L * op.co;
            }
            tile_dispatch(mr, segs, op.nseg, B, acc);
            epilogue(op, step, p, mr, acc, obase, ostep);
        }
    };
    pool().run(ntasks, task);
}

}  // namespace

API int tgm_cpu_threads() { return pool().size(); }

API void tgm_cpu_set_threads(int n) {
    std::lock_guard<std::mutex> g(g_pool_m);
    delete g_pool;
    g_pool = new Pool(n > 0 ? n : default_threads());
}

API int tgm_cpu_avx2() {
#if defined(TGM_AVX2)
    return 1;
#else
    return 0;
#endif
}

API int tgm_cpu_xpad() { return XPAD; }

API int tgm_convop_size() { return (int)sizeof(ConvOp); }

// Run `ops` (one Euler step's plan) for steps [s0, s1).
API void tgm_unet_run(const ConvOp* ops, int nops, int s0, int s1) {
    for (int s = s0; s < s1; ++s)
        for (int i = 0; i < nops; ++i) exec(ops[i], s);
}

// procedural controls (controls_cpu.h) for cells [ci0, ci0 + hc) x [cj0, cj0 + wc), rows in parallel
API int tgm_controls_args_size() { return (int)sizeof(ControlsArgs); }

API void tgm_controls(const ControlsArgs* a) {
    const ControlsArgs A = *a;
    pool().run(A.hc, [&](int i) {
        for (int j = 0; j < A.wc; ++j) ctl::cell(A, (long)i * A.wc + j, i, j);
    });
}
