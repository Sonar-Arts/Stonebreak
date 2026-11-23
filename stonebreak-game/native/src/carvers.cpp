/* Carvers over a 32 x 32 footprint - see carvers.hpp. Constants mirror the Java classes. */
#include "carvers.hpp"

#include <algorithm>
#include <array>
#include <cmath>

namespace sbw {

using stonebreak::javaFloorMod;
using stonebreak::javaRoundFloat;
using stonebreak::splitmix64Finalize;

namespace {

/* Profile carve helpers (defined below, used by carveOneRavine). */
bool profileCarve(const Generator& g);
uint16_t sourceCode(Generator& g, int64_t sx, int64_t sz);

constexpr double JPI = 3.141592653589793;

inline uint64_t sx(int64_t v) { return static_cast<uint64_t>(v); }
inline uint64_t rotl64(uint64_t v, int d) { return (v << d) | (v >> (64 - d)); }
inline int64_t rnd(float f) { return javaRoundFloat(f); }

/* -------------------------------------------------------------------- CaveWaterTable consts */
constexpr float WT_DAMP = 0.40f;
constexpr float WT_WOBBLE = 5.0f;
constexpr int WT_MIN_ROOF = 10;
constexpr int WT_LEVEL_SPACING = 22;
constexpr int WT_STOREYS = 3;
constexpr int WT_BAND = 5;

/* ------------------------------------------------------------------- PerlinWormCarver consts */
constexpr int MAX_STEPS = 70;
constexpr float STEP_SIZE = 1.0f;
constexpr float BASE_RADIUS = 3.1f;
constexpr float RADIUS_AMP = 1.25f;
constexpr float MIN_RADIUS = 1.9f;
constexpr float Y_SQUASH = 0.78f;
constexpr int SCAN_RADIUS = 9;
constexpr float HEADING_SCALE = 1.0f / 38.0f;
constexpr float RADIUS_SCALE = 1.0f / 18.0f;
constexpr float YAW_DRIFT = 0.18f;
constexpr float PITCH_DRIFT = 0.10f;
constexpr float UPWARD_BIAS = 0.025f;
constexpr float PITCH_MIN = -0.85f;
constexpr float PITCH_MAX = 0.65f;
constexpr float BRANCH_CHANCE = 0.04f;
constexpr int BRANCH_MAX_STEPS = 22;
constexpr int MAX_BRANCHES = 2;
constexpr int BRANCH_MIN_STEP = 5;
constexpr float TWIN_CHANCE = 0.75f;
constexpr float CONNECTOR_CHANCE = 0.95f;
constexpr float CAVERN_CONNECTOR_CHANCE = 1.0f;
constexpr int CONNECTOR_SEARCH_RADIUS = 4;
constexpr int CAVERN_CONNECTOR_MAX_STEPS = 85;
constexpr int CAVERN_CONNECTOR_RADIUS = 5;
constexpr float CONNECTOR_BIAS = 0.55f;
constexpr int CONNECTOR_MAX_STEPS = 80;
constexpr float CONNECTOR_REACHED_DIST = BASE_RADIUS;
constexpr int ORIGIN_DEPTH_MIN = 12;
constexpr int ORIGIN_DEPTH_MAX = 60;
constexpr int BREACH_OVERHEAD = 3;
const int WORM_WATER_CLEARANCE = static_cast<int>(std::ceil(BASE_RADIUS + RADIUS_AMP)) + 1;
constexpr float VADOSE_STEEPEN = 0.06f;
constexpr float VADOSE_PITCH_MIN = -0.95f;
constexpr float VADOSE_PITCH_MAX = 0.95f;
constexpr float GALLERY_DAMP = 0.55f;
constexpr float GALLERY_PITCH_MIN = -0.14f;
constexpr float GALLERY_PITCH_MAX = 0.14f;

constexpr uint64_t HASH_A = UINT64_C(0x9E3779B97F4A7C15);
constexpr uint64_t HASH_B = UINT64_C(0xC2B2AE3D27D4EB4F);
constexpr uint64_t LCG_A = UINT64_C(6364136223846793005);
constexpr uint64_t LCG_B = UINT64_C(1442695040888963407);

/* footprint chunk window for a source scan of radius r (16-block source chunks) */
struct Window {
    int64_t c0, c1, d0, d1;
};
Window window(int64_t X0, int64_t Z0, int r) {
    return {floorDiv64(X0, CHUNK) - r, floorDiv64(X0 + CE - 1, CHUNK) + r,
            floorDiv64(Z0, CHUNK) - r, floorDiv64(Z0 + CE - 1, CHUNK) + r};
}

/* ------------------------------------------------------------------------------- worms */
bool hasWorm(int64_t seed, int64_t cx, int64_t cz) {
    uint64_t h = sx(seed);
    h ^= sx(cx) * HASH_A;
    h = rotl64(h, 23);
    h ^= sx(cz) * HASH_B;
    h = splitmix64Finalize(h);
    return (h & 7) == 0;
}

int64_t chunkRngSeed(int64_t seed, int64_t cx, int64_t cz) {
    uint64_t h = sx(seed) * LCG_A;
    h += sx(cx);
    h *= LCG_B;
    h += sx(cz);
    return static_cast<int64_t>(h);
}

float wobbleAt(const CarveCtx& c, int64_t x, int64_t z) {
    return c.g.wobble.node->GenSingle2D(static_cast<float>(z), static_cast<float>(x), c.seeds.wobbleSeed);
}

float originY(const CarveCtx& c, float ox, float oz, JavaRandom& rng) {
    const int32_t surface = c.height(rnd(ox), rnd(oz));
    const int depth = ORIGIN_DEPTH_MIN + rng.nextInt(ORIGIN_DEPTH_MAX - ORIGIN_DEPTH_MIN);
    const int64_t y = static_cast<int64_t>(surface) - depth;
    const int64_t lo = c.yFloor() + 2;
    return static_cast<float>(y > lo ? y : lo);
}

void computeOrigin(const CarveCtx& c, int64_t cx, int64_t cz, float out[3]) {
    JavaRandom rng(chunkRngSeed(c.seeds.seed, cx, cz));
    out[0] = static_cast<float>(cx * CHUNK + rng.nextInt(CHUNK));
    out[2] = static_cast<float>(cz * CHUNK + rng.nextInt(CHUNK));
    out[1] = originY(c, out[0], out[2], rng);
}

bool nearestWormChunk(int64_t seed, int64_t cx, int64_t cz, int64_t out[2]) {
    int64_t best = INT64_MAX;
    bool found = false;
    for (int dcx = -CONNECTOR_SEARCH_RADIUS; dcx <= CONNECTOR_SEARCH_RADIUS; dcx++) {
        for (int dcz = -CONNECTOR_SEARCH_RADIUS; dcz <= CONNECTOR_SEARCH_RADIUS; dcz++) {
            if (dcx == 0 && dcz == 0) continue;
            if (!hasWorm(seed, cx + dcx, cz + dcz)) continue;
            const int64_t d = dcx * dcx + dcz * dcz;
            if (d < best) {
                best = d;
                out[0] = cx + dcx;
                out[1] = cz + dcz;
                found = true;
            }
        }
    }
    return found;
}

float lerpAngle(float from, float to, float t) {
    float diff = to - from;
    const auto twoPi = static_cast<float>(JPI * 2);
    while (diff > static_cast<float>(JPI)) diff -= twoPi;
    while (diff < -static_cast<float>(JPI)) diff += twoPi;
    return from + diff * t;
}

struct Segment {
    float x, y, z, yaw, pitch;
    int stepBudget;
    int branchesLeft;
    int64_t rngSeed;
    bool hasTarget;
    float target[3];
};

void carveWormEllipsoid(CarveCtx& c, int64_t wx, int64_t wy, int64_t wz, float radius) {
    const int rxz = static_cast<int>(std::ceil(radius));
    const int ry = static_cast<int>(std::ceil(radius * Y_SQUASH));
    if (wx + rxz < c.X0 || wx - rxz >= c.X0 + CE) return;
    if (wz + rxz < c.Z0 || wz - rxz >= c.Z0 + CE) return;
    const float invRxz2 = 1.0f / (radius * radius);
    const float ySpan = radius * Y_SQUASH;
    const float invRy2 = 1.0f / (ySpan * ySpan);
    const int32_t floorH = c.g.cfg.floorY() + 1;
    for (int ox = -rxz; ox <= rxz; ox++) {
        const int64_t bx = wx + ox - c.X0;
        if (bx < 0 || bx >= CE) continue;
        for (int oz = -rxz; oz <= rxz; oz++) {
            const int64_t bz = wz + oz - c.Z0;
            if (bz < 0 || bz >= CE) continue;
            const int idx = colIdx(static_cast<int>(bx), static_cast<int>(bz));
            if (c.th[idx] <= floorH) continue;
            const float horizTerm = static_cast<float>(ox * ox + oz * oz) * invRxz2;
            if (horizTerm >= 1.0f) continue;
            const float maxOyTerm = 1.0f - horizTerm;
            for (int oy = -ry; oy <= ry; oy++) {
                if (static_cast<float>(oy * oy) * invRy2 >= maxOyTerm) continue;
                const int64_t by = wy + oy;
                if (!c.yOk(by)) continue;
                if (c.drop != 0 && by >= c.th[idx]) continue; /* deep: stay under the real surface */
                if (guardSeals(c.guard, idx, by, WORM_WATER_CLEARANCE)) continue;
                c.set(static_cast<int>(bx), by, static_cast<int>(bz));
            }
        }
    }
}

void walkWorm(CarveCtx& c, Segment seg, std::vector<Segment>& queue, WormWalk& out) {
    JavaRandom rng(seg.rngSeed);
    float x = seg.x, y = seg.y, z = seg.z, yaw = seg.yaw, pitch = seg.pitch;
    int branchesLeft = seg.branchesLeft;
    const float reachedSq = CONNECTOR_REACHED_DIST * CONNECTOR_REACHED_DIST;
    const Node3& heading = c.g.heading;
    const Node3& radiusN = c.g.radius;
    Zone zone = Zone::PHREATIC;
    const int32_t yFloor = c.yFloor();
    const int64_t yTop = c.g.cfg.maxY + c.drop;

    for (int step = 0; step < seg.stepBudget; step++) {
        const float yawNoise = heading.node->GenSingle3D(x * HEADING_SCALE, y * HEADING_SCALE,
                                                         z * HEADING_SCALE, c.seeds.headingSeed);
        const float pitchNoise = heading.node->GenSingle3D((x + 1024.0f) * HEADING_SCALE, y * HEADING_SCALE,
                                                           (z + 1024.0f) * HEADING_SCALE, c.seeds.headingSeed);
        yaw += yawNoise * YAW_DRIFT;
        pitch += pitchNoise * PITCH_DRIFT + UPWARD_BIAS;
        if (!seg.hasTarget) {
            switch (zone) {
                case Zone::VADOSE:
                    pitch += (pitch > 0.0f ? 1.0f : (pitch < 0.0f ? -1.0f : 0.0f)) * VADOSE_STEEPEN;
                    break;
                case Zone::EPIPHREATIC: pitch *= GALLERY_DAMP; break;
                case Zone::PHREATIC: break;
            }
        }
        if (seg.hasTarget) {
            const float dx = seg.target[0] - x, dy = seg.target[1] - y, dz = seg.target[2] - z;
            const auto horiz = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
            if (horiz > 0.001f) {
                const auto tgtYaw = static_cast<float>(std::atan2(static_cast<double>(dz), static_cast<double>(dx)));
                const auto tgtPitch = static_cast<float>(std::atan2(static_cast<double>(dy), static_cast<double>(horiz)));
                yaw = lerpAngle(yaw, tgtYaw, CONNECTOR_BIAS);
                pitch += (tgtPitch - pitch) * CONNECTOR_BIAS;
            }
        }
        float pitchMin = PITCH_MIN, pitchMax = PITCH_MAX;
        if (!seg.hasTarget) {
            if (zone == Zone::VADOSE) { pitchMin = VADOSE_PITCH_MIN; pitchMax = VADOSE_PITCH_MAX; }
            else if (zone == Zone::EPIPHREATIC) { pitchMin = GALLERY_PITCH_MIN; pitchMax = GALLERY_PITCH_MAX; }
        }
        if (pitch < pitchMin) pitch = pitchMin;
        else if (pitch > pitchMax) pitch = pitchMax;

        const auto cosPitch = static_cast<float>(std::cos(static_cast<double>(pitch)));
        x += static_cast<float>(std::cos(static_cast<double>(yaw))) * cosPitch * STEP_SIZE;
        y += static_cast<float>(std::sin(static_cast<double>(pitch))) * STEP_SIZE;
        z += static_cast<float>(std::sin(static_cast<double>(yaw))) * cosPitch * STEP_SIZE;

        const int64_t wxi = rnd(x), wyi = rnd(y), wzi = rnd(z);
        if (wyi < yFloor || wyi >= yTop) break;
        const int32_t surface = c.height(wxi, wzi);
        const int32_t water = c.water(wxi, wzi);
        if (water != NO_WATER && surface <= static_cast<int64_t>(water) + WORM_WATER_CLEARANCE) break;
        if (wyi > static_cast<int64_t>(surface) + BREACH_OVERHEAD) break;

        zone = waterTableZone(waterTableResolve(c.g, surface, water, wobbleAt(c, wxi, wzi)), wyi);

        float r = BASE_RADIUS + radiusN.node->GenSingle3D(x * RADIUS_SCALE, y * RADIUS_SCALE, z * RADIUS_SCALE,
                                                         c.seeds.radiusSeed) * RADIUS_AMP;
        if (r < MIN_RADIUS) r = MIN_RADIUS;
        out.e.push_back({static_cast<int16_t>(wxi - out.ox), static_cast<int16_t>(wzi - out.oz), static_cast<int32_t>(wyi), r});
        const int rxz = static_cast<int>(std::ceil(r));
        out.minX = std::min(out.minX, wxi - rxz);
        out.maxX = std::max(out.maxX, wxi + rxz);
        out.minZ = std::min(out.minZ, wzi - rxz);
        out.maxZ = std::max(out.maxZ, wzi + rxz);

        if (seg.hasTarget) {
            const float dx = seg.target[0] - x, dy = seg.target[1] - y, dz = seg.target[2] - z;
            if (dx * dx + dy * dy + dz * dz < reachedSq) break;
        }
        if (branchesLeft > 0 && step >= BRANCH_MIN_STEP && rng.nextFloat() < BRANCH_CHANCE) {
            branchesLeft--;
            const float branchOffset = (rng.nextBoolean() ? 1.1f : -1.1f) + (rng.nextFloat() - 0.5f) * 0.6f;
            const float branchYaw = yaw + branchOffset;
            const float branchPitch = pitch + (rng.nextFloat() - 0.5f) * 0.3f;
            queue.push_back({x, y, z, branchYaw, branchPitch, BRANCH_MAX_STEPS, 0, rng.nextLong(), false, {0, 0, 0}});
        }
    }
}

void pushConnector(std::vector<Segment>& queue, float ox, float oy, float oz, const float target[3],
                   int budget, int64_t rngSeed) {
    const float dx = target[0] - ox, dy = target[1] - oy, dz = target[2] - oz;
    const auto horiz = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
    const auto yaw = static_cast<float>(std::atan2(static_cast<double>(dz), static_cast<double>(dx)));
    float pitch = horiz > 0.001f
        ? static_cast<float>(std::atan2(static_cast<double>(dy), static_cast<double>(horiz))) : 0.0f;
    if (pitch < PITCH_MIN) pitch = PITCH_MIN;
    else if (pitch > PITCH_MAX) pitch = PITCH_MAX;
    queue.push_back({ox, oy, oz, yaw, pitch, budget, 0, rngSeed, true, {target[0], target[1], target[2]}});
}

/* ----------------------------------------------------------------------------- caverns */
struct CavernParams {
    uint64_t hashXor, hashMulX, hashMulZ;
    int hashRot;
    int32_t divisor;
    uint64_t seedMul, seedMulX, seedMulZ, seedFinalXor;
    uint64_t formXor, formMulX, formMulZ;
    int formRot;
    int depthMin, depthMax;
    float baseRadius, blobOffset, ySquash;
    int minBlobs, maxBlobs;
    float stalagmiteChance, stalactiteChance;
    int formationMaxHeight;
    int scanRadius;
    int waterClearance;
};

CavernParams makeCavern() {
    CavernParams p{};
    p.hashXor = UINT64_C(0xCA7EAA12CA7EAA12); p.hashMulX = UINT64_C(0xBEA225F9EB34556D); p.hashRot = 19;
    p.hashMulZ = UINT64_C(0x94D049BB133111EB); p.divisor = 48;
    p.seedMul = UINT64_C(6364136223846793005); p.seedMulX = UINT64_C(0x9E3779B97F4A7C15);
    p.seedMulZ = UINT64_C(0xC2B2AE3D27D4EB4F); p.seedFinalXor = 0;
    p.formXor = UINT64_C(0x57A1AC1117EFCAFE); p.formMulX = UINT64_C(0x9E3779B97F4A7C15); p.formRot = 17;
    p.formMulZ = UINT64_C(0xC2B2AE3D27D4EB4F);
    p.depthMin = 18; p.depthMax = 65; p.baseRadius = 10.0f; p.blobOffset = 8.0f; p.ySquash = 0.62f;
    p.minBlobs = 5; p.maxBlobs = 9; p.stalagmiteChance = 0.10f; p.stalactiteChance = 0.08f;
    p.formationMaxHeight = 5;
    const float reach = p.blobOffset + p.baseRadius * 1.20f;
    p.scanRadius = static_cast<int>(std::ceil(static_cast<double>(reach / static_cast<float>(CHUNK)))) + 1;
    p.waterClearance = static_cast<int>(std::ceil(static_cast<double>(p.baseRadius * 1.20f))) + 1;
    return p;
}

CavernParams makeMega() {
    CavernParams p{};
    p.hashXor = UINT64_C(0x3EA9CA77B16BEEF0); p.hashMulX = UINT64_C(0xD1B54A32D192ED03); p.hashRot = 23;
    p.hashMulZ = UINT64_C(0xAEF17502108EF2D9); p.divisor = 192;
    p.seedMul = UINT64_C(0x9E3779B97F4A7C15); p.seedMulX = UINT64_C(0xBF58476D1CE4E5B9);
    p.seedMulZ = UINT64_C(0x94D049BB133111EB); p.seedFinalXor = UINT64_C(0xCAFEBABE1337F00D);
    p.formXor = UINT64_C(0xF1E2D3C4B5A69788); p.formMulX = UINT64_C(0xD1B54A32D192ED03); p.formRot = 19;
    p.formMulZ = UINT64_C(0xAEF17502108EF2D9);
    p.depthMin = 30; p.depthMax = 90; p.baseRadius = 24.0f; p.blobOffset = 15.0f; p.ySquash = 0.66f;
    p.minBlobs = 12; p.maxBlobs = 18; p.stalagmiteChance = 0.12f; p.stalactiteChance = 0.10f;
    p.formationMaxHeight = 9;
    const float reach = p.blobOffset + p.baseRadius * 1.20f;
    p.scanRadius = static_cast<int>(std::ceil(static_cast<double>(reach / static_cast<float>(CHUNK)))) + 1;
    p.waterClearance = static_cast<int>(std::ceil(static_cast<double>(p.baseRadius * 1.20f))) + 1;
    return p;
}

const CavernParams CAVERN = makeCavern();
const CavernParams MEGA = makeMega();

bool hasCavern(const CavernParams& p, int64_t seed, int64_t cx, int64_t cz) {
    uint64_t h = sx(seed) ^ p.hashXor;
    h ^= sx(cx) * p.hashMulX;
    h = rotl64(h, p.hashRot);
    h ^= sx(cz) * p.hashMulZ;
    h = splitmix64Finalize(h);
    return javaFloorMod(static_cast<int64_t>(h), p.divisor) == 0;
}

int64_t cavernRngSeed(const CavernParams& p, int64_t seed, int64_t cx, int64_t cz) {
    uint64_t h = (sx(seed) * p.seedMul) ^ (sx(cx) * p.seedMulX);
    h = h ^ (sx(cz) * p.seedMulZ) ^ p.seedFinalXor;
    return static_cast<int64_t>(h);
}

int64_t formationSeed(const CavernParams& p, int64_t seed, int64_t wx, int64_t wz) {
    uint64_t h = sx(seed) ^ p.formXor;
    h ^= sx(wx) * p.formMulX;
    h = rotl64(h, p.formRot);
    h ^= sx(wz) * p.formMulZ;
    return static_cast<int64_t>(h);
}

/* CavernCarver.centerY: a depth below this column's surface (yFloor - 4 = the old y >= 2 clamp). */
float cavernCenterY(const CavernParams& p, Generator& g, int32_t yFloor, float ox, float oz, JavaRandom& rng) {
    const int32_t surface = heightOracle(g, rnd(ox), rnd(oz));
    const int depth = p.depthMin + rng.nextInt(p.depthMax - p.depthMin);
    const int64_t y = static_cast<int64_t>(surface) - depth;
    const int64_t lo = yFloor - 4;
    return static_cast<float>(y > lo ? y : lo);
}

void cavernOriginOf(const CavernParams& p, Generator& g, int64_t seed, int32_t yFloor, int64_t cx, int64_t cz,
                    float out[3]) {
    JavaRandom rng(cavernRngSeed(p, seed, cx, cz));
    out[0] = static_cast<float>(cx * CHUNK + rng.nextInt(CHUNK));
    out[2] = static_cast<float>(cz * CHUNK + rng.nextInt(CHUNK));
    out[1] = cavernCenterY(p, g, yFloor, out[0], out[2], rng);
}

bool nearestCavernChunk(const CavernParams& p, int64_t seed, int64_t cx, int64_t cz, int r, int64_t out[2]) {
    int64_t best = INT64_MAX;
    bool found = false;
    for (int dcx = -r; dcx <= r; dcx++) {
        for (int dcz = -r; dcz <= r; dcz++) {
            if (!hasCavern(p, seed, cx + dcx, cz + dcz)) continue;
            const int64_t d = dcx * dcx + dcz * dcz;
            if (d < best) {
                best = d;
                out[0] = cx + dcx;
                out[1] = cz + dcz;
                found = true;
            }
        }
    }
    return found;
}

/* PerlinWormCarver.nearestCavernAnchor */
bool cavernAnchor(const CarveCtx& c, int64_t cx, int64_t cz, float ox, float oy, float oz, float out[3]) {
    float bestD = 0;
    bool found = false;
    for (const CavernParams* p : {&CAVERN, &MEGA}) {
        int64_t n[2] = {0, 0};
        if (!nearestCavernChunk(*p, c.seeds.seed, cx, cz, CAVERN_CONNECTOR_RADIUS, n)) continue;
        float a[3];
        cavernOriginOf(*p, c.g, c.seeds.seed, c.yFloor(), n[0], n[1], a);
        const float dx = a[0] - ox, dy = a[1] - oy, dz = a[2] - oz;
        const float d = dx * dx + dy * dy + dz * dz;
        if (!found || d < bestD) {
            bestD = d;
            out[0] = a[0]; out[1] = a[1]; out[2] = a[2];
            found = true;
        }
    }
    return found;
}

void spawnWorms(CarveCtx& c, int64_t srcCx, int64_t srcCz, WormWalk& out) {
    JavaRandom rng(chunkRngSeed(c.seeds.seed, srcCx, srcCz));
    const auto ox = static_cast<float>(srcCx * CHUNK + rng.nextInt(CHUNK));
    const auto oz = static_cast<float>(srcCz * CHUNK + rng.nextInt(CHUNK));
    const float oy = originY(c, ox, oz, rng);
    const float yaw = rng.nextFloat() * static_cast<float>(JPI * 2);
    const float pitch = -0.15f + rng.nextFloat() * 0.3f;
    const bool spawnTwin = rng.nextFloat() < TWIN_CHANCE;
    const int64_t primarySeed = rng.nextLong();
    const int64_t twinSeed = rng.nextLong();
    const bool spawnConnector = rng.nextFloat() < CONNECTOR_CHANCE;
    const int64_t connectorSeed = rng.nextLong();
    const bool spawnCavernConnector = rng.nextFloat() < CAVERN_CONNECTOR_CHANCE;
    const int64_t cavernConnectorSeed = rng.nextLong();

    std::vector<Segment> queue;
    queue.push_back({ox, oy, oz, yaw, pitch, MAX_STEPS, MAX_BRANCHES, primarySeed, false, {0, 0, 0}});
    if (spawnTwin) {
        queue.push_back({ox, oy, oz, yaw + static_cast<float>(JPI), -pitch, MAX_STEPS, MAX_BRANCHES, twinSeed,
                         false, {0, 0, 0}});
    }
    if (spawnConnector) {
        int64_t n[2] = {0, 0};
        if (nearestWormChunk(c.seeds.seed, srcCx, srcCz, n)) {
            float t[3];
            computeOrigin(c, n[0], n[1], t);
            pushConnector(queue, ox, oy, oz, t, CONNECTOR_MAX_STEPS, connectorSeed);
        }
    }
    if (spawnCavernConnector) {
        float t[3];
        if (cavernAnchor(c, srcCx, srcCz, ox, oy, oz, t)) {
            pushConnector(queue, ox, oy, oz, t, CAVERN_CONNECTOR_MAX_STEPS, cavernConnectorSeed);
        }
    }
    while (!queue.empty()) {
        Segment seg = queue.back();
        queue.pop_back();
        walkWorm(c, seg, queue, out);
    }
}

void carveCavernEllipsoid(CarveCtx& c, const CavernParams& p, float wx, float wy, float wz, float radius) {
    const int rxz = static_cast<int>(std::ceil(static_cast<double>(radius)));
    const int ry = static_cast<int>(std::ceil(static_cast<double>(radius * p.ySquash)));
    const int64_t wxi = rnd(wx), wyi = rnd(wy), wzi = rnd(wz);
    if (wxi + rxz < c.X0 || wxi - rxz >= c.X0 + CE) return;
    if (wzi + rxz < c.Z0 || wzi - rxz >= c.Z0 + CE) return;
    const float invRxz2 = 1.0f / (radius * radius);
    const float ySpan = radius * p.ySquash;
    const float invRy2 = 1.0f / (ySpan * ySpan);
    const int32_t floorH = c.g.cfg.floorY() + 1;
    for (int ox = -rxz; ox <= rxz; ox++) {
        const int64_t bx = wxi + ox - c.X0;
        if (bx < 0 || bx >= CE) continue;
        for (int oz = -rxz; oz <= rxz; oz++) {
            const int64_t bz = wzi + oz - c.Z0;
            if (bz < 0 || bz >= CE) continue;
            const int idx = colIdx(static_cast<int>(bx), static_cast<int>(bz));
            const int32_t surface = c.th[idx];
            if (surface <= floorH) continue;
            const float horizTerm = static_cast<float>(ox * ox + oz * oz) * invRxz2;
            if (horizTerm >= 1.0f) continue;
            const float maxOyTerm = 1.0f - horizTerm;
            for (int oy = -ry; oy <= ry; oy++) {
                if (static_cast<float>(oy * oy) * invRy2 >= maxOyTerm) continue;
                const int64_t by = wyi + oy;
                if (!c.yOk(by)) continue;
                if (by >= surface) continue;
                if (guardSeals(c.guard, idx, by, p.waterClearance)) continue;
                c.set(static_cast<int>(bx), by, static_cast<int>(bz));
            }
        }
    }
}

void carveOneCavern(CarveCtx& c, const CavernParams& p, int64_t srcCx, int64_t srcCz) {
    JavaRandom rng(cavernRngSeed(p, c.seeds.seed, srcCx, srcCz));
    const auto ox = static_cast<float>(srcCx * CHUNK + rng.nextInt(CHUNK));
    const auto oz = static_cast<float>(srcCz * CHUNK + rng.nextInt(CHUNK));
    const float oy = cavernCenterY(p, c.g, c.yFloor(), ox, oz, rng);
    const int blobs = p.minBlobs + rng.nextInt(p.maxBlobs - p.minBlobs + 1);
    for (int i = 0; i < blobs; i++) {
        const float dx = (rng.nextFloat() - 0.5f) * 2.0f * p.blobOffset;
        const float dy = (rng.nextFloat() - 0.5f) * 2.0f * (p.blobOffset * 0.45f);
        const float dz = (rng.nextFloat() - 0.5f) * 2.0f * p.blobOffset;
        const float r = p.baseRadius * (0.75f + rng.nextFloat() * 0.45f);
        carveCavernEllipsoid(c, p, ox + dx, oy + dy, oz + dz, r);
    }
}

/* -------------------------------------------------------------------------------- ravines */
constexpr int RAVINE_DIVISOR = 450;
/* Profile kind with SBW_FLAG_PROFILE_CARVE: a source chunk's ravine chance at carve density 15 (scaled down
 * linearly with density; 0 = none). Legacy is a flat 1/450 everywhere. */
constexpr double PROFILE_RAVINE_RATE = 1.0 / 8.0;
constexpr double PROFILE_KARST_SINK_RATE = 1.0 / 10.0;   /* legacy sinkholes: 1/40 */
constexpr int R_DEPTH_MIN = 25, R_DEPTH_MAX = 60;
constexpr float R_HALF_WIDTH_MIN = 2.5f, R_HALF_WIDTH_MAX = 6.0f;
constexpr int R_LENGTH_MIN = 70, R_LENGTH_MAX = 170;
constexpr float R_STEP = 1.0f;
constexpr float R_YAW_DRIFT = 0.06f;
constexpr float R_YAW_SCALE = 1.0f / 64.0f;
constexpr float R_SIN_MIN = 0.35f, R_SIN_MAX = 1.5f;
constexpr float R_FLOOR_AMP = 7.0f;
constexpr float R_FLOOR_SCALE = 1.0f / 40.0f;
constexpr float R_TAPER_MIN = 0.5f, R_TAPER_MAX = 2.3f;
constexpr float R_FLARE_MAX = 0.75f;
constexpr float R_WOBBLE_AMP_MAX = 0.45f;
constexpr float R_WOBBLE_SCALE_MIN = 1.0f / 55.0f, R_WOBBLE_SCALE_MAX = 1.0f / 16.0f;
constexpr float R_TILT_MAX = 0.30f;
constexpr float R_DIP_MIN = 23.0f, R_DIP_MAX = 90.0f;
constexpr float R_DIP_BIAS_POW = 3.0f;
constexpr float R_DIP_DRIFT_MAX = 25.0f;
constexpr float R_DIP_DRIFT_SCALE = 1.0f / 90.0f;
constexpr float R_ARM_MIN = 0.30f, R_ARM_MAX = 0.70f;
constexpr float R_MIN_USEFUL = 0.8f;
constexpr int R_OVERCUT = 70;
/* Profile carve styles may exceed the legacy ranges; the scan window covers both, so a long rift is never cut off at
 * a footprint edge (legacy results are unchanged: a wider window only scans more sources). */
constexpr int R_LENGTH_MAX_ALL = 300;
constexpr float R_HALF_WIDTH_MAX_ALL = 9.0f;
constexpr int R_DEPTH_MAX_ALL = 100;

int ravineScanRadius() {
    const float maxHalfWidth = R_HALF_WIDTH_MAX_ALL * (1.0f + R_WOBBLE_AMP_MAX) * (1.0f + R_FLARE_MAX);
    const auto minSinDip = static_cast<float>(std::sin(R_DIP_MIN * JPI / 180.0));
    const auto maxShear = static_cast<float>(1.0 / std::tan(R_DIP_MIN * JPI / 180.0));
    const float maxHalfWidthH = maxHalfWidth / minSinDip;
    const float maxLateral = (static_cast<float>(R_DEPTH_MAX_ALL) * (1.0f + R_TILT_MAX) + R_FLOOR_AMP) * maxShear;
    const float maxReach = (static_cast<float>(R_LENGTH_MAX_ALL) * R_ARM_MAX) + maxLateral + maxHalfWidthH + 2.0f;
    return static_cast<int>(std::ceil(maxReach / static_cast<float>(CHUNK))) + 1;
}

bool hasRavine(int64_t seed, int64_t cx, int64_t cz) {
    uint64_t h = sx(seed) ^ UINT64_C(0x5AF17E9A5AF17E9A);
    h ^= sx(cx) * UINT64_C(0xD6E8FEB86659FD93);
    h = rotl64(h, 27);
    h ^= sx(cz) * UINT64_C(0xA24BAED4963EE407);
    h = splitmix64Finalize(h);
    return javaFloorMod(static_cast<int64_t>(h), RAVINE_DIVISOR) == 0;
}

int64_t ravineRngSeed(int64_t seed, int64_t cx, int64_t cz) {
    return static_cast<int64_t>(((sx(seed) * UINT64_C(0x27BB2EE687B0B0FD)) ^ (sx(cx) * UINT64_C(0x9E3779B97F4A7C15)))
                                ^ (sx(cz) * UINT64_C(0xC2B2AE3D27D4EB4F)));
}

inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : std::min(v, hi); }

void carveSlice(CarveCtx& c, int64_t wx, int64_t wz, float halfWidth, float flare, int cutDepth, float sinDip,
                float shear, float nx, float nz) {
    const float rimWidth = halfWidth * (1.0f + flare);
    const float run = shear * (static_cast<float>(cutDepth) + R_FLOOR_AMP);
    const float pad = rimWidth / sinDip + 1.0f;
    const int64_t x0 = std::max<int64_t>(c.X0, static_cast<int64_t>(std::floor(static_cast<double>(wx) + std::min(0.0f, run * nx) - pad)));
    const int64_t x1 = std::min<int64_t>(c.X0 + CE - 1, static_cast<int64_t>(std::ceil(static_cast<double>(wx) + std::max(0.0f, run * nx) + pad)));
    if (x0 > x1) return;
    const int64_t z0 = std::max<int64_t>(c.Z0, static_cast<int64_t>(std::floor(static_cast<double>(wz) + std::min(0.0f, run * nz) - pad)));
    const int64_t z1 = std::min<int64_t>(c.Z0 + CE - 1, static_cast<int64_t>(std::ceil(static_cast<double>(wz) + std::max(0.0f, run * nz) + pad)));
    if (z0 > z1) return;

    const float rimWidthSq = rimWidth * rimWidth;
    const float floorNoise = c.g.ravinePath.noise3D(static_cast<float>(wx) * R_FLOOR_SCALE, 512.0f,
                                                    static_cast<float>(wz) * R_FLOOR_SCALE);
    const int waterClearance = static_cast<int>(std::ceil(static_cast<double>(rimWidth / sinDip))) + 2;
    const int64_t floorMin = c.g.cfg.minY + 2 + c.drop;

    for (int64_t wcx = x0; wcx <= x1; wcx++) {
        const int bx = static_cast<int>(wcx - c.X0);
        const auto ox = static_cast<float>(wcx - wx);
        for (int64_t wcz = z0; wcz <= z1; wcz++) {
            const int bz = static_cast<int>(wcz - c.Z0);
            const auto oz = static_cast<float>(wcz - wz);
            const float v = oz * nx - ox * nz;
            const float vSq = v * v;
            if (vSq >= rimWidthSq) continue;
            const float u = ox * nx + oz * nz;
            const int idx = colIdx(bx, bz);
            const int32_t surface = c.th[idx];
            if (surface <= c.g.cfg.floorY() + 1) continue;
            int64_t floor = static_cast<int64_t>(surface) - cutDepth + javaRoundFloat(floorNoise * R_FLOOR_AMP);
            if (floor < floorMin) floor = floorMin;
            const int64_t top = std::min<int64_t>(surface, floor + R_OVERCUT);
            if (top <= floor) continue;
            int64_t yLo = floor, yHi = top;
            if (shear > 1e-4f) {
                const float q = static_cast<float>(std::sqrt(static_cast<double>(rimWidthSq - vSq))) / sinDip;
                yLo = std::max<int64_t>(floor, floor + static_cast<int64_t>(std::floor(static_cast<double>((u - q) / shear))));
                yHi = std::min<int64_t>(top, floor + static_cast<int64_t>(std::ceil(static_cast<double>((u + q) / shear))) + 1);
            }
            for (int64_t by = yLo; by < yHi; by++) {
                if (!c.yOk(by)) continue;
                const auto t = static_cast<float>(by - floor);
                const float radius = halfWidth * (1.0f + flare * std::min(1.0f, t / static_cast<float>(cutDepth)));
                const float lateral = (u - shear * t) * sinDip;
                if (vSq + lateral * lateral >= radius * radius) continue;
                if (guardSeals(c.guard, idx, by, waterClearance)) continue;
                c.set(bx, by, bz);
            }
        }
    }
}

void carveOneRavine(CarveCtx& c, int64_t srcCx, int64_t srcCz, RavineWalk& out) {
    JavaRandom rng(ravineRngSeed(c.seeds.seed, srcCx, srcCz));
    const auto x = static_cast<float>(srcCx * CHUNK + rng.nextInt(CHUNK));
    const auto z = static_cast<float>(srcCz * CHUNK + rng.nextInt(CHUNK));
    float yaw = rng.nextFloat() * static_cast<float>(JPI * 2);
    int length = R_LENGTH_MIN + rng.nextInt(R_LENGTH_MAX - R_LENGTH_MIN);
    int depth = R_DEPTH_MIN + rng.nextInt(R_DEPTH_MAX - R_DEPTH_MIN);
    float halfWidth = R_HALF_WIDTH_MIN + rng.nextFloat() * (R_HALF_WIDTH_MAX - R_HALF_WIDTH_MIN);
    const float widthPow = lerpf(R_TAPER_MIN, R_TAPER_MAX, rng.nextFloat());
    const float depthPow = lerpf(R_TAPER_MIN, R_TAPER_MAX, rng.nextFloat());
    float flare = rng.nextFloat() * R_FLARE_MAX;
    float sinuosity = lerpf(R_SIN_MIN, R_SIN_MAX, rng.nextFloat());
    const float wobbleAmp = rng.nextFloat() * R_WOBBLE_AMP_MAX;
    const float wobbleScale = lerpf(R_WOBBLE_SCALE_MIN, R_WOBBLE_SCALE_MAX, rng.nextFloat());
    const float tilt = (rng.nextFloat() - 0.5f) * 2.0f * R_TILT_MAX;
    const float armSplit = lerpf(R_ARM_MIN, R_ARM_MAX, rng.nextFloat());
    const float phaseX = rng.nextFloat() * 4096.0f;
    const float phaseZ = rng.nextFloat() * 4096.0f;
    float dipBase = R_DIP_MAX - (R_DIP_MAX - R_DIP_MIN)
        * static_cast<float>(std::pow(static_cast<double>(rng.nextFloat()), static_cast<double>(R_DIP_BIAS_POW)));
    const float dipDriftAmp = rng.nextFloat() * R_DIP_DRIFT_MAX;
    const JavaSimplex& path = c.g.ravinePath;
    // Terrain-aware (profile kind + SBW_FLAG_PROFILE_CARVE): the carve node's style shapes the cut and its strike
    // aims it; a pull keeps gorges and rifts on their axis. Legacy worlds never enter this block.
    float yaw0 = yaw, pull = 0.0f;
    const uint16_t code = profileCarve(c.g) ? sourceCode(c.g, srcCx, srcCz) : 0;
    if (code != 0) {
        const int style = (code >> 4) & 7, strike = (code >> 7) & 15, dclass = (code >> 11) & 31;
        const float u1 = rng.nextFloat(), u2 = rng.nextFloat(), jitter = rng.nextFloat() - 0.5f;
        yaw0 = static_cast<float>(JPI * 0.5 - strike * (JPI / 16.0)) + 0.3f * jitter;
        const float depthMul = 0.6f + 0.8f * static_cast<float>(dclass) / 31.0f;
        switch (style) {
            case SBW_CARVE_SLOT:      // narrow, deep, winding: a slot canyon in arid rock
                halfWidth = 1.2f + 1.3f * u1;
                depth = static_cast<int>(static_cast<float>(depth) * 1.2f * depthMul);
                sinuosity = 1.2f + 0.3f * u2;
                flare *= 0.2f;
                dipBase = std::max(dipBase, 72.0f);
                break;
            case SBW_CARVE_GORGE:     // a river's cut through high ground: long, on its axis
                halfWidth = 3.0f + 3.0f * u1;
                length = static_cast<int>(static_cast<float>(length) * 1.4f);
                depth = static_cast<int>(static_cast<float>(depth) * depthMul);
                sinuosity *= 0.5f;
                pull = 0.03f;
                break;
            case SBW_CARVE_RIFT:      // wide, straight, stepped
                halfWidth = 6.0f + 3.0f * u1;
                length = static_cast<int>(static_cast<float>(length) * 1.75f);
                depth = static_cast<int>(static_cast<float>(depth) * 0.8f * depthMul);
                sinuosity *= 0.25f;
                flare = 0.2f + 0.3f * u2;
                pull = 0.06f;
                break;
            default:                  // ravine: today's shape, aimed along the incision
                depth = static_cast<int>(static_cast<float>(depth) * depthMul);
                pull = 0.015f;
                break;
        }
        length = std::min(length, R_LENGTH_MAX_ALL);
        depth = std::max(1, std::min(depth, R_DEPTH_MAX_ALL));
        halfWidth = std::min(halfWidth, R_HALF_WIDTH_MAX_ALL);
        yaw = yaw0;
    }

    for (int dir = 0; dir < 2; dir++) {
        float px = x, pz = z;
        float pyaw = dir == 0 ? yaw : yaw + static_cast<float>(JPI);
        const int arm = javaRoundFloat(static_cast<float>(length) * (dir == 0 ? armSplit : 1.0f - armSplit));
        if (arm < 2) continue;
        const float tiltSign = dir == 0 ? 1.0f : -1.0f;
        for (int step = 0; step < arm; step++) {
            const float n = path.noise3D((px + phaseX) * R_YAW_SCALE, 0.0f, (pz + phaseZ) * R_YAW_SCALE);
            pyaw += n * R_YAW_DRIFT * sinuosity;
            if (pull > 0.0f) {   // back toward the strike axis (this arm's direction)
                float d = (dir == 0 ? yaw0 : yaw0 + static_cast<float>(JPI)) - pyaw;
                d = static_cast<float>(std::remainder(static_cast<double>(d), JPI * 2.0));
                pyaw += pull * d;
            }
            px += static_cast<float>(std::cos(static_cast<double>(pyaw))) * R_STEP;
            pz += static_cast<float>(std::sin(static_cast<double>(pyaw))) * R_STEP;
            const float progress = static_cast<float>(step) / static_cast<float>(arm);
            const auto sine = static_cast<float>(std::sin(static_cast<double>(1.0f - progress) * JPI * 0.5));
            float w = halfWidth * static_cast<float>(std::pow(static_cast<double>(sine), static_cast<double>(widthPow)));
            if (wobbleAmp > 0.0f) {
                const float wob = path.noise3D((px + phaseX) * wobbleScale, 256.0f, (pz + phaseZ) * wobbleScale);
                w *= 1.0f + wobbleAmp * wob;
            }
            if (w < R_MIN_USEFUL) break;
            const float depthScale = static_cast<float>(std::pow(static_cast<double>(sine), static_cast<double>(depthPow)))
                * (1.0f + tiltSign * tilt * progress);
            const int cutDepth = std::max(1, javaRoundFloat(static_cast<float>(depth) * depthScale));
            const float dipNoise = path.noise3D((px + phaseX) * R_DIP_DRIFT_SCALE, 768.0f, (pz + phaseZ) * R_DIP_DRIFT_SCALE);
            const auto dipRad = static_cast<float>(static_cast<double>(clampf(dipBase + dipDriftAmp * dipNoise, R_DIP_MIN, R_DIP_MAX)) * JPI / 180.0);
            const auto sinDip = static_cast<float>(std::sin(static_cast<double>(dipRad)));
            const float shear = static_cast<float>(std::cos(static_cast<double>(dipRad))) / sinDip;
            const float leanSign = dir == 0 ? 1.0f : -1.0f;
            const float nx = -static_cast<float>(std::sin(static_cast<double>(pyaw))) * leanSign;
            const float nz = static_cast<float>(std::cos(static_cast<double>(pyaw))) * leanSign;
            out.s.push_back({javaRoundFloat(px), javaRoundFloat(pz), w, flare, cutDepth, sinDip, shear, nx, nz});
        }
    }
}

/* ------------------------------------------------------------------------------ sinkholes */
constexpr int SINK_DIVISOR = 40;
constexpr float SINK_MOUTH_MIN = 3.5f, SINK_MOUTH_MAX = 7.0f;
constexpr float SINK_THROAT = 0.45f;
constexpr int SINK_DEPTH_MIN = 15, SINK_DEPTH_MAX = 45, SINK_DEPTH_LIMIT = 90;
constexpr int SINK_ANCHOR_RADIUS = 3;
constexpr float SINK_RIM_AMP = 1.6f;
constexpr float SINK_RIM_SCALE = 1.0f / 9.0f;
const int SINK_SCAN = static_cast<int>(std::ceil((SINK_MOUTH_MAX + SINK_RIM_AMP + CHUNK) / CHUNK)) + 1;
const int SINK_WATER_CLEARANCE = static_cast<int>(std::ceil(SINK_MOUTH_MAX)) + 2;

bool hasSinkhole(int64_t seed, int64_t cx, int64_t cz) {
    uint64_t h = sx(seed) ^ UINT64_C(0x51E0DD1E51E0DD1E);
    h ^= sx(cx) * UINT64_C(0xFF51AFD7ED558CCD);
    h = rotl64(h, 31);
    h ^= sx(cz) * UINT64_C(0xC4CEB9FE1A85EC53);
    h = splitmix64Finalize(h);
    return javaFloorMod(static_cast<int64_t>(h), SINK_DIVISOR) == 0;
}

uint16_t carveCodeAtImpl(Generator& g, int64_t x, int64_t z) {
    if (g.cfg.kind != SBW_KIND_PROFILE || (g.cfg.flags & SBW_FLAG_PROFILE_CARVE) == 0) return 0;
    const int64_t cx = floorDiv64(x, CE), cz = floorDiv64(z, CE);
    auto b = g.base(cx, cz);
    if (!b) return 0;
    return b->carve[(z - cz * CE) * CE + (x - cx * CE)];
}

bool profileCarve(const Generator& g) {
    return g.cfg.kind == SBW_KIND_PROFILE && (g.cfg.flags & SBW_FLAG_PROFILE_CARVE) != 0;
}
uint16_t sourceCode(Generator& g, int64_t sx, int64_t sz) {
    return carveCodeAtImpl(g, sx * CHUNK + CHUNK / 2, sz * CHUNK + CHUNK / 2);
}
/* Uniform [0, 1) per (seed, chunk), independent of the legacy gate's hash. */
double chunkUniform(int64_t seed, int64_t cx, int64_t cz, uint64_t salt) {
    uint64_t h = sx(seed) ^ salt;
    h ^= sx(cx) * UINT64_C(0x9E3779B97F4A7C15);
    h = rotl64(h, 29);
    h ^= sx(cz) * UINT64_C(0xC2B2AE3D27D4EB4F);
    return static_cast<double>(splitmix64Finalize(h) >> 11) * (1.0 / 9007199254740992.0);
}


/* Profile kind with SBW_FLAG_PROFILE_CARVE: karst terrain (carve style KARST) is riddled with sinkholes
 * (PROFILE_KARST_SINK_RATE per source chunk); elsewhere the legacy hash gate. */
bool sinkholeSourceAt(Generator& g, int64_t seed, int64_t cx, int64_t cz) {
    if (profileCarve(g) && ((sourceCode(g, cx, cz) >> 4) & 7) == SBW_CARVE_KARST) {
        return chunkUniform(seed, cx, cz, UINT64_C(0x5114D01EC0DE0002)) < PROFILE_KARST_SINK_RATE;
    }
    return hasSinkhole(seed, cx, cz);
}


int64_t sinkRngSeed(int64_t seed, int64_t cx, int64_t cz) {
    return static_cast<int64_t>(((sx(seed) * UINT64_C(0x2545F4914F6CDD1D)) ^ (sx(cx) * UINT64_C(0x9E3779B97F4A7C15)))
                                ^ (sx(cz) * UINT64_C(0xC2B2AE3D27D4EB4F)));
}

void carveOneSinkhole(CarveCtx& c, int64_t srcCx, int64_t srcCz) {
    JavaRandom rng(sinkRngSeed(c.seeds.seed, srcCx, srcCz));
    const int64_t ox = srcCx * CHUNK + rng.nextInt(CHUNK);
    const int64_t oz = srcCz * CHUNK + rng.nextInt(CHUNK);
    const float mouth = SINK_MOUTH_MIN + rng.nextFloat() * (SINK_MOUTH_MAX - SINK_MOUTH_MIN);
    const int fallbackDepth = SINK_DEPTH_MIN + rng.nextInt(SINK_DEPTH_MAX - SINK_DEPTH_MIN);
    if (c.water(ox, oz) != NO_WATER) return;
    const int64_t surface = c.height(ox, oz);
    int64_t floor = surface - fallbackDepth;
    float anchor[3];
    if (nearestWormOrigin(c.g, c.seeds, srcCx, srcCz, SINK_ANCHOR_RADIUS, anchor)) {
        const int64_t anchorY = javaRoundFloat(anchor[1]);
        if (anchorY < surface - 8) {
            floor = std::max<int64_t>(anchorY - 2, surface - SINK_DEPTH_LIMIT);
        }
    }
    const int64_t floorMin = c.g.cfg.minY + 2 + c.drop;
    if (floor < floorMin) floor = floorMin;
    if (floor >= surface) return;
    const int r = static_cast<int>(std::ceil(mouth + SINK_RIM_AMP));
    if (ox + r < c.X0 || ox - r >= c.X0 + CE) return;
    if (oz + r < c.Z0 || oz - r >= c.Z0 + CE) return;
    const int64_t span = surface - floor;
    for (int dx = -r; dx <= r; dx++) {
        const int64_t bx = ox + dx - c.X0;
        if (bx < 0 || bx >= CE) continue;
        for (int dz = -r; dz <= r; dz++) {
            const int64_t bz = oz + dz - c.Z0;
            if (bz < 0 || bz >= CE) continue;
            const int idx = colIdx(static_cast<int>(bx), static_cast<int>(bz));
            const int32_t colSurface = c.th[idx];
            if (colSurface <= c.g.cfg.floorY() + 1) continue;
            const auto dist = static_cast<float>(std::sqrt(static_cast<double>(dx * dx + dz * dz)));
            const float rim = c.g.sinkRim.noise3D(static_cast<float>(ox + dx) * SINK_RIM_SCALE, 0.0f,
                                                  static_cast<float>(oz + dz) * SINK_RIM_SCALE) * SINK_RIM_AMP;
            const int64_t top = std::min<int64_t>(colSurface, surface);
            for (int64_t by = floor; by < top; by++) {
                const float t = span <= 0 ? 1.0f : static_cast<float>(by - floor) / static_cast<float>(span);
                const float radius = mouth * (SINK_THROAT + (1.0f - SINK_THROAT) * t) + rim;
                if (radius <= 0.0f || dist >= radius) continue;
                if (!c.yOk(by)) continue;
                if (guardSeals(c.guard, idx, by, SINK_WATER_CLEARANCE)) continue;
                c.set(static_cast<int>(bx), by, static_cast<int>(bz));
            }
        }
    }
}

/* ------------------------------------------------------------------------ sea stacks and tower karst */
/* Carve styles SEA_STACKS and TOWERS (profile kind + SBW_FLAG_PROFILE_CARVE only). Both are POINTWISE fields: every
 * carved cell follows from (seed, world position) and the column oracles (heights, water, carve codes), never from
 * the footprint asking, so two footprints sharing a column carve it identically. Layouts are jittered-grid Voronoi
 * cells hashed per grid cell. Horizontal sizes are metres (blocks = m / horizontal_m_per_block, so a 1:2 world gets
 * the same landform twice as wide in blocks) with a playable floor in blocks; vertical sizes are blocks, like every
 * other carver's (the vertical curve is per altitude band, not per scale). */
double blocksOf(const Generator& g, double metres, double minBlocks) {
    return std::max(minBlocks, metres / g.cfg.hmPerBlock);
}

/* k-th uniform [0, 1) of a site hash */
inline double hashU(uint64_t h, int k) {
    return static_cast<double>(splitmix64Finalize(h + static_cast<uint64_t>(k + 1) * HASH_A) >> 11)
           * (1.0 / 9007199254740992.0);
}

struct Site {
    double x = 0, z = 0;   /* world position, blocks */
    uint64_t h = 0;
    int style = SBW_CARVE_NONE;
};

/* Sites of the grid cells around [x0, x1) x [z0, z1): one per `cell`-block grid cell, jittered inside its middle 70 %
 * (no two sites closer than 0.3 cell), with a 2-cell ring so any point of the box finds its two nearest sites. */
struct SiteGrid {
    double cell = 1.0;
    int64_t gx0 = 0, gz0 = 0;
    int nx = 0, nz = 0;
    std::vector<Site> s;

