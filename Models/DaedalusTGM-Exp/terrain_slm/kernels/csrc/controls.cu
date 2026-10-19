// generator.procedural_controls as one CUDA kernel, bit-identical to the torch definition
// (generator.procedural_controls_torch). Compiled by NVRTC with --fmad=false and -ftz=false.
//
// Every fp32 multiply / add / subtract is an explicitly rounded intrinsic (__fmul_rn, __fadd_rn,
// __fsub_rn: never contracted into FMA, never paired into f32x2), division is IEEE __fdiv_rn,
// exp is CUDA expf (what torch.exp calls), denormals are kept. `tensor / scalar` in the torch
// version is torch's multiply by the fp32 reciprocal, passed in precomputed (K_* constants). The
// evaluation order mirrors the torch expressions op for op; tests/test_cuda_kernels.py pins equality.

typedef unsigned int u32;

struct ControlsArgs {
    float* out;          // (6, hc, wc): wild, trend, t0, tseason, precip, pcv
    const u32* bases;    // per-octave hash bases
    const float* invp;   // per-octave 1 / period (fp32 reciprocal, like torch's x / scalar)
    const float* arch;   // (7, 4) climate archetypes, precip column already log()
    const float* k;      // constants, see K_* below
    int ci0, cj0, hc, wc;
    int r_cont, r_wi, r_wj, r_range, r_spur, r_along, r_hills, r_hilly, r_clim;
};

enum { K_INV2, K_INV3, K_INV4, K_INV_HOME, K_INV_LAND, K_INV_STD2, K_INV_STD3, K_INV_SPINE, K_INV_FOOT,
       K_INV_BODY, K_INV_SPUR, K_INV_ALONG, K_INV_HILLY, K_WILD_K, K_WILD_0 };

#define M(a, b) __fmul_rn((a), (b))
#define A(a, b) __fadd_rn((a), (b))
#define S(a, b) __fsub_rn((a), (b))

__device__ __forceinline__ u32 hash32(u32 x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}

__device__ __forceinline__ float corner(u32 xi, u32 yi, u32 base, u32 dx, u32 dy) {
    u32 h = hash32(hash32((xi + dx) ^ base) ^ (yi + dy));
    return S(M(__uint2float_rn(h), 4.656612873077393e-10f), 1.0f);   // / 2**31 (exact), - 1
}

// f * f * f * (f * (f * 6 - 15) + 10), left to right like torch
__device__ __forceinline__ float quintic(float f) { return M(M(M(f, f), f), A(M(f, S(M(f, 6.0f), 15.0f)), 10.0f)); }

__device__ __forceinline__ float vnoise(float ci, float cj, float inv_period, u32 base) {
    float x = M(ci, inv_period), y = M(cj, inv_period);
    float x0 = floorf(x), y0 = floorf(y);
    float ux = quintic(S(x, x0)), uy = quintic(S(y, y0));
    u32 xi = (u32)(int)x0, yi = (u32)(int)y0;
    float a = corner(xi, yi, base, 0, 0), b = corner(xi, yi, base, 0, 1);
    float c = corner(xi, yi, base, 1, 0), d = corner(xi, yi, base, 1, 1);
    float vy = S(1.0f, uy);
    // (a * (1 - uy) + b * uy) * (1 - ux) + (c * (1 - uy) + d * uy) * ux
    return A(M(A(M(a, vy), M(b, uy)), S(1.0f, ux)), M(A(M(c, vy), M(d, uy)), ux));
}

__device__ __forceinline__ float fbm(const ControlsArgs& p, float ci, float cj, int row, int oct, float inv_norm) {
    float tot = vnoise(ci, cj, p.invp[row], p.bases[row]);
    if (oct > 1) tot = A(tot, M(0.5f, vnoise(ci, cj, p.invp[row + 1], p.bases[row + 1])));
    if (oct > 2) tot = A(tot, M(0.25f, vnoise(ci, cj, p.invp[row + 2], p.bases[row + 2])));
    if (oct > 3) tot = A(tot, M(0.125f, vnoise(ci, cj, p.invp[row + 3], p.bases[row + 3])));
    return M(tot, inv_norm);
}

__device__ __forceinline__ float smoothstep(float x) {
    x = fminf(fmaxf(x, 0.0f), 1.0f);
    return M(M(x, x), S(3.0f, M(2.0f, x)));
}

__device__ __forceinline__ float gauss(float x, float inv_width) {
    float y = M(x, inv_width);
    return expf(-M(y, y));
}

