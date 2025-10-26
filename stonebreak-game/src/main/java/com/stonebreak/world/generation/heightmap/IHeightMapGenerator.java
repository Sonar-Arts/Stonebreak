package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.biomes.BiomeType;

/**
 * Interface for terrain height map generation.
 *
 * Defines the contract for generating terrain heights based on various noise
 * functions and multi-noise parameters.
 *
 * Multi-Noise System:
 * - Heights generated from continentalness, erosion, PV, and weirdness
 * - Biomes no longer influence terrain height
 * - Terrain-independent generation allows same biome on varied terrain
 *
 * Implementations can use different height generation strategies:
 * - Spline-based continentalness mapping (current implementation)
 * - Direct noise-to-height mapping
 * - Realistic terrain simulation (hydraulic erosion, tectonic simulation)
 * - Heightmap importing from images or data
 *
 * Benefits of this interface:
 * - Dependency Inversion: Terrain systems depend on abstraction, not concrete implementation
 * - Testability: Easy to create deterministic mock height generators for testing
 * - Flexibility: Can swap height generation algorithms without changing generation logic
 * - Modularity: Height calculation isolated from feature generation
 */
public interface IHeightMapGenerator {

    /**
     * Generates base terrain height for the specified world position.
     * This returns the height from continentalness only, without any modifiers.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Base terrain height at the given position (clamped to world bounds)
     */
    int generateHeight(int x, int z);

    /**
     * DEPRECATED: Biome-specific height modification no longer used in multi-noise system.
     *
     * @deprecated Biomes no longer affect terrain height
     */
    @Deprecated
    int applyBiomeModifier(int baseHeight, BiomeType biome, int x, int z);

    /**
     * Gets the continentalness value at the specified world position.
     * Continentalness determines whether terrain is ocean, coast, or land.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Continentalness value in range [-1.0, 1.0]
     */
    float getContinentalness(int x, int z);

    /**
     * Gets the world seed used by this generator.
     *
     * @return World seed
     */
    long getSeed();
}
