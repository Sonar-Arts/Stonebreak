/* The Shade (SBW ABI 7, Project-Heracles; shape.cpp selectBiome, features.cpp treeAtRule / regularTree).
 *
 * Standard: with SBW_FLAG_SHADE the selector's dry temperate band is SBW_SHADE - a regional biome (a few percent of
 * land, rarer than plains) of dry grass over dirt grown with dry oaks only, denser than plains; without the flag no
 * column is Shade and every other column is unchanged. Profile kind (DaedalusTGM-Exp): a column_fn that supplies
 * SBW_SHADE gets the same ground and dry oaks, also where the tiles carry vegetation channels. */
#include "gen.hpp"
#include "stonebreak/worldgen.h"

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

sbw_gen_config makeConfig(int kind, sbw_column_fn fn, uint32_t extraFlags, bool shade) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = 31337;
    c.kind = kind;
    c.column_fn = fn;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    c.surface_prefetch = SBW_PREFETCH_OFF;
    c.flags |= extraFlags;
    if (shade) c.flags |= SBW_FLAG_SHADE;
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

int treeKind(const sbw_column_info& i) { return i.tree == 0 ? -1 : (i.tree & 0xFF) - 1; }

/* tree columns per column in a 64 x 64 area at (x0, z0) of biome `biome` only */
double treeDensity(const Gen& gen, int64_t x0, int64_t z0, int biome, int& wrongKind) {
    int cols = 0, trees = 0;
    for (int64_t z = z0; z < z0 + 64; z++) {
        for (int64_t x = x0; x < x0 + 64; x++) {
            sbw_column_info i{};
            sbw_query_column(gen.t, x, z, &i);
            if (i.biome != biome || i.carved_height != i.height) continue;
            cols++;
            const int k = treeKind(i);
            if (k < 0) continue;
            trees++;
            if (biome == SBW_SHADE) wrongKind += k != SBW_TREE_DRY_OAK;
            else wrongKind += k == SBW_TREE_DRY_OAK;
        }
    }
    return cols > 0 ? static_cast<double>(trees) / cols : 0.0;
}

void checkStandard() {
    Gen on(makeConfig(SBW_KIND_STANDARD, nullptr, 0, true)), off(makeConfig(SBW_KIND_STANDARD, nullptr, 0, false));
    if (on.t == nullptr || off.t == nullptr) { CHECK(false); return; }
    /* the base tiles (uncarved) of one column cube every 256 blocks over a 12k-block square, 64 columns each: the
     * biome share. Only the base tiles - a carved query per sample would build every carver. */
    int land = 0, shade = 0, plains = 0, offShade = 0, changed = 0, wrongGround = 0;
    int64_t shadeAt[2] = {0, 0}, plainsAt[2] = {0, 0};
    bool haveShade = false, havePlains = false;
    for (int64_t cz = -24; cz < 24; cz++) {
        for (int64_t cx = -24; cx < 24; cx++) {
            auto a = on.g().base(cx * 8, cz * 8);
            auto b = off.g().base(cx * 8, cz * 8);
            CHECK(a && b);
            if (!a || !b) continue;
            for (int c = 0; c < 1024; c += 17) {
                const bool dry = a->water[c] == NO_WATER || a->water[c] <= a->h[c];
                if (dry && a->h[c] >= 64) land++;
                offShade += b->biome[c] == SBW_SHADE;
                const int64_t x = cx * 256 + (c & 31), z = cz * 256 + (c >> 5);
                if (a->biome[c] == SBW_SHADE) {
                    shade++;
                    wrongGround += a->surface[c] != SBW_DRY_GRASS;
                    if (!haveShade) { shadeAt[0] = x; shadeAt[1] = z; haveShade = true; }
                } else {
                    /* the flag only touches Shade columns */
                    changed += a->biome[c] != b->biome[c] || a->surface[c] != b->surface[c] || a->h[c] != b->h[c];
                    if (a->biome[c] == SBW_PLAINS) {
                        plains++;
                        if (!havePlains) { plainsAt[0] = x; plainsAt[1] = z; havePlains = true; }
                    }
                }
            }
        }
    }
    const double share = land > 0 ? static_cast<double>(shade) / land : 0.0;
    CHECK(offShade == 0);                  /* no flag: never Shade */
    CHECK(changed == 0);
    CHECK(shade > 0);
    CHECK(share > 0.01 && share < 0.20);   /* a regional biome */
    CHECK(plains > shade);                 /* plains stays the commoner temperate biome */
    CHECK(wrongGround == 0);               /* dry grass over dirt */
    int wrong = 0;
    const double shadeTrees = haveShade ? treeDensity(on, shadeAt[0], shadeAt[1], SBW_SHADE, wrong) : 0.0;
    const double plainsTrees = havePlains ? treeDensity(on, plainsAt[0], plainsAt[1], SBW_PLAINS, wrong) : 0.0;
    CHECK(wrong == 0);                     /* dry oaks in the Shade, nowhere else */
    CHECK(shadeTrees > 0.0);
    std::printf("  standard: %d land samples, shade %.2f%% (%d), plains %d; tree columns per column shade %.4f plains %.4f\n",
                land, share * 100.0, shade, plains, shadeTrees, plainsTrees);
}

