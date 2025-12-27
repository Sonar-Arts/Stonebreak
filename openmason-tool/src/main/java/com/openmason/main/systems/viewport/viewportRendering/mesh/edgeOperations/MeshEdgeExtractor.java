package com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations;

import com.openmason.main.systems.viewport.viewportRendering.common.GeometryExtractionUtils;
import com.openmason.main.systems.viewport.viewportRendering.common.IGeometryExtractor;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single Responsibility: Extracts edge geometry from model data with transformations.
 * This class extracts edges from model parts and applies global/local transformations.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles edge extraction from model data
 * - Open/Closed: Can be extended for additional extraction strategies
 * - Liskov Substitution: Implements IGeometryExtractor contract
 * - Interface Segregation: Focused interface for geometry extraction
 * - Dependency Inversion: Depends on abstractions (IGeometryExtractor, ModelDefinition)
 *
 * KISS Principle: Straightforward edge extraction with transformation application.
 * DRY Principle: Uses GeometryExtractionUtils for shared validation and transformation logic.
 * YAGNI Principle: Only implements edge extraction without unnecessary features.
 *
 * Thread Safety: This class is stateless and thread-safe.
 * All data is passed as parameters and no state is maintained.
 *
 * Architecture Note: Supports mesh operations instead of directly feeding the renderer.
 * This class provides mesh data that can be used by edge operation classes like
 * MeshEdgeBufferUpdater, MeshEdgePositionUpdater, etc.
 */
