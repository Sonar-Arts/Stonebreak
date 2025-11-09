package com.stonebreak.ui.terrainmapper.visualization;

/**
 * Strategy interface for noise parameter visualization in the terrain mapper.
 *
 * Defines the contract for visualizing different noise parameters (continentalness,
 * erosion, etc.), terrain height, and biome distributions. Each implementation samples
 * and normalizes a specific aspect of terrain generation for 2D visualization.
 *
 * Design Pattern: Strategy Pattern
 * - Allows switching between different visualization modes at runtime
 * - Each visualizer handles one parameter or aspect of terrain generation
 * - Implementations are interchangeable through this common interface
 *
 * Follows SOLID Principles:
 * - SRP: Each visualizer handles ONE parameter
 * - OCP: New visualizers can be added without modifying existing code
 * - LSP: All implementations are interchangeable
 * - ISP: Minimal, focused interface
 * - DIP: Consumers depend on this abstraction, not concrete implementations
 *
 * Thread Safety: Implementations should be thread-safe for concurrent sampling
 */
public interface NoiseVisualizer {

    /**
     * Samples the noise value or terrain aspect at given world coordinates.
     *
     * This is the core sampling method that each visualizer implements differently:
     * - Parameter visualizers: Sample noise directly (e.g., continentalness)
     * - Height visualizer: Generate terrain height from multi-noise parameters
     * - Biome visualizer: Determine biome and return biome ID
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param seed World seed for deterministic noise generation
     * @return Raw value (range varies by implementation, see getMinValue/getMaxValue)
     */
    double sample(int worldX, int worldZ, long seed);

    /**
     * Gets the display name for this visualizer shown in the UI.
     *
     * Examples: "Continentalness", "Erosion", "Terrain Height", "Biome Distribution"
     *
     * @return User-friendly name for this visualizer
     */
    String getName();

    /**
     * Gets the minimum possible value returned by sample().
     * Used for normalizing values to [0, 1] for grayscale/color rendering.
     *
     * Examples:
     * - Parameters: -1.0 (noise range)
     * - Temperature/Humidity: 0.0 (already normalized)
     * - Height: 0.0 (sea level minimum)
     * - Biomes: 0.0 (first biome ordinal)
     *
     * @return Minimum value in this visualizer's range
     */
    double getMinValue();

    /**
     * Gets the maximum possible value returned by sample().
     * Used for normalizing values to [0, 1] for grayscale/color rendering.
     *
     * Examples:
     * - Parameters: 1.0 (noise range)
     * - Temperature/Humidity: 1.0 (already normalized)
     * - Height: 256.0 (max world height)
     * - Biomes: 9.0 (last biome ordinal for 10 biomes)
     *
     * @return Maximum value in this visualizer's range
     */
    double getMaxValue();

    /**
     * Helper method to normalize sample values to [0, 1] for rendering.
     * Default implementation uses linear normalization from min/max range.
     *
     * Subclasses can override for custom normalization (e.g., logarithmic).
     *
     * @param rawValue Raw value from sample()
     * @return Normalized value in [0, 1]
     */
    default double normalize(double rawValue) {
        double min = getMinValue();
        double max = getMaxValue();
        if (max == min) {
            return 0.5; // Avoid division by zero, return middle value
        }
        return (rawValue - min) / (max - min);
    }
}
