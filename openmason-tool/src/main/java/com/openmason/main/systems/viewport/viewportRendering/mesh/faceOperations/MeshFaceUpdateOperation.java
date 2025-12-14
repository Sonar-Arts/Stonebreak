package com.openmason.main.systems.viewport.viewportRendering.mesh.faceOperations;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL15.*;

/**
 * Single Responsibility: Handles face position updates in both memory and GPU buffer.
 * This class encapsulates all operations related to updating face mesh data.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles face position updates
 * - Open/Closed: Can be extended for different update strategies
 * - Liskov Substitution: Could be abstracted to IFaceUpdater if needed
 * - Interface Segregation: Focused interface for face updates
 * - Dependency Inversion: Depends on abstractions (arrays, Vector3f) not concrete implementations
 *
 * KISS Principle: Straightforward VBO update logic without unnecessary complexity.
 * DRY Principle: All VBO layout logic and vertex data operations are centralized here.
 * YAGNI Principle: Only implements what's needed for face position updates.
 *
 * This operation handles both bulk face data initialization and individual face updates.
 * Each face consists of 4 vertices forming 2 triangles (6 vertices total in VBO).
 */
public class MeshFaceUpdateOperation {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceUpdateOperation.class);

    // VBO layout constants (DRY: shared across all operations)
    public static final int FLOATS_PER_FACE_POSITION = 12;  // 4 vertices × 3 coords
    public static final int FLOATS_PER_VERTEX = 7;           // 3 position + 4 color RGBA
    public static final int VERTICES_PER_FACE = 6;           // 2 triangles × 3 vertices
    public static final int FLOATS_PER_FACE_VBO = VERTICES_PER_FACE * FLOATS_PER_VERTEX; // 42

    /**
     * Updates a face's position in both CPU memory and GPU buffer.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions CPU-side face position array
     * @param faceCount Total number of faces
     * @param faceIndex Index of the face to update
     * @param vertexIndices Array of 4 unique vertex indices (currently unused but required for future validation)
     * @param newPositions Array of 4 new vertex positions
     * @return true if update succeeded, false otherwise
     */
    public boolean updateFace(int vbo, float[] facePositions, int faceCount,
                             int faceIndex, int[] vertexIndices, Vector3f[] newPositions) {

        // Validation
        if (!validateInput(facePositions, faceCount, faceIndex, vertexIndices, newPositions)) {
            return false;
        }

        try {
            // Update CPU-side positions
            updateMemoryPositions(facePositions, faceIndex, newPositions);

            // Update GPU-side positions
            updateVBOPositions(vbo, faceIndex, newPositions);

            logger.trace("Updated face {} overlay position", faceIndex);
            return true;

        } catch (Exception e) {
            logger.error("Error updating face position for face {}", faceIndex, e);
            return false;
        }
    }

    /**
     * Validates input parameters for face update.
     */
    private boolean validateInput(float[] facePositions, int faceCount,
                                  int faceIndex, int[] vertexIndices, Vector3f[] newPositions) {

        if (facePositions == null || facePositions.length == 0) {
            logger.warn("Cannot update face: face positions array is null or empty");
            return false;
        }

        if (faceIndex < 0 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {}", faceIndex);
            return false;
        }

        if (vertexIndices == null || vertexIndices.length != 4) {
            logger.warn("Invalid vertex indices array");
            return false;
        }

        if (newPositions == null || newPositions.length != 4) {
            logger.warn("Invalid positions array");
            return false;
        }

        return true;
    }

    /**
     * Updates face positions in CPU memory.
     * Each face has 4 vertices, stored as 12 consecutive floats (4 × 3 coords).
     */
    private void updateMemoryPositions(float[] facePositions, int faceIndex, Vector3f[] newPositions) {
        int posIndex = faceIndex * FLOATS_PER_FACE_POSITION;

        for (int i = 0; i < 4; i++) {
            int idx = posIndex + (i * 3);
            facePositions[idx + 0] = newPositions[i].x;
            facePositions[idx + 1] = newPositions[i].y;
            facePositions[idx + 2] = newPositions[i].z;
        }
    }

    /**
     * Updates face positions in GPU VBO.
     * Each face consists of 2 triangles (6 vertices).
     * Each vertex has 7 floats (3 position + 4 color RGBA).
     * Only updates position data, preserving existing color data.
     */
    private void updateVBOPositions(int vbo, int faceIndex, Vector3f[] newPositions) {
        int dataStart = faceIndex * FLOATS_PER_FACE_VBO;

        Vector3f v0 = newPositions[0];
        Vector3f v1 = newPositions[1];
        Vector3f v2 = newPositions[2];
        Vector3f v3 = newPositions[3];

        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Triangle 1: v0, v1, v2 (positions only, leave colors unchanged)
        glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 0) * Float.BYTES, new float[] { v0.x, v0.y, v0.z });
        glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 7) * Float.BYTES, new float[] { v1.x, v1.y, v1.z });
        glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 14) * Float.BYTES, new float[] { v2.x, v2.y, v2.z });

        // Triangle 2: v0, v2, v3 (positions only, leave colors unchanged)
        glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 21) * Float.BYTES, new float[] { v0.x, v0.y, v0.z });
        glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 28) * Float.BYTES, new float[] { v2.x, v2.y, v2.z });
        glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 35) * Float.BYTES, new float[] { v3.x, v3.y, v3.z });

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Bulk update: Creates and uploads complete VBO data for all faces.
     * DRY: Centralizes the vertex data creation logic used during initialization.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Array of face positions [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...]
     * @param faceCount Number of faces
     * @param defaultColor Default color for all faces (with alpha)
     * @return true if update succeeded, false otherwise
     */
    public boolean updateAllFaces(int vbo, float[] facePositions, int faceCount, Vector4f defaultColor) {
        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot update all faces: invalid data");
            return false;
        }

        try {
            // Create interleaved vertex data for all faces
            // Each quad becomes 2 triangles = 6 vertices
            // Each vertex: 7 floats (3 position + 4 color RGBA)
            int triangleVertexCount = faceCount * VERTICES_PER_FACE;
            float[] vertexData = new float[triangleVertexCount * FLOATS_PER_VERTEX];

            // Process each face
            for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
                int faceStart = faceIdx * FLOATS_PER_FACE_POSITION;
                int dataStart = faceIdx * FLOATS_PER_FACE_VBO;

                // Get 4 corners of quad face
                Vector3f v0 = new Vector3f(facePositions[faceStart + 0], facePositions[faceStart + 1], facePositions[faceStart + 2]);
                Vector3f v1 = new Vector3f(facePositions[faceStart + 3], facePositions[faceStart + 4], facePositions[faceStart + 5]);
                Vector3f v2 = new Vector3f(facePositions[faceStart + 6], facePositions[faceStart + 7], facePositions[faceStart + 8]);
                Vector3f v3 = new Vector3f(facePositions[faceStart + 9], facePositions[faceStart + 10], facePositions[faceStart + 11]);

                // Split quad into 2 triangles
                // Triangle 1: v0, v1, v2
                // Triangle 2: v0, v2, v3

                // Triangle 1
                addVertexToData(vertexData, dataStart + 0, v0, defaultColor);
                addVertexToData(vertexData, dataStart + 7, v1, defaultColor);
                addVertexToData(vertexData, dataStart + 14, v2, defaultColor);

                // Triangle 2
                addVertexToData(vertexData, dataStart + 21, v0, defaultColor);
                addVertexToData(vertexData, dataStart + 28, v2, defaultColor);
                addVertexToData(vertexData, dataStart + 35, v3, defaultColor);
            }

            // Upload to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.debug("Bulk updated {} faces ({} floats) to VBO", faceCount, facePositions.length);
            return true;

        } catch (Exception e) {
            logger.error("Error during bulk face update", e);
            return false;
        }
    }

    /**
     * Helper method to add vertex data to interleaved array.
     * DRY: Used by bulk update to construct vertex data with proper layout.
     *
     * @param data The vertex data array to write to
     * @param startIdx Starting index in the data array
     * @param position Vertex position (x, y, z)
     * @param color Vertex color (r, g, b, a)
     */
    private void addVertexToData(float[] data, int startIdx, Vector3f position, Vector4f color) {
        // Position (3 floats)
        data[startIdx + 0] = position.x;
        data[startIdx + 1] = position.y;
        data[startIdx + 2] = position.z;
        // Color RGBA (4 floats)
        data[startIdx + 3] = color.x;
        data[startIdx + 4] = color.y;
        data[startIdx + 5] = color.z;
        data[startIdx + 6] = color.w; // Alpha
    }
}
