package com.openmason.main.systems.viewport.viewportRendering.face;

import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Extracts quad faces from model data and applies transformations.
 * Mirrors EdgeExtractor and VertexExtractor pattern for consistency.
 * Each face is represented as 4 vertices (quad corners).
 */
public class FaceExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FaceExtractor.class);

    /**
     * Extract faces from a collection of model parts with transformation applied.
     * Each face has 4 vertices forming a quad.
     * For a cube: 6 faces, each with 4 vertices.
     *
     * @param parts           Collection of model parts to extract faces from
     * @param globalTransform Global transformation matrix to apply
     * @return Array of face data [face0_v0(x,y,z), face0_v1(x,y,z), face0_v2(x,y,z), face0_v3(x,y,z), face1_v0...]
     *         Total: faceCount * 4 vertices * 3 coords = faceCount * 12 floats
     */
    public float[] extractFaces(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        if (parts == null || parts.isEmpty()) {
            return new float[0];
        }

        // Count total vertices from all parts
        int totalVertices = 0;
        for (ModelDefinition.ModelPart part : parts) {
            if (part != null) {
                totalVertices += part.getVerticesAtOrigin().length;
            }
        }

        if (totalVertices == 0) {
            return new float[0];
        }

        // Calculate face count: Each face has 4 vertices × 3 coords = 12 floats
        int faceCount = totalVertices / 12;

        // Allocate result array: faceCount * 12 floats (4 vertices × 3 coords per face)
        float[] result = new float[faceCount * 12];
        int offset = 0;

        // Reusable vectors for transformation
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

                // Combine global and local transforms
                Matrix4f finalTransform = new Matrix4f(globalTransform).mul(partTransform);

                // Process each face (4 vertices = 1 quad face)
                for (int faceStart = 0; faceStart < localVertices.length; faceStart += 12) {
                    // Transform and store the 4 vertices of this face
                    for (int i = 0; i < 4; i++) {
                        int vertexIndex = faceStart + (i * 3);

                        // Set vertex position (w=1.0 for positions)
                        vertex.set(
                            localVertices[vertexIndex],
                            localVertices[vertexIndex + 1],
                            localVertices[vertexIndex + 2],
                            1.0f
                        );

                        // Apply transformation
                        finalTransform.transform(vertex);

                        // Store transformed vertex position
                        result[offset++] = vertex.x;
                        result[offset++] = vertex.y;
                        result[offset++] = vertex.z;
                    }
                }

            } catch (Exception e) {
                logger.error("Error extracting faces from part: {}", part.getName(), e);
            }
        }

        return result;
    }
}
