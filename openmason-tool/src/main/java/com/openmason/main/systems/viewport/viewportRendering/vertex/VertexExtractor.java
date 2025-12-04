package com.openmason.main.systems.viewport.viewportRendering.vertex;

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
import java.util.List;

/**
 * Extracts vertices from model data and applies transformations.
 * Implements IGeometryExtractor for consistency with EdgeExtractor.
 * Uses GeometryExtractionUtils for shared functionality (DRY principle).
 * Follows Blender's approach: vertices stored in local space, rendered in world space.
 */
public class VertexExtractor implements IGeometryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(VertexExtractor.class);

    /**
     * Extract vertices from a collection of model parts with transformation applied.
     * Generic method that works with ANY model type - cow, cube, sheep, etc.
     * Interface implementation that validates inputs using common utilities.
     *
     * @param parts Model parts to extract from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of vertex positions [x1,y1,z1, x2,y2,z2, ...]
     */
    @Override
    public float[] extractGeometry(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        // Validate input parameters using shared utility
        GeometryExtractionUtils.validateExtractionParams(parts, globalTransform);

        if (parts.isEmpty()) {
            return new float[0];
        }

        return extractVertices(parts, globalTransform);
    }

    /**
     * Extract vertices from a collection of model parts with transformation applied.
     * Generic method that works with ANY model type - cow, cube, sheep, etc.
     * Note: Callers should use extractGeometry() for validation, or ensure inputs are valid.
     */
    public float[] extractVertices(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        // Note: Validation done in extractGeometry(), but we keep it here for direct callers
        if (parts == null || parts.isEmpty()) {
            return new float[0];
        }

        // Count total vertices using shared utility
        int totalVertices = GeometryExtractionUtils.countTotalVertices(parts);

        if (totalVertices == 0) {
            return new float[0];
        }

        // Allocate result array
        float[] result = new float[totalVertices];
        int offset = 0;

        // Extract and transform vertices from each part
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

                // Transform each vertex using shared utility
                for (int i = 0; i < localVertices.length; i += 3) {
                    GeometryExtractionUtils.transformVertex(localVertices, i, finalTransform, vertex);

                    // Store transformed position
                    result[offset++] = vertex.x;
                    result[offset++] = vertex.y;
                    result[offset++] = vertex.z;
                }

            } catch (Exception e) {
                logger.error("Error extracting vertices from part: {}", part.getName(), e);
            }
        }

        return result;
    }

    /**
     * Extract UNIQUE vertices from a collection of model parts with transformation applied.
     * Deduplicates vertices at the same position using epsilon comparison.
     * This prevents vertex duplication when mesh has multiple vertices at the same corner.
     * For example, a cube mesh has 24 vertices (4 per face × 6 faces), but only 8 unique corners.
     *
     * @param parts Model parts to extract vertices from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of unique vertex positions [x1,y1,z1, x2,y2,z2, ...]
     */
    public float[] extractUniqueVertices(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        // Note: Validation handled by extractVertices()
        if (parts == null || parts.isEmpty()) {
            return new float[0];
        }

        // First, extract ALL vertices using existing logic
        float[] allVertices = extractVertices(parts, globalTransform);

        if (allVertices.length == 0) {
            return new float[0];
        }

        // Convert to Vector3f list for easier deduplication
        List<Vector3f> uniqueVertices = new ArrayList<>();
        float epsilon = 0.0001f; // Epsilon for floating point comparison

        for (int i = 0; i < allVertices.length; i += 3) {
            Vector3f vertex = new Vector3f(allVertices[i], allVertices[i + 1], allVertices[i + 2]);

            // Check if this vertex is a duplicate of any existing unique vertex
            boolean isDuplicate = false;
            for (Vector3f unique : uniqueVertices) {
                if (vertex.distance(unique) < epsilon) {
                    isDuplicate = true;
                    break;
                }
            }

            // Only add if not a duplicate
            if (!isDuplicate) {
                uniqueVertices.add(vertex);
            }
        }

        // Convert back to float array
        float[] result = new float[uniqueVertices.size() * 3];
        int offset = 0;
        for (Vector3f vertex : uniqueVertices) {
            result[offset++] = vertex.x;
            result[offset++] = vertex.y;
            result[offset++] = vertex.z;
        }

        logger.debug("Extracted {} unique vertices from {} total vertices",
                uniqueVertices.size(), allVertices.length / 3);

        return result;
    }
}
