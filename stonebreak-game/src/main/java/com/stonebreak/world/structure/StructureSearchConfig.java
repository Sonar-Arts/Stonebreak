package com.stonebreak.world.structure;

/**
 * Configuration parameters for structure search operations.
 *
 * <p>This record defines the constraints and parameters used when searching
 * for structures in the world.</p>
 *
 * <p>Example usage:
 * <pre>
 * // Use default radius (8192 blocks)
 * StructureSearchConfig config = new StructureSearchConfig();
 *
 * // Custom radius
 * StructureSearchConfig config = new StructureSearchConfig(4096);
 * </pre>
 * </p>
 *
 * @param searchRadius Maximum search radius in blocks from the origin point
 */
public record StructureSearchConfig(int searchRadius) {

    /**
     * Default search radius in blocks.
     * This covers an area of approximately 512x512 chunks.
     */
    public static final int DEFAULT_RADIUS = 8192;

    /**
     * Minimum allowed search radius in blocks.
     */
    public static final int MIN_RADIUS = 1;

    /**
     * Maximum allowed search radius in blocks.
     * Larger radii may cause performance issues.
     */
    public static final int MAX_RADIUS = 16384;

    /**
     * Creates a structure search configuration with default radius.
     */
    public StructureSearchConfig() {
        this(DEFAULT_RADIUS);
    }

    /**
     * Creates a structure search configuration with the specified radius.
     *
     * @param searchRadius Maximum search radius in blocks
     * @throws IllegalArgumentException if radius is outside valid range [1, 16384]
     */
    public StructureSearchConfig {
        if (searchRadius < MIN_RADIUS || searchRadius > MAX_RADIUS) {
            throw new IllegalArgumentException(
                String.format("Search radius must be between %d and %d blocks, got: %d",
                    MIN_RADIUS, MAX_RADIUS, searchRadius)
            );
        }
    }

    /**
     * Returns a formatted string representation of this configuration.
     *
     * @return Formatted string: "StructureSearchConfig{radius=8192 blocks}"
     */
    @Override
    public String toString() {
        return String.format("StructureSearchConfig{radius=%d blocks}", searchRadius);
    }
}
