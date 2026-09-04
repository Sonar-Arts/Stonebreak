/* basin_plan.hpp invariants, check()-counter style like the other kernel tests
 * (release builds define NDEBUG, so assert() is not usable here).
 *
 * These fixtures are built backwards from a known answer: each one's water
 * level is arithmetic, not a previous run's output. That matters more here
 * than anywhere else in the pipeline — a DEM tells you where the basins are
 * but not how deep the water in them should be, so a plausible-looking lake on
 * real terrain proves nothing. Recovered in spirit from the old Python suite's
 * `hydrology/fixtures.py`, which validated the same algorithm.
 *
 * Covered (Lakes-first hydrology plan.md §9):
 *   1. never up          every cell has a non-ascending path to an outlet
 *   2. fill exactness    a paraboloid crater fills to its rim, exactly; two
 *                        basins fill to their own spill points, not their walls
 *   3. spill is escape   the spill level is the rim minimum, and a flat rim
 *                        (every cell tied) still yields one canonical answer
 *   4. halo independence  one basin, three window placements, identical
 *                        surface / depth / volume / spill cell / basin id
 *   5. bbox ownership    span in blocks is the number §4.4's halo rule tests
 *   6. nested basins     a pit inside a bowl fills to ITS rim, not the bowl's
 *                        (§12's open risk, exercised deliberately)
 */

#include "basin_plan.hpp"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace {

namespace bp = cenda::basin;

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

void checkNear(double got, double want, double tol, const char* what) {
    if (!(std::abs(got - want) <= tol)) {
        std::fprintf(stderr, "FAIL: %s (got %.6f, want %.6f, tol %.6f)\n",
                     what, got, want, tol);
        ++failures;
    }
}

/* Deep enough below any fixture's sea level to be unmistakably ocean. */
constexpr float OCEAN = -10.0f;

struct Field {
    int32_t n = 0;
    std::vector<float> z;

    explicit Field(int32_t side, float fill) : n(side), z(static_cast<size_t>(side * side), fill) {}

    float& at(int32_t i, int32_t j) {
        return z[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)];
    }
    float at(int32_t i, int32_t j) const {
        return z[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)];
    }
    void rect(int32_t i0, int32_t i1, int32_t j0, int32_t j1, float v) {
        for (int32_t i = i0; i <= i1; ++i) {
            for (int32_t j = j0; j <= j1; ++j) {
                at(i, j) = v;
            }
        }
    }
    bp::Grid grid(int32_t cellBlocks = 1, int64_t ox = 0, int64_t oz = 0) const {
        return bp::Grid{z.data(), n, cellBlocks, ox, oz};
    }
};

/* ── Invariants that must hold on every fixture ──────────────────────────── */

/** Property 1: every cell is reachable from an outlet by a never-descending
 *  walk, i.e. every cell has a non-ascending path OUT. This is the precondition
 *  §5.1 leans on — a route that descends `filled` can never go uphill — so it
 *  is asserted directly rather than inferred from the absence of local minima
 *  (a flat lake surface has no strict local minimum either way). */
bool everyCellDrains(const bp::Grid& g, const bp::Solution& s, const bp::Config& cfg) {
    const int32_t n = g.cells;
    std::vector<uint8_t> seen(static_cast<size_t>(n * n), 0);
    std::vector<int32_t> queue;
    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            const int32_t k = i * n + j;
            const bool outlet = i == 0 || i == n - 1 || j == 0 || j == n - 1
                || g.raw[static_cast<size_t>(k)] <= cfg.seaLevel;
            if (outlet) {
                seen[static_cast<size_t>(k)] = 1;
                queue.push_back(k);
            }
        }
    }
    for (size_t head = 0; head < queue.size(); ++head) {
        const int32_t k = queue[head];
        const int32_t i = k / n;
        const int32_t j = k - i * n;
        for (int d = 0; d < 8; ++d) {
            const int32_t ni = i + bp::detail::DI[d];
            const int32_t nj = j + bp::detail::DJ[d];
            if (ni < 0 || ni >= n || nj < 0 || nj >= n) {
                continue;
            }
            const auto nk = static_cast<size_t>(ni * n + nj);
            if (seen[nk] || s.filled[nk] < s.filled[static_cast<size_t>(k)]) {
                continue; /* climbing only: the reverse of a drainage path */
            }
            seen[nk] = 1;
            queue.push_back(ni * n + nj);
        }
    }
    for (size_t k = 0; k < seen.size(); ++k) {
        if (!seen[k]) {
            return false;
        }
    }
    return true;
}

