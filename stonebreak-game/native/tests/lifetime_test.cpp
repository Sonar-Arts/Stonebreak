/* Generator lifetime through the public ABI (2026-11-19, the quit crash): sbw_cv_generator_destroy while other
 * threads are inside the sbw_* samplers and queries must neither free the generator under them nor let a later
 * call with the stale handle reach freed memory. In-flight calls finish (destroy waits for them), calls that start
 * after destroy began fail with -1 and a "destroyed" message, and a second destroy is a no-op.
 *
 * Without the live-handle registry in api.cpp this test dies with SIGSEGV / heap corruption (the FastLOD workers of
 * the game did exactly this at quit). */
#include "stonebreak/worldgen.h"
#include "cenda/voxel_world.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#define CHECK(c) do { if (!(c)) { std::fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

namespace {

std::atomic<int> profileCalls{0};

/* A slow, rolling profile: every column cube costs ~1 ms, like a tile cache read that may wait on the service. */
int32_t slowProfile(void*, int64_t cx, int64_t cz, sbw_column_profile* out) {
    profileCalls.fetch_add(1, std::memory_order_relaxed);
    std::this_thread::sleep_for(std::chrono::microseconds(800));
    for (int lz = 0; lz < 32; lz++) {
        for (int lx = 0; lx < 32; lx++) {
            const int c = lz * 32 + lx;
            const int64_t x = cx * 32 + lx, z = cz * 32 + lz;
            out->height[c] = 80 + static_cast<int32_t>((x * 7 + z * 3) & 15);
            out->water[c] = SBW_NO_WATER;
            out->river_floor[c] = 0;
            out->river_roof[c] = 0;
            out->biome[c] = SBW_PLAINS;
            out->river_flow[c] = -1;
            out->carve[c] = 0;
        }
    }
    return 1;
}

const cv_generator* makeGenerator() {
    sbw_gen_config cfg;
    sbw_config_defaults(&cfg);
    cfg.seed = 777;
    cfg.kind = SBW_KIND_PROFILE;
    cfg.column_fn = slowProfile;
    cfg.cache_columns = 64;   /* small: workers keep building (and upcalling) */
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) cfg.block_ids[i] = static_cast<uint16_t>(i);
    return sbw_cv_generator_create(&cfg);
}

} // namespace

int main() {
    /* destroy(NULL) and a handle the library never made are refused quietly */
    sbw_cv_generator_destroy(nullptr);

    for (int round = 0; round < 4; round++) {
        const cv_generator* g = makeGenerator();
        CHECK(g != nullptr);
        std::atomic<bool> destroying{false};
        std::atomic<bool> stop{false};
        std::atomic<int> okBefore{0}, okAfter{0}, refused{0}, otherFailures{0};
        std::atomic<bool> badMessage{false};
        std::vector<std::thread> workers;
        for (int t = 0; t < 8; t++) {
            workers.emplace_back([&, t] {
                int32_t h[16 * 16], w[16 * 16];
                uint16_t s[16 * 16];
                int32_t trees[16 * 16];
                int64_t n = 0;
                while (!stop.load()) {
                    const bool afterBegin = destroying.load();
                    /* a fresh area each call: base + carve builds (profile upcalls) every time */
                    const int64_t x0 = (static_cast<int64_t>(t) * 100000 + n * 96) * (round + 1);
                    const int64_t z0 = static_cast<int64_t>(n % 7) * 512 - 1024;
                    int32_t r;
                    switch (n % 3) {
                        case 0: r = sbw_sample_columns(g, x0, z0, 16, 6, SBW_SAMPLE_CARVED, h, w, s, trees); break;
                        case 1: {
                            sbw_column_info info;
                            r = sbw_query_column(g, x0 + 5, z0 + 9, &info);
                            break;
                        }
                        default: r = sbw_sample_columns(g, x0, z0, 16, 6, 0, h, w, s, nullptr); break;
                    }
                    n++;
                    if (r >= 0) {
                        (afterBegin ? okAfter : okBefore).fetch_add(1);
                    } else if (destroying.load()) {
                        refused.fetch_add(1);
                        const char* e = sbw_last_error();
                        if (e == nullptr || std::strstr(e, "destroyed") == nullptr) badMessage = true;
                    } else {
                        otherFailures.fetch_add(1);
                    }
                }
            });
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(150 + 50 * round));
        destroying = true;
        sbw_cv_generator_destroy(g);   /* waits for the calls in flight, then frees */
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
        stop = true;
        for (auto& w : workers) w.join();
        sbw_cv_generator_destroy(g);   /* stale handle: no-op */

        /* every call made after destroy returned is refused (calls that began before may have finished fine) */
        int32_t h[4];
        CHECK(sbw_sample_columns(g, 0, 0, 2, 1, 0, h, nullptr, nullptr, nullptr) == -1);
        CHECK(std::strstr(sbw_last_error(), "destroyed") != nullptr);
        sbw_column_info info;
        CHECK(sbw_query_column(g, 0, 0, &info) == -1);
        int32_t ch[1024];
        uint8_t cc[1024];
        CHECK(sbw_column_carve(g, 0, 0, ch, cc) == -1);
        int64_t sky[1024], top[1024];
        CHECK(sbw_column_surface(g, 0, 0, sky, top) == -1);

        std::printf("round %d: ok before %d, ok while destroying %d, refused %d, profile calls %d\n", round,
                    okBefore.load(), okAfter.load(), refused.load(), profileCalls.load());
        CHECK(okBefore.load() > 0);
        CHECK(refused.load() > 0);
        CHECK(otherFailures.load() == 0);
        CHECK(!badMessage.load());
    }
    std::printf("lifetime ok\n");
    return 0;
}
