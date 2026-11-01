/* Tests for Stonebreak's worldgen natives (moved from the engine's
 * kernels_chunk test with the kernels themselves — #386): worm-carver and fused
 * generator determinism, water guards, the density solid-fraction bound and
 * argument rejection. Bit parity with the living Java generator is the Java
 * suites' job (FusedChunkGenParityTest, WormCarverParityTest). */
#include "stonebreak/worldgen.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

void* makeTerrainCtx(int64_t seed) {
    // Mirrors NoiseRouter channel configs (c, pv, e, d) + HeightMapGenerator splines.
    const int32_t seeds[4] = {
        static_cast<int32_t>((seed + 2) ^ ((seed + 2) >> 32)),
        static_cast<int32_t>((seed + 8) ^ ((seed + 8) >> 32)),
        static_cast<int32_t>((seed + 5) ^ ((seed + 5) >> 32)),
        static_cast<int32_t>((seed + 3) ^ ((seed + 3) >> 32)),
    };
    const int32_t octaves[4] = {8, 5, 5, 3};
    const float gain[4] = {0.45f, 0.45f, 0.40f, 0.50f};
    const float lac[4] = {2.0f, 2.0f, 2.0f, 2.0f};
    const float freq[4] = {1.0f / 800.0f, 1.0f / 260.0f, 1.0f / 900.0f, 1.0f / 45.0f};
    const int32_t xoff[4] = {0, 80860, 20700, 0};
    const int32_t zoff[4] = {0, 20020, -15300, 0};
    const double sx[] = {-1.0, -0.8, -0.4, -0.2, 0.1, 0.3, 0.7, 1.0,
                         -1.0, -0.3, 0.0, 0.15, 0.3, 0.5, 0.7, 1.0,
                         -1.0, -0.3, 0.2, 0.6, 1.0};
    const double sy[] = {70, 20, 58, 66, 72, 88, 100, 115,
                         -20, -5, 0, 10, 25, 55, 85, 110,
                         1.00, 0.95, 0.80, 0.35, 0.10};
    const int32_t sizes[3] = {8, 8, 5};
    return sbw_terrain_create(seed, seeds, octaves, gain, lac, freq, xoff, zoff,
                             sx, sy, sizes, 3.0f);
}

void testCarver() {
    void* ctx = makeTerrainCtx(987654321L);
    check(ctx != nullptr, "terrain ctx created");
    if (ctx == nullptr) return;

    std::vector<int32_t> heights(256, 200); // far above sea: carving allowed
    std::vector<uint64_t> a(1024), b(1024);

    int64_t bits = -1;
    int carvedChunk = 0;
    for (int c = 0; c < 40 && bits <= 0; c++) { // find a chunk with carving
        bits = sbw_carve_worms(ctx, c, -c, heights.data(), 0, nullptr, nullptr, a.data());
        carvedChunk = c;
    }
    check(bits > 0, "some chunk within 40 carries worm carving");

    int64_t bits2 = sbw_carve_worms(ctx, carvedChunk, -carvedChunk, heights.data(),
                                   0, nullptr, nullptr, b.data());
    check(bits2 == bits, "same input reproduces bit count");
    check(std::memcmp(a.data(), b.data(), 1024 * sizeof(uint64_t)) == 0,
          "same input reproduces mask exactly");

    // Submerged terrain: the water guard keeps carving clear of the bed and its banks.
    // Not a blanket ban — that was the old flat `surface <= SEA_LEVEL + clearance` gate,
    // which also blanked dry land merely near sea level. The guard anchors on the BED of
    // the wet columns in each column's 4-neighborhood, so what must hold is that nothing
    // is carved within WATER_CLEARANCE below it; deeper rock is fair game.
    constexpr int SEABED = 60;
    std::vector<int32_t> seaHeights(256, SEABED);
    sbw_carve_worms(ctx, carvedChunk, -carvedChunk, seaHeights.data(),
                   0, nullptr, nullptr, b.data());
    // Mirrors terrain_ctx.hpp's WATER_CLEARANCE = ceil(BASE_RADIUS + RADIUS_AMP) + 1.
    const int waterClearance = static_cast<int>(std::ceil(3.1f + 1.25f)) + 1;
    const int guardFloor = SEABED - waterClearance;
    int breaches = 0;
    for (int bit = 0; bit < 65536; bit++) {
        if ((b[static_cast<size_t>(bit) >> 6] & (1ULL << (bit & 63))) == 0) continue;
        const int by = (bit >> 4) & 0xFF;
        if (by >= guardFloor) breaches++;
    }
    check(breaches == 0, "no carve comes within WATER_CLEARANCE of a submerged bed");

    sbw_terrain_destroy(ctx);
    sbw_terrain_destroy(nullptr); // safe
}

