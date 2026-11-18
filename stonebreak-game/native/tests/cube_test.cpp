/* Cube generator tests (SBW ABI 3, #446). Links the generator's object files directly, so internals
 * (carve footprints, point queries) are reachable as well as the cv_generator table.
 *
 *   worldgen_cube_test            all tests
 *   worldgen_cube_test <name>...  only those tests
 *   worldgen_cube_test --probe    print a cross-section, height histograms and timings (no asserts)
 */
#include "decide.hpp"
#include "gen.hpp"
#include "stonebreak/worldgen.h"

#include <algorithm>
#include <cstdlib>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <set>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

using namespace sbw;

namespace sbw {
void standardColumnPoint(Generator& g, int64_t x, int64_t z, int32_t& h, int32_t& water, int& biome, uint16_t& surface);
}

namespace {

int g_failures = 0;
int g_checks = 0;
#define CHECK(c)                                                                                     \
    do {                                                                                             \
        ++g_checks;                                                                                  \
        if (!(c)) {                                                                                  \
            ++g_failures;                                                                            \
            std::fprintf(stderr, "  FAIL %s:%d  %s\n", __FILE__, __LINE__, #c);                      \
        }                                                                                            \
    } while (0)
#define CHECK_EQ(a, b)                                                                               \
    do {                                                                                             \
        ++g_checks;                                                                                  \
        const auto _a = (a);                                                                         \
        const auto _b = (b);                                                                         \
        if (!(_a == _b)) {                                                                           \
            ++g_failures;                                                                            \
            std::fprintf(stderr, "  FAIL %s:%d  %s == %s  (%lld vs %lld)\n", __FILE__, __LINE__, #a, \
                         #b, static_cast<long long>(_a), static_cast<long long>(_b));                \
        }                                                                                            \
    } while (0)

using Clock = std::chrono::steady_clock;
double ms(Clock::time_point a, Clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

constexpr int WATER_LAYER = 0, STATE_LAYER = 1, SNOW_LAYER = 2;

bool skyOpaqueRole(int r) {
    switch (r) {
        case SBW_AIR: case SBW_WATER: case SBW_ICE: case SBW_SNOW: case SBW_LEAVES: case SBW_ELM_LEAVES:
        case SBW_PINE_LEAVES: case SBW_CYPRESS_LEAVES: case SBW_ROSE: case SBW_WILDGRASS: case SBW_DANDELION:
        case SBW_CACTUS: case SBW_LIMESTONE_STALAGMITE: return false;
        default: return true;
    }
}

std::vector<uint16_t> g_flags;

sbw_gen_config makeConfig(int64_t seed, double hm = 60.0) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = seed;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    if (g_flags.empty()) {
        g_flags.resize(SBW_BLOCK_COUNT);
        for (int i = 0; i < SBW_BLOCK_COUNT; i++) {
            uint16_t f = 0;
            if (i != SBW_AIR) f |= CV_BLOCK_CUBE;
            if (skyOpaqueRole(i)) f |= CV_BLOCK_SKY_OPAQUE | CV_BLOCK_OPAQUE;
            g_flags[static_cast<size_t>(i)] = f;
        }
    }
    c.block_flags = g_flags.data();
    c.block_flags_count = SBW_BLOCK_COUNT;
    c.water_layer = WATER_LAYER;
    c.state_layer = STATE_LAYER;
    c.snow_layer = SNOW_LAYER;
    for (int i = 0; i < 24; i++) c.stalagmite_states[i] = static_cast<uint16_t>(100 + i);
    c.stalagmite_part_states[0] = 200;
    c.stalagmite_part_states[1] = 201;
    c.water_river_values[3] = 77;
    if (hm != 60.0) {
        c.horizontal_m_per_block = hm;
        const double rates[4] = {24.0, 8.0, 12.0, 19.0};
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

/* one generated cube with its layers */
struct CubeData {
    std::vector<uint16_t> ids = std::vector<uint16_t>(CELLS);
    std::vector<uint16_t> layers[3] = {std::vector<uint16_t>(CELLS), std::vector<uint16_t>(CELLS),
                                       std::vector<uint16_t>(CELLS)};
    int ret = 0;
    bool operator==(const CubeData& o) const {
        return ids == o.ids && layers[0] == o.layers[0] && layers[1] == o.layers[1] && layers[2] == o.layers[2];
    }
};

CubeData genCube(const Gen& g, int64_t cx, int64_t cy, int64_t cz) {
    CubeData d;
    uint16_t* ls[CV_MAX_LAYERS] = {};
    for (int i = 0; i < 3; i++) ls[i] = d.layers[i].data();
    d.ret = g.t->generate_cube(g.t->user, cx, cy, cz, d.ids.data(), ls);
    if (d.ret == 0) {
        std::fill(d.ids.begin(), d.ids.end(), d.ids[0]);
        for (auto& l : d.layers) std::fill(l.begin(), l.end(), uint16_t{0});
    }
    return d;
}

struct Key {
    int64_t x, y, z;
    bool operator<(const Key& o) const { return std::tie(x, y, z) < std::tie(o.x, o.y, o.z); }
    bool operator==(const Key& o) const { return x == o.x && y == o.y && z == o.z; }
};

/* ------------------------------------------------------------------- fake cv world for population */
struct FakeWorld {
    std::map<Key, CubeData> cubes;
    Key allowed0{0, 0, 0};  /* populate(c) may only write c..c+1 */
    bool strict = false;
    int violations = 0;
    std::mutex m;

    CubeData* cube(int64_t x, int64_t y, int64_t z) {
        auto it = cubes.find(Key{x >> 5, y >> 5, z >> 5});
        return it == cubes.end() ? nullptr : &it->second;
    }
    static int idx(int64_t x, int64_t y, int64_t z) {
        return CV_CELL_INDEX(static_cast<int>(x & 31), static_cast<int>(y & 31), static_cast<int>(z & 31));
    }
    bool inAllowed(int64_t x, int64_t y, int64_t z) const {
        const int64_t cx = x >> 5, cy = y >> 5, cz = z >> 5;
        return cx >= allowed0.x && cx <= allowed0.x + 1 && cy >= allowed0.y && cy <= allowed0.y + 1
               && cz >= allowed0.z && cz <= allowed0.z + 1;
    }
};

thread_local FakeWorld* tlWorld = nullptr;

int32_t fwGet(cv_world*, int64_t x, int64_t y, int64_t z) {
    CubeData* c = tlWorld->cube(x, y, z);
    return c ? static_cast<int32_t>(c->ids[static_cast<size_t>(FakeWorld::idx(x, y, z))]) : static_cast<int32_t>(CV_ERR_MISSING);
}
int32_t fwSet(cv_world*, int64_t x, int64_t y, int64_t z, uint16_t id) {
    if (tlWorld->strict && !tlWorld->inAllowed(x, y, z)) {
        tlWorld->violations++;
        return CV_ERR_ARG;
    }
    CubeData* c = tlWorld->cube(x, y, z);
    if (!c) return CV_ERR_MISSING;
    uint16_t& v = c->ids[static_cast<size_t>(FakeWorld::idx(x, y, z))];
    const uint16_t prev = v;
    v = id;
    return prev;
}
int32_t fwLayerGet(cv_world*, int32_t layer, int64_t x, int64_t y, int64_t z) {
    CubeData* c = tlWorld->cube(x, y, z);
    return c ? static_cast<int32_t>(c->layers[layer][static_cast<size_t>(FakeWorld::idx(x, y, z))]) : static_cast<int32_t>(CV_ERR_MISSING);
}
int32_t fwLayerSet(cv_world*, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t v) {
    if (tlWorld->strict && !tlWorld->inAllowed(x, y, z)) {
        tlWorld->violations++;
        return CV_ERR_ARG;
    }
    CubeData* c = tlWorld->cube(x, y, z);
    if (!c) return CV_ERR_MISSING;
    uint16_t& s = c->layers[layer][static_cast<size_t>(FakeWorld::idx(x, y, z))];
    const uint16_t prev = s;
    s = v;
    return prev;
}
int32_t fwSurface(cv_world*, int64_t, int64_t, int64_t* out) {
    *out = 0;
    return CV_ERR_MISSING;
}
const cv_gen_api API = {fwGet, fwSet, fwLayerGet, fwLayerSet, fwSurface, {}};

int populateIn(FakeWorld& w, const Gen& g, int64_t cx, int64_t cy, int64_t cz) {
    tlWorld = &w;
    w.allowed0 = Key{cx, cy, cz};
    return g.t->populate(g.t->user, &API, reinterpret_cast<cv_world*>(&w), cx, cy, cz);
}

/* the column band [lo, hi) of cube ys worth generating for a set of column cubes */
void bandOf(const Gen& g, int64_t cx0, int64_t cz0, int n, int64_t& cyLo, int64_t& cyHi) {
    int32_t lo = INT32_MAX, hi = INT32_MIN;
    for (int dx = 0; dx < n; dx++) {
        for (int dz = 0; dz < n; dz++) {
            auto cs = g.g().carved(cx0 + dx, cz0 + dz);
            auto b = g.g().base(cx0 + dx, cz0 + dz);
            for (int c = 0; c < COLS; c++) {
                lo = std::min(lo, cs->carvedH[c]);
                hi = std::max(hi, b->maxTop);
            }
        }
    }
    cyLo = floorDiv64(lo - 40, CE);
    cyHi = floorDiv64(hi + 40, CE) + 1;
}

/* a column cube near the origin that grows trees (for meaningful population tests) */
bool findForest(const Gen& g, int64_t& outCx, int64_t& outCz, int minTrees = 20) {
    for (int r = 0; r < 40; r++) {
        for (int64_t cx = -r; cx <= r; cx++) {
            for (int64_t cz = -r; cz <= r; cz++) {
                if (std::max(std::llabs(cx), std::llabs(cz)) != r) continue;
                int trees = 0;
                std::vector<int32_t> h(CE * CE), w(CE * CE), t(CE * CE);
                std::vector<uint16_t> s(CE * CE);
                sbw_sample_columns(g.t, cx * CE, cz * CE, CE, 1, SBW_SAMPLE_CARVED, h.data(), w.data(), s.data(), t.data());
                for (int v : t) trees += v != 0;
                if (trees >= minTrees) {
                    outCx = cx;
                    outCz = cz;
                    return true;
                }
            }
        }
    }
    return false;
}

/* ================================================================================== tests */

void testBounds() {
    Gen g(makeConfig(1001));
    CubeData bottom = genCube(g, 3, -12, -2);
    int bedrockFloor = 0, bandBedrock = 0;
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            bedrockFloor += bottom.ids[static_cast<size_t>(CV_CELL_INDEX(lx, 0, lz))] == SBW_BEDROCK;
            for (int ly = 1; ly <= 4; ly++) bandBedrock += bottom.ids[static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz))] == SBW_BEDROCK;
            for (int ly = 5; ly < CE; ly++) CHECK(bottom.ids[static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz))] != SBW_BEDROCK);
        }
    }
    CHECK_EQ(bedrockFloor, 1024);
    CHECK(bandBedrock > 1024 && bandBedrock < 3 * 1024); /* hashed band 4/5..1/5 */
    /* outside the world: uniform air */
    for (int64_t cy : {-13, -14, 20, 21, 30}) {
        CubeData d = genCube(g, 0, cy, 0);
        CHECK_EQ(d.ret, 0);
        CHECK_EQ(d.ids[0], SBW_AIR);
    }
    /* nothing non-air at or above max_y even where the top cube intersects the bounds */
    CubeData top = genCube(g, 0, 19, 0);
    CHECK_EQ(top.ret, 0);
    /* deep range is stone-dominated with caves, never above-ground blocks */
    CubeData deep = genCube(g, 1, -8, 1);
    int stone = 0, air = 0, ores = 0, other = 0;
    for (uint16_t v : deep.ids) {
        if (v == SBW_STONE || v == SBW_LIMESTONE) stone++;
        else if (v == SBW_AIR) air++;
        else if (v == SBW_COAL_ORE || v == SBW_IRON_ORE) ores++;
        else other++;
    }
    std::printf("  deep cube (1,-8,1): stone %d air %d ores %d other %d\n", stone, air, ores, other);
    CHECK(stone > 20000);
    CHECK_EQ(other, 0);
}

