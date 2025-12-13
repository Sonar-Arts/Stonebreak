package com.openmason.main.systems.viewport.viewportRendering.edge.operations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL15.*;

/**
 * Updates edge positions in response to vertex movements.
 * Single Responsibility: Handles edge position updates for vertex transformations.
 *
 * <p>This class encapsulates the logic for:
 * <ul>
 *   <li>Position-based edge updates (matching old vertex position with epsilon tolerance)</li>
 *   <li>Index-based edge updates (using edge-to-vertex mapping for precise targeting)</li>
 *   <li>GPU buffer updates via OpenGL VBO operations</li>
 *   <li>In-memory position array synchronization</li>
 * </ul>
 *
 * <p><b>Update Strategies:</b>
 * <ul>
 *   <li><b>Position-based</b>: Updates all endpoints matching an old position.
 *       Useful for simple vertex drags but may affect unintended edges if vertices overlap.</li>
 *   <li><b>Index-based</b>: Updates only edges connected to specific vertex indices.
 *       More precise and prevents vertex unification bugs. Recommended for most use cases.</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is stateless and thread-safe.
 * However, OpenGL calls must be made from the OpenGL context thread.
 *
 * <p><b>Data Format:</b> Edge positions are stored in a flat array with 6 floats per edge
 * (x1, y1, z1, x2, y2, z2). The VBO uses interleaved format with 6 floats per vertex
 * (x, y, z, r, g, b). This class synchronizes both representations.
 *
 * @see com.openmason.main.systems.viewport.viewportRendering.edge.EdgeRenderer
 */
public class EdgePositionUpdater {

    private static final Logger logger = LoggerFactory.getLogger(EdgePositionUpdater.class);

    /** Position matching tolerance for floating-point comparison. */
    private static final float POSITION_EPSILON = 0.0001f;

    /** Number of float values per endpoint position (x, y, z). */
    private static final int FLOATS_PER_ENDPOINT = 3;

    /** Number of float values per vertex in VBO (x, y, z, r, g, b interleaved). */
    private static final int FLOATS_PER_VERTEX_DATA = 6;

    /** Number of float values per edge in position array (2 endpoints × 3 coords). */
    private static final int FLOATS_PER_EDGE_POSITIONS = 6;

    /** Number of endpoints per edge. */
    private static final int ENDPOINTS_PER_EDGE = 2;

    /**
     * Result of a position update operation.
     * Immutable value object containing update statistics and strategy used.
     *
     * <p>This class provides transparency into the update operation,
     * allowing callers to verify that the expected number of edges were updated.
     */
    public static class UpdateResult {
        private final int updatedCount;
        private final UpdateStrategy strategy;

        /**
         * Creates a new update result.
         *
         * @param updatedCount the number of edges that were updated
         * @param strategy the strategy used for the update operation
         */
        public UpdateResult(int updatedCount, UpdateStrategy strategy) {
            this.updatedCount = updatedCount;
            this.strategy = strategy;
        }

        /**
         * Returns the number of edges that were updated.
         *
         * @return the count of updated edges
         */
        public int getUpdatedCount() {
            return updatedCount;
        }

        /**
         * Returns the strategy used for the update.
         *
         * @return the update strategy
         */
        public UpdateStrategy getStrategy() {
            return strategy;
        }

        /**
         * Returns whether the update operation updated at least one edge.
         *
         * @return true if one or more edges were updated
         */
        public boolean isSuccessful() {
            return updatedCount > 0;
        }
    }

    /**
     * Strategy used for updating edge positions.
     * Defines how edges are identified for updating.
     */
    public enum UpdateStrategy {
        /**
         * Match by old position using epsilon comparison.
         * May affect multiple edges if vertices share the same position.
         */
        POSITION_BASED,

        /**
         * Match by vertex indices using edge-to-vertex mapping.
         * More precise and prevents unintended vertex unification.
         * Recommended for most use cases.
         */
        INDEX_BASED
    }

