/* Shared native terrain context + PerlinWormCarver walk — used by both the
 * standalone carver ABI (sbw_terrain_create/sbw_carve_worms in carver.cpp) and
 * the fused chunk generator (sbw_chunkgen_* in generator.cpp).
 *
 * The context evaluates heights from the SAME FastNoise2 channel nodes the
 * Java NoiseRouter uses (bit-identical channel values) plus exact ports of the
 * Java splines and java.util.Random.
 *
 * Heading/radius/wobble parity: the Java fallback walker evaluates heading and
 * radius through TerrainNoise.channel3D — the same FastNoise2 nodes (single
 * octave, frequency 1 applied at the call sites) — and wobble through
 * TerrainNoise.channel2D, mirroring CaveWaterTable's seam. Heading/radius,
 * wobble and heights are therefore bit-identical on both sides; the walker's
 * remaining Java-vs-native difference is libm trig (sin/cos/atan2), which may
 * differ by ulps across languages. Fully deterministic per seed on a given
 * platform's libm. Callers gate it on the native noise backend so one
 * implementation owns a world's caves. Parity with the Java walker is
 * ratcheted by WormCarverParityTest. (GitHub issue #244.)
 *
 * Constants mirror PerlinWormCarver.java and must stay in lockstep. */
#pragma once

#include "java_compat.hpp"
#include "cenda/noise_nodes.hpp"

#include <algorithm>
#include <atomic>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace stonebreak::gen {

constexpr double JPI = 3.141592653589793; // Java Math.PI exactly
constexpr int CHUNK_SIZE = 16;
constexpr int WORLD_HEIGHT = 256;
constexpr int SEA_LEVEL = 64;

constexpr int NO_WATER = -1;

constexpr int WORM_CHUNK_DIVISOR = 8;
constexpr int MAX_STEPS = 70;
constexpr float STEP_SIZE = 1.0f;
constexpr float BASE_RADIUS = 3.1f;
constexpr float RADIUS_AMP = 1.25f;
constexpr float MIN_RADIUS = 1.9f;
constexpr float Y_SQUASH = 0.78f;
constexpr int SCAN_RADIUS = 9;
constexpr float HEADING_SCALE = 1.0f / 38.0f;
constexpr float RADIUS_SCALE = 1.0f / 18.0f;
constexpr float YAW_DRIFT = 0.18f;
constexpr float PITCH_DRIFT = 0.10f;
constexpr float UPWARD_BIAS = 0.025f;
constexpr float PITCH_MIN = -0.85f;
constexpr float PITCH_MAX = 0.65f;
constexpr float BRANCH_CHANCE = 0.04f;
constexpr int BRANCH_MAX_STEPS = 22;
constexpr int MAX_BRANCHES = 2;
constexpr int BRANCH_MIN_STEP = 5;
constexpr float TWIN_CHANCE = 0.75f;
constexpr float CONNECTOR_CHANCE = 0.95f;
constexpr float CAVERN_CONNECTOR_CHANCE = 1.0f;
constexpr int CONNECTOR_SEARCH_RADIUS = 4;
constexpr int CAVERN_CONNECTOR_MAX_STEPS = 85;
constexpr float CONNECTOR_BIAS = 0.55f;
constexpr int CONNECTOR_MAX_STEPS = 80;
constexpr float CONNECTOR_REACHED_DIST = BASE_RADIUS;
/* Origin depth BELOW the local surface, replacing the old absolute y=14..50 band. */
constexpr int ORIGIN_DEPTH_MIN = 12;
constexpr int ORIGIN_DEPTH_MAX = 60;
constexpr int Y_FLOOR = 6;
constexpr int BREACH_OVERHEAD = 3;
inline const int WATER_CLEARANCE = static_cast<int>(std::ceil(BASE_RADIUS + RADIUS_AMP)) + 1;

/* CaveWaterTable zone steering — see PerlinWormCarver.java. */
constexpr float VADOSE_STEEPEN = 0.06f;
constexpr float VADOSE_PITCH_MIN = -0.95f;
constexpr float VADOSE_PITCH_MAX = 0.95f;
constexpr float GALLERY_DAMP = 0.55f;
constexpr float GALLERY_PITCH_MIN = -0.14f;
constexpr float GALLERY_PITCH_MAX = 0.14f;

/* ── CaveWaterTable.java ──────────────────────────────────────────────────── */
constexpr float WT_DAMP = 0.40f;
constexpr float WT_WOBBLE = 5.0f;
constexpr int WT_MIN_ROOF = 10;
constexpr int WT_LEVEL_SPACING = 22;
constexpr int WT_STOREYS = 3;
constexpr int WT_FLOOR = 9;
constexpr int WT_BAND = 5;
constexpr float WT_SCALE = 1.0f / 220.0f;

enum class Zone { VADOSE, EPIPHREATIC, PHREATIC };

/* CaveWaterTable.resolve. */
inline int waterTableResolve(int surface, int waterLevel, float wobbleNoise) {
    int table;
    if (waterLevel != NO_WATER && waterLevel > 0) {
        table = waterLevel;
    } else {
        table = stonebreak::javaRoundFloat(
            static_cast<float>(SEA_LEVEL)
            + static_cast<float>(surface - SEA_LEVEL) * WT_DAMP
            + wobbleNoise * WT_WOBBLE);
    }
    if (table < WT_FLOOR) table = WT_FLOOR;
    const int roof = surface - WT_MIN_ROOF;
    return table < roof ? table : roof;
}

