// controls2._exp2 (the EXP-2 world layout) as one CUDA kernel, bit-identical to the torch definition.
// Compiled by NVRTC with --fmad=false -ftz=false -prec-div=true -prec-sqrt=true; the same rules as controls.cu:
//
//   * every fp32 multiply / add / subtract is an explicitly rounded intrinsic (M / A / S), tensor / tensor is IEEE
//     __fdiv_rn (D), exp / sin / cos / tanh / sqrt are CUDA's expf / sinf / cosf / tanhf / sqrtf (what torch calls);
//     denormals are kept.
//   * `tensor / python_scalar` in torch is a multiply by the reciprocal, taken in double from the Python scalar and
//     rounded to fp32 (NOT float32(1) / float32(x): the two differ for 0.12): precomputed in K (INV_*).
//   * a Python float combined with a float32 tensor is rounded to fp32 first: every non-trivial scalar comes from the
//     host table K (values computed from the controls2 / generator constants with Python's own double arithmetic, then
//     rounded), so no decimal literal here is ever re-rounded differently. Literals below are exact in fp32.
//   * `0.0 + tensor` / `tensor + 0` (Python's sum start, `ci + 0` offsets) are real adds: -0 becomes +0, kept.
//   * clamp / clamp_min / maximum / minimum propagate NaN like torch's CUDA kernels; the rest is fminf / fmaxf.
//   * the evaluation order mirrors the torch expression op for op (Python evaluates a * b * c as (a * b) * c).
//
// One thread computes one cell end to end in registers. tests/test_cuda_controls2.py pins equality on every key.

typedef unsigned int u32;
typedef long long i64;

#define NROWS 72
#define NK 160

// fbm rows (first octave index into bases / invp); kernels/cuda_controls2.py checks these against its own table.
enum { R_CONT = 0, R_BASIN = 4, R_HILLY = 6, R_MARGIN = 9, R_THETA = 11, R_RIDGE = 13, R_AHILLS = 14, R_CANYON = 17,
       R_WARP_BELT = 20, R_WARP_LAT = 22, R_ZONE = 24, R_WU = 27, R_WV = 29, R_COAST = 31, R_ISL = 34, R_SKER = 37,
       R_JIT = 40, R_LAG = 43, R_KARST = 45, R_LF = 47, R_S_ROLL = 50, R_S_MOR = 52, R_S_PLAT = 54, R_ROLL_B = 56,
       R_ROLL_F = 58, R_DRUMLIN = 60, R_PLATEAU = 62, R_CONES = 64, R_END = 66 };

// Constant table; kernels/cuda_controls2.py builds K in exactly this order (checked at load).
enum {
    K_INV2, K_INV3, K_INV4, K_INV_STD2, K_INV_STD3,
    K_INV_HOME, K_C07, K_C035, K_INV_LAND,
    K_BASIN_BIAS, K_INV_BASIN_SOFT, K_INV_BASIN_HOME, K_BASIN_PUSH,
    K_EPS,
    K_LOG2, K_ROLLING_BOOST, K_LOG_WILD_FLOOR, K_HILL_BIAS, K_INV_HILL_SOFT,
    K_ABYSS0, K_INV_ABYSS_DOWN, K_INV_ABYSS_UP, K_ABYSS_SPAN_M, K_SHELF_M,
    K_RING_DI0, K_RING_DI1, K_RING_DI2, K_RING_DI3, K_RING_DI4, K_RING_DI5, K_RING_DI6, K_RING_DI7,
    K_RING_DJ0, K_RING_DJ1, K_RING_DJ2, K_RING_DJ3, K_RING_DJ4, K_RING_DJ5, K_RING_DJ6, K_RING_DJ7,
    K_C03, K_INV03, K_TRENCH_LEVEL, K_TRENCH_BIAS, K_INV05, K_PI, K_INV_TRENCH_SIGMA,
    K_C07B, K_INV02, K_INV_RIDGE_SIGMA, K_INV_RIFT_SIGMA, K_HILLS_M, K_C06, K_C04,
    K_INV012, K_C075, K_INV_CANYON_WIDTH, K_NEG_TRENCH_M, K_RIDGE_M, K_RIFT_M, K_CANYON_M,
    K_COLD0, K_INV_COLD_SPAN, K_COAST_BIAS, K_INV08, K_INV_COAST_BAND, K_ISLAND_M,
    K_C02, K_C035B, K_VOLCANO_M, K_VOLCANO_RISE_M, K_INV_HOT_P, K_HOT_P, K_HOT_CHANCE, K_INV_VOLC_R,
    K_INV1500, K_FJORD_M, K_INV800, K_INV40, K_INV06, K_INV30, K_SEA_GATE_MIN, K_INV300, K_KARST_BIAS,
    K_INV_HOME_LAT, K_LAT_OFF, K_TWO_PI, K_INV_BELT_PERIOD, K_BELT_WARP_M, K_STRONG_WARP_M, K_ZONE_SPREAD,
    K_PHI_HOME, K_COS_TH, K_SIN_TH, K_INV10, K_INV8, K_DEG2RAD,
    K_LF_C0, K_INV_LF_C, K_LF_TOP0, K_INV_LF_TOP, K_LF_MIX, K_LF_BIAS, K_INV_LF_SOFT, K_MOR_COLD0, K_INV_MOR_COLD,
    K_MOR_COLD, K_MOR_BIAS, K_LF_SHARP, K_COS_FLOW, K_SIN_FLOW, K_INV_STRETCH, K_PLAT_LEVEL, K_INV_CONE_SHARP,
    K_ROLL_B_M, K_PLAT_M, K_ROLL_F_M, K_MOR_M, K_INV_PLAT_EDGE, K_CONE_M, K_LF_GAIN, K_CONE_LAT0, K_INV_CONE_LAT, K_ROLL_SHARE, K_LF_FLOOR,
    K_KARST2_BIAS, K_KARST_HOME0, K_INV_KARST_HOME,
    K_COUNT
};

