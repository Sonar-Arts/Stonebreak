/* Exact C++ ports of the JDK-specified primitives the carver depends on.
 * JavaRandom follows the java.util.Random spec (48-bit LCG) bit-for-bit.
 * SplineLinear mirrors com.openmason.engine.util.SplineInterpolator.
 * Compile with -ffp-contract=off so double arithmetic matches Java's. */
#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

namespace cenda {

class JavaRandom {
public:
    explicit JavaRandom(int64_t seed) { setSeed(seed); }

    void setSeed(int64_t seed) {
        state_ = (static_cast<uint64_t>(seed) ^ MULTIPLIER) & MASK;
    }

    int32_t nextInt(int32_t bound) {
        // java.util.Random#nextInt(int) semantics (bound > 0 assumed). Java's
        // rejection test relies on int wraparound — done in uint32 here since
        // signed overflow is UB in C++.
        int32_t r = next(31);
        int32_t m = bound - 1;
        if ((bound & m) == 0) { // power of two
            return static_cast<int32_t>((static_cast<int64_t>(bound) * r) >> 31);
        }
        int32_t u = r;
        while (true) {
            r = u % bound;
            const auto wrapped = static_cast<int32_t>(
                static_cast<uint32_t>(u) - static_cast<uint32_t>(r) + static_cast<uint32_t>(m));
            if (wrapped >= 0) {
                break;
            }
            u = next(31);
        }
        return r;
    }

    float nextFloat() { return static_cast<float>(next(24)) / static_cast<float>(1 << 24); }

    int64_t nextLong() {
        int64_t hi = static_cast<int64_t>(next(32)) << 32;
        return hi + static_cast<int64_t>(next(32));
    }

    bool nextBoolean() { return next(1) != 0; }

private:
    static constexpr uint64_t MULTIPLIER = 0x5DEECE66DULL;
    static constexpr uint64_t ADDEND = 0xBULL;
    static constexpr uint64_t MASK = (1ULL << 48) - 1;

    int32_t next(int bits) {
        state_ = (state_ * MULTIPLIER + ADDEND) & MASK;
        return static_cast<int32_t>(static_cast<int64_t>(state_) >> (48 - bits));
    }

    uint64_t state_ = 0;
};

/* Piecewise-linear interpolation over sorted (x,y) points; end-clamped. */
class SplineLinear {
public:
    void addPoint(double x, double y) {
        points_.push_back({x, y});
        std::sort(points_.begin(), points_.end(),
                  [](const Pt& a, const Pt& b) { return a.x < b.x; });
    }

    [[nodiscard]] double interpolate(double x) const {
        if (points_.empty()) return 0.0;
        if (x <= points_.front().x) return points_.front().y;
        if (x >= points_.back().x) return points_.back().y;
        std::size_t i = 0;
        while (i < points_.size() - 1 && x > points_[i + 1].x) ++i;
        const Pt& p1 = points_[i];
        const Pt& p2 = points_[i + 1];
        double t = (x - p1.x) / (p2.x - p1.x);
        return p1.y + t * (p2.y - p1.y);
    }

private:
    struct Pt { double x, y; };
    std::vector<Pt> points_;
};

/* Java Math.round(float): floor(f + 0.5f) as int. */
inline int32_t javaRoundFloat(float f) {
    float shifted = f + 0.5f;
    int32_t i = static_cast<int32_t>(shifted);
    return (shifted < static_cast<float>(i)) ? i - 1 : i;
}

/* Java Math.floorMod(long, positive power-of-two divisor). */
inline int64_t floorMod8(int64_t v) { return v & 7; }

inline int64_t rotateLeft(int64_t v, int distance) {
    auto u = static_cast<uint64_t>(v);
    return static_cast<int64_t>((u << distance) | (u >> (64 - distance)));
}

} // namespace cenda