void testDeterminismAndThreads() {
    const sbw_gen_config cfg = makeConfig(4242);
    Gen a(cfg);
    int64_t fx = 0, fz = 0;
    findForest(a, fx, fz, 5);
    int64_t lo, hi;
    bandOf(a, fx - 1, fz - 1, 3, lo, hi);
    std::vector<Key> keys;
    for (int64_t cx = fx - 1; cx <= fx + 1; cx++)
        for (int64_t cz = fz - 1; cz <= fz + 1; cz++)
            for (int64_t cy = lo; cy < hi; cy++) keys.push_back({cx, cy, cz});
    keys.push_back({fx, -11, fz});
    keys.push_back({fx, -6, fz + 1});
    std::map<Key, CubeData> ref;
    for (const Key& k : keys) ref[k] = genCube(a, k.x, k.y, k.z);

    /* fresh generator, shuffled order, 8 threads */
    Gen b(cfg);
    std::vector<Key> shuffled = keys;
    std::mt19937 rng(7);
    std::shuffle(shuffled.begin(), shuffled.end(), rng);
    std::map<Key, CubeData> got;
    std::mutex m;
    std::atomic<size_t> next{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < 8; t++) {
        pool.emplace_back([&] {
            for (size_t i; (i = next.fetch_add(1)) < shuffled.size();) {
                CubeData d = genCube(b, shuffled[i].x, shuffled[i].y, shuffled[i].z);
                std::lock_guard<std::mutex> lock(m);
                got[shuffled[i]] = std::move(d);
            }
        });
    }
    for (auto& t : pool) t.join();
    int same = 0;
    for (const Key& k : keys) same += ref[k] == got[k];
    CHECK_EQ(same, static_cast<int>(keys.size()));

    /* a tiny cache (constant eviction) changes nothing */
    sbw_gen_config small = cfg;
    small.cache_columns = 1;
    Gen c(small);
    int sameSmall = 0;
    for (size_t i = 0; i < keys.size(); i += 3) sameSmall += ref[keys[i]] == genCube(c, keys[i].x, keys[i].y, keys[i].z);
    CHECK_EQ(sameSmall, static_cast<int>((keys.size() + 2) / 3));
    std::printf("  %zu cubes identical across order/threads/cache\n", keys.size());
}

