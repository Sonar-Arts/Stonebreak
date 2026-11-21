/* Streamer-like benchmark of the cube generator (not a ctest; run by hand in a Release build).
 *
 *   worldgen_bench [--threads 26] [--radius 384] [--seed S] [--half] [--reps 1] [--columns 16]
 *
 * STREAM: one viewer at the surface of column cube (0, 0), the engine's surface-following load shape
 * (cv_center: radius_h, radius_v 128, band 16 below / 8 above; voxel_world.h): the PLANNER computes
 * column_surface for every column of the hysteresis disc (radius + half a cube diagonal + one cube) on ONE
 * thread in the engine's raster order (stream_plan.cpp compute: tops first, then the plan), then N workers
 * generate the WANTED cubes ((a) ellipsoid, (b) surface band; known air above the column top skipped)
 * nearest-first, then the SUPPORT cubes (3x3x3 dilation). Reported from t0 = planner start:
 * first plan, rings (all wanted cubes of columns within 64/128/256 blocks done), all wanted, all.
 * Variant "tops on workers": the same with the tops computed by the workers in parallel (what an engine
 * planner that fetched tops in parallel would see). Cold = fresh generator; warm = the same generator again.
 * COLUMNS: single-thread cold per-column numbers (stages and whole 32x32x1024 columns). */
#include "decide.hpp"
#include "gen.hpp"
#include "stonebreak/worldgen.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

using namespace sbw;

namespace {

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point a) { return std::chrono::duration<double, std::milli>(Clock::now() - a).count(); }

std::vector<uint16_t> g_flags;
int g_cache = 0; /* --cache N: cache_columns (0 = the default 4096, as the game runs it) */

sbw_gen_config makeConfig(int64_t seed, bool half, bool prefetch = true) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = seed;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    g_flags.assign(SBW_BLOCK_COUNT, 0);
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) {
        const bool clear = i == SBW_AIR || i == SBW_WATER || i == SBW_ICE || i == SBW_SNOW || i == SBW_LEAVES
            || i == SBW_ELM_LEAVES || i == SBW_PINE_LEAVES || i == SBW_CYPRESS_LEAVES || i == SBW_ROSE
            || i == SBW_WILDGRASS || i == SBW_DANDELION || i == SBW_CACTUS || i == SBW_LIMESTONE_STALAGMITE;
        uint16_t f = i == SBW_AIR ? 0 : CV_BLOCK_CUBE;
        if (!clear) f |= CV_BLOCK_SKY_OPAQUE | CV_BLOCK_OPAQUE;
        g_flags[static_cast<size_t>(i)] = f;
    }
    c.block_flags = g_flags.data();
    c.block_flags_count = SBW_BLOCK_COUNT;
    c.water_layer = 0;
    c.state_layer = 1;
    c.snow_layer = 2;
    c.cache_columns = g_cache;
#ifdef SBW_PREFETCH_OFF
    if (!prefetch) c.surface_prefetch = SBW_PREFETCH_OFF;
#else
    (void) prefetch;
#endif
    if (half) {
        c.horizontal_m_per_block = 30.0;
        const double rates[4] = {24.0, 8.0, 12.0, 19.0};
        std::memcpy(c.curve_rates, rates, sizeof(rates));
    }
    return c;
}

struct Gen {
    const cv_generator* t;
    explicit Gen(const sbw_gen_config& c) : t(sbw_cv_generator_create(&c)) {
        if (!t) {
            std::fprintf(stderr, "create failed: %s\n", sbw_last_error());
            std::exit(2);
        }
    }
    ~Gen() { sbw_cv_generator_destroy(t); }
    Generator& g() const { return *static_cast<Generator*>(t->user); }
};

uint64_t hashWords(uint64_t h, const void* p, size_t n);

struct Top {
    uint64_t hash = 0;
    bool known = false;
    int64_t topMin = 0, topMax = 0, skyMin = 0;
};

