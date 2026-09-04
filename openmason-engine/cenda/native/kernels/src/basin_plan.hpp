/* basin_plan.hpp — depression fill, basins and spill points on a coarse DEM.
 *
 * Layer 1 of the lakes-first hydrology (Dev Working/Lakes-first hydrology
 * plan.md §4). This header knows elevation and basins and nothing at all about
 * rivers, blocks or tiles; `river_plan.hpp` routes on what it produces and
 * `water.cpp` stamps the result.
 *
 * ═══ Why a fill, when flow accumulation was rejected ═══
 *
 * A basin's spill point is determined by the basin, and a basin is a local
 * object: measured on a real 640² CoarseDem grid, a lake surface computed from
 * a truncated window matched the full-grid answer to 0.000 blocks. A trunk
 * river's discharge is not local — it integrates a continental watershed, and
 * two adjacent owner-regions disagreed by 95–100 % on it at every halo from
 * 512 to 2560 blocks. So: *where* water sits is a bounded question and this
 * header answers it exactly; *how much* water flows is not, and nothing here
 * pretends to know (plan §2).
 *
 * ═══ The three properties everything downstream leans on ═══
 *
 *   1. `filled` is non-ascending toward an outlet from every cell. That is
 *      Priority-Flood's guarantee, and it is why a route that descends
 *      `filled` can never go uphill — by construction, not by a retry rule.
 *
 *   2. A basin's surface is bit-identical across all of its cells. With
 *      epsilon = 0 every cell of a depression is assigned the same popped
 *      elevation, so "lake surfaces are level" is structural rather than
 *      argued. Component labelling requires that equality, so a component can
 *      never straddle two levels even if the terrain conspires to tie.
 *
 *   3. The spill cell is the lowest rim cell, ties broken by world
 *      coordinates — never "the first cell popped". Popping order depends on
 *      where the window was placed; a lexicographic minimum does not. Two
 *      windows that both contain a basin therefore name the same spill cell,
 *      which is what makes `basin_id` canonical (plan §5.2).
 *
 * ═══ What this header does NOT decide ═══
 *
 * Whether a basin may be *emitted* is an ownership question, not a solve
 * question: a basin wider than the halo is solved here (from whatever the
 * window holds) but must be handed to the coarser level instead of trusted.
 * `Basin::spanBlocks` and `touchesBorder` are the inputs to that rule; §4.4
 * and Phase 3 own the rule itself.
 */
#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

namespace cenda::basin {

/* ── Hashing ──
 * Shared with water.cpp and river_plan.hpp. Lives here because this is the
 * lowest layer of the water stack; every other file includes it. */

inline uint64_t splitmix64(uint64_t x) {
    x += 0x9E3779B97F4A7C15ULL;
    x = (x ^ (x >> 30)) * 0xBF58476D1CE4E5B9ULL;
    x = (x ^ (x >> 27)) * 0x94D049BB133111EBULL;
    return x ^ (x >> 31);
}

inline uint64_t hashCell(int64_t seed, int64_t cx, int64_t cz, uint64_t salt) {
    uint64_t h = splitmix64(static_cast<uint64_t>(seed) ^ salt);
    h = splitmix64(h ^ static_cast<uint64_t>(cx) * 0xC2B2AE3D27D4EB4FULL);
    h = splitmix64(h ^ static_cast<uint64_t>(cz) * 0x165667B19E3779F9ULL);
    return h;
}

/** A hash's top bits as a fraction in [0, 1). */
inline float hash01(uint64_t h) {
    return static_cast<float>(h >> 40) / static_cast<float>(1 << 24);
}

constexpr uint64_t SALT_BASIN = 0x4241534E5F494400ULL; /* "BASN_ID" */

/** `Solution::basinAt` value for a cell that is not lake. */
constexpr int32_t NO_BASIN = -1;

/** `Solution::basinAt` value for lake whose surface came from the coarser
 *  level (§4.4). There is no local `Basin` record for it, on purpose: this
 *  level does not own the basin and must not source a river from it. */
constexpr int32_t IMPORTED_BASIN = -2;

struct Config {
    /* depth strictly greater than this is lake; a cell exactly at the fill
     * surface is shore, not water. */
    float minLakeDepth = 0.5f;
    /* Cells. Smaller depressions are puddles at 16 blocks per cell and are
     * dropped — they would stamp as single wet columns. */
    int32_t minLakeArea = 8;
    /* Ground at or below this is ocean: it seeds the flood, is never raised,
     * and is never a basin. The sea is not a lake and never a river source. */
    float seaLevel = 320.0f;
};

/** The coarse heights to solve, laid out row = world X, col = world Z. */
struct Grid {
    const float* raw = nullptr;
    int32_t cells = 0;      /* side length, in cells */
    int32_t cellBlocks = 16;
    int64_t originX = 0;    /* world block coords of cell (0, 0) */
    int64_t originZ = 0;