/* CaveWaterTable.zoneAt. */
inline Zone waterTableZoneAt(int table, int y) {
    if (y > table + WT_BAND) {
        return Zone::VADOSE;
    }
    for (int k = 0; k < WT_STOREYS; k++) {
        const int storey = table - k * WT_LEVEL_SPACING;
        if (std::abs(y - storey) <= WT_BAND) {
            return Zone::EPIPHREATIC;
        }
    }
    return Zone::PHREATIC;
}

/* CaveWaterTable.galleryWeight. */
inline float waterTableGalleryWeight(int table, int y) {
    float best = 0.0f;
    for (int k = 0; k < WT_STOREYS; k++) {
        const int storey = table - k * WT_LEVEL_SPACING;
        const float w = 1.0f - static_cast<float>(std::abs(y - storey)) / static_cast<float>(WT_BAND);
        if (w > best) best = w;
    }
    return best;
}

/* HeightMapGenerator.waterLevel — this branch's only standing water is the ocean,
 * so a column is wet exactly when its surface is submerged. */
inline int waterLevelOf(int surface) {
    return surface < SEA_LEVEL ? SEA_LEVEL : NO_WATER;
}

constexpr int WATER_GUARD_OPEN = INT32_MAX;

inline int32_t nativeSeedOf(int64_t v) { // Long.hashCode
    const auto u = static_cast<uint64_t>(v);
    return static_cast<int32_t>(static_cast<uint32_t>(u ^ (u >> 32)));
}


/* ── DistilledShape.java (heightmap) ─────────────────────────────────────────
 * The Standard terrain shape distilled from DaedalusTGM-Exp. Mirrors
 * DistilledShape.evaluate / Lattice / HeightCurveTable.interpolate line for line
 * and in the same operation order: only + - * /, sqrt, fabs, min/max, floor, so
 * with -ffp-contract=off every height is bit-identical to Java's. Constants and
 * the height-curve table come from Java (DistilledShape.nativeArgs, SBW ABI 2);
 * nothing numeric is duplicated here except the formula's literal shape factors. */
struct DistilledShape {
    enum Channel { CONT = 0, WARP_X, WARP_Z, RANGE, SPUR, ALONG, HILLS, HILLY, VALLEY, TEXTURE, DETAIL, CHANNELS };
    enum K {
        K_HOME_RADIUS = 0, K_HOME_AMP, K_CONT_OFFSET, K_CONT_WIDTH, K_WARP_AMP,
        K_SPINE_WIDTH, K_ALONG_BIAS, K_SPINE_M, K_SPUR_M, K_FOOT_M,
        K_HILL_BIAS, K_HILL_SOFTNESS, K_HILL_PLAINS_M, K_HILL_COUNTRY_M, K_LAND_BASE_M,
        K_SEA_FLOOR_M, K_ROUGH_FLOOR, K_ROUGH_HILL, K_VALLEY_M, K_VALLEY_EXP,
        K_TEXTURE_M, K_TEXTURE_FLOOR_M, K_DETAIL_BLOCKS, K_PEAK_KNEE_M, K_PEAK_SPAN_M,
        K_STD2, K_STD3, K_STD4, K_CURVE_MIN, K_CURVE_STEP, K_LATTICE, K_COUNT
    };

    FastNoise::SmartNode<> node[CHANNELS];
    int32_t seed[CHANNELS]{};
    double k[K_COUNT]{};
    std::vector<double> curve;
    int lattice = 16;
    uint64_t serial = 0; // distinguishes contexts in the thread-local lattice cache

    struct Column {
        int height = 0;
        int shapedHeight = 0;
    };

    // Java channel axis convention: FastNoise2 X carries worldZ, Y carries worldX (offsets are 0).
    [[nodiscard]] float sample(int c, int x, int z) const {
        return node[c]->GenSingle2D(static_cast<float>(z), static_cast<float>(x), seed[c]);
    }

    [[nodiscard]] float sampleAt(int c, float x, float z) const {
        return node[c]->GenSingle2D(z, x, seed[c]);
    }

    static double bell(double u) {
        double t = std::max(1.0 - u / 3.0, 0.0);
        return t * t * t;
    }

    static double smoothstep(double x) {
        double t = std::max(0.0, std::min(1.0, x));
        return t * t * (3.0 - 2.0 * t);
    }

    static double sq(double v) { return v * v; }

    static double clamp01(double v) { return std::max(0.0, std::min(1.0, v)); }

    static double valleyShape(double a, double e) {
        if (e == 0.5) return std::sqrt(a);
        if (e == 1.0) return a;
        if (e == 1.5) return a * std::sqrt(a);
        return a * a;
    }

    static double bspline(double t, int i) {
        double u = 1.0 - t;
        double t2 = t * t;
        double t3 = t * t * t;
        switch (i) {
            case 0: return u * u * u / 6.0;
            case 1: return (3.0 * t3 - 6.0 * t2 + 4.0) / 6.0;
            case 2: return (-3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0) / 6.0;
            default: return t3 / 6.0;
        }
    }

