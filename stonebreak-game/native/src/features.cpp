/* Surface features (VegetationGenerator + the tree shapes, CactusGenerator, SurfaceDecorationGenerator), the
 * order-independent resolution rule, populate() and column_surface(). See worldgen.h. */
#include "decide.hpp"

#include <algorithm>
#include <cmath>
#include <unordered_map>
#include <unordered_set>

namespace sbw {

float forestLattice(const DeterministicRandom& r, int64_t x, int64_t z, int spacing, int32_t feature); /* column.cpp */

namespace {

constexpr double PI = 3.141592653589793;
using stonebreak::javaStringHash;

/* ------------------------------------------------------------------------- tree buffer */
/* One tree's cells, later writes win (TreeBlockPlacer / TreeShapeBuffer semantics). */
struct TreeBuf {
    std::vector<FeatureCell> cells;
    std::unordered_map<uint64_t, size_t> index;
    static uint64_t key(int64_t x, int64_t y, int64_t z) {
        return (static_cast<uint64_t>(x & 0x1FFFFF) << 42) | (static_cast<uint64_t>(y & 0x1FFFFF) << 21)
               | static_cast<uint64_t>(z & 0x1FFFFF);
    }
    void put(int64_t x, int64_t y, int64_t z, uint16_t id) {
        const uint64_t k = key(x, y, z);
        auto it = index.find(k);
        if (it != index.end()) {
            cells[it->second].id = id;
        } else {
            index.emplace(k, cells.size());
            cells.push_back({x, y, z, id, 0});
        }
    }
};

struct Blocks {
    uint16_t log, leaves;
};

bool isLogId(const Config& c, uint16_t id) {
    return id == c.id[SBW_WOOD] || id == c.id[SBW_PINE] || id == c.id[SBW_ELM_WOOD_LOG] || id == c.id[SBW_CYPRESS_LOG];
}
bool isLeavesId(const Config& c, uint16_t id) {
    return id == c.id[SBW_LEAVES] || id == c.id[SBW_PINE_LEAVES] || id == c.id[SBW_ELM_LEAVES]
           || id == c.id[SBW_CYPRESS_LEAVES];
}

/* TreeShapeBuffer.flushAnchored: drop leaves farther than DECAY_RADIUS leaf/log steps from a log. */
void pruneUnanchored(const Config& cfg, TreeBuf& t) {
    constexpr int DECAY_RADIUS = 6;
    std::unordered_set<uint64_t> reached;
    std::vector<size_t> frontier;
    for (size_t i = 0; i < t.cells.size(); i++) {
        if (isLogId(cfg, t.cells[i].id)) {
            reached.insert(TreeBuf::key(t.cells[i].x, t.cells[i].y, t.cells[i].z));
            frontier.push_back(i);
        }
    }
    static const int D[6][3] = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    for (int depth = 0; depth < DECAY_RADIUS && !frontier.empty(); depth++) {
        std::vector<size_t> next;
        for (size_t i : frontier) {
            const FeatureCell& c = t.cells[i];
            for (const auto& d : D) {
                const uint64_t k = TreeBuf::key(c.x + d[0], c.y + d[1], c.z + d[2]);
                auto it = t.index.find(k);
                if (it == t.index.end()) continue;
                const uint16_t id = t.cells[it->second].id;
                if ((isLeavesId(cfg, id) || isLogId(cfg, id)) && reached.insert(k).second) next.push_back(it->second);
            }
        }
        frontier.swap(next);
    }
    std::vector<FeatureCell> kept;
    kept.reserve(t.cells.size());
    for (const FeatureCell& c : t.cells) {
        if (isLeavesId(cfg, c.id) && reached.count(TreeBuf::key(c.x, c.y, c.z)) == 0) continue;
        kept.push_back(c);
    }
    t.cells.swap(kept);
}

/* TreeRandom.forPosition */
JavaRandom treeRandom(int64_t x, int64_t y, int64_t z, uint64_t tag) {
    const uint64_t s = (static_cast<uint64_t>(x) * UINT64_C(341873128712)) ^ (static_cast<uint64_t>(z) * UINT64_C(132897987541))
                       ^ (static_cast<uint64_t>(y) * UINT64_C(2685821657736338717)) ^ tag ^ UINT64_C(0x9E3779B97F4A7C15);
    return JavaRandom(static_cast<int64_t>(s));
}

/* ------------------------------------------------------------------------- RegularTree */
/* size hints (vegetation channels, SBW ABI 5): -1 = the variant the hash picked (every pre-ABI-5 tree), 0 small,
 * 1 medium, 2 large; the hash's variant still breaks ties so equal hints keep their variety */
int sizedVariant(int variant, int size, const int (&table)[3][2]) {
    if (size < 0 || size > 2) return variant;
    return table[size][variant & 1];
}

void regularTree(const Config& cfg, TreeBuf& out, int64_t wx, int64_t wy, int64_t wz, int size = -1) {
    JavaRandom rng = treeRandom(wx, wy, wz, UINT64_C(0xA5A5A5A5C3C3C3C3));
    static const int SIZED[3][2] = {{2, 2}, {0, 1}, {3, 1}};
    const int variant = sizedVariant(rng.nextInt(4), size, SIZED); /* ROUND, TALL, COMPACT, BIG_BRANCHED */
    int trunk, bottom, top;
    bool canLean;
    switch (variant) {
        case 0: trunk = 5; bottom = 3; top = 6; canLean = false; break;
        case 1: trunk = 6 + rng.nextInt(2); bottom = 4; top = 9; canLean = true; break;
        case 2: trunk = 3 + rng.nextInt(2); bottom = 2; top = 5; canLean = false; break;
        default: trunk = 6 + rng.nextInt(2); bottom = 3; top = 8; canLean = false; break;
    }
    if (wy + top >= cfg.maxY) return;
    const uint16_t wood = cfg.id[SBW_WOOD], leaves = cfg.id[SBW_LEAVES];
    for (int dy = 0; dy < trunk; dy++) out.put(wx, wy + dy, wz, wood);
    const int leanX = canLean ? rng.nextInt(3) - 1 : 0;
    const int leanZ = canLean ? rng.nextInt(3) - 1 : 0;
    const int64_t ccx = wx + leanX, ccz = wz + leanZ;
    auto radiusFor = [&](float t) {
        switch (variant) {
            case 0: return t < 0.85f ? 2 : 1;
            case 1: return t < 0.5f ? 2 : 1;
            case 2: return t < 0.6f ? 2 : 1;
            default: return t < 0.25f ? 2 : (t < 0.75f ? 3 : (t < 0.95f ? 2 : 1));
        }
    };
    auto gapFor = [&](int adx, int adz, int r) {
        const bool rim = adx == r || adz == r;
        switch (variant) {
            case 1: return rim ? 0.20f : 0.0f;
            case 2: return rim ? 0.10f : 0.0f;
            case 3: return rim ? 0.18f : 0.0f;
            default: return 0.0f;
        }
    };
    for (int dy = bottom; dy <= top; dy++) {
        const int64_t y = wy + dy;
        if (y >= cfg.maxY) continue;
        const float t = (top == bottom) ? 0.5f : static_cast<float>(dy - bottom) / static_cast<float>(top - bottom);
        const int r = radiusFor(t);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                const int adx = std::abs(dx), adz = std::abs(dz);
                if (adx == r && adz == r) continue;
                if (r >= 3 && adx + adz >= r + 2) continue;
                const int64_t x = ccx + dx, z = ccz + dz;
                if (x == wx && z == wz && y < wy + trunk) continue;
                const float gap = gapFor(adx, adz, r);
                if (gap > 0.0f && rng.nextFloat() < gap) continue;
                out.put(x, y, z, leaves);
            }
        }
        if (variant == 2 && dy == top) {
            const int64_t capY = y + 1;
            if (capY < cfg.maxY) out.put(ccx, capY, ccz, leaves);
        }
    }
    static const int DX[4] = {1, -1, 0, 0}, DZ[4] = {0, 0, 1, -1};
    if (variant == 3) {
        if (trunk >= 4) {
            const int64_t by = wy + trunk - 3;
            for (int d = 0; d < 4; d++) {
                out.put(wx + DX[d], by, wz + DZ[d], wood);
                out.put(wx + 2 * DX[d], by + 1, wz + 2 * DZ[d], wood);
            }
        }
    } else {
        const int count = variant == 1 ? 3 : 2;
        if (trunk >= 3) {
            const int64_t by = wy + trunk - 2;
            bool used[4] = {false, false, false, false};
            int placed = 0;
            while (placed < std::min(count, 4)) {
                const int idx = rng.nextInt(4);
                if (used[idx]) continue;
                used[idx] = true;
                out.put(wx + DX[idx], by, wz + DZ[idx], wood);
                placed++;
            }
        }
    }
}

