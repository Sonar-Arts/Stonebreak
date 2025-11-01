package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Classifies terrain into hint categories based on noise parameter patterns.
 *
 * This classifier detects parameter combinations that indicate specific terrain types
 * (mesas, sharp peaks, gentle hills, etc.) BEFORE biome selection occurs. This solves
 * the chicken-and-egg problem of biomes needing to influence terrain while terrain
 * determines biome selection.
 *
 * Detection Logic:
 * - MESA: Hot (>0.6) + Dry (<0.3) + High Weirdness (>0.7)
 * - SHARP_PEAKS: Cold (<0.4) + Very Mountainous (<-0.5) + High Peaks (>0.3)
 * - GENTLE_HILLS: High Erosion (>0.4) + Low PV (|pv| < 0.3)
 * - FLAT_PLAINS: Very High Erosion (>0.7)
 * - NORMAL: Default for all other cases
 *
 * The thresholds are tuned to align with biome parameter ranges, creating a natural
 * correspondence between terrain hints and biomes without explicit coupling.
 *
 * Thread-safe (stateless utility class).
 */
public class TerrainHintClassifier {

    /**
     * Classifies terrain based on noise parameter patterns.
     *
     * @param params Multi-noise parameters sampled at a location
     * @return Terrain hint indicating what type of terrain should be generated
     */
    public static TerrainHint classifyTerrain(MultiNoiseParameters params) {
        float continentalness = params.continentalness;
        float erosion = params.erosion;
        float peaksValleys = params.peaksValleys;
        float weirdness = params.weirdness;
        float temperature = params.temperature;
        float humidity = params.humidity;

        // Priority 1: Mesa terrain (Hot + Dry + High Weirdness)
        // Aligns with Badlands biome parameters
        if (temperature > 0.6f && humidity < 0.3f && weirdness > 0.7f) {
            return TerrainHint.MESA;
        }

        // Priority 2: Sharp peaks (Cold + Very Mountainous + High PV)
        // Aligns with Stony Peaks biome parameters
        if (temperature < 0.4f && erosion < -0.5f && peaksValleys > 0.3f) {
            return TerrainHint.SHARP_PEAKS;
        }

        // Priority 3: Flat plains (Very High Erosion)
        // Must check before gentle hills to catch extreme flatness
        if (erosion > 0.7f) {
            return TerrainHint.FLAT_PLAINS;
        }

        // Priority 4: Gentle hills (High Erosion + Low PV)
        if (erosion > 0.4f && Math.abs(peaksValleys) < 0.3f) {
            return TerrainHint.GENTLE_HILLS;
        }

        // Default: Normal terrain generation
        return TerrainHint.NORMAL;
    }

    /**
     * Gets a human-readable description of the terrain hint's characteristics.
     *
     * @param hint Terrain hint to describe
     * @return Description string
     */
    public static String getDescription(TerrainHint hint) {
        return switch (hint) {
            case MESA -> "Mesa terrain with plateau terracing and canyon carving";
            case SHARP_PEAKS -> "Sharp mountain peaks with extreme heights and jagged surfaces";
            case GENTLE_HILLS -> "Gentle rolling hills with reduced height variation";
            case FLAT_PLAINS -> "Very flat plains with minimal elevation changes";
            case NORMAL -> "Standard terrain generation";
        };
    }

    /**
     * Checks if the terrain hint represents mountainous terrain.
     *
     * @param hint Terrain hint to check
     * @return True if the hint represents mountains
     */
    public static boolean isMountainous(TerrainHint hint) {
        return hint == TerrainHint.SHARP_PEAKS || hint == TerrainHint.MESA;
    }

    /**
     * Checks if the terrain hint represents flat terrain.
     *
     * @param hint Terrain hint to check
     * @return True if the hint represents flat terrain
     */
    public static boolean isFlat(TerrainHint hint) {
        return hint == TerrainHint.FLAT_PLAINS || hint == TerrainHint.GENTLE_HILLS;
    }
}