void* makeChunkGenCtx(int64_t seed) {
    // Same channel/spline fixture as makeTerrainCtx, plus synthetic block ids
    // and a 2-biome table: biome 0 = plains-like, biome 1 = dry magma desert.
    const int32_t seeds[4] = {
        static_cast<int32_t>((seed + 2) ^ ((seed + 2) >> 32)),
        static_cast<int32_t>((seed + 8) ^ ((seed + 8) >> 32)),
        static_cast<int32_t>((seed + 5) ^ ((seed + 5) >> 32)),
        static_cast<int32_t>((seed + 3) ^ ((seed + 3) >> 32)),
    };
    const int32_t octaves[4] = {8, 5, 5, 3};
    const float gain[4] = {0.45f, 0.45f, 0.40f, 0.50f};
    const float lac[4] = {2.0f, 2.0f, 2.0f, 2.0f};
    const float freq[4] = {1.0f / 800.0f, 1.0f / 260.0f, 1.0f / 900.0f, 1.0f / 45.0f};
    const int32_t xoff[4] = {0, 80860, 20700, 0};
    const int32_t zoff[4] = {0, 20020, -15300, 0};
    const double sx[] = {-1.0, -0.8, -0.4, -0.2, 0.1, 0.3, 0.7, 1.0,
                         -1.0, -0.3, 0.0, 0.15, 0.3, 0.5, 0.7, 1.0,
                         -1.0, -0.3, 0.2, 0.6, 1.0};
    const double sy[] = {70, 20, 58, 66, 72, 88, 100, 115,
                         -20, -5, 0, 10, 25, 55, 85, 110,
                         1.00, 0.95, 0.80, 0.35, 0.10};
    const int32_t sizes[3] = {8, 8, 5};

    const int32_t blockIds[5] = {0, 9, 1, 7, 8}; // air, water, stone, bedrock, magma
    const int16_t surf[2] = {4, 6};
    const int16_t sub[2] = {5, 6};
    const float cave[2] = {0.22f, 0.30f};      // realistic BiomeSurfaceConfig range
    const float overhang[2] = {0.10f, 0.20f};
    const uint8_t flags[2] = {0, SBW_BIOME_MAGMA | SBW_BIOME_DRY_BELOW_SEA};
    // Opacity: everything solid is opaque; air (0) and water (9) are not.
    const uint8_t opacity[10] = {0, 1, 1, 1, 1, 1, 1, 1, 1, 0};

    // Density3D's four channels, in nodeParams fill order: cheese (+17),
    // spaghetti 1 (+331), spaghetti 2 (+733), crag (+1187). FOUR is authoritative —
    // the kernel reads four entries; sizing these arrays to fewer reads one past
    // the end of each stack array, and whatever lands in slot 3 goes into
    // makeSimplexFbm, which either rejects the context or builds an arbitrary
    // crag node (see worldgen.h, Density3D.nodeParams). The crag frequency is
    // Density3D.CRAG_SCALE = 1/26f.
    const auto nativeSeed = [](int64_t v) {
        return static_cast<int32_t>(static_cast<uint32_t>(
            static_cast<uint64_t>(v) ^ (static_cast<uint64_t>(v) >> 32)));
    };
    const int32_t dSeeds[4] = {nativeSeed(seed + 17), nativeSeed(seed + 331),
                               nativeSeed(seed + 733), nativeSeed(seed + 1187)};
    const int32_t dOct[4] = {2, 2, 2, 2};
    const float dGain[4] = {0.5f, 0.5f, 0.5f, 0.5f};
    const float dLac[4] = {2.0f, 2.0f, 2.0f, 2.0f};
    const float dFreq[4] = {1.0f / 96.0f, 1.0f / 68.0f, 1.0f / 68.0f, 1.0f / 26.0f};

    // The depth -> cheese-threshold curve (Density3D.CHEESE_KNOTS).
    const double cx[] = {0, 18, 45, 90, 160, 250};
    const double cy[] = {2.0, 0.90, 0.68, 0.55, 0.47, 0.44};
    const int32_t cSizes[1] = {6};

    return sbw_chunkgen_create(seed, seeds, octaves, gain, lac, freq, xoff, zoff,
                              sx, sy, sizes, 3.0f,
                              dSeeds, dOct, dGain, dLac, dFreq,
                              cx, cy, cSizes,
                              blockIds, 2, surf, sub, cave, overhang, flags,
                              103655975 /* "magma".hashCode() */, 0.6f,
                              opacity, 10);
}

