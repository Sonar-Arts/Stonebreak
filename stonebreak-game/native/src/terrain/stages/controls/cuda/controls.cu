// Stage module "controls": the climate node's window inputs (generator2._climate_window / _continentality /
// _land_lattice), the cross-fade weights of _climate_grid, controls()' gates and climate_native's lapse.
// Bit-parity rules: every torch elementwise op is ONE IEEE fp32 op in the same order (__f*_rn intrinsics, never
// contracted), torch's "tensor / python scalar" is a multiply by the fp32 reciprocal (passed in from the manifest),
// clamp / maximum propagate NaN, numpy / scipy host math (the EDT coast) is fp64 with the same operation order.
typedef unsigned int u32;
typedef long long i64;

#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn

__device__ __forceinline__ i64 gtid() { return (i64)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) { return x != x ? x : (x < lo ? lo : (x > hi ? hi : x)); }
__device__ __forceinline__ float t_clamp_min(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_maximum(float a, float b) { return a != a ? a : (b != b ? b : (a > b ? a : b)); }
// G._smoothstep: x = clamp(x, 0, 1); x * x * (3 - 2 * x)
__device__ __forceinline__ float ss(float x) {
    x = t_clamp(x, 0.0f, 1.0f);
    return MUL(MUL(x, x), SUB(3.0f, MUL(x, 2.0f)));
}

// F.grid_sample(input, grid, "bilinear", padding "zeros", align_corners=True) on CUDA goes to cuDNN
// (aten::cudnn_grid_sampler, bilinear_sampler_fw_4d): source index ((g + 1) / 2) * (size - 1), weights
// (1 - dx)(1 - dy) ..., accumulated as ne, then nw / sw / se with fused multiply-adds (measured bit for bit against
// cuDNN on 200k random points; every point the generator samples lies at index >= 1 of its axis, where it is exact).
__device__ __forceinline__ float cudnn_sample(const float* p, int H, int W, float gx, float gy) {
    const float ix = MUL(MUL(ADD(gx, 1.0f), 0.5f), (float)(W - 1));
    const float iy = MUL(MUL(ADD(gy, 1.0f), 0.5f), (float)(H - 1));
    const float x0 = floorf(ix), y0 = floorf(iy);
    const int xi = (int)x0, yi = (int)y0;
    const float dx = SUB(ix, x0), dy = SUB(iy, y0);
    const float ex = SUB(1.0f, dx), ey = SUB(1.0f, dy);
    const bool x0in = xi >= 0 && xi < W, x1in = xi + 1 >= 0 && xi + 1 < W;
    const bool y0in = yi >= 0 && yi < H, y1in = yi + 1 >= 0 && yi + 1 < H;
    const float v00 = (y0in && x0in) ? p[(i64)yi * W + xi] : 0.0f;
    const float v01 = (y0in && x1in) ? p[(i64)yi * W + xi + 1] : 0.0f;
    const float v10 = (y1in && x0in) ? p[(i64)(yi + 1) * W + xi] : 0.0f;
    const float v11 = (y1in && x1in) ? p[(i64)(yi + 1) * W + xi + 1] : 0.0f;
    float o = MUL(v01, MUL(dx, ey));
    o = fmaf(v00, MUL(ex, ey), o);
    o = fmaf(v10, MUL(ex, dy), o);
    o = fmaf(v11, MUL(dx, dy), o);
    return o;
}

// ---------------------------------------------------------------------------------------------- sample points
// _climate_window: pi = ((a0 - e) + (arange(M) + 0.5) / k) * g, pj alike, expanded to (M, M) (contiguous).
// _land_lattice:   pi = (gi0 + (arange(n) + 0.5) * step) * g (mode 1).
struct PointsArgs {
    float* ci;
    float* cj;
    int M, mode;
    float off_i, off_j;     // float(a0 - e) / float(gi0)
    float s;                // mode 0: fp32 1 / k; mode 1: float(step)
    float g;                // fp32 CLIMATE_GRID_CELLS
};
extern "C" __global__ void controls_points(const PointsArgs a) {
    const i64 i = gtid();
    const i64 n = (i64)a.M * a.M;
    if (i >= n) return;
    const int r = (int)(i / a.M), c = (int)(i - (i64)r * a.M);
    const float sr = MUL(ADD((float)r, 0.5f), a.s), sc = MUL(ADD((float)c, 0.5f), a.s);
    a.ci[i] = MUL(ADD(sr, a.off_i), a.g);
    a.cj[i] = MUL(ADD(sc, a.off_j), a.g);
}

// (trend > 0).float()
struct Gt0Args {
    const float* src;
    float* dst;
    i64 n;
};
extern "C" __global__ void controls_gt0(const Gt0Args a) {
    const i64 i = gtid();
    if (i >= a.n) return;
    a.dst[i] = a.src[i] > 0.0f ? 1.0f : 0.0f;
}

// ---------------------------------------------------------------------------------------------- window pooling
// land_x = pool(trend > 0), elev_x = pool(trend.clamp_min(0)) / land_x.clamp_min(1e-3) / 1000 * (land_x > 0) over the
// (m, m) pooled grid (grid planes 0 / 1), and the inner (n, n) pooled belt / wind_u / wind_v (planes of `inner`).
// F.avg_pool2d: fp32 sum in row-major order from +0, then a true division by k * k.
struct PoolArgs {
    const float* trend;
    const float* belt;
    const float* wu;
    const float* wv;
    float* grid;            // (2, m, m)
    float* inner;           // (3, n, n)
    int m, k, n, e;
    float eps, inv1000;
};
extern "C" __global__ void controls_cw_pool(const PoolArgs a) {
    const i64 i = gtid();
    const i64 mm = (i64)a.m * a.m;
    if (i >= mm) return;
    const int r = (int)(i / a.m), c = (int)(i - (i64)r * a.m);
    const int MW = a.m * a.k;
    const float kk = (float)(a.k * a.k);
    const i64 base = (i64)r * a.k * MW + (i64)c * a.k;
    float sl = 0.0f, se = 0.0f;
    for (int dy = 0; dy < a.k; ++dy)
        for (int dx = 0; dx < a.k; ++dx) {
            const float t = a.trend[base + (i64)dy * MW + dx];
            sl = ADD(sl, t > 0.0f ? 1.0f : 0.0f);
            se = ADD(se, t_clamp_min(t, 0.0f));
        }
    const float land = DIV(sl, kk);
    const float elev = MUL(MUL(DIV(DIV(se, kk), t_clamp_min(land, a.eps)), a.inv1000), land > 0.0f ? 1.0f : 0.0f);
    a.grid[i] = land;
    a.grid[mm + i] = elev;
    const int ir = r - a.e, ic = c - a.e;
    if (ir < 0 || ic < 0 || ir >= a.n || ic >= a.n) return;
    float sb = 0.0f, su = 0.0f, sv = 0.0f;
    for (int dy = 0; dy < a.k; ++dy)
        for (int dx = 0; dx < a.k; ++dx) {
            const i64 o = base + (i64)dy * MW + dx;
            sb = ADD(sb, a.belt[o]);
            su = ADD(su, a.wu[o]);
            sv = ADD(sv, a.wv[o]);
        }
    const i64 nn = (i64)a.n * a.n, q = (i64)ir * a.n + ic;
    a.inner[q] = DIV(sb, kk);
    a.inner[nn + q] = DIV(su, kk);
    a.inner[2 * nn + q] = DIV(sv, kk);
}

// ---------------------------------------------------------------------------------------------- coast (EDT)
// scipy.ndimage.distance_transform_edt of sea = (land < 0.5) and of ~sea over the inner (n, n) window, exactly: the
// squared distance to the nearest zero of the input is an integer; column pass then row pass (Meijster-style, brute
// force per row: n^3). With no zero at all scipy's feature transform reads (-1, 0) for every cell.
#define EDT_INF 0x3fffffff
struct EdtColArgs {
    const float* grid;      // land_x (m, m)
    int* g;                 // (2, n, n): plane p = nearest feature row distance in the column (EDT_INF: none)
    int m, n, e;
};
extern "C" __global__ void controls_edt_cols(const EdtColArgs a) {
    const i64 i = gtid();
    const i64 nn = (i64)a.n * a.n;
    if (i >= 2 * nn) return;
    const int p = (int)(i / nn);
    const int r = (int)((i - p * nn) / a.n), c = (int)(i - p * nn - (i64)r * a.n);
    int best = EDT_INF;
    for (int q = 0; q < a.n; ++q) {
        const bool sea = a.grid[(i64)(q + a.e) * a.m + (c + a.e)] < 0.5f;
        const bool feature = p == 0 ? !sea : sea;   // p 0: edt(sea) (zeros = land); p 1: edt(~sea) (zeros = sea)
        if (feature) {
            const int d = q > r ? q - r : r - q;
            best = d < best ? d : best;
        }
    }
    a.g[i] = best;
}
struct CoastArgs {
    const int* g;
    float* coast;           // (n, n): asinh(((d_sea - d_land) / 50)) of the fp64 distances (km)
    int n;
    double grid_km, div;
};
__device__ __forceinline__ double edt_at(const int* g, int n, int r, int c) {
    long long best = -1;
    for (int q = 0; q < n; ++q) {
        const int gv = g[(i64)r * n + q];
        if (gv >= EDT_INF) continue;
        const long long d = (long long)gv * gv + (long long)(q - c) * (q - c);
        if (best < 0 || d < best) best = d;
    }
    if (best < 0) best = (long long)(r + 1) * (r + 1) + (long long)c * c;
    return __dsqrt_rn((double)best);
}
extern "C" __global__ void controls_edt_coast(const CoastArgs a) {
    const i64 i = gtid();
    const i64 nn = (i64)a.n * a.n;
    if (i >= nn) return;
    const int r = (int)(i / a.n), c = (int)(i - (i64)r * a.n);
    const double d_land = __dmul_rn(edt_at(a.g, a.n, r, c), a.grid_km);
    const double d_sea = __dmul_rn(edt_at(a.g + nn, a.n, r, c), a.grid_km);
    a.coast[i] = asinhf(__double2float_rn(__ddiv_rn(__dsub_rn(d_sea, d_land), a.div)));
}

// ---------------------------------------------------------------------------------------------- continentality
// _continentality's last step: idx = (arange(WIN) + 0.5) / step + reach - 0.5, gy = (idx / (n - 1)) * 2 - 1,
// grid_sample(blurred (n, n), (x = gy[j], y = gy[i]), align_corners=True).
struct ContArgs {
    const float* blurred;
    float* out;
    int n, win, reach;
    float inv_step, inv_n1;
};
__device__ __forceinline__ float cont_g(const ContArgs& a, int i) {
    const float idx = SUB(ADD(MUL(ADD((float)i, 0.5f), a.inv_step), (float)a.reach), 0.5f);
    return SUB(MUL(MUL(idx, a.inv_n1), 2.0f), 1.0f);
}
extern "C" __global__ void controls_cont_sample(const ContArgs a) {
    const i64 i = gtid();
    const i64 ww = (i64)a.win * a.win;
    if (i >= ww) return;
    const int r = (int)(i / a.win), c = (int)(i - (i64)r * a.win);
    a.out[i] = cudnn_sample(a.blurred, a.n, a.n, cont_g(a, c), cont_g(a, r));
}

// ---------------------------------------------------------------------------------------------- climate input
// The rest of _climate_window: damp, u, v, climatenet.upwind_features (fetch = grid_sample of (land_x, elev_x) at the
// clamped upwind positions) and the input stack x (15, n, n).
#define MAX_UP 8
struct InputArgs {
    const float* grid;      // (2, m, m) land_x, elev_x
    const float* inner;     // (3, n, n) pooled belt, wind_u, wind_v
    const float* l250;
    const float* l1000;
    const float* l2500;
    const float* coast;
    float* x;               // (15, n, n)
    int m, n, e, n_near, n_far;
    float near_km[MAX_UP], far_km[MAX_UP];
    float wind_scale, lw0, lw1;
    float eps, calm_inv, inv_km, inv_m1, inv_near, inv_far, inv5;
    float forcing0, forcing1;
};
__device__ __forceinline__ void fetch(const InputArgs& a, float ii, float jj, float drow, float dcol, float* land,
                                      float* elev) {
    const float hi = (float)(a.m - 1);
    const float gy = SUB(MUL(MUL(t_clamp(ADD(ii, drow), 0.0f, hi), a.inv_m1), 2.0f), 1.0f);
    const float gx = SUB(MUL(MUL(t_clamp(ADD(jj, dcol), 0.0f, hi), a.inv_m1), 2.0f), 1.0f);
    const i64 mm = (i64)a.m * a.m;
    *land = cudnn_sample(a.grid, a.m, a.m, gx, gy);
    *elev = cudnn_sample(a.grid + mm, a.m, a.m, gx, gy);
}
extern "C" __global__ void controls_cw_input(const InputArgs a) {
    const i64 i = gtid();
    const i64 nn = (i64)a.n * a.n;
    if (i >= nn) return;
    const int r = (int)(i / a.n), c = (int)(i - (i64)r * a.n);
    const i64 mm = (i64)a.m * a.m;
    const i64 gi = (i64)(r + a.e) * a.m + (c + a.e);
    const float land = a.grid[gi], elev = a.grid[mm + gi];
    const float belt = a.inner[i];
    const float l250 = a.l250[i], l1000 = a.l1000[i];
    const float om = SUB(1.0f, l250);
    const float damp = MUL(a.wind_scale, ADD(a.lw0, MUL(a.lw1, MUL(om, om))));
    const float u = MUL(a.inner[nn + i], damp), v = MUL(a.inner[2 * nn + i], damp);
    // upwind_features
    const float s = sqrtf(ADD(MUL(u, u), MUL(v, v)));
    const float den = ADD(s, a.eps);
    const float uh = DIV(u, den), vh = DIV(v, den);
    const float steady = t_clamp(MUL(s, a.calm_inv), 0.0f, 1.0f);
    const float ii = (float)(r + a.e), jj = (float)(c + a.e);
    float sea_near = 0.0f, barrier = 0.0f, sea_far = 0.0f;
    for (int q = 0; q < a.n_near; ++q) {
        const float d = a.near_km[q];
        float fl, fe;
        fetch(a, ii, jj, MUL(MUL(vh, d), a.inv_km), MUL(MUL(-uh, d), a.inv_km), &fl, &fe);
        const float t = MUL(SUB(1.0f, fl), a.inv_near);
        sea_near = q == 0 ? ADD(t, 0.0f) : ADD(sea_near, t);
        barrier = t_maximum(barrier, SUB(fe, elev));
    }
    for (int q = 0; q < a.n_far; ++q) {
        const float d = a.far_km[q];
        float fl, fe;
        fetch(a, ii, jj, MUL(MUL(vh, d), a.inv_km), MUL(MUL(-uh, d), a.inv_km), &fl, &fe);
        const float t = MUL(SUB(1.0f, fl), a.inv_far);
        sea_far = q == 0 ? ADD(t, 0.0f) : ADD(sea_far, t);
    }
    const float calm = SUB(1.0f, steady);
    const float up0 = ADD(MUL(steady, sea_near), MUL(calm, SUB(1.0f, l250)));
    const float up1 = ADD(MUL(steady, sea_far), MUL(calm, SUB(1.0f, l1000)));
    const float up2 = MUL(steady, t_clamp_min(barrier, 0.0f));
    float* x = a.x + i;
    x[0 * nn] = elev;
    x[1 * nn] = land;
    x[2 * nn] = l250;
    x[3 * nn] = l1000;
    x[4 * nn] = a.l2500[i];
    x[5 * nn] = a.coast[i];
    x[6 * nn] = belt;
    x[7 * nn] = fabsf(belt);
    x[8 * nn] = MUL(u, a.inv5);
    x[9 * nn] = MUL(v, a.inv5);
    x[10 * nn] = up0;
    x[11 * nn] = up1;
    x[12 * nn] = up2;
    x[13 * nn] = a.forcing0;
    x[14 * nn] = a.forcing1;
}

// ---------------------------------------------------------------------------------------------- cross-fade weights
// _climate_grid's w2: w1 = 1, w1[:ramp] = sin(0.5 pi t) ** 2, w1[-ramp:] = cos(0.5 pi t) ** 2 with
// t = (arange(ramp) + 0.5) / ramp; w2 = w1[:, None] * w1[None, :].
struct FadeArgs {
    float* w2;
    int win, ramp;
    float inv_ramp, half_pi;
};
__device__ __forceinline__ float fade1(const FadeArgs& a, int i) {
    if (i < a.ramp) {
        const float s = sinf(MUL(a.half_pi, MUL(ADD((float)i, 0.5f), a.inv_ramp)));
        return MUL(s, s);
    }
    if (i >= a.win - a.ramp) {
        const float c = cosf(MUL(a.half_pi, MUL(ADD((float)(i - (a.win - a.ramp)), 0.5f), a.inv_ramp)));
        return MUL(c, c);
    }
    return 1.0f;
}
extern "C" __global__ void controls_fade(const FadeArgs a) {
    const i64 i = gtid();
    const i64 ww = (i64)a.win * a.win;
    if (i >= ww) return;
    const int r = (int)(i / a.win), c = (int)(i - (i64)r * a.win);
    a.w2[i] = MUL(fade1(a, r), fade1(a, c));
}

// ---------------------------------------------------------------------------------------------- controls() gates
// gates (S, n): the layout's gates with the aeolian gate from the climate (controls2.aeolian_gate) and disabled
// experts zeroed: _ss((0.2 - aridity) / 0.15) * (1 - _ss((trend - 400) / 400)).
struct GatesArgs {
    const float* gates;
    const float* aridity;   // nullptr: no climate node
    const float* trend;
    float* out;
    i64 n;
    int S, aeolian;
    int enabled[8];
    float a0, inv_a, r0, inv_r;
};
extern "C" __global__ void controls_gates(const GatesArgs a) {
    const i64 i = gtid();
    if (i >= a.n * a.S) return;
    const int s = (int)(i / a.n);
    const i64 q = i - (i64)s * a.n;
    float v = a.gates[i];
    if (a.aridity != nullptr && s == a.aeolian)
        v = MUL(ss(MUL(SUB(a.a0, a.aridity[q]), a.inv_a)), SUB(1.0f, ss(MUL(SUB(a.trend[q], a.r0), a.inv_r))));
    if (!a.enabled[s]) v = 0.0f;
    a.out[i] = v;
}

// climate_native: clim[0] = clim[0] - LAPSE_C_PER_M * elev.clamp_min(0)
struct LapseArgs {
    float* t0;
    const float* elev;
    i64 n;
    float lapse;
};
extern "C" __global__ void controls_lapse(const LapseArgs a) {
    const i64 i = gtid();
    if (i >= a.n) return;
    a.t0[i] = SUB(a.t0[i], MUL(a.lapse, t_clamp_min(a.elev[i], 0.0f)));
}

// ---------------------------------------------------------------------------------------------- red deserts
// generator.red_desert_cells: domain-warped cubic B-spline blend of lattice-node votes; each node walks downhill in the
// softened dryness field (generator._dryness: softmax of the climate scores, torch's spatial softmax kernel, then the
// weighted log-precipitation summed like torch's outer-dim reduction: 4 accumulators, combined in order).
__device__ __forceinline__ u32 rd_hash32(u32 x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
__device__ __forceinline__ float rd_corner(u32 xi, u32 yi, u32 base, u32 dx, u32 dy) {
    const u32 h = rd_hash32(rd_hash32((xi + dx) ^ base) ^ (yi + dy));
    return SUB(MUL(__uint2float_rn(h), 4.656612873077393e-10f), 1.0f);
}
__device__ __forceinline__ float rd_quintic(float f) { return MUL(MUL(MUL(f, f), f), ADD(MUL(f, SUB(MUL(f, 6.0f), 15.0f)), 10.0f)); }
// generator._value_noise
__device__ __forceinline__ float rd_vnoise(float ci, float cj, float inv_period, u32 base) {
    const float x = MUL(ci, inv_period), y = MUL(cj, inv_period);
    const float x0 = floorf(x), y0 = floorf(y);
    const float ux = rd_quintic(SUB(x, x0)), uy = rd_quintic(SUB(y, y0));
    const u32 xi = (u32)(i64)x0, yi = (u32)(i64)y0;
    const float a = rd_corner(xi, yi, base, 0, 0), b = rd_corner(xi, yi, base, 0, 1);
    const float c = rd_corner(xi, yi, base, 1, 0), d = rd_corner(xi, yi, base, 1, 1);
    const float vy = SUB(1.0f, uy);
    return ADD(MUL(ADD(MUL(a, vy), MUL(b, uy)), SUB(1.0f, ux)), MUL(ADD(MUL(c, vy), MUL(d, uy)), ux));
}
// generator._fbm (gain 0.5): 0.0 + 1.0 * n0 + 0.5 * n1 ..., then / norm (a multiply by the fp32 1 / norm)
__device__ __forceinline__ float rd_fbm(float ci, float cj, const float* invp, const u32* bases, int oct, float inv_norm) {
    float tot = ADD(rd_vnoise(ci, cj, invp[0], bases[0]), 0.0f);
    float amp = 0.5f;
    for (int o = 1; o < oct; ++o) {
        tot = ADD(tot, MUL(amp, rd_vnoise(ci, cj, invp[o], bases[o])));
        amp = MUL(amp, 0.5f);
    }
    return MUL(tot, inv_norm);
}
#define RD_MAXA 8
struct RedArgs {
    // inputs / outputs
    const float* ci;
    const float* cj;
    long long* nodes;        // (2, N) n0i, n0j
    float* frac;             // (2, N) x - x0, y - y0
    long long* box_ext;      // (4) min n0i, min n0j, max n0i, max n0j
    float* box;              // (hi, hj) votes +-1
    bool* out;
    long long n;
    long long bi, bj;
    int hi, hj;
    // tables
    u32 warp_i_bases[4], warp_j_bases[4], jit_bases[4], zone_bases[RD_MAXA];
    float warp_invp[4], jit_invp[4];
    float zone_invp, inv_warp_norm, inv_jit_norm, zone_inv_norm;
    u32 red_base;
    float red_invp, threshold, softness, warp_cells, jitter, inv_lattice, inv6;
    float logp[RD_MAXA];
    int n_arch, warp_oct, jit_oct, zone_oct, max_steps, lattice, single;
};
__device__ __forceinline__ float rd_dryness(const RedArgs& a, float ci, float cj) {
    float x[RD_MAXA];
    float mx = -3.4028234663852886e+38f;
    for (int k = 0; k < a.n_arch; ++k) {
        x[k] = MUL(a.softness, rd_fbm(ci, cj, &a.zone_invp, &a.zone_bases[k], 1, a.zone_inv_norm));
        mx = mx < x[k] ? x[k] : mx;
    }
    float sum = 0.0f;
    for (int k = 0; k < a.n_arch; ++k) sum = ADD(sum, expf(SUB(x[k], mx)));
    float acc[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    for (int k = 0; k < a.n_arch; ++k) acc[k & 3] = ADD(acc[k & 3], MUL(DIV(expf(SUB(x[k], mx)), sum), a.logp[k]));
    return ADD(ADD(ADD(acc[0], acc[1]), acc[2]), acc[3]);
}
// per point: warped lattice position (node, fraction)
extern "C" __global__ void controls_red_prep(const RedArgs a) {
    const i64 i = gtid();
    if (i >= a.n) return;
    const float ci = a.ci[i], cj = a.cj[i];
    const float wi = ADD(ci, MUL(a.warp_cells, rd_fbm(ci, cj, a.warp_invp, a.warp_i_bases, a.warp_oct, a.inv_warp_norm)));
    const float wj = ADD(cj, MUL(a.warp_cells, rd_fbm(ci, cj, a.warp_invp, a.warp_j_bases, a.warp_oct, a.inv_warp_norm)));
    const float x = MUL(wi, a.inv_lattice), y = MUL(wj, a.inv_lattice);
    const float x0 = floorf(x), y0 = floorf(y);
    a.nodes[i] = (i64)x0;
    a.nodes[a.n + i] = (i64)y0;
    a.frac[i] = SUB(x, x0);
    a.frac[a.n + i] = SUB(y, y0);
}
// min / max of the node indices (one block): the host sizes the node box from them (generator: int(n0i.min()) ...)
extern "C" __global__ void controls_red_extent(const RedArgs a) {
    __shared__ i64 s[4][256];
    i64 v[4] = {0x7fffffffffffffffLL, 0x7fffffffffffffffLL, -0x7fffffffffffffffLL - 1, -0x7fffffffffffffffLL - 1};
    for (i64 i = threadIdx.x; i < a.n; i += blockDim.x) {
        const i64 x = a.nodes[i], y = a.nodes[a.n + i];
        v[0] = x < v[0] ? x : v[0];
        v[1] = y < v[1] ? y : v[1];
        v[2] = x > v[2] ? x : v[2];
        v[3] = y > v[3] ? y : v[3];
    }
    for (int q = 0; q < 4; ++q) s[q][threadIdx.x] = v[q];
    __syncthreads();
    for (int o = blockDim.x / 2; o > 0; o >>= 1) {
        if ((int)threadIdx.x < o) {
            const int t = threadIdx.x;
            s[0][t] = s[0][t + o] < s[0][t] ? s[0][t + o] : s[0][t];
            s[1][t] = s[1][t + o] < s[1][t] ? s[1][t + o] : s[1][t];
            s[2][t] = s[2][t + o] > s[2][t] ? s[2][t + o] : s[2][t];
            s[3][t] = s[3][t + o] > s[3][t] ? s[3][t + o] : s[3][t];
        }
        __syncthreads();
    }
    if (threadIdx.x == 0)
        for (int q = 0; q < 4; ++q) a.box_ext[q] = s[q][0];
}
// per node of the box: walk downhill in the dryness field (offset 0 first: ties stay put), then the red vote
extern "C" __global__ void controls_red_walk(const RedArgs a) {
    const i64 i = gtid();
    if (i >= (i64)a.hi * a.hj) return;
    i64 pi = a.bi + i / a.hj, pj = a.bj + i % a.hj;
    const int oi[9] = {0, -1, -1, -1, 0, 0, 1, 1, 1};
    const int oj[9] = {0, -1, 0, 1, -1, 1, -1, 0, 1};
    for (int step = 0; step < a.max_steps; ++step) {
        float best = 0.0f;
        int bk = 0;
        for (int k = 0; k < 9; ++k) {
            const float v = rd_dryness(a, __ll2float_rn((pi + oi[k]) * a.lattice), __ll2float_rn((pj + oj[k]) * a.lattice));
            if (k == 0 || v < best || (v != v && best == best)) {
                best = v;
                bk = k;
            }
        }
        if (bk == 0) break;
        pi += oi[bk];
        pj += oj[bk];
    }
    const float v = rd_vnoise(__ll2float_rn(pi * a.lattice), __ll2float_rn(pj * a.lattice), a.red_invp, a.red_base);
    a.box[i] = v > a.threshold ? 1.0f : -1.0f;
}
// per point: B-spline blend of the 4 x 4 node votes (sum over (0, 1) like torch: 4 accumulators, or the 16-lane
// warp tree when there is a single point) + jitter > 0
__device__ __forceinline__ void rd_bspline(float t, const RedArgs& a, float* w) {
    const float t2 = MUL(t, t), t3 = MUL(MUL(t, t), t);
    const float om = SUB(1.0f, t);
    w[0] = MUL(MUL(MUL(om, om), om), a.inv6);
    w[1] = MUL(ADD(SUB(MUL(3.0f, t3), MUL(6.0f, t2)), 4.0f), a.inv6);
    w[2] = MUL(ADD(ADD(ADD(MUL(-3.0f, t3), MUL(3.0f, t2)), MUL(3.0f, t)), 1.0f), a.inv6);
    w[3] = MUL(t3, a.inv6);
}
extern "C" __global__ void controls_red_blend(const RedArgs a) {
    const i64 i = gtid();
    if (i >= a.n) return;
    float wx[4], wy[4];
    rd_bspline(a.frac[i], a, wx);
    rd_bspline(a.frac[a.n + i], a, wy);
    const i64 r0 = a.nodes[i] - a.bi, c0 = a.nodes[a.n + i] - a.bj;
    float p[16];
    for (int q = 0; q < 4; ++q)
        for (int s = 0; s < 4; ++s)
            p[q * 4 + s] = MUL(MUL(a.box[(r0 - 1 + q) * a.hj + (c0 - 1 + s)], wx[q]), wy[s]);
    float sum;
    if (a.single) {   // contiguous 16-element reduction: warp shuffle tree, offsets 8, 4, 2, 1
        float v[16];
        for (int q = 0; q < 16; ++q) v[q] = ADD(0.0f, p[q]);
        for (int o = 8; o > 0; o >>= 1)
            for (int q = 0; q < o; ++q) v[q] = ADD(v[q], v[q + o]);
        sum = v[0];
    } else {
        float acc[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        for (int q = 0; q < 16; ++q) acc[q & 3] = ADD(acc[q & 3], p[q]);
        sum = ADD(ADD(ADD(acc[0], acc[1]), acc[2]), acc[3]);
    }
    const float ci = a.ci[i], cj = a.cj[i];
    const float blend = ADD(sum, MUL(a.jitter, rd_fbm(ci, cj, a.jit_invp, a.jit_bases, a.jit_oct, a.inv_jit_norm)));
    a.out[i] = blend > 0.0f;
}