/* ---------------------------------------------------------------------------- PineTree */
void pineTree(const Config& cfg, TreeBuf& out, int64_t wx, int64_t wy, int64_t wz, int size = -1) {
    JavaRandom rng = treeRandom(wx, wy, wz, UINT64_C(0xC0FFEEC0FFEEC0FF));
    static const int SIZED[3][2] = {{2, 2}, {0, 3}, {1, 1}};
    const int variant = sizedVariant(rng.nextInt(4), size, SIZED); /* CLASSIC, SLENDER, BUSHY, WISPY */
    int trunk, foliageStart;
    switch (variant) {
        case 1: trunk = 9 + rng.nextInt(3); foliageStart = 4; break;
        case 2: trunk = 6 + rng.nextInt(2); foliageStart = 2; break;
        case 3: trunk = 8 + rng.nextInt(3); foliageStart = 3; break;
        default: trunk = 7 + rng.nextInt(2); foliageStart = 2; break;
    }
    const int total = trunk + 2;
    if (wy + total >= cfg.maxY) return;
    const uint16_t pine = cfg.id[SBW_PINE], leaves = cfg.id[SBW_PINE_LEAVES];
    for (int dy = 0; dy < trunk; dy++) out.put(wx, wy + dy, wz, pine);
    const int layers = total - foliageStart;
    for (int i = 0; i < layers; i++) {
        const int64_t y = wy + foliageStart + i;
        if (y >= cfg.maxY) break;
        const float t = (layers <= 1) ? 1.0f : static_cast<float>(i) / static_cast<float>(layers - 1);
        int r;
        switch (variant) {
            case 1: r = i >= layers - 2 ? 0 : ((i & 1) == 0 ? 1 : 2); break;
            case 2: r = t < 0.75f ? 2 : (t < 0.95f ? 1 : 0); break;
            case 3: r = t < 0.35f ? 2 : (t < 0.85f ? 1 : 0); break;
            default: r = t < 0.30f ? 2 : (t < 0.85f ? 1 : 0); break;
        }
        const bool pinch = (variant == 0 || variant == 3) && (i & 1) == 1 && r > 1;
        if (pinch) r--;
        const bool trunkAtCenter = (y - wy) < trunk;
        if (r <= 0) {
            if (!trunkAtCenter) out.put(wx, y, wz, leaves);
            continue;
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                const int adx = std::abs(dx), adz = std::abs(dz);
                if (adx == r && adz == r) continue;
                if (dx == 0 && dz == 0 && trunkAtCenter) continue;
                const int rim = adx + adz;
                float gap;
                switch (variant) {
                    case 3: gap = (rim >= r + 1) ? 0.45f : 0.10f; break;
                    case 0: gap = (adx == r || adz == r) ? 0.18f : 0.0f; break;
                    case 1: gap = (rim >= r + 1) ? 0.30f : 0.0f; break;
                    default: gap = (adx == r && adz == r - 1) ? 0.15f : 0.0f; break;
                }
                if (gap > 0.0f && rng.nextFloat() < gap) continue;
                out.put(wx + dx, y, wz + dz, leaves);
            }
        }
    }
    if (wy + total < cfg.maxY) out.put(wx, wy + total, wz, leaves);
}

/* ----------------------------------------------------------------------------- ElmTree */
void elmTree(const Config& cfg, TreeBuf& out, int64_t wx, int64_t wy, int64_t wz, int size = -1) {
    JavaRandom rng = treeRandom(wx, wy, wz, UINT64_C(0x5EED1E1F5EED1E1F));
    static const int SIZED[3][2] = {{0, 1}, {0, 3}, {2, 2}};
    const int variant = sizedVariant(rng.nextInt(4), size, SIZED); /* CLASSIC, ASYMMETRIC, CROWN, WINDSWEPT */
    int trunk, lowerR, upperR, lean;
    switch (variant) {
        case 0: trunk = 7 + rng.nextInt(4); lowerR = 4; upperR = 3; lean = -1; break;
        case 1: trunk = 7 + rng.nextInt(4); lowerR = 4; upperR = 3; lean = rng.nextInt(4); break;
        case 2: trunk = 9 + rng.nextInt(3); lowerR = 4; upperR = 3; lean = -1; break;
        default: trunk = 8 + rng.nextInt(3); lowerR = 3; upperR = 2; lean = rng.nextInt(4); break;
    }
    if (wy + trunk + 6 >= cfg.maxY) return;
    const uint16_t log = cfg.id[SBW_ELM_WOOD_LOG], leaves = cfg.id[SBW_ELM_LEAVES];
    static const int DX[4] = {1, -1, 0, 0}, DZ[4] = {0, 0, 1, -1};
    for (int dy = 0; dy < trunk; dy++) out.put(wx, wy + dy, wz, log);
    /* branches */
    const int64_t bl = wy + trunk - 3;
    if (bl + 3 < cfg.maxY) {
        switch (variant) {
            case 0:
                for (int64_t by = bl; by < bl + 3; by++) {
                    for (int d = 0; d < 4; d++) out.put(wx + DX[d], by, wz + DZ[d], log);
                    if (by == bl + 1) {
                        out.put(wx + 1, by, wz + 1, log);
                        out.put(wx + 1, by, wz - 1, log);
                        out.put(wx - 1, by, wz + 1, log);
                        out.put(wx - 1, by, wz - 1, log);
                    }
                }
                break;
            case 1: {
                const int count = 2 + rng.nextInt(2);
                bool dirs[4] = {false, false, false, false};
                int placed = 0;
                while (placed < count) {
                    const int idx = rng.nextInt(4);
                    if (!dirs[idx]) { dirs[idx] = true; placed++; }
                }
                for (int64_t by = bl; by < bl + 3; by++)
                    for (int d = 0; d < 4; d++)
                        if (dirs[d]) out.put(wx + DX[d], by, wz + DZ[d], log);
                break;
            }
            case 2: {
                const int count = 2 + rng.nextInt(2);
                bool used[4] = {false, false, false, false};
                int placed = 0;
                while (placed < count) {
                    const int idx = rng.nextInt(4);
                    if (used[idx]) continue;
                    used[idx] = true;
                    out.put(wx + DX[idx], bl + 2, wz + DZ[idx], log);
                    placed++;
                }
                break;
            }
            default: {
                const int lx = DX[lean], lz = DZ[lean];
                out.put(wx + lx, bl, wz + lz, log);
                out.put(wx + 2 * lx, bl + 1, wz + 2 * lz, log);
                out.put(wx + 2 * lx, bl + 2, wz + 2 * lz, log);
                out.put(wx - lz, bl + 1, wz + lx, log);
                break;
            }
        }
    }
    auto dotDir = [](int dx, int dz, int dir) {
        auto sgn = [](int v) { return (v > 0) - (v < 0); };
        switch (dir) {
            case 0: return sgn(dx);
            case 1: return -sgn(dx);
            case 2: return sgn(dz);
            case 3: return -sgn(dz);
            default: return 0;
        }
    };
    /* lower canopy */
    for (int dy = trunk - 1; dy <= trunk + 2; dy++) {
        const int64_t y = wy + dy;
        if (y >= cfg.maxY) continue;
        for (int dx = -lowerR; dx <= lowerR; dx++) {
            for (int dz = -lowerR; dz <= lowerR; dz++) {
                const auto dist = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
                if (dist > static_cast<float>(lowerR) * 0.9f) continue;
                if (dx == 0 && dz == 0 && dy < trunk + 1) continue;
                const bool outer = dist > static_cast<float>(lowerR) * 0.7f;
                bool skip;
                switch (variant) {
                    case 0: skip = outer && rng.nextFloat() < 0.30f; break;
                    case 2: skip = outer && rng.nextFloat() < 0.18f; break;
                    case 1: {
                        const int dot = dotDir(dx, dz, lean);
                        if (dot < 0) skip = rng.nextFloat() < 0.55f;
                        else if (dot > 0) skip = outer && rng.nextFloat() < 0.20f;
                        else skip = outer && rng.nextFloat() < 0.30f;
                        break;
                    }
                    default: {
                        const int dot = dotDir(dx, dz, lean);
                        if (dot < 0) skip = rng.nextFloat() < 0.65f;
                        else if (dot > 0) skip = outer && rng.nextFloat() < 0.15f;
                        else skip = outer && rng.nextFloat() < 0.35f;
                        break;
                    }
                }
                if (skip) continue;
                out.put(wx + dx, y, wz + dz, leaves);
            }
        }
    }
    /* upper canopy */
    for (int dy = trunk + 3; dy <= trunk + 5; dy++) {
        const int64_t y = wy + dy;
        if (y >= cfg.maxY) continue;
        for (int dx = -upperR; dx <= upperR; dx++) {
            for (int dz = -upperR; dz <= upperR; dz++) {
                const auto dist = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
                if (dist > static_cast<float>(upperR) * 0.8f) continue;
                const bool thin = rng.nextFloat() < 0.20f;
                if (thin && !(dx == 0 && dz == 0)) continue;
                out.put(wx + dx, y, wz + dz, leaves);
            }
        }
    }
    /* high inner branches */
    {
        const int64_t innerY = wy + trunk;
        if (innerY < cfg.maxY) {
            const int count = 1 + rng.nextInt(2);
            bool used[4] = {false, false, false, false};
            int placed = 0;
            while (placed < count) {
                const int idx = rng.nextInt(4);
                if (used[idx]) continue;
                used[idx] = true;
                out.put(wx + DX[idx], innerY, wz + DZ[idx], log);
                placed++;
            }
        }
    }
    /* hidden core */
    for (int dy = trunk; dy <= trunk + 3; dy++) {
        const int64_t y = wy + dy;
        if (y >= cfg.maxY) break;
        out.put(wx, y, wz, log);
    }
    /* top cap */
    const int64_t topY = wy + trunk + 6;
    if (topY < cfg.maxY) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (!(std::abs(dx) == 1 && std::abs(dz) == 1)) out.put(wx + dx, topY, wz + dz, leaves);
    }
    pruneUnanchored(cfg, out);
}

