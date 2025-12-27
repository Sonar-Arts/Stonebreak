package com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single Responsibility: Subdivides an edge at its midpoint.
 * This class encapsulates the logic for creating a new vertex at the midpoint
 * of an edge and replacing the original edge with two new edges.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles edge subdivision logic
 * - Open/Closed: Can be extended for different subdivision strategies
 * - Liskov Substitution: Could implement IEdgeOperation if needed
 * - Interface Segregation: Focused interface for subdivision operations
 * - Dependency Inversion: Depends on abstractions (arrays) not concrete implementations
 *
 * KISS Principle: Straightforward subdivision with clear algorithm.
 * DRY Principle: Reuses existing data structures and patterns.
 * YAGNI Principle: Implements only what's needed for midpoint subdivision.
 *
 * Thread Safety: This class is stateless and thread-safe.
 * All data is passed as parameters and no state is maintained.
 */
public class MeshEdgeSubdivider {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeSubdivider.class);

    /** Number of floats per edge position (2 endpoints x 3 coordinates). */
    private static final int FLOATS_PER_EDGE = 6;

    /** Number of floats per vertex position (x, y, z). */
    private static final int FLOATS_PER_VERTEX = 3;

    /**
     * Result of a subdivision operation.
     * Immutable value object containing all updated data structures.
     */
    public static class SubdivisionResult {
        private final float[] newVertexPositions;
        private final int newVertexCount;
        private final float[] newEdgePositions;
        private final int newEdgeCount;
        private final int[][] newEdgeToVertexMapping;
        private final int newVertexIndex;
        private final boolean successful;

        public SubdivisionResult(float[] newVertexPositions, int newVertexCount,
                                 float[] newEdgePositions, int newEdgeCount,
                                 int[][] newEdgeToVertexMapping, int newVertexIndex,
                                 boolean successful) {
            this.newVertexPositions = newVertexPositions;
            this.newVertexCount = newVertexCount;
            this.newEdgePositions = newEdgePositions;
            this.newEdgeCount = newEdgeCount;
            this.newEdgeToVertexMapping = newEdgeToVertexMapping;
            this.newVertexIndex = newVertexIndex;
            this.successful = successful;
        }

        public float[] getNewVertexPositions() {
            return newVertexPositions;
        }

        public int getNewVertexCount() {
            return newVertexCount;
        }

        public float[] getNewEdgePositions() {
            return newEdgePositions;
        }

        public int getNewEdgeCount() {
            return newEdgeCount;
        }

        public int[][] getNewEdgeToVertexMapping() {
            return newEdgeToVertexMapping;
        }

        public int getNewVertexIndex() {
            return newVertexIndex;
        }

        public boolean isSuccessful() {
            return successful;
        }
    }

    /**
     * Subdivide an edge at its midpoint.
     * Creates a new vertex V3 at the midpoint and replaces the original edge
     * V1 <-> V2 with two new edges: V1 <-> V3 and V3 <-> V2.
     *
     * @param edgeIndex Index of edge to subdivide
     * @param edgePositions Current edge positions array [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeCount Current number of edges
     * @param vertexPositions Current unique vertex positions array [x,y,z, ...]
     * @param vertexCount Current number of unique vertices
     * @param edgeToVertexMapping Current edge-to-vertex mapping [edgeIdx][0=v1, 1=v2]
     * @return SubdivisionResult with updated data, or null if subdivision failed
     */
    public SubdivisionResult subdivide(int edgeIndex, float[] edgePositions, int edgeCount,
                                       float[] vertexPositions, int vertexCount,
                                       int[][] edgeToVertexMapping) {
        // 1. Validate inputs
        if (!validateInputs(edgeIndex, edgePositions, edgeCount, vertexPositions,
                           vertexCount, edgeToVertexMapping)) {
            return null;
        }

        // 2. Get edge vertex indices from mapping
        int v1Index = edgeToVertexMapping[edgeIndex][0];
        int v2Index = edgeToVertexMapping[edgeIndex][1];

        logger.debug("Subdividing edge {} connecting vertices {} and {}", edgeIndex, v1Index, v2Index);

        // 3. Calculate midpoint position V3 = (V1 + V2) / 2
        Vector3f midpoint = calculateMidpoint(edgeIndex, edgePositions);
        if (midpoint == null) {
            logger.warn("Failed to calculate midpoint for edge {}", edgeIndex);
            return null;
        }

        // 4. Create new vertex array with V3 appended
        int newVertexCount = vertexCount + 1;
        float[] newVertexPositions = new float[newVertexCount * FLOATS_PER_VERTEX];
        System.arraycopy(vertexPositions, 0, newVertexPositions, 0, vertexPositions.length);

        int v3Index = vertexCount;  // New vertex index (appended at end)
        int v3PosOffset = v3Index * FLOATS_PER_VERTEX;
        newVertexPositions[v3PosOffset] = midpoint.x;
        newVertexPositions[v3PosOffset + 1] = midpoint.y;
        newVertexPositions[v3PosOffset + 2] = midpoint.z;

        logger.debug("Created new vertex {} at position ({}, {}, {})",
                    v3Index, midpoint.x, midpoint.y, midpoint.z);

        // 5. Create new edge array:
        //    - Remove original edge at edgeIndex
        //    - Add edge V1 <-> V3
        //    - Add edge V3 <-> V2
        //    Net change: +1 edge (remove 1, add 2)
        int newEdgeCount = edgeCount + 1;
        float[] newEdgePositions = new float[newEdgeCount * FLOATS_PER_EDGE];
        int[][] newMapping = new int[newEdgeCount][2];

        // Get V1 and V2 positions from vertex positions array
        Vector3f v1Pos = new Vector3f(
            vertexPositions[v1Index * FLOATS_PER_VERTEX],
            vertexPositions[v1Index * FLOATS_PER_VERTEX + 1],
            vertexPositions[v1Index * FLOATS_PER_VERTEX + 2]
        );
        Vector3f v2Pos = new Vector3f(
            vertexPositions[v2Index * FLOATS_PER_VERTEX],
            vertexPositions[v2Index * FLOATS_PER_VERTEX + 1],
            vertexPositions[v2Index * FLOATS_PER_VERTEX + 2]
        );

        int newEdgeIdx = 0;
        for (int i = 0; i < edgeCount; i++) {
            if (i == edgeIndex) {
                // Replace original edge with two new edges

                // Edge 1: V1 <-> V3
                int offset1 = newEdgeIdx * FLOATS_PER_EDGE;
                newEdgePositions[offset1] = v1Pos.x;
                newEdgePositions[offset1 + 1] = v1Pos.y;
                newEdgePositions[offset1 + 2] = v1Pos.z;
                newEdgePositions[offset1 + 3] = midpoint.x;
                newEdgePositions[offset1 + 4] = midpoint.y;
                newEdgePositions[offset1 + 5] = midpoint.z;
                newMapping[newEdgeIdx][0] = v1Index;
                newMapping[newEdgeIdx][1] = v3Index;
                newEdgeIdx++;

                // Edge 2: V3 <-> V2
                int offset2 = newEdgeIdx * FLOATS_PER_EDGE;
                newEdgePositions[offset2] = midpoint.x;
                newEdgePositions[offset2 + 1] = midpoint.y;
                newEdgePositions[offset2 + 2] = midpoint.z;
                newEdgePositions[offset2 + 3] = v2Pos.x;
                newEdgePositions[offset2 + 4] = v2Pos.y;
                newEdgePositions[offset2 + 5] = v2Pos.z;
                newMapping[newEdgeIdx][0] = v3Index;
                newMapping[newEdgeIdx][1] = v2Index;
                newEdgeIdx++;

                logger.debug("Replaced edge {} with edges {} (V{}<->V{}) and {} (V{}<->V{})",
                            i, newEdgeIdx - 2, v1Index, v3Index, newEdgeIdx - 1, v3Index, v2Index);
            } else {
                // Copy existing edge unchanged
                int srcOffset = i * FLOATS_PER_EDGE;
                int dstOffset = newEdgeIdx * FLOATS_PER_EDGE;
                System.arraycopy(edgePositions, srcOffset, newEdgePositions, dstOffset, FLOATS_PER_EDGE);
                newMapping[newEdgeIdx][0] = edgeToVertexMapping[i][0];
                newMapping[newEdgeIdx][1] = edgeToVertexMapping[i][1];
                newEdgeIdx++;
            }
        }

        logger.info("Edge subdivision complete: {} vertices -> {}, {} edges -> {}",
                   vertexCount, newVertexCount, edgeCount, newEdgeCount);

        return new SubdivisionResult(
            newVertexPositions, newVertexCount,
            newEdgePositions, newEdgeCount,
            newMapping, v3Index, true
        );
    }

    /**
     * Calculate the midpoint of an edge.
     *
     * @param edgeIndex Index of edge
     * @param edgePositions Edge positions array [x1,y1,z1, x2,y2,z2, ...]
     * @return Midpoint position, or null if invalid
     */
    public Vector3f calculateMidpoint(int edgeIndex, float[] edgePositions) {
        if (edgePositions == null || edgeIndex < 0) {
            return null;
        }

        int offset = edgeIndex * FLOATS_PER_EDGE;
        if (offset + FLOATS_PER_EDGE > edgePositions.length) {
            return null;
        }

        float x1 = edgePositions[offset];
        float y1 = edgePositions[offset + 1];
        float z1 = edgePositions[offset + 2];
        float x2 = edgePositions[offset + 3];
        float y2 = edgePositions[offset + 4];
        float z2 = edgePositions[offset + 5];

        return new Vector3f(
            (x1 + x2) / 2.0f,
            (y1 + y2) / 2.0f,
            (z1 + z2) / 2.0f
        );
    }

    /**
     * Validate all inputs for subdivision operation.
     */
    private boolean validateInputs(int edgeIndex, float[] edgePositions, int edgeCount,
                                   float[] vertexPositions, int vertexCount,
                                   int[][] edgeToVertexMapping) {
        if (edgeIndex < 0 || edgeIndex >= edgeCount) {
            logger.warn("Invalid edge index: {} (count: {})", edgeIndex, edgeCount);
            return false;
        }

        if (edgePositions == null || edgePositions.length < edgeCount * FLOATS_PER_EDGE) {
            logger.warn("Invalid edge positions array");
            return false;
        }

        if (vertexPositions == null || vertexPositions.length < vertexCount * FLOATS_PER_VERTEX) {
            logger.warn("Invalid vertex positions array");
            return false;
        }

        if (edgeToVertexMapping == null || edgeToVertexMapping.length < edgeCount) {
            logger.warn("Invalid edge-to-vertex mapping");
            return false;
        }

        if (edgeToVertexMapping[edgeIndex] == null || edgeToVertexMapping[edgeIndex].length < 2) {
            logger.warn("Invalid mapping for edge {}", edgeIndex);
            return false;
        }

        int v1Index = edgeToVertexMapping[edgeIndex][0];
        int v2Index = edgeToVertexMapping[edgeIndex][1];

        if (v1Index < 0 || v1Index >= vertexCount || v2Index < 0 || v2Index >= vertexCount) {
            logger.warn("Edge {} references invalid vertices: {} and {}", edgeIndex, v1Index, v2Index);
            return false;
        }

        return true;
    }
}
