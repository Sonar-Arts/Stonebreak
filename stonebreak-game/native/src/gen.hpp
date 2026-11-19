/* Internal core of the cube generator (SBW ABI 3, worldgen.h). Everything here is a pure function of
 * (config, seed, coordinates); caches only memoise. Coordinates are int64 block / cube coordinates,
 * column arrays are [lz * 32 + lx] like the engine's cv_* ABI. */
#pragma once

#include "stonebreak/worldgen.h"
#include "cenda/voxel_world.h"
#include "cenda/noise_nodes.hpp"
#include "java_compat.hpp"
#include "jsimplex.hpp"

#include <algorithm>
#include <array>
#include <stdexcept>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace sbw {

using stonebreak::DeterministicRandom;
using stonebreak::JavaRandom;
using stonebreak::JavaSimplex;
using stonebreak::floorDiv64;
using stonebreak::floorMod64;

constexpr int CE = 32;            /* cube edge */
constexpr int COLS = CE * CE;     /* columns per column cube */
constexpr int CELLS = CE * CE * CE;
constexpr int CHUNK = 16;         /* the old chunk grid: spawn hashes of every carver/feature use it */
constexpr int32_t NO_WATER = SBW_NO_WATER;
/* CarveTile.flood of a column that holds no carved sea water */
constexpr int32_t FLOOD_NONE = INT32_MAX;

inline int colIdx(int lx, int lz) { return lz * CE + lx; }
inline int cellIdx(int lx, int ly, int lz) { return (ly << 10) | (lz << 5) | lx; }

void setError(const std::string& msg);

/* ---------------------------------------------------------------------------------- config */
struct Config {
    int kind = SBW_KIND_STANDARD;
    int64_t seed = 0;
    int64_t minY = -384;
    int64_t maxY = 640;
    int32_t sea = 64;
    uint32_t flags = 0;
    double hmPerBlock = 60.0;
    double rates[4]{};
    double knots[5]{};
    std::vector<uint16_t> blockFlags;
    int waterLayer = -1, stateLayer = -1, snowLayer = -1;
    sbw_column_fn columnFn = nullptr;
    void* columnUser = nullptr;
    int32_t cacheColumns = 4096;
    int prefetchThreads = 0;        /* column_surface prefetch helpers (worldgen.h surface_prefetch) */
    uint16_t waterSource = 16;
    uint16_t waterRiver[8]{};
    uint16_t stalagmiteState[24]{};
    uint16_t stalagmitePart[2]{};
    uint16_t id[SBW_BLOCK_CAP]{};
    uint16_t rockBank = 0;          /* SBW_FLAG_BOULDERS: registered SBRB bank id, 0 = procedural only */

    /* derived */
    int32_t floorY() const { return static_cast<int32_t>(minY); }
    int32_t topY() const { return static_cast<int32_t>(maxY); }
    /* Lowest y any carver may open: above the hashed bedrock band. */
    int32_t carveFloor() const { return static_cast<int32_t>(minY) + 5; }
    uint16_t flagsOf(uint16_t block) const { return block < blockFlags.size() ? blockFlags[block] : 0; }
};

/* --------------------------------------------------------------------------- height curve */
/* HeightCurveTable: metres -> blocks, rates blended between ocean/lowland/midland/highland, integrated
 * (trapezoid, 1 m), 0 m anchored on sea level. */
struct HeightCurve {
    static constexpr double MIN_M = -4000.0;
    static constexpr double MAX_M = 7000.0;
    static constexpr double STEP_M = 1.0;
    std::vector<double> table;
    void build(const double rates[4], const double knots[5], double seaLevel);
    [[nodiscard]] double blocks(double e) const;
};

/* ----------------------------------------------------------------------------- noise nodes */
struct Node2 {
    FastNoise::SmartNode<> node;
    int32_t seed = 0;
    void init(int64_t seedL, int octaves, float gain, float lacunarity, float freq);
    /* Java channel axis convention: FastNoise2 X carries world Z, Y carries world X. */
    [[nodiscard]] float at(float x, float z) const { return node->GenSingle2D(z, x, seed); }
};

