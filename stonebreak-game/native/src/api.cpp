/* SBW ABI 3 exports: config, the cv_generator table and the FastLOD samplers (worldgen.h). */
#include "decide.hpp"

#include <algorithm>
#include <cmath>
#include <exception>
#include <thread>

namespace sbw {
const char* lastError();
void standardColumnPoint(Generator& g, int64_t x, int64_t z, int32_t& h, int32_t& water, int& biome, uint16_t& surface);
float forestLattice(const DeterministicRandom& r, int64_t x, int64_t z, int spacing, int32_t feature);
} // namespace sbw

using namespace sbw;

namespace {

Generator* genOf(const cv_generator* g) {
    return g == nullptr ? nullptr : static_cast<Generator*>(g->user);
}

template <typename F>
int32_t guarded(F&& f) {
    try {
        return f();
    } catch (const std::exception& e) {
        setError(e.what());
        return -1;
    } catch (...) {
        setError("unknown failure");
        return -1;
    }
}

int32_t thunkColumnSurface(void* user, int64_t cx, int64_t cz, int64_t* sky, int64_t* top) {
    return guarded([&] { return static_cast<Generator*>(user)->columnSurface(cx, cz, sky, top); });
}

int32_t thunkGenerate(void* user, int64_t cx, int64_t cy, int64_t cz, uint16_t* ids, uint16_t* const* layers) {
    if (ids == nullptr) return -1;
    return guarded([&] { return static_cast<Generator*>(user)->generateCube(cx, cy, cz, ids, layers); });
}

int32_t thunkPopulate(void* user, const cv_gen_api* api, cv_world* world, int64_t cx, int64_t cy, int64_t cz) {
    return guarded([&] {
        const int32_t r = static_cast<Generator*>(user)->populate(api, world, cx, cy, cz);
        return r < 0 ? r : 0;
    });
}

/* SwampPoolGenerator.probePoolDepth with a chosen surface oracle (carved or raw heights) */
int poolDepthProbe(Generator& g, int biome, uint16_t surface, int32_t rawHeight, int32_t carvedHeight,
                   int32_t waterLevel, int64_t x, int64_t z, bool carved) {
    if (biome != SBW_SWAMP || surface != g.cfg.id[SBW_SWAMPY_GRASS] || carvedHeight != rawHeight) return 0;
    if (waterLevel != NO_WATER && waterLevel > rawHeight) return 0;
    if (static_cast<int64_t>(rawHeight) - 1 < g.cfg.minY + 2) return 0;
    if (carved) {
        auto pp = g.pools(x >> 5, z >> 5);
        return pp ? pp->depth[colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31))] : 0;
    }
    static const int32_t POOL_HASH = stonebreak::javaStringHash("swamp_pool");
    auto watertight = [&](int64_t px, int64_t py, int64_t pz) {
        const int32_t h = heightOracle(g, px, pz);
        if (py < h) return true;
        const int32_t w = waterOracle(g, px, pz);
        return w != NO_WATER && py < w;
    };
    auto sealed = [&](int64_t px, int64_t py, int64_t pz) {
        return watertight(px, py - 1, pz) && watertight(px + 1, py, pz) && watertight(px - 1, py, pz)
            && watertight(px, py, pz + 1) && watertight(px, py, pz - 1);
    };
    const float pool = forestLattice(g.drand, x, z, 8, POOL_HASH);
    if (pool <= 0.5f || !sealed(x, rawHeight - 1, z)) return 0;
    return (pool > 0.68f && sealed(x, rawHeight - 2, z)) ? 2 : 1;
}

bool growsTreeOn(const Generator& g, uint16_t s) {
    return s == g.cfg.id[SBW_GRASS] || s == g.cfg.id[SBW_SNOWY_DIRT] || s == g.cfg.id[SBW_SWAMPY_GRASS]
           || s == g.cfg.id[SBW_WATER];
}

} // namespace