    static int clampHeight(double floored) {
        int h = static_cast<int>(floored);
        if (h < 1) h = 1;
        if (h > WORLD_HEIGHT - 1) h = WORLD_HEIGHT - 1;
        return h;
    }

    // HeightCurveTable.interpolate
    [[nodiscard]] double curveBlocks(double e) const {
        double t = (e - k[K_CURVE_MIN]) / k[K_CURVE_STEP];
        if (t <= 0.0) return curve.front();
        const auto last = static_cast<double>(curve.size() - 1);
        if (t >= last) return curve.back();
        const auto i = static_cast<size_t>(std::floor(t));
        double f = t - static_cast<double>(i);
        return curve[i] + f * (curve[i + 1] - curve[i]);
    }

    struct Node { int64_t key; uint64_t serial; float range; float spur; };

    // DistilledShape.Lattice.node — warped range/spur at lattice node (li, lj).
    [[nodiscard]] Node latticeNode(int32_t li, int32_t lj) const {
        static constexpr int BITS = 13;
        thread_local Node cache[1 << BITS] = {};
        const int64_t key = (static_cast<int64_t>(li) << 32)
                            ^ static_cast<int64_t>(static_cast<uint32_t>(lj));
        const auto slot = static_cast<size_t>(
            (static_cast<uint64_t>(key) * 0x9E3779B97F4A7C15ULL) >> (64 - BITS));
        Node& n = cache[slot];
        if (n.serial == serial && n.key == key) {
            return n;
        }
        const int gx = li * lattice;
        const int gz = lj * lattice;
        const float wx = sample(WARP_X, gx, gz);
        const float wz = sample(WARP_Z, gx, gz);
        const auto px = static_cast<float>(static_cast<double>(gx) + k[K_WARP_AMP] * static_cast<double>(wx));
        const auto pz = static_cast<float>(static_cast<double>(gz) + k[K_WARP_AMP] * static_cast<double>(wz));
        n = Node{key, serial, sampleAt(RANGE, px, pz), sampleAt(SPUR, px, pz)};
        return n;
    }

    // DistilledShape.Lattice.interpolate: {range, spur} in one pass over the 4 x 4 nodes.
    void latticeFields(int x, int z, double& range, double& spur) const {
        const int lx = stonebreak::javaFloorDiv(x, lattice);
        const int lz = stonebreak::javaFloorDiv(z, lattice);
        const double tx = static_cast<double>(x - lx * lattice) / static_cast<double>(lattice);
        const double tz = static_cast<double>(z - lz * lattice) / static_cast<double>(lattice);
        const double wz[4] = {bspline(tz, 0), bspline(tz, 1), bspline(tz, 2), bspline(tz, 3)};
        double r = 0.0, s = 0.0;
        for (int a = 0; a < 4; a++) {
            const double wa = bspline(tx, a);
            for (int b = 0; b < 4; b++) {
                const Node n = latticeNode(lx - 1 + a, lz - 1 + b);
                const double w = wa * wz[b];
                r += w * static_cast<double>(n.range);
                s += w * static_cast<double>(n.spur);
            }
        }
        range = r;
        spur = s;
    }

    // DistilledShape.evaluate (per point: channel samples are GenSingle2D, equal to Java's grid fills).
    [[nodiscard]] Column column(int x, int z) const {
        const double contRaw = sample(CONT, x, z);
        const double alongRaw = sample(ALONG, x, z);
        const double hillsRaw = sample(HILLS, x, z);
        const double hillyRaw = sample(HILLY, x, z);
        const double valleyRaw = sample(VALLEY, x, z);
        const double textureRaw = sample(TEXTURE, x, z);
        const double detailRaw = sample(DETAIL, x, z);

        const double xd = x, zd = z;
        const double home = bell((xd * xd + zd * zd) / (2.0 * k[K_HOME_RADIUS] * k[K_HOME_RADIUS]));
        const double cont = contRaw + k[K_HOME_AMP] * home;
        const double land = smoothstep((cont + k[K_CONT_OFFSET]) / k[K_CONT_WIDTH]);

        double fRange = 0.0, fSpur = 0.0;
        latticeFields(x, z, fRange, fSpur);
        const double nRange = fRange / k[K_STD2];
        const double nSpur = fSpur / k[K_STD2];
        const double w = k[K_SPINE_WIDTH];
        const double spine = bell(sq(nRange / w));
        const double foot = bell(sq(nRange / (2.4 * w)));
        const double body = bell(sq(nRange / (1.6 * w)));
        const double spur = bell(sq(nSpur / 0.6)) * body;
        const double along = smoothstep((alongRaw / k[K_STD2] + k[K_ALONG_BIAS]) / 1.2);
        const double hills = hillsRaw;
        double hilly = smoothstep((hillyRaw / k[K_STD3] + k[K_HILL_BIAS]) / k[K_HILL_SOFTNESS]);
        hilly = std::max(hilly, 0.55 * home);

        const double mountains = along * (k[K_SPINE_M] * spine + k[K_SPUR_M] * spur + k[K_FOOT_M] * foot);
        const double hillM = k[K_HILL_PLAINS_M] + (k[K_HILL_COUNTRY_M] - k[K_HILL_PLAINS_M]) * hilly
                             + 200.0 * along * foot;
        const double trend = k[K_SEA_FLOOR_M] + (k[K_LAND_BASE_M] - k[K_SEA_FLOOR_M]) * land * (0.6 + 0.5 * cont)
                             + land * (mountains + hillM * hills);
        const double rough = clamp01(k[K_ROUGH_FLOOR] + k[K_ROUGH_HILL] * hilly
                                     + along * (0.9 * spine + 0.45 * spur + 0.25 * foot)
                                     + 0.25 * (0.3 + along * foot) * std::fabs(hills)) * land;
        const double a = std::fabs(valleyRaw) / (2.0 * k[K_STD4]);
        const double valleys = k[K_VALLEY_M] * rough * (valleyShape(a, k[K_VALLEY_EXP]) - 0.5);
        const double tex = (k[K_TEXTURE_FLOOR_M] + (k[K_TEXTURE_M] - k[K_TEXTURE_FLOOR_M]) * rough) * textureRaw;
        double e = trend + valleys + tex;
        const double over = std::max(e - k[K_PEAK_KNEE_M], 0.0);
        e = e - over + over / (1.0 + over / k[K_PEAK_SPAN_M]);

        const double blocks = curveBlocks(e);
        Column out;
        out.shapedHeight = clampHeight(std::floor(blocks));
        out.height = clampHeight(std::floor(blocks + k[K_DETAIL_BLOCKS] * detailRaw));
        return out;
    }
};

