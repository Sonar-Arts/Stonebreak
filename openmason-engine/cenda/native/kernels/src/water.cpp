/* ck_carve_water — inland water at block resolution, stamped from a water plan.
 *
 * Layer 3 of the lakes-first hydrology (Dev Working/Lakes-first hydrology
 * plan.md §6). Input is one terrain tile's raw block heights plus a one-tile
 * halo, the depression-fill planes covering that same ground, and the river
 * polylines planned over it; output is the center tile's heights, per-column
 * water levels, and — where a river runs under standing ground — the floor and
 * roof of the tunnel that carries it.
 *
 * ═══ What this file used to be, and why none of it survived ═══
 *
 * The previous version derived water from noise alone: rivers were the zero
 * isoline of a channel field, their width came from |C| / |grad C|, their
 * surface from a box-blurred and eroded copy of the terrain, and lakes came
 * from a jittered world lattice that flooded a bounded box, tried a ladder of
 * depths, and — when that found nothing, which on smooth terrain was nearly
 * always — excavated a pond instead. It was seam-free and it was fast, and it
 * delivered 0.04 % of land as inland water while the physical answer is 2.9 %.
 * Every one of those mechanisms is gone (§3.1). A channel field has no source
 * and no mouth and crosses contours; a width from a noise gradient is unrelated
 * to anything physical; the blur and erode existed to paper over the fact that
 * nothing routed; the lattice, the depth ladder and the excavator existed to
 * paper over the fact that nothing filled.
 *
 * One fill replaces all of it. `basin_plan.hpp` produces lake surfaces, lake
 * depths, river sources, river termini and the field rivers descend — five
 * things the old designs computed five different ways, none of them agreeing.
 *
 * ═══ Canonicality (the seam rule) ═══
 *
 * Every emitted value must be a pure function of (seed, column) so that any
 * tile whose window covers a column computes the identical value. This file no
 * longer has a mechanism of its own to argue about: it reads the DEM planes
 * and the routes, and those are canonical because a region owns them (§4.1,
 * §5) and because a basin small enough to be emitted is contained whole by
 * every window that touches it (§4.4). Everything here is either a comparison
 * or an order-independent merge — heights by `min`, water by `max` — so two
 * tiles stamping the same column from the same plan cannot disagree.
 *
 * ═══ Rivers tunnel; they do not excavate (2026-09-07) ═══
 *
 * This kernel does not lower terrain. It used to, in two places, and both are
 * gone:
 *
 *   The CHANNEL CUT wrote `carved = surf - cut` unconditionally. `surf` is the
 *   river surface read off the depression-filled DEM at 16-BLOCK cells, while
 *   `carved` lands in the FULL-RESOLUTION heightfield, and nothing compared the
 *   two. Where block-scale ground stood above `surf` the entire column dropped
 *   to the water line, so a river crossing a hill deleted the hill. The router's
 *   defence — "a route never climbs, largest rise 3.1 blocks"
 *   (river_plan.hpp) — is true and says nothing about this: it is measured on
 *   the same coarse grid the route descends, not on the ground written here.
 *
 *   The VALLEY PULL drew every column within 80 blocks down toward
 *   `surf + bank_tolerance`. It only ever lowered, so with the rule above it
 *   can no longer fire anywhere, and it is deleted rather than gated — a knob
 *   that looks honoured and is not is worse than an absent one. `git show
 *   6758ff77` has it if river valleys are wanted back.
 *
 * In their place, one per-column decision taken against the fine `raw`:
 *
 *     roof = min(surf + headroom·(1 - u²),  raw - tunnel_min_roof)
 *     roof >  surf  ->  TUNNEL: floor/roof planes, `carved` untouched
 *     roof <= surf  ->  OPEN:   `carved = surf - cut`, as before
 *
 * The roof arches on the same `u` the bed is cut on, so the void pinches shut
 * exactly where the channel does and the ground beside it is never touched.
 * `tunnel_min_roof` is the thinnest lid that reads as rock rather than debris,
 * and clamping the roof to `raw - it` makes it the only ground the stamp may
 * still take: about four blocks, against the unbounded amount it replaced.
 *
 * ═══ Containment (the WaterSim invariant) ═══
 *
 * Worldgen water is source blocks: a wet column with a lower dry 4-neighbor is
 * a permanent spring that floods every chunk it touches. Rule held here (same
 * as the bridge's carve.py): for every wet column at level W, each 4-neighbor
 * is wet itself or has terrain >= W. Wet-next-to-wet at different levels is a
 * waterfall and is deliberately allowed — §5.8 depends on it.
 */

#include "cenda/kernels.h"
#include "basin_plan.hpp"

#include <algorithm>
#include <cmath>
#include <vector>

