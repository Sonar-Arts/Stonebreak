package com.openmason.main.systems.viewport.viewportRendering.mesh.edgeOperations;

import com.openmason.main.systems.viewport.viewportRendering.common.GeometryExtractionUtils;
import com.openmason.main.systems.viewport.viewportRendering.common.IGeometryExtractor;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

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
}
