package com.openmason.ui.viewport.rendering.edge;

import com.stonebreak.model.ModelDefinition;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Extracts edges from model data and applies transformations.
 * Mirrors VertexExtractor pattern for consistency.
 */
public class EdgeExtractor {

    private static final Logger logger = LoggerFactory.getLogger(EdgeExtractor.class);

    /**
     * Extract edges from a collection of model parts with transformation applied.
     * Each face (4 vertices) generates 4 edges forming a quad outline.
     */
    public float[] extractEdges(Collection<ModelDefinition.ModelPart> parts, Matrix4f globalTransform) {
        if (parts == null || parts.isEmpty()) {
            return new float[0];
        }

        // Count total edges: each face (4 vertices) has 4 edges
        int totalVertices = 0;
        for (ModelDefinition.ModelPart part : parts) {
            if (part != null) {
                totalVertices += part.getVerticesAtOrigin().length;
            }
        }

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

                // Combine global and local transforms
                Matrix4f finalTransform = new Matrix4f(globalTransform).mul(partTransform);

                // Process each face (4 vertices = 1 quad face)
                for (int faceStart = 0; faceStart < localVertices.length; faceStart += 12) {
                    // Transform the 4 vertices of this face
                    for (int i = 0; i < 4; i++) {
                        int vertexIndex = faceStart + (i * 3);
                        vertex.set(
                            localVertices[vertexIndex],
                            localVertices[vertexIndex + 1],
                            localVertices[vertexIndex + 2],
                            1.0f
                        );
                        finalTransform.transform(vertex);
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