/* ------------------------------------------------------------------------- CypressTree */
void cypressDisc(const Config& cfg, TreeBuf& out, JavaRandom& rng, int64_t cx, int64_t y, int64_t cz, int radius) {
    const double limit = (radius + 0.3) * (radius + 0.3);
    for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
            const double d2 = dx * dx + dz * dz;
            if (d2 > limit) continue;
            const bool rim = d2 > (radius - 0.7) * (radius - 0.7);
            if (rim && rng.nextFloat() < 0.35f) continue;
            out.put(cx + dx, y, cz + dz, cfg.id[SBW_CYPRESS_LEAVES]);
        }
    }
}

void cypressTree(const Config& cfg, TreeBuf& out, int64_t wx, int64_t wy, int64_t wz, int size = -1) {
    constexpr int MAX_HEIGHT = 28, MAX_PAD_RADIUS = 4, TIER_SPACING = 3;
    constexpr double CANOPY_SHARE = 0.65;
    if (wy + MAX_HEIGHT >= cfg.maxY) return;
    JavaRandom rng = treeRandom(wx, wy, wz, UINT64_C(0xC1A55C1A55C1A55C));
    const int r100 = rng.nextInt(100);
    const int v = size == 0 ? 0 : size == 2 ? 2 : r100 < 35 ? 0 : r100 < 80 ? 1 : 2; /* SMALL, MEDIUM, LARGE */
    static const int MIN_TRUNK[3] = {10, 14, 19}, TRUNK_RANGE[3] = {4, 5, 6}, MIN_TIERS[3] = {2, 3, 4},
                     TIER_RANGE[3] = {2, 2, 2}, FLARE[3] = {1, 2, 3}, MAX_BRANCH[3] = {2, 3, 3};
    const int trunk = MIN_TRUNK[v] + rng.nextInt(TRUNK_RANGE[v]);
    const int64_t canopyBottom = wy + trunk - static_cast<int64_t>(std::llround(trunk * CANOPY_SHARE));
    const int64_t canopyTop = wy + trunk - 3;
    const int tiers = static_cast<int>(std::min<int64_t>(MIN_TIERS[v] + rng.nextInt(TIER_RANGE[v]),
                                                         (canopyTop - canopyBottom) / TIER_SPACING + 1));
    const int64_t top = wy + trunk;
    double angle = rng.nextDouble() * PI * 2;
    std::vector<std::array<int64_t, 3>> tips(static_cast<size_t>(std::max(0, tiers)));
    for (int i = 0; i < tiers; i++) {
        const float t = tiers == 1 ? 0.0f : static_cast<float>(i) / static_cast<float>(tiers - 1);
        const int64_t y = canopyBottom + stonebreak::javaRoundFloat(t * static_cast<float>(canopyTop - canopyBottom));
        const int length = std::max(1, stonebreak::javaRoundFloat(static_cast<float>(MAX_BRANCH[v]) * (1.0f - 0.5f * t)));
        const int radius = std::max(2, stonebreak::javaRoundFloat(static_cast<float>(MAX_PAD_RADIUS) - t * 2.0f)) - (v == 0 ? 1 : 0);
        tips[static_cast<size_t>(i)] = {wx + std::llround(std::cos(angle) * length), y, wz + std::llround(std::sin(angle) * length)};
        const auto& tp = tips[static_cast<size_t>(i)];
        const int pr = std::max(2, radius);
        cypressDisc(cfg, out, rng, tp[0], tp[1], tp[2], pr);
        cypressDisc(cfg, out, rng, tp[0], tp[1] + 1, tp[2], pr - 1);
        angle += PI * (0.55 + rng.nextDouble() * 0.35);
    }
    cypressDisc(cfg, out, rng, wx, top - 1, wz, 2);
    cypressDisc(cfg, out, rng, wx, top, wz, 1);
    out.put(wx, top + 1, wz, cfg.id[SBW_CYPRESS_LEAVES]);
    const uint16_t log = cfg.id[SBW_CYPRESS_LOG];
    for (int dy = 0; dy < FLARE[v]; dy++) {
        out.put(wx + 1, wy + dy, wz, log);
        out.put(wx - 1, wy + dy, wz, log);
        out.put(wx, wy + dy, wz + 1, log);
        out.put(wx, wy + dy, wz - 1, log);
    }
    for (int64_t y = wy; y < top; y++) out.put(wx, y, wz, log);
    for (const auto& tip : tips) {
        int64_t x = wx, z = wz;
        while (x != tip[0] || z != tip[2]) {
            if (std::llabs(tip[0] - x) >= std::llabs(tip[2] - z)) x += (tip[0] > x) - (tip[0] < x);
            else z += (tip[2] > z) - (tip[2] < z);
            out.put(x, tip[1], z, log);
        }
    }
    pruneUnanchored(cfg, out);
}

uint64_t featurePriority(int64_t seed, int64_t x, int64_t y, int64_t z) {
    return stonebreak::mix64(static_cast<uint64_t>(seed) ^ stonebreak::mix64(
        static_cast<uint64_t>(x) * UINT64_C(0x9E3779B97F4A7C15) ^ static_cast<uint64_t>(y) * UINT64_C(0xC2B2AE3D27D4EB4F)
        ^ static_cast<uint64_t>(z) * UINT64_C(0x165667B19E3779F9)));
}

/* per-column facts the features read (from the profiles, never the world) */
struct ColumnFacts {
    int32_t h = 0;
    int32_t water = NO_WATER;
    int biome = 0;
    bool uncarved = false;
    uint16_t topBlock = 0;  /* the block at h - 1 after pools (meaningful when uncarved) */
    int pool = 0;
    uint8_t veg[8] = {255, 255, 255, 255, 255, 255, 255, 255}; /* surface slots 0..7 (vegetation), 255 = none */
};