    SiteGrid(int64_t seed, uint64_t salt, double cellBlocks, int64_t x0, int64_t z0, int64_t x1, int64_t z1)
        : cell(cellBlocks) {
        gx0 = static_cast<int64_t>(std::floor(static_cast<double>(x0) / cell)) - 2;
        gz0 = static_cast<int64_t>(std::floor(static_cast<double>(z0) / cell)) - 2;
        nx = static_cast<int>(static_cast<int64_t>(std::floor(static_cast<double>(x1) / cell)) + 2 - gx0 + 1);
        nz = static_cast<int>(static_cast<int64_t>(std::floor(static_cast<double>(z1) / cell)) + 2 - gz0 + 1);
        s.resize(static_cast<size_t>(nx) * static_cast<size_t>(nz));
        for (int iz = 0; iz < nz; iz++) {
            for (int ix = 0; ix < nx; ix++) {
                const int64_t gx = gx0 + ix, gz = gz0 + iz;
                uint64_t h = sx(seed) ^ salt;
                h ^= sx(gx) * UINT64_C(0xD6E8FEB86659FD93);
                h = rotl64(h, 31);
                h ^= sx(gz) * UINT64_C(0xA24BAED4963EE407);
                h = splitmix64Finalize(h);
                Site& t = s[static_cast<size_t>(iz * nx + ix)];
                t.h = h;
                t.x = (static_cast<double>(gx) + 0.15 + 0.7 * hashU(h, 100)) * cell;
                t.z = (static_cast<double>(gz) + 0.15 + 0.7 * hashU(h, 101)) * cell;
            }
        }
    }

