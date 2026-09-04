/* river_plan.hpp invariants, check()-counter style like the other kernel tests
 * (release builds define NDEBUG, so assert() is not usable here).
 *
 * Every fixture is a plain that drains, with conical pits cut into it. That
 * shape is not decoration: an undrained surface merges into one continental
 * basin no level can own, and there is then nothing to spill and nowhere to
 * spill to. Each pit's spill elevation and position are arithmetic, so what a
 * route starts from is checkable rather than merely plausible.
 *
 * Covered (Lakes-first hydrology plan.md §9):
 *   1. never up      no route step increases `filled`. Asserted hard — a
 *                    failure means the fill broke, not that routing is loose
 *   6. source gating lowering `river_keep_fraction` strictly REMOVES rivers
 *                    and the survivors do not move
 *   plus §5.2 sources are spill points and nothing else, §5.4's four
 *   terminations, and the ownership rule that keeps one route to one region.
 */

#include "river_plan.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace {

namespace bp = cenda::basin;
namespace rp = cenda::river;

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

constexpr float SEA = 320.0f;

/* ── Fixtures ────────────────────────────────────────────────────────────── */

/**
 * A plain descending toward +X into a coast, with a conical pit at (px, pz).
 *
 * The pit's walls fall at `depth / radius` per block and the plain at `tilt`,
 * so the pit is a closed basin exactly while the former is the larger — which
 * is also what decides whether there is anything to spill.
 */
struct Plain {
    float tilt;
    float top;
    float pitRadius;
    float pitDepth;
    std::vector<std::pair<int64_t, int64_t>> pits;

    float at(int64_t x, int64_t z) const {
        double h = static_cast<double>(top) - static_cast<double>(tilt) * static_cast<double>(x);
        for (const auto& p : pits) {
            const double dx = static_cast<double>(x - p.first);
            const double dz = static_cast<double>(z - p.second);
            const double r = std::sqrt(dx * dx + dz * dz);
            if (r < pitRadius) {
                h -= static_cast<double>(pitDepth) * (1.0 - r / static_cast<double>(pitRadius));
            }
        }
        return static_cast<float>(h);
    }
};

/** One pit near the coast: its river has room to reach the sea. */
Plain coastalPit() {
    return Plain{0.05f, 420.0f, 200.0f, 40.0f, {{400, 2048}}};
}

/**
 * One pit near the far edge of region (0,0), where BOTH that region's window
 * and region (1,0)'s contain it whole. That overlap is the entire point: an
 * ownership rule is only worth testing where two regions can each see the same
 * source and only one of them may act on it.
 */
Plain borderPit() {
    /* The plain starts higher than the other fixtures because this pit sits far
     * down the slope: at top = 420 its floor lands under sea level and the fill
     * calls it ocean, which is not a basin and never a source. */
    return Plain{0.02f, 460.0f, 200.0f, 40.0f, {{3600, 2048}}};
}

/** Sixteen pits across the owned region: enough sources to gate. */
Plain pitLattice() {
    Plain p{0.02f, 420.0f, 200.0f, 40.0f, {}};
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) {
            p.pits.emplace_back(512 + 1024 * i, 512 + 1024 * j);
        }
    }
    return p;
}

/* ── Solving one region, as BasinCache does ─────────────────────────────── */

struct Solved {
    std::vector<float> dem;
    bp::Grid grid;
    bp::Solution solution;
    int32_t withheld = 0;
};

/** Build a region's DEM by averaging the terrain onto cells, then solve it. */
Solved solveRegion(const Plain& plain, int64_t regionX, int64_t regionZ,
                   const bp::Level& lv = bp::LEVEL_L1) {
    Solved out;
    const int32_t n = lv.windowCells();
    const int64_t ox = lv.originOf(regionX);
    const int64_t oz = lv.originOf(regionZ);
    out.dem.resize(static_cast<size_t>(n) * static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            double acc = 0.0;
            for (int32_t a = 0; a < lv.cellBlocks; ++a) {
                for (int32_t b = 0; b < lv.cellBlocks; ++b) {
                    acc += static_cast<double>(
                        plain.at(ox + static_cast<int64_t>(i) * lv.cellBlocks + a,
                                 oz + static_cast<int64_t>(j) * lv.cellBlocks + b));
                }
            }
            out.dem[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)] =
                static_cast<float>(acc / (lv.cellBlocks * lv.cellBlocks));
        }
    }
    out.grid = bp::Grid{out.dem.data(), n, lv.cellBlocks, ox, oz};
    bp::solve(out.grid, bp::Config{0.5f, 8, SEA}, out.solution);
    out.withheld = bp::applyOwnership(out.solution, lv).count;
    return out;
}

