package com.openmason.main.systems.rendering.model.gmr.mapping;

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

    /**
     * Get the upper bound for face ID iteration.
     * This is maxFaceId + 1, suitable for use as a loop bound:
     * {@code for (int faceId = 0; faceId < getFaceIdUpperBound(); faceId++)}
     *
     * Unlike {@link #getOriginalFaceCount()} which counts unique IDs (and thus can't be
     * used as an iteration bound when face IDs have gaps), this method guarantees that
     * all valid face IDs fall within [0, upperBound).
     *
     * Gap face IDs (IDs with no triangles) will return 0 from topology query methods
     * like {@link #getTriangleCountForFace(int)}.
     *
     * @return maxFaceId + 1, or 0 if no mapping
     */
    int getFaceIdUpperBound();

    /**
     * Get the number of triangles that belong to a specific face.
     *
     * @param faceId The face ID to query
     * @return Number of triangles for this face, or 0 if invalid
     */
    int getTriangleCountForFace(int faceId);

    /**
     * Get the number of vertices forming a specific face's polygon.
     * Derived from triangle count: N triangles in a fan = N + 2 vertices.
     *
     * @param faceId The face ID to query
     * @return Number of vertices for this face, or 0 if invalid
     */
    default int getVertexCountForFace(int faceId) {
        int triCount = getTriangleCountForFace(faceId);
        return triCount > 0 ? triCount + 2 : 0;
    }

    /**
     * Get the number of edges forming a specific face's polygon outline.
     * A polygon with N vertices has N edges.
     *
     * @param faceId The face ID to query
     * @return Number of edges for this face, or 0 if invalid
     */
    default int getEdgeCountForFace(int faceId) {
        return getVertexCountForFace(faceId);
    }
}