    int64_t worldX(int32_t i) const {
        return originX + static_cast<int64_t>(i) * cellBlocks;
    }
    int64_t worldZ(int32_t j) const {
        return originZ + static_cast<int64_t>(j) * cellBlocks;
    }
    int32_t count() const { return cells * cells; }
};

struct Basin {
    /* The filled surface over every cell of this basin — bit-identical
     * throughout, and equal to the elevation of the spill cell. */
    float level = 0.0f;
    /* Lowest raw ground inside. `level - floor` is the maximum depth. */
    float floor = 0.0f;
    /* Cells deeper than minLakeDepth — the stampable lake, which is smaller
     * than the depression the fill found by one shoreline ring. */
    int32_t area = 0;
    /* Blocks cubed: sum of depth over those cells, times cell area. This is
     * the quantity §5.5 uses as a discharge proxy, and it is legal there for
     * the same reason the surface is exact: it is bounded. */
    double volume = 0.0;

    /* Lowest cell on the basin's rim — where the lake overflows, and a free
     * river head. Ties broken by (worldX, worldZ), never by popping order. */
    int32_t spillI = 0, spillJ = 0;
    int64_t spillX = 0, spillZ = 0;

    /* Inclusive window-cell bounding box, and the cell size it is expressed
     * in. A basin carries its own resolution because §4.4 lets a fine solve
     * import a basin from the coarse one, and the two levels' cells differ. */
    int32_t minI = 0, minJ = 0, maxI = 0, maxJ = 0;
    int32_t cellBlocks = 16;

    /* The basin, or the rim it needs to find its spill, reaches the window
     * edge. Its level is then whatever the boundary seed allowed, not its real
     * spill — do not emit it from this window. */
    bool touchesBorder = false;

    /** Longest side of the bbox in blocks: the number §4.4's halo rule tests. */
    int32_t spanBlocks() const {
        return (std::max(maxI - minI, maxJ - minJ) + 1) * cellBlocks;
    }

    /** Canonical identity: derived from the spill cell's WORLD coordinates, so
     *  two windows that both contain this basin agree. A label-order index
     *  would not — it depends on the scan, and the scan depends on the window. */
    uint64_t id(int64_t seed) const {
        return hashCell(seed, spillX, spillZ, SALT_BASIN);
    }

    /** Maximum depth in blocks. */
    float maxDepth() const { return level - floor; }
};

namespace detail {

/* One heap slot. Packed into a single array rather than parallel key/index
 * vectors: a sift touches both fields of one element, and at 512² cells the
 * flood is ~500k heap operations, so the extra cache line per swap was
 * measurable (0.114 -> under the 0.1 ms/tile budget of §10).
 *
 * There is deliberately no insertion counter to break ties with. A binary heap
 * is already deterministic given the same insertion sequence, and the *result*
 * does not depend on tie order at all: with epsilon = 0 a cell's filled
 * elevation is the minimax path elevation to an outlet, which is a property of
 * the terrain rather than of the pop order. The halo-independence test asserts
 * exactly that. */
struct HeapEntry {
    float key;
    int32_t cell;
};

/* Binary min-heap over HeapEntry::key. */
struct Heap {
    std::vector<HeapEntry>* a;
    int32_t size = 0;

    bool less(int32_t x, int32_t y) const {
        return (*a)[static_cast<size_t>(x)].key < (*a)[static_cast<size_t>(y)].key;
    }
    void swapAt(int32_t x, int32_t y) {
        std::swap((*a)[static_cast<size_t>(x)], (*a)[static_cast<size_t>(y)]);
    }

    void push(float k, int32_t cell) {
        (*a)[static_cast<size_t>(size)] = HeapEntry{k, cell};
        int32_t i = size++;
        while (i > 0) {
            const int32_t p = (i - 1) >> 1;
            if (!less(i, p)) {
                break;
            }
            swapAt(i, p);
            i = p;
        }
    }

