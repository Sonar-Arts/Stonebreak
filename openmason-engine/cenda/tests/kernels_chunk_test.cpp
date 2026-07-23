/* Tests for the chunk kernels: mesher culling/lighting semantics, carver
 * determinism, zstd round-trip. Mesher block-id conventions in this test:
 * 0=air, 1=stone (cube+opaque), 2=leaves (cube+transparent), 3=water
 * (transparent, NOT cube — handled by the Java pass). */
#include "cenda/kernels.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
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

constexpr int16_t AIR = 0, STONE = 1, LEAVES = 2, WATER = 3;

struct MeshFixture {
    std::vector<int16_t> blocks = std::vector<int16_t>(65536, AIR);
    std::array<uint8_t, 4> cls{};
    std::array<int16_t, 18 * 18> heights{};
    std::vector<float> out = std::vector<float>(9 * 20000);

    MeshFixture() {
        cls[AIR] = 0;
        cls[STONE] = CK_CLASS_CUBE | CK_CLASS_OPAQUE_LIGHT;
        cls[LEAVES] = CK_CLASS_CUBE | CK_CLASS_TRANSPARENT;
        cls[WATER] = CK_CLASS_TRANSPARENT;
        heights.fill(0); // fully lit sky everywhere
    }

    void set(int x, int y, int z, int16_t id) {
        blocks[static_cast<std::size_t>((y * 16 + z) * 16 + x)] = id;
    }

    int mesh(int maxY, bool smooth) {
        return ck_mesh_chunk(blocks.data(), cls.data(), 4, AIR,
                             nullptr, nullptr, nullptr, nullptr,
                             nullptr, nullptr, nullptr, nullptr,
                             heights.data(), maxY, smooth ? 1 : 0,
                             out.data(), 20000);
    }
};

void testMesher() {
    { // lone opaque block: 6 faces, fully lit, AO-free
        MeshFixture f;
        f.set(5, 10, 5, STONE);
        int n = f.mesh(20, true);
        check(n == 6, "lone block emits 6 quads");
        for (int q = 0; q < n; q++) {
            const float* rec = f.out.data() + q * 9;
            check(rec[4] == static_cast<float>(STONE), "quad carries block id");
            for (int c = 0; c < 4; c++) {
                check(rec[5 + c] == 1.0f, "open-sky lone block corner is fully lit");
            }
        }
    }
    { // two stacked opaque blocks: shared face culled both sides -> 10 quads
        MeshFixture f;
        f.set(5, 10, 5, STONE);
        f.set(5, 11, 5, STONE);
        check(f.mesh(20, true) == 10, "stacked pair culls the shared face");
    }
    { // transparent vs same-type neighbor culled; vs different type rendered
        MeshFixture f;
        f.set(4, 10, 5, LEAVES);
        f.set(5, 10, 5, LEAVES);
        int n = f.mesh(20, true);
        check(n == 10, "same-type transparent pair culls the shared face");
        MeshFixture g;
        g.set(4, 10, 5, LEAVES);
        g.set(5, 10, 5, STONE);
        // leaves renders against stone (different type), stone renders against
        // transparent leaves: shared boundary emits BOTH faces -> 12 quads.
        check(g.mesh(20, true) == 12, "leaves|stone boundary renders both faces");
    }
    { // cube face against non-cube transparent block (water) still renders
        MeshFixture f;
        f.set(5, 10, 5, STONE);
        f.set(6, 10, 5, WATER);
        check(f.mesh(20, true) == 6, "stone renders all faces incl. against water");
    }
    { // chunk edge with missing neighbor plane behaves as AIR
        MeshFixture f;
        f.set(0, 10, 0, STONE);
        check(f.mesh(20, true) == 6, "edge block renders against unloaded neighbor");
    }
    { // buried under high heightmap: smooth top face goes dark, flat likewise
        MeshFixture f;
        f.heights.fill(50);
        f.set(5, 10, 5, STONE);
        int n = f.mesh(20, true);
        check(n == 6, "shaded block still emits 6 quads");
        for (int q = 0; q < n; q++) {
            const float* rec = f.out.data() + q * 9;
            for (int c = 0; c < 4; c++) {
                check(rec[5 + c] == 0.0f, "fully shadowed corner has zero sky light");
            }
        }
    }
    { // AO: three blocks in an L; inner bottom corner of the top block darkens
        MeshFixture f;
        f.set(5, 10, 5, STONE);
        f.set(6, 10, 5, STONE);
        f.set(6, 11, 5, STONE);
        int n = f.mesh(20, true);
        bool sawAo = false;
        for (int q = 0; q < n; q++) {
            const float* rec = f.out.data() + q * 9;
            for (int c = 0; c < 4; c++) {
                if (rec[5 + c] < 1.0f && rec[5 + c] > 0.5f) sawAo = true;
            }
        }
        check(sawAo, "L-shape produces at least one AO-darkened corner");
    }
    { // overflow reporting
        MeshFixture f;
        f.set(5, 10, 5, STONE);
        std::array<float, 9> tiny{};
        int n = ck_mesh_chunk(f.blocks.data(), f.cls.data(), 4, AIR,
                              nullptr, nullptr, nullptr, nullptr,
                              nullptr, nullptr, nullptr, nullptr,
                              f.heights.data(), 20, 1, tiny.data(), 1);
        check(n == -6, "overflow returns -(needed)");
    }
}

