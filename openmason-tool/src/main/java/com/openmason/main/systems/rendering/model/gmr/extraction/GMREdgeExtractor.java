package com.openmason.main.systems.rendering.model.gmr.extraction;

import com.openmason.main.systems.rendering.model.gmr.mapping.ITriangleFaceMapper;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshFace;
import com.openmason.main.systems.rendering.model.gmr.topology.MeshTopology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
     * Extract edge positions using pre-computed MeshTopology.
     * O(1) per face via topology vertex indices, replacing O(T) triangle scanning.
     * Emits per-face polygon-outline edges (preserving duplicates for shared edges).
     *
     * @param vertices Vertex position buffer from GMR
     * @param topology Pre-computed mesh topology
     * @return Array of edge positions, or empty array if extraction fails
     */
    public float[] extractEdgePositions(float[] vertices, MeshTopology topology) {
        if (vertices == null || topology == null) {
            logger.debug("Cannot extract edges: invalid input data");
            return new float[0];
        }

        int faceCount = topology.getFaceCount();
        if (faceCount == 0) {
            return new float[0];
        }

        // Count total edges (one edge per face vertex, forming polygon outlines)
        int totalEdgeCount = 0;
        for (int i = 0; i < faceCount; i++) {
            MeshFace face = topology.getFace(i);
            totalEdgeCount += face != null ? face.vertexCount() : 0;
        }

        float[] edgePositions = new float[totalEdgeCount * FLOATS_PER_EDGE];
        int offset = 0;

        for (int i = 0; i < faceCount; i++) {
            MeshFace face = topology.getFace(i);
            if (face == null || face.vertexCount() == 0) {
                continue;
            }

            int[] uniqueIndices = face.vertexIndices();
            int n = uniqueIndices.length;

            for (int e = 0; e < n; e++) {
                int uIdx1 = uniqueIndices[e];
                int uIdx2 = uniqueIndices[(e + 1) % n];

                int[] meshIndices1 = topology.getMeshIndicesForUniqueVertex(uIdx1);
                int[] meshIndices2 = topology.getMeshIndicesForUniqueVertex(uIdx2);
                int meshIdx1 = meshIndices1.length > 0 ? meshIndices1[0] : 0;
                int meshIdx2 = meshIndices2.length > 0 ? meshIndices2[0] : 0;

                offset = writeEdgeEndpoint(meshIdx1, vertices, edgePositions, offset);
                offset = writeEdgeEndpoint(meshIdx2, vertices, edgePositions, offset);
            }
        }

        logger.debug("Extracted {} edges ({} floats) via topology", totalEdgeCount, edgePositions.length);
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
        List<Integer> trianglesForFace = FaceTriangleQuery.findTrianglesForFace(faceId, indices, faceMapper);

        if (trianglesForFace.isEmpty()) {
            // No triangles - write zeros for the expected number of edges
            return writeZeroEdges(output, offset, expectedVertexCount);
        }

        // Extract unique vertices forming the face polygon
        Integer[] vertexIndices = FaceTriangleQuery.extractFaceVertexIndices(trianglesForFace, indices);

        // Generate edges: v0→v1, v1→v2, ..., vN-1→v0
        return writePolygonEdges(vertexIndices, vertices, output, offset);
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
     * For a polygon with N vertices, there are exactly N edges.
     *
     * @param output Output buffer
     * @param offset Current offset
     * @param expectedEdgeCount Number of edges to write zeros for (equals vertex count for polygons)
     * @return Updated offset
     */
    private int writeZeroEdges(float[] output, int offset, int expectedEdgeCount) {
        for (int i = 0; i < expectedEdgeCount * FLOATS_PER_EDGE; i++) {
            output[offset++] = 0.0f;
        }
        return offset;
    }
}
