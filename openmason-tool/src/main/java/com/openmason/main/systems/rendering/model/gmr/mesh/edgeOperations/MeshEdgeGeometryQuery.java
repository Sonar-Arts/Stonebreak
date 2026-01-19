package com.openmason.main.systems.rendering.model.gmr.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queries geometric data about edges.
 * Retrieves edge vertex positions and vertex indices from mesh data.
 *
 * Shape-Blind Design:
 * Operates on edge data provided by GenericModelRenderer (GMR) without assuming specific topology.
 * GMR is the single source of truth for mesh structure and edge connectivity.
 * All edge structure information (vertices per edge) is derived from the data itself,
 * not from hardcoded assumptions.
 *
 * Thread Safety: This class is stateless and thread-safe.
 * All data is passed as parameters and no state is maintained.
 */
public class MeshEdgeGeometryQuery {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeGeometryQuery.class);
    private static final int FLOATS_PER_POSITION = 3; // x, y, z

    /**
     * Gets the vertex positions of an edge.
     * Extracts the 3D coordinates of all vertices for the specified edge from GMR data.
     *
     * Returns an array containing the vertex positions of the edge.
     * Each point is a Vector3f with x, y, z coordinates.
     * The number and structure of vertices is determined by GMR's data model.
     *
     * @param edgeIndex the index of the edge to query (0-based)
     * @param edgePositions array of edge positions from GMR in format [x1,y1,z1, x2,y2,z2, ...]
     * @param verticesPerEdge number of vertices per edge (derived from GMR data model)
     * @return array containing edge vertices, or null if the index is invalid
     *         or the position data is unavailable
     */
    public Vector3f[] getEdgeVertices(int edgeIndex, float[] edgePositions, int verticesPerEdge) {
        // Validate inputs
        if (edgePositions == null) {
            logger.trace("Edge positions array is null");
            return null;
        }

        if (verticesPerEdge <= 0) {
            logger.trace("Invalid vertices per edge: {}", verticesPerEdge);
            return null;
        }

        // Derive edge count from data
        int floatsPerEdge = verticesPerEdge * FLOATS_PER_POSITION;
        int edgeCount = edgePositions.length / floatsPerEdge;

        // Validate edge index
        if (edgeIndex < 0 || edgeIndex >= edgeCount) {
            logger.trace("Invalid edge index: {}, valid range is 0 to {}", edgeIndex, edgeCount - 1);
            return null;
        }

        // Calculate position index
        int posIndex = edgeIndex * floatsPerEdge;

        // Validate array bounds
        if (posIndex + (floatsPerEdge - 1) >= edgePositions.length) {
            logger.warn("Edge position index out of bounds: {} >= {}",
                posIndex + (floatsPerEdge - 1), edgePositions.length);
            return null;
        }

        // Extract all vertices for this edge
        Vector3f[] vertices = new Vector3f[verticesPerEdge];
        for (int i = 0; i < verticesPerEdge; i++) {
            int offset = posIndex + (i * FLOATS_PER_POSITION);
            vertices[i] = new Vector3f(
                edgePositions[offset],
                edgePositions[offset + 1],
                edgePositions[offset + 2]
            );
        }

        return vertices;
    }

    /**
     * Gets the unique vertex indices for a given edge.
     * Returns which unique vertices this edge connects in the model.
     *
     * This is used to identify the vertex endpoints of an edge, which is critical
     * for operations like edge translation that need to update connected vertices.
     * Returns a copy of the indices to prevent external modification of the mapping.
     *
     * The number of vertices per edge is determined by GMR's data model and may vary
     * based on the mesh topology.
     *
     * @param edgeIndex the edge index to query (0-based)
     * @param edgeToVertexMapping 2D array mapping edge indices to vertex indices arrays
     * @return array of vertex indices for this edge, or null if the mapping is unavailable
     *         or the edge index is invalid
     */
    public int[] getEdgeVertexIndices(int edgeIndex, int[][] edgeToVertexMapping) {
        // Validate mapping array
        if (edgeToVertexMapping == null) {
            logger.trace("Edge-to-vertex mapping is null");
            return null;
        }

        // Validate edge index
        if (edgeIndex < 0 || edgeIndex >= edgeToVertexMapping.length) {
            logger.trace("Invalid edge index: {}, valid range is 0 to {}",
                edgeIndex, edgeToVertexMapping.length - 1);
            return null;
        }

        // Get the vertex indices for this edge
        int[] edgeVertices = edgeToVertexMapping[edgeIndex];
        if (edgeVertices == null) {
            logger.trace("No vertex mapping found for edge {}", edgeIndex);
            return null;
        }

        // Return copy to prevent external modification
        int[] copy = new int[edgeVertices.length];
        System.arraycopy(edgeVertices, 0, copy, 0, edgeVertices.length);
        return copy;
    }
}