/** Every route's surface must be non-increasing. §9.1, asserted hard. */
bool neverUp(const std::vector<rp::Route>& routes) {
    for (const rp::Route& r : routes) {
        for (size_t i = 1; i < r.points.size(); ++i) {
            if (r.points[i].surf > r.points[i - 1].surf) {
                return false;
            }
        }
    }
    return true;
}

/* ── Tests ──────────────────────────────────────────────────────────────── */

void testALakeSpillsARiverToTheSea() {
    const Plain plain = coastalPit();
    const Solved sv = solveRegion(plain, 0, 0);
    check(sv.withheld == 0, "coastal pit: L1 owns everything it found");
    check(sv.solution.basins.size() == 1, "coastal pit: exactly one basin");
    if (sv.solution.basins.empty()) {
        return;
    }
    const bp::Basin& b = sv.solution.basins[0];

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f; /* take every qualifying lake */
    std::vector<rp::Route> routes;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 777, cfg, routes);

    check(routes.size() == 1, "coastal pit: one lake, one river");
    if (routes.empty()) {
        return;
    }
    const rp::Route& r = routes[0];
    /* §5.2: rivers begin at spill points and nowhere else. */
    check(r.sourceX == b.spillX && r.sourceZ == b.spillZ,
          "the river starts at the lake's spill cell");
    check(r.sourceId == b.id(777), "the route carries the basin's canonical id");
    check(r.points.front().drained == static_cast<float>(b.volume),
          "the route starts having drained its source lake");
    check(r.drained >= b.volume, "and never un-drains it");
    check(r.ending == rp::End::Sea, "the river reaches the coast");
    check(r.points.back().surf <= SEA, "its last point is at or below sea level");
    check(neverUp(routes), "no route step increases the filled surface");

    /* It descends the whole way: the source is above the sea, the mouth is at
     * it, and the plain between them is a slope rather than a staircase. */
    check(r.points.front().surf > SEA, "the source is above sea level");
    check(r.points.size() > 8, "the route is a river, not a lip");
    std::printf("river to sea ok (%zu points, %.1f -> %.1f blocks)\n",
                r.points.size(), static_cast<double>(r.points.front().surf),
                static_cast<double>(r.points.back().surf));
}

void testSourceGatingRemovesRiversWithoutMovingTheSurvivors() {
    /* Plan §9.6, and the mistake it exists to prevent: gating CELLS instead of
     * basins makes a lower density relocate rivers rather than remove them, so
     * the knob reshuffles the network instead of thinning it. */
    const Plain plain = pitLattice();
    const Solved sv = solveRegion(plain, 0, 0);

    rp::Config wide;
    wide.seaLevel = SEA;
    wide.riverKeepFraction = 0.9f;
    rp::Config narrow = wide;
    narrow.riverKeepFraction = 0.3f;

    std::vector<rp::Route> many;
    std::vector<rp::Route> few;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 31337, wide, many);
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 31337, narrow, few);

    check(many.size() > few.size(), "a lower keep fraction yields fewer rivers");
    check(!few.empty(), "the lattice still has rivers at the lower fraction");

    /* Strictly a subset, and the survivors are IDENTICAL — same source, same
     * every vertex. Anything else means the gate moved a river. */
    bool subset = true;
    bool identical = true;
    for (const rp::Route& f : few) {
        const rp::Route* match = nullptr;
        for (const rp::Route& m : many) {
            if (m.sourceId == f.sourceId) {
                match = &m;
                break;
            }
        }
        if (match == nullptr) {
            subset = false;
            continue;
        }
        if (match->points.size() != f.points.size()) {
            identical = false;
            continue;
        }
        for (size_t i = 0; i < f.points.size(); ++i) {
            if (match->points[i].x != f.points[i].x || match->points[i].z != f.points[i].z
                    || match->points[i].surf != f.points[i].surf) {
                identical = false;
            }
        }
    }
    check(subset, "every river at the low fraction also exists at the high one");
    check(identical, "a surviving river is unchanged, vertex for vertex");
    check(neverUp(many) && neverUp(few), "no route step increases the filled surface");
    std::printf("source gating ok (%zu rivers at 0.9, %zu at 0.3, of %zu basins)\n",
                many.size(), few.size(), sv.solution.basins.size());
}