    /** Pops the minimum into (outKey, outCell). */
    void pop(float& outKey, int32_t& outCell) {
        outKey = (*a)[0].key;
        outCell = (*a)[0].cell;
        --size;
        swapAt(0, size);
        int32_t i = 0;
        for (;;) {
            const int32_t l = 2 * i + 1;
            const int32_t r = l + 1;
            int32_t m = i;
            if (l < size && less(l, m)) {
                m = l;
            }
            if (r < size && less(r, m)) {
                m = r;
            }
            if (m == i) {
                break;
            }
            swapAt(m, i);
            i = m;
        }
    }
};

/* 8-connected offsets. The flood is 8-connected because a diagonal gap is a
 * spill route for water at cell resolution — a 4-connected fill invents dams
 * out of single diagonal cells. */
constexpr int32_t DI[8] = {-1, -1, -1, 0, 0, 1, 1, 1};
constexpr int32_t DJ[8] = {-1, 0, 1, -1, 1, -1, 0, 1};

} // namespace detail

struct Solution {
    /* The filled surface. This is the water surface AND the routing field. It
     * is complete — every depression is filled to its spill point, including
     * ones too small or too truncated to be lakes — because the never-up
     * property of §5.1 depends on the fill being complete. */
    std::vector<float> filled;
    /* Lake depth: `filled - raw` inside a kept basin, and exactly 0.0f
     * everywhere else. So `depth > 0` and `basinAt != NO_BASIN` are the same
     * test, and a discarded puddle cannot be stamped by accident. */
    std::vector<float> depth;
    /* Index into `basins`, or NO_BASIN. */
    std::vector<int32_t> basinAt;
    std::vector<Basin> basins;

    /* Component id per cell over EVERY depression the fill raised, including
     * the ones too small or too shallow to be lakes; -1 on dry ground. A
     * discarded depression still has a flat surface, and anything routing on
     * `filled` will walk onto one — this is how it knows it has. */
    std::vector<int32_t> label;
    /* Per component id: the cell index of the spill it drains through, or -1.
     * The fill computes this for every component and then throws all but the
     * lakes away; keeping it costs one small vector and is what lets a river
     * CROSS a pond too small to be a lake instead of stalling on its flat. */
    std::vector<int32_t> componentSpill;
    /* Per component id: blocks³ of water the depression holds, UNTRIMMED —
     * summed over every cell the fill raised, not only the ones deeper than
     * `minLakeDepth`. `Basin::volume` is the trimmed figure because that is the
     * water a lake actually stamps; this one is what passes THROUGH a river
     * crossing the depression, which includes the shallow margin. */
    std::vector<double> componentVolume;

