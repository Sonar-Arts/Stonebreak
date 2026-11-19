// Stage module "river_ops": the river pipeline (terrain_slm/river/*) and river_field's swamp flattening, torch-exact.
// Bit-parity rules: core/cuda/sbtg_core.cu (one IEEE op per torch op, in torch's order; "/ python scalar" = * fp32
// reciprocal; torch.clamp / minimum / maximum keep NaN). The fixed-point loops of monotone_downstream and the
// centreline growth run the DaedalusTGM kernels themselves (module csrc_river). Masks are bytes (0 / 1).
#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn

typedef unsigned char u8;
typedef unsigned int u32;
typedef long long i64;

#define F_INF __int_as_float(0x7f800000)
#define F_NAN __int_as_float(0x7fc00000)

__device__ __forceinline__ i64 gtid() { return (i64)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ bool isnanf_(float x) { return x != x; }
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) {
    if (x != x) return x;
    return x < lo ? lo : (x > hi ? hi : x);
}
__device__ __forceinline__ float t_clamp_min(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_minimum(float a, float b) {
    if (a != a) return a;
    if (b != b) return b;
    return fminf(a, b);
}
__device__ __forceinline__ float t_maximum(float a, float b) {
    if (a != a) return a;
    if (b != b) return b;
    return fmaxf(a, b);
}
// torch max_pool2d accumulation: start at -inf, take val if val > max or val is NaN.
__device__ __forceinline__ float t_poolmax(float m, float v) { return (v > m || v != v) ? v : m; }
// torch.round (nearbyint, half to even), torch.remainder(x, 8.0) for floats, then .long().
__device__ __forceinline__ i64 octant_of(float a, float inv_q) {
    float r = rintf(MUL(a, inv_q));
    float mod = fmodf(r, 8.0f);
    if (mod != 0.0f && ((mod < 0.0f) != (8.0f < 0.0f))) mod = ADD(mod, 8.0f);
    return (i64)mod;
}

// ============================================================================== swamp flattening (swamp_relief.flatten)
// where(h >= 0, h + w * (t - h), h)
struct FlattenArgs {
    const float* h;
    const float* w;
    const float* t;
    float* out;
    i64 n;
};
extern "C" __global__ void rv_swamp_flatten(const FlattenArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float h = a.h[i];
    a.out[i] = h >= 0.0f ? ADD(h, MUL(a.w[i], SUB(a.t[i], h))) : h;
}
// weight > 0 anywhere -> *flag = 1
struct AnyArgs {
    const float* x;
    int* flag;
    i64 n;
};
extern "C" __global__ void rv_any_pos(const AnyArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    if (a.x[i] > 0.0f) *a.flag = 1;
}
struct AnyU8Args {
    const u8* x;
    int* flag;
    i64 n;
};
extern "C" __global__ void rv_any_u8(const AnyU8Args a) {
    i64 i = gtid();
    if (i >= a.n) return;
    if (a.x[i]) *a.flag = 1;
}

// ============================================================================== Centrelines
// ridge_lines on s = blur(up): Hessian NMS across the ridge (grid_sample, border, align_corners), weak / line masks.
struct RidgeArgs {
    const float* s;
    const float* up;
    u8* weak;       // weak ridge (>= thr_lo)
    float* grown;   // line (>= thr) as 0 / 1 floats
    float* wk;      // weak as 0 / 1 floats
    int H, W;
    float thr_lo, thr, inv_w, inv_h, half_pi;
};
__device__ __forceinline__ float rd(const float* s, int y, int x, int H, int W) {
    y = y < 0 ? 0 : (y >= H ? H - 1 : y);
    x = x < 0 ? 0 : (x >= W ? W - 1 : x);
    return s[(i64)y * W + x];
}
// F.grid_sample(s, grid, bilinear, border, align_corners=True) at grid (gx, gy), torch's CUDA kernel.
__device__ __forceinline__ float grid_border(const float* s, float gx, float gy, int H, int W) {
    float ix = MUL(MUL(ADD(gx, 1.0f), 0.5f), (float)(W - 1));
    float iy = MUL(MUL(ADD(gy, 1.0f), 0.5f), (float)(H - 1));
    ix = fminf((float)(W - 1), fmaxf(ix, 0.0f));
    iy = fminf((float)(H - 1), fmaxf(iy, 0.0f));
    if (!isfinite(ix) || ix > 2147483646.0f || ix < -2147483646.0f) ix = -100.0f;
    if (!isfinite(iy) || iy > 2147483646.0f || iy < -2147483646.0f) iy = -100.0f;
    int x0 = (int)floorf(ix), y0 = (int)floorf(iy);
    int x1 = x0 + 1, y1 = y0 + 1;
    float nw = MUL(SUB((float)x1, ix), SUB((float)y1, iy));
    float ne = MUL(SUB(ix, (float)x0), SUB((float)y1, iy));
    float sw = MUL(SUB((float)x1, ix), SUB(iy, (float)y0));
    float se = MUL(SUB(ix, (float)x0), SUB(iy, (float)y0));
    float acc = 0.0f;
    if (y0 >= 0 && y0 < H && x0 >= 0 && x0 < W) acc = fmaf(s[(i64)y0 * W + x0], nw, acc);
    if (y0 >= 0 && y0 < H && x1 >= 0 && x1 < W) acc = fmaf(s[(i64)y0 * W + x1], ne, acc);
    if (y1 >= 0 && y1 < H && x0 >= 0 && x0 < W) acc = fmaf(s[(i64)y1 * W + x0], sw, acc);
    if (y1 >= 0 && y1 < H && x1 >= 0 && x1 < W) acc = fmaf(s[(i64)y1 * W + x1], se, acc);
    return acc;
}
extern "C" __global__ void rv_ridge(const RidgeArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    const float* s = a.s;
    float c = s[i];
    float two_c = MUL(2.0f, c);
    float sxx = ADD(SUB(rd(s, y, x + 1, a.H, a.W), two_c), rd(s, y, x - 1, a.H, a.W));
    float syy = ADD(SUB(rd(s, y + 1, x, a.H, a.W), two_c), rd(s, y - 1, x, a.H, a.W));
    float sxy = MUL(0.25f, ADD(SUB(SUB(rd(s, y + 1, x + 1, a.H, a.W), rd(s, y + 1, x - 1, a.H, a.W)),
                                   rd(s, y - 1, x + 1, a.H, a.W)), rd(s, y - 1, x - 1, a.H, a.W)));
    float hd = MUL(0.5f, SUB(sxx, syy));
    float lam = SUB(MUL(0.5f, ADD(sxx, syy)), __fsqrt_rn(ADD(MUL(hd, hd), MUL(sxy, sxy))));
    float across = ADD(MUL(0.5f, atan2f(MUL(2.0f, sxy), SUB(sxx, syy))), a.half_pi);
    float co = cosf(across), si = sinf(across);
    float xf = (float)x, yf = (float)y;
    float gxp = SUB(MUL(MUL(ADD(xf, co), a.inv_w), 2.0f), 1.0f);
    float gyp = SUB(MUL(MUL(ADD(yf, si), a.inv_h), 2.0f), 1.0f);
    float gxm = SUB(MUL(MUL(ADD(xf, -co), a.inv_w), 2.0f), 1.0f);
    float gym = SUB(MUL(MUL(ADD(yf, -si), a.inv_h), 2.0f), 1.0f);
    float sp = grid_border(s, gxp, gyp, a.H, a.W);
    float sm = grid_border(s, gxm, gym, a.H, a.W);
    float u = a.up[i];
    bool weak = (c >= sp) && (c >= sm) && (lam < 0.0f) && (u >= a.thr_lo);
    bool line = weak && (u >= a.thr);
    a.weak[i] = weak;
    a.grown[i] = line ? 1.0f : 0.0f;
    a.wk[i] = weak ? 1.0f : 0.0f;
}

// prune_spurs: endpoints of a thin mask (8-ring, clockwise from (-1, -1)).
__device__ __forceinline__ bool endpoint(const u8* m, int y, int x, int H, int W) {
    if (!m[(i64)y * W + x]) return false;
    const int di[8] = {-1, -1, -1, 0, 1, 1, 1, 0};
    const int dj[8] = {-1, 0, 1, 1, 1, 0, -1, -1};
    bool r[8];
    int nb = 0;
#pragma unroll
    for (int k = 0; k < 8; ++k) {
        int yy = y + di[k], xx = x + dj[k];
        r[k] = yy >= 0 && yy < H && xx >= 0 && xx < W && m[(i64)yy * W + xx];
        nb += r[k];
    }
    int runs = 0;
#pragma unroll
    for (int k = 0; k < 8; ++k) runs += (!r[k] && r[(k + 1) & 7]);
    return nb <= 1 || (runs == 1 && nb <= 3);
}
struct MaskArgs {
    const u8* in;
    u8* out;
    int H, W;
};
// kept & ~endpoints(kept)
extern "C" __global__ void rv_peel(const MaskArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    a.out[i] = a.in[i] && !endpoint(a.in, y, x, a.H, a.W);
}
// endpoints(kept)
extern "C" __global__ void rv_tips(const MaskArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    a.out[i] = endpoint(a.in, y, x, a.H, a.W);
}
// tips = max_pool3(tips) * line * (1 - kept); kept |= tips
struct RegrowArgs {
    const u8* tips;
    const u8* kept;
    const float* line;   // grown (0 / 1 floats): the original line is grown > 0
    u8* tips_out;
    u8* kept_out;
    int H, W;
};
extern "C" __global__ void rv_regrow(const RegrowArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    bool m = false;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            int yy = y + dy, xx = x + dx;
            if (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W && a.tips[(i64)yy * a.W + xx]) m = true;
        }
    bool t = m && a.line[i] > 0.0f && !a.kept[i];
    a.tips_out[i] = t;
    a.kept_out[i] = a.kept[i] || t;
}
// grown > 0
struct GtArgs {
    const float* x;
    u8* out;
    i64 n;
};
extern "C" __global__ void rv_gt0(const GtArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    a.out[i] = a.x[i] > 0.0f;
}

