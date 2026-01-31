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
 * SOLID Principles:
 * - Single Responsibility: Only handles edge extraction from GMR mesh data
 * - Open/Closed: Can be extended for different edge extraction strategies
 * - Liskov Substitution: Implements consistent extraction contract
 * - Interface Segregation: Focused on edge extraction only
 * - Dependency Inversion: Operates on data abstractions, not concrete GMR internals
 *
 * Architecture:
 * - Input: GMR's vertices (float[]), indices (int[]), and ITriangleFaceMapper
 * - Output: Edge positions [x1,y1,z1, x2,y2,z2, ...] (6 floats per edge)
 * - Used by: EdgeRenderer for overlay visualization
 */
public class GMREdgeExtractor {

    private static final Logger logger = LoggerFactory.getLogger(GMREdgeExtractor.class);
    private static final int FLOATS_PER_EDGE = 6; // 2 endpoints × 3 coords
    private static final int EDGES_PER_FACE = 4; // Quad has 4 edges

    /**
     * Extract edge positions from GMR mesh data.
     * Each face contributes 4 edges forming a quad outline.
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

        int faceCount = faceMapper.getOriginalFaceCount();
        if (faceCount == 0) {
            return new float[0];
        }

        // Each face has 4 edges, each edge has 6 floats (2 endpoints × 3 coords)
        float[] edgePositions = new float[faceCount * EDGES_PER_FACE * FLOATS_PER_EDGE];
        int offset = 0;

        // Extract edges for each face
        for (int faceId = 0; faceId < faceCount; faceId++) {
            offset = extractFaceEdges(faceId, vertices, indices, faceMapper, edgePositions, offset);
        }

        logger.debug("Extracted {} edges ({} floats)", faceCount * EDGES_PER_FACE, edgePositions.length);
        return edgePositions;
    }

    /**
     * Extract the 4 edges of a single face.
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
        // Find all triangles belonging to this face
        List<Integer> trianglesForFace = findTrianglesForFace(faceId, indices, faceMapper);

        if (trianglesForFace.isEmpty()) {
            // No triangles - write zeros
            return writeZeroEdges(output, offset);
        }

        // Extract unique vertices forming the face quad
        Integer[] vertexIndices = extractFaceVertexIndices(trianglesForFace, indices);

        // Pad to 4 vertices if needed
        vertexIndices = padToQuad(vertexIndices);

        // Generate 4 edges: v0→v1, v1→v2, v2→v3, v3→v0
        return writeQuadEdges(vertexIndices, vertices, output, offset);
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
     * Pad vertex indices to 4 if fewer (for degenerate faces).
     */
    private Integer[] padToQuad(Integer[] vertexIndices) {
        if (vertexIndices.length >= 4) {
            return vertexIndices;
        }

        Integer[] padded = new Integer[4];
        for (int i = 0; i < 4; i++) {
            padded[i] = i < vertexIndices.length ? vertexIndices[i] : vertexIndices[vertexIndices.length - 1];
        }

        return padded;
    }

    /**
     * Write 4 edges forming a quad outline: v0→v1, v1→v2, v2→v3, v3→v0.
     */
    private int writeQuadEdges(Integer[] vertexIndices, float[] vertices, float[] output, int offset) {
        for (int edge = 0; edge < EDGES_PER_FACE; edge++) {
            int v1Idx = vertexIndices[edge];
            int v2Idx = vertexIndices[(edge + 1) % 4];

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
     * Write zero-filled edges for a face with no triangles (24 floats = 4 edges × 6 floats).
     */
    private int writeZeroEdges(float[] output, int offset) {
        for (int i = 0; i < EDGES_PER_FACE * FLOATS_PER_EDGE; i++) {
            output[offset++] = 0.0f;
        }
        return offset;
    }
}