struct Node3 {
    FastNoise::SmartNode<> node;
    int32_t seed = 0;
    float squash = 1.0f;
    void init(int64_t seedL, int octaves, float gain, float lacunarity, float freq, float ySquash);
    /* Density positions: X = world z, Y = world x, Z = y * squash, all exact float arithmetic, one
     * code path (GenPositionArray3D) for batches and single points so they agree bit for bit. */
    void points(float* out, int count, const float* zs, const float* xs, const float* ys) const;
    [[nodiscard]] float point(int64_t x, int64_t y, int64_t z) const;
};

inline int32_t javaLongHash(int64_t v) {
    const auto u = static_cast<uint64_t>(v);
    return static_cast<int32_t>(static_cast<uint32_t>(u ^ (u >> 32)));
}

/* ------------------------------------------------------------------------------ slice masks */
/* One 32^3 bit slice of a column's carve state (bit = cellIdx). */
struct Slice {
    int64_t cy = 0;
    uint64_t bits[CELLS / 64]{};
};

/* Sparse per-column-cube bit volume, slices by cube y. */
struct MaskSet {
    std::vector<std::unique_ptr<Slice>> slices; /* sorted by cy */

    [[nodiscard]] const Slice* find(int64_t cy) const;
    Slice* getOrCreate(int64_t cy);
    void set(int lx, int64_t y, int lz) {
        Slice* s = getOrCreate(y >> 5);
        const int i = cellIdx(lx, static_cast<int>(y & 31), lz);
        s->bits[i >> 6] |= 1ULL << (i & 63);
    }
    void clear(int lx, int64_t y, int lz);
    [[nodiscard]] bool test(int lx, int64_t y, int lz) const {
        const Slice* s = find(y >> 5);
        if (s == nullptr) return false;
        const int i = cellIdx(lx, static_cast<int>(y & 31), lz);
        return (s->bits[i >> 6] >> (i & 63)) & 1ULL;
    }
    [[nodiscard]] bool empty() const { return slices.empty(); }
    void orWith(const MaskSet& o);
    void andNot(const MaskSet& o);
    /* y range [lo, hi) covering every slice (lo = hi = 0 when empty). */
    void yRange(int64_t& lo, int64_t& hi) const;
    [[nodiscard]] size_t bytes() const { return slices.size() * sizeof(Slice); }
};

/* ----------------------------------------------------------------------------------- tiles */
/* L0: per column cube - raw heights, water, biomes (Standard: noise; profile kind: upcall). */
struct BaseTile {
    int64_t cx = 0, cz = 0;
    int32_t h[COLS]{};        /* raw height: first y above the ground */
    int32_t refH[COLS]{};     /* the same column under the 1:4 reference curve (biome/peak rules) */
    int32_t water[COLS]{};    /* first non-water y, NO_WATER when dry */
    uint8_t biome[COLS]{};
    uint16_t surface[COLS]{}; /* block at h - 1 before carving (OceanFloor/LakeBed/DesertGravel mix) */
    float temp[COLS]{}, moist[COLS]{};
    bool rivers = false;
    int32_t riverFloor[COLS]{}, riverRoof[COLS]{};
    int8_t riverFlow[COLS]{};
    uint16_t carve[COLS]{};   /* profile kind: carve-node codes (sbw_column_profile.carve), 0 otherwise */
    /* profile kind (SBW ABI 5): surface channels by SBW_SURF_* slot; SBW_SURF_NO_DATA (255) everywhere else */
    uint8_t surf[SBW_SURFACE_SLOTS][COLS];
    BaseTile() { std::fill(&surf[0][0], &surf[0][0] + sizeof(surf), static_cast<uint8_t>(SBW_SURF_NO_DATA)); }
    int32_t minH = 0, maxH = 0;
    int32_t maxTop = 0;       /* max over columns of max(h, water) */
};

/* L0 apron (SBW_FLAG_SEA_FLOOR): a column cube's raw heights + water exactly as its BaseTile holds them, from the
 * profile upcall; a neighbour's sea-floor pass reads it when this cube's base tile is not built yet (no 32-block
 * seams: slope and shelter look across the tile edge; review-native F3, FX1 2026-11-19). */