/* The memo layers (cached worm / ravine walks replayed per footprint, per-layer deep tiles, per-column
 * feature and limestone tiles, cached ore sources, shared raw cubes, column_surface prefetch helpers)
 * only memoise pure functions: they must give exactly the uncached results. */
void testCaches() {
    const sbw_gen_config cfg = makeConfig(777);
    Gen g(cfg);
    Generator& gen = g.g();
    /* 1. a column cube's carve tile (cached walks) == the uncached footprint carve over its columns */
    int compared = 0, carvedBits = 0, mismatches = 0;
    for (int64_t cx : {0, 7, -11}) {
        for (int64_t cz : {0, -5}) {
            auto b = gen.base(cx, cz);
            auto t = gen.carve(cx, cz);
            MaskSet cave, fA, fB;
            gen.carveFootprint(cx * CE, cz * CE, b->h, b->water, gen.cfg.seed, true, cave, fA, fB);
            int64_t lo1, hi1, lo2, hi2;
            cave.yRange(lo1, hi1);
            t->cave.yRange(lo2, hi2);
            const int64_t lo = std::min(lo1, lo2), hi = std::max(hi1, hi2);
            for (int lz = 0; lz < CE; lz++)
                for (int lx = 0; lx < CE; lx++)
                    for (int64_t y = lo; y < hi; y++) {
                        const bool a = cave.test(lx, y, lz), c = t->cave.test(lx, y, lz);
                        mismatches += a != c;
                        carvedBits += a;
                    }
            compared++;
        }
    }
    std::printf("  %d carve tiles == uncached footprint carves (%d carved cells)\n", compared, carvedBits);
    CHECK(carvedBits > 1000);
    CHECK_EQ(mismatches, 0);
    /* 2. deep layer i only ever carves below h - 100 * (i + 1) (what lets shallower cells skip it) */
    int deepBits = 0, outOfBand = 0;
    for (int64_t cx : {1, -3}) {
        for (int64_t cz : {2, 9}) {
            auto b = gen.base(cx, cz);
            for (int i = 0; i < SBW_DEEP_LAYERS; i++) {
                auto d = gen.deepLayer(i, cx, cz);
                int64_t lo, hi;
                d->carve.yRange(lo, hi);
                for (int lz = 0; lz < CE; lz++)
                    for (int lx = 0; lx < CE; lx++)
                        for (int64_t y = lo; y < hi; y++) {
                            if (!d->carve.test(lx, y, lz)) continue;
                            deepBits++;
                            outOfBand += y >= static_cast<int64_t>(b->h[colIdx(lx, lz)]) - 100 * (i + 1);
                        }
            }
        }
    }
    std::printf("  deep layers: %d carved cells, all below their layer's band\n", deepBits);
    CHECK(deepBits > 1000);
    CHECK_EQ(outOfBand, 0);
    /* 3. prefetch helpers on vs off, a contended cold start (24 threads on one small area, shuffled)
     *    vs one thread: identical surfaces and cubes */
    sbw_gen_config off = cfg;
    off.surface_prefetch = SBW_PREFETCH_OFF;
    Gen ref(off);
    Gen hot(cfg);
    std::vector<Key> keys;
    for (int64_t cx = 30; cx < 33; cx++)
        for (int64_t cz = -2; cz < 1; cz++)
            for (int64_t cy = -6; cy < 6; cy++) keys.push_back({cx, cy, cz});
    std::map<Key, CubeData> want;
    std::map<std::pair<int64_t, int64_t>, std::vector<int64_t>> wantSurf;
    for (const Key& k : keys) {
        want[k] = genCube(ref, k.x, k.y, k.z);
        auto& v = wantSurf[{k.x, k.z}];
        if (v.empty()) {
            v.resize(2048);
            ref.t->column_surface(ref.t->user, k.x, k.z, v.data(), v.data() + 1024);
        }
    }
    std::vector<Key> order = keys;
    std::mt19937 rng(99);
    std::shuffle(order.begin(), order.end(), rng);
    std::map<Key, CubeData> got;
    std::map<std::pair<int64_t, int64_t>, std::vector<int64_t>> gotSurf;
    std::mutex m;
    std::atomic<size_t> next{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < 24; t++) {
        pool.emplace_back([&] {
            for (size_t i; (i = next.fetch_add(1)) < order.size();) {
                const Key& k = order[i];
                std::vector<int64_t> v(2048);
                hot.t->column_surface(hot.t->user, k.x, k.z, v.data(), v.data() + 1024);
                CubeData d = genCube(hot, k.x, k.y, k.z);
                std::lock_guard<std::mutex> lock(m);
                got[k] = std::move(d);
                gotSurf[{k.x, k.z}] = std::move(v);
            }
        });
    }
    for (auto& t : pool) t.join();
    int same = 0;
    for (const Key& k : keys) same += want[k] == got[k];
    CHECK_EQ(same, static_cast<int>(keys.size()));
    int surfSame = 0;
    for (auto& [k, v] : wantSurf) surfSame += gotSurf[k] == v;
    CHECK_EQ(surfSame, static_cast<int>(wantSurf.size()));
    std::printf("  %zu cubes + %zu column surfaces: 24 threads cold with prefetch == 1 thread without\n", keys.size(),
                wantSurf.size());
}