/* profile kind: Shade on the left half of every column cube, plains on the right; flat at 80 */
int32_t shadeProfile(void*, int64_t, int64_t, sbw_column_profile* out) {
    for (int c = 0; c < 1024; c++) {
        out->height[c] = 80;
        out->water[c] = SBW_NO_WATER;
        out->biome[c] = (c & 31) < 16 ? SBW_SHADE : SBW_PLAINS;
        out->river_floor[c] = 0;
        out->river_roof[c] = 0;
        out->river_flow[c] = -1;
        out->carve[c] = 0;
    }
    return 1;
}

/* the same, with grassland vegetation channels (the Shade keeps its rule trees there) */
int32_t shadeVegProfile(void* u, int64_t cx, int64_t cz, sbw_column_profile* out) {
    shadeProfile(u, cx, cz, out);
    static const uint8_t V[8] = {60, 40, 0, 150, 30, 0, 10, 0};
    for (int k = 0; k < 8; k++) std::memset(out->surface[k], V[k], 1024);
    return 1;
}

void checkProfile(const char* name, sbw_column_fn fn, uint32_t flags) {
    Gen gen(makeConfig(SBW_KIND_PROFILE, fn, flags, true));
    if (gen.t == nullptr) { CHECK(false); return; }
    int shade = 0, dryOaks = 0, otherTreesInShade = 0, dryGround = 0, dryOutside = 0;
    for (int64_t z = 0; z < 128; z++) {
        for (int64_t x = 0; x < 128; x++) {
            sbw_column_info i{};
            CHECK(sbw_query_column(gen.t, x, z, &i) >= 0);
            const int k = treeKind(i);
            if (i.biome == SBW_SHADE) {
                shade++;
                dryGround += i.carved_height != i.height || i.surface_block == SBW_DRY_GRASS;   /* uncarved top */
                if (k == SBW_TREE_DRY_OAK) dryOaks++;
                else if (k >= 0) otherTreesInShade++;
            } else {
                dryOutside += k == SBW_TREE_DRY_OAK;
            }
        }
    }
    std::printf("  %-22s shade columns %d, dry grass %d, dry oaks %d\n", name, shade, dryGround, dryOaks);
    CHECK(shade == 128 * 64);
    CHECK(dryGround == shade);
    CHECK(dryOaks > 0);
    CHECK(otherTreesInShade == 0);
    CHECK(dryOutside == 0);
    /* a populated dry oak: dry log at the anchor */
    for (int64_t z = 0; z < 128; z++) {
        for (int64_t x = 0; x < 16; x++) {
            sbw_column_info i{};
            sbw_query_column(gen.t, x, z, &i);
            if (treeKind(i) != SBW_TREE_DRY_OAK) continue;
            auto tile = gen.g().features(x >> 5, z >> 5);
            bool found = false;
            for (const Feature& f : tile->fs) {
                if (f.kind != Feature::TREE || f.ax != x || f.az != z) continue;
                for (const FeatureCell& c : f.cells) {
                    found |= c.x == x && c.z == z && c.y == i.height && c.id == SBW_DRY_OAK_LOG;
                    CHECK(c.id == SBW_DRY_OAK_LOG || c.id == SBW_DRY_OAK_LEAVES);
                }
            }
            CHECK(found);
            return;
        }
    }
    CHECK(false);
}

