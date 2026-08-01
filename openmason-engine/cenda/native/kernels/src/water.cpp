/* ck_carve_water — noise-derived rivers and lakes at block resolution.
 *
 * Replaces the terrain-bridge's hydrological L0/L1 solve for inland water. The
 * old pipeline was physically faithful (global drainage, basin fill) but paid
 * for it in GPU elevation generation over ~150 Mpx windows (~90 s per cold
 * macro-region). This kernel derives water from data the game already holds —
 * one tile of block heights plus a one-tile halo — in a few milliseconds.
 *
 * ═══ Canonicality (the seam rule) ═══
 *
 * Every emitted value must be a pure function of (seed, column) so that any
 * tile whose window covers a column computes the identical value. Three
 * mechanisms, one per feature:
 *
 *   rivers   per-column noise (absolute world coords) + a fixed-radius blur of
 *            raw heights. Blur reach (2 * SURFACE_BLUR_R) is far below the
 *            one-tile halo, so no center-tile column ever sees window edge
 *            truncation.
 *
 *   lakes    NOT a global depression fill — a fill's level depends on the
 *            window when basins nest (the old design needed its continental L0
 *            layer precisely for this). Instead: candidate points on a seeded,
 *            jittered world lattice; each candidate floods a bounded box
 *            around itself on carved heights and is accepted or rejected from
 *            that box alone. Acceptance is independent of other lakes
 *            (overlaps merge by max), so there are no order chains, and every
 *            tile owning any column of a lake also sees the candidate's whole
 *            box. Identical from every window by construction.
 *
 *   repair   the containment pass reads only the water-level plane in a
 *            1-block ring, which both neighboring tiles compute identically.
 *
 * ═══ Containment (the WaterSim invariant) ═══
 *
 * Worldgen water is source blocks: a wet column with a lower dry 4-neighbor is
 * a permanent spring that floods every chunk it touches. Rule held here (same
 * as the bridge's carve.py): for every wet column at level W, each 4-neighbor
 * is wet itself or has terrain >= W. Wet-next-to-wet at different levels is a
 * waterfall and is deliberately allowed. Lakes satisfy it by construction
 * (flood stops at cells >= L), rivers/sea via the final repair pass.
 *
 * Determinism: integer/hash math plus FastNoise2 floats — same library, same
 * inputs, same outputs. Not required to match any Java reference (there is no
 * Java implementation of this path; absent lib = sea-level-only fallback).
 */

#include "cenda/kernels.h"
#include "nodes.hpp"

#include <FastNoise/FastNoise.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace {

/* ── Tunables (overridable via the params array; see kernels.h) ── */
constexpr float DEF_CHANNEL_THRESHOLD = 0.045f; /* |C| below this = channel  */
constexpr float DEF_RIVER_DEPTH_SCALE = 3.0f;   /* extra centerline depth    */
constexpr float DEF_SURFACE_BLUR_R = 24.0f;     /* box blur radius, blocks   */
constexpr float DEF_LAKE_SPACING = 160.0f;      /* candidate lattice pitch   */
constexpr float DEF_LAKE_KEEP_FRACTION = 0.35f; /* candidates that try       */
constexpr float DEF_LAKE_MAX_RADIUS = 72.0f;    /* bounded-flood box radius  */
constexpr float DEF_ALT_FADE_START = 25.0f;     /* blocks above sea          */
constexpr float DEF_ALT_FADE_END = 70.0f;
/* Mountain behavior. A per-column blurred surface is LEVEL on plains but
 * TILTED on a mountainside, and a channel crossing a slope then gets water
 * stepping sideways across its own width — the "screwy in mountains" failure.
 * Three knobs fix it: the water surface is ERODED (windowed min) so a
 * channel's whole cross-section takes the downhill-bank level and rivers hug
 * valley floors; channels FADE with the local gradient of the smoothed
 * surface so they occupy valleys rather than hillsides (what real drainage
 * does); and a channel meeting ground more than MAX_INCISION above its own
 * surface stops instead of slotting a canyon through the spur (ground that
 * high is contained by definition, so a gap in the channel is safe). */
