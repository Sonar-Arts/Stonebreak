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
