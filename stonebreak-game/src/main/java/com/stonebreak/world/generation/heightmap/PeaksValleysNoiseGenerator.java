package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.INoiseGenerator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * Generates Peaks & Valleys noise to amplify terrain extremes.
 *
 * This noise type makes high areas higher and low areas lower, creating
 * dramatic height differences with plateaus at extreme values. Inspired
 * by Minecraft 1.18+ terrain generation.
 *
 * Algorithm:
 * 1. Generate simplex noise in range [-1, 1]
 * 2. Square the value to amplify extremes
 * 3. Preserve sign (positive stays positive, negative stays negative)
 * 4. Result: peaks become more peaked, valleys become deeper
 *
 * Use Cases:
 * - Dramatic mountain peaks (Stony Peaks, Ice Fields)
 * - Deep valleys between mountains
 * - Overall terrain height variation
 * - Creating plateaus at extreme values
 *
 * Characteristics:
 * - Amplifies high and low terrain equally
 * - Creates flatter areas at extremes (plateaus)
 * - Steepens slopes in middle ranges
 * - Best for overall terrain drama
 *
 * Follows Single Responsibility Principle - only handles PV noise generation.
 * Follows Dependency Inversion Principle - depends on INoiseGenerator abstraction.
 *
 * Implements INoiseGenerator for interoperability with terrain generation system.
 */
public class PeaksValleysNoiseGenerator implements INoiseGenerator {

    private final NoiseGenerator baseNoise;

    /**
     * Creates a new Peaks & Valleys noise generator with the specified seed and configuration.
     *
     * @param seed   World seed for deterministic generation
     * @param config Noise configuration (octaves, persistence, lacunarity)
     */
    public PeaksValleysNoiseGenerator(long seed, NoiseConfig config) {
        this.baseNoise = new NoiseGenerator(seed, config);
    }

    /**
     * Generates Peaks & Valleys noise value at the specified coordinates.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return PV noise value in range [-1.0, 1.0]
     */
    @Override
    public float noise(float x, float z) {
        // 1. Get base simplex noise [-1, 1]
        float simplex = baseNoise.noise(x, z);

        // 2. Amplify extremes by squaring while preserving sign
        // Positive values become more positive, negative become more negative
        if (simplex > 0) {
            return simplex * simplex;  // Square positive values (0 to 1)
        } else {
            return -(simplex * simplex);  // Square and negate negative values (-1 to 0)
        }
    }

    /**
     * Gets PV noise with custom amplification factor.
     *
     * Higher amplification creates more extreme peaks and valleys.
     * Lower amplification creates gentler terrain variation.
     *
     * @param x             X coordinate in noise space
     * @param z             Z coordinate in noise space
     * @param amplification Amplification factor (1.0 = default squaring, 2.0 = cubing effect)
     * @return PV noise value in range [-1.0, 1.0]
     */
    public float noiseWithAmplification(float x, float z, float amplification) {
        float simplex = baseNoise.noise(x, z);

        // Apply amplification factor as exponent
        float exponent = 1.0f + amplification;

        if (simplex > 0) {
            return (float) Math.pow(simplex, exponent);
        } else {
            return -(float) Math.pow(-simplex, exponent);
        }
    }

    /**
     * Gets PV noise with spline-based amplification for smoother results.
     *
     * Uses a smooth curve instead of simple squaring, creating more natural-looking
     * terrain with gradual transitions.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return PV noise value in range [-1.0, 1.0]
     */
    public float noiseWithSpline(float x, float z) {
        float simplex = baseNoise.noise(x, z);

        // Use smoothstep function for gradual amplification
        // smoothstep(x) = 3x² - 2x³ for values in [0, 1]
        float abs = Math.abs(simplex);
        float smoothed = abs * abs * (3.0f - 2.0f * abs);

        // Restore sign
        return simplex > 0 ? smoothed : -smoothed;
    }
}