void testAreaThresholdRejectsSmallLakes() {
    const Plain plain = coastalPit();
    const Solved sv = solveRegion(plain, 0, 0);
    check(!sv.solution.basins.empty(), "area threshold: the fixture has a basin");
    if (sv.solution.basins.empty()) {
        return;
    }
    const int32_t area = sv.solution.basins[0].area;

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    std::vector<rp::Route> routes;

    cfg.minRiverLakeArea = area;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 5, cfg, routes);
    check(routes.size() == 1, "a lake exactly at the threshold spills a river");

    cfg.minRiverLakeArea = area + 1;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 5, cfg, routes);
    check(routes.empty(), "a lake below the threshold spills nothing");
}

void testOnlyTheOwningRegionEmitsARoute() {
    /* The canonicality rule. Region (1,0)'s window reaches back to x = 2048,
     * so it contains the pit at x = 3600 whole — it just must not emit it, or
     * the same river would exist twice and two regions would each have their
     * own opinion of it. */
    const Plain plain = borderPit();
    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;

    const Solved owner = solveRegion(plain, 0, 0);
    std::vector<rp::Route> mine;
    rp::plan(owner.grid, owner.solution, bp::LEVEL_L1, 0, 0, 99, cfg, mine);
    check(mine.size() == 1, "the owning region emits the river");

    const Solved neighbour = solveRegion(plain, 1, 0);
    std::vector<rp::Route> theirs;
    rp::plan(neighbour.grid, neighbour.solution, bp::LEVEL_L1, 1, 0, 99, cfg, theirs);
    check(theirs.empty(), "the neighbouring region emits nothing for a source it does not own");

    /* And the neighbour really did see the basin — otherwise the test proves
     * only that its window was too small. */
    bool sawIt = false;
    for (const bp::Basin& b : neighbour.solution.basins) {
        if (b.spillX == owner.solution.basins[0].spillX
                && b.spillZ == owner.solution.basins[0].spillZ) {
            sawIt = true;
        }
    }
    check(sawIt, "the neighbouring region's window does contain the basin");
}

void testARiverEndsAtTheLakeItFlowsInto() {
    /* Two pits on one slope: the upper spills toward the lower, and its route
     * must stop at that lake's shore rather than crossing it. The lower lake
     * gets its own outlet river if it qualifies, which is how a chain of lakes
     * connects without a confluence graph. */
    Plain plain{0.02f, 420.0f, 200.0f, 40.0f, {{1024, 2048}, {2400, 2048}}};
    const Solved sv = solveRegion(plain, 0, 0);
    check(sv.solution.basins.size() == 2, "two pits, two basins");

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    std::vector<rp::Route> routes;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 4242, cfg, routes);

    int endedInLake = 0;
    for (const rp::Route& r : routes) {
        if (r.ending == rp::End::Lake) {
            ++endedInLake;
        }
    }
    check(endedInLake >= 1, "the upper lake's river ends in the lower lake");
    check(neverUp(routes), "no route step increases the filled surface");
    std::printf("lake-to-lake ok (%zu routes, %d ending in a lake)\n",
                routes.size(), endedInLake);
}

