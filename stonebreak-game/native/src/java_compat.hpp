/* Exact C++ ports of the JDK-specified primitives the carver depends on.
 * JavaRandom follows the java.util.Random spec (48-bit LCG) bit-for-bit.
 * SplineLinear mirrors com.openmason.engine.util.SplineInterpolator.
 * Compile with -ffp-contract=off so double arithmetic matches Java's. */
#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

namespace stonebreak {

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

    /* java.util.Random#nextDouble: ((long) next(26) << 27) + next(27), times 2^-53. */
    double nextDouble() {
        const int64_t hi = static_cast<int64_t>(next(26)) << 27;
        const int64_t lo = next(27);
        return static_cast<double>(hi + lo) * 0x1.0p-53;
    }

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

/* Math.floorMod(long, int) — result carries the sign of the divisor. The carve
 * divisors (48, 192, 40, 450) are not powers of two, so the & (n-1) shortcut that
 * works for the worm's 8 is wrong for them: it samples the low bits instead of the
 * residue and picks a different, denser set of chunks. */
inline int32_t javaFloorMod(int64_t x, int32_t y) {
    int32_t r = static_cast<int32_t>(x % y);
    if (r != 0 && ((r < 0) != (y < 0))) {
        r += y;
    }
    return r;
}

/* Math.floorDiv(int, int) — rounds toward negative infinity (C++ '/' truncates). */
inline int32_t javaFloorDiv(int32_t x, int32_t y) {
    int32_t q = x / y;
    if ((x % y != 0) && ((x < 0) != (y < 0))) {
        q -= 1;
    }
    return q;
}

inline int64_t rotateLeft(int64_t v, int distance) {
    auto u = static_cast<uint64_t>(v);
    return static_cast<int64_t>((u << distance) | (u >> (64 - distance)));
}

/* splitmix64 finalizer, as the Java spawn hashes spell it inline
 * (h ^= h >>> 30; h *= ...; h ^= h >>> 27; h *= ...; h ^= h >>> 31).
 * The spawn hashes XOR cz*K in after their only rotate, and the low k bits of
 * cz*K depend only on cz mod 2^k — without this every floorMod(h, D) reads a
 * value periodic in cz (issue #243). */
inline uint64_t splitmix64Finalize(uint64_t h) {
    h ^= h >> 30; h *= UINT64_C(0xBF58476D1CE4E5B9);
    h ^= h >> 27; h *= UINT64_C(0x94D049BB133111EB);
    h ^= h >> 31;
    return h;
}

/* java.lang.String#hashCode for ASCII literals (feature salts of DeterministicRandom). */
constexpr int32_t javaStringHash(const char* s) {
    uint32_t h = 0;
    for (; *s != 0; ++s) {
        h = h * 31u + static_cast<uint32_t>(static_cast<unsigned char>(*s));
    }
    return static_cast<int32_t>(h);
}

/* com.stonebreak.world.DeterministicRandom: per-position java.util.Random streams. */
class DeterministicRandom {
public:
    explicit DeterministicRandom(int64_t worldSeed = 0) : seed_(worldSeed) {}

    [[nodiscard]] JavaRandom at(int64_t x, int64_t z, int32_t featureHash) const {
        uint64_t h = static_cast<uint64_t>(seed_);
        h ^= static_cast<uint64_t>(x) * UINT64_C(0x9e3779b9);
        h ^= h >> 16;
        h ^= static_cast<uint64_t>(z) * UINT64_C(0x85ebca6b);
        h ^= h >> 13;
        h ^= static_cast<uint64_t>(static_cast<int64_t>(featureHash)) * UINT64_C(0xc2b2ae35);
        h ^= h >> 16;
        h ^= h >> 32;
        return JavaRandom(static_cast<int64_t>(h));
    }

    [[nodiscard]] JavaRandom at3(int64_t x, int64_t y, int64_t z, int32_t featureHash) const {
        uint64_t h = static_cast<uint64_t>(seed_);
        h ^= static_cast<uint64_t>(x) * UINT64_C(0x9e3779b9);
        h ^= h >> 16;
        h ^= static_cast<uint64_t>(y) * UINT64_C(0x8f8f8f8f);
        h ^= h >> 11;
        h ^= static_cast<uint64_t>(z) * UINT64_C(0x85ebca6b);
        h ^= h >> 13;
        h ^= static_cast<uint64_t>(static_cast<int64_t>(featureHash)) * UINT64_C(0xc2b2ae35);
        h ^= h >> 16;
        h ^= h >> 32;
        return JavaRandom(static_cast<int64_t>(h));
    }

    [[nodiscard]] float getFloat(int64_t x, int64_t z, int32_t f) const { return at(x, z, f).nextFloat(); }
    [[nodiscard]] float getFloat3(int64_t x, int64_t y, int64_t z, int32_t f) const {
        return at3(x, y, z, f).nextFloat();
    }
    [[nodiscard]] bool getBoolean(int64_t x, int64_t z, int32_t f) const { return at(x, z, f).nextBoolean(); }
    [[nodiscard]] int32_t getInt(int64_t x, int64_t z, int32_t f, int32_t bound) const {
        return at(x, z, f).nextInt(bound);
    }
    [[nodiscard]] bool shouldGenerate(int64_t x, int64_t z, int32_t f, float p) const {
        return getFloat(x, z, f) < p;
    }
    [[nodiscard]] bool shouldGenerate3(int64_t x, int64_t y, int64_t z, int32_t f, float p) const {
        return getFloat3(x, y, z, f) < p;
    }

private:
    int64_t seed_;
};

inline uint64_t mix64(uint64_t h) { return splitmix64Finalize(h + UINT64_C(0x9E3779B97F4A7C15)); }

/* Floor division / modulo for int64 (block -> cube / chunk coordinates). */
inline int64_t floorDiv64(int64_t a, int64_t b) {
    int64_t q = a / b;
    if ((a % b != 0) && ((a < 0) != (b < 0))) q -= 1;
    return q;
}
inline int64_t floorMod64(int64_t a, int64_t b) { return a - floorDiv64(a, b) * b; }

} // namespace stonebreak
