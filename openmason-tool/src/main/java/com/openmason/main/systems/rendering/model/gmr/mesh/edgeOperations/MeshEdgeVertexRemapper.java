package com.openmason.main.systems.rendering.model.gmr.mesh.edgeOperations;

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
 *
 * Shape-Blind Design:
 * This operation is data-driven and works with edge-to-vertex mappings from GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology and edge connectivity.
 * Edge structure (vertices per edge) is determined by GMR's data model.
 *
 * Data Flow: GMR provides mappings → MeshManager operations → Remapping after vertex changes
 */
public class MeshEdgeVertexRemapper {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeVertexRemapper.class);

    /** Sentinel value indicating an invalid/unmatched vertex index. */
    private static final int INVALID_VERTEX_INDEX = -1;

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
     * This method works with edge mappings from GMR where vertex counts per edge
     * are determined by the data model.
     *
     * @param edgeToVertexMapping 2D array mapping edge index to vertex indices arrays [edgeIdx][vertex indices...]
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
            int[] oldVertexIndices = edgeToVertexMapping[edgeIdx];
            if (oldVertexIndices == null) {
                skippedEdges++;
                continue;
            }

            // Check if any vertices in this edge have invalid indices
            if (hasInvalidIndex(oldVertexIndices)) {
                skippedEdges++;
                continue;
            }

            // Attempt to remap all vertices in this edge (based on GMR's data model)
            boolean allRemapped = true;
            int[] newVertexIndices = new int[oldVertexIndices.length];

            for (int vertexInEdge = 0; vertexInEdge < oldVertexIndices.length; vertexInEdge++) {
                Integer newIndex = oldToNewIndexMap.get(oldVertexIndices[vertexInEdge]);
                if (newIndex == null) {
                    allRemapped = false;
                    break;
                }
                newVertexIndices[vertexInEdge] = newIndex;
            }

            if (allRemapped) {
                // Update mapping with new indices
                for (int vertexInEdge = 0; vertexInEdge < oldVertexIndices.length; vertexInEdge++) {
                    edgeToVertexMapping[edgeIdx][vertexInEdge] = newVertexIndices[vertexInEdge];
                }
                remappedEdges++;

                logRemappedEdge(edgeIdx, oldVertexIndices, newVertexIndices);
            } else {
                // Log warning for unmapped vertices
                logUnmappedEdge(edgeIdx, oldVertexIndices);
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
     * Check if any vertex index in the array is invalid.
     *
     * @param vertexIndices Array of vertex indices to check
     * @return true if any index is invalid
     */
    private boolean hasInvalidIndex(int[] vertexIndices) {
        for (int index : vertexIndices) {
            if (index == INVALID_VERTEX_INDEX) {
                return true;
            }
        }
        return false;
    }

    /**
     * Log details of a successfully remapped edge.
     */
    private void logRemappedEdge(int edgeIdx, int[] oldIndices, int[] newIndices) {
        if (logger.isTraceEnabled()) {
            logger.trace("Remapped edge {} vertices: {} -> {}",
                    edgeIdx, arrayToString(oldIndices), arrayToString(newIndices));
        }
    }

    /**
     * Log warning for an edge with vertices not in the remap.
     */
    private void logUnmappedEdge(int edgeIdx, int[] vertexIndices) {
        logger.warn("Edge {} has vertices not in remap: {}",
                edgeIdx, arrayToString(vertexIndices));
    }

    /**
     * Convert an int array to a readable string format.
     */
    private String arrayToString(int[] array) {
        if (array == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Log summary of remapping operation.
     */
    private void logSummary(RemapResult result) {
        logger.debug("Remapped {} of {} edges to new vertex indices (skipped: {})",
                result.getRemappedEdges(), result.getTotalEdges(), result.getSkippedEdges());
    }
}
