/* Fused native chunk generator: worm carve + cavern/megacavern carve &
 * formations + 3D cave density + biome block fill + sky heightmap, one call.
 *
 * The cavern/megacavern/formation/fill code is an EXACT port of the Java
 * sources (CavernCarver.java, MegaCavernCarver.java,
 * TerrainGenerationSystem.determineBlockType, DeterministicRandom) — no libm
 * in those paths, all hash math in uint64 (Java wrapping semantics), RNG draw
 * order preserved to the call. Given identical inputs the output is
 * bit-identical to the mixed path (native worms + Java caverns + Java fill),
 * which FusedChunkGenParityTest pins against the living Java code.
 *
 * Constants mirror the Java classes and must stay in lockstep. */
#include "cenda/kernels.h"
#include "terrain_ctx.hpp"

#include <algorithm>
#include <cstdint>
#include <vector>

namespace {

using cenda::JavaRandom;
using cenda::SplineLinear;
using cenda::gen::AnchorSource;
using cenda::gen::TerrainCtx;
using cenda::gen::CHUNK_SIZE;
using cenda::gen::SEA_LEVEL;
using cenda::gen::WORLD_HEIGHT;

/* PerlinWormCarver.CAVERN_CONNECTOR_RADIUS */
constexpr int CAVERN_CONNECTOR_RADIUS = 5;

/* Density3D constants (lockstep with Density3D.java). */
/* ── Density3D.java ───────────────────────────────────────────────────────────
 * Three channels now: one "cheese" (chambers) and two "spaghetti" whose zero
 * isosurfaces intersect in tubes. The single-channel field this replaced is gone;
 * biome caveIntensity no longer gates caves at all, only the overhang band. */
constexpr int DENSITY_CAVE_FLOOR = 8;
constexpr int DENSITY_OVERHANG_DEPTH = 16;

constexpr float CHEESE_Y_SQUASH = 1.6f;
constexpr float SPAG_Y_SQUASH = 1.08f;
constexpr float SPAG_THICKNESS = 0.085f;
constexpr float SPAG_GALLERY_BONUS = 0.070f;
constexpr int SPAG_FADE_START = 10;
constexpr int SPAG_FADE_END = 34;

/* Density3D.spaghettiFade. */
inline float spaghettiFade(int depth) {
    if (depth <= SPAG_FADE_START) return 0.0f;
    if (depth >= SPAG_FADE_END) return 1.0f;
    return static_cast<float>(depth - SPAG_FADE_START)
        / static_cast<float>(SPAG_FADE_END - SPAG_FADE_START);
}

constexpr int MASK_WORDS = 1024; /* 65536 bits, bit = (x<<12) | (y<<4) | z */

inline bool testBit(const uint64_t* mask, int bit) {
    return (mask[bit >> 6] & (1ULL << (bit & 63))) != 0;
}

inline void setBit(uint64_t* mask, int bit) {
    mask[bit >> 6] |= 1ULL << (bit & 63);
}

inline void clearBit(uint64_t* mask, int bit) {
    mask[bit >> 6] &= ~(1ULL << (bit & 63));
}

/* One parameterized implementation covers CavernCarver and MegaCavernCarver —
 * they differ only in constants and hash mixers. */
struct CavernParams {
    /* hasCavern: h = seed ^ hashXor; h ^= cx*hashMulX; rotl(hashRot);
     * h ^= cz*hashMulZ; floorMod(h, divisor) == 0 (divisor is a power of two,
     * so floorMod == unsigned h & (divisor-1)). */
    uint64_t hashXor, hashMulX, hashMulZ;
    int hashRot;
    int32_t divisor;      /* NOT a power of two — needs a real floorMod */
    /* cavernRngSeed: ((seed*seedMul) ^ (cx*seedMulX)) ^ (cz*seedMulZ) ^ seedFinalXor */
    uint64_t seedMul, seedMulX, seedMulZ, seedFinalXor;
    /* formationSeed: h = seed ^ formXor; h ^= wx*formMulX; rotl(formRot); h ^= wz*formMulZ */
    uint64_t formXor, formMulX, formMulZ;
    int formRot;

