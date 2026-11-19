/* Sea-floor materials (SBW_FLAG_SEA_FLOOR, Generator::seaFloor in src/shape.cpp; FX1 2026-11-19, review-native F3).
 *
 * The sea-floor pass picks rock / gravel / sand / clay from depth, slope (steepest step to a face neighbour) and
 * shelter (dry ground within 6 blocks). It used to look only inside its own 32x32 column cube, so edge columns saw
 * 3 neighbours (corners 2) and half the shelter window: the material mix shifted on every 32-block border (straight
 * lines on visible sea floors). Now it reads across the edge. Checks:
 *   - a rough all-ocean bed: stone + gravel share at tile edges / corners equals the interior's (was 0.75 / 0.67 vs
 *     0.82), and so does every material's share on a bed with islands and coves (shelter crosses edges);
 *   - pointwise: a tile built alone (neighbours from the apron upcall) equals the same tile built after its neighbours
 *     (neighbours from their base tiles), and a generator that saw the tiles in another order agrees;
 *   - flag off: no surface changes at all vs the pass on, except ocean columns; land and lake columns never change;
 *   - a neighbour whose profile upcall fails fails the tile (no silent clipped fallback); flag off it does not matter. */
#include "gen.hpp"
#include "stonebreak/worldgen.h"

#include <cmath>
#include <cstdio>
#include <cstring>

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

uint64_t mix(uint64_t v) {
    v += UINT64_C(0x9E3779B97F4A7C15);
    v = (v ^ (v >> 30)) * UINT64_C(0xBF58476D1CE4E5B9);
    v = (v ^ (v >> 27)) * UINT64_C(0x94D049BB133111EB);
    return v ^ (v >> 31);
}
uint64_t hash2(int64_t x, int64_t z, uint64_t salt) {
    return mix(static_cast<uint64_t>(x) * UINT64_C(0x632BE59BD9B4E019) ^ mix(static_cast<uint64_t>(z) ^ salt));
}

constexpr int32_t SEA_TOP = 64;   /* first non-water y of the sea (cfg.sea below) */
bool g_islands = false;           /* the bed: rough only, or with islands and coves */
int64_t g_failCx = INT64_MIN;     /* a cube whose profile upcall fails */

/* coarse value noise (9-block cells, smoothstep) in [0, 1): island blobs that do not line up with the 32-block grid */
double blob(int64_t x, int64_t z) {
    auto fl = [](int64_t a) { return a >= 0 ? a / 9 : -((-a + 8) / 9); };
    const int64_t gx = fl(x), gz = fl(z);
    const double fx = static_cast<double>(x - gx * 9) / 9.0, fz = static_cast<double>(z - gz * 9) / 9.0;
    auto v = [](int64_t a, int64_t b) { return static_cast<double>(hash2(a, b, 0xB10B) >> 11) / 9007199254740992.0; };
    const double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
    const double top = v(gx, gz) + (v(gx + 1, gz) - v(gx, gz)) * sx;
    const double bot = v(gx, gz + 1) + (v(gx + 1, gz + 1) - v(gx, gz + 1)) * sx;
    return top + (bot - top) * sz;
}

int32_t profile(void*, int64_t cx, int64_t cz, sbw_column_profile* out) {
    if (cx == g_failCx) return 0;
    for (int c = 0; c < 1024; c++) {
        const int64_t x = cx * 32 + (c & 31), z = cz * 32 + (c >> 5);
        const bool island = g_islands && blob(x, z) > 0.72;
        out->height[c] = island ? 70 : 40 + static_cast<int32_t>(hash2(x, z, 0x5EA) % 4);   /* rough bed, y 40-43 */
        out->water[c] = island ? SBW_NO_WATER : SEA_TOP;
        out->biome[c] = island ? SBW_PLAINS : SBW_OCEAN;
        out->river_floor[c] = 0;
        out->river_roof[c] = 0;
        out->river_flow[c] = -1;
        out->carve[c] = 0;
    }
    return 1;
}

sbw_gen_config makeConfig(int64_t seed, bool on) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = seed;
    c.kind = SBW_KIND_PROFILE;
    c.column_fn = profile;
    c.sea_level = SEA_TOP;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    c.surface_prefetch = SBW_PREFETCH_OFF;
    if (on) c.flags |= SBW_FLAG_SEA_FLOOR;
    else c.flags &= ~SBW_FLAG_SEA_FLOOR;
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

enum Where { INTERIOR, EDGE, CORNER };
Where where(int c) {
    const int lx = c & 31, lz = c >> 5;
    const bool ex = lx == 0 || lx == 31, ez = lz == 0 || lz == 31;
    return ex && ez ? CORNER : (ex || ez ? EDGE : INTERIOR);
}

struct Mix {
    long n = 0, stone = 0, gravel = 0, sand = 0, clay = 0, dirt = 0;
    void add(uint16_t s) {
        n++;
        stone += s == SBW_STONE;
        gravel += s == SBW_GRAVEL;
        sand += s == SBW_SAND;
        clay += s == SBW_CLAY;
        dirt += s == SBW_DIRT;
    }
    double share(long k) const { return n > 0 ? static_cast<double>(k) / static_cast<double>(n) : 0.0; }
};

/* materials of the sea columns of cubes [0, n)^2, by position in the cube */
void census(Gen& gen, int n, Mix out[3]) {
    for (int cx = 0; cx < n; cx++) {
        for (int cz = 0; cz < n; cz++) {
            auto t = gen.g().base(cx, cz);
            if (!t) continue;
            for (int c = 0; c < COLS; c++) {
                if (t->biome[c] != SBW_OCEAN) continue;
                out[where(c)].add(t->surface[c]);
            }
        }
    }
}

