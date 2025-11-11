package com.stonebreak.world.generation;

import com.stonebreak.world.generation.noise.MultiNoiseParameters;

/**
 * Interface for terrain height generation systems.
 * <p>
 * This abstraction allows for multiple terrain generation algorithms to coexist
 * in the same codebase, enabling users to choose between different generation
 * styles (legacy multi-noise, spline-based, amplified, flat, etc.).
 * <p>
 * Each implementation is responsible for converting 6D multi-noise parameters
 * (continentalness, erosion, peaks & valleys, weirdness, temperature, humidity)
 * into a final terrain height value.
 *
 * @see com.stonebreak.world.generation.legacy.LegacyTerrainGenerator
 * @see com.stonebreak.world.generation.spline.SplineTerrainGenerator
 */
public interface TerrainGenerator {

    /**
     * Generate terrain height for a specific world position given multi-noise parameters.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param params The 6D noise parameters sampled at this position
     *               (continentalness, erosion, peaksValleys, weirdness, temperature, humidity)
     * @return Terrain height in blocks (Y coordinate)
     */
    int generateHeight(int x, int z, MultiNoiseParameters params);

    /**
     * Get the name/identifier for this terrain generator.
     *
     * @return Human-readable name (e.g., "Legacy Multi-Noise Generator")
     */
    String getName();

    /**
     * Get a human-readable description of this terrain generator.
     *
     * @return Description explaining the generation algorithm and characteristics
     */
    String getDescription();

    /**
     * Get the world seed used by this terrain generator.
     *
     * @return World seed (used for deterministic generation)
     */
    long getSeed();

    /**
     * Get the terrain generator type.
     *
     * @return The terrain generator type (LEGACY, SPLINE, etc.)
     */
    TerrainGeneratorType getType();

    /**
     * Get comprehensive height calculation debug information for F3 visualization.
     * Returns generator-specific details about how height was calculated.
     *
     * @param x World X coordinate
     * @param z World Z coordinate
     * @param params The 6D noise parameters sampled at this position
     * @return Generator-specific debug information (LegacyDebugInfo or SplineDebugInfo)
     */
    com.stonebreak.world.generation.debug.HeightCalculationDebugInfo getHeightCalculationDebugInfo(
            int x, int z, MultiNoiseParameters params);
}
