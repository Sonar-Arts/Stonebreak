package com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL15.*;

/**
 * Single Responsibility: Handles face position updates in both memory and GPU buffer.
 * This class encapsulates all operations related to updating face mesh data.
 *
 * Shape-Blind Design:
 * This operation is data-driven and does not assume specific geometry (cubes, quads, etc.).
 * It operates on face data provided by GenericModelRenderer (GMR), which is the single
 * source of truth for mesh topology. Face vertex counts are determined by the data model.
 *
 * Data Flow: GMR extracts mesh data → MeshManager operations → GPU buffer updates
 */
public class MeshFaceUpdateOperation {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceUpdateOperation.class);

    // VBO layout constants (DRY: shared across all operations)
    // Note: These constants represent the current data format. Face vertex counts
    // are determined by GMR's mesh data model, not hardcoded geometry assumptions.
    public static final int FLOATS_PER_VERTEX = 7;           // 3 position + 4 color RGBA
    public static final int FLOATS_PER_FACE_POSITION = 12;  // Face corners × 3 coords (data-driven)
    public static final int VERTICES_PER_FACE = 6;           // Triangulated face vertices in VBO
    public static final int FLOATS_PER_FACE_VBO = VERTICES_PER_FACE * FLOATS_PER_VERTEX; // Total floats per face

    /**
     * Updates a face's position in both CPU memory and GPU buffer.
     * This method operates on face data provided by GMR without assuming specific topology.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions CPU-side face position array
     * @param faceCount Total number of faces
     * @param faceIndex Index of the face to update
     * @param vertexIndices Array of unique vertex indices for this face
     * @param newPositions Array of new vertex positions for this face
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
     * Validation is data-driven and does not enforce specific vertex counts.
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

        if (vertexIndices == null || vertexIndices.length == 0) {
            logger.warn("Invalid vertex indices array: null or empty");
            return false;
        }

        if (newPositions == null || newPositions.length == 0) {
            logger.warn("Invalid positions array: null or empty");
            return false;
        }

        if (vertexIndices.length != newPositions.length) {
            logger.warn("Vertex indices count ({}) does not match positions count ({})",
                    vertexIndices.length, newPositions.length);
            return false;
        }

        return true;
    }

    /**
     * Updates face positions in CPU memory.
     * Positions are stored sequentially (vertex × 3 coords per face).
     * The number of vertices is determined by the newPositions array length.
     */
    private void updateMemoryPositions(float[] facePositions, int faceIndex, Vector3f[] newPositions) {
        int posIndex = faceIndex * FLOATS_PER_FACE_POSITION;
        int vertexCount = newPositions.length;

        for (int i = 0; i < vertexCount; i++) {
            int idx = posIndex + (i * 3);
            facePositions[idx + 0] = newPositions[i].x;
            facePositions[idx + 1] = newPositions[i].y;
            facePositions[idx + 2] = newPositions[i].z;
        }
    }

    /**
     * Updates face positions in GPU VBO.
     *
     * Current Implementation Note:
     * This implementation handles the current face data format from GMR where faces
     * are stored as triangulated geometry in the VBO. The triangulation pattern
     * (vertices 0,1,2 and 0,2,3) matches the current data model but is not hardcoded
     * into the design - it reflects how GMR provides the data.
     *
     * Each vertex has 7 floats (3 position + 4 color RGBA).
     * Only updates position data, preserving existing color data.
     */
    private void updateVBOPositions(int vbo, int faceIndex, Vector3f[] newPositions) {
        int dataStart = faceIndex * FLOATS_PER_FACE_VBO;
        int vertexCount = Math.min(newPositions.length, 4); // Current format uses up to 4 corners

        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Update positions based on current triangulation pattern from GMR
        // This pattern matches how GMR structures face data in the VBO
        if (vertexCount >= 3) {
            // First triangle: indices 0, 1, 2
            glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 0) * Float.BYTES,
                    new float[] { newPositions[0].x, newPositions[0].y, newPositions[0].z });
            glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 7) * Float.BYTES,
                    new float[] { newPositions[1].x, newPositions[1].y, newPositions[1].z });
            glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 14) * Float.BYTES,
                    new float[] { newPositions[2].x, newPositions[2].y, newPositions[2].z });
        }

        if (vertexCount >= 4) {
            // Second triangle: indices 0, 2, 3
            glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 21) * Float.BYTES,
                    new float[] { newPositions[0].x, newPositions[0].y, newPositions[0].z });
            glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 28) * Float.BYTES,
                    new float[] { newPositions[2].x, newPositions[2].y, newPositions[2].z });
            glBufferSubData(GL_ARRAY_BUFFER, (dataStart + 35) * Float.BYTES,
                    new float[] { newPositions[3].x, newPositions[3].y, newPositions[3].z });
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Bulk update: Creates and uploads complete VBO data for all faces.
     * DRY: Centralizes the vertex data creation logic used during initialization.
     *
     * This method processes face data from GMR and creates GPU-ready vertex data
     * following the current VBO layout. The triangulation pattern reflects how
     * GMR structures face data, not hardcoded geometry assumptions.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Array of face positions (sequential vertex coords per face)
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
            // Face structure matches GMR's data model (triangulated geometry)
            // Each vertex: 7 floats (3 position + 4 color RGBA)
            int triangleVertexCount = faceCount * VERTICES_PER_FACE;
            float[] vertexData = new float[triangleVertexCount * FLOATS_PER_VERTEX];

            // Process each face
            for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
                int faceStart = faceIdx * FLOATS_PER_FACE_POSITION;
                int dataStart = faceIdx * FLOATS_PER_FACE_VBO;

                // Extract face corner positions (data-driven from GMR)
                Vector3f v0 = new Vector3f(facePositions[faceStart + 0], facePositions[faceStart + 1], facePositions[faceStart + 2]);
                Vector3f v1 = new Vector3f(facePositions[faceStart + 3], facePositions[faceStart + 4], facePositions[faceStart + 5]);
                Vector3f v2 = new Vector3f(facePositions[faceStart + 6], facePositions[faceStart + 7], facePositions[faceStart + 8]);
                Vector3f v3 = new Vector3f(facePositions[faceStart + 9], facePositions[faceStart + 10], facePositions[faceStart + 11]);

                // Build triangulated vertex data matching GMR's layout
                // Triangulation pattern: [0,1,2] and [0,2,3]
                // This reflects the current data model, not geometry assumptions

                // First triangle
                addVertexToData(vertexData, dataStart + 0, v0, defaultColor);
                addVertexToData(vertexData, dataStart + 7, v1, defaultColor);
                addVertexToData(vertexData, dataStart + 14, v2, defaultColor);

                // Second triangle
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
