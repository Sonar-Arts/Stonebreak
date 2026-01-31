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
 * SOLID Principles:
 * - Single Responsibility: Only handles face mesh updates
 * - Open/Closed: Can be extended for different update strategies
 * - Liskov Substitution: Could be abstracted to IFaceUpdateOperation if needed
 * - Interface Segregation: Focused interface for face updates
 * - Dependency Inversion: Depends on abstractions (arrays, buffers) not concrete implementations
 *
 * Shape-Blind Design:
 * This operation is data-driven and works with arbitrary face topology from GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology. All public methods accept topology parameters
 * (verticesPerFace, vboVerticesPerFace) to support triangles, quads, n-gons, and mixed topology
 * without hardcoded geometry assumptions.
 *
 * Migration to Shape-Blindness:
 * - NEW: Use updateFace(..., verticesPerFace, ..., vboVerticesPerFace) and
 *        updateAllFaces(..., verticesPerFace, vboVerticesPerFace, ...)
 * - LEGACY: Deprecated overloads maintain backward compatibility (default to quad topology)
 * - TODO: Parameterize triangulation indices for fully dynamic VBO layout
 *
 * Current Implementation:
 * VBO triangulation is currently hardcoded for quads (pattern: 0,1,2 and 0,2,3). This represents
 * the established format from GMR. Full shape-blindness requires parameterizing triangulation indices.
 *
 * Data Flow: GMR extracts mesh data → MeshManager operations → GPU buffer updates
 */