// ============================================================================== distance helpers (exact integers)
// Squared Euclidean distance to the nearest set pixel within R (in the frame), else BIG. Row pass: dx^2 of the
// nearest set pixel in the row; column pass: min over dy of row[y + dy] + dy^2.
#define D2_BIG 0x3fffffff
struct EdtArgs {
    const u8* m;
    const int* row;
    int* out;
    int H, W, R;
};
extern "C" __global__ void rv_edt_row(const EdtArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    const u8* r = a.m + (i64)y * a.W;
    int best = D2_BIG;
    for (int d = 0; d <= a.R; ++d) {
        if ((x - d >= 0 && r[x - d]) || (x + d < a.W && r[x + d])) {
            best = d * d;
            break;
        }
    }
    a.out[i] = best;
}
extern "C" __global__ void rv_edt_col(const EdtArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    int best = D2_BIG;
    for (int dy = -a.R; dy <= a.R; ++dy) {
        int yy = y + dy;
        if (yy < 0 || yy >= a.H) continue;
        int v = a.row[(i64)yy * a.W + x];
        if (v < D2_BIG) {
            int d = v + dy * dy;
            if (d < best) best = d;
        }
    }
    a.out[i] = best;
}
// Chebyshev distance to the nearest pixel with mask == target within cap (in the frame), else cap + 1.
struct ChebArgs {
    const u8* m;
    const int* row;
    int* out;
    int H, W, cap, target;
};
extern "C" __global__ void rv_cheb_row(const ChebArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    const u8* r = a.m + (i64)y * a.W;
    int best = a.cap + 1;
    for (int d = 0; d <= a.cap; ++d) {
        if ((x - d >= 0 && (r[x - d] != 0) == (a.target != 0)) || (x + d < a.W && (r[x + d] != 0) == (a.target != 0))) {
            best = d;
            break;
        }
    }
    a.out[i] = best;
}
extern "C" __global__ void rv_cheb_col(const ChebArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    int best = a.cap + 1;
    for (int dy = -a.cap; dy <= a.cap; ++dy) {
        int yy = y + dy;
        if (yy < 0 || yy >= a.H) continue;
        int v = a.row[(i64)yy * a.W + x];
        int ady = dy < 0 ? -dy : dy;
        int d = v > ady ? v : ady;
        if (d < best) best = d;
    }
    a.out[i] = best;
}

