/* Wild blueberry bushes (SBW ABI 6, Project-Heracles; features.cpp collectFeatures).
 *
 * Plains grass anchors groups of 1..3 grown bushes, each with one of the four wild stage states, in every world kind:
 * a Standard world, and DaedalusTGM-Exp (profile kind) worlds with and without the vegetation channels. Checks: bush
 * groups appear; every bush stands on plains grass with a wild stage state; members stay within 2 blocks sideways and
 * 2 up/down of their anchor; withholding the bush id (0, an older game) places none; populate writes the bush and its
 * state into the state layer. */
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

constexpr int STATE_LAYER = 1;
constexpr uint16_t STATES[4] = {11, 12, 13, 14};

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

/* flat plains, no surface channels (a model / tile without the vegetation node) */
int32_t plainProfile(void*, int64_t, int64_t, sbw_column_profile* out) {
    base(out);
    return 1;
}

/* flat plains with grassland vegetation channels (light cover, lots of grass, some shrubs) */
int32_t grassProfile(void*, int64_t, int64_t, sbw_column_profile* out) {
    base(out);
    //                       cover height clear grass shrub moss bare wet
    static const uint8_t V[8] = {25, 24, 0, 203, 40, 0, 10, 0};
    for (int k = 0; k < 8; k++) std::memset(out->surface[k], V[k], 1024);
    return 1;
}

sbw_gen_config makeConfig(int kind, sbw_column_fn fn, bool vegetation, bool withBush) {
    sbw_gen_config c;
    sbw_config_defaults(&c);
    c.seed = 777;
    c.kind = kind;
    c.column_fn = fn;
    for (int i = 0; i < SBW_BLOCK_COUNT; i++) c.block_ids[i] = static_cast<uint16_t>(i);
    if (!withBush) c.block_ids[SBW_BLUEBERRY_BUSH] = 0;
    std::memcpy(c.blueberry_states, STATES, sizeof(STATES));
    c.state_layer = STATE_LAYER;
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

/* population target: blocks and state-layer values by cell */
struct FakeWorld {
    std::unordered_map<uint64_t, uint16_t> ids, states;
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
int32_t fwLayerSet(cv_world* w, int32_t layer, int64_t x, int64_t y, int64_t z, uint16_t v) {
    if (layer == STATE_LAYER) reinterpret_cast<FakeWorld*>(w)->states[key(x, y, z)] = v;
    return 1;
}
int32_t fwSurface(cv_world*, int64_t, int64_t, int64_t* out) { *out = 0; return 1; }
const cv_gen_api API = {fwGet, fwSet, fwLayerGet, fwLayerSet, fwSurface, {}};

int64_t floorDiv(int64_t a, int64_t b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }

bool isWildState(uint16_t s) {
    for (uint16_t w : STATES) if (s == w) return true;
    return false;
}

/* Scans an n x n area of column cubes; returns the number of bush groups (and checks each one). */
int checkKind(const char* name, int kind, sbw_column_fn fn, bool vegetation, int n) {
    Gen gen(makeConfig(kind, fn, vegetation, true));
    Gen none(makeConfig(kind, fn, vegetation, false));
    if (gen.t == nullptr || none.t == nullptr) { CHECK(false); return 0; }
    const uint16_t BUSH = SBW_BLUEBERRY_BUSH;
    int groups = 0, bushes = 0, withoutId = 0, stageHits[4] = {};
    const Feature* sample = nullptr;
    std::shared_ptr<const FeatureTile> keep;
    for (int64_t cx = 0; cx < n; cx++) {
        for (int64_t cz = 0; cz < n; cz++) {
            auto t = gen.g().features(cx, cz);
            CHECK(t != nullptr);
            if (!t) continue;
            for (const Feature& f : t->fs) {
                if (f.kind != Feature::PLANT || f.cells.empty() || f.cells[0].id != BUSH) continue;
                groups++;
                CHECK(f.cells.size() <= 3);
                for (const FeatureCell& c : f.cells) {
                    bushes++;
                    CHECK(c.id == BUSH);
                    CHECK(isWildState(c.state));
                    for (int s = 0; s < 4; s++) stageHits[s] += c.state == STATES[s];
                    CHECK(std::llabs(c.x - f.ax) <= 2 && std::llabs(c.z - f.az) <= 2 && std::llabs(c.y - f.ay) <= 2);
                    sbw_column_info info{};
                    CHECK(sbw_query_column(gen.t, c.x, c.z, &info) >= 0);
                    CHECK(info.biome == SBW_PLAINS);
                    CHECK(info.surface_block == SBW_GRASS);
                    CHECK(c.y == info.carved_height);
                }
                if (sample == nullptr) { sample = &f; keep = t; }
            }
            auto u = none.g().features(cx, cz);
            if (u) for (const Feature& f : u->fs) withoutId += !f.cells.empty() && f.cells[0].id == BUSH;
        }
    }
    CHECK(groups > 0);
    CHECK(withoutId == 0);
    int stagesSeen = 0;
    for (int s = 0; s < 4; s++) stagesSeen += stageHits[s] > 0;
    CHECK(groups < 10 || stagesSeen == 4);
    /* populate the cube owning the first group's anchor: the bush and its stage land in the world */
    int written = 0;
    if (sample != nullptr) {
        const int64_t cx = floorDiv(sample->ax - 16, 32), cz = floorDiv(sample->az - 16, 32);
        const int64_t cy = floorDiv(sample->ay - 2, 32);
        FakeWorld w;
        CHECK(gen.g().populate(&API, reinterpret_cast<cv_world*>(&w), cx, cy, cz) >= 0);
        for (const FeatureCell& c : sample->cells) {
            auto it = w.ids.find(key(c.x, c.y, c.z));
            if (it == w.ids.end() || it->second != BUSH) continue;   /* a tree or boulder may own the cell */
            written++;
            CHECK(w.states[key(c.x, c.y, c.z)] == c.state);
        }
        CHECK(written > 0);
    }
    std::printf("  %-26s %4d groups, %4d bushes (stages %d/%d/%d/%d), %d populated from the first group\n", name,
                groups, bushes, stageHits[0], stageHits[1], stageHits[2], stageHits[3], written);
    return groups;
}

}  // namespace

int main() {
    checkKind("standard", SBW_KIND_STANDARD, nullptr, false, 16);
    checkKind("profile (no channels)", SBW_KIND_PROFILE, plainProfile, false, 8);
    checkKind("profile + vegetation", SBW_KIND_PROFILE, grassProfile, true, 8);
    std::printf("blueberry_test: %d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
