/* Land surface materials (SBW_FLAG_LAND_SURFACE, Generator::landSurface in src/shape.cpp; agent L 2026-11-19).
 *
 * A synthetic profile: plains at a flat height, the surface-detail channels set per quadrant (no data / all rock /
 * all scree / rough ground), a lake strip. Checks: no data and flag off leave the surface untouched; rock / scree
 * shares turn into roughly that share of rock / talus columns in coherent patches; wet columns keep their bed;
 * results are pointwise (the same column from any tile / generator instance) - no tile-edge seams. */
#include "gen.hpp"
#include "stonebreak/worldgen.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

using namespace sbw;

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

/* quadrants of 4x4 column cubes: cx in [0,4) x cz in [0,4) = no data; [4,8) x [0,4) = rock 0.4;
 * [0,4) x [4,8) = scree 0.5 + rough 0.6 m; [4,8) x [4,8) = rough ground only (1.0 m); lake where lx == 5 */
int32_t profile(void*, int64_t cx, int64_t cz, sbw_column_profile* out) {
    std::memset(out->surface, SBW_SURF_NO_DATA, sizeof(out->surface));
    const bool east = (cx & 7) >= 4, south = (cz & 7) >= 4;
    for (int c = 0; c < 1024; c++) {
        const int lx = c & 31;
        out->height[c] = 80;
        out->water[c] = lx == 5 ? 82 : SBW_NO_WATER;
        out->biome[c] = SBW_PLAINS;
        out->river_floor[c] = 0;
        out->river_roof[c] = 0;
        out->river_flow[c] = -1;
        out->carve[c] = 0;
        if (!east && !south) continue;
        out->surface[SBW_SURF_ROCK_EXPOSURE][c] = static_cast<uint8_t>(east && !south ? 102 : 0);   /* 0.40 */
        out->surface[SBW_SURF_SCREE][c] = static_cast<uint8_t>(!east && south ? 127 : 0);          /* 0.50 */
        out->surface[SBW_SURF_ROUGHNESS][c] = static_cast<uint8_t>(south ? (east ? 100 : 60) : 10);
    }
    return 1;
}

sbw_gen_config makeConfig(int64_t seed, bool on) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = seed;
    c.kind = SBW_KIND_PROFILE;
    c.column_fn = profile;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    c.surface_prefetch = SBW_PREFETCH_OFF;
    if (on) c.flags |= SBW_FLAG_LAND_SURFACE;
    else c.flags &= ~SBW_FLAG_LAND_SURFACE;
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

struct Counts {
    int cols = 0, grass = 0, stone = 0, gravel = 0, cobble = 0, dirt = 0, other = 0, wetChanged = 0;
};

Counts quadrant(Gen& on, Gen& off, int cx0, int cz0) {
    Counts n;
    for (int cx = cx0; cx < cx0 + 4; cx++) {
        for (int cz = cz0; cz < cz0 + 4; cz++) {
            auto a = on.g().base(cx, cz), b = off.g().base(cx, cz);
            for (int c = 0; c < COLS; c++) {
                if ((c & 31) == 5) {                 /* lake column: its bed never changes */
                    if (a->surface[c] != b->surface[c]) n.wetChanged++;
                    continue;
                }
                n.cols++;
                const uint16_t s = a->surface[c];
                if (s == SBW_GRASS) n.grass++;
                else if (s == SBW_STONE) n.stone++;
                else if (s == SBW_GRAVEL) n.gravel++;
                else if (s == SBW_COBBLESTONE) n.cobble++;
                else if (s == SBW_DIRT) n.dirt++;
                else n.other++;
            }
        }
    }
    return n;
}