    /* Scratch, reused across solves on the same thread. */
    std::vector<uint8_t> visited;
    std::vector<detail::HeapEntry> heap;
    std::vector<int32_t> stack;
};

/**
 * Priority-Flood (Barnes, Lehman & Mulla 2014) with epsilon = 0, seeded from
 * the window boundary and from ocean, followed by basin labelling and spill
 * points.
 *
 * epsilon = 0 on purpose. The old Python fill injected a gradient across flats
 * so a D8 router could cross them; nothing here routes with D8, and a nonzero
 * epsilon would tilt every lake surface by the graph distance to its outlet —
 * destroying property 2 above for the sake of a consumer that no longer exists.
 *
 * Cost, measured on real CoarseDem chunks (16 of `tile_cache/
 * coarse_5a202d6d9dbd`, assembled into the 512² window L1 uses — a 4096-block
 * region plus a 2048-block halo): 22.7 ms, i.e. 0.089 ms amortized over that
 * region's 256 terrain tiles, inside §10's 0.1 ms budget. The plan's 13.8 ms
 * was the numpy/numba prototype's figure on a different window; this is the
 * number for this code. Almost all of it is the heap, which is why
 * `detail::HeapEntry` is packed.
 */
inline void solve(const Grid& g, const Config& cfg, Solution& s) {
    const int32_t n = g.cells;
    const auto total = static_cast<size_t>(n) * static_cast<size_t>(n);
    s.filled.assign(total, 0.0f);
    s.depth.assign(total, 0.0f);
    s.basinAt.assign(total, NO_BASIN);
    s.label.assign(total, -1);
    s.visited.assign(total, 0);
    s.basins.clear();
    s.componentSpill.clear();
    s.componentVolume.clear();
    if (g.raw == nullptr || n <= 0) {
        return;
    }
    s.heap.resize(total);

    /* ── 1. Priority-Flood ── */
    detail::Heap heap{&s.heap, 0};
    for (size_t k = 0; k < total; ++k) {
        s.filled[k] = g.raw[k];
    }
    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            const auto k = static_cast<size_t>(i) * static_cast<size_t>(n)
                + static_cast<size_t>(j);
            const bool border = (i == 0 || i == n - 1 || j == 0 || j == n - 1);
            /* Ocean is an outlet in its own right: water that reaches it has
             * left. It is seeded at its own elevation and, being visited
             * already, can never be raised into a lake. */
            const bool ocean = g.raw[k] <= cfg.seaLevel;
            if (!border && !ocean) {
                continue;
            }
            s.visited[k] = 1;
            heap.push(g.raw[k], static_cast<int32_t>(k));
        }
    }

    while (heap.size > 0) {
        float elev = 0.0f;
        int32_t k = 0;
        heap.pop(elev, k);
        const int32_t i = k / n;
        const int32_t j = k - i * n;
        for (int d = 0; d < 8; ++d) {
            const int32_t ni = i + detail::DI[d];
            const int32_t nj = j + detail::DJ[d];
            if (ni < 0 || ni >= n || nj < 0 || nj >= n) {
                continue;
            }
            const int32_t nk = ni * n + nj;
            const auto unk = static_cast<size_t>(nk);
            if (s.visited[unk]) {
                continue;
            }
            s.visited[unk] = 1;
            /* Below the spill: the water backs up to exactly the spill level.
             * Assigned from `elev`, so every cell of one depression carries a
             * bit-identical surface. */
            s.filled[unk] = g.raw[unk] <= elev ? elev : g.raw[unk];
            heap.push(s.filled[unk], nk);
        }
    }

    /* ── 2. Label depressions ──
     * A component is every cell the fill RAISED (`filled > raw`), not every
     * cell deep enough to be worth stamping. The difference is the shoreline
     * ring, where the fill raised the ground by less than minLakeDepth, and it
     * matters for exactly one reason: the spill point is the lowest cell on a
     * basin's RIM, so the rim has to be ground the water never reached. Label
     * only the deep interior and the "rim" becomes the shoreline — a cell
     * fractionally below the water rather than the saddle the lake actually
     * spills over, which would put every river head in the wrong place.
     * minLakeDepth is applied afterwards, as an emission filter (§4 below).
     *
     * Equal `filled` is part of the connectivity test, so a component is a
     * single flat surface by construction rather than by assumption. The BFS
     * only assigns labels; every accumulated quantity is gathered in the
     * row-major pass below, for the reason stated there. */
    int32_t nComponents = 0;
    for (int32_t i0 = 0; i0 < n; ++i0) {
        for (int32_t j0 = 0; j0 < n; ++j0) {
            const int32_t k0 = i0 * n + j0;
            const auto uk0 = static_cast<size_t>(k0);
            if (s.label[uk0] != -1 || s.filled[uk0] <= g.raw[uk0]) {
                continue;
            }
            const int32_t id = nComponents++;
            const float level = s.filled[uk0];
            s.label[uk0] = id;
            s.stack.clear();
            s.stack.push_back(k0);
            while (!s.stack.empty()) {
                const int32_t k = s.stack.back();
                s.stack.pop_back();
                const int32_t i = k / n;
                const int32_t j = k - i * n;
                for (int d = 0; d < 8; ++d) {
                    const int32_t ni = i + detail::DI[d];
                    const int32_t nj = j + detail::DJ[d];
                    if (ni < 0 || ni >= n || nj < 0 || nj >= n) {
                        continue;
                    }
                    const int32_t nk = ni * n + nj;
                    const auto unk = static_cast<size_t>(nk);
                    if (s.label[unk] != -1
                            || s.filled[unk] != level
                            || s.filled[unk] <= g.raw[unk]) {
                        continue;
                    }
                    s.label[unk] = id;
                    s.stack.push_back(nk);
                }
            }
        }
    }

    /* ── 3. Per-component statistics and spill points ──
     * One row-major pass, and the order matters. A window shift is a
     * translation, so row-major visits a given basin's cells in the same
     * relative order from every window that contains it — which makes the
     * volume sum bit-identical across windows. Accumulating during the BFS
     * instead would order the additions by stack order, and float addition is
     * not associative: the same basin would report slightly different volumes
     * from two windows, and §5.5 turns volume into river width.
     *
     * A dry cell adjacent to a component is on that component's rim, and the
     * spill is the rim's minimum. Ties are broken by taking the FIRST cell at
     * that minimum, which in a row-major scan is the lexicographically
     * smallest (i, j) — and therefore the smallest (worldX, worldZ), since
     * worldX increases with i and worldZ with j. Order-independent, so both
     * windows name the same spill cell however the flood happened to run. */
    struct Comp {
        float level = 0.0f;
        float floor = 0.0f;
        int32_t cells = 0;       /* the whole depression, shoreline included */
        int32_t lakeArea = 0;    /* only what is deeper than minLakeDepth     */
        double depthSum = 0.0;   /* summed over those cells                   */
        double fillSum = 0.0;    /* summed over ALL of them                   */
        int32_t minI = 0, minJ = 0, maxI = 0, maxJ = 0;
        bool touchesBorder = false;
        float spillRaw = 0.0f;
        int32_t spillI = 0, spillJ = 0;
        bool spillFound = false;
    };
    std::vector<Comp> comps(static_cast<size_t>(nComponents));

    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            const int32_t k = i * n + j;
            const auto uk = static_cast<size_t>(k);
            const int32_t id = s.label[uk];
            if (id >= 0) {
                Comp& c = comps[static_cast<size_t>(id)];
                if (c.cells == 0) {
                    c.level = s.filled[uk];
                    c.floor = g.raw[uk];
                    c.minI = c.maxI = i;
                    c.minJ = c.maxJ = j;
                }
                c.cells += 1;
                const float d = c.level - g.raw[uk];
                c.fillSum += static_cast<double>(d);
                if (d > cfg.minLakeDepth) {
                    c.lakeArea += 1;
                    c.depthSum += static_cast<double>(d);
                }
                c.floor = std::min(c.floor, g.raw[uk]);
                /* The bbox covers the whole depression, not the trimmed lake:
                 * §4.4's halo rule asks whether the window could see all of
                 * the ground that decides this basin's level. */
                c.minI = std::min(c.minI, i);
                c.minJ = std::min(c.minJ, j);
                c.maxI = std::max(c.maxI, i);
                c.maxJ = std::max(c.maxJ, j);
                /* One ring out, because the spill point is a RIM cell: a basin
                 * whose rim leaves the window has not seen its own outlet. */
                if (i <= 1 || i >= n - 2 || j <= 1 || j >= n - 2) {
                    c.touchesBorder = true;
                }
                continue; /* wet: cannot be its own rim */
            }
            for (int d = 0; d < 8; ++d) {
                const int32_t ni = i + detail::DI[d];
                const int32_t nj = j + detail::DJ[d];
                if (ni < 0 || ni >= n || nj < 0 || nj >= n) {
                    continue;
                }
                const int32_t nid = s.label[static_cast<size_t>(ni * n + nj)];
                if (nid < 0) {
                    continue;
                }
                Comp& c = comps[static_cast<size_t>(nid)];
                const float here = g.raw[uk];
                if (!c.spillFound || here < c.spillRaw) {
                    c.spillRaw = here;
                    c.spillI = i;
                    c.spillJ = j;
                    c.spillFound = true;
                }
            }
        }
    }

    /* ── 4. Keep the components that are lakes; publish depth and ids ──
     * Everything below minLakeArea keeps its FILL (the routing field must stay
     * complete) but loses its DEPTH, so `depth > 0` means exactly "stampable
     * lake" for every consumer. */
    s.componentSpill.assign(comps.size(), -1);
    s.componentVolume.assign(comps.size(), 0.0);
    const double cellArea =
        static_cast<double>(g.cellBlocks) * static_cast<double>(g.cellBlocks);
    for (size_t c = 0; c < comps.size(); ++c) {
        if (comps[c].spillFound) {
            s.componentSpill[c] = comps[c].spillI * n + comps[c].spillJ;
        }
        s.componentVolume[c] = comps[c].fillSum * cellArea;
    }

    std::vector<int32_t> keep(comps.size(), NO_BASIN);
    for (size_t c = 0; c < comps.size(); ++c) {
        const Comp& comp = comps[c];
        if (comp.lakeArea < cfg.minLakeArea || !comp.spillFound) {
            continue;
        }
        Basin b;
        b.level = comp.level;
        b.floor = comp.floor;
        b.area = comp.lakeArea;
        b.volume = comp.depthSum * cellArea;
        b.spillI = comp.spillI;
        b.spillJ = comp.spillJ;
        b.spillX = g.worldX(comp.spillI);
        b.spillZ = g.worldZ(comp.spillJ);
        b.cellBlocks = g.cellBlocks;
        b.minI = comp.minI;
        b.minJ = comp.minJ;
        b.maxI = comp.maxI;
        b.maxJ = comp.maxJ;
        b.touchesBorder = comp.touchesBorder;
        keep[c] = static_cast<int32_t>(s.basins.size());
        s.basins.push_back(b);
    }

    for (size_t k = 0; k < total; ++k) {
        const int32_t id = s.label[k];
        if (id < 0) {
            continue;
        }
        const int32_t basinIdx = keep[static_cast<size_t>(id)];
        if (basinIdx == NO_BASIN) {
            continue;
        }
        const float d = s.filled[k] - g.raw[k];
        if (d <= cfg.minLakeDepth) {
            continue; /* shoreline: the fill touched it, the lake does not */
        }
        s.basinAt[k] = basinIdx;
        s.depth[k] = d;
    }
}