/** The universal post-conditions, asserted for every fixture. */
void checkUniversalInvariants(const bp::Grid& g, const bp::Solution& s,
                              const bp::Config& cfg, const char* fixture) {
    char msg[160];
    const auto total = static_cast<size_t>(g.cells) * static_cast<size_t>(g.cells);

    bool raisesOnly = true;
    bool depthAgreesWithLabel = true;
    bool surfacesLevel = true;
    for (size_t k = 0; k < total; ++k) {
        if (s.filled[k] < g.raw[k]) {
            raisesOnly = false;
        }
        const bool wet = s.basinAt[k] != bp::NO_BASIN;
        if (wet != (s.depth[k] > 0.0f)) {
            depthAgreesWithLabel = false;
        }
        if (s.basinAt[k] >= 0) {
            const bp::Basin& b = s.basins[static_cast<size_t>(s.basinAt[k])];
            if (s.filled[k] != b.level || s.depth[k] != s.filled[k] - g.raw[k]) {
                surfacesLevel = false;
            }
        }
    }
    std::snprintf(msg, sizeof msg, "%s: fill only ever raises terrain", fixture);
    check(raisesOnly, msg);
    std::snprintf(msg, sizeof msg, "%s: depth > 0 iff a basin owns the cell", fixture);
    check(depthAgreesWithLabel, msg);
    std::snprintf(msg, sizeof msg, "%s: every lake surface is level to the bit", fixture);
    check(surfacesLevel, msg);
    std::snprintf(msg, sizeof msg, "%s: every cell has a non-ascending path out", fixture);
    check(everyCellDrains(g, s, cfg), msg);

    bool spillsMatchLevels = true;
    for (const bp::Basin& b : s.basins) {
        const auto sk = static_cast<size_t>(b.spillI * g.cells + b.spillJ);
        if (g.raw[sk] != b.level || s.basinAt[sk] != bp::NO_BASIN) {
            spillsMatchLevels = false;
        }
    }
    std::snprintf(msg, sizeof msg, "%s: spill cell is dry ground at the lake's own level", fixture);
    check(spillsMatchLevels, msg);
}

/* ── Fixtures ────────────────────────────────────────────────────────────── */

/** A uniform slope draining to a coast. Nothing pools anywhere; catches a fill
 *  that invents basins out of the boundary condition. */
void testInclinedPlaneHasNoLakes() {
    constexpr int32_t N = 64;
    Field f(N, 0.0f);
    for (int32_t i = 0; i < N; ++i) {
        for (int32_t j = 0; j < N; ++j) {
            f.at(i, j) = 1000.0f - 10.0f * static_cast<float>(i);
        }
    }
    f.rect(N - 1, N - 1, 0, N - 1, OCEAN);

    const bp::Grid g = f.grid();
    bp::Config cfg{0.5f, 8, 0.0f};
    bp::Solution s;
    bp::solve(g, cfg, s);

    check(s.basins.empty(), "inclined plane: no basins");
    bool untouched = true;
    for (size_t k = 0; k < f.z.size(); ++k) {
        if (s.filled[k] != f.z[k]) {
            untouched = false;
        }
    }
    check(untouched, "inclined plane: fill leaves a draining surface alone");
    checkUniversalInvariants(g, s, cfg, "inclined plane");
}

/** A paraboloid basin ringed by a flat wall at exactly the rim elevation, so
 *  the spill elevation is unambiguous and the depth profile is closed-form:
 *      d(r) = depth * (1 - (r / radius)^2)
 *  Volume integrates to depth * pi * radius^2 / 2.
 *
 *  The flat annulus also makes this the tie-break fixture: every rim cell is at
 *  exactly `rim`, so the spill choice is a pure tie and must still be single-
 *  valued. */
void testParaboloidCraterFillsToItsRim() {
    constexpr int32_t N = 96;
    constexpr double RIM = 500.0;
    constexpr double DEPTH = 120.0;
    constexpr double RADIUS = 24.0;
    constexpr double WALL = 6.0;
    const double c = (N - 1) / 2.0;

    Field f(N, 0.0f);
    for (int32_t i = 0; i < N; ++i) {
        for (int32_t j = 0; j < N; ++j) {
            const double r = std::hypot(static_cast<double>(i) - c, static_cast<double>(j) - c);
            double z;
            if (r < RADIUS) {
                z = RIM - DEPTH * (1.0 - (r / RADIUS) * (r / RADIUS));
            } else if (r < RADIUS + WALL) {
                z = RIM;
            } else {
                z = RIM - (r - (RADIUS + WALL)) * 12.0;
            }
            f.at(i, j) = z <= 0.0 ? OCEAN : static_cast<float>(z);
        }
    }

    const bp::Grid g = f.grid();
    bp::Config cfg{0.5f, 8, 0.0f};
    bp::Solution s;
    bp::solve(g, cfg, s);

    check(s.basins.size() == 1, "crater: exactly one basin");
    if (s.basins.size() != 1) {
        return;
    }
    const bp::Basin& b = s.basins[0];
    checkNear(static_cast<double>(b.level), RIM, 1e-3, "crater: lake surface settles at the rim");
    /* No cell sits at r = 0 (the centre falls between four cells at an even
     * size), so the deepest sample is half a diagonal off the vertex. */
    const double rMin = std::hypot(0.5, 0.5);
    checkNear(static_cast<double>(b.floor),
              RIM - DEPTH * (1.0 - (rMin / RADIUS) * (rMin / RADIUS)), 1e-3,
              "crater: floor is the paraboloid's deepest sample");

    double worst = 0.0;
    double volume = 0.0;
    for (int32_t i = 0; i < N; ++i) {
        for (int32_t j = 0; j < N; ++j) {
            const double r = std::hypot(static_cast<double>(i) - c, static_cast<double>(j) - c);
            const double want = r < RADIUS ? DEPTH * (1.0 - (r / RADIUS) * (r / RADIUS)) : 0.0;
            const auto got = static_cast<double>(s.depth[static_cast<size_t>(i * N + j)]);
            volume += got;
            /* Cells within half a cell of the shoreline straddle the
             * minLakeDepth cut-off; the profile is asserted strictly inside. */
            if (want > 1.0) {
                worst = std::max(worst, std::abs(got - want));
            }
        }
    }
    checkNear(worst, 0.0, 1e-2, "crater: depth matches the analytic profile");
    /* The discrete sum approximates the integral to its sampling error. */
    checkNear(volume, DEPTH * 3.14159265358979 * RADIUS * RADIUS / 2.0,
              0.02 * DEPTH * 3.14159265358979 * RADIUS * RADIUS / 2.0,
              "crater: volume matches the integral");
    checkNear(b.volume, volume, 1e-6 * volume, "crater: reported volume is the summed depth");

    /* Pure tie across the whole annulus: still one canonical answer, and it is
     * at the rim elevation rather than at some cell the pop order favoured. */
    check(f.at(b.spillI, b.spillJ) == b.level, "crater: spill cell sits at the spill level");
    checkUniversalInvariants(g, s, cfg, "crater");
}

