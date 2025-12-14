package com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single Responsibility: Builds mapping from edge indices to unique vertex indices.
 * This class encapsulates the logic of identifying which unique vertices each edge connects to.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles edge-to-vertex mapping construction
 * - Open/Closed: Can be extended for different mapping strategies
 * - Liskov Substitution: Could be abstracted to IEdgeMappingBuilder if needed
 * - Interface Segregation: Focused interface for mapping construction
 * - Dependency Inversion: Depends on abstractions (arrays) not concrete implementations
 *
 * KISS Principle: Simple position-matching algorithm using epsilon comparison.
 * DRY Principle: All edge mapping logic centralized in one place.
 * YAGNI Principle: Only implements what's needed for edge-to-vertex mapping.
 */
public class MeshEdgeMappingBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeMappingBuilder.class);
    private static final int POSITION_COMPONENTS = 3; // x, y, z
    private static final int FLOATS_PER_EDGE = 6; // 2 endpoints × 3 coordinates
    private static final int MIN_VERTEX_ARRAY_LENGTH = 3; // Minimum one vertex
    private static final int NO_EDGE_SELECTED = -1;

    private final float vertexMatchEpsilon;

    /**
     * Create an edge mesh mapping builder.
     *
     * @param vertexMatchEpsilon Distance threshold for considering vertices matching
     */
    public MeshEdgeMappingBuilder(float vertexMatchEpsilon) {
        this.vertexMatchEpsilon = vertexMatchEpsilon;
    }

    /**
     * Build edge-to-vertex mapping from unique vertex positions.
     * Creates a mapping that identifies which unique vertices each edge connects.
     *
     * The algorithm matches edge endpoints to unique vertices using epsilon-based
     * floating-point comparison for robustness against numerical precision issues.
     *
     * @param edgePositions Array of edge positions [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeCount Number of edges
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @return 2D array mapping edge index to vertex indices [edgeIdx][0=v1, 1=v2]
     */
    public int[][] buildMapping(float[] edgePositions, int edgeCount, float[] uniqueVertexPositions) {
        // Validate inputs
        if (!validateInputs(edgePositions, edgeCount, uniqueVertexPositions)) {
            return null;
        }

        int uniqueVertexCount = uniqueVertexPositions.length / POSITION_COMPONENTS;
        int[][] mapping = new int[edgeCount][2];

        // For each edge, find which unique vertices it connects
        for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
            int edgePosIdx = edgeIdx * FLOATS_PER_EDGE;

            // Edge endpoint 1
            Vector3f endpoint1 = new Vector3f(
                edgePositions[edgePosIdx + 0],
                edgePositions[edgePosIdx + 1],
                edgePositions[edgePosIdx + 2]
            );

            // Edge endpoint 2
            Vector3f endpoint2 = new Vector3f(
                edgePositions[edgePosIdx + 3],
                edgePositions[edgePosIdx + 4],
                edgePositions[edgePosIdx + 5]
            );

            // Find matching unique vertices
            int vertexIndex1 = findMatchingVertex(endpoint1, uniqueVertexPositions, uniqueVertexCount);
            int vertexIndex2 = findMatchingVertex(endpoint2, uniqueVertexPositions, uniqueVertexCount);

            mapping[edgeIdx][0] = vertexIndex1;
            mapping[edgeIdx][1] = vertexIndex2;

            if (vertexIndex1 == NO_EDGE_SELECTED || vertexIndex2 == NO_EDGE_SELECTED) {
                logger.warn("Edge {} has unmatched endpoints: v1={}, v2={}",
                    edgeIdx, vertexIndex1, vertexIndex2);
            }
        }

        logger.debug("Built edge-to-vertex mapping for {} edges", edgeCount);
        return mapping;
    }

    /**
     * Validate inputs for mapping construction.
     */
    private boolean validateInputs(float[] edgePositions, int edgeCount, float[] uniqueVertexPositions) {
        if (edgePositions == null || edgeCount == 0) {
            logger.warn("Cannot build edge mapping: no edge data");
            return false;
        }

        if (uniqueVertexPositions == null || uniqueVertexPositions.length < MIN_VERTEX_ARRAY_LENGTH) {
            logger.warn("Cannot build edge mapping: invalid unique vertex data");
            return false;
        }

        return true;
    }

    /**
     * Find the unique vertex index that matches the given endpoint position.
     *
     * @param endpoint Edge endpoint position
     * @param uniqueVertexPositions Array of unique vertex positions
     * @param uniqueVertexCount Number of unique vertices
     * @return Matching vertex index, or -1 if no match found
     */
    private int findMatchingVertex(Vector3f endpoint, float[] uniqueVertexPositions, int uniqueVertexCount) {
        for (int vIdx = 0; vIdx < uniqueVertexCount; vIdx++) {
            int vPosIdx = vIdx * POSITION_COMPONENTS;
            Vector3f uniqueVertex = new Vector3f(
                uniqueVertexPositions[vPosIdx + 0],
                uniqueVertexPositions[vPosIdx + 1],
                uniqueVertexPositions[vPosIdx + 2]
            );

            if (endpoint.distance(uniqueVertex) < vertexMatchEpsilon) {
                return vIdx;
            }
        }

        return NO_EDGE_SELECTED;
    }
}