void testCarveSeams() {
    /* the carvers are fields: footprints offset by half a cube carve the shared columns identically */
    Gen g(makeConfig(777));
    Generator& gen = g.g();
    int checkedBits = 0, mismatches = 0, regions = 0;
    for (int64_t X0 : {INT64_C(0), INT64_C(-96), INT64_C(512)}) {
        for (int64_t Z0 : {INT64_C(0), INT64_C(160)}) {
            int32_t hA[COLS], wA[COLS], hB[COLS], wB[COLS];
            for (int lz = 0; lz < CE; lz++) {
                for (int lx = 0; lx < CE; lx++) {
                    hA[colIdx(lx, lz)] = gen.heightAt(X0 + lx, Z0 + lz);
                    wA[colIdx(lx, lz)] = gen.waterAt(X0 + lx, Z0 + lz);
                    hB[colIdx(lx, lz)] = gen.heightAt(X0 + 16 + lx, Z0 + 16 + lz);
                    wB[colIdx(lx, lz)] = gen.waterAt(X0 + 16 + lx, Z0 + 16 + lz);
                }
            }
            MaskSet caveA, fA1, fA2, caveB, fB1, fB2;
            gen.carveFootprint(X0, Z0, hA, wA, gen.cfg.seed, true, caveA, fA1, fA2);
            gen.carveFootprint(X0 + 16, Z0 + 16, hB, wB, gen.cfg.seed, true, caveB, fB1, fB2);
            int64_t lo1, hi1, lo2, hi2;
            caveA.yRange(lo1, hi1);
            caveB.yRange(lo2, hi2);
            const int64_t lo = std::min(lo1, lo2), hi = std::max(hi1, hi2);
            for (int lz = 16; lz < CE; lz++) {
                for (int lx = 16; lx < CE; lx++) {
                    for (int64_t y = lo; y < hi; y++) {
                        const bool a1 = caveA.test(lx, y, lz), b1 = caveB.test(lx - 16, y, lz - 16);
                        const bool a2 = fA1.test(lx, y, lz) || fA2.test(lx, y, lz);
                        const bool b2 = fB1.test(lx - 16, y, lz - 16) || fB2.test(lx - 16, y, lz - 16);
                        mismatches += (a1 != b1) + (a2 != b2);
                        checkedBits += a1;
                    }
                }
            }
            regions++;
        }
    }
    std::printf("  %d footprint pairs, %d carved cells compared\n", regions, checkedBits);
    CHECK(checkedBits > 1000);
    CHECK_EQ(mismatches, 0);

    /* cube borders: the batch fill equals the point query on both sides of every face */
    int pointChecks = 0, pointMismatch = 0;
    int64_t fx = 0, fz = 0;
    findForest(g, fx, fz, 1);
    int64_t lo, hi;
    bandOf(g, fx, fz, 1, lo, hi);
    for (int64_t cy = lo; cy < std::min(hi, lo + 3); cy++) {
        CubeData d = genCube(g, fx, cy, fz);
        CubeData n = genCube(g, fx + 1, cy, fz);
        (void) n;
        for (int ly = 0; ly < CE; ly += 3) {
            for (int lz = 0; lz < CE; lz += 2) {
                for (int lx : {0, 31}) {
                    const int64_t x = fx * CE + lx, y = cy * CE + ly, z = fz * CE + lz;
                    uint16_t v = d.ids[static_cast<size_t>(CV_CELL_INDEX(lx, ly, lz))];
                    const uint16_t p = gen.terrainBlockAt(x, y, z, true);
                    /* ores/limestone/stalagmites are post-terrain: compare stone classes */
                    auto cls = [](uint16_t b) {
                        return (b == SBW_COAL_ORE || b == SBW_IRON_ORE || b == SBW_LIMESTONE || b == SBW_CRYSTAL) ? static_cast<uint16_t>(SBW_STONE) : b;
                    };
                    if (v == SBW_LIMESTONE_STALAGMITE) continue;
                    pointMismatch += cls(v) != cls(p) && !(v == SBW_AIR && p == SBW_STONE);
                    pointChecks++;
                }
            }
        }
    }
    std::printf("  %d border cells: batch == point\n", pointChecks);
    CHECK_EQ(pointMismatch, 0);
}

void runPopulation(const Gen& g, FakeWorld& w, const std::vector<Key>& order) {
    for (const Key& k : order) populateIn(w, g, k.x, k.y, k.z);
}

void testPopulation() {
    Gen g(makeConfig(31337));
    int64_t fx = 0, fz = 0;
    CHECK(findForest(g, fx, fz, 25));
    int64_t lo, hi;
    bandOf(g, fx - 1, fz - 1, 4, lo, hi);
    /* cubes cx in [fx-1, fx+3), cz same, cy in [lo, hi); populate every c with c..c+1 present */
    FakeWorld base;
    for (int64_t cx = fx - 1; cx < fx + 3; cx++)
        for (int64_t cz = fz - 1; cz < fz + 3; cz++)
            for (int64_t cy = lo; cy < hi; cy++) base.cubes[{cx, cy, cz}] = genCube(g, cx, cy, cz);
    std::vector<Key> calls;
    for (int64_t cx = fx - 1; cx < fx + 2; cx++)
        for (int64_t cz = fz - 1; cz < fz + 2; cz++)
            for (int64_t cy = lo; cy < hi - 1; cy++) calls.push_back({cx, cy, cz});

    auto snapshot = [](FakeWorld& w) {
        std::map<Key, CubeData> out;
        for (auto& [k, v] : w.cubes) out[k] = v;
        return out;
    };
    std::map<Key, CubeData> ref;
    {
        FakeWorld w;
        w.cubes = base.cubes;
        w.strict = true;
        runPopulation(g, w, calls);
        CHECK_EQ(w.violations, 0);
        ref = snapshot(w);
        /* idempotent */
        runPopulation(g, w, calls);
        int same = 0;
        for (auto& [k, v] : w.cubes) same += ref[k] == v;
        CHECK_EQ(same, static_cast<int>(ref.size()));
    }
    /* what did population place? */
    int logs = 0, leaves = 0, plants = 0, changed = 0;
    for (auto& [k, v] : ref) {
        const CubeData& b = base.cubes[k];
        for (int i = 0; i < CELLS; i++) {
            if (v.ids[static_cast<size_t>(i)] == b.ids[static_cast<size_t>(i)]) continue;
            changed++;
            const uint16_t id = v.ids[static_cast<size_t>(i)];
            logs += id == SBW_WOOD || id == SBW_ELM_WOOD_LOG || id == SBW_PINE || id == SBW_CYPRESS_LOG;
            leaves += id == SBW_LEAVES || id == SBW_ELM_LEAVES || id == SBW_PINE_LEAVES || id == SBW_CYPRESS_LEAVES;
            plants += id == SBW_ROSE || id == SBW_DANDELION || id == SBW_WILDGRASS || id == SBW_SNOW || id == SBW_CACTUS;
        }
    }
    std::printf("  %zu populate calls: %d cells changed (logs %d, leaves %d, plants/snow %d)\n", calls.size(), changed,
                logs, leaves, plants);
    CHECK(logs > 50);
    CHECK(leaves > 300);

    for (int trial = 0; trial < 3; trial++) {
        FakeWorld w;
        w.cubes = base.cubes;
        std::vector<Key> order = calls;
        std::mt19937 rng(static_cast<unsigned>(100 + trial));
        std::shuffle(order.begin(), order.end(), rng);
        runPopulation(g, w, order);
        int same = 0;
        for (auto& [k, v] : w.cubes) same += ref[k] == v;
        CHECK_EQ(same, static_cast<int>(ref.size()));
    }
    /* parallel: workers own disjoint 2x2x2 sets at a time (as the engine's streamer does) */
    {
        FakeWorld w;
        w.cubes = base.cubes;
        std::vector<Key> order = calls;
        std::mt19937 rng(999);
        std::shuffle(order.begin(), order.end(), rng);
        std::mutex lockM;
        std::set<Key> held;
        std::atomic<size_t> next{0};
        std::vector<std::thread> pool;
        std::vector<Key> pending = order;
        std::mutex pm;
        Gen g2(makeConfig(31337)); /* a second, cold generator */
        for (int t = 0; t < 6; t++) {
            pool.emplace_back([&] {
                for (;;) {
                    Key k{};
                    bool got = false, empty = false;
                    {
                        std::lock_guard<std::mutex> lock(pm);
                        if (pending.empty()) {
                            empty = true;
                        } else {
                            for (size_t i = 0; i < pending.size(); i++) {
                                const Key c = pending[i];
                                bool free = true;
                                for (int d = 0; d < 8 && free; d++) {
                                    free = held.count({c.x + (d & 1), c.y + ((d >> 1) & 1), c.z + (d >> 2)}) == 0;
                                }
                                if (!free) continue;
                                for (int d = 0; d < 8; d++) held.insert({c.x + (d & 1), c.y + ((d >> 1) & 1), c.z + (d >> 2)});
                                k = c;
                                pending.erase(pending.begin() + static_cast<long>(i));
                                got = true;
                                break;
                            }
                        }
                    }
                    if (empty) return;
                    if (!got) {
                        std::this_thread::yield();
                        continue;
                    }
                    tlWorld = &w;
                    g2.t->populate(g2.t->user, &API, reinterpret_cast<cv_world*>(&w), k.x, k.y, k.z);
                    std::lock_guard<std::mutex> lock(pm);
                    for (int d = 0; d < 8; d++) held.erase({k.x + (d & 1), k.y + ((d >> 1) & 1), k.z + (d >> 2)});
                }
            });
        }
        for (auto& t : pool) t.join();
        (void) next;
        (void) lockM;
        int same = 0;
        for (auto& [k, v] : w.cubes) same += ref[k] == v;
        CHECK_EQ(same, static_cast<int>(ref.size()));
    }
}

