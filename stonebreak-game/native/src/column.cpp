/* Column profiles: L0 base (noise or upcall), L1 top-layer carve + carved surface, L1b swamp pools,
 * L2 deep cave layers, plus the point queries everything else is built from. */
#include "decide.hpp"

#include <algorithm>
#include <cmath>

namespace sbw {

void buildStandardBase(Generator& g, BaseTile& t); /* shape.cpp */
void carvedDescent(Generator& g, const std::shared_ptr<const BaseTile>& base, const std::shared_ptr<const CarveTile>& carve,
                   int64_t cx, int64_t cz, CarvedTile& out); /* cube.cpp */

namespace {
thread_local std::string tlError;
std::atomic<uint64_t> nextSerial{1};
constexpr int64_t DEEP_SALT = INT64_C(0x5DEEC0DE1A7E4);
constexpr int DEEP_SPACING = 100;

/* per-thread memo of the last base tiles, keyed by generator serial (addresses are reused) */
struct OracleSlot {
    uint64_t serial = 0;
    int64_t cx = 0, cz = 0;
    std::shared_ptr<const BaseTile> tile;
};
} // namespace

void setError(const std::string& msg) { tlError = msg; }
const char* lastError() { return tlError.c_str(); }

/* ------------------------------------------------------------------------------- masks */
const Slice* MaskSet::find(int64_t cy) const {
    auto it = std::lower_bound(slices.begin(), slices.end(), cy,
                               [](const std::unique_ptr<Slice>& s, int64_t v) { return s->cy < v; });
    return (it != slices.end() && (*it)->cy == cy) ? it->get() : nullptr;
}

Slice* MaskSet::getOrCreate(int64_t cy) {
    auto it = std::lower_bound(slices.begin(), slices.end(), cy,
                               [](const std::unique_ptr<Slice>& s, int64_t v) { return s->cy < v; });
    if (it != slices.end() && (*it)->cy == cy) return it->get();
    auto s = std::make_unique<Slice>();
    s->cy = cy;
    Slice* raw = s.get();
    slices.insert(it, std::move(s));
    return raw;
}

void MaskSet::clear(int lx, int64_t y, int lz) {
    const Slice* s = find(y >> 5);
    if (s == nullptr) return;
    const int i = cellIdx(lx, static_cast<int>(y & 31), lz);
    const_cast<Slice*>(s)->bits[i >> 6] &= ~(1ULL << (i & 63));
}

void MaskSet::orWith(const MaskSet& o) {
    for (const auto& s : o.slices) {
        Slice* d = getOrCreate(s->cy);
        for (int i = 0; i < CELLS / 64; i++) d->bits[i] |= s->bits[i];
    }
}

void MaskSet::andNot(const MaskSet& o) {
    for (const auto& s : o.slices) {
        const Slice* d = find(s->cy);
        if (d == nullptr) continue;
        auto* dm = const_cast<Slice*>(d);
        for (int i = 0; i < CELLS / 64; i++) dm->bits[i] &= ~s->bits[i];
    }
}

void MaskSet::yRange(int64_t& lo, int64_t& hi) const {
    if (slices.empty()) {
        lo = hi = 0;
        return;
    }
    lo = slices.front()->cy * CE;
    hi = (slices.back()->cy + 1) * CE;
}

/* --------------------------------------------------------------------------- generator */
Generator::Generator(const Config& c)
    : cfg(c),
      baseCache(static_cast<size_t>(std::max(64, c.cacheColumns))),
      carveCache(static_cast<size_t>(std::max(64, c.cacheColumns / 8))),
      carvedCache(static_cast<size_t>(std::max(64, c.cacheColumns))),
      poolCache(static_cast<size_t>(std::max(64, c.cacheColumns / 2))),
      surfaceCache(static_cast<size_t>(std::max(64, c.cacheColumns / 2))),
      ravineCache(static_cast<size_t>(std::max(64, c.cacheColumns / 8))),
      featureCache(static_cast<size_t>(std::max(64, c.cacheColumns / 8))),
      rawCache(static_cast<size_t>(std::max(64, c.cacheColumns / 8))),
      oreCache(static_cast<size_t>(std::max(256, c.cacheColumns / 4))),
      limeCache(static_cast<size_t>(std::max(64, c.cacheColumns / 4))) {
    serial = nextSerial.fetch_add(1);
    layerSeed[0] = cfg.seed;
    layerDrop[0] = 0;
    for (int i = 0; i < SBW_DEEP_LAYERS; i++) {
        layerSeed[i + 1] = cfg.seed ^ static_cast<int64_t>(static_cast<uint64_t>(DEEP_SALT) * static_cast<uint64_t>(i + 1));
        layerDrop[i + 1] = DEEP_SPACING * (i + 1);
        deepCache[i] = std::make_unique<TileCache<DeepTile>>(static_cast<size_t>(std::max(32, c.cacheColumns / 16)));
    }
    /* worm walks: only sources that spawn a system are cached (1 in 8); a column cube's window holds ~50 */
    for (auto& wc : wormCache) {
        wc = std::make_unique<TileCache<WormWalk>>(static_cast<size_t>(std::max(256, c.cacheColumns / 4)));
    }
    shapeScale = cfg.hmPerBlock / 60.0; /* 1:4 (60 m) = 1; 1:2 (30 m) = 0.5: the same shape twice as wide in blocks */
    curve.build(cfg.rates, cfg.knots, static_cast<double>(cfg.sea));
    const double refRates[4] = {48.0, 16.0, 24.0, 38.0};
    const double refKnots[5] = {600.0, 2000.0, 60.0, 150.0, 300.0};
    refCurve.build(refRates, refKnots, static_cast<double>(cfg.sea));

    static const int SEED_OFFSET[11] = {2, 21, 22, 8, 23, 24, 25, 26, 27, 28, 3};
    static const int OCTAVES[11] = {4, 2, 2, 2, 2, 2, 4, 3, 4, 4, 3};
    static const double PERIOD[11] = {6894.989622808905, 1821.7527333238593, 1821.7527333238593,
                                      3944.511100992684, 577.2424211879252, 4344.064076674278,
                                      142.39095239499332, 1827.960213884483, 451.3265503952944,
                                      18.47190652270889, 45.0};
    for (int i = 0; i < 11; i++) {
        shape[i].init(cfg.seed + SEED_OFFSET[i], OCTAVES[i], 0.5f, 2.0f, static_cast<float>(1.0 / PERIOD[i]));
    }
    moisture.init(cfg.seed, 6, 0.45f, 2.1f, 1.0f / 1500.0f);
    temperature.init(cfg.seed + 1, 6, 0.40f, 2.0f, 1.0f / 2000.0f);
    wobble.init(cfg.seed + 8191, 2, 0.5f, 2.0f, 1.0f / 220.0f);
    heading.init(cfg.seed + 41, 1, 0.5f, 2.0f, 1.0f, 1.0f);
    radius.init(cfg.seed + 113, 1, 0.5f, 2.0f, 1.0f, 1.0f);
    cheese.init(cfg.seed + 17, 2, 0.5f, 2.0f, 1.0f / 96.0f, 1.6f);
    spag1.init(cfg.seed + 331, 2, 0.5f, 2.0f, 1.0f / 68.0f, 1.08f);
    spag2.init(cfg.seed + 733, 2, 0.5f, 2.0f, 1.0f / 68.0f, 1.08f);
    crag.init(cfg.seed + 1187, 2, 0.5f, 2.0f, 1.0f / 26.0f, 1.8f);
    cheeseKnots = {{0, 2.0}, {18, 0.90}, {45, 0.68}, {90, 0.55}, {160, 0.47}, {250, 0.44}};
    for (int d = 0; d < CHEESE_TABLE; d++) cheeseTable[d] = cheeseThreshold(static_cast<double>(d));
    ravinePath = JavaSimplex(cfg.seed + 577, 1, 0.5, 2.0);
    sinkRim = JavaSimplex(cfg.seed + 911, 1, 0.5, 2.0);
    limePatch = JavaSimplex(cfg.seed ^ INT64_C(0x7A11E57E), 2, 0.5, 2.0);
    limeRegion = JavaSimplex(cfg.seed ^ INT64_C(0x6A57C0DE), 2, 0.5, 2.0);
    limeWarp = JavaSimplex(cfg.seed ^ INT64_C(0x57A7A000), 1, 0.5, 2.0);
    drand = DeterministicRandom(cfg.seed);
    if (cfg.prefetchThreads > 0) prefetch = std::make_unique<SurfacePrefetcher>(*this, cfg.prefetchThreads);
}

Generator::~Generator() { prefetch.reset(); }

double Generator::cheeseThreshold(double depth) const {
    if (depth <= cheeseKnots.front().first) return cheeseKnots.front().second;
    if (depth >= cheeseKnots.back().first) return cheeseKnots.back().second;
    size_t i = 0;
    while (i < cheeseKnots.size() - 1 && depth > cheeseKnots[i + 1].first) ++i;
    const auto& p1 = cheeseKnots[i];
    const auto& p2 = cheeseKnots[i + 1];
    const double t = (depth - p1.first) / (p2.first - p1.first);
    return p1.second + t * (p2.second - p1.second);
}

bool Generator::isBedrock(int64_t x, int64_t y, int64_t z) const {
    const int64_t k = y - cfg.minY;
    if (k == 0) return true;
    if (k < 0 || k > 4) return false;
    /* hashed band: probability (5 - k) / 5 at min_y + k */
    const uint64_t h = stonebreak::mix64(static_cast<uint64_t>(cfg.seed) ^ stonebreak::mix64(
        static_cast<uint64_t>(x) * UINT64_C(0x9E3779B97F4A7C15) ^ static_cast<uint64_t>(y) * UINT64_C(0xC2B2AE3D27D4EB4F)
        ^ static_cast<uint64_t>(z) * UINT64_C(0x165667B19E3779F9)));
    return static_cast<int64_t>(h % 5) < 5 - k;
}

bool Generator::magmaAt(int64_t x, int64_t y, int64_t z) const {
    return drand.shouldGenerate3(x, y, z, stonebreak::javaStringHash("magma"), 0.6f);
}

/* ------------------------------------------------------------------------------ oracles */
std::shared_ptr<const BaseTile> Generator::base(int64_t cx, int64_t cz) {
    return baseCache.get(cx, cz, [this](int64_t a, int64_t b) { return buildBase(a, b); });
}
std::shared_ptr<const CarveTile> Generator::carve(int64_t cx, int64_t cz) {
    const TileCache<CarveTile>::Builder build = [this](int64_t a, int64_t b) { return buildCarve(a, b); };
    std::shared_ptr<const CarveTile> t;
    if (carveCache.tryGet(cx, cz, build, t)) return t;
    /* another thread is building it: walk what that build still needs instead of idling */
    helpWalks(0, cx * CE, cz * CE);
    return carveCache.get(cx, cz, build);
}
std::shared_ptr<const CarvedTile> Generator::carved(int64_t cx, int64_t cz) {
    return carvedCache.get(cx, cz, [this](int64_t a, int64_t b) { return buildCarved(a, b); });
}
std::shared_ptr<const PoolTile> Generator::pools(int64_t cx, int64_t cz) {
    return poolCache.get(cx, cz, [this](int64_t a, int64_t b) { return buildPools(a, b); });
}
std::shared_ptr<const DeepTile> Generator::deepLayer(int i, int64_t cx, int64_t cz) {
    const TileCache<DeepTile>::Builder build = [this, i](int64_t a, int64_t b) { return buildDeepLayer(i, a, b); };
    std::shared_ptr<const DeepTile> t;
    if (deepCache[i]->tryGet(cx, cz, build, t)) return t;
    helpWalks(i + 1, cx * CE, cz * CE);
    return deepCache[i]->get(cx, cz, build);
}
std::shared_ptr<const DeepTile> Generator::deep(int64_t cx, int64_t cz) {
    auto t = std::make_shared<DeepTile>();
    for (int i = 0; i < SBW_DEEP_LAYERS; i++) {
        auto l = deepLayer(i, cx, cz);
        if (!l) return nullptr;
        t->carve.orWith(l->carve);
    }
    return t;
}

/* ------------------------------------------------------------------------- cached walks */
std::shared_ptr<const WormWalk> Generator::wormWalk(int layer, int64_t sx, int64_t sz) {
    return wormCache[layer]->get(sx, sz, [this, layer](int64_t a, int64_t b) {
        CarveCtx ctx(*this, LayerSeeds::of(layerSeed[layer]));
        ctx.dry = layer > 0;
        ctx.drop = layerDrop[layer];
        return walkWormSystem(ctx, a, b);
    });
}

void Generator::helpWalks(int layer, int64_t X0, int64_t Z0) {
    const SourceWindow w = wormWindow(X0, Z0);
    const int64_t seed = layerSeed[layer];
    TileCache<WormWalk>& cache = *wormCache[layer];
    const TileCache<WormWalk>::Builder build = [this, layer](int64_t a, int64_t b) {
        CarveCtx wc(*this, LayerSeeds::of(layerSeed[layer]));
        wc.dry = layer > 0;
        wc.drop = layerDrop[layer];
        return walkWormSystem(wc, a, b);
    };
    std::shared_ptr<const WormWalk> walk;
    for (int64_t sx = w.c0; sx <= w.c1; sx++) {
        for (int64_t sz = w.d0; sz <= w.d1; sz++) {
            if (wormSourceAt(seed, sx, sz) && !cache.contains(sx, sz)) cache.tryGet(sx, sz, build, walk);
        }
    }
    if (layer != 0) return;
    const SourceWindow r = ravineWindow(X0, Z0);
    const TileCache<RavineWalk>::Builder rbuild = [this](int64_t a, int64_t b) {
        CarveCtx rc(*this, LayerSeeds::of(layerSeed[0]));
        return walkRavine(rc, a, b);
    };
    std::shared_ptr<const RavineWalk> rw;
    for (int64_t sx = r.c0; sx <= r.c1; sx++) {
        for (int64_t sz = r.d0; sz <= r.d1; sz++) {
            if (ravineSourceAt(*this, seed, sx, sz) && !ravineCache.contains(sx, sz)) ravineCache.tryGet(sx, sz, rbuild, rw);
        }
    }
}

void Generator::carveLayerWorms(CarveCtx& ctx, int layer) {
    const SourceWindow w = wormWindow(ctx.X0, ctx.Z0);
    const int64_t seed = layerSeed[layer];
    TileCache<WormWalk>& cache = *wormCache[layer];
    const TileCache<WormWalk>::Builder build = [this, layer](int64_t a, int64_t b) {
        CarveCtx wc(*this, LayerSeeds::of(layerSeed[layer]));
        wc.dry = layer > 0;
        wc.drop = layerDrop[layer];
        return walkWormSystem(wc, a, b);
    };
    std::vector<std::pair<int64_t, int64_t>> deferred;
    for (int64_t sx = w.c0; sx <= w.c1; sx++) {
        for (int64_t sz = w.d0; sz <= w.d1; sz++) {
            if (!wormSourceAt(seed, sx, sz)) continue;
            std::shared_ptr<const WormWalk> walk;
            if (!cache.tryGet(sx, sz, build, walk)) {
                deferred.emplace_back(sx, sz);
                continue;
            }
            if (!walk) throw std::runtime_error("worm walk unavailable");
            rasterWorms(ctx, *walk);
        }
    }
    for (const auto& [sx, sz] : deferred) {
        auto walk = cache.get(sx, sz, build);
        if (!walk) throw std::runtime_error("worm walk unavailable");
        rasterWorms(ctx, *walk);
    }
}

void Generator::carveTopRavines(CarveCtx& ctx) {
    const SourceWindow w = ravineWindow(ctx.X0, ctx.Z0);
    const int64_t seed = layerSeed[0];
    const TileCache<RavineWalk>::Builder build = [this](int64_t a, int64_t b) {
        CarveCtx rc(*this, LayerSeeds::of(layerSeed[0]));
        return walkRavine(rc, a, b);
    };
    std::vector<std::pair<int64_t, int64_t>> deferred;
    for (int64_t sx = w.c0; sx <= w.c1; sx++) {
        for (int64_t sz = w.d0; sz <= w.d1; sz++) {
            if (!ravineSourceAt(*this, seed, sx, sz)) continue;
            std::shared_ptr<const RavineWalk> walk;
            if (!ravineCache.tryGet(sx, sz, build, walk)) {
                deferred.emplace_back(sx, sz);
                continue;
            }
            if (!walk) throw std::runtime_error("ravine walk unavailable");
            rasterRavine(ctx, *walk);
        }
    }
    for (const auto& [sx, sz] : deferred) {
        auto walk = ravineCache.get(sx, sz, build);
        if (!walk) throw std::runtime_error("ravine walk unavailable");
        rasterRavine(ctx, *walk);
    }
}

static const BaseTile& oracleTile(Generator& g, int64_t x, int64_t z, int& col) {
    thread_local OracleSlot slots[4];
    thread_local unsigned next = 0;
    const int64_t cx = x >> 5, cz = z >> 5;
    col = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
    for (auto& s : slots) {
        if (s.serial == g.serial && s.cx == cx && s.cz == cz && s.tile) return *s.tile;
    }
    auto t = g.base(cx, cz);
    if (!t) throw std::runtime_error("column profile unavailable");
    OracleSlot& s = slots[next++ & 3];
    s.serial = g.serial;
    s.cx = cx;
    s.cz = cz;
    s.tile = t;
    return *s.tile;
}

int32_t heightOracle(Generator& g, int64_t x, int64_t z) {
    int col;
    return oracleTile(g, x, z, col).h[col];
}

int32_t waterOracle(Generator& g, int64_t x, int64_t z) {
    int col;
    return oracleTile(g, x, z, col).water[col];
}

int32_t Generator::heightAt(int64_t x, int64_t z) { return heightOracle(*this, x, z); }
int32_t Generator::waterAt(int64_t x, int64_t z) { return waterOracle(*this, x, z); }

/* --------------------------------------------------------------------------------- L0 */
std::shared_ptr<BaseTile> Generator::buildBase(int64_t cx, int64_t cz) {
    auto t = std::make_shared<BaseTile>();
    t->cx = cx;
    t->cz = cz;
    if (cfg.kind == SBW_KIND_STANDARD) {
        buildStandardBase(*this, *t);
        return t;
    }
    auto prof = std::make_unique<sbw_column_profile>();
    std::memset(prof.get(), 0, sizeof(sbw_column_profile));
    if (cfg.columnFn == nullptr || cfg.columnFn(cfg.columnUser, cx, cz, prof.get()) != 1) {
        setError("column profile upcall failed");
        return nullptr;
    }
    t->rivers = true;
    t->minH = INT32_MAX;
    t->maxH = INT32_MIN;
    t->maxTop = INT32_MIN;
    const int32_t lo = static_cast<int32_t>(cfg.minY) + 1, hi = static_cast<int32_t>(cfg.maxY) - 1;
    for (int c = 0; c < COLS; c++) {
        const int32_t h = std::max(lo, std::min(hi, prof->height[c]));
        const int biome = prof->biome[c] < static_cast<int>(SBW_BIOME_COUNT) ? static_cast<int>(prof->biome[c]) : static_cast<int>(SBW_PLAINS);
        int32_t water = prof->water[c];
        if (water != NO_WATER) water = std::min(water, static_cast<int32_t>(cfg.maxY));
        t->h[c] = h;
        t->refH[c] = h;
        t->water[c] = water;
        t->biome[c] = static_cast<uint8_t>(biome);
        t->riverFloor[c] = prof->river_floor[c];
        t->riverRoof[c] = prof->river_roof[c];
        t->riverFlow[c] = prof->river_flow[c];
        t->carve[c] = prof->carve[c];
        const int32_t depth = (water == NO_WATER) ? 0 : water - h;
        t->surface[c] = surfaceAt(biome, cx * CE + (c & 31), cz * CE + (c >> 5), depth);
        t->minH = std::min(t->minH, h);
        t->maxH = std::max(t->maxH, h);
        int32_t top = std::max(h, water == NO_WATER ? h : water);
        if (t->riverRoof[c] > t->riverFloor[c]) top = std::max(top, t->riverRoof[c] + 1);
        t->maxTop = std::max(t->maxTop, top);
    }
    return t;
}

/* --------------------------------------------------------------------------------- L1 */
std::shared_ptr<CarveTile> Generator::buildCarve(int64_t cx, int64_t cz) {
    auto bp = base(cx, cz);
    if (!bp) return nullptr;
    const BaseTile& b = *bp;
    auto t = std::make_shared<CarveTile>();
    const int64_t X0 = cx * CE, Z0 = cz * CE;
    waterGuardPlane(*this, X0, Z0, b.h, b.water, t->guard);

    const LayerSeeds seeds = LayerSeeds::of(cfg.seed);
    MaskSet cavA, cavB;
    CarveCtx ctx(*this, seeds);
    ctx.X0 = X0;
    ctx.Z0 = Z0;
    ctx.th = b.h;
    ctx.guard = t->guard;
    ctx.out = &t->cave;
    /* sea stacks first: their flooded cuts lower the water guard every later carver keeps clear of (the carved cells
     * are a union - only the guard depends on the order, and only where a cut floods) */
    ctx.flood = t->flood;
    ctx.seal = t->seal;
    carveSeaStacks(ctx);
    for (int c = 0; c < COLS; c++) t->guard[c] = std::min(t->guard[c], t->seal[c]);
    carveLayerWorms(ctx, 0);
    ctx.out = &cavA;
    carveCaverns(ctx, false);
    ctx.out = &cavB;
    carveCaverns(ctx, true);
    buildFormations(*this, cfg.seed, false, X0, Z0, cavA, t->formation);
    buildFormations(*this, cfg.seed, true, X0, Z0, cavB, t->formation);
    t->cave.orWith(cavA);
    t->cave.orWith(cavB);
    ctx.out = &t->cave;
    carveTopRavines(ctx);
    carveSinkholes(ctx);
    carveTowers(ctx);

    /* CaveWaterTable per column */
    float wob[COLS];
    wobble.node->GenUniformGrid2D(wob, static_cast<float>(Z0), static_cast<float>(X0), CE, CE, 1.0f, 1.0f,
                                  seeds.wobbleSeed);
    for (int lx = 0; lx < CE; lx++) {
        for (int lz = 0; lz < CE; lz++) {
            const int c = colIdx(lx, lz);
            t->table[c] = waterTableResolve(*this, b.h[c], b.water[c], wob[lx * CE + lz]);
        }
    }

    /* FormationSupport.prune: a run of formation cells survives when the block under or over it is
     * solid in the finished terrain (density, carves and river voids included). */
    if (!t->formation.empty()) {
        int64_t lo, hi;
        t->formation.yRange(lo, hi);
        auto solid = [&](int lx, int64_t y, int lz) -> bool {
            if (y <= cfg.minY) return true;
            const int c = colIdx(lx, lz);
            if (b.rivers && b.riverRoof[c] > b.riverFloor[c] && y >= b.riverFloor[c] && y <= b.riverRoof[c]) {
                return y == b.riverFloor[c] || y == b.riverRoof[c];
            }
            if (y >= b.h[c]) return false;
            if (t->cave.test(lx, y, lz)) return false;
            PointDensity d{*this, X0 + lx, y, Z0 + lz, {}};
            return densitySolid(d, y, b.h[c], b.biome[c], b.refH[c], t->table[c], t->guard[c], t->seal[c]);
        };
        for (int lz = 0; lz < CE; lz++) {
            for (int lx = 0; lx < CE; lx++) {
                int64_t runStart = INT64_MIN;
                for (int64_t y = lo; y <= hi; y++) {
                    if (y < hi && t->formation.test(lx, y, lz)) {
                        if (runStart == INT64_MIN) runStart = y;
                        continue;
                    }
                    if (runStart == INT64_MIN) continue;
                    const int64_t runEnd = y - 1;
                    if (!solid(lx, runStart - 1, lz) && !solid(lx, runEnd + 1, lz)) {
                        for (int64_t cy = runStart; cy <= runEnd; cy++) t->formation.clear(lx, cy, lz);
                    }
                    runStart = INT64_MIN;
                }
            }
        }
    }
    return t;
}

std::shared_ptr<CarvedTile> Generator::buildCarved(int64_t cx, int64_t cz) {
    auto bp = base(cx, cz);
    auto cp = carve(cx, cz);
    if (!bp || !cp) return nullptr;
    auto t = std::make_shared<CarvedTile>();
    /* descend from the raw top while the generated block is air (or river water) */
    carvedDescent(*this, bp, cp, cx, cz, *t);
    return t;
}

/* -------------------------------------------------------------------------------- L1b */
namespace {
constexpr int POOL_LATTICE = 8;
constexpr float POOL_THRESHOLD = 0.5f;
constexpr float DEEP_THRESHOLD = 0.68f;

float sampleLattice(const DeterministicRandom& r, int64_t x, int64_t z, int spacing, int32_t feature) {
    const int64_t x0 = floorDiv64(x, spacing), z0 = floorDiv64(z, spacing);
    float tx = static_cast<float>(x - x0 * spacing) / static_cast<float>(spacing);
    float tz = static_cast<float>(z - z0 * spacing) / static_cast<float>(spacing);
    tx = tx * tx * (3.0f - 2.0f * tx);
    tz = tz * tz * (3.0f - 2.0f * tz);
    const float v00 = r.getFloat(x0, z0, feature);
    const float v10 = r.getFloat(x0 + 1, z0, feature);
    const float v01 = r.getFloat(x0, z0 + 1, feature);
    const float v11 = r.getFloat(x0 + 1, z0 + 1, feature);
    const float a = v00 + (v10 - v00) * tx;
    const float b = v01 + (v11 - v01) * tx;
    return a + (b - a) * tz;
}
} // namespace

float forestLattice(const DeterministicRandom& r, int64_t x, int64_t z, int spacing, int32_t feature) {
    return sampleLattice(r, x, z, spacing, feature);
}

std::shared_ptr<PoolTile> Generator::buildPools(int64_t cx, int64_t cz) {
    auto bp = base(cx, cz);
    auto cp = carved(cx, cz);
    if (!bp || !cp) return nullptr;
    const BaseTile& b = *bp;
    const CarvedTile& cs = *cp;
    auto t = std::make_shared<PoolTile>();
    const int64_t X0 = cx * CE, Z0 = cz * CE;
    static const int32_t POOL_HASH = stonebreak::javaStringHash("swamp_pool");
    /* SwampPoolGenerator.terrainPassBlock watertightness: carved columns read as air (conservative) */
    auto watertight = [&](int64_t x, int64_t y, int64_t z) -> bool {
        const int64_t ncx = x >> 5, ncz = z >> 5;
        const int col = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
        std::shared_ptr<const BaseTile> nb = (ncx == cx && ncz == cz) ? bp : base(ncx, ncz);
        std::shared_ptr<const CarvedTile> nc = (ncx == cx && ncz == cz) ? cp : carved(ncx, ncz);
        if (!nb || !nc) return false;
        const int32_t surface = nc->carvedH[col];
        if (y < surface) return surface == nb->h[col]; /* DIRT vs AIR */
        return nb->water[col] != NO_WATER && y < nb->water[col]; /* WATER vs AIR */
    };
    auto sealed = [&](int64_t x, int64_t y, int64_t z) {
        return watertight(x, y - 1, z) && watertight(x + 1, y, z) && watertight(x - 1, y, z)
            && watertight(x, y, z + 1) && watertight(x, y, z - 1);
    };
    for (int c = 0; c < COLS; c++) {
        if (b.biome[c] != SBW_SWAMP) continue;
        const int32_t h = b.h[c];
        if (cs.top[c] != cfg.id[SBW_SWAMPY_GRASS] || cs.carvedH[c] != h) continue;
        if (b.water[c] != NO_WATER && b.water[c] > h) continue;
        if (static_cast<int64_t>(h) - 1 < cfg.minY + 2) continue;
        const int64_t x = X0 + (c & 31), z = Z0 + (c >> 5);
        const float pool = sampleLattice(drand, x, z, POOL_LATTICE, POOL_HASH);
        if (pool <= POOL_THRESHOLD || !sealed(x, h - 1, z)) continue;
        t->depth[c] = (pool > DEEP_THRESHOLD && sealed(x, h - 2, z)) ? 2 : 1;
    }
    return t;
}

/* --------------------------------------------------------------------------------- L2 */
std::shared_ptr<DeepTile> Generator::buildDeepLayer(int i, int64_t cx, int64_t cz) {
    auto bp = base(cx, cz);
    if (!bp) return nullptr;
    auto t = std::make_shared<DeepTile>();
    if ((cfg.flags & SBW_FLAG_DEEP_LAYERS) == 0) return t;
    const int64_t X0 = cx * CE, Z0 = cz * CE;
    const int L = i + 1;
    const int64_t seed = layerSeed[L];
    const int32_t drop = layerDrop[L];
    /* skip a layer that cannot reach above the floor anywhere in this column cube */
    if (static_cast<int64_t>(bp->maxH) - drop <= cfg.carveFloor()) return t;
    CarveCtx ctx(*this, LayerSeeds::of(seed));
    ctx.dry = true;
    ctx.drop = drop;
    ctx.X0 = X0;
    ctx.Z0 = Z0;
    ctx.th = bp->h;
    ctx.guard = nullptr;
    MaskSet cave, cavA, cavB, forms;
    ctx.out = &cave;
    carveLayerWorms(ctx, L);
    ctx.out = &cavA;
    carveCaverns(ctx, false);
    ctx.out = &cavB;
    carveCaverns(ctx, true);
    buildFormations(*this, seed, false, X0, Z0, cavA, forms);
    buildFormations(*this, seed, true, X0, Z0, cavB, forms);
    cave.orWith(cavA);
    cave.orWith(cavB);
    cave.andNot(forms);
    t->carve.orWith(cave);
    return t;
}

/* ------------------------------------------------------------------------ test hook */
void Generator::carveFootprint(int64_t x0, int64_t z0, const int32_t* heights, const int32_t* water,
                               int64_t seedLayer, bool withSurfaceCarvers, MaskSet& cave, MaskSet& formA,
                               MaskSet& formB, int32_t* flood, int32_t* seal) {
    if (flood != nullptr) std::fill_n(flood, COLS, FLOOD_NONE);
    if (seal != nullptr) std::fill_n(seal, COLS, FLOOD_NONE);
    int32_t guard[COLS];
    waterGuardPlane(*this, x0, z0, heights, water, guard);
    MaskSet cavA, cavB;
    CarveCtx ctx(*this, LayerSeeds::of(seedLayer));
    ctx.X0 = x0;
    ctx.Z0 = z0;
    ctx.th = heights;
    ctx.guard = guard;
    ctx.out = &cave;
    if (withSurfaceCarvers) {   /* as buildCarve: sea stacks (and their guard) before every other carver */
        int32_t floodLocal[COLS], sealLocal[COLS];
        std::fill_n(floodLocal, COLS, FLOOD_NONE);
        std::fill_n(sealLocal, COLS, FLOOD_NONE);
        ctx.flood = flood != nullptr ? flood : floodLocal;
        ctx.seal = seal != nullptr ? seal : sealLocal;
        carveSeaStacks(ctx);
        for (int c = 0; c < COLS; c++) guard[c] = std::min(guard[c], ctx.seal[c]);
        ctx.flood = nullptr;
        ctx.seal = nullptr;
    }
    carveWorms(ctx);
    ctx.out = &cavA;
    carveCaverns(ctx, false);
    ctx.out = &cavB;
    carveCaverns(ctx, true);
    buildFormations(*this, seedLayer, false, x0, z0, cavA, formA);
    buildFormations(*this, seedLayer, true, x0, z0, cavB, formB);
    cave.orWith(cavA);
    cave.orWith(cavB);
    if (withSurfaceCarvers) {
        ctx.out = &cave;
        carveRavines(ctx);
        carveSinkholes(ctx);
        carveTowers(ctx);
    }
}

/* ------------------------------------------------------------------------------ point */
uint16_t Generator::terrainBlockAt(int64_t x, int64_t y, int64_t z, bool withPools) {
    if (y < cfg.minY || y >= cfg.maxY) return cfg.id[SBW_AIR];
    ColumnTiles t(*this, x >> 5, z >> 5);
    return t.blockAt(x, y, z, withPools);
}

ColumnTiles::ColumnTiles(Generator& gen, int64_t tcx, int64_t tcz) : g(gen), cx(tcx), cz(tcz) {
    base = g.base(cx, cz);
    if (!base) throw std::runtime_error("column profile unavailable");
}

uint16_t ColumnTiles::blockAt(int64_t x, int64_t y, int64_t z, bool withPools) {
    const Config& cfg = g.cfg;
    if (y < cfg.minY || y >= cfg.maxY) return cfg.id[SBW_AIR];
    const int lx = static_cast<int>(x & 31), lz = static_cast<int>(z & 31);
    const int col = colIdx(lx, lz);
    const BaseTile& b = *base;
    if (y >= b.h[col] && !(b.rivers && b.riverRoof[col] >= y)) {
        if (b.water[col] != NO_WATER && y < b.water[col]) return cfg.id[SBW_WATER];
        return cfg.id[SBW_AIR];
    }
    if (!carve) {
        carve = g.carve(cx, cz);
        if (!carve) throw std::runtime_error("carve unavailable");
    }
    Generator::PointDensity d{g, x, y, z, {}};
    uint16_t id = g.decideBlock(b, *carve, col, lx, lz, x, y, z, d);
    if (id == cfg.id[SBW_STONE] && (cfg.flags & SBW_FLAG_DEEP_LAYERS) != 0) {
        /* layer i only carves below h - 100 * (i + 1) */
        for (int i = 0; i < SBW_DEEP_LAYERS && y < static_cast<int64_t>(b.h[col]) - DEEP_SPACING * (i + 1); i++) {
            if (!deepLoaded[i]) {
                deep[i] = g.deepLayer(i, cx, cz);
                deepLoaded[i] = true;
            }
            if (deep[i] && deep[i]->carve.test(lx, y, lz)) {
                id = cfg.id[SBW_AIR];
                break;
            }
        }
    }
    if (withPools && b.biome[col] == SBW_SWAMP && (y == b.h[col] - 1 || y == b.h[col] - 2)) {
        if (!poolsLoaded) {
            pools = g.pools(cx, cz);
            poolsLoaded = true;
        }
        if (pools && pools->depth[col] > 0 && (y == b.h[col] - 1 || pools->depth[col] == 2)) id = cfg.id[SBW_WATER];
    }
    return id;
}

} // namespace sbw