    /* The nearest two sites of (px, pz) and their squared distances (5 x 5 cells: exact for this jitter). */
    void nearest2(double px, double pz, const Site*& a, double& da2, const Site*& b, double& db2) const {
        const int64_t gx = static_cast<int64_t>(std::floor(px / cell)), gz = static_cast<int64_t>(std::floor(pz / cell));
        a = b = nullptr;
        da2 = db2 = 1e300;
        for (int64_t iz = gz - 2; iz <= gz + 2; iz++) {
            for (int64_t ix = gx - 2; ix <= gx + 2; ix++) {
                const Site& t = s[static_cast<size_t>((iz - gz0) * nx + (ix - gx0))];
                const double dx = t.x - px, dz = t.z - pz, d2 = dx * dx + dz * dz;
                if (d2 < da2) {
                    b = a;
                    db2 = da2;
                    a = &t;
                    da2 = d2;
                } else if (d2 < db2) {
                    b = &t;
                    db2 = d2;
                }
            }
        }
    }
};

/* Distance of a point (squared distances da2 <= db2 to sites a, b) from the a|b cell border. */
inline double borderDistance(const Site& a, double da2, const Site& b, double db2) {
    const double dx = b.x - a.x, dz = b.z - a.z;
    return (db2 - da2) / (2.0 * std::sqrt(dx * dx + dz * dz));
}

/* Carve codes by world column, holding the column cube's profile tile it last read. */
struct CodeReader {
    Generator& g;
    int64_t cx = INT64_MIN, cz = INT64_MIN;
    std::shared_ptr<const BaseTile> t;
    explicit CodeReader(Generator& gen) : g(gen) {}
    uint16_t at(int64_t x, int64_t z) {
        const int64_t tx = floorDiv64(x, CE), tz = floorDiv64(z, CE);
        if (!t || tx != cx || tz != cz) {
            t = g.base(tx, tz);
            cx = tx;
            cz = tz;
        }
        return t ? t->carve[(z - tz * CE) * CE + (x - tx * CE)] : 0;
    }
};

/* SEA_STACKS. Within a land band behind every sea column (standing water at sea level), per column:
 *   retreat = STACK_BAND * exposure * (0.45..1, smooth noise): how far the cliff has been cut back.
 *   d <= retreat (d = distance to the nearest sea column): the eroded zone. Cut down to the wave-cut platform -
 *     the water surface (max of sea level and every 4-neighbour's water level) - except the discs of STACK_KEEP of
 *     the Voronoi sites, left standing: a site inside the eroded zone (its own distance to the sea under its own
 *     retreat) is a STACK, detached from the cliff by a moat STACK_GAP wide all round (cut into the cliff behind it:
 *     a cove), any other disc reaching into the zone is a headland. Stack and headland rims get the wave notch;
 *     STACK_ARCH_RATE of them are pierced by an arch (a tunnel along a hashed bearing through the site,
 *     STACK_CAVE_H.. blocks high).
 *   retreat < d <= retreat + notch: the wave notch, STACK_NOTCH_H blocks over the platform, under the cliff.
 *   retreat < d <= retreat + cave: sea caves along STACK_CAVE_RATE of the cell borders, STACK_CAVE_H.. high.
 * Ground lower than sea + STACK_MIN_CLIFF is never cut (beaches, flat coasts); a roof thinner than STACK_ROOF falls
 * in (the cut stays open).
 *
 * Flooding. A cut whose platform is the sea surface itself (no water above sea level beside it) is FLOODABLE when
 * none of its face neighbours is a dry hole under the sea (every neighbour is ground up to sea level or water up to
 * it). A floodable cut column is FLOODED when a 4-connected chain of floodable cut columns, all within STACK_FLOOD_REACH
 * (reach + one stack spacing) of it, reaches a column beside open sea water (water at sea level over ground below it):
 * a pointwise test (the same answer from every footprint), so the flooded cut is connected to the sea except, rarely,
 * through a chain longer than the reach. A flooded column's platform drops to a rock shelf STACK_SHELF_MAX..1 blocks
 * under the sea (deepest at the shoreline, rising towards the cliff foot: t = d / retreat in thirds), and its carved
 * cells from the shelf up to sea level are sea water (CarveTile.flood) - notches, caves and arches included. A cut
 * that is not flooded (no chain to the sea: behind a beach berm, or beside a dry hollow) keeps the dry platform at sea
 * level, with uncut rock under it, so flooded and dry cuts never meet below sea level. The water guard of a flooded
 * column and of its face neighbours drops to the shelf (as beside standing water: later carvers keep their clearance),
 * and Density3D makes no caves there from just under the shelf up to sea level (CarveTile.seal), so the water stays
 * contained. */
constexpr double STACK_CELL_M = 420.0, STACK_CELL_MIN = 6.0;       /* stack spacing: 7 blocks at 1:4, 14 at 1:2 */
constexpr double STACK_RADIUS_LO = 0.22, STACK_RADIUS_HI = 0.36;   /* stack radius / spacing */
constexpr double STACK_KEEP = 0.6;                                 /* sites of the eroded zone left standing */
constexpr double STACK_GAP_M = 90.0, STACK_GAP_MIN = 1.5;          /* moat around a detached stack */
constexpr double STACK_BAND_M = 300.0, STACK_BAND_MIN = 4.0;       /* deepest retreat (exposure 15) */
constexpr double STACK_NOTCH_M = 75.0, STACK_NOTCH_MIN = 1.0;      /* wave notch depth into cliffs and stacks */
constexpr int STACK_NOTCH_H = 3;                                   /* wave notch height over the platform */
constexpr double STACK_CAVE_M = 360.0, STACK_CAVE_MIN = 4.0;       /* sea caves reach this far behind the cliff */
constexpr double STACK_LANE_M = 70.0, STACK_LANE_MIN = 1.2;        /* cave / arch half width */
constexpr double STACK_CAVE_RATE = 0.3;
constexpr double STACK_ARCH_RATE = 0.35;
constexpr int STACK_CAVE_H = 4, STACK_CAVE_H_SPAN = 3;             /* cave / arch height: 4..7 */
constexpr int STACK_ROOF = 2;
constexpr int STACK_MIN_CLIFF = 5;
constexpr int STACK_SHELF_MAX = 3;                                 /* flooded shelf depth under the sea: 3..1 */
constexpr uint64_t STACK_SALT = UINT64_C(0x5EA57AC6C0DE0006);

void carveSeaStacksImpl(CarveCtx& c) {
    if (!profileCarve(c.g) || c.drop != 0) return;
    Generator& g = c.g;
    const int32_t sea = g.cfg.sea;
    CodeReader codes(g);
    auto styleOn = [&](uint16_t k) { return ((k >> 4) & 7) == SBW_CARVE_SEA_STACKS && (k & 15) != 0; };
    bool any = false;
    for (int lz = 0; lz < CE && !any; lz++) {
        for (int lx = 0; lx < CE && !any; lx++) {
            any = styleOn(codes.at(c.X0 + lx, c.Z0 + lz)) && c.th[colIdx(lx, lz)] >= sea + STACK_MIN_CLIFF;
        }
    }
    if (!any) return;
    const double cell = blocksOf(g, STACK_CELL_M, STACK_CELL_MIN), band = blocksOf(g, STACK_BAND_M, STACK_BAND_MIN);
    const double notch = blocksOf(g, STACK_NOTCH_M, STACK_NOTCH_MIN), caveLen = blocksOf(g, STACK_CAVE_M, STACK_CAVE_MIN);
    const double lane = blocksOf(g, STACK_LANE_M, STACK_LANE_MIN), gap = blocksOf(g, STACK_GAP_M, STACK_GAP_MIN);
    const double reach = band + std::max({notch, caveLen, cell * STACK_RADIUS_HI + gap});
    const int R = static_cast<int>(std::ceil(reach)) + 1;     /* sea search radius */
    const int L = static_cast<int>(std::ceil(reach + cell));  /* STACK_FLOOD_REACH: flood chain radius */
    /* rings around the footprint: flood decided on ring 1 (the guard of the edge columns), cuts on ring 1 + L, levels
     * on ring 1 + L + R + 1 (oracle reads: pointwise; the footprint's own heights are ctx.th) */
    const int CR = 1 + L, G = CR + R + 1, W = CE + 2 * G;
    std::vector<int32_t> water(static_cast<size_t>(W * W)), hgt(static_cast<size_t>(W * W));
    std::vector<uint8_t> isSea(static_cast<size_t>(W * W));
    for (int iz = 0; iz < W; iz++) {
        for (int ix = 0; ix < W; ix++) {
            const int64_t x = c.X0 + ix - G, z = c.Z0 + iz - G;
            const int lx = ix - G, lz = iz - G;
            const int32_t w = c.water(x, z);
            const int32_t h = (lx >= 0 && lx < CE && lz >= 0 && lz < CE) ? c.th[colIdx(lx, lz)] : c.height(x, z);
            const size_t i = static_cast<size_t>(iz * W + ix);
            water[i] = w;
            hgt[i] = h;
            isSea[i] = w != NO_WATER && w <= sea + 1 && h < w;
        }
    }
    std::vector<std::array<int, 3>> offsets;   /* (d2, dx, dz) within R, nearest first */
    for (int dz = -R; dz <= R; dz++) {
        for (int dx = -R; dx <= R; dx++) {
            if (dx * dx + dz * dz <= R * R) offsets.push_back({dx * dx + dz * dz, dx, dz});
        }
    }
    std::sort(offsets.begin(), offsets.end());
    const SiteGrid sites(g.cfg.seed, STACK_SALT, cell, c.X0 - CR, c.Z0 - CR, c.X0 + CE + CR, c.Z0 + CE + CR);
    const float nf = static_cast<float>(1.0 / (2.0 * cell));
    auto retreatAt = [&](int64_t x, int64_t z, uint16_t k) {   /* how far the cliff is cut back at (x, z) */
        const double exposure = 0.35 + 0.65 * static_cast<double>(k & 15) / 15.0;
        const double n01 = 0.5 + 0.5 * static_cast<double>(g.sinkRim.noise3D(static_cast<float>(x) * nf, 2304.0f,
                                                                              static_cast<float>(z) * nf));
        return band * exposure * (0.45 + 0.55 * n01);
    };
    /* A site's own distance to the sea when it stands as a detached stack, else -1 (oracle reads only, memoized per
     * site: the same answer from every footprint). */
    std::vector<double> stackSea(sites.s.size(), -2.0);
    auto stackAt = [&](const Site& t) -> double {
        double& m = stackSea[static_cast<size_t>(&t - sites.s.data())];
        if (m > -1.5) return m;
        m = -1.0;
        const int64_t x = static_cast<int64_t>(std::floor(t.x)), z = static_cast<int64_t>(std::floor(t.z));
        const uint16_t k = codes.at(x, z);
        if (!styleOn(k) || c.water(x, z) != NO_WATER || c.height(x, z) < sea + STACK_MIN_CLIFF) return m;
        const double retreat = retreatAt(x, z, k);
        for (const auto& o : offsets) {
            const double d = std::sqrt(static_cast<double>(o[0]));
            if (d >= retreat) break;
            const int32_t w = c.water(x + o[1], z + o[2]);
            if (w != NO_WATER && w <= sea + 1 && c.height(x + o[1], z + o[2]) < w) {
                m = d;
                break;
            }
        }
        return m;
    };
    /* the dry cut of a column (grid index gi, within ring CR): [floor, top), top <= floor = none */
    struct Cut {
        int8_t state = 0;   /* 0 unknown, 1 known */
        int8_t floodable = -1;
        int64_t floor = 0, top = 0;
        double dsea = 0, retreat = 0;
    };
    std::vector<Cut> cuts(static_cast<size_t>(W * W));
    auto cutAt = [&](int gi) -> const Cut& {
        Cut& r = cuts[static_cast<size_t>(gi)];
        if (r.state != 0) return r;
        r.state = 1;
        const int ix = gi % W, iz = gi / W;
        const int64_t wx = c.X0 + ix - G, wz = c.Z0 + iz - G;
        const int64_t th = hgt[static_cast<size_t>(gi)];
        const uint16_t k = codes.at(wx, wz);
        if (!styleOn(k) || th < sea + STACK_MIN_CLIFF || water[static_cast<size_t>(gi)] != NO_WATER) return r;
        double dsea = -1.0;
        for (const auto& o : offsets) {
            if (isSea[static_cast<size_t>(gi + o[2] * W + o[1])]) {
                dsea = std::sqrt(static_cast<double>(o[0]));
                break;
            }
        }
        if (dsea < 0.0 || dsea > reach) return r;
        const double retreat = retreatAt(wx, wz, k);
        int64_t floor = sea;
        for (int d : {0, 1, -1, W, -W}) {   /* never under the water of the column or a face neighbour */
            const int32_t w = water[static_cast<size_t>(gi + d)];
            if (w != NO_WATER) floor = std::max<int64_t>(floor, w);
        }
        if (floor >= th) return r;
        const double px = static_cast<double>(wx) + 0.5, pz = static_cast<double>(wz) + 0.5;
        const Site *a, *b;
        double da2, db2;
        sites.nearest2(px, pz, a, da2, b, db2);
        const double dA = std::sqrt(da2);
        const double rA = cell * lerpf(static_cast<float>(STACK_RADIUS_LO), static_cast<float>(STACK_RADIUS_HI),
                                       static_cast<float>(hashU(a->h, 1)));
        const bool keep = hashU(a->h, 0) < STACK_KEEP, inDisc = keep && dA < rA;
        const bool stack = keep && dA < rA + gap && stackAt(*a) >= 0.0;
        const double bearing = hashU(a->h, 3) * JPI;
        const bool onArch = inDisc && hashU(a->h, 2) < STACK_ARCH_RATE
            && std::fabs((px - a->x) * std::sin(bearing) - (pz - a->z) * std::cos(bearing)) < lane;
        const int archH = STACK_CAVE_H + static_cast<int>(hashU(a->h, 4) * (STACK_CAVE_H_SPAN + 1));
        int64_t top = floor;
        if (stack || dsea <= retreat) {   /* eroded: a platform, but for the discs (notched) */
            if (!inDisc) top = th;
            else if (rA - dA < notch) top = floor + STACK_NOTCH_H;
        } else {
            const double behind = dsea - retreat;
            if (behind <= notch) top = floor + STACK_NOTCH_H;
            const uint64_t pair = a->h ^ b->h;   /* symmetric: both sides of a border agree */
            if (behind <= caveLen && hashU(pair, 5) < STACK_CAVE_RATE && borderDistance(*a, da2, *b, db2) < lane) {
                top = std::max<int64_t>(top, floor + STACK_CAVE_H + static_cast<int>(hashU(pair, 6) * (STACK_CAVE_H_SPAN + 1)));
            }
        }
        if (onArch) top = std::max<int64_t>(top, floor + archH);
        if (top < th && th - top < STACK_ROOF) top = th;
        top = std::min(top, th);
        r.floor = floor;
        r.top = top;
        r.dsea = dsea;
        r.retreat = retreat;
        return r;
    };
    /* face neighbours of a flooded column: ground up to sea level, or water up to it (never a dry hollow under it) */
    auto holdsSea = [&](int gi) {
        const int32_t w = water[static_cast<size_t>(gi)];
        return hgt[static_cast<size_t>(gi)] >= sea || (w != NO_WATER && w >= sea);
    };
    auto openSea = [&](int gi) {   /* sea water at sea level over ground below it */
        const int32_t w = water[static_cast<size_t>(gi)];
        return w != NO_WATER && w >= sea && hgt[static_cast<size_t>(gi)] < sea;
    };
    const int face[4] = {1, -1, W, -W};
    auto floodable = [&](int gi) -> bool {
        const Cut& k = cutAt(gi);
        Cut& m = cuts[static_cast<size_t>(gi)];
        if (m.floodable >= 0) return m.floodable != 0;
        bool ok = k.top > k.floor && k.floor == sea;
        for (int d : face) ok = ok && holdsSea(gi + d);
        m.floodable = ok ? 1 : 0;
        return ok;
    };
    /* flooded: a chain of floodable columns within L of gi reaches open sea (BFS, nearest first) */
    std::vector<int32_t> stamp(static_cast<size_t>(W * W), -1);
    std::vector<int> queue;
    int bfs = 0;
    auto flooded = [&](int gi) -> bool {
        if (!floodable(gi)) return false;
        bfs++;
        const int ox = gi % W, oz = gi / W;
        queue.clear();
        queue.push_back(gi);
        stamp[static_cast<size_t>(gi)] = bfs;
        for (size_t qi = 0; qi < queue.size(); qi++) {
            const int q = queue[qi];
            for (int d : face) {
                if (openSea(q + d)) return true;
            }
            for (int d : face) {
                const int n = q + d;
                const int dx = n % W - ox, dz = n / W - oz;
                if (dx * dx + dz * dz > L * L || stamp[static_cast<size_t>(n)] == bfs) continue;
                stamp[static_cast<size_t>(n)] = bfs;
                if (floodable(n)) queue.push_back(n);
            }
        }
        return false;
    };
    /* the shelf (lowest flooded y) of a flooded column */
    auto shelfOf = [&](int gi) -> int64_t {
        const Cut& k = cutAt(gi);
        const double t = k.dsea / std::max(k.retreat, 1.0);
        const int depth = std::clamp(STACK_SHELF_MAX - static_cast<int>(std::floor(t * STACK_SHELF_MAX)), 1, STACK_SHELF_MAX);
        return static_cast<int64_t>(sea) - depth;
    };
    /* footprint + ring 1: flooded shelves (FLOOD_NONE = dry or uncut) */
    const int F = CE + 2;
    std::vector<int64_t> shelf(static_cast<size_t>(F * F), FLOOD_NONE);
    for (int fz = 0; fz < F; fz++) {
        for (int fx = 0; fx < F; fx++) {
            const int gi = (fz - 1 + G) * W + (fx - 1 + G);
            if (flooded(gi)) shelf[static_cast<size_t>(fz * F + fx)] = shelfOf(gi);
        }
    }
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int idx = colIdx(lx, lz);
            const int gi = (lz + G) * W + (lx + G);
            const Cut& k = cutAt(gi);
            if (k.top <= k.floor) continue;
            const int fi = (lz + 1) * F + (lx + 1);
            int64_t lo = k.floor;
            if (shelf[static_cast<size_t>(fi)] != FLOOD_NONE) {
                lo = shelf[static_cast<size_t>(fi)];
                if (c.flood != nullptr) c.flood[idx] = static_cast<int32_t>(lo);
            }
            for (int64_t by = lo; by < k.top; by++) {
                if (c.yOk(by)) c.set(lx, by, lz);
            }
        }
    }
    if (c.seal != nullptr) {   /* the seal: the lowest shelf of the column and of its face neighbours */
        for (int lz = 0; lz < CE; lz++) {
            for (int lx = 0; lx < CE; lx++) {
                const int fi = (lz + 1) * F + (lx + 1);
                int64_t m = shelf[static_cast<size_t>(fi)];
                for (int d : {1, -1, F, -F}) m = std::min(m, shelf[static_cast<size_t>(fi + d)]);
                if (m != FLOOD_NONE) {
                    int32_t& sl = c.seal[colIdx(lx, lz)];
                    sl = std::min(sl, static_cast<int32_t>(m));
                }
            }
        }
    }
}