/* ── scalar vs bitmask mesher parity ──
 * The two culling implementations inside ck_mesh_chunk must produce
 * byte-identical output (same count, same quad order, same floats). Fuzzed
 * over random densities, transparent mixes (incl. a second transparent cube
 * id so cross-id adj != id is exercised), random border planes CONTAINING
 * transparent ids, corner columns, -1 height cells, and all max_y regimes. */

uint32_t xorshift(uint32_t& s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return s;
}

void checkScalarBitmaskParity(const std::vector<int16_t>& blocks,
                              const uint8_t* cls, int32_t clsLen,
                              const int16_t* pxn, const int16_t* pxp,
                              const int16_t* pzn, const int16_t* pzp,
                              const int16_t* cnn, const int16_t* cpn,
                              const int16_t* cnp, const int16_t* cpp2,
                              const std::array<int16_t, 18 * 18>& heights,
                              int maxY, int smooth, const char* what) {
    static std::vector<float> outA(9 * 250000);
    static std::vector<float> outB(9 * 250000);
    setenv("CENDA_MESHER_IMPL", "scalar", 1);
    int nA = ck_mesh_chunk(blocks.data(), cls, clsLen, AIR, pxn, pxp, pzn, pzp,
                           cnn, cpn, cnp, cpp2, heights.data(), maxY, smooth,
                           outA.data(), 250000);
    unsetenv("CENDA_MESHER_IMPL");
    int nB = ck_mesh_chunk(blocks.data(), cls, clsLen, AIR, pxn, pxp, pzn, pzp,
                           cnn, cpn, cnp, cpp2, heights.data(), maxY, smooth,
                           outB.data(), 250000);
    check(nA == nB, what);
    if (nA == nB && nA > 0) {
        check(std::memcmp(outA.data(), outB.data(),
                          static_cast<std::size_t>(nA) * 9 * sizeof(float)) == 0, what);
    }
}