// Output channels (each (hc, wc)); GATES spans 7 channels in detail2.STYLES order.
enum { O_WILD, O_HILLY, O_TREND_SEA, O_TREND, O_LAT, O_BELT, O_WIND_U, O_WIND_V, O_COAST, O_LAND, O_TRENCH, O_RIDGE,
       O_CANYON, O_KARST, O_LANDFORM, O_KARST2, O_GATES, O_COUNT = O_GATES + 7 };

// controls2_at_kernel only (v4's procedural_controls_at): its own fbm rows and constants, kept out of the R_* / K_*
// tables so those stay unchanged (the binding parses them; running services keep working across this edit).
#define NROWS_V4 16
#define NKV4 32
enum { V_WARP_I = 0, V_WARP_J = 2, V_RANGE = 4, V_SPUR = 6, V_ALONG = 8, V_HILLS = 10, V_END = 14 };
enum {
    KV4_WARP, KV4_INV_SPINE, KV4_INV_FOOT, KV4_INV_BODY, KV4_INV_SPUR, KV4_ALONG_BIAS, KV4_INV_ALONG, KV4_HOME_MIN,
    KV4_SPINE_M, KV4_SPUR_M, KV4_FOOT_M, KV4_PLAINS_M, KV4_HILL_SPAN, KV4_FOOT_HILL_M, KV4_ROUGH0, KV4_ROUGH_HILLY,
    KV4_ROUGH_SPINE, KV4_ROUGH_SPUR, KV4_ROUGH_FOOT, KV4_ROUGH_HILLS, KV4_ROUGH_HILLS0, KV4_WILD_K,
    KV4_COUNT
};

struct Controls2Args {
    float* out;              // (O_COUNT, hc, wc)
    const float* trend_v4;   // base["trend"], (hc, wc) contiguous
    const float* wild_v4;    // base["wild"]
    int ci0, cj0, hc, wc;
    int layout;              // 0 belts, 1 zones, 2 latitude
    u32 hot_base;
    u32 bases[NROWS];        // per-octave hash bases
    float invp[NROWS];       // per-octave fp32 1 / period
    float k[NK];
    // controls2_at_kernel only
    const float* ci_at;      // (hc * wc) arbitrary cell coordinates
    const float* cj_at;
    u32 bases_v4[NROWS_V4];
    float invp_v4[NROWS_V4];
    float kv4[NKV4];
};

#define M(a, b) __fmul_rn((a), (b))
#define A(a, b) __fadd_rn((a), (b))
#define S(a, b) __fsub_rn((a), (b))
#define D(a, b) __fdiv_rn((a), (b))

__device__ __forceinline__ u32 hash32(u32 x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}

// torch's CUDA clamp / clamp_min / maximum / minimum: NaN propagates, else ::min / ::max (fminf / fmaxf).
__device__ __forceinline__ float t_clamp(float v, float lo, float hi) { return isnan(v) ? v : fminf(fmaxf(v, lo), hi); }
__device__ __forceinline__ float t_clamp_min(float v, float lo) { return isnan(v) ? v : fmaxf(v, lo); }
__device__ __forceinline__ float t_maximum(float a, float b) { return isnan(a) ? a : (isnan(b) ? b : fmaxf(a, b)); }
__device__ __forceinline__ float t_minimum(float a, float b) { return isnan(a) ? a : (isnan(b) ? b : fminf(a, b)); }
__device__ __forceinline__ float t_sign(float a) { return (float)((0.0f < a) - (a < 0.0f)); }
__device__ __forceinline__ float sq(float x) { return M(x, x); }   // x ** 2

// G._smoothstep: x = clamp(x, 0, 1); x * x * (3 - 2 * x)
__device__ __forceinline__ float ss(float x) {
    x = t_clamp(x, 0.0f, 1.0f);
    return M(M(x, x), S(3.0f, M(x, 2.0f)));
}

