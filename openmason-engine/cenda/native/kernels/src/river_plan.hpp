/* river_plan.hpp — river routes: spill sources, descent on the filled surface.
 *
 * Layer 2 of the lakes-first hydrology (Dev Working/Lakes-first hydrology
 * plan.md §5). This header knows routing and nothing about blocks or tiles;
 * `basin_plan.hpp` produces the field it descends and `water.cpp` stamps what
 * it returns.
 *
 * ═══ The one property everything else is built out of ═══
 *
 * **Routes descend the FILLED surface, not the raw terrain.**
 *
 * Priority-Flood guarantees every cell has a non-ascending path to an outlet.
 * So on the filled surface a river cannot go uphill by construction rather
 * than by a rejection rule, a route and the lake it meets agree exactly
 * because they are the same field, and the whole never-up / basin-escape /
 * monotone-pass machinery three earlier river designs needed is replaced by a
 * property of the input. Everything below is short because of this.
 *
 * ═══ What is NOT here, and why ═══
 *
 * No flow accumulation, and no discharge. Where a river goes is a local
 * question and a bounded window answers it exactly; how big a river is
 * integrates a continental watershed, and two adjacent regions measuring it
 * disagreed by 95–100 % at every halo from 512 to 2560 blocks (plan §2.2).
 * That is structural and settled. Rivers here are lake-to-lake trunks with no
 * fine dendritic tributary network, and that is the deliberate trade.
 *
 * No terminus lakes. Every lake in the world comes from the fill; a route that
 * runs out of budget simply stops. One source of lakes, not three.
 *
 * ═══ Canonicality: ownership again ═══
 *
 * A route is emitted by the region that owns its SOURCE — the spill cell's
 * region, by integer division of world coordinates. Exactly one region owns
 * any given source, so no two regions ever compute the same route and there is
 * nothing for them to disagree about. That is the same rule lakes use, applied
 * to a different object.
 *
 * It only holds while the walk stays inside the window that computed it: a
 * truncated `filled` would bend a route at the window edge, and the region
 * next door would bend it somewhere else. `Config::stepBudget` therefore
 * derives the step cap from the level's halo rather than taking it on trust,
 * so a route from anywhere in the region reaches the edge of the halo at
 * worst. A consumer that needs every route touching some ground must gather
 * from every region within `maxReach()` of it — rivers cross region borders,
 * and no amount of ownership changes that.
 */
#pragma once

#include "basin_plan.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace cenda::river {

constexpr uint64_t SALT_RIVER = 0x52495645525F4B00ULL;  /* "RIVER_K"  */
constexpr uint64_t SALT_MEANDER = 0x4D45414E44455200ULL; /* "MEANDER" */

struct Config {
    /* ── Sources (§5.2) ── */
    /* Cells. A lake smaller than this drains as seepage, not as a river.
     *
     * Retuned from §8's 24 down to 12 (about 3,100 blocks², a lake ~55 blocks
     * across) in phase 10. At 24 with the plan's keep fraction of 0.45, the one
     * real region the fixture can assemble produced **zero rivers**: it holds
     * 14 lakes its region owns, of which only four are that large, and the
     * hashed gate then rejected all four. */
    int32_t minRiverLakeArea = 12;
    /* Fraction of qualifying lakes that get an outlet river.
     *
     * Gate the BASIN, not the cell. Learned twice already — `mouthKeepFraction`
     * on the Streams grammar and `source_keep_fraction` on the budgeted walker:
     * gating the basin makes lowering density *remove* rivers, while gating
     * cells relocates the survivors instead, so a knob meant to thin the
     * network reshuffles it.
     *
     * Retuned from §8's 0.45 to 0.70 in phase 10, which with the area gate
     * above yields 4 rivers over a 4096-block region on measured terrain — one
     * about every 1.7 km. The pair was chosen so BOTH knobs stay live: at the
     * area gate's original 24 cells only a keep fraction of 0.85+ produced any
     * rivers at all, which makes the density dial a switch. */
    float riverKeepFraction = 0.70f;

    /* ── Stepping (§5.3) ── */
    float stepLen = 16.0f;
    /* Requested cap; the effective one is `stepBudget()`, which will not let a
     * route walk out of the window that computed it. */
    int32_t maxSteps = 256;
    /* Inertia above descent, plus the meander turn, is what produces sinuosity.
     * The bias lives in the HEADING — a wiggle applied to a finished straight
     * line reads as a wiggle, not as a river. */
    float wInertia = 1.0f;
    float wDescent = 0.55f;
    float meanderAmp = 0.35f;          /* radians of signed turn per step */
    float meanderFreq = 1.0f / 300.0f; /* blocks^-1                       */