struct HeightWater {
    int32_t h[COLS]{};
    int32_t water[COLS]{};
};

/* L1: the top-layer carve of a column cube. */
struct CarveTile {
    MaskSet cave;             /* worms, caverns, megacaverns, ravines, sinkholes, sea stacks, towers */
    MaskSet formation;        /* pruned stalagmite/stalactite pillars (STONE) */
    int32_t guard[COLS]{};    /* WaterGuard plane (INT32_MAX = open) */
    int32_t table[COLS]{};    /* CaveWaterTable per column */
    /* sea-flooded carve (SEA_STACKS cuts connected to the open sea): carved cells with flood <= y < cfg.sea are
     * WATER, not AIR. FLOOD_NONE = none (every other carver, every legacy world). */
    int32_t flood[COLS];
    /* Density3D seal beside a sea-flooded cut (the shelf of the column or of a face neighbour, FLOOD_NONE = none):
     * cells with seal - 1 <= y < cfg.sea are never density caves, so the flooded water stays contained */
    int32_t seal[COLS];
    CarveTile() {
        std::fill_n(flood, COLS, FLOOD_NONE);
        std::fill_n(seal, COLS, FLOOD_NONE);
    }
};

/* L1 surface: the carved surface of a column cube (small; FastLOD keeps many). */
struct CarvedTile {
    int32_t carvedH[COLS]{};  /* one past the highest solid block (river voids and water excluded) */
    uint8_t carved[COLS]{};   /* carvedH < raw height */
    uint16_t top[COLS]{};     /* the generated block at carvedH - 1 (before pools) */
    uint8_t sea[COLS]{};      /* 1: an open sea-flooded carve - sea water from carvedH up to cfg.sea (CarveTile.flood) */
};

/* L1b: swamp pools of a column cube (need the neighbours' carved surfaces). */
struct PoolTile {
    uint8_t depth[COLS]{};
};

/* L2: deep cave layers (already shifted to world y, clipped, formations removed). */
struct DeepTile {
    MaskSet carve;
};

/* Cached carver walks (carvers.hpp): one per carving layer and 16-block source chunk. */
struct WormEllipsoid {
    int16_t dx, dz;   /* rounded centre relative to the walk's origin (ox, oz) */
    int32_t y;        /* rounded centre, FRAME y */
    float r;
};
struct WormWalk {
    int64_t ox = 0, oz = 0;  /* the source chunk's block origin */
    std::vector<WormEllipsoid> e;
    int64_t minX = INT64_MAX, maxX = INT64_MIN, minZ = INT64_MAX, maxZ = INT64_MIN; /* x/z reach incl. radius */
};
struct RavineSlice {
    int64_t wx, wz;
    float halfWidth, flare;
    int cutDepth;
    float sinDip, shear, nx, nz;
};
struct RavineWalk {
    std::vector<RavineSlice> s;
};

/* L3: column_surface of a column cube (features included). */
struct SurfaceTile {
    int64_t top[COLS]{};
    int64_t sky[COLS]{};
};

/* ---------------------------------------------------------------------------- once-cache */
/* Bounded, sharded, thread-safe memo: concurrent callers for one key share one build; builds of
 * different keys run in parallel. Values are immutable once built; eviction drops the cache's
 * reference only (holders keep theirs). tryGet never waits for another thread's build, so a caller
 * that needs many keys builds the free ones first and waits only for the rest at the end. */
template <typename V>
class TileCache {
public:
    using Ptr = std::shared_ptr<const V>;
    using Builder = std::function<std::shared_ptr<V>(int64_t, int64_t)>;
    using Builder3 = std::function<std::shared_ptr<V>(int64_t, int64_t, int64_t)>;

    explicit TileCache(size_t capacity) : perShard_(capacity / SHARDS + 1) {}

