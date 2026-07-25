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
using cenda::gen::AnchorSource;
using cenda::gen::TerrainCtx;
using cenda::gen::CHUNK_SIZE;
using cenda::gen::SEA_LEVEL;
using cenda::gen::WORLD_HEIGHT;

/* PerlinWormCarver.CAVERN_CONNECTOR_RADIUS */
constexpr int CAVERN_CONNECTOR_RADIUS = 5;

/* Density3D constants (lockstep with Density3D.java). */
constexpr int DENSITY_CAVE_FLOOR = 8;
constexpr int DENSITY_OVERHANG_DEPTH = 16;
constexpr float DENSITY_Y_SQUASH = 1.8f;

constexpr int MASK_WORDS = 1024; /* 65536 bits, bit = (x<<12) | (y<<4) | z */

inline bool testBit(const uint64_t* mask, int bit) {
    return (mask[bit >> 6] & (1ULL << (bit & 63))) != 0;
}

inline void setBit(uint64_t* mask, int bit) {
    mask[bit >> 6] |= 1ULL << (bit & 63);
}

/* One parameterized implementation covers CavernCarver and MegaCavernCarver —
 * they differ only in constants and hash mixers. */
struct CavernParams {
    /* hasCavern: h = seed ^ hashXor; h ^= cx*hashMulX; rotl(hashRot);
     * h ^= cz*hashMulZ; floorMod(h, divisor) == 0 (divisor is a power of two,
     * so floorMod == unsigned h & (divisor-1)). */
    uint64_t hashXor, hashMulX, hashMulZ;
    int hashRot;
    uint64_t divisorMask;
    /* cavernRngSeed: ((seed*seedMul) ^ (cx*seedMulX)) ^ (cz*seedMulZ) ^ seedFinalXor */
    uint64_t seedMul, seedMulX, seedMulZ, seedFinalXor;
    /* formationSeed: h = seed ^ formXor; h ^= wx*formMulX; rotl(formRot); h ^= wz*formMulZ */
    uint64_t formXor, formMulX, formMulZ;
    int formRot;

