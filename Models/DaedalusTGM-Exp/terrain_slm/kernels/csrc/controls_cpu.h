// `generator.procedural_controls` on the CPU (included by unet_cpu.cpp, which owns the thread pool).
//
// Mirrors the GPU definition (csrc/controls.cu) op for op, so CPU and GPU worlds start from the same
// controls: every multiply / add a separately rounded fp32 op (built with -ffp-contract=off, no
// fast-math), `tensor / scalar` as a multiply by the fp32 reciprocal (torch's CUDA division), softmax
// as exp(x - max) / sequential sum, and the archetype blend summed in torch's CUDA 4-accumulator order.
// The one difference is exp: std::exp (glibc expf, correctly rounded) where CUDA's expf is within 2
// ulp, so a value here may differ from the GPU by an ulp wherever an exp feeds it.
#pragma once
#include <cmath>
#include <cstdint>

struct ControlsArgs {
    float* out;            // (6, hc, wc): wild, trend, t0, tseason, precip, pcv
    const uint32_t* bases; // fbm octave hash bases, rows as in kernels/cuda_controls.py _FBM
    const float* invp;     // fp32 reciprocal periods per octave
    const float* arch;     // (7, 4) archetypes, column 2 = log(precip)
    const float* k;        // constants, see controls_cpu.py K_NAMES
    const int* rows;       // row index per fbm: continent, warp_i, warp_j, range, spur, along, hills, hilly, climate0
    int ci0, cj0, hc, wc;
};

