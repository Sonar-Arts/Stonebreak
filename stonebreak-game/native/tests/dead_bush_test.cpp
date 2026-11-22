/* Dead bushes (2026-11-22, within SBW ABI 7; features.cpp collectFeatures).
 *
 * Open desert / red sand desert sand grows a sparse dead bush on columns that did not roll a cactus. Checks, on flat
 * desert and red sand desert profiles (vegetation channels on and off): bushes appear in both biomes at about
 * DEAD_BUSH_CHANCE of the sand columns; every bush stands on desert sand at the carved height with no cactus on its
 * column; nothing grows on a non-desert biome; withholding the bush id (0, an older game) places none and leaves every
 * other feature as it was; populate writes the bush into the world. */
#include "gen.hpp"
#include "stonebreak/worldgen.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unordered_map>

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

constexpr float DEAD_BUSH_CHANCE = 0.0035f;   /* features.cpp */

/* flat land; the x < 0 half is red sand desert, x >= 0 desert (or plains for the negative case) */
template <int EAST, int WEST>
int32_t flatProfile(void*, int64_t cx, int64_t, sbw_column_profile* out) {
    for (int c = 0; c < 1024; c++) {
        out->height[c] = 80;
        out->water[c] = SBW_NO_WATER;
        out->biome[c] = static_cast<uint8_t>(cx < 0 ? WEST : EAST);
        out->river_floor[c] = 0;
        out->river_roof[c] = 0;
        out->river_flow[c] = -1;
        out->carve[c] = 0;
    }
    return 1;
}

/* the same land with sparse dryland vegetation channels (no canopy, mostly bare ground) */
template <int EAST, int WEST>
int32_t vegProfile(void* u, int64_t cx, int64_t cz, sbw_column_profile* out) {
    flatProfile<EAST, WEST>(u, cx, cz, out);
    //                       cover height clear grass shrub moss bare wet
    static const uint8_t V[8] = {0, 0, 254, 20, 15, 0, 220, 0};
    for (int k = 0; k < 8; k++) std::memset(out->surface[k], V[k], 1024);
    return 1;
}

sbw_gen_config makeConfig(sbw_column_fn fn, bool vegetation, bool withBush) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = 4242;
    c.kind = SBW_KIND_PROFILE;
    c.column_fn = fn;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    if (!withBush) c.block_ids[SBW_DEAD_BUSH] = 0;
    c.surface_prefetch = SBW_PREFETCH_OFF;
    if (vegetation) c.flags |= SBW_FLAG_VEGETATION;
    else c.flags &= ~SBW_FLAG_VEGETATION;
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

struct FakeWorld {
    std::unordered_map<uint64_t, uint16_t> ids;
};
uint64_t key(int64_t x, int64_t y, int64_t z) {
    return (static_cast<uint64_t>(x & 0x1FFFFF) << 42) | (static_cast<uint64_t>(y & 0x1FFFFF) << 21)
           | static_cast<uint64_t>(z & 0x1FFFFF);
}
int32_t fwGet(cv_world*, int64_t, int64_t, int64_t) { return 0; }
int32_t fwSet(cv_world* w, int64_t x, int64_t y, int64_t z, uint16_t id) {
    reinterpret_cast<FakeWorld*>(w)->ids[key(x, y, z)] = id;
    return 1;
}
int32_t fwLayerGet(cv_world*, int32_t, int64_t, int64_t, int64_t) { return 0; }
int32_t fwLayerSet(cv_world*, int32_t, int64_t, int64_t, int64_t, uint16_t) { return 1; }
int32_t fwSurface(cv_world*, int64_t, int64_t, int64_t* out) { *out = 0; return 1; }
const cv_gen_api API = {fwGet, fwSet, fwLayerGet, fwLayerSet, fwSurface, {}};