class FactSource {
public:
    explicit FactSource(Generator& g) : g_(g) {}
    ColumnFacts at(int64_t x, int64_t z) {
        const int64_t cx = x >> 5, cz = z >> 5;
        Entry* e = nullptr;
        for (auto& c : cache_) if (c.cx == cx && c.cz == cz && c.base) { e = &c; break; }
        if (e == nullptr) {
            e = &cache_[next_++ % cache_.size()];
            e->cx = cx;
            e->cz = cz;
            e->base = g_.base(cx, cz);
            e->carved = g_.carved(cx, cz);
            e->pools = nullptr;
            e->poolsLoaded = false;
            if (!e->base || !e->carved) throw std::runtime_error("column profile unavailable");
        }
        const int col = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
        ColumnFacts f;
        f.h = e->base->h[col];
        f.water = e->base->water[col];
        f.biome = e->base->biome[col];
        f.uncarved = e->carved->carvedH[col] == f.h;
        if ((g_.cfg.flags & SBW_FLAG_VEGETATION) != 0)   /* else 255: the biome rules, as before the channels */
            for (int k = 0; k < 8; k++) f.veg[k] = e->base->surf[k][col];
        f.topBlock = f.uncarved ? e->carved->top[col] : g_.cfg.id[SBW_AIR];
        if (f.biome == SBW_SWAMP) {
            if (!e->poolsLoaded) {
                e->pools = g_.pools(cx, cz);
                e->poolsLoaded = true;
            }
            f.pool = e->pools ? e->pools->depth[col] : 0;
            if (f.pool > 0) f.topBlock = g_.cfg.id[SBW_WATER];
        }
        return f;
    }

private:
    struct Entry {
        int64_t cx = INT64_MIN, cz = INT64_MIN;
        std::shared_ptr<const BaseTile> base;
        std::shared_ptr<const CarvedTile> carved;
        std::shared_ptr<const PoolTile> pools;
        bool poolsLoaded = false;
    };
    Generator& g_;
    std::array<Entry, 9> cache_{};
    size_t next_ = 0;
};

bool rolls(const DeterministicRandom& r, int64_t x, int64_t z, int32_t f, float p) {
    if (p <= 0.0f) return false;
    if (p >= 1.0f) return true;
    return r.getFloat(x, z, f) < p;
}

/* ForestDensityField */
float forestMultiplier(const DeterministicRandom& r, int64_t x, int64_t z) {
    static const int32_t H = javaStringHash("forest_density");
    const float d = forestLattice(r, x, z, 96, H);
    if (d <= 0.40f) return 0.0f;
    return (d - 0.40f) * 5.8f;
}
float elmProbability(const DeterministicRandom& r, int64_t x, int64_t z) {
    static const int32_t H = javaStringHash("elm_pocket");
    const float d = forestLattice(r, x, z, 32, H);
    if (d <= 0.55f) return 0.0f;
    return (d - 0.55f) / (1.0f - 0.55f) * 0.85f;
}

/* ------------------------------------------------------------------ vegetation channels (SBW ABI 5) */
/* The vegetation node's surface channels of one column (Python world/vegetation_node.py; trained on ETH canopy
 * height + ESA WorldCover). present = the column carries them (DaedalusTGM-Exp worlds with veg.pt); otherwise
 * every rule below is skipped and the column keeps the pre-ABI-5 vegetation exactly (Standard worlds, golden). */
struct Veg {
    bool present = false;
    float cover = 0, height = 0, clearing = 0, grass = 0, shrub = 0, moss = 0, bare = 0, wetland = 0;
};
Veg vegOf(const uint8_t* v) {
    Veg g;
    if (v == nullptr || v[SBW_SURF_CANOPY_COVER] == SBW_SURF_NO_DATA) return g;
    auto frac = [&](int s) { return v[s] == SBW_SURF_NO_DATA ? 0.0f : static_cast<float>(v[s]) / 254.0f; };
    g.present = true;
    g.cover = frac(SBW_SURF_CANOPY_COVER);
    g.height = v[SBW_SURF_CANOPY_HEIGHT] == SBW_SURF_NO_DATA ? 0.0f : v[SBW_SURF_CANOPY_HEIGHT] * 0.25f;
    g.clearing = frac(SBW_SURF_CLEARING);
    g.grass = frac(SBW_SURF_GROUND_GRASS);
    g.shrub = frac(SBW_SURF_GROUND_SHRUB);
    g.moss = frac(SBW_SURF_GROUND_MOSS);
    g.bare = frac(SBW_SURF_GROUND_BARE);
    g.wetland = frac(SBW_SURF_GROUND_WETLAND);
    return g;
}
/* forestLattice's values (bilinear + smoothstep over uniform nodes) mapped to ~uniform [0, 1]: the quantiles at k/16 */
float latticeUniform(float n) {
    static const float Q[17] = {0.0f, 0.1654f, 0.2348f, 0.2893f, 0.3368f, 0.3803f, 0.4214f, 0.4610f, 0.4999f, 0.5389f,
                                0.5785f, 0.6196f, 0.6631f, 0.7109f, 0.7654f, 0.8348f, 1.0f};
    if (n <= 0.0f) return 0.0f;
    if (n >= 1.0f) return 1.0f;
    int i = 0;
    while (i < 15 && n > Q[i + 1]) i++;
    return (static_cast<float>(i) + (n - Q[i]) / std::max(1e-6f, Q[i + 1] - Q[i])) / 16.0f;
}
/* Peak tree chance per column in closed forest (canopy cover 1): a tree every ~11 columns, crowns touching. */
constexpr float VEG_TREE_MAX = 0.09f;
/* Clearings: lattice noise of this spacing (blocks); the share under the column's clearing value is open ground. */
constexpr int VEG_CLEARING_SPACING = 14;
/* Effective canopy density of a column: clearing patches take `clearing` of the area, the forest between them
 * holds the rest of the cover; a fine lattice adds stand-scale patchiness. */
float vegDensity(const DeterministicRandom& r, int64_t x, int64_t z, const Veg& v) {
    static const int32_t H_CLEAR = javaStringHash("veg_clearing"), H_PATCH = javaStringHash("veg_patch");
    const float k = std::min(0.85f, v.clearing);
    const float n = latticeUniform(forestLattice(r, x, z, VEG_CLEARING_SPACING, H_CLEAR));
    if (n < k) return v.cover * 0.06f;                       /* in a clearing: the odd lone tree */
    const float inside = std::min(1.0f, v.cover / std::max(0.15f, 1.0f - k));
    const float patch = 0.75f + 0.5f * forestLattice(r, x, z, 6, H_PATCH);
    return std::min(1.0f, inside * patch);
}
/* SBW_FLAG_VEG_OPEN_LAND (WorldFeatureVersion V4, 2026-11-20): the node predicts natural vegetation and its cover
 * (share of ground under canopy >= 5 m) saturates near 1 in any closed forest, so V3 temperate worlds came out ~90 %
 * dense forest. V4 opens the land up while closed forest stays closed:
 *  - meadows: big open patches (a 40-block lattice) take a per-biome share of wooded land (plains 20 %, meadow
 *    35 %, swamp 12 %, snowy plains 15 %, taiga / tundra / peaks 8 %; scaled in from cover 0.3 to 0.7, so open
 *    savanna keeps its scattered trees) with only the odd lone tree;
 *  - a steeper cover curve (1 stays 1, 0.8 -> 0.77, 0.5 -> 0.45, 0.1 -> 0.10: low cover nearly untouched;
 *    2026-11-20 second pass: meadow shares and curve halved, halfway back toward V3's density), so
 *    only near-closed canopy reaches the full density. */
constexpr int VEG_MEADOW_SPACING = 40;
float vegMeadowShare(int biome) {
    switch (biome) {
        case SBW_PLAINS: return 0.20f;
        case SBW_MEADOW: return 0.35f;
        case SBW_SWAMP: return 0.12f;
        case SBW_SNOWY_PLAINS: return 0.15f;
        case SBW_TAIGA: case SBW_TUNDRA: case SBW_STONY_PEAKS: return 0.08f;
        default: return 0.15f;
    }
}
bool vegInMeadow(const DeterministicRandom& r, int64_t x, int64_t z, int biome, float cover) {
    static const int32_t H_MEADOW = javaStringHash("veg_meadow");
    const float share = vegMeadowShare(biome) * std::clamp((cover - 0.3f) / 0.4f, 0.0f, 1.0f);
    return share > 0.0f && latticeUniform(forestLattice(r, x, z, VEG_MEADOW_SPACING, H_MEADOW)) < share;
}
float vegDensityOpen(const DeterministicRandom& r, int64_t x, int64_t z, const Veg& v, int biome) {
    const float c = v.cover - 0.2f * v.cover * (1.0f - v.cover) * std::min(1.0f, v.cover / 0.4f);
    if (vegInMeadow(r, x, z, biome, v.cover)) return 0.1f * c;   /* a meadow: the odd lone tree */
    Veg o = v;
    o.cover = c;
    return vegDensity(r, x, z, o);
}
/* real canopy height (m) -> tree size class: short scrub / savanna trees, mid forest, tall forest */
int vegSize(const Veg& v) { return v.height < 9.0f ? 0 : v.height < 21.0f ? 1 : 2; }

