package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;

/**
 * Generates subtle erosion/weathering effects for terrain.
 *
 * This class adds high-frequency, low-amplitude noise to simulate:
 * - Surface weathering and erosion patterns
 * - Soil depth variation
 * - Natural terrain irregularities
 * - Breaking up monotonous flat surfaces
 *
 * Phase 1 Enhancement: Adds depth noise layer inspired by Minecraft's depth noise.
 * Applied multiplicatively to final height: finalHeight *= (1.0 + erosion * factor)
 *
 * Typical usage: 5% variation (factor = 0.05) creates subtle, natural-looking variation
 * without dramatically altering the underlying terrain shape.
 *
 * Follows Single Responsibility Principle - only handles erosion/detail noise.
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 */
public class ErosionNoiseGenerator {

    private final NoiseGenerator detailNoise;
    private final float erosionScale;
    private final float defaultErosionFactor;

    /**
     * Creates a new erosion noise generator with the given seed and configuration.
     * Uses detail noise config for high-frequency, low-amplitude variation.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public ErosionNoiseGenerator(long seed, TerrainGenerationConfig config) {
        // Use seed + 4 to ensure independence from other noise generators
        this.detailNoise = new NoiseGenerator(seed + 4, NoiseConfigFactory.detail());
        this.erosionScale = config.erosionNoiseScale;
        this.defaultErosionFactor = config.erosionStrengthFactor;
    }

    /**
     * Generates erosion/detail noise value at the specified position.
     * Returns a value typically in range [-0.3, 0.3] suitable for multiplication.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Erosion noise value (around [-0.3, 0.3])
     */
    public float getErosionNoise(int x, int z) {
        // Sample at high frequency for fine detail
        float nx = x / erosionScale;
        float nz = z / erosionScale;

        // Noise returns [-1, 1], scale down for subtle effect
        return detailNoise.noise(nx, nz) * 0.3f;
    }

    /**
     * Applies erosion noise to a height value.
     * Multiplies height by (1.0 + erosionNoise * factor) to add subtle variation.
     *
     * Example: height=100, erosionNoise=0.2, factor=0.05 → height=101 (1% increase)
     *
     * @param baseHeight Base height before erosion
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param factor Erosion strength (typically 0.03-0.07, default from config)
     * @return Height with erosion applied
     */
    public int applyErosion(int baseHeight, int x, int z, float factor) {
        float erosionNoise = getErosionNoise(x, z);
        float modifiedHeight = baseHeight * (1.0f + erosionNoise * factor);
        return Math.round(modifiedHeight);
    }

    /**
     * Applies erosion noise with default factor from configuration.
     *
     * @param baseHeight Base height before erosion
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height with erosion applied
     */
    public int applyErosion(int baseHeight, int x, int z) {
        return applyErosion(baseHeight, x, z, defaultErosionFactor);
    }
}