void testUniformFastPath() {
    Gen g(makeConfig(55));
    Generator& gen = g.g();
    int uniform = 0, verified = 0, bad = 0;
    for (int64_t cx = -3; cx <= 3; cx += 3) {
        for (int64_t cz = -3; cz <= 3; cz += 3) {
            for (int64_t cy = -12; cy < 20; cy++) {
                CubeData d = genCube(g, cx, cy, cz);
                if (d.ret != 0) continue;
                uniform++;
                /* the full computation agrees on a dense lattice of cells */
                for (int ly = 0; ly < CE; ly += 5)
                    for (int lz = 0; lz < CE; lz += 3)
                        for (int lx = 0; lx < CE; lx += 3) {
                            const uint16_t p = gen.terrainBlockAt(cx * CE + lx, cy * CE + ly, cz * CE + lz, true);
                            bad += p != d.ids[0];
                            verified++;
                        }
            }
        }
    }
    std::printf("  %d uniform cubes, %d cells re-derived\n", uniform, verified);
    CHECK(uniform > 50);
    CHECK_EQ(bad, 0);
}

void testColumnSurface() {
    for (int64_t seed : {INT64_C(2024), INT64_C(9)}) {
        Gen g(makeConfig(seed));
        int64_t fx = 0, fz = 0;
        CHECK(findForest(g, fx, fz, 20));
        int64_t lo, hi;
        bandOf(g, fx - 1, fz - 1, 3, lo, hi);
        FakeWorld w;
        for (int64_t cx = fx - 1; cx <= fx + 1; cx++)
            for (int64_t cz = fz - 1; cz <= fz + 1; cz++)
                for (int64_t cy = lo; cy < hi; cy++) w.cubes[{cx, cy, cz}] = genCube(g, cx, cy, cz);
        /* every cube above the band is uniform air */
        for (int64_t cy = hi; cy < hi + 3; cy++) CHECK_EQ(genCube(g, fx, cy, fz).ret, 0);
        for (int64_t cx = fx - 1; cx <= fx; cx++)
            for (int64_t cz = fz - 1; cz <= fz; cz++)
                for (int64_t cy = lo; cy < hi - 1; cy++) populateIn(w, g, cx, cy, cz);
        int64_t sky[COLS], top[COLS];
        CHECK_EQ(g.t->column_surface(g.t->user, fx, fz, sky, top), 1);
        int topBad = 0, skyBad = 0, treesTop = 0;
        for (int lz = 0; lz < CE; lz++) {
            for (int lx = 0; lx < CE; lx++) {
                const int64_t x = fx * CE + lx, z = fz * CE + lz;
                int64_t t = INT64_MIN, s = INT64_MIN;
                for (int64_t y = hi * CE - 1; y >= lo * CE; y--) {
                    const uint16_t id = w.cube(x, y, z)->ids[static_cast<size_t>(FakeWorld::idx(x, y, z))];
                    if (id != SBW_AIR && t == INT64_MIN) t = y + 1;
                    if (skyOpaqueRole(id)) {
                        s = y + 1;
                        break;
                    }
                }
                const int col = colIdx(lx, lz);
                if (t != top[col]) {
                    if (topBad < 5) std::fprintf(stderr, "  top (%lld,%lld): world %lld surface %lld\n",
                                                 static_cast<long long>(x), static_cast<long long>(z),
                                                 static_cast<long long>(t), static_cast<long long>(top[col]));
                    topBad++;
                }
                if (s != sky[col]) {
                    if (skyBad < 5) std::fprintf(stderr, "  sky (%lld,%lld): world %lld surface %lld\n",
                                                 static_cast<long long>(x), static_cast<long long>(z),
                                                 static_cast<long long>(s), static_cast<long long>(sky[col]));
                    skyBad++;
                }
                treesTop += top[col] > g.g().carved(fx, fz)->carvedH[col] + 3;
            }
        }
        std::printf("  seed %lld column (%lld,%lld): %d columns with canopy above the ground\n",
                    static_cast<long long>(seed), static_cast<long long>(fx), static_cast<long long>(fz), treesTop);
        CHECK_EQ(topBad, 0);
        CHECK_EQ(skyBad, 0);
        CHECK(treesTop > 0);
    }
}

/* SBW_KIND_PROFILE: a synthetic profile upcall with lakes, a river void and river flow */
int32_t synthProfile(void* user, int64_t cx, int64_t cz, sbw_column_profile* out) {
    auto* calls = static_cast<std::atomic<int>*>(user);
    calls->fetch_add(1);
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int c = lz * CE + lx;
            const double x = static_cast<double>(cx * CE + lx), z = static_cast<double>(cz * CE + lz);
            const int h = 70 + static_cast<int>(12.0 * std::sin(x / 23.0) * std::cos(z / 31.0));
            out->height[c] = h;
            out->water[c] = h < 66 ? 67 : SBW_NO_WATER;
            out->biome[c] = static_cast<uint8_t>(h < 66 ? SBW_PLAINS : (h > 78 ? SBW_TAIGA : SBW_MEADOW));
            const bool river = static_cast<int64_t>(x) % 64 >= 20 && static_cast<int64_t>(x) % 64 < 24;
            out->river_floor[c] = river ? h - 9 : 0;
            out->river_roof[c] = river ? h - 4 : 0;
            if (river) out->water[c] = h - 6;
            out->river_flow[c] = static_cast<int8_t>(river ? 3 : -1);
        }
    }
    return 1;
}

