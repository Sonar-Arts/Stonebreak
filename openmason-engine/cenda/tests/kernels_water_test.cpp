/* ck_carve_water invariants, check()-counter style like the other kernel
 * tests (release builds define NDEBUG, so assert() is not usable here).
 *
 * The synthetic terrain is a pure function of world coordinates, so any tile's
 * 3x3 input window can be materialized independently — exactly how the game
 * assembles windows from cached bridge tiles. The tests pin:
 *
 *   1. determinism          same inputs -> byte-identical outputs
 *   2. in-tile containment  no wet column pours onto lower dry ground
 *   3. seam containment     the same invariant across the border of two
 *                           independently-computed adjacent tiles
 *   4. lake seam agreement  a basin straddling a tile border gets the same
 *                           level from both owning tiles (rivers disabled so
 *                           the lake lattice is the only variable)
 *   5. sanity               sea columns report sea level; rivers exist and
 *                           carve on default params
 */

#include "cenda/kernels.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

constexpr int T = 256;
constexpr int W = 3 * T;
constexpr int SEA = 320;
constexpr int WH = 1024;

size_t idx(int row, int col, int stride) {
    return static_cast<size_t>(row) * static_cast<size_t>(stride) + static_cast<size_t>(col);
}

/* Deterministic rolling-hills terrain with a coast: pure function of (x, z). */
int16_t terrainAt(int64_t x, int64_t z) {
    double h = 330.0;
    h += 14.0 * std::sin(static_cast<double>(x) * 0.011) * std::cos(static_cast<double>(z) * 0.009);
    h += 6.0 * std::sin(static_cast<double>(x) * 0.031 + 1.7) * std::sin(static_cast<double>(z) * 0.027);
    h += 3.0 * std::cos(static_cast<double>(x + z) * 0.05);
    if (x < -200) { /* coast toward -x */
        h -= static_cast<double>(-200 - x) * 0.15;
    }
    return static_cast<int16_t>(h);
}

/* Mountainous terrain: big ridges with slopes past 1 block/block near the
 * inflection lines, plus valley floors between them. */
int16_t mountainTerrainAt(int64_t x, int64_t z) {
    double h = 380.0;
    h += 60.0 * std::sin(static_cast<double>(x) * 0.020) * std::cos(static_cast<double>(z) * 0.015);
    h += 22.0 * std::sin(static_cast<double>(x) * 0.043 + 0.9) * std::sin(static_cast<double>(z) * 0.037);
    h += 6.0 * std::cos(static_cast<double>(x - z) * 0.09);
    return static_cast<int16_t>(h);
}

/* Same idea with a parabolic bowl (a lake basin) stamped at (bx, bz). */
int16_t bowlTerrainAt(int64_t x, int64_t z, int64_t bx, int64_t bz) {
    double base = 340.0 + 2.0 * std::sin(static_cast<double>(x) * 0.013)
                        * std::cos(static_cast<double>(z) * 0.017);
    const double dx = static_cast<double>(x - bx);
    const double dz = static_cast<double>(z - bz);
    const double r2 = dx * dx + dz * dz;
    const double R = 26.0;
    if (r2 < R * R) {
        base -= 7.0 * (1.0 - r2 / (R * R));
    }
    return static_cast<int16_t>(base);
}

struct Tile {
    std::vector<int16_t> heights;
    std::vector<int16_t> water;
};

template <typename F>
Tile runTile(int64_t seed, int64_t tileX, int64_t tileZ, F&& terrain,
             const float* params, int32_t nParams) {
    const int64_t originX = (tileX - 1) * T;
    const int64_t originZ = (tileZ - 1) * T;
    std::vector<int16_t> window(static_cast<size_t>(W) * static_cast<size_t>(W));
    for (int x = 0; x < W; ++x) {
        for (int z = 0; z < W; ++z) {
            window[idx(x, z, W)] = terrain(originX + x, originZ + z);
        }
    }
    Tile t;
    t.heights.resize(static_cast<size_t>(T) * static_cast<size_t>(T));
    t.water.resize(t.heights.size());
    const int32_t rc = ck_carve_water(seed, T,
                                      static_cast<int32_t>(originX), static_cast<int32_t>(originZ),
                                      window.data(), SEA, WH, params, nParams,
                                      t.heights.data(), t.water.data());
    check(rc == 0, "ck_carve_water returned 0");
    return t;
}

/* Containment inside one tile, interior columns only (border columns have
 * their off-tile neighbors checked by the seam test). */