    Ptr get(int64_t a, int64_t b, const Builder& build) {
        Ptr out;
        fetch(Key{a, b, 0}, [&] { return build(a, b); }, true, out);
        return out;
    }
    /* three-part keys (cubes) */
    Ptr get3(int64_t a, int64_t b, int64_t c, const Builder3& build) {
        Ptr out;
        fetch(Key{a, b, c}, [&] { return build(a, b, c); }, true, out);
        return out;
    }

    /* the key is cached or being built (no build, no stamp update) */
    bool contains(int64_t a, int64_t b) {
        const Key k{a, b, 0};
        Shard& sh = shards_[keyHash(k) % SHARDS];
        std::lock_guard<std::mutex> lock(sh.m);
        return sh.map.find(k) != sh.map.end();
    }
    /* the value when it is built and cached, else null (never builds or waits) */
    Ptr peek3(int64_t a, int64_t b, int64_t c) {
        const Key k{a, b, c};
        Shard& sh = shards_[keyHash(k) % SHARDS];
        std::shared_ptr<Entry> e;
        {
            std::lock_guard<std::mutex> lock(sh.m);
            auto it = sh.map.find(k);
            if (it == sh.map.end()) return nullptr;
            e = it->second;
            e->stamp = ++sh.clock;
        }
        std::lock_guard<std::mutex> lock(e->m);
        return e->ready ? e->value : nullptr;
    }

    /* false = another thread is building the key right now (out untouched); else as get(). */
    bool tryGet(int64_t a, int64_t b, const Builder& build, Ptr& out) {
        return fetch(Key{a, b, 0}, [&] { return build(a, b); }, false, out);
    }

    size_t size() {
        size_t n = 0;
        for (auto& s : shards_) {
            std::lock_guard<std::mutex> lock(s.m);
            n += s.map.size();
        }
        return n;
    }

private:
    struct Key {
        int64_t a, b, c;
        bool operator==(const Key& o) const { return a == o.a && b == o.b && c == o.c; }
    };

    template <typename B>
    bool fetch(const Key& key, B&& build, bool wait, Ptr& out) {
        Shard& sh = shards_[keyHash(key) % SHARDS];
        std::shared_ptr<Entry> e;
        bool builder = false;
        {
            std::lock_guard<std::mutex> lock(sh.m);
            auto it = sh.map.find(key);
            if (it != sh.map.end()) {
                e = it->second;
                e->stamp = ++sh.clock;
            } else {
                e = std::make_shared<Entry>();
                e->stamp = ++sh.clock;
                sh.map.emplace(key, e);
                builder = true;
                if (sh.map.size() > perShard_) evict(sh);
            }
        }
        if (builder) {
            std::shared_ptr<V> v;
            try {
                v = build();
            } catch (...) {
                v = nullptr;
            }
            {
                std::lock_guard<std::mutex> lock(e->m);
                e->value = v;
                e->ready = true;
            }
            e->cv.notify_all();
            if (!v) { /* failed builds are not cached */
                std::lock_guard<std::mutex> lock(sh.m);
                auto it = sh.map.find(key);
                if (it != sh.map.end() && it->second == e) sh.map.erase(it);
            }
            out = v;
            return true;
        }
        std::unique_lock<std::mutex> lock(e->m);
        if (!wait && !e->ready) return false;
        e->cv.wait(lock, [&] { return e->ready; });
        out = e->value;
        return true;
    }

    static constexpr size_t SHARDS = 64;
    static uint64_t keyHash(const Key& k) {
        return stonebreak::mix64((stonebreak::mix64(static_cast<uint64_t>(k.a)) + static_cast<uint64_t>(k.c)) * UINT64_C(0x9E3779B97F4A7C15)
                                 ^ stonebreak::mix64(static_cast<uint64_t>(k.b)));
    }
    struct KeyHash {
        size_t operator()(const Key& k) const { return static_cast<size_t>(keyHash(k) >> 7); }
    };
    struct Entry {
        std::mutex m;
        std::condition_variable cv;
        bool ready = false;
        Ptr value;
        uint64_t stamp = 0;
    };
    struct Shard {
        std::mutex m;
        std::unordered_map<Key, std::shared_ptr<Entry>, KeyHash> map;
        uint64_t clock = 0;
    };
    void evict(Shard& sh) {
        /* drop the oldest finished quarter */
        std::vector<std::pair<uint64_t, Key>> order;
        order.reserve(sh.map.size());
        for (auto& [k, e] : sh.map) {
            std::lock_guard<std::mutex> lock(e->m);
            if (e->ready) order.emplace_back(e->stamp, k);
        }
        std::sort(order.begin(), order.end(), [](auto& x, auto& y) { return x.first < y.first; });
        const size_t drop = std::max<size_t>(1, sh.map.size() / 4);
        for (size_t i = 0; i < order.size() && i < drop; i++) sh.map.erase(order[i].second);
    }
    size_t perShard_;
    std::array<Shard, SHARDS> shards_;
};