void testProfileKind() {
    std::atomic<int> calls{0};
    sbw_gen_config cfg = makeConfig(5);
    cfg.kind = SBW_KIND_PROFILE;
    cfg.column_fn = synthProfile;
    cfg.column_user = &calls;
    cfg.flags &= ~SBW_FLAG_PEAK_DAMP;
    Gen g(cfg);
    CHECK(g.t != nullptr);
    CubeData d = genCube(g, 0, 2, 0); /* y 64..96 */
    CHECK_EQ(d.ret, 1);
    int voidStone = 0, voidWater = 0, riverMarks = 0, waterSources = 0;
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 20; lx < 24; lx++) {
            const int h = g.g().heightAt(lx, lz);
            for (int y : {h - 9, h - 4}) {
                if (y < 64 || y >= 96) continue;
                voidStone += d.ids[static_cast<size_t>(CV_CELL_INDEX(lx, y - 64, lz))] == SBW_STONE;
            }
            for (int y = h - 8; y < h - 6; y++) {
                if (y < 64 || y >= 96) continue;
                voidWater += d.ids[static_cast<size_t>(CV_CELL_INDEX(lx, y - 64, lz))] == SBW_WATER;
            }
        }
    }
    for (int i = 0; i < CELLS; i++) {
        riverMarks += d.layers[WATER_LAYER][static_cast<size_t>(i)] == 77;
        waterSources += d.layers[WATER_LAYER][static_cast<size_t>(i)] == 16;
        if (d.ids[static_cast<size_t>(i)] == SBW_WATER) CHECK(d.layers[WATER_LAYER][static_cast<size_t>(i)] != 0);
    }
    std::printf("  profile kind: %d upcalls, void stone %d, void water %d, river marks %d, sources %d\n",
                calls.load(), voidStone, voidWater, riverMarks, waterSources);
    CHECK(calls.load() > 0);
    CHECK(voidStone > 0);
    CHECK(voidWater > 0);
    CHECK(riverMarks > 0);
    /* deterministic and identical through a fresh generator */
    Gen g2(cfg);
    CHECK(genCube(g2, 0, 2, 0) == d);
}

void testPointEqualsTile() {
    int checked = 0, bad = 0;
    for (double hm : {60.0, 30.0}) {
        Gen g(makeConfig(606, hm));
        std::mt19937_64 rng(1);
        for (int i = 0; i < 3000; i++) {
            const int64_t x = static_cast<int64_t>(rng() % 200000) - 100000, z = static_cast<int64_t>(rng() % 200000) - 100000;
            int32_t h, w;
            int b;
            uint16_t s;
            standardColumnPoint(g.g(), x, z, h, w, b, s);
            auto t = g.g().base(x >> 5, z >> 5);
            const int c = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
            bad += h != t->h[c] || w != t->water[c] || b != t->biome[c] || s != t->surface[c];
            checked++;
        }
    }
    std::printf("  %d columns: point == tile\n", checked);
    CHECK_EQ(bad, 0);
}

/* height distribution of a preset over a large area */
struct Hist {
    std::vector<int32_t> v;
    void add(int32_t x) { v.push_back(x); }
    int32_t pct(double p) {
        std::sort(v.begin(), v.end());
        return v[static_cast<size_t>(p * static_cast<double>(v.size() - 1))];
    }
};

void heightReport(const char* name, const Gen& g, int64_t span, int stride, Hist& hist, int& oceanPct) {
    const int count = static_cast<int>(span / stride);
    std::vector<int32_t> h(static_cast<size_t>(count * count)), w(static_cast<size_t>(count * count));
    sbw_sample_columns(g.t, -span / 2, -span / 2, count, stride, 0, h.data(), w.data(), nullptr, nullptr);
    int ocean = 0;
    for (size_t i = 0; i < h.size(); i++) {
        hist.add(h[i]);
        ocean += w[i] != SBW_NO_WATER && w[i] > h[i];
    }
    oceanPct = static_cast<int>(100 * ocean / static_cast<int>(h.size()));
    std::printf("  %s (%lld^2 blocks, stride %d): min %d p1 %d p10 %d p50 %d p90 %d p99 %d max %d, submerged %d%%\n",
                name, static_cast<long long>(span), stride, hist.pct(0), hist.pct(0.01), hist.pct(0.1), hist.pct(0.5),
                hist.pct(0.9), hist.pct(0.99), hist.pct(1.0), oceanPct);
    /* histogram in 32-block bins */
    std::map<int, int> bins;
    for (int32_t x : hist.v) bins[static_cast<int>(floorDiv64(x, 32))]++;
    std::printf("    bins(32):");
    for (auto& [b, n] : bins) std::printf(" [%d..%d):%.1f%%", b * 32, b * 32 + 32, 100.0 * n / static_cast<double>(hist.v.size()));
    std::printf("\n");
}

void testScalePresets() {
    Hist h4, h2;
    int o4 = 0, o2 = 0;
    Gen g4(makeConfig(20261117));
    Gen g2(makeConfig(20261117, 30.0));
    heightReport("1:4", g4, 32768, 64, h4, o4);
    heightReport("1:2", g2, 65536, 128, h2, o2);
    for (Hist* h : {&h4, &h2}) {
        CHECK(h->pct(0) > -384 + 8);
        CHECK(h->pct(1.0) < 640);
    }
    /* 1:2 keeps the shape at twice the size: similar ocean share, twice the relief above sea */
    CHECK(std::abs(o4 - o2) <= 8);
    CHECK(h2.pct(0.99) - 64 > static_cast<int>(1.5 * (h4.pct(0.99) - 64)));
    CHECK(64 - h2.pct(0.0) > static_cast<int>(1.5 * (64 - h4.pct(0.0))));
    /* the curve itself: 4 km ~ y 380 and 5 km ~ y 435, -4 km ~ y -100 at 1:2 */
    const HeightCurve& c2 = g2.g().curve;
    std::printf("  1:2 curve: -4000m %.1f, 2000m %.1f, 4000m %.1f, 5000m %.1f, 7000m %.1f\n", c2.blocks(-4000),
                c2.blocks(2000), c2.blocks(4000), c2.blocks(5000), c2.blocks(7000));
    const HeightCurve& c4 = g4.g().curve;
    std::printf("  1:4 curve: -4000m %.1f, 2000m %.1f, 4000m %.1f, 5000m %.1f, 7000m %.1f\n", c4.blocks(-4000),
                c4.blocks(2000), c4.blocks(4000), c4.blocks(5000), c4.blocks(7000));
    CHECK(std::abs(c2.blocks(4000) - 380) < 15);
    CHECK(std::abs(c2.blocks(5000) - 435) < 15);
    CHECK(std::abs(c2.blocks(-4000) + 100) < 15);
    CHECK(c2.blocks(7000) < 640);
    /* a 1:2 cube generates inside the bounds */
    CubeData d = genCube(g2, 0, -12, 0);
    CHECK_EQ(d.ids[0], SBW_BEDROCK);
    /* biome mix is preserved (biomes key on the reference curve) */
    std::map<int, int> b4, b2;
    for (int i = 0; i < 4000; i++) {
        const int64_t x = (i % 64) * 211 - 6000, z = (i / 64) * 211 - 6000;
        int32_t h, w;
        int bA, bB;
        uint16_t s;
        standardColumnPoint(g4.g(), x, z, h, w, bA, s);
        standardColumnPoint(g2.g(), 2 * x, 2 * z, h, w, bB, s);
        b4[bA]++;
        b2[bB]++;
    }
    int diff = 0;
    for (int bi = 0; bi < SBW_BIOME_COUNT; bi++) diff += std::abs(b4[bi] - b2[bi]);
    std::printf("  biome mix difference 1:4 vs 1:2 at matching shape positions: %.1f%%\n", 50.0 * diff / 4000.0);
    CHECK(diff < 4000 / 5);
}

