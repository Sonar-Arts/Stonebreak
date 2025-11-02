package com.openmason.ui.components.textureCreator.filters.noise;

/**
 * Interface for noise generation algorithms.
 * Implementations generate noise values in the range [0, 1].
 */
public interface NoiseGenerator {

    /**
     * Generate a noise value at the given coordinates.
     *
     * @param x X coordinate (can be fractional for continuous noise)
     * @param y Y coordinate (can be fractional for continuous noise)
     * @return Noise value in the range [0, 1]
     */
    float generate(float x, float y);

    /**
     * @return Human-readable name of the noise algorithm
     */
    String getName();
}