/* TOWERS. Voronoi cells of TOWER_CELL spacing whose site column carries style TOWERS: each keeps a tower - the disc
 * of radius TOWER_RADIUS * spacing around its site, cut back to TOWER_LANE from every cell border - and everything
 * else in the cell is carved down a corridor depth (TOWER_DEPTH.. blocks, smooth noise, times the incision depth
 * class like ravines) below the column's own surface. TOWER_PIT_RATE of the cells are carved out whole (pits /
 * poljes), TOWER_UNDERCUT_RATE of the towers get a foot notch TOWER_UNDERCUT_H blocks high, and every tower's top
 * two blocks are rounded off by TOWER_CROWN; otherwise the walls are vertical. Never under water (water columns and
 * the water guard are left alone). */
constexpr double TOWER_CELL_M = 540.0, TOWER_CELL_MIN = 7.0;       /* tower spacing: 9 blocks at 1:4, 18 at 1:2 */
constexpr double TOWER_RADIUS_LO = 0.34, TOWER_RADIUS_HI = 0.50;   /* tower radius / spacing */
constexpr double TOWER_LANE_M = 100.0, TOWER_LANE_MIN = 1.2;       /* corridor half width along a cell border */
constexpr double TOWER_PIT_RATE = 0.18;
constexpr double TOWER_UNDERCUT_RATE = 0.4;
constexpr double TOWER_UNDERCUT_M = 60.0, TOWER_UNDERCUT_MIN = 1.0; /* foot notch depth into the wall */
constexpr int TOWER_UNDERCUT_H = 3;
constexpr double TOWER_CROWN_M = 40.0, TOWER_CROWN_MIN = 0.7;      /* top rounding (top block; half on the next) */
constexpr int TOWER_DEPTH_MIN = 14, TOWER_DEPTH_SPAN = 18;         /* corridor depth: 14..32 blocks (class 15) */
constexpr int TOWER_WATER_CLEARANCE = 2;
constexpr uint64_t TOWER_SALT = UINT64_C(0x70E3C0DE5EED0007);

