package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.INoiseGenerator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * Generates erosion noise to control terrain flatness vs roughness.
 *
 * This noise type determines whether terrain should be flat plains or
 * mountainous regions. Unlike surface detail noise (40-block scale),
 * erosion noise operates at continental scale (300 blocks) to create
 * large, consistent terrain regions.
 *
 * Algorithm:
 * 1. Generate simplex noise in range [-1, 1]
 * 2. Return raw value (no transformation)
 * 3. Result: smooth, large-scale erosion patterns
 *
 * Value Interpretation:
 * - Low erosion (-1.0): Mountainous, rough terrain with high amplification
 * - Medium erosion (0.0): Neutral terrain with moderate variation
 * - High erosion (1.0): Flat plains with reduced height variation
 *
 * Use Cases:
 * - Large flat plains spanning 300-600 blocks
 * - Continental-scale mountain ranges
 * - Gradual transitions between flat and mountainous regions
 * - Building-friendly terrain (cities, bases, farms)
 *
 * Characteristics:
 * - Scale: 300 blocks per noise unit (vs 40 for surface detail)
 * - Octaves: 6 (moderate detail for smooth transitions)
 * - Persistence: 0.5 (balanced variation)
 * - Lacunarity: 2.2 (slightly more variation between octaves)
 *
 * Terra v.06 Improvement:
 * Replaced SurfaceDetailGenerator (40-block scale) with dedicated
 * ErosionNoiseGenerator (300-block scale) to create larger flat plains
 * instead of small 40-80 block patches.
 *
 * Follows Single Responsibility Principle - only handles erosion noise generation.
 * Follows Dependency Inversion Principle - depends on INoiseGenerator abstraction.
 *
 * Implements INoiseGenerator for interoperability with terrain generation system.
 */
public class ErosionNoiseGenerator implements INoiseGenerator {

    private final NoiseGenerator baseNoise;

    /**
     * Creates a new erosion noise generator with the specified seed and configuration.
     *
     * @param seed   World seed for deterministic generation
     * @param config Noise configuration (octaves, persistence, lacunarity)
     */
    public ErosionNoiseGenerator(long seed, NoiseConfig config) {
        this.baseNoise = new NoiseGenerator(seed, config);
    }

    /**
     * Generates erosion noise value at the specified coordinates.
     *
     * Returns raw simplex noise without transformation, allowing for
     * smooth, natural-looking erosion patterns at continental scale.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return Erosion noise value in range [-1.0, 1.0]
     */
    @Override
    public float noise(float x, float z) {
        // Return raw simplex noise for smooth erosion patterns
        return baseNoise.noise(x, z);
    }

    /**
     * Gets erosion noise with custom scaling for testing different scales.
     *
     * Allows experimenting with different erosion scales without changing
     * the base configuration.
     *
     * @param x     X coordinate in world space
     * @param z     Z coordinate in world space
     * @param scale Scale factor (e.g., 300.0 for standard, 500.0 for larger regions)
     * @return Erosion noise value in range [-1.0, 1.0]
     */
    public float noiseWithScale(float x, float z, float scale) {
        return baseNoise.noise(x / scale, z / scale);
    }

    /**
     * Gets erosion noise with dampened extremes for gentler terrain.
     *
     * Applies a power function to reduce extreme values, creating
     * more moderate terrain variation overall.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return Dampened erosion noise value in range [-1.0, 1.0]
     */
    public float dampenedNoise(float x, float z) {
        float erosion = baseNoise.noise(x, z);

        // Apply power function to dampen extremes
        // Exponent < 1 reduces extreme values while keeping middle values
        float abs = Math.abs(erosion);
        float dampened = (float) Math.pow(abs, 0.8);

        // Restore sign
        return erosion > 0 ? dampened : -dampened;
    }
}