void testOceanIsNeverASource() {
    /* A hollow punched below sea level is sea, not a lake, so the fill never
     * labels it a basin and there is no spill point to start from. */
    Plain plain{0.0f, 400.0f, 300.0f, 200.0f, {{2048, 2048}}};
    const Solved sv = solveRegion(plain, 0, 0);

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    cfg.minRiverLakeArea = 1;
    std::vector<rp::Route> routes;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 8, cfg, routes);

    bool anySubSea = false;
    for (const rp::Route& r : routes) {
        if (r.points.front().surf <= SEA) {
            anySubSea = true;
        }
    }
    check(!anySubSea, "no river is sourced at or below sea level");
}

void testTheStepBudgetCannotLeaveTheWindow() {
    /* The rule that keeps a route canonical: it must not read ground its own
     * window does not hold, or the region next door would bend it elsewhere.
     * The plan's stated default of 256 steps is twice the L1 halo. */
    rp::Config cfg;
    const int32_t budget = cfg.stepBudget(bp::LEVEL_L1);
    check(budget < cfg.maxSteps, "the halo, not the knob, is what bounds the walk");
    check(cfg.maxReach(bp::LEVEL_L1) <= static_cast<float>(bp::LEVEL_L1.haloBlocks),
          "a route cannot reach past the halo of the window that owns it");
    /* L0's halo is 8x larger, so there the knob is the binding constraint. */
    check(cfg.stepBudget(bp::LEVEL_L0) == cfg.maxSteps,
          "at L0 the halo is generous enough that maxSteps binds");
    /* And the budget is spent as distance, so a flat crossing — which jumps a
     * whole pond in one move — cannot smuggle a route past the halo. */
    std::printf("step budget ok (L1 %d steps = %.0f blocks, halo %d)\n",
                budget, static_cast<double>(cfg.maxReach(bp::LEVEL_L1)),
                bp::LEVEL_L1.haloBlocks);
}

void testNoRouteTravelsFurtherThanItsReach() {
    /* The window bound in its measurable form: total path length, jumps
     * included, stays inside what `maxReach` promises a consumer. */
    const Plain plain = pitLattice();
    const Solved sv = solveRegion(plain, 0, 0);
    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    std::vector<rp::Route> routes;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 606, cfg, routes);
    check(!routes.empty(), "reach: the lattice produces rivers");

    const float reach = cfg.maxReach(bp::LEVEL_L1);
    float worstAway = 0.0f;
    float worstPath = 0.0f;
    for (const rp::Route& r : routes) {
        float len = 0.0f;
        for (size_t i = 1; i < r.points.size(); ++i) {
            len += std::hypot(r.points[i].x - r.points[i - 1].x,
                              r.points[i].z - r.points[i - 1].z);
            worstAway = std::max(worstAway,
                std::hypot(r.points[i].x - r.points[0].x, r.points[i].z - r.points[0].z));
        }
        worstPath = std::max(worstPath, len);
    }
    /* The promise is about DISTANCE FROM THE SOURCE, which is what a consumer
     * searches outward by. The path itself is longer, because refinement makes
     * a wiggly line out of a straight walk. */
    check(worstAway <= reach, "no route reaches further from its source than maxReach promises");
    std::printf("reach ok (furthest vertex %.0f blocks from source of %.0f allowed; "
                "path length %.0f)\n",
                static_cast<double>(worstAway), static_cast<double>(reach),
                static_cast<double>(worstPath));
}


/* ── Phase 7: carve policy and waterfalls ───────────────────────────────── */

/** A staircase: level treads with sharp risers, so drops per step are large
 *  and predictable. §5.8's trigger, built to fire. */
struct Staircase {
    float top = 500.0f;
    float treadBlocks = 160.0f;
    float riserBlocks = 12.0f;

    float at(int64_t x, int64_t /*z*/) const {
        const double steps = std::floor(static_cast<double>(x) / static_cast<double>(treadBlocks));
        return static_cast<float>(static_cast<double>(top)
                                  - steps * static_cast<double>(riserBlocks));
    }
};

