/* Vegetation channels (SBW ABI 5 surface slots 0..7, agent V 2026-11-19; Generator::treeAtVeg + the ground rules in
 * features.cpp collectFeatures).
 *
 * A synthetic PLAINS profile at a flat height with the vegetation channels set per quadrant of 4x4 column cubes:
 * no data / closed tall forest / half cover with clearings / savanna (sparse short trees, grass, shrubs) / bare
 * ground. Checks: no data = exactly the pre-ABI-5 features (same anchors as a generator whose column_fn never
 * touches the surface slots); tree density follows cover; tall canopy gives bigger trees than savanna; clearings
 * open tree-free gaps; shrubs give bushes; bare ground swaps grass to dirt; the FastLOD probe (treeAt) agrees with
 * population. */
#include "gen.hpp"
#include "stonebreak/worldgen.h"

#include <cstdio>
#include <cstring>
#include <set>
#include <tuple>

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

enum Quad { NODATA = 0, FOREST, CLEARINGS, SAVANNA, BARE, QUADS };

Quad quadOf(int64_t cx, int64_t cz) {
    const int qx = static_cast<int>(cx & 7) >> 2, qz = static_cast<int>(cz & 7) >> 2, band = static_cast<int>(cz & 15) >> 3;
    if (band == 1) return qx == 0 ? BARE : NODATA;
    return static_cast<Quad>(qz * 2 + qx);   /* 0 NODATA, 1 FOREST, 2 CLEARINGS, 3 SAVANNA */
}

void base(sbw_column_profile* out) {
    for (int c = 0; c < 1024; c++) {
        out->height[c] = 80;
        out->water[c] = SBW_NO_WATER;
        out->biome[c] = SBW_PLAINS;
        out->river_floor[c] = 0;
        out->river_roof[c] = 0;
        out->river_flow[c] = -1;
        out->carve[c] = 0;
    }
}

/* writes no surface slot at all: the generator's 255 pre-fill is all it sees */
int32_t plainProfile(void*, int64_t, int64_t, sbw_column_profile* out) {
    base(out);
    return 1;
}

int32_t vegProfile(void*, int64_t cx, int64_t cz, sbw_column_profile* out) {
    base(out);
    const Quad q = quadOf(cx, cz);
    if (q == NODATA) return 1;
    //                       cover height clear  grass shrub moss  bare  wet
    static const uint8_t V[QUADS][8] = {{0},
                                        {254, 120, 0, 25, 10, 0, 0, 0},      /* closed forest, 30 m canopy */
                                        {127, 60, 127, 100, 10, 0, 0, 0},    /* half cover, half clearings, 15 m */
                                        {25, 24, 0, 203, 76, 0, 10, 0},      /* savanna: 10 % cover, 6 m, grass, shrub */
                                        {0, 0, 0, 25, 0, 0, 203, 0}};        /* bare ground 80 % */
    for (int k = 0; k < 8; k++) std::memset(out->surface[k], V[q][k], 1024);
    return 1;
}

sbw_gen_config makeConfig(sbw_column_fn fn, bool vegetation = true) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = 4242;
    c.kind = SBW_KIND_PROFILE;
    c.column_fn = fn;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
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

struct Stats {
    int cols = 0, trees = 0, bushes = 0, treeCells = 0, plants = 0, dirtSwaps = 0, emptyBlocks = 0, blocks = 0;
    int probeTrees = 0;
};

}  // namespace

