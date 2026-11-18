/* Port of com.stonebreak.world.generation.NoiseGenerator: the classic Java simplex (permutation from
 * java.util.Random(seed), fbm normalised by the amplitude sum). The ravine and sinkhole shape grammars
 * and the limestone patch/region/warp fields sample it. Double arithmetic in Java's operation order. */
#pragma once

#include "java_compat.hpp"

#include <cmath>
#include <cstdint>

namespace stonebreak {

class JavaSimplex {
public:
    JavaSimplex() = default;

    JavaSimplex(int64_t seed, int32_t octaves, double persistence, double lacunarity)
        : octaves_(octaves), persistence_(persistence), lacunarity_(lacunarity) {
        JavaRandom random(seed);
        for (int i = 0; i < 256; i++) perm_[i] = i;
        for (int i = 0; i < 256; i++) {
            const int j = random.nextInt(256);
            const int t = perm_[i];
            perm_[i] = perm_[j];
            perm_[j] = t;
        }
        for (int i = 0; i < 256; i++) perm_[256 + i] = perm_[i];
    }

    /* NoiseGenerator#noise(float, float) */
    [[nodiscard]] float noise(float x, float y) const {
        double total = 0, frequency = 1, amplitude = 1, maxValue = 0;
        for (int i = 0; i < octaves_; i++) {
            total += simplex2(static_cast<double>(x) * frequency, static_cast<double>(y) * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence_;
            frequency *= lacunarity_;
        }
        return static_cast<float>(total / maxValue);
    }

    /* NoiseGenerator#noise3D(float, float, float) */
    [[nodiscard]] float noise3D(float x, float y, float z) const {
        double total = 0, frequency = 1, amplitude = 1, maxValue = 0;
        for (int i = 0; i < octaves_; i++) {
            total += simplex3(static_cast<double>(x) * frequency, static_cast<double>(y) * frequency,
                              static_cast<double>(z) * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence_;
            frequency *= lacunarity_;
        }
        return static_cast<float>(total / maxValue);
    }

private:
    static constexpr int GRAD3[12][3] = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};

    static int fastFloor(double x) {
        const auto xi = static_cast<int>(x);
        return x < xi ? xi - 1 : xi;
    }

    [[nodiscard]] double simplex2(double x, double y) const {
        const double F2 = 0.5 * (std::sqrt(3.0) - 1.0);
        const double s = (x + y) * F2;
        const int i = fastFloor(x + s);
        const int j = fastFloor(y + s);
        const double G2 = (3.0 - std::sqrt(3.0)) / 6.0;
        const double t = (i + j) * G2;
        const double x0 = x - (i - t);
        const double y0 = y - (j - t);
        int i1, j1;
        if (x0 > y0) { i1 = 1; j1 = 0; } else { i1 = 0; j1 = 1; }
        const double x1 = x0 - i1 + G2, y1 = y0 - j1 + G2;
        const double x2 = x0 - 1.0 + 2.0 * G2, y2 = y0 - 1.0 + 2.0 * G2;
        const int ii = i & 255, jj = j & 255;
        const int gi0 = perm_[ii + perm_[jj]] % 12;
        const int gi1 = perm_[ii + i1 + perm_[jj + j1]] % 12;
        const int gi2 = perm_[ii + 1 + perm_[jj + 1]] % 12;
        double n0, n1, n2;
        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if (t0 < 0) n0 = 0.0; else { t0 *= t0; n0 = t0 * t0 * (GRAD3[gi0][0] * x0 + GRAD3[gi0][1] * y0); }
        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if (t1 < 0) n1 = 0.0; else { t1 *= t1; n1 = t1 * t1 * (GRAD3[gi1][0] * x1 + GRAD3[gi1][1] * y1); }
        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if (t2 < 0) n2 = 0.0; else { t2 *= t2; n2 = t2 * t2 * (GRAD3[gi2][0] * x2 + GRAD3[gi2][1] * y2); }
        return 70.0 * (n0 + n1 + n2);
    }

    [[nodiscard]] double simplex3(double xin, double yin, double zin) const {
        const double F3 = 1.0 / 3.0, G3 = 1.0 / 6.0;
        const double s = (xin + yin + zin) * F3;
        const int i = fastFloor(xin + s), j = fastFloor(yin + s), k = fastFloor(zin + s);
        const double t = (i + j + k) * G3;
        const double x0 = xin - (i - t), y0 = yin - (j - t), z0 = zin - (k - t);
        int i1, j1, k1, i2, j2, k2;
        if (x0 >= y0) {
            if (y0 >= z0)      { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
            else if (x0 >= z0) { i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1; }
            else               { i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1; }
        } else {
            if (y0 < z0)       { i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1; }
            else if (x0 < z0)  { i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1; }
            else               { i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0; }
        }
        const double x1 = x0 - i1 + G3, y1 = y0 - j1 + G3, z1 = z0 - k1 + G3;
        const double x2 = x0 - i2 + 2.0 * G3, y2 = y0 - j2 + 2.0 * G3, z2 = z0 - k2 + 2.0 * G3;
        const double x3 = x0 - 1.0 + 3.0 * G3, y3 = y0 - 1.0 + 3.0 * G3, z3 = z0 - 1.0 + 3.0 * G3;
        const int ii = i & 255, jj = j & 255, kk = k & 255;
        const int gi0 = perm_[ii + perm_[jj + perm_[kk]]] % 12;
        const int gi1 = perm_[ii + i1 + perm_[jj + j1 + perm_[kk + k1]]] % 12;
        const int gi2 = perm_[ii + i2 + perm_[jj + j2 + perm_[kk + k2]]] % 12;
        const int gi3 = perm_[ii + 1 + perm_[jj + 1 + perm_[kk + 1]]] % 12;
        auto dot = [](const int* g, double a, double b, double c) { return g[0] * a + g[1] * b + g[2] * c; };
        double n0, n1, n2, n3;
        double t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0;
        if (t0 < 0) n0 = 0.0; else { t0 *= t0; n0 = t0 * t0 * dot(GRAD3[gi0], x0, y0, z0); }
        double t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1;
        if (t1 < 0) n1 = 0.0; else { t1 *= t1; n1 = t1 * t1 * dot(GRAD3[gi1], x1, y1, z1); }
        double t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2;
        if (t2 < 0) n2 = 0.0; else { t2 *= t2; n2 = t2 * t2 * dot(GRAD3[gi2], x2, y2, z2); }
        double t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3;
        if (t3 < 0) n3 = 0.0; else { t3 *= t3; n3 = t3 * t3 * dot(GRAD3[gi3], x3, y3, z3); }
        return 32.0 * (n0 + n1 + n2 + n3);
    }

    int perm_[512] = {};
    int32_t octaves_ = 1;
    double persistence_ = 0.5;
    double lacunarity_ = 2.0;
};

} // namespace stonebreak
