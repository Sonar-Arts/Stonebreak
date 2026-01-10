package com.openmason.main.systems.rendering.model.gmr;

/**
 * Interface for triangle-to-original-face mapping.
 * Tracks which original face each triangle belongs to.
 * Preserves face IDs through subdivision operations.
 *
 * Supports arbitrary geometry - not locked to cube topology.
 */
public interface ITriangleFaceMapper {

    /**
     * Initialize 1:1 mapping for generic geometry (each triangle is its own face).
     * This is the default and treats all geometry uniformly without assumptions.
     *
     * @param triangleCount Total number of triangles
     */
    void initializeStandardMapping(int triangleCount);

    /**
     * Initialize mapping with explicit topology information (opt-in).
     * Only use when you explicitly know the face structure and want custom grouping.
     *
     * @param triangleCount Total number of triangles
     * @param trianglesPerFace Number of triangles per face (e.g., 2 for quads, 1 for triangles)
     */
    void initializeWithTopology(int triangleCount, int trianglesPerFace);

    /**
     * Set the face mapping directly.
     *
     * @param triangleToFaceId Array mapping triangle index to original face ID
     */
    void setMapping(int[] triangleToFaceId);

    /**
     * Get the original face ID for a given triangle index.
     *
     * @param triangleIndex The triangle index (0-based)
     * @return The original face ID, or -1 if invalid
     */
    int getOriginalFaceIdForTriangle(int triangleIndex);

    /**
     * Get the number of original faces (before subdivision).
     * Depends on topology: triangles=N, quads=N/2, mixed=varies.
     *
     * @return The number of original faces
     */
    int getOriginalFaceCount();

    /**
     * Check if face mapping is available.
     *
     * @return true if mapping is available
     */
    boolean hasMapping();

    /**
     * Get the current triangle count.
     *
     * @return Number of triangles
     */
    int getTriangleCount();

    /**
     * Get a copy of the current face mapping array.
     *
     * @return Copy of triangle-to-face mapping, or null if none
     */
    int[] getMappingCopy();

    /**
     * Clear the face mapping.
     */
    void clear();
}
