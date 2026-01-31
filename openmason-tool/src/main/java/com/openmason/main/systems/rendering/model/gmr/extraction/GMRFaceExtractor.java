package com.openmason.main.systems.rendering.model.gmr.extraction;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Single Responsibility: Extract face positions from GenericModelRenderer's internal mesh data.
 *
 * This extractor operates on GMR's vertex buffer, index buffer, and face mapping to produce
 * face vertex data for overlay rendering. Topology-aware: supports faces with any vertex count
 * (triangles, quads, n-gons).
 *
 * Architecture:
 * - Input: GMR's vertices (float[]), indices (int[]), and ITriangleFaceMapper
 * - Output: Face positions as vertex arrays, with structured metadata via FaceExtractionResult
 * - Used by: FaceRenderer for overlay visualization
 */
public class GMRFaceExtractor {

    private static final Logger logger = LoggerFactory.getLogger(GMRFaceExtractor.class);

    @Deprecated
    private static final int FLOATS_PER_FACE = 12; // 4 vertices × 3 coords (legacy quad format)

    /**
     * Structured result from face extraction, containing per-face topology info.
     *
     * @param positions    All vertex positions, packed sequentially (3 floats per vertex)
     * @param faceOffsets  Float offset into positions[] for each face (length = faceCount + 1).
     *                     Face i spans positions[faceOffsets[i]] to positions[faceOffsets[i+1]].
     * @param verticesPerFace Vertex count per face
     * @param faceCount    Number of faces
     */
    public record FaceExtractionResult(
        float[] positions,
        int[] faceOffsets,
        int[] verticesPerFace,
        int faceCount
    ) {}

    /**
     * Extract face data with full topology information.
     * Returns a structured result with per-face vertex counts and offsets.
     *
     * @param vertices Vertex position buffer from GMR
     * @param indices Index buffer from GMR
     * @param faceMapper Triangle-to-face mapper from GMR
     * @return FaceExtractionResult with positions and topology, or null if extraction fails
     */
    public FaceExtractionResult extractFaceData(float[] vertices, int[] indices, ITriangleFaceMapper faceMapper) {
        // Validate inputs
        if (vertices == null || indices == null || faceMapper == null || !faceMapper.hasMapping()) {
            logger.debug("Cannot extract faces: invalid input data");
            return null;
        }

        // Use upper bound for safe iteration over potentially non-contiguous face IDs
        int faceIdUpperBound = faceMapper.getFaceIdUpperBound();
        if (faceIdUpperBound == 0) {
            return null;
        }

        // Compute total vertex count across all faces
        // Array is indexed by face ID, gap IDs get 0 vertices
        int[] verticesPerFace = new int[faceIdUpperBound];
        int totalVertexCount = 0;
        for (int faceId = 0; faceId < faceIdUpperBound; faceId++) {
            verticesPerFace[faceId] = faceMapper.getVertexCountForFace(faceId);
            totalVertexCount += verticesPerFace[faceId];
        }

        // Allocate output
        float[] positions = new float[totalVertexCount * 3];
        int[] faceOffsets = new int[faceIdUpperBound + 1];
        int offset = 0;

        // Extract each face (gap IDs have 0 vertices, so their offset span is empty)
        for (int faceId = 0; faceId < faceIdUpperBound; faceId++) {
            faceOffsets[faceId] = offset;
            if (verticesPerFace[faceId] > 0) {
                offset = extractSingleFace(faceId, verticesPerFace[faceId], vertices, indices, faceMapper, positions, offset);
            }
        }
        faceOffsets[faceIdUpperBound] = offset;

        int actualFaceCount = faceMapper.getOriginalFaceCount();
        logger.debug("Extracted {} faces ({} total vertices, {} floats, upperBound={})",
            actualFaceCount, totalVertexCount, positions.length, faceIdUpperBound);
        return new FaceExtractionResult(positions, faceOffsets, verticesPerFace, faceIdUpperBound);
    }

