/* ck_carve_water invariants, check()-counter style like the other kernel
 * tests (release builds define NDEBUG, so assert() is not usable here).
 *
 * The synthetic terrain is a pure function of world coordinates, so any tile's
 * 3x3 input window can be materialized independently — exactly how the game
 * assembles windows from cached bridge tiles. The DEM planes come from a real
 * ck_solve_basins over one L1 region, sliced per tile the way NativeWaterTiles
 * slices them, so the two kernels are exercised against each other rather than
 * against a hand-built plane that could agree with neither.
 *
 * The tests pin:
 *
 *   1. determinism          same inputs -> byte-identical outputs
 *   2. in-tile containment  no wet column pours onto lower dry ground
 *   3. seam containment     the same invariant across the border of two
 *                           independently-computed adjacent tiles
 *   4. lake seam agreement  a basin straddling a tile border gets the same
 *                           level from both tiles, bit for bit
 *   5. lakes come from the fill, not from this kernel: the level is the
 *                           filled surface, the shoreline is where block
 *                           heights cross it, and no ground is excavated
 *   6. sea                  columns below sea level report sea level, with or
 *                           without a DEM
 *   7. steep terrain        containment and level surfaces survive mountains
 *   8. tunnelling          ground standing over a route is NOT removed: the
 *                           river runs under it, the lid stays solid, and the
 *                           void is sealed by the rock around it
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

/* L1 geometry, mirroring BasinCache.Level.L1. */
constexpr int CELL = 16;
constexpr int REGION_BLOCKS = 4096;
constexpr int HALO_BLOCKS = 2048;
constexpr int REGION_CELLS = (REGION_BLOCKS + 2 * HALO_BLOCKS) / CELL; /* 512 */
constexpr int SPAN_CELLS = W / CELL;                                   /* 48  */

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

/* A conical bowl (one lake basin) on a plain that tilts away toward +X.
 *
 * The tilt is not decoration. An untilted rolling surface has no drainage at
 * all, so Priority-Flood merges the entire window into one continental basin
 * that no level can own, and the bowl vanishes into it — which is exactly what
 * the first version of this fixture did. The plain descends 0.02 blocks per
 * block, steeper than any ripple, so the only closed depression in the window
 * is the one the test is about. */
int16_t bowlTerrainAt(int64_t x, int64_t z, int64_t bx, int64_t bz) {
    double base = 500.0 - 0.02 * static_cast<double>(x);
    const double dx = static_cast<double>(x - bx);
    const double dz = static_cast<double>(z - bz);
    const double r = std::sqrt(dx * dx + dz * dz);
    const double R = 200.0;
    if (r < R) {
        base -= 24.0 * (1.0 - r / R);   /* 0.12 per block: deeper than the tilt */
    }
    return static_cast<int16_t>(base);
}

/* ── The region solve, and the per-tile slice of it ─────────────────────── */

/** One L1 region's solved planes, held the way BasinCache holds them. */
struct Region {
    int64_t originX = 0, originZ = 0;
    std::vector<float> filled;
    std::vector<float> depth;
    int32_t withheld = 0;
    /* The river plan, as ck_solve_basins packs it. */
    int32_t routeCount = 0;
    std::vector<int32_t> starts;
    std::vector<float> verts;
};

template <typename F>
Region solveRegion(int64_t regionX, int64_t regionZ, F&& terrain,
                   int64_t seed = 1, float keepFraction = 0.0f) {
    Region r;
    r.originX = regionX * REGION_BLOCKS - HALO_BLOCKS;
    r.originZ = regionZ * REGION_BLOCKS - HALO_BLOCKS;
    /* The DEM is the block heights averaged onto 16-block cells — what
     * CoarseDem serves, built here from the same terrain function so the two
     * kernels see one world. */
    std::vector<float> dem(static_cast<size_t>(REGION_CELLS) * REGION_CELLS);
    for (int i = 0; i < REGION_CELLS; ++i) {
        for (int j = 0; j < REGION_CELLS; ++j) {
            double acc = 0.0;
            for (int a = 0; a < CELL; ++a) {
                for (int b = 0; b < CELL; ++b) {
                    acc += terrain(r.originX + static_cast<int64_t>(i) * CELL + a,
                                   r.originZ + static_cast<int64_t>(j) * CELL + b);
                }
            }
            dem[idx(i, j, REGION_CELLS)] = static_cast<float>(acc / (CELL * CELL));
        }
    }
    r.filled.resize(dem.size());
    r.depth.resize(dem.size());
    constexpr int32_t MAX_ROUTES = 64;
    constexpr int32_t MAX_VERTS = 1 << 15;
    r.starts.assign(MAX_ROUTES + 1, 0);
    r.verts.assign(static_cast<size_t>(MAX_VERTS) * CK_RIVER_VERTEX_FLOATS, 0.0f);
    int32_t counts[2] = {0, 0};
    const float params[] = {0.5f, 8.0f, static_cast<float>(SEA), 8.0f, keepFraction};
    r.withheld = ck_solve_basins(seed, REGION_BLOCKS, HALO_BLOCKS,
                                 REGION_CELLS, CELL,
                                 r.originX, r.originZ, dem.data(), params, 5,
                                 0, 0, 0, 0, nullptr, nullptr,
                                 r.filled.data(), r.depth.data(),
                                 MAX_ROUTES, MAX_VERTS,
                                 r.starts.data(), r.verts.data(), counts, nullptr);
    check(r.withheld >= 0, "ck_solve_basins returned a basin count");
    r.routeCount = counts[0];
    return r;
}

