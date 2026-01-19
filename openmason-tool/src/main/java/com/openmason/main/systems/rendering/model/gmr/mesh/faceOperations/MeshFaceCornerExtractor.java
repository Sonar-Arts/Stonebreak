package com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single Responsibility: Extracts face corner positions from face position arrays.
 * This class encapsulates the logic of retrieving vertex positions for face corners.
 */
public class MeshFaceCornerExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceCornerExtractor.class);
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z
    private static final int CORNERS_PER_FACE = 4; // quad face
    private static final int FLOATS_PER_FACE_POSITION = 12; // 4 corners × 3 components

    /**
     * Get the 4 corner vertices of a face.
     *
     * @param facePositions Array of face positions [v0x,v0y,v0z, v1x,v1y,v1z, ...]
     * @param faceIndex Face index
     * @param faceCount Total number of faces
     * @return Array of 4 vertices [v0, v1, v2, v3], or null if invalid
     */
    public Vector3f[] getFaceVertices(float[] facePositions, int faceIndex, int faceCount) {
        // Validate inputs
        if (!validateInputs(facePositions, faceIndex, faceCount)) {
            return null;
        }

        int posIndex = faceIndex * FLOATS_PER_FACE_POSITION;

        // Bounds check
        if (posIndex + (FLOATS_PER_FACE_POSITION - 1) >= facePositions.length) {
            logger.warn("Face index {} out of bounds for face positions array", faceIndex);
            return null;
        }

        // Extract 4 corners
        Vector3f[] vertices = new Vector3f[CORNERS_PER_FACE];
        for (int i = 0; i < CORNERS_PER_FACE; i++) {
            vertices[i] = extractFaceCornerPosition(facePositions, posIndex, i);
        }

        return vertices;
    }

    /**
     * Extract position of a specific face corner.
     *
     * @param facePositions Array of face positions
     * @param facePosIdx Starting index of face in positions array
     * @param corner Corner index (0-3)
     * @return Position vector
     */
    public Vector3f extractFaceCornerPosition(float[] facePositions, int facePosIdx, int corner) {
        if (facePositions == null) {
            logger.warn("Cannot extract corner position: face positions array is null");
            return new Vector3f();
        }

        if (corner < 0 || corner >= CORNERS_PER_FACE) {
            logger.warn("Invalid corner index: {} (must be 0-3)", corner);
            return new Vector3f();
        }

        int cornerPosIdx = facePosIdx + (corner * COMPONENTS_PER_POSITION);

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
     *
     * @param faceIndex Face index
     * @param faceCount Total number of faces
     * @param faceToVertexMapping Map from face index to vertex indices
     * @return Array of 4 vertex indices [v0, v1, v2, v3], or null if invalid
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
    private boolean validateInputs(float[] facePositions, int faceIndex, int faceCount) {
        if (facePositions == null) {
            logger.warn("Cannot get face vertices: face positions array is null");
            return false;
        }

        if (faceIndex < 0 || faceIndex >= faceCount) {
            logger.warn("Invalid face index: {} (valid range: 0-{})", faceIndex, faceCount - 1);
            return false;
        }

        return true;
    }
}
