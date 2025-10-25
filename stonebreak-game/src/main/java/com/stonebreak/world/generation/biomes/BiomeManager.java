package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.climate.ClimateRegionManager;
import com.stonebreak.world.generation.climate.ClimateRegionType;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.List;

/**
 * Manages biome determination based on temperature and moisture values.
 * Uses noise functions to generate climate patterns across the world.
 *
 * Phase 1 Enhancement: Uses separate noise configs for moisture and temperature.
 *                      Implements altitude-based temperature chill (Luanti-inspired).
 *                      Implements multi-scale climate system with regional biome filtering.
 * Phase 2 Enhancement: Uses Whittaker diagram classification for accurate ecological biome distribution.
 *
 * Follows Single Responsibility Principle - only handles biome logic.
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 *
 * Implements IBiomeManager for dependency inversion and testability.
 */
public class BiomeManager implements IBiomeManager {
    private final NoiseGenerator moistureNoise;
    private final NoiseGenerator temperatureNoise;
    private final BiomeClassifier classifier;
    private final ClimateRegionManager climateRegionManager;
    private final float altitudeChillFactor;
    private final float moistureNoiseScale;
    private final float temperatureNoiseScale;

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    /**
     * Creates a new biome manager with the given seed and configuration.
     * Uses different noise configs for moisture and temperature to create varied climate patterns.
     *
     * Phase 1: Accepts ClimateRegionManager for multi-scale climate system.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     * @param climateRegionManager Manager for climate region determination
     */
    public BiomeManager(long seed, TerrainGenerationConfig config, ClimateRegionManager climateRegionManager) {
        this.moistureNoise = new NoiseGenerator(seed, NoiseConfigFactory.moisture());
        this.temperatureNoise = new NoiseGenerator(seed + 1, NoiseConfigFactory.temperature());
        this.classifier = new BiomeClassifier();
        this.climateRegionManager = climateRegionManager;
        this.altitudeChillFactor = config.altitudeChillFactor;
        this.moistureNoiseScale = config.moistureNoiseScale;
        this.temperatureNoiseScale = config.temperatureNoiseScale;
    }

    /**
     * Determines the biome type based on temperature and moisture values at sea level.
     * Uses sea level temperature (no altitude adjustment).
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return The biome type at the given position (at sea level)
     */
    @Override
    public BiomeType getBiome(int x, int z) {
        return getBiomeAtHeight(x, z, SEA_LEVEL);
    }

    /**
     * Determines the biome type based on temperature and moisture values at a specific height.
     *
     * Phase 1: Implements altitude-based temperature chill (Luanti-inspired).
     *          Mountains naturally become colder and get snow on peaks.
     *          Implements multi-scale climate system with regional biome filtering.
     * Phase 2: Uses Whittaker diagram classifier for ecological biome distribution.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param height Terrain height at this position (affects temperature)
     * @return The biome type at the given position and height
     */
    @Override
    public BiomeType getBiomeAtHeight(int x, int z, int height) {
        float moisture = getMoisture(x, z);
        float temperature = getTemperatureAtHeight(x, z, height);

        // Phase 1: Get climate region and filter allowed biomes
        ClimateRegionType climateRegion = climateRegionManager.getClimateRegion(x, z, temperature, moisture);
        List<BiomeType> allowedBiomes = climateRegion.getAllowedBiomes();

        // Use Whittaker classification with climate region filtering
        return classifier.classifyWithFilter(temperature, moisture, allowedBiomes);
    }

    /**
     * Generates moisture value for determining biomes.
     * Uses moisture-specific noise config for appropriate climate patterns.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Moisture value in range [0.0, 1.0]
     */
    @Override
    public float getMoisture(int x, int z) {
        float nx = x / moistureNoiseScale;
        float nz = z / moistureNoiseScale;
        return moistureNoise.noise(nx + 100, nz + 100) * 0.5f + 0.5f;
    }

    /**
     * Generates base temperature value at sea level.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Temperature value in range [0.0, 1.0] at sea level
     */
    @Override
    public float getTemperature(int x, int z) {
        return getTemperatureAtHeight(x, z, SEA_LEVEL);
    }

    /**
     * Generates temperature value at a specific height.
     *
     * Phase 1 Enhancement: Implements altitude chill - temperature decreases with height.
     * Inspired by Luanti's valleys mapgen altitude chill system.
     *
     * Example: At height 270 (200 blocks above sea level 70):
     *   - Base temperature: 0.8 (hot)
     *   - Altitude adjustment: -200/altitudeChillFactor = -1.0
     *   - Final temperature: max(0.0, 0.8 - 1.0) = 0.0 (cold, snowy peaks)
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param height Terrain height (affects cooling)
     * @return Temperature value in range [0.0, 1.0] adjusted for altitude
     */
    @Override
    public float getTemperatureAtHeight(int x, int z, int height) {
        float nx = x / temperatureNoiseScale;
        float nz = z / temperatureNoiseScale;
        float baseTemperature = temperatureNoise.noise(nx - 50, nz - 50) * 0.5f + 0.5f;

        // Apply altitude chill: higher elevation = colder
        // Only apply chill above sea level
        if (height > SEA_LEVEL) {
            float altitudeAboveSeaLevel = height - SEA_LEVEL;
            float temperatureDecrease = altitudeAboveSeaLevel / altitudeChillFactor;
            baseTemperature -= temperatureDecrease;
        }

        // Clamp to valid range [0.0, 1.0]
        return Math.max(0.0f, Math.min(1.0f, baseTemperature));
    }
}