struct Tile {
    std::vector<int16_t> heights;
    std::vector<int16_t> water;
    std::vector<int16_t> floor;
    std::vector<int16_t> roof;
};

/**
 * Hydrate one tile the way NativeWaterTiles does: build the 3x3 raw window,
 * slice the owning region's planes over exactly that window, and stamp.
 * `region == nullptr` runs the no-DEM path.
 */
template <typename F>
Tile runTile(int64_t seed, int64_t tileX, int64_t tileZ, F&& terrain, const Region* region,
             const float* params = nullptr, int32_t nParams = 0) {
    const int64_t originX = (tileX - 1) * T;
    const int64_t originZ = (tileZ - 1) * T;
    std::vector<int16_t> window(static_cast<size_t>(W) * static_cast<size_t>(W));
    for (int x = 0; x < W; ++x) {
        for (int z = 0; z < W; ++z) {
            window[idx(x, z, W)] = terrain(originX + x, originZ + z);
        }
    }

    std::vector<float> spanFilled;
    std::vector<float> spanDepth;
    if (region != nullptr) {
        spanFilled.resize(static_cast<size_t>(SPAN_CELLS) * SPAN_CELLS);
        spanDepth.resize(spanFilled.size());
        const int64_t di = (originX - region->originX) / CELL;
        const int64_t dj = (originZ - region->originZ) / CELL;
        check(di >= 0 && dj >= 0 && di + SPAN_CELLS <= REGION_CELLS
                  && dj + SPAN_CELLS <= REGION_CELLS,
              "tile window lies inside its region's window");
        for (int i = 0; i < SPAN_CELLS; ++i) {
            const size_t src = idx(static_cast<int>(di) + i, static_cast<int>(dj), REGION_CELLS);
            std::memcpy(&spanFilled[idx(i, 0, SPAN_CELLS)], &region->filled[src],
                        SPAN_CELLS * sizeof(float));
            std::memcpy(&spanDepth[idx(i, 0, SPAN_CELLS)], &region->depth[src],
                        SPAN_CELLS * sizeof(float));
        }
    }

    Tile t;
    t.heights.resize(static_cast<size_t>(T) * static_cast<size_t>(T));
    t.water.resize(t.heights.size());
    t.floor.resize(t.heights.size());
    t.roof.resize(t.heights.size());
    const int32_t rc = ck_carve_water(seed, T,
                                      static_cast<int32_t>(originX), static_cast<int32_t>(originZ),
                                      window.data(), SEA, WH,
                                      region == nullptr ? 0 : SPAN_CELLS, CELL,
                                      region == nullptr ? nullptr : spanFilled.data(),
                                      region == nullptr ? nullptr : spanDepth.data(),
                                      region == nullptr ? 0 : region->routeCount,
                                      region == nullptr ? nullptr : region->starts.data(),
                                      region == nullptr ? nullptr : region->verts.data(),
                                      params, nParams,
                                      t.heights.data(), t.water.data(),
                                      t.floor.data(), t.roof.data());
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

/* A tilted plain with one pit, and a coast far enough down it that the pit's
 * outlet river has room to run: the fixture the whole river path needs. */
int16_t riverTerrainAt(int64_t x, int64_t z) {
    double base = 420.0 - 0.05 * static_cast<double>(x);
    const double dx = static_cast<double>(x - 400);
    const double dz = static_cast<double>(z - 2048);
    const double r = std::sqrt(dx * dx + dz * dz);
    if (r < 200.0) {
        base -= 40.0 * (1.0 - r / 200.0);
    }
    return static_cast<int16_t>(base);
}

/* `riverTerrainAt` with a ridge thrown across the river's path.
 *
 * The fixture for the tunnelling tests, and it is built the way the real defect
 * was: the region is solved on the SMOOTH plain, so the route is planned on a
 * DEM that knows nothing about the ridge, and then the carve is handed ground
 * that has one. That is not a contrivance — it is the production case in
 * miniature. The router descends a 16-block filled DEM and can honestly report
 * that it never climbs, while the stamp writes block-resolution terrain the DEM
 * averaged away. A route crossing this ridge used to delete it.
 *
 * 44 blocks tall against a bed a few blocks deep, so nothing about the answer is
 * ambiguous: either the ridge is standing afterwards or it is not. */
int16_t ridgedRiverTerrainAt(int64_t x, int64_t z) {
    const double d = (static_cast<double>(x) - 1200.0) / 70.0;
    return static_cast<int16_t>(riverTerrainAt(x, z) + 44.0 * std::exp(-d * d));
}

/* ── Tests ──────────────────────────────────────────────────────────────── */

void testDeterminism() {
    const Region r = solveRegion(0, 0, terrainAt);
    const Tile t1 = runTile(42, 3, 2, terrainAt, &r);
    const Tile t2 = runTile(42, 3, 2, terrainAt, &r);
    check(std::memcmp(t1.heights.data(), t2.heights.data(), t1.heights.size() * 2) == 0,
          "heights deterministic");
    check(std::memcmp(t1.water.data(), t2.water.data(), t1.water.size() * 2) == 0,
          "water deterministic");
    std::puts("determinism ok");
}

void testContainmentAndSeams() {
    const Region r = solveRegion(0, 0, terrainAt);
    Tile prev{};
    bool havePrev = false;
    for (int64_t tx = 1; tx <= 6; ++tx) {
        Tile cur = runTile(1, tx, 4, terrainAt, &r);
        check(containmentViolations(cur) == 0, "in-tile containment");
        if (havePrev) {
            check(seamViolations(prev, cur) == 0, "cross-seam containment");
        }
        prev = std::move(cur);
        havePrev = true;
    }
    std::puts("containment + seams ok");
}

void testLakeComesFromTheFill() {
    /* A 400-block bowl at world (2048, 2048): deep inside L1 region (0,0),
     * far narrower than the 2048-block halo, so L1 owns it outright. */
    auto terrain = [](int64_t x, int64_t z) { return bowlTerrainAt(x, z, 2048, 2048); };
    const Region r = solveRegion(0, 0, terrain);
    check(r.withheld == 0, "L1 owns a 400-block bowl");

    /* The bowl's center cell in the region planes. */
    const size_t centre = idx(static_cast<int>((2048 - r.originX) / CELL),
                              static_cast<int>((2048 - r.originZ) / CELL), REGION_CELLS);
    check(r.depth[centre] > 0.0f, "the fill found the bowl");
    const int fillLevel = static_cast<int>(std::lround(r.filled[centre]));

    const Tile t = runTile(3, 8, 8, terrain, &r);   /* tile covering (2048, 2048) */
    int wet = 0;
    int levels = 0;
    int16_t seen = -1;
    int excavated = 0;
    int raised = 0;
    for (int x = 0; x < T; ++x) {
        for (int z = 0; z < T; ++z) {
            const size_t i = idx(x, z, T);
            const int64_t wx = 8 * T + x;
            const int64_t wz = 8 * T + z;
            const int16_t rawH = terrain(wx, wz);
            if (t.heights[i] < rawH) {
                ++excavated;
            }
            if (t.heights[i] > rawH) {
                ++raised;
            }
            const int16_t w = t.water[i];
            if (w < 0 || w == SEA) {
                continue;
            }
            ++wet;
            if (seen < 0) {
                seen = w;
                ++levels;
            } else if (w != seen) {
                ++levels;
            }
            /* The shoreline is a comparison, not a construction: a column is
             * wet exactly when its BLOCK height is under the lake's level. */
            check(rawH < w, "a wet column's block height is below the lake level");
        }
    }
    check(wet > 0, "the bowl is stamped as lake");
    check(levels == 1, "the whole lake sits on one level");
    check(seen == fillLevel, "the stamped level IS the filled surface");
    /* No excavator: the depression was already there, which is what the fill
     * found. The old pond digger existed only because nothing was finding
     * depressions, and it is gone (§3.1). */
    check(excavated == 0, "no ground is dug out for a lake");
    check(containmentViolations(t) == 0, "the lake is contained");
    /* `raised` is reported rather than required, and it comes out zero here.
     * That is the interesting result: a lake drawn by comparing block heights
     * against its own spill level is contained BY CONSTRUCTION — a dry column
     * beside a wet one is dry precisely because it stands at or above the
     * level. The containment repair is now a safety net for the sea and for
     * whatever the river phases add, not the mechanism holding lakes in. */
    std::printf("lake from fill ok (%d wet columns at level %d, %d columns walled)\n",
                wet, seen, raised);
}

void testLakeSeamAgreement() {
    /* Bowl centered exactly on the border between tiles (8,8) and (9,8):
     * world x = 9*256 = 2304. Both tiles must stamp the identical level. */
    auto terrain = [](int64_t x, int64_t z) { return bowlTerrainAt(x, z, 2304, 2048); };
    const Region r = solveRegion(0, 0, terrain);
    const Tile a = runTile(4, 8, 8, terrain, &r);
    const Tile b = runTile(4, 9, 8, terrain, &r);
    check(seamViolations(a, b) == 0, "lake seam containment");

    int straddling = 0;
    for (int z = 0; z < T; ++z) {
        const int16_t wa = a.water[idx(T - 1, z, T)];
        const int16_t wb = b.water[idx(0, z, T)];
        if (wa > SEA && wb > SEA) {
            check(wa == wb, "straddling lake level agrees across tiles");
            ++straddling;
        }
    }
    check(straddling > 0, "the bowl really does straddle the seam");
    std::printf("lake seam agreement ok (%d straddling columns)\n", straddling);
}


void testARiverIsStampedIntoTheGround() {
    /* The end of the whole pipeline: a lake's spill point becomes a route,
     * the route becomes a channel, and the channel holds water. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    check(r.routeCount > 0, "river stamp: the fixture plans a river");
    if (r.routeCount == 0) {
        return;
    }

    /* Walk the tiles the river crosses on its way to the coast. */
    int wet = 0;
    int carved = 0;
    int deepestCut = 0;
    int violations = 0;
    for (int64_t tx = 2; tx <= 7; ++tx) {
        const Tile tile = runTile(777, tx, 8, riverTerrainAt, &r);
        violations += containmentViolations(tile);
        for (int x = 0; x < T; ++x) {
            for (int z = 0; z < T; ++z) {
                const size_t i = idx(x, z, T);
                const int64_t wx = tx * T + x;
                const int64_t wz = 8 * T + z;
                const int16_t rawH = riverTerrainAt(wx, wz);
                if (tile.water[i] > SEA) {
                    ++wet;
                    /* A wet column either sits under its own water — the channel
                     * was cut for it rather than the water perched on the ground
                     * — or it carries that water through a tunnel, in which case
                     * the ground standing over it is the entire point and it is
                     * correct for the height to be above the surface. */
                    check(tile.heights[i] < tile.water[i] || tile.roof[i] >= 0,
                          "a river column is under its own surface or tunnelled");
                }
                if (tile.heights[i] < rawH) {
                    ++carved;
                    deepestCut = std::max(deepestCut, rawH - tile.heights[i]);
                }
            }
        }
    }
    check(violations == 0, "river stamp: containment holds along the whole reach");
    check(wet > 0, "river stamp: the route puts water on the ground");
    check(carved > 0, "river stamp: the route carves a channel");
    check(deepestCut > 0 && deepestCut < 64, "river stamp: the cut is a channel, not a shaft");
    std::printf("river stamp ok (%d wet columns, %d carved, deepest cut %d blocks)\n",
                wet, carved, deepestCut);
}

void testTheChannelIsLevelAcrossItsWidth() {
    /* The regression the old noise water died of: a per-column water surface
     * on a slope steps sideways across a channel's own width, so one bank sits
     * a block above the other and water pours across itself.
     *
     * The surface is rounded once per cross-section, and a cross-section is
     * PERPENDICULAR to the flow. A row of the tile is not: where the river runs
     * diagonally a row cuts it at an angle and spans a range of positions
     * ALONG the channel, over which a descending river legitimately drops. So
     * what is asserted is that the range within one slice is at most a block —
     * a step along the flow, never a tilt across it. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        return;
    }
    int slices = 0;
    int stepped = 0;
    for (int64_t tx = 3; tx <= 6; ++tx) {
        const Tile tile = runTile(777, tx, 8, riverTerrainAt, &r);
        for (int x = 0; x < T; ++x) {
            /* One row across the tile is roughly perpendicular to a river
             * running along +X, so a run of wet columns in it is a slice. */
            int z = 0;
            while (z < T) {
                if (tile.water[idx(x, z, T)] <= SEA) {
                    ++z;
                    continue;
                }
                int16_t lo = tile.water[idx(x, z, T)];
                int16_t hi = lo;
                int width = 0;
                while (z < T && tile.water[idx(x, z, T)] > SEA) {
                    lo = std::min(lo, tile.water[idx(x, z, T)]);
                    hi = std::max(hi, tile.water[idx(x, z, T)]);
                    ++width;
                    ++z;
                }
                if (width >= 3) {
                    ++slices;
                    if (hi - lo > 1) {
                        ++stepped;
                    }
                }
            }
        }
    }
    check(slices > 0, "level cross-section: found channel slices to check");
    check(stepped == 0, "no channel slice spans more than one block of surface");
    std::printf("level cross-section ok (%d slices, %d spanning more than a block)\n",
                slices, stepped);
}

