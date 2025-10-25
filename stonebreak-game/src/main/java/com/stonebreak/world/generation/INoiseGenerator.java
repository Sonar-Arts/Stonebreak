package com.stonebreak.world.generation;

/**
 * Interface for noise generation in terrain systems.
 *
 * Defines the contract for generating procedural noise values used in
 * terrain generation, biome distribution, and other world generation features.
 *
 * Implementations can use different noise algorithms:
 * - Simplex noise (current implementation)
 * - Perlin noise
 * - Open Simplex noise
 * - Worley noise
 * - Value noise
 *
 * Benefits of this interface:
 * - Dependency Inversion: Higher-level modules depend on abstraction, not concrete implementation
 * - Testability: Easy to create mock noise generators for unit testing
 * - Flexibility: Can swap noise algorithms without changing dependent code
 * - Performance: Can provide optimized implementations for specific use cases
 */
public interface INoiseGenerator {

    /**
     * Generates a 2D noise value at the specified coordinates.
     *
     * @param x X coordinate in noise space (not world coordinates)
     * @param y Y coordinate in noise space (not world coordinates)
     * @return Noise value typically in range [-1.0, 1.0]
     */
    float noise(float x, float y);
}
