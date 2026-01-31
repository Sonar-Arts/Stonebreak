package com.openmason.main.systems.rendering.model.gmr.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL15.*;

/**
 * Handles creation of interleaved vertex data and GPU buffer updates for edge rendering.
 * Transforms edge position data from GMR into GPU-ready format and uploads to VBO.
 *
 * Shape-Blind Design:
 * Operates on edge data provided by GenericModelRenderer (GMR) without assuming specific topology.
 * GMR is the single source of truth for mesh structure and edge connectivity.
 * All edge structure information is derived from the data itself, not from hardcoded assumptions.
 */
public class MeshEdgeBufferUpdater {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeBufferUpdater.class);

    // Vertex data format constants
    private static final int FLOATS_PER_POSITION = 3;       // x, y, z
    private static final int FLOATS_PER_COLOR = 3;          // r, g, b
    private static final int FLOATS_PER_VERTEX = FLOATS_PER_POSITION + FLOATS_PER_COLOR;

    /**
     * Result of buffer update operation.
     * Immutable value object containing the edge count and positions.
     */
    public static class UpdateResult {
        private final int edgeCount;
        private final int verticesPerEdge;
        private final float[] edgePositions;

        public UpdateResult(int edgeCount, int verticesPerEdge, float[] edgePositions) {
            this.edgeCount = edgeCount;
            this.verticesPerEdge = verticesPerEdge;
            this.edgePositions = edgePositions;
        }

        public int getEdgeCount() {
            return edgeCount;
        }

        public int getVerticesPerEdge() {
            return verticesPerEdge;
        }

        public float[] getEdgePositions() {
            return edgePositions;
        }
    }

    /**
     * Update the VBO with interleaved vertex data from edge positions.
     * Creates vertex data with position and color attributes for each vertex.
     * Derives edge structure from the provided data without shape assumptions.
     *
     * @param vbo The OpenGL VBO handle to update
     * @param edgePositions Raw edge position data from GMR [x1,y1,z1, x2,y2,z2, ...]
     * @param verticesPerEdge Number of vertices per edge (derived from GMR data model)
     * @param edgeColor The color to apply to all edges
     * @return UpdateResult containing edge count and positions, or null if input invalid
     * @throws IllegalArgumentException if edgePositions array length doesn't match expected format
     */
    public UpdateResult updateBuffer(int vbo, float[] edgePositions, int verticesPerEdge, Vector3f edgeColor) {
        if (edgePositions == null || edgePositions.length == 0) {
            logger.debug("Empty edge positions, clearing buffer");
            return new UpdateResult(0, verticesPerEdge, null);
        }

        if (verticesPerEdge <= 0) {
            throw new IllegalArgumentException("Vertices per edge must be positive (got " + verticesPerEdge + ")");
        }

        int floatsPerEdge = verticesPerEdge * FLOATS_PER_POSITION;
        if (edgePositions.length % floatsPerEdge != 0) {
            throw new IllegalArgumentException(
                "Edge positions array length must be multiple of " + floatsPerEdge +
                " (got " + edgePositions.length + "). Data format mismatch with GMR."
            );
        }

        if (edgeColor == null) {
            throw new IllegalArgumentException("Edge color cannot be null");
        }

        try {
            // Calculate edge count from data
            int edgeCount = edgePositions.length / floatsPerEdge;

            // Build interleaved vertex data
            float[] vertexData = buildInterleavedVertexData(edgePositions, edgeColor);

            // Upload to GPU
            uploadToGPU(vbo, vertexData);

            logger.trace("Updated edge buffer: {} edges, {} vertices per edge", edgeCount, verticesPerEdge);

            return new UpdateResult(edgeCount, verticesPerEdge, edgePositions);

        } catch (Exception e) {
            logger.error("Error updating edge buffer", e);
            return null;
        }
    }

    /**
     * Build interleaved vertex data from edge positions and color.
     * Creates array with format: [x,y,z,r,g,b, x,y,z,r,g,b, ...] for each vertex.
     * Processes edge data from GMR into GPU-ready format.
     *
     * @param edgePositions Raw edge positions from GMR [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeColor Color to apply to all vertices
     * @return Interleaved vertex data array ready for GPU upload
     */
    private float[] buildInterleavedVertexData(float[] edgePositions, Vector3f edgeColor) {
        int vertexCount = (edgePositions.length / FLOATS_PER_POSITION);
        float[] vertexData = new float[vertexCount * FLOATS_PER_VERTEX];

        for (int i = 0; i < vertexCount; i++) {
            int posIndex = i * FLOATS_PER_POSITION;
            int dataIndex = i * FLOATS_PER_VERTEX;

            // Copy position (x, y, z)
            vertexData[dataIndex] = edgePositions[posIndex];
            vertexData[dataIndex + 1] = edgePositions[posIndex + 1];
            vertexData[dataIndex + 2] = edgePositions[posIndex + 2];

            // Copy color (r, g, b)
            vertexData[dataIndex + 3] = edgeColor.x;
            vertexData[dataIndex + 4] = edgeColor.y;
            vertexData[dataIndex + 5] = edgeColor.z;
        }

        return vertexData;
    }

    /**
     * Upload vertex data to GPU VBO.
     * Binds the VBO, uploads data with GL_DYNAMIC_DRAW usage hint, then unbinds.
     *
     * @param vbo The OpenGL VBO handle
     * @param vertexData The interleaved vertex data to upload
     */
    private void uploadToGPU(int vbo, float[] vertexData) {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        logger.trace("Uploaded {} floats to VBO {}", vertexData.length, vbo);
    }
}
