package com.openmason.main.systems.rendering.model.gmr.extraction;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared utility for querying triangle-to-face relationships in GMR mesh data.
 *
 * Provides methods to find triangles belonging to a face and extract unique vertex
 * indices from those triangles. Used by both GMRFaceExtractor and GMREdgeExtractor
 * to avoid duplication of identical logic.
 *
 * Thread Safety: This class is stateless and thread-safe.
 */
public final class FaceTriangleQuery {

    private FaceTriangleQuery() {
        // Utility class — no instantiation
    }

    /**
     * Find all triangle indices that belong to a specific face.
     *
     * @param faceId    The face ID to search for
     * @param indices   Index buffer from GMR
     * @param faceMapper Triangle-to-face mapper from GMR
     * @return List of triangle indices belonging to the face (may be empty)
     */
    public static List<Integer> findTrianglesForFace(int faceId, int[] indices, ITriangleFaceMapper faceMapper) {
        List<Integer> triangles = new ArrayList<>();
        int triangleCount = indices.length / 3;

        for (int triIdx = 0; triIdx < triangleCount; triIdx++) {
            if (faceMapper.getOriginalFaceIdForTriangle(triIdx) == faceId) {
                triangles.add(triIdx);
            }
        }

        return triangles;
    }

    /**
     * Extract unique vertex indices from face triangles, preserving insertion order.
     * For a quad split as [v0,v1,v2] and [v0,v2,v3], returns [v0, v1, v2, v3].
     *
     * @param triangles List of triangle indices belonging to the face
     * @param indices   Index buffer from GMR
     * @return Ordered array of unique vertex indices
     */
    public static Integer[] extractFaceVertexIndices(List<Integer> triangles, int[] indices) {
        Set<Integer> uniqueVertexIndices = new LinkedHashSet<>();

        for (int triIdx : triangles) {
            uniqueVertexIndices.add(indices[triIdx * 3]);
            uniqueVertexIndices.add(indices[triIdx * 3 + 1]);
            uniqueVertexIndices.add(indices[triIdx * 3 + 2]);
        }

        return uniqueVertexIndices.toArray(new Integer[0]);
    }
}