// ============================================================================== ChannelGeometry
// (flow - thr).clamp_min(0) * line
struct ExLineArgs {
    const float* flow;
    const u8* line;
    float* out;
    i64 n;
    float thr;
};
extern "C" __global__ void rv_ex_line(const ExLineArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float ex = t_clamp_min(SUB(a.flow[i], a.thr), 0.0f);
    a.out[i] = MUL(ex, a.line[i] ? 1.0f : 0.0f);
}
// max_pool2d(x, 2r + 1, 1, r) one axis at a time (max is separable; torch's NaN rule).
struct MaxSepArgs {
    const float* in;
    float* out;
    int H, W, r, axis;
};
extern "C" __global__ void rv_maxpool_sep(const MaxSepArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    float m = -F_INF;
    for (int d = -a.r; d <= a.r; ++d) {
        int yy = a.axis ? y + d : y, xx = a.axis ? x : x + d;
        if (yy < 0 || yy >= a.H || xx < 0 || xx >= a.W) continue;
        m = t_poolmax(m, a.in[(i64)yy * a.W + xx]);
    }
    a.out[i] = m;
}
// width / half / depth from line_ex; channel = line | (half_px >= kmin(d2 to the line)) for kmin <= max_half.
struct ChannelArgs {
    const float* line_ex;
    const u8* line;
    const int* d2;
    u8* channel;
    float* half_px;
    float* depth;
    i64 n;
    int max_half;
    float wmin, wper, wmax, dmin, dper, dmax, ppb;
};
extern "C" __global__ void rv_channel(const ChannelArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float le = a.line_ex[i];
    float wb = t_clamp(ADD(a.wmin, MUL(a.wper, le)), a.wmin, a.wmax);
    float half = MUL(MUL(wb, a.ppb), 0.5f);
    a.half_px[i] = half;
    a.depth[i] = t_clamp(ADD(a.dmin, MUL(a.dper, le)), a.dmin, a.dmax);
    bool ch = a.line[i] != 0;
    int d2 = a.d2[i];
    if (!ch && d2 < D2_BIG) {
        int k = 1;
        while (k * k + k < d2) ++k;     // the smallest disk radius covering d2
        if (k <= a.max_half && half >= (float)k) ch = true;
    }
    a.channel[i] = ch;
}
// erosion_depth: min(chebyshev distance to a non-channel pixel, cap), 0 outside the channel
struct EdgeArgs {
    const u8* channel;
    const int* cheb;
    float* out;
    i64 n;
    int cap;
};
extern "C" __global__ void rv_edge(const EdgeArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    int c = a.cheb[i];
    a.out[i] = a.channel[i] ? (float)(c < a.cap ? c : a.cap) : 0.0f;
}

// ============================================================================== WaterSurface
// planes (4, H, W): g * lf, lf, g * cf, cf
struct SurfPlanesArgs {
    const float* g;
    const u8* line;
    const u8* channel;
    float* out;
    i64 n;
};
extern "C" __global__ void rv_surf_planes(const SurfPlanesArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float lf = a.line[i] ? 1.0f : 0.0f, cf = a.channel[i] ? 1.0f : 0.0f, g = a.g[i];
    a.out[i] = MUL(g, lf);
    a.out[a.n + i] = lf;
    a.out[2 * a.n + i] = MUL(g, cf);
    a.out[3 * a.n + i] = cf;
}
// num / den.clamp_min(1e-6) for plane pairs (0, 1) -> out0, (2, 3) -> out1 (out1 optional)
struct RatioArgs {
    const float* b;
    float* out0;
    float* out1;
    i64 n;
};
extern "C" __global__ void rv_ratio(const RatioArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    a.out0[i] = DIV(a.b[i], t_clamp_min(a.b[a.n + i], 1e-6f));
    if (a.out1) a.out1[i] = DIV(a.b[2 * a.n + i], t_clamp_min(a.b[3 * a.n + i], 1e-6f));
}
// monotone_downstream (kernels/cuda_river.py) set-up: lv = where(line, level, inf); ups bit k (geometry._N8 order) =
// (shift(fl, k) < fl) & line, fl = where(line, flow, inf), inf past the edge.
struct MonoPrepArgs {
    const float* level;
    const u8* line;
    const float* flow;
    float* lv;
    u8* ups;
    int H, W;
};
extern "C" __global__ void rv_mono_prep(const MonoPrepArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    bool on = a.line[i] != 0;
    a.lv[i] = on ? a.level[i] : F_INF;
    float fl = on ? a.flow[i] : F_INF;
    const int di[8] = {-1, -1, -1, 0, 0, 1, 1, 1};
    const int dj[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
    unsigned u = 0;
    if (on) {
#pragma unroll
        for (int k = 0; k < 8; ++k) {
            int yy = y + di[k], xx = x + dj[k];
            float nf = F_INF;
            if (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W) {
                i64 j = (i64)yy * a.W + xx;
                nf = a.line[j] ? a.flow[j] : F_INF;
            }
            if (nf < fl) u |= 1u << k;
        }
    }
    a.ups[i] = (u8)u;
}
// where(mask, a, b)
struct WhereArgs {
    const u8* m;
    const float* a;
    const float* b;
    float* out;
    i64 n;
};
extern "C" __global__ void rv_where(const WhereArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    a.out[i] = a.m[i] ? a.a[i] : a.b[i];
}
// propagate: v = where(seed, values, 0), done = seed
struct PropInitArgs {
    const float* values;
    const u8* seed;
    float* v;
    float* done;
    i64 n;
};
extern "C" __global__ void rv_prop_init(const PropInitArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    bool s = a.seed[i] != 0;
    a.v[i] = s ? a.values[i] : 0.0f;
    a.done[i] = s ? 1.0f : 0.0f;
}
// one propagate step: num = conv3x3(v * done) and den = conv3x3(done) (ones, zero padding; cuDNN's row-major sum,
// measured), new = (den > 0) & (done == 0) & region
struct PropStepArgs {
    const float* v;
    const float* done;
    const u8* region;
    float* v_out;
    float* done_out;
    int H, W;
};
extern "C" __global__ void rv_prop_step(const PropStepArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    float num = 0.0f, den = 0.0f;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            int yy = y + dy, xx = x + dx;
            float pv = 0.0f, pd = 0.0f;
            if (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W) {
                i64 j = (i64)yy * a.W + xx;
                pd = a.done[j];
                pv = MUL(a.v[j], pd);
            }
            num = ADD(num, pv);
            den = ADD(den, pd);
        }
    float d0 = a.done[i];
    bool nw = den > 0.0f && d0 == 0.0f && a.region[i];
    a.v_out[i] = nw ? DIV(num, t_clamp_min(den, 1e-6f)) : a.v[i];
    a.done_out[i] = nw ? 1.0f : d0;
}
// level0 = where(reached, across, in_chan); planes (2, H, W) = (level0 * cf, cf)
struct Level0Args {
    const float* across;
    const float* done;
    const float* in_chan;
    const u8* channel;
    float* level0;
    float* planes;
    i64 n;
};
extern "C" __global__ void rv_level0(const Level0Args a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float l = a.done[i] > 0.5f ? a.across[i] : a.in_chan[i];
    float cf = a.channel[i] ? 1.0f : 0.0f;
    a.level0[i] = l;
    a.planes[i] = MUL(l, cf);
    a.planes[a.n + i] = cf;
}
// level = where(channel, level0, spread num / den.clamp_min(1e-6))
struct SpreadArgs {
    const float* level0;
    const float* b;
    const u8* channel;
    float* out;
    i64 n;
};
extern "C" __global__ void rv_spread(const SpreadArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    a.out[i] = a.channel[i] ? a.level0[i] : DIV(a.b[i], t_clamp_min(a.b[a.n + i], 1e-6f));
}