Top topOf(const cv_generator* g, int64_t cx, int64_t cz) {
    std::vector<int64_t> sky(1024), top(1024);
    Top t;
    if (g->column_surface(g->user, cx, cz, sky.data(), top.data()) != 1) return t;
    t.known = true;
    t.hash = hashWords(hashWords(1, sky.data(), 8192), top.data(), 8192);
    t.topMin = INT64_MAX;
    t.topMax = INT64_MIN;
    t.skyMin = INT64_MAX;
    for (int i = 0; i < 1024; i++) {
        const int64_t tv = std::clamp<int64_t>(top[static_cast<size_t>(i)], -384, 640);
        const int64_t sv = std::clamp<int64_t>(sky[static_cast<size_t>(i)], -384, 640);
        t.topMin = std::min(t.topMin, tv);
        t.topMax = std::max(t.topMax, tv);
        t.skyMin = std::min(t.skyMin, sv);
    }
    return t;
}

constexpr double HALF_DIAG = 27.712812921102035;
constexpr int64_t CMIN = -12, CMAX = 19;

struct Job {
    int64_t cx, cy, cz;
    int cls;          /* 0 ellipsoid, 1 band, 2 support */
    double d2;
    double colDist;   /* horizontal distance of the column centre */
};

struct Plan {
    std::vector<std::pair<int64_t, int64_t>> columns; /* hysteresis disc, raster order */
    std::vector<Job> jobs;
};

/* the wanted + support set from the tops (stream_plan.cpp, one viewer) */
void buildJobs(Plan& p, const std::unordered_map<int64_t, Top>& tops, int64_t vx, int64_t vy, int64_t vz, int rh,
               int rv) {
    auto colKey = [](int64_t cx, int64_t cz) { return (cx + 100000) * 1000003 + cz; };
    const double Rn = rh + HALF_DIAG, Vn = rv + HALF_DIAG;
    std::unordered_map<int64_t, int> level; /* key -> cls */
    auto cubeKey = [](int64_t cx, int64_t cy, int64_t cz) { return ((cx + 4096) * 8192 + (cz + 4096)) * 64 + (cy + 32); };
    std::vector<Job> wanted;
    for (const auto& [cx, cz] : p.columns) {
        const double ddx = static_cast<double>(cx * 32 + 16 - vx), ddz = static_cast<double>(cz * 32 + 16 - vz);
        const double d2 = ddx * ddx + ddz * ddz;
        if (d2 > Rn * Rn) continue;
        const Top& t = tops.at(colKey(cx, cz));
        /* (a) ellipsoid */
        const double half = Vn * std::sqrt(std::max(0.0, 1.0 - d2 / (Rn * Rn)));
        const int64_t shifted = vy - 16;
        const int64_t base = floorDiv64(shifted, 32);
        const double frac = static_cast<double>(shifted - base * 32);
        const auto lo = base + static_cast<int64_t>(std::ceil((frac - half) / 32.0));
        const auto hi = base + static_cast<int64_t>(std::floor((frac + half) / 32.0));
        for (int64_t cy = std::max(lo, CMIN); cy <= std::min(hi, CMAX); cy++) {
            if (t.known && cy * 32 >= t.topMax) continue; /* known air */
            const double ddy = static_cast<double>(cy * 32 + 16 - vy);
            wanted.push_back({cx, cy, cz, 0, d2 + ddy * ddy, std::sqrt(d2)});
        }
        /* (b) band */
        if (t.known) {
            const int64_t loY = std::min(t.skyMin, t.topMin) - 16, hiY = t.topMax + 8;
            for (int64_t cy = std::max(floorDiv64(loY, 32), CMIN); cy <= std::min(floorDiv64(hiY - 1, 32), CMAX); cy++) {
                const double ddy = static_cast<double>(cy * 32 + 16 - vy);
                wanted.push_back({cx, cy, cz, 1, d2 + ddy * ddy, std::sqrt(d2)});
            }
        }
    }
    for (const Job& j : wanted) {
        auto it = level.find(cubeKey(j.cx, j.cy, j.cz));
        if (it == level.end() || it->second > j.cls) level[cubeKey(j.cx, j.cy, j.cz)] = j.cls;
    }
    std::vector<Job> out;
    std::unordered_map<int64_t, char> seen;
    for (const Job& j : wanted) {
        const int64_t k = cubeKey(j.cx, j.cy, j.cz);
        if (seen.count(k) || level[k] != j.cls) continue;
        seen[k] = 1;
        out.push_back(j);
    }
    std::vector<Job> support;
    for (const Job& j : out) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++) {
                    const int64_t cy = j.cy + dy;
                    if (cy < CMIN || cy > CMAX) continue;
                    const int64_t k = cubeKey(j.cx + dx, cy, j.cz + dz);
                    if (seen.count(k)) continue;
                    seen[k] = 1;
                    const double ddx = static_cast<double>((j.cx + dx) * 32 + 16 - vx), ddz = static_cast<double>((j.cz + dz) * 32 + 16 - vz);
                    const double ddy = static_cast<double>(cy * 32 + 16 - vy);
                    support.push_back({j.cx + dx, cy, j.cz + dz, 2, ddx * ddx + ddy * ddy + ddz * ddz, std::sqrt(ddx * ddx + ddz * ddz)});
                }
    }
    std::sort(out.begin(), out.end(), [](const Job& a, const Job& b) { return a.cls != b.cls ? a.cls < b.cls : a.d2 < b.d2; });
    std::sort(support.begin(), support.end(), [](const Job& a, const Job& b) { return a.d2 < b.d2; });
    p.jobs = std::move(out);
    p.jobs.insert(p.jobs.end(), support.begin(), support.end());
}