int64_t floorDiv(int64_t a, int64_t b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

bool isBush(const Feature& f) { return f.kind == Feature::PLANT && f.cells.size() == 1 && f.cells[0].id == SBW_DEAD_BUSH; }

/* Scans column cubes cx in [-n, n), cz in [0, n): red sand desert west of x = 0, desert (or `east`) east of it. */
void checkDesert(const char* name, sbw_column_fn fn, bool vegetation, int n) {
    Gen gen(makeConfig(fn, vegetation, true));
    Gen none(makeConfig(fn, vegetation, false));
    if (gen.t == nullptr || none.t == nullptr) { CHECK(false); return; }
    int bushes = 0, west = 0, east = 0, withoutId = 0;
    long sandColumns = 0;
    const Feature* sample = nullptr;
    std::shared_ptr<const FeatureTile> keep;
    for (int64_t cx = -n; cx < n; cx++) {
        for (int64_t cz = 0; cz < n; cz++) {
            auto t = gen.g().features(cx, cz);
            auto u = none.g().features(cx, cz);
            CHECK(t != nullptr && u != nullptr);
            if (!t || !u) continue;
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    sbw_column_info info{};
                    if (sbw_query_column(gen.t, cx * 32 + lx, cz * 32 + lz, &info) >= 0
                            && (info.surface_block == SBW_SAND || info.surface_block == SBW_RED_SAND)) {
                        sandColumns++;
                    }
                }
            }
            size_t otherWith = 0, otherWithout = 0;
            for (const Feature& f : t->fs) {
                if (!isBush(f)) { otherWith++; continue; }
                bushes++;
                const FeatureCell& c = f.cells[0];
                (c.x < 0 ? west : east)++;
                sbw_column_info info{};
                CHECK(sbw_query_column(gen.t, c.x, c.z, &info) >= 0);
                CHECK(info.biome == (c.x < 0 ? SBW_RED_SAND_DESERT : SBW_DESERT));
                CHECK(info.surface_block == (c.x < 0 ? SBW_RED_SAND : SBW_SAND));
                CHECK(c.y == info.carved_height);
                if (sample == nullptr) { sample = &f; keep = t; }
            }
            /* no bush shares its column with a cactus */
            for (const Feature& f : t->fs) {
                if (f.kind != Feature::PLANT || f.cells.empty() || f.cells[0].id != SBW_CACTUS) continue;
                for (const Feature& b : t->fs) CHECK(!(isBush(b) && b.ax == f.ax && b.az == f.az));
            }
            for (const Feature& f : u->fs) {
                if (isBush(f) || (!f.cells.empty() && f.cells[0].id == 0)) withoutId++;
                else otherWithout++;
            }
            CHECK(otherWith == otherWithout);   /* the bush rule moves nothing else */
        }
    }
    CHECK(west > 0 && east > 0);
    CHECK(withoutId == 0);
    const double rate = sandColumns > 0 ? static_cast<double>(bushes) / static_cast<double>(sandColumns) : 0.0;
    CHECK(rate > DEAD_BUSH_CHANCE * 0.6 && rate < DEAD_BUSH_CHANCE * 1.4);
    int written = 0;
    if (sample != nullptr) {
        const FeatureCell& c = sample->cells[0];
        const int64_t cx = floorDiv(sample->ax - 16, 32), cz = floorDiv(sample->az - 16, 32);
        const int64_t cy = floorDiv(sample->ay - 2, 32);
        FakeWorld w;
        CHECK(gen.g().populate(&API, reinterpret_cast<cv_world*>(&w), cx, cy, cz) >= 0);
        auto it = w.ids.find(key(c.x, c.y, c.z));
        written = it != w.ids.end() && it->second == SBW_DEAD_BUSH;
        CHECK(written == 1);
    }
    std::printf("  %-24s %4d bushes (%d red sand, %d sand) over %ld sand columns = %.4f, populated %d\n", name, bushes,
                west, east, sandColumns, rate, written);
}

/* plains / meadow: no dead bushes at all */
void checkElsewhere(const char* name, sbw_column_fn fn, int n) {
    Gen gen(makeConfig(fn, false, true));
    if (gen.t == nullptr) { CHECK(false); return; }
    int bushes = 0;
    for (int64_t cx = -n; cx < n; cx++) {
        for (int64_t cz = 0; cz < n; cz++) {
            auto t = gen.g().features(cx, cz);
            CHECK(t != nullptr);
            if (t) for (const Feature& f : t->fs) bushes += isBush(f);
        }
    }
    CHECK(bushes == 0);
    std::printf("  %-24s %4d bushes\n", name, bushes);
}

}  // namespace

int main() {
    checkDesert("desert", flatProfile<SBW_DESERT, SBW_RED_SAND_DESERT>, false, 6);
    checkDesert("desert + vegetation", vegProfile<SBW_DESERT, SBW_RED_SAND_DESERT>, true, 6);
    checkElsewhere("plains / meadow", flatProfile<SBW_PLAINS, SBW_MEADOW>, 4);
    std::printf("dead_bush_test: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
