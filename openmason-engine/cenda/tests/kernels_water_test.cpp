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
#include <deque>
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

/* The same bowl with the block-scale detail the coarse DEM never sees.
 *
 * Not decoration, and not a stand-in for "noise": it is the actual relationship
 * between the two fields the lake stamp compares. The bridge fetches the coarse
 * DEM with `scale=1`, which never runs the upsampler that adds slope-scaled
 * Perlin, while a block tile is fetched at `scale=2` and does. So the coarse
 * surface is SMOOTH and the block surface wobbles a few blocks either side of
 * it — and a fixture whose two fields agree (every bowl test before this one)
 * cannot see a shoreline defect at all, which is why the plan's "zero raised
 * columns" measurement was true and meant nothing.
 *
 * +-3 blocks on a 0.12-per-block cone moves the real waterline up to ~25 blocks
 * in or out, comfortably past the 8 blocks of dilation the cell mask allows. */
int16_t bowlDetailAt(int64_t x, int64_t z, int64_t bx, int64_t bz) {
    return static_cast<int16_t>(
        bowlTerrainAt(x, z, bx, bz)
        + 3.0 * std::sin(static_cast<double>(x) * 0.07 + 0.3)
              * std::cos(static_cast<double>(z) * 0.061));
}

/* The bowl with a narrow slot cut clean through its rim, 14 blocks below the
 * water it will hold. A 16x16 mean barely registers a slot this narrow, so the
 * fill fills the bowl as if the rim were whole — which is exactly the case the
 * shore flood must NOT chase, or it runs a tongue of water down the outside. */
int16_t notchedBowlAt(int64_t x, int64_t z, int64_t bx, int64_t bz) {
    const int16_t h = bowlTerrainAt(x, z, bx, bz);
    /* One block wide and open-ended. Both matter: a 16x16 mean drops by under
     * a block, so the fill's spill barely moves and the lake still fills to
     * nearly the smooth rim; and a trench with a far END would be a depression
     * the fill is RIGHT to fill, which is not the case under test. */
    if (z == bz && x > bx + 80) {
        return static_cast<int16_t>(h - 14);
    }
    return h;
}

/* Two bowls 220 blocks apart with a 20-block step of ground between them, so
 * the second holds its water well below the first's spill: §5.2's case where a
 * lake is perched over another one and the two must be joined. */
int16_t twoBowlsAt(int64_t x, int64_t z, int64_t bx, int64_t bz) {
    double base = 500.0 - 0.02 * static_cast<double>(x);
    if (x > bx + 110) {
        base -= 20.0;
    }
    const auto dip = [&](int64_t cx, int64_t cz) {
        const double dx = static_cast<double>(x - cx);
        const double dz = static_cast<double>(z - cz);
        const double r = std::sqrt(dx * dx + dz * dz);
        const double R = 100.0;
        if (r < R) {
            base -= 24.0 * (1.0 - r / R);
        }
    };
    dip(bx, bz);
    dip(bx + 220, bz);
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

/* The full-array form: what the shipping caller passes is a prefix, but a test
 * that has to move a planner knob past [4] needs the whole thing. */
template <typename F>
Region solveRegionWithParams(int64_t regionX, int64_t regionZ, F&& terrain,
                             int64_t seed, const float* params, int32_t nParams) {
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
    r.withheld = ck_solve_basins(seed, REGION_BLOCKS, HALO_BLOCKS,
                                 REGION_CELLS, CELL,
                                 r.originX, r.originZ, dem.data(), params, nParams,
                                 0, 0, 0, 0, nullptr, nullptr,
                                 r.filled.data(), r.depth.data(),
                                 MAX_ROUTES, MAX_VERTS,
                                 r.starts.data(), r.verts.data(), counts, nullptr);
    check(r.withheld >= 0, "ck_solve_basins returned a basin count");
    r.routeCount = counts[0];
    return r;
}

template <typename F>
Region solveRegion(int64_t regionX, int64_t regionZ, F&& terrain,
                   int64_t seed = 1, float keepFraction = 0.0f) {
    const float params[] = {0.5f, 8.0f, static_cast<float>(SEA), 8.0f, keepFraction};
    return solveRegionWithParams(regionX, regionZ, terrain, seed, params, 5);
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
     * a step along the flow, never a tilt across it.
     *
     * With §5.8b's step-pool surface a slice may also STRADDLE a step: the
     * river is flat either side of a pool boundary and drops vertically across
     * it, and a row that cuts that boundary diagonally reads both levels. That
     * is the one legitimate way to span more than a block, and it looks
     * nothing like a tilt — it is exactly TWO levels, each in one contiguous
     * run. A tilt is a gradient: three or more levels, or two that interleave.
     * So a spanning slice is admitted only in that shape, and counted. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        return;
    }
    /* The widest a single channel's slice can be: `w_base + w_lake*log1p +
     * w_dist*sqrt` saturates near fifteen blocks, `plunge_widen` takes it to
     * twenty-four, and a diagonal row cuts a little more. Past that, a wet run
     * holds more than one channel. */
    constexpr int MAX_CHANNEL_SLICE = 28;
    constexpr int JUNCTION_SPAN = 6;
    int slices = 0;
    int stepped = 0;
    int straddles = 0;
    int junctions = 0;
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
                int runs = 0;           /* maximal constant-level runs        */
                int16_t prev = -1;
                bool twoLevels = true;  /* never a third distinct level       */
                int16_t first = tile.water[idx(x, z, T)];
                int16_t second = -1;
                while (z < T && tile.water[idx(x, z, T)] > SEA) {
                    const int16_t w = tile.water[idx(x, z, T)];
                    lo = std::min(lo, w);
                    hi = std::max(hi, w);
                    if (w != prev) {
                        ++runs;
                        prev = w;
                    }
                    if (w != first) {
                        if (second < 0) {
                            second = w;
                        } else if (w != second) {
                            twoLevels = false;
                        }
                    }
                    ++width;
                    ++z;
                }
                if (width >= 3) {
                    ++slices;
                    if (hi - lo > 1) {
                        /* A step: two levels, two runs. Anything else tilts. */
                        if (twoLevels && runs <= 2) {
                            ++straddles;
                        } else if (width > MAX_CHANNEL_SLICE && hi - lo <= JUNCTION_SPAN) {
                            /* Wider than any one channel can be: this is a
                             * BIFURCATION, where a distributary is still
                             * merged with the trunk it left and each carries
                             * its own level. Measured on this fixture: 35 to
                             * 40 columns across, three or four levels, three
                             * blocks top to bottom. A tilt inside a single
                             * channel cannot look like that — it has nowhere
                             * near the width — so admitting it here does not
                             * blunt the rule that matters. */
                            ++junctions;
                        } else {
                            ++stepped;
                        }
                    }
                }
            }
        }
    }
    check(slices > 0, "level cross-section: found channel slices to check");
    check(stepped == 0, "no channel slice spans more than one block of surface");
    std::printf("level cross-section ok (%d slices, %d tilted, %d straddling a step, "
                "%d at a bifurcation)\n", slices, stepped, straddles, junctions);
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
const float DOCUMENTED_DEFAULTS[35] = {
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
    64.0f,     /* 26 lake_shore_reach    */
    8.0f,      /* 27 lake_shore_max_depth*/
    128.0f,    /* 28 lake_link_reach     */
    0.65f,     /* 29 lake_bank_slope     */
    12.0f,     /* 30 lake_bank_reach     */
    12.0f,     /* 31 river_guard_reach   */
    3.0f,      /* 32 pool_max_drop       */
    512.0f,    /* 33 pool_max_run        */
    1.8f,      /* 34 plunge_deepen       */
};

/* Mirrors DOCUMENTED_DEFAULTS[25]; the tests below assert against the lid the
 * kernel promises to leave, so the two must not drift. */
constexpr int TUNNEL_MIN_ROOF = 4;
/* The carve's portal constants, mirrored: within PORTAL_CLEARANCE blocks of a
 * tunnel mouth the lid is allowed to thin to PORTAL_MIN_ROOF so the passage
 * opens toward daylight instead of pinching out. See `portalNearness`. */
constexpr int PORTAL_CLEARANCE = 10;
constexpr int PORTAL_MIN_ROOF = 2;

