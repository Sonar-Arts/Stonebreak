/* The terrain block decision (TerrainGenerationSystem.determineBlockType + the mask precedence of the
 * block fill + DiffusionTerrainGenerator's river voids) and Density3D's solidity test, shared by the
 * cube fill (batched density) and point queries (single-point density) so both agree by construction. */
#pragma once

#include "carvers.hpp"
#include "gen.hpp"

#include <cmath>

namespace sbw {

/* Density3D constants */
constexpr int D_OVERHANG_DEPTH = 16;
constexpr int D_WATER_CLEARANCE = 1;
constexpr int D_PEAK_DAMP_START = 100;
constexpr int D_PEAK_DAMP_END = 145;
constexpr float D_PEAK_DAMP_FLOOR = 0.55f;
constexpr float D_SPAG_THICKNESS = 0.085f;
constexpr float D_SPAG_GALLERY_BONUS = 0.070f;
constexpr int D_SPAG_FADE_START = 10;
constexpr int D_SPAG_FADE_END = 34;
constexpr int D_CHEESE = 0, D_SPAG1 = 1, D_SPAG2 = 2, D_CRAG = 3;

/* BiomeSurfaceConfig.overhangIntensity */
inline float overhangIntensity(int biome) {
    switch (biome) {
        case SBW_PLAINS: return 0.10f;
        case SBW_RED_SAND_DESERT: return 0.20f;
        case SBW_SNOWY_PLAINS: return 0.08f;
        case SBW_TUNDRA: return 0.18f;
        case SBW_TAIGA: return 0.15f;
        case SBW_STONY_PEAKS: return 0.35f;
        case SBW_BADLANDS: return 0.32f;
        case SBW_MEADOW: return 0.10f;
        default: return 0.0f; /* desert, beach, ice fields, ocean, swamp */
    }
}

inline float peakDamp(int32_t h) {
    if (h <= D_PEAK_DAMP_START) return 1.0f;
    if (h >= D_PEAK_DAMP_END) return D_PEAK_DAMP_FLOOR;
    const float t = static_cast<float>(h - D_PEAK_DAMP_START) / static_cast<float>(D_PEAK_DAMP_END - D_PEAK_DAMP_START);
    return 1.0f - (1.0f - D_PEAK_DAMP_FLOOR) * t;
}

inline float spaghettiFadeRaw(int64_t depth) {
    if (depth <= D_SPAG_FADE_START) return 0.0f;
    if (depth >= D_SPAG_FADE_END) return 1.0f;
    return static_cast<float>(depth - D_SPAG_FADE_START) / static_cast<float>(D_SPAG_FADE_END - D_SPAG_FADE_START);
}
/* the same values, tabulated over the fade */
struct SpaghettiFadeTable {
    float v[D_SPAG_FADE_END + 1];
    SpaghettiFadeTable() {
        for (int d = 0; d <= D_SPAG_FADE_END; d++) v[d] = spaghettiFadeRaw(d);
    }
};
inline const SpaghettiFadeTable SPAGHETTI_FADE{};
inline float spaghettiFade(int64_t depth) {
    if (depth <= D_SPAG_FADE_START) return 0.0f;
    if (depth >= D_SPAG_FADE_END) return 1.0f;
    return SPAGHETTI_FADE.v[depth];
}

template <typename D>
bool Generator::densitySolid(D&& dens, int64_t y, int32_t h, int biome, int32_t refH, int32_t wtable,
                             int32_t guard) const {
    if (y < caveFloor() || y >= h) return true;
    const int64_t depth = static_cast<int64_t>(h) - y;
    if (depth <= D_OVERHANG_DEPTH) {
        float intensity = overhangIntensity(biome);
        if ((cfg.flags & SBW_FLAG_PEAK_DAMP) != 0) intensity *= peakDamp(refH);
        if (intensity > 0.0f) {
            const float n = densityNeedsCrag(biome) ? dens(D_CRAG) : dens(D_CHEESE);
            const bool bandSolid = n < (1.0f - 2.0f * intensity);
            const bool sealed = guard != GUARD_OPEN && y >= static_cast<int64_t>(guard) - D_WATER_CLEARANCE;
            if (!bandSolid && !sealed) return false;
        }
    }
    if (static_cast<double>(dens(D_CHEESE)) > cheeseThresholdAt(depth)) {
        return false;
    }
    const float fade = spaghettiFade(depth);
    /* fade 0: thickness (finite > 0) * 0 = +0, the gallery weight cannot matter */
    const float thickness = fade == 0.0f ? 0.0f : (D_SPAG_THICKNESS + D_SPAG_GALLERY_BONUS * waterTableGallery(wtable, y)) * fade;
    if (thickness > 0.0f && std::fabs(dens(D_SPAG1)) < thickness && std::fabs(dens(D_SPAG2)) < thickness) {
        return false;
    }
    return true;
}

template <typename D>
uint16_t Generator::decideBlock(const BaseTile& b, const CarveTile& c, int col, int lx, int lz, int64_t x,
                                int64_t y, int64_t z, D&& dens) const {
    return decideBlockM(b, [&] { return c.formation.test(lx, y, lz); }, [&] { return c.cave.test(lx, y, lz); },
                        [&] { return densitySolid(dens, y, b.h[col], b.biome[col], b.refH[col], c.table[col], c.guard[col]); },
                        col, x, y, z);
}

template <typename F, typename V, typename S>
uint16_t Generator::decideBlockM(const BaseTile& b, F&& inFormation, V&& inCave, S&& solid, int col, int64_t x,
                                 int64_t y, int64_t z) const {
    if (y < cfg.minY || y >= cfg.maxY) return cfg.id[SBW_AIR];
    if (isBedrock(x, y, z)) return cfg.id[SBW_BEDROCK];
    const int32_t h = b.h[col];
    const int32_t water = b.water[col];
    if (b.rivers) {
        const int32_t rf = b.riverFloor[col], rr = b.riverRoof[col];
        if (rr > rf && y >= rf && y <= rr) {
            if (y == rf || y == rr) return cfg.id[SBW_STONE];
            return (water != NO_WATER && y < water) ? cfg.id[SBW_WATER] : cfg.id[SBW_AIR];
        }
    }
    if (y < h) {
        if (inFormation()) return cfg.id[SBW_STONE];
        if (inCave()) return cfg.id[SBW_AIR];
        const int biome = b.biome[col];
        if (!solid()) return cfg.id[SBW_AIR];
        if (y < static_cast<int64_t>(h) - 4) {
            if (biome == SBW_RED_SAND_DESERT && y < static_cast<int64_t>(h) - 10 && magmaAt(x, y, z)) {
                return cfg.id[SBW_MAGMA];
            }
            return cfg.id[SBW_STONE];
        }
        if (y < static_cast<int64_t>(h) - 1) return subsurfaceId(biome);
        return b.surface[col];
    }
    if (water != NO_WATER && y < water) return cfg.id[SBW_WATER];
    return cfg.id[SBW_AIR];
}

} // namespace sbw
