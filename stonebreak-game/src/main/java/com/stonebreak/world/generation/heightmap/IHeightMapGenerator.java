package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.biomes.BiomeBlendResult;
import com.stonebreak.world.generation.biomes.BiomeType;

/**
 * Interface for terrain height map generation.
 *
 * Defines the contract for generating terrain heights based on various noise
 * functions, biome modifiers, and blending systems.
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
     * This returns the height from continentalness only, without biome-specific modifications.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @return Base terrain height at the given position (clamped to world bounds)
     */
    int generateHeight(int x, int z);

    /**
     * Applies biome-specific height modification to the base terrain height.
     *
     * Different biomes have different terrain characteristics (gentle plains,
     * flat deserts, rolling hills, jagged mountains, etc.).
     *
     * @param baseHeight The base height from continentalness
     * @param biome      The biome type at this location
     * @param x          World X coordinate
     * @param z          World Z coordinate
     * @return Final height with biome-specific modifications applied (clamped to world bounds)
     */
    int applyBiomeModifier(int baseHeight, BiomeType biome, int x, int z);

    /**
     * Generates blended height using weighted biome influences.
     *
     * Creates smooth terrain transitions between biomes by blending heights from
     * multiple nearby biomes based on their weights. Also applies erosion noise
     * for subtle weathering effects.
     *
     * @param baseHeight  The base height from continentalness
     * @param blendResult The biome blend result with weighted influences
     * @param x           World X coordinate
     * @param z           World Z coordinate
     * @return Blended height from multiple biomes (clamped to world bounds)
     */
    int generateBlendedHeight(int baseHeight, BiomeBlendResult blendResult, int x, int z);

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