/** Solve one region with an explicit params array. */
template <typename F = int16_t (*)(int64_t, int64_t)>
Region solveWithParams(const float* params, int32_t n,
                       F&& terrain = riverTerrainAt, int64_t seed = 31337) {
    Region r;
    r.originX = -2048;
    r.originZ = -2048;
    std::vector<float> dem(static_cast<size_t>(REGION_CELLS) * REGION_CELLS);
    for (int32_t i = 0; i < REGION_CELLS; ++i) {
        for (int32_t j = 0; j < REGION_CELLS; ++j) {
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
    r.withheld = ck_solve_basins(seed, REGION_BLOCKS, HALO_BLOCKS,
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
    const Region spelled = solveWithParams(DOCUMENTED_DEFAULTS, 32);

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
                   DOCUMENTED_DEFAULTS, 32, outH.data(), outW.data(),
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
    bool thinLidsAreAllPortals = true;
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
                    /* Wet: the river itself. Dry: the bulge beside it, which is
                     * air above a stone lip and holds no water at all. */
                    check(tile.water[i] > tile.floor[i]
                              || (tile.water[i] < 0 && tile.roof[i] > tile.floor[i] + 1),
                          "and water standing on its floor, or air over a dry lip");
                    const int lid = tile.heights[i] - tile.roof[i];
                    thinnestLid = std::min(thinnestLid, lid);
                    /* `rawH` is the ground and the roof is under it, so
                     * `rawH - roof` IS how much rock the column carries; a lid
                     * under the ordinary bound is only legitimate where the
                     * ground itself runs out within a portal's fade. */
                    if (lid < TUNNEL_MIN_ROOF
                            && rawH - tile.floor[i] > PORTAL_CLEARANCE + TUNNEL_MIN_ROOF) {
                        thinLidsAreAllPortals = false;
                    }
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
    /* The lid thins at a MOUTH, on purpose: a passage clamped to a full
     * `tunnel_min_roof` all the way to daylight pinches shut exactly where a
     * player sees it, which is what made river tunnels read as holes punched
     * in a flat face. Within PORTAL_CLEARANCE of an opening the carve lets the
     * brow come down to PORTAL_MIN_ROOF and the vault rise to meet it. Away
     * from one the old bound stands, and that is what is checked here: a thin
     * lid is only ever allowed where there is not much ground to be under. */
    check(thinnestLid >= PORTAL_MIN_ROOF,
          "no tunnel is roofed by less than a portal brow");
    check(thinLidsAreAllPortals,
          "and a lid thinner than tunnel_min_roof only happens at a mouth");
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
                if (t.roof[i] < 0 || t.water[i] < 0) {
                    continue;   /* a dry bulge holds nothing to leak */
                }
                const int waterTop = std::min<int>(t.water[i], t.roof[i]);
                const size_t nb[4] = {i - static_cast<size_t>(T), i + static_cast<size_t>(T),
                                      i - 1, i + 1};
                for (size_t n : nb) {
                    ++checked;
                    /* A tunnel neighbour seals only if it is wet too, or is a
                     * dry bulge whose lip stands at the top water block: the
                     * bulge is air, and air below the surface is a spring. */
                    const bool tunnelSeals = t.roof[n] >= 0
                        && (t.water[n] >= 0 || t.floor[n] >= waterTop - 1);
                    const bool sealed = t.heights[n] >= waterTop
                                        || tunnelSeals
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
    float p[31];
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
    const Region base = solveWithParams(p, 31);

    p[4] = 0.0f;   /* river_keep_fraction */
    check(solveWithParams(p, 31).routeCount == 0, "[4] keep fraction 0 plans no rivers");
    p[4] = 1.0f;
    const Region all = solveWithParams(p, 31);
    check(all.routeCount >= base.routeCount, "[4] keep fraction 1 plans at least as many");
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);

    p[3] = 100000.0f;  /* min_river_lake_area */
    check(solveWithParams(p, 31).routeCount == 0, "[3] an impossible area gate plans no rivers");
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);

    p[0] = 10000.0f;   /* min_lake_depth */
    const Region dry = solveWithParams(p, 31);
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

/* ── The shoreline ──────────────────────────────────────────────────────── */

/* Columns the stamp raised above the terrain it was handed.
 *
 * A river only ever lowers ground, so the two things that raise it are the
 * containment wall and the bank skirt that grades away from it. A caller that
 * means WALLS — the shore tests do — must pass `lake_bank_reach` 0, or it is
 * measuring the skirt as well and the number stops being a defect count. */
template <typename F>
int walledColumns(const Tile& t, F&& terrain, int64_t tileX, int64_t tileZ) {
    int n = 0;
    for (int x = 0; x < T; ++x) {
        for (int z = 0; z < T; ++z) {
            if (t.heights[idx(x, z, T)] > terrain(tileX * T + x, tileZ * T + z)) {
                ++n;
            }
        }
    }
    return n;
}

/* The furthest any wet column in `b` sits from the nearest wet column in `a`,
 * in 4-connected steps.
 *
 * This is the lattice, measured. A cell mask can put water at most 8 blocks
 * past a lake CELL's centre — that is the whole of the "shoreline tolerance the
 * coarse lattice owes the block grid" — so against the mask-only stamp this
 * number is bounded by 8 no matter what the ground does. Anything above 8 is
 * shore the cell grid could not have drawn. */
int reachPastMask(const Tile& a, const Tile& b) {
    std::vector<int> dist(static_cast<size_t>(T) * T, -1);
    std::vector<int> ring;
    std::vector<int> next;
    for (int i = 0; i < T * T; ++i) {
        if (a.water[static_cast<size_t>(i)] >= 0) {
            dist[static_cast<size_t>(i)] = 0;
            ring.push_back(i);
        }
    }
    int furthest = 0;
    for (int step = 1; !ring.empty(); ++step) {
        next.clear();
        for (int ci : ring) {
            const int cx = ci / T;
            const int cz = ci % T;
            const int nbx[4] = {cx - 1, cx + 1, cx, cx};
            const int nbz[4] = {cz, cz, cz - 1, cz + 1};
            for (int k = 0; k < 4; ++k) {
                if (nbx[k] < 0 || nbx[k] >= T || nbz[k] < 0 || nbz[k] >= T) {
                    continue;
                }
                const size_t ni = idx(nbx[k], nbz[k], T);
                if (dist[ni] >= 0 || b.water[ni] < 0) {
                    continue;
                }
                dist[ni] = step;
                furthest = std::max(furthest, step);
                next.push_back(static_cast<int>(ni));
            }
        }
        ring.swap(next);
    }
    return furthest;
}

/* The tallest drop from ground the stamp RAISED to the ground beside it.
 *
 * The artifact's own height, with the fixture's natural relief excluded — a
 * plain "steepest step near the water" counts the cliffs a fixture was built
 * with and cannot tell a wall from a canyon wall. A containment wall stands at
 * the waterline with whatever was under it still under it, so before the skirt
 * this is the full height of the cliff; after it, it is one tread of the ramp.
 * Wet neighbours are excluded at the far end: ground dropping to a water
 * surface is a shore, not a cliff. */
template <typename F>
int maxRaisedDrop(const Tile& t, F&& terrain, int64_t tileX, int64_t tileZ) {
    int worst = 0;
    for (int x = 0; x < T; ++x) {
        for (int z = 0; z < T; ++z) {
            const size_t i = idx(x, z, T);
            if (t.heights[i] <= terrain(tileX * T + x, tileZ * T + z)) {
                continue;
            }
            const int nbx[4] = {x - 1, x + 1, x, x};
            const int nbz[4] = {z, z, z - 1, z + 1};
            for (int k = 0; k < 4; ++k) {
                if (nbx[k] < 0 || nbx[k] >= T || nbz[k] < 0 || nbz[k] >= T) {
                    continue;
                }
                const size_t n = idx(nbx[k], nbz[k], T);
                if (t.water[n] >= 0) {
                    continue;
                }
                worst = std::max(worst, t.heights[i] - t.heights[n]);
            }
        }
    }
    return worst;
}

int wetColumns(const Tile& t) {
    int n = 0;
    for (size_t i = 0; i < t.water.size(); ++i) {
        if (t.water[i] >= 0) {
            ++n;
        }
    }
    return n;
}

void testTheShoreFollowsTheGroundNotTheCellLattice() {
    /* The DEM is solved on the SMOOTH bowl and the tile is stamped from the
     * detailed one — the bridge's own arrangement, and the whole defect. */
    auto smooth = [](int64_t x, int64_t z) { return bowlTerrainAt(x, z, 2048, 2048); };
    auto detailed = [](int64_t x, int64_t z) { return bowlDetailAt(x, z, 2048, 2048); };
    const Region r = solveRegion(0, 0, smooth);
    check(r.withheld == 0, "L1 owns the bowl");

    /* The skirt is off for both: `walledColumns` counts everything the stamp
     * raises, and what is under test here is how much shoreline the flood
     * leaves to WALL. The skirt's own effect on those walls is
     * `testTheBankBrushSmoothsTheSpillLip`. */
    float noBank[31];
    std::memcpy(noBank, DOCUMENTED_DEFAULTS, sizeof noBank);
    noBank[30] = 0.0f;
    float p[31];
    std::memcpy(p, noBank, sizeof p);
    p[26] = 0.0f;   /* reach 0 is exactly the old coarse-mask-only stamp */
    const Tile mask = runTile(3, 8, 8, detailed, &r, p, 31);
    const Tile flooded = runTile(3, 8, 8, detailed, &r, noBank, 31);

    const int maskWalls = walledColumns(mask, detailed, 8, 8);
    const int floodWalls = walledColumns(flooded, detailed, 8, 8);
    const int past = reachPastMask(mask, flooded);

    /* 1. The fixture really does exhibit the defect. Without this the rest of
     *    the test could pass on a fixture where nothing was ever wrong. */
    check(maskWalls > 50, "the cell mask walls a great deal of shoreline");

    /* 2. And the flood removes it. Not "reduces": a dry column beside water is
     *    dry because the ground there is at or above the level, which is what
     *    the repair being idle means. */
    /*    What is left is the spill lip and nothing else: a handful of columns
     *    in one cluster where the fill says the ground is already downstream,
     *    which is the one place a lake without an outlet river HAS to be
     *    walled. It is bounded by a cell, against a mask artifact that ran the
     *    length of the shoreline. */
    check(floodWalls <= CELL, "the flood leaves only the spill lip to wall");
    check(floodWalls * 10 < maskWalls, "which is a different order of thing");
    check(past > CELL / 2, "and the shore reaches past anything the cell grid could draw");

    /* 3. It widened the lake rather than moving it. */
    check(wetColumns(flooded) > wetColumns(mask), "the flood reaches ground the mask cut off");

    /* 4. The property this must not cost: one level for the whole basin. */
    int16_t level = -1;
    int levels = 0;
    for (size_t i = 0; i < flooded.water.size(); ++i) {
        if (flooded.water[i] < 0) {
            continue;
        }
        if (flooded.water[i] != level) {
            level = flooded.water[i];
            ++levels;
        }
    }
    check(levels <= 1 || level > 0, "the surface is still one integer");
    bool flat = true;
    int16_t first = -1;
    for (size_t i = 0; i < flooded.water.size(); ++i) {
        if (flooded.water[i] < 0) {
            continue;
        }
        if (first < 0) {
            first = flooded.water[i];
        } else if (flooded.water[i] != first) {
            flat = false;
        }
    }
    check(flat, "and it is the same integer everywhere");

    std::printf("shore ok (walls %d -> %d, wet %d -> %d, %d blocks past the mask)\n",
                maskWalls, floodWalls, wetColumns(mask), wetColumns(flooded), past);
}

void testTheShoreFloodRefusesADeepNotch() {
    /* The fill fills the bowl as though the rim were whole, so the slot sits 14
     * blocks under the water surface with dry ground all around it. The flood
     * may repair a shoreline; it may not discover a basin, because discovering
     * one needs the fill's window and not this tile's. */
    auto notched = [](int64_t x, int64_t z) { return notchedBowlAt(x, z, 2048, 2048); };
    const Region r = solveRegion(0, 0, notched);
    const Tile t = runTile(3, 8, 8, notched, &r, DOCUMENTED_DEFAULTS, 31);

    int outsideRim = 0;
    for (int x = 0; x < T; ++x) {
        for (int z = 0; z < T; ++z) {
            const int16_t w = t.water[idx(x, z, T)];
            if (w < 0) {
                continue;
            }
            const int64_t wx = 8 * T + x;
            const int64_t wz = 8 * T + z;
            const double dx = static_cast<double>(wx - 2048);
            const double dz = static_cast<double>(wz - 2048);
            if (std::sqrt(dx * dx + dz * dz) > 230.0) {
                ++outsideRim;
            }
        }
    }
    check(outsideRim == 0, "the flood does not run down a notch the fill never saw");
    /* And it passed because the flood stopped, not because there was no lake:
     * a fixture that came out dry would satisfy the line above for free. */
    check(wetColumns(t) > 10000, "and the bowl still holds its lake");
    std::printf("notch ok (%d wet in the bowl, %d outside the rim)\n",
                wetColumns(t), outsideRim);
}

void testTheShoreAgreesAcrossATileSeam() {
    /* Two tiles that share the bowl's +X edge, each stamped from its own 3x3
     * window. The flood travels at most `reach`, and the caller clamps that to
     * T-1, so the facing columns must match at any legal reach. */
    auto smooth = [](int64_t x, int64_t z) { return bowlTerrainAt(x, z, 2200, 2048); };
    auto detailed = [](int64_t x, int64_t z) { return bowlDetailAt(x, z, 2200, 2048); };
    const Region r = solveRegion(0, 0, smooth);

    const float reaches[] = {64.0f, static_cast<float>(T - 1), 4096.0f};
    for (float reach : reaches) {
        float p[31];
        std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
        p[26] = reach;
        const Tile a = runTile(3, 8, 8, detailed, &r, p, 31);
        const Tile b = runTile(3, 9, 8, detailed, &r, p, 31);
        int mismatched = 0;
        int wetPairs = 0;
        for (int z = 0; z < T; ++z) {
            const int16_t wa = a.water[idx(T - 1, z, T)];
            const int16_t wb = b.water[idx(0, z, T)];
            if (wa >= 0 || wb >= 0) {
                ++wetPairs;
            }
            /* Not "equal": they are different columns. The invariant is that
             * neither stands dry below the other's water — the seam form of
             * containment, and the thing a truncated flood would break. */
            if (wa >= 0 && wb < 0 && b.heights[idx(0, z, T)] < wa) {
                ++mismatched;
            }
            if (wb >= 0 && wa < 0 && a.heights[idx(T - 1, z, T)] < wb) {
                ++mismatched;
            }
            if (wa >= 0 && wb >= 0 && wa != wb) {
                ++mismatched;
            }
        }
        check(mismatched == 0, "the two tiles draw the same shore at their seam");
        check(wetPairs > 0, "and the seam actually crosses the lake");
    }
    std::puts("shore seam ok");
}

/* ── The bank skirt ─────────────────────────────────────────────────────── */

/** The documented defaults with the skirt switched off: the pre-brush stamp. */
void bankOff(float (&p)[31]) {
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
    p[30] = 0.0f;
}

void testTheBankBrushGradesAWallIntoTheGround() {
    /* The notched bowl, because it is the fixture that still HAS a wall. The
     * flood is right to refuse a slot fourteen blocks under the surface — that
     * is a basin to discover, not a shoreline to repair — so it walls it, and
     * what the wall then needs is not to be a fourteen-block cliff. */
    auto notched = [](int64_t x, int64_t z) { return notchedBowlAt(x, z, 2048, 2100); };
    const Region r = solveRegion(0, 0, notched);

    float off[31];
    bankOff(off);
    const Tile plain = runTile(3, 8, 8, notched, &r, off, 31);
    const Tile brushed = runTile(3, 8, 8, notched, &r, DOCUMENTED_DEFAULTS, 31);

    const int walls = walledColumns(plain, notched, 8, 8);
    const int raised = walledColumns(brushed, notched, 8, 8);
    const int dropPlain = maxRaisedDrop(plain, notched, 8, 8);
    const int dropBrushed = maxRaisedDrop(brushed, notched, 8, 8);

    /* The same tile with reach enough for the whole wall. At the defaults the
     * skirt has 0.65 * 12 = 8 blocks of fall to spend and this wall is taller
     * than that, on purpose — the budget is tied to `lake_shore_max_depth` so
     * that a wall the flood WANTED is graded to nothing and a rim it refused
     * is only taken down, not landscaped away. Doubling the reach puts this
     * wall inside the budget and shows the other half of that rule. */
    float wide[31];
    std::memcpy(wide, DOCUMENTED_DEFAULTS, sizeof wide);
    wide[30] = 64.0f;
    const Tile fully = runTile(3, 8, 8, notched, &r, wide, 31);
    const int dropWide = maxRaisedDrop(fully, notched, 8, 8);

    /* 1. There is still something to blend. Without this the rest passes on a
     *    tile the flood already wetted clean, which proves nothing. */
    check(walls > 0, "the flood leaves a wall the skirt has something to do with");

    /* 2. The skirt grades ground away from it — more columns raised, by less. */
    check(raised > walls, "and the skirt grades ground away from that wall");

    /* 3. Which is the whole point: the cliff comes down, by the budget the
     *    slope and the reach agree on. */
    check(dropBrushed <= dropPlain - 6, "so the cliff comes down by most of the budget");

    /* 4. And given reach for all of it, down to one LEDGE. The ramp falls at
     *    the angle of repose and `strataStep` gathers that fall into bedding
     *    planes, so a fully graded wall no longer ends in a quarter-block step:
     *    it ends in a bed's riser, `STRATA_BAND * (1 - 2 * STRATA_TREAD)`
     *    spread over the column and a bit of repose the riser sits on. Three
     *    blocks, and still well under a bed — a fully graded wall is a flight
     *    of ledges, never a cliff. */
    check(dropWide <= 3, "and a reach that covers the wall grades it down to ledges");

    std::printf("bank ok (walls %d, raised %d, tallest drop off raised ground"
                " %d -> %d, %d at reach 64)\n",
                walls, raised, dropPlain, dropBrushed, dropWide);
}

void testTheBankBrushNeverWetsNorLowersNorRaisesWater() {
    /* The four things a raise-only brush may not do to a tile. Run on both
     * shore fixtures: the plain bowl is where the flood leaves nothing to
     * grade and the brush must be inert, the notched one is where it fires. */
    auto smooth = [](int64_t x, int64_t z) { return bowlTerrainAt(x, z, 2048, 2048); };
    auto detailed = [](int64_t x, int64_t z) { return bowlDetailAt(x, z, 2048, 2048); };
    auto notched = [](int64_t x, int64_t z) { return notchedBowlAt(x, z, 2048, 2100); };

    float off[31];
    bankOff(off);
    int raises = 0;
    for (int fixture = 0; fixture < 2; ++fixture) {
        const Region r = fixture == 0 ? solveRegion(0, 0, smooth) : solveRegion(0, 0, notched);
        const Tile plain = fixture == 0 ? runTile(3, 8, 8, detailed, &r, off, 31)
                                        : runTile(3, 8, 8, notched, &r, off, 31);
        const Tile brushed = fixture == 0
            ? runTile(3, 8, 8, detailed, &r, DOCUMENTED_DEFAULTS, 31)
            : runTile(3, 8, 8, notched, &r, DOCUMENTED_DEFAULTS, 31);

        /* The skirt's ceiling is the crest it fell from, and every crest is a
         * water level, so no column it raises may stand above the highest
         * water on the tile. Looser than the per-wall bound and checkable
         * without re-deriving which wall fed which column. */
        int16_t highest = -1;
        for (size_t i = 0; i < brushed.water.size(); ++i) {
            highest = std::max(highest, brushed.water[i]);
        }

        int wetChanged = 0;
        int lowered = 0;
        int wetMoved = 0;
        int overCrest = 0;
        int voidMoved = 0;
        for (size_t i = 0; i < plain.heights.size(); ++i) {
            if (brushed.water[i] != plain.water[i]) {
                ++wetChanged;
            }
            if (brushed.heights[i] < plain.heights[i]) {
                ++lowered;
            }
            if (plain.water[i] >= 0 && brushed.heights[i] != plain.heights[i]) {
                ++wetMoved;
            }
            if (brushed.floor[i] != plain.floor[i] || brushed.roof[i] != plain.roof[i]) {
                ++voidMoved;
            }
            if (brushed.heights[i] > plain.heights[i]) {
                ++raises;
                if (brushed.heights[i] > highest) {
                    ++overCrest;
                }
            }
        }
        check(wetChanged == 0, "the skirt does not wet or dry a single column");
        check(lowered == 0, "and it never lowers ground");
        check(wetMoved == 0, "and it never raises a wet column");
        check(overCrest == 0, "and it never stands above the water it banks");
        check(voidMoved == 0, "and it leaves every river tunnel where it was");
    }
    check(raises > 0, "and the fixtures actually exercised it");
    std::printf("bank invariants ok (%d raised columns checked)\n", raises);
}

void testTheBankSkirtAgreesAcrossATileSeam() {
    /* The test that would have caught seeding the skirt from only the water a
     * tile already stamps. The notch leaves this bowl's mask within a few
     * blocks of the 8|9 tile edge, so the wall sits on one side of the seam
     * and its ramp runs out across the other. Tile A has to stamp B's water to
     * draw that ramp; if it does not, A stops at the seam and B does not, and
     * the two leave a step along a straight tile-aligned line. */
    auto notched = [](int64_t x, int64_t z) { return notchedBowlAt(x, z, 2104, 2100); };
    const Region r = solveRegion(0, 0, notched);

    const float reaches[] = {8.0f, 32.0f, static_cast<float>(T - 1)};
    for (float reach : reaches) {
        float p[31];
        std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
        p[30] = reach;
        const Tile a = runTile(3, 8, 8, notched, &r, p, 31);
        const Tile b = runTile(3, 9, 8, notched, &r, p, 31);
        int worst = 0;
        int graded = 0;
        for (int z = 0; z < T; ++z) {
            const size_t ia = idx(T - 1, z, T);
            const size_t ib = idx(0, z, T);
            if (a.water[ia] >= 0 || b.water[ib] >= 0) {
                continue;   /* the shore seam test owns the wet columns */
            }
            if (a.heights[ia] > notched(8 * T + T - 1, 8 * T + z)
                    || b.heights[ib] > notched(9 * T, 8 * T + z)) {
                ++graded;
            }
            /* Adjacent columns of one continuous ramp. The ramp falls at the
             * angle of repose and `strataStep` gathers that fall into bedding
             * planes, so one column of it is at most a bed's riser — measured
             * on this seam, 255 of 256 columns agree exactly and one differs
             * by a riser. Anything taller is one side grading and the other
             * not, which is the failure this exists to catch. */
            worst = std::max(worst, std::abs(a.heights[ia] - b.heights[ib]));
        }
        check(worst <= 2, "the two tiles draw one continuous bank at their seam");
        check(reach < 32.0f || graded > 0, "and the seam really is inside a skirt");
    }
    std::puts("bank seam ok");
}

void testTheBankBrushIsMonotoneInItsReach() {
    /* Reach 0 is the pre-brush stamp exactly — it collapses the stamp range
     * back to the tile plus §3's ring — so this sweep doubles as the proof
     * that widening that range changed nothing on its own. */
    auto notched = [](int64_t x, int64_t z) { return notchedBowlAt(x, z, 2048, 2100); };
    const Region r = solveRegion(0, 0, notched);

    const float reaches[] = {0.0f, 8.0f, 16.0f, 32.0f};
    Tile prev;
    int prevDrop = 0;
    int firstRaised = 0;
    int lastRaised = 0;
    for (size_t k = 0; k < sizeof reaches / sizeof *reaches; ++k) {
        float p[31];
        std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
        p[30] = reaches[k];
        const Tile t = runTile(3, 8, 8, notched, &r, p, 31);
        const int drop = maxRaisedDrop(t, notched, 8, 8);
        lastRaised = walledColumns(t, notched, 8, 8);
        if (k == 0) {
            firstRaised = lastRaised;
        } else {
            int lowered = 0;
            int moved = 0;
            for (size_t i = 0; i < t.heights.size(); ++i) {
                if (t.heights[i] < prev.heights[i]) {
                    ++lowered;
                }
                if (t.water[i] != prev.water[i] || t.floor[i] != prev.floor[i]
                        || t.roof[i] != prev.roof[i]) {
                    ++moved;
                }
            }
            check(lowered == 0, "a longer reach only ever raises more ground");
            check(moved == 0, "and moves no water and no tunnel");
            check(drop <= prevDrop, "and never leaves the wall steeper than it was");
        }
        prev = t;
        prevDrop = drop;
    }
    /* And the knob is live: everything above would pass on a dead one. */
    check(lastRaised > firstRaised, "the reach is a knob and not a comment");
    std::printf("bank reach knob ok (%d raised at reach 0, %d at 32)\n",
                firstRaised, lastRaised);
}

void testTheSeaShorelineIsNotBrushed() {
    /* §2 gives a column sea water only where `carved < sea_level`, so a DRY
     * coastal column already stands at or above the sea and the containment
     * repair is idle there. No wall, therefore no seed, therefore no skirt —
     * without a single line testing for the sea. Non-obvious enough to pin. */
    const Region r = solveRegion(-1, 0, terrainAt);
    float off[31];
    bankOff(off);
    const Tile plain = runTile(9, -3, 0, terrainAt, &r, off, 31);
    const Tile brushed = runTile(9, -3, 0, terrainAt, &r, DOCUMENTED_DEFAULTS, 31);
    int coastal = 0;
    for (size_t i = 0; i < plain.heights.size(); ++i) {
        if (plain.water[i] == SEA) {
            ++coastal;
        }
    }
    check(coastal > 0, "the tile really is coastal");
    check(std::memcmp(plain.heights.data(), brushed.heights.data(),
                      plain.heights.size() * sizeof(int16_t)) == 0,
          "the sea walls nothing, so the skirt has nothing to grade");
    std::printf("sea unbrushed ok (%d sea columns)\n", coastal);
}

void testTheBankBrushDoesNotDamAnOutlet() {
    /* The failure a raise-only brush invites: banking a lake by filling in the
     * river that drains it. Wet columns are barriers, so the outlet keeps both
     * its water and its bed. */
    auto two = [](int64_t x, int64_t z) { return twoBowlsAt(x, z, 1400, 2048); };
    float p[31];
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
    p[3] = 100000.0f;   /* min_river_lake_area: only the link rule may plan */
    p[4] = 0.0f;        /* river_keep_fraction */
    const Region r = solveWithParams(p, 31, two);
    check(r.routeCount > 0, "the fixture has an outlet to dam");

    float off[31];
    std::memcpy(off, p, sizeof off);
    off[30] = 0.0f;
    const Tile plain = runTile(3, 5, 8, two, &r, off, 31);
    const Tile brushed = runTile(3, 5, 8, two, &r, p, 31);

    int wet = 0;
    int changed = 0;
    for (size_t i = 0; i < plain.water.size(); ++i) {
        if (plain.water[i] >= 0) {
            ++wet;
            if (brushed.water[i] != plain.water[i]
                    || brushed.heights[i] != plain.heights[i]) {
                ++changed;
            }
        }
    }
    check(wet > 0, "and the tile holds that water");
    check(changed == 0, "which the skirt neither fills nor floors");
    std::printf("outlet ok (%d wet columns, %d touched by the skirt)\n", wet, changed);
}

void testALakePerchedOverAnotherGetsAnOutlet() {
    /* Both gates are shut: keep fraction 0 rejects every basin, and the area
     * gate is set past anything the fixture holds. The only thing that can
     * plan a river here is the perched-lake rule. */
    auto two = [](int64_t x, int64_t z) { return twoBowlsAt(x, z, 1400, 2048); };
    float p[31];
    std::memcpy(p, DOCUMENTED_DEFAULTS, sizeof p);
    p[3] = 100000.0f;   /* min_river_lake_area */
    p[4] = 0.0f;        /* river_keep_fraction */

    p[28] = 0.0f;       /* lake_link_reach off */
    const Region off = solveWithParams(p, 31, two);
    check(off.routeCount == 0, "with the link rule off, two shut gates plan no rivers");

    p[28] = 128.0f;
    const Region on = solveWithParams(p, 31, two);
    check(on.routeCount > 0, "a lake with lower water in reach drains past both gates");

    /* It has to arrive somewhere, and over a 20-block step it has to fall. */
    bool falls = false;
    int longest = 0;
    for (int32_t v = 0; v < on.routeCount; ++v) {
        const int32_t from = on.starts[static_cast<size_t>(v)];
        const int32_t to = on.starts[static_cast<size_t>(v) + 1];
        longest = std::max(longest, to - from);
        for (int32_t i = from; i < to; ++i) {
            const auto flags = static_cast<int32_t>(
                on.verts[static_cast<size_t>(i) * CK_RIVER_VERTEX_FLOATS + 6]);
            if ((flags & CK_RIVER_FLAG_WATERFALL) != 0) {
                falls = true;
            }
        }
    }
    check(longest >= 4, "and it is a route, not an overflow lip");
    check(falls, "and the step between the two lakes is stamped as a waterfall");
    std::printf("lake link ok (%d routes, longest %d vertices, waterfall %s)\n",
                on.routeCount, longest, falls ? "yes" : "no");
}

/* The lip a wall leaves, measured from every wall column: how many dry columns
 * in a row, walking straight away from the water it holds, stand at the crest.
 * The crest is the wall's own height in the reach-0 tile — the guard rail, which
 * can stand above the water directly beside it.
 * The wall column counts, so a bare wall is 1. Capped at 5 — past the most
 * the profile can hold, so an overlong run is visible rather than truncated. */
template <typename F>
void lipHistogram(const Tile& off, const Tile& on, F&& terrain, int64_t tileX, int64_t tileZ,
                  int (&hist)[6]) {
    for (int x = 1; x < T - 1; ++x) {
        for (int z = 1; z < T - 1; ++z) {
            const size_t i = idx(x, z, T);
            /* A wall: dry, raised by §3 alone (the reach-0 tile), beside water. */
            if (off.water[i] >= 0 || off.heights[i] <= terrain(tileX * T + x, tileZ * T + z)) {
                continue;
            }
            const int dx[4] = {-1, 1, 0, 0};
            const int dz[4] = {0, 0, -1, 1};
            for (int k = 0; k < 4; ++k) {
                const size_t n = idx(x + dx[k], z + dz[k], T);
                if (on.water[n] < 0 || on.water[n] > off.heights[i]) {
                    continue;
                }
                /* Walk away from that water. */
                const int crest = off.heights[i];
                /* A taller rail's skirt nearby may lift this wall past its own
                 * crest; that is a bank doing its job, not a lip to measure. */
                if (on.heights[i] > crest) {
                    continue;
                }
                int run = 0;
                int cx = x;
                int cz = z;
                while (run < 5 && cx >= 0 && cx < T && cz >= 0 && cz < T
                       && on.water[idx(cx, cz, T)] < 0 && on.heights[idx(cx, cz, T)] == crest
                       && on.heights[idx(cx, cz, T)] > terrain(tileX * T + cx, tileZ * T + cz)) {
                    ++run;
                    cx -= dx[k];
                    cz -= dz[k];
                }
                ++hist[run];
            }
        }
    }
}

void testTheBankLipIsOneToThreeThickAndVaries() {
    /* The complaint this pins: a river one block above the ground beside it
     * was held back by a ridge one column wide, and a 1:4 ramp could not help
     * — the column past the crest floors to the ground that was already there.
     * The lip now holds the crest for a noisy one to three columns. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        check(false, "bank lip: the fixture plans a river");
        return;
    }
    float off[31];
    bankOff(off);
    int hist[6] = {0, 0, 0, 0, 0, 0};
    int widestLip = 0;
    constexpr int PROBE = 5;
    /* How far a taller wall's ramp can still stand level with a lower crest:
     * a full plateau, then a block of fall at the gentlest slope plus a block
     * of roughness — 2 + 2 / 0.25. */
    constexpr int RAMP = 10;
    for (int64_t tx = 2; tx <= 7; ++tx) {
        const Tile a = runTile(777, tx, 8, riverTerrainAt, &r, off, 31);
        const Tile b = runTile(777, tx, 8, riverTerrainAt, &r, DOCUMENTED_DEFAULTS, 31);
        lipHistogram(a, b, riverTerrainAt, tx, 8, hist);
        for (int x = RAMP; x < T - RAMP; ++x) {
            for (int z = RAMP; z < T - RAMP; ++z) {
                const size_t i = idx(x, z, T);
                if (b.water[i] >= 0 || b.heights[i] <= riverTerrainAt(tx * T + x, 8 * T + z)) {
                    continue;
                }
                /* The nearest wall with this column's crest — a wall being
                 * what the reach-0 tile raised — counting the wall as 1, i.e.
                 * the wall this column is a lip for. Measured from the wall
                 * and not from water at that level because the crest is the
                 * guard rail, which need not equal the water beside it. */
                int nearest = PROBE + 1;
                int topCrest = -1;
                for (int dx = -RAMP; dx <= RAMP; ++dx) {
                    for (int dz = -RAMP; dz <= RAMP; ++dz) {
                        const size_t w = idx(x + dx, z + dz, T);
                        if (a.water[w] < 0
                                && a.heights[w] > riverTerrainAt(tx * T + x + dx, 8 * T + z + dz)) {
                            topCrest = std::max<int>(topCrest, a.heights[w]);
                            if (a.heights[w] == b.heights[i]
                                    && std::max(std::abs(dx), std::abs(dz)) < PROBE) {
                                nearest = std::min(nearest, 1 + std::max(std::abs(dx), std::abs(dz)));
                            }
                        }
                    }
                }
                /* Only a column at the TALLEST crest in reach is a lip: one
                 * level with a lower wall may just be a taller wall's ramp. */
                if (nearest <= PROBE && b.heights[i] == topCrest) {
                    widestLip = std::max(widestLip, nearest);
                }
            }
        }
    }
    const int walls = hist[1] + hist[2] + hist[3] + hist[4] + hist[5];
    check(walls > 0, "bank lip: the river leaves walls to measure");
    check(hist[0] == 0, "every wall column still stands at its crest");
    check(hist[2] + hist[3] > 0, "some lips are thicker than the wall itself");
    check(hist[1] > 0 || hist[3] > 0, "and they are not all the same thickness");
    /* The upper bound, measured the way the skirt measures: not an axial walk,
     * which runs along a diagonal bank and counts the wall's own length, but
     * the 8-connected distance from each crest column to the water it banks.
     * Wall at 1, plateau of at most two past it: nothing at the crest past 3. */
    check(widestLip <= 3, "no lip is thicker than three columns");
    check(widestLip == 3, "and the noise really reaches three");
    std::printf("bank lip ok (%d walls: %d / %d / %d / %d+ along an axis, widest %d)\n",
                walls, hist[1], hist[2], hist[3], hist[4] + hist[5], widestLip);
}

void testTheTunnelVaultIsIrregular() {
    /* The vault used to be one parabola extruded along the route, so the air
     * over the water was the same height at every centreline column. Now it
     * swells and lumps, and a dry bulge widens the passage above the water. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        check(false, "vault: the fixture plans a river");
        return;
    }
    int lo = WH;
    int hi = -1;
    int bulges = 0;
    int badBulges = 0;
    for (int64_t tx = 3; tx <= 6; ++tx) {
      for (int64_t tz = 7; tz <= 9; ++tz) {
        const Tile t = runTile(777, tx, tz, ridgedRiverTerrainAt, &r);
        for (int x = 1; x < T - 1; ++x) {
            for (int z = 1; z < T - 1; ++z) {
                const size_t i = idx(x, z, T);
                if (t.roof[i] < 0) {
                    continue;
                }
                if (t.water[i] >= 0) {
                    /* Headroom where the roof is not clamped by the lid. */
                    if (t.roof[i] < t.heights[i] - TUNNEL_MIN_ROOF) {
                        lo = std::min(lo, t.roof[i] - t.water[i]);
                        hi = std::max(hi, t.roof[i] - t.water[i]);
                    }
                    continue;
                }
                ++bulges;
                /* A bulge is air: its lip must stand at the top water block of
                 * every wet neighbour, and its roof under a full lid. */
                const size_t nb[4] = {i - static_cast<size_t>(T), i + static_cast<size_t>(T),
                                      i - 1, i + 1};
                for (size_t n : nb) {
                    if (t.water[n] >= 0 && t.floor[i] < t.water[n] - 1) {
                        ++badBulges;
                    }
                }
                /* PORTAL_MIN_ROOF, not TUNNEL_MIN_ROOF: an alcove at a mouth
                 * is roofed by a brow, and the brow is the point. */
                if (t.roof[i] > t.heights[i] - PORTAL_MIN_ROOF) {
                    ++badBulges;
                }
            }
        }
      }
    }
    check(hi >= 0, "vault: there were tunnel columns to measure");
    check(hi - lo >= 3, "the headroom over the water varies along the tunnel");
    check(bulges > 0, "the passage bulges past the channel above the water");
    check(badBulges == 0, "and every bulge is air over a lip, under a lid");
    std::printf("vault ok (headroom %d..%d, %d bulge columns, %d bad)\n",
                lo, hi, bulges, badBulges);
}