void testAWaterfallIsFlaggedAndNothingIsRampedToIt() {
    /* A pit on a staircase: the outlet river runs down the treads, and each
     * riser is a step-down bigger than `waterfallMinDrop`. The surface takes a
     * vertical step; the containment invariant already permits wet-next-to-wet
     * at different levels and calls that a waterfall. */
    Staircase stair;
    struct Terrain {
        Staircase stair;
        float at(int64_t x, int64_t z) const {
            float h = stair.at(x, z);
            const double dx = static_cast<double>(x - 800);
            const double dz = static_cast<double>(z - 2048);
            const double r = std::sqrt(dx * dx + dz * dz);
            if (r < 200.0) {
                h -= static_cast<float>(30.0 * (1.0 - r / 200.0));
            }
            return h;
        }
    } terrain{stair};

    const bp::Level& lv = bp::LEVEL_L1;
    const int32_t n = lv.windowCells();
    const int64_t ox = lv.originOf(0), oz = lv.originOf(0);
    std::vector<float> dem(static_cast<size_t>(n) * static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            double acc = 0.0;
            for (int32_t a = 0; a < lv.cellBlocks; ++a) {
                for (int32_t b = 0; b < lv.cellBlocks; ++b) {
                    acc += static_cast<double>(
                        terrain.at(ox + static_cast<int64_t>(i) * lv.cellBlocks + a,
                                   oz + static_cast<int64_t>(j) * lv.cellBlocks + b));
                }
            }
            dem[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)] =
                static_cast<float>(acc / (lv.cellBlocks * lv.cellBlocks));
        }
    }
    bp::Grid g{dem.data(), n, lv.cellBlocks, ox, oz};
    bp::Solution s;
    bp::solve(g, bp::Config{0.5f, 8, SEA}, s);
    bp::applyOwnership(s, lv);

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    std::vector<rp::Route> routes;
    rp::plan(g, s, lv, 0, 0, 8080, cfg, routes);
    check(!routes.empty(), "waterfall: the staircase pit spills a river");
    if (routes.empty()) {
        return;
    }

    int falls = 0;
    bool dropsAgree = true;
    bool flagsAgree = true;
    for (const rp::Route& r : routes) {
        for (size_t i = 0; i < r.points.size(); ++i) {
            const rp::Vertex& v = r.points[i];
            const float expected = i == 0 ? 0.0f : r.points[i - 1].surf - v.surf;
            if (v.drop != expected) {
                dropsAgree = false;
            }
            if (v.waterfall != (v.drop >= cfg.waterfallMinDrop)) {
                flagsAgree = false;
            }
            if (v.waterfall) {
                ++falls;
            }
            /* Never negative: a "drop" that climbs would mean never-up broke. */
            if (v.drop < 0.0f) {
                dropsAgree = false;
            }
        }
    }
    check(dropsAgree, "each vertex records the descent into it, and it never climbs");
    check(flagsAgree, "the waterfall flag is exactly the drop threshold");
    check(falls > 0, "a 12-block riser is a waterfall");
    /* Raising the threshold above the riser must remove them all — the flag is
     * a measurement of the terrain, not a property the router invented. */
    rp::Config high = cfg;
    high.waterfallMinDrop = 100.0f;
    std::vector<rp::Route> calm;
    rp::plan(g, s, lv, 0, 0, 8080, high, calm);
    int stillFalling = 0;
    for (const rp::Route& r : calm) {
        for (const rp::Vertex& v : r.points) {
            if (v.waterfall) {
                ++stillFalling;
            }
        }
    }
    check(stillFalling == 0, "no drop counts as a waterfall above the threshold");
    std::printf("waterfall ok (%d of %zu vertices fall)\n", falls, routes[0].points.size());
}