    /* ── Carve policy (§5.6) and waterfalls (§5.8) ── */
    /* Params slot [10], bank_tolerance — the height of ground above the water
     * that still reads as an ordinary bank — is deliberately NOT here. Routing
     * does not use it: a reach is Normal or Gorge by `gorgeMaxDepth` below, and
     * the bank height only matters at stamp time. It lived here until
     * 2026-09-06, parsed out of the array and never read once, which is worse
     * than absent — a knob that looks honoured and is not. `water.cpp` owns it.
     */
    /* Banks higher than this make the reach a GORGE: confined ground where the
     * valley pull would flatten a canyon into a trough, so phase 9 leaves the
     * walls standing instead. */
    float gorgeMaxDepth = 24.0f;
    /* How far to either side to look for those walls. */
    float gorgeMaxWidth = 96.0f;
    /* A drop of at least this much over one step is a waterfall: a vertical
     * step in the water surface, with nothing carved to ramp it. */
    float waterfallMinDrop = 6.0f;

    /* ── Width and bed depth (§5.5) ──
     *
     * Width grows with the LAKE VOLUME drained and with distance travelled.
     *
     * This is a proxy for discharge and must be read as one. The honest
     * quantity is upstream drainage area, and §2.2 measured that two adjacent
     * regions disagree about it by 95–100 % at every halo out to 2,560 blocks —
     * a trunk river integrates a continental watershed and no bounded window
     * can see it. Lake volume is the opposite: exact from a bounded window, to
     * 0.000 blocks. So this will look right without being right, and that is
     * the deliberate trade. A river here widens because of what it drains and
     * how far it has run, not because of how much land feeds it.
     *
     * Widths are FULL channel widths in blocks; the stamper halves them. */
    float wBase = 4.0f;
    float wLake = 3.0f;
    float wDist = 2.0f;
    float volScale = 50000.0f;  /* blocks³; a middling lake on real terrain */
    float distScale = 1000.0f;  /* blocks                                   */
    float dBase = 1.5f;         /* bed cut below the water surface, blocks  */
    float dGain = 0.8f;
    /* §5.8's plunge basin: a short widening at a waterfall's foot, and no
     * flood fill. Applied to the falling vertex and the one below it. */
    float plungeWiden = 1.6f;

    /* ── Sub-cell refinement (§6) ── */
    /* Midpoint subdivisions applied to the finished polyline. Two levels turns
     * 16-block segments into 4-block ones, which is what stops a reach reading
     * as a chain of straight lines at block scale. */
    int32_t refineLevels = 2;
    /* Sideways displacement as a fraction of the segment being split. */
    float refineAmp = 0.22f;

    /* ── Termination (§5.4) ── */
    float seaLevel = 320.0f;
    /* A route shorter than this is not a river, it is a puddle's overflow lip.
     * Vertices, source included. */
    int32_t minPoints = 4;

    /**
     * Steps this route may take without leaving the window.
     *
     * A source can sit anywhere in the owned region, so the worst case starts
     * at the region's edge with only the halo ahead of it. Two steps of margin
     * keep the gradient stencil, which reaches one step either side, off the
     * window boundary. The plan's default of 256 steps is 4,096 blocks — twice
     * the L1 halo — and taking it literally would let a route run off the edge
     * of its own window and bend differently for the region next door.
     */
    int32_t stepBudget(const basin::Level& lv) const {
        const auto reach = static_cast<int32_t>(
            (static_cast<float>(lv.haloBlocks) - 2.0f * stepLen) / stepLen);
        return std::max(1, std::min(maxSteps, reach));
    }

    /**
     * How far from its source a route can reach, in blocks: what a consumer
     * must search outward to be sure it has every route touching its ground.
     *
     * A DISPLACEMENT bound, not a path length. Refinement pushes midpoints
     * sideways, so the finished polyline is longer than the walk that drew it
     * — measured, about 6 % longer — while reaching no further. The lateral
     * slack is what refinement can add to the distance from the source, which
     * is bounded by the displacement of one step summed over the levels.
     */
    float maxReach(const basin::Level& lv) const {
        const float slack = 2.0f * refineAmp * stepLen * static_cast<float>(refineLevels + 1);
        return static_cast<float>(stepBudget(lv)) * stepLen + slack;
    }
};

/** Why a route stopped. */
enum class End : uint8_t {
    Sea,      /* reached the coast — a mouth                            */
    Lake,     /* ran into a lake; that lake may have its own outlet      */
    Budget,   /* hit the step cap. No terminus lake is dug (§5.4)        */
    Window,   /* would have left the window that owns it                 */
    Stalled,  /* no descending step exists. Should be unreachable inland */
};