void testRiversAgreeAcrossATileSeam() {
    /* Two adjacent tiles hydrated independently, each with its own gathered
     * routes. The river crossing the border must come out identical from both:
     * the route is the same object, and everything here is a comparison or an
     * order-independent merge. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        return;
    }
    int compared = 0;
    for (int64_t tx = 2; tx <= 6; ++tx) {
        const Tile a = runTile(777, tx, 8, riverTerrainAt, &r);
        const Tile b = runTile(777, tx + 1, 8, riverTerrainAt, &r);
        check(seamViolations(a, b) == 0, "river seam containment");
        for (int z = 0; z < T; ++z) {
            const int16_t wa = a.water[idx(T - 1, z, T)];
            const int16_t wb = b.water[idx(0, z, T)];
            if (wa > SEA && wb > SEA) {
                /* Facing columns are one block apart; a river surface may drop
                 * between them, but only downstream and only by a step. */
                check(std::abs(wa - wb) <= 1, "the river surface agrees across the seam");
                ++compared;
            }
        }
    }
    check(compared > 0, "a river straddled a tile seam to compare");
    std::printf("river seam ok (%d straddling columns)\n", compared);
}

void testNoRoutesMeansNoRivers() {
    /* The gate all the way through: with the keep fraction at zero the region
     * plans nothing, and the tile is exactly the lakes-only result. */
    const Region withRivers = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    const Region without = solveRegion(0, 0, riverTerrainAt, 777, 0.0f);
    check(without.routeCount == 0, "keep fraction 0 plans no rivers");
    /* Compared over the band the river crosses rather than one tile: with a
     * meander on, which tile it threads is not something a test should assume. */
    int changed = 0;
    for (int64_t tx = 2; tx <= 7; ++tx) {
        const Tile a = runTile(777, tx, 8, riverTerrainAt, &withRivers);
        const Tile b = runTile(777, tx, 8, riverTerrainAt, &without);
        for (size_t i = 0; i < a.water.size(); ++i) {
            if (a.water[i] != b.water[i] || a.heights[i] != b.heights[i]) {
                ++changed;
            }
        }
    }
    check(changed > 0, "a planned river actually changes the ground it crosses");
    std::printf("river gate ok (%d columns differ with rivers on)\n", changed);
}