/* ------------------------------------------------------------------------------- features */
struct FeatureCell {
    int64_t x, y, z;
    uint16_t id;
    uint16_t snow;  /* snow layers for SNOW cells */
};

/* One surface feature (tree, cactus, flower, snow, surface swap, clay patch). */
struct Feature {
    enum Kind : uint8_t { TREE, PLANT, SWAP, CLAY, BOULDER } kind = TREE;  /* BOULDER: boulders.cpp */
    int64_t ax = 0, ay = 0, az = 0;  /* anchor (column, surface y) */
    uint64_t priority = 0;
    std::vector<FeatureCell> cells;  /* trees: unique cells, last write won */
};

/* The raw terrain of one cube (terrainBlockAt(x, y, z, false) of every cell: before pools, stalagmites,
 * ores and limestone) - what generate_cube starts from and what a neighbour's stalagmite / limestone
 * shell reads. Kept a short while: neighbours generated around the same time share it. */
struct RawCube {
    int64_t x0 = 0, y0 = 0, z0 = 0;
    uint16_t value = 0;          /* the block of a uniform cube */
    std::vector<uint16_t> ids;   /* CV_CELL_INDEX order; empty = uniform */
    [[nodiscard]] uint16_t at(int64_t x, int64_t y, int64_t z) const {
        return ids.empty() ? value : ids[static_cast<size_t>(cellIdx(static_cast<int>(x - x0), static_cast<int>(y - y0), static_cast<int>(z - z0)))];
    }
};

/* The kept ore spheres one 16-block source chunk spawns: [coal, iron][top stream, deep stream]. */
struct OreSphere {
    float x, y, z, r;
};
struct OreSource {
    std::vector<OreSphere> spheres[2][2];
};

/* LimestoneGenerator's per-column fields of a column cube (karst region, strata bed fill, strata warp). */
struct LimeColumns {
    float karst[COLS]{}, bedFill[COLS]{}, warp[COLS]{};
    bool anyKarst = false;
};

/* the features anchored in one column cube */
struct FeatureTile {
    std::vector<Feature> fs;
};
struct FeatureSet {
    std::vector<std::shared_ptr<const FeatureTile>> tiles; /* keep-alive */
    std::vector<const Feature*> fs;
};

/* ------------------------------------------------------------------------------ generator */
struct CarveCtx;
class Generator;
class RockBank; /* rocks.hpp */

/* Helper threads that build the column surfaces around a missed column_surface call (worldgen.h
 * SBW_PREFETCH_OFF). Started on the first request, joined on destruction. */
class SurfacePrefetcher {
public:
    SurfacePrefetcher(Generator& g, int threads) : g_(g), threads_(threads) {}
    ~SurfacePrefetcher();
    SurfacePrefetcher(const SurfacePrefetcher&) = delete;
    SurfacePrefetcher& operator=(const SurfacePrefetcher&) = delete;
    /* queue the column cubes within `radius` of (cx, cz) whose surfaces are not cached, nearest first */
    void around(int64_t cx, int64_t cz, int radius);

private:
    void run();
    Generator& g_;
    const int threads_;
    std::mutex m_;
    std::condition_variable cv_;
    std::vector<std::pair<int64_t, int64_t>> queue_;   /* LIFO: the latest request's ring first */
    std::vector<std::thread> pool_;
    bool stop_ = false;
};

