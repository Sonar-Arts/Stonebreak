/* Standard terrain: DistilledShape (fitted at 1:4), HeightCurveTable, NoiseRouter climate, BiomeSelector,
 * surface-block rules (OceanFloor, LakeBed, DesertGravel). Per column cube, all channels grid-filled.
 *
 * TERRAIN SCALE: the shape and climate fields are evaluated in SHAPE SPACE = block * (horizontal metres
 * per block / 60), so 1:2 (30 m blocks) is the 1:4 shape at twice the horizontal size; the height
 * curve comes from the config's rates/knots (1:2 halves the rates: same exaggeration relative to the
 * horizontal). Block-scale things do not scale: the detail fuzz (45-block channel), caves, features.
 * Biome rules keyed on block heights (coast/swamp heights, altitude chill) read the column's height
 * under the 1:4 REFERENCE curve, so a preset changes the size of the world, not its biome mix. */
#include "gen.hpp"

#include <algorithm>
#include <cmath>

namespace sbw {

namespace {

/* DistilledShape.FITTED (terrain_slm.eval.distill_export fit_v2) */
struct Fitted {
    double contPeriod = 6894.989622808905, contOffset = 0.34463361352951966, contWidth = 0.09859857325686658;
    double homeRadius = 600.0, homeAmp = 0.7;
    double rangePeriod = 3944.511100992684, warpPeriod = 1821.7527333238593, warpAmp = 404.5148080216746;
    double spineWidth = 0.5310144938793813, spurPeriod = 577.2424211879252;
    double alongPeriod = 4344.064076674278, alongBias = 0.011431446131307199;
    double spineM = 2348.41250626434, spurM = 1911.1343613301422, footM = 172.30188545142178;
    double hillsPeriod = 142.39095239499332, hillyPeriod = 1827.960213884483, hillBias = -0.4538265830406665;
    double hillSoftness = 4.600349343005898, hillPlainsM = 161.1449691547196, hillCountryM = 399.1323760057598;
    double landBaseM = 587.0916674781482, seaFloorM = -238.16724380150708;
    double roughFloor = 0.053105921091504305, roughHill = 0.9401180978049882;
    double valleyPeriod = 451.3265503952944, valleyM = 508.73099538006727, valleyPower = 2.0;
    double texturePeriod = 18.47190652270889, textureM = 123.11199426403962, textureFloorM = 1.651965331080935;
    double detailBlocks = 0.7185657206192777, peakKneeM = 4134.428426652372, peakSpanM = 835.096478721458;
};
const Fitted P;

constexpr double FBM_STD2 = 0.405, FBM_STD3 = 0.3555, FBM_STD4 = 0.3337;
constexpr int LATTICE = 16;
enum Ch { CONT = 0, WARP_X, WARP_Z, RANGE, SPUR, ALONG, HILLS, HILLY, VALLEY, TEXTURE, DETAIL, CHANNELS };

double bell(double u) { const double t = std::max(1.0 - u / 3.0, 0.0); return t * t * t; }
double smoothstep(double x) { const double t = std::max(0.0, std::min(1.0, x)); return t * t * (3.0 - 2.0 * t); }
double sq(double v) { return v * v; }
double clamp01(double v) { return std::max(0.0, std::min(1.0, v)); }
double valleyShape(double a, double e) {
    if (e == 0.5) return std::sqrt(a);
    if (e == 1.0) return a;
    if (e == 1.5) return a * std::sqrt(a);
    return a * a;
}
double bspline(double t, int i) {
    const double u = 1.0 - t, t2 = t * t, t3 = t * t * t;
    switch (i) {
        case 0: return u * u * u / 6.0;
        case 1: return (3.0 * t3 - 6.0 * t2 + 4.0) / 6.0;
        case 2: return (-3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0) / 6.0;
        default: return t3 / 6.0;
    }
}

/* NoiseRouter climate */
constexpr int ALTITUDE_CHILL_START_Y = 110;
constexpr float ALTITUDE_CHILL_FACTOR = 250.0f;
constexpr double MOISTURE_OFF = 150000.0;
constexpr double TEMPERATURE_OFF = -100000.0;

float temperatureFromRaw(float raw, int32_t height) {
    float base = raw * 0.5f + 0.5f;
    if (height > ALTITUDE_CHILL_START_Y) {
        base -= static_cast<float>(height - ALTITUDE_CHILL_START_Y) / ALTITUDE_CHILL_FACTOR;
    }
    return std::max(0.0f, std::min(1.0f, base));
}

/* BiomeSelector */
struct Sample {
    float land, mountain, hilly, rough;
    int32_t height; /* shaped height, reference curve */
    float temperature, moisture;
};
constexpr float COAST_MAX_LAND = 0.999f, BEACH_MAX_MOUNTAIN = 0.15f, MOUNTAIN_MIN = 0.617f;
constexpr float HILL_MIN_MOUNTAIN = 0.46f, HILL_MIN_HILLY = 0.237f, PLATEAU_MAX_ROUGH = 0.45f;
constexpr float T_FREEZING = 0.20f, T_COLD = 0.38f, T_WARM = 0.62f, T_HOT = 0.78f;
constexpr float M_ARID = 0.25f, M_DRY = 0.45f, M_WET = 0.65f;
constexpr int SWAMP_MAX_Y = 80;
constexpr float SWAMP_MAX_ROUGH = 0.3f, SWAMP_MAX_MOUNTAIN = 0.1f;

int selectBiome(const Sample& s, bool submerged, int32_t sea) {
    if (submerged) return s.temperature < T_FREEZING ? SBW_ICE_FIELDS : SBW_OCEAN;
    if (s.height <= sea + 2 && s.land < COAST_MAX_LAND && s.mountain < BEACH_MAX_MOUNTAIN) {
        return s.temperature < T_COLD ? SBW_ICE_FIELDS : SBW_BEACH;
    }
    if (s.mountain >= MOUNTAIN_MIN) {
        const bool plateau = s.rough < PLATEAU_MAX_ROUGH;
        if (s.temperature < T_FREEZING) return SBW_ICE_FIELDS;
        if (s.temperature < T_COLD) return plateau ? SBW_SNOWY_PLAINS : SBW_STONY_PEAKS;
        if (s.temperature >= T_HOT && s.moisture < M_DRY) return SBW_BADLANDS;
        return SBW_STONY_PEAKS;
    }
    if (s.mountain >= HILL_MIN_MOUNTAIN || s.hilly >= HILL_MIN_HILLY) {
        if (s.temperature < T_FREEZING) return SBW_TUNDRA;
        if (s.temperature < T_COLD) return s.moisture >= M_WET ? SBW_TAIGA : SBW_SNOWY_PLAINS;
        if (s.temperature < T_WARM) return s.moisture >= M_WET ? SBW_MEADOW : SBW_PLAINS;
        if (s.moisture < M_ARID) return SBW_BADLANDS;
        if (s.moisture < M_DRY) return SBW_RED_SAND_DESERT;
        return SBW_PLAINS;
    }
    const bool swamp = s.height <= SWAMP_MAX_Y && s.rough < SWAMP_MAX_ROUGH && s.mountain < SWAMP_MAX_MOUNTAIN
                       && s.temperature >= T_COLD && s.moisture >= M_WET;
    if (swamp) return SBW_SWAMP;
    if (s.temperature < T_FREEZING) return s.moisture >= M_WET ? SBW_ICE_FIELDS : SBW_TUNDRA;
    if (s.temperature < T_COLD) return s.moisture >= M_WET ? SBW_TAIGA : SBW_SNOWY_PLAINS;
    if (s.temperature < T_WARM) return s.moisture >= M_WET ? SBW_MEADOW : SBW_PLAINS;
    if (s.temperature < T_HOT) {
        if (s.moisture < M_ARID) return SBW_DESERT;
        if (s.moisture < M_DRY) return SBW_RED_SAND_DESERT;
        return SBW_PLAINS;
    }
    if (s.moisture < M_ARID) return SBW_DESERT;
    if (s.moisture < M_DRY) return SBW_RED_SAND_DESERT;
    if (s.moisture < M_WET) return SBW_BADLANDS;
    return SBW_PLAINS;
}

/* DesertGravel */
constexpr int GRAVEL_CELL = 64;
constexpr double GRAVEL_CHANCE = 0.55, GRAVEL_MIN_R = 3.5, GRAVEL_MAX_R = 7.0, GRAVEL_WOBBLE = 0.28;
const int GRAVEL_REACH = static_cast<int>(std::ceil(GRAVEL_MAX_R * (1 + GRAVEL_WOBBLE))) + 1;

uint64_t gravelHash(int64_t seed, int64_t cx, int64_t cz) {
    uint64_t h = static_cast<uint64_t>(seed) ^ UINT64_C(0x67726176656C7061);
    h = (h ^ (static_cast<uint64_t>(cx) * UINT64_C(0x9E3779B97F4A7C15))) * UINT64_C(0xBF58476D1CE4E5B9);
    h = (h ^ (static_cast<uint64_t>(cz) * UINT64_C(0xC2B2AE3D27D4EB4F))) * UINT64_C(0x94D049BB133111EB);
    return h ^ (h >> 31);
}
double gravelUnit(uint64_t h, int k) {
    uint64_t x = h + static_cast<uint64_t>(k) * UINT64_C(0x9E3779B97F4A7C15);
    x = (x ^ (x >> 30)) * UINT64_C(0xBF58476D1CE4E5B9);
    x = (x ^ (x >> 27)) * UINT64_C(0x94D049BB133111EB);
    x ^= x >> 31;
    return static_cast<double>(x >> 11) * 0x1.0p-53;
}
bool desertGravel(int64_t x, int64_t z, int64_t seed) {
    const int64_t cx = floorDiv64(x, GRAVEL_CELL), cz = floorDiv64(z, GRAVEL_CELL);
    const uint64_t h = gravelHash(seed, cx, cz);
    if (gravelUnit(h, 0) >= GRAVEL_CHANCE) return false;
    const int span = GRAVEL_CELL - 2 * GRAVEL_REACH;
    const double centreX = static_cast<double>(cx * GRAVEL_CELL + GRAVEL_REACH) + gravelUnit(h, 1) * span;
    const double centreZ = static_cast<double>(cz * GRAVEL_CELL + GRAVEL_REACH) + gravelUnit(h, 2) * span;
    const double dx = static_cast<double>(x) + 0.5 - centreX;
    const double dz = static_cast<double>(z) + 0.5 - centreZ;
    const double distSq = dx * dx + dz * dz;
    const double radius = GRAVEL_MIN_R + gravelUnit(h, 3) * (GRAVEL_MAX_R - GRAVEL_MIN_R);
    const double outer = radius * (1 + GRAVEL_WOBBLE);
    if (distSq > outer * outer) return false;
    const double angle = std::atan2(dz, dx);
    const double pi = 3.141592653589793;
    const double wob = 0.6 * std::sin(2 * angle + gravelUnit(h, 4) * 2 * pi)
                       + 0.4 * std::sin(3 * angle + gravelUnit(h, 5) * 2 * pi);
    const double edge = radius * (1 + GRAVEL_WOBBLE * wob);
    return distSq <= edge * edge;
}

uint64_t colHash(int64_t seed, uint64_t salt, int64_t x, int64_t z) {
    uint64_t h = static_cast<uint64_t>(seed) ^ salt;
    h = (h ^ (static_cast<uint64_t>(x) * UINT64_C(0x9E3779B97F4A7C15))) * UINT64_C(0xBF58476D1CE4E5B9);
    h = (h ^ (static_cast<uint64_t>(z) * UINT64_C(0xC2B2AE3D27D4EB4F))) * UINT64_C(0x94D049BB133111EB);
    return h ^ (h >> 31);
}

} // namespace

/* --------------------------------------------------------------------------------- curve */
void HeightCurve::build(const double rates[4], const double knots[5], double seaLevel) {
    const double ocean = rates[0], lowland = rates[1], midland = rates[2], highland = rates[3];
    const double lowlandTop = knots[0], highlandBase = knots[1];
    const double shoreBlend = knots[2], midlandBlend = knots[3], highlandBlend = knots[4];
    auto blend = [](double below, double above, double e, double center, double halfWidth) {
        double t = (e - (center - halfWidth)) / (2.0 * halfWidth);
        t = std::max(0.0, std::min(1.0, t));
        const double s = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        return below + (above - below) * s;
    };
    const auto n = static_cast<size_t>(std::llround((MAX_M - MIN_M) / STEP_M)) + 1;
    std::vector<double> inv(n);
    for (size_t k = 0; k < n; k++) {
        const double e = MIN_M + static_cast<double>(k) * STEP_M;
        double ramp = lowland + (midland - lowland) * (e - lowlandTop) / (highlandBase - lowlandTop);
        ramp = std::max(std::min(lowland, midland), std::min(std::max(lowland, midland), ramp));
        double m = blend(ocean, lowland, e, 0.0, shoreBlend);
        m = blend(m, ramp, e, lowlandTop, midlandBlend);
        m = blend(m, highland, e, highlandBase, highlandBlend);
        inv[k] = 1.0 / m;
    }
    table.assign(n, 0.0);
    for (size_t k = 1; k < n; k++) table[k] = table[k - 1] + 0.5 * (inv[k] + inv[k - 1]) * STEP_M;
    const auto zero = static_cast<size_t>(std::llround(-MIN_M / STEP_M));
    const double shift = seaLevel - table[zero];
    for (double& v : table) v += shift;
}

double HeightCurve::blocks(double e) const {
    const double t = (e - MIN_M) / STEP_M;
    if (t <= 0.0) return table.front();
    const auto last = static_cast<double>(table.size() - 1);
    if (t >= last) return table.back();
    const auto i = static_cast<size_t>(std::floor(t));
    const double f = t - static_cast<double>(i);
    return table[i] + f * (table[i + 1] - table[i]);
}

/* --------------------------------------------------------------------------------- nodes */
void Node2::init(int64_t seedL, int octaves, float gain, float lacunarity, float freq) {
    node = cenda::makeSimplexFbm(octaves, lacunarity, gain, freq);
    seed = javaLongHash(seedL);
}

void Node3::init(int64_t seedL, int octaves, float gain, float lacunarity, float freq, float ySquash) {
    node = cenda::makeSimplexFbm(octaves, lacunarity, gain, freq);
    seed = javaLongHash(seedL);
    squash = ySquash;
}

void Node3::points(float* out, int count, const float* zs, const float* xs, const float* ys) const {
    node->GenPositionArray3D(out, count, zs, xs, ys, 0.0f, 0.0f, 0.0f, seed);
}

float Node3::point(int64_t x, int64_t y, int64_t z) const {
    const auto fz = static_cast<float>(z);
    const auto fx = static_cast<float>(x);
    const float fy = static_cast<float>(y) * squash;
    float out = 0.0f;
    node->GenPositionArray3D(&out, 1, &fz, &fx, &fy, 0.0f, 0.0f, 0.0f, seed);
    return out;
}

/* ----------------------------------------------------------------------- surface blocks */
uint16_t Generator::surfaceBaseId(int biome) const {
    switch (biome) {
        case SBW_DESERT: case SBW_BEACH: case SBW_OCEAN: return cfg.id[SBW_SAND];
        case SBW_RED_SAND_DESERT: case SBW_BADLANDS: return cfg.id[SBW_RED_SAND];
        case SBW_PLAINS: case SBW_MEADOW: return cfg.id[SBW_GRASS];
        case SBW_SWAMP: return cfg.id[SBW_SWAMPY_GRASS];
        case SBW_SNOWY_PLAINS: case SBW_TAIGA: case SBW_TUNDRA: return cfg.id[SBW_SNOWY_DIRT];
        case SBW_STONY_PEAKS: return cfg.id[SBW_STONE];
        case SBW_ICE_FIELDS: return cfg.id[SBW_ICE];
        default: return cfg.id[SBW_DIRT];
    }
}

uint16_t Generator::subsurfaceId(int biome) const {
    switch (biome) {
        case SBW_RED_SAND_DESERT: case SBW_BADLANDS: return cfg.id[SBW_RED_SANDSTONE];
        case SBW_DESERT: case SBW_BEACH: case SBW_OCEAN: return cfg.id[SBW_SANDSTONE];
        case SBW_STONY_PEAKS: return cfg.id[SBW_STONE];
        case SBW_ICE_FIELDS: return cfg.id[SBW_ICE];
        default: return cfg.id[SBW_DIRT];
    }
}

/* surfaceBlockAt: OceanFloor on the sea floor, LakeBed under inland water, DesertGravel patches. */
uint16_t Generator::surfaceAt(int biome, int64_t x, int64_t z, int32_t waterDepth) const {
    if (biome == SBW_OCEAN) {
        const uint64_t h = colHash(cfg.seed, UINT64_C(0x006F6365616E666C), x, z);
        const uint64_t r = h % 100;
        return r < 97 ? cfg.id[SBW_SAND] : (r < 99 ? cfg.id[SBW_DIRT] : cfg.id[SBW_CLAY]);
    }
    const uint16_t own = surfaceBaseId(biome);
    if (waterDepth > 0) {
        switch (biome) {
            case SBW_DESERT: case SBW_BEACH: case SBW_RED_SAND_DESERT: case SBW_BADLANDS:
            case SBW_STONY_PEAKS: case SBW_ICE_FIELDS: return own;
            default: break;
        }
        const uint64_t h = colHash(cfg.seed, UINT64_C(0x006C616B65626564), x, z);
        const uint64_t r = h % 100;
        if (waterDepth <= 1) return r < 75 ? cfg.id[SBW_SAND] : cfg.id[SBW_DIRT];
        return r < 70 ? cfg.id[SBW_DIRT] : (r < 95 ? cfg.id[SBW_SAND] : cfg.id[SBW_CLAY]);
    }
    if (biome == SBW_DESERT && desertGravel(x, z, cfg.seed)) return cfg.id[SBW_GRAVEL];
    return own;
}

/* ------------------------------------------------------------------------ sea floor */
namespace {
/* Smooth patch noise in [0, 1): bilinear value noise on a `cell`-block lattice (pointwise in world coordinates, so a
 * column gets the same value whichever tile builds it). */
double patchNoise(int64_t seed, uint64_t salt, int64_t x, int64_t z, int cell) {
    const int64_t gx = x >= 0 ? x / cell : -((-x + cell - 1) / cell);
    const int64_t gz = z >= 0 ? z / cell : -((-z + cell - 1) / cell);
    const double fx = static_cast<double>(x - gx * cell) / cell, fz = static_cast<double>(z - gz * cell) / cell;
    auto v = [&](int64_t a, int64_t b) {
        return static_cast<double>(colHash(seed, salt, a, b) >> 11) / 9007199254740992.0;
    };
    const double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
    const double top = v(gx, gz) + (v(gx + 1, gz) - v(gx, gz)) * sx;
    const double bot = v(gx, gz + 1) + (v(gx + 1, gz + 1) - v(gx, gz + 1)) * sx;
    return top + (bot - top) * sz;
}
} // namespace

bool Generator::seaFloor(BaseTile& t) {
    const int64_t X0 = t.cx * CE, Z0 = t.cz * CE;
    /* Heights / water of the 3x3 cubes around this one (review-native F3: slope and shelter used to stop at the tile
     * edge, so the rock / sand choice shifted on every 32-block border). Neighbours come from their built base tiles
     * (the same values) or, not built yet, from the apron cache (one profile upcall); fetched only when a sea column
     * within 6 blocks of that side needs them. */
    const int32_t* nh[9] = {};
    const int32_t* nw[9] = {};
    std::shared_ptr<const BaseTile> nbase[9];
    std::shared_ptr<const HeightWater> napron[9];
    nh[4] = t.h;
    nw[4] = t.water;
    bool failed = false;
    auto planes = [&](int k) {
        if (nh[k] != nullptr || failed) return nh[k] != nullptr;
        const int64_t ncx = t.cx + (k % 3) - 1, ncz = t.cz + (k / 3) - 1;
        if (auto b = baseCache.peek3(ncx, ncz, 0)) {
            nbase[k] = b;
            nh[k] = b->h;
            nw[k] = b->water;
            return true;
        }
        auto a = apronCache.get(ncx, ncz, [this](int64_t p, int64_t q) { return buildApron(p, q); });
        if (!a) {
            failed = true;
            return false;
        }
        napron[k] = a;
        nh[k] = a->h;
        nw[k] = a->water;
        return true;
    };
    /* (ax, az) local to this cube, -CE < ax, az < 2 CE: the neighbour's plane index k and its column */
    auto at = [&](int ax, int az, int& k, int& col) {
        const int tx = ax < 0 ? 0 : (ax >= CE ? 2 : 1), tz = az < 0 ? 0 : (az >= CE ? 2 : 1);
        k = tz * 3 + tx;
        col = (az - (tz - 1) * CE) * CE + (ax - (tx - 1) * CE);
        return planes(k);
    };
    for (int c = 0; c < COLS; c++) {
        /* seas: ocean ids and frozen seas (ICE_FIELDS under sea-level water); lakes keep their lake bed */
        const bool sea = t.biome[c] == SBW_OCEAN || (t.biome[c] == SBW_ICE_FIELDS && t.water[c] == cfg.sea);
        if (!sea || t.water[c] == NO_WATER || t.water[c] <= t.h[c] || t.riverFlow[c] >= 0) continue;
        const int lx = c & 31, lz = c >> 5;
        const int64_t x = X0 + lx, z = Z0 + lz;
        const int32_t depth = t.water[c] - t.h[c];
        /* slope: steepest step to a face neighbour (across the tile edge too) */
        int32_t slope = 0;
        const int nb[4][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (const auto& d : nb) {
            int k, col;
            if (!at(lx + d[0], lz + d[1], k, col)) return false;
            slope = std::max(slope, std::abs(nh[k][col] - t.h[c]));
        }
        /* shelter: share of dry ground (water-free columns) within 6 blocks */
        int land = 0, seen = 0;
        for (int dz = -6; dz <= 6; dz += 2) {
            for (int dx = -6; dx <= 6; dx += 2) {
                int k, col;
                if (!at(lx + dx, lz + dz, k, col)) return false;
                seen++;
                if (nw[k][col] == NO_WATER || nw[k][col] <= nh[k][col]) land++;
            }
        }
        const double shelter = seen > 0 ? static_cast<double>(land) / seen : 0.0;
        const bool cliff = ((t.carve[c] >> 4) & 7) == SBW_CARVE_SEA_STACKS;
        /* patches ~8-24 blocks across, edges dithered per column */
        const double r = static_cast<double>(colHash(cfg.seed, UINT64_C(0x5EAF100D), x, z) % 1000) / 1000.0;
        const double p = 0.75 * patchNoise(cfg.seed, UINT64_C(0x5EAF1001), x, z, 12)
                         + 0.25 * patchNoise(cfg.seed, UINT64_C(0x5EAF1002), x, z, 5) + 0.06 * (r - 0.5);
        uint16_t id;
        if (slope >= 3 || (cliff && depth <= 10)) {
            id = (slope >= 4 || p > 0.35) ? cfg.id[SBW_STONE] : cfg.id[SBW_GRAVEL];       /* rock walls, cliffed coasts */
        } else if (slope == 2) {
            id = p < 0.55 ? cfg.id[SBW_GRAVEL] : (p < 0.8 ? cfg.id[SBW_STONE] : cfg.id[SBW_SAND]);   /* swept slopes */
        } else if (depth <= 2) {
            id = p < 0.12 ? cfg.id[SBW_GRAVEL] : cfg.id[SBW_SAND];                          /* beach shallows */
        } else if (shelter > 0.45 && depth >= 3) {
            id = p < 0.6 ? cfg.id[SBW_CLAY] : (p < 0.85 ? cfg.id[SBW_SAND] : cfg.id[SBW_DIRT]);  /* bays, lagoons */
        } else if (depth <= 10) {
            id = p < 0.2 ? cfg.id[SBW_GRAVEL] : (p > 0.86 ? cfg.id[SBW_CLAY] : cfg.id[SBW_SAND]);   /* shelf */
        } else if (depth <= 24) {
            id = p < 0.3 ? cfg.id[SBW_GRAVEL] : (p < 0.62 ? cfg.id[SBW_SAND] : cfg.id[SBW_CLAY]);  /* outer shelf */
        } else {
            id = p < 0.18 ? cfg.id[SBW_GRAVEL] : (p < 0.3 ? cfg.id[SBW_SAND] : cfg.id[SBW_CLAY]);  /* deep: mud */
        }
        t.surface[c] = id;
    }
    return true;
}

/* ------------------------------------------------------------------------ land surface (agent L) */
namespace {
/* Rank in [0, 1) of 0.75 patch noise + 0.25 per-column hash: the blend's quantiles (16 steps, measured over 2M samples
 * of the bilinear smoothstep lattice) map it to ~uniform, so the threshold `u < share` covers ~`share` of the columns
 * while the patches (one `cell`-block lattice) stay coherent. */
double landRank(int64_t seed, uint64_t salt, int64_t x, int64_t z, int cell) {
    static constexpr double Q[17] = {0.0, 0.2268, 0.2874, 0.3323, 0.3705, 0.4051, 0.4375, 0.4690, 0.4998,
                                     0.5306, 0.5619, 0.5944, 0.6293, 0.6677, 0.7126, 0.7733, 1.0};
    const double h = static_cast<double>(colHash(seed, salt ^ UINT64_C(0x1A9D), x, z) >> 11) / 9007199254740992.0;
    const double s = 0.75 * patchNoise(seed, salt, x, z, cell) + 0.25 * h;
    int k = 0;
    while (k < 15 && s >= Q[k + 1]) k++;
    return std::clamp((k + (s - Q[k]) / (Q[k + 1] - Q[k])) / 16.0, 0.0, 0.999999);
}
} // namespace

void Generator::landSurface(BaseTile& t) const {
    const int64_t X0 = t.cx * CE, Z0 = t.cz * CE;
    for (int c = 0; c < COLS; c++) {
        const uint8_t bRock = t.surf[SBW_SURF_ROCK_EXPOSURE][c], bScree = t.surf[SBW_SURF_SCREE][c];
        if (bRock == SBW_SURF_NO_DATA || bScree == SBW_SURF_NO_DATA) continue;      /* no node data: unchanged */
        const int biome = t.biome[c];
        if (biome == SBW_OCEAN || biome == SBW_BEACH || biome == SBW_ICE_FIELDS || biome == SBW_SWAMP) continue;
        if ((t.water[c] != NO_WATER && t.water[c] > t.h[c]) || t.riverFlow[c] >= 0) continue;   /* wet columns */
        const int64_t x = X0 + (c & 31), z = Z0 + (c >> 5);
        /* Shares of the block's ground (1 m lidar labels): rock walls / outcrop faces, talus. Pointwise in (seed,
         * x, z) and the column's own channel bytes (the node computed them with a halo), so no tile-edge seams. */
        const double rock = bRock / 254.0, scree = bScree / 254.0;
        const uint8_t bRough = t.surf[SBW_SURF_ROUGHNESS][c];
        const double rough = bRough == SBW_SURF_NO_DATA ? 0.0 : bRough * 0.01;
        const double u = landRank(cfg.seed, UINT64_C(0x1A2D5EED), x, z, 7);
        const bool red = biome == SBW_RED_SAND_DESERT || biome == SBW_BADLANDS;
        const bool sandy = biome == SBW_DESERT;
        const bool karst = ((t.carve[c] >> 4) & 7) == SBW_CARVE_KARST || ((t.carve[c] >> 4) & 7) == SBW_CARVE_TOWERS;
        uint16_t id = 0;
        if (u < rock) {                                                          /* bare rock */
            id = red ? cfg.id[SBW_RED_SANDSTONE] : sandy ? cfg.id[SBW_SANDSTONE]
                 : (karst && cfg.id[SBW_LIMESTONE] != 0) ? cfg.id[SBW_LIMESTONE] : cfg.id[SBW_STONE];
        } else if (u < rock + scree) {                                           /* talus / block field */
            const double v = static_cast<double>(colHash(cfg.seed, UINT64_C(0x5C2EE001), x, z) % 1000) / 1000.0;
            const bool blocky = v < 0.15 + 0.5 * std::clamp((rough - 0.15) / 0.5, 0.0, 1.0);
            if (red) {
                id = blocky && cfg.id[SBW_RED_SAND_COBBLESTONE] != 0 ? cfg.id[SBW_RED_SAND_COBBLESTONE] : cfg.id[SBW_RED_SAND];
            } else if (sandy) {
                id = blocky && cfg.id[SBW_SAND_COBBLESTONE] != 0 ? cfg.id[SBW_SAND_COBBLESTONE] : cfg.id[SBW_GRAVEL];
            } else {
                id = blocky && cfg.id[SBW_COBBLESTONE] != 0 ? cfg.id[SBW_COBBLESTONE] : cfg.id[SBW_GRAVEL];
            }
        } else if (rough > 0.3 && (biome == SBW_PLAINS || biome == SBW_MEADOW || biome == SBW_TAIGA
                                   || biome == SBW_TUNDRA || biome == SBW_SNOWY_PLAINS)) {
            /* rough, broken ground between the rock and the talus: patches of bare soil / stones */
            const double w = static_cast<double>(colHash(cfg.seed, UINT64_C(0x50D1D1A7), x, z) % 1000) / 1000.0;
            if (w < 0.35 * std::clamp((rough - 0.3) / 0.6, 0.0, 1.0)) id = w < 0.1 ? cfg.id[SBW_GRAVEL] : cfg.id[SBW_DIRT];
        }
        if (id != 0) t.surface[c] = id;
    }
}

/* ------------------------------------------------------------------------ standard tile */
namespace {

/* Raw channel values of one column (CONT, ALONG, HILLS, HILLY, VALLEY, TEXTURE, DETAIL + climate). */
struct ShapeSample {
    float cont, along, hills, hilly, valley, texture, detail, tRaw, mRaw;
};

/* Sample positions, computed once per column the same way for tiles and points (FastNoise2 X carries
 * world z, Y carries world x; shape space = block * scale). */
struct Positions {
    float sz, sx;   /* shape space */
    float bz, bx;   /* block space (detail) */
    float tz, tx;   /* temperature (shape space + offset) */
    float mz, mx;   /* moisture */
};

Positions positionsOf(double s, int64_t x, int64_t z) {
    Positions p{};
    p.sx = static_cast<float>(static_cast<double>(x) * s);
    p.sz = static_cast<float>(static_cast<double>(z) * s);
    p.bx = static_cast<float>(x);
    p.bz = static_cast<float>(z);
    p.tx = static_cast<float>(static_cast<double>(x) * s + TEMPERATURE_OFF);
    p.tz = static_cast<float>(static_cast<double>(z) * s + TEMPERATURE_OFF);
    p.mx = static_cast<float>(static_cast<double>(x) * s + MOISTURE_OFF);
    p.mz = static_cast<float>(static_cast<double>(z) * s + MOISTURE_OFF);
    return p;
}

/* Lattice node (DistilledShape.Lattice.node): warped range/spur at shape-space node (li, lj). */
void latticeNode(const Generator& g, int64_t li, int64_t lj, float& range, float& spur) {
    const auto gx = static_cast<float>(li * LATTICE);
    const auto gz = static_cast<float>(lj * LATTICE);
    const float wx = g.shape[WARP_X].at(gx, gz);
    const float wz = g.shape[WARP_Z].at(gx, gz);
    const auto px = static_cast<float>(static_cast<double>(gx) + P.warpAmp * static_cast<double>(wx));
    const auto pz = static_cast<float>(static_cast<double>(gz) + P.warpAmp * static_cast<double>(wz));
    range = g.shape[RANGE].at(px, pz);
    spur = g.shape[SPUR].at(px, pz);
}

/* thread-local node memo for point queries (keyed by generator serial) */
void cachedNode(const Generator& g, int64_t li, int64_t lj, float& range, float& spur) {
    struct Slot { uint64_t serial; int64_t li, lj; float range, spur; };
    constexpr int BITS = 12;
    thread_local Slot cache[1 << BITS] = {};
    const uint64_t key = static_cast<uint64_t>(li) * UINT64_C(0x9E3779B97F4A7C15) ^ static_cast<uint64_t>(lj) * UINT64_C(0xC2B2AE3D27D4EB4F);
    Slot& sl = cache[(key * UINT64_C(0x9E3779B97F4A7C15)) >> (64 - BITS)];
    if (sl.serial == g.serial && sl.li == li && sl.lj == lj) {
        range = sl.range;
        spur = sl.spur;
        return;
    }
    latticeNode(g, li, lj, range, spur);
    sl = Slot{g.serial, li, lj, range, spur};
}

/* B-spline interpolation of the 4 x 4 nodes around a shape-space position */
template <typename NodeFn>
void latticeFields(double xs, double zs, NodeFn&& node, double& range, double& spur) {
    const double flx = std::floor(xs / LATTICE), flz = std::floor(zs / LATTICE);
    const double tx = (xs - flx * LATTICE) / LATTICE, tz = (zs - flz * LATTICE) / LATTICE;
    const double wzv[4] = {bspline(tz, 0), bspline(tz, 1), bspline(tz, 2), bspline(tz, 3)};
    const auto li0 = static_cast<int64_t>(flx) - 1, lj0 = static_cast<int64_t>(flz) - 1;
    double r = 0.0, sp = 0.0;
    for (int a = 0; a < 4; a++) {
        const double wa = bspline(tx, a);
        for (int b = 0; b < 4; b++) {
            float nr, ns;
            node(li0 + a, lj0 + b, nr, ns);
            const double w = wa * wzv[b];
            r += w * static_cast<double>(nr);
            sp += w * static_cast<double>(ns);
        }
    }
    range = r;
    spur = sp;
}

struct ColumnOut {
    int32_t h, refH, water;
    int biome;
    uint16_t surface;
    float temp, moist;
};

/* DistilledShape.evaluate + BiomeManager + surfaceBlockAt for one column */
ColumnOut evaluateColumn(const Generator& g, int64_t x, int64_t z, const ShapeSample& sm, double fRange, double fSpur) {
    const Config& cfg = g.cfg;
    const double s = g.shapeScale;
    const double xs = static_cast<double>(x) * s, zs = static_cast<double>(z) * s;
    const double home = bell((xs * xs + zs * zs) / (2.0 * P.homeRadius * P.homeRadius));
    const double cont = static_cast<double>(sm.cont) + P.homeAmp * home;
    const double land = smoothstep((cont + P.contOffset) / P.contWidth);
    const double nR = fRange / FBM_STD2, nS = fSpur / FBM_STD2;
    const double w = P.spineWidth;
    const double spine = bell(sq(nR / w));
    const double foot = bell(sq(nR / (2.4 * w)));
    const double body = bell(sq(nR / (1.6 * w)));
    const double spur = bell(sq(nS / 0.6)) * body;
    const double along = smoothstep((static_cast<double>(sm.along) / FBM_STD2 + P.alongBias) / 1.2);
    const double hills = sm.hills;
    double hilly = smoothstep((static_cast<double>(sm.hilly) / FBM_STD3 + P.hillBias) / P.hillSoftness);
    hilly = std::max(hilly, 0.55 * home);
    const double mountains = along * (P.spineM * spine + P.spurM * spur + P.footM * foot);
    const double hillM = P.hillPlainsM + (P.hillCountryM - P.hillPlainsM) * hilly + 200.0 * along * foot;
    const double trend = P.seaFloorM + (P.landBaseM - P.seaFloorM) * land * (0.6 + 0.5 * cont)
                         + land * (mountains + hillM * hills);
    const double rough = clamp01(P.roughFloor + P.roughHill * hilly + along * (0.9 * spine + 0.45 * spur + 0.25 * foot)
                                 + 0.25 * (0.3 + along * foot) * std::fabs(hills)) * land;
    const double av = std::fabs(static_cast<double>(sm.valley)) / (2.0 * FBM_STD4);
    const double valleys = P.valleyM * rough * (valleyShape(av, P.valleyPower) - 0.5);
    const double tex = (P.textureFloorM + (P.textureM - P.textureFloorM) * rough) * static_cast<double>(sm.texture);
    double e = trend + valleys + tex;
    const double over = std::max(e - P.peakKneeM, 0.0);
    e = e - over + over / (1.0 + over / P.peakSpanM);
    const double detail = P.detailBlocks * static_cast<double>(sm.detail);
    const double blocks = g.curve.blocks(e);
    const double refBlocks = g.refCurve.blocks(e);
    const int32_t lo = static_cast<int32_t>(cfg.minY) + 1, hi = static_cast<int32_t>(cfg.maxY) - 1;
    auto clampH = [&](double v) {
        const auto hh = static_cast<int64_t>(v);
        return static_cast<int32_t>(std::max<int64_t>(lo, std::min<int64_t>(hh, hi)));
    };
    ColumnOut o{};
    o.h = clampH(std::floor(blocks + detail));
    const int32_t refShaped = clampH(std::floor(refBlocks));
    o.refH = clampH(std::floor(refBlocks + detail));
    o.water = o.h < cfg.sea ? cfg.sea : NO_WATER;
    Sample smp{static_cast<float>(land), static_cast<float>(along * body), static_cast<float>(hilly),
               static_cast<float>(rough), refShaped, temperatureFromRaw(sm.tRaw, o.refH), sm.mRaw * 0.5f + 0.5f};
    o.temp = smp.temperature;
    o.moist = smp.moisture;
    o.biome = selectBiome(smp, o.h < cfg.sea, cfg.sea);
    const int32_t depth = o.water == NO_WATER ? 0 : o.water - o.h;
    o.surface = g.surfaceAt(o.biome, x, z, depth);
    return o;
}

void gen2(const Node2& n, float* out, int count, const float* zs, const float* xs) {
    n.node->GenPositionArray2D(out, count, zs, xs, 0.0f, 0.0f, n.seed);
}

} // namespace

void buildStandardBase(Generator& g, BaseTile& t) {
    const double s = g.shapeScale;
    const int64_t X0 = t.cx * CE, Z0 = t.cz * CE;
    float sz[COLS], sx[COLS], bz[COLS], bx[COLS], tz[COLS], tx[COLS], mz[COLS], mx[COLS];
    for (int c = 0; c < COLS; c++) {
        const Positions p = positionsOf(s, X0 + (c & 31), Z0 + (c >> 5));
        sz[c] = p.sz; sx[c] = p.sx; bz[c] = p.bz; bx[c] = p.bx;
        tz[c] = p.tz; tx[c] = p.tx; mz[c] = p.mz; mx[c] = p.mx;
    }
    float v[9][COLS];
    gen2(g.shape[CONT], v[0], COLS, sz, sx);
    gen2(g.shape[ALONG], v[1], COLS, sz, sx);
    gen2(g.shape[HILLS], v[2], COLS, sz, sx);
    gen2(g.shape[HILLY], v[3], COLS, sz, sx);
    gen2(g.shape[VALLEY], v[4], COLS, sz, sx);
    gen2(g.shape[TEXTURE], v[5], COLS, sz, sx);
    gen2(g.shape[DETAIL], v[6], COLS, bz, bx);
    gen2(g.temperature, v[7], COLS, tz, tx);
    gen2(g.moisture, v[8], COLS, mz, mx);

    /* the tile's lattice nodes, computed once */
    const double xs0 = static_cast<double>(X0) * s, xs1 = static_cast<double>(X0 + CE - 1) * s;
    const double zs0 = static_cast<double>(Z0) * s, zs1 = static_cast<double>(Z0 + CE - 1) * s;
    const auto li0 = static_cast<int64_t>(std::floor(xs0 / LATTICE)) - 1;
    const auto li1 = static_cast<int64_t>(std::floor(xs1 / LATTICE)) + 2;
    const auto lj0 = static_cast<int64_t>(std::floor(zs0 / LATTICE)) - 1;
    const auto lj1 = static_cast<int64_t>(std::floor(zs1 / LATTICE)) + 2;
    const auto ni = static_cast<size_t>(li1 - li0 + 1), nj = static_cast<size_t>(lj1 - lj0 + 1);
    std::vector<float> nr(ni * nj), ns(ni * nj);
    for (size_t a = 0; a < ni; a++)
        for (size_t b = 0; b < nj; b++)
            latticeNode(g, li0 + static_cast<int64_t>(a), lj0 + static_cast<int64_t>(b), nr[a * nj + b], ns[a * nj + b]);
    auto tileNode = [&](int64_t li, int64_t lj, float& r, float& sp) {
        const auto k = static_cast<size_t>(li - li0) * nj + static_cast<size_t>(lj - lj0);
        r = nr[k];
        sp = ns[k];
    };

    t.minH = INT32_MAX;
    t.maxH = INT32_MIN;
    t.maxTop = INT32_MIN;
    for (int c = 0; c < COLS; c++) {
        const int64_t x = X0 + (c & 31), z = Z0 + (c >> 5);
        const ShapeSample sm{v[0][c], v[1][c], v[2][c], v[3][c], v[4][c], v[5][c], v[6][c], v[7][c], v[8][c]};
        double fr, fs;
        latticeFields(static_cast<double>(x) * s, static_cast<double>(z) * s, tileNode, fr, fs);
        const ColumnOut o = evaluateColumn(g, x, z, sm, fr, fs);
        t.h[c] = o.h;
        t.refH[c] = o.refH;
        t.water[c] = o.water;
        t.biome[c] = static_cast<uint8_t>(o.biome);
        t.surface[c] = o.surface;
        t.temp[c] = o.temp;
        t.moist[c] = o.moist;
        t.minH = std::min(t.minH, o.h);
        t.maxH = std::max(t.maxH, o.h);
        t.maxTop = std::max(t.maxTop, std::max(o.h, o.water == NO_WATER ? o.h : o.water));
    }
}

/* One Standard column without building its tile (FastLOD's strided samples); identical to the tile's
 * value at the same column: same positions, same GenPositionArray2D path, same formula. */
void standardColumnPoint(Generator& g, int64_t x, int64_t z, int32_t& h, int32_t& water, int& biome, uint16_t& surface) {
    const Positions p = positionsOf(g.shapeScale, x, z);
    ShapeSample sm{};
    gen2(g.shape[CONT], &sm.cont, 1, &p.sz, &p.sx);
    gen2(g.shape[ALONG], &sm.along, 1, &p.sz, &p.sx);
    gen2(g.shape[HILLS], &sm.hills, 1, &p.sz, &p.sx);
    gen2(g.shape[HILLY], &sm.hilly, 1, &p.sz, &p.sx);
    gen2(g.shape[VALLEY], &sm.valley, 1, &p.sz, &p.sx);
    gen2(g.shape[TEXTURE], &sm.texture, 1, &p.sz, &p.sx);
    gen2(g.shape[DETAIL], &sm.detail, 1, &p.bz, &p.bx);
    gen2(g.temperature, &sm.tRaw, 1, &p.tz, &p.tx);
    gen2(g.moisture, &sm.mRaw, 1, &p.mz, &p.mx);
    double fr, fs;
    latticeFields(static_cast<double>(x) * g.shapeScale, static_cast<double>(z) * g.shapeScale,
                  [&](int64_t li, int64_t lj, float& r, float& sp) { cachedNode(g, li, lj, r, sp); }, fr, fs);
    const ColumnOut o = evaluateColumn(g, x, z, sm, fr, fs);
    h = o.h;
    water = o.water;
    biome = o.biome;
    surface = o.surface;
}

} // namespace sbw
