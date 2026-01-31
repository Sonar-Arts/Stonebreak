package com.openmason.main.systems.rendering.api;

/**
 * Immutable holder for geometry data.
 * Encapsulates vertex and index data for GPU upload.
 *
 * @param vertices Array of vertex data (position, color, texCoords, etc.)
 * @param indices Array of indices for indexed drawing (null for non-indexed)
 * @param vertexCount Number of vertices
 * @param indexCount Number of indices (0 for non-indexed)
 * @param stride Bytes per vertex (for interleaved data)
 */
public record GeometryData(
        float[] vertices,
        int[] indices,
        int vertexCount,
        int indexCount,
        int stride
) {
    /**
     * Create geometry data for non-indexed drawing.
     *
     * @param vertices Vertex data array
     * @param vertexCount Number of vertices
     * @param stride Bytes per vertex
     * @return GeometryData for non-indexed rendering
     */
    public static GeometryData nonIndexed(float[] vertices, int vertexCount, int stride) {
        return new GeometryData(vertices, null, vertexCount, 0, stride);
    }

    /**
     * Create geometry data for indexed drawing.
     *
     * @param vertices Vertex data array
     * @param indices Index data array
     * @param vertexCount Number of vertices
     * @param stride Bytes per vertex
     * @return GeometryData for indexed rendering
     */
    public static GeometryData indexed(float[] vertices, int[] indices, int vertexCount, int stride) {
        return new GeometryData(vertices, indices, vertexCount, indices != null ? indices.length : 0, stride);
    }

    /**
     * Check if this geometry uses indexed drawing.
     *
     * @return true if indexed, false otherwise
     */
    public boolean isIndexed() {
        return indices != null && indexCount > 0;
    }

    /**
     * Check if this geometry has valid data.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return vertices != null && vertexCount > 0 && stride > 0;
    }

    /**
     * Calculate total vertex data size in bytes.
     *
     * @return Size in bytes
     */
    public int getVertexDataSize() {
        return vertices != null ? vertices.length * Float.BYTES : 0;
    }

    /**
     * Calculate total index data size in bytes.
     *
     * @return Size in bytes
     */
    public int getIndexDataSize() {
        return indices != null ? indices.length * Integer.BYTES : 0;
    }
}