/* resolution of a feature set into final cells */
struct Resolved {
    struct Cell {
        uint16_t id;
        uint16_t snow;
        int32_t owner; /* feature index */
    };
    std::unordered_map<uint64_t, Cell> cells;
};

uint64_t cellKey(int64_t x, int64_t y, int64_t z) { return TreeBuf::key(x, y, z); }

} // namespace

/* ------------------------------------------------------------------------- tree probe */
int Generator::treeAt(int64_t x, int64_t z, uint16_t surface, int biome, int& trunk) {
    if (cfg.channelWorld() && (cfg.flags & SBW_FLAG_VEGETATION) != 0) {   /* vegetation channels (ABI 5; Standard 2) */
        auto b = base(x >> 5, z >> 5);
        if (b) {
            const int col = colIdx(static_cast<int>(x & 31), static_cast<int>(z & 31));
            if (b->surf[SBW_SURF_CANOPY_COVER][col] != SBW_SURF_NO_DATA) {
                uint8_t v[8];
                for (int k = 0; k < 8; k++) v[k] = b->surf[k][col];
                int size = -1;
                return treeAtVeg(x, z, surface, biome, v, trunk, size);
            }
        }
    }
    return treeAtRule(x, z, surface, biome, trunk);
}

int Generator::treeAtVeg(int64_t x, int64_t z, uint16_t surface, int biome, const uint8_t* vb, int& trunk, int& size) {
    static const int32_t H_VTREE = javaStringHash("veg_tree"), H_TYPE = javaStringHash("tree_type"),
                         H_ELM = javaStringHash("elm_tree");
    const Veg v = vegOf(vb);
    const bool grass = surface == cfg.id[SBW_GRASS], snowy = surface == cfg.id[SBW_SNOWY_DIRT];
    const bool swampy = surface == cfg.id[SBW_SWAMPY_GRASS] || surface == cfg.id[SBW_WATER];
    int kind = -1;
    switch (biome) {
        case SBW_PLAINS: case SBW_MEADOW: if (grass) kind = SBW_TREE_OAK; break;
        case SBW_SNOWY_PLAINS: case SBW_TAIGA: case SBW_TUNDRA: case SBW_STONY_PEAKS:
            if (snowy || grass) kind = SBW_TREE_PINE;
            break;
        case SBW_SWAMP: kind = swampy ? SBW_TREE_CYPRESS : grass ? SBW_TREE_OAK : -1; break;
        default: break;   /* deserts, beaches, ice, badlands, ocean: no trees (their ground is sand / ice / rock) */
    }
    if (kind < 0 || v.cover <= 0.0f) return -1;
    const bool open = (cfg.flags & SBW_FLAG_VEG_OPEN_LAND) != 0;
    const float dens = open ? vegDensityOpen(drand, x, z, v, biome) : vegDensity(drand, x, z, v);
    const float p = VEG_TREE_MAX * std::pow(dens, 1.25f) * (kind == SBW_TREE_CYPRESS ? 0.5f : 1.0f);
    if (!rolls(drand, x, z, H_VTREE, p)) return -1;
    size = vegSize(v);
    if (kind == SBW_TREE_OAK) {
        /* elm pockets as before, and tall broadleaf canopy (> 24 m) leans elm (the big crowns) */
        const float elm = std::min(0.9f, elmProbability(drand, x, z) + (v.height > 24.0f ? 0.35f : 0.0f));
        if (size > 0 && elm > 0.0f && rolls(drand, x, z, H_TYPE, elm)) {
            trunk = 8 + drand.at(x, z, H_ELM).nextInt(5);
            return SBW_TREE_ELM;
        }
        trunk = size == 0 ? 4 : size == 1 ? 5 : 7;
        return SBW_TREE_OAK;
    }
    if (kind == SBW_TREE_PINE) {
        trunk = size == 0 ? 6 : size == 1 ? 7 : 10;
        return SBW_TREE_PINE;
    }
    trunk = size == 0 ? 11 : size == 1 ? 16 : 21;
    return SBW_TREE_CYPRESS;
}

int Generator::treeAtRule(int64_t x, int64_t z, uint16_t surface, int biome, int& trunk) {
    static const int32_t H_TREE = javaStringHash("tree"), H_TYPE = javaStringHash("tree_type"),
                         H_ELM = javaStringHash("elm_tree"), H_PINE = javaStringHash("pine_tree"),
                         H_TAIGA = javaStringHash("taiga_pine_tree"), H_MEADOW = javaStringHash("meadow_tree"),
                         H_MEADOW_ELM = javaStringHash("meadow_elm"), H_CYPRESS = javaStringHash("cypress_tree");
    const float forest = forestMultiplier(drand, x, z);
    if (forest <= 0.0f) return -1;
    if (biome == SBW_PLAINS && surface == cfg.id[SBW_GRASS] && rolls(drand, x, z, H_TREE, 0.01f * forest)) {
        const float elm = elmProbability(drand, x, z);
        if (elm > 0.0f && rolls(drand, x, z, H_TYPE, elm)) {
            trunk = 8 + drand.at(x, z, H_ELM).nextInt(5);
            return SBW_TREE_ELM;
        }
        trunk = 5;
        return SBW_TREE_OAK;
    }
    if (biome == SBW_SNOWY_PLAINS && surface == cfg.id[SBW_SNOWY_DIRT] && rolls(drand, x, z, H_PINE, 0.015f * forest)) {
        trunk = 7;
        return SBW_TREE_PINE;
    }
    if (biome == SBW_TAIGA && surface == cfg.id[SBW_SNOWY_DIRT] && rolls(drand, x, z, H_TAIGA, 0.03f * forest)) {
        trunk = 7;
        return SBW_TREE_PINE;
    }
    if (biome == SBW_MEADOW && surface == cfg.id[SBW_GRASS] && rolls(drand, x, z, H_MEADOW, 0.002f * forest)) {
        trunk = 8 + drand.at(x, z, H_MEADOW_ELM).nextInt(5);
        return SBW_TREE_ELM;
    }
    if (biome == SBW_SWAMP && (surface == cfg.id[SBW_SWAMPY_GRASS] || surface == cfg.id[SBW_WATER])
            && rolls(drand, x, z, H_CYPRESS, 0.02f * forest)) {
        trunk = 16;
        return SBW_TREE_CYPRESS;
    }
    return -1;
}

/* ---------------------------------------------------------------------- feature collection */
std::shared_ptr<const FeatureTile> Generator::features(int64_t cx, int64_t cz) {
    return featureCache.get(cx, cz, [this](int64_t a, int64_t b) {
        auto t = std::make_shared<FeatureTile>();
        collectFeatures(a * CE, b * CE, a * CE + CE, b * CE + CE, t->fs);
        collectBoulders(a * CE, b * CE, a * CE + CE, b * CE + CE, t->fs); /* SBW_FLAG_BOULDERS, boulders.cpp */
        t->fs.shrink_to_fit();
        return t;
    });
}

void Generator::gatherFeatures(int64_t x0, int64_t z0, int64_t x1, int64_t z1, FeatureSet& out) {
    out.tiles.clear();
    out.fs.clear();
    for (int64_t cz = z0 >> 5; cz <= (z1 - 1) >> 5; cz++) {
        for (int64_t cx = x0 >> 5; cx <= (x1 - 1) >> 5; cx++) {
            auto t = features(cx, cz);
            if (!t) throw std::runtime_error("column profile unavailable");
            for (const Feature& f : t->fs) {
                if (f.ax >= x0 && f.ax < x1 && f.az >= z0 && f.az < z1) out.fs.push_back(&f);
            }
            out.tiles.push_back(std::move(t));
        }
    }
}

