// Stonebreak terrain pipeline (libstonebreak_terrain, phase 2 of the DaedalusTGM native engine): cell-grid kernels
// of the planner stage. Compiled at run time by NVRTC through Open Mason's engine runtime (ci_kernel_get).
//
// Bit-parity contract with the Python generator (world/generator.py, generator2.py): every elementwise op is one IEEE
// fp32 op in torch's order, written with __f*_rn intrinsics so NVRTC cannot contract a*b+c into an FMA; torch's
// "tensor / python scalar" is a multiply by the fp32 reciprocal (passed in precomputed); the Gaussian blur weights
// come from the host (data/descriptors.gaussian_kernel1d, computed by torch); the blur's accumulation order follows
// torch's depthwise convolution (see sbtg_blur).

typedef unsigned int u32;

#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn

__device__ __forceinline__ float clampf(float x, float lo, float hi) { return fminf(fmaxf(x, lo), hi); }
// torch.clamp / clamp_min / clamp_max keep NaN (fminf / fmaxf would drop it)
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) { return x != x ? x : (x < lo ? lo : (x > hi ? hi : x)); }
__device__ __forceinline__ float t_clamp_min(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_clamp_max(float x, float hi) { return x != x ? x : (x > hi ? hi : x); }
__device__ __forceinline__ float t_minimum(float a, float b) { return (a != a || b != b) ? __int_as_float(0x7fffffff) : (a < b ? a : b); }

// ------------------------------------------------------------------------------------------------ gather
// windows[b, c, y, x] = src[c, oy_b + y, ox_b + x] for an (C, H, W) source and per-window offsets (pairs, device).
#define SBTG_MAXW 32   // windows per call (the planner batch)
struct GatherArgs {
    const float* src;
    float* dst;
    int C, H, W, win, n;
    int offs[2 * SBTG_MAXW];   // per window: row, col (by value: no host -> device copy)
};
extern "C" __global__ void sbtg_gather(const GatherArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long per = (long long)a.C * a.win * a.win;
    if (i >= per * a.n) return;
    int b = (int)(i / per);
    long long r = i - b * per;
    int c = (int)(r / ((long long)a.win * a.win));
    int yx = (int)(r - (long long)c * a.win * a.win);
    int y = yx / a.win, x = yx - y * a.win;
    a.dst[i] = a.src[((long long)c * a.H + a.offs[2 * b] + y) * a.W + a.offs[2 * b + 1] + x];
}

// ------------------------------------------------------------------------------------------------ blur
// One pass of data/descriptors.blur (reflect padding, 1-D kernel of 2r+1 taps) over P independent (H, W) planes:
// axis 0 = along W (rows), 1 = along H. out = sum_t k[t] * x[reflect(i - r + t)], accumulated in tap order.
// FMA selects fused multiply-add accumulation (what torch's depthwise conv kernel compiles to) or separately
// rounded products; the parity test picks the one that matches.
struct BlurArgs {
    const float* src;
    float* dst;
    const float* k;       // 2r + 1 taps
    int P, H, W, r, axis, fma;
};
__device__ __forceinline__ int reflect(int i, int n) {
    if (i < 0) return -i;
    if (i >= n) return 2 * n - 2 - i;
    return i;
}
extern "C" __global__ void sbtg_blur(const BlurArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long hw = (long long)a.H * a.W;
    if (i >= hw * a.P) return;
    long long p = i / hw;
    int yx = (int)(i - p * hw);
    int y = yx / a.W, x = yx - y * a.W;
    const float* s = a.src + p * hw;
    float acc = 0.0f;
    const int K = 2 * a.r + 1;
    for (int t = 0; t < K; ++t) {
        float v = a.axis == 0 ? s[(long long)y * a.W + reflect(x - a.r + t, a.W)] : s[(long long)reflect(y - a.r + t, a.H) * a.W + x];
        acc = a.fma ? fmaf(a.k[t], v, acc) : ADD(acc, MUL(a.k[t], v));
    }
    a.dst[i] = acc;
}

// ------------------------------------------------------------------------------------------------ planner post
// Planner output (n, 11, win, win), already blurred -> the cached window value:
//   ch 0  height = o0 * HEIGHT_SCALE  (then the sea merge below)
//   1..8  desc = o * desc_std + desc_mean
//   9     river = sigmoid(o9)
//   10    logacc = o10 * LOGACC_SCALE
// Writes channels 1..10 of each window's own output tensor and the raw height into `h0` (n, win, win).
struct PostArgs {
    const float* o;
    float* outs[SBTG_MAXW];  // n pointers, each (11, win, win)
    float* h0;
    const float* desc_mean;
    const float* desc_std;
    int n, win;
    float height_scale, logacc_scale;
};
extern "C" __global__ void sbtg_planner_post(const PostArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long ww = (long long)a.win * a.win;
    if (i >= ww * a.n * 11) return;
    int b = (int)(i / (ww * 11));
    long long r = i - (long long)b * ww * 11;
    int c = (int)(r / ww);
    long long yx = r - (long long)c * ww;
    float o = a.o[i];
    if (c == 0) {
        a.h0[(long long)b * ww + yx] = MUL(o, a.height_scale);
        return;
    }
    float v;
    if (c <= 8) v = ADD(MUL(o, a.desc_std[c - 1]), a.desc_mean[c - 1]);
    else if (c == 9) v = 1.0f / (1.0f + expf(-o));
    else v = MUL(o, a.logacc_scale);
    a.outs[b][c * ww + yx] = v;
}

// ------------------------------------------------------------------------------------------------ sea merge
// generator2._sea_merge (+ generator2._shelf_remap) per cell, given the blurs it needs:
//   hill transfer:  h1 = h0 + (ht * land) * ((T - bT) - (h0 - bh0)),  land = smoothstep(T / 60)
//   sea detail:     sea = clamp(-T / 150, 0, 1); local = clamp(h1 - bh1, -MAX, MAX)
//                   m = (1 - sea) * h1 + sea * min(T + local, -5)
//   shelf remap:    (see _shelf_remap)
// Stage 1 (hill) writes h1; stage 2 (sea + shelf) writes channel 0 of each window's output.
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
extern "C" __global__ void sbtg_hill(const HillArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= a.count) return;
    float h = a.h0[i];
    if (!a.on) {
        a.h1[i] = h;
        return;
    }
    float T = a.T[i];
    float x = t_clamp(MUL(T, a.inv60), 0.0f, 1.0f);
    float land = MUL(MUL(x, x), SUB(3.0f, MUL(2.0f, x)));
    float hpT = SUB(T, a.bT[i]);
    float hpH = SUB(h, a.bh0[i]);
    float w = MUL(a.ht, land);
    a.h1[i] = ADD(h, MUL(w, SUB(hpT, hpH)));
}