namespace {

/* Blocks of air between the river surface and the tunnel roof, measured at the
 * centreline; the roof arches down to meet the surface at the channel edge.
 * Params slot [24] ("tunnel_headroom") overrides it. */
constexpr float DEF_TUNNEL_HEADROOM = 5.0f;
/* The thinnest rock lid that still reads as ground rather than as debris.
 * Because the roof is clamped to `raw - this`, it doubles as the excavation
 * budget: the most ground the stamp may take from a column it tunnels under.
 * Params slot [25] ("tunnel_min_roof") overrides it.
 *
 * Lowering it toward zero is what makes a river bead — `surf` is a bilinear
 * sample of a 16-block DEM and fine terrain wobbles a few blocks either side of
 * it, so a small value roofs a river with one-block lids wherever it does. This
 * is the knob to raise if that shows up. */
constexpr float DEF_TUNNEL_MIN_ROOF = 4.0f;

/* How far past the coarse lake mask the shore flood may travel, in blocks.
 * Params slot [26] ("lake_shore_reach") overrides it.
 *
 * This is a PATH length, not a box: a column's wetness has to be a function of
 * a neighbourhood no window can truncate, or two tiles sharing a shore draw it
 * differently. Every center-tile column has T blocks of margin inside its own
 * window, so the cap is clamped to `T - 1` below. 64 covers any real shelf;
 * the clamp is what makes a silly value safe rather than seam-breaking. */
constexpr float DEF_LAKE_SHORE_REACH = 64.0f;
/* The deepest a column may sit below the lake surface and still be claimed by
 * the shore flood. Params slot [27] ("lake_shore_max_depth") overrides it.
 *
 * A real shore is shallow by definition — it is ground the coarse DEM read a
 * few blocks too high, because a cell is a 16x16 MEAN of a noisy heightmap and
 * the coarse fetch carries no noise at all. A column ten blocks under the
 * surface that the fill nonetheless called dry is a different animal: a
 * block-scale notch through the rim that the mean averaged away. Flooding it
 * would run a tongue of water down the outside of the basin. So the flood
 * repairs the shoreline and refuses to discover new basins, which is the fill's
 * job and needs the fill's window. */
constexpr float DEF_LAKE_SHORE_MAX_DEPTH = 8.0f;

inline size_t idx2(int row, int col, int stride) {
    return static_cast<size_t>(row) * static_cast<size_t>(stride) + static_cast<size_t>(col);
}

/** Floor division for the bilinear stencil, which straddles zero near a
 *  window's first cell. */
inline int floorDivInt(int a, int b) {
    const int q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

/* The DEM planes covering the 3x3 tile window, at cell resolution. */
struct Dem {
    const float* filled;
    const float* depth;
    int cells;
    int cellBlocks;
};

/**
 * The lake level at one column of the window, or -1 for dry.
 *
 * The lake's SURFACE is flat and comes from the fill; all that happens here is
 * deciding which columns are under it. That split is the whole trick: the level
 * is one integer for a whole basin, so a lake is level to the bit however
 * jagged its edge, while the edge itself is drawn at block resolution by
 * comparing real block heights against that level. A 16-block DEM produces a
 * 1-block shoreline for free, and the two properties never fight.
 *
 * Membership uses the bilinear 2x2 stencil — the four cells whose CENTERS
 * bracket the column — but takes the maximum level among those that are lake
 * rather than interpolating between them. §6 says "bilinear across cells", and
 * interpolating is what that would mean; it is also wrong here. Between a lake
 * cell and the dry cell beside it, an interpolated surface tilts, and a tilted
 * lake surface is the exact defect the old eroded-blur water plane had. Taking
 * the maximum keeps the surface flat and lets the lake reach at most half a
 * cell into the dry ground beside it, which is the shoreline tolerance the
 * coarse lattice owes the block grid. Where two lakes at different levels reach
 * the same column the higher wins, which is the same order-independent max rule
 * lakes already merge by.
 */
inline int lakeLevelAt(const Dem& dem, int x, int z) {
    /* Cell c's center sits at (c + 0.5) * cellBlocks in window coordinates, so
     * the stencil's low cell is floor(x / cellBlocks - 0.5). */
    const int i0 = floorDivInt(2 * x - dem.cellBlocks, 2 * dem.cellBlocks);
    const int j0 = floorDivInt(2 * z - dem.cellBlocks, 2 * dem.cellBlocks);
    int level = -1;
    for (int di = 0; di <= 1; ++di) {
        const int ci = std::clamp(i0 + di, 0, dem.cells - 1);
        for (int dj = 0; dj <= 1; ++dj) {
            const int cj = std::clamp(j0 + dj, 0, dem.cells - 1);
            const size_t c = idx2(ci, cj, dem.cells);
            if (dem.depth[c] > 0.0f) {
                level = std::max(level, static_cast<int>(std::lround(dem.filled[c])));
            }
        }
    }
    return level;
}

/* ── Rivers ─────────────────────────────────────────────────────────────── */

/** One reach of a route, in WINDOW coordinates. */
struct Seg {
    float ax, az, bx, bz;
    float aSurf, bSurf;
    float aHalf, bHalf;   /* half-widths */
    float aBed, bBed;
    float shape;          /* cross-section exponent; see rosgenShape */
    bool falls;           /* the surface steps rather than ramping (§5.8) */
    bool gorge;           /* runs between walls: a narrower channel (§5.6) */
};

/**
 * Cross-section exponent from a simplified Rosgen classification on
 * (local slope, width) — §6's "cheap per-column table lookup, and the largest
 * visual gain per millisecond available".
 *
 * The cut across a channel is `bed * (1 - t^p)` for `t` the fraction of the
 * half-width, so `p` alone decides the shape: near 1 it is a V, and as it
 * grows the bed flattens and the banks steepen into a U. Rosgen's stream types
 * differ mostly in exactly that, and mostly with gradient:
 *
 *   A  steep (> 4 %)   entrenched step-pool, narrow and deep     -> V
 *   B  moderate (2-4%) moderately entrenched, moderate w/d       -> between
 *   C/E gentle (< 2 %) meandering, wide and shallow, flat bed    -> U
 *
 * This is a caricature of the classification, not the classification: real
 * Rosgen needs entrenchment ratio, width/depth ratio and sinuosity, and types
 * D (braided) and F (incised) have no representation here at all. It is
 * documented as a caricature because it will look right without being right,
 * the same trade §5.5 makes for width.
 */
inline float rosgenShape(float slope) {
    if (slope > 0.04f) {
        return 1.2f;   /* A: a V-notch cut by a steep stream          */
    }
    if (slope > 0.015f) {
        return 2.0f;   /* B: a moderate trough                        */
    }
    return 3.2f;       /* C/E: a broad flat bed with defined banks    */
}

/** Squared distance from a point to a segment, and the parameter of the
 *  nearest point along it. */
inline float segDistanceSq(const Seg& s, float px, float pz, float& t) {
    const float dx = s.bx - s.ax;
    const float dz = s.bz - s.az;
    const float len2 = dx * dx + dz * dz;
    t = len2 > 1e-9f ? ((px - s.ax) * dx + (pz - s.az) * dz) / len2 : 0.0f;
    t = std::clamp(t, 0.0f, 1.0f);
    const float qx = s.ax + dx * t - px;
    const float qz = s.az + dz * t - pz;
    return qx * qx + qz * qz;
}

/* Per-thread scratch. Reused across calls on the same worker thread (the
 * generator.cpp pattern). */
struct Scratch {
    std::vector<int16_t> carved;
    std::vector<int16_t> water;
    /* Where a river runs under standing ground: the void carried through it,
     * -1 for the great majority of columns that have none. */
    std::vector<int16_t> floor;
    std::vector<int16_t> roof;
    /* Every segment of the refined polyline: what the CHANNEL is cut from,
     * where four-block detail is the whole point of refining. */
    std::vector<Seg> channel;
    /* Segment indices per 16-block bucket of the window. Rivers touch a few per
     * cent of a tile, so the bucket list is what lets the other 95 % of columns
     * skip the river pass on one empty-vector test. */
    std::vector<std::vector<int32_t>> channelBuckets;
    /* Which columns are under a lake surface, and at what level; -1 for dry.
     * Built once per tile by `floodLakeShore` and read by the stamp. */
    std::vector<int16_t> lakeWater;
    /* The shore flood's working set: the seeded columns that have somewhere to
     * expand to, their levels, and the two rings of a level-synchronous BFS.
     * Synchronous rings rather than a distance plane — the ring index IS the
     * path length, so the reach cap costs no memory and no per-column state. */
    std::vector<int32_t> shoreFrontier;
    std::vector<int32_t> shoreFrontierLevel;
    std::vector<int32_t> shoreRing;
    std::vector<int32_t> shoreNextRing;
    std::vector<int32_t> shoreLevels;
};

thread_local Scratch tls;

/**
 * Fill `s.lakeWater` over `[lo, hi)^2` of the window: the level at every column
 * a lake covers, -1 elsewhere.
 *
 * `lakeLevelAt` decides where a lake IS. This decides where it ENDS, and the
 * two are different questions that were being answered by the same test.
 *
 * The coarse answer is a cell mask: a lake cell donates its level to its own 16
 * columns plus 8 blocks of dilation, so the wet region's boundary lies on the
 * lines `x = 8 (mod 16)` — straight, axis-aligned, and visible from a long way
 * off. Worse, the columns cut off that way are not merely left dry: §3's
 * containment repair RAISES them flush to the water, so the artifact reads as a
 * wall of ground standing exactly at the waterline. (Beaches are disabled for
 * all inland water because of those walls — see DiffusionBiomeMapper.)
 *
 * Two things guarantee real ground falls outside the mask, and neither is a
 * tuning error. `min_lake_depth` drops the outermost ring of cells, whose 16x16
 * MEAN depth is under half a block even where most of their columns are wet.
 * And the coarse DEM is a different FIELD from the block heightmap, not a
 * coarser view of it: the coarse fetch carries no noise and is not floored,
 * while fine heights carry both, so fine ground near a shore sits
 * systematically below what the fill believes.
 *
 * So the mask seeds a flood and stops there. The shore is then wherever the
 * real blocks cross the level — connectivity, at block resolution, the way the
 * water itself would find it.
 *
 * What is deliberately NOT changed by this: the level. It is still one integer
 * per basin, still straight from the fill, still never interpolated. A flood
 * cannot tilt a surface, which is why this can be a pure widening of the wet
 * set rather than a new way of computing where the water sits.
 *
 * Order-independence, which the seam rule needs: levels are flooded in
 * DESCENDING order and a claimed column is never re-claimed, so where two lakes
 * reach one column the higher wins — the same max rule `lakeLevelAt` already
 * uses across its stencil, and the same one rivers merge by. Within one level a
 * BFS with uniform edge weights reaches the same set whatever the queue order.
 *
 * Seam-safety, which is the reason for `reach`: a claim travels at most `reach`
 * columns from a seed, so a center-tile column's answer depends only on ground
 * within `reach` of it. `[lo, hi)` is the stamp range grown by `reach`, and the
 * caller clamps `reach` to `T - 1`, so every column of every path is inside the
 * window that stamps it. Grow either without the other and two tiles will
 * disagree along their shared edge.
 */
void floodLakeShore(const Dem& dem, const int16_t* heights, int W,
                    int world_height, int lo, int hi,
                    int reach, int maxDepth, Scratch& s) {
    const size_t N = static_cast<size_t>(W) * static_cast<size_t>(W);
    s.lakeWater.assign(N, -1);

    /* 1. Seed from the coarse mask, by exactly the test the stamp used to make
     *    inline. A seed is the fill's own answer and is taken as given; the
     *    depth cap below applies only to what the flood ADDS. */
    bool any = false;
    for (int x = lo; x < hi; ++x) {
        for (int z = lo; z < hi; ++z) {
            const size_t i = idx2(x, z, W);
            const int level = lakeLevelAt(dem, x, z);
            if (level <= 0) {
                continue;
            }
            if (std::clamp<int>(heights[i], 1, world_height - 1) >= level) {
                continue;
            }
            s.lakeWater[i] = static_cast<int16_t>(level);
            any = true;
        }
    }
    if (!any || reach <= 0 || maxDepth <= 0) {
        return;
    }

    /* 2. The frontier: seeded columns with an unclaimed neighbour. A lake's
     *    interior is already answered, so only its rim can expand — which is
     *    what keeps this proportional to shoreline rather than to lake area. */
    s.shoreFrontier.clear();
    s.shoreFrontierLevel.clear();
    const auto stride = static_cast<size_t>(W);
    for (int x = lo + 1; x < hi - 1; ++x) {
        for (int z = lo + 1; z < hi - 1; ++z) {
            const size_t i = idx2(x, z, W);
            const int level = s.lakeWater[i];
            if (level < 0) {
                continue;
            }
            if (s.lakeWater[i - stride] >= 0 && s.lakeWater[i + stride] >= 0
                    && s.lakeWater[i - 1] >= 0 && s.lakeWater[i + 1] >= 0) {
                continue;
            }
            s.shoreFrontier.push_back(static_cast<int32_t>(i));
            s.shoreFrontierLevel.push_back(level);
        }
    }
    if (s.shoreFrontier.empty()) {
        return;
    }

    /* 3. Distinct levels, highest first. There are as many as there are lakes
     *    touching the window — a handful — so this sorts the frontier's levels
     *    and not the far larger seed set. */
    s.shoreLevels.assign(s.shoreFrontierLevel.begin(), s.shoreFrontierLevel.end());
    std::sort(s.shoreLevels.begin(), s.shoreLevels.end(),
              [](int32_t a, int32_t b) { return a > b; });
    s.shoreLevels.erase(std::unique(s.shoreLevels.begin(), s.shoreLevels.end()),
                        s.shoreLevels.end());

    for (const int32_t level : s.shoreLevels) {
        s.shoreRing.clear();
        for (size_t k = 0; k < s.shoreFrontier.size(); ++k) {
            if (s.shoreFrontierLevel[k] == level) {
                s.shoreRing.push_back(s.shoreFrontier[k]);
            }
        }
        /* Ring `step` holds the columns exactly `step` from the mask, so the
         * loop bound IS the reach cap. */
        for (int step = 0; step < reach && !s.shoreRing.empty(); ++step) {
            s.shoreNextRing.clear();
            for (const int32_t ci : s.shoreRing) {
                const int cx = ci / W;
                const int cz = ci % W;
                const auto claim = [&](int nx, int nz) {
                    if (nx < lo || nx >= hi || nz < lo || nz >= hi) {
                        return;
                    }
                    const size_t ni = idx2(nx, nz, W);
                    if (s.lakeWater[ni] >= 0) {
                        return;
                    }
                    const int h = std::clamp<int>(heights[ni], 1, world_height - 1);
                    if (h >= level || level - h > maxDepth) {
                        return;
                    }
                    /* And the fill's own answer about which ground belongs to
                     * this water: the cell's filled surface must stand at the
                     * level or above it.
                     *
                     * The depth cap alone does not bound an ESCAPE. A notch
                     * through the rim, five blocks wide and too narrow for a
                     * 16x16 mean to notice, is shallow where it leaves the
                     * lake and only deepens at the gradient of the ground
                     * outside — so a cap on depth lets the flood walk tens of
                     * blocks down the outside of the basin before it bites.
                     * `filled` says which side of the spill a cell is on, which
                     * is the question actually being asked, and it says it from
                     * the fill's window rather than from this tile's. Inside
                     * the basin `filled` IS the level; on the shore ring it is
                     * the ground, which is why that ring stays reachable; one
                     * cell past the spill it is already below, which is where
                     * this stops. Repair a shoreline, never discover a basin. */
                    const int cellI = std::clamp(nx / dem.cellBlocks, 0, dem.cells - 1);
                    const int cellJ = std::clamp(nz / dem.cellBlocks, 0, dem.cells - 1);
                    if (std::lround(dem.filled[idx2(cellI, cellJ, dem.cells)]) < level) {
                        return;
                    }
                    s.lakeWater[ni] = static_cast<int16_t>(level);
                    s.shoreNextRing.push_back(static_cast<int32_t>(ni));
                };
                claim(cx - 1, cz);
                claim(cx + 1, cz);
                claim(cx, cz - 1);
                claim(cx, cz + 1);
            }
            s.shoreRing.swap(s.shoreNextRing);
        }
    }
}

constexpr int BUCKET = 16;

/** Register a segment list into buckets, each expanded by its own reach. */
inline void bucketSegments(const std::vector<Seg>& segs, float pad, int nb, int W,
                           std::vector<std::vector<int32_t>>& out) {
    (void)W;
    out.assign(static_cast<size_t>(nb) * static_cast<size_t>(nb), {});
    for (size_t i = 0; i < segs.size(); ++i) {
        const Seg& g = segs[i];
        const float reach = pad + std::max(g.aHalf, g.bHalf) + 1.0f;
        const int x0 = std::max(0,
            static_cast<int>(std::floor((std::min(g.ax, g.bx) - reach) / BUCKET)));
        const int x1 = std::min(nb - 1,
            static_cast<int>(std::floor((std::max(g.ax, g.bx) + reach) / BUCKET)));
        const int z0 = std::max(0,
            static_cast<int>(std::floor((std::min(g.az, g.bz) - reach) / BUCKET)));
        const int z1 = std::min(nb - 1,
            static_cast<int>(std::floor((std::max(g.az, g.bz) + reach) / BUCKET)));
        for (int bx = x0; bx <= x1; ++bx) {
            for (int bz = z0; bz <= z1; ++bz) {
                out[static_cast<size_t>(bx) * static_cast<size_t>(nb)
                    + static_cast<size_t>(bz)].push_back(static_cast<int32_t>(i));
            }
        }
    }
}

} // namespace

extern "C" {

int32_t ck_carve_water(int64_t seed,
                       int32_t tile_size,
                       int32_t origin_x, int32_t origin_z,
                       const int16_t* heights3x3,
                       int32_t sea_level, int32_t world_height,
                       int32_t dem_cells, int32_t dem_cell_blocks,
                       const float* dem_filled, const float* dem_depth,
                       int32_t n_routes, const int32_t* route_starts,
                       const float* vertices,
                       const float* params, int32_t n_params,
                       int16_t* out_heights, int16_t* out_water,
                       int16_t* out_river_floor, int16_t* out_river_roof) {
    /* Unread, and kept in the signature on purpose. The DEM span and the routes
     * are addressed in world coordinates and everything else here is a
     * comparison, so stamping needs no hashed mechanism of its own; the plan is
     * the only source. Breaking the ABI again to re-add a seed would cost more
     * than carrying it. */
    (void)seed;
    if (heights3x3 == nullptr || out_heights == nullptr || out_water == nullptr) {
        return -1;
    }
    if (tile_size < 64 || tile_size > 4096) {
        return -2;
    }
    if (world_height < 64 || sea_level < 1 || sea_level >= world_height) {
        return -3;
    }
    const int T = tile_size;
    const int W = 3 * T;
    const size_t N = static_cast<size_t>(W) * static_cast<size_t>(W);

    /* The shared water params array (kernels.h documents it). The carve reads
     * exactly FOUR of its entries — [24]-[27]; the rest belong to the plan, and
     * sea level arrives as its own argument rather than through [2].
     *
     * Each is one idea in one slot, and this is the only place any of them is
     * consumed. `bank_tolerance` and `valley_radius` used to live at [10] and
     * [14] and were read here; the pull they shaped is gone, so they are gone
     * with it rather than left as knobs that look live. */
    float tunnelHeadroom = DEF_TUNNEL_HEADROOM;
    float tunnelMinRoofF = DEF_TUNNEL_MIN_ROOF;
    float shoreReachF = DEF_LAKE_SHORE_REACH;
    float shoreMaxDepthF = DEF_LAKE_SHORE_MAX_DEPTH;
    if (params != nullptr) {
        if (n_params > 24) tunnelHeadroom = params[24];
        if (n_params > 25) tunnelMinRoofF = params[25];
        if (n_params > 26) shoreReachF = params[26];
        if (n_params > 27) shoreMaxDepthF = params[27];
    }
    tunnelHeadroom = std::clamp(tunnelHeadroom, 0.0f, static_cast<float>(world_height));
    /* At least one block of lid: a roof flush with the surface is not a roof,
     * and it would let the void breach the ground it is supposed to run under. */
    const int tunnelMinRoof =
        std::max(1, static_cast<int>(std::lround(tunnelMinRoofF)));

    /* The DEM span is optional: without it the tile gets sea-level-only water,
     * which is the same graceful degradation as an absent kernels library. It
     * must cover the window exactly, because the whole point of this kernel is
     * that it invents nothing — a partial span would silently drop lakes near
     * one edge and the tile next door would disagree. */
    Dem dem{nullptr, nullptr, 0, 0};
    const bool haveDem = dem_filled != nullptr && dem_depth != nullptr && dem_cells > 0;
    if (haveDem) {
        if (dem_cell_blocks <= 0 || dem_cells * dem_cell_blocks != W) {
            return -4;
        }
        dem = Dem{dem_filled, dem_depth, dem_cells, dem_cell_blocks};
    }

    /* Clamped, not rejected: these arrive from outside, and a silly value
     * should cost a plain shoreline rather than a seam. `T - 1` is the margin
     * every center-tile column has inside its own window, and it is the whole
     * of the seam argument in `floodLakeShore` — a reach past it lets one tile
     * read ground its neighbour cannot, and the two draw different shores. */
    const int shoreReach =
        std::clamp(static_cast<int>(std::lround(shoreReachF)), 0, T - 1);
    const int shoreMaxDepth =
        std::clamp(static_cast<int>(std::lround(shoreMaxDepthF)), 0, world_height);

    Scratch& s = tls;
    s.carved.resize(N);
    s.water.assign(N, -1);
    s.floor.assign(N, -1);
    s.roof.assign(N, -1);

    /* ── 1a. Where the lakes are, at block resolution ──
     *
     * Grown by the reach on each side of §2's stamp range, because a claim
     * travels at most that far: the columns §2 stamps then have every path that
     * could reach them inside this domain, and no column §2 writes depends on
     * ground the domain cut off. The two bounds move together or not at all. */
    if (haveDem) {
        const int floodLo = std::max(0, (T - 1) - shoreReach);
        const int floodHi = std::min(W, (2 * T + 1) + shoreReach);
        floodLakeShore(dem, heights3x3, W, world_height,
                       floodLo, floodHi, shoreReach, shoreMaxDepth, s);
    } else {
        s.lakeWater.assign(N, -1);
    }

    /* ── 1. Build the river segments in window coordinates ──
     * Clipped to what can reach the window: a segment further than its own
     * half-width from it changes nothing here, and the region that owns it
     * stamps its own ground anyway. */
    s.channel.clear();
    const bool haveRivers = vertices != nullptr && route_starts != nullptr && n_routes > 0;
    if (haveRivers) {
        /* Build one Seg from two packed vertices, in window coordinates.
         *
         * Clipped by the segment's OWN half-width — a channel is a few blocks
         * wide, so a reach further than that from the window changes nothing
         * here. It used to be clipped by the valley radius, which was the pull's
         * reach and not the channel's; with the pull gone that bound was both
         * wrong and eighty blocks too generous. */
        const auto makeSeg = [&](const float* a, const float* b, Seg& g) {
            g.ax = a[0] - static_cast<float>(origin_x);
            g.az = a[1] - static_cast<float>(origin_z);
            g.bx = b[0] - static_cast<float>(origin_x);
            g.bz = b[1] - static_cast<float>(origin_z);
            const float reach = std::max(std::max(a[3], b[3]), 1.0f) * 0.5f + 1.0f;
            const float lo = -reach;
            const float hi = static_cast<float>(W) + reach;
            if (std::max(g.ax, g.bx) < lo || std::min(g.ax, g.bx) > hi
                    || std::max(g.az, g.bz) < lo || std::min(g.az, g.bz) > hi) {
                return false;
            }
            g.aSurf = a[2];
            g.bSurf = b[2];
            g.aHalf = std::max(a[3], 1.0f) * 0.5f;
            g.bHalf = std::max(b[3], 1.0f) * 0.5f;
            g.aBed = a[4];
            g.bBed = b[4];
            const auto flags = static_cast<int32_t>(b[6]);
            g.falls = (flags & CK_RIVER_FLAG_WATERFALL) != 0;
            g.gorge = (flags & CK_RIVER_FLAG_GORGE) != 0;
            if (g.gorge) {
                /* §5.6: a gorge is a narrow channel with walls, not a valley.
                 * Narrowing the channel is all this flag does now — the valley
                 * pull it used to suppress is gone for every reach, not just
                 * this one. */
                g.aHalf *= 0.75f;
                g.bHalf *= 0.75f;
            }
            const float segLen = std::hypot(g.bx - g.ax, g.bz - g.az);
            const float drop = g.aSurf - g.bSurf;
            g.shape = rosgenShape(segLen > 1e-3f ? drop / segLen : 0.0f);
            return true;
        };
        for (int32_t r = 0; r < n_routes; ++r) {
            const int32_t from = route_starts[r];
            const int32_t to = route_starts[r + 1];
            const auto at = [&](int32_t v) {
                return vertices + static_cast<size_t>(v) * CK_RIVER_VERTEX_FLOATS;
            };
            for (int32_t v = from; v + 1 < to; ++v) {
                Seg g{};
                if (makeSeg(at(v), at(v + 1), g)) {
                    s.channel.push_back(g);
                }
            }
        }
    }

    const int nb = (W + BUCKET - 1) / BUCKET;
    bucketSegments(s.channel, 2.0f, nb, W, s.channelBuckets);

    /* ── 2. Stamp: lakes from the fill, rivers from the plan, then the sea ──
     *
     * Over the center tile and a ONE-COLUMN ring around it, not the whole
     * window. The window exists so this pass has its INPUTS — a route sourced
     * next door crosses the border, and the DEM stencil straddles cells — and
     * both are read at world coordinates that lie outside the stamped range
     * without being stamped themselves. The ring is there for §3 below, which
     * walls a dry column against its four neighbors' water and therefore reads
     * one column past the tile on each side; nothing reads further.
     *
     * Stamping the full window instead computed 9x the columns and discarded
     * eight ninths of them. Measured on a 256-block tile, 200 iterations:
     * lakes only 4.22 -> 0.555 ms, with two routes crossing 14.70 -> 3.30 ms,
     * with `out_heights`/`out_water` byte-identical either way.
     *
     * The bound is coupled to §3's neighbor reads: widen those and this range
     * must widen with them, or the repair reads columns this pass never wrote.
     * `s.water` is cleared to -1 over the whole window below regardless, so
     * the failure mode of getting that wrong is a missing wall rather than
     * stale water from the previous tile on this thread. */
    const int stampLo = T - 1;
    const int stampHi = 2 * T + 1;
    for (int x = stampLo; x < stampHi; ++x) {
        for (int z = stampLo; z < stampHi; ++z) {
            const size_t i = idx2(x, z, W);
            /* Terrain is NOT carved for a lake. A lake sits in a depression the
             * terrain already has — that is what the fill found — so there is
             * nothing to excavate, and the old excavator existed only because
             * nothing was finding depressions. A river cuts a bed where it runs
             * at grade, and where it does not, tunnels instead of levelling the
             * ground in its way. */
            const int raw = std::clamp<int>(heights3x3[i], 1, world_height - 1);
            int carved = raw;
            int water = -1;
            /* The tunnel, if any reach over this column turns out to need one.
             * Merged across reaches the same order-independent way everything
             * else here is: floor by min, roof by max. */
            int tunnelFloor = world_height;
            int tunnelRoof = -1;

            /* §1a already answered this, on the real block heights and by
             * connectivity. The height test is a no-op for anything it claimed
             * — nothing raises `carved`, only rivers lower it — and is kept
             * because it is the invariant the plane is built to satisfy. */
            {
                const int level = s.lakeWater[i];
                if (level > 0 && carved < level) {
                    water = level;
                }
            }

            /* Rivers touch a few per cent of a tile, so for almost every
             * column this one empty-bucket test is the entire river pass. */
            const size_t bi = idx2(std::min(x / BUCKET, nb - 1),
                                   std::min(z / BUCKET, nb - 1), nb);
            const float px = static_cast<float>(x) + 0.5f;
            const float pz = static_cast<float>(z) + 0.5f;

            /* The channel, from every segment of the refined polyline: this is
             * the detail refinement exists for, and it is cheap because a
             * channel is a few blocks wide.
             *
             * Heights merge by min, water and roof by max, floor by min, so two
             * rivers meeting is order-independent and needs no confluence graph
             * (§5.9).
             *
             * TUNNEL vs OPEN is decided here, per column, against the FULL
             * RESOLUTION `raw` — which is the whole point of the change. `surf`
             * is a bilinear sample of a 16-block DEM, so it says nothing on its
             * own about the block-scale ground this kernel writes. */
            for (int32_t si : s.channelBuckets[bi]) {
                const Seg& g = s.channel[static_cast<size_t>(si)];
                float t = 0.0f;
                const float d2 = segDistanceSq(g, px, pz, t);
                const float half = g.aHalf + (g.bHalf - g.aHalf) * t;
                if (d2 >= half * half) {
                    continue;
                }
                /* §5.8: a falling reach steps rather than ramps. The surface
                 * takes the upstream level for the upper half and the
                 * downstream one for the lower, so the drop is a cliff and
                 * nothing is carved to ease it — which is exactly the
                 * "unnecessary terrain to hold the river" being avoided. The
                 * containment invariant already calls wet-next-to-wet at
                 * differing levels a waterfall. */
                const float surfF = g.falls
                    ? (t < 0.5f ? g.aSurf : g.bSurf)
                    : g.aSurf + (g.bSurf - g.aSurf) * t;
                /* Rounded once per cross-section: every column whose nearest
                 * point is at this `t` shares the level, so a channel never
                 * steps sideways across its own width. */
                const int surf = static_cast<int>(std::lround(surfF));
                const float bed = g.aBed + (g.bBed - g.aBed) * t;
                /* `cut` is `bed * (1 - u^p)`, deepest at the centreline and
                 * meeting the bank at the half-width; `p` is the Rosgen shape. */
                const float u = std::sqrt(d2) / half;
                const float cut = bed * (1.0f - std::pow(u, g.shape));
                const int bedY = surf - std::max(1, static_cast<int>(std::lround(cut)));

                /* Tunnel or open is decided by the COLUMN, not by the roof: is
                 * there enough ground standing over this reach's water to make a
                 * lid out of? Deciding it on the roof instead is a trap worth
                 * recording — the arch goes to zero at the channel edge, so
                 * every rim column fell to the open branch and was cut to the
                 * water line even with forty blocks of hill on it. That is the
                 * original defect, moved to the edge of the channel. */
                if (raw - tunnelMinRoof > surf) {
                    /* The ground stands and the river runs under it. `carved` is
                     * deliberately untouched: this is the whole point.
                     *
                     * The roof arches on the same `u` the bed is cut on, so the
                     * void pinches shut exactly where the channel does and the
                     * column outside the half-width is never opened — that, and
                     * not a containment rule, is what holds the water in. The
                     * clamp to `raw - tunnelMinRoof` is what keeps a lid on it. */
                    const float arch = tunnelHeadroom * (1.0f - u * u);
                    const int roofY = std::min(surf + static_cast<int>(std::lround(arch)),
                                               raw - tunnelMinRoof);
                    if (roofY > bedY) {
                        tunnelFloor = std::min(tunnelFloor, bedY);
                        tunnelRoof = std::max(tunnelRoof, roofY);
                    }
                } else {
                    carved = std::min(carved, bedY);
                }
                water = std::max(water, surf);
            }

            carved = std::clamp(carved, 1, world_height - 1);
            /* The sea is one case of the per-column water level (bridge rule). */
            if (carved < sea_level) {
                water = std::max(water, sea_level);
            }

            /* A column can be both, where one reach tunnels over it and another
             * runs open across it — a tight meander, or a confluence. The open
             * reach lowered `carved`, so hold the roof a full lid under it: a
             * void at or above the ground would put water in mid-air, and a lid
             * thinner than the one promised is not a roof. Where only tunnelling
             * happened `carved == raw` and the roof is already under that, so
             * this changes nothing. */
            tunnelRoof = std::min(tunnelRoof, carved - tunnelMinRoof);
            const bool hasTunnel = tunnelRoof > tunnelFloor && tunnelFloor >= 1;

            s.carved[i] = static_cast<int16_t>(carved);
            s.water[i] = static_cast<int16_t>(water);
            s.floor[i] = hasTunnel ? static_cast<int16_t>(tunnelFloor) : static_cast<int16_t>(-1);
            s.roof[i] = hasTunnel ? static_cast<int16_t>(tunnelRoof) : static_cast<int16_t>(-1);
        }
    }

    /* ── 3. Containment repair + emission (center tile only) ──
     *
     * The four neighbor reads below reach one column outside the tile, which
     * is exactly what §2's stamp range is sized for. Reaching further — a
     * diagonal, a second ring — means widening `stampLo`/`stampHi` to match. */
    for (int x = 0; x < T; ++x) {
        for (int z = 0; z < T; ++z) {
            const size_t wi = idx2(x + T, z + T, W);
            int h = s.carved[wi];
            const int16_t w = s.water[wi];
            if (w < 0) {
                /* Dry ground beside water must wall it (raise, never wet —
                 * extending water would need re-checking ITS neighbors). */
                int need = -1;
                need = std::max<int>(need, s.water[wi - static_cast<size_t>(W)]);
                need = std::max<int>(need, s.water[wi + static_cast<size_t>(W)]);
                need = std::max<int>(need, s.water[wi - 1]);
                need = std::max<int>(need, s.water[wi + 1]);
                if (h < need) {
                    h = std::min(need, world_height - 1);
                }
            }
            const size_t oi = idx2(x, z, T);
            out_heights[oi] = static_cast<int16_t>(h);
            out_water[oi] = w;
            if (out_river_floor != nullptr) {
                out_river_floor[oi] = s.floor[wi];
            }
            if (out_river_roof != nullptr) {
                out_river_roof[oi] = s.roof[wi];
            }
        }
    }
    return 0;
}

} // extern "C"
