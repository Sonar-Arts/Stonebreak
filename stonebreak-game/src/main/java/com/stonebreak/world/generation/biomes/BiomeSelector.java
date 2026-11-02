package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.StandardTerrain;
import com.stonebreak.world.generation.noise.MultiNoiseSample;

/**
 * Selects a biome from the distilled terrain shape (land, mountain, hilly, rough) and climate
 * (temperature, moisture). Biomes are matched to terrain shape — selection does NOT alter shape.
 *
 * Precedence, top-down:
 *   1. Submerged columns (surface under sea level) → ocean, or ice fields when frozen
 *   2. Coastal strip (within two blocks of the sea, in the continent-edge transition) → beach variants
 *   3. Mountain-range body → peak biomes, climate-skinned (smooth bodies read as plateaus)
 *   4. Range flanks and hill-country provinces → rolling biomes, climate-skinned
 *   5. Flatland → swamp on low-lying, wet, non-cold ground; otherwise pure climate Whittaker selection
 */
public final class BiomeSelector {

    // Shape thresholds over the distilled terrain fields (DistilledShape.Column via MultiNoiseSample),
    // calibrated on StandardTerrainDump tiles by terrain_slm.eval.distill_biomes (2026-11-01):
    // mountain biomes cover the land share standing at y >= 150, hill biomes the y 110..150 share
    // (half range flanks, half hill-country provinces), swamps the low-lying warm wet flats.
    // Beach: dry ground within two blocks of the sea, still inside the continent-edge transition.
    private static final int COAST_MAX_Y = StandardTerrain.SEA_LEVEL + 2;
    private static final float COAST_MAX_LAND = 0.999f;
    private static final float BEACH_MAX_MOUNTAIN = 0.15f;
    private static final float MOUNTAIN_MIN = 0.617f;
    private static final float HILL_MIN_MOUNTAIN = 0.46f;
    private static final float HILL_MIN_HILLY = 0.237f;

    // A smooth range body (high but gentle) reads as a plateau.
    private static final float PLATEAU_MAX_ROUGH = 0.45f;

    // Temperature bands [0,1]
    private static final float T_FREEZING = 0.20f;
    private static final float T_COLD = 0.38f;
    private static final float T_WARM = 0.62f;
    private static final float T_HOT = 0.78f;

    // Moisture bands [0,1]
    private static final float M_ARID = 0.25f;
    private static final float M_DRY = 0.45f;
    private static final float M_WET = 0.65f;

    // Swamp: flat, low-lying inland ground, away from ranges (DaedalusTGM-Exp swamps sit below ~300 m).
    private static final int SWAMP_MAX_Y = 80;
    private static final float SWAMP_MAX_ROUGH = 0.3f;
    private static final float SWAMP_MAX_MOUNTAIN = 0.1f;

    public BiomeType select(MultiNoiseSample s, boolean submerged) {
        if (submerged) {
            return ocean(s);
        }
        if (s.height() <= COAST_MAX_Y && s.land() < COAST_MAX_LAND && s.mountain() < BEACH_MAX_MOUNTAIN) {
            return coastal(s);
        }
        if (s.mountain() >= MOUNTAIN_MIN) {
            return mountainous(s);
        }
        if (s.mountain() >= HILL_MIN_MOUNTAIN || s.hilly() >= HILL_MIN_HILLY) {
            return hilly(s);
        }
        return flatland(s);
    }

    /** Mirrors DaedalusTGM-Exp's ocean ids: frozen_ocean is ice fields, every other sea is OCEAN. */
    private BiomeType ocean(MultiNoiseSample s) {
        return s.temperature() < T_FREEZING ? BiomeType.ICE_FIELDS : BiomeType.OCEAN;
    }

    private BiomeType coastal(MultiNoiseSample s) {
        if (s.temperature() < T_COLD) {
            return BiomeType.ICE_FIELDS;
        }
        return BiomeType.BEACH;
    }

    private BiomeType mountainous(MultiNoiseSample s) {
        // A smooth range body = plateau-like; pick climate match over stony peaks.
        boolean plateau = s.rough() < PLATEAU_MAX_ROUGH;

        if (s.temperature() < T_FREEZING) {
            return BiomeType.ICE_FIELDS;
        }
        if (s.temperature() < T_COLD) {
            return plateau ? BiomeType.SNOWY_PLAINS : BiomeType.STONY_PEAKS;
        }
        if (s.temperature() >= T_HOT && s.moisture() < M_DRY) {
            return BiomeType.BADLANDS;
        }
        return BiomeType.STONY_PEAKS;
    }

    private BiomeType hilly(MultiNoiseSample s) {
        if (s.temperature() < T_FREEZING) {
            return BiomeType.TUNDRA;
        }
        if (s.temperature() < T_COLD) {
            return s.moisture() >= M_WET ? BiomeType.TAIGA : BiomeType.SNOWY_PLAINS;
        }
        if (s.temperature() < T_WARM) {
            return s.moisture() >= M_WET ? BiomeType.MEADOW : BiomeType.PLAINS;
        }
        if (s.moisture() < M_ARID) {
            return BiomeType.BADLANDS;
        }
        if (s.moisture() < M_DRY) {
            return BiomeType.RED_SAND_DESERT;
        }
        return BiomeType.PLAINS;
    }

    private BiomeType flatland(MultiNoiseSample s) {
        if (isSwamp(s)) {
            return BiomeType.SWAMP;
        }
        if (s.temperature() < T_FREEZING) {
            return s.moisture() >= M_WET ? BiomeType.ICE_FIELDS : BiomeType.TUNDRA;
        }
        if (s.temperature() < T_COLD) {
            if (s.moisture() >= M_WET) return BiomeType.TAIGA;
            return BiomeType.SNOWY_PLAINS;
        }
        if (s.temperature() < T_WARM) {
            return s.moisture() >= M_WET ? BiomeType.MEADOW : BiomeType.PLAINS;
        }
        if (s.temperature() < T_HOT) {
            if (s.moisture() < M_ARID) return BiomeType.DESERT;
            if (s.moisture() < M_DRY) return BiomeType.RED_SAND_DESERT;
            return BiomeType.PLAINS;
        }
        // Hot flatlands
        if (s.moisture() < M_ARID) return BiomeType.DESERT;
        if (s.moisture() < M_DRY) return BiomeType.RED_SAND_DESERT;
        if (s.moisture() < M_WET) return BiomeType.BADLANDS;
        return BiomeType.PLAINS;
    }

    private static boolean isSwamp(MultiNoiseSample s) {
        return s.height() <= SWAMP_MAX_Y
            && s.rough() < SWAMP_MAX_ROUGH && s.mountain() < SWAMP_MAX_MOUNTAIN
            && s.temperature() >= T_COLD
            && s.moisture() >= M_WET;
    }
}