/* FastLOD's coarse tree spots (sbw_probe_cell_trees): dry oaks in the Shade, their kind in 3 bits (SBW_TREE_DRY_OAK = 4
 * overflowed the old 2-bit field into the trunk height) */
void checkCoarseSpots() {
    Gen gen(makeConfig(SBW_KIND_PROFILE, shadeProfile, 0, true));
    if (gen.t == nullptr) { CHECK(false); return; }
    const int cells = 8, cellSize = 8;   /* a 64 x 64 node: Shade where x % 32 < 16 */
    const size_t nc = static_cast<size_t>(cells * cells);
    std::vector<int32_t> h(nc, 80), w(nc, SBW_NO_WATER);
    std::vector<uint16_t> surf(nc);
    for (int ix = 0; ix < cells; ix++)
        for (int iz = 0; iz < cells; iz++)
            surf[static_cast<size_t>(ix * cells + iz)] = ((ix * cellSize) & 31) < 16 ? SBW_DRY_GRASS : SBW_GRASS;
    std::vector<int32_t> spots(8192);
    const int32_t n = sbw_probe_cell_trees(gen.t, 0, 0, cells, cellSize, h.data(), w.data(), surf.data(), spots.data(),
                                           static_cast<int32_t>(spots.size()));
    CHECK(n > 0);
    int dry = 0, other = 0, wrongPlace = 0, wrongTrunk = 0;
    for (int32_t i = 0; i < n; i++) {
        const int32_t sp = spots[static_cast<size_t>(i)];
        const int lx = sp & 0xFF, kind = (sp >> 16) & 7, trunk = (sp >> 19) & 0xFF;
        const bool shadeCol = (lx & 31) < 16;
        if (kind == SBW_TREE_DRY_OAK) {
            dry++;
            wrongPlace += !shadeCol;
            wrongTrunk += trunk != 5;
        } else {
            other++;
            wrongPlace += shadeCol;
        }
    }
    std::printf("  coarse spots: %d dry oaks, %d other trees\n", dry, other);
    CHECK(dry > 0);
    CHECK(wrongPlace == 0);
    CHECK(wrongTrunk == 0);
}

/* FastLOD point samples of a Standard shape-2 world with vegetation (its distilled channels): the Shade keeps dry oaks */
void checkPointSamplesShape2() {
    sbw_gen_config c = makeConfig(SBW_KIND_STANDARD, nullptr, SBW_FLAG_VEGETATION, true);
    c.standard_shape = SBW_STANDARD_SHAPE_V2;
    Gen gen(c);
    if (gen.t == nullptr) { CHECK(false); return; }
    /* find a Shade column cube */
    int64_t sx = 0, sz = 0;
    bool found = false;
    for (int64_t cz = -24; cz < 24 && !found; cz++) {
        for (int64_t cx = -24; cx < 24 && !found; cx++) {
            auto b = gen.g().base(cx * 8, cz * 8);
            if (!b) continue;
            for (int col = 0; col < 1024 && !found; col++) {
                if (b->biome[col] == SBW_SHADE) {
                    sx = cx * 256 + (col & 31);
                    sz = cz * 256 + (col >> 5);
                    found = true;
                }
            }
        }
    }
    CHECK(found);
    if (!found) return;
    const int count = 48, stride = 2, n = count * count;   /* stride >= 2: the point path */
    std::vector<int32_t> h(static_cast<size_t>(n)), w(static_cast<size_t>(n)), trees(static_cast<size_t>(n));
    std::vector<uint16_t> surf(static_cast<size_t>(n));
    CHECK(sbw_sample_columns(gen.t, sx - count, sz - count, count, stride, 0, h.data(), w.data(), surf.data(),
                             trees.data()) >= 0);
    int dry = 0, wrong = 0;
    for (size_t i = 0; i < static_cast<size_t>(n); i++) {
        if (trees[i] == 0) continue;
        const int kind = (trees[i] & 0xFF) - 1;
        const bool shade = surf[i] == SBW_DRY_GRASS;
        dry += kind == SBW_TREE_DRY_OAK;
        wrong += shade != (kind == SBW_TREE_DRY_OAK);
    }
    std::printf("  shape-2 point samples: %d dry oaks\n", dry);
    CHECK(dry > 0);
    CHECK(wrong == 0);
}

