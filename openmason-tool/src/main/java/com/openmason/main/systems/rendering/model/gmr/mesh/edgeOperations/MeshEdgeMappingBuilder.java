package com.openmason.main.systems.rendering.model.gmr.mesh.edgeOperations;

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
 *
 * Shape-Blind Design:
 * This operation is data-driven and operates on edge data provided by GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology and edge connectivity.
 * Edge structure (vertices per edge) is determined by GMR's data model.
 *
 * Data Flow: GMR extracts edge data → MeshManager operations → Mapping construction
 */
public class MeshEdgeMappingBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeMappingBuilder.class);
    private static final int POSITION_COMPONENTS = 3; // x, y, z

    /**
     * Number of floats per edge in GMR's current data format.
     * This represents edge endpoints × 3 coordinates (data-driven from GMR).
     */
    private static final int FLOATS_PER_EDGE = 6;

    /**
     * Number of vertices per edge in GMR's current data format.
     * Edge topology is determined by GMR's data model.
     */
    private static final int VERTICES_PER_EDGE = FLOATS_PER_EDGE / POSITION_COMPONENTS;

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
     * The algorithm matches edge vertices to unique vertices using epsilon-based
     * floating-point comparison for robustness against numerical precision issues.
     *
     * The number of vertices per edge is determined by GMR's data model.
     *
     * @param edgePositions Array of edge positions from GMR [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeCount Number of edges
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @return 2D array mapping edge index to vertex indices [edgeIdx][vertex indices...]
     */
    public int[][] buildMapping(float[] edgePositions, int edgeCount, float[] uniqueVertexPositions) {
        // Validate inputs
        if (!validateInputs(edgePositions, edgeCount, uniqueVertexPositions)) {
            return null;
        }

        int uniqueVertexCount = uniqueVertexPositions.length / POSITION_COMPONENTS;
        int[][] mapping = new int[edgeCount][VERTICES_PER_EDGE];

        // For each edge, find which unique vertices it connects
        for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
            int edgePosIdx = edgeIdx * FLOATS_PER_EDGE;

            // Extract and match all vertices for this edge (based on GMR's data format)
            for (int vertexInEdge = 0; vertexInEdge < VERTICES_PER_EDGE; vertexInEdge++) {
                int posOffset = edgePosIdx + (vertexInEdge * POSITION_COMPONENTS);

                Vector3f edgeVertex = new Vector3f(
                    edgePositions[posOffset + 0],
                    edgePositions[posOffset + 1],
                    edgePositions[posOffset + 2]
                );

                // Find matching unique vertex
                int vertexIndex = findMatchingVertex(edgeVertex, uniqueVertexPositions, uniqueVertexCount);
                mapping[edgeIdx][vertexInEdge] = vertexIndex;

                if (vertexIndex == NO_EDGE_SELECTED) {
                    logger.warn("Edge {} vertex {} has no matching unique vertex",
                        edgeIdx, vertexInEdge);
                }
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
     * Find the unique vertex index that matches the given edge vertex position.
     *
     * @param edgeVertex Edge vertex position from GMR data
     * @param uniqueVertexPositions Array of unique vertex positions
     * @param uniqueVertexCount Number of unique vertices
     * @return Matching vertex index, or -1 if no match found
     */
    private int findMatchingVertex(Vector3f edgeVertex, float[] uniqueVertexPositions, int uniqueVertexCount) {
        for (int vIdx = 0; vIdx < uniqueVertexCount; vIdx++) {
            int vPosIdx = vIdx * POSITION_COMPONENTS;
            Vector3f uniqueVertex = new Vector3f(
                uniqueVertexPositions[vPosIdx + 0],
                uniqueVertexPositions[vPosIdx + 1],
                uniqueVertexPositions[vPosIdx + 2]
            );

            if (edgeVertex.distance(uniqueVertex) < vertexMatchEpsilon) {
                return vIdx;
            }
        }

        return NO_EDGE_SELECTED;
    }
}
