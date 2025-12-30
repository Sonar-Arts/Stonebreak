package com.stonebreak.world.structure;

import org.joml.Vector3f;

/**
 * Represents the result of a structure search operation.
 *
 * <p>This record encapsulates information about a found structure, including
 * its type, position, and distance from the search origin.</p>
 *
 * <p>Example usage:
 * <pre>
 * Optional&lt;StructureSearchResult&gt; result = structureFinder.findNearest(
 *     StructureType.LAKE,
 *     playerPosition,
 *     new StructureSearchConfig(8192)
 * );
 *
 * if (result.isPresent()) {
 *     Vector3f pos = result.get().position();
 *     System.out.printf("Found lake at (%.0f, %.0f, %.0f)", pos.x, pos.y, pos.z);
 * }
 * </pre>
 * </p>
 *
 * @param type The type of structure found
 * @param position The world position of the structure (x, y, z)
 * @param distance The distance in blocks from the search origin
 */
public record StructureSearchResult(
    StructureType type,
    Vector3f position,
    float distance
) {
    /**
     * Creates a structure search result.
     *
     * @param type The type of structure found (must not be null)
     * @param position The position of the structure (must not be null)
     * @param distance The distance from search origin (must be >= 0)
     * @throws IllegalArgumentException if type or position is null, or distance is negative
     */
    public StructureSearchResult {
        if (type == null) {
            throw new IllegalArgumentException("Structure type cannot be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }
        if (distance < 0) {
            throw new IllegalArgumentException("Distance cannot be negative: " + distance);
        }
    }

    /**
     * Returns a formatted string representation of this result.
     *
     * @return Formatted string: "LAKE at (100.0, 70.0, -200.0), 250.5 blocks away"
     */
    @Override
    public String toString() {
        return String.format("%s at (%.0f, %.0f, %.0f), %.1f blocks away",
            type, position.x, position.y, position.z, distance);
    }
}