struct SeaArgs {
    const float* h1;
    const float* T;
    const float* bh1;
    float* outs[SBTG_MAXW];  // channel 0 of each window
    int n, win;
    int sea_detail;       // 0: v4 rule (1 - sea) h + sea min(h, T)
    float inv150, max_m, floor_m;
    // shelf remap (k >= 1: off)
    float k, dm, inv_dm, one_minus_k, k0_minus_k, d0, inv_d0;
    int keep_on;
};
__device__ __forceinline__ float S5(float t) {   // t ** 4 * (t * t - 3.0 * t + 2.5)
    return MUL(powf(t, 4.0f), ADD(SUB(MUL(t, t), MUL(3.0f, t)), 2.5f));
}
extern "C" __global__ void sbtg_sea(const SeaArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long ww = (long long)a.win * a.win;
    if (i >= ww * a.n) return;
    int b = (int)(i / ww);
    long long yx = i - (long long)b * ww;
    float h = a.h1[i], T = a.T[i];
    float sea = t_clamp(MUL(-T, a.inv150), 0.0f, 1.0f);
    float m;
    if (a.sea_detail) {
        float local = t_clamp(SUB(h, a.bh1[i]), -a.max_m, a.max_m);
        m = ADD(MUL(SUB(1.0f, sea), h), MUL(sea, t_minimum(ADD(T, local), a.floor_m)));
    } else {
        m = ADD(MUL(SUB(1.0f, sea), h), MUL(sea, t_minimum(h, T)));
    }
    if (a.k < 1.0f) {
        float d = t_clamp_min(-m, 0.0f);
        float t = t_clamp(MUL(d, a.inv_dm), 0.0f, 1.0f);
        float f = ADD(MUL(a.k, d), MUL(a.one_minus_k, ADD(MUL(a.dm, S5(t)), t_clamp_min(SUB(d, a.dm), 0.0f))));
        if (a.keep_on) {
            float u = t_clamp(MUL(d, a.inv_d0), 0.0f, 1.0f);
            f = ADD(f, MUL(a.k0_minus_k, SUB(t_clamp_max(d, a.d0), MUL(a.d0, S5(u)))));
        }
        m = m < 0.0f ? -f : m;
    }
    a.outs[b][yx] = m;
}

