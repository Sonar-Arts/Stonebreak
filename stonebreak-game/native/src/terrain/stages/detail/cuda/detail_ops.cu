// Stage module "detail_ops": the detail / SR / carve kernels of stages/detail (generator._detail_window & co,
// generator2 SR-30 / SR-15, tiles.TileBuilder._carve + world/carve_node.py). Bit-parity rules: core/cuda/sbtg_core.cu
// (one IEEE fp32 op per torch op, in torch's order; __f*_rn so NVRTC never contracts; "/ python scalar" = * fp32
// reciprocal from the host; torch.clamp & co keep NaN). Each kernel takes ONE struct by value; host twins in
// stages/detail/detail.cpp.
typedef unsigned int u32;

#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn

__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) { return x != x ? x : (x < lo ? lo : (x > hi ? hi : x)); }
__device__ __forceinline__ float t_clamp_min(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_clamp_max(float x, float hi) { return x != x ? x : (x > hi ? hi : x); }
__device__ __forceinline__ int clampi(int i, int lo, int hi) { return i < lo ? lo : (i > hi ? hi : i); }

// generator._fade / generator2._sr_blend: w2 = wv[:, None] * wv[None, :]
struct OuterArgs {
    const float* v;
    float* o;
    int n;
};
extern "C" __global__ void dk_outer(const OuterArgs a) {
    long long i = gtid();
    if (i >= (long long)a.n * a.n) return;
    int y = (int)(i / a.n), x = (int)(i - (long long)y * a.n);
    a.o[i] = MUL(a.v[y], a.v[x]);
}

// The guard's fallback: base + zeros_like(base) (+0: a -0 base becomes +0, as in torch).
struct AddZeroArgs {
    const float* base;
    float* out;
    long long n;
};
extern "C" __global__ void dk_add_zero(const AddZeroArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    a.out[i] = ADD(a.base[i], 0.0f);
}

// max |x.nan_to_num(1e9)| as uint bits (non-negative floats order like their bits) -- the guard's warning only.
struct AbsMaxArgs {
    const float* x;
    u32* mx;
    long long n;
};
extern "C" __global__ void dk_absmax(const AbsMaxArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float v = a.x[i];
    if (v != v) v = 1e9f;
    else if (isinf(v)) v = 3.4028234663852886e38f;
    atomicMax(a.mx, __float_as_uint(fabsf(v)));
}

// G._smoothstep: x = clamp(x, 0, 1); x * x * (3 - 2 * x)
__device__ __forceinline__ float smoothstep(float x) {
    x = t_clamp(x, 0.0f, 1.0f);
    return MUL(MUL(x, x), SUB(3.0f, MUL(2.0f, x)));
}

// generator2._detail_window's lowland boost: regional = blur(win); d = win - regional;
// low = 1 - ss((regional - TOP0) / SPAN); land = ss(regional / 5); gain = 1 + B * exp(-(d / D0) ** 2) * low * land;
// regional + d * gain
struct LowlandArgs {
    const float* win;
    const float* reg;
    float* out;
    long long n;
    float boost, top0, inv_span, inv5, inv_d0;
};
extern "C" __global__ void dk_lowland(const LowlandArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float r = a.reg[i];
    float d = SUB(a.win[i], r);
    float low = SUB(1.0f, smoothstep(MUL(SUB(r, a.top0), a.inv_span)));
    float land = smoothstep(MUL(r, a.inv5));
    float q = MUL(d, a.inv_d0);
    float e = expf(-MUL(q, q));
    float gain = ADD(1.0f, MUL(MUL(MUL(a.boost, e), low), land));
    a.out[i] = ADD(r, MUL(d, gain));
}

// synth/noise.white_noise
__device__ __forceinline__ u32 lowbias32(u32 x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
struct NoiseArgs {
    float* o;
    long long i0, j0;
    int h, w;
    u32 base;
    float sqrt3;
};
extern "C" __global__ void dk_white_noise(const NoiseArgs a) {
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
    a.o[i] = MUL(SUB(acc, 2.0f), a.sqrt3);
}

// ---------------------------------------------------------------------------------------------------- SR
// F.interpolate(x, scale_factor=2, mode="bicubic", align_corners=False) of one (h, w) plane (torch's
// upsample_bicubic2d: src = 0.5 (dst + 0.5) - 0.5, floor, t; A = -0.75; clamped taps; x first, then y).
__device__ __forceinline__ void cubic_coeffs(float t, float c[4]) {
    const float A = -0.75f;
    float x1 = t;
    float x2 = SUB(1.0f, t);
    float u = ADD(x1, 1.0f);
    c[0] = SUB(MUL(ADD(MUL(SUB(MUL(A, u), MUL(5.0f, A)), u), MUL(8.0f, A)), u), MUL(4.0f, A));
    c[1] = ADD(MUL(MUL(SUB(MUL(ADD(A, 2.0f), x1), ADD(A, 3.0f)), x1), x1), 1.0f);
    c[2] = ADD(MUL(MUL(SUB(MUL(ADD(A, 2.0f), x2), ADD(A, 3.0f)), x2), x2), 1.0f);
    float v = ADD(x2, 1.0f);
    c[3] = SUB(MUL(ADD(MUL(SUB(MUL(A, v), MUL(5.0f, A)), v), MUL(8.0f, A)), v), MUL(4.0f, A));
}
// fm 2 = torch's (nvcc contracts x0 c0 + x1 c1 into fma(x0, c0, x1 c1), then fuses x2 c2 and x3 c3; measured
// against F.interpolate on sm_120); 0 plain and 1 the other contraction kept for re-measuring.
__device__ __forceinline__ float cubic_interp(float x0, float x1, float x2, float x3, const float c[4], int fm) {
    if (fm == 1) return fmaf(x3, c[3], fmaf(x2, c[2], fmaf(x1, c[1], MUL(x0, c[0]))));
    if (fm == 2) return fmaf(x3, c[3], fmaf(x2, c[2], fmaf(x0, c[0], MUL(x1, c[1]))));
    return ADD(ADD(ADD(MUL(x0, c[0]), MUL(x1, c[1])), MUL(x2, c[2])), MUL(x3, c[3]));
}
struct Bicubic2Args {
    const float* src;
    float* dst;
    int h, w, fm;
};
extern "C" __global__ void dk_bicubic2(const Bicubic2Args a) {
    long long i = gtid();
    const int H = 2 * a.h, W = 2 * a.w;
    if (i >= (long long)H * W) return;
    int Y = (int)(i / W), X = (int)(i - (long long)Y * W);
    float sy = SUB(MUL(0.5f, ADD((float)Y, 0.5f)), 0.5f), sx = SUB(MUL(0.5f, ADD((float)X, 0.5f)), 0.5f);
    int iy = (int)floorf(sy), ix = (int)floorf(sx);
    float cy[4], cx[4];
    cubic_coeffs(SUB(sy, (float)iy), cy);
    cubic_coeffs(SUB(sx, (float)ix), cx);
    int xs[4];
    for (int k = 0; k < 4; ++k) xs[k] = clampi(ix - 1 + k, 0, a.w - 1);
    float r[4];
    for (int k = 0; k < 4; ++k) {
        const float* row = a.src + (long long)clampi(iy - 1 + k, 0, a.h - 1) * a.w;
        r[k] = cubic_interp(row[xs[0]], row[xs[1]], row[xs[2]], row[xs[3]], cx, a.fm);
    }
    a.dst[i] = cubic_interp(r[0], r[1], r[2], r[3], cy, a.fm);
}

// models/sr.build_cond(up, px) cropped to [o, o + E)^2 of the (U, U) upsample: rel = (up - avg_pool(pad_rep(up, R),
// 2R+1)) / REL; slope = central differences (replicate) / (2 px); climate (4) and flag zeros. (8, E, E).
struct SrCondArgs {
    const float* up;
    float* cond;
    int U, E, o, R;
    float inv_rel, inv_2px, area;
};
extern "C" __global__ void dk_sr_cond(const SrCondArgs a) {
    long long i = gtid();
    const long long ee = (long long)a.E * a.E;
    if (i >= ee) return;
    int y = (int)(i / a.E), x = (int)(i - (long long)y * a.E);
    int Y = y + a.o, X = x + a.o;
    const float* u = a.up;
    const int U = a.U;
    float s = 0.0f;
    for (int dy = -a.R; dy <= a.R; ++dy) {
        const float* row = u + (long long)clampi(Y + dy, 0, U - 1) * U;
        for (int dx = -a.R; dx <= a.R; ++dx) s = ADD(s, row[clampi(X + dx, 0, U - 1)]);
    }
    float pool = DIV(s, a.area);        // torch's avg_pool2d: sum / (k * k), a true division
    float c = u[(long long)Y * U + X];
    a.cond[i] = MUL(SUB(c, pool), a.inv_rel);
    float gx = SUB(u[(long long)Y * U + clampi(X + 1, 0, U - 1)], u[(long long)Y * U + clampi(X - 1, 0, U - 1)]);
    float gy = SUB(u[(long long)clampi(Y + 1, 0, U - 1) * U + X], u[(long long)clampi(Y - 1, 0, U - 1) * U + X]);
    a.cond[ee + i] = MUL(gx, a.inv_2px);
    a.cond[2 * ee + i] = MUL(gy, a.inv_2px);
    for (int k = 3; k < 8; ++k) a.cond[k * ee + i] = 0.0f;
}

// models/sr.assemble(coarse, x * gain): fine = upsample(coarse) + ASINH * sinh(x * gain); err = avg_pool2(fine) - coarse;
// fine - nearest(err). One thread per 2x2 block; `up` = the bicubic upsample of `coarse` (2h, 2w).
struct SrAsmArgs {
    const float* coarse;
    const float* up;
    const float* x;
    float* out;
    int h, w;
    float gain, asinh_m;
};
extern "C" __global__ void dk_sr_assemble(const SrAsmArgs a) {
    long long i = gtid();
    if (i >= (long long)a.h * a.w) return;
    int by = (int)(i / a.w), bx = (int)(i - (long long)by * a.w);
    const int W = 2 * a.w;
    float f[4];
    long long idx[4];
    for (int k = 0; k < 4; ++k) {
        idx[k] = (long long)(2 * by + (k >> 1)) * W + 2 * bx + (k & 1);
        float xv = MUL(a.x[idx[k]], a.gain);
        f[k] = ADD(a.up[idx[k]], MUL(a.asinh_m, sinhf(xv)));
    }
    float s = ADD(ADD(ADD(f[0], f[1]), f[2]), f[3]);
    float err = SUB(DIV(s, 4.0f), a.coarse[i]);
    for (int k = 0; k < 4; ++k) a.out[idx[k]] = SUB(f[k], err);
}

// generator.native_fine's stand-in: up (2h, 2w) bilinear of px (h, w); err = avg_pool2(up) - px; up - nearest(err).
struct FineFixArgs {
    const float* up;
    const float* px;
    float* out;
    int h, w;
};
extern "C" __global__ void dk_fine_fix(const FineFixArgs a) {
    long long i = gtid();
    if (i >= (long long)a.h * a.w) return;
    int by = (int)(i / a.w), bx = (int)(i - (long long)by * a.w);
    const int W = 2 * a.w;
    long long idx[4];
    float f[4];
    for (int k = 0; k < 4; ++k) {
        idx[k] = (long long)(2 * by + (k >> 1)) * W + 2 * bx + (k & 1);
        f[k] = a.up[idx[k]];
    }
    float err = SUB(DIV(ADD(ADD(ADD(f[0], f[1]), f[2]), f[3]), 4.0f), a.px[i]);
    for (int k = 0; k < 4; ++k) a.out[idx[k]] = SUB(f[k], err);
}

// ---------------------------------------------------------------------------------------------------- carve
// x.repeat_interleave(f, 0).repeat_interleave(f, 1)[oi : oi + H, oj : oj + W] of a (hc, wc) cell field.
struct CellUpArgs {
    const float* cell;
    float* out;
    int wc, H, W, oi, oj, f;
};
extern "C" __global__ void dk_cell_up(const CellUpArgs a) {
    long long i = gtid();
    if (i >= (long long)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (long long)y * a.W);
    a.out[i] = a.cell[(long long)((a.oi + y) / a.f) * a.wc + (a.oj + x) / a.f];
}

// tiles._carve's aridity without the climate node: precip / (300 + 45 * t0.clamp_min(0))
struct AridArgs {
    const float* precip;
    const float* t0;
    float* out;
    long long n;
};
extern "C" __global__ void dk_arid(const AridArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    a.out[i] = DIV(a.precip[i], ADD(300.0f, MUL(45.0f, t_clamp_min(a.t0[i], 0.0f))));
}

// k x k stride-1 max pool of a transformed input, torch's loop (row-major window, `v > m || isnan(v)`, from -inf):
//   edge 0: replicate-padded input (F.pad(mode="replicate") then max_pool2d), 1: padding r (out of range skipped)
//   in 0: x, 1: -x, 2: (x < 0) as 0 / 1, 3: x.clamp_min(0), 4: where(s, c, 0) of two planes (s = x, c = x2)
//   neg: output -max (the closing's second pass); vd: output (that - z).clamp_min(0) with z = x2 (valley depth)
struct PoolArgs {
    const float* x;
    const float* x2;
    float* out;
    int H, W, k, edge, in, neg, vd;
};
__device__ __forceinline__ float pool_in(const PoolArgs& a, long long j) {
    float v = a.x[j];
    switch (a.in) {
    case 1: return -v;
    case 2: return v < 0.0f ? 1.0f : 0.0f;
    case 3: return t_clamp_min(v, 0.0f);
    case 4: return v != 0.0f ? a.x2[j] : 0.0f;
    default: return v;
    }
}
extern "C" __global__ void dk_pool(const PoolArgs a) {
    long long i = gtid();
    if (i >= (long long)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (long long)y * a.W);
    const int r = a.k / 2;
    float m = -__int_as_float(0x7f800000);
    for (int dy = -r; dy <= r; ++dy) {
        int yy = y + dy;
        if (a.edge == 1 && (yy < 0 || yy >= a.H)) continue;
        yy = clampi(yy, 0, a.H - 1);
        for (int dx = -r; dx <= r; ++dx) {
            int xx = x + dx;
            if (a.edge == 1 && (xx < 0 || xx >= a.W)) continue;
            xx = clampi(xx, 0, a.W - 1);
            float v = pool_in(a, (long long)yy * a.W + xx);
            if (v > m || v != v) m = v;
        }
    }
    if (a.neg) m = -m;
    if (a.vd) m = t_clamp_min(SUB(m, a.x2[i]), 0.0f);
    a.out[i] = m;
}

// carve_labels.strike's inputs: torch.gradient(vd) (central / 2 inside, one-sided at the edges) -> gx*gx, gy*gy, gx*gy
struct GradArgs {
    const float* vd;
    float* out;     // (3, H, W)
    int H, W;
};
extern "C" __global__ void dk_grad(const GradArgs a) {
    long long i = gtid();
    const long long hw = (long long)a.H * a.W;
    if (i >= hw) return;
    int y = (int)(i / a.W), x = (int)(i - (long long)y * a.W);
    const float* v = a.vd;
    float gy, gx;
    if (y == 0) gy = SUB(v[i + a.W], v[i]);
    else if (y == a.H - 1) gy = SUB(v[i], v[i - a.W]);
    else gy = DIV(SUB(v[i + a.W], v[i - a.W]), 2.0f);
    if (x == 0) gx = SUB(v[i + 1], v[i]);
    else if (x == a.W - 1) gx = SUB(v[i], v[i - 1]);
    else gx = DIV(SUB(v[i + 1], v[i - 1]), 2.0f);
    a.out[i] = MUL(gx, gx);
    a.out[hw + i] = MUL(gy, gy);
    a.out[2 * hw + i] = MUL(gx, gy);
}

// carve_node: shore = (pool(sea, 1) > 0.5) & (sea < 0.5); steep = shore & (cliff >= CLIFF_M) -> steep (0/1) and
// where(steep, cliff, 0)
struct SteepArgs {
    const float* z;
    const float* seapool;
    const float* cliff;
    float* steep;
    float* steep_cliff;
    long long n;
    float cliff_m;
};
extern "C" __global__ void dk_steep(const SteepArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float sea = a.z[i] < 0.0f ? 1.0f : 0.0f;
    float c = a.cliff[i];
    bool st = a.seapool[i] > 0.5f && sea < 0.5f && c >= a.cliff_m;
    a.steep[i] = st ? 1.0f : 0.0f;
    a.steep_cliff[i] = st ? c : 0.0f;
}

// world/carve_node.carve_codes' rules at the gathered samples + tiles._carve's per-block gather -> int64 codes.
struct CarveArgs {
    const float* z;          // h60 (H, W), margin included
    const float* vd150;
    const float* vd600;
    const float* vd2000;
    const float* J;          // (3, H, W) blurred gx*gx, gy*gy, gx*gy
    const float* arid;
    const float* river;      // null: no drainage
    const float* fjord;      // null: no gates
    const float* karst;      // null: no karst source
    const float* zmax;       // relief pools (karst only)
    const float* nzmax;
    const float* near;       // pool(steep, reach)
    const float* expo;       // pool(where(steep, cliff, 0), reach)
    long long* out;          // (n_rows, n_cols)
    long long i1, j1, s1, t1;
    int mode, per, d, ds;    // tiles._carve's sample_of: mode 0 = (i * d) // ds, 1 = i // per
    int H, W, n_rows, n_cols;
    float d0, inv_dspan, slot, arid_max, gorge, rift, rift_coh, humid, tower_m, inv_cliff_dens, pi, inv_pi, eps;
};
__device__ __forceinline__ long long floordiv(long long a, long long b) { long long q = a / b; return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q; }
__device__ __forceinline__ long long sample_of(const CarveArgs& a, long long i) {
    return a.mode == 0 ? floordiv(i * a.d, a.ds) : floordiv(i, a.per);
}
extern "C" __global__ void dk_carve(const CarveArgs a) {
    long long q = gtid();
    if (q >= (long long)a.n_rows * a.n_cols) return;
    int rr = (int)(q / a.n_cols), cc = (int)(q - (long long)rr * a.n_cols);
    long long i = (long long)(sample_of(a, a.i1 + rr) - a.s1) * a.W + (sample_of(a, a.j1 + cc) - a.t1);
    const long long hw = (long long)a.H * a.W;
    float jxx = a.J[i], jyy = a.J[hw + i], jxy = a.J[2 * hw + i];
    float tr = ADD(jxx, jyy);
    float dd = SUB(jxx, jyy);
    float sq = sqrtf(ADD(MUL(dd, dd), MUL(4.0f, MUL(jxy, jxy))));
    float coh = DIV(sq, t_clamp_min(tr, a.eps));
    float den = t_clamp_min(sq, a.eps);
    float c2 = MUL(DIV(-dd, den), coh);
    float s2 = MUL(DIV(-MUL(2.0f, jxy), den), coh);
    float coh2 = sqrtf(ADD(MUL(c2, c2), MUL(s2, s2)));
    float angle = MUL(0.5f, atan2f(s2, c2));
    float md = fmodf(angle, a.pi);
    if (md != 0.0f && ((a.pi < 0.0f) != (md < 0.0f))) md = ADD(md, a.pi);
    long long strike = (long long)rintf(MUL(MUL(md, a.inv_pi), 16.0f));
    strike = ((strike % 16) + 16) % 16;
    float v6 = a.vd600[i], v2k = a.vd2000[i], z = a.z[i], ar = a.arid[i];
    float density = t_clamp(MUL(MUL(SUB(v6, a.d0), a.inv_dspan), 15.0f), 0.0f, 15.0f);
    int style = 0;
    if (a.vd150[i] > a.slot && ar < a.arid_max) style = 1;
    if (a.river != 0 && a.river[i] > 0.5f && v6 > a.d0 && v2k > a.gorge) style = 2;
    if (v2k > a.rift && coh2 > a.rift_coh) style = 3;
    if (a.karst != 0) {
        bool k = a.karst[i] > 0.5f;
        if (k) style = 4;
        float relief = ADD(a.zmax[i], a.nzmax[i]);
        if (k && ar >= a.humid && relief >= a.tower_m) style = 7;
    }
    if (a.fjord != 0) density = MUL(density, SUB(1.0f, MUL(0.8f, a.fjord[i])));
    bool near = a.near[i] > 0.5f && z > 0.0f;
    if (near) {
        style = 6;
        density = t_clamp(MUL(a.expo[i], a.inv_cliff_dens), 1.0f, 15.0f);
    }
    if (z <= 0.0f) density = 0.0f;
    float depth = MUL(t_clamp_max(v2k, 1000.0f), 0.03125f);
    int di = (int)t_clamp(rintf(density), 0.0f, 15.0f);
    int dp = (int)t_clamp(rintf(depth), 0.0f, 31.0f);
    long long st = strike < 0 ? 0 : (strike > 15 ? 15 : strike);
    a.out[q] = (long long)(di | (style << 4) | ((int)st << 7) | (dp << 11));
}

// ---------------------------------------------------------------------------------------------------- FX4 block look
// generator2._hill_band: win + gain * ((blur(win, s0) - blur(win, s1)) * fade), fade = (1 - ss((reg - TOP0) / SPAN))
// * ss((reg - C0) / CSPAN), reg = blur(win, sreg)
struct HillBandArgs {
    const float* win;
    const float* b0;
    const float* b1;
    const float* reg;
    float* out;
    long long n;
    float gain, top0, inv_top, c0, inv_c;
};
extern "C" __global__ void dk_hill_band(const HillBandArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float r = a.reg[i];
    float band = SUB(a.b0[i], a.b1[i]);
    float fade = MUL(SUB(1.0f, smoothstep(MUL(SUB(r, a.top0), a.inv_top))), smoothstep(MUL(SUB(r, a.c0), a.inv_c)));
    a.out[i] = ADD(a.win[i], MUL(a.gain, MUL(band, fade)));
}

// generator2._sr_gain_look's first line: fine + (gain - 1) * (fine - blur(fine, SR_LOOK_SIGMA_PX))
struct SrLookArgs {
    const float* fine;
    const float* lp;
    float* out;
    long long n;
    float k;
};
extern "C" __global__ void dk_sr_look(const SrLookArgs a) {
    long long i = gtid();
    if (i >= a.n) return;
    float f = a.fine[i];
    a.out[i] = ADD(f, MUL(a.k, SUB(f, a.lp[i])));
}
