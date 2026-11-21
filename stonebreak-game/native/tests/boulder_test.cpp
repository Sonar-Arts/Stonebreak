/* Boulder feature tests (SBW_FLAG_BOULDERS, src/boulders.cpp + src/rocks.cpp).
 *
 *   worldgen_boulder_test              all tests
 *   worldgen_boulder_test <name>...    only those tests
 *   worldgen_boulder_test --bake <out.sbrb> [perClass]   write the placeholder rock bank (id 1)
 */
#include "gen.hpp"
#include "rocks.hpp"
#include "stonebreak/worldgen.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <random>
#include <set>
#include <string>
#include <thread>
#include <vector>

using namespace sbw;

namespace {

int g_failures = 0;
int g_checks = 0;
int g_straddleTotal = 0;
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
double ms(Clock::time_point a, Clock::time_point b) { return std::chrono::duration<double, std::milli>(b - a).count(); }

constexpr int WATER_LAYER = 0, STATE_LAYER = 1, SNOW_LAYER = 2;

bool skyOpaqueRole(int r) {
    switch (r) {
        case SBW_AIR: case SBW_WATER: case SBW_ICE: case SBW_SNOW: case SBW_LEAVES: case SBW_ELM_LEAVES:
        case SBW_PINE_LEAVES: case SBW_CYPRESS_LEAVES: case SBW_ROSE: case SBW_WILDGRASS: case SBW_DANDELION:
        case SBW_CACTUS: case SBW_LIMESTONE_STALAGMITE: return false;
        default: return true;
    }
}
bool rockRole(int r) {
    return r == SBW_STONE || r == SBW_COBBLESTONE || r == SBW_SANDSTONE || r == SBW_SAND_COBBLESTONE
           || r == SBW_RED_SANDSTONE || r == SBW_RED_SAND_COBBLESTONE || r == SBW_LIMESTONE;
}

std::vector<uint16_t> g_flags;

/* A synthetic Daedalus-like profile, four quadrants around the origin:
 *   x < 0, z < 0   tundra, rolling (glacial lowland: boulder fields, erratics, snow caps)
 *   x >= 0, z < 0  stony peaks with 10-block terrace cliffs every 48 blocks (scree aprons)
 *   x < 0, z >= 0  desert dunes (sandstone)
 *   x >= 0, z >= 0 plains carrying karst carve codes (limestone), a lake in one corner */
int32_t quadrantProfile(void*, int64_t cx, int64_t cz, sbw_column_profile* out) {
    std::memset(out->surface, SBW_SURF_NO_DATA, sizeof(out->surface));
    for (int lz = 0; lz < 32; lz++) {
        for (int lx = 0; lx < 32; lx++) {
            const int c = lz * 32 + lx;
            const int64_t xi = cx * 32 + lx, zi = cz * 32 + lz;
            const double x = static_cast<double>(xi), z = static_cast<double>(zi);
            int h, biome;
            uint16_t carve = 0;
            int32_t water = SBW_NO_WATER;
            if (xi < 0 && zi < 0) {
                h = 80 + static_cast<int>(4.0 * std::sin(x / 19.0) * std::cos(z / 23.0));
                biome = SBW_TUNDRA;
            } else if (xi >= 0 && zi < 0) {
                const int64_t step = xi / 48;
                const int64_t in = xi % 48;
                h = 90 + static_cast<int>(step * 10) + (in > 44 ? static_cast<int>(in - 44) * 2 : 0)
                    + static_cast<int>(2.0 * std::sin(z / 11.0));
                biome = SBW_STONY_PEAKS;
            } else if (xi < 0) {
                h = 72 + static_cast<int>(3.0 * std::sin(x / 13.0 + z / 29.0));
                biome = SBW_DESERT;
            } else {
                h = 70 + static_cast<int>(3.0 * std::sin(x / 17.0) * std::sin(z / 21.0));
                biome = SBW_PLAINS;
                carve = static_cast<uint16_t>(8 | (SBW_CARVE_KARST << 4));
                if (xi > 100 && zi > 100) {
                    water = h + 2;
                }
            }
            out->height[c] = h;
            out->water[c] = water;
            out->biome[c] = static_cast<uint8_t>(biome);
            out->river_floor[c] = 0;
            out->river_roof[c] = 0;
            out->river_flow[c] = -1;
            out->carve[c] = carve;
        }
    }
    return 1;
}

sbw_gen_config makeConfig(int64_t seed, bool boulders, uint16_t bank = 0) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = seed;
    c.kind = SBW_KIND_PROFILE;
    c.column_fn = quadrantProfile;
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
    c.surface_prefetch = SBW_PREFETCH_OFF;
    if (boulders) c.flags |= SBW_FLAG_BOULDERS;
    c.rock_bank = bank;
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

struct CubeData {
    std::vector<uint16_t> ids = std::vector<uint16_t>(CELLS);
    std::vector<uint16_t> layers[3] = {std::vector<uint16_t>(CELLS), std::vector<uint16_t>(CELLS),
                                       std::vector<uint16_t>(CELLS)};
    bool operator==(const CubeData& o) const {
        return ids == o.ids && layers[0] == o.layers[0] && layers[1] == o.layers[1] && layers[2] == o.layers[2];
    }
};

CubeData genCube(const Gen& g, int64_t cx, int64_t cy, int64_t cz) {
    CubeData d;
    uint16_t* ls[CV_MAX_LAYERS] = {};
    for (int i = 0; i < 3; i++) ls[i] = d.layers[i].data();
    const int ret = g.t->generate_cube(g.t->user, cx, cy, cz, d.ids.data(), ls);
    if (ret == 0) {
        std::fill(d.ids.begin(), d.ids.end(), d.ids[0]);
        for (auto& l : d.layers) std::fill(l.begin(), l.end(), uint16_t{0});
    }
    return d;
}

struct Key {
    int64_t x, y, z;
    bool operator<(const Key& o) const { return std::tie(x, y, z) < std::tie(o.x, o.y, o.z); }
};

struct FakeWorld {
    std::map<Key, CubeData> cubes;
    Key allowed0{0, 0, 0};
    bool strict = false;
    int violations = 0;
    CubeData* cube(int64_t x, int64_t y, int64_t z) {
        auto it = cubes.find(Key{x >> 5, y >> 5, z >> 5});
        return it == cubes.end() ? nullptr : &it->second;
    }
    static size_t idx(int64_t x, int64_t y, int64_t z) {
        return static_cast<size_t>(CV_CELL_INDEX(static_cast<int>(x & 31), static_cast<int>(y & 31), static_cast<int>(z & 31)));
    }
    bool inAllowed(int64_t x, int64_t y, int64_t z) const {
        const int64_t cx = x >> 5, cy = y >> 5, cz = z >> 5;
        return cx >= allowed0.x && cx <= allowed0.x + 1 && cy >= allowed0.y && cy <= allowed0.y + 1
               && cz >= allowed0.z && cz <= allowed0.z + 1;
    }
    uint16_t at(int64_t x, int64_t y, int64_t z) {
        CubeData* c = cube(x, y, z);
        return c ? c->ids[idx(x, y, z)] : static_cast<uint16_t>(0xFFFF);
    }
};

thread_local FakeWorld* tlWorld = nullptr;
int32_t fwGet(cv_world*, int64_t x, int64_t y, int64_t z) {
    CubeData* c = tlWorld->cube(x, y, z);
    return c ? static_cast<int32_t>(c->ids[FakeWorld::idx(x, y, z)]) : static_cast<int32_t>(CV_ERR_MISSING);
}
int32_t fwSet(cv_world*, int64_t x, int64_t y, int64_t z, uint16_t id) {
    if (tlWorld->strict && !tlWorld->inAllowed(x, y, z)) {
        tlWorld->violations++;
        return CV_ERR_ARG;
    }
    CubeData* c = tlWorld->cube(x, y, z);
    if (!c) return CV_ERR_MISSING;
    uint16_t& v = c->ids[FakeWorld::idx(x, y, z)];
    const uint16_t prev = v;
    v = id;
    return prev;
}
int32_t fwLayerGet(cv_world*, int32_t layer, int64_t x, int64_t y, int64_t z) {
    CubeData* c = tlWorld->cube(x, y, z);
    return c ? static_cast<int32_t>(c->layers[layer][FakeWorld::idx(x, y, z)]) : static_cast<int32_t>(CV_ERR_MISSING);
}
int32_t fwLayerSet(cv_world*, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t v) {
    if (tlWorld->strict && !tlWorld->inAllowed(x, y, z)) {
        tlWorld->violations++;
        return CV_ERR_ARG;
    }
    CubeData* c = tlWorld->cube(x, y, z);
    if (!c) return CV_ERR_MISSING;
    uint16_t& s = c->layers[layer][FakeWorld::idx(x, y, z)];
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

/* an area of n x n column cubes from (cx0, cz0): generated cubes over the surface band, and the populate calls */
struct Area {
    int64_t cx0 = 0, cz0 = 0;
    int n = 4;
    int64_t lo = 0, hi = 0;
    FakeWorld base;
    std::vector<Key> calls;
};

void buildArea(const Gen& g, Area& a) {
    int32_t lo = INT32_MAX, hi = INT32_MIN;
    for (int dx = 0; dx < a.n; dx++) {
        for (int dz = 0; dz < a.n; dz++) {
            auto b = g.g().base(a.cx0 + dx, a.cz0 + dz);
            lo = std::min(lo, b->minH);
            hi = std::max(hi, b->maxTop);
        }
    }
    a.lo = floorDiv64(lo - 40, CE);
    a.hi = floorDiv64(hi + 40, CE) + 1;
    for (int64_t cx = a.cx0; cx < a.cx0 + a.n; cx++)
        for (int64_t cz = a.cz0; cz < a.cz0 + a.n; cz++)
            for (int64_t cy = a.lo; cy < a.hi; cy++) a.base.cubes[{cx, cy, cz}] = genCube(g, cx, cy, cz);
    for (int64_t cx = a.cx0; cx < a.cx0 + a.n - 1; cx++)
        for (int64_t cz = a.cz0; cz < a.cz0 + a.n - 1; cz++)
            for (int64_t cy = a.lo; cy < a.hi - 1; cy++) a.calls.push_back({cx, cy, cz});
}

bool sameWorld(FakeWorld& a, FakeWorld& b) {
    if (a.cubes.size() != b.cubes.size()) return false;
    for (auto& [k, v] : a.cubes) {
        auto it = b.cubes.find(k);
        if (it == b.cubes.end() || !(it->second == v)) return false;
    }
    return true;
}

/* =========================================================================================== tests */

void testShapes() {
    int bad = 0, total = 0;
    int vol[ROCK_CLASSES] = {}, cnt[ROCK_CLASSES] = {}, maxExt[ROCK_CLASSES] = {};
    for (int cls = 0; cls < ROCK_CLASSES; cls++) {
        for (uint64_t s = 0; s < 400; s++) {
            const RockShape r = proceduralRock(cls, s * 7919 + 13);
            total++;
            bool ok = r.dx >= 1 && r.dx <= ROCK_MAX_DXZ && r.dz >= 1 && r.dz <= ROCK_MAX_DXZ && r.dy >= 1
                      && r.dy <= ROCK_MAX_DY && r.sink < r.dy && r.occ.size() == static_cast<size_t>(r.dx * r.dy * r.dz);
            bool bottom = false;
            for (int z = 0; z < r.dz; z++)
                for (int x = 0; x < r.dx; x++) bottom = bottom || r.at(x, 0, z);
            ok = ok && bottom && r.volume() > 0;
            bad += !ok;
            vol[cls] += r.volume();
            cnt[cls]++;
            maxExt[cls] = std::max(maxExt[cls], static_cast<int>(std::max(r.dx, r.dz)));
            /* determinism */
            const RockShape r2 = proceduralRock(cls, s * 7919 + 13);
            bad += !(r2.occ == r.occ && r2.dx == r.dx && r2.dy == r.dy && r2.dz == r.dz && r2.sink == r.sink);
        }
    }
    for (int cls = 0; cls < ROCK_CLASSES; cls++) {
        std::printf("  class %d: mean volume %.1f voxels, max extent %d\n", cls,
                    static_cast<double>(vol[cls]) / cnt[cls], maxExt[cls]);
    }
    CHECK_EQ(bad, 0);
    CHECK(total == 2000);
    /* a size ladder: each class bigger on average than the one below */
    for (int cls = 1; cls < ROCK_CLASSES; cls++) CHECK(vol[cls] > vol[cls - 1]);
}

void testBank() {
    const std::vector<RockShape> shapes = placeholderShapes(8, 42);
    const std::vector<uint8_t> img = bakeRockBank(7, shapes);
    std::string err;
    auto bank = parseRockBank(img.data(), img.size(), err);
    CHECK(bank != nullptr);
    if (!bank) {
        std::fprintf(stderr, "  parse: %s\n", err.c_str());
        return;
    }
    CHECK_EQ(bank->id, 7u);
    CHECK_EQ(bank->count(), shapes.size());
    size_t k = 0;
    int same = 0;
    for (int cls = 0; cls < ROCK_CLASSES; cls++) {
        for (const RockShape& s : bank->byClass[cls]) {
            /* shapes are grouped by class in input order */
            while (k < shapes.size() && shapes[k].cls != cls) k++;
            if (k >= shapes.size()) break;
            const RockShape& o = shapes[k++];
            same += s.occ == o.occ && s.hint == o.hint && s.dx == o.dx && s.dy == o.dy && s.dz == o.dz && s.sink == o.sink;
        }
    }
    CHECK_EQ(same, static_cast<int>(shapes.size()));
    /* corruption is caught */
    std::vector<uint8_t> bad = img;
    bad[bad.size() - 1] ^= 0x10;
    CHECK(parseRockBank(bad.data(), bad.size(), err) == nullptr);
    std::vector<uint8_t> trunc(img.begin(), img.begin() + 40);
    CHECK(parseRockBank(trunc.data(), trunc.size(), err) == nullptr);
    /* registry: same bank again ok, a different bank under the id refused, a mismatched id refused */
    CHECK_EQ(sbw_register_rock_bank(7, img.data(), static_cast<int64_t>(img.size())), 0);
    CHECK_EQ(sbw_register_rock_bank(7, img.data(), static_cast<int64_t>(img.size())), 0);
    const std::vector<uint8_t> other = bakeRockBank(7, placeholderShapes(4, 43));
    CHECK_EQ(sbw_register_rock_bank(7, other.data(), static_cast<int64_t>(other.size())), -1);
    CHECK_EQ(sbw_register_rock_bank(8, img.data(), static_cast<int64_t>(img.size())), -1);
    /* a generator asking for an unregistered bank fails loudly */
    sbw_gen_config c = makeConfig(5, true, 99);
    const cv_generator* g = sbw_cv_generator_create(&c);
    CHECK(g == nullptr);
    sbw_cv_generator_destroy(g);
    std::printf("  placeholder bank: %zu shapes, %zu bytes\n", shapes.size(), img.size());
}

/* boulder features of the area (anchored in column cubes [cx0, cx0 + n)) */
std::vector<const Feature*> boulders(const Gen& g, int64_t cx0, int64_t cz0, int n,
                                     std::vector<std::shared_ptr<const FeatureTile>>& keep) {
    std::vector<const Feature*> out;
    for (int64_t cx = cx0; cx < cx0 + n; cx++) {
        for (int64_t cz = cz0; cz < cz0 + n; cz++) {
            auto t = g.g().features(cx, cz);
            for (const Feature& f : t->fs) if (f.kind == Feature::BOULDER) out.push_back(&f);
            keep.push_back(std::move(t));
        }
    }
    return out;
}

void populationChecks(const Gen& g, int64_t cx0, int64_t cz0, const char* name) {
    Area a;
    a.cx0 = cx0;
    a.cz0 = cz0;
    a.n = 6;
    buildArea(g, a);
    FakeWorld ref;
    ref.cubes = a.base.cubes;
    ref.strict = true;
    for (const Key& k : a.calls) populateIn(ref, g, k.x, k.y, k.z);
    CHECK_EQ(ref.violations, 0);
    /* idempotent */
    {
        FakeWorld again;
        again.cubes = ref.cubes;
        for (const Key& k : a.calls) populateIn(again, g, k.x, k.y, k.z);
        CHECK(sameWorld(again, ref));
    }
    /* order independence: shuffled, reversed, and on a cold generator in parallel (disjoint 2x2x2 sets) */
    for (int trial = 0; trial < 3; trial++) {
        FakeWorld w;
        w.cubes = a.base.cubes;
        std::vector<Key> order = a.calls;
        if (trial == 0) std::reverse(order.begin(), order.end());
        else std::shuffle(order.begin(), order.end(), std::mt19937(static_cast<unsigned>(77 + trial)));
        for (const Key& k : order) populateIn(w, g, k.x, k.y, k.z);
        CHECK(sameWorld(w, ref));
    }
    {
        Gen cold(makeConfig(static_cast<int64_t>(g.g().cfg.seed), true, g.g().cfg.rockBank));
        FakeWorld w;
        w.cubes = a.base.cubes;
        std::vector<Key> pending = a.calls;
        std::shuffle(pending.begin(), pending.end(), std::mt19937(4242));
        std::set<Key> held;
        std::mutex pm;
        std::vector<std::thread> pool;
        for (int t = 0; t < 6; t++) {
            pool.emplace_back([&] {
                for (;;) {
                    Key k{};
                    bool got = false;
                    {
                        std::lock_guard<std::mutex> lock(pm);
                        if (pending.empty()) return;
                        for (size_t i = 0; i < pending.size(); i++) {
                            const Key c = pending[i];
                            bool free = true;
                            for (int d = 0; d < 8 && free; d++) free = held.count({c.x + (d & 1), c.y + ((d >> 1) & 1), c.z + (d >> 2)}) == 0;
                            if (!free) continue;
                            for (int d = 0; d < 8; d++) held.insert({c.x + (d & 1), c.y + ((d >> 1) & 1), c.z + (d >> 2)});
                            k = c;
                            pending.erase(pending.begin() + static_cast<long>(i));
                            got = true;
                            break;
                        }
                    }
                    if (!got) {
                        std::this_thread::yield();
                        continue;
                    }
                    tlWorld = &w;
                    cold.t->populate(cold.t->user, &API, reinterpret_cast<cv_world*>(&w), k.x, k.y, k.z);
                    std::lock_guard<std::mutex> lock(pm);
                    for (int d = 0; d < 8; d++) held.erase({k.x + (d & 1), k.y + ((d >> 1) & 1), k.z + (d >> 2)});
                }
            });
        }
        for (auto& t : pool) t.join();
        CHECK(sameWorld(w, ref));
    }
    /* wholeness across cube borders: every boulder anchored in the populated core (whose cells all lie in
     * populated cubes) shows each of its rock cells as rock (its own or an overlapping boulder's) or a tree cell */
    std::vector<std::shared_ptr<const FeatureTile>> keep;
    const auto bs = boulders(g, cx0, cz0, a.n, keep);
    int straddle = 0, checkedCells = 0, missing = 0, featuresIn = 0, snowCaps = 0;
    /* anchors of the populated origin regions, minus 14 (a boulder anchored outside them may win a shared cell) */
    const int64_t X0 = cx0 * CE + 16 + 14, X1 = (cx0 + a.n - 1) * CE + 16 - 14;
    const int64_t Z0 = cz0 * CE + 16 + 14, Z1 = (cz0 + a.n - 1) * CE + 16 - 14;
    std::map<uint16_t, int> mats;
    for (const Feature* f : bs) {
        if (f->ax < X0 || f->ax >= X1 || f->az < Z0 || f->az >= Z1) continue;
        if (f->ay < (a.lo * CE) + 2 + 14 || f->ay >= (a.hi - 1) * CE + 2 - 14) continue;
        featuresIn++;
        std::set<Key> cubesTouched;
        for (const FeatureCell& c : f->cells) {
            cubesTouched.insert({c.x >> 5, c.y >> 5, c.z >> 5});
            if (c.snow != 0) {
                snowCaps++;
                continue;
            }
            const uint16_t id = ref.at(c.x, c.y, c.z);
            checkedCells++;
            mats[c.id]++;
            const bool ok = rockRole(id) || id == SBW_GRAVEL || id == SBW_WOOD || id == SBW_LEAVES || id == SBW_PINE
                            || id == SBW_PINE_LEAVES || id == SBW_ELM_LEAVES || id == SBW_ELM_WOOD_LOG;
            if (!ok && missing < 5) {
                std::fprintf(stderr, "    cell (%lld,%lld,%lld) want %u have %u (anchor %lld,%lld,%lld)\n",
                             static_cast<long long>(c.x), static_cast<long long>(c.y), static_cast<long long>(c.z), c.id, id,
                             static_cast<long long>(f->ax), static_cast<long long>(f->ay), static_cast<long long>(f->az));
            }
            missing += !ok;
        }
        straddle += cubesTouched.size() > 1;
    }
    std::printf("  %s: %zu populate calls, %d boulders/scree in the core (%d cross a cube border), %d rock cells, "
                "%d snow caps\n", name, a.calls.size(), featuresIn, straddle, checkedCells, snowCaps);
    std::printf("    materials:");
    for (auto& [id, n] : mats) std::printf(" %u:%d", id, n);
    std::printf("\n");
    CHECK(featuresIn >= 10);
    CHECK(straddle >= 1);
    g_straddleTotal += straddle;
    CHECK_EQ(missing, 0);

    /* column surface exact over the populated core */
    int topBad = 0, skyBad = 0, cols = 0;
    for (int64_t cx = cx0 + 1; cx < cx0 + a.n - 1; cx++) {
        for (int64_t cz = cz0 + 1; cz < cz0 + a.n - 1; cz++) {
            int64_t sky[COLS], top[COLS];
            CHECK_EQ(g.t->column_surface(g.t->user, cx, cz, sky, top), 1);
            for (int lz = 0; lz < CE; lz++) {
                for (int lx = 0; lx < CE; lx++) {
                    const int64_t x = cx * CE + lx, z = cz * CE + lz;
                    int64_t t = INT64_MIN, s = INT64_MIN;
                    for (int64_t y = a.hi * CE - 1; y >= a.lo * CE; y--) {
                        const uint16_t id = ref.at(x, y, z);
                        if (id != SBW_AIR && t == INT64_MIN) t = y + 1;
                        if (skyOpaqueRole(id)) {
                            s = y + 1;
                            break;
                        }
                    }
                    const int col = colIdx(lx, lz);
                    topBad += t != top[col];
                    skyBad += s != sky[col];
                    cols++;
                }
            }
        }
    }
    CHECK_EQ(topBad, 0);
    CHECK_EQ(skyBad, 0);
    (void) cols;
}

void testPopulation() {
    for (int64_t seed : {INT64_C(1234), INT64_C(-77)}) {
        Gen g(makeConfig(seed, true));
        populationChecks(g, -3, -3, "tundra");
        populationChecks(g, 0, -3, "peaks");
        populationChecks(g, -3, 0, "desert");
        populationChecks(g, 0, 0, "karst plains");
    }
    std::printf("  %d boulders crossing a cube border checked whole\n", g_straddleTotal);
    CHECK(g_straddleTotal >= 20);
}

void testWithBank() {
    const std::vector<uint8_t> img = bakeRockBank(3, placeholderShapes(24, 3));
    CHECK_EQ(sbw_register_rock_bank(3, img.data(), static_cast<int64_t>(img.size())), 0);
    Gen g(makeConfig(99, true, 3));
    CHECK(g.t != nullptr);
    if (g.t == nullptr) return;
    CHECK(g.g().rocks != nullptr);
    populationChecks(g, -3, -3, "tundra + bank");
    populationChecks(g, 0, -3, "peaks + bank");
}

/* surface channels drive the fields where present: plains everywhere, L's boulder_density 1 (size 10 m) west of
 * x = 0, 0 east of it (rock exposure and scree 0 there too) */
int32_t channelProfile(void* u, int64_t cx, int64_t cz, sbw_column_profile* out) {
    quadrantProfile(u, cx, cz, out);
    for (int c = 0; c < 1024; c++) {
        const int64_t x = cx * 32 + (c & 31);
        out->biome[c] = SBW_PLAINS;
        out->carve[c] = 0;
        out->water[c] = SBW_NO_WATER;
        out->height[c] = 70 + static_cast<int>(2.0 * std::sin(static_cast<double>(x) / 9.0));
        const bool west = x < 0;
        out->surface[SBW_SURF_BOULDER_DENSITY][c] = west ? 254 : 0;
        out->surface[SBW_SURF_BOULDER_SIZE][c] = west ? 200 : 0;
        out->surface[SBW_SURF_ROCK_EXPOSURE][c] = 0;
        out->surface[SBW_SURF_SCREE][c] = 0;
    }
    return 1;
}

void testChannels() {
    sbw_gen_config c = makeConfig(808, true);
    c.column_fn = channelProfile;
    Gen g(c);
    int west = 0, east = 0, westCells = 0;
    for (int64_t cx = -4; cx < 4; cx++) {
        for (int64_t cz = -4; cz < 4; cz++) {
            for (const Feature& f : g.g().features(cx, cz)->fs) {
                if (f.kind != Feature::BOULDER) continue;
                if (f.ax < 0) {
                    west++;
                    westCells += static_cast<int>(f.cells.size());
                } else {
                    east++;
                }
            }
        }
    }
    std::printf("  channel density 1 west: %d boulders (%.1f cells each), density 0 east: %d\n", west,
                west > 0 ? static_cast<double>(westCells) / west : 0.0, east);
    CHECK(west > 200);
    CHECK_EQ(east, 0);
    /* plains alone (heuristic) would give far fewer: the channel is what drives it */
    Gen h(makeConfig(808, true));
    int heur = 0;
    for (int64_t cx = 0; cx < 4; cx++)
        for (int64_t cz = 0; cz < 4; cz++)
            for (const Feature& f : h.g().features(cx, cz)->fs) heur += f.kind == Feature::BOULDER;
    std::printf("  heuristic karst plains, same area: %d boulders\n", heur);
}

/* the flag only adds population: generate_cube is unchanged, and without it population is the old one */
void testFlagOff() {
    Gen on(makeConfig(555, true)), off(makeConfig(555, false));
    int same = 0, cubes = 0;
    for (int64_t cx = -2; cx <= 1; cx++)
        for (int64_t cz = -2; cz <= 1; cz++)
            for (int64_t cy = 1; cy <= 4; cy++) {
                same += genCube(on, cx, cy, cz) == genCube(off, cx, cy, cz);
                cubes++;
            }
    CHECK_EQ(same, cubes);
    int boulderFeatures = 0;
    for (int64_t cx = -2; cx <= 1; cx++)
        for (int64_t cz = -2; cz <= 1; cz++)
            for (const Feature& f : off.g().features(cx, cz)->fs) boulderFeatures += f.kind == Feature::BOULDER;
    CHECK_EQ(boulderFeatures, 0);
    /* the Standard kind never places boulders, even with the flag */
    sbw_gen_config sc = makeConfig(555, true);
    sc.kind = SBW_KIND_STANDARD;
    sc.column_fn = nullptr;
    Gen st(sc);
    int stBoulders = 0;
    for (int64_t cx = -2; cx <= 1; cx++)
        for (int64_t cz = -2; cz <= 1; cz++)
            for (const Feature& f : st.g().features(cx, cz)->fs) stBoulders += f.kind == Feature::BOULDER;
    CHECK_EQ(stBoulders, 0);
}

/* feature-tile cost with and without boulders (cold generators, same columns) */
void testCost() {
    for (int rep = 0; rep < 2; rep++) {
        for (bool on : {false, true}) {
            Gen g(makeConfig(31 + rep, on));
            /* warm the column profiles / carved tiles first: time only the feature tiles */
            for (int64_t cx = -5; cx <= 5; cx++)
                for (int64_t cz = -5; cz <= 5; cz++) g.g().carved(cx, cz);
            auto t0 = Clock::now();
            size_t n = 0, b = 0;
            for (int64_t cx = -4; cx <= 4; cx++)
                for (int64_t cz = -4; cz <= 4; cz++) {
                    auto t = g.g().features(cx, cz);
                    n += t->fs.size();
                    for (const Feature& f : t->fs) b += f.kind == Feature::BOULDER;
                }
            std::printf("  boulders %s: 81 feature tiles %.1f ms (%zu features, %zu boulder)\n", on ? "on " : "off",
                        ms(t0, Clock::now()), n, b);
        }
    }
    /* whole pipeline on cold generators: generate the cubes of a 6 x 6 column area, then populate them */
    for (int rep = 0; rep < 2; rep++) {
        for (bool on : {false, true}) {
            Gen g(makeConfig(77 + rep, on));
            auto t0 = Clock::now();
            Area a;
            a.cx0 = -3;
            a.cz0 = -3;
            a.n = 6;
            buildArea(g, a);
            auto t1 = Clock::now();
            FakeWorld w;
            w.cubes = a.base.cubes;
            for (const Key& k : a.calls) populateIn(w, g, k.x, k.y, k.z);
            auto t2 = Clock::now();
            std::printf("  boulders %s: %zu cubes generated %.1f ms + %zu populate calls %.1f ms = %.1f ms\n",
                        on ? "on " : "off", a.base.cubes.size(), ms(t0, t1), a.calls.size(), ms(t1, t2), ms(t0, t2));
        }
    }
}

int bake(const char* path, int perClass) {
    const std::vector<RockShape> shapes = placeholderShapes(perClass, UINT64_C(0x5B0B1DE5));
    const std::vector<uint8_t> img = bakeRockBank(1, shapes);
    std::string err;
    if (!parseRockBank(img.data(), img.size(), err)) {
        std::fprintf(stderr, "baked bank does not parse: %s\n", err.c_str());
        return 1;
    }
    std::FILE* f = std::fopen(path, "wb");
    if (!f) return 1;
    const size_t w = std::fwrite(img.data(), 1, img.size(), f);
    std::fclose(f);
    std::printf("wrote %s: %zu shapes, %zu bytes\n", path, shapes.size(), img.size());
    return w == img.size() ? 0 : 1;
}

} // namespace

int main(int argc, char** argv) {
    if (argc >= 3 && std::string(argv[1]) == "--bake") {
        return bake(argv[2], argc >= 4 ? std::stoi(argv[3]) : 64);
    }
    std::vector<std::pair<std::string, void (*)()>> tests = {
        {"shapes", testShapes}, {"bank", testBank}, {"flagoff", testFlagOff}, {"channels", testChannels},
        {"population", testPopulation}, {"withbank", testWithBank}, {"cost", testCost},
    };
    std::set<std::string> only;
    for (int i = 1; i < argc; i++) only.insert(argv[i]);
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