void Generator::collectFeatures(int64_t x0, int64_t z0, int64_t x1, int64_t z1, std::vector<Feature>& out) {
    static const int32_t H_FLOWER = javaStringHash("flower"), H_FLOWER_TYPE = javaStringHash("flower_type"),
                         H_FLOWER_SUB = javaStringHash("flower_subtype"), H_CACTUS = javaStringHash("cactus"),
                         H_CACTUS_H = javaStringHash("cactus_height"), H_SNOW_ICE = javaStringHash("snow_ice_feature"),
                         H_SNOW_LAYERS = javaStringHash("snow_layers"), H_CLAY = javaStringHash("clay_patch"),
                         H_CLAY_SIZE = javaStringHash("clay_patch_size"), H_CLAY_PLACE = javaStringHash("clay_place"),
                         H_TUNDRA = javaStringHash("tundra_feature"), H_TAIGA_SNOW = javaStringHash("taiga_snow"),
                         H_STONY = javaStringHash("stony_peaks_feature"), H_GLACIAL = javaStringHash("glacial_feature"),
                         H_BADLANDS = javaStringHash("badlands_feature");
    FactSource facts(*this);
    const uint16_t AIRID = cfg.id[SBW_AIR];
    (void) AIRID;
    for (int64_t z = z0; z < z1; z++) {
        for (int64_t x = x0; x < x1; x++) {
            const ColumnFacts f = facts.at(x, z);
            const int64_t h = f.h;
            if (h >= cfg.maxY) continue;
            if (f.water != NO_WATER && f.water > h) continue; /* submerged */
            const uint16_t surface = f.topBlock;
            /* trees */
            if (f.uncarved) {
                int trunk = 0, size = -1;
                const int kind = f.veg[SBW_SURF_CANOPY_COVER] != SBW_SURF_NO_DATA
                                     ? treeAtVeg(x, z, surface, f.biome, f.veg, trunk, size)
                                     : treeAtRule(x, z, surface, f.biome, trunk);
                if (kind >= 0) {
                    int64_t base = h;
                    bool ok = true;
                    if (kind == SBW_TREE_CYPRESS && surface == cfg.id[SBW_WATER]) {
                        base = h - 1;
                        ok = f.pool == 1; /* a 2-deep pool core has water under the trunk */
                    }
                    if (ok) {
                        TreeBuf buf;
                        int maxH = kind == SBW_TREE_OAK ? 13 : kind == SBW_TREE_PINE ? 14 : kind == SBW_TREE_ELM ? 18 : 28;
                        if (base + maxH < cfg.maxY) {
                            if (kind == SBW_TREE_OAK) regularTree(cfg, buf, x, base, z, size);
                            else if (kind == SBW_TREE_ELM) elmTree(cfg, buf, x, base, z, size);
                            else if (kind == SBW_TREE_PINE) pineTree(cfg, buf, x, base, z, size);
                            else cypressTree(cfg, buf, x, base, z, size);
                        }
                        if (!buf.cells.empty()) {
                            Feature ft;
                            ft.kind = Feature::TREE;
                            ft.ax = x; ft.ay = h; ft.az = z;
                            ft.priority = featurePriority(cfg.seed, x, base, z);
                            ft.cells = std::move(buf.cells);
                            out.push_back(std::move(ft));
                        }
                    }
                }
            }
            if (!f.uncarved && f.pool == 0) continue; /* every remaining feature reads the uncarved top */
            Feature ft;
            ft.ax = x; ft.ay = h; ft.az = z;
            ft.priority = featurePriority(cfg.seed, x, h, z);
            /* ground vegetation from the vegetation channels (ABI 5): replaces the biome flower rule there */
            Veg gv = vegOf(f.veg);
            /* V4 meadows: open sky, so grass tufts and flowers as on open grassland and no understorey bushes */
            if (gv.present && (cfg.flags & SBW_FLAG_VEG_OPEN_LAND) != 0 && vegInMeadow(drand, x, z, f.biome, gv.cover))
                gv.cover *= 0.1f;
            if (gv.present && f.uncarved) {
                static const int32_t H_VBARE = javaStringHash("veg_bare"), H_VWET = javaStringHash("veg_wetland"),
                                     H_VBUSH = javaStringHash("veg_bush"), H_VBUSH_SHAPE = javaStringHash("veg_bush_shape"),
                                     H_VPLANT = javaStringHash("veg_plant"), H_VFLOWER = javaStringHash("veg_flower");
                const bool grassTop = surface == cfg.id[SBW_GRASS], snowyTop = surface == cfg.id[SBW_SNOWY_DIRT];
                bool swapped = false;
                if (grassTop) {   /* bare earth and wet meadow patches (lattice patches, share ~ the channel) */
                    if (latticeUniform(forestLattice(drand, x, z, 7, H_VBARE)) < gv.bare * 0.85f) {
                        ft.kind = Feature::SWAP;
                        ft.cells = {{x, h - 1, z, cfg.id[SBW_DIRT], 0}};
                        out.push_back(ft);
                        swapped = true;
                    } else if (latticeUniform(forestLattice(drand, x, z, 9, H_VWET)) < gv.wetland * 0.8f) {
                        ft.kind = Feature::SWAP;
                        ft.cells = {{x, h - 1, z, cfg.id[SBW_SWAMPY_GRASS], 0}};
                        out.push_back(ft);
                        swapped = true;
                    }
                }
                /* bushes: shrubland, plus a thin understorey under canopy (a stub log and a leaf cap: leaves stay
                 * within reach of their log) */
                const float bush = 0.035f * gv.shrub + 0.003f * gv.cover;
                if ((grassTop || snowyTop) && h + 2 < cfg.maxY && rolls(drand, x, z, H_VBUSH, bush)) {
                    const uint16_t wood = cfg.id[snowyTop ? SBW_PINE : SBW_WOOD];
                    const uint16_t leaf = cfg.id[snowyTop ? SBW_PINE_LEAVES : SBW_LEAVES];
                    Feature b;
                    b.kind = Feature::TREE;
                    b.ax = x; b.ay = h; b.az = z;
                    b.priority = ft.priority;
                    b.cells = {{x, h, z, wood, 0}, {x, h + 1, z, leaf, 0}};
                    const int bushShape = drand.getInt(x, z, H_VBUSH_SHAPE, 4);
                    static const int DX[4] = {1, -1, 0, 0}, DZ[4] = {0, 0, 1, -1};
                    for (int d = 0; d < 4; d++) {
                        if (bushShape == 0 && d >= 2) continue;   /* a narrow one now and then */
                        b.cells.push_back({x + DX[d], h, z + DZ[d], leaf, 0});
                    }
                    if (bushShape == 3) b.cells.push_back({x + 1, h + 1, z, leaf, 0});
                    out.push_back(std::move(b));
                } else if (grassTop && !swapped) {
                    /* grass tufts and flowers by the grass share, fewer in deep shade */
                    const float wild = 0.22f * gv.grass * (1.0f - 0.6f * gv.cover) + 0.02f * (1.0f - gv.bare);
                    const float flower = 0.05f * gv.grass * (1.0f - gv.cover);
                    const float roll = drand.getFloat(x, z, H_VPLANT);
                    if (roll < flower + wild) {
                        uint16_t id = cfg.id[SBW_WILDGRASS];
                        if (roll < flower) id = drand.getBoolean(x, z, H_VFLOWER) ? cfg.id[SBW_DANDELION] : cfg.id[SBW_ROSE];
                        ft.kind = Feature::PLANT;
                        ft.cells = {{x, h, z, id, 0}};
                        out.push_back(ft);
                    }
                }
            }
            /* flowers */
            const float fchance = gv.present ? 0.0f : f.biome == SBW_PLAINS ? 0.08f : f.biome == SBW_MEADOW ? 0.25f : 0.0f;
            if (fchance > 0.0f && surface == cfg.id[SBW_GRASS] && drand.shouldGenerate(x, z, H_FLOWER, fchance)) {
                uint16_t flower;
                if (drand.getBoolean(x, z, H_FLOWER_TYPE)) {
                    flower = drand.getBoolean(x, z, H_FLOWER_SUB) ? cfg.id[SBW_ROSE] : cfg.id[SBW_WILDGRASS];
                } else {
                    flower = cfg.id[SBW_DANDELION];
                }
                ft.kind = Feature::PLANT;
                ft.cells = {{x, h, z, flower, 0}};
                out.push_back(ft);
            }
            /* cactus */
            if ((f.biome == SBW_DESERT || f.biome == SBW_RED_SAND_DESERT)
                    && (surface == cfg.id[SBW_SAND] || surface == cfg.id[SBW_RED_SAND])
                    && drand.getFloat(x, z, H_CACTUS) < 0.003f) {
                const int height = drand.getFloat(x, z, H_CACTUS_H) < 0.5f ? 2 : 3;
                ft.kind = Feature::PLANT;
                ft.cells.clear();
                for (int i = 0; i < height && h + i < cfg.maxY; i++) ft.cells.push_back({x, h + i, z, cfg.id[SBW_CACTUS], 0});
                if (!ft.cells.empty()) out.push_back(ft);
            }
            /* decorations */
            auto snow = [&](int minLayers, int extra) {
                const int layers = minLayers + drand.getInt(x, z, H_SNOW_LAYERS, extra);
                ft.kind = Feature::PLANT;
                ft.cells = {{x, h, z, cfg.id[SBW_SNOW], static_cast<uint16_t>(layers)}};
                out.push_back(ft);
            };
            auto plantAt = [&](uint16_t id) {
                ft.kind = Feature::PLANT;
                ft.cells = {{x, h, z, id, 0}};
                out.push_back(ft);
            };
            auto swap = [&](uint16_t id) {
                ft.kind = Feature::SWAP;
                ft.cells = {{x, h - 1, z, id, 0}};
                out.push_back(ft);
            };
            switch (f.biome) {
                case SBW_SNOWY_PLAINS:
                    if (surface == cfg.id[SBW_SNOWY_DIRT]) {
                        const float roll = drand.getFloat(x, z, H_SNOW_ICE);
                        if (roll < 0.03f) plantAt(cfg.id[SBW_ICE]);
                        else if (roll < 0.08f) snow(1, 3);
                    }
                    break;
                case SBW_RED_SAND_DESERT:
                    if (surface == cfg.id[SBW_RED_SAND] && drand.shouldGenerate(x, z, H_CLAY, 0.0012f)) {
                        const int patchR = 4 + drand.getInt(x, z, H_CLAY_SIZE, 3);
                        Feature clay;
                        clay.kind = Feature::CLAY;
                        clay.ax = x; clay.ay = h; clay.az = z;
                        clay.priority = ft.priority;
                        for (int dx = -patchR; dx <= patchR; dx++) {
                            for (int dz = -patchR; dz <= patchR; dz++) {
                                const double dist = std::sqrt(static_cast<double>(dx * dx + dz * dz));
                                if (dist > patchR) continue;
                                const int64_t px = x + dx, pz = z + dz;
                                const float placeChance = 1.0f - static_cast<float>(dist / patchR) * 0.4f;
                                if (!(drand.getFloat(px, pz, H_CLAY_PLACE) < placeChance)) continue;
                                const ColumnFacts pf = (dx == 0 && dz == 0) ? f : facts.at(px, pz);
                                if (!pf.uncarved || pf.topBlock != cfg.id[SBW_RED_SAND]) continue;
                                /* never under a cactus */
                                if ((pf.biome == SBW_DESERT || pf.biome == SBW_RED_SAND_DESERT)
                                        && !(pf.water != NO_WATER && pf.water > pf.h)
                                        && drand.getFloat(px, pz, H_CACTUS) < 0.003f) {
                                    continue;
                                }
                                clay.cells.push_back({px, static_cast<int64_t>(pf.h) - 1, pz, cfg.id[SBW_CLAY], 0});
                            }
                        }
                        if (!clay.cells.empty()) out.push_back(std::move(clay));
                    }
                    break;
                case SBW_TUNDRA:
                    if (surface == cfg.id[SBW_SNOWY_DIRT]) {
                        const float roll = drand.getFloat(x, z, H_TUNDRA);
                        if (roll < 0.01f) plantAt(cfg.id[SBW_ICE]);
                        else if (roll < 0.55f) snow(1, 3);
                    }
                    break;
                case SBW_TAIGA:
                    if (surface == cfg.id[SBW_SNOWY_DIRT] && drand.shouldGenerate(x, z, H_TAIGA_SNOW, 0.03f)) snow(1, 2);
                    break;
                case SBW_STONY_PEAKS:
                    if (surface == cfg.id[SBW_STONE]) {
                        const float roll = drand.getFloat(x, z, H_STONY);
                        if (roll < 0.05f) swap(cfg.id[SBW_GRAVEL]);
                        else if (roll < 0.08f) swap(cfg.id[SBW_COAL_ORE]);
                    }
                    break;
                case SBW_ICE_FIELDS:
                    if (surface == cfg.id[SBW_ICE] && drand.getFloat(x, z, H_GLACIAL) < 0.6f) snow(2, 3);
                    break;
                case SBW_BADLANDS:
                    if (surface == cfg.id[SBW_RED_SAND]) {
                        const float roll = drand.getFloat(x, z, H_BADLANDS);
                        if (roll < 0.03f) swap(cfg.id[SBW_CLAY]);
                        else if (roll < 0.05f) swap(cfg.id[SBW_GRAVEL]);
                        else if (roll < 0.06f) swap(cfg.id[SBW_RED_SAND_COBBLESTONE]);
                    }
                    break;
                default: break;
            }
        }
    }
}

