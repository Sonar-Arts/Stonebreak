package com.openmason.main.systems.rendering.model.gmr;

/**
 * Interface for triangle-to-original-face mapping.
 * Tracks which original face (0-5 for cube) each triangle belongs to.
 * Preserves face IDs through subdivision operations.
 */
public interface ITriangleFaceMapper {

    /**
     * Initialize the face mapping for a standard configuration.
     * For a cube: 12 triangles total (6 faces x 2 triangles each).
     * Triangles 0-1 = face 0, triangles 2-3 = face 1, etc.
     *
     * @param triangleCount Total number of triangles
     */
    void initializeStandardMapping(int triangleCount);

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
     * @return The original face ID (0-5 for cube), or -1 if invalid
     */
    int getOriginalFaceIdForTriangle(int triangleIndex);

    /**
     * Get the number of original faces (before subdivision).
     * For a cube, this is always 6.
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
