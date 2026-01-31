package com.openmason.main.systems.rendering.model.gmr.extraction;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single Responsibility: Extract edge positions from GenericModelRenderer's internal mesh data.
 *
 * This extractor operates on GMR's vertex buffer, index buffer, and face mapping to produce
 * edge data for overlay rendering. Each edge is represented as 2 endpoints (6 floats).
 *
 * Topology-aware: Supports faces with any vertex count (triangles, quads, n-gons)
 * by querying the face mapper for per-face vertex counts.
 *
 * Architecture:
 * - Input: GMR's vertices (float[]), indices (int[]), and ITriangleFaceMapper
 * - Output: Edge positions [x1,y1,z1, x2,y2,z2, ...] (6 floats per edge)
 * - Used by: EdgeRenderer for overlay visualization
 */
public class GMREdgeExtractor {

    private static final Logger logger = LoggerFactory.getLogger(GMREdgeExtractor.class);
    private static final int FLOATS_PER_EDGE = 6; // 2 endpoints × 3 coords

    @Deprecated
    public static final int EDGES_PER_FACE = 4; // Quad has 4 edges

    /**
     * Extract edge positions from GMR mesh data.
     * Each face contributes edges equal to its vertex count, forming a polygon outline.
     *
     * @param vertices Vertex position buffer from GMR
     * @param indices Index buffer from GMR
     * @param faceMapper Triangle-to-face mapper from GMR
     * @return Array of edge positions, or empty array if extraction fails
     */
    public float[] extractEdgePositions(float[] vertices, int[] indices, ITriangleFaceMapper faceMapper) {
        // Validate inputs
        if (vertices == null || indices == null || faceMapper == null || !faceMapper.hasMapping()) {
            logger.debug("Cannot extract edges: invalid input data");
            return new float[0];
        }

        int faceIdUpperBound = faceMapper.getFaceIdUpperBound();
        if (faceIdUpperBound == 0) {
            return new float[0];
        }

        // Compute total edge count dynamically from per-face topology
        // Uses upper bound to handle non-contiguous face IDs (gap IDs contribute 0 edges)
        int totalEdgeCount = 0;
        for (int faceId = 0; faceId < faceIdUpperBound; faceId++) {
            totalEdgeCount += faceMapper.getEdgeCountForFace(faceId);
        }

        float[] edgePositions = new float[totalEdgeCount * FLOATS_PER_EDGE];
        int offset = 0;

        // Extract edges for each face
        for (int faceId = 0; faceId < faceIdUpperBound; faceId++) {
            if (faceMapper.getTriangleCountForFace(faceId) == 0) {
                continue; // Skip gap face IDs
            }
            offset = extractFaceEdges(faceId, vertices, indices, faceMapper, edgePositions, offset);
        }

        logger.debug("Extracted {} edges ({} floats)", totalEdgeCount, edgePositions.length);
        return edgePositions;
    }

    /**
     * Extract the edges of a single face based on its actual vertex count.
     *
     * @param faceId Face ID to extract edges from
     * @param vertices Vertex buffer
     * @param indices Index buffer
     * @param faceMapper Face mapping
     * @param output Output buffer to write to
     * @param offset Current offset in output buffer
     * @return Updated offset after writing edge data
     */
    private int extractFaceEdges(int faceId, float[] vertices, int[] indices,
                                  ITriangleFaceMapper faceMapper, float[] output, int offset) {
        int expectedVertexCount = faceMapper.getVertexCountForFace(faceId);

        // Find all triangles belonging to this face
        List<Integer> trianglesForFace = findTrianglesForFace(faceId, indices, faceMapper);

        if (trianglesForFace.isEmpty()) {
            // No triangles - write zeros for the expected number of edges
            return writeZeroEdges(output, offset, expectedVertexCount);
        }

        // Extract unique vertices forming the face polygon
        Integer[] vertexIndices = extractFaceVertexIndices(trianglesForFace, indices);

        // Generate edges: v0→v1, v1→v2, ..., vN-1→v0
        return writePolygonEdges(vertexIndices, vertices, output, offset);
    }

    /**
     * Find all triangle indices that belong to a specific face.
     */
    private List<Integer> findTrianglesForFace(int faceId, int[] indices, ITriangleFaceMapper faceMapper) {
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
     * Extract unique vertex indices from face triangles.
     */
    private Integer[] extractFaceVertexIndices(List<Integer> triangles, int[] indices) {
        Set<Integer> uniqueVertexIndices = new LinkedHashSet<>();

        for (int triIdx : triangles) {
            uniqueVertexIndices.add(indices[triIdx * 3]);
            uniqueVertexIndices.add(indices[triIdx * 3 + 1]);
            uniqueVertexIndices.add(indices[triIdx * 3 + 2]);
        }

        return uniqueVertexIndices.toArray(new Integer[0]);
    }

    /**
     * Write edges forming a polygon outline for any vertex count.
     * For N vertices: v0→v1, v1→v2, ..., vN-1→v0.
     */
    private int writePolygonEdges(Integer[] vertexIndices, float[] vertices, float[] output, int offset) {
        int vertexCount = vertexIndices.length;
        for (int edge = 0; edge < vertexCount; edge++) {
            int v1Idx = vertexIndices[edge];
            int v2Idx = vertexIndices[(edge + 1) % vertexCount];

            offset = writeEdgeEndpoint(v1Idx, vertices, output, offset);
            offset = writeEdgeEndpoint(v2Idx, vertices, output, offset);
        }

        return offset;
    }

    /**
     * Write a single edge endpoint (3 floats).
     */
    private int writeEdgeEndpoint(int vertexIdx, float[] vertices, float[] output, int offset) {
        int vOffset = vertexIdx * 3;

        if (vOffset + 2 < vertices.length) {
            output[offset++] = vertices[vOffset];
            output[offset++] = vertices[vOffset + 1];
            output[offset++] = vertices[vOffset + 2];
        } else {
            output[offset++] = 0.0f;
            output[offset++] = 0.0f;
            output[offset++] = 0.0f;
        }

        return offset;
    }

    /**
     * Write zero-filled edges for a face with no triangles.
     *
     * @param output Output buffer
     * @param offset Current offset
     * @param edgeCount Number of edges to write zeros for
     * @return Updated offset
     */
    private int writeZeroEdges(float[] output, int offset, int edgeCount) {
        for (int i = 0; i < edgeCount * FLOATS_PER_EDGE; i++) {
            output[offset++] = 0.0f;
        }
        return offset;
    }
}