    /**
     * Updates all edge endpoints that match a dragged vertex position.
     * Searches through ALL edge endpoints and updates any that were at the old vertex position
     * using epsilon-based floating-point comparison.
     *
     * <p>This method handles models where EdgeExtractor creates face-based edges
     * (e.g., 24 edges for a cube: 4 per face × 6 faces) instead of unique edges,
     * meaning multiple edge endpoints may share the same vertex position.
     *
     * <p><b>Warning:</b> This method may update unintended edges if vertices share
     * the same position. For precise updates, use {@link #updateByIndices} instead.
     *
     * <p><b>OpenGL Context:</b> This method must be called from the OpenGL context thread
     * as it performs VBO operations.
     *
     * @param vbo the OpenGL VBO handle for the edge buffer
     * @param edgePositions edge position array in format [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeCount the total number of edges in the array
     * @param oldPosition the original position of the vertex before dragging
     * @param newPosition the new position of the vertex after dragging
     * @return UpdateResult containing update statistics, or null if input is invalid
     */
    public UpdateResult updateByPosition(int vbo, float[] edgePositions, int edgeCount,
                                         Vector3f oldPosition, Vector3f newPosition) {
        // Validate inputs
        if (!validateBasicInputs(edgePositions, edgeCount, newPosition)) {
            return null;
        }

        if (oldPosition == null) {
            logger.warn("Cannot update edge endpoints: old position is null");
            return null;
        }

        try {
            int updatedCount = 0;
            glBindBuffer(GL_ARRAY_BUFFER, vbo);

            // Search through ALL edge endpoints (2 endpoints per edge)
            int totalEndpoints = edgeCount * ENDPOINTS_PER_EDGE;
            for (int endpointIdx = 0; endpointIdx < totalEndpoints; endpointIdx++) {
                int posIndex = endpointIdx * FLOATS_PER_ENDPOINT;

                // Check if this endpoint matches the old vertex position
                if (posIndex + 2 < edgePositions.length) {
                    Vector3f endpointPos = new Vector3f(
                        edgePositions[posIndex + 0],
                        edgePositions[posIndex + 1],
                        edgePositions[posIndex + 2]
                    );

                    if (endpointPos.distance(oldPosition) < POSITION_EPSILON) {
                        // Found a matching endpoint - update it!
                        updateEndpointInBuffer(vbo, endpointIdx, newPosition);
                        updateEndpointInArray(edgePositions, posIndex, newPosition);
                        updatedCount++;
                    }
                }
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.trace("Updated {} edge endpoints from ({}, {}, {}) to ({}, {}, {})",
                updatedCount,
                String.format("%.2f", oldPosition.x),
                String.format("%.2f", oldPosition.y),
                String.format("%.2f", oldPosition.z),
                String.format("%.2f", newPosition.x),
                String.format("%.2f", newPosition.y),
                String.format("%.2f", newPosition.z));

            return new UpdateResult(updatedCount, UpdateStrategy.POSITION_BASED);

        } catch (Exception e) {
            logger.error("Error updating edge endpoints by position", e);
            return null;
        }
    }

    /**
     * Updates edges connected to specific vertex indices.
     * Only updates edges that connect to the specified unique vertex indices,
     * providing precise control and preventing vertex unification bugs.
     *
     * <p>This method uses the edge-to-vertex mapping to identify exactly which edges
     * are connected to the moved vertices. Each edge's endpoints are checked against
     * the provided vertex indices, and only matching endpoints are updated.
     *
     * <p><b>Advantages over position-based updates:</b>
     * <ul>
     *   <li>Prevents unintended updates when vertices share positions</li>
     *   <li>More efficient for selective updates</li>
     *   <li>Clear mapping between vertex movements and edge updates</li>
     * </ul>
     *
     * <p><b>Prerequisites:</b> The edge-to-vertex mapping must be built via
     * {@code EdgeRenderer.buildEdgeToVertexMapping()} before calling this method.
     *
     * <p><b>OpenGL Context:</b> This method must be called from the OpenGL context thread
     * as it performs VBO operations.
     *
     * @param vbo the OpenGL VBO handle for the edge buffer
     * @param edgePositions edge position array in format [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeCount the total number of edges in the array
     * @param edgeToVertexMapping 2D array mapping edge indices to pairs of vertex indices
     * @param vertexIndex1 the first unique vertex index that was moved
     * @param newPosition1 the new position for the first vertex
     * @param vertexIndex2 the second unique vertex index that was moved
     * @param newPosition2 the new position for the second vertex
     * @return UpdateResult containing update statistics, or null if input is invalid
     */
    public UpdateResult updateByIndices(int vbo, float[] edgePositions, int edgeCount,
                                        int[][] edgeToVertexMapping,
                                        int vertexIndex1, Vector3f newPosition1,
                                        int vertexIndex2, Vector3f newPosition2) {
        // Validate inputs
        if (!validateBasicInputs(edgePositions, edgeCount, newPosition1)) {
            return null;
        }

        if (edgeToVertexMapping == null) {
            logger.warn("Cannot update edges: mapping not built. Call buildEdgeToVertexMapping() first");
            return null;
        }

        if (newPosition2 == null) {
            logger.warn("Cannot update edges: second position is null");
            return null;
        }

        try {
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            int updatedCount = 0;

            // Scan all edges and update those connected to either vertex
            for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
                int v1 = edgeToVertexMapping[edgeIdx][0];
                int v2 = edgeToVertexMapping[edgeIdx][1];

                // Check if this edge connects to either of the moved vertices
                Vector3f newEndpoint1 = null;
                Vector3f newEndpoint2 = null;
                boolean endpoint1Updated = false;
                boolean endpoint2Updated = false;

                // Determine new positions for this edge's endpoints
                if (v1 == vertexIndex1) {
                    newEndpoint1 = newPosition1;
                    endpoint1Updated = true;
                } else if (v1 == vertexIndex2) {
                    newEndpoint1 = newPosition2;
                    endpoint1Updated = true;
                }

                if (v2 == vertexIndex1) {
                    newEndpoint2 = newPosition1;
                    endpoint2Updated = true;
                } else if (v2 == vertexIndex2) {
                    newEndpoint2 = newPosition2;
                    endpoint2Updated = true;
                }

                // Update this edge if either endpoint changed
                if (endpoint1Updated || endpoint2Updated) {
                    int edgePosIdx = edgeIdx * FLOATS_PER_EDGE_POSITIONS;

                    // Update endpoint 1 if changed
                    if (endpoint1Updated && newEndpoint1 != null) {
                        int endpointIdx1 = edgeIdx * ENDPOINTS_PER_EDGE; // First endpoint of this edge
                        updateEndpointInBuffer(vbo, endpointIdx1, newEndpoint1);

                        edgePositions[edgePosIdx + 0] = newEndpoint1.x;
                        edgePositions[edgePosIdx + 1] = newEndpoint1.y;
                        edgePositions[edgePosIdx + 2] = newEndpoint1.z;
                    }

                    // Update endpoint 2 if changed
                    if (endpoint2Updated && newEndpoint2 != null) {
                        int endpointIdx2 = edgeIdx * ENDPOINTS_PER_EDGE + 1; // Second endpoint of this edge
                        updateEndpointInBuffer(vbo, endpointIdx2, newEndpoint2);

                        edgePositions[edgePosIdx + 3] = newEndpoint2.x;
                        edgePositions[edgePosIdx + 4] = newEndpoint2.y;
                        edgePositions[edgePosIdx + 5] = newEndpoint2.z;
                    }

                    updatedCount++;
                }
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.trace("Updated {} edges connected to vertices {} and {} (index-based)",
                updatedCount, vertexIndex1, vertexIndex2);

            return new UpdateResult(updatedCount, UpdateStrategy.INDEX_BASED);

        } catch (Exception e) {
            logger.error("Error updating edges by vertex indices", e);
            return null;
        }
    }

    /**
     * Validates basic inputs common to all update operations.
     * Checks that edge data exists and new position is provided.
     *
     * @param edgePositions the edge position array to validate
     * @param edgeCount the number of edges to validate
     * @param newPosition the new position to validate (must be non-null)
     * @return true if all inputs are valid, false otherwise
     */
    private boolean validateBasicInputs(float[] edgePositions, int edgeCount, Vector3f newPosition) {
        if (edgePositions == null || edgeCount == 0) {
            logger.warn("Cannot update edges: no edge data");
            return false;
        }

        if (newPosition == null) {
            logger.warn("Cannot update edges: position is null");
            return false;
        }

        return true;
    }

    /**
     * Updates an endpoint position in the GPU buffer.
     * Updates only the position part of the interleaved vertex data, leaving color unchanged.
     *
     * <p>This method uses {@code glBufferSubData} to efficiently update just the
     * 3 position floats for the specified endpoint without re-uploading the entire buffer.
     *
     * @param vbo the OpenGL VBO handle (must already be bound to GL_ARRAY_BUFFER)
     * @param endpointIdx the endpoint index (0-based across all edges in the buffer)
     * @param newPosition the new position for the endpoint (x, y, z)
     */
    private void updateEndpointInBuffer(int vbo, int endpointIdx, Vector3f newPosition) {
        // Calculate offset in interleaved buffer (position comes first in each vertex)
        int dataIndex = endpointIdx * FLOATS_PER_VERTEX_DATA; // 6 floats per vertex (x,y,z, r,g,b)
        long offset = dataIndex * Float.BYTES;

        // Upload only the position part (first 3 floats)
        float[] positionData = new float[] { newPosition.x, newPosition.y, newPosition.z };
        glBufferSubData(GL_ARRAY_BUFFER, offset, positionData);
    }

    /**
     * Updates an endpoint position in the in-memory array.
     * Synchronizes the CPU-side position array with GPU buffer changes.
     *
     * <p>This ensures the in-memory position array stays synchronized with
     * the GPU VBO data for subsequent operations like hit detection.
     *
     * @param edgePositions the edge position array to update
     * @param posIndex the position index in the array (must point to x coordinate)
     * @param newPosition the new position for the endpoint (x, y, z)
     */
    private void updateEndpointInArray(float[] edgePositions, int posIndex, Vector3f newPosition) {
        edgePositions[posIndex + 0] = newPosition.x;
        edgePositions[posIndex + 1] = newPosition.y;
        edgePositions[posIndex + 2] = newPosition.z;
    }
}
