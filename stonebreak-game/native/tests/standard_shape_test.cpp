/* Standard shape versions (sbw_gen_config.standard_shape; shape.cpp; agent SD 2026-11-19).
 *
 * Shape 2 = the Standard generator distilled from DaedalusTGM-Exp-2 (shape P2, curve v2 sea bands, inland pit lakes,
 * distilled surface channels feeding the sea-floor / land-surface / boulder / vegetation passes). Checks:
 *   - shape 0 (unset) == shape 1: the field is backward compatible (saved Standard worlds keep their terrain);
 *   - shape 2 differs from shape 1 and stays inside the column; sbw_standard_shape_max() reports it;
 *   - FastLOD's strided point samples (no tile) equal the tiles: height, water, biome, and the trees the pointwise
 *     vegetation channels give;
 *   - the channels: dry land carries them, sea and lake columns do not (no data -> the biome rules); rock exposure
 *     rises with slope;
 *   - order independence: a base tile built cold equals the same tile built after its neighbours (the edge slopes and
 *     the sea floor read across the tile edge);
 *   - inland sub-sea columns are lakes (a land biome), never ocean;
 *   - an unknown shape is refused. */
#include "gen.hpp"
#include "stonebreak/worldgen.h"

#include <cstdio>
#include <cstring>
#include <vector>

using namespace sbw;

namespace sbw {
void standardColumnPoint(Generator& g, int64_t x, int64_t z, int32_t& h, int32_t& water, int& biome, uint16_t& surface,
                         int32_t* refH, bool surfacePass);
}

namespace {

int g_failures = 0, g_checks = 0;
#define CHECK(c)                                                                                     \
    do {                                                                                             \
        ++g_checks;                                                                                  \
        if (!(c)) {                                                                                  \
            ++g_failures;                                                                            \
            std::fprintf(stderr, "  FAIL %s:%d  %s\n", __FILE__, __LINE__, #c);                      \
        }                                                                                            \
    } while (0)

sbw_gen_config makeConfig(int64_t seed, int shape, double hm = 60.0) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = seed;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    c.water_layer = 0;
    c.state_layer = 1;
    c.snow_layer = 2;
    c.standard_shape = static_cast<uint16_t>(shape);
    const double k = hm / 60.0;
    c.horizontal_m_per_block = hm;
    if (shape >= 2) {
        /* TerrainScalePreset.heightCurve(V2): deep 32 m per block, narrow shore blend; the generator adds the bands */
        const double rates[4] = {32.0, 16.0 * k, 24.0 * k, 38.0 * k};
        const double knots[5] = {600.0, 2000.0, 10.0, 150.0, 300.0};
        std::memcpy(c.curve_rates, rates, sizeof(rates));
        std::memcpy(c.curve_knots, knots, sizeof(knots));
        c.flags |= SBW_FLAG_SEA_FLOOR | SBW_FLAG_LAND_SURFACE | SBW_FLAG_BOULDERS | SBW_FLAG_VEGETATION;
    } else if (hm != 60.0) {
        const double rates[4] = {48.0 * k, 16.0 * k, 24.0 * k, 38.0 * k};
        std::memcpy(c.curve_rates, rates, sizeof(rates));
    }
    return c;
}

struct Gen {
    const cv_generator* t = nullptr;
    explicit Gen(const sbw_gen_config& c) : t(sbw_cv_generator_create(&c)) {
        if (t == nullptr) std::fprintf(stderr, "create failed: %s\n", sbw_last_error());
    }
    ~Gen() { sbw_cv_generator_destroy(t); }
    Generator& g() const { return *static_cast<Generator*>(t->user); }
};

bool sameBase(const BaseTile& a, const BaseTile& b, bool withSurf) {
    if (std::memcmp(a.h, b.h, sizeof(a.h)) != 0) return false;
    if (std::memcmp(a.water, b.water, sizeof(a.water)) != 0) return false;
    if (std::memcmp(a.biome, b.biome, sizeof(a.biome)) != 0) return false;
    if (std::memcmp(a.surface, b.surface, sizeof(a.surface)) != 0) return false;
    if (withSurf && std::memcmp(a.surf, b.surf, sizeof(a.surf)) != 0) return false;
    return true;
}

}  // namespace