int containmentViolations(const Tile& t) {
    int bad = 0;
    for (int x = 1; x < T - 1; ++x) {
        for (int z = 1; z < T - 1; ++z) {
            const size_t i = idx(x, z, T);
            const int16_t w = t.water[i];
            if (w < 0) {
                continue;
            }
            const size_t nb[4] = {i - static_cast<size_t>(T), i + static_cast<size_t>(T),
                                  i - 1, i + 1};
            for (size_t n : nb) {
                if (t.water[n] < 0 && t.heights[n] < w) {
                    ++bad;
                }
            }
        }
    }
    return bad;
}

/* Facing-column containment across the +X seam of tiles a (lower) and b. */
int seamViolations(const Tile& a, const Tile& b) {
    int bad = 0;
    for (int z = 0; z < T; ++z) {
        const size_t ia = idx(T - 1, z, T); /* a's last row  */
        const size_t ib = idx(0, z, T);     /* b's first row */
        const int16_t wa = a.water[ia];
        const int16_t wb = b.water[ib];
        if (wa >= 0 && wb < 0 && b.heights[ib] < wa) ++bad;
        if (wb >= 0 && wa < 0 && a.heights[ia] < wb) ++bad;
    }
    return bad;
}

void testDeterminism() {
    const Tile t1 = runTile(42, 3, -2, terrainAt, nullptr, 0);
    const Tile t2 = runTile(42, 3, -2, terrainAt, nullptr, 0);
    check(std::memcmp(t1.heights.data(), t2.heights.data(), t1.heights.size() * 2) == 0,
          "heights deterministic");
    check(std::memcmp(t1.water.data(), t2.water.data(), t1.water.size() * 2) == 0,
          "water deterministic");
    std::puts("determinism ok");
}

void testContainmentAndSeams() {
    for (int64_t seed : {1LL, 7LL, 20260801LL}) {
        Tile prev{};
        bool havePrev = false;
        for (int64_t tx = -1; tx <= 2; ++tx) {
            Tile cur = runTile(seed, tx, 0, terrainAt, nullptr, 0);
            check(containmentViolations(cur) == 0, "in-tile containment");
            if (havePrev) {
                check(seamViolations(prev, cur) == 0, "cross-seam containment");
            }
            prev = std::move(cur);
            havePrev = true;
        }
    }
    std::puts("containment + seams ok");
}

void testLakeSeamAgreement() {
    /* Bowl centered exactly on the border between tiles (0,0) and (1,0):
     * world x = 256. Rivers off so only the lake machinery runs; a generous
     * keep fraction so some lattice candidate lands in the bowl across seeds. */
    const float params[] = {0.0f, 3.0f, 24.0f, 96.0f, 1.0f, 72.0f, 25.0f, 70.0f};
    auto terrain = [](int64_t x, int64_t z) { return bowlTerrainAt(x, z, 256, 128); };
    int lakesSeen = 0;
    for (int64_t seed = 1; seed <= 12; ++seed) {
        const Tile a = runTile(seed, 0, 0, terrain, params, 8);
        const Tile b = runTile(seed, 1, 0, terrain, params, 8);
        check(seamViolations(a, b) == 0, "lake seam containment");
        bool any = false;
        for (int z = 0; z < T; ++z) {
            const int16_t wa = a.water[idx(T - 1, z, T)];
            const int16_t wb = b.water[idx(0, z, T)];
            if (wa >= 0 && wb >= 0) {
                check(wa == wb, "straddling lake level agrees across tiles");
                any = true;
            }
        }
        if (any) {
            ++lakesSeen;
        }
    }
    check(lakesSeen > 0, "at least one seed put a lake across the seam");
    std::printf("lake seam agreement ok (%d/12 seeds straddled)\n", lakesSeen);
}

void testSanity() {
    /* Coastal tile: columns below sea report sea level. */
    const Tile coast = runTile(9, -3, 0, terrainAt, nullptr, 0);
    int seaCols = 0;
    for (size_t i = 0; i < coast.heights.size(); ++i) {
        if (coast.heights[i] < SEA) {
            check(coast.water[i] == SEA, "sea column reports sea level");
            ++seaCols;
        }
    }
    check(seaCols > 0, "coastal tile has sea columns");

    /* Rivers exist somewhere on default params: scan a row of tiles for
     * inland wet columns and carved ground. */
    int wet = 0;
    int carvedCols = 0;
    for (int64_t tx = 0; tx < 6; ++tx) {
        const Tile t = runTile(9, tx, 1, terrainAt, nullptr, 0);
        for (int x = 0; x < T; ++x) {
            for (int z = 0; z < T; ++z) {
                const size_t i = idx(x, z, T);
                if (t.water[i] > SEA) ++wet;
                if (t.heights[i] < terrainAt(tx * T + x, T + z)) ++carvedCols;
            }
        }
    }
    check(wet > 0, "default params produce inland water");
    check(carvedCols > 0, "default params carve terrain");
    std::printf("sanity ok (%d inland wet, %d carved over 6 tiles)\n", wet, carvedCols);
}

