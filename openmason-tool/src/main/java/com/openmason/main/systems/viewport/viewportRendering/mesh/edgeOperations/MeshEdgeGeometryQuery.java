package com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single Responsibility: Queries geometric data about edges.
 * This class retrieves edge endpoint positions and vertex indices.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles edge geometry queries
 * - Open/Closed: Can be extended for additional query types
 * - Liskov Substitution: Could be abstracted to IEdgeGeometryQuery if needed
 * - Interface Segregation: Focused interface for geometry queries
 * - Dependency Inversion: Depends on abstractions (arrays) not concrete implementations
 *
 * KISS Principle: Simple array indexing and bounds checking.
 * DRY Principle: All edge query logic centralized in one place.
 * YAGNI Principle: Only implements what's needed for edge geometry queries.
 *
 * Thread Safety: This class is stateless and thread-safe.
 * All data is passed as parameters and no state is maintained.
 */
public class MeshEdgeGeometryQuery {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeGeometryQuery.class);

    /** Number of float values per edge (2 endpoints × 3 coordinates). */
    private static final int FLOATS_PER_EDGE = 6;

    /**
     * Gets the endpoint positions of an edge.
     * Extracts the 3D coordinates of both endpoints for the specified edge.
     *
     * Returns a two-element array containing the start and end points of the edge.
     * Each point is a Vector3f with x, y, z coordinates.
     *
     * @param edgeIndex the index of the edge to query (0-based)
     * @param edgePositions array of edge positions in format [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeCount the total number of edges in the array
     * @return array containing [endpoint1, endpoint2], or null if the index is invalid
     *         or the position data is unavailable
     */
    public Vector3f[] getEdgeEndpoints(int edgeIndex, float[] edgePositions, int edgeCount) {
        // Validate edge index
        if (edgeIndex < 0 || edgeIndex >= edgeCount) {
            logger.trace("Invalid edge index: {}, valid range is 0 to {}", edgeIndex, edgeCount - 1);
            return null;
        }

        // Validate edge positions array
        if (edgePositions == null) {
            logger.trace("Edge positions array is null");
            return null;
        }

        // Calculate position index
        int posIndex = edgeIndex * FLOATS_PER_EDGE;

        // Validate array bounds
        if (posIndex + (FLOATS_PER_EDGE - 1) >= edgePositions.length) {
            logger.warn("Edge position index out of bounds: {} >= {}",
                posIndex + (FLOATS_PER_EDGE - 1), edgePositions.length);
            return null;
        }

        // Extract endpoint 1
        Vector3f point1 = new Vector3f(
            edgePositions[posIndex + 0],
            edgePositions[posIndex + 1],
            edgePositions[posIndex + 2]
        );

        // Extract endpoint 2
        Vector3f point2 = new Vector3f(
            edgePositions[posIndex + 3],
            edgePositions[posIndex + 4],
            edgePositions[posIndex + 5]
        );

        return new Vector3f[] { point1, point2 };
    }

    /**
     * Gets the unique vertex indices for a given edge.
     * Returns which unique vertices this edge connects in the model.
     *
     * This is used to identify the vertex endpoints of an edge, which is critical
     * for operations like edge translation that need to update connected vertices.
     * Returns a copy of the indices to prevent external modification of the mapping.
     *
     * @param edgeIndex the edge index to query (0-based)
     * @param edgeToVertexMapping 2D array mapping edge indices to pairs of vertex indices
     * @return array of [vertexIndex1, vertexIndex2], or null if the mapping is unavailable
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

        // Return copy to prevent external modification
        return new int[] {
            edgeToVertexMapping[edgeIndex][0],
            edgeToVertexMapping[edgeIndex][1]
        };
    }
}
