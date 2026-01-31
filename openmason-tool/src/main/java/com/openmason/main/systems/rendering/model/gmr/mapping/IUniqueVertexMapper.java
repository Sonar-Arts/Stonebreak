package com.openmason.main.systems.rendering.model.gmr.mapping;

import org.joml.Vector3f;

/**
 * Interface for index-based unique vertex lookup system.
 * Maps between mesh vertices and unique geometric positions.
 * For a cube: 24 mesh vertices map to 8 unique positions.
 */
public interface IUniqueVertexMapper {

    /**
     * Build the unique vertex mapping from vertex positions.
     * Groups vertices by position to establish unique-to-mesh relationships.
     *
     * @param vertices Vertex positions (x,y,z interleaved)
     */
    void buildMapping(float[] vertices);

    /**
     * Get the position of a unique vertex by index.
     *
     * @param uniqueIndex The unique vertex index (0 to uniqueVertexCount-1)
     * @param vertices Current vertex positions array
     * @return The vertex position, or null if invalid index
     */
    Vector3f getUniqueVertexPosition(int uniqueIndex, float[] vertices);

    /**
     * Get all mesh vertex indices that share a unique geometric position.
     * For a cube corner, this returns 3 mesh indices (one per adjacent face).
     *
     * @param uniqueIndex The unique vertex index
     * @return Array of mesh indices, or empty array if invalid
     */
    int[] getMeshIndicesForUniqueVertex(int uniqueIndex);

    /**
     * Get the unique vertex index for a given mesh vertex.
     * This is the inverse of getMeshIndicesForUniqueVertex.
     *
     * @param meshIndex The mesh vertex index
     * @return The unique vertex index, or -1 if invalid
     */
    int getUniqueIndexForMeshVertex(int meshIndex);

    /**
     * Get all unique vertex positions as an array.
     * Format: [x0, y0, z0, x1, y1, z1, ...]
     *
     * @param vertices Current vertex positions array
     * @return Array of unique vertex positions, or null if none
     */
    float[] getAllUniqueVertexPositions(float[] vertices);

    /**
     * Get the number of unique geometric positions.
     *
     * @return Number of unique vertices
     */
    int getUniqueVertexCount();

    /**
     * Check if mapping has been built.
     *
     * @return true if mapping is available
     */
    boolean hasMapping();

    /**
     * Clear all mapping data.
     */
    void clear();
}
