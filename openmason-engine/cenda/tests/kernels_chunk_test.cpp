/* Tests for the chunk kernels: mesher culling/lighting semantics, zstd
 * round-trip. (The worm-carver/generator tests moved with those kernels to the
 * game's worldgen library, stonebreak-game/native/tests — #386.)
 * Mesher block-id conventions in this test: 0=air, 1=stone (cube+opaque), 2=leaves (cube+transparent), 3=water
 * (transparent, NOT cube — handled by the Java pass). */
#include "cenda/kernels.h"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {
// setenv/unsetenv are POSIX; MSVC's CRT has _putenv_s, which updates the same environment
// getenv() reads (an empty value removes the variable).
void setEnv(const char* name, const char* value) {
#ifdef _WIN32
    _putenv_s(name, value);
#else
    setenv(name, value, 1);
#endif
}

void unsetEnv(const char* name) {
#ifdef _WIN32
    _putenv_s(name, "");
#else
    unsetenv(name);
#endif
}
} // namespace

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
        return ck_mesh_chunk(blocks.data(), 16, 256, cls.data(), 4, AIR,
                             nullptr, nullptr, nullptr, nullptr,
                             nullptr, nullptr, nullptr, nullptr,
                             heights.data(), maxY, smooth ? 1 : 0,
                             out.data(), 20000);
    }
};


/* #385: the mesher takes its dimensions as arguments. A small chunk and a tall
 * column must mesh like the 16x256 one (lone block = 6 lit faces, both culling
 * implementations byte-identical), and out-of-range sizes are refused. */
void testMesherDimensions() {
    std::array<uint8_t, 4> cls{};
    cls[STONE] = CK_CLASS_CUBE | CK_CLASS_OPAQUE_LIGHT;
    cls[LEAVES] = CK_CLASS_CUBE | CK_CLASS_TRANSPARENT;
    std::vector<float> outA(9 * 4000), outB(9 * 4000);

    struct Dims { int cs, wh, y; };
    for (const Dims d : {Dims{8, 64, 40}, Dims{16, 1024, 900}, Dims{5, 32, 31}}) {
        std::vector<int16_t> blocks(static_cast<std::size_t>(d.cs * d.cs * d.wh), AIR);
        std::vector<int16_t> heights(static_cast<std::size_t>((d.cs + 2) * (d.cs + 2)), 0);
        auto at = [&](int x, int y, int z) -> int16_t& {
            return blocks[static_cast<std::size_t>((y * d.cs + z) * d.cs + x)];
        };
        at(d.cs / 2, d.y, d.cs / 2) = STONE;            // lone block near the top
        at(0, 1, 0) = STONE;                             // touches the x/z borders
        at(d.cs - 1, 1, d.cs - 1) = LEAVES;
        at(d.cs - 2, 1, d.cs - 1) = STONE;
        setEnv("CENDA_MESHER_IMPL", "scalar");
        const int nA = ck_mesh_chunk(blocks.data(), d.cs, d.wh, cls.data(), 4, AIR,
                                     nullptr, nullptr, nullptr, nullptr,
                                     nullptr, nullptr, nullptr, nullptr,
                                     heights.data(), d.wh - 1, 1, outA.data(), 4000);
        unsetEnv("CENDA_MESHER_IMPL");
        const int nB = ck_mesh_chunk(blocks.data(), d.cs, d.wh, cls.data(), 4, AIR,
                                     nullptr, nullptr, nullptr, nullptr,
                                     nullptr, nullptr, nullptr, nullptr,
                                     heights.data(), d.wh - 1, 1, outB.data(), 4000);
        check(nA > 0 && nA == nB, "dims: scalar and bitmask agree on the count");
        check(nA > 0 && nA == nB
              && std::memcmp(outA.data(), outB.data(), sizeof(float) * 9 * static_cast<std::size_t>(nA)) == 0,
              "dims: scalar and bitmask are byte-identical");
        int lone = 0;
        for (int q = 0; q < nB; q++) {
            if (static_cast<int>(outB[static_cast<std::size_t>(q) * 9 + 1]) == d.y) lone++;
        }
        check(lone == 6, "dims: the lone block high in the column emits 6 faces");
    }

    std::vector<int16_t> blocks(16 * 16 * 16, AIR);
    std::vector<int16_t> heights(18 * 18, 0);
    std::vector<float> out(9);
    for (const int cs : {0, 17, -1}) {
        check(ck_mesh_chunk(blocks.data(), cs, 16, cls.data(), 4, AIR, nullptr, nullptr, nullptr, nullptr,
                            nullptr, nullptr, nullptr, nullptr, heights.data(), 15, 1, out.data(), 1)
                  == CK_MESH_BAD_ARGS,
              "dims: a chunk size outside 1..CK_MESH_MAX_CHUNK_SIZE is refused");
    }
    check(ck_mesh_chunk(blocks.data(), 16, 0, cls.data(), 4, AIR, nullptr, nullptr, nullptr, nullptr,
                        nullptr, nullptr, nullptr, nullptr, heights.data(), 15, 1, out.data(), 1)
              == CK_MESH_BAD_ARGS,
          "dims: a non-positive world height is refused");
}

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
        // stone renders against transparent leaves, but the leaf face toward the
        // opaque stone is fully covered (and would z-fight stone's face): only
        // ONE face is emitted on the shared boundary -> 11 quads.
        check(g.mesh(20, true) == 11, "leaves|stone boundary renders only the stone face");
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
        int n = ck_mesh_chunk(f.blocks.data(), 16, 256, f.cls.data(), 4, AIR,
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
    setEnv("CENDA_MESHER_IMPL", "scalar");
    int nA = ck_mesh_chunk(blocks.data(), 16, 256, cls, clsLen, AIR, pxn, pxp, pzn, pzp,
                           cnn, cpn, cnp, cpp2, heights.data(), maxY, smooth,
                           outA.data(), 250000);
    unsetEnv("CENDA_MESHER_IMPL");
    int nB = ck_mesh_chunk(blocks.data(), 16, 256, cls, clsLen, AIR, pxn, pxp, pzn, pzp,
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
        setEnv("CENDA_MESHER_IMPL", "scalar");
        int nA = ck_mesh_chunk(blocks.data(), 16, 256, cls.data(), 6, AIR,
                               nullptr, nullptr, nullptr, nullptr,
                               nullptr, nullptr, nullptr, nullptr,
                               heights.data(), 20, 1, tiny.data(), 1);
        unsetEnv("CENDA_MESHER_IMPL");
        int nB = ck_mesh_chunk(blocks.data(), 16, 256, cls.data(), 6, AIR,
                               nullptr, nullptr, nullptr, nullptr,
                               nullptr, nullptr, nullptr, nullptr,
                               heights.data(), 20, 1, tiny.data(), 1);
        check(nA == -6 && nB == -6, "overflow parity");
    }
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
    testMesherDimensions();
    testMesherParity();
    testZstd();
    if (failures == 0) {
        std::puts("kernels_chunk: all checks passed");
        return 0;
    }
    return 1;
}