void testRoughBedHasNoEdgeBias() {
    g_islands = false;
    Gen on(makeConfig(31337, true));
    Mix m[3];
    census(on, 16, m);
    const double in = m[INTERIOR].share(m[INTERIOR].stone + m[INTERIOR].gravel),
                 edge = m[EDGE].share(m[EDGE].stone + m[EDGE].gravel),
                 corner = m[CORNER].share(m[CORNER].stone + m[CORNER].gravel);
    std::printf("  rough bed, stone+gravel share: interior %.3f (n=%ld)  edge %.3f (n=%ld)  corner %.3f (n=%ld)\n", in,
                m[INTERIOR].n, edge, m[EDGE].n, corner, m[CORNER].n);
    CHECK(m[INTERIOR].n > 200000 && m[EDGE].n > 25000 && m[CORNER].n > 900);
    CHECK(in > 0.5);                              /* the bed is rough: mostly rock / gravel */
    CHECK(std::fabs(edge - in) < 0.015);          /* clipped: 0.748 vs 0.822 */
    CHECK(std::fabs(corner - in) < 0.045);        /* clipped: 0.666 */
}

void testIslandsHaveNoEdgeBias() {
    g_islands = true;
    Gen on(makeConfig(99, true));
    Mix m[3];
    census(on, 16, m);
    std::printf("  islands: %-8s %6s %6s %6s %6s %6s\n", "", "stone", "gravel", "sand", "clay", "dirt");
    const char* names[3] = {"interior", "edge", "corner"};
    for (int w = 0; w < 3; w++) {
        std::printf("           %-8s %6.3f %6.3f %6.3f %6.3f %6.3f  (n=%ld)\n", names[w], m[w].share(m[w].stone),
                    m[w].share(m[w].gravel), m[w].share(m[w].sand), m[w].share(m[w].clay), m[w].share(m[w].dirt),
                    m[w].n);
    }
    CHECK(m[INTERIOR].clay > 0 && m[INTERIOR].dirt > 0);   /* sheltered coves exist (bay palette) */
    const long Mix::*kinds[5] = {&Mix::stone, &Mix::gravel, &Mix::sand, &Mix::clay, &Mix::dirt};
    for (auto k : kinds) {
        CHECK(std::fabs(m[EDGE].share(m[EDGE].*k) - m[INTERIOR].share(m[INTERIOR].*k)) < 0.02);
        CHECK(std::fabs(m[CORNER].share(m[CORNER].*k) - m[INTERIOR].share(m[INTERIOR].*k)) < 0.06);
    }
    g_islands = false;
}

void testPointwise() {
    g_islands = true;
    /* a: the middle cube alone, its neighbours never built (apron path); b: the neighbours first (base path);
     * c: another generator, the cubes in reverse order */
    Gen a(makeConfig(7, true)), b(makeConfig(7, true)), c(makeConfig(7, true));
    for (int cx = 0; cx < 3; cx++) {
        for (int cz = 0; cz < 3; cz++) {
            if (cx != 1 || cz != 1) (void)b.g().base(cx, cz);
        }
    }
    for (int cx = 2; cx >= 0; cx--) {
        for (int cz = 2; cz >= 0; cz--) (void)c.g().base(cx, cz);
    }
    auto ta = a.g().base(1, 1), tb = b.g().base(1, 1), tc = c.g().base(1, 1);
    CHECK(ta && tb && tc);
    int diff = 0, sea = 0;
    for (int k = 0; ta && tb && tc && k < COLS; k++) {
        diff += ta->surface[k] != tb->surface[k];
        diff += ta->surface[k] != tc->surface[k];
        sea += ta->biome[k] == SBW_OCEAN;
    }
    CHECK(diff == 0);
    CHECK(sea > 500);
    g_islands = false;
}

void testOnlyOceanColumnsChange() {
    g_islands = true;
    Gen on(makeConfig(5, true)), off(makeConfig(5, false));
    int landDiff = 0, seaDiff = 0;
    for (int cx = 0; cx < 6; cx++) {
        for (int cz = 0; cz < 6; cz++) {
            auto x = on.g().base(cx, cz), y = off.g().base(cx, cz);
            for (int k = 0; k < COLS; k++) {
                CHECK(x->h[k] == y->h[k] && x->water[k] == y->water[k]);
                if (x->biome[k] == SBW_OCEAN) seaDiff += x->surface[k] != y->surface[k];
                else landDiff += x->surface[k] != y->surface[k];
            }
        }
    }
    CHECK(landDiff == 0);
    CHECK(seaDiff > 0);
    g_islands = false;
}

void testNeighbourFailureFailsTheTile() {
    g_islands = false;
    g_failCx = 10;
    {
        Gen on(makeConfig(3, true)), off(makeConfig(3, false));
        CHECK(on.g().base(9, 4) == nullptr);   /* its east neighbour cannot be read: no clipped fallback */
        CHECK(off.g().base(9, 4) != nullptr);  /* without the pass the neighbour is never asked */
        CHECK(on.g().base(7, 4) != nullptr);   /* cubes not next to it are fine */
    }
    g_failCx = INT64_MIN;
}

} // namespace

int main() {
    testRoughBedHasNoEdgeBias();
    testIslandsHaveNoEdgeBias();
    testPointwise();
    testOnlyOceanColumnsChange();
    testNeighbourFailureFailsTheTile();
    std::printf("sea floor: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