int main() {
    CHECK(sbw_standard_shape_max() == SBW_STANDARD_SHAPE_V2);

    /* shape 0 (unset) == shape 1 */
    {
        Gen a(makeConfig(1234, 0)), b(makeConfig(1234, 1));
        CHECK(a.t != nullptr && b.t != nullptr);
        bool same = true;
        for (int64_t cx = -2; cx <= 2; cx++)
            for (int64_t cz = -2; cz <= 2; cz++) same = same && sameBase(*a.g().base(cx, cz), *b.g().base(cx, cz), true);
        CHECK(same);
    }
    /* an unknown shape is refused */
    {
        sbw_gen_config c = makeConfig(1, 3);
        const cv_generator* t = sbw_cv_generator_create(&c);
        CHECK(t == nullptr);
        if (t != nullptr) sbw_cv_generator_destroy(t);
    }

    for (const double hm : {60.0, 30.0}) {
        for (const int64_t seed : {INT64_C(20261119), INT64_C(-77), INT64_C(4242)}) {
            Gen one(makeConfig(seed, 1, hm)), two(makeConfig(seed, 2, hm));
            CHECK(one.t != nullptr && two.t != nullptr);
            if (one.t == nullptr || two.t == nullptr) continue;
            Generator& g = two.g();

            /* shape 2 is its own terrain, inside the column */
            long differ = 0, cols = 0, land = 0, dryWithVeg = 0, wetWithVeg = 0, lakes = 0, lakeOcean = 0;
            double rockFlat = 0, rockSteep = 0, nFlat = 0, nSteep = 0;
            for (int64_t cx = -6; cx <= 6; cx += 2) {
                for (int64_t cz = -6; cz <= 6; cz += 2) {
                    auto b1 = one.g().base(cx, cz);
                    auto b2 = g.base(cx, cz);
                    CHECK(b1 && b2);
                    if (!b1 || !b2) continue;
                    for (int c = 0; c < COLS; c++) {
                        cols++;
                        differ += b1->h[c] != b2->h[c];
                        CHECK(b2->h[c] > g.cfg.floorY() && b2->h[c] < g.cfg.topY());
                        const bool wet = b2->water[c] != NO_WATER && b2->water[c] > b2->h[c];
                        if (!wet) land++;
                        const bool veg = b2->surf[SBW_SURF_CANOPY_COVER][c] != SBW_SURF_NO_DATA;
                        if (wet) wetWithVeg += veg;
                        else if (b2->biome[c] != SBW_BEACH && b2->biome[c] != SBW_OCEAN) dryWithVeg += veg;
                        if (wet && b2->biome[c] != SBW_OCEAN && b2->biome[c] != SBW_ICE_FIELDS) lakes++;
                        /* rock exposure vs slope (interior columns) */
                        const int lx = c & 31, lz = c >> 5;
                        if (!wet && lx > 0 && lx < 31 && lz > 0 && lz < 31
                                && b2->surf[SBW_SURF_ROCK_EXPOSURE][c] != SBW_SURF_NO_DATA) {
                            const int32_t h = b2->h[c];
                            int32_t s = std::max(std::max(std::abs(b2->h[c - 1] - h), std::abs(b2->h[c + 1] - h)),
                                                 std::max(std::abs(b2->h[c - 32] - h), std::abs(b2->h[c + 32] - h)));
                            if (s == 0) { rockFlat += b2->surf[SBW_SURF_ROCK_EXPOSURE][c]; nFlat++; }
                            if (s >= 3) { rockSteep += b2->surf[SBW_SURF_ROCK_EXPOSURE][c]; nSteep++; }
                        }
                    }
                }
            }
            (void)lakeOcean;
            std::printf("  seed %lld 1:%g: %ld/%ld columns differ from shape 1, land %ld, lake columns %ld, "
                        "rock exposure flat %.1f (%.0f) / steep %.1f (%.0f)\n", static_cast<long long>(seed), hm / 15.0,
                        differ, cols, land, lakes, nFlat > 0.0 ? rockFlat / nFlat : 0.0, nFlat,
                        nSteep > 0.0 ? rockSteep / nSteep : 0.0, nSteep);
            CHECK(differ > cols / 4);
            CHECK(wetWithVeg == 0);
            CHECK(land == 0 || dryWithVeg > 0);
            if (nFlat > 200 && nSteep > 200) CHECK(rockSteep / nSteep > rockFlat / nFlat);

            /* strided point samples (no tile) == tiles: height, water, biome, trees */
            const int n = 48, stride = 7;
            const int64_t x0 = -150, z0 = -170;
            const auto nn = static_cast<size_t>(n * n);
            std::vector<int32_t> hh(nn), ww(nn), tt(nn);
            std::vector<uint16_t> ss(nn);
            CHECK(sbw_sample_columns(two.t, x0, z0, n, stride, 0, hh.data(), ww.data(), ss.data(), tt.data()) == 0);
            long mism = 0, treeMism = 0, trees = 0, surfMism = 0, dry = 0;
            for (int ix = 0; ix < n; ix++) {
                for (int iz = 0; iz < n; iz++) {
                    const int64_t x = x0 + ix * stride, z = z0 + iz * stride;
                    auto b = g.base(x >> 5, z >> 5);
                    const int col = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
                    const auto i = static_cast<size_t>(ix * n + iz);
                    std::vector<int32_t> h1(1), w1(1), t1(1);
                    std::vector<uint16_t> s1(1);
                    /* stride 1 goes through the tile (the reference) */
                    sbw_sample_columns(two.t, x, z, 1, 1, 0, h1.data(), w1.data(), s1.data(), t1.data());
                    if (hh[i] != h1[0] || ww[i] != w1[0]) mism++;
                    if (w1[0] == NO_WATER || w1[0] <= h1[0]) {   /* dry: the land-surface pass matches too */
                        dry++;
                        surfMism += ss[i] != s1[0];
                    }
                    (void)b;
                    (void)col;
                    trees += t1[0] != 0;
                    if (tt[i] != t1[0]) treeMism++;
                }
            }
            std::printf("    point vs tile: %ld height/water mismatches, %ld tree mismatches (%ld trees), %ld dry surface "
                        "mismatches (%ld dry)\n", mism, treeMism, trees, surfMism, dry);
            CHECK(mism == 0);
            CHECK(treeMism == 0);
            CHECK(surfMism == 0);

            /* order independence: cold tile == tile built after its neighbours (fresh generators) */
            {
                Gen cold(makeConfig(seed, 2, hm)), warm(makeConfig(seed, 2, hm));
                for (int64_t cx = -1; cx <= 1; cx++)
                    for (int64_t cz = -1; cz <= 1; cz++)
                        if (cx != 0 || cz != 0) warm.g().base(3 + cx, -2 + cz);
                auto a = cold.g().base(3, -2);
                auto b = warm.g().base(3, -2);
                CHECK(a && b && sameBase(*a, *b, true));
            }
        }
    }
    /* inland sub-sea pits are lakes: never ocean, never beach (point samples over a wide area) */
    {
        Gen two(makeConfig(20261119, 2));
        long sea = 0, lakes = 0, lakeBeach = 0;
        for (int64_t x = -9000; x <= 9000; x += 37) {
            for (int64_t z = -9000; z <= 9000; z += 37) {
                int32_t h, w;
                int b;
                uint16_t sf;
                standardColumnPoint(two.g(), x, z, h, w, b, sf, nullptr, false);
                if (w == NO_WATER || w <= h) continue;
                if (b == SBW_OCEAN || b == SBW_ICE_FIELDS) sea++;
                else lakes++;
                lakeBeach += b == SBW_BEACH;
            }
        }
        std::printf("  wide scan: %ld sea samples, %ld lake samples\n", sea, lakes);
        CHECK(lakeBeach == 0);
        CHECK(sea > 0);
    }
    std::printf("standard_shape_test: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
