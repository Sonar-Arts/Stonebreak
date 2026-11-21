// Fused implicit-GEMM convolution for the flow-matching UNets (detail, relief): fp16 tensor cores,
// fp32 accumulation. Compiled at run time by NVRTC (kernels/native.py), no headers: every fp16 and
// tensor-core operation is inline PTX on raw 16-bit storage.
//
//   GEMM view   M = output pixels (grid rows), N = output channels, K = taps x input channels.
//               K runs over one or two SEGMENTS: segment 0 reads x1, segment 1 (optional) reads x2.
//   MODE 0      plain convolution: KS x KS taps (KS = 1 or 3, "same" padding), stride 1 or 2;
//               segment 1 = a second input concatenated along channels (same geometry).
//   MODE 1      conv3x3(nearest_upsample_2x(x1)) as four 2x2 PHASE convolutions (blockIdx.z = phase
//               (py, px)): grid = the low-resolution pixels, 4 taps; segment 1 = one tap of the
//               full-resolution skip tensor at (2i+py, 2j+px) -- a 1x1 merge folded in.
//   epilogue    + bias, FiLM (x * film[n] + film[CO + n], the per-step table), + residual, store the
//               raw value and/or its SiLU as fp16; or (output conv, CO = 1) the Euler update
//               xs += dt * v in fp32, writing fp16(xs) into channel 0 of the stem's input.
//
// Determinism: every output is one fixed sequence of k16 MMA steps (segment, tap, channel order) with
// no split-K, so its value does not depend on the tile configuration, the grid, or the batch.
//
// Layouts: activations NHWC fp16, channels a multiple of 32 (BK); weights (K_rows, NP) fp16 row-major,
// row = segment-major (tap * C + c), NP = CO padded to a multiple of BN; bias padded to NP.

#include "conv_common.cuh"

struct ConvArgs {
    const u16* x1;
    const u16* x2;
    const u16* w;
    const float* bias;
    const float* film;
    const u16* res;
    u16* out_raw;
    u16* out_silu;
    float* xs;
    u16* stem_in;
    long long w_phase;   // MODE 1: weight elements between consecutive phases' blocks
    int M, Hg, Wg;       // grid rows and grid dims (MODE 1: the low-resolution grid)
    int H1, W1, C1;
    int H2, W2, C2;      // C2 = 0: no segment 1
    int CO, NP;
    int Ho, Wo;          // output tensor dims
    int stem_c;
    int flags;
    float dt;
    const float* gate;   // F_GATE: (B * Ho * Wo,) fp32, one weight per output pixel
    float rs;            // F_RSCALE: residual weight
    unsigned char* out_silu8;   // F_SILU8: e4m3 SiLU codes (x qs)
    float qs;
};

#define BK 32
#define ASTR 32   // A smem row stride (u16): 64 B = 4 x 16 B chunks, XOR-swizzled (see aswz) for conflict-free ldmatrix

// Physical 16 B chunk of logical chunk q in A row `row`: ldmatrix reads 8 consecutive rows at one
// logical chunk; rows 2k, 2k+1 differ in bank half, pairs differ in chunk -> all 8 land in distinct banks.
__device__ __forceinline__ int aswz(int row, int q) { return q ^ ((row >> 1) & 3); }

