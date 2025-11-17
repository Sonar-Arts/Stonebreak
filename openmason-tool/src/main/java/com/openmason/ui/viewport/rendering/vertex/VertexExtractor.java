package com.openmason.ui.viewport.rendering.vertex;

import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Extracts vertices from model data and applies transformations.
 * Follows Blender's approach: vertices stored in local space, rendered in world space.
 *
 * Single Responsibility: Only handles vertex extraction and transformation.
 * No rendering logic - purely data extraction.
 *
 * KISS: Simple, focused functionality
 * DRY: Single implementation works for all model types
 * SOLID: Single responsibility, works with any ModelPart collection
 * YAGNI: No unnecessary features, no model-type-specific code
 */
public class VertexExtractor {

    private static final Logger logger = LoggerFactory.getLogger(VertexExtractor.class);

    /**
     * Extract vertices from a collection of model parts with transformation applied.
     * Generic method that works with ANY model type - cow, cube, sheep, etc.
     *
     * @param parts Collection of model parts to extract vertices from
     * @param globalTransform Global transformation matrix (from viewport controls)
     * @return Array of transformed vertex positions [x, y, z, x, y, z, ...]
     */
    public float[] extractVertices(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        if (parts == null || parts.isEmpty()) {
            return new float[0];
        }

        // Count total vertices first for efficient array allocation
        int totalVertices = 0;
        for (ModelDefinition.ModelPart part : parts) {
            if (part != null) {
                totalVertices += part.getVerticesAtOrigin().length;
            }
        }

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

                // Combine global and local transforms (matches Blender: local -> world)
                Matrix4f finalTransform = new Matrix4f(globalTransform).mul(partTransform);

                // Transform each vertex
                for (int i = 0; i < localVertices.length; i += 3) {
                    // Set vertex position (w=1.0 for position transform)
                    vertex.set(localVertices[i], localVertices[i + 1], localVertices[i + 2], 1.0f);

                    // Apply transformation
                    finalTransform.transform(vertex);

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
}