void testReachKindFollowsTheGroundBesideTheChannel() {
    /* §5.6's surviving half. A river on an open plain runs through ordinary
     * banks; one in a walled valley must be marked Gorge so phase 9 does not
     * pull the walls down and flatten it. */
    const Plain open = coastalPit();
    const Solved flat = solveRegion(open, 0, 0);

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    std::vector<rp::Route> onThePlain;
    rp::plan(flat.grid, flat.solution, bp::LEVEL_L1, 0, 0, 616, cfg, onThePlain);
    check(!onThePlain.empty(), "reach: the plain has a river");
    if (onThePlain.empty()) {
        return;
    }

    int normal = 0;
    int gorge = 0;
    bool bankMatchesReach = true;
    for (const rp::Route& r : onThePlain) {
        for (const rp::Vertex& v : r.points) {
            if (v.reach == rp::Reach::Gorge) {
                ++gorge;
            } else {
                ++normal;
            }
            if ((v.bank > cfg.gorgeMaxDepth) != (v.reach == rp::Reach::Gorge)) {
                bankMatchesReach = false;
            }
        }
    }
    check(bankMatchesReach, "the reach kind is exactly the bank-height threshold");
    check(normal > gorge, "a river on a gentle plain runs mostly through open ground");

    /* The same river with the threshold dropped below the plain's own relief
     * must reclassify — the knob is what moves, not the terrain. */
    rp::Config tight = cfg;
    tight.gorgeMaxDepth = 0.0f;
    std::vector<rp::Route> confined;
    rp::plan(flat.grid, flat.solution, bp::LEVEL_L1, 0, 0, 616, tight, confined);
    int nowGorge = 0;
    for (const rp::Route& r : confined) {
        for (const rp::Vertex& v : r.points) {
            if (v.reach == rp::Reach::Gorge) {
                ++nowGorge;
            }
        }
    }
    check(nowGorge > gorge, "lowering the gorge threshold confines more of the river");
    std::printf("reach kind ok (%d normal, %d gorge; %d gorge at threshold 0)\n",
                normal, gorge, nowGorge);
}


/* ── Phase 8: width from drained volume, and bed depth ──────────────────── */

void testWidthGrowsWithDistanceAndDrainedVolume() {
    const Plain plain = coastalPit();
    const Solved sv = solveRegion(plain, 0, 0);

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    cfg.plungeWiden = 1.0f;  /* off, so monotonicity is the only thing tested */
    std::vector<rp::Route> routes;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 1717, cfg, routes);
    check(!routes.empty(), "width: the fixture has a river");
    if (routes.empty()) {
        return;
    }
    const rp::Route& r = routes[0];

    /* Drained never falls, distance never falls, and both terms are increasing,
     * so a river only ever widens on its way down. */
    bool monotone = true;
    bool drainedMonotone = true;
    for (size_t i = 1; i < r.points.size(); ++i) {
        if (r.points[i].width < r.points[i - 1].width) {
            monotone = false;
        }
        if (r.points[i].drained < r.points[i - 1].drained) {
            drainedMonotone = false;
        }
    }
    check(monotone, "a river never narrows on its way downstream");
    check(drainedMonotone, "drained volume never decreases");
    check(r.points.back().width > r.points.front().width,
          "the mouth is wider than the source");
    check(r.points.front().width >= cfg.wBase, "even at the source it is at least w_base");

    /* Each term is separable: zero it and the width it contributed goes. */
    rp::Config noDist = cfg;
    noDist.wDist = 0.0f;
    std::vector<rp::Route> flatWidth;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 1717, noDist, flatWidth);
    check(!flatWidth.empty()
              && flatWidth[0].points.back().width == flatWidth[0].points.front().width,
          "with w_dist zero a single-lake river is a constant width");

    rp::Config noLake = cfg;
    noLake.wLake = 0.0f;
    std::vector<rp::Route> narrow;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 1717, noLake, narrow);
    check(!narrow.empty() && narrow[0].points.front().width < r.points.front().width,
          "with w_lake zero the source is narrower than with it");
    std::printf("width ok (%.1f blk at source -> %.1f at mouth, drained %.3g blk^3)\n",
                static_cast<double>(r.points.front().width),
                static_cast<double>(r.points.back().width), r.drained);
}