std::string g_digest; /* --digest FILE: per cube / per column digests of the first cold run */

uint64_t hashWords(uint64_t h, const void* p, size_t n) {
    const auto* b = static_cast<const unsigned char*>(p);
    for (size_t i = 0; i + 8 <= n; i += 8) {
        uint64_t v;
        std::memcpy(&v, b + i, 8);
        h = stonebreak::mix64(h ^ v);
    }
    return h;
}

struct StreamResult {
    double plan = 0, ring64 = 0, ring128 = 0, ring256 = 0, wanted = 0, all = 0, a64 = 0, a128 = 0;
    size_t nWanted = 0, nAll = 0, columns = 0;
    double cpuMs = 0; /* summed generate time */
};

StreamResult runStream(const Gen& gen, int threads, int rh, int rv, int64_t vx, int64_t vy, int64_t vz, bool topsOnWorkers) {
    const cv_generator* g = gen.t;
    Plan p;
    const double Rk = rh + HALF_DIAG + 32.0;
    const auto R = static_cast<int64_t>(std::ceil(Rk / 32.0)) + 1;
    const int64_t vcx = floorDiv64(vx, 32), vcz = floorDiv64(vz, 32);
    for (int64_t dcz = -R; dcz <= R; dcz++) {
        for (int64_t dcx = -R; dcx <= R; dcx++) {
            const double ddx = static_cast<double>(dcx) * 32.0 + 16.0 - static_cast<double>(vx & 31);
            const double ddz = static_cast<double>(dcz) * 32.0 + 16.0 - static_cast<double>(vz & 31);
            if (ddx * ddx + ddz * ddz > Rk * Rk) continue;
            p.columns.push_back({vcx + dcx, vcz + dcz});
        }
    }
    auto colKey = [](int64_t cx, int64_t cz) { return (cx + 100000) * 1000003 + cz; };
    std::unordered_map<int64_t, Top> tops;
    StreamResult r;
    r.columns = p.columns.size();
    const auto t0 = Clock::now();
    if (!topsOnWorkers) {
        for (const auto& [cx, cz] : p.columns) tops[colKey(cx, cz)] = topOf(g, cx, cz);
    } else {
        std::vector<Top> res(p.columns.size());
        /* nearest first */
        std::vector<size_t> ord(p.columns.size());
        for (size_t i = 0; i < ord.size(); i++) ord[i] = i;
        std::sort(ord.begin(), ord.end(), [&](size_t a, size_t b) {
            auto d = [&](size_t i) { const double x = static_cast<double>(p.columns[i].first - vcx), z = static_cast<double>(p.columns[i].second - vcz); return x * x + z * z; };
            return d(a) < d(b);
        });
        std::atomic<size_t> next{0};
        std::vector<std::thread> pool;
        for (int t = 0; t < threads; t++) {
            pool.emplace_back([&] {
                for (size_t i; (i = next.fetch_add(1)) < ord.size();) {
                    res[ord[i]] = topOf(g, p.columns[ord[i]].first, p.columns[ord[i]].second);
                }
            });
        }
        for (auto& t : pool) t.join();
        for (size_t i = 0; i < p.columns.size(); i++) tops[colKey(p.columns[i].first, p.columns[i].second)] = res[i];
    }
    r.plan = msSince(t0);
    buildJobs(p, tops, vx, vy, vz, rh, rv);
    std::vector<double> done(p.jobs.size(), 0.0);
    std::vector<uint64_t> digest(g_digest.empty() ? 0 : p.jobs.size());
    std::atomic<size_t> next{0};
    std::atomic<int64_t> cpuNs{0};
    std::vector<std::thread> pool;
    for (int t = 0; t < threads; t++) {
        pool.emplace_back([&] {
            std::vector<uint16_t> ids(CELLS), l0(CELLS), l1(CELLS), l2(CELLS);
            uint16_t* ls[CV_MAX_LAYERS] = {l0.data(), l1.data(), l2.data()};
            for (size_t i; (i = next.fetch_add(1)) < p.jobs.size();) {
                const Job& j = p.jobs[i];
                const auto a = Clock::now();
                std::fill(l0.begin(), l0.end(), 0);
                std::fill(l1.begin(), l1.end(), 0);
                std::fill(l2.begin(), l2.end(), 0);
                const int32_t ret = g->generate_cube(g->user, j.cx, j.cy, j.cz, ids.data(), ls);
                if (ret < 0) {
                    std::fprintf(stderr, "generate failed: %s\n", sbw_last_error());
                    std::exit(3);
                }
                cpuNs += std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - a).count();
                done[i] = msSince(t0);
                if (!digest.empty()) {
                    if (ret == 0) {
                        std::fill(ids.begin() + 1, ids.end(), ids[0]);
                        std::fill(l0.begin(), l0.end(), 0);
                        std::fill(l1.begin(), l1.end(), 0);
                        std::fill(l2.begin(), l2.end(), 0);
                    }
                    uint64_t h = hashWords(static_cast<uint64_t>(j.cx * 7 + j.cy * 131 + j.cz * 8191), ids.data(), ids.size() * 2);
                    h = hashWords(h, l0.data(), l0.size() * 2);
                    h = hashWords(h, l1.data(), l1.size() * 2);
                    digest[i] = hashWords(h, l2.data(), l2.size() * 2);
                }
            }
        });
    }
    for (auto& t : pool) t.join();
    r.cpuMs = static_cast<double>(cpuNs.load()) / 1e6;
    for (size_t i = 0; i < p.jobs.size(); i++) {
        const Job& j = p.jobs[i];
        r.all = std::max(r.all, done[i]);
        if (j.cls == 2) continue;
        r.nWanted++;
        r.wanted = std::max(r.wanted, done[i]);
        if (j.colDist <= 64) r.ring64 = std::max(r.ring64, done[i]);
        if (j.cls == 0 && j.colDist <= 64) r.a64 = std::max(r.a64, done[i]);
        if (j.cls == 0 && j.colDist <= 128) r.a128 = std::max(r.a128, done[i]);
        if (j.colDist <= 128) r.ring128 = std::max(r.ring128, done[i]);
        if (j.colDist <= 256) r.ring256 = std::max(r.ring256, done[i]);
    }
    r.nAll = p.jobs.size();
    if (!digest.empty()) {
        std::FILE* f = std::fopen(g_digest.c_str(), "w");
        for (const auto& [cx, cz] : p.columns) {
            std::fprintf(f, "top %lld %lld %016llx\n", static_cast<long long>(cx), static_cast<long long>(cz),
                         static_cast<unsigned long long>(tops[colKey(cx, cz)].hash));
        }
        std::vector<std::string> lines;
        for (size_t i = 0; i < p.jobs.size(); i++) {
            char buf[128];
            std::snprintf(buf, sizeof(buf), "cube %lld %lld %lld %016llx", static_cast<long long>(p.jobs[i].cx),
                          static_cast<long long>(p.jobs[i].cy), static_cast<long long>(p.jobs[i].cz),
                          static_cast<unsigned long long>(digest[i]));
            lines.push_back(buf);
        }
        std::sort(lines.begin(), lines.end());
        for (const auto& l : lines) std::fprintf(f, "%s\n", l.c_str());
        std::fclose(f);
        g_digest.clear();
    }
    return r;
}

