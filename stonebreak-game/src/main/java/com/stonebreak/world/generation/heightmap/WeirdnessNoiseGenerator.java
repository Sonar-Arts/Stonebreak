package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.INoiseGenerator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.config.NoiseConfig;

/**
 * Generates "weirdness" noise for unusual terrain formations.
 *
 * This noise type creates plateaus, mesas, and terraced hillsides by
 * quantizing simplex noise into discrete steps. The result is flat-topped
 * formations with steep sides, similar to badlands/mesa biomes.
 *
 * Algorithm:
 * 1. Generate simplex noise in range [-1, 1]
 * 2. Quantize into discrete steps (e.g., 5 levels)
 * 3. Optionally blend with original noise for smoothed edges
 * 4. Result: terraced, plateau-like terrain
 *
 * Use Cases:
 * - Badlands mesa plateaus
 * - Stony peaks with flat tops
 * - Red sand desert volcanic plateaus
 * - Ice fields pressure ridges
 *
 * Characteristics:
 * - Creates flat-topped formations
 * - Steep vertical faces between levels
 * - Terraced appearance (step-like)
 * - Best for unusual/dramatic biomes
 *
 * Follows Single Responsibility Principle - only handles weirdness noise generation.
 * Follows Dependency Inversion Principle - depends on INoiseGenerator abstraction.
 *
 * Implements INoiseGenerator for interoperability with terrain generation system.
 */
public class WeirdnessNoiseGenerator implements INoiseGenerator {

    private final NoiseGenerator baseNoise;
    private final int terraceSteps;
    private final float blendFactor;

    /**
     * Creates a new weirdness noise generator with the specified seed and configuration.
     *
     * Uses default 5 terrace steps and 0.3 blend factor for balanced plateaus.
     *
     * @param seed   World seed for deterministic generation
     * @param config Noise configuration (octaves, persistence, lacunarity)
     */
    public WeirdnessNoiseGenerator(long seed, NoiseConfig config) {
        this(seed, config, 5, 0.3f);
    }

    /**
     * Creates a new weirdness noise generator with custom terrace configuration.
     *
     * @param seed          World seed for deterministic generation
     * @param config        Noise configuration (octaves, persistence, lacunarity)
     * @param terraceSteps  Number of discrete terrace levels (3-10 recommended)
     * @param blendFactor   How much to blend with original noise (0.0 = sharp edges, 1.0 = smooth)
     */
    public WeirdnessNoiseGenerator(long seed, NoiseConfig config, int terraceSteps, float blendFactor) {
        this.baseNoise = new NoiseGenerator(seed, config);
        this.terraceSteps = Math.max(3, Math.min(terraceSteps, 10));  // Clamp to [3, 10]
        this.blendFactor = Math.max(0.0f, Math.min(blendFactor, 1.0f));  // Clamp to [0, 1]
    }

    /**
     * Generates weirdness noise value at the specified coordinates.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return Weirdness noise value in range [-1.0, 1.0]
     */
    @Override
    public float noise(float x, float z) {
        // 1. Get base simplex noise [-1, 1]
        float simplex = baseNoise.noise(x, z);

        // 2. Quantize into discrete terrace steps
        // Map [-1, 1] to [0, steps-1], round, then map back
        float normalized = (simplex + 1.0f) / 2.0f;  // Map to [0, 1]
        float stepped = Math.round(normalized * (terraceSteps - 1)) / (float) (terraceSteps - 1);
        float terraced = stepped * 2.0f - 1.0f;  // Map back to [-1, 1]

        // 3. Blend with original noise for smoothed edges
        // blendFactor = 0.0 → pure terraced (sharp edges)
        // blendFactor = 1.0 → pure simplex (smooth, no terraces)
        return simplex * blendFactor + terraced * (1.0f - blendFactor);
    }

    /**
     * Gets weirdness noise with custom step count.
     *
     * Higher step counts create more gradual terraces.
     * Lower step counts create fewer, more dramatic plateaus.
     *
     * @param x     X coordinate in noise space
     * @param z     Z coordinate in noise space
     * @param steps Number of terrace levels (overrides constructor value)
     * @return Weirdness noise value in range [-1.0, 1.0]
     */
    public float noiseWithSteps(float x, float z, int steps) {
        float simplex = baseNoise.noise(x, z);

        float normalized = (simplex + 1.0f) / 2.0f;
        float stepped = Math.round(normalized * (steps - 1)) / (float) (steps - 1);
        float terraced = stepped * 2.0f - 1.0f;

        return simplex * blendFactor + terraced * (1.0f - blendFactor);
    }

    /**
     * Gets pure terraced noise without blending (sharp edges).
     *
     * Useful for dramatic badlands/mesa formations with vertical cliffs.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return Pure terraced noise value in range [-1.0, 1.0]
     */
    public float pureTerraced(float x, float z) {
        float simplex = baseNoise.noise(x, z);

        float normalized = (simplex + 1.0f) / 2.0f;
        float stepped = Math.round(normalized * (terraceSteps - 1)) / (float) (terraceSteps - 1);

        return stepped * 2.0f - 1.0f;
    }

    /**
     * Gets mesa-style noise with flatter tops and steeper sides.
     *
     * Uses a power function to create more pronounced flat areas at terrace levels.
     *
     * @param x X coordinate in noise space
     * @param z Z coordinate in noise space
     * @return Mesa-style noise value in range [-1.0, 1.0]
     */
    public float mesaStyle(float x, float z) {
        float simplex = baseNoise.noise(x, z);

        // Apply power function to create flatter tops
        float abs = Math.abs(simplex);
        float powered = (float) Math.pow(abs, 0.7);  // Exponent < 1 flattens curves

        // Quantize the powered value
        float sign = simplex >= 0 ? 1.0f : -1.0f;
        float normalized = powered;  // Already in [0, 1]
        float stepped = Math.round(normalized * (terraceSteps - 1)) / (float) (terraceSteps - 1);

        return stepped * sign;
    }
}
