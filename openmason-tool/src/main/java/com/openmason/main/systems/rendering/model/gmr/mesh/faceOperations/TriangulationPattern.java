package com.openmason.main.systems.rendering.model.gmr.mesh.faceOperations;

/**
 * Represents a triangulation pattern for converting polygons to VBO triangles.
 * Defines how to map polygon corners to VBO triangle vertices.
 *
 * Shape-Blind Design:
 * Supports arbitrary triangulation patterns (quads, triangles, n-gons).
 *
 * Example Patterns:
 * - Quad (0,1,2,3) → Triangles: [0,1,2] [0,2,3] → indices = [[0,1,2], [0,2,3]]
 * - Triangle (0,1,2) → Triangles: [0,1,2] → indices = [[0,1,2]]
 * - Pentagon (0,1,2,3,4) → Triangles: [0,1,2] [0,2,3] [0,3,4] → indices = [[0,1,2], [0,2,3], [0,3,4]]
 *
 * @param indices 2D array where indices[triangle][vertex] maps to corner indices
 *                Example: [[0,1,2], [0,2,3]] for quad triangulation
 */
public record TriangulationPattern(int[][] indices) {

    /**
     * Standard quad triangulation pattern: (0,1,2) and (0,2,3).
     * Converts a quad with corners [v0, v1, v2, v3] into two triangles.
     */
    public static final TriangulationPattern QUAD = new TriangulationPattern(new int[][] {
        {0, 1, 2},  // First triangle: v0, v1, v2
        {0, 2, 3}   // Second triangle: v0, v2, v3
    });

    /**
     * Standard triangle pattern: (0,1,2).
     * Single triangle, no subdivision needed.
     */
    public static final TriangulationPattern TRIANGLE = new TriangulationPattern(new int[][] {
        {0, 1, 2}   // Single triangle: v0, v1, v2
    });

    /**
     * Get the number of triangles in this pattern.
     *
     * @return Number of triangles
     */
    public int getTriangleCount() {
        return indices.length;
    }

    /**
     * Get the number of VBO vertices needed for this pattern.
     * Each triangle requires 3 vertices.
     *
     * @return Total VBO vertices (triangleCount * 3)
     */
    public int getVBOVertexCount() {
        return indices.length * 3;
    }

    /**
     * Get the corner index for a specific VBO vertex.
     *
     * @param vboVertexIndex VBO vertex index (0 to getVBOVertexCount()-1)
     * @return Corner index in the original polygon
     */
    public int getCornerIndex(int vboVertexIndex) {
        int triangleIndex = vboVertexIndex / 3;
        int vertexInTriangle = vboVertexIndex % 3;
        return indices[triangleIndex][vertexInTriangle];
    }

    /**
     * Create a fan triangulation pattern for an N-gon.
     * Uses vertex 0 as the hub: triangles are (0,1,2), (0,2,3), ..., (0,N-2,N-1).
     * Returns cached instances for triangles (N=3) and quads (N=4).
     *
     * @param vertexCount Number of vertices in the polygon (must be >= 3)
     * @return TriangulationPattern for the N-gon
     * @throws IllegalArgumentException if vertexCount < 3
     */
    public static TriangulationPattern forNGon(int vertexCount) {
        if (vertexCount < 3) {
            throw new IllegalArgumentException("Polygon must have at least 3 vertices, got " + vertexCount);
        }
        if (vertexCount == 3) {
            return TRIANGLE;
        }
        if (vertexCount == 4) {
            return QUAD;
        }

        // Fan triangulation: N-2 triangles for an N-gon
        int triangleCount = vertexCount - 2;
        int[][] fanIndices = new int[triangleCount][3];
        for (int i = 0; i < triangleCount; i++) {
            fanIndices[i][0] = 0;
            fanIndices[i][1] = i + 1;
            fanIndices[i][2] = i + 2;
        }
        return new TriangulationPattern(fanIndices);
    }

    /**
     * Validate the triangulation pattern.
     *
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        if (indices == null || indices.length == 0) {
            return false;
        }

        // Each triangle must have exactly 3 vertices
        for (int[] triangle : indices) {
            if (triangle == null || triangle.length != 3) {
                return false;
            }

            // Corner indices must be non-negative
            for (int cornerIdx : triangle) {
                if (cornerIdx < 0) {
                    return false;
                }
            }
        }

        return true;
    }
}
