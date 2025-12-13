package com.stonebreak.world.generation.noise;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfigFactory;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;

/**
 * Generates subtle surface detail effects for terrain.
 *
 * This class adds high-frequency, low-amplitude noise to simulate:
 * - Surface weathering and erosion patterns
 * - Soil depth variation
 * - Natural terrain irregularities
 * - Breaking up monotonous flat surfaces
 *
 * Phase 1 Enhancement: Adds depth noise layer inspired by Minecraft's depth noise.
 * Applied multiplicatively to final height: finalHeight *= (1.0 + detail * factor)
 *
 * Typical usage: 5% variation (factor = 0.05) creates subtle, natural-looking variation
 * without dramatically altering the underlying terrain shape.
 *
 * Follows Single Responsibility Principle - only handles surface detail noise.
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 */
public class SurfaceDetailGenerator {

    private final NoiseGenerator detailNoise;
    private final float detailScale;
    private final float defaultDetailFactor;

    /**
     * Creates a new surface detail noise generator with the given seed and configuration.
     * Uses detail noise config for high-frequency, low-amplitude variation.
     *
     * @param seed World seed for deterministic generation
     * @param config Terrain generation configuration
     */
    public SurfaceDetailGenerator(long seed, TerrainGenerationConfig config) {
        // Use seed + 4 to ensure independence from other noise generators
        this.detailNoise = new NoiseGenerator(seed + 4, NoiseConfigFactory.detail());
        this.detailScale = config.erosionNoiseScale;
        this.defaultDetailFactor = config.erosionStrengthFactor;
    }

    /**
     * Generates surface detail noise value at the specified position.
     * Returns a value typically in range [-0.3, 0.3] suitable for multiplication.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Surface detail noise value (around [-0.3, 0.3])
     */
    public float getSurfaceDetailNoise(int x, int z) {
        // Sample at high frequency for fine detail
        float nx = x / detailScale;
        float nz = z / detailScale;

        // Noise returns [-1, 1], scale down for subtle effect
        return detailNoise.noise(nx, nz) * 0.3f;
    }

    /**
     * Applies surface detail noise to a height value.
     * Multiplies height by (1.0 + detailNoise * factor) to add subtle variation.
     *
     * Example: height=100, detailNoise=0.2, factor=0.05 → height=101 (1% increase)
     *
     * @param baseHeight Base height before detail application
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param factor Detail strength (typically 0.03-0.07, default from config)
     * @return Height with surface detail applied
     */
    public int applySurfaceDetail(int baseHeight, int x, int z, float factor) {
        float detailNoise = getSurfaceDetailNoise(x, z);
        float modifiedHeight = baseHeight * (1.0f + detailNoise * factor);
        return Math.round(modifiedHeight);
    }

    /**
     * Applies surface detail noise with default factor from configuration.
     *
     * @param baseHeight Base height before detail application
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Height with surface detail applied
     */
    public int applySurfaceDetail(int baseHeight, int x, int z) {
        return applySurfaceDetail(baseHeight, x, z, defaultDetailFactor);
    }
}
