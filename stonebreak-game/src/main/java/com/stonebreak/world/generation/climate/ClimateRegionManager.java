package com.stonebreak.world.generation.climate;

import com.stonebreak.world.generation.NoiseGenerator;

/**
 * Samples a very-low-frequency continentalness channel to decide each position's
 * climate region (oceanic / coastal / continental interior / polar / tropical / arid).
 *
 * Uses seed + 4 to remain independent of the terrain-height continentalness noise (seed + 2).
 */
public final class ClimateRegionManager {

    private static final float NOISE_SCALE = 1f / 10_000f;
    private static final int OCTAVES = 6;
    private static final double PERSISTENCE = 0.5;
    private static final double LACUNARITY = 2.0;

    private final NoiseGenerator continentalnessNoise;

    public ClimateRegionManager(long seed) {
        this.continentalnessNoise = new NoiseGenerator(seed + 4, OCTAVES, PERSISTENCE, LACUNARITY);
    }

    public float getContinentalness(int x, int z) {
        return continentalnessNoise.noise(x * NOISE_SCALE, z * NOISE_SCALE);
    }

    public ClimateRegionType getRegion(int x, int z, float temperature, float moisture) {
        return ClimateRegionType.determineRegion(getContinentalness(x, z), temperature, moisture);
    }
}
