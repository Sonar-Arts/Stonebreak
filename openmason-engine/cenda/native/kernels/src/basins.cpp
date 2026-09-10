/* ck_solve_basins — one region's lakes and rivers, across the FFM boundary.
 *
 * Layers 1 and 2 of the lakes-first hydrology, exposed to Java. All of the
 * thinking is in basin_plan.hpp and river_plan.hpp; this file is the C ABI
 * wrapper and nothing else.
 *
 * ═══ Why a REGION and not a tile ═══
 *
 * The solve costs ~23 ms on the 512² window L1 uses. Run per terrain tile that
 * would be ruinous; run once per 4096-block region and cached, it is 0.089 ms
 * amortized over the region's 256 tiles. So the unit of work here is the
 * region, the caller (`BasinCache`) owns the cache, and `water.cpp` only ever
 * reads the finished planes and polylines.
 *
 * ═══ Why lakes and rivers come from one call ═══
 *
 * The routes need the fill's basin table — spill points, areas, volumes — and
 * that table deliberately does not cross this boundary; publishing it would
 * mean marshalling a structure whose only consumer is C++. Planning rivers in
 * a second entry point would therefore mean solving the region twice, at 23 ms
 * a time. One call, one cache entry, one solve.
 *
 * ═══ Why the result is two float planes and a polyline ═══
 *
 * `filled` is the water surface and the routing field; `depth` is the lake,
 * zero wherever there is no lake this level vouches for. Ownership (§4.4) is
 * applied before either leaves this function, which is what makes them
 * self-describing: `depth > 0` means "a lake this level may emit", so the
 * caller needs no basin table to use them and the finer level needs none to
 * import from them. Rivers leave as packed vertices because a route is a
 * polyline at block resolution and rasterising it onto the cell lattice here
 * would throw away exactly the sub-cell detail phase 9 exists to add.
 */

#include "cenda/kernels.h"
#include "basin_plan.hpp"
#include "river_plan.hpp"

#include <cstring>
#include <vector>

namespace {

namespace bp = cenda::basin;
namespace rp = cenda::river;

/* Per-thread scratch, reused across calls on the same worker (the pattern
 * generator.cpp and water.cpp already use). A 512² solve holds ~7 MB. */
thread_local bp::Solution tlsSolution;
thread_local std::vector<rp::Route> tlsRoutes;

/* The caller's rung of the escalation ladder, as geometry rather than an id.
 * `downsample` is derived, not passed: it is only ever "how many L1 cells wide
 * is one of my cells", and a caller free to state it separately could state it
 * inconsistently with cell_blocks. */
bp::Level levelFrom(int32_t cellBlocks, int32_t regionBlocks, int32_t haloBlocks) {
    return bp::Level{cellBlocks, regionBlocks, haloBlocks,
                     cellBlocks / bp::LEVEL_L1.cellBlocks};
}

void readBasinParams(const float* params, int32_t n, bp::Config& cfg) {
    if (params == nullptr) {
        return;
    }
    if (n > 0) cfg.minLakeDepth = params[0];
    if (n > 1) cfg.minLakeArea = static_cast<int32_t>(params[1]);
    if (n > 2) cfg.seaLevel = params[2];
}

void readRiverParams(const float* params, int32_t n, rp::Config& cfg) {
    if (params == nullptr) {
        return;
    }
    if (n > 2) cfg.seaLevel = params[2];
    if (n > 3) cfg.minRiverLakeArea = static_cast<int32_t>(params[3]);
    if (n > 4) cfg.riverKeepFraction = params[4];
    if (n > 5) cfg.stepLen = params[5];
    if (n > 6) cfg.maxSteps = static_cast<int32_t>(params[6]);
    if (n > 7) cfg.wInertia = params[7];
    if (n > 8) cfg.wDescent = params[8];
    if (n > 9) cfg.meanderAmp = params[9];
    if (n > 10) cfg.gorgeMaxDepth = params[10];
    if (n > 11) cfg.gorgeMaxWidth = params[11];
    if (n > 12) cfg.waterfallMinDrop = params[12];
    if (n > 13) cfg.wBase = params[13];
    if (n > 14) cfg.wLake = params[14];
    if (n > 15) cfg.wDist = params[15];
    if (n > 16) cfg.volScale = params[16];
    if (n > 17) cfg.distScale = params[17];
    if (n > 18) cfg.dBase = params[18];
    if (n > 19) cfg.dGain = params[19];
    if (n > 20) cfg.plungeWiden = params[20];
    if (n > 21) cfg.refineLevels = static_cast<int32_t>(params[21]);
    if (n > 22) cfg.refineAmp = params[22];
    if (n > 23) cfg.minPoints = static_cast<int32_t>(params[23]);
    /* [24] and [25] are the tunnel knobs, which only the carve reads. The two
     * carve-only slots that used to sit at [10] and [14] went with the valley
     * pull; the indices after them shifted down to close the holes. */
}

} // namespace