/** What kind of ground a reach runs through, and therefore how it is carved. */
enum class Reach : uint8_t {
    /* Open ground: phase 9 pulls terrain down toward the water over the valley
     * radius, so an ordinary reach carves a valley rather than a slot. */
    Normal,
    /* Confined ground, walls above `gorgeMaxDepth`. The valley pull is skipped
     * — a gorge should have walls, and pulling a 130-block canyon wall down to
     * the river would read as a trench dug through a mountain. */
    Gorge,
};

struct Vertex {
    float x = 0.0f, z = 0.0f; /* world blocks, continuous                     */
    float surf = 0.0f;        /* the filled surface at this point             */
    float drop = 0.0f;        /* blocks descended from the previous vertex    */
    /* How far the ground stands above the water within `gorgeMaxWidth` either
     * side of the channel. What phase 9 needs to know to carve banks. */
    float bank = 0.0f;
    Reach reach = Reach::Normal;
    /* Blocks³ of lake water drained by the time the river reaches this point:
     * the source lake, plus every depression the route has crossed since. */
    float drained = 0.0f;
    float width = 0.0f;     /* full channel width, blocks       */
    float bedDepth = 0.0f;  /* cut below the water surface      */
    /* §5.8: a vertical step in the water surface, carved flat at neither end.
     * The containment invariant already permits wet-next-to-wet at different
     * levels and calls it a waterfall, so this costs nothing to allow — while
     * ramping the drop to keep the surface continuous is exactly the
     * "unnecessary terrain to hold the river" the design is avoiding. */
    bool waterfall = false;
};

struct Route {
    /* Canonical identity of the lake this drains, from its spill cell's world
     * coordinates (never a label-order index — that depends on the scan, and
     * the scan depends on the window). */
    uint64_t sourceId = 0;
    int64_t sourceX = 0, sourceZ = 0;
    std::vector<Vertex> points;
    End ending = End::Stalled;
    /* Blocks³ of lake drained so far. §5.5 turns this into width in phase 8;
     * here it is just the source basin's volume. It is a PROXY for discharge,
     * chosen because lake volume is exactly computable from a bounded window
     * (0.000 blocks error) and upstream drainage area is not. It will look
     * right without being right, which is the correct trade. */
    double drained = 0.0;
};

namespace detail {

/** Bilinear sample of a cell plane at a continuous world position, clamped at
 *  the window edge. Cell c's value is taken to sit at its centre. */
inline float sampleBilinear(const basin::Grid& g, const float* plane, float wx, float wz) {
    const float u = (wx - static_cast<float>(g.originX)) / static_cast<float>(g.cellBlocks) - 0.5f;
    const float v = (wz - static_cast<float>(g.originZ)) / static_cast<float>(g.cellBlocks) - 0.5f;
    const float fi = std::floor(u);
    const float fj = std::floor(v);
    const float tu = u - fi;
    const float tv = v - fj;
    const auto i0 = static_cast<int32_t>(fi);
    const auto j0 = static_cast<int32_t>(fj);
    const int32_t n = g.cells;
    const auto at = [&](int32_t i, int32_t j) {
        const int32_t ci = std::clamp(i, 0, n - 1);
        const int32_t cj = std::clamp(j, 0, n - 1);
        return plane[static_cast<size_t>(ci) * static_cast<size_t>(n) + static_cast<size_t>(cj)];
    };
    const float a = at(i0, j0) + (at(i0, j0 + 1) - at(i0, j0)) * tv;
    const float b = at(i0 + 1, j0) + (at(i0 + 1, j0 + 1) - at(i0 + 1, j0)) * tv;
    return a + (b - a) * tu;
}

/** The cell containing a world position, or -1 outside the window. */
inline int32_t cellAt(const basin::Grid& g, float wx, float wz) {
    const int64_t i = basin::floorDiv(static_cast<int64_t>(std::floor(wx)) - g.originX,
                                      g.cellBlocks);
    const int64_t j = basin::floorDiv(static_cast<int64_t>(std::floor(wz)) - g.originZ,
                                      g.cellBlocks);
    if (i < 0 || i >= g.cells || j < 0 || j >= g.cells) {
        return -1;
    }
    return static_cast<int32_t>(i * g.cells + j);
}

inline float smootherstep(float t) {
    return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
}

/**
 * Smooth signed value noise in [-1, 1] on a lattice of `1 / freq` blocks.
 *
 * §5.3 asks for fbm here. This is one octave of quintic-interpolated value
 * noise instead, and the substitution is deliberate: the meander needs a
 * smooth band-limited turn signal at one scale, which is exactly what this is,
 * and building it out of `hashCell` keeps this header dependency-free. That
 * matters more than it sounds — `basin_plan.hpp` and this file can then be
 * unit-tested with no library at all, and the whole routing layer stays
 * testable without a noise backend. Add octaves here if a single scale ever
 * reads as too regular.
 */
inline float meanderNoise(int64_t seed, float x, float z, float freq) {
    const float fx = x * freq;
    const float fz = z * freq;
    const float gx = std::floor(fx);
    const float gz = std::floor(fz);
    const auto ix = static_cast<int64_t>(gx);
    const auto iz = static_cast<int64_t>(gz);
    const float tu = smootherstep(fx - gx);
    const float tv = smootherstep(fz - gz);
    const auto corner = [&](int64_t a, int64_t b) {
        return basin::hash01(basin::hashCell(seed, a, b, SALT_MEANDER)) * 2.0f - 1.0f;
    };
    const float c00 = corner(ix, iz);
    const float c01 = corner(ix, iz + 1);
    const float c10 = corner(ix + 1, iz);
    const float c11 = corner(ix + 1, iz + 1);
    const float a = c00 + (c01 - c00) * tv;
    const float b = c10 + (c11 - c10) * tv;
    return a + (b - a) * tu;
}

struct Vec2 {
    float x = 0.0f, z = 0.0f;
};

inline Vec2 normalize(Vec2 v) {
    const float len = std::sqrt(v.x * v.x + v.z * v.z);
    if (len < 1e-6f) {
        return Vec2{0.0f, 0.0f};
    }
    return Vec2{v.x / len, v.z / len};
}

inline Vec2 rotate(Vec2 v, float phi) {
    const float c = std::cos(phi);
    const float s = std::sin(phi);
    return Vec2{v.x * c - v.z * s, v.x * s + v.z * c};
}

/** Steepest descent on the filled surface: -grad, central difference. */
inline Vec2 descent(const basin::Grid& g, const float* filled, float x, float z, float h) {
    const float dx = sampleBilinear(g, filled, x + h, z) - sampleBilinear(g, filled, x - h, z);
    const float dz = sampleBilinear(g, filled, x, z + h) - sampleBilinear(g, filled, x, z - h);
    return normalize(Vec2{-dx, -dz});
}

} // namespace detail