class Generator {
public:
    explicit Generator(const Config& c);
    ~Generator();
    Generator(const Generator&) = delete;
    Generator& operator=(const Generator&) = delete;

    const Config cfg;
    cv_generator table{};
    uint64_t serial = 0; /* distinguishes generators in thread-local memos (addresses get reused) */

    /* noise */
    HeightCurve curve, refCurve;
    Node2 shape[11];
    Node2 moisture, temperature, wobble;
    Node3 heading, radius;
    Node3 cheese, spag1, spag2, crag;
    std::vector<std::pair<double, double>> cheeseKnots;
    JavaSimplex ravinePath, sinkRim, limePatch, limeRegion, limeWarp;
    DeterministicRandom drand;
    double shapeScale = 1.0; /* horizontal_m_per_block / 60: shape-space units per block */

    /* tiles */
    std::shared_ptr<const BaseTile> base(int64_t cx, int64_t cz);
    std::shared_ptr<const CarveTile> carve(int64_t cx, int64_t cz);
    std::shared_ptr<const CarvedTile> carved(int64_t cx, int64_t cz);
    std::shared_ptr<const PoolTile> pools(int64_t cx, int64_t cz);
    /* union of the deep layers (tests / tools); generation reads deepLayer per layer */
    std::shared_ptr<const DeepTile> deep(int64_t cx, int64_t cz);
    /* deep layer i (0..SBW_DEEP_LAYERS-1, 100 * (i + 1) blocks down): its cells all lie below
     * h(column) - 100 * (i + 1), so a cell above that never needs the layer */
    std::shared_ptr<const DeepTile> deepLayer(int i, int64_t cx, int64_t cz);
    std::shared_ptr<const SurfaceTile> surfaceTile(int64_t cx, int64_t cz);

    /* point queries (through the tiles) */
    int32_t heightAt(int64_t x, int64_t z);
    int32_t waterAt(int64_t x, int64_t z);

    /* the generated terrain block at a point (exactly what generate_cube writes before ores,
     * stalagmites and limestone; pools included when asked) */
    uint16_t terrainBlockAt(int64_t x, int64_t y, int64_t z, bool withPools = true);
    bool terrainAirAt(int64_t x, int64_t y, int64_t z) { return terrainBlockAt(x, y, z, false) == cfg.id[SBW_AIR]; }
    /* point density sampler for decideBlock */
    struct PointDensity {
        const Generator& g;
        int64_t x, y, z;
        float v[4];
        bool have[4] = {false, false, false, false};
        float operator()(int k) {
            if (!have[k]) {
                const Node3& n = k == 0 ? g.cheese : k == 1 ? g.spag1 : k == 2 ? g.spag2 : g.crag;
                v[k] = n.point(x, y, z);
                have[k] = true;
            }
            return v[k];
        }
    };

    /* entry points */
    int32_t generateCube(int64_t cx, int64_t cy, int64_t cz, uint16_t* ids, uint16_t* const* layers);
    int32_t populate(const cv_gen_api* api, cv_world* world, int64_t cx, int64_t cy, int64_t cz);
    int32_t columnSurface(int64_t cx, int64_t cz, int64_t* sky, int64_t* top);

    /* features anchored in columns [x0, x1) x [z0, z1), computed */
    void collectFeatures(int64_t x0, int64_t z0, int64_t x1, int64_t z1, std::vector<Feature>& out);
    /* SBW_FLAG_BOULDERS: the boulders anchored in columns [x0, x1) x [z0, z1) (boulders.cpp) */
    void collectBoulders(int64_t x0, int64_t z0, int64_t x1, int64_t z1, std::vector<Feature>& out);
    /* the world's rock shape bank (null = procedural shapes only) */
    std::shared_ptr<const RockBank> rocks;
    /* the same set from the per-column-cube feature tiles (a column's features are computed once and
     * shared by every column_surface and populate call that reads them; resolution is independent of
     * the enumeration order) */
    std::shared_ptr<const FeatureTile> features(int64_t cx, int64_t cz);
    void gatherFeatures(int64_t x0, int64_t z0, int64_t x1, int64_t z1, FeatureSet& out);
    /* tree probe of one column (FastLOD + population share it); returns kind or -1 */
    int treeAt(int64_t x, int64_t z, uint16_t surfaceBlock, int biome, int& trunk);
    /* the pre-ABI-5 rule (biome base rate x forest lattice); treeAt uses it where a column carries no vegetation */
    int treeAtRule(int64_t x, int64_t z, uint16_t surfaceBlock, int biome, int& trunk);
    /* vegetation channels (SBW ABI 5): veg = surface slots 0..7 of the column; size = tree size class 0..2 */
    int treeAtVeg(int64_t x, int64_t z, uint16_t surfaceBlock, int biome, const uint8_t* veg, int& trunk, int& size);

