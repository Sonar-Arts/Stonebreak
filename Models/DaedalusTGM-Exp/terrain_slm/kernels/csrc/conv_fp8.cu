// FP8 (e4m3) convolutions for the EXP-2 detail sampler (kernels/cuda_detail2_fp8.py): W8A8 on sm_89+ tensor cores
// (mma.sync m16n8k32 e4m3 x e4m3, fp32 accumulation), the fp16 kernels' structure and epilogue (conv.cu, hconv.cu)
// with one-byte operands. Compiled at run time by NVRTC (kernels/native.py); the fp16 kernels are untouched.
//
// Numbers (quant/detail2_sim.py simulates exactly this):
//   weights      e4m3 codes, one fp32 scale per output channel (per phase and channel for the up-merge) folded with
//                the input's per-tensor activation scale into sc[n]: v = acc * sc[n]
//   activations  e4m3 codes written by the PRODUCER's epilogue: code = e4m3_satfinite(value * qs), qs = 1 / scale of
//                the consumer; raw residual stream stays fp16 (F_RAW), a second e4m3 copy of it on request (F_RAW8:
//                the up-merge skip and the adapters read raw)
//   accumulation fp32, one fixed sequence of k32 MMA steps per output (channel chunk, tap; no split-K), so a value
//                does not depend on tiles or grid
//
// Layouts: activations NHWC e4m3 (one byte per channel), channels a multiple of 32.
//   hconv8   weights (C / 32 chunks, NP, 9 taps, 32 channels) bytes: per chunk, an output channel's 288 bytes
//   conv8    weights (NP, K) bytes per phase block, row = output channel, k = segment-major (tap * C + c)
//            (the transpose of conv.cu's packing: e4m3 fragments are loaded with plain, not transposed, ldmatrix)

#include "conv_common.cuh"

typedef unsigned char u8;

#define F8_SC 1        // v = acc * sc[n]
#define F8_FILM 2
#define F8_RES 4
#define F8_RAW 8       // fp16 raw
#define F8_SILU 16     // fp16 SiLU
#define F8_EULER 32
#define F8_RSCALE 64
#define F8_GATE 128
#define F8_CLIP 256
#define F8_SILU8 512   // e4m3 SiLU (x qs)
#define F8_RAW8 1024   // e4m3 raw copy (x qs_raw)

__device__ __forceinline__ void mma_e4m3(float* d, const u32* a, const u32* b) {
    asm volatile("mma.sync.aligned.m16n8k32.row.col.f32.e4m3.e4m3.f32 {%0,%1,%2,%3}, {%4,%5,%6,%7}, {%8,%9}, {%0,%1,%2,%3};\n"
                 : "+f"(d[0]), "+f"(d[1]), "+f"(d[2]), "+f"(d[3])
                 : "r"(a[0]), "r"(a[1]), "r"(a[2]), "r"(a[3]), "r"(b[0]), "r"(b[1]));
}

// two floats -> two e4m3 bytes (lo in the low byte), round to nearest even, saturating
__device__ __forceinline__ u16 pack_e4m3x2(float lo, float hi) {
    u16 r;
    asm("cvt.rn.satfinite.e4m3x2.f32 %0, %1, %2;" : "=h"(r) : "f"(hi), "f"(lo));
    return r;
}

struct Epi8 {
    const float* sc;      // F8_SC: (NP,) [x phases for MODE 1]
    const float* film;
    const u16* res;       // fp16 residual
    u16* out_raw;         // fp16
    u16* out_silu;        // fp16
    u8* out_silu8;        // e4m3
    u8* out_raw8;         // e4m3
    float* xs;            // F8_EULER
    u16* stem_in;
    const float* gate;
    float rs, qs, qs_raw, dt;
    int CO, flags, stem_c;
};

