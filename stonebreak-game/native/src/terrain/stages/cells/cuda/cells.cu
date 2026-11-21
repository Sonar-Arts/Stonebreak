// Stage module "cells": the elementwise kernels of the cell-grid generator methods (generator._relief_window / trend /
// _planner_input / drainage / cells / overview_cells / swamp_relief_cells, generator2._shelf_remap). The planner post-
// processing and the relief window's cond / post reuse module "sbtg_cells" (cuda/sbtg_cells.cu); the cross-fades run
// the DaedalusTGM blend kernel itself (module "csrc_blend").
//
// Bit-parity rules (core/cuda/sbtg_core.cu): every torch elementwise op = one IEEE fp32 op in torch's order
// (__f*_rn, no FMA contraction); "tensor / python scalar" = * the fp32 reciprocal (passed in from the host);
// transcendental functions = the CUDA libm ones torch calls; clamp / clamp_min / clamp_max keep NaN.
typedef unsigned int u32;

#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn

__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) { return x != x ? x : (x < lo ? lo : (x > hi ? hi : x)); }
__device__ __forceinline__ float t_clamp_min(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_clamp_max(float x, float hi) { return x != x ? x : (x > hi ? hi : x); }

// ------------------------------------------------------------------------------------------------ outer product
// w2 = a[:, None] * b[None, :] (the cross-fade weights from their 1-D profile).
struct OuterArgs {
    const float* a;
    const float* b;
    float* out;
    int H, W;
};
extern "C" __global__ void cells_outer(const OuterArgs a) {
    long long i = gtid();
    if (i >= (long long)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (long long)y * a.W);
    a.out[i] = MUL(a.a[y], a.b[x]);
}

// out = a + b
struct AddArgs {
    const float* a;
    const float* b;
    float* out;
    long long n;
};
extern "C" __global__ void cells_add(const AddArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    a.out[i] = ADD(a.a[i], a.b[i]);
}

// ------------------------------------------------------------------------------------------------ planner input
// generator._planner_input's x (N_IN, h, w): every channel written (unlisted ones 0, the has_* flags 1).
struct PlannerInputArgs {
    const float* trend;
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    const float* wild;
    float* x;
    long long hw;
    int n_in, c_trend, c_t0, c_tseason, c_precip, c_pcv, c_wild, c_has_trend, c_has_climate, c_has_wild;
    float inv_height, wild_off, inv_wild, inv8, inv300, log800, inv09, inv30;
};
__device__ __forceinline__ float precip_input(float p, float log800, float inv09) {
    p = p != p ? p : (p < 10.0f ? 10.0f : p);              // value.clamp_min(10.0)
    return MUL(SUB(logf(p), log800), inv09);
}
extern "C" __global__ void cells_planner_input(const PlannerInputArgs a) {
    long long i = gtid();
    if (i >= a.hw) return;
    for (int c = 0; c < a.n_in; ++c) {
        float v = 0.0f;
        if (c == a.c_trend) v = MUL(a.trend[i], a.inv_height);
        else if (c == a.c_t0) v = MUL(SUB(a.t0[i], 15.0f), a.inv8);
        else if (c == a.c_tseason) v = MUL(SUB(a.tseason[i], 600.0f), a.inv300);
        else if (c == a.c_precip) v = precip_input(a.precip[i], a.log800, a.inv09);
        else if (c == a.c_pcv) v = MUL(SUB(a.pcv[i], 40.0f), a.inv30);
        else if (c == a.c_wild) v = MUL(SUB(a.wild[i], a.wild_off), a.inv_wild);
        else if (c == a.c_has_trend || c == a.c_has_climate || c == a.c_has_wild) v = 1.0f;
        a.x[(long long)c * a.hw + i] = v;
    }
}

// ------------------------------------------------------------------------------------------------ white noise
// synth/noise.white_noise (exact integer hashes, fp32 sum in order).
__device__ __forceinline__ u32 lowbias32(u32 x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
struct NoiseArgs {
    float* out;
    long long i0, j0;
    int h, w;
    u32 base;
    float sqrt3;
};
extern "C" __global__ void cells_white_noise(const NoiseArgs a) {
    long long i = gtid();
    if (i >= (long long)a.h * a.w) return;
    int y = (int)(i / a.w), x = (int)(i - (long long)y * a.w);
    u32 key = lowbias32(lowbias32((u32)(a.i0 + y) ^ a.base) ^ (u32)(a.j0 + x));
    float acc = 0.0f;
#pragma unroll
    for (int k = 0; k < 4; ++k) {
        u32 hh = lowbias32(key ^ (0x27D4EB2Fu * (u32)(k + 1)));
        acc = ADD(acc, MUL(__uint2float_rn(hh), 2.3283064365386963e-10f));
    }
    a.out[i] = MUL(SUB(acc, 2.0f), a.sqrt3);
}

// ------------------------------------------------------------------------------------------------ route inputs
// generator._route_inputs: jit = blurred_noise * K; (height + jit * (height > 0), jit / ROUTE_NOISE_M).
struct RouteInArgs {
    const float* height;
    const float* blurred;
    float* hz;
    float* flat;
    long long n;
    float K, inv_m;
};
extern "C" __global__ void cells_route_inputs(const RouteInArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float h = a.height[i];
    float jit = MUL(a.blurred[i], a.K);
    a.hz[i] = ADD(h, MUL(jit, h > 0.0f ? 1.0f : 0.0f));
    a.flat[i] = MUL(jit, a.inv_m);
}

// ------------------------------------------------------------------------------------------------ drainage fields
// generator._drainage_fields: acc (float64 -> float32), 3x3 max pool (stride 1, padding 1, NaN-propagating like torch)
// -> [log1p(acc), acc >= RIVER_MIN_UPSLOPE_CELLS, d8 class as float].
struct DrainArgs {
    const double* acc;
    const long long* d8;
    float* out;      // (3, n, n)
    int n;
    float thr;
};
extern "C" __global__ void cells_drain_fields(const DrainArgs a) {
    long long i = gtid();
    const long long nn = (long long)a.n * a.n;
    if (i >= nn) return;
    int y = (int)(i / a.n), x = (int)(i - (long long)y * a.n);
    float m = __int_as_float(0xff800000);    // -inf
    for (int dy = -1; dy <= 1; ++dy) {
        int yy = y + dy;
        if (yy < 0 || yy >= a.n) continue;
        for (int dx = -1; dx <= 1; ++dx) {
            int xx = x + dx;
            if (xx < 0 || xx >= a.n) continue;
            float v = __double2float_rn(a.acc[(long long)yy * a.n + xx]);
            if (v > m || v != v) m = v;
        }
    }
    a.out[i] = log1pf(m);
    a.out[nn + i] = m >= a.thr ? 1.0f : 0.0f;
    a.out[2 * nn + i] = (float)a.d8[i];
}

// ------------------------------------------------------------------------------------------------ hydro sidecar
// models/hydro.build_input: [height / HEIGHT, slope x, slope y (replicate pad, * 0.5 / SLOPE), climate inputs].
struct HydroInArgs {
    const float* h;
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    float* x;        // (7, n, n)
    int n;
    float inv_height, inv_slope, inv8, inv300, log800, inv09, inv30;
};
extern "C" __global__ void cells_hydro_input(const HydroInArgs a) {
    long long i = gtid();
    const long long nn = (long long)a.n * a.n;
    if (i >= nn) return;
    int y = (int)(i / a.n), x = (int)(i - (long long)y * a.n);
    int xl = x > 0 ? x - 1 : x, xr = x < a.n - 1 ? x + 1 : x;
    int yu = y > 0 ? y - 1 : y, yd = y < a.n - 1 ? y + 1 : y;
    const float* h = a.h;
    a.x[i] = MUL(h[i], a.inv_height);
    a.x[nn + i] = MUL(MUL(SUB(h[(long long)y * a.n + xr], h[(long long)y * a.n + xl]), 0.5f), a.inv_slope);
    a.x[2 * nn + i] = MUL(MUL(SUB(h[(long long)yd * a.n + x], h[(long long)yu * a.n + x]), 0.5f), a.inv_slope);
    a.x[3 * nn + i] = MUL(SUB(a.t0[i], 15.0f), a.inv8);
    a.x[4 * nn + i] = MUL(SUB(a.tseason[i], 600.0f), a.inv300);
    a.x[5 * nn + i] = precip_input(a.precip[i], a.log800, a.inv09);
    a.x[6 * nn + i] = MUL(SUB(a.pcv[i], 40.0f), a.inv30);
}

// generator._hydro_pred's stack: [out[LOGACC] * LOGACC_SCALE, sigmoid(out[RIVER]), argmax(out[D8 slice]) as float].
struct HydroPostArgs {
    const float* o;  // (n_out, n, n)
    float* out;      // (3, n, n)
    long long hw;
    int c_logacc, c_river, c_d8, n_d8;
    float logacc_scale;
};
extern "C" __global__ void cells_hydro_post(const HydroPostArgs a) {
    long long i = gtid();
    if (i >= a.hw) return;
    a.out[i] = MUL(a.o[(long long)a.c_logacc * a.hw + i], a.logacc_scale);
    float r = a.o[(long long)a.c_river * a.hw + i];
    a.out[a.hw + i] = 1.0f / (1.0f + expf(-r));
    // torch.argmax: the first maximal index; NaN counts as the maximum
    float best = a.o[(long long)a.c_d8 * a.hw + i];
    int bi = 0;
    for (int k = 1; k < a.n_d8; ++k) {
        float v = a.o[(long long)(a.c_d8 + k) * a.hw + i];
        if (best != best) break;
        if (v != v || v > best) {
            best = v;
            bi = k;
        }
    }
    a.out[2 * a.hw + i] = (float)bi;
}

// ------------------------------------------------------------------------------------------------ owned (categorical)
// generator._owned: out[r, c] = (long) win[r + dr, c + dc] over one owner's rectangle of the (H, W) int64 output.
struct OwnedArgs {
    const float* win;   // (EXT, EXT) plane
    long long* out;     // (H, W)
    int W, EXT;
    int r0, r1, s0, s1; // output rectangle
    int dr, dc;         // window row / col = output row / col + (dr, dc)
};
extern "C" __global__ void cells_owned(const OwnedArgs a) {
    long long i = gtid();
    const int h = a.r1 - a.r0, w = a.s1 - a.s0;
    if (i >= (long long)h * w) return;
    int y = (int)(i / w), x = (int)(i - (long long)y * w);
    int r = a.r0 + y, c = a.s0 + x;
    a.out[(long long)r * a.W + c] = (long long)a.win[(long long)(r + a.dr) * a.EXT + c + a.dc];
}

// ------------------------------------------------------------------------------------------------ coastal shelf
// generator2._shelf_remap (k = SEA_SHELF_SLOPE < 1).
struct ShelfArgs {
    const float* h;
    float* out;
    long long n;
    float k, dm, inv_dm, one_minus_k, k0_minus_k, d0, inv_d0;
    int keep_on;
};
__device__ __forceinline__ float S5(float t) {   // t ** 4 * (t * t - 3.0 * t + 2.5)
    return MUL(powf(t, 4.0f), ADD(SUB(MUL(t, t), MUL(3.0f, t)), 2.5f));
}
extern "C" __global__ void cells_shelf(const ShelfArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float h = a.h[i];
    float d = t_clamp_min(-h, 0.0f);
    float t = t_clamp(MUL(d, a.inv_dm), 0.0f, 1.0f);
    float f = ADD(MUL(a.k, d), MUL(a.one_minus_k, ADD(MUL(a.dm, S5(t)), t_clamp_min(SUB(d, a.dm), 0.0f))));
    if (a.keep_on) {
        float u = t_clamp(MUL(d, a.inv_d0), 0.0f, 1.0f);
        f = ADD(f, MUL(a.k0_minus_k, SUB(t_clamp_max(d, a.d0), MUL(a.d0, S5(u)))));
    }
    a.out[i] = h < 0.0f ? -f : h;
}

// ------------------------------------------------------------------------------------------------ swamp relief
// swamp_relief.cell_fields: weight = smoothstep of the blurred candidate mask; table = stairs(broad) + KEEP (local -
// broad), clamped at FLOOR.
struct SwampWeightArgs {
    const float* blurred;
    float* out;
    long long n;
    float lo, inv_span;
};
extern "C" __global__ void cells_swamp_weight(const SwampWeightArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float t = t_clamp(MUL(SUB(a.blurred[i], a.lo), a.inv_span), 0.0f, 1.0f);
    a.out[i] = MUL(MUL(t, t), SUB(3.0f, MUL(2.0f, t)));
}
struct SwampTableArgs {
    const float* local;
    const float* broad;
    float* out;
    long long n;
    int passes, stairs_on;
    float inv_stair, stair, two_pi, inv_two_pi, keep, floor_m;
};
extern "C" __global__ void cells_swamp_table(const SwampTableArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float b = a.broad[i];
    float s = b;
    if (a.stairs_on) {
        float x = MUL(b, a.inv_stair);
        for (int k = 0; k < a.passes; ++k) x = SUB(x, MUL(sinf(MUL(a.two_pi, x)), a.inv_two_pi));
        s = MUL(x, a.stair);
    }
    a.out[i] = t_clamp_min(ADD(s, MUL(a.keep, SUB(a.local[i], b))), a.floor_m);
}

// ------------------------------------------------------------------------------------------------ lapse climate
// torch.stack([t0 - LAPSE * reg.clamp_min(0), tseason, precip, pcv]) (overview_cells, swamp_relief_cells).
struct LapseArgs {
    const float* t0;
    const float* tseason;
    const float* precip;
    const float* pcv;
    const float* reg;
    float* out;     // (4, n)
    long long n;
    float lapse;
};
extern "C" __global__ void cells_lapse(const LapseArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    a.out[i] = SUB(a.t0[i], MUL(a.lapse, t_clamp_min(a.reg[i], 0.0f)));
    a.out[a.n + i] = a.tseason[i];
    a.out[2 * a.n + i] = a.precip[i];
    a.out[3 * a.n + i] = a.pcv[i];
}

// ------------------------------------------------------------------------------------------------ sea fields (FX3)
// generator2._distance_cells, one separable min-plus pass over +-cap along rows (axis 0) or columns (axis 1): out =
// min_k (x[. + k] or BIG outside the window) + k^2. Every value is a small integer, exact in fp32 (any order).
struct DistPassArgs {
    const float* src;   // pass 0: nullptr -> f = land ? 0 : BIG from `land`
    const unsigned char* land;
    float* dst;
    int H, W, cap, axis;
    float big;
};
extern "C" __global__ void cells_dist_pass(const DistPassArgs a) {
    long long i = gtid();
    if (i >= (long long)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (long long)y * a.W);
    float best = __int_as_float(0x7f800000);
    for (int k = -a.cap; k <= a.cap; ++k) {
        int yy = a.axis == 1 ? y + k : y, xx = a.axis == 0 ? x + k : x;
        float v;
        if (yy < 0 || yy >= a.H || xx < 0 || xx >= a.W) v = a.big;
        else {
            long long j = (long long)yy * a.W + xx;
            v = a.src ? a.src[j] : (a.land[j] ? 0.0f : a.big);
        }
        float c = ADD(v, (float)(k * k));
        best = c < best ? c : best;
    }
    a.dst[i] = best;
}
// sqrt(d2).clamp_max(cap)
struct DistFinishArgs {
    const float* d2;
    float* out;
    long long n;
    float cap;
};
extern "C" __global__ void cells_dist_finish(const DistFinishArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    a.out[i] = t_clamp_max(sqrtf(a.d2[i]), a.cap);
}

// sub = cells < 0, land = ~sub
struct SeaMaskArgs {
    const float* cells;
    unsigned char* sub;
    unsigned char* land;
    long long n;
};
extern "C" __global__ void cells_sea_masks(const SeaMaskArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    bool s = a.cells[i] < 0.0f;
    a.sub[i] = s;
    a.land[i] = !s;
}
// seed_land = where(trend < SEA_SEED_TREND_M, 0, open_land);
// seeds = sub & (where(offshore_dist >= LAKE_SEED_MIN_CELLS, seed_land, open_land) < SEA_LAND)
struct SeaSeedArgs2 {
    const float* trend;
    const float* open_land;
    const float* off_dist;   // _distance_cells(~sub, MIN)
    const unsigned char* sub;
    unsigned char* seeds;
    long long n;
    float sea_trend, sea_land, min_cells;
};
extern "C" __global__ void cells_sea_seeds(const SeaSeedArgs2 a) {
    long long i = gtid();
    if (i >= a.n) return;
    float ol = a.open_land[i];
    float seed_land = a.trend[i] < a.sea_trend ? 0.0f : ol;
    float v = a.off_dist[i] >= a.min_cells ? seed_land : ol;
    a.seeds[i] = a.sub[i] && v < a.sea_land;
}
// one growth step: out = max_pool(in, k, 1, r) * through (boolean); `through` nullptr = no mask
struct GrowArgs {
    const unsigned char* src;
    const unsigned char* through;
    unsigned char* dst;
    int H, W, r;
};
extern "C" __global__ void cells_grow(const GrowArgs a) {
    long long i = gtid();
    if (i >= (long long)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (long long)y * a.W);
    unsigned char m = 0;
    for (int dy = -a.r; dy <= a.r && !m; ++dy) {
        int yy = y + dy;
        if (yy < 0 || yy >= a.H) continue;
        for (int dx = -a.r; dx <= a.r; ++dx) {
            int xx = x + dx;
            if (xx >= 0 && xx < a.W && a.src[(long long)yy * a.W + xx]) {
                m = 1;
                break;
            }
        }
    }
    a.dst[i] = (a.through == 0 || a.through[i]) ? m : 0;
}
// crop + finish: dist_m = clamp_min(dist - 0.5, 0) * CELL_M; lake = sub & ~coast_sea
struct SeaOutArgs {
    const float* dist;
    const unsigned char* sub;
    const unsigned char* coast;   // dilated sea
    float* dist_m;
    unsigned char* lake;
    int W, m, hc, wc;
    float cell_m;
};
extern "C" __global__ void cells_sea_out(const SeaOutArgs a) {
    long long i = gtid();
    if (i >= (long long)a.hc * a.wc) return;
    int y = (int)(i / a.wc), x = (int)(i - (long long)y * a.wc);
    long long j = (long long)(y + a.m) * a.W + x + a.m;
    a.dist_m[i] = MUL(t_clamp_min(SUB(a.dist[j], 0.5f), 0.0f), a.cell_m);
    a.lake[i] = a.sub[j] && !a.coast[j];
}
// generator2.sea_fields_px: bilinear between cell centres, u = (p + 0.5) / ppc - 0.5 - c0 in float64 (torch: / scalar =
// * reciprocal), t = float(u - floor u); rows first, then columns: lo + t * (hi - lo) in fp32. Two planes (dist, lake);
// the lake plane is bool -> float.
struct SeaPxArgs {
    const float* d;
    const unsigned char* lake;
    float* od;
    float* ol;
    int h, w, CW;               // output size, cell grid width
    long long i1, j1, ci0, cj0;
    double inv_ppc;
};
__device__ __forceinline__ void sea_axis(long long p, double inv_ppc, long long c0, int* a, float* t) {
    double u = __dsub_rn(__dsub_rn(__dmul_rn(__dadd_rn((double)p, 0.5), inv_ppc), 0.5), (double)c0);
    double f = floor(u);
    *a = (int)f;
    *t = __double2float_rn(__dsub_rn(u, f));
}
__device__ __forceinline__ float lerp_t(float lo, float hi, float t) { return ADD(lo, MUL(t, SUB(hi, lo))); }
extern "C" __global__ void cells_sea_px(const SeaPxArgs a) {
    long long i = gtid();
    if (i >= (long long)a.h * a.w) return;
    int y = (int)(i / a.w), x = (int)(i - (long long)y * a.w);
    int ay, ax;
    float ty, tx;
    sea_axis(a.i1 + y, a.inv_ppc, a.ci0, &ay, &ty);
    sea_axis(a.j1 + x, a.inv_ppc, a.cj0, &ax, &tx);
    long long r0 = (long long)ay * a.CW, r1 = (long long)(ay + 1) * a.CW;
    float c0 = lerp_t(a.d[r0 + ax], a.d[r1 + ax], ty), c1 = lerp_t(a.d[r0 + ax + 1], a.d[r1 + ax + 1], ty);
    a.od[i] = lerp_t(c0, c1, tx);
    float l00 = a.lake[r0 + ax] ? 1.0f : 0.0f, l10 = a.lake[r1 + ax] ? 1.0f : 0.0f;
    float l01 = a.lake[r0 + ax + 1] ? 1.0f : 0.0f, l11 = a.lake[r1 + ax + 1] ? 1.0f : 0.0f;
    a.ol[i] = lerp_t(lerp_t(l00, l10, ty), lerp_t(l01, l11, ty), tx);
}
// generator2._shelf_profile_remap(h, dist_m, lake): planes of h share the (H, W) dist / lake.
struct ProfileArgs {
    const float* h;
    const float* dist;
    const float* lake;          // nullptr: no lake weight
    float* out;
    long long n, hw;
    float m0, slope, lake_m0, lake_slope;
    // _shelf_remap(-e, COMPRESS, RETURN_M, 1.0, KEEP_M)
    float k, dm, inv_dm, one_minus_k, k0_minus_k, d0, inv_d0;
    int keep_on;
};
__device__ __forceinline__ float t_minimum(float a, float b) { return (a != a || b != b) ? __int_as_float(0x7fffffff) : (a < b ? a : b); }
extern "C" __global__ void cells_shelf_profile(const ProfileArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    long long q = i % a.hw;
    float h = a.h[i], dist = a.dist[q];
    float d = t_clamp_min(-h, 0.0f);
    float p = ADD(a.m0, MUL(a.slope, dist));
    float e = t_clamp_min(SUB(d, p), 0.0f);
    // f(e) of _shelf_remap on -e (e = -(-e) exactly)
    float t = t_clamp(MUL(e, a.inv_dm), 0.0f, 1.0f);
    float f = ADD(MUL(a.k, e), MUL(a.one_minus_k, ADD(MUL(a.dm, S5(t)), t_clamp_min(SUB(e, a.dm), 0.0f))));
    if (a.keep_on) {
        float u = t_clamp(MUL(e, a.inv_d0), 0.0f, 1.0f);
        f = ADD(f, MUL(a.k0_minus_k, SUB(t_clamp_max(e, a.d0), MUL(a.d0, S5(u)))));
    }
    float hh = -e;                             // _shelf_remap's input; g = -(where(hh < 0, -f, hh))
    float g = -(hh < 0.0f ? -f : hh);
    float sea_d = d > p ? ADD(p, g) : d;
    if (a.lake) {
        float lake_d = t_minimum(d, ADD(a.lake_m0, MUL(a.lake_slope, dist)));
        sea_d = ADD(sea_d, MUL(a.lake[q], SUB(lake_d, sea_d)));
    }
    a.out[i] = h < 0.0f ? -sea_d : h;
}