constexpr float DEF_SLOPE_FADE_START = 0.35f;   /* blocks per block          */
constexpr float DEF_SLOPE_FADE_END = 0.80f;
constexpr float DEF_MAX_INCISION = 12.0f;       /* blocks above surface      */
constexpr float DEF_ERODE_RADIUS = 16.0f;       /* min-filter radius, blocks */

/* Channel field: base meander + wiggle detail; rivers live on its zero
 * isolines. Frequencies in blocks^-1. Base sets river spacing (~1.4 km),
 * detail sets sinuosity. Distance to the isoline is estimated per column as
 * |C| / |grad C| so channel width is expressed in BLOCKS and controlled
 * directly (thresholding |C| alone made width a slave of the local noise
 * gradient — most reaches came out a few blocks wide). */
constexpr float CHANNEL_BASE_FREQ = 1.0f / 1400.0f;
constexpr float CHANNEL_DETAIL_FREQ = 1.0f / 230.0f;
constexpr float CHANNEL_DETAIL_AMP = 0.30f;
constexpr float WIDTH_NOISE_FREQ = 1.0f / 240.0f;
constexpr float CHANNEL_GRAD_EPS = 1e-3f;   /* saddle guard: bounds the wet band */
constexpr float RIVER_HALFWIDTH_MIN = 3.0f; /* blocks, before fades            */
constexpr float RIVER_HALFWIDTH_MAX = 24.0f;

constexpr int LAKE_MIN_AREA = 12;
constexpr int LAKE_TRY_DEPTHS[] = {5, 4, 3, 2}; /* deepest accepted wins */
constexpr int POND_MAX_RELIEF = 6;   /* skip excavating into slopes           */
constexpr int POND_MIN_RADIUS = 8;
constexpr int POND_RADIUS_SPAN = 11; /* radii 8..18 blocks                    */

constexpr double PI = 3.14159265358979323846;

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

inline int32_t noiseSeed(int64_t seed, uint64_t salt) {
    return static_cast<int32_t>(splitmix64(static_cast<uint64_t>(seed) ^ salt));
}

inline size_t idx2(int row, int col, int stride) {
    return static_cast<size_t>(row) * static_cast<size_t>(stride) + static_cast<size_t>(col);
}

/* Separable, edge-clamped box blur. Two passes ≈ triangular kernel; total
 * reach = 2 * radius, which must stay well under tile_size (asserted by the
 * caller-facing validation). */
void boxBlur(const std::vector<float>& src, std::vector<float>& dst,
             std::vector<float>& tmp, int w, int radius) {
    const float inv = 1.0f / static_cast<float>(2 * radius + 1);
    /* rows */
    for (int r = 0; r < w; ++r) {
        const float* row = src.data() + idx2(r, 0, w);
        float* out = tmp.data() + idx2(r, 0, w);
        float acc = 0.0f;
        for (int c = -radius; c <= radius; ++c) {
            acc += row[std::clamp(c, 0, w - 1)];
        }
        for (int c = 0; c < w; ++c) {
            out[c] = acc * inv;
            acc += row[std::min(c + radius + 1, w - 1)] - row[std::max(c - radius, 0)];
        }
    }
    /* columns */
    for (int c = 0; c < w; ++c) {
        float acc = 0.0f;
        for (int r = -radius; r <= radius; ++r) {
            acc += tmp[idx2(std::clamp(r, 0, w - 1), c, w)];
        }
        for (int r = 0; r < w; ++r) {
            dst[idx2(r, c, w)] = acc * inv;
            const int rn = std::min(r + radius + 1, w - 1);
            const int rp = std::max(r - radius, 0);
            acc += tmp[idx2(rn, c, w)] - tmp[idx2(rp, c, w)];
        }
    }
}

/* Separable, edge-truncated sliding-window MINIMUM (morphological erosion),
 * monotonic-deque per line: O(1) amortized per element at any radius. */