// Finish 8 consecutive channels of one output pixel (v: after scale and FiLM); off = element offset of channel 0.
__device__ __forceinline__ void store8_fp8(const Epi8& a, long long off, float* v, uint4 rr) {
    if (a.flags & F8_GATE) {
        const float g = a.gate[off / a.CO];
#pragma unroll
        for (int e = 0; e < 8; ++e) v[e] *= g;
    }
    if (a.flags & F8_RES) {
        u32 rw[4] = {rr.x, rr.y, rr.z, rr.w};
        if (a.flags & F8_RSCALE) {
#pragma unroll
            for (int e = 0; e < 4; ++e) {
                v[2 * e] = fmaf(a.rs, from_h((u16)(rw[e] & 0xFFFF)), v[2 * e]);
                v[2 * e + 1] = fmaf(a.rs, from_h((u16)(rw[e] >> 16)), v[2 * e + 1]);
            }
        } else {
#pragma unroll
            for (int e = 0; e < 4; ++e) {
                v[2 * e] += from_h((u16)(rw[e] & 0xFFFF));
                v[2 * e + 1] += from_h((u16)(rw[e] >> 16));
            }
        }
    }
    if (a.flags & F8_CLIP) {
#pragma unroll
        for (int e = 0; e < 8; ++e) v[e] = v[e] > 256.0f ? 256.0f : (v[e] < -256.0f ? -256.0f : v[e]);
    }
    if (a.flags & F8_RAW)
        *reinterpret_cast<uint4*>(a.out_raw + off) =
            make_uint4(pack_h2(v[0], v[1]), pack_h2(v[2], v[3]), pack_h2(v[4], v[5]), pack_h2(v[6], v[7]));
    if (a.flags & F8_RAW8) {
        const float q = a.qs_raw;
        *reinterpret_cast<uint2*>(a.out_raw8 + off) =
            make_uint2((u32)pack_e4m3x2(v[0] * q, v[1] * q) | ((u32)pack_e4m3x2(v[2] * q, v[3] * q) << 16),
                       (u32)pack_e4m3x2(v[4] * q, v[5] * q) | ((u32)pack_e4m3x2(v[6] * q, v[7] * q) << 16));
    }
    if (a.flags & (F8_SILU | F8_SILU8)) {
        float s[8];
#pragma unroll
        for (int e = 0; e < 8; ++e) s[e] = silu(v[e]);
        if (a.flags & F8_SILU)
            *reinterpret_cast<uint4*>(a.out_silu + off) =
                make_uint4(pack_h2(s[0], s[1]), pack_h2(s[2], s[3]), pack_h2(s[4], s[5]), pack_h2(s[6], s[7]));
        if (a.flags & F8_SILU8) {
            const float q = a.qs;
            *reinterpret_cast<uint2*>(a.out_silu8 + off) =
                make_uint2((u32)pack_e4m3x2(s[0] * q, s[1] * q) | ((u32)pack_e4m3x2(s[2] * q, s[3] * q) << 16),
                           (u32)pack_e4m3x2(s[4] * q, s[5] * q) | ((u32)pack_e4m3x2(s[6] * q, s[7] * q) << 16));
        }
    }
}

// 32 B smem rows (two 16 B chunks): 8 consecutive rows, whatever the start, hit 8 distinct bank groups.
__device__ __forceinline__ int swz32(int row, int q) { return q ^ ((row >> 2) & 1); }

// =====================================================================================================================
// Halo-tiled 3x3 stride-1 convolution (hconv.cu with e4m3 operands): per 32-channel chunk the (TH+2) x (TW+2) halo
// is loaded once and all nine taps run out of shared memory.
struct HConv8Args {
    const u8* x;         // (B, H, W, C) e4m3
    const u8* w;         // (C / 32, NP, 288) e4m3
    Epi8 e;
    int B, H, W, C, NP;
};

#define WROW8 304   // smem bytes per output channel per chunk: 288 + 16 (odd number of 16 B chunks: conflict-free)