/** Two pits in a plateau, one draining into the other and on to the sea. Water
 *  levels are set by the OUTLETS, not by the surrounding walls:
 *      pit B (floor 60) spills down a channel at 70  -> fills to 70, depth 10
 *      pit A (floor 50) spills over a saddle at 80   -> fills to 80, depth 30
 *  A fill that ignored spill points would raise both to the 100 plateau and
 *  report 50 and 40; one that stopped at the first barrier would leave them dry. */
void testBasinsFillToSpillPointsNotWalls() {
    constexpr int32_t N = 64;
    Field f(N, 100.0f);
    f.rect(0, N - 1, 61, 63, OCEAN);   /* coast along the +Z edge      */
    f.rect(28, 31, 10, 19, 50.0f);     /* pit A floor                  */
    f.rect(28, 31, 30, 39, 60.0f);     /* pit B floor                  */
    f.rect(29, 30, 20, 29, 80.0f);     /* saddle: A spills into B here */
    f.rect(29, 30, 40, 60, 70.0f);     /* channel: B spills to the sea */

    const bp::Grid g = f.grid(16);     /* real cell size: the span assertion reads it */
    bp::Config cfg{0.5f, 8, 0.0f};
    bp::Solution s;
    bp::solve(g, cfg, s);

    check(s.basins.size() == 2, "two basins: exactly two lakes");
    if (s.basins.size() != 2) {
        return;
    }
    const int32_t idA = s.basinAt[static_cast<size_t>(29 * N + 15)];
    const int32_t idB = s.basinAt[static_cast<size_t>(29 * N + 35)];
    check(idA >= 0 && idB >= 0 && idA != idB, "two basins: the pits are separate lakes");
    if (idA < 0 || idB < 0 || idA == idB) {
        return;
    }
    const bp::Basin& a = s.basins[static_cast<size_t>(idA)];
    const bp::Basin& b = s.basins[static_cast<size_t>(idB)];

    checkNear(static_cast<double>(a.level), 80.0, 0.0, "two basins: A fills to its saddle");
    checkNear(static_cast<double>(b.level), 70.0, 0.0, "two basins: B fills to its channel");
    check(a.area == 40 && b.area == 40, "two basins: each pit is its own floor");
    /* Volume is blocks CUBED: summed depth times the ground one cell covers. */
    checkNear(a.volume, 40.0 * 30.0 * 16.0 * 16.0, 1e-6, "two basins: A holds area x depth");
    checkNear(b.volume, 40.0 * 10.0 * 16.0 * 16.0, 1e-6, "two basins: B holds area x depth");
    check(a.spillI == 29 && a.spillJ == 20, "two basins: A spills over the saddle's first cell");
    check(b.spillI == 29 && b.spillJ == 40, "two basins: B spills into the channel's first cell");
    /* bbox is 4 x 10 cells; the longest side is what §4.4's halo rule reads. */
    check(a.spanBlocks() == 160, "two basins: span is the longest bbox side in blocks");
    checkUniversalInvariants(g, s, cfg, "two basins");
}

/** §12's open risk, made explicit: a pit inside a bowl. The inner pit must fill
 *  to ITS OWN rim (93) and not to the bowl's level (92) or to the plateau
 *  (100), and the two must come out as two basins at two levels rather than one
 *  merged sheet. */
