package com.openmason.main.systems.viewport.viewportRendering.mesh.faceOperations;

import com.openmason.main.systems.viewport.viewportRendering.common.GeometryExtractionUtils;
import com.openmason.main.systems.viewport.viewportRendering.common.IGeometryExtractor;
import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Single Responsibility: Extracts face geometry from model data with transformations.
 * This class extracts faces from model parts and applies global/local transformations.
 *
 * SOLID Principles:
 * - Single Responsibility: Only handles face extraction from model data
 * - Open/Closed: Can be extended for additional extraction strategies
 * - Liskov Substitution: Implements IGeometryExtractor contract
 * - Interface Segregation: Focused interface for geometry extraction
 * - Dependency Inversion: Depends on abstractions (IGeometryExtractor, ModelDefinition)
 *
 * KISS Principle: Straightforward face extraction with transformation application.
 * DRY Principle: Uses GeometryExtractionUtils for shared validation and transformation logic.
 * YAGNI Principle: Only implements face extraction without unnecessary features.
 *
 * Thread Safety: This class is stateless and thread-safe.
 * All data is passed as parameters and no state is maintained.
 *
 * Architecture Note: Supports mesh operations instead of directly feeding the renderer.
 * This class provides mesh data that can be used by face operation classes like
 * MeshFaceUpdateOperation, MeshFaceVertexMatcher, etc.
 *
 * Data Format: Each face is represented as 4 vertices (quad) in counter-clockwise order.
 * For a cube: 6 faces × 4 vertices × 3 floats = 72 floats total.
 */
public class MeshFaceExtractor implements IGeometryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(MeshFaceExtractor.class);

    /**
     * Extract faces from a collection of model parts with transformation applied.
     * Each face is represented as 4 vertices (quad corners) with 12 floats per face.
     * Interface implementation that validates inputs using common utilities.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of face vertex positions [v0x,v0y,v0z, v1x,v1y,v1z, v2x,v2y,v2z, v3x,v3y,v3z, ...]
     */
    @Override
    public float[] extractGeometry(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        // Validate input parameters using shared utility
        GeometryExtractionUtils.validateExtractionParams(parts, globalTransform);

        if (parts.isEmpty()) {
            return new float[0];
        }

        return extractFaces(parts, globalTransform);
    }

    /**
     * Extract faces from a collection of model parts with transformation applied.
     * Each face (4 vertices) is extracted and transformed to world space.
     * Note: Callers should use extractGeometry() for validation, or ensure inputs are valid.
     */
    public float[] extractFaces(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        // Note: Validation done in extractGeometry(), but we keep it here for direct callers
        if (parts == null || parts.isEmpty()) {
            return new float[0];
        }

        // Count total vertices using shared utility
        int totalVertices = GeometryExtractionUtils.countTotalVertices(parts);

        if (totalVertices == 0) {
            return new float[0];
        }

        // Each face has 4 vertices × 3 floats = 12 floats per face
        // Total vertices are already in groups of 12 (one face per group)
        int faceCount = totalVertices / 12; // 12 floats per face (4 corners × 3 floats)
        int resultSize = totalVertices; // Same as totalVertices since we keep all face vertices

        // Allocate result array
        float[] result = new float[resultSize];
        int offset = 0;

        // Extract and transform faces from each part
        Vector4f vertex = new Vector4f();

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

                // Process each face (12 floats = 4 vertices × 3 coords)
                for (int faceStart = 0; faceStart < localVertices.length; faceStart += 12) {
                    // Transform the 4 vertices of this face
                    for (int i = 0; i < 4; i++) {
                        int vertexIndex = faceStart + (i * 3);

                        // Transform vertex using shared utility
                        GeometryExtractionUtils.transformVertex(localVertices, vertexIndex, finalTransform, vertex);

                        // Store transformed vertex
                        result[offset++] = vertex.x;
                        result[offset++] = vertex.y;
                        result[offset++] = vertex.z;
                    }
                }

            } catch (Exception e) {
                logger.error("Error extracting faces from part: {}", part.getName(), e);
            }
        }

        logger.debug("Extracted {} faces ({} floats) from {} parts", faceCount, result.length, parts.size());
        return result;
    }
}