template <int BM, int BN, int WM, int WN, int ST, int MODE, int KS, int STRIDE>
__global__ void __launch_bounds__(WM * WN * 32) conv_kernel(const ConvArgs a) {
    constexpr int NT = WM * WN * 32;
    constexpr int WTM = BM / WM, WTN = BN / WN;
    constexpr int MT = WTM / 16, NTL = WTN / 8;
    static_assert(WTM % 16 == 0 && WTN % 16 == 0, "warp tile must be a multiple of 16 x 16");
    constexpr int BSTR = ((BN / 8) % 2 == 0) ? BN + 8 : BN;   // odd number of 16 B chunks per row
    constexpr int A_ST = BM * ASTR, B_ST = BK * BSTR;
    constexpr int APT = BM * 4 / NT;                            // A chunks (16 B) per thread per stage
    static_assert(BM * 4 % NT == 0, "BM * 4 must be a multiple of the thread count");
    constexpr int T0 = MODE == 1 ? 4 : KS * KS;
    constexpr int T1 = MODE == 1 ? 1 : KS * KS;

    extern __shared__ __align__(16) u16 smem[];
    u16* sA = smem;
    u16* sB = smem + ST * A_ST;

    const int tid = threadIdx.x, lane = tid & 31, warp = tid >> 5;
    const int wm = warp / WN, wn = warp % WN;
    const int num_n = a.NP / BN;
    const int pid_n = blockIdx.x % num_n, pid_m = blockIdx.x / num_n;
    const int bm = pid_m * BM, bn = pid_n * BN;
    const int phase = MODE == 1 ? blockIdx.z : 0;
    const int py = phase >> 1, px = phase & 1;
    const int hw = a.Hg * a.Wg;

    // This thread's A rows: fixed 16 B chunk q, rows (tid >> 2) + i * NT / 4.
    const int q = tid & 3;
    int rb[APT], ry[APT], rx[APT];
#pragma unroll
    for (int i = 0; i < APT; ++i) {
        int m = bm + (tid >> 2) + i * (NT / 4);
        if (m < a.M) {
            rb[i] = m / hw;
            int r = m - rb[i] * hw;
            ry[i] = r / a.Wg;
            rx[i] = r - ry[i] * a.Wg;
        } else {
            rb[i] = -1;
            ry[i] = rx[i] = 0;
        }
    }

    const int kc1 = a.C1 / BK, kc2 = a.C2 / BK;
    const int n0 = T0 * kc1, n_it = n0 + T1 * kc2;
    const u16* wbase = a.w + (MODE == 1 ? (long long)phase * a.w_phase : 0LL);

    auto load_stage = [&](int stage, int it) {
        int seg = it >= n0;
        int j = seg ? it - n0 : it;
        int kc = seg ? kc2 : kc1;
        int tap = j / kc;
        int c0 = (j - tap * kc) * BK;
        const u16* src = seg ? a.x2 : a.x1;
        int H = seg ? a.H2 : a.H1, W = seg ? a.W2 : a.W1, C = seg ? a.C2 : a.C1;
        int wrow = seg ? T0 * a.C1 + tap * a.C2 + c0 : tap * a.C1 + c0;
        int dy, dx, sy, sx;   // source pixel = (grid_y * sy + dy, grid_x * sx + dx)
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
            int row = (tid >> 2) + i * (NT / 4);
            int iy = ry[i] * sy + dy, ix = rx[i] * sx + dx;
            bool ok = rb[i] >= 0 && iy >= 0 && iy < H && ix >= 0 && ix < W;
            const u16* p = ok ? src + ((size_t)((rb[i] * H + iy) * W + ix)) * C + c0 + q * 8 : src;
            cp_async16(sa + (row * ASTR + aswz(row, q) * 8) * 2, p, ok);
        }
        u32 sb = smem_u32(sB + stage * B_ST);
        const u16* wp = wbase + (size_t)wrow * a.NP + bn;
#pragma unroll
        for (int id = tid; id < BK * (BN / 8); id += NT) {
            int kr = id / (BN / 8), cc = id - kr * (BN / 8);
            cp_async16(sb + (kr * BSTR + cc * 8) * 2, wp + (size_t)kr * a.NP + cc * 8, true);
        }
    };

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
        const u16* As = sA + stage * A_ST;
        const u16* Bs = sB + stage * B_ST;
