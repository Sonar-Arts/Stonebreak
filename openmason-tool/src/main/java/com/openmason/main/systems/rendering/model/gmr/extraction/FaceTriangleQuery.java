package com.openmason.main.systems.rendering.model.gmr.extraction;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;

import java.util.*;

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
     * Extract vertex indices from face triangles in correct polygon boundary winding order.
     *
     * <p>Uses directed boundary-edge walking to reconstruct the polygon outline from
     * an arbitrary triangulation. A directed edge (a→b) from a triangle is a boundary
     * edge if its reverse (b→a) does not appear in any other triangle of the same face.
     * Chaining these directed boundary edges produces the correct polygon winding.
     *
     * <p>Falls back to insertion-order collection if the boundary walk fails.
     *
     * @param triangles List of triangle indices belonging to the face
     * @param indices   Index buffer from GMR
     * @return Ordered array of vertex indices forming the polygon boundary
     */
    public static Integer[] extractFaceVertexIndices(List<Integer> triangles, int[] indices) {
        if (triangles.isEmpty()) {
            return new Integer[0];
        }

        // Single triangle — return its vertices directly
        if (triangles.size() == 1) {
            int t = triangles.get(0);
            return new Integer[]{ indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2] };
        }

        // Collect all directed edges from all triangles of this face.
        // A directed edge (src→dst) is internal if its reverse (dst→src) also appears;
        // otherwise it is a boundary edge of the polygon.
        Map<Long, Integer> directedEdgeCounts = new HashMap<>();

        for (int triIdx : triangles) {
            int i0 = indices[triIdx * 3];
            int i1 = indices[triIdx * 3 + 1];
            int i2 = indices[triIdx * 3 + 2];

            directedEdgeCounts.merge(directedEdgeKey(i0, i1), 1, Integer::sum);
            directedEdgeCounts.merge(directedEdgeKey(i1, i2), 1, Integer::sum);
            directedEdgeCounts.merge(directedEdgeKey(i2, i0), 1, Integer::sum);
        }

        // Build boundary adjacency map: src → dst for boundary-only directed edges
        Map<Integer, Integer> boundaryNext = new LinkedHashMap<>();

        for (Map.Entry<Long, Integer> entry : directedEdgeCounts.entrySet()) {
            long key = entry.getKey();
            int src = (int) (key >>> 32);
            int dst = (int) key;
            long reverseKey = directedEdgeKey(dst, src);

            if (!directedEdgeCounts.containsKey(reverseKey)) {
                boundaryNext.put(src, dst);
            }
        }

        // Need at least 3 boundary edges to form a polygon
        if (boundaryNext.size() < 3) {
            return extractFallback(triangles, indices);
        }

        // Start the walk from the first triangle's first boundary vertex for determinism
        int start = -1;
        int triIdx0 = triangles.get(0);
        for (int offset = 0; offset < 3; offset++) {
            int v = indices[triIdx0 * 3 + offset];
            if (boundaryNext.containsKey(v)) {
                start = v;
                break;
            }
        }
        if (start < 0) {
            start = boundaryNext.keySet().iterator().next();
        }

        // Walk the directed boundary chain
        List<Integer> polygon = new ArrayList<>(boundaryNext.size());
        int current = start;
        int maxSteps = boundaryNext.size();

        do {
            polygon.add(current);
            Integer next = boundaryNext.get(current);
            if (next == null) {
                break; // broken chain
            }
            current = next;
        } while (current != start && polygon.size() < maxSteps);

        // Verify we formed a closed loop with at least 3 vertices
        if (polygon.size() < 3 || current != start) {
            return extractFallback(triangles, indices);
        }

        return polygon.toArray(new Integer[0]);
    }

    /**
     * Encode a directed edge (src → dst) as a single long key.
     */
    private static long directedEdgeKey(int src, int dst) {
        return ((long) src << 32) | (dst & 0xFFFFFFFFL);
    }

    /**
     * Fallback extraction using insertion-order deduplication.
     * Works correctly for simple fan triangulations but may produce wrong
     * winding for non-fan patterns (e.g., after edge subdivision).
     */
    private static Integer[] extractFallback(List<Integer> triangles, int[] indices) {
        Set<Integer> uniqueVertexIndices = new LinkedHashSet<>();

        for (int triIdx : triangles) {
            uniqueVertexIndices.add(indices[triIdx * 3]);
            uniqueVertexIndices.add(indices[triIdx * 3 + 1]);
            uniqueVertexIndices.add(indices[triIdx * 3 + 2]);
        }

        return uniqueVertexIndices.toArray(new Integer[0]);
    }
}
