package com.stonebreak.world.generation.climate;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;

/**
 * Manages multi-scale climate regions for biome distribution.
 *
 * Phase 1 Enhancement: Implements large-scale climate regions (10,000+ block scale)
 * using two noise generators:
 * - Continentalness: Determines inland vs coastal vs oceanic regions
 * - Region Weirdness: Adds variety and exceptions to regional rules
 *
 * Follows SOLID principles:
 * - Single Responsibility: Only manages climate region noise generation
 * - Dependency Inversion: Configuration injected via constructor
 * - Interface Segregation: Provides focused public API
 *
 * Follows KISS principle:
 * - No caching in Phase 1 (optimize later if needed)
 * - Simple delegation to ClimateRegionType for classification
 * - Minimal logic, just noise sampling
 */
public class ClimateRegionManager {

    private final NoiseGenerator continentalnessNoise;
    private final NoiseGenerator regionWeirdnessNoise;
    private final float continentalnessNoiseScale;
    private final float regionWeirdnessNoiseScale;

    /**
     * Creates a new climate region manager with the given seed and configuration.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public ClimateRegionManager(long seed, TerrainGenerationConfig config) {
        // Initialize noise generators with different seed offsets for independence
        this.continentalnessNoise = new NoiseGenerator(seed + 4, NoiseConfigFactory.createContinentalnessClimateNoise());
        this.regionWeirdnessNoise = new NoiseGenerator(seed + 5, NoiseConfigFactory.createRegionWeirdnessNoise());

        // Store scales from configuration
        this.continentalnessNoiseScale = config.continentalnessClimateNoiseScale;
        this.regionWeirdnessNoiseScale = config.regionWeirdnessNoiseScale;
    }

    /**
     * Gets the continentalness value at the specified world position.
     * Continentalness determines whether terrain is ocean, coast, or inland.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     *         -1.0 = oceanic, 0.0 = coastal, 1.0 = deep inland
     */
    public float getContinentalness(int worldX, int worldZ) {
        float nx = worldX / continentalnessNoiseScale;
        float nz = worldZ / continentalnessNoiseScale;
        return continentalnessNoise.noise(nx, nz);  // Already in range [-1, 1]
    }

    /**
     * Gets the region weirdness value at the specified world position.
     * Weirdness adds variety and exceptions to strict climate region rules.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Region weirdness value in range [-1.0, 1.0]
     *         High absolute values indicate "weird" areas with unusual biome placement
     */
    public float getRegionWeirdness(int worldX, int worldZ) {
        float nx = worldX / regionWeirdnessNoiseScale;
        float nz = worldZ / regionWeirdnessNoiseScale;
        return regionWeirdnessNoise.noise(nx, nz);  // Already in range [-1, 1]
    }

    /**
     * Determines the climate region type for the specified world position.
     * Delegates to ClimateRegionType.determineRegion() for classification.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param temperature Temperature value [0.0, 1.0] (0=cold, 1=hot)
     * @param moisture Moisture value [0.0, 1.0] (0=dry, 1=wet)
     * @return The climate region type at the given position
     */
    public ClimateRegionType getClimateRegion(int worldX, int worldZ, float temperature, float moisture) {
        float continentalness = getContinentalness(worldX, worldZ);
        // Note: regionWeirdness can be used in future phases for biome exceptions
        // float weirdness = getRegionWeirdness(worldX, worldZ);

        return ClimateRegionType.determineRegion(continentalness, temperature, moisture);
    }
}
