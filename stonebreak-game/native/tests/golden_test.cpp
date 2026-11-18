/* Differential golden test of the cube generator (SBW ABI 3): digests of generate_cube (ids + layers),
 * column_surface, populate results and the FastLOD samplers over several seeds, both terrain-scale presets,
 * flag variants, the profile kind and a tiny cache (eviction paths), computed on several threads in a
 * shuffled order. Uses only the public C ABI, so the same file builds against any library revision.
 *
 *   worldgen_golden_test --check <file>          compare with a digest file (ctest)
 *   worldgen_golden_test --write <file> [--big]  capture digests (run against the reference build)
 *   options: --threads N (default 8), --big (larger areas, more seeds: scratch use)
 *
 * The checked-in tests/golden/worldgen_golden.txt pins the generator's output. A deliberate change of
 * worldgen output (or of the shared FastNoise2 pin) needs a re-capture with --write; a performance change
 * must leave it green.
 *
 * PLATFORM FINGERPRINT: FastNoise2 rounds differently per SIMD level (fused multiply-add or not) and libm's
 * cos/sin/atan2/pow/cbrt may differ in the last bit between C libraries, so the same generator can produce
 * other (equally valid) terrain on another platform. The file records a digest of raw FastNoise2 and libm
 * samples that depends on neither the generator's code nor its data; where it differs, --check exits 77
 * (ctest SKIP) instead of reporting a generator change. */
#include "stonebreak/worldgen.h"
#include "cenda/noise_nodes.hpp"
#include "cenda/voxel_world.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <tuple>
#include <vector>

namespace {

constexpr int CE = 32;
constexpr int CELLS = CE * CE * CE;
constexpr int NLAYERS = 3;

uint64_t mix(uint64_t h) {
    h += UINT64_C(0x9E3779B97F4A7C15);
    h = (h ^ (h >> 30)) * UINT64_C(0xBF58476D1CE4E5B9);
    h = (h ^ (h >> 27)) * UINT64_C(0x94D049BB133111EB);
    return h ^ (h >> 31);
}
struct Hasher {
    uint64_t h = UINT64_C(0x5BD1E9955BD1E995);
    void add(uint64_t v) { h = mix(h ^ v); }
    void bytes(const void* p, size_t n) {
        const auto* b = static_cast<const unsigned char*>(p);
        size_t i = 0;
        for (; i + 8 <= n; i += 8) {
            uint64_t v;
            std::memcpy(&v, b + i, 8);
            add(v);
        }
        uint64_t tail = 0;
        for (size_t k = 0; i < n; i++, k++) tail |= static_cast<uint64_t>(b[i]) << (8 * k);
        add(tail ^ (static_cast<uint64_t>(n) << 56));
    }
};

int64_t floorDiv(int64_t a, int64_t b) { return a / b - ((a % b != 0) && ((a < 0) != (b < 0))); }

bool skyOpaqueRole(int r) {
    switch (r) {
        case SBW_AIR: case SBW_WATER: case SBW_ICE: case SBW_SNOW: case SBW_LEAVES: case SBW_ELM_LEAVES:
        case SBW_PINE_LEAVES: case SBW_CYPRESS_LEAVES: case SBW_ROSE: case SBW_WILDGRASS: case SBW_DANDELION:
        case SBW_CACTUS: case SBW_LIMESTONE_STALAGMITE: return false;
        default: return true;
    }
}

std::vector<uint16_t> g_flags;

int32_t synthProfile(void*, int64_t cx, int64_t cz, sbw_column_profile* out) {
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int c = lz * CE + lx;
            const double x = static_cast<double>(cx * CE + lx), z = static_cast<double>(cz * CE + lz);
            const int h = 70 + static_cast<int>(12.0 * std::sin(x / 23.0) * std::cos(z / 31.0));
            out->height[c] = h;
            out->water[c] = h < 66 ? 67 : SBW_NO_WATER;
            out->biome[c] = static_cast<uint8_t>(h < 66 ? SBW_PLAINS : (h > 78 ? SBW_TAIGA : (h > 74 ? SBW_SWAMP : SBW_MEADOW)));
            const auto xi = static_cast<int64_t>(x);
            const bool river = ((xi % 64) + 64) % 64 >= 20 && ((xi % 64) + 64) % 64 < 24;
            out->river_floor[c] = river ? h - 9 : 0;
            out->river_roof[c] = river ? h - 4 : 0;
            if (river) out->water[c] = h - 6;
            out->river_flow[c] = static_cast<int8_t>(river ? 3 : -1);
        }
    }
    return 1;
}