void carveTowersImpl(CarveCtx& c) {
    if (!profileCarve(c.g) || c.drop != 0) return;
    Generator& g = c.g;
    const double cell = blocksOf(g, TOWER_CELL_M, TOWER_CELL_MIN);
    SiteGrid sites(g.cfg.seed, TOWER_SALT, cell, c.X0, c.Z0, c.X0 + CE, c.Z0 + CE);
    CodeReader codes(g);
    bool any = false;
    for (Site& s : sites.s) {
        s.style = (codes.at(static_cast<int64_t>(std::floor(s.x)), static_cast<int64_t>(std::floor(s.z))) >> 4) & 7;
        any |= s.style == SBW_CARVE_TOWERS;
    }
    if (!any) return;
    const double lane = blocksOf(g, TOWER_LANE_M, TOWER_LANE_MIN);
    const double under = blocksOf(g, TOWER_UNDERCUT_M, TOWER_UNDERCUT_MIN), crown = blocksOf(g, TOWER_CROWN_M, TOWER_CROWN_MIN);
    const float nf = static_cast<float>(1.0 / (3.0 * cell));
    const int64_t floorMin = g.cfg.minY + 2 + c.drop;
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            const int idx = colIdx(lx, lz);
            const int64_t th = c.th[idx];
            if (th <= g.cfg.floorY() + 1) continue;
            const int64_t wx = c.X0 + lx, wz = c.Z0 + lz;
            const double px = static_cast<double>(wx) + 0.5, pz = static_cast<double>(wz) + 0.5;
            const Site *a, *b;
            double da2, db2;
            sites.nearest2(px, pz, a, da2, b, db2);
            if (a->style != SBW_CARVE_TOWERS) continue;
            /* inset: how far inside its tower's wall the column stands (< 0: corridor or pit) */
            const double rA = cell * lerpf(static_cast<float>(TOWER_RADIUS_LO), static_cast<float>(TOWER_RADIUS_HI),
                                           static_cast<float>(hashU(a->h, 1)));
            const bool pit = hashU(a->h, 0) < TOWER_PIT_RATE;
            const bool undercut = hashU(a->h, 2) < TOWER_UNDERCUT_RATE;
            const double inset = pit ? -1.0 : std::min(rA - std::sqrt(da2), borderDistance(*a, da2, *b, db2) - lane);
            if (inset >= std::max(undercut ? under : 0.0, crown)) continue;
            if (c.water(wx, wz) != NO_WATER) continue;
            const double n01 = 0.5 + 0.5 * static_cast<double>(g.sinkRim.noise3D(static_cast<float>(wx) * nf, 1536.0f,
                                                                                  static_cast<float>(wz) * nf));
            const int dclass = (codes.at(wx, wz) >> 11) & 31;
            const double depthMul = 0.6 + 0.8 * static_cast<double>(dclass) / 31.0;
            const int64_t depth = std::max<int64_t>(4, javaRoundFloat(static_cast<float>((TOWER_DEPTH_MIN + TOWER_DEPTH_SPAN * n01) * depthMul)));
            const int64_t floor = std::max(th - depth, floorMin);
            for (int64_t by = floor; by < th; by++) {
                double need = 0.0;   /* the rock a cell needs around it to stay */
                if (undercut && by < floor + TOWER_UNDERCUT_H) need = under;
                if (by >= th - 2) need = std::max(need, crown * static_cast<double>(by - (th - 3)) / 2.0);
                if (inset >= need) continue;
                if (!c.yOk(by)) continue;
                if (guardSeals(c.guard, idx, by, TOWER_WATER_CLEARANCE)) continue;
                c.set(lx, by, lz);
            }
        }
    }
}

} // namespace