void testATunnelStaysSealedAcrossATileSeam() {
    /* The tunnel seal test checks interior columns only; the noise that shapes
     * the vault and the bulge is a function of the world column, so the seam
     * has to come out as sealed as the interior. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        return;
    }
    int compared = 0;
    int leaks = 0;
    for (int64_t tx = 3; tx <= 5; ++tx) {
      for (int64_t tz = 7; tz <= 9; ++tz) {
        const Tile a = runTile(777, tx, tz, ridgedRiverTerrainAt, &r);
        const Tile b = runTile(777, tx + 1, tz, ridgedRiverTerrainAt, &r);
        for (int z = 0; z < T; ++z) {
            const size_t ia = idx(T - 1, z, T);
            const size_t ib = idx(0, z, T);
            const auto leaksInto = [&](const Tile& wet, size_t w, const Tile& dry, size_t d) {
                if (wet.water[w] < 0 || dry.water[d] >= 0 || dry.roof[d] < 0) {
                    return;
                }
                ++compared;
                if (dry.floor[d] < wet.water[w] - 1) {
                    ++leaks;
                }
            };
            leaksInto(a, ia, b, ib);
            leaksInto(b, ib, a, ia);
        }
      }
    }
    check(leaks == 0, "no bulge opens below the water across a tile seam");
    std::printf("tunnel seam ok (%d bulge columns on a seam, %d leaks)\n", compared, leaks);
}

/* ── The guard rail, judged by the sim that actually runs ─────────────────
 *
 * Every containment test above checks the planes against the STATIC rule, and
 * the static rule is not what floods a bank: WaterSim is. A river steps down a
 * block at a time, the step exposes water to air, the flowing layer runs a
 * block above the reach below, and the infinite-source rule promotes that
 * reach toward the level upstream. None of that is visible in the planes. */

/**
 * WaterSim (stonebreak-game blocks/waterSystem/WaterSim.java), rule for rule,
 * over one stamped tile turned into blocks the way TerrainGenerationSystem
 * turns them: the tunnel shell first, then solid below `height`, water below
 * the level, air above. No caves — this judges the planes, not Density3D.
 *
 * Deliberate differences, all pessimistic: every exposed water cell in the
 * tile is scheduled (the game only sees exposure inside one chunk), updates
 * run FIFO with no tick delay, and the whole tile counts as loaded while
 * everything past it does not — so water piles up at the tile edge rather
 * than leaving, which is why `escaped` ignores that ring.
 */
class FlowReplica {
public:
    explicit FlowReplica(const Tile& t) {
        int lo = WH;
        int hi = 0;
        for (size_t i = 0; i < t.water.size(); ++i) {
            if (t.water[i] < 0) {
                continue;
            }
            const int bed = t.floor[i] >= 0 ? std::min<int>(t.floor[i], t.heights[i]) : t.heights[i];
            lo = std::min(lo, bed - 1);
            hi = std::max<int>(hi, t.water[i] + 1);
        }
        y0_ = std::max(0, lo);
        ny_ = std::max(0, hi - y0_ + 1);
        block_.assign(static_cast<size_t>(ny_) * T * T, AIR);
        state_.assign(block_.size(), EMPTY);
        queued_.assign(block_.size(), 0);
        for (int x = 0; x < T; ++x) {
            for (int z = 0; z < T; ++z) {
                const size_t c = idx(x, z, T);
                const int h = t.heights[c];
                const int w = t.water[c];
                const int f = t.floor[c];
                const int r = t.roof[c];
                for (int y = y0_; y < y0_ + ny_; ++y) {
                    uint8_t b;
                    if (r > f && y >= f && y <= r) {
                        b = (y == f || y == r) ? SOLID : (y < w ? WATER : AIR);
                    } else if (y < h) {
                        b = SOLID;
                    } else {
                        b = y < w ? WATER : AIR;
                    }
                    const int32_t k = at(x, y, z);
                    block_[static_cast<size_t>(k)] = b;
                    if (b == WATER) {
                        state_[static_cast<size_t>(k)] = SOURCE;
                    }
                }
            }
        }
        /* WaterSim.onChunkLoaded: water with air below or beside it. */
        for (int y = y0_; y < y0_ + ny_; ++y) {
            for (int x = 0; x < T; ++x) {
                for (int z = 0; z < T; ++z) {
                    const int32_t k = at(x, y, z);
                    if (block_[static_cast<size_t>(k)] != WATER) {
                        continue;
                    }
                    if (isAir(x, y - 1, z) || isAir(x + 1, y, z) || isAir(x - 1, y, z)
                            || isAir(x, y, z + 1) || isAir(x, y, z - 1)) {
                        enqueue(k);
                    }
                }
            }
        }
    }

    /** Run to a fixed point; false if `budget` updates were not enough. */
    bool settle(long budget) {
        while (!queue_.empty()) {
            if (budget-- <= 0) {
                return false;
            }
            const int32_t k = queue_.front();
            queue_.pop_front();
            queued_[static_cast<size_t>(k)] = 0;
            update(k);
        }
        return true;
    }

    /** Water cells standing in columns the planes call dry, off the edge ring. */
    long escaped(const Tile& t) const {
        long n = 0;
        for (int x = 1; x < T - 1; ++x) {
            for (int z = 1; z < T - 1; ++z) {
                if (t.water[idx(x, z, T)] >= 0) {
                    continue;
                }
                for (int y = y0_; y < y0_ + ny_; ++y) {
                    n += block_[static_cast<size_t>(at(x, y, z))] == WATER ? 1 : 0;
                }
            }
        }
        return n;
    }

private:
    static constexpr int8_t SOURCE = 0;
    static constexpr int8_t FALLING = 8;
    static constexpr int8_t MAX_LEVEL = 7;
    static constexpr int8_t EMPTY = -1;
    static constexpr int SLOPE_SEARCH_RANGE = 4;
    static constexpr int NO_HOLE = 1 << 30;
    enum : uint8_t { AIR = 0, SOLID = 1, WATER = 2 };
    static constexpr int HX[4] = {1, -1, 0, 0};
    static constexpr int HZ[4] = {0, 0, 1, -1};

    int y0_ = 0;
    int ny_ = 0;
    std::vector<uint8_t> block_;
    std::vector<int8_t> state_;
    std::vector<uint8_t> queued_;
    std::deque<int32_t> queue_;

    int32_t at(int x, int y, int z) const {
        return static_cast<int32_t>((static_cast<size_t>(y - y0_) * T + static_cast<size_t>(x)) * T
                                    + static_cast<size_t>(z));
    }
    bool loaded(int x, int y, int z) const {
        return x >= 0 && x < T && z >= 0 && z < T && y >= y0_ && y < y0_ + ny_;
    }
    bool isAir(int x, int y, int z) const {
        return loaded(x, y, z) && block_[static_cast<size_t>(at(x, y, z))] == AIR;
    }
    /* Below the volume is ground; the volume is sized so nothing reaches it. */
    bool isSolidAt(int x, int y, int z) const {
        if (y < y0_) {
            return true;
        }
        return loaded(x, y, z) && block_[static_cast<size_t>(at(x, y, z))] == SOLID;
    }
    int waterAt(int x, int y, int z) const {
        if (!loaded(x, y, z)) {
            return EMPTY;
        }
        const size_t k = static_cast<size_t>(at(x, y, z));
        return block_[k] == WATER ? state_[k] : EMPTY;
    }
    static int effectiveLevel(int s) { return s == FALLING ? 0 : s; }
    bool canFlowInto(int x, int y, int z) const {
        if (!loaded(x, y, z)) {
            return false;
        }
        const size_t k = static_cast<size_t>(at(x, y, z));
        if (block_[k] == SOLID) {
            return false;
        }
        return !(block_[k] == WATER && state_[k] == SOURCE);
    }
    bool isHole(int x, int y, int z) const { return canFlowInto(x, y - 1, z); }

    void enqueue(int32_t k) {
        if (!queued_[static_cast<size_t>(k)]) {
            queued_[static_cast<size_t>(k)] = 1;
            queue_.push_back(k);
        }
    }
    void scheduleIfWater(int x, int y, int z) {
        if (loaded(x, y, z) && block_[static_cast<size_t>(at(x, y, z))] == WATER) {
            enqueue(at(x, y, z));
        }
    }
    void scheduleWaterNeighbors(int x, int y, int z) {
        for (int d = 0; d < 4; ++d) {
            scheduleIfWater(x + HX[d], y, z + HZ[d]);
        }
        scheduleIfWater(x, y + 1, z);
        scheduleIfWater(x, y - 1, z);
    }
    void coords(int32_t k, int& x, int& y, int& z) const {
        z = k % T;
        x = (k / T) % T;
        y = k / (T * T) + y0_;
    }

    void update(int32_t k) {
        int x, y, z;
        coords(k, x, y, z);
        if (block_[static_cast<size_t>(k)] != WATER) {
            return;
        }
        int state = state_[static_cast<size_t>(k)];
        if (state != SOURCE) {
            const int desired = computeState(x, y, z);
            if (desired == EMPTY) {
                block_[static_cast<size_t>(k)] = AIR;
                state_[static_cast<size_t>(k)] = EMPTY;
                scheduleWaterNeighbors(x, y, z);
                return;
            }
            if (desired != state) {
                state_[static_cast<size_t>(k)] = static_cast<int8_t>(desired);
                scheduleWaterNeighbors(x, y, z);
                enqueue(k);
                state = desired;
            }
        }
        if (canFlowInto(x, y - 1, z)) {
            fill(x, y - 1, z, FALLING);
            return;
        }
        const int spread = effectiveLevel(state) + 1;
        if (spread > MAX_LEVEL) {
            return;
        }
        const int mask = pickFlowDirections(x, y, z);
        for (int d = 0; d < 4; ++d) {
            if ((mask & (1 << d)) != 0) {
                fill(x + HX[d], y, z + HZ[d], spread);
            }
        }
    }

    int computeState(int x, int y, int z) const {
        int sources = 0;
        int minNeighbor = NO_HOLE;
        for (int d = 0; d < 4; ++d) {
            const int s = waterAt(x + HX[d], y, z + HZ[d]);
            if (s == EMPTY) {
                continue;
            }
            if (s == SOURCE) {
                ++sources;
            }
            minNeighbor = std::min(minNeighbor, effectiveLevel(s));
        }
        if (sources >= 2 && (isSolidAt(x, y - 1, z) || waterAt(x, y - 1, z) == SOURCE)) {
            return SOURCE;
        }
        if (waterAt(x, y + 1, z) != EMPTY) {
            return FALLING;
        }
        if (minNeighbor == NO_HOLE || minNeighbor + 1 > MAX_LEVEL) {
            return EMPTY;
        }
        return minNeighbor + 1;
    }

    void fill(int x, int y, int z, int candidate) {
        if (!canFlowInto(x, y, z)) {
            return;
        }
        const size_t k = static_cast<size_t>(at(x, y, z));
        if (block_[k] == WATER && effectiveLevel(candidate) >= effectiveLevel(state_[k])) {
            return;
        }
        block_[k] = WATER;
        state_[k] = static_cast<int8_t>(candidate);
        enqueue(static_cast<int32_t>(k));
    }

    int pickFlowDirections(int x, int y, int z) const {
        int best = NO_HOLE;
        int mask = 0;
        for (int d = 0; d < 4; ++d) {
            const int nx = x + HX[d];
            const int nz = z + HZ[d];
            if (!canFlowInto(nx, y, nz)) {
                continue;
            }
            const int dist = isHole(nx, y, nz) ? 0 : slopeDistance(nx, y, nz, 1, d);
            if (dist < best) {
                best = dist;
                mask = 1 << d;
            } else if (dist == best) {
                mask |= 1 << d;
            }
        }
        return mask;
    }

    int slopeDistance(int x, int y, int z, int distance, int from) const {
        if (distance >= SLOPE_SEARCH_RANGE) {
            return NO_HOLE;
        }
        int best = NO_HOLE;
        for (int d = 0; d < 4; ++d) {
            if ((d ^ 1) == from) {
                continue;
            }
            const int nx = x + HX[d];
            const int nz = z + HZ[d];
            if (!canFlowInto(nx, y, nz)) {
                continue;
            }
            if (isHole(nx, y, nz)) {
                return distance;
            }
            best = std::min(best, slopeDistance(nx, y, nz, distance + 1, d));
        }
        return best;
    }
};

/** Settle a tile under the replica and count what left the river. */
long escapedAfterSettling(const Tile& t) {
    FlowReplica sim(t);
    const bool settled = sim.settle(400L * 1000L * 1000L);
    check(settled, "the flow replica reaches a fixed point");
    return sim.escaped(t);
}

/**
 * §5.8b: flat pools mean bare banks.
 *
 * The measurement this pass was built from. `ck_carve_water` walls every dry
 * column beside the river up to the guard rail — the highest water within
 * `river_guard_reach` wet steps — so on a RAMP the freeboard it adds is the
 * river's own slope times that reach, along the whole river. Measured on a
 * 0.25-per-block flank while this was being written: 383,822 blocks of ground
 * raised at the shipping defaults, and 29,393 with the rail switched off. Over
 * nine tenths of every bank on that flank was slope multiplied by reach.
 *
 * A pool is flat, so it has no slope, so it has no freeboard. What is asserted
 * here is exactly that, end to end: same seed, same terrain, same tiles, only
 * `pool_max_drop` moved, and the ground the kernel raises falls away.
 *
 * Also asserted, because it is what makes the change safe rather than merely
 * nice: no dry column is ever LOWERED. The pass only moves a water surface
 * down, and a lower surface can only ask for a lower wall.
 */
void testPoolsShrinkTheBanks() {
    float rampPlan[35];
    std::memcpy(rampPlan, DOCUMENTED_DEFAULTS, sizeof(float) * 32);
    rampPlan[32] = 0.0f;      /* pool_max_drop: the continuous ramp */
    rampPlan[33] = 512.0f;
    rampPlan[34] = 1.8f;
    float poolPlan[35];
    std::memcpy(poolPlan, rampPlan, sizeof poolPlan);
    poolPlan[32] = 3.0f;      /* and the shipping default */

    long rampVol = 0, poolVol = 0;
    long rampCols = 0, poolCols = 0;
    long lowered = 0;
    int rampTallest = 0, poolTallest = 0;
    const auto sweep = [&](const float* plan, long& vol, long& cols, int& tallest) {
        const Region r = solveRegionWithParams(0, 0, riverTerrainAt, 777, plan, 35);
        if (r.routeCount == 0) {
            check(false, "pools vs banks: the fixture plans a river");
            return;
        }
        for (int64_t tx = 2; tx <= 7; ++tx) {
            const Tile t = runTile(777, tx, 8, riverTerrainAt, &r, plan, 35);
            const int64_t ox = tx * T, oz = 8 * T;
            for (int x = 0; x < T; ++x) {
                for (int z = 0; z < T; ++z) {
                    const size_t i = idx(x, z, T);
                    const int d = t.heights[i] - riverTerrainAt(ox + x, oz + z);
                    if (d > 0) {
                        vol += d;
                        ++cols;
                        tallest = std::max(tallest, d);
                    } else if (d < 0 && t.water[i] < 0) {
                        ++lowered; /* a dry column lost ground: never allowed */
                    }
                }
            }
        }
    };
    sweep(rampPlan, rampVol, rampCols, rampTallest);
    sweep(poolPlan, poolVol, poolCols, poolTallest);

    check(rampVol > 0, "pools vs banks: the ramp really does raise ground");
    check(lowered == 0, "no dry column is lowered, with pools or without");
    /* A third off is far inside what was measured (55 % on a 0.05 flank, and
     * the same direction on 0.25); the margin is for terrain, not for doubt. */
    check(poolVol * 3 < rampVol * 2, "pooling cuts the raised ground by a third or more");
    check(poolTallest <= rampTallest, "and never builds a taller wall than the ramp did");
    std::printf("pools vs banks ok (raised %ld blocks over %ld columns with a ramp, "
                "%ld over %ld with pools; tallest %d -> %d)\n",
                rampVol, rampCols, poolVol, poolCols, rampTallest, poolTallest);
}

/**
 * A tunnel MOUTH opens toward daylight instead of pinching shut.
 *
 * The defect this pins: the roof is clamped to `raw - tunnel_min_roof`, so as
 * the ground falls away toward an opening the lid drags the roof down with it
 * and the passage is at its SMALLEST exactly where it is seen from outside —
 * a hole punched in a face, which is what river tunnels looked like from the
 * valley floor. A real cave mouth is the widest part of the cave.
 *
 * What CANNOT be asserted, and the first version of this test got it wrong: a
 * mouth is not taller than the passage behind it. It cannot be. The roof is
 * clamped by the ground over it and at a mouth the ground is, by definition,
 * running out — deep passage measured 5.95 blocks of void against a mouth's
 * 3.46. Nothing short of lowering the hillside changes that, and lowering is
 * exactly what this kernel does not do.
 *
 * What the portal terms do buy is that the last few blocks are an OPENING
 * rather than a slot: the brow thins to PORTAL_MIN_ROOF instead of holding a
 * full lid until the void pinches to one block, and the dry alcove flares out
 * past the channel. Both are asserted below, and the mouth-column category
 * itself — a lid thinner than `tunnel_min_roof` — exists only because of them.
 */
void testATunnelMouthOpensOut() {
    /* Solved on the SMOOTH plain and stamped on the ridged one, exactly as the
     * tunnelling tests do it: the router must not know about the ridge, or it
     * routes around it and there is no tunnel to have a mouth. */
    const Region r = solveRegion(0, 0, riverTerrainAt, 777, 1.0f);
    if (r.routeCount == 0) {
        check(false, "portal: the fixture plans a river");
        return;
    }
    long mouthVoid = 0, mouthCols = 0;
    long deepVoid = 0, deepCols = 0;
    int thinnestBrow = 1 << 20;
    for (int64_t tx = 2; tx <= 7; ++tx) {
      for (int64_t tz = 7; tz <= 9; ++tz) {
        const Tile t = runTile(777, tx, tz, ridgedRiverTerrainAt, &r, DOCUMENTED_DEFAULTS, 35);
        for (int x = 0; x < T; ++x) {
            for (int z = 0; z < T; ++z) {
                const size_t i = idx(x, z, T);
                if (t.roof[i] < 0 || t.floor[i] < 0) {
                    continue;
                }
                const int height = t.roof[i] - t.floor[i];
                const int lid = t.heights[i] - t.roof[i];
                if (lid < TUNNEL_MIN_ROOF) {
                    mouthVoid += height;
                    ++mouthCols;
                    thinnestBrow = std::min(thinnestBrow, lid);
                } else if (lid >= 2 * PORTAL_CLEARANCE) {
                    deepVoid += height;
                    ++deepCols;
                }
            }
        }
      }
    }
    check(mouthCols > 0, "portal: the route has mouths to look at");
    check(deepCols > 0, "portal: and deep passage to compare them with");
    if (mouthCols == 0 || deepCols == 0) {
        return;
    }
    const double mouth = static_cast<double>(mouthVoid) / static_cast<double>(mouthCols);
    const double deep = static_cast<double>(deepVoid) / static_cast<double>(deepCols);
    /* Thinning the brow from 4 to 2 lifts the roof two blocks exactly where the
     * clamp binds, so a mouth averages an opening rather than the one-block
     * slot the old clamp left. Under 2 means the portal terms stopped firing. */
    check(mouth >= 2.0, "a tunnel mouth is an opening, not a slot");
    check(thinnestBrow >= PORTAL_MIN_ROOF, "and its brow is still rock, not a skylight");
    check(deep > 0.0, "portal: deep passage measured");
    std::printf("portal ok (%ld mouth columns averaging %.2f blocks of void, "
                "%ld deep averaging %.2f; thinnest brow %d)\n",
                mouthCols, mouth, deepCols, deep, thinnestBrow);
}

/**
 * Mirrors `RIVER_GUARD_LIFT`: the most the carve lets a rail stand over the
 * water beside it. Not a params slot, so it can only drift by being edited in
 * the kernel — and either direction is caught below. Raised, and banks appear
 * over this line; lowered, and water leaves the river.
 */
constexpr int GUARD_LIFT = 3;

/**
 * Dry columns 4-adjacent to water, and how many of them stand more than
 * `GUARD_LIFT` over the highest water they touch.
 */
void countFreeboard(const Tile& t, long& banks, long& tall) {
    for (int x = 1; x < T - 1; ++x) {
        for (int z = 1; z < T - 1; ++z) {
            const size_t i = idx(x, z, T);
            if (t.water[i] >= 0) {
                continue;
            }
            int w = -1;
            w = std::max<int>(w, t.water[i - T]);
            w = std::max<int>(w, t.water[i + T]);
            w = std::max<int>(w, t.water[i - 1]);
            w = std::max<int>(w, t.water[i + 1]);
            if (w < 0) {
                continue;
            }
            ++banks;
            tall += t.heights[i] - w > GUARD_LIFT ? 1 : 0;
        }
    }
}

/**
 * The rail holds the sim — and costs no more ground than that takes.
 *
 * Two directions, because the rail has been wrong in both. Too short and the
 * cascade runs over the bank it was walled to, which is the leak the pass
 * exists to stop. Too long and it builds a retaining wall down the length of
 * every river that descends at all: the freeboard is the slope times the
 * reach, so at the 32 uncapped steps this shipped with for a day, these two
 * fixtures carried 58 bank columns standing more than three blocks over their
 * own water where the terrain accounts for 5.
 *
 * So the escape count pins the floor and the FREEBOARD — how far the ground
 * beside the water stands over that water, which is the thing a player is
 * looking at — pins the ceiling. The ceiling is not zero-tall banks: a lake
 * rim, a gorge wall and the skirt hanging off a legitimate wall all stand
 * over water and all belong there. It is that the rail must not manufacture
 * them, and the rail-off run is the control for how many there are anyway.
 */
void testTheGuardRailHoldsWhatTheSimMakes() {
    /* Both river fixtures: the sloped plain, whose river steps a block at a
     * time, and the ridged one, whose river tunnels and comes out again. */
    long before = 0;      /* ramped surface, rail off: the cascade          */
    long pooled = 0;      /* step-pool surface, rail off                    */
    long after = 0;       /* shipping defaults                              */
    long wet = 0;
    long banks = 0;       /* dry columns beside water, at the defaults      */
    long tall = 0;        /* ...standing more than the lift over it         */
    long banksOff = 0;    /* and the same two with the rail switched off    */
    long tallOff = 0;
    /* The negative control has to RAMP. §5.8b's pools are flat, and flat water
     * does not cascade — which is the whole point of them — so a control built
     * on the shipping planner would prove only that the fixture had stopped
     * exercising the rail. `pool_max_drop = 0` restores the continuous descent
     * the rail was measured against, and is what keeps `FlowReplica` honest. */
    const auto run = [&](auto terrain, int64_t tx0, int64_t tx1) {
        float rampPlan[35];
        std::memcpy(rampPlan, DOCUMENTED_DEFAULTS, sizeof(float) * 32);
        rampPlan[32] = 0.0f;   /* pool_max_drop: off  */
        rampPlan[33] = 512.0f;
        rampPlan[34] = 1.8f;
        const Region ramped = solveRegionWithParams(0, 0, terrain, 777, rampPlan, 35);
        const Region r = solveRegion(0, 0, terrain, 777, 1.0f);
        if (r.routeCount == 0 || ramped.routeCount == 0) {
            check(false, "guard rail: the fixture plans a river");
            return;
        }
        float noRail[35];
        std::memcpy(noRail, rampPlan, sizeof noRail);
        noRail[31] = 0.0f;
        /* The rail off, but the river still pooled: the control for how much
         * of the freeboard below is the terrain's rather than the rail's. */
        float pooledNoRail[35];
        std::memcpy(pooledNoRail, DOCUMENTED_DEFAULTS, sizeof pooledNoRail);
        pooledNoRail[31] = 0.0f;
        for (int64_t tx = tx0; tx <= tx1; ++tx) {
            before += escapedAfterSettling(runTile(777, tx, 8, terrain, &ramped, noRail, 35));
            pooled += escapedAfterSettling(runTile(777, tx, 8, terrain, &r, noRail, 35));
            const Tile b = runTile(777, tx, 8, terrain, &r, DOCUMENTED_DEFAULTS, 32);
            after += escapedAfterSettling(b);
            for (int16_t w : b.water) {
                wet += w >= 0 ? 1 : 0;
            }
            countFreeboard(b, banks, tall);
            countFreeboard(runTile(777, tx, 8, terrain, &r, pooledNoRail, 35),
                           banksOff, tallOff);
        }
    };
    run(riverTerrainAt, 2, 7);
    run(ridgedRiverTerrainAt, 2, 7);
    check(wet > 0, "guard rail: the fixtures hold water to spill");
    /* Proves the replica is live: a RAMPED river without the rail floods its
     * banks. This is the measurement the rail's reach was chosen from. */
    check(before > 0, "without the rail a ramped river spills over its banks");
    check(after == 0, "with the rail nothing leaves the river");
    /* And the reason §5.8b earns its keep: flat pools do not cascade, so the
     * same river with the same rail switched off stays where it was put. This
     * is an observation, not a licence — the rail still ships, because the
     * pools are bounded by the terrain and a steep enough reach still steps. */
    check(pooled <= before, "pooling never makes the sim spill more than a ramp");
    /* The ceiling. `tallOff` is the ground that stands over water whatever the
     * rail does — rims, gorge walls, the skirt under a real wall — so the rail
     * is allowed to add its own share of that and no more. The shipping pair
     * adds NONE of it on these fixtures (5 against the control's 5); the 32
     * uncapped steps it replaced turned those 5 into 58, which is what this
     * bound is sized to catch while leaving a retune room to move. */
    check(banks > 0, "guard rail: the fixtures have banks to measure");
    check(tall <= 2 * tallOff + 16,
          "the rail does not manufacture banks taller than the lift it may add");
    std::printf("guard rail ok (%ld wet columns; escaped: ramp-no-rail %ld, "
                "pooled-no-rail %ld, shipping %ld; banks over the lift: %ld of %ld, "
                "%ld of %ld with the rail off)\n",
                wet, before, pooled, after, tall, banks, tallOff, banksOff);
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
    testTheShoreFollowsTheGroundNotTheCellLattice();
    testTheShoreFloodRefusesADeepNotch();
    testTheShoreAgreesAcrossATileSeam();
    testTheBankBrushGradesAWallIntoTheGround();
    testTheBankBrushNeverWetsNorLowersNorRaisesWater();
    testTheBankSkirtAgreesAcrossATileSeam();
    testTheBankBrushIsMonotoneInItsReach();
    testTheSeaShorelineIsNotBrushed();
    testTheBankBrushDoesNotDamAnOutlet();
    testALakePerchedOverAnotherGetsAnOutlet();
    testTheBankLipIsOneToThreeThickAndVaries();
    testTheTunnelVaultIsIrregular();
    testATunnelStaysSealedAcrossATileSeam();
    testATunnelMouthOpensOut();
    testPoolsShrinkTheBanks();
    testTheGuardRailHoldsWhatTheSimMakes();
    testSea();
    testMountains();
    if (failures == 0) {
        std::puts("kernels_water: all ok");
    }
    return failures == 0 ? 0 : 1;
}
