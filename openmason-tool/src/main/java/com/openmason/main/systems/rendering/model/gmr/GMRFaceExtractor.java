package com.openmason.main.systems.rendering.model.gmr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Single Responsibility: Extract face positions from GenericModelRenderer's internal mesh data.
 *
 * This extractor operates on GMR's vertex buffer, index buffer, and face mapping to produce
 * face quad data for overlay rendering. Each face is represented as 4 vertices forming a quad.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles face extraction from GMR mesh data
 * - Open/Closed: Can be extended for different face extraction strategies
 * - Liskov Substitution: Implements consistent extraction contract
 * - Interface Segregation: Focused on face extraction only
 * - Dependency Inversion: Operates on data abstractions, not concrete GMR internals
 *
 * Architecture:
 * - Input: GMR's vertices (float[]), indices (int[]), and ITriangleFaceMapper
 * - Output: Face positions as quads [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...]
 * - Used by: FaceRenderer for overlay visualization
 */
public class GMRFaceExtractor {

    private static final Logger logger = LoggerFactory.getLogger(GMRFaceExtractor.class);
    private static final int FLOATS_PER_FACE = 12; // 4 vertices × 3 coords

    /**
     * Extract face positions from GMR mesh data.
     *
     * @param vertices Vertex position buffer from GMR
     * @param indices Index buffer from GMR
     * @param faceMapper Triangle-to-face mapper from GMR
     * @return Array of face positions, or empty array if extraction fails
     */
    public float[] extractFacePositions(float[] vertices, int[] indices, ITriangleFaceMapper faceMapper) {
        // Validate inputs
        if (vertices == null || indices == null || faceMapper == null || !faceMapper.hasMapping()) {
            logger.debug("Cannot extract faces: invalid input data");
            return new float[0];
        }

        int faceCount = faceMapper.getOriginalFaceCount();
        if (faceCount == 0) {
            return new float[0];
        }

        // Allocate output buffer
        float[] facePositions = new float[faceCount * FLOATS_PER_FACE];
        int offset = 0;

        // Extract each face
        for (int faceId = 0; faceId < faceCount; faceId++) {
            offset = extractSingleFace(faceId, vertices, indices, faceMapper, facePositions, offset);
        }

        logger.debug("Extracted {} faces ({} floats)", faceCount, facePositions.length);
        return facePositions;
    }

    /**
     * Extract a single face's vertex positions.
     *
     * @param faceId Face ID to extract
     * @param vertices Vertex buffer
     * @param indices Index buffer
     * @param faceMapper Face mapping
     * @param output Output buffer to write to
     * @param offset Current offset in output buffer
     * @return Updated offset after writing face data
     */
    private int extractSingleFace(int faceId, float[] vertices, int[] indices,
                                   ITriangleFaceMapper faceMapper, float[] output, int offset) {
        // Find all triangles belonging to this face
        List<Integer> trianglesForFace = findTrianglesForFace(faceId, indices, faceMapper);

        if (trianglesForFace.isEmpty()) {
            // No triangles - write zeros
            return writeZeroFace(output, offset);
        }

        // Extract unique vertices from triangles (preserving winding order)
        Integer[] vertexIndices = extractFaceVertexIndices(trianglesForFace, indices);

        // Write vertex positions to output
        return writeFacePositions(vertexIndices, vertices, output, offset);
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
     * For a quad split as [v0,v1,v2] and [v0,v2,v3], returns [v0, v1, v2, v3].
     */
    private Integer[] extractFaceVertexIndices(List<Integer> triangles, int[] indices) {
        Set<Integer> uniqueVertexIndices = new LinkedHashSet<>();

        for (int triIdx : triangles) {
            int i0 = indices[triIdx * 3];
            int i1 = indices[triIdx * 3 + 1];
            int i2 = indices[triIdx * 3 + 2];
            uniqueVertexIndices.add(i0);
            uniqueVertexIndices.add(i1);
            uniqueVertexIndices.add(i2);
        }

        return uniqueVertexIndices.toArray(new Integer[0]);
    }

    /**
     * Write face vertex positions to output buffer.
     * Writes up to 4 vertices, padding with the last vertex if fewer than 4.
     */
    private int writeFacePositions(Integer[] vertexIndices, float[] vertices, float[] output, int offset) {
        for (int i = 0; i < 4; i++) {
            int vIdx = i < vertexIndices.length ? vertexIndices[i] : vertexIndices[vertexIndices.length - 1];
            int vOffset = vIdx * 3;

            if (vOffset + 2 < vertices.length) {
                output[offset++] = vertices[vOffset];
                output[offset++] = vertices[vOffset + 1];
                output[offset++] = vertices[vOffset + 2];
            } else {
                output[offset++] = 0.0f;
                output[offset++] = 0.0f;
                output[offset++] = 0.0f;
            }
        }

        return offset;
    }

    /**
     * Write a zero-filled face (12 floats).
     */
    private int writeZeroFace(float[] output, int offset) {
        for (int i = 0; i < FLOATS_PER_FACE; i++) {
            output[offset++] = 0.0f;
        }
        return offset;
    }
}