#pragma unroll
        for (int kk = 0; kk < BK / 16; ++kk) {
            u32 af[MT][4], bf[NTL][2];
#pragma unroll
            for (int mt = 0; mt < MT; ++mt) {
                int row = wm * WTM + mt * 16 + (lane & 15);
                ldm_x4(af[mt], smem_u32(As + row * ASTR + aswz(row, kk * 2 + (lane >> 4)) * 8));
            }
#pragma unroll
            for (int np = 0; np < NTL / 2; ++np) {
                u32 r[4];
                ldm_x4_t(r, smem_u32(Bs + (kk * 16 + (lane & 15)) * BSTR + wn * WTN + np * 16 + (lane >> 4) * 8));
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

    // ------------------------------------------------------------------ epilogue
    const int g = lane >> 2, t = lane & 3;
    if (a.flags & F_EULER) {   // output conv: CO = 1, column 0 of the first N tile only
        if (bn == 0 && wn == 0 && t == 0) {
#pragma unroll
            for (int mt = 0; mt < MT; ++mt)
#pragma unroll
                for (int h = 0; h < 2; ++h) {
                    int m = bm + wm * WTM + mt * 16 + g + h * 8;
                    if (m >= a.M) continue;
                    float x = a.xs[m] + a.dt * (acc[mt][0][h * 2] + a.bias[0]);
                    a.xs[m] = x;
                    a.stem_in[(long long)m * a.stem_c] = to_h(x);
                }
        }
        return;
    }
    // Each warp stages its accumulators 16 rows at a time through shared memory (fp32, bias and FiLM
    // applied), then every lane finishes 8 consecutive channels of one row: one 16 B residual load and
    // 16 B raw / SiLU stores -- coalesced along the channel axis instead of 4 B scattered per fragment.
    constexpr int ESTR = WTN + 4;
    __syncthreads();   // the pipeline's shared memory is free now
    float* stg = reinterpret_cast<float*>(smem) + warp * 16 * ESTR;
    float bv[NTL][2], fs[NTL][2], fb[NTL][2];
#pragma unroll
    for (int nt = 0; nt < NTL; ++nt) {
        int n = bn + wn * WTN + nt * 8 + 2 * t;   // < NP: bias is padded; FiLM only read for n < CO
        bv[nt][0] = a.bias[n];
        bv[nt][1] = a.bias[n + 1];
        bool f = (a.flags & F_FILM) && n < a.CO;
        fs[nt][0] = f ? a.film[n] : 1.0f;
        fs[nt][1] = f ? a.film[n + 1] : 1.0f;
        fb[nt][0] = f ? a.film[a.CO + n] : 0.0f;
        fb[nt][1] = f ? a.film[a.CO + n + 1] : 0.0f;
    }
    constexpr int CH = WTN / 8;   // 16 B output chunks per staged row
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
        for (int task = lane; task < 16 * CH; task += 32) {
            int r = task / CH, c8 = task - r * CH;
            int m = bm + wm * WTM + mt * 16 + r;
            int n = bn + wn * WTN + c8 * 8;
            if (m < a.M && n < a.CO) {
                long long o;
                if (MODE == 0) {
                    o = m;
                } else {
                    int b = m / hw, rr = m - b * hw, oy = rr / a.Wg, ox = rr - oy * a.Wg;
                    o = ((long long)b * a.Ho + 2 * oy + py) * a.Wo + 2 * ox + px;
                }
                const float* sp = stg + r * ESTR + c8 * 8;
                float4 lo = *reinterpret_cast<const float4*>(sp), hi = *reinterpret_cast<const float4*>(sp + 4);
                float v[8] = {lo.x, lo.y, lo.z, lo.w, hi.x, hi.y, hi.z, hi.w};
                store8(a, o * a.CO + n, v);
            }
        }
        __syncwarp();
    }
}

// =====================================================================================================================
// Halo-tiled up-merge (K4, 2026-11-19): conv3x3(nearest_up_2x(x1)) + 1x1 merge with the skip x2 -- the MODE 1 GEMM of
// conv_kernel, but a CTA loads the (TH+1) x (TW+1) low-resolution halo of a 16-channel chunk ONCE and runs its phase's
// four taps out of shared memory (conv_kernel streamed the A operand once per tap: 4x the L2 traffic per phase).
// Kernel type "uconv": K order chunk-major (segment 0: 16-channel chunk, tap, k16; then segment 1: skip chunks), a
// fixed order per output (configs never change numbers) but NOT conv_kernel's tap-major order (different fp32
// summation: its own model_id marker). Grid: (spatial tiles x N tiles, 1, 4 phases); a spatial tile = TH x TW
// low-resolution pixels of one image (Hg % TH == 0, Wg % TW == 0, so M / (TH * TW) tiles: batch scales like M).
// Weights: the conv_kernel MODE 1 packing (per phase 4 * C1 + C2 rows, NP columns).
// 32 B smem rows (16 fp16 channels): 8 consecutive rows hit 8 distinct bank groups (hconv.cu's hswz)
__device__ __forceinline__ int uswz(int row, int q) { return q ^ ((row >> 2) & 1); }