    /**
     * Extract face positions from GMR mesh data.
     * Legacy method: pads/truncates all faces to 4 vertices (quad format).
     *
     * @param vertices Vertex position buffer from GMR
     * @param indices Index buffer from GMR
     * @param faceMapper Triangle-to-face mapper from GMR
     * @return Array of face positions (12 floats per face), or empty array if extraction fails
     * @deprecated Use {@link #extractFaceData(float[], int[], ITriangleFaceMapper)} for topology-aware extraction
     */
    @Deprecated
    public float[] extractFacePositions(float[] vertices, int[] indices, ITriangleFaceMapper faceMapper) {
        // Validate inputs
        if (vertices == null || indices == null || faceMapper == null || !faceMapper.hasMapping()) {
            logger.debug("Cannot extract faces: invalid input data");
            return new float[0];
        }

        // Use upper bound for safe iteration over potentially non-contiguous face IDs
        int faceIdUpperBound = faceMapper.getFaceIdUpperBound();
        if (faceIdUpperBound == 0) {
            return new float[0];
        }

        // Count actual faces (with triangles) for allocation
        int actualFaceCount = faceMapper.getOriginalFaceCount();

        // Legacy: allocate fixed 12 floats per face (4 vertices × 3 coords)
        float[] facePositions = new float[actualFaceCount * FLOATS_PER_FACE];
        int offset = 0;

        // Extract each face, padding/truncating to 4 vertices
        for (int faceId = 0; faceId < faceIdUpperBound; faceId++) {
            if (faceMapper.getTriangleCountForFace(faceId) == 0) {
                continue; // Skip gap face IDs
            }
            offset = extractSingleFaceLegacy(faceId, vertices, indices, faceMapper, facePositions, offset);
        }

        logger.debug("Extracted {} faces ({} floats) [legacy quad format]", actualFaceCount, facePositions.length);
        return facePositions;
    }

    /**
     * Extract a single face's vertex positions with actual vertex count.
     */
    private int extractSingleFace(int faceId, int expectedVertexCount, float[] vertices, int[] indices,
                                   ITriangleFaceMapper faceMapper, float[] output, int offset) {
        List<Integer> trianglesForFace = findTrianglesForFace(faceId, indices, faceMapper);

        if (trianglesForFace.isEmpty()) {
            // Write zeros for expected vertex count
            for (int i = 0; i < expectedVertexCount * 3; i++) {
                output[offset++] = 0.0f;
            }
            return offset;
        }

        Integer[] vertexIndices = extractFaceVertexIndices(trianglesForFace, indices);

        // Write actual vertex positions
        for (int i = 0; i < expectedVertexCount; i++) {
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
     * Extract a single face's vertex positions in legacy quad format (4 vertices).
     */
    private int extractSingleFaceLegacy(int faceId, float[] vertices, int[] indices,
                                         ITriangleFaceMapper faceMapper, float[] output, int offset) {
        List<Integer> trianglesForFace = findTrianglesForFace(faceId, indices, faceMapper);

        if (trianglesForFace.isEmpty()) {
            return writeZeroFace(output, offset);
        }

        Integer[] vertexIndices = extractFaceVertexIndices(trianglesForFace, indices);
        return writeFacePositionsLegacy(vertexIndices, vertices, output, offset);
    }

    private List<Integer> findTrianglesForFace(int faceId, int[] indices, ITriangleFaceMapper faceMapper) {
        return FaceTriangleQuery.findTrianglesForFace(faceId, indices, faceMapper);
    }

    private Integer[] extractFaceVertexIndices(List<Integer> triangles, int[] indices) {
        return FaceTriangleQuery.extractFaceVertexIndices(triangles, indices);
    }

    /**
     * Write face vertex positions in legacy quad format (4 vertices, padding if fewer).
     */
    private int writeFacePositionsLegacy(Integer[] vertexIndices, float[] vertices, float[] output, int offset) {
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
     * Write a zero-filled face (12 floats for legacy quad format).
     */
    private int writeZeroFace(float[] output, int offset) {
        for (int i = 0; i < FLOATS_PER_FACE; i++) {
            output[offset++] = 0.0f;
        }
        return offset;
    }
}