void slideMinLine(const float* line, float* out, int n, int radius,
                  std::vector<int>& dq) {
    dq.clear();
    size_t head = 0;
    for (int c = 0; c < n + radius; ++c) {
        if (c < n) {
            while (dq.size() > head && line[dq.back()] >= line[c]) {
                dq.pop_back();
            }
            dq.push_back(c);
        }
        const int center = c - radius;
        if (center >= 0 && center < n) {
            while (dq[head] < center - radius) {
                ++head;
            }
            out[center] = line[dq[head]];
        }
    }
}

void boxErode(const std::vector<float>& src, std::vector<float>& dst,
              std::vector<float>& tmp, int w, int radius,
              std::vector<int>& dq, std::vector<float>& line) {
    for (int r = 0; r < w; ++r) {
        slideMinLine(src.data() + idx2(r, 0, w), tmp.data() + idx2(r, 0, w), w, radius, dq);
    }
    line.resize(static_cast<size_t>(w) * 2);
    float* colIn = line.data();
    float* colOut = line.data() + w;
    for (int c = 0; c < w; ++c) {
        for (int r = 0; r < w; ++r) {
            colIn[r] = tmp[idx2(r, c, w)];
        }
        slideMinLine(colIn, colOut, w, radius, dq);
        for (int r = 0; r < w; ++r) {
            dst[idx2(r, c, w)] = colOut[r];
        }
    }
}

struct Params {
    float channelThreshold = DEF_CHANNEL_THRESHOLD;
    float riverDepthScale = DEF_RIVER_DEPTH_SCALE;
    int surfaceBlurR = static_cast<int>(DEF_SURFACE_BLUR_R);
    int lakeSpacing = static_cast<int>(DEF_LAKE_SPACING);
    float lakeKeepFraction = DEF_LAKE_KEEP_FRACTION;
    int lakeMaxRadius = static_cast<int>(DEF_LAKE_MAX_RADIUS);
    float altFadeStart = DEF_ALT_FADE_START;
    float altFadeEnd = DEF_ALT_FADE_END;
    float slopeFadeStart = DEF_SLOPE_FADE_START;
    float slopeFadeEnd = DEF_SLOPE_FADE_END;
    int maxIncision = static_cast<int>(DEF_MAX_INCISION);
    int erodeRadius = static_cast<int>(DEF_ERODE_RADIUS);
};

Params readParams(const float* params, int32_t n) {
    Params p;
    if (params == nullptr || n <= 0) {
        return p;
    }
    if (n > 0) p.channelThreshold = params[0];
    if (n > 1) p.riverDepthScale = params[1];
    if (n > 2) p.surfaceBlurR = static_cast<int>(params[2]);
    if (n > 3) p.lakeSpacing = static_cast<int>(params[3]);
    if (n > 4) p.lakeKeepFraction = params[4];
    if (n > 5) p.lakeMaxRadius = static_cast<int>(params[5]);
    if (n > 6) p.altFadeStart = params[6];
    if (n > 7) p.altFadeEnd = params[7];
    if (n > 8) p.slopeFadeStart = params[8];
    if (n > 9) p.slopeFadeEnd = params[9];
    if (n > 10) p.maxIncision = static_cast<int>(params[10]);
    if (n > 11) p.erodeRadius = static_cast<int>(params[11]);
    return p;
}

/* Per-thread scratch: ~7 window-sized float planes plus flood state. Reused
 * across calls on the same worker thread (generator.cpp pattern). */
struct Scratch {
    std::vector<float> base, detail, widthN, smooth, eroded, chan, tmpA, tmpB, line;
    std::vector<int> dq;
    std::vector<int16_t> carved;
    std::vector<int16_t> water;
    std::vector<int16_t> waterBase;
    std::vector<int16_t> exBed;
    std::vector<int32_t> floodStack;
    std::vector<uint8_t> floodMark;
    std::vector<int32_t> region;
};

