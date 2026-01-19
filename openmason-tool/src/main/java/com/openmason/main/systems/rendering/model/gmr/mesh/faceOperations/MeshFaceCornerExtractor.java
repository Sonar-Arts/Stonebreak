package com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single Responsibility: Extracts face corner positions from face position arrays.
 * This class encapsulates the logic of retrieving vertex positions for face corners.
 *
 * Shape-Blind Design:
 * This operation is data-driven and works with arbitrary face topology from GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology. Face extraction works with any vertex count
 * per face and mesh structure determined by GMR's data model. Supports triangles, quads, n-gons,
 * and mixed topology without hardcoded assumptions.
 */
public class MeshFaceCornerExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceCornerExtractor.class);
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z

    /**
     * Get the corner vertices of a face.
     * Supports arbitrary face topology (triangles, quads, n-gons, or mixed).
     *
     * @param facePositions Array of face positions [v0x,v0y,v0z, v1x,v1y,v1z, ...]
     * @param faceIndex Face index
     * @param faceCount Total number of faces
     * @param verticesPerFace Number of vertices per face (topology determined by GMR)
     * @return Array of vertices [v0, v1, v2, ..., vN] (length = verticesPerFace), or null if invalid
     */
    public Vector3f[] getFaceVertices(float[] facePositions, int faceIndex, int faceCount, int verticesPerFace) {
        // Validate inputs
        if (!validateInputs(facePositions, faceIndex, faceCount, verticesPerFace)) {
            return null;
        }

        // Calculate floats per face dynamically based on topology
        int floatsPerFacePosition = verticesPerFace * COMPONENTS_PER_POSITION;
        int posIndex = faceIndex * floatsPerFacePosition;

        // Bounds check
        if (posIndex + (floatsPerFacePosition - 1) >= facePositions.length) {
            logger.warn("Face index {} out of bounds for face positions array", faceIndex);
            return null;
        }

        // Extract vertices dynamically
        Vector3f[] vertices = new Vector3f[verticesPerFace];
        for (int i = 0; i < verticesPerFace; i++) {
            vertices[i] = extractFaceCornerPosition(facePositions, posIndex, i, verticesPerFace);
        }

        return vertices;
    }

    /**
     * Extract position of a specific face corner.
     * Works with any face topology determined by GMR's data model.
     *
     * @param facePositions Array of face positions
     * @param facePosIdx Starting index of face in positions array
     * @param vertexIdx Vertex index within the face (0 to verticesPerFace-1)
     * @param verticesPerFace Number of vertices per face (topology determined by GMR)
     * @return Position vector
     */
    public Vector3f extractFaceCornerPosition(float[] facePositions, int facePosIdx, int vertexIdx, int verticesPerFace) {
        if (facePositions == null) {
            logger.warn("Cannot extract corner position: face positions array is null");
            return new Vector3f();
        }

        if (vertexIdx < 0 || vertexIdx >= verticesPerFace) {
            logger.warn("Invalid vertex index: {} (must be 0-{})", vertexIdx, verticesPerFace - 1);
            return new Vector3f();
        }

        int cornerPosIdx = facePosIdx + (vertexIdx * COMPONENTS_PER_POSITION);

        // Bounds check
        if (cornerPosIdx + 2 >= facePositions.length) {
            logger.warn("Corner position index out of bounds");
            return new Vector3f();
        }

        return new Vector3f(
            facePositions[cornerPosIdx],
            facePositions[cornerPosIdx + 1],
            facePositions[cornerPosIdx + 2]
        );
    }

    /**
     * Get face vertex indices for a face from the mapping.
     * Array length depends on face topology (e.g., 3 for triangles, 4 for quads).
     *
     * @param faceIndex Face index
     * @param faceCount Total number of faces
     * @param faceToVertexMapping Map from face index to vertex indices
     * @return Array of vertex indices [v0, v1, v2, ..., vN], or null if invalid
     */
    public int[] getFaceVertexIndices(int faceIndex, int faceCount,
                                     java.util.Map<Integer, int[]> faceToVertexMapping) {
        if (faceIndex < 0 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {} (valid range: 0-{})", faceIndex, faceCount - 1);
            return null;
        }

        if (faceToVertexMapping == null) {
            logger.warn("Face-to-vertex mapping is null");
            return null;
        }

        return faceToVertexMapping.get(faceIndex);
    }

    /**
     * Validate inputs for face vertex extraction.
     */
    private boolean validateInputs(float[] facePositions, int faceIndex, int faceCount, int verticesPerFace) {
        if (facePositions == null) {
            logger.warn("Cannot get face vertices: face positions array is null");
            return false;
        }

        if (faceIndex < 0 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {} (valid range: 0-{})", faceIndex, faceCount - 1);
            return false;
        }

        if (verticesPerFace <= 0) {
            logger.warn("Cannot get face vertices: invalid verticesPerFace ({})", verticesPerFace);
            return false;
        }

        return true;
    }
}