void printStream(const char* label, const StreamResult& r) {
    std::printf("  %-40s plan %5.0f | ellipsoid r64 %5.0f r128 %5.0f | rings (a+b) r64 %5.0f r128 %5.0f r256 %5.0f | "
                "wanted %5.0f (%zu) all %5.0f (%zu) ms | gen cpu %.0f ms\n",
                label, r.plan, r.a64, r.a128, r.ring64, r.ring128, r.ring256, r.wanted, r.nWanted, r.all, r.nAll, r.cpuMs);
    std::fflush(stdout);
}

void columns(int64_t seed, bool half, int n) {
    Gen gen(makeConfig(seed, half, false));
    Generator& g = gen.g();
    /* per column, single thread, cold, sequential area n x n starting far from anything built */
    double tBase = 0, tCarve = 0, tCarved = 0, tDeep = 0, tSurf = 0, tCubes = 0, tBand = 0;
    int bandCubes = 0;
    const int64_t ox = 300, oz = -200;
    int side = static_cast<int>(std::lround(std::sqrt(static_cast<double>(n))));
    std::vector<uint16_t> ids(CELLS), l0(CELLS), l1(CELLS), l2(CELLS);
    uint16_t* ls[CV_MAX_LAYERS] = {l0.data(), l1.data(), l2.data()};
    for (int i = 0; i < side * side; i++) {
        const int64_t cx = ox + i % side, cz = oz + i / side;
        auto a = Clock::now();
        g.base(cx, cz);
        tBase += msSince(a);
        a = Clock::now();
        g.carve(cx, cz);
        tCarve += msSince(a);
        a = Clock::now();
        g.carved(cx, cz);
        tCarved += msSince(a);
        a = Clock::now();
        g.deep(cx, cz);
        tDeep += msSince(a);
        a = Clock::now();
        const Top t = topOf(gen.t, cx, cz);
        tSurf += msSince(a);
        a = Clock::now();
        for (int64_t cy = CMIN; cy <= CMAX; cy++) gen.t->generate_cube(gen.t->user, cx, cy, cz, ids.data(), ls);
        tCubes += msSince(a);
        a = Clock::now();
        const int64_t loY = std::min(t.skyMin, t.topMin) - 16, hiY = t.topMax + 8;
        for (int64_t cy = floorDiv64(loY, 32); cy <= floorDiv64(hiY - 1, 32); cy++) {
            gen.t->generate_cube(gen.t->user, cx, cy, cz, ids.data(), ls);
            bandCubes++;
        }
        tBand += msSince(a);
    }
    const double k = side * side;
    std::printf("  per column (%dx%d area, 1 thread, stages cold in order): base %.2f, top carve %.2f, carved %.2f, "
                "deep %.2f, column_surface %.2f, 32 cubes %.2f (band cubes again, warm tiles: %.3f per cube) ms\n",
                side, side, tBase / k, tCarve / k, tCarved / k, tDeep / k, tSurf / k, tCubes / k, tBand / bandCubes);
    /* whole column cold from scratch: a fresh generator, public API only */
    Gen fresh(makeConfig(seed + 1, half, false));
    auto a = Clock::now();
    for (int i = 0; i < side * side; i++) {
        const int64_t cx = ox + i % side, cz = oz + i / side;
        for (int64_t cy = CMIN; cy <= CMAX; cy++) fresh.t->generate_cube(fresh.t->user, cx, cy, cz, ids.data(), ls);
    }
    const double whole = msSince(a);
    /* the first cube of an untouched far region */
    a = Clock::now();
    fresh.t->generate_cube(fresh.t->user, 5000, 1, 5000, ids.data(), ls);
    const double first = msSince(a);
    std::printf("  whole column cold (32x32x1024, 1 thread, %dx%d area): %.1f ms per column; first cube of an untouched "
                "region %.1f ms\n", side, side, whole / k, first);
    std::fflush(stdout);
}

} // namespace