    /* density solidity (Density3D.isSolid); dens(k) yields channel k (0 cheese, 1/2 spaghetti,
     * 3 crag) at the cell, sampled lazily */
    template <typename D>
    bool densitySolid(D&& dens, int64_t y, int32_t h, int biome, int32_t refH, int32_t wtable, int32_t guard,
                      int32_t seal = FLOOD_NONE) const;
    /* the terrain block decision shared by cube fills and point queries */
    template <typename D>
    uint16_t decideBlock(const BaseTile& b, const CarveTile& c, int col, int lx, int lz, int64_t x, int64_t y,
                         int64_t z, D&& dens) const;
    /* the same decision with the carve masks and the density solidity as lazy predicates (inFormation(),
     * inCave(), solid() = densitySolid of the cell), for callers that already know them (cube fills keep
     * per-slice mask cursors and evaluate densitySolid over batches) */
    template <typename F, typename V, typename S>
    uint16_t decideBlockM(const BaseTile& b, F&& inFormation, V&& inCave, S&& solid, int col, int64_t x, int64_t y,
                          int64_t z, int32_t flood = FLOOD_NONE) const;
    double cheeseThreshold(double depth) const;
    /* cheeseThreshold(depth) for an integer depth: tabulated (same values) for 0..CHEESE_TABLE-1 */
    static constexpr int CHEESE_TABLE = 256;
    double cheeseTable[CHEESE_TABLE]{};
    double cheeseThresholdAt(int64_t depth) const {
        return depth >= 0 && depth < CHEESE_TABLE ? cheeseTable[depth] : cheeseThreshold(static_cast<double>(depth));
    }
    bool densityNeedsCrag(int biome) const { return biome == SBW_STONY_PEAKS; }
    int32_t caveFloor() const { return static_cast<int32_t>(cfg.minY) + 8; }

    /* block helpers */
    uint16_t subsurfaceId(int biome) const;
    uint16_t surfaceBaseId(int biome) const;
    uint16_t surfaceAt(int biome, int64_t x, int64_t z, int32_t waterDepth) const;
    /* SBW_FLAG_SEA_FLOOR: re-pick the ocean columns' surface of a profile base tile (depth, slope, shelter, cliffs).
     * Slope and shelter read the neighbour cubes' heights / water across the tile edge (built base tiles, else
     * apronCache); false (error set) when a neighbour's profile upcall fails. */
    bool seaFloor(BaseTile& t);
    /* SBW_FLAG_LAND_SURFACE: re-pick dry land columns' surface from the surface-detail node's channels (rock exposure,
     * scree, roughness; agent L). Columns without channel data keep their surface. */
    void landSurface(BaseTile& t) const;
    bool isBedrock(int64_t x, int64_t y, int64_t z) const;
    bool magmaAt(int64_t x, int64_t y, int64_t z) const;

    /* caches */
    TileCache<BaseTile> baseCache;
    TileCache<CarveTile> carveCache;
    TileCache<CarvedTile> carvedCache;
    TileCache<PoolTile> poolCache;
    std::unique_ptr<TileCache<DeepTile>> deepCache[SBW_DEEP_LAYERS];
    TileCache<SurfaceTile> surfaceCache;

