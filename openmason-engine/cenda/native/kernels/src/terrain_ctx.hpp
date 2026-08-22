/* Shared native terrain context + PerlinWormCarver walk — used by both the
 * standalone carver ABI (ck_terrain_create/ck_carve_worms in carver.cpp) and
 * the fused chunk generator (ck_chunkgen_* in generator.cpp).
 *
 * The context evaluates heights from the SAME FastNoise2 channel nodes the
 * Java NoiseRouter uses (bit-identical channel values) plus exact ports of the
 * Java splines and java.util.Random.
 *
 * NOT bit-identical to the Java carver overall — libm trig (sin/cos/atan2)
 * may differ by ulps across languages — but fully deterministic per seed on a
 * given platform's libm. Callers gate it on the native noise backend so one
 * implementation owns a world's caves.
 *
 * Constants mirror PerlinWormCarver.java and must stay in lockstep. */
#pragma once

#include "java_compat.hpp"
#include "nodes.hpp"

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace cenda::gen {

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
        table = cenda::javaRoundFloat(
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

struct TerrainCtx {
    // Channels order: continentalness, peaksValleys, erosion, detail.
    FastNoise::SmartNode<> channel[4];
    int32_t channelSeed[4]{};
    int32_t xOff[4]{};
    int32_t zOff[4]{};
    SplineLinear splineBase;
    SplineLinear splinePeak;
    SplineLinear splineStrength;
    float detailAmplitude = 0;

    int64_t seed = 0;
    FastNoise::SmartNode<> headingNoise;
    FastNoise::SmartNode<> radiusNoise;
    FastNoise::SmartNode<> wobbleNoise;
    int32_t headingSeed = 0;
    int32_t radiusSeed = 0;
    int32_t wobbleSeed = 0;

    // Java channel axis convention: FastNoise2 X carries worldZ, Y carries worldX.
    [[nodiscard]] float sampleChannel(int i, int x, int z) const {
        return channel[i]->GenSingle2D(static_cast<float>(z + zOff[i]),
                                       static_cast<float>(x + xOff[i]), channelSeed[i]);
    }

    // HeightMapGenerator.heightFromChannels, exact float/double op order.
    [[nodiscard]] int generateHeight(int x, int z) const {
        float c = sampleChannel(0, x, z);
        float pv = sampleChannel(1, x, z);
        float e = sampleChannel(2, x, z);
        float d = sampleChannel(3, x, z);
        float base = static_cast<float>(splineBase.interpolate(c));
        float peak = static_cast<float>(splinePeak.interpolate(pv));
        float strength = static_cast<float>(splineStrength.interpolate(e));
        float detail = d * detailAmplitude;
        int h = cenda::javaRoundFloat(base + peak * strength + detail);
        if (h < 1) h = 1;
        if (h > WORLD_HEIGHT - 1) h = WORLD_HEIGHT - 1;
        return h;
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
    const int surface = ctx.generateHeight(cenda::javaRoundFloat(ox), cenda::javaRoundFloat(oz));
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
     * surface/water that step already fetched. Seeded from the segment's own origin. */
    Zone zone = waterTableZoneAt(ctx.waterTableAt(cenda::javaRoundFloat(x), cenda::javaRoundFloat(z)),
                                 cenda::javaRoundFloat(y));

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

        const int wxi = cenda::javaRoundFloat(x);
        const int wyi = cenda::javaRoundFloat(y);
        const int wzi = cenda::javaRoundFloat(z);
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

/* Shared construction for ck_terrain_create and the fused generator context.
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
    auto ctx = new TerrainCtx();
    ctx->seed = worm_seed;
    ctx->detailAmplitude = detail_amplitude;
    for (int i = 0; i < 4; i++) {
        ctx->channel[i] = cenda::makeSimplexFbm(ch_octaves[i], ch_lacunarity[i], ch_gain[i], ch_freq[i]);
        if (!ctx->channel[i]) {
            delete ctx;
            return nullptr;
        }
        ctx->channelSeed[i] = ch_seeds[i];
        ctx->xOff[i] = ch_xoff[i];
        ctx->zOff[i] = ch_zoff[i];
    }
    SplineLinear* splines[3] = {&ctx->splineBase, &ctx->splinePeak, &ctx->splineStrength};
    int32_t offset = 0;
    for (int s = 0; s < 3; s++) {
        for (int32_t p = 0; p < spline_sizes[s]; p++) {
            splines[s]->addPoint(spline_xs[offset + p], spline_ys[offset + p]);
        }
        offset += spline_sizes[s];
    }
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
        bits += __builtin_popcountll(out_mask[i]);
    }
    return bits;
}

} // namespace cenda::gen