template <int TH, int TW, int BN, int WM, int WN, int ST>
__global__ void __launch_bounds__(WM * WN * 32) hconv8_kernel(const HConv8Args a) {
    constexpr int NT = WM * WN * 32;
    constexpr int BM = TH * TW;
    constexpr int WTM = BM / WM, WTN = BN / WN;
    constexpr int MT = WTM / 16, NTL = WTN / 8;
    static_assert(TW % 8 == 0 && WTM % 16 == 0 && WTN % 16 == 0, "tile shape");
    constexpr int HC = TW + 2, HN = (TH + 2) * HC;
    constexpr int A_ST = HN * 32, B_ST = BN * WROW8;    // bytes per stage

    extern __shared__ __align__(16) u8 smem8[];
    u8* sA = smem8;
    u8* sB = smem8 + ST * A_ST;

    const int tid = threadIdx.x, lane = tid & 31, warp = tid >> 5;
    const int wm = warp / WN, wn = warp % WN;
    const int num_n = a.NP / BN;
    const int tiles_x = (a.W + TW - 1) / TW, tiles_y = (a.H + TH - 1) / TH;
    int pid = blockIdx.x;
    const int pid_n = pid % num_n;
    pid /= num_n;
    const int bx = pid % tiles_x;
    pid /= tiles_x;
    const int by = pid % tiles_y, b = pid / tiles_y;
    const int bn = pid_n * BN, oy0 = by * TH, ox0 = bx * TW;

    auto load_stage = [&](int stage, int kc) {
        u32 sa = smem_u32(sA + stage * A_ST);
        for (int id = tid; id < HN * 2; id += NT) {
            int r = id >> 1, q = id & 1;
            int hr = r / HC, hc = r - hr * HC;
            int iy = oy0 - 1 + hr, ix = ox0 - 1 + hc;
            bool ok = iy >= 0 && iy < a.H && ix >= 0 && ix < a.W;
            const u8* p = ok ? a.x + ((size_t)((b * a.H + iy) * a.W + ix)) * a.C + kc * 32 + q * 16 : a.x;
            cp_async16(sa + r * 32 + swz32(r, q) * 16, p, ok);
        }
        u32 sb = smem_u32(sB + stage * B_ST);
        const u8* wp = a.w + ((size_t)kc * a.NP + bn) * 288;
        for (int id = tid; id < BN * 18; id += NT) {
            int n = id / 18, cc = id - n * 18;
            cp_async16(sb + n * WROW8 + cc * 16, wp + (size_t)n * 288 + cc * 16, true);
        }
    };

    int hrow0[MT];
#pragma unroll
    for (int mt = 0; mt < MT; ++mt) {
        int p = wm * WTM + mt * 16 + (lane & 15);
        int py = p / TW, px = p - py * TW;
        hrow0[mt] = py * HC + px;
    }
    // B fragment rows for ldmatrix.x4: matrices (n 0-7, k 0-15), (n 0-7, k 16-31), (n 8-15, k 0-15), (n 8-15, k 16-31)
    const int bn_l = (lane & 7) + ((lane >> 4) << 3), bk_l = ((lane >> 3) & 1) * 16;

    float acc[MT][NTL][4];
#pragma unroll
    for (int i = 0; i < MT; ++i)
#pragma unroll
        for (int j = 0; j < NTL; ++j)
#pragma unroll
            for (int k = 0; k < 4; ++k) acc[i][j][k] = 0.0f;

    const int nk = a.C / 32;
#pragma unroll
    for (int s = 0; s < ST - 1; ++s) {
        if (s < nk) load_stage(s, s);
        cp_commit();
    }
    for (int kc = 0; kc < nk; ++kc) {
        cp_wait<ST - 2>();
        __syncthreads();
        {
            int nx = kc + ST - 1;
            if (nx < nk) load_stage(nx % ST, nx);
            cp_commit();
        }
        const int stage = kc % ST;
        const u8* As = sA + stage * A_ST;
        const u8* Bs = sB + stage * B_ST;
#pragma unroll
        for (int tap = 0; tap < 9; ++tap) {
            const int toff = (tap / 3) * HC + tap % 3;
            u32 af[MT][4], bf[NTL][2];
#pragma unroll
            for (int mt = 0; mt < MT; ++mt) {
                int row = hrow0[mt] + toff;
                ldm_x4(af[mt], smem_u32(As + row * 32 + swz32(row, lane >> 4) * 16));
            }
#pragma unroll
            for (int np = 0; np < NTL / 2; ++np) {
                u32 r[4];
                ldm_x4(r, smem_u32(Bs + (wn * WTN + np * 16 + bn_l) * WROW8 + tap * 32 + bk_l));
                bf[2 * np][0] = r[0];
                bf[2 * np][1] = r[1];
                bf[2 * np + 1][0] = r[2];
                bf[2 * np + 1][1] = r[3];
            }
#pragma unroll
            for (int mt = 0; mt < MT; ++mt)
#pragma unroll
                for (int nt = 0; nt < NTL; ++nt) mma_e4m3(acc[mt][nt], af[mt], bf[nt]);
        }
    }
    cp_wait<0>();

    // ------------------------------------------------------------------ epilogue (staged, as hconv.cu)
    const Epi8& e = a.e;
    const int g = lane >> 2, t = lane & 3;
    if (e.flags & F8_EULER) {
        if (bn == 0 && wn == 0 && t == 0) {
            const float s0 = (e.flags & F8_SC) ? e.sc[0] : 1.0f;
#pragma unroll
            for (int mt = 0; mt < MT; ++mt)
#pragma unroll
                for (int h = 0; h < 2; ++h) {
                    int p = wm * WTM + mt * 16 + g + h * 8;
                    int py = p / TW, px = p - py * TW, oy = oy0 + py, ox = ox0 + px;
                    if (oy >= a.H || ox >= a.W) continue;
                    long long m = (long long)(b * a.H + oy) * a.W + ox;
                    float x = e.xs[m] + e.dt * (acc[mt][0][h * 2] * s0);
                    e.xs[m] = x;
                    e.stem_in[m * e.stem_c] = to_h(x);
                }
        }
        return;
    }
    constexpr int ESTR = WTN + 4;
    __syncthreads();
    float* stg = reinterpret_cast<float*>(smem8) + warp * 16 * ESTR;
    float fs[NTL][2], fb[NTL][2];
#pragma unroll
    for (int nt = 0; nt < NTL; ++nt) {
        int n = bn + wn * WTN + nt * 8 + 2 * t;
        float s0 = (e.flags & F8_SC) ? e.sc[n] : 1.0f, s1 = (e.flags & F8_SC) ? e.sc[n + 1] : 1.0f;
        bool f = (e.flags & F8_FILM) && n < e.CO;
        fs[nt][0] = s0 * (f ? e.film[n] : 1.0f);
        fs[nt][1] = s1 * (f ? e.film[n + 1] : 1.0f);
        fb[nt][0] = f ? e.film[e.CO + n] : 0.0f;
        fb[nt][1] = f ? e.film[e.CO + n + 1] : 0.0f;
    }
    constexpr int CH = WTN / 8;
    constexpr int TPL = (16 * CH + 31) / 32;
    uint4 rv[MT][TPL];
    long long offs[MT][TPL];
#pragma unroll
    for (int mt = 0; mt < MT; ++mt)
#pragma unroll
        for (int k = 0; k < TPL; ++k) {
            int task = lane + 32 * k;
            int r = task / CH, c8 = task - r * CH;
            int p = wm * WTM + mt * 16 + r;
            int py = p / TW, px = p - py * TW;
            int oy = oy0 + py, ox = ox0 + px;
            int n = bn + wn * WTN + c8 * 8;
            bool ok = task < 16 * CH && oy < a.H && ox < a.W && n < e.CO;
            offs[mt][k] = ok ? ((long long)(b * a.H + oy) * a.W + ox) * e.CO + n : -1;
            rv[mt][k] = (ok && (e.flags & F8_RES)) ? *reinterpret_cast<const uint4*>(e.res + offs[mt][k])
                                                    : make_uint4(0, 0, 0, 0);
        }
#pragma unroll
    for (int mt = 0; mt < MT; ++mt) {
#pragma unroll
        for (int nt = 0; nt < NTL; ++nt)
#pragma unroll
            for (int h = 0; h < 2; ++h) {
                float2 v;
                v.x = acc[mt][nt][h * 2] * fs[nt][0] + fb[nt][0];
                v.y = acc[mt][nt][h * 2 + 1] * fs[nt][1] + fb[nt][1];
                *reinterpret_cast<float2*>(stg + (g + h * 8) * ESTR + nt * 8 + 2 * t) = v;
            }
        __syncwarp();
#pragma unroll
        for (int k = 0; k < TPL; ++k) {
            int task = lane + 32 * k;
            if (offs[mt][k] >= 0) {
                int r = task / CH, c8 = task - r * CH;
                const float* sp = stg + r * ESTR + c8 * 8;
                float4 lo = *reinterpret_cast<const float4*>(sp), hi = *reinterpret_cast<const float4*>(sp + 4);
                float v[8] = {lo.x, lo.y, lo.z, lo.w, hi.x, hi.y, hi.z, hi.w};
                store8_fp8(e, offs[mt][k], v, rv[mt][k]);
            }
        }
        __syncwarp();
    }
}

