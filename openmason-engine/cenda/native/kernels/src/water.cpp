/* ck_carve_water — inland water at block resolution, stamped from a water plan.
 *
 * Layer 3 of the lakes-first hydrology (Dev Working/Lakes-first hydrology
 * plan.md §6). Input is one terrain tile's raw block heights plus a one-tile
 * halo, the depression-fill planes covering that same ground, and the river
 * polylines planned over it; output is the center tile's heights and
 * per-column water levels.
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

constexpr float DEF_VALLEY_RADIUS = 80.0f;
/* Blocks of ground above the water surface that still read as an ordinary
 * bank: what a Normal reach's valley pull lifts terrain to. Params slot [10]
 * ("bank_tolerance") overrides it, and this is the slot's only consumer. */
constexpr float DEF_BANK_TOLERANCE = 8.0f;

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
    bool gorge;           /* no valley pull; leave the walls (§5.6)       */
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
    /* Every segment of the refined polyline: what the CHANNEL is cut from,
     * where four-block detail is the whole point of refining. */
    std::vector<Seg> channel;
    /* The same routes decimated: what the VALLEY PULL is shaped from. */
    std::vector<Seg> valley;
    /* Segment indices per 16-block bucket of the window, one set per list.
     * Rivers touch a few per cent of a tile, so the bucket list is what lets
     * the other 95 % of columns skip the river pass on one empty-vector test. */
    std::vector<std::vector<int32_t>> channelBuckets;
    std::vector<std::vector<int32_t>> valleyBuckets;
};

thread_local Scratch tls;

constexpr int BUCKET = 16;

/* Length of one segment of the decimated polyline the VALLEY PULL reads, as a
 * fraction of the pull's own radius.
 *
 * The pull falls off smoothly over its whole radius, so its shape cannot tell a
 * four-block polyline from a thirty-two-block one: the two differ in distance
 * by a few blocks, which moves the pull by a few per cent. The CHANNEL is a
 * different matter and reads every segment, because that detail is visible at
 * the water's edge and is the whole reason for refining.
 *
 * This is the difference between fitting §10's 15 ms per tile and not. Testing
 * every refined segment against every column within the pull radius cost 52 ms
 * on the busiest tile of the real fixture against 6 ms on average; measured
 * over the same tiles as the decimation coarsens:
 *
 *     segment length    worst tile
 *     4 blk (none)        52.5 ms
 *     16 blk              17.1 ms
 *     32 blk              11.3 ms
 *     48 blk               9.6 ms
 *
 * 0.4 puts the polyline at 32 blocks against an 80-block radius, which is
 * comfortably inside budget while still following a meander. Expressed as a
 * fraction rather than a vertex count so it stays right if `refine_levels` or
 * `valley_radius` are retuned. */
