// Halo-tiled direct 3x3 convolution (stride 1, "same" padding) on fp16 tensor cores, fp32 accumulation.
// Compiled by NVRTC (kernels/native.py).
//
// conv.cu's implicit GEMM streams the A operand once per TAP: every input pixel crosses L2 nine times,
// and at the UNet's top levels that traffic, not the tensor cores, sets the speed. Here a block owns a
// 2D tile of TH x TW output pixels: per 16-channel chunk it loads the (TH+2) x (TW+2) input halo ONCE
// and runs all nine taps out of shared memory -- a tap is the same halo shifted by (kh, kw), which for
// ldmatrix is just a different row address. Same arithmetic as the direct convolution (same products,
// fixed K order: channel chunk, tap, k16 -- no split-K), so a value does not depend on tiles or grid.
//
// Epilogue: as conv.cu (bias, FiLM, residual, raw / SiLU), staged per warp for 16 B coalesced stores.

#include "conv_common.cuh"

struct HConvArgs {
    const u16* x;        // (B, H, W, C) NHWC fp16
    const u16* w;        // (9 * C, NP) fp16, row = chunk-major: (c0 / 16) * 144 + tap * 16 + (c % 16)
    const float* bias;   // (NP,)
    const float* film;
    const u16* res;
    u16* out_raw;
    u16* out_silu;
    float* xs;           // F_EULER (output conv, CO = 1): fp32 state, and the stem input it refreshes
    u16* stem_in;
    int B, H, W, C, CO, NP, flags, stem_c;
    float dt;
};

// 32 B rows (two 16 B chunks): 8 consecutive rows, whatever the start, hit 8 distinct bank groups.
__device__ __forceinline__ int hswz(int row, int q) { return q ^ ((row >> 2) & 1); }

template <int TH, int TW, int BN, int WM, int WN, int ST>
__global__ void __launch_bounds__(WM * WN * 32) hconv_kernel(const HConvArgs a) {
    constexpr int NT = WM * WN * 32;
    constexpr int BM = TH * TW;
    constexpr int WTM = BM / WM, WTN = BN / WN;
    constexpr int MT = WTM / 16, NTL = WTN / 8;
    static_assert(TW % 8 == 0 && WTM % 16 == 0 && WTN % 16 == 0, "tile shape");
    constexpr int HC = TW + 2, HN = (TH + 2) * HC;
    constexpr int BSTR = ((BN / 8) % 2 == 0) ? BN + 8 : BN;
    constexpr int A_ST = HN * 16, B_ST = 144 * BSTR;

    extern __shared__ __align__(16) u16 smem[];
    u16* sA = smem;
    u16* sB = smem + ST * A_ST;

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
            const u16* p = ok ? a.x + ((size_t)((b * a.H + iy) * a.W + ix)) * a.C + kc * 16 + q * 8 : a.x;
            cp_async16(sa + (r * 16 + hswz(r, q) * 8) * 2, p, ok);
        }
        u32 sb = smem_u32(sB + stage * B_ST);
        const u16* wp = a.w + (size_t)kc * 144 * a.NP + bn;
        for (int id = tid; id < 144 * (BN / 8); id += NT) {
            int kr = id / (BN / 8), cc = id - kr * (BN / 8);
            cp_async16(sb + (kr * BSTR + cc * 8) * 2, wp + (size_t)kr * a.NP + cc * 8, true);
        }
    };

    // Halo row of this lane's A-fragment pixel for tap (0, 0); tap (kh, kw) adds kh * HC + kw.
    int hrow0[MT];
#pragma unroll
    for (int mt = 0; mt < MT; ++mt) {
        int p = wm * WTM + mt * 16 + (lane & 15);
        int py = p / TW, px = p - py * TW;
        hrow0[mt] = py * HC + px;
    }

    float acc[MT][NTL][4];
#pragma unroll
    for (int i = 0; i < MT; ++i)
#pragma unroll
        for (int j = 0; j < NTL; ++j)
#pragma unroll
            for (int k = 0; k < 4; ++k) acc[i][j][k] = 0.0f;

    const int nk = a.C / 16;
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
        const u16* As = sA + stage * A_ST;
        const u16* Bs = sB + stage * B_ST;