int main(int argc, char** argv) {
    int threads = 26, radius = 384, reps = 1, ncols = 16;
    int64_t seed = 20261117;
    bool half = false, skipStream = false, skipColumns = false;
    for (int i = 1; i < argc; i++) {
        const std::string a = argv[i];
        if (a == "--threads" && i + 1 < argc) threads = std::atoi(argv[++i]);
        else if (a == "--radius" && i + 1 < argc) radius = std::atoi(argv[++i]);
        else if (a == "--seed" && i + 1 < argc) seed = std::atoll(argv[++i]);
        else if (a == "--reps" && i + 1 < argc) reps = std::atoi(argv[++i]);
        else if (a == "--columns" && i + 1 < argc) ncols = std::atoi(argv[++i]);
        else if (a == "--half") half = true;
        else if (a == "--cache" && i + 1 < argc) g_cache = std::atoi(argv[++i]);
        else if (a == "--digest" && i + 1 < argc) g_digest = argv[++i];
        else if (a == "--no-stream") skipStream = true;
        else if (a == "--no-columns") skipColumns = true;
        else {
            std::fprintf(stderr, "unknown option %s\n", a.c_str());
            return 2;
        }
    }
    std::printf("worldgen bench: seed %lld, %s, %d workers, radius %d, cache_columns %d\n", static_cast<long long>(seed),
                half ? "1:2" : "1:4", threads, radius, g_cache);
    if (!skipColumns) columns(seed, half, ncols);
    if (skipStream) return 0;
    /* viewer: surface of column (0,0) centre, found with a separate generator (keeps the timed one cold) */
    int64_t vy;
    {
        Gen probe(makeConfig(seed, half));
        sbw_column_info info;
        sbw_query_column(probe.t, 16, 16, &info);
        vy = std::max<int64_t>(info.top, info.water_level == SBW_NO_WATER ? info.top : info.water_level) + 2;
    }
    for (int rep = 0; rep < reps; rep++) {
        for (int variant = 0; variant < 3; variant++) {
#ifndef SBW_PREFETCH_OFF
            if (variant == 1) continue; /* no prefetch in this library */
#endif
            const bool workers = variant == 2, prefetch = variant == 0;
            const char* name = variant == 0 ? "tops on planner thread" : variant == 1 ? "tops on planner, prefetch off" : "tops on workers";
            Gen gen(makeConfig(seed + rep, half, prefetch));
            char label[96];
            std::snprintf(label, sizeof(label), "cold, %s", name);
            printStream(label, runStream(gen, threads, radius, 128, 16, vy, 16, workers));
            std::snprintf(label, sizeof(label), "warm, %s", name);
            printStream(label, runStream(gen, threads, radius, 128, 16, vy, 16, workers));
        }
    }
    return 0;
}