// =====================================================================================================================
// Implicit-GEMM convolution (conv.cu with e4m3 operands, BK = 32 channels = one k32 MMA step per stage).
//   MODE 0  KS x KS taps (KS = 1 or 3, "same" padding), stride 1 or 2; optional segment 1 (x2, same geometry)
//   MODE 1  conv3x3(nearest_up_2x(x1)) as four 2x2 phase convolutions (blockIdx.z = phase) + one skip tap of x2 at
//           (2i+py, 2j+px); sc is per phase: sc[phase * NP + n]
struct Conv8Args {
    const u8* x1;
    const u8* x2;
    const u8* w;
    Epi8 e;
    long long w_phase;   // MODE 1: bytes between consecutive phases' weight blocks
    int M, Hg, Wg;
    int H1, W1, C1;
    int H2, W2, C2;
    int NP, K;           // K = bytes per weight row (all segments)
    int Ho, Wo;
};

template <int BM, int BN, int WM, int WN, int ST, int MODE, int KS, int STRIDE>
__global__ void __launch_bounds__(WM * WN * 32) conv8_kernel(const Conv8Args a) {
    constexpr int NT = WM * WN * 32;
    constexpr int WTM = BM / WM, WTN = BN / WN;
    constexpr int MT = WTM / 16, NTL = WTN / 8;
    static_assert(WTM % 16 == 0 && WTN % 16 == 0, "warp tile must be a multiple of 16 x 16");
    constexpr int A_ST = BM * 32, B_ST = BN * 32;
    constexpr int APT = (BM * 2 + NT - 1) / NT;
    constexpr int T0 = MODE == 1 ? 4 : KS * KS;
    constexpr int T1 = MODE == 1 ? 1 : KS * KS;

    extern __shared__ __align__(16) u8 smem8[];
    u8* sA = smem8;
    u8* sB = smem8 + ST * A_ST;

    const int tid = threadIdx.x, lane = tid & 31, warp = tid >> 5;
    const int wm = warp / WN, wn = warp % WN;
    const int num_n = a.NP / BN;
    const int pid_n = blockIdx.x % num_n, pid_m = blockIdx.x / num_n;
    const int bm = pid_m * BM, bn = pid_n * BN;
    const int phase = MODE == 1 ? blockIdx.z : 0;
    const int py = phase >> 1, px = phase & 1;
    const int hw = a.Hg * a.Wg;

    // This thread's A chunks: id = tid + i * NT -> row id >> 1, chunk id & 1.
    int rb[APT], ry[APT], rx[APT];
#pragma unroll
    for (int i = 0; i < APT; ++i) {
        int id = tid + i * NT;
        int m = bm + (id >> 1);
        if (id < BM * 2 && m < a.M) {
            rb[i] = m / hw;
            int r = m - rb[i] * hw;
            ry[i] = r / a.Wg;
            rx[i] = r - ry[i] * a.Wg;
        } else {
            rb[i] = -1;
            ry[i] = rx[i] = 0;
        }
    }

    const int kc1 = a.C1 / 32, kc2 = a.C2 / 32;
    const int n0 = T0 * kc1, n_it = n0 + T1 * kc2;
    const u8* wbase = a.w + (MODE == 1 ? (long long)phase * a.w_phase : 0LL);

    auto load_stage = [&](int stage, int it) {
        int seg = it >= n0;
        int j = seg ? it - n0 : it;
        int kc = seg ? kc2 : kc1;
        int tap = j / kc;
        int c0 = (j - tap * kc) * 32;
        const u8* src = seg ? a.x2 : a.x1;
        int H = seg ? a.H2 : a.H1, W = seg ? a.W2 : a.W1, C = seg ? a.C2 : a.C1;
        int wcol = seg ? T0 * a.C1 + tap * a.C2 + c0 : tap * a.C1 + c0;
        int dy, dx, sy, sx;
        if (MODE == 0) {
            dy = tap / KS - KS / 2;
            dx = tap % KS - KS / 2;
            sy = sx = STRIDE;
        } else if (!seg) {
            dy = (tap >> 1) - 1 + py;
            dx = (tap & 1) - 1 + px;
            sy = sx = 1;
        } else {
            dy = py;
            dx = px;
            sy = sx = 2;
        }
        u32 sa = smem_u32(sA + stage * A_ST);
#pragma unroll
        for (int i = 0; i < APT; ++i) {
            int id = tid + i * NT;
            if (id < BM * 2) {
                int row = id >> 1, q = id & 1;
                int iy = ry[i] * sy + dy, ix = rx[i] * sx + dx;
                bool ok = rb[i] >= 0 && iy >= 0 && iy < H && ix >= 0 && ix < W;
                const u8* p = ok ? src + ((size_t)((rb[i] * H + iy) * W + ix)) * C + c0 + q * 16 : src;
                cp_async16(sa + row * 32 + swz32(row, q) * 16, p, ok);
            }
        }
        u32 sb = smem_u32(sB + stage * B_ST);
        const u8* wp = wbase + (size_t)bn * a.K + wcol;
        for (int id = tid; id < BN * 2; id += NT) {
            int n = id >> 1, q = id & 1;
            cp_async16(sb + n * 32 + swz32(n, q) * 16, wp + (size_t)n * a.K + q * 16, true);
        }
    };

    const int bn_l = (lane & 7) + ((lane >> 4) << 3), bq_l = (lane >> 3) & 1;

    float acc[MT][NTL][4];
#pragma unroll
    for (int i = 0; i < MT; ++i)
#pragma unroll
        for (int j = 0; j < NTL; ++j)
#pragma unroll
            for (int k = 0; k < 4; ++k) acc[i][j][k] = 0.0f;

#pragma unroll
    for (int s = 0; s < ST - 1; ++s) {
        if (s < n_it) load_stage(s, s);
        cp_commit();
    }
    for (int it = 0; it < n_it; ++it) {
        cp_wait<ST - 2>();
        __syncthreads();
        {
            int nx = it + ST - 1;
            if (nx < n_it) load_stage(nx % ST, nx);
            cp_commit();
        }
        const int stage = it % ST;
        const u8* As = sA + stage * A_ST;
        const u8* Bs = sB + stage * B_ST;
        u32 af[MT][4], bf[NTL][2];
#pragma unroll
        for (int mt = 0; mt < MT; ++mt) {
            int row = wm * WTM + mt * 16 + (lane & 15);
            ldm_x4(af[mt], smem_u32(As + row * 32 + swz32(row, lane >> 4) * 16));
        }
#pragma unroll
        for (int np = 0; np < NTL / 2; ++np) {
            u32 r[4];
            int n = wn * WTN + np * 16 + bn_l;
            ldm_x4(r, smem_u32(Bs + n * 32 + swz32(n, bq_l) * 16));
            bf[2 * np][0] = r[0];
            bf[2 * np][1] = r[1];
            bf[2 * np + 1][0] = r[2];
            bf[2 * np + 1][1] = r[3];
        }
#pragma unroll
        for (int mt = 0; mt < MT; ++mt)
#pragma unroll
            for (int nt = 0; nt < NTL; ++nt) mma_e4m3(acc[mt][nt], af[mt], bf[nt]);
    }
    cp_wait<0>();

    // ------------------------------------------------------------------ epilogue
    const Epi8& e = a.e;
    const float* sc = e.sc + (MODE == 1 ? phase * a.NP : 0);
    const int g = lane >> 2, t = lane & 3;
    if (e.flags & F8_EULER) {
        if (bn == 0 && wn == 0 && t == 0) {
            const float s0 = (e.flags & F8_SC) ? sc[0] : 1.0f;
#pragma unroll
            for (int mt = 0; mt < MT; ++mt)
#pragma unroll
                for (int h = 0; h < 2; ++h) {
                    int m = bm + wm * WTM + mt * 16 + g + h * 8;
                    if (m >= a.M) continue;
                    float x = e.xs[m] + e.dt * (acc[mt][0][h * 2] * s0);
                    e.xs[m] = x;
                    e.stem_in[(long long)m * e.stem_c] = to_h(x);
                }
        }
        return;
    }
    constexpr int ESTR = WTN + 4;
    __syncthreads();
    float* stg = reinterpret_cast<float*>(smem8) + warp * 16 * ESTR;
    float fs[NTL][2], fb[NTL][2];
#pragma unroll
    for (int nt = 0; nt < NTL; ++nt) {
        int n = bn + wn * WTN + nt * 8 + 2 * t;
        float s0 = (e.flags & F8_SC) ? sc[n] : 1.0f, s1 = (e.flags & F8_SC) ? sc[n + 1] : 1.0f;
        bool f = (e.flags & F8_FILM) && n < e.CO;
        fs[nt][0] = s0 * (f ? e.film[n] : 1.0f);
        fs[nt][1] = s1 * (f ? e.film[n + 1] : 1.0f);
        fb[nt][0] = f ? e.film[e.CO + n] : 0.0f;
        fb[nt][1] = f ? e.film[e.CO + n + 1] : 0.0f;
    }
    constexpr int CH = WTN / 8;
#pragma unroll
    for (int mt = 0; mt < MT; ++mt) {
#pragma unroll
        for (int nt = 0; nt < NTL; ++nt)
#pragma unroll
            for (int h = 0; h < 2; ++h) {
                float2 v;
                v.x = acc[mt][nt][h * 2] * fs[nt][0] + fb[nt][0];
                v.y = acc[mt][nt][h * 2 + 1] * fs[nt][1] + fb[nt][1];
                *reinterpret_cast<float2*>(stg + (g + h * 8) * ESTR + nt * 8 + 2 * t) = v;
            }
        __syncwarp();
#pragma unroll
        for (int task = lane; task < 16 * CH; task += 32) {
            int r = task / CH, c8 = task - r * CH;
            int m = bm + wm * WTM + mt * 16 + r;
            int n = bn + wn * WTN + c8 * 8;
            if (m < a.M && n < e.CO) {
                long long o;
                if (MODE == 0) {
                    o = m;
                } else {
                    int bb = m / hw, rr = m - bb * hw, oy = rr / a.Wg, ox = rr - oy * a.Wg;
                    o = ((long long)bb * a.Ho + 2 * oy + py) * a.Wo + 2 * ox + px;
                }
                const float* sp = stg + r * ESTR + c8 * 8;
                float4 lo = *reinterpret_cast<const float4*>(sp), hi = *reinterpret_cast<const float4*>(sp + 4);
                float v[8] = {lo.x, lo.y, lo.z, lo.w, hi.x, hi.y, hi.z, hi.w};
                uint4 rr = make_uint4(0, 0, 0, 0);
                if (e.flags & F8_RES) rr = *reinterpret_cast<const uint4*>(e.res + o * e.CO + n);
                store8_fp8(e, o * e.CO + n, v, rr);
            }
        }
        __syncwarp();
    }
}

