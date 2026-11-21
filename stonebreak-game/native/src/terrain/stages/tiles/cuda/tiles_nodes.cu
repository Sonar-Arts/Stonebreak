// Stage module "tiles_nodes": the surface nodes' tile-time code (world/vegetation_node.py + models/vegnet.py build_input,
// world/surface_node.py + models/surfacenet.py build_input / activate) around their engine networks. One IEEE op per
// torch op, in its order.
#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn
__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ float t_cmin(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_cmax(float x, float hi) { return x != x ? x : (x > hi ? hi : x); }
__device__ __forceinline__ float t_clamp(float x, float lo, float hi) { return t_cmax(t_cmin(x, lo), hi); }
__device__ __forceinline__ int iclamp(int x, int lo, int hi) { return x < lo ? lo : (x > hi ? hi : x); }
__device__ __forceinline__ float rep(const float* p, int H, int W, int r, int c) {
    return p[(long long)iclamp(r, 0, H - 1) * W + iclamp(c, 0, W - 1)];
}

// F.avg_pool2d(F.pad(x, r, replicate), k, stride=1): sequential row-major sum, / (k * k)
struct BoxArgs {
    const float* src;
    float* dst;
    int H, W, k;
};
extern "C" __global__ void tiles_box(const BoxArgs a) {
    long long t = gtid();
    if (t >= (long long)a.H * a.W) return;
    int r = (int)(t / a.W), c = (int)(t - (long long)r * a.W), rr = a.k / 2;
    float s = 0.0f;
    for (int u = -rr; u <= rr; ++u)
        for (int v = -rr; v <= rr; ++v) s = ADD(s, rep(a.src, a.H, a.W, r + u, c + v));
    a.dst[t] = DIV(s, (float)(a.k * a.k));
}

// water = ((height < sea) | (wet & (level > height))) as 0 / 1 floats (frame)
struct WaterArgs {
    const long long* height;
    const long long* level;
    const bool* wet;
    float* out;
    long long n, sea;
};
extern "C" __global__ void tiles_node_water(const WaterArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    long long h = a.height[t];
    a.out[t] = ((h < a.sea) || (a.wet[t] && a.level[t] > h)) ? 1.0f : 0.0f;
}
// (half(water) >= 0.5).float()
struct GeArgs {
    float* x;
    long long n;
};
extern "C" __global__ void tiles_ge_half(const GeArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    a.x[t] = a.x[t] >= 0.5f ? 1.0f : 0.0f;
}

// vegnet.build_input -> x (16, H, W)
struct VegInputArgs {
    const float* e;        // elev_m
    const float* es;       // box5(e)
    const float* b9;       // box9(e)
    const float* b17;      // box17(e)
    const float* clim;     // (7, H, W)
    const float* reg;      // regional
    const float* w;        // water 0 / 1
    const float* wb9;      // box9(w)
    float* x;
    int H, W;
    float lapse;
};
extern "C" __global__ void tiles_veg_input(const VegInputArgs a) {
    long long t = gtid();
    const long long hw = (long long)a.H * a.W;
    if (t >= hw) return;
    int r = (int)(t / a.W), c = (int)(t - (long long)r * a.W);
    const float e = a.e[t], es = a.es[t];
    const float R = rep(a.es, a.H, a.W, r, c + 1), L = rep(a.es, a.H, a.W, r, c - 1);
    const float D = rep(a.es, a.H, a.W, r + 1, c), U = rep(a.es, a.H, a.W, r - 1, c);
    const float gx = MUL(0.5f, SUB(R, L)), gy = MUL(0.5f, SUB(D, U));
    const float slope = MUL(sqrtf(ADD(MUL(gx, gx), MUL(gy, gy))), 1.0f / 60.0f);
    const float curv = MUL(SUB(ADD(ADD(ADD(R, L), D), U), MUL(4.0f, es)), 1.0f / 60.0f);
    const float w = a.w[t];
    const float tt = a.clim[t], ts = a.clim[hw + t], pr = t_cmin(a.clim[2 * hw + t], 0.0f), cv = a.clim[3 * hw + t];
    const float t_local = SUB(tt, MUL(a.lapse, SUB(t_cmin(e, 0.0f), t_cmin(a.reg[t], 0.0f))));
    const float land = (e > 0.0f && w < 0.5f) ? 1.0f : 0.0f;
    float* x = a.x;
    x[t] = MUL(t_cmin(e, 0.0f), 1.0f / 1000.0f);
    x[hw + t] = t_clamp(slope, 0.0f, 3.0f);
    x[2 * hw + t] = t_clamp(MUL(curv, 10.0f), -3.0f, 3.0f);
    x[3 * hw + t] = t_clamp(MUL(SUB(es, a.b9[t]), 1.0f / 50.0f), -4.0f, 4.0f);
    x[4 * hw + t] = t_clamp(MUL(SUB(es, a.b17[t]), 1.0f / 150.0f), -4.0f, 4.0f);
    x[5 * hw + t] = MUL(tt, 1.0f / 10.0f);
    x[6 * hw + t] = MUL(t_local, 1.0f / 10.0f);
    x[7 * hw + t] = MUL(ts, 1.0f / 500.0f);
    x[8 * hw + t] = MUL(log1pf(pr), 1.0f / 7.0f);
    x[9 * hw + t] = MUL(cv, 1.0f / 50.0f);
    x[10 * hw + t] = MUL(logf(ADD(t_cmin(a.clim[4 * hw + t], 0.0f), 0.01f)), 1.0f / 3.0f);
    x[11 * hw + t] = MUL(a.clim[5 * hw + t], 1.0f / 0.4f);
    x[12 * hw + t] = MUL(log1pf(MUL(t_cmin(a.clim[6 * hw + t], 0.0f), 100.0f)), 1.0f / 3.0f);
    x[13 * hw + t] = w;
    x[14 * hw + t] = a.wb9[t];
    x[15 * hw + t] = land;
}

// torch.sigmoid (CUDA float: 1 / (1 + exp(-x))), in place
struct SigArgs {
    float* x;
    long long n;
};
extern "C" __global__ void tiles_sigmoid(const SigArgs a) {
    long long t = gtid();
    if (t >= a.n) return;
    a.x[t] = DIV(1.0f, ADD(1.0f, expf(-a.x[t])));
}

// provider output: out = src[ch][crop] * scale, NaN where water[crop] > 0.5 (src (C, H, W), water frame (H, W))
struct NodeOutArgs {
    const float* src;
    const float* water;   // 0 / 1 floats (vegetation) or null (then `mask` is used)
    const bool* mask;     // surface: water mask over the tile (h, w)
    float* out;           // (h, w)
    int H, W, mb, h, w, ch;
    float scale;
    int apply_scale;
};
extern "C" __global__ void tiles_node_out(const NodeOutArgs a) {
    long long t = gtid();
    if (t >= (long long)a.h * a.w) return;
    int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const long long i = (long long)(r + a.mb) * a.W + (c + a.mb);
    float v = a.src[(long long)a.ch * a.H * a.W + i];
    if (a.apply_scale) v = MUL(v, a.scale);
    const bool wet = a.water ? a.water[i] > 0.5f : a.mask[t];
    a.out[t] = wet ? __int_as_float(0x7fc00000) : v;
}

// surfacenet.build_input -> x (9, H, W)
struct SurfInputArgs {
    const float* z;
    const float* b5;      // box5(z)
    const float* temp;
    const float* precip;
    float* x;
    int H, W;
};
extern "C" __global__ void tiles_surf_input(const SurfInputArgs a) {
    long long t = gtid();
    const long long hw = (long long)a.H * a.W;
    if (t >= hw) return;
    int r = (int)(t / a.W), c = (int)(t - (long long)r * a.W);
    const float z = a.z[t];
    const float R = rep(a.z, a.H, a.W, r, c + 1), L = rep(a.z, a.H, a.W, r, c - 1);
    const float D = rep(a.z, a.H, a.W, r + 1, c), U = rep(a.z, a.H, a.W, r - 1, c);
    const float gx = MUL(MUL(0.5f, SUB(R, L)), 1.0f / 60.0f), gy = MUL(MUL(0.5f, SUB(D, U)), 1.0f / 60.0f);
    const float slope = sqrtf(ADD(MUL(gx, gx), MUL(gy, gy)));
    float mx = -__int_as_float(0x7f800000), mn = __int_as_float(0x7f800000), s = 0.0f;
    for (int u = -1; u <= 1; ++u)
        for (int v = -1; v <= 1; ++v) {
            float p = rep(a.z, a.H, a.W, r + u, c + v);
            mx = (p > mx || p != p) ? p : mx;
            mn = (p < mn || p != p) ? p : mn;
            s = ADD(s, p);
        }
    const float mean3 = DIV(s, 9.0f);
    const float rel = SUB(z, a.b5[t]);
    float* x = a.x;
    x[t] = MUL(rel, 1.0f / 30.0f);
    x[hw + t] = t_clamp(gx, -3.0f, 3.0f);
    x[2 * hw + t] = t_clamp(gy, -3.0f, 3.0f);
    x[3 * hw + t] = t_clamp(slope, 0.0f, 3.0f);
    x[4 * hw + t] = MUL(SUB(mx, mn), 1.0f / 100.0f);
    x[5 * hw + t] = MUL(SUB(mean3, z), 1.0f / 10.0f);
    x[6 * hw + t] = MUL(z, 1.0f / 2000.0f);
    x[7 * hw + t] = MUL(a.temp[t], 1.0f / 15.0f);
    x[8 * hw + t] = log1pf(MUL(t_cmin(a.precip[t], 0.0f), 1.0f / 500.0f));
}

// surfacenet.activate on the raw heads: F fields at 60 m from raw[0:F] (H, W), or at 15 m from raw[F:F + F*S*S]
// through pixel_shuffle(S) (out (F, S*H, S*W)). Field k: < FRACTIONS sigmoid; 3: softplus * 0.25; 4: softplus * 0.5;
// 5: 1.6 + softplus * 2.0 (softplus = x > 20 ? x : log1p(exp(x))).
struct ActArgs {
    const float* raw;     // (F + F*S*S, H, W)
    float* out;
    int H, W, F, S, fine, fractions;
};
__device__ __forceinline__ float softplus(float x) { return x > 20.0f ? x : log1pf(expf(x)); }
extern "C" __global__ void tiles_surf_activate(const ActArgs a) {
    long long t = gtid();
    const int S = a.fine ? a.S : 1;
    const int Ho = a.H * S, Wo = a.W * S;
    const long long n = (long long)a.F * Ho * Wo;
    if (t >= n) return;
    const int f = (int)(t / ((long long)Ho * Wo));
    const long long p = t - (long long)f * Ho * Wo;
    const int Y = (int)(p / Wo), X = (int)(p - (long long)Y * Wo);
    long long src;
    if (a.fine) {
        const int y = Y / S, i = Y - y * S, x = X / S, j = X - x * S;
        const int ch = a.F + f * S * S + i * S + j;
        src = (long long)ch * a.H * a.W + (long long)y * a.W + x;
    } else {
        src = (long long)f * a.H * a.W + p;
    }
    const float v = a.raw[src];
    float o;
    if (f < a.fractions) o = DIV(1.0f, ADD(1.0f, expf(-v)));
    else if (f == 3) o = MUL(softplus(v), 0.25f);
    else if (f == 4) o = MUL(softplus(v), 0.5f);
    else o = ADD(1.6f, MUL(softplus(v), 2.0f));
    a.out[t] = o;
}
// surface_node water mask over the tile: (height < sea) | (wet & (level > height)) on the cropped frame
struct SurfMaskArgs {
    const long long* height;
    const long long* level;
    const bool* wet;
    bool* out;
    int W, mb, h, w;
    long long sea;
};
extern "C" __global__ void tiles_surf_mask(const SurfMaskArgs a) {
    long long t = gtid();
    if (t >= (long long)a.h * a.w) return;
    int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const long long i = (long long)(r + a.mb) * a.W + (c + a.mb);
    const long long h = a.height[i];
    a.out[t] = (h < a.sea) || (a.wet[i] && a.level[i] > h);
}
