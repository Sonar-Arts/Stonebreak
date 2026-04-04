package com.openmason.main.systems.rendering.model.gmr.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL15.*;

/**
 * Updates edge positions in response to vertex movements.
 * Handles edge position updates for vertex transformations using different strategies.
 *
 * Shape-Blind Design:
 * Operates on edge data provided by GenericModelRenderer (GMR) without assuming specific topology.
 * GMR is the single source of truth for mesh structure and edge connectivity.
 * All edge structure information (vertices per edge) is derived from the data itself,
 * not from hardcoded assumptions.
 *
 * Thread Safety: This class is stateless and thread-safe.
 * However, OpenGL calls must be made from the OpenGL context thread.
 */
public class MeshEdgePositionUpdater {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgePositionUpdater.class);

    /** Number of float values per vertex position (x, y, z). */
    private static final int FLOATS_PER_POSITION = 3;

    /** Number of float values per vertex in VBO (x, y, z, r, g, b interleaved). */
    private static final int FLOATS_PER_VERTEX_DATA = 6;

    /**
     * Result of a position update operation.
     * Immutable value object containing update statistics and strategy used.
     */
    public static class UpdateResult {
        private final int updatedCount;
        private final UpdateStrategy strategy;

        public UpdateResult(int updatedCount, UpdateStrategy strategy) {
            this.updatedCount = updatedCount;
            this.strategy = strategy;
        }

        public int getUpdatedCount() {
            return updatedCount;
        }

        public UpdateStrategy getStrategy() {
            return strategy;
        }

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
         * Match by vertex indices using edge-to-vertex mapping.
         * Precise and prevents unintended vertex unification.
         */
        INDEX_BASED
    }

    /**
     * Updates edges connected to a single vertex index.
     * Only updates edges that connect to the specified unique vertex index,
     * providing precise control and preventing vertex unification bugs.
     *
     * This is a convenience method for single-vertex updates (e.g., vertex dragging).
     * It is more reliable than position-based matching, especially after subdivision.
     *
     * Operates on edge data from GMR without assuming specific topology.
     * The number of vertices per edge is derived from the actual data.
     *
     * Prerequisites: The edge-to-vertex mapping must be built before calling this method.
     *
     * @param vbo the OpenGL VBO handle for the edge buffer
     * @param edgePositions edge position array from GMR in format [x1,y1,z1, x2,y2,z2, ...]
     * @param verticesPerEdge number of vertices per edge (derived from GMR data model)
     * @param edgeToVertexMapping 2D array mapping edge indices to vertex indices arrays
     * @param vertexIndex the unique vertex index that was moved
     * @param newPosition the new position for the vertex
     * @return UpdateResult containing update statistics, or null if input is invalid
     */
    public UpdateResult updateSingleVertexByIndex(int vbo, float[] edgePositions, int verticesPerEdge,
                                                   int[][] edgeToVertexMapping,
                                                   int vertexIndex, Vector3f newPosition) {
        // Delegate to the two-vertex version with same vertex for both parameters
        return updateByIndices(vbo, edgePositions, verticesPerEdge, edgeToVertexMapping,
                               vertexIndex, newPosition, vertexIndex, newPosition);
    }

    /**
     * Updates edges connected to specific vertex indices.
     * Only updates edges that connect to the specified unique vertex indices,
     * providing precise control and preventing vertex unification bugs.
     *
     * Operates on edge data from GMR without assuming specific topology.
     * The number of vertices per edge is derived from the actual data.
     *
     * Prerequisites: The edge-to-vertex mapping must be built before calling this method.
     *
     * @param vbo the OpenGL VBO handle for the edge buffer
     * @param edgePositions edge position array from GMR in format [x1,y1,z1, x2,y2,z2, ...]
     * @param verticesPerEdge number of vertices per edge (derived from GMR data model)
     * @param edgeToVertexMapping 2D array mapping edge indices to vertex indices arrays
     * @param vertexIndex1 the first unique vertex index that was moved
     * @param newPosition1 the new position for the first vertex
     * @param vertexIndex2 the second unique vertex index that was moved
     * @param newPosition2 the new position for the second vertex
     * @return UpdateResult containing update statistics, or null if input is invalid
     */
    public UpdateResult updateByIndices(int vbo, float[] edgePositions, int verticesPerEdge,
                                        int[][] edgeToVertexMapping,
                                        int vertexIndex1, Vector3f newPosition1,
                                        int vertexIndex2, Vector3f newPosition2) {
        // Validate inputs
        if (!validateBasicInputs(edgePositions, verticesPerEdge, newPosition1)) {
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

            try {
                // Derive edge count from data
                int floatsPerEdge = verticesPerEdge * FLOATS_PER_POSITION;
                int edgeCount = edgePositions.length / floatsPerEdge;

                // Scan all edges and update those connected to either vertex
                for (int edgeIdx = 0; edgeIdx < edgeCount; edgeIdx++) {
                    int[] edgeVertexIndices = edgeToVertexMapping[edgeIdx];
                    if (edgeVertexIndices == null) {
                        continue;
                    }

                    boolean edgeUpdated = false;
                    int edgePosIdx = edgeIdx * floatsPerEdge;

                    // Check all vertices in this edge
                    for (int vertexInEdge = 0; vertexInEdge < edgeVertexIndices.length; vertexInEdge++) {
                        int vertexIndex = edgeVertexIndices[vertexInEdge];
                        Vector3f newPosition = null;

                        // Determine if this vertex needs updating
                        if (vertexIndex == vertexIndex1) {
                            newPosition = newPosition1;
                        } else if (vertexIndex == vertexIndex2) {
                            newPosition = newPosition2;
                        }

                        // Update this vertex if it matches one of the moved vertices
                        if (newPosition != null) {
                            // Calculate position in edge position array
                            int posOffset = edgePosIdx + (vertexInEdge * FLOATS_PER_POSITION);

                            // Calculate vertex index in VBO
                            int vertexIdxInBuffer = edgeIdx * verticesPerEdge + vertexInEdge;

                            // Update in GPU buffer
                            updateVertexInBuffer(vbo, vertexIdxInBuffer, newPosition);

                            // Update in CPU array
                            edgePositions[posOffset] = newPosition.x;
                            edgePositions[posOffset + 1] = newPosition.y;
                            edgePositions[posOffset + 2] = newPosition.z;

                            edgeUpdated = true;
                        }
                    }

                    if (edgeUpdated) {
                        updatedCount++;
                    }
                }
            } finally {
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            }

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
     * @param verticesPerEdge the number of vertices per edge to validate
     * @param newPosition the new position to validate (must be non-null)
     * @return true if all inputs are valid, false otherwise
     */
    private boolean validateBasicInputs(float[] edgePositions, int verticesPerEdge, Vector3f newPosition) {
        if (edgePositions == null || edgePositions.length == 0) {
            logger.warn("Cannot update edges: no edge data");
            return false;
        }

        if (verticesPerEdge <= 0) {
            logger.warn("Cannot update edges: invalid vertices per edge ({})", verticesPerEdge);
            return false;
        }

        if (newPosition == null) {
            logger.warn("Cannot update edges: position is null");
            return false;
        }

        return true;
    }

    /**
     * Updates a vertex position in the GPU buffer.
     * Updates only the position part of the interleaved vertex data, leaving color unchanged.
     *
     * @param vbo the OpenGL VBO handle (must already be bound to GL_ARRAY_BUFFER)
     * @param vertexIdx the vertex index (0-based across all edges in the buffer)
     * @param newPosition the new position for the vertex (x, y, z)
     */
    private void updateVertexInBuffer(int vbo, int vertexIdx, Vector3f newPosition) {
        // Calculate offset in interleaved buffer (position comes first in each vertex)
        int dataIndex = vertexIdx * FLOATS_PER_VERTEX_DATA; // 6 floats per vertex (x,y,z, r,g,b)
        long offset = dataIndex * Float.BYTES;

        // Upload only the position part (first 3 floats)
        float[] positionData = new float[] { newPosition.x, newPosition.y, newPosition.z };
        glBufferSubData(GL_ARRAY_BUFFER, offset, positionData);
    }

}