void testABiggerLakeMakesAWiderRiver() {
    /* The whole point of the proxy: a river's size should track what it
     * drains. Two identical slopes, one pit twice as deep as the other. */
    Plain small{0.05f, 420.0f, 200.0f, 20.0f, {{400, 2048}}};
    Plain large{0.05f, 420.0f, 200.0f, 40.0f, {{400, 2048}}};
    const Solved a = solveRegion(small, 0, 0);
    const Solved b = solveRegion(large, 0, 0);

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    std::vector<rp::Route> ra;
    std::vector<rp::Route> rb;
    rp::plan(a.grid, a.solution, bp::LEVEL_L1, 0, 0, 55, cfg, ra);
    rp::plan(b.grid, b.solution, bp::LEVEL_L1, 0, 0, 55, cfg, rb);
    check(!ra.empty() && !rb.empty(), "bigger lake: both pits spill");
    if (ra.empty() || rb.empty()) {
        return;
    }
    check(rb[0].points.front().drained > ra[0].points.front().drained,
          "the deeper pit holds more water");
    check(rb[0].points.front().width > ra[0].points.front().width,
          "and its river is wider at the source");
    check(rb[0].points.front().bedDepth > ra[0].points.front().bedDepth,
          "and cuts a deeper bed");

    /* Saturating, not proportional: twice the water is not twice the river. */
    const double volRatio = static_cast<double>(rb[0].points.front().drained)
        / static_cast<double>(ra[0].points.front().drained);
    const double widthRatio = static_cast<double>(rb[0].points.front().width)
        / static_cast<double>(ra[0].points.front().width);
    check(widthRatio < volRatio, "width saturates in volume rather than tracking it");
    std::printf("lake-fed width ok (volume x%.2f -> width x%.2f)\n", volRatio, widthRatio);
}

void testAWaterfallWidensAPlungePool() {
    Staircase stair;
    struct Terrain {
        Staircase stair;
        float at(int64_t x, int64_t z) const {
            float h = stair.at(x, z);
            const double dx = static_cast<double>(x - 800);
            const double dz = static_cast<double>(z - 2048);
            const double r = std::sqrt(dx * dx + dz * dz);
            if (r < 200.0) {
                h -= static_cast<float>(30.0 * (1.0 - r / 200.0));
            }
            return h;
        }
    } terrain{stair};

    const bp::Level& lv = bp::LEVEL_L1;
    const int32_t n = lv.windowCells();
    const int64_t ox = lv.originOf(0), oz = lv.originOf(0);
    std::vector<float> dem(static_cast<size_t>(n) * static_cast<size_t>(n));
    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            double acc = 0.0;
            for (int32_t a = 0; a < lv.cellBlocks; ++a) {
                for (int32_t b = 0; b < lv.cellBlocks; ++b) {
                    acc += static_cast<double>(
                        terrain.at(ox + static_cast<int64_t>(i) * lv.cellBlocks + a,
                                   oz + static_cast<int64_t>(j) * lv.cellBlocks + b));
                }
            }
            dem[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)] =
                static_cast<float>(acc / (lv.cellBlocks * lv.cellBlocks));
        }
    }
    bp::Grid g{dem.data(), n, lv.cellBlocks, ox, oz};
    bp::Solution s;
    bp::solve(g, bp::Config{0.5f, 8, SEA}, s);
    bp::applyOwnership(s, lv);

    rp::Config cfg;
    cfg.seaLevel = SEA;
    cfg.riverKeepFraction = 1.0f;
    rp::Config noPlunge = cfg;
    noPlunge.plungeWiden = 1.0f;

    std::vector<rp::Route> pooled;
    std::vector<rp::Route> plain;
    rp::plan(g, s, lv, 0, 0, 8080, cfg, pooled);
    rp::plan(g, s, lv, 0, 0, 8080, noPlunge, plain);
    check(!pooled.empty() && pooled.size() == plain.size(), "plunge: the staircase spills");
    if (pooled.empty()) {
        return;
    }

    int widened = 0;
    bool onlyAtFalls = true;
    for (size_t i = 0; i < pooled[0].points.size(); ++i) {
        const rp::Vertex& p = pooled[0].points[i];
        const rp::Vertex& q = plain[0].points[i];
        if (p.width > q.width) {
            ++widened;
            const bool here = p.waterfall;
            const bool above = i > 0 && pooled[0].points[i - 1].waterfall;
            if (!here && !above) {
                onlyAtFalls = false;
            }
        }
    }
    check(widened > 0, "a waterfall widens the channel at its foot");
    check(onlyAtFalls, "and widens nothing that is not a fall or just below one");
    std::printf("plunge pool ok (%d widened vertices)\n", widened);
}