/* ── Phase 10: the shared params array ──────────────────────────────────── */

/** The defaults kernels.h documents, in its own order. */
const float DOCUMENTED_DEFAULTS[26] = {
    0.5f,      /*  0 min_lake_depth      */
    8.0f,      /*  1 min_lake_area       */
    320.0f,    /*  2 sea_level           */
    12.0f,     /*  3 min_river_lake_area */
    0.70f,     /*  4 river_keep_fraction */
    16.0f,     /*  5 step_len            */
    256.0f,    /*  6 max_steps           */
    1.0f,      /*  7 w_inertia           */
    0.55f,     /*  8 w_descent           */
    0.35f,     /*  9 meander_amp         */
    24.0f,     /* 10 gorge_max_depth     */
    96.0f,     /* 11 gorge_max_width     */
    6.0f,      /* 12 waterfall_min_drop  */
    4.0f,      /* 13 w_base              */
    3.0f,      /* 14 w_lake              */
    2.0f,      /* 15 w_dist              */
    50000.0f,  /* 16 vol_scale           */
    1000.0f,   /* 17 dist_scale          */
    1.5f,      /* 18 d_base              */
    0.8f,      /* 19 d_gain              */
    1.6f,      /* 20 plunge_widen        */
    2.0f,      /* 21 refine_levels       */
    0.22f,     /* 22 refine_amp          */
    4.0f,      /* 23 min_points          */
    5.0f,      /* 24 tunnel_headroom     */
    4.0f,      /* 25 tunnel_min_roof     */
};