thread_local Scratch tls;

} // namespace

extern "C" {

int32_t ck_carve_water(int64_t seed,
                       int32_t tile_size,
                       int32_t origin_x, int32_t origin_z,
                       const int16_t* heights3x3,
                       int32_t sea_level, int32_t world_height,
                       const float* params, int32_t n_params,
                       int16_t* out_heights, int16_t* out_water) {
    if (heights3x3 == nullptr || out_heights == nullptr || out_water == nullptr) {
        return -1;
    }
    if (tile_size < 64 || tile_size > 4096) {
        return -2;
    }
    if (world_height < 64 || sea_level < 1 || sea_level >= world_height) {
        return -3;
    }
    const Params p = readParams(params, n_params);
    const int T = tile_size;
    const int W = 3 * T;
    const size_t N = static_cast<size_t>(W) * static_cast<size_t>(W);
    const int lakeR = std::clamp(p.lakeMaxRadius, 8, T / 2 - 4);
    const int blurR = std::clamp(p.surfaceBlurR, 1, T / 4);
    if (p.lakeSpacing != 0 && p.lakeSpacing < 16) {
        return -4;
    }

    const int erodeR = std::clamp(p.erodeRadius, 0, T / 4);

    Scratch& s = tls;
    s.base.resize(N);
    s.detail.resize(N);
    s.widthN.resize(N);
    s.smooth.resize(N);
    s.eroded.resize(N);
    s.chan.resize(N);
    s.tmpA.resize(N);
    s.tmpB.resize(N);
    s.carved.resize(N);
    s.water.assign(N, -1);

    /* ── 1. Smoothed reference surface S, and its erosion ──
     * S (two box blurs) is the local terrain trend; it is what the slope fade
     * reads. The water surface itself is the EROSION (windowed min) of S: on
     * a mountainside S is tilted, and water assigned per column from a tilted
     * surface steps sideways across the channel's own width. The min filter
     * gives the whole cross-section the downhill-bank level, which is also
     * what pins rivers to valley floors. */
    for (size_t i = 0; i < N; ++i) {
        s.tmpB[i] = static_cast<float>(heights3x3[i]);
    }
    boxBlur(s.tmpB, s.smooth, s.tmpA, W, blurR);
    boxBlur(s.smooth, s.tmpB, s.tmpA, W, blurR);
    s.smooth.swap(s.tmpB);
    if (erodeR > 0) {
        boxErode(s.smooth, s.eroded, s.tmpA, W, erodeR, s.dq, s.line);
    } else {
        s.eroded = s.smooth;
    }

    /* ── 2. Noise planes ──
     * Heights are indexed [rowX * W + colZ]; FastNoise grids are x-fastest, so
     * the noise x axis maps to world Z and y to world X (index parity). */
    const bool rivers = p.channelThreshold > 0.0f;
    if (rivers) {
        auto baseNode = cenda::makeSimplexFbm(2, 2.0f, 0.5f, CHANNEL_BASE_FREQ);
        auto detailNode = cenda::makeSimplexFbm(2, 2.0f, 0.5f, CHANNEL_DETAIL_FREQ);
        auto widthNode = cenda::makeSimplexFbm(1, 2.0f, 0.5f, WIDTH_NOISE_FREQ);
        if (!baseNode || !detailNode || !widthNode) {
            return -5;
        }
        const auto fz = static_cast<float>(origin_z);
        const auto fx = static_cast<float>(origin_x);
        baseNode->GenUniformGrid2D(s.base.data(), fz, fx, W, W, 1.0f, 1.0f,
                                   noiseSeed(seed, 0x5245564152ULL));
        detailNode->GenUniformGrid2D(s.detail.data(), fz, fx, W, W, 1.0f, 1.0f,
                                     noiseSeed(seed, 0x574947474CULL));
        widthNode->GenUniformGrid2D(s.widthN.data(), fz, fx, W, W, 1.0f, 1.0f,
                                    noiseSeed(seed, 0x5749445448ULL));
        for (size_t i = 0; i < N; ++i) {
            s.chan[i] = s.base[i] + CHANNEL_DETAIL_AMP * s.detail[i];
        }
    }

    /* ── 3. Rivers: threshold the channel field, carve a cosine bed ── */
    for (int x = 0; x < W; ++x) {
        for (int z = 0; z < W; ++z) {
            const size_t i = idx2(x, z, W);
            const int raw = heights3x3[i];
            int carved = raw;
            int16_t water = -1;

            if (rivers) {
                /* Water surface from the eroded plane; fades from the trend
                 * plane. Rounding once here keeps the whole cross-section on
                 * one integer level wherever the erosion made it flat. */
                const int surf = static_cast<int>(std::lround(s.eroded[i]));
                /* Channels thin out with altitude so ridgelines are not
                 * gridded with gorges; never fully off, so highland brooks
                 * survive. */
                float altFade = 1.0f;
                const float above = s.smooth[i] - static_cast<float>(sea_level);
                if (above > p.altFadeStart && p.altFadeEnd > p.altFadeStart) {
                    altFade = 1.0f - (above - p.altFadeStart) / (p.altFadeEnd - p.altFadeStart);
                    altFade = std::clamp(altFade, 0.15f, 1.0f);
                }
                /* Channels avoid steep hillsides entirely — real drainage
                 * occupies valley floors, and a channel perched on a slope is
                 * where the tilted-water artifacts came from. Gradient of the
                 * TREND surface, central difference over +-2 blocks. */
                const int xn = std::max(x - 2, 0), xp = std::min(x + 2, W - 1);
                const int zn = std::max(z - 2, 0), zp = std::min(z + 2, W - 1);
                const float gx = (s.smooth[idx2(xp, z, W)] - s.smooth[idx2(xn, z, W)])
                    / static_cast<float>(xp - xn);
                const float gz = (s.smooth[idx2(x, zp, W)] - s.smooth[idx2(x, zn, W)])
                    / static_cast<float>(zp - zn);
                const float slope = std::sqrt(gx * gx + gz * gz);
                float slopeFade = 1.0f;
                if (slope > p.slopeFadeStart && p.slopeFadeEnd > p.slopeFadeStart) {
                    slopeFade = 1.0f - (slope - p.slopeFadeStart)
                        / (p.slopeFadeEnd - p.slopeFadeStart);
                    slopeFade = std::clamp(slopeFade, 0.0f, 1.0f);
                }

                /* Half-width in BLOCKS: base range from the width noise, then
                 * every fade multiplies the WIDTH rather than a noise
                 * threshold. That is what makes rivers originate like rivers:
                 * in the highlands the altitude fade pinches a channel down to
                 * a one-block brook and then to nothing (its source), and the
                 * same channel followed downhill widens toward the sea — a
                 * poor man's discharge accumulation. params[0] scales overall
                 * width/presence (its old thresholding role, re-expressed). */
                const float w01 = 0.5f * (s.widthN[i] + 1.0f);
                const float widthScale = p.channelThreshold / DEF_CHANNEL_THRESHOLD;
                const float halfwidth = (RIVER_HALFWIDTH_MIN
                        + (RIVER_HALFWIDTH_MAX - RIVER_HALFWIDTH_MIN) * w01 * w01)
                    * altFade * slopeFade * widthScale;
                if (halfwidth >= 1.0f) {
                    /* Distance to the channel centerline (the C = 0 isoline),
                     * estimated as |C| / |grad C|. The gradient floor bounds
                     * the wet band where C flattens near saddles. */
                    const float c = s.chan[i];
                    const float cgx = (s.chan[idx2(std::min(x + 1, W - 1), z, W)]
                        - s.chan[idx2(std::max(x - 1, 0), z, W)]) * 0.5f;
                    const float cgz = (s.chan[idx2(x, std::min(z + 1, W - 1), W)]
                        - s.chan[idx2(x, std::max(z - 1, 0), W)]) * 0.5f;
                    const float grad = std::max(std::sqrt(cgx * cgx + cgz * cgz),
                                                CHANNEL_GRAD_EPS);
                    const float d = (std::abs(c) / grad) / halfwidth;
                    /* raw >= surf-2: a channel crossing ground far below its
                     * own surface is a dry gully, not an aqueduct (the repair
                     * pass walls the wet columns beside it). raw - surf <=
                     * maxIncision: ground far ABOVE the surface is a spur the
                     * river stops at rather than slotting a canyon through —
                     * that ground already stands above the water level, so
                     * the gap is contained by construction. */
                    if (d < 1.0f && raw >= surf - 2 && raw - surf <= p.maxIncision) {
                        const float falloff = 0.5f
                            * (1.0f + static_cast<float>(std::cos(PI * static_cast<double>(d))));
                        const int maxDepth = 1 + static_cast<int>(std::lround(
                            p.riverDepthScale * halfwidth / RIVER_HALFWIDTH_MAX));
                        const int cut = std::max(1,
                            static_cast<int>(std::lround(static_cast<float>(maxDepth) * falloff)));
                        carved = std::min(raw, surf - cut);
                        water = static_cast<int16_t>(surf);
                    }
                }
            }

            carved = std::clamp(carved, 1, world_height - 1);
            /* The sea is one case of the per-column water level (bridge rule). */
            if (carved < sea_level) {
                water = static_cast<int16_t>(std::max<int>(water, sea_level));
            }
            s.carved[i] = static_cast<int16_t>(carved);
            s.water[i] = water;
        }
    }

    /* ── 4. Lakes: canonical jittered lattice + bounded local flood ── */
    if (p.lakeSpacing > 0) {
        /* Acceptance must be independent of OTHER lakes or candidate-order
         * chains would leak across the window edge and break the seam rule.
         * Rejection therefore tests the immutable sea/river plane, never the
         * accumulating one; accepted lakes merge into s.water by max, which
         * is order-independent. */
        s.waterBase = s.water;
        /* Excavated-pond bed deltas are DEFERRED: every candidate must read
         * the pristine river-carved terrain, or excavation by one candidate
         * would change another's flood — an order chain, and order chains are
         * how seams come back. min-merged and applied after all candidates. */
        s.exBed.assign(N, INT16_MAX);
        bool anyExcavated = false;
        const int spacing = p.lakeSpacing;
        /* Lattice cells whose jittered point could influence a center column:
         * center tile expanded by the flood box radius. */
        const int64_t loX = static_cast<int64_t>(origin_x) + T - lakeR;
        const int64_t hiX = static_cast<int64_t>(origin_x) + 2 * T + lakeR;
        const int64_t loZ = static_cast<int64_t>(origin_z) + T - lakeR;
        const int64_t hiZ = static_cast<int64_t>(origin_z) + 2 * T + lakeR;
        const int64_t c0x = static_cast<int64_t>(std::floor(static_cast<double>(loX) / spacing));
        const int64_t c1x = static_cast<int64_t>(std::floor(static_cast<double>(hiX) / spacing));
        const int64_t c0z = static_cast<int64_t>(std::floor(static_cast<double>(loZ) / spacing));
        const int64_t c1z = static_cast<int64_t>(std::floor(static_cast<double>(hiZ) / spacing));

        s.floodMark.assign(N, 0);
        for (int64_t cx = c0x; cx <= c1x; ++cx) {
            for (int64_t cz = c0z; cz <= c1z; ++cz) {
                const uint64_t h = hashCell(seed, cx, cz, 0x4C414B45ULL);
                const auto keepRoll =
                    static_cast<float>(h >> 40) / static_cast<float>(1 << 24);
                if (keepRoll >= p.lakeKeepFraction) {
                    continue;
                }
                /* Jitter within the middle of the cell so the flood box of a
                 * considered point always fits the window. */
                const int64_t px = cx * spacing + spacing / 4 +
                    static_cast<int64_t>((h >> 8) % static_cast<uint64_t>(std::max(1, spacing / 2)));
                const int64_t pz = cz * spacing + spacing / 4 +
                    static_cast<int64_t>((h >> 20) % static_cast<uint64_t>(std::max(1, spacing / 2)));
                const int wx = static_cast<int>(px - origin_x);
                const int wz = static_cast<int>(pz - origin_z);
                if (wx < lakeR + 1 || wx >= W - lakeR - 1 || wz < lakeR + 1 || wz >= W - lakeR - 1) {
                    continue; /* box would leave the window; also outside influence range */
                }
                /* Local floor: the lowest ground near the seed anchors the lake. */
                int floor0 = world_height;
                for (int dx = -4; dx <= 4; ++dx) {
                    for (int dz = -4; dz <= 4; ++dz) {
                        floor0 = std::min<int>(floor0, s.carved[idx2(wx + dx, wz + dz, W)]);
                    }
                }
                if (floor0 <= sea_level) {
                    continue; /* coastal/sea ground: the ocean owns it */
                }

                bool accepted = false;
                for (int depth : LAKE_TRY_DEPTHS) {
                    const int level = floor0 + depth;
                    if (level >= world_height) {
                        continue;
                    }
                    /* Bounded flood: cells with carved < level, 4-connected,
                     * confined to the box. Reject if it touches the box edge
                     * (unbounded basin at this level), overlaps sea/river
                     * water, or comes out too small. */
                    s.floodStack.clear();
                    s.region.clear();
                    bool ok = true;
                    const int seedIdxX = wx;
                    const int seedIdxZ = wz;
                    const auto seedIdx = static_cast<int32_t>(idx2(seedIdxX, seedIdxZ, W));
                    if (s.carved[static_cast<size_t>(seedIdx)] >= level) {
                        continue;
                    }
                    s.floodStack.push_back(seedIdx);
                    s.floodMark[static_cast<size_t>(seedIdx)] = 1;
                    s.region.push_back(seedIdx);
                    while (!s.floodStack.empty() && ok) {
                        const int32_t cur = s.floodStack.back();
                        s.floodStack.pop_back();
                        const int cx2 = cur / W;
                        const int cz2 = cur % W;
                        if (std::abs(cx2 - seedIdxX) >= lakeR || std::abs(cz2 - seedIdxZ) >= lakeR) {
                            ok = false;
                            break;
                        }
                        if (s.waterBase[static_cast<size_t>(cur)] >= 0) {
                            ok = false; /* runs into sea or a river */
                            break;
                        }
                        const int32_t nb[4] = {cur - W, cur + W, cur - 1, cur + 1};
                        for (int32_t nIdx : nb) {
                            if (s.floodMark[static_cast<size_t>(nIdx)]) {
                                continue;
                            }
                            if (s.carved[static_cast<size_t>(nIdx)] < level) {
                                s.floodMark[static_cast<size_t>(nIdx)] = 1;
                                s.floodStack.push_back(nIdx);
                                s.region.push_back(nIdx);
                            }
                        }
                    }
                    /* Clear marks for reuse regardless of outcome. */
                    for (int32_t idx : s.region) {
                        s.floodMark[static_cast<size_t>(idx)] = 0;
                    }
                    if (!ok || s.region.size() < static_cast<size_t>(LAKE_MIN_AREA)) {
                        continue;
                    }
                    /* Accepted: overlapping lakes merge by max, which keeps
                     * per-column water independent of candidate order. */
                    for (int32_t idx : s.region) {
                        s.water[static_cast<size_t>(idx)] = static_cast<int16_t>(
                            std::max<int>(s.water[static_cast<size_t>(idx)], level));
                    }
                    accepted = true;
                    break; /* deepest accepted depth wins */
                }

                /* ── Excavated pond fallback ──
                 * Natural fill needs an existing depression, and smooth
                 * terrain barely has any — a lattice of candidates that only
                 * ever fills would leave the world nearly lakeless. When the
                 * flood found nothing, dig a shallow elliptical pond instead,
                 * vanilla-lake style, on ground flat enough to hold it. Level
                 * = the MINIMUM of pristine terrain over footprint AND rim, so
                 * every rim column sits at or above the water by construction. */
                if (!accepted) {
                    const int rx2 = POND_MIN_RADIUS
                        + static_cast<int>((h >> 28) % POND_RADIUS_SPAN);
                    const int rz2 = POND_MIN_RADIUS
                        + static_cast<int>((h >> 36) % POND_RADIUS_SPAN);
                    const float invRx = 1.0f / static_cast<float>(rx2);
                    const float invRz = 1.0f / static_cast<float>(rz2);
                    int minC = world_height, maxC = 0;
                    bool blocked = false;
                    for (int dx = -rx2 - 1; dx <= rx2 + 1 && !blocked; ++dx) {
                        for (int dz = -rz2 - 1; dz <= rz2 + 1; ++dz) {
                            const float fdx = static_cast<float>(dx) * invRx;
                            const float fdz = static_cast<float>(dz) * invRz;
                            const float e = fdx * fdx + fdz * fdz;
                            if (e >= 1.7f) {
                                continue;
                            }
                            const size_t ci = idx2(wx + dx, wz + dz, W);
                            minC = std::min<int>(minC, s.carved[ci]);
                            maxC = std::max<int>(maxC, s.carved[ci]);
                            if (e < 1.0f && s.waterBase[ci] >= 0) {
                                blocked = true; /* footprint meets sea/river */
                                break;
                            }
                        }
                    }
                    const int level = minC;
                    if (blocked || maxC - minC > POND_MAX_RELIEF
                            || level <= sea_level || level >= world_height) {
                        continue;
                    }
                    const int pondDepth = 2 + static_cast<int>((h >> 44) % 3); /* 2..4 */
                    for (int dx = -rx2; dx <= rx2; ++dx) {
                        for (int dz = -rz2; dz <= rz2; ++dz) {
                            const float fdx = static_cast<float>(dx) * invRx;
                            const float fdz = static_cast<float>(dz) * invRz;
                            const float e = fdx * fdx + fdz * fdz;
                            if (e >= 1.0f) {
                                continue;
                            }
                            const size_t ci = idx2(wx + dx, wz + dz, W);
                            const int bed = level - 1 - static_cast<int>(
                                std::lround(static_cast<float>(pondDepth - 1) * (1.0f - e)));
                            s.exBed[ci] = static_cast<int16_t>(
                                std::min<int>(s.exBed[ci], std::max(bed, 1)));
                            s.water[ci] = static_cast<int16_t>(
                                std::max<int>(s.water[ci], level));
                        }
                    }
                    anyExcavated = true;
                }
            }
        }
        if (anyExcavated) {
            for (size_t i = 0; i < N; ++i) {
                if (s.exBed[i] < s.carved[i]) {
                    s.carved[i] = s.exBed[i];
                }
            }
        }
    }

    /* ── 5. Containment repair + emission (center tile only) ── */
    for (int x = 0; x < T; ++x) {
        for (int z = 0; z < T; ++z) {
            const size_t wi = idx2(x + T, z + T, W);
            int h = s.carved[wi];
            const int16_t w = s.water[wi];
            if (w < 0) {
                /* Dry ground beside water must wall it (raise, never wet —
                 * extending water would need re-checking ITS neighbors). */
                int need = -1;
                need = std::max<int>(need, s.water[wi - static_cast<size_t>(W)]);
                need = std::max<int>(need, s.water[wi + static_cast<size_t>(W)]);
                need = std::max<int>(need, s.water[wi - 1]);
                need = std::max<int>(need, s.water[wi + 1]);
                if (h < need) {
                    h = std::min(need, world_height - 1);
                }
            }
            const size_t oi = idx2(x, z, T);
            out_heights[oi] = static_cast<int16_t>(h);
            out_water[oi] = w;
        }
    }
    return 0;
}

} // extern "C"