uint16_t carveCodeAt(Generator& g, int64_t x, int64_t z) { return carveCodeAtImpl(g, x, z); }

/* ---------------------------------------------------------------------------- public parts */
int32_t waterTableResolve(const Generator& g, int32_t surface, int32_t waterLevel, float wobble) {
    int64_t table;
    if (waterLevel != NO_WATER) {
        table = waterLevel;
    } else {
        table = javaRoundFloat(static_cast<float>(g.cfg.sea) + static_cast<float>(surface - g.cfg.sea) * WT_DAMP
                               + wobble * WT_WOBBLE);
    }
    const int64_t floor = g.cfg.minY + 9;
    if (table < floor) table = floor;
    const int64_t roof = static_cast<int64_t>(surface) - WT_MIN_ROOF;
    return static_cast<int32_t>(table < roof ? table : roof);
}

Zone waterTableZone(int32_t table, int64_t y) {
    if (y > static_cast<int64_t>(table) + WT_BAND) return Zone::VADOSE;
    for (int k = 0; k < WT_STOREYS; k++) {
        const int64_t storey = static_cast<int64_t>(table) - k * WT_LEVEL_SPACING;
        if (std::llabs(y - storey) <= WT_BAND) return Zone::EPIPHREATIC;
    }
    return Zone::PHREATIC;
}