/* ═══════════════════ Two-level ownership (§4.4) ═══════════════════
 *
 * A basin is exact only if the solve window contained the whole basin AND its
 * path to its spill point. The L1 window is a 4096-block region plus a
 * 2048-block halo, so L1 can be trusted about a basin up to 2048 blocks
 * across — and there are real basins wider than that. The old L0/L1 hydrology
 * resolved this on a 247 km² counterexample and left one instruction behind:
 *
 *     Decide which level owns a basin by testing THE BASIN'S OWN BOUNDING BOX
 *     against the halo. A window-local test "looks identical and is wrong."
 *
 * So a basin too wide for L1 is not emitted by L1 at all; L1 imports its
 * surface from L0, whose cells are 8x coarser and whose halo is 16 km.
 *
 * L0 is BOX-DOWNSAMPLED from the same coarse heights L1 reads — never
 * generated independently. The old plan tried a cheap low-frequency L0 and
 * measured lake surfaces misplaced by ~10 blocks against L1's; averaging the
 * same data cannot disagree with itself that way.
 */

struct Level {
    int32_t cellBlocks;    /* DEM resolution                                 */
    int32_t regionBlocks;  /* ground one solve owns and emits                */
    int32_t haloBlocks;    /* extra ground on each side of the owned region  */
    int32_t downsample;    /* L1 cells per cell on a side (1 at L1)          */