// ------------------------------------------------------------------------------------------------ white noise
// synth/noise.white_noise: lowbias32 hashes of (row, col, seed, stream), 4 uniform draws summed (exact integer
// hashes, fp32 sum in order), (acc - 2) * sqrt(3).
__device__ __forceinline__ u32 lowbias32(u32 x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
__device__ __forceinline__ float white_noise(long long i, long long j, u32 base, float sqrt3) {
    u32 key = lowbias32(lowbias32((u32)i ^ base) ^ (u32)j);
    float acc = 0.0f;
#pragma unroll
    for (int k = 0; k < 4; ++k) {
        u32 h = lowbias32(key ^ (0x27D4EB2Fu * (u32)(k + 1)));
        acc = ADD(acc, MUL(__uint2float_rn(h), 2.3283064365386963e-10f));   // * 2^-32 (exact)
    }
    return MUL(SUB(acc, 2.0f), sqrt3);
}

// ------------------------------------------------------------------------------------------------ relief window
// generator._relief_window's conditioning (models/relief.build_cond, planner.climate_input, relief.cell_slope) and
// noise, from the layout + climate fields over the window plus a margin m (N = n + 2m), the trend already blurred.
// cond (10, n, n): smooth / H, slope x, slope y (central differences, replicate edges, / SLOPE), wild normalised,
// t0, tseason, precip, pcv inputs, has_wild, has_climate.
struct ReliefCondArgs {
    const float* smooth_full;   // (N, N) blurred trend
    const float* wild;          // (N, N)
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    float* cond;                // (10, n, n)
    float* noise;               // (n, n)
    int N, n, m;
    long long i0, j0;           // the window's first cell (noise coordinates)
    u32 base;
    float sqrt3;
    float inv_height, inv_slope, wild_off, inv_wild, inv8, inv300, log800, inv09, inv30;
};
extern "C" __global__ void sbtg_relief_cond(const ReliefCondArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long nn = (long long)a.n * a.n;
    if (i >= nn) return;
    int y = (int)(i / a.n), x = (int)(i - (long long)y * a.n);
    const int Y = y + a.m, X = x + a.m;
    const float* s = a.smooth_full;
    long long at = (long long)Y * a.N + X;
    float sm = s[at];
    // cell_slope on the CROPPED smooth: replicate padding at the crop's own edges
    int xl = x > 0 ? X - 1 : X, xr = x < a.n - 1 ? X + 1 : X;
    int yu = y > 0 ? Y - 1 : Y, yd = y < a.n - 1 ? Y + 1 : Y;
    float gx = MUL(SUB(s[(long long)Y * a.N + xr], s[(long long)Y * a.N + xl]), 0.5f);
    float gy = MUL(SUB(s[(long long)yd * a.N + X], s[(long long)yu * a.N + X]), 0.5f);
    float* c = a.cond;
    c[0 * nn + i] = MUL(sm, a.inv_height);
    c[1 * nn + i] = MUL(gx, a.inv_slope);
    c[2 * nn + i] = MUL(gy, a.inv_slope);
    c[3 * nn + i] = MUL(MUL(SUB(a.wild[at], a.wild_off), a.inv_wild), 1.0f);
    c[4 * nn + i] = MUL(SUB(a.t0[at], 15.0f), a.inv8);
    c[5 * nn + i] = MUL(SUB(a.tseason[at], 600.0f), a.inv300);
    float p = a.precip[at];
    p = p != p ? p : (p < 10.0f ? 10.0f : p);
    c[6 * nn + i] = MUL(SUB(logf(p), a.log800), a.inv09);
    c[7 * nn + i] = MUL(SUB(a.pcv[at], 40.0f), a.inv30);
    c[8 * nn + i] = 1.0f;
    c[9 * nn + i] = 1.0f;
    a.noise[i] = white_noise(a.i0 + y, a.j0 + x, a.base, a.sqrt3);
}

// out = smooth + ((smoothstep((smooth + 100) / 300) * SCALE) * gain) * x, floored at min(smooth, FLOOR) on land.
struct ReliefPostArgs {
    const float* smooth_full;
    const float* x;             // (n, n) sampled relief
    float* out;                 // (n, n)
    int N, n, m;
    float inv300, scale, gain, floor_m;
};
extern "C" __global__ void sbtg_relief_post(const ReliefPostArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long nn = (long long)a.n * a.n;
    if (i >= nn) return;
    int y = (int)(i / a.n), x = (int)(i - (long long)y * a.n);
    float sm = a.smooth_full[(long long)(y + a.m) * a.N + x + a.m];
    float t = t_clamp(MUL(ADD(sm, 100.0f), a.inv300), 0.0f, 1.0f);
    float gate = MUL(MUL(t, t), SUB(3.0f, MUL(2.0f, t)));
    float o = ADD(sm, MUL(MUL(MUL(gate, a.scale), a.gain), a.x[i]));
    if (sm > 0.0f) {
        float fl = sm < a.floor_m ? sm : a.floor_m;           // torch.minimum(smooth, full(FLOOR)) (finite)
        o = o > fl ? o : fl;                                  // torch.maximum(out, floor)
    }
    a.out[i] = o;
}

// ------------------------------------------------------------------------------------------------ climate cells
// generator2.climate_cells (CANONICAL_CLIMATE): each cell's absolute position on the climate grid in fp64
// (p = (c + 0.5) / g - 0.5, torch's "/ scalar" = * (1 / g) in fp64), bilinear in fp32 from the cross-faded grid
// y (7, gh, gw), then climatenet.denorm. Seven separate outputs (hc, wc).
struct ClimateArgs {
    const float* y;
    float* outs[7];
    long long ci0, cj0, gi0, gj0;
    int hc, wc, gh, gw;
    double inv_g;
    float mean[7], std_[7];
};
extern "C" __global__ void sbtg_climate_cells(const ClimateArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long n = (long long)a.hc * a.wc;
    if (i >= n) return;
    int r = (int)(i / a.wc), q = (int)(i - (long long)r * a.wc);
    double pi = __dsub_rn(__dmul_rn(__dadd_rn((double)(a.ci0 + r), 0.5), a.inv_g), 0.5);
    double pj = __dsub_rn(__dmul_rn(__dadd_rn((double)(a.cj0 + q), 0.5), a.inv_g), 0.5);
    double ki = floor(pi), kj = floor(pj);
    float fy = __double2float_rn(__dsub_rn(pi, ki)), fx = __double2float_rn(__dsub_rn(pj, kj));
    long long yi = (long long)ki - a.gi0, xj = (long long)kj - a.gj0;
    float w00 = MUL(SUB(1.0f, fx), SUB(1.0f, fy)), w01 = MUL(fx, SUB(1.0f, fy));
    float w10 = MUL(SUB(1.0f, fx), fy), w11 = MUL(fx, fy);
    const long long plane = (long long)a.gh * a.gw;
    for (int c = 0; c < 7; ++c) {
        const float* y = a.y + c * plane;
        float v00 = y[yi * a.gw + xj], v01 = y[yi * a.gw + xj + 1];
        float v10 = y[(yi + 1) * a.gw + xj], v11 = y[(yi + 1) * a.gw + xj + 1];
        float v = ADD(ADD(MUL(v00, w00), MUL(v01, w01)), ADD(MUL(v10, w10), MUL(v11, w11)));
        v = ADD(MUL(v, a.std_[c]), a.mean[c]);
        float o;
        switch (c) {
        case 2: case 4: o = expf(v); break;                             // precip, aridity
        case 3: o = t_clamp(v, 0.0f, 250.0f); break;                    // pcv
        case 5: o = t_clamp(v, 0.0f, 0.8f); break;                      // soil
        case 6: o = MUL(t_clamp_min(SUB(expf(v), 1.0f), 0.0f), 0.01f); break;   // snow: (exp - 1).clamp_min(0) / 100
        default: o = v;                                                 // t0, tseason
        }
        a.outs[c][i] = o;
    }
}

// ------------------------------------------------------------------------------------------------ detail window
// torch's upsample_bilinear2d (align_corners False) of a (C, h, w) field by an integer factor f, evaluated at sample
// (Y, X) of the upsampled grid: src = scale * (dst + 0.5) - 0.5 (clamped at 0), lambdas, then
// h0 * (w0 a + w1 b) + h1 * (w0 c + w1 d). `fm` selects how the products are fused (calibrated against torch).
__device__ __forceinline__ float up_bilinear(const float* p, int h, int w, int f, int Y, int X, int fm) {
    const float scale = 1.0f / (float)f;
    float sy = fmaf(scale, (float)Y + 0.5f, -0.5f), sx = fmaf(scale, (float)X + 0.5f, -0.5f);
    sy = sy < 0.0f ? 0.0f : sy;
    sx = sx < 0.0f ? 0.0f : sx;
    int y1 = (int)sy, x1 = (int)sx;
    int yp = y1 < h - 1 ? 1 : 0, xp = x1 < w - 1 ? 1 : 0;
    float l1 = SUB(sy, (float)y1), l0 = SUB(1.0f, l1);
    float k1 = SUB(sx, (float)x1), k0 = SUB(1.0f, k1);
    float a = p[(long long)y1 * w + x1], b = p[(long long)y1 * w + x1 + xp];
    float c = p[(long long)(y1 + yp) * w + x1], d = p[(long long)(y1 + yp) * w + x1 + xp];
    if (fm == 0) return ADD(MUL(l0, ADD(MUL(k0, a), MUL(k1, b))), MUL(l1, ADD(MUL(k0, c), MUL(k1, d))));
    if (fm == 1) {
        float A = fmaf(k0, a, MUL(k1, b)), B = fmaf(k0, c, MUL(k1, d));
        return fmaf(l0, A, MUL(l1, B));
    }
    if (fm == 2) {
        float A = fmaf(k1, b, MUL(k0, a)), B = fmaf(k1, d, MUL(k0, c));
        return fmaf(l1, B, MUL(l0, A));
    }
    float A = fmaf(k0, a, MUL(k1, b)), B = fmaf(k0, c, MUL(k1, d));   // 3: inner fused, outer plain
    return ADD(MUL(l0, A), MUL(l1, B));
}

// Whole-field upsample (C planes of (h, w) -> (h f, w f)): the detail base is blurred AFTER upsampling.
struct UpArgs {
    const float* src;
    float* dst;
    int C, h, w, f, fm;
};
extern "C" __global__ void sbtg_up(const UpArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const int H = a.h * a.f, W = a.w * a.f;
    const long long plane = (long long)H * W;
    if (i >= plane * a.C) return;
    int c = (int)(i / plane);
    long long r = i - c * plane;
    int Y = (int)(r / W), X = (int)(r - (long long)Y * W);
    a.dst[i] = up_bilinear(a.src + (long long)c * a.h * a.w, a.h, a.w, a.f, Y, X, a.fm);
}

// Detail cells -> conditioning (22, E, E) + gates (S, E, E) + noise (E, E), E = DETAIL_EXT samples, crop offset ms in
// the upsampled grid. Cell fields (nc, nc): wild, t0, tseason, precip, pcv, logacc, river (blurred), gates (S planes).
// base_full: the blurred upsampled base (4 nc, 4 nc). models/detail.build_cond's channel order.
struct DetailCondArgs {
    const float* base_full;
    const float* wild;
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    const float* logacc;
    const float* river;
    const float* gates;      // (S, nc, nc) or null
    float* cond;             // (22, E, E)
    float* gates_up;         // (S, E, E)
    float* noise;            // (E, E)
    float* base;             // (E, E) cropped base (the window's smooth surface)
    int nc, f, E, ms, S, fm;
    long long si0, sj0;
    u32 base_hash;
    float sqrt3;
    float has_climate, has_flow;
    float inv_height, inv_slope, wild_off, inv_wild, inv8, inv300, log800, inv09, inv30, inv_logacc;
};
// planner.climate_input / wild normalisation on a cell value (same ops as the relief conditioning)
__device__ __forceinline__ float cell_in(const DetailCondArgs& a, int which, float v) {
    switch (which) {
    case 0: return MUL(SUB(v, a.wild_off), a.inv_wild);
    case 1: return MUL(SUB(v, 15.0f), a.inv8);
    case 2: return MUL(SUB(v, 600.0f), a.inv300);
    case 3: { float p = v != v ? v : (v < 10.0f ? 10.0f : v); return MUL(SUB(logf(p), a.log800), a.inv09); }
    case 4: return MUL(SUB(v, 40.0f), a.inv30);
    default: return MUL(v, a.inv_logacc);
    }
}
// bilinear of a TRANSFORMED cell field: transform the 4 corners first (torch transforms the cells, then upsamples)
__device__ __forceinline__ float up_t(const DetailCondArgs& a, const float* p, int which, int Y, int X) {
    const int h = a.nc, w = a.nc;
    const float scale = 1.0f / (float)a.f;
    float sy = fmaf(scale, (float)Y + 0.5f, -0.5f), sx = fmaf(scale, (float)X + 0.5f, -0.5f);
    sy = sy < 0.0f ? 0.0f : sy;
    sx = sx < 0.0f ? 0.0f : sx;
    int y1 = (int)sy, x1 = (int)sx;
    int yp = y1 < h - 1 ? 1 : 0, xp = x1 < w - 1 ? 1 : 0;
    float q[4] = {p[(long long)y1 * w + x1], p[(long long)y1 * w + x1 + xp], p[(long long)(y1 + yp) * w + x1],
                  p[(long long)(y1 + yp) * w + x1 + xp]};
    if (which >= 0)
        for (int k = 0; k < 4; ++k) q[k] = cell_in(a, which, q[k]);
    float tmp[4] = {q[0], q[1], q[2], q[3]};
    // evaluate with up_bilinear's arithmetic on the 2x2 neighbourhood (h = w = 2 view)
    float l1 = SUB(sy, (float)y1), l0 = SUB(1.0f, l1);
    float k1 = SUB(sx, (float)x1), k0 = SUB(1.0f, k1);
    float A, B;
    switch (a.fm) {
    case 0: return ADD(MUL(l0, ADD(MUL(k0, tmp[0]), MUL(k1, tmp[1]))), MUL(l1, ADD(MUL(k0, tmp[2]), MUL(k1, tmp[3]))));
    case 1: A = fmaf(k0, tmp[0], MUL(k1, tmp[1])); B = fmaf(k0, tmp[2], MUL(k1, tmp[3])); return fmaf(l0, A, MUL(l1, B));
    case 2: A = fmaf(k1, tmp[1], MUL(k0, tmp[0])); B = fmaf(k1, tmp[3], MUL(k0, tmp[2])); return fmaf(l1, B, MUL(l0, A));
    default: A = fmaf(k0, tmp[0], MUL(k1, tmp[1])); B = fmaf(k0, tmp[2], MUL(k1, tmp[3])); return ADD(MUL(l0, A), MUL(l1, B));
    }
}
extern "C" __global__ void sbtg_detail_cond(const DetailCondArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    const long long ee = (long long)a.E * a.E;
    if (i >= ee) return;
    int y = (int)(i / a.E), x = (int)(i - (long long)y * a.E);
    const int F = a.nc * a.f;
    const int Y = y + a.ms, X = x + a.ms;
    const float* s = a.base_full;
    float bs = s[(long long)Y * F + X];
    int xl = x > 0 ? X - 1 : X, xr = x < a.E - 1 ? X + 1 : X;
    int yu = y > 0 ? Y - 1 : Y, yd = y < a.E - 1 ? Y + 1 : Y;
    float gx = MUL(SUB(s[(long long)Y * F + xr], s[(long long)Y * F + xl]), 0.5f);
    float gy = MUL(SUB(s[(long long)yd * F + X], s[(long long)yu * F + X]), 0.5f);
    float* c = a.cond;
    a.base[i] = bs;
    c[0 * ee + i] = MUL(bs, a.inv_height);
    c[1 * ee + i] = MUL(gx, a.inv_slope);
    c[2 * ee + i] = MUL(gy, a.inv_slope);
    for (int k = 3; k < 11; ++k) c[k * ee + i] = 0.0f;                         // desc * has_desc (0)
    c[11 * ee + i] = MUL(up_t(a, a.wild, 0, Y, X), 1.0f);
    c[12 * ee + i] = MUL(up_t(a, a.t0, 1, Y, X), a.has_climate);
    c[13 * ee + i] = MUL(up_t(a, a.tseason, 2, Y, X), a.has_climate);
    c[14 * ee + i] = MUL(up_t(a, a.precip, 3, Y, X), a.has_climate);
    c[15 * ee + i] = MUL(up_t(a, a.pcv, 4, Y, X), a.has_climate);
    c[16 * ee + i] = a.logacc ? MUL(up_t(a, a.logacc, 5, Y, X), a.has_flow) : 0.0f;
    c[17 * ee + i] = a.river ? MUL(up_t(a, a.river, -1, Y, X), a.has_flow) : 0.0f;
    c[18 * ee + i] = 0.0f;
    c[19 * ee + i] = 1.0f;
    c[20 * ee + i] = a.has_climate;
    c[21 * ee + i] = a.has_flow;
    if (a.gates != 0)
        for (int k = 0; k < a.S; ++k)
            a.gates_up[k * ee + i] = up_t(a, a.gates + (long long)k * a.nc * a.nc, -1, Y, X);
    a.noise[i] = white_noise(a.si0 + y, a.sj0 + x, a.base_hash, a.sqrt3);
}

// Guard + output: ok = all finite and max |x| < limit (flag cleared when not); out = base + ASINH * sinh(clamp(x)).
struct DetailOutArgs {
    const float* x;
    const float* base;
    float* out;
    int* bad;                // set to 1 when any sample fails the guard (zeroed by the host before)
    long long n;
    float limit, asinh_m;
};
extern "C" __global__ void sbtg_detail_out(const DetailOutArgs a) {
    long long i = (long long)blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= a.n) return;
    float x = a.x[i];
    if (!(fabsf(x) < a.limit)) atomicOr(a.bad, 1);                            // NaN / inf / too large
    float xc = t_clamp(x, -a.limit, a.limit);
    a.out[i] = ADD(a.base[i], MUL(a.asinh_m, sinhf(xc)));
}