void testThroughput() {
    Gen g(makeConfig(8086));
    /* 4 x 4 column cubes, every cube of every column, cold caches, 1 thread */
    int64_t lo = -12, hi = 20;
    auto t0 = Clock::now();
    int total = 0, uniform = 0;
    std::vector<double> perCube;
    double colBuild = 0, deepBuild = 0, baseBuild = 0;
    for (int64_t cx = 0; cx < 4; cx++) {
        for (int64_t cz = 0; cz < 4; cz++) {
            auto b0 = Clock::now();
            g.g().base(cx, cz);
            baseBuild += ms(b0, Clock::now());
            auto c0 = Clock::now();
            g.g().carve(cx, cz);
            g.g().carved(cx, cz);
            colBuild += ms(c0, Clock::now());
            auto d0 = Clock::now();
            g.g().deep(cx, cz);
            deepBuild += ms(d0, Clock::now());
            for (int64_t cy = lo; cy < hi; cy++) {
                auto a = Clock::now();
                CubeData d = genCube(g, cx, cy, cz);
                perCube.push_back(ms(a, Clock::now()));
                total++;
                uniform += d.ret == 0;
            }
        }
    }
    const double all = ms(t0, Clock::now());
    std::sort(perCube.begin(), perCube.end());
    std::printf("  1 thread, 16 columns x 32 cubes cold: %.0f ms total, %.1f ms per column-equivalent (32x32x1024)\n",
                all, all / 16.0);
    std::printf("    per column: base %.2f ms, top-layer carve + carved surface %.1f ms, deep layers %.1f ms; cubes: "
                "p50 %.3f ms, p90 %.3f ms, max %.1f ms, %d/%d uniform\n", baseBuild / 16.0, colBuild / 16.0,
                deepBuild / 16.0, perCube[perCube.size() / 2], perCube[perCube.size() * 9 / 10], perCube.back(),
                uniform, total);
    std::printf("    per old 16x16x256 chunk area equivalent: %.1f ms (4 old chunks per column cube)\n", all / 16.0 / 4.0);
    /* warm caches, 8 threads */
    std::vector<Key> keys;
    for (int64_t cx = 0; cx < 4; cx++)
        for (int64_t cz = 0; cz < 4; cz++)
            for (int64_t cy = lo; cy < hi; cy++) keys.push_back({cx, cy, cz});
    std::atomic<size_t> next{0};
    auto t1 = Clock::now();
    std::vector<std::thread> pool;
    for (int t = 0; t < 8; t++) {
        pool.emplace_back([&] {
            for (size_t i; (i = next.fetch_add(1)) < keys.size();) genCube(g, keys[i].x, keys[i].y, keys[i].z);
        });
    }
    for (auto& t : pool) t.join();
    const double warm = ms(t1, Clock::now());
    std::printf("  8 threads, warm column caches: %.1f us per cube (%zu cubes in %.0f ms)\n",
                warm * 1000.0 / static_cast<double>(keys.size()), keys.size(), warm);
    /* cold, 8 threads, new area */
    Gen g2(makeConfig(8087));
    std::vector<Key> keys2;
    for (int64_t cx = 10; cx < 18; cx++)
        for (int64_t cz = 10; cz < 18; cz++)
            for (int64_t cy = lo; cy < hi; cy++) keys2.push_back({cx, cy, cz});
    next = 0;
    auto t2 = Clock::now();
    pool.clear();
    for (int t = 0; t < 8; t++) {
        pool.emplace_back([&] {
            for (size_t i; (i = next.fetch_add(1)) < keys2.size();) genCube(g2, keys2[i].x, keys2[i].y, keys2[i].z);
        });
    }
    for (auto& t : pool) t.join();
    const double cold8 = ms(t2, Clock::now());
    std::printf("  8 threads, cold, 64 columns: %.1f ms per column-equivalent wall, %.1f us per cube wall\n",
                cold8 / 64.0, cold8 * 1000.0 / static_cast<double>(keys2.size()));
    /* population cost */
    FakeWorld w;
    int64_t fx = 0, fz = 0;
    findForest(g, fx, fz, 10);
    int64_t blo, bhi;
    bandOf(g, fx, fz, 2, blo, bhi);
    for (int64_t cx = fx; cx < fx + 2; cx++)
        for (int64_t cz = fz; cz < fz + 2; cz++)
            for (int64_t cy = blo; cy < bhi; cy++) w.cubes[{cx, cy, cz}] = genCube(g, cx, cy, cz);
    auto t3 = Clock::now();
    int pops = 0;
    for (int64_t cy = blo; cy < bhi - 1; cy++, pops++) populateIn(w, g, fx, cy, fz);
    std::printf("  populate: %.2f ms per call (%d calls over a forest column)\n", ms(t3, Clock::now()) / pops, pops);
    CHECK(total == 16 * 32);
}

void probe() {
    Gen g(makeConfig(20261117));
    Generator& gen = g.g();
    /* cross-section x in [0, 128), z = 40, y in [-384, 300) every 4 */
    std::printf("cross-section z=40 (every 2nd x, every 6th y):\n");
    const int64_t z = 40;
    for (int64_t y = 260; y >= -384; y -= 6) {
        std::printf("%5lld ", static_cast<long long>(y));
        for (int64_t x = 0; x < 256; x += 2) {
            const uint16_t id = gen.terrainBlockAt(x, y, z, true);
            char ch = '?';
            switch (id) {
                case SBW_AIR: ch = ' '; break;
                case SBW_WATER: ch = '~'; break;
                case SBW_STONE: ch = '#'; break;
                case SBW_BEDROCK: ch = 'B'; break;
                case SBW_DIRT: case SBW_GRASS: case SBW_SNOWY_DIRT: case SBW_SWAMPY_GRASS: ch = 'd'; break;
                case SBW_SAND: case SBW_SANDSTONE: case SBW_RED_SAND: case SBW_RED_SANDSTONE: ch = 's'; break;
                case SBW_ICE: ch = 'i'; break;
                case SBW_MAGMA: ch = 'm'; break;
                default: ch = '+'; break;
            }
            std::putchar(ch);
        }
        std::putchar('\n');
    }
}

/* Non-gating sanity diff against a Java dump (scratch harness): file = int n, then per chunk
 * (cx0..cx0+n, cz0..cz0+n) 256 x 16 x 16 modified-UTF block names in y, z, x order. */