/* 1 - d / WT_BAND for d < WT_BAND (the only distances that can beat 0), computed with the same float ops */
const std::array<float, 5> GALLERY_WEIGHT = [] {
    static_assert(WT_BAND == 5, "table size");
    std::array<float, 5> t{};
    for (int d = 0; d < WT_BAND; d++) t[static_cast<size_t>(d)] = 1.0f - static_cast<float>(d) / static_cast<float>(WT_BAND);
    return t;
}();

float waterTableGallery(int32_t table, int64_t y) {
    float best = 0.0f;
    for (int k = 0; k < WT_STOREYS; k++) {
        const int64_t storey = static_cast<int64_t>(table) - k * WT_LEVEL_SPACING;
        const int64_t d = std::llabs(y - storey);
        if (d >= WT_BAND) continue; /* weight <= 0: never above best */
        const float w = GALLERY_WEIGHT[static_cast<size_t>(d)];
        if (w > best) best = w;
    }
    return best;
}

void waterGuardPlane(Generator& g, int64_t X0, int64_t Z0, const int32_t* th, const int32_t* water,
                     int32_t* outPlane) {
    auto consider = [&](int32_t guard, int x, int z) -> int32_t {
        int32_t h, w;
        if (x >= 0 && x < CE && z >= 0 && z < CE) {
            h = th[colIdx(x, z)];
            w = water[colIdx(x, z)];
        } else {
            w = waterOracle(g, X0 + x, Z0 + z);
            if (w == NO_WATER) return guard;
            h = heightOracle(g, X0 + x, Z0 + z);
        }
        if (w != NO_WATER) return guard < h ? guard : h;
        return guard;
    };
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            int32_t guard = GUARD_OPEN;
            guard = consider(guard, lx, lz);
            guard = consider(guard, lx - 1, lz);
            guard = consider(guard, lx + 1, lz);
            guard = consider(guard, lx, lz - 1);
            guard = consider(guard, lx, lz + 1);
            outPlane[colIdx(lx, lz)] = guard;
        }
    }
}