    /* carving layers: 0 = the top carvers (world seed), 1..SBW_DEEP_LAYERS = DeepCaveLayers */
    static constexpr int CARVE_LAYERS = 1 + SBW_DEEP_LAYERS;
    int64_t layerSeed[CARVE_LAYERS]{};
    int32_t layerDrop[CARVE_LAYERS]{};
    std::unique_ptr<TileCache<WormWalk>> wormCache[CARVE_LAYERS];
    TileCache<RavineWalk> ravineCache;
    TileCache<FeatureTile> featureCache;
    TileCache<RawCube> rawCache;      /* cubes: (cx, cy, cz) */
    TileCache<OreSource> oreCache;    /* 16-block source chunks */
    TileCache<LimeColumns> limeCache;
    TileCache<HeightWater> apronCache;   /* sea-floor aprons of cubes whose base tile is not built (seaFloor) */
    /* last data member: its helper threads stop before anything they use is destroyed */
    std::unique_ptr<SurfacePrefetcher> prefetch;

    std::shared_ptr<const LimeColumns> limeColumns(int64_t cx, int64_t cz);
    std::shared_ptr<const OreSource> oreSource(int64_t scx, int64_t scz);
    std::shared_ptr<const RawCube> rawCube(int64_t cx, int64_t cy, int64_t cz);
    /* cached walks of one source chunk (empty walk when the source spawns nothing) */
    std::shared_ptr<const WormWalk> wormWalk(int layer, int64_t sx, int64_t sz);
    /* replay every worm system / ravine of ctx's footprint window from the walk caches, building
     * missing walks cooperatively (keys another thread is building are waited for last) */
    void carveLayerWorms(CarveCtx& ctx, int layer);
    /* build (never wait for) the walks of a footprint's window that nobody has started - what a thread
     * does while another one builds the tile it needs */
    void helpWalks(int layer, int64_t X0, int64_t Z0);
    void carveTopRavines(CarveCtx& ctx);

    /* builders (column.cpp) */
    std::shared_ptr<BaseTile> buildBase(int64_t cx, int64_t cz);
    std::shared_ptr<HeightWater> buildApron(int64_t cx, int64_t cz);
    std::shared_ptr<CarveTile> buildCarve(int64_t cx, int64_t cz);
    std::shared_ptr<CarvedTile> buildCarved(int64_t cx, int64_t cz);
    std::shared_ptr<PoolTile> buildPools(int64_t cx, int64_t cz);
    std::shared_ptr<DeepTile> buildDeepLayer(int i, int64_t cx, int64_t cz);
    std::shared_ptr<SurfaceTile> buildSurface(int64_t cx, int64_t cz);

    /* test hook: the top-layer carve mask of an arbitrary 32 x 32 footprint (carvers.cpp) */
    void carveFootprint(int64_t x0, int64_t z0, const int32_t* heights, const int32_t* water,
                        int64_t seedLayer, bool withSurfaceCarvers, MaskSet& cave, MaskSet& formA,
                        MaskSet& formB, int32_t* flood = nullptr, int32_t* seal = nullptr);
};

/* The tiles of one column cube for point queries, fetched once and lazily (carve, deep layers and pools
 * only when a cell needs them). blockAt = Generator::terrainBlockAt for a cell of this column cube. */
struct ColumnTiles {
    Generator& g;
    int64_t cx, cz;
    std::shared_ptr<const BaseTile> base;
    std::shared_ptr<const CarveTile> carve;
    std::shared_ptr<const PoolTile> pools;
    bool poolsLoaded = false;
    std::shared_ptr<const DeepTile> deep[SBW_DEEP_LAYERS];
    bool deepLoaded[SBW_DEEP_LAYERS] = {};
    ColumnTiles(Generator& gen, int64_t tcx, int64_t tcz);
    uint16_t blockAt(int64_t x, int64_t y, int64_t z, bool withPools);
};

std::shared_ptr<OreSource> buildOreSource(Generator& g, int64_t scx, int64_t scz); /* cube.cpp */

/* thread-local "last tile" for the hot height oracle */
int32_t heightOracle(Generator& g, int64_t x, int64_t z);
int32_t waterOracle(Generator& g, int64_t x, int64_t z);

} // namespace sbw