void testExcavatedLakes() {
    /* Smooth rolling terrain has almost no natural closed depressions, so a
     * lake system that only fills existing basins leaves the world lakeless —
     * the excavation fallback digs vanilla-style ponds at the lattice rate.
     * Rivers disabled so ponds are the only inland water; containment and
     * cross-tile agreement must hold for dug ponds exactly like filled ones. */
    const float params[] = {0.0f};
    int ponds = 0;
    Tile prev{};
    bool havePrev = false;
    for (int64_t tx = 0; tx < 4; ++tx) {
        Tile cur = runTile(11, tx, 0, terrainAt, params, 1);
        check(containmentViolations(cur) == 0, "pond in-tile containment");
        if (havePrev) {
            check(seamViolations(prev, cur) == 0, "pond cross-seam containment");
        }
        for (size_t i = 0; i < cur.water.size(); ++i) {
            if (cur.water[i] > SEA) {
                ++ponds;
                /* An excavated pond's bed is dug below its level. */
                check(cur.heights[i] < cur.water[i], "pond column has room for water");
            }
        }
        prev = std::move(cur);
        havePrev = true;
    }
    check(ponds > 0, "excavated ponds appear on smooth terrain");
    std::printf("excavated lakes ok (%d pond columns over 4 tiles)\n", ponds);
}

void testMountains() {
    /* The mountain regression: a tilted per-column water surface once stepped
     * water sideways across a channel's own width on hillsides and slotted
     * canyons through spurs. Pinned here: containment and seams still hold on
     * steep terrain, no wet column sits under ground more than max_incision
     * above its surface, and steps between adjacent wet columns stay small
     * (level cross-sections; along-flow steps are pool-and-drop). */
    constexpr int MAX_INCISION = 12;
    int wet = 0;
    int maxStep = 0;
    long steps = 0;
    long bigSteps = 0;
    Tile prev{};
    bool havePrev = false;
    for (int64_t tx = -1; tx <= 2; ++tx) {
        Tile cur = runTile(5, tx, 0, mountainTerrainAt, nullptr, 0);
        check(containmentViolations(cur) == 0, "mountain in-tile containment");
        if (havePrev) {
            check(seamViolations(prev, cur) == 0, "mountain cross-seam containment");
        }
        for (int x = 0; x < T; ++x) {
            for (int z = 0; z < T; ++z) {
                const size_t i = idx(x, z, T);
                const int16_t w = cur.water[i];
                if (w < 0) {
                    continue;
                }
                ++wet;
                const int raw = mountainTerrainAt(tx * T + x, z);
                check(raw - w <= MAX_INCISION, "wet column within max incision of its surface");
                if (x + 1 < T && cur.water[i + T] >= 0) {
                    const int d = std::abs(w - cur.water[i + T]);
                    maxStep = std::max(maxStep, d);
                    ++steps;
                    if (d > 1) ++bigSteps;
                }
                if (z + 1 < T && cur.water[i + 1] >= 0) {
                    const int d = std::abs(w - cur.water[i + 1]);
                    maxStep = std::max(maxStep, d);
                    ++steps;
                    if (d > 1) ++bigSteps;
                }
            }
        }
        prev = std::move(cur);
        havePrev = true;
    }
    check(wet > 0, "mountain terrain still gets valley rivers/lakes");
    check(maxStep <= 8, "no giant water cliffs between adjacent wet columns");
    if (steps > 0) {
        check(bigSteps * 10 < steps, "under 10% of adjacent wet pairs step more than 1 block");
    }
    std::printf("mountains ok (%d wet, max adjacent step %d, %ld/%ld steps > 1)\n",
                wet, maxStep, bigSteps, steps);
}

} // namespace

int main() {
    check(ck_abi_version() == CK_ABI_VERSION, "abi version");
    testDeterminism();
    testContainmentAndSeams();
    testLakeSeamAgreement();
    testExcavatedLakes();
    testMountains();
    testSanity();
    if (failures == 0) {
        std::puts("kernels_water: all ok");
    }
    return failures == 0 ? 0 : 1;
}
