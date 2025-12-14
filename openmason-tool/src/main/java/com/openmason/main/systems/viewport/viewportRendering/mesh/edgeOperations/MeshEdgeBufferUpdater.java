package com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL15.*;

/**
 * Single Responsibility: Handles creation of interleaved vertex data and GPU buffer updates for edge rendering.
 * This class transforms edge position data into GPU-ready format and uploads to VBO.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles edge buffer building and GPU upload
 * - Open/Closed: Can be extended for different buffer formats
 * - Liskov Substitution: Could be abstracted to IEdgeBufferUpdater if needed
 * - Interface Segregation: Focused interface for buffer updates
 * - Dependency Inversion: Depends on abstractions (arrays, OpenGL) not concrete implementations
 *
 * KISS Principle: Simple interleaved buffer format (position + color).
 * DRY Principle: All buffer creation logic centralized in one place.
 * YAGNI Principle: Only implements what's needed for edge rendering.
 */
public class MeshEdgeBufferUpdater {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeBufferUpdater.class);

    // Constants for data layout
    private static final int FLOATS_PER_EDGE = 6;           // 2 endpoints × 3 coords
    private static final int FLOATS_PER_POSITION = 3;       // x, y, z
    private static final int FLOATS_PER_COLOR = 3;          // r, g, b
    private static final int FLOATS_PER_VERTEX = FLOATS_PER_POSITION + FLOATS_PER_COLOR;
    private static final int VERTICES_PER_EDGE = 2;         // 2 endpoints per edge

    /**
     * Result of buffer update operation.
     * Immutable value object containing the edge count and positions.
     */
    public static class UpdateResult {
        private final int edgeCount;
        private final float[] edgePositions;

        public UpdateResult(int edgeCount, float[] edgePositions) {
            this.edgeCount = edgeCount;
            this.edgePositions = edgePositions;
        }

        public int getEdgeCount() {
            return edgeCount;
        }

        public float[] getEdgePositions() {
            return edgePositions;
        }
    }

    /**
     * Update the VBO with interleaved vertex data from edge positions.
     * Creates vertex data with position and color attributes for each endpoint.
     *
     * @param vbo The OpenGL VBO handle to update
     * @param edgePositions Raw edge position data [x1,y1,z1, x2,y2,z2, ...] (2 endpoints per edge)
     * @param edgeColor The color to apply to all edges
     * @return UpdateResult containing edge count and positions, or null if input invalid
     * @throws IllegalArgumentException if edgePositions array length is not a multiple of 6
     */
    public UpdateResult updateBuffer(int vbo, float[] edgePositions, Vector3f edgeColor) {
        if (edgePositions == null || edgePositions.length == 0) {
            logger.debug("Empty edge positions, clearing buffer");
            return new UpdateResult(0, null);
        }

        if (edgePositions.length % FLOATS_PER_EDGE != 0) {
            throw new IllegalArgumentException(
                "Edge positions array length must be multiple of 6 (got " + edgePositions.length + ")"
            );
        }

        if (edgeColor == null) {
            throw new IllegalArgumentException("Edge color cannot be null");
        }

        try {
            // Calculate edge count
            int edgeCount = edgePositions.length / FLOATS_PER_EDGE;

            // Build interleaved vertex data
            float[] vertexData = buildInterleavedVertexData(edgePositions, edgeColor);

            // Upload to GPU
            uploadToGPU(vbo, vertexData);

            logger.trace("Updated edge buffer: {} edges, {} vertices", edgeCount, edgeCount * VERTICES_PER_EDGE);

            return new UpdateResult(edgeCount, edgePositions);

        } catch (Exception e) {
            logger.error("Error updating edge buffer", e);
            return null;
        }
    }

    /**
     * Build interleaved vertex data from edge positions and color.
     * Creates array with format: [x,y,z,r,g,b, x,y,z,r,g,b, ...] for each endpoint.
     *
     * @param edgePositions Raw edge positions [x1,y1,z1, x2,y2,z2, ...]
     * @param edgeColor Color to apply to all vertices
     * @return Interleaved vertex data array
     */
    private float[] buildInterleavedVertexData(float[] edgePositions, Vector3f edgeColor) {
        int vertexCount = (edgePositions.length / FLOATS_PER_POSITION);
        float[] vertexData = new float[vertexCount * FLOATS_PER_VERTEX];

        for (int i = 0; i < vertexCount; i++) {
            int posIndex = i * FLOATS_PER_POSITION;
            int dataIndex = i * FLOATS_PER_VERTEX;

            // Copy position (x, y, z)
            vertexData[dataIndex + 0] = edgePositions[posIndex + 0];
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