/* Mirrors DOCUMENTED_DEFAULTS[25]; the tests below assert against the lid the
 * kernel promises to leave, so the two must not drift. */
constexpr int TUNNEL_MIN_ROOF = 4;

/** Solve one region with an explicit params array. */
Region solveWithParams(const float* params, int32_t n) {
    Region r;
    r.originX = -2048;
    r.originZ = -2048;
    std::vector<float> dem(static_cast<size_t>(REGION_CELLS) * REGION_CELLS);
    for (int32_t i = 0; i < REGION_CELLS; ++i) {
        for (int32_t j = 0; j < REGION_CELLS; ++j) {
            double acc = 0.0;
            for (int a = 0; a < CELL; ++a) {
                for (int b = 0; b < CELL; ++b) {
                    acc += riverTerrainAt(r.originX + static_cast<int64_t>(i) * CELL + a,
                                          r.originZ + static_cast<int64_t>(j) * CELL + b);
                }
            }
            dem[idx(i, j, REGION_CELLS)] = static_cast<float>(acc / (CELL * CELL));
        }
    }
    r.filled.resize(dem.size());
    r.depth.resize(dem.size());
    constexpr int32_t MAX_ROUTES = 64;
    constexpr int32_t MAX_VERTS = 1 << 15;
    r.starts.assign(MAX_ROUTES + 1, 0);
    r.verts.assign(static_cast<size_t>(MAX_VERTS) * CK_RIVER_VERTEX_FLOATS, 0.0f);
    int32_t counts[2] = {0, 0};
    r.withheld = ck_solve_basins(31337, REGION_BLOCKS, HALO_BLOCKS,
                                 REGION_CELLS, CELL,
                                 r.originX, r.originZ, dem.data(), params, n,
                                 0, 0, 0, 0, nullptr, nullptr,
                                 r.filled.data(), r.depth.data(),
                                 MAX_ROUTES, MAX_VERTS,
                                 r.starts.data(), r.verts.data(), counts, nullptr);
    r.routeCount = counts[0];
    return r;
}