void testNestedBasinFillsToItsOwnRim() {
    constexpr int32_t N = 64;
    Field f(N, 100.0f);
    f.rect(0, N - 1, 61, 63, OCEAN);
    f.rect(10, 50, 10, 50, 90.0f);   /* the outer bowl floor            */
    f.rect(26, 34, 26, 34, 93.0f);   /* a wall standing inside the bowl */
    f.rect(28, 32, 28, 32, 60.0f);   /* the pit inside that wall        */
    f.rect(30, 31, 51, 60, 92.0f);   /* the bowl's outlet to the sea    */

    const bp::Grid g = f.grid();
    bp::Config cfg{0.5f, 8, 0.0f};
    bp::Solution s;
    bp::solve(g, cfg, s);

    const int32_t idOuter = s.basinAt[static_cast<size_t>(15 * N + 15)];
    const int32_t idInner = s.basinAt[static_cast<size_t>(30 * N + 30)];
    check(idOuter >= 0 && idInner >= 0 && idOuter != idInner,
          "nested: the bowl and the pit inside it are separate basins");
    if (idOuter < 0 || idInner < 0 || idOuter == idInner) {
        return;
    }
    const bp::Basin& outer = s.basins[static_cast<size_t>(idOuter)];
    const bp::Basin& inner = s.basins[static_cast<size_t>(idInner)];

    checkNear(static_cast<double>(outer.level), 92.0, 0.0, "nested: the bowl fills to its outlet");
    checkNear(static_cast<double>(inner.level), 93.0, 0.0, "nested: the pit fills to its own wall");
    check(outer.area == 41 * 41 - 9 * 9, "nested: the bowl is its floor minus the wall it holds");
    check(inner.area == 25, "nested: the pit is the ground inside the wall");
    checkNear(inner.volume, 25.0 * 33.0, 1e-6, "nested: the pit holds 33 blocks over 25 cells");
    check(inner.spillI == 27 && inner.spillJ == 27, "nested: the pit spills over its wall");
    check(outer.spillI == 30 && outer.spillJ == 51, "nested: the bowl spills down its outlet");
    checkUniversalInvariants(g, s, cfg, "nested");
}

/* ── Halo independence ───────────────────────────────────────────────────── */

/* A conical pit on a gently tilted plain: a pure function of world
 * coordinates, so any window over it can be materialized independently —
 * exactly how a region assembles its DEM from CoarseDem chunks. */
constexpr int64_t PIT_X = 4096;
constexpr int64_t PIT_Z = 8192;

float terrainAt(int64_t x, int64_t z) {
    double h = 400.0 + 0.002 * static_cast<double>(x) + 0.001 * static_cast<double>(z);
    const double r = std::hypot(static_cast<double>(x - PIT_X), static_cast<double>(z - PIT_Z));
    if (r < 300.0) {
        h -= 40.0 * (1.0 - r / 300.0);
    }
    return static_cast<float>(h);
}

/** Plan §9.4 and the fixture in §2.1: one basin, three window placements, and
 *  the answer must not move. Surface, depth, volume, spill cell and basin id
 *  are all compared bit-exactly, because a difference of one ULP in a lake
 *  level is a visible seam and a difference in a spill cell is a river that
 *  starts somewhere else. */
void testHaloIndependence() {
    constexpr int32_t CELLS = 256;
    constexpr int32_t CELL_BLOCKS = 16;
    const int64_t origins[3][2] = {
        {PIT_X - 2048, PIT_Z - 2048},
        {PIT_X - 1600, PIT_Z - 2400},
        {PIT_X - 2400, PIT_Z - 1600},
    };

    bp::Basin found[3];
    bool ok[3] = {false, false, false};
    for (int w = 0; w < 3; ++w) {
        std::vector<float> z(static_cast<size_t>(CELLS) * static_cast<size_t>(CELLS));
        for (int32_t i = 0; i < CELLS; ++i) {
            for (int32_t j = 0; j < CELLS; ++j) {
                z[static_cast<size_t>(i * CELLS + j)] = terrainAt(
                    origins[w][0] + static_cast<int64_t>(i) * CELL_BLOCKS,
                    origins[w][1] + static_cast<int64_t>(j) * CELL_BLOCKS);
            }
        }
        const bp::Grid g{z.data(), CELLS, CELL_BLOCKS, origins[w][0], origins[w][1]};
        bp::Config cfg{0.5f, 8, 0.0f};
        bp::Solution s;
        bp::solve(g, cfg, s);

        /* The pit's own cell, addressed in world coordinates. */
        const auto pi = static_cast<int32_t>((PIT_X - origins[w][0]) / CELL_BLOCKS);
        const auto pj = static_cast<int32_t>((PIT_Z - origins[w][1]) / CELL_BLOCKS);
        const int32_t id = s.basinAt[static_cast<size_t>(pi * CELLS + pj)];
        if (id < 0) {
            check(false, "halo: the pit is a basin from every window");
            continue;
        }
        found[w] = s.basins[static_cast<size_t>(id)];
        ok[w] = true;
        checkUniversalInvariants(g, s, cfg, "halo window");
    }

    if (!ok[0] || !ok[1] || !ok[2]) {
        return;
    }
    for (int w = 1; w < 3; ++w) {
        check(found[w].level == found[0].level, "halo: same lake surface, bit for bit");
        check(found[w].floor == found[0].floor, "halo: same floor");
        check(found[w].area == found[0].area, "halo: same area");
        check(found[w].volume == found[0].volume, "halo: same volume, bit for bit");
        check(found[w].spillX == found[0].spillX && found[w].spillZ == found[0].spillZ,
              "halo: same spill cell in world coordinates");
        check(found[w].id(1433293152336000383LL) == found[0].id(1433293152336000383LL),
              "halo: same basin id");
        check(found[w].spanBlocks() == found[0].spanBlocks(),
              "halo: same bbox span");
    }
    /* The pit is 600 blocks across, so it is well inside the 2048-block L1
     * halo — §4.4 would let L1 own it. */
    check(found[0].spanBlocks() > 0 && found[0].spanBlocks() <= 2048,
          "halo: the fixture basin is one L1 can own");
    check(!found[0].touchesBorder, "halo: the fixture basin is fully inside its window");
}