namespace ctl {

enum { INV2, INV3, INV4, INV_HOME, INV_LAND, INV_STD2, INV_STD3, INV_SPINE, INV_FOOT, INV_BODY, INV_SPUR,
       INV_ALONG, INV_HILLY, WILD_K, WILD_0 };

inline uint32_t hash32(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7FEB352Du;
    x ^= x >> 15;
    x *= 0x846CA68Bu;
    return x ^ (x >> 16);
}

// volatile-free: -ffp-contract=off keeps every * and + separately rounded
inline float corner(uint32_t xi, uint32_t yi, uint32_t base, uint32_t dx, uint32_t dy) {
    const uint32_t h = hash32(hash32((xi + dx) ^ base) ^ (yi + dy));
    return (float)h * 4.656612873077393e-10f - 1.0f;
}

inline float quintic(float f) { return ((f * f) * f) * ((f * ((f * 6.0f) - 15.0f)) + 10.0f); }

inline float vnoise(float ci, float cj, float inv_period, uint32_t base) {
    const float x = ci * inv_period, y = cj * inv_period;
    const float x0 = std::floor(x), y0 = std::floor(y);
    const float ux = quintic(x - x0), uy = quintic(y - y0);
    const uint32_t xi = (uint32_t)(int32_t)x0, yi = (uint32_t)(int32_t)y0;
    const float a = corner(xi, yi, base, 0, 0), b = corner(xi, yi, base, 0, 1);
    const float c = corner(xi, yi, base, 1, 0), d = corner(xi, yi, base, 1, 1);
    const float vy = 1.0f - uy;
    return ((a * vy) + (b * uy)) * (1.0f - ux) + ((c * vy) + (d * uy)) * ux;
}

inline float fbm(const ControlsArgs& A, float ci, float cj, int row, int oct, float inv_norm) {
    float tot = vnoise(ci, cj, A.invp[row], A.bases[row]);
    if (oct > 1) tot = tot + 0.5f * vnoise(ci, cj, A.invp[row + 1], A.bases[row + 1]);
    if (oct > 2) tot = tot + 0.25f * vnoise(ci, cj, A.invp[row + 2], A.bases[row + 2]);
    if (oct > 3) tot = tot + 0.125f * vnoise(ci, cj, A.invp[row + 3], A.bases[row + 3]);
    return tot * inv_norm;
}

inline float smoothstep(float x) {
    x = std::fmin(std::fmax(x, 0.0f), 1.0f);
    return (x * x) * (3.0f - (2.0f * x));
}

inline float gauss(float x, float inv_width) {
    const float y = x * inv_width;
    return std::exp(-(y * y));
}

inline void cell(const ControlsArgs& A, long idx, int i, int j) {
    const float* K = A.k;
    const int* R = A.rows;
    const float ci = (float)(A.ci0 + i), cj = (float)(A.cj0 + j);
    const long n = (long)A.hc * A.wc;
    const float home = std::exp((-((ci * ci) + (cj * cj))) * K[INV_HOME]);
    float continent = fbm(A, ci, cj, R[0], 4, K[INV4]);
    continent = continent + 0.7f * home;
    const float land = smoothstep((continent + 0.35f) * K[INV_LAND]);
    const float wi = ci + 60.0f * fbm(A, ci, cj, R[1], 2, K[INV2]);
    const float wj = cj + 60.0f * fbm(A, ci, cj, R[2], 2, K[INV2]);
    const float n_range = fbm(A, wi, wj, R[3], 2, K[INV2]) * K[INV_STD2];
    const float n_spur = fbm(A, wi, wj, R[4], 2, K[INV2]) * K[INV_STD2];
    const float spine = gauss(n_range, K[INV_SPINE]);
    const float foot = gauss(n_range, K[INV_FOOT]);
    const float body = gauss(n_range, K[INV_BODY]);
    const float spur = gauss(n_spur, K[INV_SPUR]) * body;
    const float along = smoothstep(((fbm(A, ci, cj, R[5], 2, K[INV2]) * K[INV_STD2]) + 0.2f) * K[INV_ALONG]);
    const float hills = fbm(A, ci, cj, R[6], 4, K[INV4]);
    float hilly = smoothstep(((fbm(A, ci, cj, R[7], 3, K[INV3]) * K[INV_STD3]) + 0.8f) * K[INV_HILLY]);
    hilly = std::fmax(hilly, 0.55f * home);
    const float mountains = along * (((2800.0f * spine) + (900.0f * spur)) + (500.0f * foot));
    const float hill_m = (100.0f + (260.0f * hilly)) + ((200.0f * along) * foot);
    const float trend = (-300.0f + ((600.0f * land) * (0.6f + (0.5f * continent)))) +
                        (land * (mountains + (hill_m * hills)));
    float rough = ((0.14f + (0.55f * hilly)) + (along * (((0.9f * spine) + (0.45f * spur)) + (0.25f * foot)))) +
                  ((0.25f * (0.3f + (along * foot))) * std::fabs(hills));
    rough = std::fmin(std::fmax(rough, 0.0f), 1.0f) * land;
    const float wild = K[WILD_0] + (rough * K[WILD_K]);

    float s[7];
    for (int q = 0; q < 7; ++q) s[q] = 10.0f * fbm(A, ci, cj, R[8] + 2 * q, 2, K[INV2]);
    const float mx = std::fmax(std::fmax(std::fmax(s[0], s[1]), std::fmax(s[2], s[3])),
                               std::fmax(std::fmax(s[4], s[5]), s[6]));
    float e[7], w[7];
    for (int q = 0; q < 7; ++q) e[q] = std::exp(s[q] - mx);
    const float tot = (((((e[0] + e[1]) + e[2]) + e[3]) + e[4]) + e[5]) + e[6];
    for (int q = 0; q < 7; ++q) w[q] = e[q] / tot;
    A.out[idx] = wild;
    A.out[idx + n] = trend;
    for (int col = 0; col < 4; ++col) {
        float a[7];
        for (int q = 0; q < 7; ++q) a[q] = w[q] * A.arch[q * 4 + col];
        float v = (((a[0] + a[4]) + (a[1] + a[5])) + (a[2] + a[6])) + a[3];
        if (col == 2) v = std::exp(v);   // precipitation is blended in log space
        A.out[idx + (2 + col) * n] = v;
    }
}

}  // namespace ctl
