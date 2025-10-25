package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;

/**
 * Manages biome determination based on temperature and moisture values.
 * Uses noise functions to generate climate patterns across the world.
 *
 * Phase 2 Enhancement: Uses Whittaker diagram classification for accurate ecological biome distribution.
 *
 * Follows Single Responsibility Principle - only handles biome logic.
 */
public class BiomeManager {
    private final NoiseGenerator terrainNoise;
    private final NoiseGenerator temperatureNoise;
    private final BiomeClassifier classifier;

    /**
     * Creates a new biome manager with the given seed.
     *
     * @param seed World seed for deterministic generation
     */
    public BiomeManager(long seed) {
        this.terrainNoise = new NoiseGenerator(seed);
        this.temperatureNoise = new NoiseGenerator(seed + 1);
        this.classifier = new BiomeClassifier();
    }

    /**
     * Determines the biome type based on temperature and moisture values.
     *
     * Phase 2: Uses Whittaker diagram classifier for ecological biome distribution.
     * Replaces hard-coded if/else logic with a proper 2D lookup table.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return The biome type at the given position
     */
    public BiomeType getBiome(int x, int z) {
        float moisture = getMoisture(x, z);
        float temperature = getTemperature(x, z);

        // Use Whittaker classification for accurate ecological biome mapping
        return classifier.classify(temperature, moisture);
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