public class MeshFaceUpdateOperation {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceUpdateOperation.class);

    // VBO layout constants (DRY: shared across all operations)
    // Shape-blind: These represent vertex data format, not geometry assumptions
    public static final int FLOATS_PER_VERTEX = 7;           // 3 position + 4 color RGBA
    public static final int COMPONENTS_PER_POSITION = 3;     // x, y, z

    // Legacy constants for backward compatibility with existing callers
    // TODO: Remove once all callers are updated to use parameterized methods
    @Deprecated
    public static final int FLOATS_PER_FACE_POSITION = 12;   // Legacy: 4 corners × 3 coords
    @Deprecated
    public static final int VERTICES_PER_FACE = 6;            // Legacy: 2 triangles × 3 vertices
    @Deprecated
    public static final int FLOATS_PER_FACE_VBO = VERTICES_PER_FACE * FLOATS_PER_VERTEX;

    /**
     * Updates a face's position in both CPU memory and GPU buffer.
     * Shape-blind: Accepts topology parameters and triangulation pattern for arbitrary face structure.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions CPU-side face position array
     * @param faceCount Total number of faces
     * @param faceIndex Index of the face to update
     * @param verticesPerFace Number of vertices per face (topology from GMR)
     * @param vertexIndices Array of unique vertex indices for this face
     * @param newPositions Array of new vertex positions for this face
     * @param triangulationPattern Pattern defining how polygon corners map to VBO triangles
     * @return true if update succeeded, false otherwise
     */
    public boolean updateFace(int vbo, float[] facePositions, int faceCount, int faceIndex,
                             int verticesPerFace, int[] vertexIndices, Vector3f[] newPositions,
                             TriangulationPattern triangulationPattern) {

        // Validation
        if (!validateInput(facePositions, faceCount, faceIndex, verticesPerFace, vertexIndices, newPositions)) {
            return false;
        }

        if (triangulationPattern == null || !triangulationPattern.isValid()) {
            logger.warn("Invalid triangulation pattern");
            return false;
        }

        try {
            // Update CPU-side positions
            updateMemoryPositions(facePositions, faceIndex, verticesPerFace, newPositions);

            // Update GPU-side positions
            updateVBOPositions(vbo, faceIndex, triangulationPattern, newPositions);

            logger.trace("Updated face {} overlay position", faceIndex);
            return true;

        } catch (Exception e) {
            logger.error("Error updating face position for face {}", faceIndex, e);
            return false;
        }
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use {@link #updateFace(int, float[], int, int, int, int[], Vector3f[], TriangulationPattern)} instead
     */
    @Deprecated
    public boolean updateFace(int vbo, float[] facePositions, int faceCount,
                             int faceIndex, int[] vertexIndices, Vector3f[] newPositions) {
        // Default to quad topology (4 vertices per face, quad triangulation pattern)
        return updateFace(vbo, facePositions, faceCount, faceIndex, 4, vertexIndices, newPositions, TriangulationPattern.QUAD);
    }

    /**
     * Validates input parameters for face update.
     * Shape-blind: Validation is data-driven and does not enforce specific vertex counts.
     */
    private boolean validateInput(float[] facePositions, int faceCount, int faceIndex,
                                  int verticesPerFace, int[] vertexIndices, Vector3f[] newPositions) {

        if (facePositions == null || facePositions.length == 0) {
            logger.warn("Cannot update face: face positions array is null or empty");
            return false;
        }

        if (faceIndex < 0 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {}", faceIndex);
            return false;
        }

        if (verticesPerFace <= 0) {
            logger.warn("Invalid verticesPerFace: {}", verticesPerFace);
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

        if (newPositions.length != verticesPerFace) {
            logger.warn("Positions count ({}) does not match verticesPerFace ({})",
                    newPositions.length, verticesPerFace);
            return false;
        }

        return true;
    }

    /**
     * Updates face positions in CPU memory.
     * Positions are stored sequentially (vertex × 3 coords per face).
     * Shape-blind: Calculates position index dynamically based on actual topology from GMR.
     *
     * @param facePositions Array of face positions
     * @param faceIndex Index of the face to update
     * @param verticesPerFace Number of vertices per face (topology from GMR)
     * @param newPositions Array of new vertex positions
     */
    private void updateMemoryPositions(float[] facePositions, int faceIndex, int verticesPerFace, Vector3f[] newPositions) {
        int floatsPerFace = verticesPerFace * COMPONENTS_PER_POSITION;
        int posIndex = faceIndex * floatsPerFace;

        for (int i = 0; i < newPositions.length; i++) {
            int idx = posIndex + (i * COMPONENTS_PER_POSITION);
            facePositions[idx] = newPositions[i].x;
            facePositions[idx + 1] = newPositions[i].y;
            facePositions[idx + 2] = newPositions[i].z;
        }
    }

    /**
     * Updates face positions in GPU VBO.
     * Shape-blind: Uses triangulation pattern to map polygon corners to VBO triangles.
     *
     * VBO Layout:
     * - Each vertex: FLOATS_PER_VERTEX floats (3 position + 4 color RGBA)
     * - Only updates position data, preserving existing color data
     *
     * @param vbo OpenGL VBO handle
     * @param faceIndex Index of the face to update
     * @param triangulationPattern Pattern defining how polygon corners map to VBO triangles
     * @param newPositions Array of new corner positions (before triangulation)
     */
    private void updateVBOPositions(int vbo, int faceIndex, TriangulationPattern triangulationPattern, Vector3f[] newPositions) {
        int vboVerticesPerFace = triangulationPattern.getVBOVertexCount();
        int floatsPerFaceVBO = vboVerticesPerFace * FLOATS_PER_VERTEX;
        int dataStart = faceIndex * floatsPerFaceVBO;

        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        // Update each VBO vertex using the triangulation pattern
        for (int vboVertexIdx = 0; vboVertexIdx < vboVerticesPerFace; vboVertexIdx++) {
            int cornerIdx = triangulationPattern.getCornerIndex(vboVertexIdx);

            if (cornerIdx < newPositions.length) {
                Vector3f position = newPositions[cornerIdx];
                int offset = dataStart + (vboVertexIdx * FLOATS_PER_VERTEX);

                glBufferSubData(GL_ARRAY_BUFFER, (long) offset * Float.BYTES,
                        new float[] { position.x, position.y, position.z });
            }
        }

        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Bulk update: Creates and uploads complete VBO data for all faces.
     * DRY: Centralizes the vertex data creation logic used during initialization.
     * Shape-blind: Accepts topology parameters and triangulation pattern for arbitrary face structure.
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Array of face positions (sequential vertex coords per face)
     * @param faceCount Number of faces
     * @param verticesPerFace Number of vertices per face (topology from GMR)
     * @param triangulationPattern Pattern defining how polygon corners map to VBO triangles
     * @param defaultColor Default color for all faces (with alpha)
     * @return true if update succeeded, false otherwise
     */
    public boolean updateAllFaces(int vbo, float[] facePositions, int faceCount,
                                  int verticesPerFace, TriangulationPattern triangulationPattern, Vector4f defaultColor) {
        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot update all faces: invalid data");
            return false;
        }

        if (verticesPerFace <= 0) {
            logger.warn("Cannot update all faces: invalid topology (verticesPerFace={})", verticesPerFace);
            return false;
        }

        if (triangulationPattern == null || !triangulationPattern.isValid()) {
            logger.warn("Cannot update all faces: invalid triangulation pattern");
            return false;
        }

        try {
            // Create interleaved vertex data for all faces
            int vboVerticesPerFace = triangulationPattern.getVBOVertexCount();
            int totalVBOVertices = faceCount * vboVerticesPerFace;
            float[] vertexData = new float[totalVBOVertices * FLOATS_PER_VERTEX];

            int floatsPerFacePosition = verticesPerFace * COMPONENTS_PER_POSITION;
            int floatsPerFaceVBO = vboVerticesPerFace * FLOATS_PER_VERTEX;

            // Process each face
            for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
                int faceStart = faceIdx * floatsPerFacePosition;
                int dataStart = faceIdx * floatsPerFaceVBO;

                // Load corner positions
                Vector3f[] corners = new Vector3f[verticesPerFace];
                for (int cornerIdx = 0; cornerIdx < verticesPerFace; cornerIdx++) {
                    int posIdx = faceStart + (cornerIdx * COMPONENTS_PER_POSITION);
                    corners[cornerIdx] = new Vector3f(
                        facePositions[posIdx],
                        facePositions[posIdx + 1],
                        facePositions[posIdx + 2]
                    );
                }

                // Use triangulation pattern to create VBO vertices
                for (int vboVertexIdx = 0; vboVertexIdx < vboVerticesPerFace; vboVertexIdx++) {
                    int cornerIdx = triangulationPattern.getCornerIndex(vboVertexIdx);

                    if (cornerIdx < corners.length) {
                        int offset = dataStart + (vboVertexIdx * FLOATS_PER_VERTEX);
                        addVertexToData(vertexData, offset, corners[cornerIdx], defaultColor);
                    } else {
                        logger.warn("Triangulation pattern references invalid corner index: {} (max: {})",
                                cornerIdx, corners.length - 1);
                    }
                }
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
     * Legacy method for backward compatibility.
     * @deprecated Use {@link #updateAllFaces(int, float[], int, int, TriangulationPattern, Vector4f)} instead
     */
    @Deprecated
    public boolean updateAllFaces(int vbo, float[] facePositions, int faceCount, Vector4f defaultColor) {
        // Default to quad topology (4 vertices per face, quad triangulation pattern)
        return updateAllFaces(vbo, facePositions, faceCount, 4, TriangulationPattern.QUAD, defaultColor);
    }

    /**
     * Bulk update: Creates and uploads complete VBO data for faces with mixed topology.
     * Each face can have a different vertex count (triangles, quads, n-gons mixed).
     *
     * @param vbo OpenGL VBO handle
     * @param facePositions Packed face positions (variable vertices per face, sequential)
     * @param faceCount Number of faces
     * @param verticesPerFace Per-face vertex counts
     * @param faceOffsets Per-face float offsets into facePositions
     * @param defaultColor Default color for all faces (with alpha)
     * @return true if update succeeded, false otherwise
     */
    public boolean updateAllFacesMixed(int vbo, float[] facePositions, int faceCount,
                                        int[] verticesPerFace, int[] faceOffsets,
                                        Vector4f defaultColor) {
        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot update all faces (mixed): invalid data");
            return false;
        }

        if (verticesPerFace == null || verticesPerFace.length != faceCount) {
            logger.warn("Cannot update all faces (mixed): verticesPerFace length mismatch");
            return false;
        }

        if (faceOffsets == null || faceOffsets.length != faceCount) {
            logger.warn("Cannot update all faces (mixed): faceOffsets length mismatch");
            return false;
        }

        try {
            // Pre-compute triangulation patterns and total VBO vertex count
            TriangulationPattern[] patterns = new TriangulationPattern[faceCount];
            int totalVBOVertices = 0;

            for (int i = 0; i < faceCount; i++) {
                patterns[i] = TriangulationPattern.forNGon(verticesPerFace[i]);
                totalVBOVertices += patterns[i].getVBOVertexCount();
            }

            // Build VBO data with variable stride per face
            float[] vertexData = new float[totalVBOVertices * FLOATS_PER_VERTEX];
            int vboOffset = 0;

            for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
                int vpf = verticesPerFace[faceIdx];
                int faceStart = faceOffsets[faceIdx];
                TriangulationPattern pattern = patterns[faceIdx];
                int vboVerticesForFace = pattern.getVBOVertexCount();

                // Load corner positions
                Vector3f[] corners = new Vector3f[vpf];
                for (int cornerIdx = 0; cornerIdx < vpf; cornerIdx++) {
                    int posIdx = faceStart + (cornerIdx * COMPONENTS_PER_POSITION);
                    corners[cornerIdx] = new Vector3f(
                        facePositions[posIdx],
                        facePositions[posIdx + 1],
                        facePositions[posIdx + 2]
                    );
                }

                // Use triangulation pattern to create VBO vertices
                for (int vboVertexIdx = 0; vboVertexIdx < vboVerticesForFace; vboVertexIdx++) {
                    int cornerIdx = pattern.getCornerIndex(vboVertexIdx);

                    if (cornerIdx < corners.length) {
                        int dataIdx = vboOffset + (vboVertexIdx * FLOATS_PER_VERTEX);
                        addVertexToData(vertexData, dataIdx, corners[cornerIdx], defaultColor);
                    } else {
                        logger.warn("Mixed triangulation references invalid corner index: {} (max: {})",
                                cornerIdx, corners.length - 1);
                    }
                }

                vboOffset += vboVerticesForFace * FLOATS_PER_VERTEX;
            }

            // Upload to GPU
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, vertexData, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            logger.debug("Bulk updated {} faces (mixed topology, {} VBO vertices) to VBO",
                faceCount, totalVBOVertices);
            return true;

        } catch (Exception e) {
            logger.error("Error during mixed-topology bulk face update", e);
            return false;
        }
    }

    /**
     * Helper method to add vertex data to interleaved array.
     * DRY: Used by bulk update to construct vertex data with proper VBO layout.
     * Shape-blind: Writes vertex data in the established VBO format (7 floats per vertex).
     *
     * VBO Vertex Layout (current format):
     * - Position: 3 floats (x, y, z)
     * - Color: 4 floats (r, g, b, a)
     * - Total: 7 floats per vertex (FLOATS_PER_VERTEX)
     *
     * @param data The vertex data array to write to
     * @param startIdx Starting index in the data array
     * @param position Vertex position (x, y, z)
     * @param color Vertex color (r, g, b, a)
     */
    private void addVertexToData(float[] data, int startIdx, Vector3f position, Vector4f color) {
        // Position (3 floats)
        data[startIdx] = position.x;
        data[startIdx + 1] = position.y;
        data[startIdx + 2] = position.z;
        // Color RGBA (4 floats)
        data[startIdx + 3] = color.x;
        data[startIdx + 4] = color.y;
        data[startIdx + 5] = color.z;
        data[startIdx + 6] = color.w; // Alpha
    }
}