constexpr float VALLEY_SEGMENT_FRACTION = 0.4f;

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
                       int16_t* out_heights, int16_t* out_water) {
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
     * exactly TWO of its entries — [10] and [14]; the rest belong to the plan,
     * and sea level arrives as its own argument rather than through [2].
     *
     * `bank_tolerance` is deliberately one idea in one slot: the height of
     * ground above the water that still reads as an ordinary bank. This is the
     * only place it is consumed — the router classifies Normal vs Gorge on
     * `gorge_max_depth`, not on this — so a second copy on the solve side would
     * be a knob that looks live and is not. There was one until 2026-09-06. */
    float valleyRadius = DEF_VALLEY_RADIUS;
    float bankTolerance = DEF_BANK_TOLERANCE;
    if (params != nullptr) {
        if (n_params > 10) bankTolerance = params[10];
        if (n_params > 14) valleyRadius = params[14];
    }
    valleyRadius = std::clamp(valleyRadius, 1.0f, static_cast<float>(T));

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

    Scratch& s = tls;
    s.carved.resize(N);
    s.water.assign(N, -1);

    /* ── 1. Build the river segments in window coordinates ──
     * Clipped to what can reach the window: a segment further than the valley
     * radius from it changes nothing here, and the region that owns it stamps
     * its own ground anyway. */
    s.channel.clear();
    s.valley.clear();
    const bool haveRivers = vertices != nullptr && route_starts != nullptr && n_routes > 0;
    if (haveRivers) {
        const float lo = -valleyRadius;
        const float hi = static_cast<float>(W) + valleyRadius;
        /* Build one Seg from two packed vertices, in window coordinates. */
        const auto makeSeg = [&](const float* a, const float* b, Seg& g) {
            g.ax = a[0] - static_cast<float>(origin_x);
            g.az = a[1] - static_cast<float>(origin_z);
            g.bx = b[0] - static_cast<float>(origin_x);
            g.bz = b[1] - static_cast<float>(origin_z);
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
                 * The pull is skipped below; the channel narrows here. */
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
            /* The decimated copy: the same route walked by DISTANCE rather
             * than by vertex count, so the result is the same polyline
             * whatever `refine_levels` was. The tail is always kept, so the
             * decimated route still ends where the river does. */
            const float target = valleyRadius * VALLEY_SEGMENT_FRACTION;
            int32_t anchor = from;
            float run = 0.0f;
            for (int32_t v = from; v + 1 < to; ++v) {
                run += std::hypot(at(v + 1)[0] - at(v)[0], at(v + 1)[1] - at(v)[1]);
                if (run < target && v + 2 < to) {
                    continue;
                }
                Seg g{};
                if (makeSeg(at(anchor), at(v + 1), g)) {
                    s.valley.push_back(g);
                }
                anchor = v + 1;
                run = 0.0f;
            }
        }
    }

    const int nb = (W + BUCKET - 1) / BUCKET;
    bucketSegments(s.channel, 2.0f, nb, W, s.channelBuckets);
    bucketSegments(s.valley, valleyRadius, nb, W, s.valleyBuckets);

    /* ── 2. Stamp: lakes from the fill, rivers from the plan, then the sea ──
     *
     * Over the center tile and a ONE-COLUMN ring around it, not the whole
     * window. The window exists so this pass has its INPUTS — a valley pull
     * reaches 80 blocks, a route sourced next door crosses the border, and the
     * DEM stencil straddles cells — and all three are read at world
     * coordinates that lie outside the stamped range without being stamped
     * themselves. The ring is there for §3 below, which walls a dry column
     * against its four neighbors' water and therefore reads one column past
     * the tile on each side; nothing reads further.
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
             * nothing was finding depressions. Rivers DO carve: a channel is
             * cut into the ground and, on an ordinary reach, a valley pulled
             * down around it. */
            const int raw = std::clamp<int>(heights3x3[i], 1, world_height - 1);
            int carved = raw;
            int water = -1;

            if (haveDem) {
                const int level = lakeLevelAt(dem, x, z);
                if (level > 0 && carved < level) {
                    water = level;
                }
            }

            /* Rivers touch a few per cent of a tile, so for almost every
             * column these two empty-bucket tests are the entire river pass. */
            const size_t bi = idx2(std::min(x / BUCKET, nb - 1),
                                   std::min(z / BUCKET, nb - 1), nb);
            const float px = static_cast<float>(x) + 0.5f;
            const float pz = static_cast<float>(z) + 0.5f;

            /* Valley pull, on ordinary reaches only (§5.6), from the decimated
             * polyline. Terrain is drawn down toward the water over the valley
             * radius with a smooth falloff, so a reach carves a valley rather
             * than a slot. On a gorge it is deliberately skipped: pulling a
             * 130-block canyon wall down to the river would read as a trench
             * dug through a mountain. */
            for (int32_t si : s.valleyBuckets[bi]) {
                const Seg& g = s.valley[static_cast<size_t>(si)];
                if (g.gorge) {
                    continue;
                }
                float t = 0.0f;
                const float d2 = segDistanceSq(g, px, pz, t);
                if (d2 >= valleyRadius * valleyRadius) {
                    continue;
                }
                const float surfF = g.aSurf + (g.bSurf - g.aSurf) * t;
                const float d = std::sqrt(d2);
                const float f = 1.0f - d / valleyRadius;
                const float falloff = f * f * (3.0f - 2.0f * f);
                const float target = static_cast<float>(raw)
                    + falloff * (surfF + bankTolerance - static_cast<float>(raw));
                carved = std::min(carved, static_cast<int>(std::lround(target)));
            }

            /* The channel itself, from every segment of the refined polyline:
             * this is the detail refinement exists for, and it is cheap because
             * a channel is a few blocks wide rather than eighty.
             *
             * Nearest reach wins the cross-section; heights merge by min and
             * water by max, so two rivers meeting is order-independent and
             * needs no confluence graph (§5.9). */
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
                carved = std::min(carved, surf - std::max(1, static_cast<int>(std::lround(cut))));
                water = std::max(water, surf);
            }

            carved = std::clamp(carved, 1, world_height - 1);
            /* The sea is one case of the per-column water level (bridge rule). */
            if (carved < sea_level) {
                water = std::max(water, sea_level);
            }

            s.carved[i] = static_cast<int16_t>(carved);
            s.water[i] = static_cast<int16_t>(water);
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
        }
    }
    return 0;
}

} // extern "C"