public class MeshEdgeExtractor implements IGeometryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MeshEdgeExtractor.class);

    /**
     * Extract edges from a collection of model parts with transformation applied.
     * Each face (4 vertices) generates 4 edges forming a quad outline.
     * Interface implementation that validates inputs using common utilities.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of edge endpoint positions [x1,y1,z1, x2,y2,z2, ...]
     */
    @Override
    public float[] extractGeometry(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        // Validate input parameters using shared utility
        GeometryExtractionUtils.validateExtractionParams(parts, globalTransform);

        if (parts.isEmpty()) {
            return new float[0];
        }

        return extractEdges(parts, globalTransform);
    }

    /**
     * Extract edges from a collection of model parts with transformation applied.
     * Each face (4 vertices) generates 4 edges forming a quad outline.
     * Note: Callers should use extractGeometry() for validation, or ensure inputs are valid.
     */
    public float[] extractEdges(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        // Note: Validation done in extractGeometry(), but we keep it here for direct callers
        if (parts == null || parts.isEmpty()) {
            return new float[0];
        }

        // Count total vertices using shared utility
        int totalVertices = GeometryExtractionUtils.countTotalVertices(parts);

        if (totalVertices == 0) {
            return new float[0];
        }

        // Each face has 4 vertices → 4 edges → 8 endpoints (2 per edge)
        int faceCount = totalVertices / 12; // 12 vertices per face (4 corners × 3 floats)
        int edgeEndpointCount = faceCount * 4 * 2 * 3; // faces × 4 edges × 2 endpoints × 3 floats

        // Allocate result array
        float[] result = new float[edgeEndpointCount];
        int offset = 0;

        // Extract and transform edges from each part
        Vector4f vertex = new Vector4f();
        Vector4f[] faceVertices = new Vector4f[4];
        for (int i = 0; i < 4; i++) {
            faceVertices[i] = new Vector4f();
        }

        for (ModelDefinition.ModelPart part : parts) {
            if (part == null) {
                continue;
            }

            try {
                // Get vertices at origin (local space)
                float[] localVertices = part.getVerticesAtOrigin();

                // Get part's local transformation matrix
                Matrix4f partTransform = part.getTransformationMatrix();

                // Combine global and local transforms using shared utility
                Matrix4f finalTransform = GeometryExtractionUtils.createFinalTransform(globalTransform, partTransform);

                // Process each face (4 vertices = 1 quad face)
                for (int faceStart = 0; faceStart < localVertices.length; faceStart += 12) {
                    // Transform the 4 vertices of this face using shared utility
                    for (int i = 0; i < 4; i++) {
                        int vertexIndex = faceStart + (i * 3);
                        GeometryExtractionUtils.transformVertex(localVertices, vertexIndex, finalTransform, vertex);
                        faceVertices[i].set(vertex);
                    }

                    // Generate 4 edges for this face (quad outline)
                    // Edge 0: v0 → v1
                    // Edge 1: v1 → v2
                    // Edge 2: v2 → v3
                    // Edge 3: v3 → v0
                    for (int edge = 0; edge < 4; edge++) {
                        int v1 = edge;
                        int v2 = (edge + 1) % 4;

                        // Store edge start point
                        result[offset++] = faceVertices[v1].x;
                        result[offset++] = faceVertices[v1].y;
                        result[offset++] = faceVertices[v1].z;

                        // Store edge end point
                        result[offset++] = faceVertices[v2].x;
                        result[offset++] = faceVertices[v2].y;
                        result[offset++] = faceVertices[v2].z;
                    }
                }

            } catch (Exception e) {
                logger.error("Error extracting edges from part: {}", part.getName(), e);
            }
        }

        return result;
    }

    /**
     * Extract unique edges from model parts, eliminating duplicates.
     * An edge v1<->v2 is considered the same as v2<->v1.
     * For a cube: returns 12 unique edges instead of 24 face-based edges.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @param uniqueVertexPositions Array of unique vertex positions for matching
     * @return Array of unique edge endpoint positions [x1,y1,z1, x2,y2,z2, ...]
     */
    public float[] extractUniqueEdges(Collection<ModelDefinition.ModelPart> parts,
                                       Matrix4f globalTransform,
                                       float[] uniqueVertexPositions) {
        if (parts == null || parts.isEmpty() || uniqueVertexPositions == null) {
            return new float[0];
        }

        // First extract all face-based edges (with duplicates)
        float[] allEdges = extractEdges(parts, globalTransform);
        if (allEdges.length == 0) {
            return new float[0];
        }

        int totalEdges = allEdges.length / FLOATS_PER_EDGE;
        int uniqueVertexCount = uniqueVertexPositions.length / 3;

        // Track seen edges using normalized key (min, max vertex indices)
        Set<Long> seenEdges = new HashSet<>();
        List<float[]> uniqueEdgeList = new ArrayList<>();

        for (int edgeIdx = 0; edgeIdx < totalEdges; edgeIdx++) {
            int offset = edgeIdx * FLOATS_PER_EDGE;

            // Get edge endpoints
            Vector3f p1 = new Vector3f(allEdges[offset], allEdges[offset + 1], allEdges[offset + 2]);
            Vector3f p2 = new Vector3f(allEdges[offset + 3], allEdges[offset + 4], allEdges[offset + 5]);

            // Find matching unique vertex indices
            int v1Index = findMatchingVertex(p1, uniqueVertexPositions, uniqueVertexCount);
            int v2Index = findMatchingVertex(p2, uniqueVertexPositions, uniqueVertexCount);

            if (v1Index < 0 || v2Index < 0) {
                logger.warn("Edge {} has unmatched vertices", edgeIdx);
                continue;
            }

            // Create normalized edge key (order-independent)
            long edgeKey = createEdgeKey(v1Index, v2Index);

            // Only add if not already seen
            if (!seenEdges.contains(edgeKey)) {
                seenEdges.add(edgeKey);
                uniqueEdgeList.add(new float[] {
                    p1.x, p1.y, p1.z, p2.x, p2.y, p2.z
                });
            }
        }

        // Convert list to array
        float[] result = new float[uniqueEdgeList.size() * FLOATS_PER_EDGE];
        int resultOffset = 0;
        for (float[] edge : uniqueEdgeList) {
            System.arraycopy(edge, 0, result, resultOffset, FLOATS_PER_EDGE);
            resultOffset += FLOATS_PER_EDGE;
        }

        logger.debug("Extracted {} unique edges from {} face-based edges",
                    uniqueEdgeList.size(), totalEdges);

        return result;
    }

    /**
     * Find the index of the unique vertex that matches the given position.
     *
     * @param position Position to match
     * @param uniqueVertexPositions Array of unique vertex positions
     * @param vertexCount Number of unique vertices
     * @return Vertex index, or -1 if not found
     */
    private int findMatchingVertex(Vector3f position, float[] uniqueVertexPositions, int vertexCount) {
        for (int i = 0; i < vertexCount; i++) {
            int offset = i * 3;
            float dx = position.x - uniqueVertexPositions[offset];
            float dy = position.y - uniqueVertexPositions[offset + 1];
            float dz = position.z - uniqueVertexPositions[offset + 2];
            float distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < VERTEX_EPSILON * VERTEX_EPSILON) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Create a normalized edge key that is order-independent.
     * Edge (v1, v2) produces the same key as edge (v2, v1).
     *
     * @param v1 First vertex index
     * @param v2 Second vertex index
     * @return Unique key for this edge
     */
    private long createEdgeKey(int v1, int v2) {
        int min = Math.min(v1, v2);
        int max = Math.max(v1, v2);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    /** Epsilon for vertex position matching. */
    private static final float VERTEX_EPSILON = 0.0001f;

    /** Number of floats per edge (2 endpoints × 3 coordinates). */
    private static final int FLOATS_PER_EDGE = 6;
}