void testShares() {
    Gen on(makeConfig(4242, true)), off(makeConfig(4242, false));
    const Counts none = quadrant(on, off, 0, 0), rock = quadrant(on, off, 4, 0), scree = quadrant(on, off, 0, 4),
                 rough = quadrant(on, off, 4, 4);
    std::printf("  no data : grass %d / %d\n", none.grass, none.cols);
    std::printf("  rock 0.4: stone %.3f gravel %.3f\n", double(rock.stone) / rock.cols, double(rock.gravel) / rock.cols);
    std::printf("  scree 0.5: gravel %.3f cobble %.3f stone %.3f\n", double(scree.gravel) / scree.cols,
                double(scree.cobble) / scree.cols, double(scree.stone) / scree.cols);
    std::printf("  rough 1 m: dirt %.3f gravel %.3f grass %.3f\n", double(rough.dirt) / rough.cols,
                double(rough.gravel) / rough.cols, double(rough.grass) / rough.cols);
    CHECK(none.grass == none.cols);                                             /* no data: unchanged */
    CHECK(std::fabs(double(rock.stone) / rock.cols - 0.40) < 0.06);
    CHECK(rock.gravel == 0 && rock.cobble == 0);
    CHECK(std::fabs(double(scree.gravel + scree.cobble) / scree.cols - 0.50) < 0.07);
    CHECK(scree.cobble > 0 && scree.stone == 0);
    CHECK(rough.dirt + rough.gravel > rough.cols / 20 && rough.dirt + rough.gravel < rough.cols / 2);
    CHECK(none.wetChanged + rock.wetChanged + scree.wetChanged + rough.wetChanged == 0);
}

void testPatches() {
    /* coherent patches: a rock column's 4 neighbours are rock far more often than the 0.4 share */
    Gen on(makeConfig(9, true));
    int rockCols = 0, rockNb = 0, nb = 0;
    for (int cx = 4; cx < 8; cx++) {
        for (int cz = 0; cz < 4; cz++) {
            auto t = on.g().base(cx, cz);
            for (int lz = 1; lz < 31; lz++) {
                for (int lx = 1; lx < 31; lx++) {
                    if (t->surface[lz * 32 + lx] != SBW_STONE) continue;
                    rockCols++;
                    for (int d : {-1, 1, -32, 32}) {
                        const int q = lz * 32 + lx + d;
                        if (((q & 31) == 5)) continue;
                        nb++;
                        if (t->surface[q] == SBW_STONE) rockNb++;
                    }
                }
            }
        }
    }
    const double p = nb > 0 ? double(rockNb) / nb : 0.0;
    std::printf("  rock neighbour share %.3f over %d rock columns\n", p, rockCols);
    CHECK(p > 0.55);
}

void testPointwise() {
    /* the same column from two generators built in different orders (and tiles requested in different orders) */
    Gen a(makeConfig(77, true)), b(makeConfig(77, true));
    int diff = 0;
    for (int cx = 7; cx >= 0; cx--) (void)b.g().base(cx, 3);
    for (int cx = 0; cx < 8; cx++) {
        auto ta = a.g().base(cx, 3), tb = b.g().base(cx, 3);
        for (int c = 0; c < COLS; c++) diff += ta->surface[c] != tb->surface[c];
    }
    CHECK(diff == 0);
}

void testFlagOffIdentity() {
    /* channels present but flag off: the surface is exactly the pre-existing one */
    Gen off(makeConfig(5, false));
    sbw_gen_config c = makeConfig(5, false);
    c.column_fn = [](void* u, int64_t cx, int64_t cz, sbw_column_profile* out) -> int32_t {
        const int32_t r = profile(u, cx, cz, out);
        std::memset(out->surface, SBW_SURF_NO_DATA, sizeof(out->surface));
        return r;
    };
    Gen bare(c);
    int diff = 0;
    for (int cx = 0; cx < 8; cx++) {
        for (int cz = 0; cz < 8; cz += 3) {
            auto x = off.g().base(cx, cz), y = bare.g().base(cx, cz);
            for (int k = 0; k < COLS; k++) diff += x->surface[k] != y->surface[k];
        }
    }
    CHECK(diff == 0);
}

} // namespace

int main() {
    testShares();
    testPatches();
    testPointwise();
    testFlagOffIdentity();
    std::printf("land surface: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