    int yMin, yMax;
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
    p.divisorMask = UINT64_C(64) - 1;
    p.seedMul = UINT64_C(6364136223846793005);
    p.seedMulX = UINT64_C(0x9E3779B97F4A7C15);
    p.seedMulZ = UINT64_C(0xC2B2AE3D27D4EB4F);
    p.seedFinalXor = 0;
    p.formXor = UINT64_C(0x57A1AC1117EFCAFE);
    p.formMulX = UINT64_C(0x9E3779B97F4A7C15);
    p.formRot = 17;
    p.formMulZ = UINT64_C(0xC2B2AE3D27D4EB4F);
    p.yMin = 10;
    p.yMax = 30;
    p.baseRadius = 7.0f;
    p.blobOffset = 5.5f;
    p.ySquash = 0.55f;
    p.minBlobs = 4;
    p.maxBlobs = 7;
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
    p.divisorMask = UINT64_C(256) - 1;
    p.seedMul = UINT64_C(0x9E3779B97F4A7C15);
    p.seedMulX = UINT64_C(0xBF58476D1CE4E5B9);
    p.seedMulZ = UINT64_C(0x94D049BB133111EB);
    p.seedFinalXor = UINT64_C(0xCAFEBABE1337F00D);
    p.formXor = UINT64_C(0xF1E2D3C4B5A69788);
    p.formMulX = UINT64_C(0xD1B54A32D192ED03);
    p.formRot = 19;
    p.formMulZ = UINT64_C(0xAEF17502108EF2D9);
    p.yMin = 8;
    p.yMax = 50;
    p.baseRadius = 18.0f;
    p.blobOffset = 11.0f;
    p.ySquash = 0.60f;
    p.minBlobs = 10;
    p.maxBlobs = 14;
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
    return (h & p.divisorMask) == 0; /* floorMod(h, 2^n) on the signed value */
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

/* Mirrors CavernCarver.computeCavernOrigin's three RNG draws exactly. */
void computeCavernOrigin(const CavernParams& p, int64_t seed, int cx, int cz, float out[3]) {
    JavaRandom rng(cavernRngSeed(p, seed, cx, cz));
    out[0] = static_cast<float>(cx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    out[2] = static_cast<float>(cz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    out[1] = static_cast<float>(p.yMin + rng.nextInt(p.yMax - p.yMin));
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
                          uint64_t* mask) {
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
            const int surface = targetHeights[bx * CHUNK_SIZE + bz];
            if (surface <= SEA_LEVEL + p.waterClearance) continue;
            const float horizTerm = static_cast<float>(ox * ox + oz * oz) * invRxz2;
            if (horizTerm >= 1.0f) continue;
            const float maxOyTerm = 1.0f - horizTerm;
            for (int oy = -ry; oy <= ry; oy++) {
                if (static_cast<float>(oy * oy) * invRy2 >= maxOyTerm) continue;
                const int by = wyi + oy;
                if (by < 1 || by >= WORLD_HEIGHT) continue;
                if (by >= surface) continue;
                setBit(mask, (bx << 12) | (by << 4) | bz);
            }
        }
    }
}

/* Mirrors CavernCarver.carveCavern's RNG draw order exactly (3 origin draws
 * shared with computeCavernOrigin, blob count, then 4 draws per blob). */
void carveCavern(const CavernParams& p, int64_t seed, int srcCx, int srcCz,
                 int targetCx, int targetCz, const int32_t* targetHeights, uint64_t* mask) {
    JavaRandom rng(cavernRngSeed(p, seed, srcCx, srcCz));
    const float ox = static_cast<float>(srcCx * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    const float oz = static_cast<float>(srcCz * CHUNK_SIZE + rng.nextInt(CHUNK_SIZE));
    const float oy = static_cast<float>(p.yMin + rng.nextInt(p.yMax - p.yMin));

    const int blobs = p.minBlobs + rng.nextInt(p.maxBlobs - p.minBlobs + 1);
    for (int i = 0; i < blobs; i++) {
        const float dx = (rng.nextFloat() - 0.5f) * 2.0f * p.blobOffset;
        const float dy = (rng.nextFloat() - 0.5f) * 2.0f * (p.blobOffset * 0.45f);
        const float dz = (rng.nextFloat() - 0.5f) * 2.0f * p.blobOffset;
        const float r = p.baseRadius * (0.75f + rng.nextFloat() * 0.45f);
        carveCavernEllipsoid(p, ox + dx, oy + dy, oz + dz, r,
                             targetCx, targetCz, targetHeights, mask);
    }
}

/* CavernCarver.buildForChunk carve scan (formations are built separately from
 * this carver's own mask only, matching the Java per-carver Result split). */
void buildCavernCarve(const CavernParams& p, int64_t seed, int chunkX, int chunkZ,
                      const int32_t* targetHeights, uint64_t* mask) {
    for (int dcx = -p.scanRadius; dcx <= p.scanRadius; dcx++) {
        for (int dcz = -p.scanRadius; dcz <= p.scanRadius; dcz++) {
            const int srcCx = chunkX + dcx;
            const int srcCz = chunkZ + dcz;
            if (!hasCavern(p, seed, srcCx, srcCz)) continue;
            carveCavern(p, seed, srcCx, srcCz, chunkX, chunkZ, targetHeights, mask);
        }
    }
}

/* Mirrors CavernCarver.buildFormations: per-column floor/ceiling scan of THIS
 * carver's carve mask, fresh Random per column, ?: short-circuit draw order. */
void buildFormations(const CavernParams& p, int64_t seed, int chunkX, int chunkZ,
                     const uint64_t* carve, uint64_t* formations) {
    const int baseX = chunkX * CHUNK_SIZE;
    const int baseZ = chunkZ * CHUNK_SIZE;
    for (int bx = 0; bx < CHUNK_SIZE; bx++) {
        for (int bz = 0; bz < CHUNK_SIZE; bz++) {
            int floorY = -1;
            int ceilY = -1;
            for (int by = 1; by < WORLD_HEIGHT; by++) {
                if (testBit(carve, (bx << 12) | (by << 4) | bz)) {
                    if (floorY < 0) floorY = by;
                    ceilY = by;
                }
            }
            const int gap = ceilY - floorY;
            if (floorY < 0 || gap < 1) continue;

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

            if (stalagH > 0 && floorY > 0
                    && !testBit(carve, (bx << 12) | ((floorY - 1) << 4) | bz)) {
                for (int h = 0; h < stalagH; h++) {
                    const int by = floorY + h;
                    if (by > ceilY) break;
                    setBit(formations, (bx << 12) | (by << 4) | bz);
                }
            }
            if (stalactiteH > 0 && ceilY < WORLD_HEIGHT - 1
                    && !testBit(carve, (bx << 12) | ((ceilY + 1) << 4) | bz)) {
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
    FastNoise::SmartNode<> densityNode;
    int32_t densitySeed = 0;
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
            computeCavernOrigin(*p, ctx->seed, neighbor[0], neighbor[1], anchor);
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

/* Density3D.Field.isSolid — chunk-local, volume laid out
 * [(y - CAVE_FLOOR)*256 + localX*16 + localZ]. volume == nullptr means the
 * whole chunk is below the cave floor (prepareChunk returned null): solid. */
bool densitySolid(const ChunkGenCtx& c, int localX, int y, int localZ,
                  int surfaceHeight, int biomeIdx, const float* volume, int yCount) {
    if (y < DENSITY_CAVE_FLOOR || y >= surfaceHeight) {
        return true;
    }
    const float intensity = (y >= surfaceHeight - DENSITY_OVERHANG_DEPTH)
        ? c.biomeOverhang[static_cast<size_t>(biomeIdx)]
        : c.biomeCave[static_cast<size_t>(biomeIdx)];
    if (intensity <= 0.0f) {
        return true;
    }
    if (volume == nullptr) {
        return true;
    }
    const int yIndex = y - DENSITY_CAVE_FLOOR;
    if (yIndex >= yCount) {
        return true;
    }
    const float n = volume[(yIndex * CHUNK_SIZE + localX) * CHUNK_SIZE + localZ];
    /* CAUTION: Java's helper is NAMED carve() but isSolid RETURNS IT DIRECTLY —
     * `n < 1 - 2*intensity` is the SOLID predicate (air on the high-noise tail).
     * Inverting this carves ~75% of the underground into floating-blob sponge. */
    return n < (1.0f - 2.0f * intensity);
}

/* TerrainGenerationSystem.determineBlockType, exact branch order. */
int16_t determineBlock(const ChunkGenCtx& c, int worldX, int y, int worldZ,
                       int height, int biomeIdx, const float* volume, int yCount,
                       int localX, int localZ) {
    if (y == 0) {
        return c.bedrockId;
    }
    if (y < height && !densitySolid(c, localX, y, localZ, height, biomeIdx, volume, yCount)) {
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
    int32_t density_seed, int32_t density_octaves,
    float density_gain, float density_lacunarity, float density_freq,
    const int32_t* block_ids,
    int32_t n_biomes,
    const int16_t* biome_surface_id, const int16_t* biome_subsurface_id,
    const float* biome_cave_intensity, const float* biome_overhang_intensity,
    const uint8_t* biome_flags,
    int32_t magma_feature_hash, float magma_chance,
    const uint8_t* opacity_table, int32_t opacity_table_len) {
    if (block_ids == nullptr || n_biomes <= 0
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
    auto densityNode = cenda::makeSimplexFbm(density_octaves, density_lacunarity,
                                             density_gain, density_freq);
    if (!densityNode) {
        delete terrain;
        return nullptr;
    }

    auto* ctx = new ChunkGenCtx();
    ctx->terrain = terrain;
    ctx->densityNode = std::move(densityNode);
    ctx->densitySeed = density_seed;
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

    buildCavernCarve(ctx->cavern, ctx->seed, chunk_x, chunk_z, heights, cavA);
    bool anyA = false;
    for (const uint64_t w : cavA) {
        if (w != 0) { anyA = true; break; }
    }
    if (anyA) {
        buildFormations(ctx->cavern, ctx->seed, chunk_x, chunk_z, cavA, formMask);
    }

    buildCavernCarve(ctx->mega, ctx->seed, chunk_x, chunk_z, heights, cavB);
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
    thread_local std::vector<float> volume;
    int yCount = 0;
    if (maxSurface > DENSITY_CAVE_FLOOR) {
        yCount = maxSurface - DENSITY_CAVE_FLOOR;
        if (volume.size() < static_cast<size_t>(yCount) * CHUNK_SIZE * CHUNK_SIZE) {
            volume.resize(static_cast<size_t>(yCount) * CHUNK_SIZE * CHUNK_SIZE);
        }
        ctx->densityNode->GenUniformGrid3D(
            volume.data(),
            static_cast<float>(chunk_z * CHUNK_SIZE),
            static_cast<float>(chunk_x * CHUNK_SIZE),
            static_cast<float>(DENSITY_CAVE_FLOOR) * DENSITY_Y_SQUASH,
            CHUNK_SIZE, CHUNK_SIZE, yCount,
            1.0f, 1.0f, DENSITY_Y_SQUASH,
            ctx->densitySeed);
    }
    const float* vol = yCount > 0 ? volume.data() : nullptr;

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
                                           vol, yCount, x, z);
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