/* The Shade gathers into one mass per region (an accursed forest), not specks through other biomes: on a 128 x 128
 * grid of samples 48 blocks apart (~6 km square), little of it lies in small fragments, most of it is interior and its
 * patches are large. Its regions: native shade_regions = Java ShadeRegions. */
void checkCohesion() {
    Gen gen(makeConfig(SBW_KIND_STANDARD, nullptr, 0, true));
    if (gen.t == nullptr) { CHECK(false); return; }
    constexpr int N = 128, STEP = 48;
    std::vector<uint8_t> shade(static_cast<size_t>(N * N));
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            const int64_t x = static_cast<int64_t>(i - N / 2) * STEP, z = static_cast<int64_t>(j - N / 2) * STEP;
            auto b = gen.g().base(x >> 5, z >> 5);
            CHECK(b != nullptr);
            if (!b) continue;
            shade[static_cast<size_t>(i * N + j)] = b->biome[colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31))] == SBW_SHADE;
        }
    }
    int total = 0, interior = 0, inSmall = 0, components = 0;
    std::vector<int> label(static_cast<size_t>(N * N), -1), stack;
    for (int k = 0; k < N * N; k++) {
        if (!shade[static_cast<size_t>(k)]) continue;
        total++;
        const int i = k / N, j = k % N;
        bool in = i > 0 && j > 0 && i < N - 1 && j < N - 1;
        if (in) in = shade[static_cast<size_t>(k - N)] && shade[static_cast<size_t>(k + N)] && shade[static_cast<size_t>(k - 1)] && shade[static_cast<size_t>(k + 1)];
        interior += in;
        if (label[static_cast<size_t>(k)] >= 0) continue;
        int size = 0;
        stack.assign(1, k);
        label[static_cast<size_t>(k)] = components;
        while (!stack.empty()) {
            const int c = stack.back();
            stack.pop_back();
            size++;
            const int ci = c / N, cj = c % N;
            const int nb[4][2] = {{ci - 1, cj}, {ci + 1, cj}, {ci, cj - 1}, {ci, cj + 1}};
            for (const auto& q : nb) {
                if (q[0] < 0 || q[1] < 0 || q[0] >= N || q[1] >= N) continue;
                const auto qk = static_cast<size_t>(q[0] * N + q[1]);
                if (shade[qk] && label[qk] < 0) { label[qk] = components; stack.push_back(static_cast<int>(qk)); }
            }
        }
        components++;
        if (size < 20) inSmall += size;   /* < 20 samples = under ~0.05 km^2 */
    }
    const double smallShare = total ? static_cast<double>(inSmall) / total : 0.0;
    const double interiorShare = total ? static_cast<double>(interior) / total : 0.0;
    std::printf("  cohesion: %d Shade samples in %d patches; %.1f%% in small fragments, %.1f%% interior\n", total,
                components, smallShare * 100.0, interiorShare * 100.0);
    CHECK(total > 0);
    CHECK(smallShare < 0.05);                                   /* few specks */
    CHECK(interiorShare > 0.60);                                /* mostly interior (coasts and the cold line cut it) */
    CHECK(components > 0 && total / components >= 60);         /* patches average ~0.14 km^2 or more */
}

/* No Shade on small isles: on a 160 x 160 grid of samples 48 blocks apart (~7.7 km square) the dry land splits into
 * connected landmasses; none smaller than ~0.14 km^2 (60 samples) holds a Shade sample. */