// ============================================================================== banks
// band = d2(channel) <= r^2 + r and not channel
struct BandArgs {
    const int* d2;
    const u8* channel;
    u8* band;
    i64 n;
    int r;
};
extern "C" __global__ void rv_band(const BandArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    a.band[i] = a.d2[i] <= a.r * a.r + a.r && !a.channel[i];
}
// bank_inputs set-up: rel = (base - level) / rate; planes (2, H, W) = (rel * known, known); out = rel * known
struct BankRelArgs {
    const float* base;
    const float* level;
    const float* rate;
    const u8* band;
    const u8* channel;
    float* planes;
    float* out;
    u8* done;
    i64 n;
};
extern "C" __global__ void rv_bank_rel(const BankRelArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float rel = DIV(SUB(a.base[i], a.level[i]), a.rate[i]);
    bool known = !(a.band[i] || a.channel[i]);
    float k = known ? 1.0f : 0.0f;
    float rk = MUL(rel, k);
    a.planes[i] = rk;
    a.planes[a.n + i] = k;
    a.out[i] = rk;
    a.done[i] = known;
}
// fill_unknown step: take = ~done & (den > 0.02); out = where(take, num / den.clamp_min(1e-6), out); done |= take
struct FillArgs {
    const float* b;
    float* out;
    u8* done;
    i64 n;
};
extern "C" __global__ void rv_fill_step(const FillArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float den = a.b[a.n + i];
    bool take = !a.done[i] && den > 0.02f;
    if (take) {
        a.out[i] = DIV(a.b[i], t_clamp_min(den, 1e-6f));
        a.done[i] = 1;
    }
}
// signed_distance(water) from the Chebyshev distances (cap SD_CAP): -(inside + 1) in the water, outside + 1 beyond.
__device__ __forceinline__ float sdist(bool water, int cheb_dry, int cheb_wet, int cap) {
    if (water) {
        int c = cheb_dry - 1;
        c = c < 0 ? 0 : (c > cap ? cap : c);
        return -((float)c + 1.0f);
    }
    int c = cheb_wet - 1;
    c = c < 0 ? 0 : (c > cap ? cap : c);
    return (float)c + 1.0f;
}
// The bank model's input (1, 4, Hp, Wp), replicate-padded at the bottom / right:
// [(filled / REL).clamp(-4, 4), band, water, (sd / 16).clamp(-2, 2)], filled = where(water, 0, out).
struct BankXArgs {
    const float* out;
    const u8* band;
    const u8* channel;
    const int* cheb_dry;
    const int* cheb_wet;
    float* x;
    int H, W, Hp, Wp, cap;
    float inv_rel;
};
extern "C" __global__ void rv_bank_x(const BankXArgs a) {
    i64 i = gtid();
    i64 np = (i64)a.Hp * a.Wp;
    if (i >= np) return;
    int y = (int)(i / a.Wp), x = (int)(i - (i64)y * a.Wp);
    int ys = y < a.H ? y : a.H - 1, xs = x < a.W ? x : a.W - 1;
    i64 j = (i64)ys * a.W + xs;
    bool w = a.channel[j] != 0;
    float filled = w ? 0.0f : a.out[j];
    float sd = sdist(w, a.cheb_dry[j], a.cheb_wet[j], a.cap);
    a.x[i] = t_clamp(MUL(filled, a.inv_rel), -4.0f, 4.0f);
    a.x[np + i] = a.band[j] ? 1.0f : 0.0f;
    a.x[2 * np + i] = w ? 1.0f : 0.0f;
    a.x[3 * np + i] = t_clamp(MUL(sd, 0.0625f), -2.0f, 2.0f);
}
// carved = where(band, wgt * new + (1 - wgt) * base, base), new = level + rate * pred * REL,
// wgt = ((band_px - sd) / rim).clamp(0, 1)
struct BankApplyArgs {
    const float* pred;      // (Hp, Wp)
    const float* base;
    const float* level;
    const float* rate;
    const u8* band;
    const u8* channel;
    const int* cheb_dry;
    const int* cheb_wet;
    float* carved;
    int H, W, Wp, cap;
    float rel, band_px, inv_rim;
};
extern "C" __global__ void rv_bank_apply(const BankApplyArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    float base = a.base[i];
    if (!a.band[i]) {
        a.carved[i] = base;
        return;
    }
    float pred = MUL(a.pred[(i64)y * a.Wp + x], a.rel);
    float sd = sdist(a.channel[i] != 0, a.cheb_dry[i], a.cheb_wet[i], a.cap);
    float wgt = t_clamp(MUL(SUB(a.band_px, sd), a.inv_rim), 0.0f, 1.0f);
    float nw = ADD(a.level[i], MUL(a.rate[i], pred));
    a.carved[i] = ADD(MUL(wgt, nw), MUL(SUB(1.0f, wgt), base));
}
// LeveeBanks: band = d2 <= 2^2+2 ring; carved = where(band, maximum(base, level + 0.3), base)
struct LeveeArgs {
    const float* base;
    const float* level;
    const u8* band;
    float* carved;
    i64 n;
};
extern "C" __global__ void rv_levee(const LeveeArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float b = a.base[i];
    a.carved[i] = a.band[i] ? t_maximum(b, ADD(a.level[i], 0.3f)) : b;
}

