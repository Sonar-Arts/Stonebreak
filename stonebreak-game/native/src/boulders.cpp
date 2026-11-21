/* Boulders (SBW_FLAG_BOULDERS, profile kind / DaedalusTGM worlds): rocks from pebbles to glacial erratics, scree
 * aprons and boulder fields, placed as population features (Feature::BOULDER) so they follow the order-independent
 * resolution of features.cpp: every boulder is a pure function of (seed, anchor column, column profiles), reaches at
 * most 7 columns sideways from its anchor and spans < 30 blocks vertically from its lowest cell (its anchor y is
 * that cell + 2), so a boulder crossing a cube border comes out whole and identical whichever cube populates first.
 *
 * WHERE: per-column fields (BoulderFields: density, size, rock exposure, scree, erratic). Today they come from a
 * heuristic over what the profile carries (slope and relief of the heights, biome, carve codes, altitude); the
 * lidar-learned fields (agent L) will arrive in the TGMPipe surface-channel block and replace it in `fieldsAt`.
 * WHAT: a rock shape of a size class - procedural (rocks.cpp) for pebbles / small rocks and as the fallback, the
 * world's SBRB shape bank for medium / large / erratic when it has that class - in one of 8 orientations, sunk into
 * the ground by the shape's sink (+1 on slopes), with any gap down to the ground under a column filled (no rock
 * floats). Materials by biome and carve style (stone / cobblestone, sandstone in deserts, red sandstone in badlands,
 * limestone in karst), per-voxel rough hints -> the cobble variant, snow caps in cold climates and high uplands. */
#include "gen.hpp"
#include "rocks.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <stdexcept>

