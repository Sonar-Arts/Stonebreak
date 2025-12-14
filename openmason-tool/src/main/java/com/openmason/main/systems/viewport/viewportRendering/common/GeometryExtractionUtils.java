package com.openmason.main.systems.viewport.viewportRendering.common;

import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Collection;

/**
 * Common utility methods for geometry extraction.
 * Provides shared functionality for MeshVertexExtractor, MeshEdgeExtractor, and MeshFaceExtractor.
 * Follows DRY principle by centralizing common logic.
 */
public final class GeometryExtractionUtils {

    private GeometryExtractionUtils() {
        // Prevent instantiation
    }

    /**
     * Count total vertices from a collection of model parts.
     *
     * @param parts Model parts to count
     * @return Total number of vertex floats (vertices * 3)
     */
    public static int countTotalVertices(Collection<ModelDefinition.ModelPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }

        int totalVertices = 0;
        for (ModelDefinition.ModelPart part : parts) {
            if (part != null) {
                totalVertices += part.getVerticesAtOrigin().length;
            }
        }
        return totalVertices;
    }

    /**
     * Create a combined transformation matrix from global and local transforms.
     *
     * @param globalTransform Global transformation matrix
     * @param partTransform Part's local transformation matrix
     * @return Combined transformation matrix (global * local)
     */
    public static Matrix4f createFinalTransform(Matrix4f globalTransform, Matrix4f partTransform) {
        return new Matrix4f(globalTransform).mul(partTransform);
    }

    /**
     * Transform a vertex position using a transformation matrix.
     *
     * @param localVertices Local vertex array
     * @param vertexIndex Starting index in vertex array (must be aligned to 3)
     * @param finalTransform Transformation matrix to apply
     * @param result Vector4f to store transformed result
     * @return The result vector (for chaining)
     */
    public static Vector4f transformVertex(float[] localVertices, int vertexIndex,
                                           Matrix4f finalTransform, Vector4f result) {
        result.set(
            localVertices[vertexIndex],
            localVertices[vertexIndex + 1],
            localVertices[vertexIndex + 2],
            1.0f
        );
        return finalTransform.transform(result);
    }

    /**
     * Validate input parameters for geometry extraction.
     *
     * @param parts Model parts to validate
     * @param globalTransform Global transform to validate
     * @throws IllegalArgumentException if validation fails
     */
    public static void validateExtractionParams(Collection<ModelDefinition.ModelPart> parts,
                                                 Matrix4f globalTransform) {
        if (parts == null) {
            throw new IllegalArgumentException("Model parts cannot be null");
        }
        if (globalTransform == null) {
            throw new IllegalArgumentException("Global transform cannot be null");
        }
    }
}