namespace {

/* The order-independent resolution (worldgen.h): trees by (log?, priority); everything else yields
 * to any tree; badlands swaps yield to clay patches; a cactus stops at its first tree cell. */
void resolve(const Config& cfg, const std::vector<const Feature*>& fs, Resolved& r) {
    /* total order of features: priority, then anchor (independent of enumeration order) */
    auto beats = [&](int32_t a, int32_t b) {
        const Feature& fa = *fs[static_cast<size_t>(a)];
        const Feature& fb = *fs[static_cast<size_t>(b)];
        if (fa.priority != fb.priority) return fa.priority > fb.priority;
        if (fa.ax != fb.ax) return fa.ax < fb.ax;
        if (fa.az != fb.az) return fa.az < fb.az;
        if (fa.ay != fb.ay) return fa.ay < fb.ay;
        return fa.kind < fb.kind;
    };
    struct Best { uint64_t rank; int32_t owner; uint16_t id; };
    std::unordered_map<uint64_t, Best> tree;
    std::unordered_set<uint64_t> clay;
    for (size_t i = 0; i < fs.size(); i++) {
        const Feature& f = *fs[i];
        const auto fi = static_cast<int32_t>(i);
        if (f.kind == Feature::TREE) {
            for (const FeatureCell& c : f.cells) {
                const uint64_t k = cellKey(c.x, c.y, c.z);
                const uint64_t rank = isLogId(cfg, c.id) ? 1 : 0;
                auto it = tree.find(k);
                if (it == tree.end() || rank > it->second.rank
                        || (rank == it->second.rank && beats(fi, it->second.owner))) {
                    tree[k] = {rank, fi, c.id};
                }
            }
        } else if (f.kind == Feature::CLAY) {
            for (const FeatureCell& c : f.cells) clay.insert(cellKey(c.x, c.y, c.z));
        }
    }
    /* boulders (SBW_FLAG_BOULDERS) yield to trees and beat every other feature; among boulders a rock cell beats a
     * snow cap, then priority */
    struct RockBest { uint64_t rank; int32_t owner; uint16_t id; uint16_t snow; };
    std::unordered_map<uint64_t, RockBest> rock;
    for (size_t i = 0; i < fs.size(); i++) {
        const Feature& f = *fs[i];
        if (f.kind != Feature::BOULDER) continue;
        const auto fi = static_cast<int32_t>(i);
        for (const FeatureCell& c : f.cells) {
            const uint64_t k = cellKey(c.x, c.y, c.z);
            if (tree.count(k) != 0) continue;
            const uint64_t rank = c.snow == 0 ? 1 : 0;
            auto it = rock.find(k);
            if (it == rock.end() || rank > it->second.rank || (rank == it->second.rank && beats(fi, it->second.owner))) {
                rock[k] = {rank, fi, c.id, c.snow};
            }
        }
    }
    r.cells.clear();
    for (auto& [k, b] : tree) r.cells[k] = {b.id, 0, b.owner};
    for (auto& [k, b] : rock) r.cells[k] = {b.id, b.snow, b.owner};
    for (size_t i = 0; i < fs.size(); i++) {
        const Feature& f = *fs[i];
        if (f.kind == Feature::TREE || f.kind == Feature::BOULDER) continue;
        const auto fi = static_cast<int32_t>(i);
        const bool isCactus = f.kind == Feature::PLANT && !f.cells.empty() && f.cells[0].id == cfg.id[SBW_CACTUS];
        for (const FeatureCell& c : f.cells) {
            const uint64_t k = cellKey(c.x, c.y, c.z);
            if (tree.count(k) != 0 || rock.count(k) != 0) {
                if (isCactus) break;
                continue;
            }
            if (f.kind == Feature::SWAP && clay.count(k) != 0) continue;
            auto it = r.cells.find(k);
            if (it == r.cells.end() || beats(fi, it->second.owner)) r.cells[k] = {c.id, c.snow, fi};
        }
    }
}

} // namespace

