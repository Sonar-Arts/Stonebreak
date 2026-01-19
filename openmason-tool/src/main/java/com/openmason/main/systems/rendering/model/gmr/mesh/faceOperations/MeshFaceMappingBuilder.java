package com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Single Responsibility: Builds mapping from face indices to unique vertex indices.
 * This class encapsulates the logic of identifying which unique vertices each face connects to.
 */
public class MeshFaceMappingBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceMappingBuilder.class);
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z
    private static final int CORNERS_PER_FACE = 4; // quad face
    private static final int FLOATS_PER_FACE_POSITION = 12; // 4 corners × 3 components

    private final float vertexMatchEpsilon;

    /**
     * Create a face mesh mapping builder.
     *
     * @param vertexMatchEpsilon Distance threshold for considering vertices matching
     */
    public MeshFaceMappingBuilder(float vertexMatchEpsilon) {
        this.vertexMatchEpsilon = vertexMatchEpsilon;
    }

    /**
     * Build face-to-vertex mapping from unique vertex positions.
     * Maps each face index to array of 4 unique vertex indices [v0, v1, v2, v3].
     *
     * @param facePositions Array of face positions [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...]
     * @param faceCount Number of faces
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @return Map from face index to array of 4 unique vertex indices
     */
    public Map<Integer, int[]> buildMapping(float[] facePositions, int faceCount,
                                           float[] uniqueVertexPositions) {
        // Validate inputs
        if (!validateInputs(facePositions, faceCount, uniqueVertexPositions)) {
            return new HashMap<>();
        }

        int uniqueVertexCount = uniqueVertexPositions.length / COMPONENTS_PER_POSITION;
        Map<Integer, int[]> mapping = new HashMap<>();

        // For each face, find which unique vertices it connects
        for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
            int[] vertexIndices = findVertexIndicesForFace(
                faceIdx,
                facePositions,
                uniqueVertexPositions,
                uniqueVertexCount
            );
            mapping.put(faceIdx, vertexIndices);
        }

        logger.debug("Built face-to-vertex mapping for {} faces", faceCount);
        return mapping;
    }

    /**
     * Validate inputs for mapping construction.
     */
    private boolean validateInputs(float[] facePositions, int faceCount,
                                   float[] uniqueVertexPositions) {
        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot build face mapping: no face data");
            return false;
        }

        if (uniqueVertexPositions == null || uniqueVertexPositions.length < COMPONENTS_PER_POSITION) {
            logger.warn("Cannot build face mapping: invalid unique vertex data");
            return false;
        }

        return true;
    }

    /**
     * Find vertex indices for a specific face by matching positions.
     *
     * @param faceIdx Face index
     * @param facePositions Array of face positions
     * @param uniqueVertexPositions Array of unique vertex positions
     * @param uniqueVertexCount Number of unique vertices
     * @return Array of 4 vertex indices for the face corners
     */
    private int[] findVertexIndicesForFace(int faceIdx, float[] facePositions,
                                          float[] uniqueVertexPositions,
                                          int uniqueVertexCount) {
        int facePosIdx = faceIdx * FLOATS_PER_FACE_POSITION;
        int[] vertexIndices = new int[CORNERS_PER_FACE];

        MeshFaceVertexMatcher matcher = new MeshFaceVertexMatcher(vertexMatchEpsilon);

        for (int corner = 0; corner < CORNERS_PER_FACE; corner++) {
            Vector3f faceVertex = extractFaceCornerPosition(facePositions, facePosIdx, corner);
            int matchedIndex = matcher.findMatchingVertexIndex(
                faceVertex,
                uniqueVertexPositions,
                uniqueVertexCount
            );

            vertexIndices[corner] = matchedIndex;

            if (matchedIndex == -1) {
                logger.warn("Face {} corner {} has unmatched vertex", faceIdx, corner);
            }
        }

        return vertexIndices;
    }

    /**
     * Extract position of a face corner.
     *
     * @param facePositions Array of face positions
     * @param facePosIdx Starting index of face in positions array
     * @param corner Corner index (0-3)
     * @return Position vector
     */
    private Vector3f extractFaceCornerPosition(float[] facePositions, int facePosIdx, int corner) {
        int cornerPosIdx = facePosIdx + (corner * COMPONENTS_PER_POSITION);
        return new Vector3f(
            facePositions[cornerPosIdx],
            facePositions[cornerPosIdx + 1],
            facePositions[cornerPosIdx + 2]
        );
    }
}