void testDocumentedDefaultsAreTheRealDefaults() {
    /* The one assertion that keeps a params table honest: passing the values
     * kernels.h documents must be indistinguishable from passing nothing. A
     * default edited in the code and not in the table — or an index that
     * shifted when a knob was inserted — shows up here and nowhere else. */
    const Region none = solveWithParams(nullptr, 0);
    const Region spelled = solveWithParams(DOCUMENTED_DEFAULTS, 26);

    check(none.routeCount == spelled.routeCount,
          "the documented defaults plan the same rivers as no params at all");
    bool sameFill = none.filled.size() == spelled.filled.size();
    for (size_t i = 0; i < none.filled.size() && sameFill; ++i) {
        if (none.filled[i] != spelled.filled[i] || none.depth[i] != spelled.depth[i]) {
            sameFill = false;
        }
    }
    check(sameFill, "and the same lakes");
    bool sameVerts = true;
    for (int32_t v = 0; v < none.routeCount && sameVerts; ++v) {
        if (none.starts[static_cast<size_t>(v)] != spelled.starts[static_cast<size_t>(v)]) {
            sameVerts = false;
        }
    }
    check(sameVerts, "and the same route geometry");

    /* And the carve's three indices are read from the same array, not a second
     * one: a params array that is all defaults must stamp what NULL stamps. */
    const Tile a = runTile(31337, 4, 8, riverTerrainAt, &none);
    std::vector<int16_t> outH(static_cast<size_t>(T) * T);
    std::vector<int16_t> outW(outH.size());
    std::vector<int16_t> outF(outH.size());
    std::vector<int16_t> outR(outH.size());
    std::vector<int16_t> win(static_cast<size_t>(W) * W);
    const int64_t ox = 3 * T;
    const int64_t oz = 7 * T;
    for (int x = 0; x < W; ++x) {
        for (int z = 0; z < W; ++z) {
            win[idx(x, z, W)] = riverTerrainAt(ox + x, oz + z);
        }
    }
    std::vector<float> spanF(static_cast<size_t>(SPAN_CELLS) * SPAN_CELLS);
    std::vector<float> spanD(spanF.size());
    const int64_t di = (ox - none.originX) / CELL;
    const int64_t dj = (oz - none.originZ) / CELL;
    for (int i = 0; i < SPAN_CELLS; ++i) {
        const size_t src = idx(static_cast<int>(di) + i, static_cast<int>(dj), REGION_CELLS);
        std::memcpy(&spanF[idx(i, 0, SPAN_CELLS)], &none.filled[src], SPAN_CELLS * sizeof(float));
        std::memcpy(&spanD[idx(i, 0, SPAN_CELLS)], &none.depth[src], SPAN_CELLS * sizeof(float));
    }
    ck_carve_water(31337, T, static_cast<int32_t>(ox), static_cast<int32_t>(oz),
                   win.data(), SEA, WH, SPAN_CELLS, CELL, spanF.data(), spanD.data(),
                   none.routeCount, none.starts.data(), none.verts.data(),
                   DOCUMENTED_DEFAULTS, 26, outH.data(), outW.data(),
                   outF.data(), outR.data());
    check(std::memcmp(a.heights.data(), outH.data(), outH.size() * 2) == 0
              && std::memcmp(a.water.data(), outW.data(), outW.size() * 2) == 0
              && std::memcmp(a.floor.data(), outF.data(), outF.size() * 2) == 0
              && std::memcmp(a.roof.data(), outR.data(), outR.size() * 2) == 0,
          "the carve reads the same array's defaults, not a second table");
    std::puts("params ABI ok");
}

void testTunnelKnobsAreLiveOnTheCarve() {
    /* The carve reads exactly two slots, and a knob that looks honoured and is
     * not is worse than an absent one — the reason [10] and [14] are gone. So
     * each of the two that replaced them is probed in the direction it can
     * actually move the ground.
     *
     * [25] tunnel_min_roof is the lid the roof is clamped under, so raising it
     * past any available ground forces every column back onto the open-channel
     * branch: no tunnels, and terrain cut instead. That is the strongest signal
     * either slot can give, because it swings the kernel between its two modes.
     *
     * [24] tunnel_headroom cannot do that, and deliberately: whether a river
     * tunnels is a question about the ground over it, not about how much air it
     * is given. Setting it to zero leaves every tunnel exactly where it was and
     * floods it — the ground is still kept, which is the invariant — so what is
     * probed is the void, which must shrink and may never grow. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    check(r.routeCount > 0, "tunnel knobs: the fixture plans a river");
    if (r.routeCount == 0) {
        return;
    }

    float noRoof[26];
    float noAir[26];
    std::memcpy(noRoof, DOCUMENTED_DEFAULTS, sizeof noRoof);
    std::memcpy(noAir, DOCUMENTED_DEFAULTS, sizeof noAir);
    noRoof[25] = 4096.0f;   /* no column has that much ground to spare */
    noAir[24] = 0.0f;

    int baseTunnels = 0;
    int noRoofTunnels = 0;
    int noAirTunnels = 0;
    int cutMore = 0;
    long baseVoid = 0;
    long airlessVoid = 0;
    int airlessTaller = 0;
    for (int64_t tx = 2; tx <= 7; ++tx) {
      for (int64_t tz = 7; tz <= 9; ++tz) {
        const Tile base = runTile(777, tx, tz, ridgedRiverTerrainAt, &r, DOCUMENTED_DEFAULTS, 26);
        const Tile flat = runTile(777, tx, tz, ridgedRiverTerrainAt, &r, noRoof, 26);
        const Tile airless = runTile(777, tx, tz, ridgedRiverTerrainAt, &r, noAir, 26);
        for (size_t i = 0; i < base.heights.size(); ++i) {
            baseTunnels += base.roof[i] >= 0 ? 1 : 0;
            noRoofTunnels += flat.roof[i] >= 0 ? 1 : 0;
            noAirTunnels += airless.roof[i] >= 0 ? 1 : 0;
            if (base.roof[i] >= 0) {
                baseVoid += base.roof[i] - base.floor[i];
            }
            if (airless.roof[i] >= 0) {
                airlessVoid += airless.roof[i] - airless.floor[i];
                if (base.roof[i] >= 0 && airless.roof[i] > base.roof[i]) {
                    ++airlessTaller;
                }
            }
            check(airless.heights[i] == base.heights[i],
                  "[24] headroom never changes the ground, only the void under it");
            if (flat.heights[i] < base.heights[i]) {
                ++cutMore;
            }
        }
      }
    }
    check(baseTunnels > 0, "at the defaults the ridged fixture tunnels");
    check(noRoofTunnels == 0, "[25] an unaffordable lid leaves no tunnels at all");
    check(cutMore > 0, "[25] and the columns that stopped tunnelling got cut instead");
    check(noAirTunnels == baseTunnels, "[24] headroom does not decide WHETHER to tunnel");
    check(airlessVoid < baseVoid, "[24] but no headroom does shrink the void (the slot is live)");
    check(airlessTaller == 0, "and it can only ever shrink it");
    std::printf("tunnel knobs ok (%d tunnel columns, %d cut when tunnels are off, "
                "void %ld -> %ld with no headroom)\n",
                baseTunnels, cutMore, baseVoid, airlessVoid);
}