extern "C" {

int32_t ck_solve_basins(int64_t seed,
                        int32_t region_blocks, int32_t halo_blocks,
                        int32_t cells, int32_t cell_blocks,
                        int64_t origin_x, int64_t origin_z,
                        const float* dem,
                        const float* params, int32_t n_params,
                        int32_t coarse_cells, int32_t coarse_cell_blocks,
                        int64_t coarse_origin_x, int64_t coarse_origin_z,
                        const float* coarse_filled, const float* coarse_depth,
                        float* out_filled, float* out_depth,
                        int32_t max_routes, int32_t max_vertices,
                        int32_t* out_route_starts, float* out_vertices,
                        int32_t* out_river_counts,
                        int32_t* out_withheld) {
    if (dem == nullptr || out_filled == nullptr || out_depth == nullptr) {
        return -1;
    }
    /* Cleared up front so an early bad-args return never leaves the caller
     * reading whatever was in its buffer as a stake. */
    if (out_withheld != nullptr) {
        out_withheld[0] = 0;
        out_withheld[1] = 0;
    }
    if (cells < 16 || cells > 4096 || cell_blocks < 1) {
        return -2;
    }
    /* The ladder's invariants. A rung whose region is not a whole number of
     * cells, or whose halo is not, cannot place its owned rectangle on the cell
     * lattice, and `applyOwnership` would test the wrong cells. */
    if (region_blocks < 1 || halo_blocks < 0
            || region_blocks % cell_blocks != 0 || halo_blocks % cell_blocks != 0
            || cell_blocks % bp::LEVEL_L1.cellBlocks != 0) {
        return -3;
    }

    bp::Config cfg;
    readBasinParams(params, n_params, cfg);
    if (cfg.minLakeArea < 1) {
        return -4;
    }

    const bp::Level lv = levelFrom(cell_blocks, region_blocks, halo_blocks);
    /* The window must be the level's own, because ownership of both lakes and
     * river sources is decided from where the region sits — not from where the
     * caller happened to place a window. */
    if (cells != lv.windowCells()) {
        return -5;
    }
    if ((origin_x + lv.haloBlocks) % lv.regionBlocks != 0
            || (origin_z + lv.haloBlocks) % lv.regionBlocks != 0) {
        return -6;
    }
    const int64_t regionX = (origin_x + lv.haloBlocks) / lv.regionBlocks;
    const int64_t regionZ = (origin_z + lv.haloBlocks) / lv.regionBlocks;

    const bp::Grid g{dem, cells, cell_blocks, origin_x, origin_z};
    bp::Solution& s = tlsSolution;
    bp::solve(g, cfg, s);
    const bp::Withheld w = bp::applyOwnership(s, lv);
    const int32_t withheld = w.count;
    /* Written before the coarse import, so it describes what THIS level could
     * not own — which is the question an escalation decision is asking. After
     * the import the same basins are present and no longer withheld. */
    if (out_withheld != nullptr) {
        out_withheld[0] = w.lakeCells;
        out_withheld[1] = w.spanBlocks;
    }

    /* The coarse level is optional and, on terrain whose basins all fit this
     * level's halo, never supplied — `withheld == 0` is the caller's licence
     * to skip a solve that costs a cold macro-region of GPU elevation. */
    if (coarse_filled != nullptr && coarse_depth != nullptr && coarse_cells > 0
            && coarse_cell_blocks > 0) {
        const bp::Grid cg{nullptr, coarse_cells, coarse_cell_blocks,
                          coarse_origin_x, coarse_origin_z};
        bp::mergeLevels(g, s, cg, coarse_filled, coarse_depth, cfg);
    }

    const auto n = static_cast<size_t>(cells) * static_cast<size_t>(cells);
    std::memcpy(out_filled, s.filled.data(), n * sizeof(float));
    std::memcpy(out_depth, s.depth.data(), n * sizeof(float));

    if (out_vertices == nullptr || out_route_starts == nullptr
            || out_river_counts == nullptr || max_routes <= 0 || max_vertices <= 0) {
        return withheld; /* lakes only */
    }
    out_river_counts[0] = 0;
    out_river_counts[1] = 0;

    rp::Config rcfg;
    rcfg.seaLevel = cfg.seaLevel;
    readRiverParams(params, n_params, rcfg);
    std::vector<rp::Route>& routes = tlsRoutes;
    rp::plan(g, s, lv, regionX, regionZ, seed, rcfg, routes);

    /* Packed out, stopping at either cap rather than overflowing. A caller
     * that sees its counts pinned at the caps should raise them; silently
     * dropping the tail of a river would put a seam back. */
    int32_t nRoutes = 0;
    int32_t nVerts = 0;
    out_route_starts[0] = 0;
    for (const rp::Route& r : routes) {
        const auto count = static_cast<int32_t>(r.points.size());
        if (nRoutes >= max_routes || nVerts + count > max_vertices) {
            break;
        }
        for (const rp::Vertex& v : r.points) {
            float* dst = out_vertices
                + static_cast<size_t>(nVerts) * CK_RIVER_VERTEX_FLOATS;
            dst[0] = v.x;
            dst[1] = v.z;
            dst[2] = v.surf;
            dst[3] = v.width;
            dst[4] = v.bedDepth;
            dst[5] = v.bank;
            int32_t flags = 0;
            if (v.waterfall) flags |= CK_RIVER_FLAG_WATERFALL;
            if (v.reach == rp::Reach::Gorge) flags |= CK_RIVER_FLAG_GORGE;
            dst[6] = static_cast<float>(flags);
            ++nVerts;
        }
        ++nRoutes;
        out_route_starts[nRoutes] = nVerts;
    }
    out_river_counts[0] = nRoutes;
    out_river_counts[1] = nVerts;
    return withheld;
}

} // extern "C"