int compareJava(const char* path, int64_t seed, int64_t cx0, int64_t cz0) {
    static const char* NAMES[SBW_BLOCK_COUNT] = {
        "AIR", "WATER", "STONE", "BEDROCK", "MAGMA", "DIRT", "GRASS", "SAND", "RED_SAND", "SANDSTONE",
        "RED_SANDSTONE", "GRAVEL", "CLAY", "SNOWY_DIRT", "SWAMPY_GRASS", "ICE", "SNOW", "COAL_ORE", "IRON_ORE",
        "CRYSTAL", "LIMESTONE", "LIMESTONE_STALAGMITE", "WOOD", "LEAVES", "ELM_WOOD_LOG", "ELM_LEAVES", "PINE",
        "PINE_LEAVES", "CYPRESS_LOG", "CYPRESS_LEAVES", "ROSE", "WILDGRASS", "DANDELION", "CACTUS",
        "RED_SAND_COBBLESTONE"};
    std::FILE* f = std::fopen(path, "rb");
    if (!f) return 2;
    auto readInt = [&] {
        unsigned char b[4];
        if (std::fread(b, 1, 4, f) != 4) return -1;
        return (b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3];
    };
    const int n = readInt();
    Gen g(makeConfig(seed));
    std::map<Key, CubeData> cubes;
    auto mine = [&](int64_t x, int64_t y, int64_t z) {
        const Key k{x >> 5, y >> 5, z >> 5};
        auto it = cubes.find(k);
        if (it == cubes.end()) it = cubes.emplace(k, genCube(g, k.x, k.y, k.z)).first;
        return it->second.ids[static_cast<size_t>(FakeWorld::idx(x, y, z))];
    };
    auto cls = [](int r) {
        if (r == SBW_COAL_ORE || r == SBW_IRON_ORE || r == SBW_CRYSTAL || r == SBW_LIMESTONE || r == SBW_LIMESTONE_STALAGMITE) return static_cast<int>(SBW_STONE);
        return r;
    };
    double total = 0, diff = 0, solidDiff = 0, totalHi = 0, diffHi = 0, surfaceCols = 0, surfaceSame = 0;
    std::map<std::pair<int, int>, long> pairs;
    for (int64_t cx = cx0; cx < cx0 + n; cx++) {
        for (int64_t cz = cz0; cz < cz0 + n; cz++) {
            std::vector<int> java(65536);
            for (int i = 0; i < 65536; i++) {
                unsigned char lb[2];
                if (std::fread(lb, 1, 2, f) != 2) return 3;
                const int len = (lb[0] << 8) | lb[1];
                std::string name(static_cast<size_t>(len), ' ');
                if (std::fread(name.data(), 1, static_cast<size_t>(len), f) != static_cast<size_t>(len)) return 3;
                int role = -1;
                for (int r = 0; r < SBW_BLOCK_COUNT; r++) if (name == NAMES[r]) role = r;
                java[static_cast<size_t>(i)] = role;
            }
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int topJ = -1, topM = -1;
                    for (int y = 0; y < 256; y++) {
                        const int j = java[static_cast<size_t>((y * 16 + z) * 16 + x)];
                        const int m = mine(cx * 16 + x, y, cz * 16 + z);
                        const int a = cls(j);
                        /* crystals are a population feature the Java terrain dump lacks: they sit in
                         * MAGMA / STONE / RED_SAND */
                        const int b = (m == SBW_CRYSTAL && (j == SBW_MAGMA || j == SBW_STONE || j == SBW_RED_SAND)) ? a : cls(m);
                        total++;
                        if (a != b) {
                            diff++;
                            pairs[{a, b}]++;
                            static int shown = 0;
                            if (getenv("SBW_SHOW_DIFF") != nullptr && shown < 12 && (a == SBW_MAGMA || b == SBW_MAGMA) && y > 8) {
                                shown++;
                                const int64_t wx = cx * 16 + x, wz = cz * 16 + z;
                                auto bt = g.g().base(wx >> 5, wz >> 5);
                                const int col = colIdx(static_cast<int>(wx & 31), static_cast<int>(wz & 31));
                                std::printf("  diff (%lld,%d,%lld) java %s native %s (raw %s) biome %d h %d magmaAt %d\n",
                                            static_cast<long long>(wx), y, static_cast<long long>(wz), NAMES[a], NAMES[b], NAMES[m],
                                            bt->biome[col], bt->h[col], g.g().magmaAt(wx, y, wz) ? 1 : 0);
                            }
                        }
                        const bool sa = a != SBW_AIR && a != SBW_WATER, sb = b != SBW_AIR && b != SBW_WATER;
                        solidDiff += sa != sb;
                        if (y >= 10) {
                            totalHi++;
                            diffHi += a != b;
                        }
                        if (a != SBW_AIR) topJ = y;
                        if (b != SBW_AIR) topM = y;
                    }
                    surfaceCols++;
                    surfaceSame += topJ == topM;
                }
            }
        }
    }
    std::fclose(f);
    std::printf("seed %lld chunks (%lld,%lld)+%d: cells %.0f, class diff %.3f%% (y>=10: %.3f%%), solid/open diff %.3f%%, "
                "column top equal %.2f%%\n", static_cast<long long>(seed), static_cast<long long>(cx0),
                static_cast<long long>(cz0), n, total, 100.0 * diff / total, 100.0 * diffHi / totalHi,
                100.0 * solidDiff / total, 100.0 * surfaceSame / surfaceCols);
    std::vector<std::pair<long, std::pair<int, int>>> top;
    for (auto& [k, v] : pairs) top.push_back({v, k});
    std::sort(top.rbegin(), top.rend());
    for (size_t i = 0; i < top.size() && i < 8; i++) {
        std::printf("  java %s -> native %s: %ld\n", top[i].second.first >= 0 ? NAMES[top[i].second.first] : "?",
                    top[i].second.second >= 0 ? NAMES[top[i].second.second] : "?", top[i].first);
    }
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    if (argc == 6 && std::string(argv[1]) == "--compare") {
        return compareJava(argv[2], std::stoll(argv[3]), std::stoll(argv[4]), std::stoll(argv[5]));
    }
    std::vector<std::pair<std::string, void (*)()>> tests = {
        {"bounds", testBounds},
        {"determinism", testDeterminismAndThreads},
        {"seams", testCarveSeams},
        {"caches", testCaches},
        {"population", testPopulation},
        {"uniform", testUniformFastPath},
        {"surface", testColumnSurface},
        {"profile", testProfileKind},
        {"pointtile", testPointEqualsTile},
        {"scale", testScalePresets},
        {"throughput", testThroughput},
    };
    std::set<std::string> only;
    for (int i = 1; i < argc; i++) {
        if (std::string(argv[i]) == "--probe") {
            probe();
            return 0;
        }
        only.insert(argv[i]);
    }
    for (auto& [name, fn] : tests) {
        if (!only.empty() && only.count(name) == 0) continue;
        const int before = g_failures;
        auto t0 = Clock::now();
        std::printf("[%s]\n", name.c_str());
        std::fflush(stdout);
        fn();
        std::printf("[%s] %s (%.0f ms)\n", name.c_str(), g_failures == before ? "ok" : "FAILED", ms(t0, Clock::now()));
        std::fflush(stdout);
    }
    std::printf("%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