/* ── Filtering ───────────────────────────────────────────────────────────── */

/** A puddle below minLakeArea is not a lake — but the FILL still happened, so
 *  the routing field stays complete and §5.1's never-up guarantee survives. */
void testMinLakeAreaDiscardsPuddlesButKeepsTheFill() {
    constexpr int32_t N = 48;
    Field f(N, 100.0f);
    f.rect(0, N - 1, 45, 47, OCEAN);
    f.rect(20, 21, 20, 21, 80.0f);   /* a 4-cell puddle */

    const bp::Grid g = f.grid();
    bp::Solution kept;
    bp::solve(g, bp::Config{0.5f, 4, 0.0f}, kept);
    bp::Solution dropped;
    bp::solve(g, bp::Config{0.5f, 9, 0.0f}, dropped);

    check(kept.basins.size() == 1, "min area: the puddle is a lake at threshold 4");
    check(dropped.basins.empty(), "min area: the same puddle is not a lake at threshold 9");
    bool sameFill = true;
    bool noDepth = true;
    for (size_t k = 0; k < f.z.size(); ++k) {
        if (kept.filled[k] != dropped.filled[k]) {
            sameFill = false;
        }
        if (dropped.depth[k] != 0.0f) {
            noDepth = false;
        }
    }
    check(sameFill, "min area: discarding a puddle does not change the routing field");
    check(noDepth, "min area: a discarded puddle reports no depth to stamp");
    checkUniversalInvariants(g, dropped, bp::Config{0.5f, 9, 0.0f}, "min area");
}

/** Ocean is an outlet, never a basin: ground below sea level keeps its own
 *  elevation and cannot be filled into a lake. */
void testOceanIsNeverALake() {
    constexpr int32_t N = 48;
    Field f(N, 100.0f);
    /* A hole punched down through sea level, walled in by the plateau. */
    f.rect(20, 27, 20, 27, -50.0f);

    const bp::Grid g = f.grid();
    bp::Config cfg{0.5f, 8, 0.0f};
    bp::Solution s;
    bp::solve(g, cfg, s);

    check(s.basins.empty(), "ocean: a sub-sea hollow is sea, not a lake");
    bool untouched = true;
    for (int32_t i = 20; i <= 27; ++i) {
        for (int32_t j = 20; j <= 27; ++j) {
            if (s.filled[static_cast<size_t>(i * N + j)] != -50.0f) {
                untouched = false;
            }
        }
    }
    check(untouched, "ocean: sub-sea ground is never raised");
    checkUniversalInvariants(g, s, cfg, "ocean");
}

/* ── Two-level ownership (§4.4) ──────────────────────────────────────────── */

/* A continent-scale bowl, far wider than L1's 2048-block halo, sitting on a
 * plain that tilts away to the sea. Pure function of world coordinates, so
 * every region's window can be materialized on its own — which is the whole
 * point of the test: two L1 regions must emit the SAME surface for it, and
 * neither may emit it from its own solve. */
constexpr double BOWL_R = 3200.0;   /* blocks; 6400 across, 3.1x the L1 halo */
constexpr int64_t BOWL_X = 0;
constexpr int64_t BOWL_Z = 0;

float bigBowlAt(int64_t x, int64_t z) {
    /* A plain at 500 that falls away to the sea in +X, so the bowl has an
     * outlet and the fill has somewhere to drain to. The tilt is deliberately
     * gentler than the bowl is deep over the same distance (6.4 blocks against
     * 60) — at a steeper tilt the plain simply out-drops the bowl and there is
     * no closed depression to own. */
    double h = 500.0 - 0.002 * static_cast<double>(x);
    const double r = std::hypot(static_cast<double>(x - BOWL_X), static_cast<double>(z - BOWL_Z));
    if (r < BOWL_R) {
        const double t = r / BOWL_R;
        h -= 60.0 * (1.0 - t * t);
    }
    return static_cast<float>(h);
}

void fillWindow(const bp::Grid& g, std::vector<float>& z, float (*f)(int64_t, int64_t)) {
    z.resize(static_cast<size_t>(g.cells) * static_cast<size_t>(g.cells));
    for (int32_t i = 0; i < g.cells; ++i) {
        for (int32_t j = 0; j < g.cells; ++j) {
            z[static_cast<size_t>(i) * static_cast<size_t>(g.cells) + static_cast<size_t>(j)] =
                f(g.worldX(i), g.worldZ(j));
        }
    }
}