struct TerrainCtx {
    DistilledShape shape;

    int64_t seed = 0;
    FastNoise::SmartNode<> headingNoise;
    FastNoise::SmartNode<> radiusNoise;
    FastNoise::SmartNode<> wobbleNoise;
    int32_t headingSeed = 0;
    int32_t radiusSeed = 0;
    int32_t wobbleSeed = 0;

    // HeightMapGenerator.generateHeight == DistilledShape.height.
    [[nodiscard]] int generateHeight(int x, int z) const {
        return shape.column(x, z).height;
    }

    [[nodiscard]] float heading3D(float x, float y, float z) const {
        return headingNoise->GenSingle3D(x, y, z, headingSeed);
    }

    [[nodiscard]] float radius3D(float x, float y, float z) const {
        return radiusNoise->GenSingle3D(x, y, z, radiusSeed);
    }

    /* CaveWaterTable's wobble channel. Same axis convention as sampleChannel:
     * FastNoise2 X carries worldZ, Y carries worldX. */
    [[nodiscard]] float waterTableWobble(int x, int z) const {
        return wobbleNoise->GenSingle2D(static_cast<float>(z), static_cast<float>(x), wobbleSeed);
    }

    /* CaveWaterTable.tableFrom, for a caller that already resolved the column. */
    [[nodiscard]] int waterTableFrom(int x, int z, int surface, int waterLevel) const {
        return waterTableResolve(surface, waterLevel, waterTableWobble(x, z));
    }

    /* CaveWaterTable.tableAt. */
    [[nodiscard]] int waterTableAt(int x, int z) const {
        const int surface = generateHeight(x, z);
        return waterTableFrom(x, z, surface, waterLevelOf(surface));
    }
};

/* WaterGuard.guardPlane — per-column lowest wet-column bed in the 4-neighborhood
 * (itself included), or WATER_GUARD_OPEN. Border columns resolve through the
 * height oracle so a coastline hugging a chunk edge guards from both sides. */
inline void waterGuardPlane(const TerrainCtx& ctx, const int32_t* targetHeights,
                            int chunkX, int chunkZ, int32_t* outPlane) {
    const int baseX = chunkX * CHUNK_SIZE;
    const int baseZ = chunkZ * CHUNK_SIZE;
    auto consider = [&](int guard, int x, int z) {
        if (x >= 0 && x < CHUNK_SIZE && z >= 0 && z < CHUNK_SIZE) {
            const int idx = x * CHUNK_SIZE + z;
            const int h = targetHeights[idx];
            if (waterLevelOf(h) != NO_WATER) {
                return guard < h ? guard : h;
            }
            return guard;
        }
        const int h = ctx.generateHeight(baseX + x, baseZ + z);
        if (waterLevelOf(h) != NO_WATER) {
            return guard < h ? guard : h;
        }
        return guard;
    };
    for (int x = 0; x < CHUNK_SIZE; x++) {
        for (int z = 0; z < CHUNK_SIZE; z++) {
            int guard = WATER_GUARD_OPEN;
            guard = consider(guard, x, z);
            guard = consider(guard, x - 1, z);
            guard = consider(guard, x + 1, z);
            guard = consider(guard, x, z - 1);
            guard = consider(guard, x, z + 1);
            outPlane[x * CHUNK_SIZE + z] = guard;
        }
    }
}

/* WaterGuard.seals. */
inline bool waterGuardSeals(const int32_t* plane, int index, int y, int clearance) {
    return plane != nullptr && plane[index] != WATER_GUARD_OPEN
        && y >= plane[index] - clearance;
}

struct Segment {
    float x, y, z, yaw, pitch;
    int stepBudget;
    int branchesLeft;
    int64_t rngSeed;
    bool hasTarget;
    float target[3];
};