namespace detail {

/**
 * Fill in each vertex's drop, bank height, reach kind and waterfall flag.
 *
 * A second pass rather than inline work, because every one of these reads the
 * vertex's NEIGHBOURS: the drop needs the previous point and the bank probe
 * needs the local heading, which is only defined once the route exists.
 *
 * ═══ What §5.6 asked for, and what is here instead ═══
 *
 * §5.6 evaluates a three-way carve policy against `rise = raw_ground - surf`
 * at each step: ordinary cut, gorge, or DETOUR around the obstruction (§5.7).
 * That policy came over with the budgeted walker, which routed on raw
 * elevation and genuinely ran into rising ground. It has no trigger here.
 * `filled >= raw` everywhere by construction and a route never climbs, so
 * ground standing above the water surface cannot lie ahead of it. Measured on
 * 158 steps of real routes over `coarse_5a202d6d9dbd`: the largest `rise` at
 * any centreline was **3.1 blocks against a bank tolerance of 8**, and that
 * 3.1 is the gap between a bilinear sample and its own cell rather than
 * terrain. So there is no obstruction to probe the width of and nothing to
 * steer around; §5.1 removed the very thing §5.6 and §5.7 exist to handle.
 *
 * What does fire, and what phase 9 actually needs, is the other half of §5.6:
 * whether this reach runs through open ground or through walls. Over the same
 * 158 steps the ground within three cells stood up to **133 blocks** above the
 * water and was above 24 blocks at 89 of them. That is the number that decides
 * whether pulling terrain toward the river over 80 blocks carves a valley or
 * bulldozes a canyon, so it is what is measured here.
 */
inline void classify(const basin::Grid& g, const Config& cfg, Route& r) {
    const auto n = r.points.size();
    float travelled = 0.0f;
    for (size_t i = 0; i < n; ++i) {
        Vertex& v = r.points[i];
        v.drop = i == 0 ? 0.0f : r.points[i - 1].surf - v.surf;
        v.waterfall = v.drop >= cfg.waterfallMinDrop;
        if (i > 0) {
            travelled += std::hypot(v.x - r.points[i - 1].x, v.z - r.points[i - 1].z);
        }

        /* §5.5. The lake term saturates — a lake ten times larger makes a
         * river about one `w_lake` wider, not ten times wider — which is what
         * keeps one outsized basin from producing an absurd trunk. The
         * distance term is a square root for the same reason. */
        const float lakeTerm = std::log1p(v.drained / cfg.volScale);
        v.width = cfg.wBase + cfg.wLake * lakeTerm
            + cfg.wDist * std::sqrt(travelled / cfg.distScale);
        v.bedDepth = cfg.dBase + cfg.dGain * lakeTerm;

        /* Probe across the channel, perpendicular to where it is going. The
         * cross-section is what the valley pull acts on, so that is the
         * direction the walls matter in. */
        Vec2 along{};
        if (n >= 2) {
            const Vertex& a = r.points[i == 0 ? 0 : i - 1];
            const Vertex& b = r.points[i == 0 ? 1 : i];
            along = normalize(Vec2{b.x - a.x, b.z - a.z});
        }
        if (along.x == 0.0f && along.z == 0.0f) {
            along = Vec2{1.0f, 0.0f};
        }
        const Vec2 perp{-along.z, along.x};

        float bank = 0.0f;
        for (float d = cfg.stepLen; d <= cfg.gorgeMaxWidth; d += cfg.stepLen) {
            for (int side = -1; side <= 1; side += 2) {
                const float px = v.x + perp.x * d * static_cast<float>(side);
                const float pz = v.z + perp.z * d * static_cast<float>(side);
                bank = std::max(bank, sampleBilinear(g, g.raw, px, pz) - v.surf);
            }
        }
        v.bank = bank;
        v.reach = bank > cfg.gorgeMaxDepth ? Reach::Gorge : Reach::Normal;
    }

}


/**
 * Midpoint-displace the polyline so a reach is not a chain of straight
 * segments at block scale (§6).
 *
 * Never-up survives refinement by construction rather than by the clamp §6
 * asks for: a midpoint's surface is the LINEAR INTERPOLATION of its two
 * endpoints, and the displacement moves only x and z. A linear interpolant
 * between two non-increasing values is non-increasing, so no displaced vertex
 * can raise the surface however far it is pushed sideways.
 *
 * A segment that falls is never subdivided. §5.8 wants a waterfall to be one
 * vertical step in the water surface; splitting it would turn a single 8-block
 * fall into a staircase of 2-block ones, which is the ramp the design exists
 * to avoid.
 */
inline void refine(int64_t seed, const Config& cfg, Route& r) {
    for (int32_t level = 0; level < cfg.refineLevels; ++level) {
        if (r.points.size() < 2) {
            return;
        }
        std::vector<Vertex> out;
        out.reserve(r.points.size() * 2);
        for (size_t i = 0; i + 1 < r.points.size(); ++i) {
            const Vertex& a = r.points[i];
            const Vertex& b = r.points[i + 1];
            out.push_back(a);
            if (b.drop >= cfg.waterfallMinDrop) {
                continue; /* leave a fall as the single step it is */
            }
            const float dx = b.x - a.x;
            const float dz = b.z - a.z;
            const float len = std::hypot(dx, dz);
            if (len < 1e-3f) {
                continue;
            }
            /* Seeded by the segment's own world position rather than its index,
             * so refining a route does not depend on where in the route it
             * started — the same reach displaces the same way from any window. */
            const auto key = static_cast<int64_t>(std::lround((a.x + b.x) * 0.5f));
            const auto key2 = static_cast<int64_t>(std::lround((a.z + b.z) * 0.5f));
            const float jitter =
                basin::hash01(basin::hashCell(seed, key, key2, SALT_MEANDER)) * 2.0f - 1.0f;
            const float push = jitter * cfg.refineAmp * len;
            Vertex m{};
            m.x = (a.x + b.x) * 0.5f + (-dz / len) * push;
            m.z = (a.z + b.z) * 0.5f + (dx / len) * push;
            m.surf = (a.surf + b.surf) * 0.5f;
            m.drained = (a.drained + b.drained) * 0.5f;
            m.width = (a.width + b.width) * 0.5f;
            m.bedDepth = (a.bedDepth + b.bedDepth) * 0.5f;
            m.bank = (a.bank + b.bank) * 0.5f;
            m.reach = a.reach;
            out.push_back(m);
        }
        out.push_back(r.points.back());
        r.points.swap(out);
        /* Drops shift when segments are split, and the waterfall flags with
         * them; the split segments each carry half the original descent. */
        for (size_t i = 0; i < r.points.size(); ++i) {
            r.points[i].drop = i == 0 ? 0.0f : r.points[i - 1].surf - r.points[i].surf;
            r.points[i].waterfall = r.points[i].drop >= cfg.waterfallMinDrop;
        }
    }
}

/**
 * §5.8's plunge basin: a short widening at the foot of a waterfall, and no
 * flood fill — the pool a fall digs for itself is a wider channel, not a lake,
 * and every lake in this world comes from the fill.
 *
 * Applied AFTER refinement, and that order matters. Refinement recomputes which
 * vertices fall (a split segment carries half the descent), so widening before
 * it would pool at vertices that are no longer waterfalls and interpolate the
 * widening into midpoints that never were.
 */
inline void applyPlungePools(const Config& cfg, Route& r) {
    const auto n = r.points.size();
    for (size_t i = 0; i < n; ++i) {
        if (!r.points[i].waterfall) {
            continue;
        }
        r.points[i].width *= cfg.plungeWiden;
        if (i + 1 < n) {
            r.points[i + 1].width *= cfg.plungeWiden;
        }
    }
}

} // namespace detail

