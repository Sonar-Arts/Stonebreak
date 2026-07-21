/* Tests for the chunk kernels: mesher culling/lighting semantics, carver
 * determinism, zstd round-trip. Mesher block-id conventions in this test:
 * 0=air, 1=stone (cube+opaque), 2=leaves (cube+transparent), 3=water
 * (transparent, NOT cube — handled by the Java pass). */
#include "cenda/kernels.h"

#include <array>
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
    testCarver();
    testZstd();
    if (failures == 0) {
        std::puts("kernels_chunk: all checks passed");
        return 0;
    }
    return 1;
}