struct Case {
    std::string name;
    int64_t seed;
    double hm;          /* 60 = 1:4, 30 = 1:2 */
    uint32_t flags;
    bool profile;
    int32_t cache;      /* cache_columns, 0 = default */
    int64_t cx0, cz0;
    int n;              /* n x n column cubes */
};

sbw_gen_config makeConfig(const Case& k) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = k.seed;
    c.flags = k.flags;
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
    c.water_layer = 0;
    c.state_layer = 1;
    c.snow_layer = 2;
    for (int i = 0; i < 24; i++) c.stalagmite_states[i] = static_cast<uint16_t>(100 + i);
    c.stalagmite_part_states[0] = 200;
    c.stalagmite_part_states[1] = 201;
    c.water_river_values[3] = 77;
    c.water_river_values[5] = 78;
    c.cache_columns = k.cache;
    if (k.hm != 60.0) {
        c.horizontal_m_per_block = k.hm;
        const double rates[4] = {24.0, 8.0, 12.0, 19.0};
        std::memcpy(c.curve_rates, rates, sizeof(rates));
    }
    if (k.profile) {
        c.kind = SBW_KIND_PROFILE;
        c.column_fn = synthProfile;
    }
    return c;
}

struct Cube {
    std::vector<uint16_t> ids = std::vector<uint16_t>(CELLS);
    std::vector<uint16_t> layers[NLAYERS] = {std::vector<uint16_t>(CELLS), std::vector<uint16_t>(CELLS),
                                             std::vector<uint16_t>(CELLS)};
    int ret = 0;
    uint64_t hash() const {
        Hasher h;
        h.add(static_cast<uint64_t>(ret));
        h.bytes(ids.data(), ids.size() * 2);
        for (const auto& l : layers) h.bytes(l.data(), l.size() * 2);
        return h.h;
    }
};

Cube genCube(const cv_generator* g, int64_t cx, int64_t cy, int64_t cz) {
    Cube d;
    uint16_t* ls[CV_MAX_LAYERS] = {};
    for (int i = 0; i < NLAYERS; i++) ls[i] = d.layers[i].data();
    d.ret = g->generate_cube(g->user, cx, cy, cz, d.ids.data(), ls);
    if (d.ret == 0) { /* uniform: canonical form */
        std::fill(d.ids.begin(), d.ids.end(), d.ids[0]);
        for (auto& l : d.layers) std::fill(l.begin(), l.end(), uint16_t{0});
    }
    return d;
}