    /** Side of the solve window, in cells. */
    int32_t windowCells() const {
        return (regionBlocks + 2 * haloBlocks) / cellBlocks;
    }

    /** World coordinate of a region's window origin, halo included. */
    int64_t originOf(int64_t region) const {
        return region * regionBlocks - haloBlocks;
    }
};

/* §4.4's table. L1 owns basins up to a 2048-block span; L0 up to 16 km, which
 * covered every basin in the 2026-09-02 fixture with room to spare. */
constexpr Level LEVEL_L1{16, 4096, 2048, 1};
constexpr Level LEVEL_L0{128, 32768, 16384, 8};

/** Floor division, so negative world coordinates tile contiguously. */
inline int64_t floorDiv(int64_t a, int64_t b) {
    const int64_t q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

/** The region that owns a world column at this level. Ownership is an absolute
 *  lattice on world coordinates, canonical by fiat: a column belongs to exactly
 *  one region, only that region emits it, so two regions never both compute the
 *  same column and there is nothing for them to disagree about. */
inline int64_t regionOf(int64_t world, const Level& lv) {
    return floorDiv(world, lv.regionBlocks);
}

/** The solve window for one region: the region itself plus its halo. */
inline Grid windowFor(const Level& lv, int64_t regionX, int64_t regionZ, const float* raw) {
    return Grid{raw,
                lv.windowCells(),
                lv.cellBlocks,
                regionX * lv.regionBlocks - lv.haloBlocks,
                regionZ * lv.regionBlocks - lv.haloBlocks};
}

/** §4.4: may this level emit this basin, or must it defer to the coarser one?
 *
 *  Two conditions, and both are about the basin rather than about the window.
 *  `touchesBorder` catches a basin the window truncated outright; the span test
 *  catches one that fits in this particular window only by luck of placement —
 *  it would be cut in half by a window one region over, and the two would
 *  disagree. Requiring the span to fit the halo means every window that
 *  contains any of the basin contains all of it.
 *
 *  The span test leaves a one-cell rim on each side rather than testing the
 *  bare span, and that margin is what makes the two conditions agree instead of
 *  merely overlapping. A basin that touches ground the region owns sits at
 *  least `halo` from the window border, so it can only reach that border by
 *  spanning the full halo; excluding the last cell on each side puts the
 *  boundary case out of reach entirely. Without it there is a narrow band of
 *  spans — just under the halo, positioned just so — where one region's window
 *  truncates a basin and its neighbour's does not, and the two emit different
 *  water over the ground they share. The rim is also what the spill point needs:
 *  it is a cell just outside the basin, so a window that holds the basin but not
 *  its rim has not seen its outlet. */
inline bool ownsBasin(const Basin& b, const Level& lv) {
    return !b.touchesBorder && b.spanBlocks() + 2 * b.cellBlocks <= lv.haloBlocks;
}

/**
 * Box-downsample a fine grid by `factor` on a side, producing the coarser
 * level's input. The fine grid's side must be a multiple of `factor`.
 *
 * Averaging, not sampling: a lake's level is set by the lowest saddle on its
 * rim, and point-sampling every 8th cell walks straight past most saddles. The
 * mean also keeps L0 consistent with L1 in the only sense that matters — it is
 * a function of exactly the same numbers.
 */
inline void boxDownsample(const Grid& fine, int32_t factor, std::vector<float>& out) {
    const int32_t n = fine.cells / factor;
    out.assign(static_cast<size_t>(n) * static_cast<size_t>(n), 0.0f);
    if (factor <= 0 || n <= 0 || fine.cells % factor != 0) {
        return;
    }
    const double inv = 1.0 / (static_cast<double>(factor) * static_cast<double>(factor));
    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            double acc = 0.0;
            for (int32_t di = 0; di < factor; ++di) {
                const int32_t fi = i * factor + di;
                for (int32_t dj = 0; dj < factor; ++dj) {
                    acc += static_cast<double>(
                        fine.raw[static_cast<size_t>(fi) * static_cast<size_t>(fine.cells)
                                 + static_cast<size_t>(j * factor + dj)]);
                }
            }
            out[static_cast<size_t>(i) * static_cast<size_t>(n) + static_cast<size_t>(j)] =
                static_cast<float>(acc * inv);
        }
    }
}

