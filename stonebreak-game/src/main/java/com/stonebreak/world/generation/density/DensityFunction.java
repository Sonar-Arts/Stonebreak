package com.stonebreak.world.generation.density;

/**
 * Density function interface for 3D terrain generation
 *
 * A density function returns a value at any 3D coordinate.
 * Terrain is generated where density > threshold (usually 0).
 *
 * Examples:
 * - density > 0 = solid block
 * - density <= 0 = air/cave
 */
public interface DensityFunction {

    /**
     * Sample density at given 3D coordinate
     *
     * @param x World X coordinate
     * @param y World Y coordinate (height)
     * @param z World Z coordinate
     * @return Density value (positive = solid, negative = air)
     */
    float sample(int x, int y, int z);

    /**
     * Sample density with noise parameters for context
     */
    default float sample(int x, int y, int z, com.stonebreak.world.generation.noise.MultiNoiseParameters params) {
        return sample(x, y, z);
    }
}