/* ---------------------------------------------------------------------------- fake world (populate) */
struct Key {
    int64_t x, y, z;
    bool operator<(const Key& o) const { return std::tie(x, y, z) < std::tie(o.x, o.y, o.z); }
};
struct FakeWorld {
    std::map<Key, Cube> cubes;
    Cube* cube(int64_t x, int64_t y, int64_t z) {
        auto it = cubes.find(Key{floorDiv(x, CE), floorDiv(y, CE), floorDiv(z, CE)});
        return it == cubes.end() ? nullptr : &it->second;
    }
    static size_t idx(int64_t x, int64_t y, int64_t z) {
        return static_cast<size_t>(CV_CELL_INDEX(static_cast<int>(x & 31), static_cast<int>(y & 31), static_cast<int>(z & 31)));
    }
};
FakeWorld* fw(cv_world* w) { return reinterpret_cast<FakeWorld*>(w); }
int32_t fwGet(cv_world* w, int64_t x, int64_t y, int64_t z) {
    Cube* c = fw(w)->cube(x, y, z);
    return c ? static_cast<int32_t>(c->ids[FakeWorld::idx(x, y, z)]) : static_cast<int32_t>(CV_ERR_MISSING);
}
int32_t fwSet(cv_world* w, int64_t x, int64_t y, int64_t z, uint16_t id) {
    Cube* c = fw(w)->cube(x, y, z);
    if (!c) return CV_ERR_MISSING;
    uint16_t& v = c->ids[FakeWorld::idx(x, y, z)];
    const uint16_t prev = v;
    v = id;
    return prev;
}
int32_t fwLayerGet(cv_world* w, int32_t layer, int64_t x, int64_t y, int64_t z) {
    Cube* c = fw(w)->cube(x, y, z);
    return c ? static_cast<int32_t>(c->layers[layer][FakeWorld::idx(x, y, z)]) : static_cast<int32_t>(CV_ERR_MISSING);
}
int32_t fwLayerSet(cv_world* w, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t v) {
    Cube* c = fw(w)->cube(x, y, z);
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

/* ------------------------------------------------------------------------------- platform fingerprint */
uint64_t platformFingerprint() {
    Hasher h;
    std::mt19937_64 rng(12345);
    auto coord = [&](double span) { return static_cast<float>((static_cast<double>(rng() % 2000001) / 1000000.0 - 1.0) * span); };
    constexpr int N = 512;
    std::vector<float> xs(N), ys(N), zs(N), out(N);
    for (int oct = 1; oct <= 6; oct++) {
        auto node = cenda::makeSimplexFbm(oct, 2.0f, 0.5f, 1.0f / 37.0f);
        for (int i = 0; i < N; i++) {
            xs[static_cast<size_t>(i)] = coord(40000.0);
            ys[static_cast<size_t>(i)] = coord(600.0);
            zs[static_cast<size_t>(i)] = coord(40000.0);
        }
        node->GenPositionArray3D(out.data(), N, xs.data(), ys.data(), zs.data(), 0.0f, 0.0f, 0.0f, oct * 7919);
        h.bytes(out.data(), out.size() * 4);
        node->GenPositionArray2D(out.data(), N, xs.data(), zs.data(), 0.0f, 0.0f, oct * 104729);
        h.bytes(out.data(), out.size() * 4);
        node->GenUniformGrid2D(out.data(), -1000.0f, 3000.0f, 16, 32, 1.0f, 1.0f, oct * 31);
        h.bytes(out.data(), out.size() * 4);
        for (int i = 0; i < 64; i++) {
            const float a = node->GenSingle3D(xs[static_cast<size_t>(i)], ys[static_cast<size_t>(i)], zs[static_cast<size_t>(i)], oct);
            const float b = node->GenSingle2D(xs[static_cast<size_t>(i)], zs[static_cast<size_t>(i)], oct);
            h.bytes(&a, 4);
            h.bytes(&b, 4);
        }
    }
    for (int i = 0; i < 4096; i++) {
        const double x = static_cast<double>(coord(8.0)), y = static_cast<double>(coord(8.0));
        const double v[6] = {std::cos(x), std::sin(x), std::atan2(y, x), std::pow(std::fabs(x) + 0.01, std::fabs(y)),
                             std::cbrt(x), std::sqrt(std::fabs(y))};
        h.bytes(v, sizeof(v));
    }
    return h.h;
}

/* ------------------------------------------------------------------------------------------- runner */
using Digests = std::map<std::string, uint64_t>;

template <typename F>
void parallelFor(int threads, size_t n, F&& f) {
    std::atomic<size_t> next{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < threads; t++) {
        pool.emplace_back([&] {
            for (size_t i; (i = next.fetch_add(1)) < n;) f(i);
        });
    }
    for (auto& t : pool) t.join();
}

std::string keyOf(const Case& k, const char* what, int64_t a, int64_t b) {
    char buf[160];
    std::snprintf(buf, sizeof(buf), "%s/%s/%lld/%lld", k.name.c_str(), what, static_cast<long long>(a),
                  static_cast<long long>(b));
    return buf;
}

void runCase(const Case& k, int threads, Digests& out) {
    const sbw_gen_config cfg = makeConfig(k);
    const cv_generator* g = sbw_cv_generator_create(&cfg);
    if (g == nullptr) {
        std::fprintf(stderr, "create failed: %s\n", sbw_last_error());
        std::exit(2);
    }
    std::mt19937_64 rng(static_cast<uint64_t>(k.seed) * 31 + static_cast<uint64_t>(k.n));
    const int n = k.n;
    /* 1. column surfaces, shuffled, threaded */
    struct Col { int64_t cx, cz; std::vector<int64_t> sky = std::vector<int64_t>(1024), top = std::vector<int64_t>(1024); int ret = 0; };
    std::vector<Col> cols;
    for (int dx = 0; dx < n; dx++)
        for (int dz = 0; dz < n; dz++) cols.push_back(Col{k.cx0 + dx, k.cz0 + dz});
    std::vector<size_t> order(cols.size());
    for (size_t i = 0; i < order.size(); i++) order[i] = i;
    std::shuffle(order.begin(), order.end(), rng);
    parallelFor(threads, order.size(), [&](size_t i) {
        Col& c = cols[order[i]];
        c.ret = g->column_surface(g->user, c.cx, c.cz, c.sky.data(), c.top.data());
    });
    int64_t lo = INT64_MAX, hi = INT64_MIN;
    for (const Col& c : cols) {
        Hasher h;
        h.add(static_cast<uint64_t>(c.ret));
        h.bytes(c.sky.data(), c.sky.size() * 8);
        h.bytes(c.top.data(), c.top.size() * 8);
        out[keyOf(k, "surface", c.cx, c.cz)] = h.h;
        for (int i = 0; i < 1024; i++) {
            lo = std::min({lo, c.sky[static_cast<size_t>(i)], c.top[static_cast<size_t>(i)]});
            hi = std::max(hi, c.top[static_cast<size_t>(i)]);
        }
    }
    /* 2. cubes: the band (+-) and a few deep cubes, shuffled, threaded */
    const int64_t cyLo = std::max<int64_t>(-12, floorDiv(lo - 48, CE)), cyHi = std::min<int64_t>(19, floorDiv(hi + 40, CE));
    std::vector<Key> keys;
    for (const Col& c : cols) {
        for (int64_t cy = cyLo; cy <= cyHi; cy++) keys.push_back({c.cx, cy, c.cz});
        for (int64_t cy : {-12, -9, -6, -3}) {
            if (cy < cyLo) keys.push_back({c.cx, cy, c.cz});
        }
    }
    std::vector<Cube> cubes(keys.size());
    order.resize(keys.size());
    for (size_t i = 0; i < order.size(); i++) order[i] = i;
    std::shuffle(order.begin(), order.end(), rng);
    parallelFor(threads, order.size(), [&](size_t i) {
        const Key& kk = keys[order[i]];
        cubes[order[i]] = genCube(g, kk.x, kk.y, kk.z);
    });
    std::map<std::pair<int64_t, int64_t>, Hasher> perColumn;
    FakeWorld world;
    for (size_t i = 0; i < keys.size(); i++) {
        perColumn[{keys[i].x, keys[i].z}].add(cubes[i].hash() ^ static_cast<uint64_t>(keys[i].y));
        if (keys[i].y >= cyLo) world.cubes[keys[i]] = cubes[i];
    }
    for (auto& [xz, h] : perColumn) out[keyOf(k, "cubes", xz.first, xz.second)] = h.h;
    /* 3. population of every origin whose 2x2x2 lies in the area, sequential */
    int pops = 0;
    for (int64_t cx = k.cx0; cx < k.cx0 + n - 1; cx++) {
        for (int64_t cz = k.cz0; cz < k.cz0 + n - 1; cz++) {
            for (int64_t cy = cyLo; cy < cyHi; cy++) {
                const int32_t r = g->populate(g->user, &API, reinterpret_cast<cv_world*>(&world), cx, cy, cz);
                out[keyOf(k, "popret", (cx - k.cx0) * 64 + (cz - k.cz0), cy)] = static_cast<uint64_t>(static_cast<int64_t>(r));
                pops++;
            }
        }
    }
    for (int64_t cx = k.cx0; cx < k.cx0 + n; cx++) {
        for (int64_t cz = k.cz0; cz < k.cz0 + n; cz++) {
            Hasher h;
            for (int64_t cy = cyLo; cy <= cyHi; cy++) h.add(world.cubes[Key{cx, cy, cz}].hash());
            out[keyOf(k, "populated", cx, cz)] = h.h;
        }
    }
    /* 4. the FastLOD samplers and point queries */
    {
        const int64_t x0 = k.cx0 * CE, z0 = k.cz0 * CE;
        const int count = n * CE;
        std::vector<int32_t> hh(static_cast<size_t>(count * count)), ww(hh.size()), tt(hh.size());
        std::vector<uint16_t> ss(hh.size());
        for (uint32_t flags : {0u, static_cast<uint32_t>(SBW_SAMPLE_CARVED)}) {
            for (int stride : {1, 3}) {
                const int cnt = stride == 1 ? count : count / 3;
                Hasher h;
                h.add(static_cast<uint64_t>(sbw_sample_columns(g, x0, z0, cnt, stride, flags, hh.data(), ww.data(), ss.data(), tt.data())));
                const size_t m = static_cast<size_t>(cnt * cnt);
                h.bytes(hh.data(), m * 4);
                h.bytes(ww.data(), m * 4);
                h.bytes(ss.data(), m * 2);
                h.bytes(tt.data(), m * 4);
                out[keyOf(k, "sample", flags, stride)] = h.h;
            }
        }
        /* coarse cells over the carved heights */
        sbw_sample_columns(g, x0, z0, count, 1, SBW_SAMPLE_CARVED, hh.data(), ww.data(), ss.data(), tt.data());
        const int cellSize = 8, cells = count / cellSize;
        std::vector<int32_t> ch(static_cast<size_t>(cells * cells)), cw(ch.size()), fl(ch.size());
        std::vector<uint16_t> cs(ch.size());
        std::vector<uint8_t> cov(ch.size());
        for (int ix = 0; ix < cells; ix++) {
            for (int iz = 0; iz < cells; iz++) {
                const size_t s = static_cast<size_t>((ix * cellSize + cellSize / 2) * count + iz * cellSize + cellSize / 2);
                const size_t c = static_cast<size_t>(ix * cells + iz);
                ch[c] = hh[s];
                cw[c] = ww[s];
                cs[c] = ss[s];
            }
        }
        Hasher h;
        h.add(static_cast<uint64_t>(sbw_sample_cell_openings(g, x0, z0, cells, cellSize, ch.data(), fl.data(), cov.data())));
        h.bytes(fl.data(), fl.size() * 4);
        h.bytes(cov.data(), cov.size());
        std::vector<int32_t> spots(4096);
        h.add(static_cast<uint64_t>(sbw_probe_cell_trees(g, x0, z0, cells, cellSize, ch.data(), cw.data(), cs.data(), spots.data(), 4096)));
        h.bytes(spots.data(), spots.size() * 4);
        out[keyOf(k, "cells", 0, 0)] = h.h;
        Hasher q;
        std::mt19937_64 pr(static_cast<uint64_t>(k.seed) ^ 0xABCDu);
        for (int i = 0; i < 96; i++) {
            const int64_t x = x0 + static_cast<int64_t>(pr() % static_cast<uint64_t>(count));
            const int64_t z = z0 + static_cast<int64_t>(pr() % static_cast<uint64_t>(count));
            sbw_column_info info;
            q.add(static_cast<uint64_t>(sbw_query_column(g, x, z, &info)));
            q.bytes(&info, sizeof(info));
        }
        out[keyOf(k, "query", 0, 0)] = q.h;
        for (const Col& c : cols) {
            std::vector<int32_t> ch2(1024);
            std::vector<uint8_t> cc2(1024);
            Hasher cv;
            cv.add(static_cast<uint64_t>(sbw_column_carve(g, c.cx, c.cz, ch2.data(), cc2.data())));
            cv.bytes(ch2.data(), 4096);
            cv.bytes(cc2.data(), 1024);
            out[keyOf(k, "carve", c.cx, c.cz)] = cv.h;
        }
    }
    std::printf("  %-28s %3zu columns, %4zu cubes (cy %lld..%lld), %d populates\n", k.name.c_str(), cols.size(),
                keys.size(), static_cast<long long>(cyLo), static_cast<long long>(cyHi), pops);
    std::fflush(stdout);
    sbw_cv_generator_destroy(g);
}

std::vector<Case> cases(bool big) {
    const uint32_t ALL = SBW_FLAG_PEAK_DAMP | SBW_FLAG_DEEP_LAYERS | SBW_FLAG_FEATURES;
    std::vector<Case> v = {
        {"s1-q", 1, 60.0, ALL, false, 0, 0, 0, 3},
        {"s1-h", 1, 30.0, ALL, false, 0, 0, 0, 3},
        {"s4242-q", 4242, 60.0, ALL, false, 0, 40, -25, 3},
        {"s20261117-h", 20261117, 30.0, ALL, false, 0, -200, 130, 2},
        {"sm77-q-nodeep", -77, 60.0, SBW_FLAG_PEAK_DAMP | SBW_FLAG_FEATURES, false, 0, 5, 5, 2},
        {"s31337-q-tiny", 31337, 60.0, ALL, false, 64, -3, 7, 3},
        {"s9-profile", 9, 60.0, ALL, true, 0, -1, 0, 3},
    };
    if (big) {
        const int64_t seeds[] = {2, 77, 8086, 123456789, -5, 2024, 31337, 20261117};
        int i = 0;
        for (int64_t s : seeds) {
            for (double hm : {60.0, 30.0}) {
                const int64_t ox = (i * 37) % 300 - 150, oz = (i * 91) % 300 - 150;
                char name[64];
                std::snprintf(name, sizeof(name), "big-s%lld-%s", static_cast<long long>(s), hm == 60.0 ? "q" : "h");
                v.push_back({name, s, hm, ALL, false, i % 3 == 0 ? 256 : 0, ox, oz, 6});
                i++;
            }
        }
        v.push_back({"big-profile", 3, 60.0, ALL, true, 0, 10, -4, 5});
        v.push_back({"big-nofeat", 6, 30.0, SBW_FLAG_DEEP_LAYERS, false, 0, 2, 2, 4});
    }
    return v;
}

} // namespace