void checkNoSmallIsles() {
    Gen gen(makeConfig(SBW_KIND_STANDARD, nullptr, 0, true));
    if (gen.t == nullptr) { CHECK(false); return; }
    constexpr int N = 160, STEP = 48, SMALL = 60;
    std::vector<uint8_t> land(static_cast<size_t>(N * N)), shade(static_cast<size_t>(N * N)), region(static_cast<size_t>(N * N));
    for (int i = 0; i < N; i++) {
        for (int j = 0; j < N; j++) {
            /* centred on a seed-31337 archipelago (-14000, 9800): several isles, found by a coarse scan */
            const int64_t x = static_cast<int64_t>(i - N / 2) * STEP - 14000, z = static_cast<int64_t>(j - N / 2) * STEP + 9800;
            auto b = gen.g().base(x >> 5, z >> 5);
            if (!b) { CHECK(false); return; }
            const int c = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
            const bool dry = b->water[c] == NO_WATER || b->water[c] <= b->h[c];
            const auto k = static_cast<size_t>(i * N + j);
            land[k] = dry && b->biome[c] != SBW_OCEAN;
            shade[k] = b->biome[c] == SBW_SHADE;
            region[k] = shade_regions::isShadeRegion(31337, x, z);
        }
    }
    std::vector<int> label(static_cast<size_t>(N * N), -1), stack, members;
    int smallIsles = 0, shadeOnSmall = 0, shadeTotal = 0, isleInRegion = 0;
    for (int k = 0; k < N * N; k++) {
        shadeTotal += shade[static_cast<size_t>(k)];
        if (!land[static_cast<size_t>(k)] || label[static_cast<size_t>(k)] >= 0) continue;
        members.clear();
        stack.assign(1, k);
        label[static_cast<size_t>(k)] = k;
        bool edge = false;
        while (!stack.empty()) {
            const int c = stack.back();
            stack.pop_back();
            members.push_back(c);
            const int ci = c / N, cj = c % N;
            edge |= ci == 0 || cj == 0 || ci == N - 1 || cj == N - 1;
            const int nb[4][2] = {{ci - 1, cj}, {ci + 1, cj}, {ci, cj - 1}, {ci, cj + 1}};
            for (const auto& q : nb) {
                if (q[0] < 0 || q[1] < 0 || q[0] >= N || q[1] >= N) continue;
                const auto qk = static_cast<size_t>(q[0] * N + q[1]);
                if (land[qk] && label[qk] < 0) { label[qk] = k; stack.push_back(static_cast<int>(qk)); }
            }
        }
        if (edge || static_cast<int>(members.size()) >= SMALL) continue;   /* cut by the grid edge: size unknown */
        smallIsles++;
        for (int m : members) {
            shadeOnSmall += shade[static_cast<size_t>(m)];
            isleInRegion += region[static_cast<size_t>(m)];
        }
    }
    std::printf("  isles: %d small isles (< %d samples), %d of their samples in Shade regions, %d Shade on them (%d in "
                "the window)\n", smallIsles, SMALL, isleInRegion, shadeOnSmall, shadeTotal);
    CHECK(smallIsles > 0);     /* the area has isles to test */
    CHECK(shadeOnSmall == 0);
    /* the rule itself, inside a real Shade region (regions are rare, so the archipelago above may lie outside every
     * one): temperate plains on deep land is Shade, the same column on an isle / coastal fringe (land < 1) is not */
    int64_t rx = 0;
    while (!shade_regions::isShadeRegion(31337, rx, 0)) rx += 64;
    CHECK(shade_regions::shadeOver(SBW_PLAINS, 1.0f, 0.5f, 31337, rx, 0) == SBW_SHADE);
    CHECK(shade_regions::shadeOver(SBW_PLAINS, 0.85f, 0.5f, 31337, rx, 0) == SBW_PLAINS);
    CHECK(shade_regions::shadeOver(SBW_PLAINS, 0.999f, 0.5f, 31337, rx, 0) == SBW_PLAINS);
}

}  // namespace

int main() {
    checkNoSmallIsles();
    checkCohesion();
    checkCoarseSpots();
    checkPointSamplesShape2();
    checkStandard();
    checkProfile("profile", shadeProfile, 0);
    checkProfile("profile + vegetation", shadeVegProfile, SBW_FLAG_VEGETATION);
    std::printf("shade_test: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