// exp(-(x / w) ** 2) with 1 / w given
__device__ __forceinline__ float gauss(float x, float inv_w) { return expf(-sq(M(x, inv_w))); }

__device__ __forceinline__ float corner(u32 xi, u32 yi, u32 base, u32 dx, u32 dy) {
    u32 h = hash32(hash32((xi + dx) ^ base) ^ (yi + dy));
    return S(M(__uint2float_rn(h), 4.656612873077393e-10f), 1.0f);   // h.float() / 2**31 (exact) - 1
}

__device__ __forceinline__ float quintic(float f) { return M(M(M(f, f), f), A(M(f, S(M(f, 6.0f), 15.0f)), 10.0f)); }

// G._value_noise
__device__ __forceinline__ float vnoise(float ci, float cj, float inv_period, u32 base) {
    float x = M(ci, inv_period), y = M(cj, inv_period);
    float x0 = floorf(x), y0 = floorf(y);
    float ux = quintic(S(x, x0)), uy = quintic(S(y, y0));
    u32 xi = (u32)(i64)x0, yi = (u32)(i64)y0;
    float a = corner(xi, yi, base, 0, 0), b = corner(xi, yi, base, 0, 1);
    float c = corner(xi, yi, base, 1, 0), d = corner(xi, yi, base, 1, 1);
    float vy = S(1.0f, uy);
    return A(M(A(M(a, vy), M(b, uy)), S(1.0f, ux)), M(A(M(c, vy), M(d, uy)), ux));
}

// G._fbm: tot = 0.0 + 1.0 * n0 (+ 0.5 * n1 ...), then tot / norm (norm 1.0 is a multiply by 1)
template <int OCT>
__device__ __forceinline__ float fbm(const Controls2Args& p, float ci, float cj, int row) {
    float tot = A(vnoise(ci, cj, p.invp[row], p.bases[row]), 0.0f);
    if (OCT > 1) tot = A(tot, M(0.5f, vnoise(ci, cj, p.invp[row + 1], p.bases[row + 1])));
    if (OCT > 2) tot = A(tot, M(0.25f, vnoise(ci, cj, p.invp[row + 2], p.bases[row + 2])));
    if (OCT > 3) tot = A(tot, M(0.125f, vnoise(ci, cj, p.invp[row + 3], p.bases[row + 3])));
    const float inv = OCT == 1 ? 1.0f : OCT == 2 ? p.k[K_INV2] : OCT == 3 ? p.k[K_INV3] : p.k[K_INV4];
    return M(tot, inv);
}

// controls2.continent2 -> c2; also G._continent's land weight (land_v4) when asked
__device__ __forceinline__ float continent2(const Controls2Args& p, float ci, float cj, float* land_v4) {
    const float* k = p.k;
    float r2 = A(sq(ci), sq(cj));
    // G._continent
    float cont = A(fbm<4>(p, ci, cj, R_CONT), M(expf(M(-r2, k[K_INV_HOME])), k[K_C07]));
    if (land_v4) *land_v4 = ss(M(A(cont, k[K_C035]), k[K_INV_LAND]));
    // basins
    float b = ss(M(A(M(fbm<2>(p, ci, cj, R_BASIN), k[K_INV_STD2]), k[K_BASIN_BIAS]), k[K_INV_BASIN_SOFT]));
    float home = expf(M(-r2, k[K_INV_BASIN_HOME]));
    float basin = M(b, S(1.0f, home));
    return S(cont, M(basin, k[K_BASIN_PUSH]));
}

// controls2.latitude_deg; warp0 = the belts' warp at the origin (unused otherwise)
__device__ __forceinline__ float latitude(const Controls2Args& p, float ci, float cj, float warp0) {
    const float* k = p.k;
    float home = expf(M(-A(sq(ci), sq(cj)), k[K_INV_HOME_LAT]));
    float phi;
    if (p.layout == 2) {
        float warp = M(M(fbm<2>(p, ci, cj, R_WARP_LAT), k[K_BELT_WARP_M]), k[K_INV_STD2]);
        float t = A(A(-ci, k[K_LAT_OFF]), warp);
        float l = M(M(sinf(M(M(t, k[K_TWO_PI]), k[K_INV_BELT_PERIOD])), 90.0f), S(1.0f, home));
        return A(l, M(home, k[K_PHI_HOME]));
    }
    if (p.layout == 1) {
        float z = M(fbm<3>(p, ci, cj, R_ZONE), k[K_INV_STD3]);
        phi = M(tanhf(M(z, k[K_ZONE_SPREAD])), 90.0f);
    } else {
        float s = A(M(ci, k[K_COS_TH]), M(cj, k[K_SIN_TH]));
        float warp = M(M(fbm<2>(p, ci, cj, R_WARP_BELT), k[K_STRONG_WARP_M]), k[K_INV_STD2]);
        float t = A(S(A(s, warp), warp0), k[K_LAT_OFF]);
        phi = M(sinf(M(M(t, k[K_TWO_PI]), k[K_INV_BELT_PERIOD])), 90.0f);
    }
    return A(M(phi, S(1.0f, home)), M(home, k[K_PHI_HOME]));
}