/* Supplies the cavern-connector target for a worm-bearing source chunk.
 * (ox, oy, oz) is the worm origin (already drawn from the chunk RNG) — the
 * native cavern implementation measures world-space distance from it; the
 * array-backed implementation ignores it (Java precomputed with the same
 * origin). Returns false when no cavern is in range. */
struct AnchorSource {
    virtual ~AnchorSource() = default;
    virtual bool anchorFor(int cx, int cz, float ox, float oy, float oz,
                           float out[3]) const = 0;
};

// Java relies on wrapping long arithmetic; signed overflow is UB in C++, so
// all hash math runs in uint64 (two's-complement identical results).
constexpr uint64_t HASH_A = UINT64_C(0x9E3779B97F4A7C15);
constexpr uint64_t HASH_B = UINT64_C(0xC2B2AE3D27D4EB4F);
constexpr uint64_t LCG_A = UINT64_C(6364136223846793005);
constexpr uint64_t LCG_B = UINT64_C(1442695040888963407);

inline bool hasWorm(int64_t seed, int cx, int cz) {
    uint64_t h = static_cast<uint64_t>(seed);
    h ^= static_cast<uint64_t>(static_cast<int64_t>(cx)) * HASH_A;
    h = (h << 23) | (h >> 41);
    h ^= static_cast<uint64_t>(static_cast<int64_t>(cz)) * HASH_B;
    h = stonebreak::splitmix64Finalize(h);
    return (h & 7) == 0; // Math.floorMod(h, 8) on the signed value
}

inline int64_t chunkRngSeed(int64_t seed, int cx, int cz) {
    uint64_t h = static_cast<uint64_t>(seed) * LCG_A;
    h += static_cast<uint64_t>(static_cast<int64_t>(cx));
    h *= LCG_B;
    h += static_cast<uint64_t>(static_cast<int64_t>(cz));
    return static_cast<int64_t>(h);
}

/* PerlinWormCarver.originY — a depth below THIS column's surface, not an absolute band.
 * Consumes exactly one nextInt so the downstream stream (twin, connector and
 * cavern-connector seeds) keeps its shape; spawnCarvers must mirror these three draws. */
inline float originY(const TerrainCtx& ctx, float ox, float oz, JavaRandom& rng) {
    const int surface = ctx.generateHeight(stonebreak::javaRoundFloat(ox), stonebreak::javaRoundFloat(oz));
    const int depth = ORIGIN_DEPTH_MIN + rng.nextInt(ORIGIN_DEPTH_MAX - ORIGIN_DEPTH_MIN);
    const int y = surface - depth;
    return static_cast<float>(y > Y_FLOOR + 2 ? y : Y_FLOOR + 2);
}

