package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.INoiseGenerator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * Generates ridged noise for sharp mountain peaks and ridge formations.
 *
 * This noise type creates linear ridges and sharp peaks by inverting and
 * taking the absolute value of simplex noise, then squaring the result
 * for additional sharpness.
 *
 * Algorithm:
 * 1. Generate simplex noise in range [-1, 1]
 * 2. Invert and take absolute value: ridged = 1.0 - abs(simplex)
 * 3. Square for sharpness: sharpened = ridged * ridged
 * 4. Remap to [-1, 1]: result = sharpened * 2.0 - 1.0
 *
 * Use Cases:
 * - Sharp mountain ridges (Stony Peaks biome)
 * - Volcanic ridge formations (Red Sand Desert)
 * - Ice pressure ridges (Ice Fields)
 * - Dramatic mountain ranges with defined peaks
 *
 * Characteristics:
 * - Linear ridges instead of smooth curves
 * - Sharp transitions at ridge lines
 * - Valleys between ridges
 * - Best used in high-altitude regions
 *
 * Follows Single Responsibility Principle - only handles ridged noise generation.
 * Follows Dependency Inversion Principle - depends on INoiseGenerator abstraction.
 *
 * Implements INoiseGenerator for interoperability with terrain generation system.
 */
public class RidgedNoiseGenerator implements INoiseGenerator {

    private final NoiseGenerator baseNoise;

    /**
     * Creates a new ridged noise generator with the specified seed and configuration.
     *
     * @param seed   World seed for deterministic generation
     * @param config Noise configuration (octaves, persistence, lacunarity)
     */
    public RidgedNoiseGenerator(long seed, NoiseConfig config) {
        this.baseNoise = new NoiseGenerator(seed, config);
    }

    /**
     * Generates ridged noise value at the specified coordinates.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return Ridged noise value in range [-1.0, 1.0]
     */
    @Override
    public float noise(float x, float z) {
        // 1. Get base simplex noise [-1, 1]
        float simplex = baseNoise.noise(x, z);

        // 2. Create ridge by inverting and taking absolute value
        // This creates peaks at 1.0 and valleys at 0.0
        float ridged = 1.0f - Math.abs(simplex);

        // 3. Square to sharpen the ridges
        // Makes ridges narrower and valleys broader
        float sharpened = ridged * ridged;

        // 4. Remap from [0, 1] back to [-1, 1] for consistency
        return sharpened * 2.0f - 1.0f;
    }

    /**
     * Gets ridged noise with custom sharpness factor.
     *
     * Higher sharpness creates narrower, more dramatic ridges.
     * Lower sharpness creates broader, gentler ridges.
     *
     * @param x         X coordinate in noise space
     * @param z         Z coordinate in noise space
     * @param sharpness Sharpness factor (1.0 = default, 2.0 = sharper, 0.5 = gentler)
     * @return Ridged noise value in range [-1.0, 1.0]
     */
    public float noiseWithSharpness(float x, float z, float sharpness) {
        float simplex = baseNoise.noise(x, z);
        float ridged = 1.0f - Math.abs(simplex);

        // Apply sharpness factor as exponent
        float sharpened = (float) Math.pow(ridged, sharpness);

        return sharpened * 2.0f - 1.0f;
    }
}