// controls2.hotspots
__device__ __forceinline__ float hotspots(const Controls2Args& p, float ci, float cj) {
    const float* k = p.k;
    const float P = k[K_HOT_P];
    i64 gi = (i64)floorf(M(ci, k[K_INV_HOT_P])), gj = (i64)floorf(M(cj, k[K_INV_HOT_P]));
    float out = 0.0f;
    for (int di = -1; di <= 1; ++di) {
        for (int dj = -1; dj <= 1; ++dj) {
            i64 a = gi + di, b = gj + dj;
            u32 h = hash32(hash32((u32)a ^ p.hot_base) ^ (u32)b);
            float u[3];
#pragma unroll
            for (int q = 0; q < 3; ++q) {
                u32 hk = hash32(h ^ (u32)(0x51ED27 * (q + 1)));
                // (h.float() / 2**32) % 1.0: torch remainder = fmod (+ b when signs differ; never here)
                float x = M(__uint2float_rn(hk), 2.3283064365386963e-10f);
                float m = fmodf(x, 1.0f);
                if (m != 0.0f && ((1.0f < 0.0f) != (m < 0.0f))) m = A(m, 1.0f);
                u[q] = m;
            }
            float alive = u[0] < k[K_HOT_CHANCE] ? 1.0f : 0.0f;
            float pi = A(M(__ll2float_rn(a), P), M(u[1], P));
            float pj = A(M(__ll2float_rn(b), P), M(u[2], P));
            float d2 = A(sq(S(ci, pi)), sq(S(cj, pj)));
            out = t_maximum(out, M(alive, expf(M(-d2, k[K_INV_VOLC_R]))));
        }
    }
    return out;
}

// controls2.landforms -> (broad, fine) metres
__device__ __forceinline__ void landforms(const Controls2Args& p, float ci, float cj, float trend0, float land_w, float phi,
                                          float n_hill, float karst_p, float* broad, float* fine) {
    const float* k = p.k;
    const float low = M(M(land_w, ss(M(S(trend0, k[K_LF_C0]), k[K_INV_LF_C]))),
                        S(1.0f, ss(M(S(trend0, k[K_LF_TOP0]), k[K_INV_LF_TOP]))));
    const float inten = ss(M(A(A(n_hill, M(M(fbm<3>(p, ci, cj, R_LF), k[K_INV_STD3]), k[K_LF_MIX])), k[K_LF_BIAS]),
                             k[K_INV_LF_SOFT]));
    const float glacial = ss(M(S(fabsf(phi), k[K_MOR_COLD0]), k[K_INV_MOR_COLD]));
    const float s_roll = M(fbm<2>(p, ci, cj, R_S_ROLL), k[K_INV_STD2]);
    const float s_mor = S(A(M(fbm<2>(p, ci, cj, R_S_MOR), k[K_INV_STD2]), M(glacial, k[K_MOR_COLD])), k[K_MOR_BIAS]);
    const float s_plat = M(fbm<2>(p, ci, cj, R_S_PLAT), k[K_INV_STD2]);
    const float m = t_maximum(t_maximum(s_roll, s_mor), s_plat);
    const float e_roll = expf(M(S(s_roll, m), k[K_LF_SHARP]));
    const float e_mor = expf(M(S(s_mor, m), k[K_LF_SHARP]));
    const float e_plat = expf(M(S(s_plat, m), k[K_LF_SHARP]));
    const float tot = A(A(e_roll, e_mor), e_plat);
    const float cone_w = M(karst_p, S(1.0f, ss(M(S(fabsf(phi), k[K_CONE_LAT0]), k[K_INV_CONE_LAT]))));
    const float keep = S(1.0f, cone_w);
    const float w_roll = M(D(e_roll, tot), keep), w_mor = M(D(e_mor, tot), keep), w_plat = M(D(e_plat, tot), keep);
    const float roll_w = A(w_roll, M(A(w_mor, w_plat), k[K_ROLL_SHARE]));
    const float rb = M(fbm<2>(p, ci, cj, R_ROLL_B), k[K_INV_STD2]);
    const float rf = M(fbm<2>(p, ci, cj, R_ROLL_F), k[K_INV_STD2]);
    const float u = M(A(M(ci, k[K_COS_FLOW]), M(cj, k[K_SIN_FLOW])), k[K_INV_STRETCH]);
    const float v = A(M(-ci, k[K_SIN_FLOW]), M(cj, k[K_COS_FLOW]));
    const float mor = S(ss(A(M(M(fbm<2>(p, u, v, R_DRUMLIN), k[K_INV_STD2]), k[K_C06]), 0.5f)), 0.5f);
    const float mesa = S(ss(M(S(M(fbm<2>(p, ci, cj, R_PLATEAU), k[K_INV_STD2]), k[K_PLAT_LEVEL]), k[K_INV_PLAT_EDGE])), 0.5f);
    const float cones = ss(M(M(fbm<2>(p, ci, cj, R_CONES), k[K_INV_STD2]), k[K_INV_CONE_SHARP]));
    const float a = M(M(inten, low), k[K_LF_GAIN]);
    const float room = t_minimum(0.0f, S(k[K_LF_FLOOR], trend0));
    const float half = M(room, 0.5f);
    *broad = t_maximum(M(a, M(M(roll_w, k[K_ROLL_B_M]), rb)), half);
    *fine = t_maximum(M(a, A(A(A(M(M(roll_w, k[K_ROLL_F_M]), rf), M(M(w_mor, k[K_MOR_M]), mor)),
                               M(M(w_plat, k[K_PLAT_M]), mesa)), M(M(cone_w, k[K_CONE_M]), cones))), half);
}

