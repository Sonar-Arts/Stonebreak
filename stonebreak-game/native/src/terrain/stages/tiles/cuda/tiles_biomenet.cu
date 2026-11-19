// Stage module "tiles_biomenet": models/biomenet.py build_input (the sidecar's 10 or 13 input channels: elevation,
// slope, climate, the rule classifier's noise fields) and the class argmax of BN.classify. The network itself is the
// engine's "biome_<H>x<W>" model (export/cenda_export.py export_biome), the same plan the Python generator runs.
#define MUL __fmul_rn
#define ADD __fadd_rn
#define SUB __fsub_rn
#define DIV __fdiv_rn
__device__ __forceinline__ long long gtid() { return (long long)blockIdx.x * blockDim.x + threadIdx.x; }
__device__ __forceinline__ float t_cmin(float x, float lo) { return x != x ? x : (x < lo ? lo : x); }
__device__ __forceinline__ float t_cmax(float x, float hi) { return x != x ? x : (x > hi ? hi : x); }
__device__ __forceinline__ int iclamp(int x, int lo, int hi) { return x < lo ? lo : (x > hi ? hi : x); }

// noise: (5, h, w) raw FastNoiseLite fields at classifier_noise's coordinates (temp, temp fine, precip, snow, snow fine)
struct BuildArgs {
    const float* elev;    // (h, w) mid-block metres
    const float* clim;    // (C, h, w)
    const float* noise;   // (5, h, w)
    float* x;             // (n_in, h, w)
    int h, w, C, extras;
};
extern "C" __global__ void tiles_bn_input(const BuildArgs a) {
    long long t = gtid();
    const long long hw = (long long)a.h * a.w;
    if (t >= hw) return;
    const int r = (int)(t / a.w), c = (int)(t - (long long)r * a.w);
    const float e = a.elev[t];
    // slope: replicate pad, gx = 0.5 * (right - left), gy = 0.5 * (down - up), sqrt(gx^2 + gy^2) / PIXEL_M
    const float* E = a.elev;
    const float right = E[(long long)r * a.w + iclamp(c + 1, 0, a.w - 1)], left = E[(long long)r * a.w + iclamp(c - 1, 0, a.w - 1)];
    const float down = E[(long long)iclamp(r + 1, 0, a.h - 1) * a.w + c], up = E[(long long)iclamp(r - 1, 0, a.h - 1) * a.w + c];
    const float gx = MUL(0.5f, SUB(right, left)), gy = MUL(0.5f, SUB(down, up));
    const float slope = MUL(sqrtf(ADD(MUL(gx, gx), MUL(gy, gy))), 1.0f / 60.0f);
    const float n_t = a.noise[t], n_tf = a.noise[hw + t], n_p = a.noise[2 * hw + t], n_s = a.noise[3 * hw + t],
                n_sf = a.noise[4 * hw + t];
    // numpy fp32: temp = 0.4 * T + 0.2 * Tf; precip = 0.2 * P; snow = (3.0 * S + 2.0 * Sf) / 5.0
    const float nz0 = ADD(MUL(0.4f, n_t), MUL(0.2f, n_tf));
    const float nz1 = MUL(0.2f, n_p);
    const float nz2 = DIV(ADD(MUL(3.0f, n_s), MUL(2.0f, n_sf)), 5.0f);
    const float c0 = a.clim[t], c1 = a.clim[hw + t], c2 = t_cmin(a.clim[2 * hw + t], 0.0f), c3 = a.clim[3 * hw + t];
    float* x = a.x;
    x[t] = MUL(t_cmin(e, 0.0f), 1.0f / 1000.0f);
    x[hw + t] = t_cmax(t_cmin(slope, 0.0f), 3.0f);
    x[2 * hw + t] = MUL(c0, 1.0f / 10.0f);
    x[3 * hw + t] = MUL(c1, 1.0f / 500.0f);
    x[4 * hw + t] = MUL(log1pf(c2), 1.0f / 7.0f);
    x[5 * hw + t] = MUL(c3, 1.0f / 50.0f);
    x[6 * hw + t] = nz0;
    x[7 * hw + t] = MUL(nz1, 5.0f);
    x[8 * hw + t] = nz2;
    x[9 * hw + t] = e > 0.0f ? 1.0f : 0.0f;
    if (a.extras) {
        x[10 * hw + t] = MUL(logf(ADD(t_cmin(a.clim[4 * hw + t], 0.0f), 0.01f)), 1.0f / 3.0f);
        x[11 * hw + t] = MUL(a.clim[5 * hw + t], 1.0f / 0.4f);
        x[12 * hw + t] = MUL(log1pf(MUL(t_cmin(a.clim[6 * hw + t], 0.0f), 100.0f)), 1.0f / 3.0f);
    }
}

// ids[logits.argmax(0)]: the first maximum (a NaN counts as the maximum, as torch's argmax).
struct ArgmaxArgs {
    const float* logits;   // (K, P)
    short* out;
    const short* ids;      // (K)
    long long P;
    int K;
};
extern "C" __global__ void tiles_bn_argmax(const ArgmaxArgs a) {
    long long p = gtid();
    if (p >= a.P) return;
    int best = 0;
    float bv = a.logits[p];
    for (int k = 1; k < a.K && bv == bv; ++k) {
        float v = a.logits[(long long)k * a.P + p];
        if (v != v || v > bv) {
            bv = v;
            best = k;
        }
    }
    a.out[p] = a.ids[best];
}
