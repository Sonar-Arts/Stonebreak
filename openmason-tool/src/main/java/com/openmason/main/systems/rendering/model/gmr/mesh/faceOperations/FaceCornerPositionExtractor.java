package com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for extracting face corner positions from face position arrays.
 * Centralizes the logic of retrieving vertex positions for face corners to eliminate duplication.
 *
 * Shape-Blind Design:
 * Works with arbitrary face topology (triangles, quads, n-gons) without hardcoded assumptions.
 */
public class FaceCornerPositionExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FaceCornerPositionExtractor.class);
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z

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
    public static Vector3f extractFaceCornerPosition(float[] facePositions, int facePosIdx, int vertexIdx, int verticesPerFace) {
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
     * Extract position of a face vertex (simplified version without verticesPerFace validation).
     * Assumes caller has validated vertexIdx.
     *
     * @param facePositions Array of face positions
     * @param facePosIdx Starting index of face in positions array
     * @param vertexIdx Vertex index within the face
     * @return Position vector
     */
    public static Vector3f extractSimple(float[] facePositions, int facePosIdx, int vertexIdx) {
        int vertexPosIdx = facePosIdx + (vertexIdx * COMPONENTS_PER_POSITION);
        return new Vector3f(
            facePositions[vertexPosIdx],
            facePositions[vertexPosIdx + 1],
            facePositions[vertexPosIdx + 2]
        );
    }
}