// G._fbm over the v4 rows (bases_v4 / invp_v4), same op order as fbm<OCT>
template <int OCT>
__device__ __forceinline__ float fbm_v4(const Controls2Args& p, float ci, float cj, int row) {
    float tot = A(vnoise(ci, cj, p.invp_v4[row], p.bases_v4[row]), 0.0f);
    if (OCT > 1) tot = A(tot, M(0.5f, vnoise(ci, cj, p.invp_v4[row + 1], p.bases_v4[row + 1])));
    if (OCT > 2) tot = A(tot, M(0.25f, vnoise(ci, cj, p.invp_v4[row + 2], p.bases_v4[row + 2])));
    if (OCT > 3) tot = A(tot, M(0.125f, vnoise(ci, cj, p.invp_v4[row + 3], p.bases_v4[row + 3])));
    const float inv = OCT == 1 ? 1.0f : OCT == 2 ? p.k[K_INV2] : OCT == 3 ? p.k[K_INV3] : p.k[K_INV4];
    return M(tot, inv);
}

// generator.procedural_controls_at's wild and trend (v4's base) at one point: controls2_at_kernel's input. Mirrors the
// torch definition op for op (its climate archetypes are not needed by controls2_at's users and are not computed).
__device__ __forceinline__ void v4_base(const Controls2Args& p, float ci, float cj, float* trend, float* wild) {
    const float* k = p.k;
    const float* kv = p.kv4;
    const float home = expf(M(-A(sq(ci), sq(cj)), k[K_INV_HOME]));
    const float cont = A(fbm<4>(p, ci, cj, R_CONT), M(home, k[K_C07]));
    const float land = ss(M(A(cont, k[K_C035]), k[K_INV_LAND]));
    const float wi = A(ci, M(fbm_v4<2>(p, ci, cj, V_WARP_I), kv[KV4_WARP]));
    const float wj = A(cj, M(fbm_v4<2>(p, ci, cj, V_WARP_J), kv[KV4_WARP]));
    const float n_range = M(fbm_v4<2>(p, wi, wj, V_RANGE), k[K_INV_STD2]);
    const float n_spur = M(fbm_v4<2>(p, wi, wj, V_SPUR), k[K_INV_STD2]);
    const float spine = gauss(n_range, kv[KV4_INV_SPINE]);
    const float foot = gauss(n_range, kv[KV4_INV_FOOT]);
    const float body = gauss(n_range, kv[KV4_INV_BODY]);
    const float spur = M(gauss(n_spur, kv[KV4_INV_SPUR]), body);
    const float along = ss(M(A(M(fbm_v4<2>(p, ci, cj, V_ALONG), k[K_INV_STD2]), kv[KV4_ALONG_BIAS]), kv[KV4_INV_ALONG]));
    const float hills = fbm_v4<4>(p, ci, cj, V_HILLS);
    float hilly = ss(M(A(M(fbm<3>(p, ci, cj, R_HILLY), k[K_INV_STD3]), k[K_HILL_BIAS]), k[K_INV_HILL_SOFT]));
    hilly = t_maximum(hilly, M(expf(M(-A(sq(ci), sq(cj)), k[K_INV_HOME])), kv[KV4_HOME_MIN]));
    const float mountains = M(along, A(A(M(spine, kv[KV4_SPINE_M]), M(spur, kv[KV4_SPUR_M])), M(foot, kv[KV4_FOOT_M])));
    const float hill_m = A(A(kv[KV4_PLAINS_M], M(hilly, kv[KV4_HILL_SPAN])), M(M(along, kv[KV4_FOOT_HILL_M]), foot));
    *trend = A(A(-300.0f, M(M(land, 600.0f), A(M(cont, 0.5f), k[K_C06]))), M(land, A(mountains, M(hill_m, hills))));
    float rough = A(A(A(kv[KV4_ROUGH0], M(hilly, kv[KV4_ROUGH_HILLY])),
                      M(along, A(A(M(spine, kv[KV4_ROUGH_SPINE]), M(spur, kv[KV4_ROUGH_SPUR])), M(foot, kv[KV4_ROUGH_FOOT])))),
                    M(M(A(M(along, foot), kv[KV4_ROUGH_HILLS0]), kv[KV4_ROUGH_HILLS]), fabsf(hills)));
    rough = M(t_clamp(rough, 0.0f, 1.0f), land);
    *wild = A(M(rough, kv[KV4_WILD_K]), k[K_LOG2]);
}