template <int TH, int TW, int BN, int WM, int WN, int ST>
__global__ void __launch_bounds__(WM * WN * 32) uconv_kernel(const ConvArgs a) {
    constexpr int NT = WM * WN * 32;
    constexpr int BM = TH * TW;
    constexpr int WTM = BM / WM, WTN = BN / WN;
    constexpr int MT = WTM / 16, NTL = WTN / 8;
    static_assert(TW % 8 == 0 && WTM % 16 == 0 && WTN % 16 == 0, "tile shape");
    constexpr int HC = TW + 1, HN = (TH + 1) * HC;               // low-res halo of one phase
    constexpr int AR = HN > BM ? HN : BM;                         // A rows per stage (segment 0 halo / segment 1 tile)
    constexpr int BSTR = ((BN / 8) % 2 == 0) ? BN + 8 : BN;
    constexpr int A_ST = AR * 16, B_ST = 64 * BSTR;              // u16 elements per stage

    extern __shared__ __align__(16) u16 smem[];
    u16* sA = smem;
    u16* sB = smem + ST * A_ST;

    const int tid = threadIdx.x, lane = tid & 31, warp = tid >> 5;
    const int wm = warp / WN, wn = warp % WN;
    const int num_n = a.NP / BN;
    const int pid_n = blockIdx.x % num_n, sp = blockIdx.x / num_n;
    const int tiles_x = a.Wg / TW, tiles_y = a.Hg / TH;
    const int tx = sp % tiles_x, ty = (sp / tiles_x) % tiles_y, b = sp / (tiles_x * tiles_y);
    const int bn = pid_n * BN, y0 = ty * TH, x0 = tx * TW;
    const int phase = blockIdx.z, py = phase >> 1, px = phase & 1;
    const u16* wbase = a.w + (long long)phase * a.w_phase;

    const int kc1 = a.C1 / 16, kc2 = a.C2 / 16;
    const int n_it = kc1 + kc2;

    auto load_stage = [&](int stage, int it) {
        u32 sa = smem_u32(sA + stage * A_ST);
        u32 sb = smem_u32(sB + stage * B_ST);
        if (it < kc1) {   // segment 0: halo rows (y0 + py - 1 + hr, x0 + px - 1 + hc) of x1, channels it * 16..
            for (int id = tid; id < HN * 2; id += NT) {
                int r = id >> 1, q = id & 1;
                int hr = r / HC, hc = r - hr * HC;
                int iy = y0 + py - 1 + hr, ix = x0 + px - 1 + hc;
                bool ok = iy >= 0 && iy < a.H1 && ix >= 0 && ix < a.W1;
                const u16* p = ok ? a.x1 + ((size_t)((b * a.H1 + iy) * a.W1 + ix)) * a.C1 + it * 16 + q * 8 : a.x1;
                cp_async16(sa + (r * 16 + uswz(r, q) * 8) * 2, p, ok);
            }
            // B: 4 taps x 16 rows (tap * C1 + it * 16 + k), stored tap-major
            for (int id = tid; id < 64 * (BN / 8); id += NT) {
                int kr = id / (BN / 8), cc = id - kr * (BN / 8);
                int t = kr >> 4, k = kr & 15;
                const u16* wp = wbase + (size_t)(t * a.C1 + it * 16 + k) * a.NP + bn + cc * 8;
                cp_async16(sb + (kr * BSTR + cc * 8) * 2, wp, true);
            }
        } else {          // segment 1: the skip pixel (2 (y0 + i) + py, 2 (x0 + j) + px) of x2
            const int c0 = (it - kc1) * 16;
            for (int id = tid; id < BM * 2; id += NT) {
                int r = id >> 1, q = id & 1;
                int i = r / TW, j = r - i * TW;
                int iy = 2 * (y0 + i) + py, ix = 2 * (x0 + j) + px;
                bool ok = iy < a.H2 && ix < a.W2;
                const u16* p = ok ? a.x2 + ((size_t)((b * a.H2 + iy) * a.W2 + ix)) * a.C2 + c0 + q * 8 : a.x2;
                cp_async16(sa + (r * 16 + uswz(r, q) * 8) * 2, p, ok);
            }
            for (int id = tid; id < 16 * (BN / 8); id += NT) {
                int k = id / (BN / 8), cc = id - k * (BN / 8);
                const u16* wp = wbase + (size_t)(4 * a.C1 + c0 + k) * a.NP + bn + cc * 8;
                cp_async16(sb + (k * BSTR + cc * 8) * 2, wp, true);
            }
        }
    };

    // this lane's A-fragment pixel: halo row for tap (0, 0) and its row in the skip tile
    int hrow0[MT], srow[MT];
#pragma unroll
    for (int mt = 0; mt < MT; ++mt) {
        int p = wm * WTM + mt * 16 + (lane & 15);
        int i = p / TW, j = p - i * TW;
        hrow0[mt] = i * HC + j;
        srow[mt] = p;
    }

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
        const u16* As = sA + stage * A_ST;
        const u16* Bs = sB + stage * B_ST;
        auto tap = [&](int row_off, int brow0, bool seg0) {
            u32 af[MT][4], bf[NTL][2];
#pragma unroll
            for (int mt = 0; mt < MT; ++mt) {
                int row = seg0 ? hrow0[mt] + row_off : srow[mt];
                ldm_x4(af[mt], smem_u32(As + row * 16 + uswz(row, lane >> 4) * 8));
            }
#pragma unroll
            for (int np = 0; np < NTL / 2; ++np) {
                u32 r[4];
                ldm_x4_t(r, smem_u32(Bs + (brow0 + (lane & 15)) * BSTR + wn * WTN + np * 16 + (lane >> 4) * 8));
                bf[2 * np][0] = r[0];
                bf[2 * np][1] = r[1];
                bf[2 * np + 1][0] = r[2];
                bf[2 * np + 1][1] = r[3];
            }
#pragma unroll
            for (int mt = 0; mt < MT; ++mt)
#pragma unroll
                for (int nt = 0; nt < NTL; ++nt) mma16816(acc[mt][nt], af[mt], bf[nt]);
        };
        if (it < kc1) {
#pragma unroll
            for (int t = 0; t < 4; ++t) tap((t >> 1) * HC + (t & 1), t * 16, true);
        } else {
            tap(0, 0, false);
        }
    }
    cp_wait<0>();

    // ------------------------------------------------------------------ epilogue (conv_kernel MODE 1's, staged)
    const int g = lane >> 2, tq = lane & 3;
    constexpr int ESTR = WTN + 4;
    __syncthreads();
    float* stg = reinterpret_cast<float*>(smem) + warp * 16 * ESTR;
    float bv[NTL][2], fs[NTL][2], fb[NTL][2];