__global__ void controls_kernel(const ControlsArgs p) {
    const int n = p.hc * p.wc;
    const int i = blockIdx.x * blockDim.x + threadIdx.x;
    if (i >= n) return;
    const float* k = p.k;
    float ci = (float)(p.ci0 + i / p.wc);
    float cj = (float)(p.cj0 + i % p.wc);

    float home = expf(M(-A(M(ci, ci), M(cj, cj)), k[K_INV_HOME]));
    float continent = fbm(p, ci, cj, p.r_cont, 4, k[K_INV4]);
    continent = A(continent, M(0.7f, home));
    float land = smoothstep(M(A(continent, 0.35f), k[K_INV_LAND]));
    float wi = A(ci, M(60.0f, fbm(p, ci, cj, p.r_wi, 2, k[K_INV2])));
    float wj = A(cj, M(60.0f, fbm(p, ci, cj, p.r_wj, 2, k[K_INV2])));
    float n_range = M(fbm(p, wi, wj, p.r_range, 2, k[K_INV2]), k[K_INV_STD2]);
    float n_spur = M(fbm(p, wi, wj, p.r_spur, 2, k[K_INV2]), k[K_INV_STD2]);
    float spine = gauss(n_range, k[K_INV_SPINE]);
    float foot = gauss(n_range, k[K_INV_FOOT]);
    float body = gauss(n_range, k[K_INV_BODY]);
    float spur = M(gauss(n_spur, k[K_INV_SPUR]), body);
    float along = smoothstep(M(A(M(fbm(p, ci, cj, p.r_along, 2, k[K_INV2]), k[K_INV_STD2]), 0.2f), k[K_INV_ALONG]));
    float hills = fbm(p, ci, cj, p.r_hills, 4, k[K_INV4]);
    float hilly = smoothstep(M(A(M(fbm(p, ci, cj, p.r_hilly, 3, k[K_INV3]), k[K_INV_STD3]), 0.8f), k[K_INV_HILLY]));
    hilly = fmaxf(hilly, M(0.55f, home));
    // along * (2800 spine + 900 spur + 500 foot)
    float mountains = M(along, A(A(M(2800.0f, spine), M(900.0f, spur)), M(500.0f, foot)));
    // 100 + 260 hilly + 200 along foot
    float hill_m = A(A(100.0f, M(260.0f, hilly)), M(M(200.0f, along), foot));
    // -300 + 600 land (0.6 + 0.5 continent) + land (mountains + hill_m hills)
    float trend = A(A(-300.0f, M(M(600.0f, land), A(0.6f, M(0.5f, continent)))), M(land, A(mountains, M(hill_m, hills))));
    float rough = A(A(A(0.14f, M(0.55f, hilly)), M(along, A(A(M(0.9f, spine), M(0.45f, spur)), M(0.25f, foot)))),
                    M(M(0.25f, A(0.3f, M(along, foot))), fabsf(hills)));
    rough = M(fminf(fmaxf(rough, 0.0f), 1.0f), land);
    float wild = A(k[K_WILD_0], M(rough, k[K_WILD_K]));

    // Climate archetypes: softmax over 7 sharpened scores, blends summed in torch's reduction order.
    float s[7], e[7], w[7];
#pragma unroll
    for (int a = 0; a < 7; ++a) s[a] = M(10.0f, fbm(p, ci, cj, p.r_clim + 2 * a, 2, k[K_INV2]));
    float mx = fmaxf(fmaxf(fmaxf(s[0], s[1]), fmaxf(s[2], s[3])), fmaxf(fmaxf(s[4], s[5]), s[6]));
#pragma unroll
    for (int a = 0; a < 7; ++a) e[a] = expf(S(s[a], mx));
    float tot = A(A(A(A(A(A(e[0], e[1]), e[2]), e[3]), e[4]), e[5]), e[6]);
#pragma unroll
    for (int a = 0; a < 7; ++a) w[a] = __fdiv_rn(e[a], tot);
    float* o = p.out + i;
    o[0] = wild;
    o[n] = trend;
#pragma unroll
    for (int col = 0; col < 4; ++col) {
        float v[7];
#pragma unroll
        for (int a = 0; a < 7; ++a) v[a] = M(w[a], p.arch[a * 4 + col]);
        float r = A(A(A(A(v[0], v[4]), A(v[1], v[5])), A(v[2], v[6])), v[3]);
        if (col == 2) r = expf(r);   // precipitation is blended in log space
        o[(2 + col) * n] = r;
    }
}
