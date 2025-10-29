package com.stonebreak.world.generation.noise;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.heightmap.ErosionNoiseGenerator;
import com.stonebreak.world.generation.heightmap.PeaksValleysNoiseGenerator;
import com.stonebreak.world.generation.heightmap.WeirdnessNoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Centralized noise router for multi-noise terrain generation.
 *
 * This class coordinates all noise generators and provides a unified interface
 * for sampling the six parameters used in terrain generation and biome selection.
 *
 * Inspired by Minecraft's noise router system where multiple noise generators
 * work together to create varied, interesting terrain.
 *
 * Architecture:
 * - Each parameter has its own noise generator with specific characteristics
 * - All generators share the same world seed for consistency
 * - Noise scales configured via TerrainGenerationConfig
 *
 * Thread Safety:
 * - Immutable after construction
 * - Noise generators are thread-safe
 * - Safe for concurrent sampling from multiple threads
 *
 * Follows Dependency Inversion Principle:
 * - Configuration injected via constructor
 * - Consumers depend on this abstraction, not individual generators
 */
public class NoiseRouter {

    private final NoiseGenerator continentalnessNoise;
    private final ErosionNoiseGenerator erosionNoise;
    private final PeaksValleysNoiseGenerator peaksValleysNoise;
    private final WeirdnessNoiseGenerator weirdnessNoise;
    private final NoiseGenerator temperatureNoise;
    private final NoiseGenerator humidityNoise;

    private final TerrainGenerationConfig config;
    private final int seaLevel;
    private final float altitudeChillFactor;

    /**
     * Creates a new noise router with the given seed and configuration.
     *
     * Initializes all six noise generators with appropriate seed offsets
     * to ensure independence between noise patterns.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration (scales, etc.)
     */
    public NoiseRouter(long seed, TerrainGenerationConfig config) {
        this.config = config;
        this.seaLevel = WorldConfiguration.SEA_LEVEL;
        this.altitudeChillFactor = config.altitudeChillFactor;

        // Initialize noise generators with different seed offsets for independence
        this.continentalnessNoise = new NoiseGenerator(seed + 2, NoiseConfigFactory.continentalness());
        this.erosionNoise = new ErosionNoiseGenerator(seed + 10, config);
        this.peaksValleysNoise = new PeaksValleysNoiseGenerator(seed + 11, NoiseConfigFactory.terrainPeaksValleys());
        this.weirdnessNoise = new WeirdnessNoiseGenerator(seed + 12, NoiseConfigFactory.terrainWeirdness());
        this.temperatureNoise = new NoiseGenerator(seed + 1, NoiseConfigFactory.temperature());
        this.humidityNoise = new NoiseGenerator(seed, NoiseConfigFactory.moisture());
    }

    /**
     * Samples all six parameters at a world position.
     * Uses sea level for temperature calculation (no altitude adjustment).
     *
     * This is the main entry point for parameter sampling during terrain generation.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Multi-noise parameters at this position
     */
    public MultiNoiseParameters sampleParameters(int worldX, int worldZ) {
        return sampleParameters(worldX, worldZ, seaLevel);
    }

    /**
     * Samples all six parameters at a world position.
     *
     * All parameters are sampled from noise at the given X/Z coordinates.
     * Temperature is purely noise-based without altitude adjustment.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param height Terrain height (unused for temperature, kept for API compatibility)
     * @return Multi-noise parameters at this position
     */
    public MultiNoiseParameters sampleParameters(int worldX, int worldZ, int height) {
        // Sample continentalness
        float continentalness = continentalnessNoise.noise(
                worldX / config.continentalnessNoiseScale,
                worldZ / config.continentalnessNoiseScale
        );

        // Sample erosion (already handles scaling internally)
        float erosion = erosionNoise.getErosionNoise(worldX, worldZ);

        // Sample peaks & valleys (scale 150 blocks per unit)
        float peaksValleys = peaksValleysNoise.noise(worldX / 150.0f, worldZ / 150.0f);

        // Sample weirdness (scale 200 blocks per unit)
        float weirdness = weirdnessNoise.noise(worldX / 200.0f, worldZ / 200.0f);

        // Sample temperature (height parameter unused but passed for consistency)
        float temperature = sampleTemperature(worldX, worldZ, height);

        // Sample humidity (moisture)
        float humidity = humidityNoise.noise(
                worldX / config.moistureNoiseScale + 100,
                worldZ / config.moistureNoiseScale + 100
        ) * 0.5f + 0.5f;  // Map from [-1, 1] to [0, 1]

        return new MultiNoiseParameters(
                continentalness,
                erosion,
                peaksValleys,
                weirdness,
                temperature,
                humidity
        );
    }

    /**
     * Samples temperature based purely on noise at X/Z coordinates.
     *
     * Temperature is determined solely by 2D noise patterns without any
     * altitude-based adjustment. This allows biomes to be fully controlled
     * by the multi-noise parameter system.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param height Terrain height (unused, kept for API compatibility)
     * @return Temperature in range [0.0, 1.0]
     */
    private float sampleTemperature(int worldX, int worldZ, int height) {
        // Temperature from noise only (no altitude adjustment)
        float temperature = temperatureNoise.noise(
                worldX / config.temperatureNoiseScale - 50,
                worldZ / config.temperatureNoiseScale - 50
        ) * 0.5f + 0.5f;  // Map from [-1, 1] to [0, 1]

        // Clamp to valid range (should already be in range, but ensure safety)
        return Math.max(0.0f, Math.min(1.0f, temperature));
    }

    /**
     * Gets the continentalness value at a position.
     * Convenience method for systems that only need continentalness.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Continentalness in range [-1.0, 1.0]
     */
    public float getContinentalness(int worldX, int worldZ) {
        return continentalnessNoise.noise(
                worldX / config.continentalnessNoiseScale,
                worldZ / config.continentalnessNoiseScale
        );
    }

    /**
     * Gets the erosion value at a position.
     * Convenience method for systems that only need erosion.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Erosion in range [-1.0, 1.0]
     */
    public float getErosion(int worldX, int worldZ) {
        return erosionNoise.getErosionNoise(worldX, worldZ);
    }

    /**
     * Gets the temperature value at a position (sea level).
     * Convenience method for systems that only need temperature.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Temperature in range [0.0, 1.0]
     */
    public float getTemperature(int worldX, int worldZ) {
        return sampleTemperature(worldX, worldZ, seaLevel);
    }

    /**
     * Gets the humidity value at a position.
     * Convenience method for systems that only need humidity.
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Humidity in range [0.0, 1.0]
     */
    public float getHumidity(int worldX, int worldZ) {
        return humidityNoise.noise(
                worldX / config.moistureNoiseScale + 100,
                worldZ / config.moistureNoiseScale + 100
        ) * 0.5f + 0.5f;
    }
}