/**
 * Plan the rivers whose SOURCES this region owns.
 *
 * A source is a lake's spill point — the cell its water overflows — which the
 * fill already computed and which is therefore free (§4.2). Ocean is not a
 * basin and never a source; rivers begin at spill points and nowhere else.
 *
 * `s` must be a solved window covering region `(regionX, regionZ)` plus its
 * halo, already through `basin::applyOwnership`: a basin this level withheld
 * has no trustworthy spill point, so it must not seed a river either.
 */
inline void plan(const basin::Grid& g, const basin::Solution& s, const basin::Level& lv,
                 int64_t regionX, int64_t regionZ, int64_t seed,
                 const Config& cfg, std::vector<Route>& out) {
    out.clear();
    if (s.filled.empty() || g.raw == nullptr) {
        return;
    }
    const int32_t budget = cfg.stepBudget(lv);
    const int64_t ownedX0 = regionX * lv.regionBlocks;
    const int64_t ownedZ0 = regionZ * lv.regionBlocks;
    const int64_t ownedX1 = ownedX0 + lv.regionBlocks;
    const int64_t ownedZ1 = ownedZ0 + lv.regionBlocks;

    /* The stencil reaches one step either side of a sample, so a route must
     * stop before its next step could read outside the window. */
    const float edge = 2.0f * cfg.stepLen;
    const auto windowX0 = static_cast<float>(g.originX) + edge;
    const auto windowZ0 = static_cast<float>(g.originZ) + edge;
    const float windowX1 =
        static_cast<float>(g.originX + static_cast<int64_t>(g.cells) * g.cellBlocks) - edge;
    const float windowZ1 =
        static_cast<float>(g.originZ + static_cast<int64_t>(g.cells) * g.cellBlocks) - edge;

    for (const basin::Basin& b : s.basins) {
        /* Owned by this region, by the spill cell's world coordinates. */
        if (b.spillX < ownedX0 || b.spillX >= ownedX1
                || b.spillZ < ownedZ0 || b.spillZ >= ownedZ1) {
            continue;
        }
        /* A basin this level withheld has no spill point it can vouch for. */
        if (!basin::ownsBasin(b, lv)) {
            continue;
        }
        if (b.area < cfg.minRiverLakeArea) {
            continue;
        }
        if (basin::hash01(basin::hashCell(seed, b.spillX, b.spillZ, SALT_RIVER))
                >= cfg.riverKeepFraction) {
            continue;
        }

        Route r;
        r.sourceId = b.id(seed);
        r.sourceX = b.spillX;
        r.sourceZ = b.spillZ;
        r.drained = b.volume;
        const auto sourceIndex = static_cast<int32_t>(&b - s.basins.data());

        /* Start at the spill cell's centre. */
        float x = static_cast<float>(b.spillX) + static_cast<float>(g.cellBlocks) * 0.5f;
        float z = static_cast<float>(b.spillZ) + static_cast<float>(g.cellBlocks) * 0.5f;
        float surf = detail::sampleBilinear(g, s.filled.data(), x, z);
        /* Accumulated in double and published to each vertex as float: the
                 * width math wants a float, but rounding the running total on
                 * every pond would make the route's own reported total drift
                 * below the source lake it started with. */
        double drained = b.volume;
        Vertex first{};
        first.x = x;
        first.z = z;
        first.surf = surf;
        first.drained = static_cast<float>(drained);
        r.points.push_back(first);

        /* The initial heading is straight downhill, away from the lake. Where
         * the spill sits on a perfect flat the gradient says nothing, so fall
         * back to the direction that leaves the basin: from its bbox centre
         * out through the spill. */
        detail::Vec2 heading = detail::descent(g, s.filled.data(), x, z, cfg.stepLen);
        if (heading.x == 0.0f && heading.z == 0.0f) {
            const float cx = static_cast<float>(g.worldX((b.minI + b.maxI) / 2));
            const float cz = static_cast<float>(g.worldZ((b.minJ + b.maxJ) / 2));
            heading = detail::normalize(detail::Vec2{x - cx, z - cz});
        }
        if (heading.x == 0.0f && heading.z == 0.0f) {
            continue; /* nowhere to go at all */
        }

        /* The budget is a DISTANCE, not a step count. A step is one stepLen,
         * but the discrete fallback moves to a cell centre and a flat crossing
         * jumps the width of a pond, so counting steps would let a route travel
         * further than `maxReach` says — and `maxReach` is what tells a
         * consumer how far away a source can be and still reach its ground. */
        const float reach = cfg.maxReach(lv);
        float travelled = 0.0f;
        int32_t lastCrossed = -1;
        for (int32_t step = 0; step < budget; ++step) {
            if (travelled + cfg.stepLen > reach) {
                r.ending = End::Budget;
                break;
            }
            const detail::Vec2 grad = detail::descent(g, s.filled.data(), x, z, cfg.stepLen);
            const detail::Vec2 desired = detail::normalize(detail::Vec2{
                cfg.wInertia * heading.x + cfg.wDescent * grad.x,
                cfg.wInertia * heading.z + cfg.wDescent * grad.z});
            const float phi = cfg.meanderAmp
                * detail::meanderNoise(seed, x, z, cfg.meanderFreq);

            /* Never up, and never back into its own lake.
             *
             * §5.1 says a route that DESCENDS cannot climb, and that is true of
             * the surface — but the heading is inertia plus a meander turn, and
             * either can point at rising ground. The source basin needs its own
             * guard for a different reason: its surface is flat at exactly the
             * spill level, so a step into it is level rather than rising and
             * the never-up rule would wave it through. A river that turns round
             * and flows back into the lake it came out of is a two-point stub,
             * and on real terrain that was most of them.
             *
             * Three candidate sources are tried in order; see each below. */
            const detail::Vec2 steered = detail::rotate(desired, phi);
            bool moved = false;
            float nx = 0.0f, nz = 0.0f, nsurf = 0.0f;
            detail::Vec2 taken{};

            /* A candidate is admissible if it does not climb, does not go back
             * into the source lake, and does not fall back into the pond just
             * crossed (whose spill is a rim cell, so the pond is right beside
             * the route and one meander turn is enough to re-enter it). */
            const auto admit = [&](detail::Vec2 cand) {
                if (cand.x == 0.0f && cand.z == 0.0f) {
                    return false;
                }
                const float cx2 = x + cfg.stepLen * cand.x;
                const float cz2 = z + cfg.stepLen * cand.z;
                const float csurf = detail::sampleBilinear(g, s.filled.data(), cx2, cz2);
                if (csurf > surf) {
                    return false;
                }
                const int32_t ccell = detail::cellAt(g, cx2, cz2);
                if (ccell >= 0) {
                    if (s.basinAt[static_cast<size_t>(ccell)] == sourceIndex) {
                        return false;
                    }
                    if (lastCrossed >= 0 && s.label[static_cast<size_t>(ccell)] == lastCrossed) {
                        return false;
                    }
                }
                nx = cx2;
                nz = cz2;
                nsurf = csurf;
                taken = cand;
                return true;
            };

            /* 1. The steered step: inertia, descent and the meander turn. This
             *    is the one that produces sinuosity, and almost always the one
             *    that is taken. */
            moved = admit(steered);

            /* 2. A fan around steepest descent, tried nearest-first, for where
             *    the meander turned into rising ground. */
            if (!moved) {
                const detail::Vec2 axis = (grad.x != 0.0f || grad.z != 0.0f) ? grad : heading;
                static constexpr float FAN[] = {0.0f, 0.35f, -0.35f, 0.7f, -0.7f,
                                                1.05f, -1.05f, 1.4f, -1.4f, 1.75f, -1.75f,
                                                2.1f, -2.1f, 2.6f, -2.6f, 3.1f};
                for (float off : FAN) {
                    if (admit(detail::rotate(axis, off))) {
                        moved = true;
                        break;
                    }
                }
            }

            /* 3. The discrete fallback, and the reason a route cannot get
             *    stuck on ground the fill produced. Priority-Flood guarantees
             *    every CELL has a non-ascending neighbour leading to an outlet;
             *    a continuous walk sampling bilinearly at a fixed 16-block
             *    radius has no such guarantee, and on real terrain three routes
             *    in four died in narrow filled valleys the fan stepped straight
             *    over. Falling back to the lowest neighbouring cell hands the
             *    walk back to the field's own guarantee. Ties break by
             *    neighbour order, which is fixed, so this stays canonical. */
            if (!moved) {
                const int32_t here = detail::cellAt(g, x, z);
                if (here >= 0) {
                    const int32_t ci = here / g.cells;
                    const int32_t cj = here % g.cells;
                    float best = surf;
                    int32_t bi = -1, bj = -1;
                    for (int d = 0; d < 8; ++d) {
                        const int32_t ni = ci + basin::detail::DI[d];
                        const int32_t nj = cj + basin::detail::DJ[d];
                        if (ni < 0 || ni >= g.cells || nj < 0 || nj >= g.cells) {
                            continue;
                        }
                        const auto nk = static_cast<size_t>(ni) * static_cast<size_t>(g.cells)
                            + static_cast<size_t>(nj);
                        if (s.basinAt[nk] == sourceIndex) {
                            continue;
                        }
                        if (lastCrossed >= 0 && s.label[nk] == lastCrossed) {
                            continue;
                        }
                        if (s.filled[nk] <= best) {
                            best = s.filled[nk];
                            bi = ni;
                            bj = nj;
                        }
                    }
                    if (bi >= 0) {
                        nx = static_cast<float>(g.worldX(bi))
                            + static_cast<float>(g.cellBlocks) * 0.5f;
                        nz = static_cast<float>(g.worldZ(bj))
                            + static_cast<float>(g.cellBlocks) * 0.5f;
                        nsurf = best;
                        taken = detail::normalize(detail::Vec2{nx - x, nz - z});
                        moved = taken.x != 0.0f || taken.z != 0.0f;
                    }
                }
            }

            if (!moved) {
                r.ending = End::Stalled;
                break;
            }

            if (nx < windowX0 || nx > windowX1 || nz < windowZ0 || nz > windowZ1) {
                r.ending = End::Window;
                break;
            }

            travelled += std::hypot(nx - x, nz - z);
            x = nx;
            z = nz;
            surf = nsurf;
            heading = taken;
            {
                Vertex v{};
                v.x = x;
                v.z = z;
                v.surf = surf;
                v.drained = static_cast<float>(drained);
                r.points.push_back(v);
            }

            if (surf <= cfg.seaLevel) {
                r.ending = End::Sea; /* a mouth */
                break;
            }

            const int32_t cell = detail::cellAt(g, x, z);
            if (cell < 0) {
                r.ending = End::Window;
                break;
            }
            if (s.basinAt[static_cast<size_t>(cell)] >= 0) {
                /* Into a lake. The route ends at the shore; that lake gets its
                 * own outlet river if it qualifies, which is how a chain of
                 * lakes connects without a confluence graph. */
                r.ending = End::Lake;
                break;
            }
            const int32_t comp = s.label[static_cast<size_t>(cell)];
            if (comp >= 0 && comp < static_cast<int32_t>(s.componentSpill.size())) {
                /* A depression the fill raised but that is too small to be a
                 * lake. Its surface is flat, so there is no gradient to follow
                 * across it — but the fill already knows where it drains, so
                 * the route crosses to that spill in one straight reach, which
                 * is what the water does. Wandering the flat looking for the
                 * exit is the alternative, and it is both slower and not
                 * canonical. */
                const int32_t sp = s.componentSpill[static_cast<size_t>(comp)];
                if (sp < 0) {
                    r.ending = End::Stalled;
                    break;
                }
                lastCrossed = comp;
                const float sx = static_cast<float>(g.worldX(sp / g.cells))
                    + static_cast<float>(g.cellBlocks) * 0.5f;
                const float sz = static_cast<float>(g.worldZ(sp % g.cells))
                    + static_cast<float>(g.cellBlocks) * 0.5f;
                const detail::Vec2 across = detail::normalize(detail::Vec2{sx - x, sz - z});
                if (across.x == 0.0f && across.z == 0.0f) {
                    r.ending = End::Stalled;
                    break;
                }
                if (sx < windowX0 || sx > windowX1 || sz < windowZ0 || sz > windowZ1) {
                    r.ending = End::Window;
                    break;
                }
                travelled += std::hypot(sx - x, sz - z);
                heading = across;
                x = sx;
                z = sz;
                surf = detail::sampleBilinear(g, s.filled.data(), x, z);
                /* §5.5's "drained += basin.volume each time the route passes
                 * through a basin". With termination at every lake this is the
                 * only place it can happen: the ponds a river threads on its
                 * way down, each too small to stop it. */
                drained += s.componentVolume[static_cast<size_t>(comp)];
                Vertex v{};
                v.x = x;
                v.z = z;
                v.surf = surf;
                v.drained = static_cast<float>(drained);
                r.points.push_back(v);
            }

            if (step + 1 == budget) {
                r.ending = End::Budget;
            }
        }

        r.drained = drained;
        if (static_cast<int32_t>(r.points.size()) >= cfg.minPoints) {
            detail::classify(g, cfg, r);
            detail::refine(seed, cfg, r);
            detail::applyPlungePools(cfg, r);
            out.push_back(std::move(r));
        }
    }
}

} // namespace cenda::river