void carveWorms(CarveCtx& c) {
    const Window w = window(c.X0, c.Z0, SCAN_RADIUS);
    for (int64_t sxc = w.c0; sxc <= w.c1; sxc++) {
        for (int64_t szc = w.d0; szc <= w.d1; szc++) {
            if (!hasWorm(c.seeds.seed, sxc, szc)) continue;
            WormWalk walk;
            walk.ox = sxc * CHUNK;
            walk.oz = szc * CHUNK;
            spawnWorms(c, sxc, szc, walk);
            rasterWorms(c, walk);
        }
    }
}

SourceWindow wormWindow(int64_t X0, int64_t Z0) {
    const Window w = window(X0, Z0, SCAN_RADIUS);
    return {w.c0, w.c1, w.d0, w.d1};
}

SourceWindow ravineWindow(int64_t X0, int64_t Z0) {
    static const int scan = ravineScanRadius();
    const Window w = window(X0, Z0, scan);
    return {w.c0, w.c1, w.d0, w.d1};
}

bool wormSourceAt(int64_t seed, int64_t sx, int64_t sz) { return hasWorm(seed, sx, sz); }
bool ravineSourceAt(Generator& g, int64_t seed, int64_t sx, int64_t sz) {
    if (!profileCarve(g)) return hasRavine(seed, sx, sz);
    const uint16_t code = sourceCode(g, sx, sz);
    const int density = code & 15, style = (code >> 4) & 7;
    if (density == 0 || style == SBW_CARVE_KARST || style == SBW_CARVE_NONE || style == SBW_CARVE_SEA_STACKS
        || style == SBW_CARVE_TOWERS) {
        return false;
    }
    return chunkUniform(seed, sx, sz, UINT64_C(0x7A11E5C0DE5EED01)) < PROFILE_RAVINE_RATE * density / 15.0;
}

std::shared_ptr<WormWalk> walkWormSystem(CarveCtx& c, int64_t sx, int64_t sz) {
    auto w = std::make_shared<WormWalk>();
    w->ox = sx * CHUNK;
    w->oz = sz * CHUNK;
    if (hasWorm(c.seeds.seed, sx, sz)) spawnWorms(c, sx, sz, *w);
    w->e.shrink_to_fit();
    return w;
}

std::shared_ptr<RavineWalk> walkRavine(CarveCtx& c, int64_t sx, int64_t sz) {
    auto w = std::make_shared<RavineWalk>();
    if (ravineSourceAt(c.g, c.seeds.seed, sx, sz)) carveOneRavine(c, sx, sz, *w);
    w->s.shrink_to_fit();
    return w;
}

void rasterWorms(CarveCtx& c, const WormWalk& w) {
    if (w.e.empty() || w.maxX < c.X0 || w.minX >= c.X0 + CE || w.maxZ < c.Z0 || w.minZ >= c.Z0 + CE) return;
    for (const WormEllipsoid& e : w.e) carveWormEllipsoid(c, w.ox + e.dx, e.y, w.oz + e.dz, e.r);
}

void rasterRavine(CarveCtx& c, const RavineWalk& w) {
    for (const RavineSlice& s : w.s) {
        carveSlice(c, s.wx, s.wz, s.halfWidth, s.flare, s.cutDepth, s.sinDip, s.shear, s.nx, s.nz);
    }
}

void carveCaverns(CarveCtx& c, bool mega) {
    const CavernParams& p = mega ? MEGA : CAVERN;
    const Window w = window(c.X0, c.Z0, p.scanRadius);
    for (int64_t sxc = w.c0; sxc <= w.c1; sxc++) {
        for (int64_t szc = w.d0; szc <= w.d1; szc++) {
            if (hasCavern(p, c.seeds.seed, sxc, szc)) carveOneCavern(c, p, sxc, szc);
        }
    }
}

void carveRavines(CarveCtx& c) {
    static const int scan = ravineScanRadius();
    const Window w = window(c.X0, c.Z0, scan);
    for (int64_t sxc = w.c0; sxc <= w.c1; sxc++) {
        for (int64_t szc = w.d0; szc <= w.d1; szc++) {
            if (!ravineSourceAt(c.g, c.seeds.seed, sxc, szc)) continue;
            RavineWalk walk;
            carveOneRavine(c, sxc, szc, walk);
            rasterRavine(c, walk);
        }
    }
}

void carveSeaStacks(CarveCtx& c) { carveSeaStacksImpl(c); }
void carveTowers(CarveCtx& c) { carveTowersImpl(c); }

void carveSinkholes(CarveCtx& c) {
    const Window w = window(c.X0, c.Z0, SINK_SCAN);
    for (int64_t sxc = w.c0; sxc <= w.c1; sxc++) {
        for (int64_t szc = w.d0; szc <= w.d1; szc++) {
            if (sinkholeSourceAt(c.g, c.seeds.seed, sxc, szc)) carveOneSinkhole(c, sxc, szc);
        }
    }
}

void buildFormations(Generator& g, int64_t layerSeed, bool mega, int64_t X0, int64_t Z0, const MaskSet& carve,
                     MaskSet& formations) {
    (void) g;
    if (carve.empty()) return;
    const CavernParams& p = mega ? MEGA : CAVERN;
    int64_t lo, hi;
    carve.yRange(lo, hi);
    for (int lz = 0; lz < CE; lz++) {
        for (int lx = 0; lx < CE; lx++) {
            int64_t floorY = -1, ceilY = -1, runStart = INT64_MIN;
            bool have = false;
            for (int64_t by = lo; by <= hi; by++) {
                if (by < hi && carve.test(lx, by, lz)) {
                    if (runStart == INT64_MIN) runStart = by;
                    continue;
                }
                if (runStart != INT64_MIN) {
                    const int64_t len = by - 1 - runStart;
                    if (len > (have ? ceilY - floorY : 0)) {
                        floorY = runStart;
                        ceilY = by - 1;
                        have = true;
                    }
                    runStart = INT64_MIN;
                }
            }
            if (!have) continue;
            const int64_t gap = ceilY - floorY;
            JavaRandom rng(formationSeed(p, layerSeed, X0 + lx, Z0 + lz));
            int stalagH = rng.nextFloat() < p.stalagmiteChance ? 1 + rng.nextInt(p.formationMaxHeight) : 0;
            int stalactiteH = rng.nextFloat() < p.stalactiteChance ? 1 + rng.nextInt(p.formationMaxHeight) : 0;
            const int total = stalagH + stalactiteH;
            if (total > gap && total > 0) {
                stalagH = static_cast<int>(static_cast<int64_t>(stalagH) * gap / total);
                stalactiteH = static_cast<int>(static_cast<int64_t>(stalactiteH) * gap / total);
            }
            for (int h = 0; h < stalagH; h++) {
                const int64_t by = floorY + h;
                if (by > ceilY) break;
                formations.set(lx, by, lz);
            }
            for (int h = 0; h < stalactiteH; h++) {
                const int64_t by = ceilY - h;
                if (by < floorY) break;
                formations.set(lx, by, lz);
            }
        }
    }
}

bool cavernOrigin(Generator& g, int64_t seed, int32_t drop, bool mega, int64_t cx16, int64_t cz16, float out[3]) {
    const CavernParams& p = mega ? MEGA : CAVERN;
    if (!hasCavern(p, seed, cx16, cz16)) return false;
    /* exactly carveOneCavern's origin in the layer's frame (CarveCtx::yFloor), then frame -> world y */
    cavernOriginOf(p, g, seed, static_cast<int32_t>(g.cfg.minY) + 6 + drop, cx16, cz16, out);
    out[1] -= static_cast<float>(drop);
    return true;
}

bool nearestWormOrigin(Generator& g, const LayerSeeds& s, int64_t cx16, int64_t cz16, int radius, float out[3]) {
    int64_t best = INT64_MAX, bx = 0, bz = 0;
    bool found = false;
    for (int dcx = -radius; dcx <= radius; dcx++) {
        for (int dcz = -radius; dcz <= radius; dcz++) {
            if (!hasWorm(s.seed, cx16 + dcx, cz16 + dcz)) continue;
            const int64_t d = dcx * dcx + dcz * dcz;
            if (d < best) {
                best = d;
                bx = cx16 + dcx;
                bz = cz16 + dcz;
                found = true;
            }
        }
    }
    if (!found) return false;
    CarveCtx c(g, s);
    computeOrigin(c, bx, bz, out);
    return true;
}

} // namespace sbw