extern "C" {

int32_t sbw_abi_version(void) { return SBW_ABI_VERSION; }

int32_t sbw_config_size(void) { return static_cast<int32_t>(sizeof(sbw_gen_config)); }

const char* sbw_last_error(void) { return sbw::lastError(); }

void sbw_config_defaults(sbw_gen_config* c) {
    if (c == nullptr) return;
    std::memset(c, 0, sizeof(*c));
    c->struct_size = sizeof(sbw_gen_config);
    c->kind = SBW_KIND_STANDARD;
    c->min_y = -384;
    c->max_y = 640;
    c->sea_level = 64;
    c->flags = SBW_FLAG_PEAK_DAMP | SBW_FLAG_DEEP_LAYERS | SBW_FLAG_FEATURES;
    c->horizontal_m_per_block = 60.0;
    const double rates[4] = {48.0, 16.0, 24.0, 38.0};
    const double knots[5] = {600.0, 2000.0, 60.0, 150.0, 300.0};
    std::memcpy(c->curve_rates, rates, sizeof(rates));
    std::memcpy(c->curve_knots, knots, sizeof(knots));
    c->water_layer = -1;
    c->state_layer = -1;
    c->snow_layer = -1;
    c->water_source_value = 16;
}

const cv_generator* sbw_cv_generator_create(const sbw_gen_config* in) {
    try {
        if (in == nullptr || in->struct_size < sizeof(sbw_gen_config)) {
            setError("config missing or too small (struct_size)");
            return nullptr;
        }
        if (in->kind != SBW_KIND_STANDARD && in->kind != SBW_KIND_PROFILE) {
            setError("unknown generator kind");
            return nullptr;
        }
        if (in->kind == SBW_KIND_PROFILE && in->column_fn == nullptr) {
            setError("profile kind needs column_fn");
            return nullptr;
        }
        if (in->min_y >= in->max_y || in->max_y - in->min_y < 64 || in->min_y < -(INT64_C(1) << 30)
                || in->max_y > (INT64_C(1) << 30)) {
            setError("bad vertical bounds");
            return nullptr;
        }
        if (in->sea_level <= in->min_y + 16 || in->sea_level >= in->max_y) {
            setError("sea level outside the world");
            return nullptr;
        }
        if (!(in->horizontal_m_per_block > 0.0) || !std::isfinite(in->horizontal_m_per_block)) {
            setError("bad horizontal_m_per_block");
            return nullptr;
        }
        for (double r : in->curve_rates) {
            if (!(r > 0.0)) {
                setError("bad curve rate");
                return nullptr;
            }
        }
        for (int layer : {in->water_layer, in->state_layer, in->snow_layer}) {
            if (layer < -1 || layer >= CV_MAX_LAYERS) {
                setError("layer index out of range");
                return nullptr;
            }
        }
        Config c;
        c.kind = in->kind;
        c.seed = in->seed;
        c.minY = in->min_y;
        c.maxY = in->max_y;
        c.sea = in->sea_level;
        c.flags = in->flags;
        c.hmPerBlock = in->horizontal_m_per_block;
        std::memcpy(c.rates, in->curve_rates, sizeof(c.rates));
        std::memcpy(c.knots, in->curve_knots, sizeof(c.knots));
        if (in->block_flags != nullptr && in->block_flags_count > 0) {
            c.blockFlags.assign(in->block_flags, in->block_flags + in->block_flags_count);
        }
        c.waterLayer = in->water_layer;
        c.stateLayer = in->state_layer;
        c.snowLayer = in->snow_layer;
        c.columnFn = in->column_fn;
        c.columnUser = in->column_user;
        c.cacheColumns = in->cache_columns > 0 ? in->cache_columns : 4096;
        if (in->surface_prefetch == SBW_PREFETCH_OFF) {
            c.prefetchThreads = 0;
        } else if (in->surface_prefetch == 0) {
            const unsigned hw = std::thread::hardware_concurrency();
            c.prefetchThreads = std::clamp(static_cast<int>(hw / 4), 1, 8);
        } else {
            c.prefetchThreads = std::min<int>(in->surface_prefetch, 64);
        }
        c.waterSource = in->water_source_value;
        std::memcpy(c.waterRiver, in->water_river_values, sizeof(c.waterRiver));
        std::memcpy(c.stalagmiteState, in->stalagmite_states, sizeof(c.stalagmiteState));
        std::memcpy(c.stalagmitePart, in->stalagmite_part_states, sizeof(c.stalagmitePart));
        std::memcpy(c.id, in->block_ids, sizeof(c.id));
        auto* g = new Generator(c);
        g->table.abi = CV_ABI_VERSION;
        g->table.populate_reach = 1;
        g->table.user = g;
        g->table.column_surface = thunkColumnSurface;
        g->table.generate_cube = thunkGenerate;
        g->table.populate = thunkPopulate;
        return &g->table;
    } catch (const std::exception& e) {
        setError(e.what());
        return nullptr;
    }
}

void sbw_cv_generator_destroy(const cv_generator* g) {
    delete genOf(g);
}

int32_t sbw_generate_cube(const cv_generator* g, int64_t cx, int64_t cy, int64_t cz, uint16_t* ids,
                          uint16_t* const* layers) {
    if (genOf(g) == nullptr) return -1;
    return thunkGenerate(g->user, cx, cy, cz, ids, layers);
}

int32_t sbw_populate(const cv_generator* g, const cv_gen_api* api, cv_world* world, int64_t cx, int64_t cy,
                     int64_t cz) {
    if (genOf(g) == nullptr) return -1;
    return thunkPopulate(g->user, api, world, cx, cy, cz);
}

int32_t sbw_column_surface(const cv_generator* g, int64_t cx, int64_t cz, int64_t* sky, int64_t* top) {
    if (genOf(g) == nullptr) return -1;
    return thunkColumnSurface(g->user, cx, cz, sky, top);
}

int32_t sbw_sample_columns(const cv_generator* gp, int64_t x0, int64_t z0, int32_t count, int32_t stride,
                           uint32_t flags, int32_t* outHeights, int32_t* outWater, uint16_t* outSurface,
                           int32_t* outTrees) {
    Generator* g = genOf(gp);
    if (g == nullptr || outHeights == nullptr || count <= 0 || stride <= 0) return -1;
    return guarded([&] {
        const bool carved = (flags & SBW_SAMPLE_CARVED) != 0;
        for (int ix = 0; ix < count; ix++) {
            for (int iz = 0; iz < count; iz++) {
                const int idx = ix * count + iz;
                const int64_t wx = x0 + static_cast<int64_t>(ix) * stride, wz = z0 + static_cast<int64_t>(iz) * stride;
                const int col = colIdx(static_cast<int>(wx & 31), static_cast<int>(wz & 31));
                int32_t raw, water;
                int biome;
                uint16_t surface;
                if (!carved && stride > 1 && g->cfg.kind == SBW_KIND_STANDARD) {
                    /* strided raw samples: no tile per sample */
                    standardColumnPoint(*g, wx, wz, raw, water, biome, surface);
                } else {
                    auto b = g->base(wx >> 5, wz >> 5);
                    if (!b) throw std::runtime_error("column profile unavailable");
                    raw = b->h[col];
                    water = b->water[col];
                    biome = b->biome[col];
                    surface = b->surface[col];
                }
                int32_t height = raw;
                if (carved) {
                    auto cs = g->carved(wx >> 5, wz >> 5);
                    if (!cs) throw std::runtime_error("carve unavailable");
                    height = cs->carvedH[col];
                    surface = cs->top[col];
                    if (cs->sea[col] != 0 && water == NO_WATER) water = g->cfg.sea; /* a sea-flooded cut */
                }
                const bool needPool = outWater != nullptr || outSurface != nullptr || outTrees != nullptr;
                const int pool = needPool ? poolDepthProbe(*g, biome, surface, raw, height, water, wx, wz, carved) : 0;
                if (outTrees != nullptr) {
                    int trunk = 0;
                    const bool submerged = water != NO_WATER && water > raw;
                    int kind = -1;
                    if (!submerged && height == raw && pool != 2) {
                        kind = g->treeAt(wx, wz, pool > 0 ? g->cfg.id[SBW_WATER] : surface, biome, trunk);
                    }
                    outTrees[idx] = kind < 0 ? 0 : ((kind + 1) | (trunk << 8));
                }
                outHeights[idx] = height;
                if (pool > 0) {
                    outHeights[idx] = height - pool;
                    water = raw;
                    surface = g->cfg.id[SBW_DIRT];
                }
                if (outWater != nullptr) outWater[idx] = water;
                if (outSurface != nullptr) outSurface[idx] = surface;
            }
        }
        return 0;
    });
}

int32_t sbw_sample_cell_openings(const cv_generator* gp, int64_t x0, int64_t z0, int32_t cells, int32_t cellSize,
                                 const int32_t* cellHeights, int32_t* outFloor, uint8_t* outCoverage) {
    Generator* g = genOf(gp);
    if (g == nullptr || cells <= 0 || cellSize <= 0 || cellHeights == nullptr || outFloor == nullptr
            || outCoverage == nullptr) {
        return -1;
    }
    return guarded([&] {
        for (int ix = 0; ix < cells; ix++) {
            for (int iz = 0; iz < cells; iz++) {
                const int cell = ix * cells + iz;
                const int32_t shown = cellHeights[cell];
                int32_t floor = INT32_MAX;
                int carvedCount = 0;
                for (int dx = 0; dx < cellSize; dx++) {
                    for (int dz = 0; dz < cellSize; dz++) {
                        const int64_t wx = x0 + static_cast<int64_t>(ix) * cellSize + dx;
                        const int64_t wz = z0 + static_cast<int64_t>(iz) * cellSize + dz;
                        auto cs = g->carved(wx >> 5, wz >> 5);
                        if (!cs) throw std::runtime_error("carve unavailable");
                        const int col = colIdx(static_cast<int>(wx & 31), static_cast<int>(wz & 31));
                        if (cs->carved[col] == 0) continue;
                        carvedCount++;
                        floor = std::min(floor, cs->carvedH[col]);
                    }
                }
                if (carvedCount == 0 || floor >= shown) {
                    outFloor[cell] = INT32_MIN;
                    outCoverage[cell] = 0;
                } else {
                    outFloor[cell] = floor;
                    const int total = cellSize * cellSize;
                    outCoverage[cell] = static_cast<uint8_t>(std::min(255, std::max(1, carvedCount * 255 / total)));
                }
            }
        }
        return 0;
    });
}

int32_t sbw_probe_cell_trees(const cv_generator* gp, int64_t x0, int64_t z0, int32_t cells, int32_t cellSize,
                             const int32_t* cellHeights, const int32_t* cellWater, const uint16_t* cellSurface,
                             int32_t* outSpots, int32_t outCap) {
    Generator* g = genOf(gp);
    if (g == nullptr || cells <= 0 || cellSize <= 0 || cellHeights == nullptr || cellWater == nullptr
            || cellSurface == nullptr) {
        return -1;
    }
    return guarded([&]() -> int32_t {
        int32_t n = 0;
        for (int ix = 0; ix < cells; ix++) {
            for (int iz = 0; iz < cells; iz++) {
                const int cell = ix * cells + iz;
                const int rep = cellSize / 2;
                const int64_t repX = x0 + static_cast<int64_t>(ix) * cellSize + rep;
                const int64_t repZ = z0 + static_cast<int64_t>(iz) * cellSize + rep;
                auto b = g->base(repX >> 5, repZ >> 5);
                if (!b) throw std::runtime_error("column profile unavailable");
                const int rcol = colIdx(static_cast<int>(repX & 31), static_cast<int>(repZ & 31));
                const int biome = b->biome[rcol];
                uint16_t surface = cellSurface[cell];
                if (cellWater[cell] != NO_WATER && cellWater[cell] > cellHeights[cell]) {
                    const int32_t raw = b->h[rcol];
                    const bool shallowPool = cellWater[cell] == raw && cellHeights[cell] == raw - 1
                        && poolDepthProbe(*g, biome, b->surface[rcol], raw, raw, b->water[rcol], repX, repZ, false) > 0;
                    if (!shallowPool) continue;
                    surface = g->cfg.id[SBW_WATER];
                }
                if (!growsTreeOn(*g, surface)) continue;
                for (int dx = 0; dx < cellSize; dx++) {
                    for (int dz = 0; dz < cellSize; dz++) {
                        const int lx = ix * cellSize + dx, lz = iz * cellSize + dz;
                        int trunk = 0;
                        const int kind = g->treeAt(x0 + lx, z0 + lz, surface, biome, trunk);
                        if (kind < 0) continue;
                        if (n < outCap && outSpots != nullptr) {
                            outSpots[n] = (lx & 0xFF) | ((lz & 0xFF) << 8) | (kind << 16)
                                          | (std::max(0, std::min(31, trunk)) << 18);
                        }
                        n++;
                    }
                }
            }
        }
        return n <= outCap ? n : CV_NEED(n);
    });
}

int32_t sbw_query_column(const cv_generator* gp, int64_t x, int64_t z, sbw_column_info* out) {
    Generator* g = genOf(gp);
    if (g == nullptr || out == nullptr) return -1;
    return guarded([&] {
        auto b = g->base(x >> 5, z >> 5);
        auto cs = g->carved(x >> 5, z >> 5);
        if (!b || !cs) throw std::runtime_error("column profile unavailable");
        const int col = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
        std::memset(out, 0, sizeof(*out));
        out->height = b->h[col];
        out->carved_height = cs->carvedH[col];
        out->water_level = b->water[col];
        if (cs->sea[col] != 0 && out->water_level == NO_WATER) out->water_level = g->cfg.sea; /* a sea-flooded cut */
        out->biome = b->biome[col];
        out->surface_block = cs->top[col];
        out->pool_depth = poolDepthProbe(*g, b->biome[col], cs->top[col], b->h[col], cs->carvedH[col], b->water[col], x, z, true);
        if (out->pool_depth > 0) out->water_level = b->h[col];
        const bool submerged = b->water[col] != NO_WATER && b->water[col] > b->h[col];
        if (!submerged && cs->carvedH[col] == b->h[col] && out->pool_depth != 2) {
            int trunk = 0;
            const int kind = g->treeAt(x, z, out->pool_depth > 0 ? g->cfg.id[SBW_WATER] : cs->top[col], b->biome[col], trunk);
            out->tree = kind < 0 ? 0 : ((kind + 1) | (trunk << 8));
        }
        auto st = g->surfaceTile(x >> 5, z >> 5);
        out->top = st ? static_cast<int32_t>(st->top[col]) : out->carved_height;
        out->temperature = b->temp[col];
        out->moisture = b->moist[col];
        return 0;
    });
}

int32_t sbw_column_carve(const cv_generator* gp, int64_t cx, int64_t cz, int32_t* outH, uint8_t* outCarved) {
    Generator* g = genOf(gp);
    if (g == nullptr) return -1;
    return guarded([&] {
        auto cs = g->carved(cx, cz);
        if (!cs) throw std::runtime_error("carve unavailable");
        if (outH) std::memcpy(outH, cs->carvedH, sizeof(cs->carvedH));
        if (outCarved) std::memcpy(outCarved, cs->carved, sizeof(cs->carved));
        return 0;
    });
}

} // extern "C"