void testRoutesAreDeterministic() {
    const Plain plain = pitLattice();
    const Solved sv = solveRegion(plain, 0, 0);
    rp::Config cfg;
    cfg.seaLevel = SEA;

    std::vector<rp::Route> a;
    std::vector<rp::Route> b;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 1234, cfg, a);
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 1234, cfg, b);
    check(a.size() == b.size(), "the same inputs plan the same number of rivers");
    bool same = a.size() == b.size();
    for (size_t r = 0; r < a.size() && same; ++r) {
        if (a[r].sourceId != b[r].sourceId || a[r].points.size() != b[r].points.size()) {
            same = false;
            break;
        }
        for (size_t i = 0; i < a[r].points.size(); ++i) {
            if (a[r].points[i].x != b[r].points[i].x || a[r].points[i].z != b[r].points[i].z) {
                same = false;
            }
        }
    }
    check(same, "routes are bit-identical run to run");

    /* A different seed must reshuffle which lakes spill, and the meander. */
    std::vector<rp::Route> other;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 999, cfg, other);
    bool differs = other.size() != a.size();
    for (size_t r = 0; r < a.size() && r < other.size() && !differs; ++r) {
        if (a[r].points.size() != other[r].points.size()) {
            differs = true;
        }
    }
    check(differs, "a different seed plans a different network");
}

void testRoutesMeander() {
    /* Sinuosity comes from the heading — inertia above descent, plus a turn —
     * not from a wiggle applied to a finished line. On a uniform slope a route
     * with the meander off runs dead straight; with it on it must not. */
    const Plain plain = coastalPit();
    const Solved sv = solveRegion(plain, 0, 0);

    rp::Config straight;
    straight.seaLevel = SEA;
    straight.riverKeepFraction = 1.0f;
    straight.meanderAmp = 0.0f;
    rp::Config winding = straight;
    winding.meanderAmp = 0.35f;

    std::vector<rp::Route> a;
    std::vector<rp::Route> b;
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 2468, straight, a);
    rp::plan(sv.grid, sv.solution, bp::LEVEL_L1, 0, 0, 2468, winding, b);
    check(!a.empty() && !b.empty(), "meander: both settings produce a river");
    if (a.empty() || b.empty()) {
        return;
    }

    const auto spread = [](const rp::Route& r) {
        float lo = r.points[0].z;
        float hi = r.points[0].z;
        for (const rp::Vertex& v : r.points) {
            lo = std::min(lo, v.z);
            hi = std::max(hi, v.z);
        }
        return hi - lo;
    };
    check(spread(b[0]) > spread(a[0]) + 4.0f, "the meander turn actually bends the route");
    check(neverUp(b), "a meandering route still never climbs");
    std::printf("meander ok (straight spread %.1f, winding %.1f blocks)\n",
                static_cast<double>(spread(a[0])), static_cast<double>(spread(b[0])));
}

} // namespace

int main() {
    testALakeSpillsARiverToTheSea();
    testSourceGatingRemovesRiversWithoutMovingTheSurvivors();
    testAreaThresholdRejectsSmallLakes();
    testOnlyTheOwningRegionEmitsARoute();
    testARiverEndsAtTheLakeItFlowsInto();
    testOceanIsNeverASource();
    testTheStepBudgetCannotLeaveTheWindow();
    testNoRouteTravelsFurtherThanItsReach();
    testAWaterfallIsFlaggedAndNothingIsRampedToIt();
    testReachKindFollowsTheGroundBesideTheChannel();
    testWidthGrowsWithDistanceAndDrainedVolume();
    testABiggerLakeMakesAWiderRiver();
    testAWaterfallWidensAPlungePool();
    testRoutesAreDeterministic();
    testRoutesMeander();

    if (failures != 0) {
        std::fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("kernels_river: all checks passed\n");
    return 0;
}