int main(int argc, char** argv) {
    std::string mode, path;
    bool big = false;
    int threads = 8;
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        if ((a == "--check" || a == "--write") && i + 1 < argc) {
            mode = a;
            path = argv[++i];
        } else if (a == "--big") {
            big = true;
        } else if (a == "--threads" && i + 1 < argc) {
            threads = std::max(1, std::atoi(argv[++i]));
        } else {
            std::fprintf(stderr, "usage: %s --check|--write <file> [--big] [--threads N]\n", argv[0]);
            return 2;
        }
    }
    if (mode.empty()) {
        std::fprintf(stderr, "usage: %s --check|--write <file> [--big] [--threads N]\n", argv[0]);
        return 2;
    }
    if (mode == "--check") {
        /* a mismatching platform skips before generating anything */
        std::FILE* probe = std::fopen(path.c_str(), "r");
        if (probe) {
            char l[256];
            unsigned long long fp = 0;
            while (std::fgets(l, sizeof(l), probe)) {
                if (std::sscanf(l, "# fingerprint %llx", &fp) == 1 && fp != platformFingerprint()) {
                    std::fclose(probe);
                    std::printf("SKIP: platform fingerprint %016llx differs from the captured %016llx (FastNoise2 SIMD "
                                "level / libm rounding)\n", static_cast<unsigned long long>(platformFingerprint()), fp);
                    return 77;
                }
            }
            std::fclose(probe);
        }
    }
    Digests got;
    for (const Case& k : cases(big)) runCase(k, threads, got);
    if (mode == "--write") {
        std::FILE* f = std::fopen(path.c_str(), "w");
        if (!f) {
            std::fprintf(stderr, "cannot write %s\n", path.c_str());
            return 2;
        }
        std::fprintf(f, "# worldgen golden digests (tests/golden_test.cpp), %zu entries\n", got.size());
        std::fprintf(f, "# fingerprint %016llx\n", static_cast<unsigned long long>(platformFingerprint()));
        for (const auto& [key, h] : got) std::fprintf(f, "%s %016llx\n", key.c_str(), static_cast<unsigned long long>(h));
        std::fclose(f);
        std::printf("wrote %zu digests to %s\n", got.size(), path.c_str());
        return 0;
    }
    std::FILE* f = std::fopen(path.c_str(), "r");
    if (!f) {
        std::fprintf(stderr, "cannot read %s\n", path.c_str());
        return 2;
    }
    Digests want;
    char line[512];
    unsigned long long fingerprint = 0;
    bool haveFingerprint = false;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::sscanf(line, "# fingerprint %llx", &fingerprint) == 1) {
            haveFingerprint = true;
            continue;
        }
        if (line[0] == '#' || line[0] == '\n') continue;
        char key[400];
        unsigned long long h = 0;
        if (std::sscanf(line, "%399s %llx", key, &h) == 2) want[key] = h;
    }
    std::fclose(f);
    const uint64_t here = platformFingerprint();
    if (haveFingerprint && here != fingerprint) {
        std::printf("SKIP: this platform's FastNoise2 / libm rounding (fingerprint %016llx) differs from the one the "
                    "digests were captured on (%016llx); the generator's output is platform-specific there.\n",
                    static_cast<unsigned long long>(here), fingerprint);
        return 77;
    }
    int bad = 0, missing = 0;
    for (const auto& [key, h] : want) {
        auto it = got.find(key);
        if (it == got.end()) {
            if (missing++ < 20) std::fprintf(stderr, "  MISSING %s\n", key.c_str());
            continue;
        }
        if (it->second != h) {
            if (bad++ < 40) std::fprintf(stderr, "  DIFF %s\n", key.c_str());
        }
    }
    const size_t extra = got.size() > want.size() ? got.size() - want.size() : 0;
    std::printf("%zu digests compared: %d differ, %d missing, %zu extra\n", want.size(), bad, missing, extra);
    if (bad || missing || want.empty()) {
        std::fprintf(stderr, "worldgen output differs from %s. An intended worldgen change needs a re-capture "
                             "(worldgen_golden_test --write <file>); a performance change must not change it.\n",
                     path.c_str());
        return 1;
    }
    return 0;
}