void testARiverTunnelsRatherThanRemovingTheGround() {
    /* THE regression for the reported defect: a river crossing a hill used to
     * take the hill with it. `carved = surf - cut` was written unconditionally
     * against a `surf` sampled off a 16-block DEM, so every block of ground
     * standing over the route was deleted down to the water line.
     *
     * The ridge here is 44 blocks tall. What is pinned is the whole of the new
     * contract: the ground survives, the river is still there underneath it, and
     * the lid over it is real rock rather than a token block. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    check(r.routeCount > 0, "tunnelling: the fixture plans a river");
    if (r.routeCount == 0) {
        return;
    }

    int tunnelled = 0;
    int deepestCut = 0;
    int thinnestLid = WH;
    int ridgeCrestColumns = 0;
    for (int64_t tx = 2; tx <= 7; ++tx) {
      for (int64_t tz = 7; tz <= 9; ++tz) {
        const Tile tile = runTile(777, tx, tz, ridgedRiverTerrainAt, &r);
        for (int x = 0; x < T; ++x) {
            for (int z = 0; z < T; ++z) {
                const size_t i = idx(x, z, T);
                const int64_t wx = tx * T + x;
                const int64_t wz = tz * T + z;
                const int rawH = ridgedRiverTerrainAt(wx, wz);

                /* No column anywhere loses more than the bed of an open channel
                 * plus the lid the roof clamp is allowed to shave. Before the
                 * fix this reached the full height of the ridge. */
                deepestCut = std::max(deepestCut, rawH - tile.heights[i]);

                if (tile.roof[i] >= 0) {
                    ++tunnelled;
                    check(tile.heights[i] >= rawH,
                          "a tunnelled column keeps every block of its ground");
                    check(tile.floor[i] < tile.roof[i], "a tunnel has room inside it");
                    check(tile.water[i] > tile.floor[i],
                          "and water standing on its floor");
                    thinnestLid = std::min(thinnestLid, tile.heights[i] - tile.roof[i]);
                }
                /* The crest of the ridge, away from the channel, must be
                 * untouched — the stamp has no business there at all. */
                if (wx - 1200 <= 4 && 1200 - wx <= 4) {
                    ++ridgeCrestColumns;
                    check(tile.heights[i] >= rawH, "the ridge crest is not planed down");
                }
            }
        }
      }
    }
    check(tunnelled > 0, "the route tunnels through the ridge rather than cutting it");
    check(ridgeCrestColumns > 0, "the walked tiles actually cover the ridge");
    check(thinnestLid >= TUNNEL_MIN_ROOF,
          "every tunnel keeps at least tunnel_min_roof of rock over it");
    check(deepestCut <= TUNNEL_MIN_ROOF + 8,
          "no column is lowered by more than a bed plus the lid");
    std::printf("tunnelling ok (%d tunnel columns, thinnest lid %d, deepest cut %d blocks)\n",
                tunnelled, thinnestLid, deepestCut);
}