// =====================================================================================================================
// pixel norm (pixnorm.cu) writing its SiLU as e4m3 (x qs) instead of fp16; the raw tensor is normalised in place, fp16.
struct PixNorm8Args {
    u16* raw;
    u8* out_silu8;
    int P, C;
    float inv_sqrt_c, qs;
};

template <int CPL>
__global__ void __launch_bounds__(256) pixnorm8_kernel(const PixNorm8Args a) {
    const int lane = threadIdx.x & 31;
    const long long pix = (long long)blockIdx.x * (blockDim.x >> 5) + (threadIdx.x >> 5);
    if (pix >= a.P) return;
    const int nch = a.C >> 3;
    u16* base = a.raw + pix * a.C;
    uint4 q[CPL];
    float ss = 0.0f;
#pragma unroll
    for (int k = 0; k < CPL; ++k) {
        int c = lane + 32 * k;
        q[k] = c < nch ? *reinterpret_cast<const uint4*>(base + c * 8) : make_uint4(0, 0, 0, 0);
        u32 w[4] = {q[k].x, q[k].y, q[k].z, q[k].w};
#pragma unroll
        for (int e = 0; e < 4; ++e) {
            float lo = from_h((u16)(w[e] & 0xFFFF)), hi = from_h((u16)(w[e] >> 16));
            ss = fmaf(lo, lo, ss);
            ss = fmaf(hi, hi, ss);
        }
    }
#pragma unroll
    for (int o = 16; o > 0; o >>= 1) ss += __shfl_xor_sync(0xffffffffu, ss, o);
    const float norm = 1e-4f + sqrtf(ss) * a.inv_sqrt_c;
#pragma unroll
    for (int k = 0; k < CPL; ++k) {
        int c = lane + 32 * k;
        if (c >= nch) continue;
        u32 w[4] = {q[k].x, q[k].y, q[k].z, q[k].w};
        float v[8];
#pragma unroll
        for (int e = 0; e < 4; ++e) {
            v[2 * e] = from_h((u16)(w[e] & 0xFFFF)) / norm;
            v[2 * e + 1] = from_h((u16)(w[e] >> 16)) / norm;
        }
        *reinterpret_cast<uint4*>(base + c * 8) =
            make_uint4(pack_h2(v[0], v[1]), pack_h2(v[2], v[3]), pack_h2(v[4], v[5]), pack_h2(v[6], v[7]));
        const float s = a.qs;
        *reinterpret_cast<uint2*>(a.out_silu8 + pix * a.C + c * 8) =
            make_uint2((u32)pack_e4m3x2(silu(v[0]) * s, silu(v[1]) * s) | ((u32)pack_e4m3x2(silu(v[2]) * s, silu(v[3]) * s) << 16),
                       (u32)pack_e4m3x2(silu(v[4]) * s, silu(v[5]) * s) | ((u32)pack_e4m3x2(silu(v[6]) * s, silu(v[7]) * s) << 16));
    }
}

// fp16 -> e4m3 (x qs), 8 elements per thread: a kept-fp16 producer feeding an FP8 consumer.
struct Quant8Args {
    const u16* x;
    u8* y;
    long long n8;        // elements / 8
    float qs;
};

__global__ void __launch_bounds__(256) quant8_kernel(const Quant8Args a) {
    long long i = (long long)blockIdx.x * 256 + threadIdx.x;
    if (i >= a.n8) return;
    uint4 v = reinterpret_cast<const uint4*>(a.x)[i];
    u32 w[4] = {v.x, v.y, v.z, v.w};
    float f[8];
#pragma unroll
    for (int e = 0; e < 4; ++e) {
        f[2 * e] = from_h((u16)(w[e] & 0xFFFF)) * a.qs;
        f[2 * e + 1] = from_h((u16)(w[e] >> 16)) * a.qs;
    }
    reinterpret_cast<uint2*>(a.y)[i] = make_uint2((u32)pack_e4m3x2(f[0], f[1]) | ((u32)pack_e4m3x2(f[2], f[3]) << 16),
                                                 (u32)pack_e4m3x2(f[4], f[5]) | ((u32)pack_e4m3x2(f[6], f[7]) << 16));
}