/** L0's input is a box-downsample of the SAME heights L1 reads (§4.4), never an
 *  independently generated field. Materialized here by averaging every L1 cell
 *  inside each L0 cell, which is what `boxDownsample` does over a resident fine
 *  grid — the L0 window is 65536 blocks a side, far too much fine data to hold
 *  at once, so the real caller downsamples chunk by chunk. */
void fillCoarseWindow(const bp::Grid& g, const bp::Level& lv, std::vector<float>& z,
                      float (*f)(int64_t, int64_t)) {
    z.resize(static_cast<size_t>(g.cells) * static_cast<size_t>(g.cells));
    const int32_t k = lv.downsample;
    const int32_t fineBlocks = g.cellBlocks / k;
    const double inv = 1.0 / (static_cast<double>(k) * static_cast<double>(k));
    for (int32_t i = 0; i < g.cells; ++i) {
        for (int32_t j = 0; j < g.cells; ++j) {
            double acc = 0.0;
            for (int32_t di = 0; di < k; ++di) {
                for (int32_t dj = 0; dj < k; ++dj) {
                    acc += static_cast<double>(f(g.worldX(i) + di * fineBlocks,
                                                 g.worldZ(j) + dj * fineBlocks));
                }
            }
            z[static_cast<size_t>(i) * static_cast<size_t>(g.cells) + static_cast<size_t>(j)] =
                static_cast<float>(acc * inv);
        }
    }
}

/** `boxDownsample` must average, not sample: a lake level is set by the lowest
 *  saddle on its rim, and every 8th cell walks past most saddles. */
void testBoxDownsampleAverages() {
    constexpr int32_t N = 16;
    Field f(N, 0.0f);
    for (int32_t i = 0; i < N; ++i) {
        for (int32_t j = 0; j < N; ++j) {
            f.at(i, j) = static_cast<float>(i * N + j);
        }
    }
    std::vector<float> out;
    bp::boxDownsample(f.grid(16), 4, out);
    check(out.size() == 16, "downsample: 16^2 cells at factor 4 is 4^2");
    /* Block (0,0) covers rows 0..3 and cols 0..3: mean of 0..3, 16..19, ... */
    double want = 0.0;
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) {
            want += i * N + j;
        }
    }
    checkNear(static_cast<double>(out[0]), want / 16.0, 1e-4, "downsample: cell is the box mean");
}

/** Plan §9.5. The bowl is ~6400 blocks across; L1's halo is 2048, so no L1
 *  region may emit it, and L0 (16 km halo) must. */
void testBasinTooWideForL1IsNotEmittedByL1() {
    const bp::Level& l1 = bp::LEVEL_L1;
    std::vector<float> z;
    bp::Grid g = bp::windowFor(l1, 0, 0, nullptr);
    fillWindow(g, z, bigBowlAt);
    g.raw = z.data();

    bp::Config cfg{0.5f, 8, 0.0f};
    bp::Solution s;
    bp::solve(g, cfg, s);

    /* L1 does find a basin over the bowl — it just must not be trusted. */
    const auto ci = static_cast<int32_t>((BOWL_X - g.originX) / l1.cellBlocks);
    const auto cj = static_cast<int32_t>((BOWL_Z - g.originZ) / l1.cellBlocks);
    const int32_t id = s.basinAt[static_cast<size_t>(ci * g.cells + cj)];
    check(id >= 0, "ownership: L1's own solve does see water over the bowl");
    if (id >= 0) {
        check(!bp::ownsBasin(s.basins[static_cast<size_t>(id)], l1),
              "ownership: L1 does not own a basin wider than its halo");
    }

    /* A basin small enough is still owned — the rule is not "never trust L1". */
    bp::Basin small;
    small.cellBlocks = l1.cellBlocks;
    small.maxI = small.maxJ = 40;   /* 41 cells = 656 blocks */
    check(bp::ownsBasin(small, l1), "ownership: L1 owns a basin that fits its halo");
    small.touchesBorder = true;
    check(!bp::ownsBasin(small, l1), "ownership: a truncated basin is never owned");
}

/** The stake that leaves with the withheld count.
 *
 *  A count alone cannot tell a caller whether escalating is worth 4x the DEM,
 *  because the coarser rung applies minLakeArea in its own (larger) cells and
 *  may discard the very basin it was fetched for. Measured 2026-09-03, one
 *  such escalation cost 207 CoarseDem chunks and emitted zero lake cells. So
 *  `applyOwnership` reports what is actually at risk, and this pins it to the
 *  withheld basin's own record rather than to a plausible-looking number. */