int main() {
    Gen veg(makeConfig(vegProfile)), plain(makeConfig(plainProfile)), off(makeConfig(vegProfile, false));
    if (veg.t == nullptr || plain.t == nullptr || off.t == nullptr) return 1;
    /* flag off (worlds created before WorldFeatureVersion V3): channels present but ignored = the biome rules */
    int offDiff = 0;
    for (int64_t cx = 0; cx < 8; cx++) {
        for (int64_t cz = 0; cz < 16; cz += 3) {
            auto a = off.g().features(cx, cz);
            auto b = plain.g().features(cx, cz);
            if (!a || !b || a->fs.size() != b->fs.size()) { offDiff++; continue; }
            for (size_t i = 0; i < a->fs.size(); i++)
                offDiff += a->fs[i].ax != b->fs[i].ax || a->fs[i].az != b->fs[i].az || a->fs[i].cells.size() != b->fs[i].cells.size();
            for (int lz = 0; lz < 32; lz += 5)
                for (int lx = 0; lx < 32; lx += 5) {
                    int t1 = 0, t2 = 0;
                    offDiff += off.g().treeAt(cx * 32 + lx, cz * 32 + lz, SBW_GRASS, SBW_PLAINS, t1)
                               != plain.g().treeAt(cx * 32 + lx, cz * 32 + lz, SBW_GRASS, SBW_PLAINS, t2);
                }
        }
    }
    CHECK(offDiff == 0);
    Stats st[QUADS];
    std::set<std::tuple<int64_t, int64_t, int>> vegNoData, plainNoData;
    for (int64_t cx = 0; cx < 8; cx++) {
        for (int64_t cz = 0; cz < 16; cz++) {
            const Quad q = quadOf(cx, cz);
            auto fv = veg.g().features(cx, cz);
            auto fp = plain.g().features(cx, cz);
            CHECK(fv && fp);
            if (!fv || !fp) continue;
            Stats& s = st[q];
            s.cols += 1024;
            bool has[4][4] = {};   /* trees per 8x8 block of the cube */
            for (const Feature& f : fv->fs) {
                if (q == NODATA) vegNoData.insert({f.ax, f.az, static_cast<int>(f.kind) * 1000 + static_cast<int>(f.cells.size())});
                if (f.kind == Feature::TREE && f.cells.size() > 8) {
                    s.trees++;
                    s.treeCells += static_cast<int>(f.cells.size());
                    has[(f.ax & 31) >> 3][(f.az & 31) >> 3] = true;
                } else if (f.kind == Feature::TREE) {
                    s.bushes++;
                } else if (f.kind == Feature::PLANT) {
                    s.plants++;
                } else if (f.kind == Feature::SWAP && !f.cells.empty() && f.cells[0].id == SBW_DIRT) {
                    s.dirtSwaps++;
                }
            }
            for (auto& row : has)
                for (bool b : row) { s.blocks++; s.emptyBlocks += b ? 0 : 1; }
            if (q == NODATA)
                for (const Feature& f : fp->fs)
                    plainNoData.insert({f.ax, f.az, static_cast<int>(f.kind) * 1000 + static_cast<int>(f.cells.size())});
            /* the FastLOD tree probe sees the same trees (bushes are not trees) */
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    int trunk = 0;
                    if (veg.g().treeAt(cx * 32 + lx, cz * 32 + lz, SBW_GRASS, SBW_PLAINS, trunk) >= 0) s.probeTrees++;
                }
            }
        }
    }
    static const char* NAME[QUADS] = {"no data", "forest", "clearings", "savanna", "bare"};
    for (int q = 0; q < QUADS; q++) {
        const Stats& s = st[q];
        std::printf("  %-9s trees/col %.4f (probe %.4f) cells/tree %.1f bushes/col %.4f plants/col %.3f dirt/col %.3f "
                    "empty 8x8 %.2f\n", NAME[q], double(s.trees) / s.cols, double(s.probeTrees) / s.cols,
                    s.trees ? double(s.treeCells) / s.trees : 0.0, double(s.bushes) / s.cols, double(s.plants) / s.cols,
                    double(s.dirtSwaps) / s.cols, s.blocks ? double(s.emptyBlocks) / s.blocks : 0.0);
    }
    const auto per = [&](Quad q, int Stats::*m) { return double(st[q].*m) / st[q].cols; };
    CHECK(vegNoData == plainNoData);                                 /* no data = the pre-ABI-5 features exactly */
    CHECK(!vegNoData.empty());
    CHECK(per(FOREST, &Stats::trees) > 0.04);                        /* closed forest: a tree every < 25 columns */
    CHECK(per(FOREST, &Stats::trees) > 1.6 * per(CLEARINGS, &Stats::trees));
    CHECK(per(CLEARINGS, &Stats::trees) > 2.0 * per(SAVANNA, &Stats::trees));
    CHECK(per(SAVANNA, &Stats::trees) > 0.0);
    CHECK(st[BARE].trees == 0);
    CHECK(st[CLEARINGS].emptyBlocks > st[FOREST].emptyBlocks + st[FOREST].blocks / 10);  /* gaps */
    CHECK(double(st[FOREST].treeCells) / st[FOREST].trees > 1.3 * double(st[SAVANNA].treeCells) / st[SAVANNA].trees);
    CHECK(per(SAVANNA, &Stats::bushes) > 2.0 * per(FOREST, &Stats::bushes));
    CHECK(per(SAVANNA, &Stats::plants) > per(FOREST, &Stats::plants));
    CHECK(per(BARE, &Stats::dirtSwaps) > 0.4);
    CHECK(per(FOREST, &Stats::dirtSwaps) == 0.0);
    /* probe = population, but for the odd column a carver cut (population needs uncarved ground) */
    for (Quad q : {FOREST, CLEARINGS, SAVANNA, BARE}) CHECK(st[q].probeTrees >= st[q].trees && st[q].probeTrees <= st[q].trees * 1.15 + 2);

    /* SBW_FLAG_VEG_OPEN_LAND (WorldFeatureVersion V4): meadows + steeper density curve open the land up; closed
     * forest keeps most of its trees, half cover thins out more, no-data columns are untouched */
    sbw_gen_config oc = makeConfig(vegProfile);
    oc.flags |= SBW_FLAG_VEG_OPEN_LAND;
    Gen open(oc);
    if (open.t == nullptr) return 1;
    int probeOpen[QUADS] = {}, probeVeg[QUADS] = {}, plantsOpen[QUADS] = {}, plantsVeg[QUADS] = {}, noDataDiff = 0;
    for (int64_t cx = 0; cx < 8; cx++) {
        for (int64_t cz = 0; cz < 16; cz++) {
            const Quad q = quadOf(cx, cz);
            for (int lz = 0; lz < 32; lz++)
                for (int lx = 0; lx < 32; lx++) {
                    int t1 = 0, t2 = 0;
                    const bool a = open.g().treeAt(cx * 32 + lx, cz * 32 + lz, SBW_GRASS, SBW_PLAINS, t1) >= 0;
                    const bool b = veg.g().treeAt(cx * 32 + lx, cz * 32 + lz, SBW_GRASS, SBW_PLAINS, t2) >= 0;
                    probeOpen[q] += a;
                    probeVeg[q] += b;
                    if (q == NODATA) noDataDiff += a != b;
                }
            auto fo = open.g().features(cx, cz);
            auto fv = veg.g().features(cx, cz);
            if (fo) for (const Feature& f : fo->fs) plantsOpen[q] += f.kind == Feature::PLANT;
            if (fv) for (const Feature& f : fv->fs) plantsVeg[q] += f.kind == Feature::PLANT;
        }
    }
    for (int q = 1; q < QUADS; q++)
        std::printf("  open land %-9s trees %d -> %d (x%.2f), plants %d -> %d\n", NAME[q], probeVeg[q], probeOpen[q],
                    probeVeg[q] ? double(probeOpen[q]) / probeVeg[q] : 0.0, plantsVeg[q], plantsOpen[q]);
    CHECK(noDataDiff == 0);
    CHECK(probeOpen[FOREST] > 0.65 * probeVeg[FOREST]);    /* closed forest stays forest outside the meadows */
    CHECK(probeOpen[FOREST] < 0.9 * probeVeg[FOREST]);     /* ... and the meadows open it up */
    CHECK(probeOpen[CLEARINGS] < 0.92 * probeVeg[CLEARINGS]);
    CHECK(probeOpen[SAVANNA] > 0.75 * probeVeg[SAVANNA]);  /* open savanna keeps its scattered trees */
    CHECK(probeOpen[BARE] == 0);
    CHECK(plantsOpen[FOREST] > plantsVeg[FOREST]);         /* meadow grass and flowers */
    std::printf("vegetation: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
