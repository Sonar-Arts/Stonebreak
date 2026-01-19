package com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Single Responsibility: Builds mapping from face indices to unique vertex indices.
 * This class encapsulates the logic of identifying which unique vertices each face connects to.
 *
 * Shape-Blind Design:
 * This operation is data-driven and works with arbitrary face topology from GenericModelRenderer (GMR).
 * GMR is the single source of truth for mesh topology. Face mapping works with any vertex count
 * per face and mesh structure determined by GMR's data model. Supports triangles, quads, n-gons,
 * and mixed topology without hardcoded assumptions.
 */
public class MeshFaceMappingBuilder {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceMappingBuilder.class);
    private static final int COMPONENTS_PER_POSITION = 3; // x, y, z

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
     * Maps each face index to array of vertex indices [v0, v1, v2, ..., vN].
     * Supports arbitrary face topology (triangles, quads, n-gons, or mixed).
     *
     * @param facePositions Array of face positions [v0x,v0y,v0z, v1x,v1y,v1z, ...]
     * @param faceCount Number of faces
     * @param verticesPerFace Number of vertices per face (topology determined by GMR)
     * @param uniqueVertexPositions Array of unique vertex positions [x0,y0,z0, x1,y1,z1, ...]
     * @return Map from face index to array of vertex indices (array length = verticesPerFace)
     */
    public Map<Integer, int[]> buildMapping(float[] facePositions, int faceCount,
                                           int verticesPerFace,
                                           float[] uniqueVertexPositions) {
        // Validate inputs
        if (!validateInputs(facePositions, faceCount, verticesPerFace, uniqueVertexPositions)) {
            return new HashMap<>();
        }

        int uniqueVertexCount = uniqueVertexPositions.length / COMPONENTS_PER_POSITION;
        Map<Integer, int[]> mapping = new HashMap<>();

        // For each face, find which unique vertices it connects
        for (int faceIdx = 0; faceIdx < faceCount; faceIdx++) {
            int[] vertexIndices = findVertexIndicesForFace(
                faceIdx,
                facePositions,
                verticesPerFace,
                uniqueVertexPositions,
                uniqueVertexCount
            );
            mapping.put(faceIdx, vertexIndices);
        }

        logger.debug("Built face-to-vertex mapping for {} faces ({} vertices/face)",
                faceCount, verticesPerFace);
        return mapping;
    }

    /**
     * Validate inputs for mapping construction.
     */
    private boolean validateInputs(float[] facePositions, int faceCount,
                                   int verticesPerFace,
                                   float[] uniqueVertexPositions) {
        if (facePositions == null || faceCount == 0) {
            logger.warn("Cannot build face mapping: no face data");
            return false;
        }

        if (verticesPerFace <= 0) {
            logger.warn("Cannot build face mapping: invalid verticesPerFace ({})", verticesPerFace);
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
     * Face topology is determined by GMR's data model - supports any vertex count per face.
     *
     * @param faceIdx Face index
     * @param facePositions Array of face positions
     * @param verticesPerFace Number of vertices per face (topology from GMR)
     * @param uniqueVertexPositions Array of unique vertex positions
     * @param uniqueVertexCount Number of unique vertices
     * @return Array of vertex indices for the face (length = verticesPerFace)
     */
    private int[] findVertexIndicesForFace(int faceIdx, float[] facePositions,
                                          int verticesPerFace,
                                          float[] uniqueVertexPositions,
                                          int uniqueVertexCount) {
        // Calculate face position index dynamically based on topology
        int floatsPerFacePosition = verticesPerFace * COMPONENTS_PER_POSITION;
        int facePosIdx = faceIdx * floatsPerFacePosition;
        int[] vertexIndices = new int[verticesPerFace];

        MeshFaceVertexMatcher matcher = new MeshFaceVertexMatcher(vertexMatchEpsilon);

        for (int vertexIdx = 0; vertexIdx < verticesPerFace; vertexIdx++) {
            Vector3f faceVertex = extractFaceCornerPosition(facePositions, facePosIdx, vertexIdx);
            int matchedIndex = matcher.findMatchingVertexIndex(
                faceVertex,
                uniqueVertexPositions,
                uniqueVertexCount
            );

            vertexIndices[vertexIdx] = matchedIndex;

            if (matchedIndex == -1) {
                logger.warn("Face {} vertex {} has unmatched vertex", faceIdx, vertexIdx);
            }
        }

        return vertexIndices;
    }

    /**
     * Extract position of a face vertex.
     * Works with any face topology determined by GMR's data model.
     *
     * @param facePositions Array of face positions
     * @param facePosIdx Starting index of face in positions array
     * @param vertexIdx Vertex index within the face (0 to verticesPerFace-1)
     * @return Position vector
     */
    private Vector3f extractFaceCornerPosition(float[] facePositions, int facePosIdx, int vertexIdx) {
        int vertexPosIdx = facePosIdx + (vertexIdx * COMPONENTS_PER_POSITION);
        return new Vector3f(
            facePositions[vertexPosIdx],
            facePositions[vertexPosIdx + 1],
            facePositions[vertexPosIdx + 2]
        );
    }
}