void testWithheldReportsTheStakeNotJustTheCount() {
    const bp::Level& l1 = bp::LEVEL_L1;
    bp::Config cfg{0.5f, 8, 0.0f};

    /* A pit that fits its level's halo comfortably: owned, so nothing is at
     * risk even though the window has a lake in it. 12 cells is 192 blocks
     * against a 512-block halo, and it sits inside the owned rectangle
     * (cells 32..63), so neither ownership test can be the one passing. */
    {
        constexpr int32_t N = 64;
        std::vector<float> z(static_cast<size_t>(N) * N, 0.0f);
        for (int32_t i = 0; i < N; ++i) {
            for (int32_t j = 0; j < N; ++j) {
                z[static_cast<size_t>(i) * N + static_cast<size_t>(j)] =
                    100.0f - 0.01f * static_cast<float>(i);
            }
        }
        for (int32_t i = 34; i <= 45; ++i) {
            for (int32_t j = 34; j <= 45; ++j) {
                z[static_cast<size_t>(i) * N + static_cast<size_t>(j)] = 60.0f;
            }
        }
        const bp::Grid g{z.data(), N, l1.cellBlocks, 0, 0};
        bp::Solution s;
        bp::solve(g, cfg, s);
        check(s.basins.size() == 1, "stake: the control fixture has exactly one lake");
        const bp::Level small{l1.cellBlocks, 512, 512, 1};
        const bp::Withheld w = bp::applyOwnership(s, small);
        check(w.count == 0, "stake: an owned basin is not withheld");
        check(w.lakeCells == 0 && w.spanBlocks == 0,
              "stake: nothing withheld reports no stake at all");
    }

    /* The continent-scale bowl: 3.1x L1's halo, so L1 must withhold it. */
    std::vector<float> z;
    bp::Grid g = bp::windowFor(l1, 0, 0, nullptr);
    fillWindow(g, z, bigBowlAt);
    g.raw = z.data();
    bp::Solution s;
    bp::solve(g, cfg, s);

    /* Read the basin's own record BEFORE ownership zeroes its depth, so the
     * expected numbers come from the solve rather than from the thing under
     * test. */
    const auto ci = static_cast<int32_t>((BOWL_X - g.originX) / l1.cellBlocks);
    const auto cj = static_cast<int32_t>((BOWL_Z - g.originZ) / l1.cellBlocks);
    const int32_t id = s.basinAt[static_cast<size_t>(ci * g.cells + cj)];
    check(id >= 0, "stake: the bowl is a basin in L1's own solve");
    if (id < 0) {
        return;
    }
    const int32_t wantArea = s.basins[static_cast<size_t>(id)].area;
    const int32_t wantSpan = s.basins[static_cast<size_t>(id)].spanBlocks();

    const bp::Withheld w = bp::applyOwnership(s, l1);
    check(w.count > 0, "stake: L1 withholds the oversized bowl");
    check(w.lakeCells == wantArea, "stake: the lake at risk is the basin's trimmed area");
    check(w.spanBlocks == wantSpan, "stake: the span at risk is the basin's bbox side");
    check(w.spanBlocks > l1.haloBlocks, "stake: the withheld bowl really is too wide for L1");

    /* The point of the number: L2 doubles the cell, so it needs 4x the block
     * area before it will emit anything. A caller compares these two. */
    const int64_t stakeBlocks =
        static_cast<int64_t>(w.lakeCells) * l1.cellBlocks * l1.cellBlocks;
    const int64_t l2Floor =
        static_cast<int64_t>(cfg.minLakeArea) * (2 * l1.cellBlocks) * (2 * l1.cellBlocks);
    check(stakeBlocks > l2Floor,
          "stake: this fixture's lake IS worth escalating for, so the gate must pass it");
}

/** The acceptance criterion of §4.4, and the bar the old system set at 6,715
 *  surfaces: two L1 regions covering different parts of one oversized basin
 *  must emit the SAME lake level, bit for bit, after importing it from L0. */