inline void computeOrigin(const TerrainCtx& ctx, int64_t seed, int cx, int cz, float out[3]) {
    JavaRandom rng(chunkRngSeed(seed, cx, cz));
    out[0] = static_cast<float>(cx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    out[2] = static_cast<float>(cz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    out[1] = originY(ctx, out[0], out[2], rng);
}

inline bool nearestWormChunk(int64_t seed, int cx, int cz, int out[2]) {
    int bestDistSq = INT32_MAX;
    bool found = false;
    for (int dcx = -CONNECTOR_SEARCH_RADIUS; dcx <= CONNECTOR_SEARCH_RADIUS; dcx++) {
        for (int dcz = -CONNECTOR_SEARCH_RADIUS; dcz <= CONNECTOR_SEARCH_RADIUS; dcz++) {
            if (dcx == 0 && dcz == 0) continue;
            int ncx = cx + dcx, ncz = cz + dcz;
            if (!hasWorm(seed, ncx, ncz)) continue;
            int d = dcx * dcx + dcz * dcz;
            if (d < bestDistSq) {
                bestDistSq = d;
                out[0] = ncx;
                out[1] = ncz;
                found = true;
            }
        }
    }
    return found;
}

inline float lerpAngle(float from, float to, float t) {
    float diff = to - from;
    const float twoPi = static_cast<float>(JPI * 2);
    while (diff > static_cast<float>(JPI)) diff -= twoPi;
    while (diff < -static_cast<float>(JPI)) diff += twoPi;
    return from + diff * t;
}

inline void carveEllipsoid(int wx, int wy, int wz, float radius, int targetCx, int targetCz,
                           const int32_t* targetHeights, const int32_t* waterGuard,
                           uint64_t* mask) {
    const int targetBaseX = targetCx * CHUNK_SIZE;
    const int targetBaseZ = targetCz * CHUNK_SIZE;
    const int rxz = static_cast<int>(std::ceil(radius));
    const int ry = static_cast<int>(std::ceil(radius * Y_SQUASH));
    if (wx + rxz < targetBaseX || wx - rxz >= targetBaseX + CHUNK_SIZE) return;
    if (wz + rxz < targetBaseZ || wz - rxz >= targetBaseZ + CHUNK_SIZE) return;

    const float invRxz2 = 1.0f / (radius * radius);
    const float ySpan = radius * Y_SQUASH;
    const float invRy2 = 1.0f / (ySpan * ySpan);

    for (int ox = -rxz; ox <= rxz; ox++) {
        const int bx = wx + ox - targetBaseX;
        if (bx < 0 || bx >= CHUNK_SIZE) continue;
        for (int oz = -rxz; oz <= rxz; oz++) {
            const int bz = wz + oz - targetBaseZ;
            if (bz < 0 || bz >= CHUNK_SIZE) continue;
            const int idx = bx * CHUNK_SIZE + bz;
            /* Only skip columns at/below the world floor; waterGuardSeals below is what
             * actually keeps carving away from water. */
            if (targetHeights[idx] <= 1) continue;
            const float horizTerm = static_cast<float>(ox * ox + oz * oz) * invRxz2;
            if (horizTerm >= 1.0f) continue;
            const float maxOyTerm = 1.0f - horizTerm;
            for (int oy = -ry; oy <= ry; oy++) {
                if (static_cast<float>(oy * oy) * invRy2 >= maxOyTerm) continue;
                const int by = wy + oy;
                if (by < 1 || by >= WORLD_HEIGHT) continue;
                if (waterGuardSeals(waterGuard, idx, by, WATER_CLEARANCE)) continue;
                const int bit = (bx << 12) | (by << 4) | bz;
                mask[bit >> 6] |= (1ULL << (bit & 63));
            }
        }
    }
}

inline void walkCarver(const TerrainCtx& ctx, Segment seg, std::vector<Segment>& queue,
                       int targetCx, int targetCz, const int32_t* targetHeights,
                       const int32_t* waterGuard, uint64_t* mask) {
    JavaRandom rng(seg.rngSeed);
    float x = seg.x, y = seg.y, z = seg.z, yaw = seg.yaw, pitch = seg.pitch;
    int branchesLeft = seg.branchesLeft;
    const float reachedSq = CONNECTOR_REACHED_DIST * CONNECTOR_REACHED_DIST;
    /* Zone of the CURRENT position, resolved at the end of the previous step from the
     * surface/water that step already fetched. Mirrors the Java walker's deliberate
     * one-step lag: the first step starts PHREATIC and behaves as it always did, so
     * both backends carve identical walks from the same seed. (GitHub issue #244.) */
    Zone zone = Zone::PHREATIC;

    for (int step = 0; step < seg.stepBudget; step++) {
        const float yawNoise = ctx.heading3D(x * HEADING_SCALE, y * HEADING_SCALE, z * HEADING_SCALE);
        const float pitchNoise = ctx.heading3D((x + 1024.0f) * HEADING_SCALE, y * HEADING_SCALE,
                                               (z + 1024.0f) * HEADING_SCALE);
        yaw += yawNoise * YAW_DRIFT;
        pitch += pitchNoise * PITCH_DRIFT + UPWARD_BIAS;

        /* Vadose water cuts down, phreatic water wanders, and the band between them runs
         * flat along the table — the shape difference that makes a cave system read as one
         * rather than as noise. Connectors are exempt: they are aiming at a cavern. */
        if (!seg.hasTarget) {
            switch (zone) {
                case Zone::VADOSE:
                    pitch += (pitch > 0.0f ? 1.0f : (pitch < 0.0f ? -1.0f : 0.0f)) * VADOSE_STEEPEN;
                    break;
                case Zone::EPIPHREATIC:
                    pitch *= GALLERY_DAMP;
                    break;
                case Zone::PHREATIC:
                    break;
            }
        }

        if (seg.hasTarget) {
            const float dx = seg.target[0] - x;
            const float dy = seg.target[1] - y;
            const float dz = seg.target[2] - z;
            const float horiz = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
            if (horiz > 0.001f) {
                const float tgtYaw = static_cast<float>(std::atan2(static_cast<double>(dz), static_cast<double>(dx)));
                const float tgtPitch = static_cast<float>(std::atan2(static_cast<double>(dy), static_cast<double>(horiz)));
                yaw = lerpAngle(yaw, tgtYaw, CONNECTOR_BIAS);
                pitch += (tgtPitch - pitch) * CONNECTOR_BIAS;
            }
        }

        /* Clamp to the zone's envelope. Connectors keep the default so they can still aim. */
        float pitchMin = PITCH_MIN;
        float pitchMax = PITCH_MAX;
        if (!seg.hasTarget) {
            if (zone == Zone::VADOSE) {
                pitchMin = VADOSE_PITCH_MIN;
                pitchMax = VADOSE_PITCH_MAX;
            } else if (zone == Zone::EPIPHREATIC) {
                pitchMin = GALLERY_PITCH_MIN;
                pitchMax = GALLERY_PITCH_MAX;
            }
        }
        if (pitch < pitchMin) pitch = pitchMin;
        else if (pitch > pitchMax) pitch = pitchMax;

        const float cosPitch = static_cast<float>(std::cos(static_cast<double>(pitch)));
        x += static_cast<float>(std::cos(static_cast<double>(yaw))) * cosPitch * STEP_SIZE;
        y += static_cast<float>(std::sin(static_cast<double>(pitch))) * STEP_SIZE;
        z += static_cast<float>(std::sin(static_cast<double>(yaw))) * cosPitch * STEP_SIZE;

        const int wxi = stonebreak::javaRoundFloat(x);
        const int wyi = stonebreak::javaRoundFloat(y);
        const int wzi = stonebreak::javaRoundFloat(z);
        if (wyi < Y_FLOOR || wyi >= WORLD_HEIGHT) break;
        const int surface = ctx.generateHeight(wxi, wzi);
        /* Gate on whether THIS column actually holds water rather than on global sea level:
         * the old test killed every worm under dry coastal flats while saying nothing about
         * standing water above it. waterGuardSeals does the precise per-cell sealing. */
        const int water = waterLevelOf(surface);
        if (water != NO_WATER && surface <= water + WATER_CLEARANCE) break;
        if (wyi > surface + BREACH_OVERHEAD) break;

        /* Zone for the NEXT step, reusing the surface/water this step already resolved. */
        zone = waterTableZoneAt(ctx.waterTableFrom(wxi, wzi, surface, water), wyi);

        float radius = BASE_RADIUS + ctx.radius3D(x * RADIUS_SCALE, y * RADIUS_SCALE, z * RADIUS_SCALE) * RADIUS_AMP;
        if (radius < MIN_RADIUS) radius = MIN_RADIUS;
        carveEllipsoid(wxi, wyi, wzi, radius, targetCx, targetCz, targetHeights, waterGuard, mask);

        if (seg.hasTarget) {
            const float dx = seg.target[0] - x;
            const float dy = seg.target[1] - y;
            const float dz = seg.target[2] - z;
            if (dx * dx + dy * dy + dz * dz < reachedSq) {
                break;
            }
        }

        if (branchesLeft > 0 && step >= BRANCH_MIN_STEP && rng.nextFloat() < BRANCH_CHANCE) {
            branchesLeft--;
            const float branchOffset = (rng.nextBoolean() ? 1.1f : -1.1f) + (rng.nextFloat() - 0.5f) * 0.6f;
            const float branchYaw = yaw + branchOffset;
            const float branchPitch = pitch + (rng.nextFloat() - 0.5f) * 0.3f;
            queue.push_back({x, y, z, branchYaw, branchPitch, BRANCH_MAX_STEPS, 0,
                             rng.nextLong(), false, {0, 0, 0}});
        }
    }
}

inline void pushConnectorToward(std::vector<Segment>& queue, float ox, float oy, float oz,
                                const float target[3], int stepBudget, int64_t rngSeed) {
    const float dx = target[0] - ox;
    const float dy = target[1] - oy;
    const float dz = target[2] - oz;
    const float horiz = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
    const float yaw = static_cast<float>(std::atan2(static_cast<double>(dz), static_cast<double>(dx)));
    float pitch = horiz > 0.001f
        ? static_cast<float>(std::atan2(static_cast<double>(dy), static_cast<double>(horiz)))
        : 0.0f;
    if (pitch < PITCH_MIN) pitch = PITCH_MIN;
    else if (pitch > PITCH_MAX) pitch = PITCH_MAX;
    queue.push_back({ox, oy, oz, yaw, pitch, stepBudget, 0, rngSeed, true,
                     {target[0], target[1], target[2]}});
}

inline void spawnCarvers(const TerrainCtx& ctx, int srcCx, int srcCz, int targetCx, int targetCz,
                         const int32_t* targetHeights, const int32_t* waterGuard,
                         const AnchorSource* anchorSource, uint64_t* mask) {
    // RNG draw order mirrors PerlinWormCarver.spawnCarvers exactly.
    JavaRandom rng(chunkRngSeed(ctx.seed, srcCx, srcCz));
    const float ox = static_cast<float>(srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    const float oz = static_cast<float>(srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    const float oy = originY(ctx, ox, oz, rng);
    const float yaw = rng.nextFloat() * static_cast<float>(JPI * 2);
    const float pitch = -0.15f + rng.nextFloat() * 0.3f;
    const bool spawnTwin = rng.nextFloat() < TWIN_CHANCE;
    const int64_t primarySeed = rng.nextLong();
    const int64_t twinSeed = rng.nextLong();
    const bool spawnConnector = rng.nextFloat() < CONNECTOR_CHANCE;
    const int64_t connectorSeed = rng.nextLong();
    const bool spawnCavernConnector = rng.nextFloat() < CAVERN_CONNECTOR_CHANCE;
    const int64_t cavernConnectorSeed = rng.nextLong();

    std::vector<Segment> queue;
    queue.push_back({ox, oy, oz, yaw, pitch, MAX_STEPS, MAX_BRANCHES, primarySeed, false, {0, 0, 0}});
    if (spawnTwin) {
        queue.push_back({ox, oy, oz, yaw + static_cast<float>(JPI), -pitch,
                         MAX_STEPS, MAX_BRANCHES, twinSeed, false, {0, 0, 0}});
    }
    if (spawnConnector) {
        int neighbor[2] = {};
        if (nearestWormChunk(ctx.seed, srcCx, srcCz, neighbor)) {
            float target[3];
            computeOrigin(ctx, ctx.seed, neighbor[0], neighbor[1], target);
            pushConnectorToward(queue, ox, oy, oz, target, CONNECTOR_MAX_STEPS, connectorSeed);
        }
    }
    if (spawnCavernConnector && anchorSource != nullptr) {
        float target[3];
        if (anchorSource->anchorFor(srcCx, srcCz, ox, oy, oz, target)) {
            pushConnectorToward(queue, ox, oy, oz, target,
                                CAVERN_CONNECTOR_MAX_STEPS, cavernConnectorSeed);
        }
    }

    while (!queue.empty()) {
        Segment seg = queue.back();
        queue.pop_back();
        walkCarver(ctx, seg, queue, targetCx, targetCz, targetHeights, waterGuard, mask);
    }
}

/* Shared construction for sbw_terrain_create and the fused generator context.
 * Returns nullptr on bad args or node-construction failure. */
inline TerrainCtx* terrainCreateImpl(int64_t worm_seed,
                                     const int32_t* ch_seeds, const int32_t* ch_octaves,
                                     const float* ch_gain, const float* ch_lacunarity,
                                     const float* ch_freq,
                                     const int32_t* ch_xoff, const int32_t* ch_zoff,
                                     const double* spline_xs, const double* spline_ys,
                                     const int32_t* spline_sizes,
                                     float detail_amplitude) {
    if (ch_seeds == nullptr || ch_octaves == nullptr || ch_gain == nullptr ||
        ch_lacunarity == nullptr || ch_freq == nullptr || ch_xoff == nullptr ||
        ch_zoff == nullptr || spline_xs == nullptr || spline_ys == nullptr ||
        spline_sizes == nullptr) {
        return nullptr;
    }
    // SBW ABI 2: spline_sizes = {channels, constants, curve table length}; spline_xs = the
    // DistilledShape constants, spline_ys = the height-curve table (DistilledShape.nativeArgs).
    if (spline_sizes[0] != DistilledShape::CHANNELS || spline_sizes[1] < DistilledShape::K_COUNT
            || spline_sizes[2] < 2) {
        return nullptr;
    }
    static std::atomic<uint64_t> nextSerial{1};
    auto ctx = new TerrainCtx();
    ctx->seed = worm_seed;
    DistilledShape& sh = ctx->shape;
    for (int i = 0; i < DistilledShape::CHANNELS; i++) {
        if (ch_xoff[i] != 0 || ch_zoff[i] != 0) { // the distilled channels carry no block offsets
            delete ctx;
            return nullptr;
        }
        sh.node[i] = cenda::makeSimplexFbm(ch_octaves[i], ch_lacunarity[i], ch_gain[i], ch_freq[i]);
        if (!sh.node[i]) {
            delete ctx;
            return nullptr;
        }
        sh.seed[i] = ch_seeds[i];
    }
    for (int i = 0; i < DistilledShape::K_COUNT; i++) {
        sh.k[i] = spline_xs[i];
    }
    sh.curve.assign(spline_ys, spline_ys + spline_sizes[2]);
    sh.lattice = static_cast<int>(sh.k[DistilledShape::K_LATTICE]);
    sh.serial = nextSerial.fetch_add(1);
    (void) detail_amplitude; // carried in the constants (K_DETAIL_BLOCKS); kept in the ABI slot for the signature
    // Single-octave heading/radius noise, frequency applied at the call site
    // (positions pre-multiplied by HEADING_SCALE/RADIUS_SCALE like the Java code),
    // so the node itself is frequency 1.
    ctx->headingNoise = cenda::makeSimplexFbm(1, 2.0f, 0.5f, 1.0f);
    ctx->radiusNoise = cenda::makeSimplexFbm(1, 2.0f, 0.5f, 1.0f);
    ctx->headingSeed = nativeSeedOf(worm_seed + 41);
    ctx->radiusSeed = nativeSeedOf(worm_seed + 113);
    // CaveWaterTable's wobble: 2 octaves with the frequency INSIDE the node, matching
    // TerrainNoise.channel2D(seed + 8191, 2, 0.5, 2.0, WT_SCALE, ...).
    ctx->wobbleNoise = cenda::makeSimplexFbm(2, 2.0f, 0.5f, WT_SCALE);
    ctx->wobbleSeed = nativeSeedOf(worm_seed + 8191);
    return ctx;
}

/* Full worm pass over the source-chunk scan window into out_mask (1024 uint64
 * words, bit index (x<<12 | y<<4 | z)). out_mask is zeroed first. Returns the
 * number of set bits. */
inline int64_t carveWormsImpl(const TerrainCtx& ctx, int32_t chunk_x, int32_t chunk_z,
                              const int32_t* target_heights, const AnchorSource* anchorSource,
                              uint64_t* out_mask) {
    std::memset(out_mask, 0, 1024 * sizeof(uint64_t));

    int32_t guard[CHUNK_SIZE * CHUNK_SIZE];
    waterGuardPlane(ctx, target_heights, chunk_x, chunk_z, guard);

    for (int dcx = -SCAN_RADIUS; dcx <= SCAN_RADIUS; dcx++) {
        for (int dcz = -SCAN_RADIUS; dcz <= SCAN_RADIUS; dcz++) {
            const int srcCx = chunk_x + dcx;
            const int srcCz = chunk_z + dcz;
            if (!hasWorm(ctx.seed, srcCx, srcCz)) continue;
            spawnCarvers(ctx, srcCx, srcCz, chunk_x, chunk_z, target_heights,
                         guard, anchorSource, out_mask);
        }
    }

    int64_t bits = 0;
    for (int i = 0; i < 1024; i++) {
        bits += std::popcount(static_cast<uint64_t>(out_mask[i])); // portable (MSVC has no __builtin)
    }
    return bits;
}

} // namespace stonebreak::gen