void testMesherParity() {
    // 0 air, 1 stone, 2 leaves (transparent cube), 3 water (transparent
    // non-cube), 4 ice-like (SECOND transparent cube id), 5 second opaque.
    std::array<uint8_t, 6> cls{};
    cls[1] = CK_CLASS_CUBE | CK_CLASS_OPAQUE_LIGHT;
    cls[2] = CK_CLASS_CUBE | CK_CLASS_TRANSPARENT;
    cls[3] = CK_CLASS_TRANSPARENT;
    cls[4] = CK_CLASS_CUBE | CK_CLASS_TRANSPARENT;
    cls[5] = CK_CLASS_CUBE | CK_CLASS_OPAQUE_LIGHT;
    const int16_t ids[] = {1, 2, 4, 5, 3};

    uint32_t rng = 0xC0FFEE11u;
    const int maxYs[] = {0, 5, 48, 255};
    const int densities[] = {5, 40, 85, 100};

    for (int round = 0; round < 12; round++) {
        std::vector<int16_t> blocks(65536, 0);
        const int density = densities[static_cast<std::size_t>(round) % 4];
        const int maxY = maxYs[static_cast<std::size_t>(round) % 4];
        const bool allLeaves = round == 7;
        for (int y = 0; y < 90; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (static_cast<int>(xorshift(rng) % 100) < density) {
                        blocks[static_cast<std::size_t>((y * 16 + z) * 16 + x)] =
                            allLeaves ? int16_t{2} : ids[xorshift(rng) % 5];
                    }
                }
            }
        }
        // Buried-top-face edge: real block data at maxY and maxY+1 (a mask
        // build range of [0, maxY] instead of [0, maxY+1] would miss this).
        if (maxY < 255) {
            blocks[static_cast<std::size_t>((maxY * 16 + 8) * 16 + 8)] = 1;
            blocks[static_cast<std::size_t>(((maxY + 1) * 16 + 8) * 16 + 8)] = 2;
        }

        // Random border planes (transparent ids included) + corner columns.
        std::vector<int16_t> planes[4];
        const int16_t* planePtr[4] = {nullptr, nullptr, nullptr, nullptr};
        for (int p = 0; p < 4; p++) {
            if (xorshift(rng) % 2 == 0) continue;
            planes[p].assign(256 * 16, 0);
            for (auto& v : planes[p]) {
                if (xorshift(rng) % 3 == 0) v = ids[xorshift(rng) % 5];
            }
            planePtr[p] = planes[p].data();
        }
        std::vector<int16_t> corners[4];
        const int16_t* cornerPtr[4] = {nullptr, nullptr, nullptr, nullptr};
        for (int p = 0; p < 4; p++) {
            if (xorshift(rng) % 2 == 0) continue;
            corners[p].assign(256, 0);
            for (auto& v : corners[p]) {
                if (xorshift(rng) % 3 == 0) v = ids[xorshift(rng) % 5];
            }
            cornerPtr[p] = corners[p].data();
        }

        std::array<int16_t, 18 * 18> heights{};
        for (auto& h : heights) {
            h = (xorshift(rng) % 4 == 0) ? int16_t{-1}
                : static_cast<int16_t>(xorshift(rng) % 80);
        }

        for (int smooth = 0; smooth <= 1; smooth++) {
            checkScalarBitmaskParity(blocks, cls.data(), 6,
                planePtr[0], planePtr[1], planePtr[2], planePtr[3],
                cornerPtr[0], cornerPtr[1], cornerPtr[2], cornerPtr[3],
                heights, maxY, smooth, "randomized scalar==bitmask parity");
        }
    }

    { // lateral edges/corners with and without planes
        std::vector<int16_t> blocks(65536, 0);
        const int spots[][3] = {{0, 10, 0}, {15, 10, 0}, {0, 10, 15}, {15, 10, 15},
                                {0, 10, 7}, {15, 10, 7}, {7, 10, 0}, {7, 10, 15}};
        for (const auto& s : spots) {
            blocks[static_cast<std::size_t>((s[1] * 16 + s[2]) * 16 + s[0])] = 1;
        }
        std::array<int16_t, 18 * 18> heights{};
        checkScalarBitmaskParity(blocks, cls.data(), 6,
            nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
            heights, 20, 1, "edge blocks without planes");
        std::vector<int16_t> solidPlane(256 * 16, 1), leafPlane(256 * 16, 2);
        checkScalarBitmaskParity(blocks, cls.data(), 6,
            solidPlane.data(), leafPlane.data(), solidPlane.data(), leafPlane.data(),
            nullptr, nullptr, nullptr, nullptr,
            heights, 20, 1, "edge blocks with mixed planes");
    }

    { // world-top slab at max_y == 255
        std::vector<int16_t> blocks(65536, 0);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                blocks[static_cast<std::size_t>((255 * 16 + z) * 16 + x)] = 1;
            }
        }
        std::array<int16_t, 18 * 18> heights{};
        checkScalarBitmaskParity(blocks, cls.data(), 6,
            nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, nullptr,
            heights, 255, 1, "world-top slab parity");
    }

    { // overflow parity: identical negative -(needed) from both impls
        std::vector<int16_t> blocks(65536, 0);
        blocks[static_cast<std::size_t>((10 * 16 + 5) * 16 + 5)] = 1;
        std::array<int16_t, 18 * 18> heights{};
        std::array<float, 9> tiny{};
        setenv("CENDA_MESHER_IMPL", "scalar", 1);
        int nA = ck_mesh_chunk(blocks.data(), cls.data(), 6, AIR,
                               nullptr, nullptr, nullptr, nullptr,
                               nullptr, nullptr, nullptr, nullptr,
                               heights.data(), 20, 1, tiny.data(), 1);
        unsetenv("CENDA_MESHER_IMPL");
        int nB = ck_mesh_chunk(blocks.data(), cls.data(), 6, AIR,
                               nullptr, nullptr, nullptr, nullptr,
                               nullptr, nullptr, nullptr, nullptr,
                               heights.data(), 20, 1, tiny.data(), 1);
        check(nA == -6 && nB == -6, "overflow parity");
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
    return ck_terrain_create(seed, seeds, octaves, gain, lac, freq, xoff, zoff,
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
        bits = ck_carve_worms(ctx, c, -c, heights.data(), 0, nullptr, nullptr, a.data());
        carvedChunk = c;
    }
    check(bits > 0, "some chunk within 40 carries worm carving");

    int64_t bits2 = ck_carve_worms(ctx, carvedChunk, -carvedChunk, heights.data(),
                                   0, nullptr, nullptr, b.data());
    check(bits2 == bits, "same input reproduces bit count");
    check(std::memcmp(a.data(), b.data(), 1024 * sizeof(uint64_t)) == 0,
          "same input reproduces mask exactly");

    // Submerged terrain: water guard suppresses all carving.
    std::vector<int32_t> seaHeights(256, 60);
    int64_t seaBits = ck_carve_worms(ctx, carvedChunk, -carvedChunk, seaHeights.data(),
                                     0, nullptr, nullptr, b.data());
    check(seaBits == 0, "sea-level columns are never carved");

    ck_terrain_destroy(ctx);
    ck_terrain_destroy(nullptr); // safe
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
    const uint8_t flags[2] = {0, CK_BIOME_MAGMA | CK_BIOME_DRY_BELOW_SEA};
    // Opacity: everything solid is opaque; air (0) and water (9) are not.
    const uint8_t opacity[10] = {0, 1, 1, 1, 1, 1, 1, 1, 1, 0};

    return ck_chunkgen_create(seed, seeds, octaves, gain, lac, freq, xoff, zoff,
                              sx, sy, sizes, 3.0f,
                              static_cast<int32_t>((seed + 17) ^ ((seed + 17) >> 32)),
                              2, 0.5f, 2.0f, 1.0f / 26.0f,
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

    int64_t rc = ck_generate_chunk(ctx, 3, -4, heights.data(), biomes.data(),
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
        int64_t rc2 = ck_generate_chunk(ctx2, 3, -4, heights.data(), biomes.data(),
                                        blocks2.data(), heightmap2.data());
        check(rc2 == rc, "independent ctx reproduces non-air count");
        check(std::memcmp(blocks.data(), blocks2.data(), blocks.size() * sizeof(int16_t)) == 0,
              "independent ctx reproduces blocks exactly");
        check(std::memcmp(heightmap.data(), heightmap2.data(),
                          heightmap.size() * sizeof(int32_t)) == 0,
              "independent ctx reproduces heightmap exactly");
        ck_chunkgen_destroy(ctx2);
    }

    // Submerged terrain gets a water column from surface to sea level.
    std::vector<int32_t> seaHeights(256, 30);
    rc = ck_generate_chunk(ctx, 3, -4, seaHeights.data(), biomes.data(),
                           blocks.data(), nullptr);
    check(rc > 0, "submerged chunk generates");
    bool waterOk = true;
    for (int y = 31; y < 64 && waterOk; y++) {
        for (int i = 0; i < 256; i++) {
            if (blocks[static_cast<std::size_t>(y * 256 + i)] != 9) { waterOk = false; break; }
        }
    }
    check(waterOk, "sub-sea cells above the surface are water");

    // Bad biome ordinal rejected.
    std::vector<int32_t> badBiomes(256, 5);
    check(ck_generate_chunk(ctx, 0, 0, heights.data(), badBiomes.data(),
                            blocks.data(), nullptr) == -2,
          "out-of-range biome ordinal rejected");

    ck_chunkgen_destroy(ctx);
    ck_chunkgen_destroy(nullptr); // safe
}

void testZstd() {
    std::vector<uint8_t> src(131072);
    for (std::size_t i = 0; i < src.size(); i++) {
        src[i] = static_cast<uint8_t>((i / 97) & 0xFF); // compressible pattern
    }
    int64_t bound = ck_zstd_bound(static_cast<int64_t>(src.size()));
    check(bound > 0, "zstd bound positive");
    std::vector<uint8_t> compressed(static_cast<std::size_t>(bound));
    int64_t written = ck_zstd_compress(compressed.data(), bound, src.data(),
                                       static_cast<int64_t>(src.size()), 3);
    check(written > 0 && written < static_cast<int64_t>(src.size()), "compresses smaller");
    std::vector<uint8_t> restored(src.size());
    int64_t restoredSize = ck_zstd_decompress(restored.data(), static_cast<int64_t>(restored.size()),
                                              compressed.data(), written);
    check(restoredSize == static_cast<int64_t>(src.size()), "round-trip size");
    check(std::memcmp(src.data(), restored.data(), src.size()) == 0, "round-trip bytes");
    check(ck_zstd_decompress(restored.data(), 16, compressed.data(), written) < 0,
          "undersized dst rejected");
    check(ck_zstd_decompress(restored.data(), static_cast<int64_t>(restored.size()),
                             src.data(), 64) < 0, "garbage input rejected");
}

} // namespace

int main() {
    testMesher();
    testMesherParity();
    testCarver();
    testGenerator();
    testZstd();
    if (failures == 0) {
        std::puts("kernels_chunk: all checks passed");
        return 0;
    }
    return 1;
}