// One cell of controls2._exp2 at (ci, cj) with v4's base (trend_v4, wild_v4) there.
__device__ __forceinline__ void cell(const Controls2Args& p, const int idx, const int n, const float ci, const float cj,
                                     const float trend_v4, const float wild_v4) {
    const float* k = p.k;

    // ---- ocean basins over v4's land (G._continent + continent2 at the cell)
    float land_v4;
    const float continent = continent2(p, ci, cj, &land_v4);
    const float land_w = ss(M(A(continent, k[K_C035]), k[K_INV_LAND]));
    const float r = D(land_w, t_clamp_min(land_v4, k[K_EPS]));
    const bool on_v4 = land_v4 > 0.0f;
    float trend0 = on_v4 ? A(M(A(trend_v4, 300.0f), r), -300.0f) : trend_v4;
    const float wsel = on_v4 ? r : 1.0f;
    float wild = A(M(wild_v4, wsel), M(S(1.0f, wsel), k[K_LOG2]));
    const float n_hill = M(fbm<3>(p, ci, cj, R_HILLY), k[K_INV_STD3]);
    const float rolling = ss(M(A(n_hill, k[K_HILL_BIAS]), k[K_INV_HILL_SOFT]));
    wild = t_maximum(A(wild, M(M(rolling, k[K_ROLLING_BOOST]), land_w)),
                     A(A(M(wild, 0.0f), M(land_w, k[K_LOG_WILD_FLOOR])), M(wild, S(1.0f, land_w))));
    const float hilly = M(rolling, land_w);
    const float deep = ss(M(S(continent, k[K_ABYSS0]), k[K_INV_ABYSS_DOWN]));
    const float ocean_floor = A(M(deep, k[K_ABYSS_SPAN_M]), k[K_SHELF_M]);

    // ---- ocean_tectonics
    float ring = 0.0f;
#pragma unroll 1
    for (int q = 0; q < 8; ++q) {
        float c = continent2(p, A(ci, k[K_RING_DI0 + q]), A(cj, k[K_RING_DJ0 + q]), nullptr);
        float t = M(ss(M(S(k[K_ABYSS0], c), k[K_INV_ABYSS_UP])), 0.125f);
        ring = q == 0 ? A(t, 0.0f) : A(ring, t);
    }
    const float big = ss(M(S(ring, k[K_C03]), k[K_INV03]));
    const float cs8 = continent2(p, A(ci, 1.0f), A(cj, 0.0f), nullptr);
    const float cs9 = continent2(p, A(ci, -1.0f), A(cj, 0.0f), nullptr);
    const float cs10 = continent2(p, A(ci, 0.0f), A(cj, 1.0f), nullptr);
    const float cs11 = continent2(p, A(ci, 0.0f), A(cj, -1.0f), nullptr);
    const float c_i = M(S(cs8, cs9), 0.5f), c_j = M(S(cs10, cs11), 0.5f);
    const float g_n = t_clamp_min(sqrtf(A(sq(c_i), sq(c_j))), k[K_EPS]);
    const float d_trench = D(S(continent, k[K_TRENCH_LEVEL]), g_n);
    const float margin = ss(M(A(M(fbm<2>(p, ci, cj, R_MARGIN), k[K_INV_STD2]), k[K_TRENCH_BIAS]), k[K_INV05]));
    const float theta = M(M(fbm<2>(p, ci, cj, R_THETA), k[K_PI]), k[K_INV_STD2]);
    const float facing = D(A(M(c_i, cosf(theta)), M(c_j, sinf(theta))), g_n);
    const float side = ss(M(S(facing, 0.25f), k[K_INV05]));
    const float trench = M(M(M(M(big, margin), side), gauss(d_trench, k[K_INV_TRENCH_SIGMA])), ss(M(deep, 3.0f)));
    const float rs0 = fbm<1>(p, A(ci, 0.0f), A(cj, 0.0f), R_RIDGE);
    const float rs1 = fbm<1>(p, A(ci, 1.0f), A(cj, 0.0f), R_RIDGE);
    const float rs2 = fbm<1>(p, A(ci, -1.0f), A(cj, 0.0f), R_RIDGE);
    const float rs3 = fbm<1>(p, A(ci, 0.0f), A(cj, 1.0f), R_RIDGE);
    const float rs4 = fbm<1>(p, A(ci, 0.0f), A(cj, -1.0f), R_RIDGE);
    const float rg_i = M(S(rs1, rs2), 0.5f), rg_j = M(S(rs3, rs4), 0.5f);
    const float d_ridge = D(rs0, t_clamp_min(sqrtf(A(sq(rg_i), sq(rg_j))), k[K_EPS]));
    const float far = M(big, ss(M(S(deep, k[K_C07B]), k[K_INV02])));
    const float rise = gauss(d_ridge, k[K_INV_RIDGE_SIGMA]);
    const float ridge = M(far, rise);
    const float rift = M(far, gauss(d_ridge, k[K_INV_RIFT_SIGMA]));
    const float ahills = M(M(M(M(deep, k[K_HILLS_M]), A(M(rise, k[K_C06]), k[K_C04])), fbm<3>(p, ci, cj, R_AHILLS)),
                           k[K_INV_STD3]);
    const float slope_zone = M(ss(M(deep, k[K_INV012])), S(1.0f, ss(M(S(deep, k[K_C075]), k[K_INV02]))));
    const float ncan = M(fbm<3>(p, ci, cj, R_CANYON), k[K_INV_STD3]);
    const float canyon = M(M(slope_zone, gauss(ncan, k[K_INV_CANYON_WIDTH])), S(1.0f, trench));
    const float tecto = S(A(S(A(M(trench, k[K_NEG_TRENCH_M]), M(ridge, k[K_RIDGE_M])), M(rift, k[K_RIFT_M])), ahills),
                          M(M(canyon, k[K_CANYON_M]), A(M(deep, k[K_C06]), k[K_C04])));
    if (land_w < 1.0f) trend0 = A(trend0, M(S(1.0f, land_w), A(A(ocean_floor, 300.0f), tecto)));

    // ---- latitude
    float warp0 = 0.0f;
    if (p.layout == 0) warp0 = M(M(fbm<2>(p, 0.0f, 0.0f, R_WARP_BELT), k[K_STRONG_WARP_M]), k[K_INV_STD2]);
    const float phi = latitude(p, ci, cj, warp0);
    const float cold = ss(M(S(fabsf(phi), k[K_COLD0]), k[K_INV_COLD_SPAN]));
    const float z_karst = M(fbm<2>(p, ci, cj, R_KARST), k[K_INV_STD2]);
    const float karst_p = ss(M(A(z_karst, k[K_KARST_BIAS]), k[K_INV03]));
    // v2 worlds' karst (FX3): 1 in 8 of land, none around spawn
    const float karst_p2 = M(ss(M(A(z_karst, k[K_KARST2_BIAS]), k[K_INV03])),
                             ss(M(S(sqrtf(A(sq(ci), sq(cj))), k[K_KARST_HOME0]), k[K_INV_KARST_HOME])));
    float lf_broad, lf_fine;
    landforms(p, ci, cj, trend0, land_w, phi, n_hill, karst_p, &lf_broad, &lf_fine);

    // ---- coast complexity, islands
    const float coast = ss(M(A(M(fbm<3>(p, ci, cj, R_COAST), k[K_INV_STD3]), k[K_COAST_BIAS]), k[K_INV08]));
    const float band = gauss(trend0, k[K_INV_COAST_BAND]);
    const float isl = M(fbm<3>(p, ci, cj, R_ISL), k[K_INV_STD3]);
    const float sker = M(fbm<3>(p, ci, cj, R_SKER), k[K_INV_STD3]);
    const float noise = A(M(S(1.0f, cold), isl), M(cold, A(M(isl, k[K_C06]), M(sker, k[K_C04]))));
    float trend = A(trend0, M(M(M(coast, band), k[K_ISLAND_M]), noise));
    trend = A(trend, lf_broad);

    // ---- hotspots
    const float spot = hotspots(p, ci, cj);
    const float jitter = M(fbm<3>(p, ci, cj, R_JIT), k[K_INV_STD3]);
    const float cone = M(spot, t_clamp(A(M(jitter, k[K_C02]), 1.0f), 0.5f, 1.5f));
    const float peak = t_minimum(k[K_VOLCANO_M], A(trend, M(A(M(jitter, k[K_C035B]), 1.0f), k[K_VOLCANO_RISE_M])));
    trend = t_maximum(trend, A(trend, M(t_clamp(cone, 0.0f, 1.0f), S(peak, trend))));

    // ---- wind
    float wu, wv;
    {
        const float a = fabsf(phi);
        const float w_trade = S(1.0f, ss(M(S(a, 25.0f), k[K_INV10])));
        const float w_polar = ss(M(S(a, 58.0f), k[K_INV8]));
        const float w_west = M(S(1.0f, w_trade), S(1.0f, w_polar));
        const float zonal = S(A(M(w_trade, -6.0f), M(w_west, 7.0f)), M(w_polar, 3.0f));
        const float merid = S(A(M(w_trade, -2.0f), M(w_west, 1.0f)), M(w_polar, 1.0f));
        const float ls0 = latitude(p, A(ci, 1.0f), A(cj, 0.0f), warp0);
        const float ls1 = latitude(p, A(ci, -1.0f), A(cj, 0.0f), warp0);
        const float ls2 = latitude(p, A(ci, 0.0f), A(cj, 1.0f), warp0);
        const float ls3 = latitude(p, A(ci, 0.0f), A(cj, -1.0f), warp0);
        const float gi = M(S(ls0, ls1), 0.5f), gj = M(S(ls2, ls3), 0.5f);
        const float gn = t_clamp_min(sqrtf(A(M(gi, gi), M(gj, gj))), k[K_EPS]);
        const float ni = D(gi, gn), nj = D(gj, gn);
        const float ei = nj, ej = -ni;
        const float pole = t_sign(phi);
        const float wi = A(M(zonal, ei), M(M(merid, pole), ni));
        const float wj = A(M(zonal, ej), M(M(merid, pole), nj));
        wu = A(wj, M(M(fbm<2>(p, ci, cj, R_WU), 1.5f), k[K_INV_STD2]));
        wv = A(-wi, M(M(fbm<2>(p, ci, cj, R_WV), 1.5f), k[K_INV_STD2]));
    }

    // ---- gates
    const float land = trend > 0.0f ? 1.0f : 0.0f;
    const float near_coast = A(gauss(trend, k[K_INV1500]), 0.0f);
    const float mountains = ss(M(S(trend0, k[K_FJORD_M]), k[K_INV800]));
    const float g_fjord = M(M(cold, ss(M(mountains, 1.5f))), ss(M(near_coast, 2.0f)));
    const float g_volc = ss(M(spot, 2.0f));
    const float g_drown = M(M(coast, band), S(1.0f, cold));
    const float g_sker = M(M(M(coast, band), cold), S(1.0f, mountains));
    const float low_gentle = M(M(S(1.0f, mountains), gauss(trend0, k[K_INV40])), S(1.0f, coast));
    const float lag = ss(M(S(M(fbm<2>(p, ci, cj, R_LAG), k[K_INV_STD2]), k[K_C02]), k[K_INV06]));
    const float g_lag = M(M(low_gentle, lag), S(1.0f, cold));
    const float g_under = M(ss(M(-trend, k[K_INV30])), ss(M(S(trend, k[K_SEA_GATE_MIN]), k[K_INV300])));
    const float karst = M(karst_p, land);
    const float karst2 = M(karst_p2, land);

    float* o = p.out + idx;
    o[O_WILD * n] = wild;
    o[O_HILLY * n] = hilly;
    o[O_TREND_SEA * n] = trend0;
    o[O_TREND * n] = trend;
    o[O_LAT * n] = phi;
    o[O_BELT * n] = sinf(M(phi, k[K_DEG2RAD]));
    o[O_WIND_U * n] = wu;
    o[O_WIND_V * n] = wv;
    o[O_COAST * n] = coast;
    o[O_LAND * n] = land;
    o[O_TRENCH * n] = trench;
    o[O_RIDGE * n] = ridge;
    o[O_CANYON * n] = canyon;
    o[O_KARST * n] = karst;
    o[O_LANDFORM * n] = lf_fine;
    o[O_KARST2 * n] = karst2;
    // glacial_fjord, volcanic, drowned_archipelago, skerries, lagoon_barrier, aeolian (0: filled later), undersea
    o[(O_GATES + 0) * n] = t_clamp(g_fjord, 0.0f, 1.0f);
    o[(O_GATES + 1) * n] = t_clamp(g_volc, 0.0f, 1.0f);
    o[(O_GATES + 2) * n] = t_clamp(g_drown, 0.0f, 1.0f);
    o[(O_GATES + 3) * n] = t_clamp(g_sker, 0.0f, 1.0f);
    o[(O_GATES + 4) * n] = t_clamp(g_lag, 0.0f, 1.0f);
    o[(O_GATES + 5) * n] = 0.0f;
    o[(O_GATES + 6) * n] = t_clamp(g_under, 0.0f, 1.0f);
}

extern "C" __global__ void controls2_kernel(const __grid_constant__ Controls2Args p) {
    const int n = p.hc * p.wc;
    const int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= n) return;
    cell(p, idx, n, (float)(p.ci0 + idx / p.wc), (float)(p.cj0 + idx % p.wc), p.trend_v4[idx], p.wild_v4[idx]);
}

// controls2.controls2_at: cells at arbitrary float coordinates (the climate node's sparse grids), v4's base computed
// here; one extra output channel O_COUNT = the base trend ("trend_v4").
extern "C" __global__ void controls2_at_kernel(const __grid_constant__ Controls2Args p) {
    const int n = p.hc * p.wc;
    const int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= n) return;
    const float ci = p.ci_at[idx], cj = p.cj_at[idx];
    float trend_v4, wild_v4;
    v4_base(p, ci, cj, &trend_v4, &wild_v4);
    p.out[O_COUNT * n + idx] = trend_v4;
    cell(p, idx, n, ci, cj, trend_v4, wild_v4);
}
