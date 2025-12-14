package com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Single Responsibility: Handles remapping of edge-to-vertex indices after vertex merging operations.
 * This class updates edge-to-vertex mappings when vertex indices change.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles edge index remapping
 * - Open/Closed: Can be extended for different remapping strategies
 * - Liskov Substitution: Could be abstracted to IEdgeRemapper if needed
 * - Interface Segregation: Focused interface for remapping operations
 * - Dependency Inversion: Depends on abstractions (maps, arrays) not concrete implementations
 *
 * KISS Principle: Simple mapping lookup and replacement algorithm.
 * DRY Principle: All remapping logic centralized in one place.
 * YAGNI Principle: Only implements what's needed for edge index remapping.
 */
public class MeshEdgeVertexRemapper {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeVertexRemapper.class);

    private static final int INVALID_VERTEX_INDEX = -1;
    private static final int VERTEX_1_INDEX = 0;
    private static final int VERTEX_2_INDEX = 1;

    /**
     * Result of remapping operation.
     * Immutable value object containing remapping statistics.
     */
    public static class RemapResult {
        private final int totalEdges;
        private final int remappedEdges;
        private final int skippedEdges;

        public RemapResult(int totalEdges, int remappedEdges, int skippedEdges) {
            this.totalEdges = totalEdges;
            this.remappedEdges = remappedEdges;
            this.skippedEdges = skippedEdges;
        }

        public int getTotalEdges() {
            return totalEdges;
        }

        public int getRemappedEdges() {
            return remappedEdges;
        }

        public int getSkippedEdges() {
            return skippedEdges;
        }

        public boolean isSuccessful() {
            return remappedEdges > 0;
        }
    }

    /**
     * Remap edge vertex indices using the provided index mapping.
     * Updates the edge-to-vertex mapping array in-place with new vertex indices.
     *
     * @param edgeToVertexMapping 2D array mapping edge index to vertex indices [edgeIdx][0=v1, 1=v2]
     * @param edgeCount Total number of edges
     * @param oldToNewIndexMap Mapping from old vertex indices to new vertex indices
     * @return RemapResult containing statistics about the remapping operation, or null if input invalid
     */
    public RemapResult remapIndices(int[][] edgeToVertexMapping, int edgeCount, Map<Integer, Integer> oldToNewIndexMap) {
        // Validate inputs
        if (!validateInputs(edgeToVertexMapping, edgeCount, oldToNewIndexMap)) {
            return null;
        }

        int remappedEdges = 0;
        int skippedEdges = 0;

        // Process each edge
        for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
            int oldVertexIndex1 = edgeToVertexMapping[edgeIdx][VERTEX_1_INDEX];
            int oldVertexIndex2 = edgeToVertexMapping[edgeIdx][VERTEX_2_INDEX];

            // Skip edges with invalid indices
            if (isInvalidIndex(oldVertexIndex1, oldVertexIndex2)) {
                skippedEdges++;
                continue;
            }

            // Attempt to remap both vertices
            Integer newVertexIndex1 = oldToNewIndexMap.get(oldVertexIndex1);
            Integer newVertexIndex2 = oldToNewIndexMap.get(oldVertexIndex2);

            if (canRemap(newVertexIndex1, newVertexIndex2)) {
                // Update mapping with new indices
                edgeToVertexMapping[edgeIdx][VERTEX_1_INDEX] = newVertexIndex1;
                edgeToVertexMapping[edgeIdx][VERTEX_2_INDEX] = newVertexIndex2;
                remappedEdges++;

                logRemappedEdge(edgeIdx, oldVertexIndex1, oldVertexIndex2, newVertexIndex1, newVertexIndex2);
            } else {
                // Log warning for unmapped vertices
                logUnmappedEdge(edgeIdx, oldVertexIndex1, oldVertexIndex2);
                skippedEdges++;
            }
        }

        RemapResult result = new RemapResult(edgeCount, remappedEdges, skippedEdges);
        logSummary(result);
        return result;
    }

    /**
     * Validate input parameters for remapping operation.
     *
     * @param edgeToVertexMapping The edge-to-vertex mapping array
     * @param edgeCount Number of edges
     * @param oldToNewIndexMap Index mapping
     * @return true if inputs are valid, false otherwise
     */
    private boolean validateInputs(int[][] edgeToVertexMapping, int edgeCount, Map<Integer, Integer> oldToNewIndexMap) {
        if (edgeToVertexMapping == null || edgeCount == 0) {
            logger.warn("Cannot remap edge indices: no edge-to-vertex mapping");
            return false;
        }

        if (oldToNewIndexMap == null || oldToNewIndexMap.isEmpty()) {
            logger.warn("Cannot remap edge indices: invalid index map");
            return false;
        }

        if (edgeToVertexMapping.length < edgeCount) {
            logger.warn("Cannot remap edge indices: mapping array too small ({} < {})",
                    edgeToVertexMapping.length, edgeCount);
            return false;
        }

        return true;
    }

    /**
     * Check if either vertex index is invalid.
     *
     * @param vertexIndex1 First vertex index
     * @param vertexIndex2 Second vertex index
     * @return true if either index is invalid
     */
    private boolean isInvalidIndex(int vertexIndex1, int vertexIndex2) {
        return vertexIndex1 == INVALID_VERTEX_INDEX || vertexIndex2 == INVALID_VERTEX_INDEX;
    }

    /**
     * Check if both new vertex indices are valid for remapping.
     *
     * @param newVertexIndex1 New index for first vertex
     * @param newVertexIndex2 New index for second vertex
     * @return true if both indices are non-null
     */
    private boolean canRemap(Integer newVertexIndex1, Integer newVertexIndex2) {
        return newVertexIndex1 != null && newVertexIndex2 != null;
    }

    /**
     * Log details of a successfully remapped edge.
     */
    private void logRemappedEdge(int edgeIdx, int oldV1, int oldV2, int newV1, int newV2) {
        logger.trace("Remapped edge {} vertices: ({}, {}) -> ({}, {})",
                edgeIdx, oldV1, oldV2, newV1, newV2);
    }

    /**
     * Log warning for an edge with vertices not in the remap.
     */
    private void logUnmappedEdge(int edgeIdx, int v1, int v2) {
        logger.warn("Edge {} has vertices not in remap: v1={}, v2={}",
                edgeIdx, v1, v2);
    }

    /**
     * Log summary of remapping operation.
     */
    private void logSummary(RemapResult result) {
        logger.debug("Remapped {} of {} edges to new vertex indices (skipped: {})",
                result.getRemappedEdges(), result.getTotalEdges(), result.getSkippedEdges());
    }
}