namespace sbw {

float forestLattice(const DeterministicRandom& r, int64_t x, int64_t z, int spacing, int32_t feature); /* column.cpp */

namespace {

float sampleBoulderLattice(Generator& g, int64_t x, int64_t z, int spacing, int32_t feature) {
    return forestLattice(g.drand, x, z, spacing, feature);
}

using stonebreak::javaStringHash;

/* ----------------------------------------------------------------------------------- fields */
/* What a column says about boulders. Matches the channel set L's lidar model produces (boulder_density,
 * boulder_size, rock_exposure, scree) plus the erratic weight (moraine / glacial lowland). */
struct BoulderFields {
    float density = 0.0f;  /* ~0..1.5: boulder anchors per column = density * ANCHOR_RATE */
    float size = 0.0f;     /* 0..1: shifts the size-class mix toward large */
    float rock = 0.0f;     /* 0..1: rock exposure */
    float scree = 0.0f;    /* 0..1: a talus apron (foot of a cliff) - many small rocks + gravel patches */
    float erratic = 0.0f;  /* 0..1: weight of rare glacial erratics */
    float patch = 1.0f;    /* the boulder-field patchiness at the column (x0.12 .. x2.1) */
};

constexpr float ANCHOR_RATE = 0.018f;   /* density 1 = one boulder per ~55 columns */
constexpr float DENSITY_CAP = 2.5f;           /* the densest channel field (1.0 per 100 m^2) + scree */
constexpr float ANCHOR_MAX = DENSITY_CAP * ANCHOR_RATE;
constexpr float ERRATIC_RATE = 0.0006f; /* erratic 1 = one per ~1700 columns */
constexpr float SCREE_SWAP_RATE = 0.22f;

float smooth(float a, float b, float x) {
    const float t = std::clamp((x - a) / (b - a), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

struct BiomeRow {
    float density, size, rock;
};
/* sbw_biome order */
constexpr BiomeRow BIOME_ROWS[SBW_BIOME_COUNT] = {
    {0.10f, 0.30f, 0.05f}, /* plains */
    {0.10f, 0.35f, 0.15f}, /* desert */
    {0.18f, 0.40f, 0.25f}, /* red sand desert */
    {0.30f, 0.45f, 0.10f}, /* snowy plains */
    {0.45f, 0.50f, 0.20f}, /* tundra */
    {0.35f, 0.50f, 0.15f}, /* taiga */
    {0.70f, 0.55f, 0.70f}, /* stony peaks */
    {0.05f, 0.25f, 0.05f}, /* beach */
    {0.15f, 0.45f, 0.10f}, /* ice fields */
    {0.40f, 0.50f, 0.50f}, /* badlands */
    {0.08f, 0.30f, 0.05f}, /* meadow */
    {0.00f, 0.00f, 0.00f}, /* ocean */
    {0.03f, 0.25f, 0.00f}, /* swamp */
};

int carveStyle(uint16_t code) { return (code >> 4) & 7; }
int carveDensity(uint16_t code) { return code & 15; }

/* The placeholder field source: slope and relief of the raw heights, biome, carve codes. Pure in (seed, x, z). */
BoulderFields heuristicFields(Generator& g, int64_t x, int64_t z, int32_t h, int biome, uint16_t carve) {
    static const int32_t H_PATCH = javaStringHash("boulder_field"), H_MORAINE = javaStringHash("boulder_moraine");
    BoulderFields f;
    const BiomeRow row = BIOME_ROWS[biome < SBW_BIOME_COUNT ? biome : 0];
    if (row.density <= 0.0f) return f;
    const auto hAt = [&](int64_t dx, int64_t dz) { return static_cast<float>(heightOracle(g, x + dx, z + dz)); };
    const float hf = static_cast<float>(h);
    const float slope = std::max(std::fabs(hAt(2, 0) - hAt(-2, 0)), std::fabs(hAt(0, 2) - hAt(0, -2))) * 0.25f;
    /* relief above the column within 6 blocks: a cliff or steep wall upslope */
    float up = 0.0f;
    static const int DIRS[8][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    for (const auto& d : DIRS) {
        for (int dist : {3, 6}) up = std::max(up, hAt(static_cast<int64_t>(d[0]) * dist, static_cast<int64_t>(d[1]) * dist) - hf);
    }
    const float cliffAbove = smooth(4.0f, 12.0f, up);
    f.scree = cliffAbove * (1.0f - smooth(0.8f, 1.8f, slope));
    float carveRock = 0.0f;
    const int style = carveStyle(carve), cd = carveDensity(carve);
    if (cd > 0) {
        const float k = static_cast<float>(cd) / 15.0f;
        if (style == SBW_CARVE_KARST || style == SBW_CARVE_TOWERS) carveRock = 0.45f * k;
        else if (style == SBW_CARVE_SEA_STACKS) carveRock = 0.3f * k;
        else if (style != SBW_CARVE_NONE) carveRock = 0.25f * k;
    }
    f.rock = std::clamp(row.rock + 0.6f * smooth(0.5f, 1.5f, slope) + carveRock, 0.0f, 1.0f);
    /* boulder fields are patchy: a 40-block lattice, ~10x denser in its high patches than in its low ones */
    const float lat = sampleBoulderLattice(g, x, z, 40, H_PATCH);
    const float patch = 0.12f + 2.0f * std::pow(smooth(0.3f, 0.85f, lat), 1.5f);
    f.patch = patch;
    f.density = std::min(1.5f, (row.density + 0.5f * f.rock) * patch + 0.9f * f.scree);
    f.size = std::clamp(row.size + 0.25f * f.rock - 0.15f * f.scree, 0.0f, 1.0f);
    /* glacial erratics: cold lowland flats, in 256-block "moraine" provinces (until a moraine channel exists) */
    float cold = 0.0f;
    if (biome == SBW_TUNDRA || biome == SBW_TAIGA || biome == SBW_SNOWY_PLAINS) cold = 1.0f;
    else if (biome == SBW_PLAINS || biome == SBW_MEADOW) cold = 0.25f;
    if (cold > 0.0f) {
        const float moraine = smooth(0.45f, 0.8f, sampleBoulderLattice(g, x, z, 256, H_MORAINE));
        f.erratic = cold * moraine * (1.0f - smooth(0.3f, 0.9f, slope));
    }
    return f;
}

/* ------------------------------------------------------------------------- column access */
struct Col {
    int32_t h = 0;
    int biome = 0;
    uint16_t top = 0;     /* surface block (after carving) */
    uint16_t carve = 0;
    bool usable = false;  /* uncarved, dry, no pool, no river void */
    /* surface channels (worldgen.h SBW_SURF_*, SBW_SURF_NO_DATA = none): rock exposure, scree, boulder density,
     * boulder size */
    uint8_t rockExposure = SBW_SURF_NO_DATA, scree = SBW_SURF_NO_DATA, boulderDensity = SBW_SURF_NO_DATA,
            boulderSize = SBW_SURF_NO_DATA, roughness = SBW_SURF_NO_DATA;
};

class ColumnView {
public:
    explicit ColumnView(Generator& g) : g_(g) {}
    Col at(int64_t x, int64_t z) {
        const int64_t cx = x >> 5, cz = z >> 5;
        Entry* e = nullptr;
        for (auto& c : cache_) {
            if (c.base && c.cx == cx && c.cz == cz) {
                e = &c;
                break;
            }
        }
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
        const BaseTile& b = *e->base;
        Col c;
        c.h = b.h[col];
        c.biome = b.biome[col];
        c.carve = b.carve[col];
        c.rockExposure = b.surf[SBW_SURF_ROCK_EXPOSURE][col];
        c.scree = b.surf[SBW_SURF_SCREE][col];
        c.boulderDensity = b.surf[SBW_SURF_BOULDER_DENSITY][col];
        c.boulderSize = b.surf[SBW_SURF_BOULDER_SIZE][col];
        c.roughness = b.surf[SBW_SURF_ROUGHNESS][col];
        const bool uncarved = e->carved->carvedH[col] == c.h && e->carved->sea[col] == 0;
        c.top = uncarved ? e->carved->top[col] : g_.cfg.id[SBW_AIR];
        const bool dry = b.water[col] == NO_WATER || b.water[col] <= c.h;
        const bool river = b.rivers && b.riverRoof[col] > b.riverFloor[col];
        c.usable = uncarved && dry && !river && c.h > g_.cfg.minY + 8 && c.h + 34 < g_.cfg.maxY;
        if (c.usable && c.biome == SBW_SWAMP) {
            if (!e->poolsLoaded) {
                e->pools = g_.pools(cx, cz);
                e->poolsLoaded = true;
            }
            if (e->pools && e->pools->depth[col] > 0) c.usable = false;
        }
        return c;
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

/* Surface-channel decoding (worldgen.h SBW_SURF_* table): rock_exposure / scree = share of the block's ground
 * (1 m lidar: slope >= 45 deg, or 28-45 deg and >= 0.12 m RMS roughness), byte / 254; boulder_density = boulders
 * 1.6-6.8 m across per 100 m^2, byte / 254 (capped 1.0); surface_roughness = RMS of the sub-10 m relief, byte * 0.01 m.
 * Change here if L changes a channel's scale. */
float surfFraction(uint8_t v) { return static_cast<float>(v) / 254.0f; }
float surfRoughnessMetres(uint8_t v) { return static_cast<float>(v) * 0.01f; }

/* REAL -> GAME MAPPING (deliberate; L's model skill, 2026-11-19: rock exposure / scree / roughness good - val corr
 * 0.77 / 0.83 / 0.74 - boulder_density weak - corr 0.30, mean ~2x low - boulder_size ~constant, no skill).
 *  - Placement is driven by the GOOD channels: base = 1.3 x rock exposure + 0.6 x scree + 0.8 x rough01
 *    (rough01 = smoothstep 0.15 .. 0.8 m RMS), x the boulder-field patchiness (40-block lattice, x0.12 .. x2.1).
 *  - boulder_density is a MODULATOR after a x2 bias correction (bd = 2 x channel): base x (0.6 + 0.8 x min(1, bd / 0.12))
 *    plus a literal term. The literal term reads the density at the player's scale: the terrain is exaggerated (a 1:4
 *    block is 60 m of land) but the player sees 1 m voxels and the rocks are not exaggerated, so a column reads as 1 m^2
 *    and the medium / large rocks (the 1.6-6.8 m class L counts, ~35 % of the size mix) land at the real areal rate,
 *    the pebbles / small rocks below L's cut-off on top: anchors per column = 3 x bd / 100.
 *    Typical values (L): lowlands 0.01-0.04 -> density ~0.1 (one rock per ~500 columns, like the heuristic's plains);
 *    mountains (rock 0.5, scree 0.15, rough 0.5 m, bd p95 0.1) -> ~1.2 (one per ~45 columns).
 *  - Size: boulder_size is NOT read (no skill). The size mix stays the biome's + 0.25 x rock exposure (heuristic).
 *  - Erratics: the heuristic's cold-lowland moraine weight (no channel carries a landform / moraine field yet).
 * Every field whose slot is SBW_SURF_NO_DATA falls back to the heuristic (models without L's node behave as before). */
BoulderFields fieldsAt(Generator& g, int64_t x, int64_t z, const Col& c) {
    BoulderFields f = heuristicFields(g, x, z, c.h, c.biome, c.carve);
    const bool rock = c.rockExposure != SBW_SURF_NO_DATA, scree = c.scree != SBW_SURF_NO_DATA,
               rough = c.roughness != SBW_SURF_NO_DATA, bd = c.boulderDensity != SBW_SURF_NO_DATA;
    if (!rock && !scree && !rough && !bd) return f;
    if (rock) {
        f.rock = surfFraction(c.rockExposure);
        f.size = std::clamp(BIOME_ROWS[c.biome < SBW_BIOME_COUNT ? c.biome : 0].size + 0.25f * f.rock, 0.0f, 1.0f);
    }
    if (scree) f.scree = surfFraction(c.scree);
    if (rock || scree || rough) {
        const float rough01 = rough ? smooth(0.15f, 0.8f, surfRoughnessMetres(c.roughness)) : 0.0f;
        float base = (1.3f * f.rock + 0.6f * f.scree + 0.8f * rough01) * f.patch;
        float literal = 0.0f;
        if (bd) {
            const float bdc = 2.0f * surfFraction(c.boulderDensity);
            base *= 0.6f + 0.8f * std::min(1.0f, bdc / 0.12f);
            literal = 3.0f * bdc / 100.0f / ANCHOR_RATE;
        }
        f.density = base + literal;
    } else {
        f.density = 3.0f * 2.0f * surfFraction(c.boulderDensity) / 100.0f / ANCHOR_RATE;
    }
    return f;
}

/* ------------------------------------------------------------------------------- materials */
struct Materials {
    uint16_t body, rough, moss;
};

uint16_t idOr(const Config& cfg, int role, int fallback) {
    const uint16_t id = cfg.id[role];
    return id != 0 ? id : cfg.id[fallback];
}

Materials materialsFor(const Config& cfg, int biome, uint16_t carve) {
    Materials m{};
    const int style = carveStyle(carve);
    if (biome == SBW_DESERT || biome == SBW_BEACH) {
        m.body = cfg.id[SBW_SANDSTONE];
        m.rough = idOr(cfg, SBW_SAND_COBBLESTONE, SBW_SANDSTONE);
    } else if (biome == SBW_RED_SAND_DESERT || biome == SBW_BADLANDS) {
        m.body = cfg.id[SBW_RED_SANDSTONE];
        m.rough = idOr(cfg, SBW_RED_SAND_COBBLESTONE, SBW_RED_SANDSTONE);
    } else if (carveDensity(carve) > 0 && (style == SBW_CARVE_KARST || style == SBW_CARVE_TOWERS)) {
        m.body = cfg.id[SBW_LIMESTONE];
        m.rough = idOr(cfg, SBW_COBBLESTONE, SBW_STONE);
    } else {
        m.body = cfg.id[SBW_STONE];
        m.rough = idOr(cfg, SBW_COBBLESTONE, SBW_STONE);
    }
    /* no mossy rock block exists yet (2026-11-19): moss hints keep the body material */
    m.moss = m.body;
    return m;
}

bool isSnowy(const Config& cfg, int biome, int32_t h, uint64_t roll) {
    if (biome == SBW_SNOWY_PLAINS || biome == SBW_TUNDRA || biome == SBW_ICE_FIELDS) return true;
    if (biome == SBW_TAIGA) return (roll & 1u) != 0;
    /* high uplands: ~2600 m under the lowland/midland rate of this terrain scale */
    const double metres = static_cast<double>(h - cfg.sea) * 24.0 * cfg.hmPerBlock / 60.0;
    return biome == SBW_STONY_PEAKS && metres > 2600.0;
}

uint64_t boulderHash(int64_t seed, int64_t x, int64_t z, uint64_t salt) {
    return stonebreak::mix64(static_cast<uint64_t>(seed) ^ salt ^ stonebreak::mix64(
        static_cast<uint64_t>(x) * UINT64_C(0x9E3779B97F4A7C15) ^ static_cast<uint64_t>(z) * UINT64_C(0x165667B19E3779F9)));
}

int pickClass(const BoulderFields& f, float u) {
    const float s = f.size;
    float w[4] = {0.42f - 0.25f * s + 0.3f * f.scree, 0.33f + 0.2f * f.scree, 0.17f + 0.12f * s, 0.06f + 0.12f * s};
    float total = 0.0f;
    for (float& v : w) total += (v = std::max(0.0f, v));
    float acc = 0.0f;
    for (int k = 0; k < 4; k++) {
        acc += w[k] / total;
        if (u < acc) return k;
    }
    return ROCK_LARGE;
}

/* place `shape` at anchor (ax, az); false when the site does not take it */
bool place(Generator& g, ColumnView& view, const RockShape& shape, int orientation, int64_t ax, int64_t az,
           const Materials& mat, bool snowy, uint64_t h, Feature& out) {
    const Config& cfg = g.cfg;
    const int dx = shape.dx, dy = shape.dy, dz = shape.dz;
    const int cxs = (dx - 1) / 2, czs = (dz - 1) / 2;
    struct FootCol {
        int64_t x, z;
        int lo, hi;      /* lowest / highest occupied shape row */
        int32_t ground;  /* first air y */
        int sx, sz;
    };
    std::vector<FootCol> foot;
    foot.reserve(static_cast<size_t>(dx * dz));
    for (int sz = 0; sz < dz; sz++) {
        for (int sx = 0; sx < dx; sx++) {
            int lo = -1, hi = -1;
            for (int y = 0; y < dy; y++) {
                if (!shape.at(sx, y, sz)) continue;
                if (lo < 0) lo = y;
                hi = y;
            }
            if (lo < 0) continue;
            int64_t ox = sx - cxs, oz = sz - czs;
            if ((orientation & 4) != 0) ox = -ox;
            for (int r = 0; r < (orientation & 3); r++) {
                const int64_t t = ox;
                ox = -oz;
                oz = t;
            }
            foot.push_back({ax + ox, az + oz, lo, hi, 0, sx, sz});
        }
    }
    int32_t gMin = INT32_MAX, gMax = INT32_MIN, coreMin = INT32_MAX, coreMax = INT32_MIN;
    for (FootCol& fc : foot) {
        const Col c = view.at(fc.x, fc.z);
        if (!c.usable) return false;
        int trunk = 0;
        if (g.treeAt(fc.x, fc.z, c.top, c.biome, trunk) >= 0) return false;
        fc.ground = c.h;
        gMin = std::min(gMin, c.h);
        gMax = std::max(gMax, c.h);
        if (fc.lo == 0) {
            coreMin = std::min(coreMin, c.h);
            coreMax = std::max(coreMax, c.h);
        }
    }
    if (foot.empty() || coreMin == INT32_MAX) return false;
    if (gMax - gMin > std::max(3, dy + 1)) return false;  /* too steep for this rock */
    const int sink = std::min(dy - 1, shape.sink + (coreMax - coreMin >= 2 ? 1 : 0));
    const int64_t by = static_cast<int64_t>(coreMin) - sink;
    /* cells: visible shape voxels (the buried ones below the ground's top block are skipped), skirts, snow */
    out.cells.clear();
    int64_t yMin = INT64_MAX, yMax = INT64_MIN;
    int visible = 0;
    auto put = [&](int64_t x, int64_t y, int64_t z, uint16_t id, uint16_t snow) {
        out.cells.push_back({x, y, z, id, snow});
        yMin = std::min(yMin, y);
        yMax = std::max(yMax, y);
    };
    for (const FootCol& fc : foot) {
        const int64_t ground = fc.ground;
        const int64_t low = by + fc.lo;
        if (low - ground > 4) return false;  /* would overhang a drop */
        for (int y = fc.lo; y <= fc.hi; y++) {
            if (!shape.at(fc.sx, y, fc.sz)) continue;
            const int64_t wy = by + y;
            if (wy < ground - 1) continue;
            const uint8_t hint = shape.hintAt(fc.sx, y, fc.sz);
            uint16_t id = hint == HINT_ROUGH ? mat.rough : hint == HINT_MOSS ? mat.moss : mat.body;
            put(fc.x, wy, fc.z, id, 0);
            visible += wy >= ground;
        }
        for (int64_t y = ground; y < low; y++) put(fc.x, y, fc.z, mat.body, 0);
        if (snowy) {
            const int64_t top = by + fc.hi + 1;
            if (top >= ground) {
                const auto layers = static_cast<uint16_t>(1 + (boulderHash(static_cast<int64_t>(h), fc.x, fc.z, UINT64_C(0x5A0B)) % 3));
                put(fc.x, top, fc.z, cfg.id[SBW_SNOW], layers);
            }
        }
    }
    if (visible == 0 || out.cells.empty()) return false;
    if (yMax - yMin > 30 || yMax >= cfg.maxY || yMin < cfg.minY + 2) return false;
    /* the anchor y window populate's quick reject assumes (features.cpp): [h - 22, h + 8] of the anchor column */
    const int64_t ha = view.at(ax, az).h;
    if (yMin + 2 < ha - 22 || yMin + 2 > ha + 8) return false;
    out.kind = Feature::BOULDER;
    out.ax = ax;
    out.az = az;
    out.ay = yMin + 2;
    out.priority = boulderHash(cfg.seed, ax, az, static_cast<uint64_t>(yMin) * UINT64_C(0xA24BAED4963EE407));
    return true;
}

} // namespace

void Generator::collectBoulders(int64_t x0, int64_t z0, int64_t x1, int64_t z1, std::vector<Feature>& out) {
    if ((cfg.flags & SBW_FLAG_BOULDERS) == 0 || !cfg.channelWorld()) return;
    static const int32_t H_ANCHOR = javaStringHash("boulder"), H_ERRATIC = javaStringHash("boulder_erratic"),
                         H_SCREE = javaStringHash("boulder_scree");
    ColumnView view(*this);
    for (int64_t z = z0; z < z1; z++) {
        for (int64_t x = x0; x < x1; x++) {
            const float uA = drand.getFloat(x, z, H_ANCHOR);
            const float uE = drand.getFloat(x, z, H_ERRATIC);
            const float uS = drand.getFloat(x, z, H_SCREE);
            if (uA >= ANCHOR_MAX && uE >= ERRATIC_RATE && uS >= SCREE_SWAP_RATE) continue;
            const Col c = view.at(x, z);
            if (!c.usable || BIOME_ROWS[c.biome < SBW_BIOME_COUNT ? c.biome : 0].density <= 0.0f) continue;
            const BoulderFields f = fieldsAt(*this, x, z, c);
            const uint64_t h = boulderHash(cfg.seed, x, z, UINT64_C(0xB0B0));
            int cls = -1;
            if (uE < ERRATIC_RATE * f.erratic) {
                cls = ROCK_ERRATIC;
            } else if (uA < ANCHOR_RATE * std::min(DENSITY_CAP, f.density)) {
                cls = pickClass(f, static_cast<float>((h >> 40) & 0xFFFFFF) / 16777216.0f);
            }
            const Materials mat = materialsFor(cfg, c.biome, c.carve);
            if (cls >= 0) {
                const RockShape* rs = cls >= ROCK_MEDIUM && rocks ? rocks->pick(cls, h >> 8) : nullptr;
                RockShape local;
                if (rs == nullptr) {
                    local = proceduralRock(cls, h);
                    rs = &local;
                }
                Feature ft;
                if (place(*this, view, *rs, static_cast<int>(h & 7u), x, z, mat, isSnowy(cfg, c.biome, c.h, h >> 3),
                          h, ft)) {
                    out.push_back(std::move(ft));
                }
            } else if (uS < SCREE_SWAP_RATE * f.scree) {
                /* a gravel / rubble patch in the apron's ground */
                const uint16_t t = c.top;
                const bool ground = t == cfg.id[SBW_GRASS] || t == cfg.id[SBW_DIRT] || t == cfg.id[SBW_SNOWY_DIRT]
                                    || t == cfg.id[SBW_STONE] || t == cfg.id[SBW_SAND] || t == cfg.id[SBW_RED_SAND];
                int trunk = 0;
                if (!ground || treeAt(x, z, t, c.biome, trunk) >= 0) continue;
                Feature ft;
                ft.kind = Feature::BOULDER;
                ft.ax = x;
                ft.az = z;
                ft.ay = c.h - 1 + 2;
                ft.priority = boulderHash(cfg.seed, x, z, UINT64_C(0x5C5C));
                const uint16_t id = ((h >> 20) & 3u) == 0 ? mat.rough : cfg.id[SBW_GRAVEL];
                ft.cells = {{x, static_cast<int64_t>(c.h) - 1, z, id, 0}};
                out.push_back(std::move(ft));
            }
        }
    }
}

} // namespace sbw
