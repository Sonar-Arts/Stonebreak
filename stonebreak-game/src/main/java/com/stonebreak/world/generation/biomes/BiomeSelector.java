package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.noise.MultiNoiseSample;

/**
 * Selects a biome from the full (continentalness, erosion, peaks/valleys, temperature, moisture)
 * noise tuple. Biomes are matched to terrain shape — selection does NOT alter shape.
 *
 * Precedence, top-down:
 *   1. Coastal shelves (low continentalness + low peaks) → beach variants
 *   2. Mountainous shape (high peaks/valleys) → peak biomes, climate-skinned
 *   3. Hilly shape (mid peaks/valleys) → rolling biomes, climate-skinned
 *   4. Flatland (low peaks/valleys) → pure climate Whittaker selection
 */
public final class BiomeSelector {

    // Continentalness thresholds
    private static final float COAST_MAX_C = -0.15f;

    // Peaks/valleys thresholds (signed: negative = valley, positive = peak).
    // Simplex output clusters near 0, so mountain/hill thresholds must stay low
    // or those biomes barely appear.
    private static final float BEACH_MAX_PV = 0.15f;
    private static final float MOUNTAIN_MIN_PV = 0.50f;
    private static final float HILL_MIN_PV = 0.05f;

    // Erosion threshold (high erosion flattens peaks into plateaus)
    private static final float PLATEAU_MIN_EROSION = 0.35f;

    // Temperature bands [0,1]
    private static final float T_FREEZING = 0.20f;
    private static final float T_COLD = 0.38f;
    private static final float T_WARM = 0.62f;
    private static final float T_HOT = 0.78f;

    // Moisture bands [0,1]
    private static final float M_ARID = 0.25f;
    private static final float M_DRY = 0.45f;
    private static final float M_WET = 0.65f;

    public BiomeType select(MultiNoiseSample s) {
        if (s.continentalness() < COAST_MAX_C && s.peaksValleys() < BEACH_MAX_PV) {
            return coastal(s);
        }
        if (s.peaksValleys() >= MOUNTAIN_MIN_PV) {
            return mountainous(s);
        }
        if (s.peaksValleys() >= HILL_MIN_PV) {
            return hilly(s);
        }
        return flatland(s);
    }

    private BiomeType coastal(MultiNoiseSample s) {
        if (s.temperature() < T_COLD) {
            return BiomeType.ICE_FIELDS;
        }
        return BiomeType.BEACH;
    }

    private BiomeType mountainous(MultiNoiseSample s) {
        // High erosion on a mountain = plateau-like; pick climate match over stony peaks.
        boolean plateau = s.erosion() >= PLATEAU_MIN_EROSION;

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
}