/* ------------------------------------------------------------------------------- populate */
int32_t Generator::populate(const cv_gen_api* api, cv_world* world, int64_t cx, int64_t cy, int64_t cz) {
    if ((cfg.flags & SBW_FLAG_FEATURES) == 0) return 0;
    if (api == nullptr || api->set == nullptr) return -1;
    const int64_t X0 = cx * CE, Z0 = cz * CE, Y0 = cy * CE;
    /* quick reject: no column of the 2x2 footprint anchors inside this cube's y region */
    const int64_t oy0 = Y0 + 2, oy1 = oy0 + CE;
    bool any = false;
    for (int dx = 0; dx < 2 && !any; dx++) {
        for (int dz = 0; dz < 2 && !any; dz++) {
            auto b = base(cx + dx, cz + dz);
            if (!b) return -1;
            /* boulder anchors sit at their lowest cell + 2: within [minH - 22, maxH + 8] (boulders.cpp) */
            any = (cfg.flags & SBW_FLAG_BOULDERS) != 0 && cfg.channelWorld() ? b->maxH + 8 >= oy0 && b->minH - 22 < oy1
                                                       : b->maxH >= oy0 && b->minH < oy1;
        }
    }
    if (!any) return 0;
    FeatureSet set;
    gatherFeatures(X0, Z0, X0 + 2 * CE, Z0 + 2 * CE, set);
    const std::vector<const Feature*>& fs = set.fs;
    Resolved r;
    resolve(cfg, fs, r);
    const int64_t ox0 = X0 + 16, ox1 = ox0 + CE, oz0 = Z0 + 16, oz1 = oz0 + CE;
    int32_t writes = 0;
    /* write in a deterministic order: iterate owned features, their cells, consult the winner */
    for (size_t i = 0; i < fs.size(); i++) {
        const Feature& f = *fs[i];
        if (f.ax < ox0 || f.ax >= ox1 || f.az < oz0 || f.az >= oz1 || f.ay < oy0 || f.ay >= oy1) continue;
        for (const FeatureCell& c : f.cells) {
            auto it = r.cells.find(cellKey(c.x, c.y, c.z));
            if (it == r.cells.end() || it->second.owner != static_cast<int32_t>(i)) continue;
            if (c.x < X0 || c.x >= X0 + 2 * CE || c.z < Z0 || c.z >= Z0 + 2 * CE || c.y < Y0 || c.y >= Y0 + 2 * CE) continue;
            if (c.y < cfg.minY || c.y >= cfg.maxY) continue;
            if (api->set(world, c.x, c.y, c.z, it->second.id) < 0) return -1;
            if (api->layer_set != nullptr) {
                if (cfg.waterLayer >= 0) api->layer_set(world, cfg.waterLayer, c.x, c.y, c.z, 0);
                if (cfg.stateLayer >= 0) api->layer_set(world, cfg.stateLayer, c.x, c.y, c.z, 0);
                if (cfg.snowLayer >= 0) api->layer_set(world, cfg.snowLayer, c.x, c.y, c.z, it->second.snow);
            }
            writes++;
        }
    }
    return writes;
}

/* ------------------------------------------------------------------------- column surface */
std::shared_ptr<const SurfaceTile> Generator::surfaceTile(int64_t cx, int64_t cz) {
    return surfaceCache.get(cx, cz, [this](int64_t a, int64_t b) { return buildSurface(a, b); });
}

std::shared_ptr<SurfaceTile> Generator::buildSurface(int64_t cx, int64_t cz) {
    auto bp = base(cx, cz);
    auto cp = carved(cx, cz);
    if (!bp || !cp) return nullptr;
    const BaseTile& b = *bp;
    const CarvedTile& cs = *cp;
    auto t = std::make_shared<SurfaceTile>();
    const int64_t X0 = cx * CE, Z0 = cz * CE;
    Resolved r;
    FeatureSet set;
    const std::vector<const Feature*>& fs = set.fs;
    if ((cfg.flags & SBW_FLAG_FEATURES) != 0) {
        gatherFeatures(X0 - 8, Z0 - 8, X0 + CE + 8, Z0 + CE + 8, set);
        resolve(cfg, fs, r);
    }
    /* per column: highest feature cell, and the feature block lookup */
    std::vector<int64_t> featTop(COLS, INT64_MIN);
    std::unordered_map<uint64_t, uint16_t> featBlock;
    for (const Feature* fp : fs) {
        for (const FeatureCell& c : fp->cells) {
            if (c.x < X0 || c.x >= X0 + CE || c.z < Z0 || c.z >= Z0 + CE) continue;
            auto it = r.cells.find(cellKey(c.x, c.y, c.z));
            if (it == r.cells.end()) continue;
            featBlock[cellKey(c.x, c.y, c.z)] = it->second.id;
            const int col = colIdx(static_cast<int>(c.x - X0), static_cast<int>(c.z - Z0));
            featTop[static_cast<size_t>(col)] = std::max(featTop[static_cast<size_t>(col)], c.y);
        }
    }
    const uint16_t air = cfg.id[SBW_AIR];
    ColumnTiles tiles(*this, cx, cz);
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int col = colIdx(lx, lz);
            const int64_t x = X0 + lx, z = Z0 + lz;
            int64_t top = cs.carvedH[col];
            if (b.water[col] != NO_WATER && b.water[col] > b.h[col]) top = std::max<int64_t>(top, b.water[col]);
            if (cs.sea[col] != 0) top = std::max<int64_t>(top, cfg.sea); /* a sea-flooded cut */
            if (featTop[static_cast<size_t>(col)] != INT64_MIN) top = std::max(top, featTop[static_cast<size_t>(col)] + 1);
            if (top <= cfg.minY) top = cfg.minY;
            t->top[col] = top;
            /* sky: walk down the final column */
            int64_t y = top - 1;
            int64_t sky = cfg.minY;
            for (; y >= cfg.minY; y--) {
                uint16_t id;
                auto it = featBlock.find(cellKey(x, y, z));
                if (it != featBlock.end()) id = it->second;
                else id = tiles.blockAt(x, y, z, true);
                if (id != air && (cfg.flagsOf(id) & CV_BLOCK_SKY_OPAQUE) != 0) {
                    sky = y + 1;
                    break;
                }
            }
            t->sky[col] = sky;
        }
    }
    return t;
}

int32_t Generator::columnSurface(int64_t cx, int64_t cz, int64_t* sky, int64_t* top) {
    if (prefetch && !surfaceCache.contains(cx, cz)) prefetch->around(cx, cz, 2);
    auto t = surfaceTile(cx, cz);
    if (!t) return 0;
    if (sky) std::memcpy(sky, t->sky, sizeof(t->sky));
    if (top) std::memcpy(top, t->top, sizeof(t->top));
    return 1;
}

/* ------------------------------------------------------------------------- surface prefetch */
SurfacePrefetcher::~SurfacePrefetcher() {
    {
        std::lock_guard<std::mutex> lock(m_);
        stop_ = true;
        queue_.clear();
    }
    cv_.notify_all();
    for (auto& t : pool_) t.join();
}

void SurfacePrefetcher::around(int64_t cx, int64_t cz, int radius) {
    /* farthest ring first into the LIFO, so the nearest columns are taken first */
    std::vector<std::pair<int64_t, int64_t>> add;
    for (int r = radius; r >= 1; r--) {
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (std::max(std::abs(dx), std::abs(dz)) != r) continue;
                if (g_.surfaceCache.contains(cx + dx, cz + dz)) continue;
                add.emplace_back(cx + dx, cz + dz);
            }
        }
    }
    if (add.empty()) return;
    {
        std::lock_guard<std::mutex> lock(m_);
        if (stop_) return;
        if (pool_.empty()) {
            for (int i = 0; i < threads_; i++) pool_.emplace_back([this] { run(); });
        }
        constexpr size_t MAX_QUEUE = 512; /* stale requests go first */
        if (queue_.size() + add.size() > MAX_QUEUE) {
            const size_t drop = std::min(queue_.size(), queue_.size() + add.size() - MAX_QUEUE);
            queue_.erase(queue_.begin(), queue_.begin() + static_cast<std::ptrdiff_t>(drop));
        }
        queue_.insert(queue_.end(), add.begin(), add.end());
    }
    cv_.notify_all();
}

void SurfacePrefetcher::run() {
    for (;;) {
        std::pair<int64_t, int64_t> k;
        {
            std::unique_lock<std::mutex> lock(m_);
            cv_.wait(lock, [this] { return stop_ || !queue_.empty(); });
            if (stop_) return;
            k = queue_.back();
            queue_.pop_back();
        }
        try {
            g_.surfaceTile(k.first, k.second); /* failures are not cached; the caller sees them itself */
        } catch (...) {
        }
    }
}

} // namespace sbw
