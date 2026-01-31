package com.openmason.main.systems.rendering.model.gmr.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds mapping from edge indices to unique vertex indices.
 * Identifies which unique vertices each edge connects by matching edge vertex positions
 * to unique vertex positions using epsilon-based comparison.
 *
 * Shape-Blind Design:
 * Operates on edge data provided by GenericModelRenderer (GMR) without assuming specific topology.
 * GMR is the single source of truth for mesh structure and edge connectivity.
 * All edge structure information (vertices per edge) is derived from the data itself,
 * not from hardcoded assumptions.
 */
public class MeshEdgeMappingBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeMappingBuilder.class);
    private static final int POSITION_COMPONENTS = 3; // x, y, z
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
     * The number of vertices per edge is derived from the actual edge data structure
     * provided by GMR, not from hardcoded assumptions.
     *
     * @param edgePositions Array of edge positions from GMR [x1,y1,z1, x2,y2,z2, ...]
     * @param verticesPerEdge Number of vertices per edge (derived from GMR data model)
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @return 2D array mapping edge index to vertex indices [edgeIdx][vertex indices...]
     */
    public int[][] buildMapping(float[] edgePositions, int verticesPerEdge, float[] uniqueVertexPositions) {
        // Validate inputs
        if (!validateInputs(edgePositions, verticesPerEdge, uniqueVertexPositions)) {
            return null;
        }

        // Derive edge count from data
        int floatsPerEdge = verticesPerEdge * POSITION_COMPONENTS;
        int edgeCount = edgePositions.length / floatsPerEdge;
        int uniqueVertexCount = uniqueVertexPositions.length / POSITION_COMPONENTS;

        int[][] mapping = new int[edgeCount][verticesPerEdge];

        // For each edge, find which unique vertices it connects
        for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
            int edgePosIdx = edgeIdx * floatsPerEdge;

            // Extract and match all vertices for this edge
            for (int vertexInEdge = 0; vertexInEdge < verticesPerEdge; vertexInEdge++) {
                int posOffset = edgePosIdx + (vertexInEdge * POSITION_COMPONENTS);

                Vector3f edgeVertex = new Vector3f(
                    edgePositions[posOffset],
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

        logger.debug("Built edge-to-vertex mapping for {} edges ({} vertices per edge)",
                     edgeCount, verticesPerEdge);
        return mapping;
    }

    /**
     * Validate inputs for mapping construction.
     */
    private boolean validateInputs(float[] edgePositions, int verticesPerEdge, float[] uniqueVertexPositions) {
        if (edgePositions == null || edgePositions.length == 0) {
            logger.warn("Cannot build edge mapping: no edge data");
            return false;
        }

        if (verticesPerEdge <= 0) {
            logger.warn("Cannot build edge mapping: invalid vertices per edge ({})", verticesPerEdge);
            return false;
        }

        int floatsPerEdge = verticesPerEdge * POSITION_COMPONENTS;
        if (edgePositions.length % floatsPerEdge != 0) {
            logger.warn("Cannot build edge mapping: edge positions length {} not divisible by {} (vertices per edge: {})",
                       edgePositions.length, floatsPerEdge, verticesPerEdge);
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
                uniqueVertexPositions[vPosIdx],
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