#pragma unroll
        for (int tap = 0; tap < 9; ++tap) {
            const int toff = (tap / 3) * HC + tap % 3;
            u32 af[MT][4], bf[NTL][2];
#pragma unroll
            for (int mt = 0; mt < MT; ++mt) {
                int row = hrow0[mt] + toff;
                ldm_x4(af[mt], smem_u32(As + row * 16 + hswz(row, lane >> 4) * 8));
            }
#pragma unroll
            for (int np = 0; np < NTL / 2; ++np) {
                u32 r[4];
                ldm_x4_t(r, smem_u32(Bs + (tap * 16 + (lane & 15)) * BSTR + wn * WTN + np * 16 + (lane >> 4) * 8));
                bf[2 * np][0] = r[0];
                bf[2 * np][1] = r[1];
                bf[2 * np + 1][0] = r[2];
                bf[2 * np + 1][1] = r[3];
            }
#pragma unroll
            for (int mt = 0; mt < MT; ++mt)
#pragma unroll
                for (int nt = 0; nt < NTL; ++nt) mma16816(acc[mt][nt], af[mt], bf[nt]);
        }
    }
    cp_wait<0>();

    // ------------------------------------------------------------------ epilogue (staged, as conv.cu)
    const int g = lane >> 2, t = lane & 3;
    if (a.flags & F_EULER) {   // output conv: CO = 1, x += dt * v in fp32, fp16(x) -> stem input channel 0
        if (bn == 0 && wn == 0 && t == 0) {
#pragma unroll
            for (int mt = 0; mt < MT; ++mt)
#pragma unroll
                for (int h = 0; h < 2; ++h) {
                    int p = wm * WTM + mt * 16 + g + h * 8;
                    int py = p / TW, px = p - py * TW, oy = oy0 + py, ox = ox0 + px;
                    if (oy >= a.H || ox >= a.W) continue;
                    long long m = (long long)(b * a.H + oy) * a.W + ox;
                    float x = a.xs[m] + a.dt * (acc[mt][0][h * 2] + a.bias[0]);
                    a.xs[m] = x;
                    a.stem_in[m * a.stem_c] = to_h(x);
                }
        }
        return;
    }
    constexpr int ESTR = WTN + 4;
    __syncthreads();
    float* stg = reinterpret_cast<float*>(smem) + warp * 16 * ESTR;
    float bv[NTL][2], fs[NTL][2], fb[NTL][2];
#pragma unroll
    for (int nt = 0; nt < NTL; ++nt) {
        int n = bn + wn * WTN + nt * 8 + 2 * t;
        bv[nt][0] = a.bias[n];
        bv[nt][1] = a.bias[n + 1];
        bool f = (a.flags & F_FILM) && n < a.CO;
        fs[nt][0] = f ? a.film[n] : 1.0f;
        fs[nt][1] = f ? a.film[n + 1] : 1.0f;
        fb[nt][0] = f ? a.film[a.CO + n] : 0.0f;
        fb[nt][1] = f ? a.film[a.CO + n + 1] : 0.0f;
    }
    constexpr int CH = WTN / 8;
    constexpr int TPL = (16 * CH + 31) / 32;   // epilogue tasks (pixel row, 8 channels) per lane per m16 tile
    // Residuals for every task of this warp, loaded up front: in place (res == out_raw) the compiler
    // cannot move a load above an earlier store, so loading inside the store loop serialised ~600-cycle
    // misses. A warp's pixels and channels are its own (no other warp stores them): loading early is safe.
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
            bool ok = task < 16 * CH && oy < a.H && ox < a.W && n < a.CO;
            offs[mt][k] = ok ? ((long long)(b * a.H + oy) * a.W + ox) * a.CO + n : -1;
            rv[mt][k] = (ok && (a.flags & F_RES)) ? *reinterpret_cast<const uint4*>(a.res + offs[mt][k])
                                                   : make_uint4(0, 0, 0, 0);
        }
#pragma unroll
    for (int mt = 0; mt < MT; ++mt) {
#pragma unroll
        for (int nt = 0; nt < NTL; ++nt)
#pragma unroll
            for (int h = 0; h < 2; ++h) {
                float2 v;
                v.x = (acc[mt][nt][h * 2] + bv[nt][0]) * fs[nt][0] + fb[nt][0];
                v.y = (acc[mt][nt][h * 2 + 1] + bv[nt][1]) * fs[nt][1] + fb[nt][1];
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
                store8r(a, offs[mt][k], v, rv[mt][k]);
            }
        }
        __syncwarp();
    }
}
