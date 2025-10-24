package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;

/**
 * Manages biome determination based on temperature and moisture values.
 * Uses noise functions to generate climate patterns across the world.
 *
 * Follows Single Responsibility Principle - only handles biome logic.
 */
public class BiomeManager {
    private final NoiseGenerator terrainNoise;
    private final NoiseGenerator temperatureNoise;

    /**
     * Creates a new biome manager with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public BiomeManager(long seed) {
        this.terrainNoise = new NoiseGenerator(seed);
        this.temperatureNoise = new NoiseGenerator(seed + 1);
    }

    /**
     * Determines the biome type based on temperature and moisture values.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return The biome type at the given position
     */
    public BiomeType getBiome(int x, int z) {
        float moisture = getMoisture(x, z);
        float temperature = getTemperature(x, z);

        if (temperature > 0.65f) { // Hot
            if (moisture < 0.35f) {
                return BiomeType.DESERT;
            } else {
                return BiomeType.RED_SAND_DESERT; // Hot and somewhat moist/varied = Red Sand Desert
            }
        } else if (temperature < 0.35f) { // Cold
            if (moisture > 0.6f) {
                return BiomeType.SNOWY_PLAINS; // Cold and moist = snowy plains
            } else {
                return BiomeType.PLAINS; // Cold but dry = regular plains
            }
        } else { // Temperate
            if (moisture < 0.3f) {
                return BiomeType.DESERT; // Temperate but dry = also desert like
            } else {
                return BiomeType.PLAINS;
            }
        }
    }

    /**
     * Generates moisture value for determining biomes.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Moisture value in range [0.0, 1.0]
     */
    public float getMoisture(int x, int z) {
        float nx = x / 200.0f;
        float nz = z / 200.0f;
        return terrainNoise.noise(nx + 100, nz + 100) * 0.5f + 0.5f;
    }

    /**
     * Generates temperature value for determining biomes.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Temperature value in range [0.0, 1.0]
     */
    public float getTemperature(int x, int z) {
        float nx = x / 300.0f; // Different scale for temperature
        float nz = z / 300.0f;
        return temperatureNoise.noise(nx - 50, nz - 50) * 0.5f + 0.5f;
    }
}