#pragma unroll
    for (int nt = 0; nt < NTL; ++nt) {
        int n = bn + wn * WTN + nt * 8 + 2 * tq;
        bv[nt][0] = a.bias[n];
        bv[nt][1] = a.bias[n + 1];
        bool f = (a.flags & F_FILM) && n < a.CO;
        fs[nt][0] = f ? a.film[n] : 1.0f;
        fs[nt][1] = f ? a.film[n + 1] : 1.0f;
        fb[nt][0] = f ? a.film[a.CO + n] : 0.0f;
        fb[nt][1] = f ? a.film[a.CO + n + 1] : 0.0f;
    }
    constexpr int CH = WTN / 8;
#pragma unroll
    for (int mt = 0; mt < MT; ++mt) {
#pragma unroll
        for (int nt = 0; nt < NTL; ++nt)
#pragma unroll
            for (int h = 0; h < 2; ++h) {
                float2 v;
                v.x = (acc[mt][nt][h * 2] + bv[nt][0]) * fs[nt][0] + fb[nt][0];
                v.y = (acc[mt][nt][h * 2 + 1] + bv[nt][1]) * fs[nt][1] + fb[nt][1];
                *reinterpret_cast<float2*>(stg + (g + h * 8) * ESTR + nt * 8 + 2 * tq) = v;
            }
        __syncwarp();
#pragma unroll
        for (int task = lane; task < 16 * CH; task += 32) {
            int r = task / CH, c8 = task - r * CH;
            int p = wm * WTM + mt * 16 + r;
            int i = p / TW, j = p - i * TW;
            int n = bn + wn * WTN + c8 * 8;
            if (n < a.CO) {
                long long o = ((long long)b * a.Ho + 2 * (y0 + i) + py) * a.Wo + 2 * (x0 + j) + px;
                const float* sp2 = stg + r * ESTR + c8 * 8;
                float4 lo = *reinterpret_cast<const float4*>(sp2), hi = *reinterpret_cast<const float4*>(sp2 + 4);
                float v[8] = {lo.x, lo.y, lo.z, lo.w, hi.x, hi.y, hi.z, hi.w};
                store8(a, o * a.CO + n, v);
            }
        }
        __syncwarp();
    }
}