// ============================================================================== BankGuard
struct GuardPreArgs {
    const u8* channel;
    const float* ground;
    const float* level;
    u8* ring;
    float* water;
    int H, W;
};
extern "C" __global__ void rv_guard_pre(const GuardPreArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    bool any = false;
    float touch = -F_INF;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            int yy = y + dy, xx = x + dx;
            if (yy < 0 || yy >= a.H || xx < 0 || xx >= a.W) continue;
            i64 j = (i64)yy * a.W + xx;
            bool c = a.channel[j] != 0;
            any = any || c;
            touch = t_poolmax(touch, c ? a.level[j] : -F_INF);
        }
    bool ring = any && !a.channel[i] && a.ground[i] > 0.5f;
    a.ring[i] = ring;
    float lv = a.level[i];
    a.water[i] = ring ? t_maximum(lv, touch) : lv;
}
struct GuardApplyArgs {
    const u8* ring;
    const float* water;
    const float* rate;
    float* carved;
    i64 n;
    float margin;
};
extern "C" __global__ void rv_guard_apply(const GuardApplyArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float need = ADD(a.water[i], MUL(a.margin, a.rate[i]));
    if (a.ring[i] && a.carved[i] < need) a.carved[i] = need;
}

// ============================================================================== UBed
struct UBedArgs {
    const float* level;
    const float* rate;
    const float* edge;
    const float* half;
    const float* depth;
    const float* ground;
    const u8* channel;
    float* carved;
    i64 n;
    float extra;
};
extern "C" __global__ void rv_ubed(const UBedArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float base = a.carved[i];
    if (!a.channel[i]) return;
    float rate = a.rate[i], db = a.depth[i];
    float u = t_clamp(DIV(a.edge[i], t_clamp_min(a.half[i], 1.0f)), 0.0f, 1.0f);
    float om = SUB(1.0f, u);
    float prof = SUB(1.0f, MUL(om, om));
    float depth_m = MUL(rate, ADD(MUL(SUB(db, 1.0f), prof), 1.0f));
    float bed = SUB(a.level[i], depth_m);
    bed = t_maximum(bed, SUB(a.ground[i], MUL(rate, ADD(db, a.extra))));
    a.carved[i] = t_minimum(base, bed);
}

// ============================================================================== NoSpill
struct NoSpillWallArgs {
    const float* carved;
    const u8* channel;
    const float* ground;
    const float* sea;       // 1 element: to_metres(sea_level)
    float* negwall;         // -wall
    u8* wet;
    i64 n;
};
extern "C" __global__ void rv_nospill_wall(const NoSpillWallArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float sea = a.sea[0], c = a.carved[i];
    bool wet = a.channel[i] && a.ground[i] > 0.5f;
    float wall = c < sea ? sea : c;
    if (wet) wall = F_INF;
    a.negwall[i] = -wall;
    a.wet[i] = wet;
}
struct NoSpillArgs {
    const float* negwall;
    const float* level;
    const u8* wet;
    float* carved;
    float* top;
    int H, W;
    float min_water;
};
extern "C" __global__ void rv_nospill(const NoSpillArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    float m = -F_INF;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            int yy = y + dy, xx = x + dx;
            if (yy < 0 || yy >= a.H || xx < 0 || xx >= a.W) continue;
            m = t_poolmax(m, a.negwall[(i64)yy * a.W + xx]);
        }
    float top = t_minimum(a.level[i], SUB(-m, 0.05f));
    bool wet = a.wet[i] != 0;
    float c = a.carved[i];
    if (wet && top < ADD(c, a.min_water)) a.carved[i] = SUB(top, a.min_water);
    a.top[i] = wet ? top : F_NAN;
}

// ============================================================================== MonotoneTop
// top0 = where(wet, top, carved)
struct MTopApplyArgs {
    const u8* wet;
    const float* mono;
    const float* rate;
    float* carved;
    float* top;
    i64 n;
};
extern "C" __global__ void rv_mtop_apply(const MTopApplyArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    if (!a.wet[i]) return;
    float m = a.mono[i];
    a.carved[i] = t_minimum(a.carved[i], SUB(m, a.rate[i]));
    a.top[i] = m;
}