void testGenerator() {
    void* ctx = makeChunkGenCtx(987654321L);
    check(ctx != nullptr, "chunkgen ctx created");
    if (ctx == nullptr) return;

    std::vector<int32_t> heights(256, 200);
    std::vector<int32_t> biomes(256, 0);
    for (int i = 0; i < 128; i++) biomes[static_cast<std::size_t>(i)] = 1;
    std::vector<int16_t> blocks(65536);
    std::vector<int32_t> heightmap(256);

    int64_t rc = sbw_generate_chunk(ctx, 3, -4, heights.data(), biomes.data(), nullptr,
                                   blocks.data(), heightmap.data());
    check(rc > 0, "generator returns non-air count");

    // Bedrock floor everywhere; nothing above the surface except air.
    bool bedrockOk = true, aboveOk = true;
    for (int x = 0; x < 16 && (bedrockOk || aboveOk); x++) {
        for (int z = 0; z < 16; z++) {
            if (blocks[static_cast<std::size_t>(z * 16 + x)] != 7) bedrockOk = false;
            for (int y = 201; y < 256; y += 13) {
                if (blocks[static_cast<std::size_t>(y * 256 + z * 16 + x)] != 0) aboveOk = false;
            }
        }
    }
    check(bedrockOk, "y=0 is bedrock in every column");
    check(aboveOk, "cells above the surface are air");

    // Sub-surface air exists (caves), but the underground must remain MOSTLY
    // solid: the solid predicate is `n < 1 - 2*intensity` (isSolid returns the
    // misleadingly-named carve() directly) — an inverted port flips ~25% air
    // into ~75% floating-blob sponge, which this fraction bound catches.
    int64_t airBelow = 0;
    const int64_t cells = 170L * 256L;
    for (int y = 10; y < 180; y++) {
        for (int i = 0; i < 256; i++) {
            if (blocks[static_cast<std::size_t>(y * 256 + i)] == 0) airBelow++;
        }
    }
    check(airBelow > 0, "sub-surface air (caves) present");
    check(airBelow < cells / 2, "underground stays mostly solid (density not inverted)");

    // Heightmap self-consistency vs. the returned blocks + opacity table.
    bool hmOk = true;
    const uint8_t opacity[10] = {0, 1, 1, 1, 1, 1, 1, 1, 1, 0};
    for (int z = 0; z < 16 && hmOk; z++) {
        for (int x = 0; x < 16; x++) {
            int top = 0;
            for (int y = 255; y >= 0; y--) {
                int16_t id = blocks[static_cast<std::size_t>(y * 256 + z * 16 + x)];
                if (id >= 0 && id < 10 && opacity[id] != 0) { top = y + 1; break; }
            }
            if (heightmap[static_cast<std::size_t>(z * 16 + x)] != top) hmOk = false;
        }
    }
    check(hmOk, "heightmap matches recompute from blocks");

    // Determinism: an independently created context reproduces byte-identically.
    void* ctx2 = makeChunkGenCtx(987654321L);
    check(ctx2 != nullptr, "second chunkgen ctx created");
    if (ctx2 != nullptr) {
        std::vector<int16_t> blocks2(65536);
        std::vector<int32_t> heightmap2(256);
        int64_t rc2 = sbw_generate_chunk(ctx2, 3, -4, heights.data(), biomes.data(), nullptr,
                                        blocks2.data(), heightmap2.data());
        check(rc2 == rc, "independent ctx reproduces non-air count");
        check(std::memcmp(blocks.data(), blocks2.data(), blocks.size() * sizeof(int16_t)) == 0,
              "independent ctx reproduces blocks exactly");
        check(std::memcmp(heightmap.data(), heightmap2.data(),
                          heightmap.size() * sizeof(int32_t)) == 0,
              "independent ctx reproduces heightmap exactly");
        sbw_chunkgen_destroy(ctx2);
    }

    // Submerged terrain gets a water column from surface to sea level.
    std::vector<int32_t> seaHeights(256, 30);
    rc = sbw_generate_chunk(ctx, 3, -4, seaHeights.data(), biomes.data(), nullptr,
                           blocks.data(), nullptr);
    check(rc > 0, "submerged chunk generates");
    bool waterOk = true;
    for (int y = 31; y < 64 && waterOk; y++) {
        for (int i = 0; i < 256; i++) {
            if (blocks[static_cast<std::size_t>(y * 256 + i)] != 9) { waterOk = false; break; }
        }
    }
    check(waterOk, "sub-sea cells above the surface are water");

    // Density water gate: the overhang band may not carve the block directly beneath
    // the source water cell. Here every column is wet at bed 30 (water at 30..63), so
    // the chunk's water guard plane anchors at 30 in every column and the band carve
    // at bed-1 = 29 is always sealed (29 >= 30 - DENSITY_WATER_CLEARANCE). The cave
    // test below the band cannot fire at depth 1 (the cheese threshold is still ~1.94
    // there and the spaghetti fade is 0), magma needs height-10, and no mask carver's
    // blob reaches the top terrain block from below (clearance >= radius+1 bounds the
    // blob top under plane-1) — so bed-1 must be terrain in every column.
    bool bedOk = true;
    for (int i = 0; i < 256 && bedOk; i++) {
        if (blocks[static_cast<std::size_t>(29 * 256 + i)] == 0) bedOk = false;
    }
    check(bedOk, "band carve sealed under standing water (bed-1 solid in every column)");

    // Bad biome ordinal rejected.
    std::vector<int32_t> badBiomes(256, 5);
    check(sbw_generate_chunk(ctx, 0, 0, heights.data(), badBiomes.data(), nullptr,
                            blocks.data(), nullptr) == -2,
          "out-of-range biome ordinal rejected");

    sbw_chunkgen_destroy(ctx);
    sbw_chunkgen_destroy(nullptr); // safe
}

} // namespace

int main() {
    check(sbw_abi_version() == SBW_ABI_VERSION, "ABI handshake");
    testCarver();
    testGenerator();
    if (failures == 0) {
        std::puts("worldgen: all checks passed");
        return 0;
    }
    return 1;
}