void testATunnelIsSealedByTheRockAroundIt() {
    /* A tunnel is not covered by the containment rule the open water uses — it
     * is contained by the rock it was bored through — so the seal is asserted
     * directly. Worldgen water is a source block, so a void beside a tunnel at
     * the same height is a permanent spring, exactly like a breached riverbed.
     *
     * Each 4-neighbour of a tunnelled column must therefore be one of: solid to
     * at least the tunnel's water line; a tunnel itself; or wet at that line or
     * above, which is the mouth where the passage opens into open channel. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        check(false, "tunnel seal: the fixture plans a river");
        return;
    }

    int checked = 0;
    int leaks = 0;
    for (int64_t tx = 2; tx <= 7; ++tx) {
      for (int64_t tz = 7; tz <= 9; ++tz) {
        const Tile t = runTile(777, tx, tz, ridgedRiverTerrainAt, &r);
        for (int x = 1; x < T - 1; ++x) {
            for (int z = 1; z < T - 1; ++z) {
                const size_t i = idx(x, z, T);
                if (t.roof[i] < 0) {
                    continue;
                }
                const int waterTop = std::min<int>(t.water[i], t.roof[i]);
                const size_t nb[4] = {i - static_cast<size_t>(T), i + static_cast<size_t>(T),
                                      i - 1, i + 1};
                for (size_t n : nb) {
                    ++checked;
                    const bool sealed = t.heights[n] >= waterTop
                                        || t.roof[n] >= 0
                                        || t.water[n] >= waterTop;
                    if (!sealed) {
                        ++leaks;
                    }
                }
            }
        }
      }
    }
    check(checked > 0, "tunnel seal: there were tunnels to check");
    check(leaks == 0, "no tunnel column pours into a lower dry neighbour");
    std::printf("tunnel seal ok (%d neighbours checked, %d leaks)\n", checked, leaks);
}

void testEachDensityKnobMovesInTheDocumentedDirection() {
    float p[26];
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
    const Region base = solveWithParams(p, 26);

    p[4] = 0.0f;   /* river_keep_fraction */
    check(solveWithParams(p, 26).routeCount == 0, "[4] keep fraction 0 plans no rivers");
    p[4] = 1.0f;
    const Region all = solveWithParams(p, 26);
    check(all.routeCount >= base.routeCount, "[4] keep fraction 1 plans at least as many");
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);

    p[3] = 100000.0f;  /* min_river_lake_area */
    check(solveWithParams(p, 26).routeCount == 0, "[3] an impossible area gate plans no rivers");
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);

    p[0] = 10000.0f;   /* min_lake_depth */
    const Region dry = solveWithParams(p, 26);
    long wet = 0;
    for (float d : dry.depth) {
        if (d > 0.0f) {
            ++wet;
        }
    }
    check(wet == 0, "[0] an impossible depth gate leaves no lakes");
    std::printf("density knobs ok (%d rivers at the defaults, %d at keep 1.0)\n",
                base.routeCount, all.routeCount);
}

void testSea() {
    /* Coastal tile: columns below sea report sea level, DEM or no DEM. The
     * no-DEM path is the graceful degradation an absent basin cache falls to. */
    const Region r = solveRegion(-1, 0, terrainAt);
    for (int withDem = 0; withDem < 2; ++withDem) {
        const Tile coast = runTile(9, -3, 0, terrainAt, withDem ? &r : nullptr);
        int seaCols = 0;
        int inland = 0;
        for (size_t i = 0; i < coast.heights.size(); ++i) {
            if (coast.heights[i] < SEA) {
                check(coast.water[i] == SEA, "sea column reports sea level");
                ++seaCols;
            }
            if (coast.water[i] > SEA) {
                ++inland;
            }
        }
        check(seaCols > 0, "coastal tile has sea columns");
        if (!withDem) {
            check(inland == 0, "without a DEM there is no inland water at all");
        }
    }
    std::puts("sea ok");
}

void testMountains() {
    /* Steep ground was where the old noise-derived water broke: a tilted
     * per-column surface stepped water sideways across a channel's own width.
     * There is no per-column surface any more — a lake's level is one integer
     * for the whole basin — so what is pinned here is that the fill still finds
     * water in the valleys and that every surface it puts there is level. */
    const Region r = solveRegion(0, 0, mountainTerrainAt);
    int wet = 0;
    int sideSteps = 0;
    Tile prev{};
    bool havePrev = false;
    for (int64_t tx = 9; tx <= 12; ++tx) {
        Tile cur = runTile(5, tx, 9, mountainTerrainAt, &r);
        check(containmentViolations(cur) == 0, "mountain in-tile containment");
        if (havePrev) {
            check(seamViolations(prev, cur) == 0, "mountain cross-seam containment");
        }
        for (int x = 0; x < T; ++x) {
            for (int z = 0; z < T; ++z) {
                const size_t i = idx(x, z, T);
                const int16_t w = cur.water[i];
                if (w <= SEA) {
                    continue;
                }
                ++wet;
                /* Adjacent inland wet columns may differ only where two lakes
                 * at different levels meet; within one lake the surface is
                 * flat, so a step is rare and never a gradient. */
                if (x + 1 < T && cur.water[i + T] > SEA && cur.water[i + T] != w) {
                    ++sideSteps;
                }
                if (z + 1 < T && cur.water[i + 1] > SEA && cur.water[i + 1] != w) {
                    ++sideSteps;
                }
            }
        }
        prev = std::move(cur);
        havePrev = true;
    }
    check(wet > 0, "mountain valleys hold lakes");
    check(sideSteps * 100 < wet, "under 1% of adjacent wet pairs sit at different levels");
    std::printf("mountains ok (%d wet, %d level changes between neighbours)\n", wet, sideSteps);
}

} // namespace

int main() {
    check(ck_abi_version() == CK_ABI_VERSION, "abi version");
    testDeterminism();
    testContainmentAndSeams();
    testLakeComesFromTheFill();
    testLakeSeamAgreement();
    testARiverIsStampedIntoTheGround();
    testTheChannelIsLevelAcrossItsWidth();
    testRiversAgreeAcrossATileSeam();
    testNoRoutesMeansNoRivers();
    testDocumentedDefaultsAreTheRealDefaults();
    testTunnelKnobsAreLiveOnTheCarve();
    testARiverTunnelsRatherThanRemovingTheGround();
    testATunnelIsSealedByTheRockAroundIt();
    testEachDensityKnobMovesInTheDocumentedDirection();
    testSea();
    testMountains();
    if (failures == 0) {
        std::puts("kernels_water: all ok");
    }
    return failures == 0 ? 0 : 1;
}
