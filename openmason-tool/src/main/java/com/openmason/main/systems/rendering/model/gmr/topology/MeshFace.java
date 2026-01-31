package com.openmason.main.systems.rendering.model.gmr.topology;

/**
 * Immutable face entity with vertex and edge connectivity.
 * Represents a polygon with ordered vertex indices and corresponding edge IDs.
 *
 * <p>The {@code vertexIndices} array contains unique vertex indices forming the
 * polygon in winding order. The {@code edgeIds} array contains the edge IDs
 * forming the polygon outline, where {@code edgeIds[i]} connects
 * {@code vertexIndices[i]} to {@code vertexIndices[(i+1) % length]}.
 *
 * @param faceId         Stable face identifier (matches ITriangleFaceMapper face IDs)
 * @param vertexIndices  Unique vertex indices forming the polygon (ordered)
 * @param edgeIds        Edge IDs forming the outline (same length as vertexIndices)
 */
public record MeshFace(
    int faceId,
    int[] vertexIndices,
    int[] edgeIds
) {

    /**
     * Get the number of vertices (and edges) in this polygon.
     *
     * @return Vertex count
     */
    public int vertexCount() {
        return vertexIndices != null ? vertexIndices.length : 0;
    }

    /**
     * Check if a specific unique vertex belongs to this face.
     *
     * @param vertexIndex Unique vertex index to check
     * @return true if this face contains the vertex
     */
    public boolean containsVertex(int vertexIndex) {
        if (vertexIndices == null) {
            return false;
        }
        for (int v : vertexIndices) {
            if (v == vertexIndex) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a specific edge belongs to this face.
     *
     * @param edgeId Edge ID to check
     * @return true if this face contains the edge
     */
    public boolean containsEdge(int edgeId) {
        if (edgeIds == null) {
            return false;
        }
        for (int e : edgeIds) {
            if (e == edgeId) {
                return true;
            }
        }
        return false;
    }
}