/**
 * §4.4's emission filter: withhold every basin this level may not own, and
 * report how many of those the REGION ACTUALLY NEEDED.
 *
 * The distinction is the whole point of the return value, and getting it wrong
 * costs about an hour. A window is a region plus a halo, and the halo is
 * scaffolding: a basin sitting out at its edge belongs to whichever region owns
 * that ground, whose own window holds it comfortably interior. This level
 * withholds it either way — the halo must not emit lakes — but it is not
 * evidence that anything is wrong, because nothing here was going to emit it.
 *
 * Measured on real terrain at seed 1433293152336000383, every one of nine L1
 * regions withheld two to five basins, and in seven of them the largest was
 * 192-384 blocks across against a limit of 2,016. They were withheld purely for
 * touching the window edge. Counting those as "the coarse level is needed"
 * sent `BasinCache` to solve an L0 region — 1,024 CoarseDem chunks, most of
 * them cold at 3.25 s — for a basin two kilometres outside the ground the
 * region emits.
 *
 * So the count is over basins that overlap the region's OWN rectangle. Those
 * are the ones this level was going to emit and now cannot, and they are the
 * only ones a coarser level can help with.
 *
 * A withheld basin loses its LAKE and keeps its FILL. The fill is the routing
 * field and §5.1's never-up guarantee needs it complete; only the water is
 * withheld, and only until a level that can be trusted about it says otherwise.
 *
 * Running this before anything leaves the solve is what makes the two planes
 * self-describing: afterwards `depth > 0` means "a lake this level vouches
 * for", so a consumer — including the finer level importing from this one —
 * needs no basin table to know what it may trust.
 *
 * ── Why the count alone is not enough ──
 *
 * A count says a coarser rung is *permitted* to help; it says nothing about
 * whether it *can*. `minLakeArea` is counted in CELLS, so the smallest lake a
 * rung will emit grows with its cell area — 0.46 km² at L1, 1.84 at L2, 7.37
 * at L3, 29.5 at L4 on the shipping parameters. Escalating for a basin whose
 * lake is below the next rung's own floor buys a plane that is guaranteed to
 * come back dry over that ground, at 4x the DEM.
 *
 * That is not hypothetical. Measured 2026-09-03 (seed 5145549158747503491):
 * L1 region (-1,-1) withheld one basin, escalated to L2, was withheld again,
 * escalated to L3 — 207 extra CoarseDem chunks, ~5-7 min of serial GPU, 76 %
 * of that whole world load — and both coarse planes emitted ZERO lake cells
 * over the ground they were fetched for.
 *
 * So the stake leaves with the count, and `BasinCache` decides. Reported in
 * CELLS of this call's lattice rather than blocks² because a wide basin at a
 * coarse rung overflows int32 in blocks² and the caller knows its own cell
 * size anyway.
 */
struct Withheld {
    /** Basins this level may not emit that overlap its OWN rectangle. */
    int32_t count = 0;
    /** Largest trimmed lake among them, in cells of this level's lattice.
     *
     *  A LOWER BOUND when the basin is truncated by the window: the border is
     *  an escape, so a truncated fill can only sit at or below the true spill,
     *  and the trimmed lake can only be at or smaller than the true one. A
     *  caller gating on this must leave headroom for that. */
    int32_t lakeCells = 0;
    /** Longest bbox side among them, in blocks — the number the halo rule
     *  tests, kept for diagnostics so a log line can say how wide the thing
     *  that triggered an escalation actually was. */
    int32_t spanBlocks = 0;
};