// ============================================================================== block stages (river/blocks.py)
// pool_to_blocks (d >= 1): avg_pool2d = rows-then-columns fp32 sum, then / (d*d); min = -max_pool(-x).
struct PoolArgs {
    const u8* wet_n;
    const float* top;
    const float* carved;
    const float* half_px;
    const u8* channel;
    const float* flow;
    float* ground;
    float* top_m;
    u8* wet;
    float* half_b;
    float* flow_log;
    int H, W, d;      // H, W: the pooled size
    float inv_d;
};
extern "C" __global__ void rv_pool(const PoolArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    const int SW = a.W * a.d, d = a.d;
    float sw = 0.0f, sc = 0.0f, sf = 0.0f;
    float mt = -F_INF, mc = -F_INF, mh = -F_INF;
    for (int dy = 0; dy < d; ++dy)
        for (int dx = 0; dx < d; ++dx) {
            i64 j = (i64)(y * d + dy) * SW + x * d + dx;
            bool w = a.wet_n[j] != 0;
            sw = ADD(sw, w ? 1.0f : 0.0f);
            float c = a.carved[j];
            sc = ADD(sc, c);
            sf = ADD(sf, a.flow[j]);
            mt = t_poolmax(mt, -(w ? a.top[j] : F_INF));
            mc = t_poolmax(mc, -c);
            mh = t_poolmax(mh, MUL(a.half_px[j], a.channel[j] ? 1.0f : 0.0f));
        }
    const float kk = (float)(d * d);
    bool wet = DIV(sw, kk) >= 0.25f;
    float top = -mt;
    a.ground[i] = wet ? -mc : DIV(sc, kk);
    a.top_m[i] = top;
    a.wet[i] = wet && isfinite(top) && top > 0.0f;
    a.half_b[i] = MUL(mh, a.inv_d);
    a.flow_log[i] = DIV(sf, kk);
}
// unpool_to_blocks (k x k blocks per pixel)
struct UnpoolArgs {
    const u8* wet_n;
    const float* top;
    const float* carved;
    const float* half_px;
    const u8* channel;
    const float* flow;
    const float* ground_fine;
    float* ground;
    float* top_m;
    u8* wet;
    float* half_b;
    float* flow_log;
    int H, W, k;      // H, W: the block size
    float kf;
};
extern "C" __global__ void rv_unpool(const UnpoolArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    i64 j = (i64)(y / a.k) * (a.W / a.k) + x / a.k;
    bool w = a.wet_n[j] != 0;
    float top = w ? a.top[j] : F_INF;
    a.ground[i] = w ? a.carved[j] : a.ground_fine[i];
    a.top_m[i] = top;
    a.wet[i] = w && isfinite(top) && top > 0.0f;
    a.half_b[i] = MUL(MUL(a.half_px[j], a.channel[j] ? 1.0f : 0.0f), a.kf);
    a.flow_log[i] = a.flow[j];
}
// BlockQuantize (tb = to_blocks(ground), tw = to_blocks(where(wet, top, ground)))
struct QuantArgs {
    const float* tb;
    const float* tw;
    const u8* wet;
    i64* height;
    i64* water;
    i64* floor_;
    i64* roof;
    i64* flow;
    u8* wet_out;
    i64 n;
    float lo, hi;
};
extern "C" __global__ void rv_quantize(const QuantArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    i64 hb = (i64)t_clamp(floorf(a.tb[i]), a.lo, a.hi);
    i64 lb = (i64)floorf(a.tw[i]);
    bool w = a.wet[i] != 0;
    i64 h1 = hb + 1;
    a.height[i] = hb;
    a.water[i] = w ? (lb > h1 ? lb : h1) : -1;
    a.floor_[i] = -1;
    a.roof[i] = -1;
    a.flow[i] = -1;
    a.wet_out[i] = w;
}
struct QuantInArgs {
    const float* ground;
    const float* top;
    const u8* wet;
    float* out;     // (2, n): ground, where(wet, top, ground)
    i64 n;
};
extern "C" __global__ void rv_quant_in(const QuantInArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float g = a.ground[i];
    a.out[i] = g;
    a.out[a.n + i] = a.wet[i] ? a.top[i] : g;
}
// FlowOctants planes (3, n): top (where(wet & isfinite(top), top, 0)), wet, flow_log where wet
struct FlowPlanesArgs {
    const float* top;
    const u8* wet;
    const float* flow_log;
    float* out;
    i64 n;
};
extern "C" __global__ void rv_flow_planes(const FlowPlanesArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    bool w = a.wet[i] != 0;
    float t = a.top[i];
    a.out[i] = (w && isfinite(t)) ? t : 0.0f;
    a.out[a.n + i] = w ? 1.0f : 0.0f;
    a.out[2 * a.n + i] = a.flow_log ? (w ? a.flow_log[i] : 0.0f) : 0.0f;
}
// s = blur(top) / blur(wf).clamp_min(1e-6), fs = blur(fl) / blur(wf).clamp_min(1e-6)
struct FlowRatioArgs {
    const float* b;
    float* s;
    float* fs;
    i64 n;
};
extern "C" __global__ void rv_flow_ratio(const FlowRatioArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    float den = t_clamp_min(a.b[a.n + i], 1e-6f);
    a.s[i] = DIV(a.b[i], den);
    a.fs[i] = DIV(a.b[2 * a.n + i], den);
}
struct FlowOctArgs {
    const float* s;
    const float* fs;
    const u8* wet;
    const i64* d8;      // (dh, dw) or nullptr
    i64 table[9];
    i64* flow;          // oct before the consensus (live where wet & flow >= 0)
    float* uxz;         // (2, n)
    int H, W, dh, dw, has_acc;
    i64 bo0, bo1, do0, do1;
    int d_int, k;       // d >= 1: d_int = d; else k blocks per pixel (d_int = 0)
    int cp;
    float inv_q, q;
};
extern "C" __global__ void rv_flow_oct(const FlowOctArgs a) {
    i64 i = gtid();
    i64 n = (i64)a.H * a.W;
    if (i >= n) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    const float* s = a.s;
    float gx = MUL(0.5f, SUB(rd(s, y + 1, x, a.H, a.W), rd(s, y - 1, x, a.H, a.W)));
    float gz = MUL(0.5f, SUB(rd(s, y, x + 1, a.H, a.W), rd(s, y, x - 1, a.H, a.W)));
    float dx = -gx, dz = -gz;
    float mag = __fsqrt_rn(ADD(MUL(dx, dx), MUL(dz, dz)));
    i64 og = octant_of(atan2f(dz, dx), a.inv_q);
    i64 od = -1;
    if (a.d8) {
        i64 bi = a.bo0 + y, bj = a.bo1 + x;
        i64 ci, cj;
        if (a.d_int >= 1) {
            i64 pi = bi * a.d_int, pj = bj * a.d_int;
            ci = (pi >= 0 ? pi / a.cp : -((-pi + a.cp - 1) / a.cp)) - a.do0;
            cj = (pj >= 0 ? pj / a.cp : -((-pj + a.cp - 1) / a.cp)) - a.do1;
        } else {
            i64 kc = (i64)a.k * a.cp;
            ci = (bi >= 0 ? bi / kc : -((-bi + kc - 1) / kc)) - a.do0;
            cj = (bj >= 0 ? bj / kc : -((-bj + kc - 1) / kc)) - a.do1;
        }
        ci = ci < 0 ? 0 : (ci > a.dh - 1 ? a.dh - 1 : ci);
        cj = cj < 0 ? 0 : (cj > a.dw - 1 ? a.dw - 1 : cj);
        i64 cls = a.d8[ci * a.dw + cj];
        cls = cls < 0 ? 0 : (cls > 8 ? 8 : cls);
        od = a.table[cls];
    }
    if (a.has_acc) {
        const float* f = a.fs;
        float ax = MUL(0.5f, SUB(rd(f, y + 1, x, a.H, a.W), rd(f, y - 1, x, a.H, a.W)));
        float az = MUL(0.5f, SUB(rd(f, y, x + 1, a.H, a.W), rd(f, y, x - 1, a.H, a.W)));
        i64 oa = octant_of(atan2f(az, ax), a.inv_q);
        if (__fsqrt_rn(ADD(MUL(ax, ax), MUL(az, az))) > 1e-4f) od = oa;
    }
    i64 fl = mag > 1e-3f ? og : od;
    bool live = a.wet[i] && fl >= 0;
    float ang = MUL((float)fl, a.q);
    a.flow[i] = fl;
    a.uxz[i] = live ? cosf(ang) : 0.0f;
    a.uxz[n + i] = live ? sinf(ang) : 0.0f;
}
// consensus: sx, sz = conv2d(ux / uz, ones(C, C), padding C // 2) (cuDNN's row-major sum, measured)
struct ConsensusArgs {
    const i64* flow_in;
    const float* uxz;
    const u8* wet;
    i64* flow;
    int H, W, C;
    float inv_q;
};
extern "C" __global__ void rv_consensus(const ConsensusArgs a) {
    i64 i = gtid();
    i64 n = (i64)a.H * a.W;
    if (i >= n) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    int r = a.C / 2;
    float sx = 0.0f, sz = 0.0f;
    for (int dy = -r; dy <= r; ++dy)
        for (int dx = -r; dx <= r; ++dx) {
            int yy = y + dy, xx = x + dx;
            float vx = 0.0f, vz = 0.0f;
            if (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W) {
                i64 j = (i64)yy * a.W + xx;
                vx = a.uxz[j];
                vz = a.uxz[n + j];
            }
            sx = ADD(sx, vx);
            sz = ADD(sz, vz);
        }
    i64 fl = a.flow_in[i];
    bool wet = a.wet[i] != 0;
    bool live = wet && fl >= 0;
    if (live && ADD(MUL(sx, sx), MUL(sz, sz)) > 0.25f) fl = octant_of(atan2f(sz, sx), a.inv_q);
    a.flow[i] = wet ? fl : -1;
}
#define BIG_LV 2305843009213693951LL   // torch.iinfo(int64).max // 4
// FlattenAcross step (one side): best = min(lv, shift(lv, k)) where wet & (oct + side) % 8 == k & oct >= 0
struct FlattenStepArgs {
    const i64* lv;
    i64* out;
    const u8* wet;
    const i64* oct;
    int H, W, side;
    int di[8], dj[8];
};
extern "C" __global__ void rv_flatten_step(const FlattenStepArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    i64 v = a.lv[i];
    i64 o = a.oct[i];
    if (a.wet[i] && o >= 0) {
        i64 k = (o + a.side) % 8;
        int yy = y + a.di[k], xx = x + a.dj[k];
        i64 nb = (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W) ? a.lv[(i64)yy * a.W + xx] : BIG_LV;
        if (nb < v) v = nb;
    }
    a.out[i] = v;
}
// lv = where(wet, water, big)
struct LvInitArgs {
    const u8* wet;
    const i64* water;
    i64* lv;
    i64 n;
};
extern "C" __global__ void rv_lv_init(const LvInitArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    a.lv[i] = a.wet[i] ? a.water[i] : BIG_LV;
}
// water = where(wet, lv, water); height = where(wet, minimum(height, water - 1), height)
struct LvFinArgs {
    const u8* wet;
    const i64* lv;
    i64* water;
    i64* height;
    i64 n;
};
extern "C" __global__ void rv_lv_fin(const LvFinArgs a) {
    i64 i = gtid();
    if (i >= a.n) return;
    if (!a.wet[i]) return;
    i64 w = a.lv[i];
    a.water[i] = w;
    i64 h = a.height[i];
    a.height[i] = h < w - 1 ? h : w - 1;
}
// MonotoneBlocks step
struct MonoBlocksArgs {
    const i64* lv;
    i64* out;
    const u8* wet;      // cols.wet
    const i64* flow;
    int H, W;
    int di[8], dj[8];
};
__device__ __forceinline__ bool mb_along(const u8* wet, const i64* flow, i64 j, int k) {
    if (!wet[j]) return false;
    i64 f = flow[j];
    return f >= 0 && (f == k || f == (k + 1) % 8 || f == (k + 7) % 8);
}
extern "C" __global__ void rv_mono_blocks(const MonoBlocksArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    i64 v = a.lv[i];
    if (!a.wet[i]) {
        a.out[i] = v;
        return;
    }
    i64 cap = BIG_LV;
    for (int k = 0; k < 8; ++k) {
        int sy = y - a.di[k], sx = x - a.dj[k];
        if (sy >= 0 && sy < a.H && sx >= 0 && sx < a.W) {
            i64 j = (i64)sy * a.W + sx;
            i64 s = mb_along(a.wet, a.flow, j, k) ? a.lv[j] : BIG_LV;
            if (s < cap) cap = s;
        }
        if (mb_along(a.wet, a.flow, i, k)) {
            int dy = y + a.di[k], dx = x + a.dj[k];
            i64 down = BIG_LV;
            if (dy >= 0 && dy < a.H && dx >= 0 && dx < a.W) {
                i64 j = (i64)dy * a.W + dx;
                down = a.wet[j] ? a.lv[j] : BIG_LV;
            }
            if (down < BIG_LV && down + 1 < cap) cap = down + 1;
        }
    }
    a.out[i] = v < cap ? v : cap;
}
// BlockContainment
struct ContainArgs {
    const u8* wet;
    const i64* water;
    i64* height_in;
    i64* height;
    int H, W;
    i64 sea;
};
extern "C" __global__ void rv_contain(const ContainArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    float held = -F_INF;
    for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            int yy = y + dy, xx = x + dx;
            if (yy < 0 || yy >= a.H || xx < 0 || xx >= a.W) continue;
            i64 j = (i64)yy * a.W + xx;
            held = t_poolmax(held, (float)(a.wet[j] ? a.water[j] : -1));
        }
    i64 h = a.height_in[i];
    bool need = !a.wet[i] && held >= 0.0f && h >= a.sea && (float)h < held;
    a.height[i] = need ? (i64)held : h;
}
__device__ __forceinline__ u32 lowbias32(u32 x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}
// Undercuts
struct UndercutArgs {
    const u8* wet;
    const i64* water;
    const i64* height;
    i64* floor_;
    i64* roof;
    int H, W;
    i64 o0, o1;
    u32 base;
    float inv_period;
    int min_rise, air, cover;
};
__device__ __forceinline__ float corner(float x0, float y0, int dx, int dy, u32 base) {
    i64 xi = (i64)x0 + dx, yi = (i64)y0 + dy;
    u32 h = lowbias32(lowbias32((u32)xi ^ base) ^ (u32)yi);
    return SUB(MUL((float)(i64)h, 4.656612873077393e-10f), 1.0f);
}
extern "C" __global__ void rv_undercut(const UndercutArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    float nb = -1.0f;   // max of the 4-neighbours, -1 past the edge
    const int ny[4] = {-1, 1, 0, 0}, nx[4] = {0, 0, -1, 1};
    for (int k = 0; k < 4; ++k) {
        int yy = y + ny[k], xx = x + nx[k];
        float v = -1.0f;
        if (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W) {
            i64 j = (i64)yy * a.W + xx;
            v = (float)(a.wet[j] ? a.water[j] : -1);
        }
        nb = t_maximum(nb, v);
    }
    i64 nbl = (i64)nb;
    i64 h = a.height[i];
    float bi = (float)(a.o0 + y), bj = (float)(a.o1 + x);
    float xf = MUL(bi, a.inv_period), yf = MUL(bj, a.inv_period);
    float x0 = floorf(xf), y0 = floorf(yf);
    float fx = SUB(xf, x0), fy = SUB(yf, y0);
    float ux = MUL(MUL(fx, fx), SUB(3.0f, MUL(2.0f, fx)));
    float uy = MUL(MUL(fy, fy), SUB(3.0f, MUL(2.0f, fy)));
    float c00 = corner(x0, y0, 0, 0, a.base), c01 = corner(x0, y0, 0, 1, a.base);
    float c10 = corner(x0, y0, 1, 0, a.base), c11 = corner(x0, y0, 1, 1, a.base);
    float omy = SUB(1.0f, uy), omx = SUB(1.0f, ux);
    float A = ADD(MUL(c00, omy), MUL(c01, uy));
    float B = ADD(MUL(c10, omy), MUL(c11, uy));
    float noise = ADD(MUL(A, omx), MUL(B, ux));
    i64 fl = nbl - 1;
    i64 rf = fl + 1 + a.air;
    bool ok = !a.wet[i] && nbl >= 0 && h - nbl >= a.min_rise && h >= rf + a.cover && noise > 0.0f;
    if (ok) {
        a.floor_[i] = fl;
        a.roof[i] = rf;
    }
}
// Overhangs
struct OverhangArgs {
    const u8* wet;
    const i64* water;
    const i64* height_in;
    const i64* roof_in;
    const float* half_b;
    i64* height;
    i64* floor_;
    i64* roof;
    int H, W;
    float min_width;
};
extern "C" __global__ void rv_overhang(const OverhangArgs a) {
    i64 i = gtid();
    if (i >= (i64)a.H * a.W) return;
    int y = (int)(i / a.W), x = (int)(i - (i64)y * a.W);
    float nr = -1.0f, nt = -1.0f;
    const int ny[4] = {-1, 1, 0, 0}, nx[4] = {0, 0, -1, 1};
    for (int k = 0; k < 4; ++k) {
        int yy = y + ny[k], xx = x + nx[k];
        float r = -1.0f, t = -1.0f;
        if (yy >= 0 && yy < a.H && xx >= 0 && xx < a.W) {
            i64 j = (i64)yy * a.W + xx;
            bool under = !a.wet[j] && a.roof_in[j] != -1;
            r = (float)(under ? a.roof_in[j] : -1);
            t = (float)(under ? a.height_in[j] : -1);
        }
        nr = t_maximum(nr, r);
        nt = t_maximum(nt, t);
    }
    i64 nbr = (i64)nr, nbt = (i64)nt;
    i64 h = a.height_in[i];
    bool wide = MUL(a.half_b[i], 2.0f) >= a.min_width;
    bool ok = a.wet[i] && nbr >= 0 && wide && nbr >= a.water[i] + 1 && h >= 1;
    if (ok) {
        a.floor_[i] = h - 1;
        a.roof[i] = nbr;
        a.height[i] = nbt;
    } else {
        a.height[i] = h;
    }
}