    /* Center depth BELOW the local surface, replacing the old absolute Y band. */
    int depthMin, depthMax;
    float baseRadius, blobOffset, ySquash;
    int minBlobs, maxBlobs;
    float stalagmiteChance, stalactiteChance;
    int formationMaxHeight;
    int scanRadius;      /* ceil((blobOffset + baseRadius*1.20f) / 16) + 1 */
    int waterClearance;  /* ceil(baseRadius * 1.20f) + 1 */
};

CavernParams cavernParams() { /* CavernCarver.java */
    CavernParams p{};
    p.hashXor = UINT64_C(0xCA7EAA12CA7EAA12);
    p.hashMulX = UINT64_C(0xBEA225F9EB34556D);
    p.hashRot = 19;
    p.hashMulZ = UINT64_C(0x94D049BB133111EB);
    p.divisor = 48;
    p.seedMul = UINT64_C(6364136223846793005);
    p.seedMulX = UINT64_C(0x9E3779B97F4A7C15);
    p.seedMulZ = UINT64_C(0xC2B2AE3D27D4EB4F);
    p.seedFinalXor = 0;
    p.formXor = UINT64_C(0x57A1AC1117EFCAFE);
    p.formMulX = UINT64_C(0x9E3779B97F4A7C15);
    p.formRot = 17;
    p.formMulZ = UINT64_C(0xC2B2AE3D27D4EB4F);
    p.depthMin = 18;
    p.depthMax = 65;
    p.baseRadius = 10.0f;
    p.blobOffset = 8.0f;
    p.ySquash = 0.62f;
    p.minBlobs = 5;
    p.maxBlobs = 9;
    p.stalagmiteChance = 0.10f;
    p.stalactiteChance = 0.08f;
    p.formationMaxHeight = 5;
    const float maxReach = p.blobOffset + p.baseRadius * 1.20f;
    p.scanRadius = static_cast<int>(std::ceil(
        static_cast<double>(maxReach / static_cast<float>(CHUNK_SIZE)))) + 1;
    p.waterClearance = static_cast<int>(std::ceil(
        static_cast<double>(p.baseRadius * 1.20f))) + 1;
    return p;
}

CavernParams megaCavernParams() { /* MegaCavernCarver.java */
    CavernParams p{};
    p.hashXor = UINT64_C(0x3EA9CA77B16BEEF0);
    p.hashMulX = UINT64_C(0xD1B54A32D192ED03);
    p.hashRot = 23;
    p.hashMulZ = UINT64_C(0xAEF17502108EF2D9);
    p.divisor = 192;
    p.seedMul = UINT64_C(0x9E3779B97F4A7C15);
    p.seedMulX = UINT64_C(0xBF58476D1CE4E5B9);
    p.seedMulZ = UINT64_C(0x94D049BB133111EB);
    p.seedFinalXor = UINT64_C(0xCAFEBABE1337F00D);
    p.formXor = UINT64_C(0xF1E2D3C4B5A69788);
    p.formMulX = UINT64_C(0xD1B54A32D192ED03);
    p.formRot = 19;
    p.formMulZ = UINT64_C(0xAEF17502108EF2D9);
    p.depthMin = 30;
    p.depthMax = 90;
    p.baseRadius = 24.0f;
    p.blobOffset = 15.0f;
    p.ySquash = 0.66f;
    p.minBlobs = 12;
    p.maxBlobs = 18;
    p.stalagmiteChance = 0.12f;
    p.stalactiteChance = 0.10f;
    p.formationMaxHeight = 9;
    const float maxReach = p.blobOffset + p.baseRadius * 1.20f;
    p.scanRadius = static_cast<int>(std::ceil(
        static_cast<double>(maxReach / static_cast<float>(CHUNK_SIZE)))) + 1;
    p.waterClearance = static_cast<int>(std::ceil(
        static_cast<double>(p.baseRadius * 1.20f))) + 1;
    return p;
}

inline uint64_t rotl64(uint64_t v, int distance) {
    return (v << distance) | (v >> (64 - distance));
}

inline uint64_t signExt(int32_t v) {
    return static_cast<uint64_t>(static_cast<int64_t>(v));
}

bool hasCavern(const CavernParams& p, int64_t seed, int cx, int cz) {
    uint64_t h = static_cast<uint64_t>(seed) ^ p.hashXor;
    h ^= signExt(cx) * p.hashMulX;
    h = rotl64(h, p.hashRot);
    h ^= signExt(cz) * p.hashMulZ;
    return cenda::javaFloorMod(static_cast<int64_t>(h), p.divisor) == 0;
}

int64_t cavernRngSeed(const CavernParams& p, int64_t seed, int cx, int cz) {
    uint64_t h = (static_cast<uint64_t>(seed) * p.seedMul) ^ (signExt(cx) * p.seedMulX);
    h = h ^ (signExt(cz) * p.seedMulZ) ^ p.seedFinalXor;
    return static_cast<int64_t>(h);
}

int64_t formationSeed(const CavernParams& p, int64_t seed, int worldX, int worldZ) {
    uint64_t h = static_cast<uint64_t>(seed) ^ p.formXor;
    h ^= signExt(worldX) * p.formMulX;
    h = rotl64(h, p.formRot);
    h ^= signExt(worldZ) * p.formMulZ;
    return static_cast<int64_t>(h);
}

/* CavernCarver.centerY — a depth below THIS column's surface, not an absolute band.
 * Clamped to y>=2, so a depth range overshooting the rock column piles caverns on
 * bedrock; the Java depth ranges are chosen to stay inside a ~114-block column. */
float cavernCenterY(const CavernParams& p, const TerrainCtx& terrain,
                    float ox, float oz, JavaRandom& rng) {
    const int surface = terrain.generateHeight(cenda::javaRoundFloat(ox), cenda::javaRoundFloat(oz));
    const int depth = p.depthMin + rng.nextInt(p.depthMax - p.depthMin);
    const int y = surface - depth;
    return static_cast<float>(y > 2 ? y : 2);
}

/* Mirrors CavernCarver.computeCavernOrigin's three RNG draws exactly. */
void computeCavernOrigin(const CavernParams& p, const TerrainCtx& terrain,
                         int64_t seed, int cx, int cz, float out[3]) {
    JavaRandom rng(cavernRngSeed(p, seed, cx, cz));
    out[0] = static_cast<float>(cx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    out[2] = static_cast<float>(cz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    out[1] = cavernCenterY(p, terrain, out[0], out[2], rng);
}

/* Mirrors CavernCarver.nearestCavernChunk (includes the center chunk). */
bool nearestCavernChunk(const CavernParams& p, int64_t seed, int cx, int cz,
                        int searchRadius, int out[2]) {
    int bestDistSq = INT32_MAX;
    bool found = false;
    for (int dcx = -searchRadius; dcx <= searchRadius; dcx++) {
        for (int dcz = -searchRadius; dcz <= searchRadius; dcz++) {
            const int ncx = cx + dcx, ncz = cz + dcz;
            if (!hasCavern(p, seed, ncx, ncz)) continue;
            const int d = dcx * dcx + dcz * dcz;
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

/* Cavern ellipsoid raster — differs from the worm variant: float center
 * (rounded here), per-column waterClearance gate, and the by >= surface clamp. */
void carveCavernEllipsoid(const CavernParams& p, float wx, float wy, float wz, float radius,
                          int targetCx, int targetCz, const int32_t* targetHeights,
                          const int32_t* waterGuard, uint64_t* mask) {
    const int targetBaseX = targetCx * CHUNK_SIZE;
    const int targetBaseZ = targetCz * CHUNK_SIZE;
    const int rxz = static_cast<int>(std::ceil(static_cast<double>(radius)));
    const int ry = static_cast<int>(std::ceil(static_cast<double>(radius * p.ySquash)));
    const int wxi = cenda::javaRoundFloat(wx);
    const int wyi = cenda::javaRoundFloat(wy);
    const int wzi = cenda::javaRoundFloat(wz);
    if (wxi + rxz < targetBaseX || wxi - rxz >= targetBaseX + CHUNK_SIZE) return;
    if (wzi + rxz < targetBaseZ || wzi - rxz >= targetBaseZ + CHUNK_SIZE) return;

    const float invRxz2 = 1.0f / (radius * radius);
    const float ySpan = radius * p.ySquash;
    const float invRy2 = 1.0f / (ySpan * ySpan);

    for (int ox = -rxz; ox <= rxz; ox++) {
        const int bx = wxi + ox - targetBaseX;
        if (bx < 0 || bx >= CHUNK_SIZE) continue;
        for (int oz = -rxz; oz <= rxz; oz++) {
            const int bz = wzi + oz - targetBaseZ;
            if (bz < 0 || bz >= CHUNK_SIZE) continue;
            const int idx = bx * CHUNK_SIZE + bz;
            const int surface = targetHeights[idx];
            /* Only the world floor here; waterGuardSeals below anchors on the bed of any
             * wet column in this column's 4-neighborhood, covering beds and banks alike. */
            if (surface <= 1) continue;
            const float horizTerm = static_cast<float>(ox * ox + oz * oz) * invRxz2;
            if (horizTerm >= 1.0f) continue;
            const float maxOyTerm = 1.0f - horizTerm;
            for (int oy = -ry; oy <= ry; oy++) {
                if (static_cast<float>(oy * oy) * invRy2 >= maxOyTerm) continue;
                const int by = wyi + oy;
                if (by < 1 || by >= WORLD_HEIGHT) continue;
                if (by >= surface) continue;
                if (cenda::gen::waterGuardSeals(waterGuard, idx, by, p.waterClearance)) continue;
                setBit(mask, (bx << 12) | (by << 4) | bz);
            }
        }
    }
}

/* Mirrors CavernCarver.carveCavern's RNG draw order exactly (3 origin draws
 * shared with computeCavernOrigin, blob count, then 4 draws per blob). */
void carveCavern(const CavernParams& p, const TerrainCtx& terrain, int64_t seed,
                 int srcCx, int srcCz, int targetCx, int targetCz,
                 const int32_t* targetHeights, const int32_t* waterGuard, uint64_t* mask) {
    JavaRandom rng(cavernRngSeed(p, seed, srcCx, srcCz));
    const float ox = static_cast<float>(srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    const float oz = static_cast<float>(srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    const float oy = cavernCenterY(p, terrain, ox, oz, rng);

    const int blobs = p.minBlobs + rng.nextInt(p.maxBlobs - p.minBlobs + 1);
    for (int i = 0; i < blobs; i++) {
        const float dx = (rng.nextFloat() - 0.5f) * 2.0f * p.blobOffset;
        const float dy = (rng.nextFloat() - 0.5f) * 2.0f * (p.blobOffset * 0.45f);
        const float dz = (rng.nextFloat() - 0.5f) * 2.0f * p.blobOffset;
        const float r = p.baseRadius * (0.75f + rng.nextFloat() * 0.45f);
        carveCavernEllipsoid(p, ox + dx, oy + dy, oz + dz, r,
                             targetCx, targetCz, targetHeights, waterGuard, mask);
    }
}

/* CavernCarver.buildForChunk carve scan (formations are built separately from
 * this carver's own mask only, matching the Java per-carver Result split). */
void buildCavernCarve(const CavernParams& p, const TerrainCtx& terrain, int64_t seed,
                      int chunkX, int chunkZ, const int32_t* targetHeights,
                      const int32_t* waterGuard, uint64_t* mask) {
    for (int dcx = -p.scanRadius; dcx <= p.scanRadius; dcx++) {
        for (int dcz = -p.scanRadius; dcz <= p.scanRadius; dcz++) {
            const int srcCx = chunkX + dcx;
            const int srcCz = chunkZ + dcz;
            if (!hasCavern(p, seed, srcCx, srcCz)) continue;
            carveCavern(p, terrain, seed, srcCx, srcCz, chunkX, chunkZ,
                        targetHeights, waterGuard, mask);
        }
    }
}

/* Mirrors CavernCarver.buildFormations: per-column scan of THIS carver's carve
 * mask for its TALLEST CONTIGUOUS run, fresh Random per column, ?: short-circuit
 * draw order. The run — not the column's overall min/max — is what a formation
 * stands in; see the Java for why the global extremes grew pillars through rock
 * and left their tips floating in the next void up. */
void buildFormations(const CavernParams& p, int64_t seed, int chunkX, int chunkZ,
                     const uint64_t* carve, uint64_t* formations) {
    const int baseX = chunkX * CHUNK_SIZE;
    const int baseZ = chunkZ * CHUNK_SIZE;
    for (int bx = 0; bx < CHUNK_SIZE; bx++) {
        for (int bz = 0; bz < CHUNK_SIZE; bz++) {
            int floorY = -1;
            int ceilY = -1;
            int runStart = -1;
            for (int by = 1; by <= WORLD_HEIGHT; by++) {
                if (by < WORLD_HEIGHT && testBit(carve, (bx << 12) | (by << 4) | bz)) {
                    if (runStart < 0) runStart = by;
                    continue;
                }
                if (runStart >= 0) {
                    /* Strictly taller, so ties keep the lowest run. */
                    if (by - 1 - runStart > ceilY - floorY) {
                        floorY = runStart;
                        ceilY = by - 1;
                    }
                    runStart = -1;
                }
            }
            /* Covers both "no carve here" and "no run taller than one cell": the
             * initial -1/-1 pair scores 0, which nothing one cell tall can beat. */
            if (floorY < 0) continue;
            const int gap = ceilY - floorY;

            const int worldX = baseX + bx;
            const int worldZ = baseZ + bz;
            JavaRandom rng(formationSeed(p, seed, worldX, worldZ));

            int stalagH = rng.nextFloat() < p.stalagmiteChance
                ? 1 + rng.nextInt(p.formationMaxHeight) : 0;
            int stalactiteH = rng.nextFloat() < p.stalactiteChance
                ? 1 + rng.nextInt(p.formationMaxHeight) : 0;
            const int total = stalagH + stalactiteH;
            if (total > gap && total > 0) {
                /* (int) ((long) h * gap / total) — int64 math, truncating. */
                stalagH = static_cast<int>(static_cast<int64_t>(stalagH) * gap / total);
                stalactiteH = static_cast<int>(static_cast<int64_t>(stalactiteH) * gap / total);
            }

            /* Both anchors are solid within this carver's own mask by construction —
             * a run ends where the carve stops. Whether they survive the other carvers
             * is settled afterwards, by pruneUnsupportedFormations. */
            if (stalagH > 0) {
                for (int h = 0; h < stalagH; h++) {
                    const int by = floorY + h;
                    if (by > ceilY) break;
                    setBit(formations, (bx << 12) | (by << 4) | bz);
                }
            }
            if (stalactiteH > 0) {
                for (int h = 0; h < stalactiteH; h++) {
                    const int by = ceilY - h;
                    if (by < floorY) break;
                    setBit(formations, (bx << 12) | (by << 4) | bz);
                }
            }
        }
    }
}

/* DeterministicRandom.getRandomForPosition3D(...).nextFloat() < chance.
 * Java's >>> on long == logical shift on uint64; multipliers are positive
 * 32-bit constants widened to long; coordinates sign-extend. */
bool magmaAt(int64_t worldSeed, int x, int y, int z, int32_t featureHash, float chance) {
    uint64_t h = static_cast<uint64_t>(worldSeed);
    h ^= signExt(x) * UINT64_C(0x9e3779b9);
    h ^= h >> 16;
    h ^= signExt(y) * UINT64_C(0x8f8f8f8f);
    h ^= h >> 11;
    h ^= signExt(z) * UINT64_C(0x85ebca6b);
    h ^= h >> 13;
    h ^= signExt(featureHash) * UINT64_C(0xc2b2ae35);
    h ^= h >> 16;
    h ^= h >> 32;
    return JavaRandom(static_cast<int64_t>(h)).nextFloat() < chance;
}

struct ChunkGenCtx {
    TerrainCtx* terrain = nullptr; /* owned */
    /* [0] cheese, [1] spaghetti 1, [2] spaghetti 2 — Density3D's fill order. */
    FastNoise::SmartNode<> densityNode[3];
    int32_t densitySeed[3] = {};
    float densityYSquash[3] = {CHEESE_Y_SQUASH, SPAG_Y_SQUASH, SPAG_Y_SQUASH};
    SplineLinear cheeseThreshold;
    int64_t seed = 0;

    int16_t airId = 0, waterId = 0, stoneId = 0, bedrockId = 0, magmaId = 0;

    int32_t nBiomes = 0;
    std::vector<int16_t> biomeSurface, biomeSubsurface;
    std::vector<float> biomeCave, biomeOverhang;
    std::vector<uint8_t> biomeFlags;

    int32_t magmaFeatureHash = 0;
    float magmaChance = 0;

    std::vector<uint8_t> opacity;

    CavernParams cavern = cavernParams();
    CavernParams mega = megaCavernParams();

    ~ChunkGenCtx() { delete terrain; }
};

/* Mirrors PerlinWormCarver.nearestCavernAnchor: the closer of (nearest normal
 * cavern anchor, nearest megacavern anchor) by world-space distance from the
 * worm origin, both searched within CAVERN_CONNECTOR_RADIUS. */
struct NativeAnchorSource final : AnchorSource {
    const ChunkGenCtx* ctx = nullptr;

    bool anchorFor(int cx, int cz, float ox, float oy, float oz,
                   float out[3]) const override {
        float best[3] = {0, 0, 0};
        float bestDistSq = 0;
        bool found = false;
        const CavernParams* sets[2] = {&ctx->cavern, &ctx->mega};
        for (const CavernParams* p : sets) {
            int neighbor[2] = {0, 0};
            if (!nearestCavernChunk(*p, ctx->seed, cx, cz, CAVERN_CONNECTOR_RADIUS, neighbor)) {
                continue;
            }
            float anchor[3];
            computeCavernOrigin(*p, *ctx->terrain, ctx->seed, neighbor[0], neighbor[1], anchor);
            const float dx = anchor[0] - ox;
            const float dy = anchor[1] - oy;
            const float dz = anchor[2] - oz;
            const float d = dx * dx + dy * dy + dz * dz;
            if (!found || d < bestDistSq) {
                bestDistSq = d;
                best[0] = anchor[0];
                best[1] = anchor[1];
                best[2] = anchor[2];
                found = true;
            }
        }
        if (found) {
            out[0] = best[0];
            out[1] = best[1];
            out[2] = best[2];
        }
        return found;
    }
};

/* Density3D.solidInOverhangBand — the biome rule, now a UNION with the cave test
 * rather than a branch that short-circuits it. */
bool solidInOverhangBand(const ChunkGenCtx& c, float cheese, int biomeIdx) {
    const float intensity = c.biomeOverhang[static_cast<size_t>(biomeIdx)];
    if (intensity <= 0.0f) {
        return true;
    }
    /* CAUTION: Java's helper is NAMED carve() but the caller RETURNS IT DIRECTLY —
     * `n < 1 - 2*intensity` is the SOLID predicate (air on the high-noise tail). */
    return cheese < (1.0f - 2.0f * intensity);
}

/* Density3D.solidAt — the carve decision, shared by both backends. */
bool densitySolidAt(const ChunkGenCtx& c, float cheese, float s1, float s2,
                    int y, int surfaceHeight, int table) {
    const int depth = surfaceHeight - y;
    if (static_cast<double>(cheese) > c.cheeseThreshold.interpolate(depth)) {
        return false;
    }
    /* Two noise sheets intersect in a curve: this is the tube test. */
    const float thickness =
        (SPAG_THICKNESS + SPAG_GALLERY_BONUS * cenda::gen::waterTableGalleryWeight(table, y))
        * spaghettiFade(depth);
    if (thickness > 0.0f && std::fabs(s1) < thickness && std::fabs(s2) < thickness) {
        return false;
    }
    return true;
}

/* Density3D.Field.isSolid — chunk-local, volumes laid out
 * [(y - CAVE_FLOOR)*256 + localX*16 + localZ]. volume == nullptr means the
 * whole chunk is below the cave floor (prepareChunk returned null): solid. */
bool densitySolid(const ChunkGenCtx& c, int localX, int y, int localZ,
                  int surfaceHeight, int biomeIdx, const float* cheeseVol,
                  const float* spag1Vol, const float* spag2Vol, int yCount,
                  const int32_t* table) {
    if (y < DENSITY_CAVE_FLOOR || y >= surfaceHeight) {
        return true;
    }
    if (cheeseVol == nullptr) {
        return true;
    }
    const int yIndex = y - DENSITY_CAVE_FLOOR;
    if (yIndex >= yCount) {
        return true;
    }
    const int i = (yIndex * CHUNK_SIZE + localX) * CHUNK_SIZE + localZ;
    const int col = localX * CHUNK_SIZE + localZ;
    const float cheese = cheeseVol[i];
    if (y >= surfaceHeight - DENSITY_OVERHANG_DEPTH && !solidInOverhangBand(c, cheese, biomeIdx)) {
        return false;
    }
    return densitySolidAt(c, cheese, spag1Vol[i], spag2Vol[i], y, surfaceHeight, table[col]);
}

/* FormationSupport.prune — clears every formation run that neither the block below
 * it nor the block above it holds up. The support test is a caller-supplied
 * predicate for the same reason the Java takes an interface: it is the head of the
 * block fill, and only the caller has the pieces. */
template <typename SupportFn>
void pruneUnsupportedFormations(uint64_t* formations, SupportFn&& solidAt) {
    for (int x = 0; x < CHUNK_SIZE; x++) {
        for (int z = 0; z < CHUNK_SIZE; z++) {
            int runStart = -1;
            for (int y = 1; y <= WORLD_HEIGHT; y++) {
                if (y < WORLD_HEIGHT && testBit(formations, (x << 12) | (y << 4) | z)) {
                    if (runStart < 0) runStart = y;
                    continue;
                }
                if (runStart < 0) continue;
                const int runEnd = y - 1;
                if (!solidAt(x, runStart - 1, z) && !solidAt(x, runEnd + 1, z)) {
                    for (int cy = runStart; cy <= runEnd; cy++) {
                        clearBit(formations, (x << 12) | (cy << 4) | z);
                    }
                }
                runStart = -1;
            }
        }
    }
}

/* TerrainGenerationSystem.determineBlockType, exact branch order. */
int16_t determineBlock(const ChunkGenCtx& c, int worldX, int y, int worldZ,
                       int height, int biomeIdx, const float* cheeseVol,
                       const float* spag1Vol, const float* spag2Vol, int yCount,
                       const int32_t* table, int localX, int localZ) {
    if (y == 0) {
        return c.bedrockId;
    }
    if (y < height && !densitySolid(c, localX, y, localZ, height, biomeIdx,
                                    cheeseVol, spag1Vol, spag2Vol, yCount, table)) {
        return c.airId;
    }
    const uint8_t flags = c.biomeFlags[static_cast<size_t>(biomeIdx)];
    if (y < height - 4) {
        if ((flags & CK_BIOME_MAGMA) != 0 && y < height - 10
                && magmaAt(c.seed, worldX, y, worldZ, c.magmaFeatureHash, c.magmaChance)) {
            return c.magmaId;
        }
        return c.stoneId;
    }
    if (y < height - 1) {
        return c.biomeSubsurface[static_cast<size_t>(biomeIdx)];
    }
    if (y < height) {
        return c.biomeSurface[static_cast<size_t>(biomeIdx)];
    }
    if (y < SEA_LEVEL) {
        if ((flags & CK_BIOME_DRY_BELOW_SEA) != 0 && height > SEA_LEVEL) {
            return c.airId;
        }
        return c.waterId;
    }
    return c.airId;
}

} // namespace

extern "C" {

void* ck_chunkgen_create(
    int64_t seed,
    const int32_t* ch_seeds, const int32_t* ch_octaves,
    const float* ch_gain, const float* ch_lacunarity, const float* ch_freq,
    const int32_t* ch_xoff, const int32_t* ch_zoff,
    const double* spline_xs, const double* spline_ys, const int32_t* spline_sizes,
    float detail_amplitude,
    const int32_t* density_seeds, const int32_t* density_octaves,
    const float* density_gain, const float* density_lacunarity, const float* density_freq,
    const double* cheese_spline_xs, const double* cheese_spline_ys,
    const int32_t* cheese_spline_sizes,
    const int32_t* block_ids,
    int32_t n_biomes,
    const int16_t* biome_surface_id, const int16_t* biome_subsurface_id,
    const float* biome_cave_intensity, const float* biome_overhang_intensity,
    const uint8_t* biome_flags,
    int32_t magma_feature_hash, float magma_chance,
    const uint8_t* opacity_table, int32_t opacity_table_len) {
    if (density_seeds == nullptr || density_octaves == nullptr || density_gain == nullptr
            || density_lacunarity == nullptr || density_freq == nullptr
            || cheese_spline_xs == nullptr || cheese_spline_ys == nullptr
            || cheese_spline_sizes == nullptr
            || block_ids == nullptr || n_biomes <= 0
            || biome_surface_id == nullptr || biome_subsurface_id == nullptr
            || biome_cave_intensity == nullptr || biome_overhang_intensity == nullptr
            || biome_flags == nullptr
            || opacity_table == nullptr || opacity_table_len <= 0) {
        return nullptr;
    }
    TerrainCtx* terrain = cenda::gen::terrainCreateImpl(
        seed, ch_seeds, ch_octaves, ch_gain, ch_lacunarity, ch_freq,
        ch_xoff, ch_zoff, spline_xs, spline_ys, spline_sizes, detail_amplitude);
    if (terrain == nullptr) {
        return nullptr;
    }
    FastNoise::SmartNode<> densityNodes[3];
    for (int n = 0; n < 3; n++) {
        densityNodes[n] = cenda::makeSimplexFbm(density_octaves[n], density_lacunarity[n],
                                                density_gain[n], density_freq[n]);
        if (!densityNodes[n]) {
            delete terrain;
            return nullptr;
        }
    }

    auto* ctx = new ChunkGenCtx();
    ctx->terrain = terrain;
    for (int n = 0; n < 3; n++) {
        ctx->densityNode[n] = std::move(densityNodes[n]);
        ctx->densitySeed[n] = density_seeds[n];
    }
    for (int32_t i = 0; i < cheese_spline_sizes[0]; i++) {
        ctx->cheeseThreshold.addPoint(cheese_spline_xs[i], cheese_spline_ys[i]);
    }
    ctx->seed = seed;
    ctx->airId = static_cast<int16_t>(block_ids[0]);
    ctx->waterId = static_cast<int16_t>(block_ids[1]);
    ctx->stoneId = static_cast<int16_t>(block_ids[2]);
    ctx->bedrockId = static_cast<int16_t>(block_ids[3]);
    ctx->magmaId = static_cast<int16_t>(block_ids[4]);
    ctx->nBiomes = n_biomes;
    const auto nb = static_cast<size_t>(n_biomes);
    ctx->biomeSurface.assign(biome_surface_id, biome_surface_id + nb);
    ctx->biomeSubsurface.assign(biome_subsurface_id, biome_subsurface_id + nb);
    ctx->biomeCave.assign(biome_cave_intensity, biome_cave_intensity + nb);
    ctx->biomeOverhang.assign(biome_overhang_intensity, biome_overhang_intensity + nb);
    ctx->biomeFlags.assign(biome_flags, biome_flags + nb);
    ctx->magmaFeatureHash = magma_feature_hash;
    ctx->magmaChance = magma_chance;
    ctx->opacity.assign(opacity_table, opacity_table + static_cast<size_t>(opacity_table_len));
    return ctx;
}

void ck_chunkgen_destroy(void* ctx) {
    delete static_cast<ChunkGenCtx*>(ctx);
}

int64_t ck_generate_chunk(void* ctxPtr, int32_t chunk_x, int32_t chunk_z,
                          const int32_t* heights, const int32_t* biomes,
                          const uint64_t* extra_carve_mask,
                          int16_t* out_blocks, int32_t* out_heightmap) {
    auto* ctx = static_cast<ChunkGenCtx*>(ctxPtr);
    if (ctx == nullptr || heights == nullptr || biomes == nullptr || out_blocks == nullptr) {
        return -1;
    }
    for (int i = 0; i < CHUNK_SIZE * CHUNK_SIZE; i++) {
        if (biomes[i] < 0 || biomes[i] >= ctx->nBiomes) {
            return -2;
        }
    }

    /* Carve masks: worms (shared walk, native anchors) + caverns + megacaverns.
     * Formations are built per-carver from that carver's own mask, then OR'd —
     * exactly the Java Result split. */
    uint64_t caveMask[MASK_WORDS];
    uint64_t cavA[MASK_WORDS] = {};
    uint64_t cavB[MASK_WORDS] = {};
    uint64_t formMask[MASK_WORDS] = {};

    NativeAnchorSource anchors;
    anchors.ctx = ctx;
    cenda::gen::carveWormsImpl(*ctx->terrain, chunk_x, chunk_z, heights, &anchors, caveMask);

    int32_t waterGuard[CHUNK_SIZE * CHUNK_SIZE];
    cenda::gen::waterGuardPlane(*ctx->terrain, heights, chunk_x, chunk_z, waterGuard);

    buildCavernCarve(ctx->cavern, *ctx->terrain, ctx->seed, chunk_x, chunk_z,
                     heights, waterGuard, cavA);
    bool anyA = false;
    for (const uint64_t w : cavA) {
        if (w != 0) { anyA = true; break; }
    }
    if (anyA) {
        buildFormations(ctx->cavern, ctx->seed, chunk_x, chunk_z, cavA, formMask);
    }

    buildCavernCarve(ctx->mega, *ctx->terrain, ctx->seed, chunk_x, chunk_z,
                     heights, waterGuard, cavB);
    bool anyB = false;
    for (const uint64_t w : cavB) {
        if (w != 0) { anyB = true; break; }
    }
    if (anyB) {
        buildFormations(ctx->mega, ctx->seed, chunk_x, chunk_z, cavB, formMask);
    }

    for (int i = 0; i < MASK_WORDS; i++) {
        caveMask[i] |= cavA[i] | cavB[i];
    }
    /* Ravines and sinkholes, carved Java-side and handed over — see kernels.h. */
    if (extra_carve_mask != nullptr) {
        for (int i = 0; i < MASK_WORDS; i++) {
            caveMask[i] |= extra_carve_mask[i];
        }
    }

    /* Density volume: the identical GenUniformGrid3D call Density3D.prepareChunk
     * makes through ck_gen_grid_3d (fnX = worldZ, fnY = worldX, fnZ = squashed Y). */
    int maxSurface = 0;
    for (int i = 0; i < CHUNK_SIZE * CHUNK_SIZE; i++) {
        maxSurface = std::max(maxSurface, heights[i]);
    }
    /* Reused per generation thread — a fresh ~256 KB vector per chunk was the
     * only per-call heap allocation in this kernel. Contents for [0, yCount)
     * rows are fully overwritten by GenUniformGrid3D; vol is null when this
     * call generated no rows, so stale data from a previous chunk is never
     * read. */
    thread_local std::vector<float> volume[3];
    int yCount = 0;
    if (maxSurface > DENSITY_CAVE_FLOOR) {
        yCount = maxSurface - DENSITY_CAVE_FLOOR;
        const size_t need = static_cast<size_t>(yCount) * CHUNK_SIZE * CHUNK_SIZE;
        for (int n = 0; n < 3; n++) {
            if (volume[n].size() < need) {
                volume[n].resize(need);
            }
            ctx->densityNode[n]->GenUniformGrid3D(
                volume[n].data(),
                static_cast<float>(chunk_z * CHUNK_SIZE),
                static_cast<float>(chunk_x * CHUNK_SIZE),
                static_cast<float>(DENSITY_CAVE_FLOOR) * ctx->densityYSquash[n],
                CHUNK_SIZE, CHUNK_SIZE, yCount,
                1.0f, 1.0f, ctx->densityYSquash[n],
                ctx->densitySeed[n]);
        }
    }
    const float* cheeseVol = yCount > 0 ? volume[0].data() : nullptr;
    const float* spag1Vol = yCount > 0 ? volume[1].data() : nullptr;
    const float* spag2Vol = yCount > 0 ? volume[2].data() : nullptr;

    /* CaveWaterTable.tableForChunk — one batched wobble fill, then resolve per column. */
    float wobble[CHUNK_SIZE * CHUNK_SIZE];
    ctx->terrain->wobbleNoise->GenUniformGrid2D(
        wobble,
        static_cast<float>(chunk_z * CHUNK_SIZE), static_cast<float>(chunk_x * CHUNK_SIZE),
        CHUNK_SIZE, CHUNK_SIZE, 1.0f, 1.0f, ctx->terrain->wobbleSeed);
    int32_t table[CHUNK_SIZE * CHUNK_SIZE];
    for (int i = 0; i < CHUNK_SIZE * CHUNK_SIZE; i++) {
        table[i] = cenda::gen::waterTableResolve(
            heights[i], cenda::gen::waterLevelOf(heights[i]), wobble[i]);
    }

    /* Every carver is in and the density volumes exist, so a formation's anchor can
     * finally be tested against the chunk as it will actually be written — the same
     * point TerrainGenerationSystem prunes at, and the same predicate. */
    if (anyA || anyB) {
        pruneUnsupportedFormations(formMask, [&](int lx, int ly, int lz) -> bool {
            if (ly <= 0) return true; /* bedrock floor */
            const int col = lx * CHUNK_SIZE + lz;
            const int h = heights[col];
            if (ly >= h) return false; /* sky or open water above the surface */
            if (testBit(caveMask, (lx << 12) | (ly << 4) | lz)) return false;
            return densitySolid(*ctx, lx, ly, lz, h, biomes[col],
                                cheeseVol, spag1Vol, spag2Vol, yCount, table);
        });
    }

    /* Block fill — exact port of the generateTerrainOnly loop + determineBlockType. */
    std::fill_n(out_blocks, 65536, ctx->airId);
    const int baseX = chunk_x * CHUNK_SIZE;
    const int baseZ = chunk_z * CHUNK_SIZE;
    int64_t nonAir = 0;
    for (int x = 0; x < CHUNK_SIZE; x++) {
        for (int z = 0; z < CHUNK_SIZE; z++) {
            const int idx = x * CHUNK_SIZE + z;
            const int height = heights[idx];
            const int biomeIdx = biomes[idx];
            const int worldX = baseX + x;
            const int worldZ = baseZ + z;
            for (int y = 0; y < WORLD_HEIGHT; y++) {
                const int bit = (x << 12) | (y << 4) | z;
                int16_t block;
                if (y > 0 && y < height && testBit(formMask, bit)) {
                    block = ctx->stoneId;
                } else if (y > 0 && y < height && testBit(caveMask, bit)) {
                    continue; /* carved to air — already the fill value */
                } else {
                    block = determineBlock(*ctx, worldX, y, worldZ, height, biomeIdx,
                                           cheeseVol, spag1Vol, spag2Vol, yCount,
                                           table, x, z);
                }
                if (block != ctx->airId) {
                    out_blocks[y * 256 + z * 16 + x] = block;
                    nonAir++;
                }
            }
        }
    }

    /* Sky heightmap: Y+1 of the topmost opaque block per column, 0 = sky. */
    if (out_heightmap != nullptr) {
        const auto opacityLen = static_cast<int32_t>(ctx->opacity.size());
        for (int z = 0; z < CHUNK_SIZE; z++) {
            for (int x = 0; x < CHUNK_SIZE; x++) {
                int top = 0;
                for (int y = WORLD_HEIGHT - 1; y >= 0; y--) {
                    const int16_t id = out_blocks[y * 256 + z * 16 + x];
                    if (id >= 0 && id < opacityLen && ctx->opacity[static_cast<size_t>(id)] != 0) {
                        top = y + 1;
                        break;
                    }
                }
                out_heightmap[z * CHUNK_SIZE + x] = top;
            }
        }
    }

    return nonAir;
}

} // extern "C"