inline Withheld applyOwnership(Solution& s, const Level& lv) {
    /* The region's own ground, in window cells: the window is the region plus a
     * halo on each side, so the owned rectangle always starts one halo in. */
    const int32_t ownedLo = lv.haloBlocks / lv.cellBlocks;
    const int32_t ownedHi = ownedLo + lv.regionBlocks / lv.cellBlocks;
    std::vector<uint8_t> owned(s.basins.size(), 0);
    Withheld w;
    for (size_t b = 0; b < s.basins.size(); ++b) {
        const Basin& basin = s.basins[b];
        const bool ok = ownsBasin(basin, lv);
        owned[b] = ok ? 1u : 0u;
        if (ok) {
            continue;
        }
        const bool overlapsRegion = basin.maxI >= ownedLo && basin.minI < ownedHi
            && basin.maxJ >= ownedLo && basin.minJ < ownedHi;
        if (overlapsRegion) {
            ++w.count;
            /* The stake is the biggest single lake at risk, not their sum: the
             * caller is deciding whether one coarser solve is worth its DEM,
             * and that solve either owns the largest of these or owns none. */
            w.lakeCells = std::max(w.lakeCells, basin.area);
            w.spanBlocks = std::max(w.spanBlocks, basin.spanBlocks());
        }
    }
    if (w.count == 0) {
        return w;
    }
    for (size_t k = 0; k < s.basinAt.size(); ++k) {
        const int32_t b = s.basinAt[k];
        if (b >= 0 && !owned[static_cast<size_t>(b)]) {
            s.basinAt[k] = NO_BASIN;
            s.depth[k] = 0.0f;
        }
    }
    return w;
}

/**
 * Import the coarse level's lakes into a fine solve that has already been
 * through `applyOwnership` — the second half of §4.4.
 *
 * The coarse level arrives as its two planes, not as a basin table, because
 * `applyOwnership` already left them self-describing: `coarseDepth > 0` marks
 * ground the coarse level vouches for, and `coarseFilled` is its surface. That
 * keeps the whole two-level protocol to four float planes, which is also what
 * crosses the FFM boundary in `ck_solve_basins`.
 *
 * Where the fine level has no lake of its own and the coarse level has one
 * standing above the FINE ground, the fine level takes it: the coarse surface
 * verbatim, applied to fine heights. Verbatim matters — a lake surface is flat,
 * so the coarse value IS the fine value, and that is what makes surfaces level
 * to the bit across a region seam. Applied to fine heights matters too: L0
 * averaged 8x8 cells, so an island inside a big lake is invisible to it and
 * only the fine ground can keep it dry.
 *
 * Imported cells are marked `IMPORTED_BASIN` rather than pointed at a local
 * record. There is deliberately no local record: this level does not own the
 * basin, and a river must not be sourced from a spill point this window cannot
 * see (§5.2). The coarse level sources it.
 *
 * The fine solve's own reading of an oversized basin is discarded rather than
 * unioned. It cannot be trusted even where it looks wet: a truncated window
 * fills to whatever its boundary allowed, which is at or below the true spill,
 * so the fine lake is a subset of the real one with a shoreline in the wrong
 * place.
 */
inline void mergeLevels(const Grid& fineGrid, Solution& fine,
                        const Grid& coarseGrid, const float* coarseFilled,
                        const float* coarseDepth, const Config& cfg) {
    if (coarseFilled == nullptr || coarseDepth == nullptr || coarseGrid.cells <= 0) {
        return;
    }
    const int32_t n = fineGrid.cells;
    for (int32_t i = 0; i < n; ++i) {
        for (int32_t j = 0; j < n; ++j) {
            const auto k = static_cast<size_t>(i) * static_cast<size_t>(n)
                + static_cast<size_t>(j);
            if (fine.basinAt[k] != NO_BASIN) {
                continue; /* this level has its own answer and may be trusted */
            }
            const int64_t ci = floorDiv(fineGrid.worldX(i) - coarseGrid.originX,
                                        coarseGrid.cellBlocks);
            const int64_t cj = floorDiv(fineGrid.worldZ(j) - coarseGrid.originZ,
                                        coarseGrid.cellBlocks);
            if (ci < 0 || ci >= coarseGrid.cells || cj < 0 || cj >= coarseGrid.cells) {
                continue; /* outside the coarse solve: nothing to import */
            }
            const auto ck = static_cast<size_t>(ci) * static_cast<size_t>(coarseGrid.cells)
                + static_cast<size_t>(cj);
            if (coarseDepth[ck] <= 0.0f) {
                continue;
            }
            const float level = coarseFilled[ck];
            const float d = level - fineGrid.raw[k];
            if (d <= cfg.minLakeDepth) {
                continue; /* fine ground stands above the coarse lake's surface */
            }
            fine.basinAt[k] = IMPORTED_BASIN;
            fine.depth[k] = d;
            /* The routing field takes the imported surface too, so a route
             * crossing the lake sees one surface rather than two. */
            fine.filled[k] = level;
        }
    }
}

} // namespace cenda::basin