void testOversizedBasinLevelsAcrossARegionSeam() {
    const bp::Level& l1 = bp::LEVEL_L1;
    const bp::Level& l0 = bp::LEVEL_L0;
    bp::Config cfg{0.5f, 8, 0.0f};

    /* One L0 region covers 32768 blocks and contains the whole bowl. */
    std::vector<float> cz;
    bp::Grid coarseGrid = bp::windowFor(l0, bp::regionOf(BOWL_X, l0), bp::regionOf(BOWL_Z, l0), nullptr);
    fillCoarseWindow(coarseGrid, l0, cz, bigBowlAt);
    coarseGrid.raw = cz.data();
    bp::Solution coarse;
    bp::solve(coarseGrid, cfg, coarse);
    const bp::Withheld coarseWithheld = bp::applyOwnership(coarse, l0);
    check(coarseWithheld.count == 0, "seam: L0 owns everything it found in this window");

    const auto bi = static_cast<int32_t>((BOWL_X - coarseGrid.originX) / l0.cellBlocks);
    const auto bj = static_cast<int32_t>((BOWL_Z - coarseGrid.originZ) / l0.cellBlocks);
    const int32_t bigId = coarse.basinAt[static_cast<size_t>(bi * coarseGrid.cells + bj)];
    check(bigId >= 0, "seam: L0 finds the oversized basin");
    if (bigId < 0) {
        return;
    }
    const bp::Basin& big = coarse.basins[static_cast<size_t>(bigId)];
    check(bp::ownsBasin(big, l0), "seam: L0 owns a basin L1 cannot");
    check(big.spanBlocks() > l1.haloBlocks, "seam: the fixture basin really is too wide for L1");

    /* Two adjacent L1 regions, each containing part of the bowl. Region (-1, 0)
     * and region (0, 0) share the seam at world x = 0, straight through it. */
    const int64_t regions[2][2] = {{-1, 0}, {0, 0}};
    float levelSeen[2] = {0.0f, 0.0f};
    int64_t wetCells[2] = {0, 0};
    for (int r = 0; r < 2; ++r) {
        std::vector<float> fz;
        bp::Grid fineGrid = bp::windowFor(l1, regions[r][0], regions[r][1], nullptr);
        fillWindow(fineGrid, fz, bigBowlAt);
        fineGrid.raw = fz.data();
        bp::Solution fine;
        bp::solve(fineGrid, cfg, fine);
        const bp::Withheld withheld = bp::applyOwnership(fine, l1);
        check(withheld.count > 0, "seam: L1 withholds the oversized basin rather than emitting it");
        bp::mergeLevels(fineGrid, fine, coarseGrid,
                        coarse.filled.data(), coarse.depth.data(), cfg);

        /* Only the region's OWN columns are emitted; the halo is scaffolding. */
        bool oneLevel = true;
        float level = -1.0f;
        for (int32_t i = 0; i < fineGrid.cells; ++i) {
            for (int32_t j = 0; j < fineGrid.cells; ++j) {
                const int64_t wx = fineGrid.worldX(i);
                const int64_t wz = fineGrid.worldZ(j);
                if (bp::regionOf(wx, l1) != regions[r][0]
                        || bp::regionOf(wz, l1) != regions[r][1]) {
                    continue;
                }
                const auto k = static_cast<size_t>(i) * static_cast<size_t>(fineGrid.cells)
                    + static_cast<size_t>(j);
                if (fine.basinAt[k] == bp::NO_BASIN) {
                    continue;
                }
                check(fine.basinAt[k] == bp::IMPORTED_BASIN,
                      "seam: the oversized lake is imported, never L1's own record");
                const float lv = fine.filled[k];
                if (level < 0.0f) {
                    level = lv;
                } else if (lv != level) {
                    oneLevel = false;
                }
                ++wetCells[r];
            }
        }
        check(oneLevel, "seam: one region emits a single level over the oversized basin");
        levelSeen[r] = level;
    }

    check(wetCells[0] > 0 && wetCells[1] > 0, "seam: both regions emit part of the lake");
    check(levelSeen[0] == levelSeen[1],
          "seam: adjacent regions agree on the lake surface, bit for bit");
    check(levelSeen[0] == big.level, "seam: the emitted surface is L0's, imported unchanged");
}

/** Importing must not resurrect a lake where the fine ground stands above the
 *  coarse surface — L0 averaged 8x8 cells, so an island inside a big lake is
 *  invisible to it and only L1's own heights can keep it dry. */
void testImportedLakeRespectsFineGround() {
    const bp::Level& l1 = bp::LEVEL_L1;
    bp::Config cfg{0.5f, 8, 0.0f};

    /* A coarse level that has already been through applyOwnership: one wide
     * lake at level 100, published as the two self-describing planes. */
    constexpr int32_t CN = 8;
    std::vector<float> cz(static_cast<size_t>(CN * CN), 50.0f);
    const bp::Grid coarseGrid{cz.data(), CN, 128, -512, -512};
    const std::vector<float> coarseFilled(cz.size(), 100.0f);
    const std::vector<float> coarseDepth(cz.size(), 50.0f);

    /* Fine ground: mostly at 60 (drowned), with a peak at 120 (an island). */
    constexpr int32_t FN = 64;
    std::vector<float> fz(static_cast<size_t>(FN * FN), 60.0f);
    bp::Grid fineGrid{fz.data(), FN, 16, -512, -512};
    fz[static_cast<size_t>(30 * FN + 30)] = 120.0f;
    bp::Solution fine;
    bp::solve(fineGrid, cfg, fine);
    bp::applyOwnership(fine, l1);
    bp::mergeLevels(fineGrid, fine, coarseGrid, coarseFilled.data(), coarseDepth.data(), cfg);

    check(fine.basinAt[static_cast<size_t>(30 * FN + 30)] == bp::NO_BASIN,
          "import: fine ground above the imported surface stays dry");
    const auto drowned = static_cast<size_t>(30 * FN + 31);
    check(fine.basinAt[drowned] == bp::IMPORTED_BASIN,
          "import: fine ground below it takes the imported lake");
    checkNear(static_cast<double>(fine.depth[drowned]), 40.0, 1e-4,
              "import: depth is the imported surface over the FINE ground");
    check(fine.filled[drowned] == 100.0f,
          "import: the routing field takes the imported surface too");
}

} // namespace

int main() {
    testInclinedPlaneHasNoLakes();
    testParaboloidCraterFillsToItsRim();
    testBasinsFillToSpillPointsNotWalls();
    testNestedBasinFillsToItsOwnRim();
    testHaloIndependence();
    testMinLakeAreaDiscardsPuddlesButKeepsTheFill();
    testOceanIsNeverALake();
    testBoxDownsampleAverages();
    testBasinTooWideForL1IsNotEmittedByL1();
    testWithheldReportsTheStakeNotJustTheCount();
    testOversizedBasinLevelsAcrossARegionSeam();
    testImportedLakeRespectsFineGround();

    if (failures != 0) {
        std::fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    std::printf("kernels_basin: all checks passed\n");
    return 0;
}
