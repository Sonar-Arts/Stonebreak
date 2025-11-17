package com.openmason.ui.viewport.rendering;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for detecting vertex hover interactions.
 * Uses screen-space distance with actual vertex point size (same pattern as gizmo).
 *
 * <p>Follows SOLID principles:
 * - Single Responsibility: Only handles vertex hover detection
 * - KISS: Simple screen-space distance check using actual point size
 * - SAFE: Validates all inputs and handles edge cases
 */
public final class VertexHoverDetector {

    private static final Logger logger = LoggerFactory.getLogger(VertexHoverDetector.class);

    // Private constructor to prevent instantiation (utility class)
    private VertexHoverDetector() {
        throw new AssertionError("VertexHoverDetector is a utility class and should not be instantiated");
    }

    /**
     * Detects which vertex (if any) is currently hovered by the mouse.
     * Uses screen-space distance with actual vertex point size (KISS approach).
     *
     * @param mouseX Mouse X coordinate in screen space (pixels)
     * @param mouseY Mouse Y coordinate in screen space (pixels)
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param vertexPositions Array of vertex positions [x, y, z, x, y, z, ...]
     * @param vertexCount Number of vertices
     * @param pointSize Vertex point size in pixels
     * @return Index of the hovered vertex, or -1 if no vertex is hovered
     */
    public static int detectHoveredVertex(float mouseX, float mouseY,
                                         int viewportWidth, int viewportHeight,
                                         Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                         float[] vertexPositions, int vertexCount,
                                         float pointSize) {
        // Validate inputs
        if (viewMatrix == null || projectionMatrix == null) {
            return -1;
        }

        if (vertexPositions == null || vertexCount == 0) {
            return -1;
        }

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return -1;
        }

        if (pointSize <= 0.0f) {
            return -1;
        }

        try {
            // Create MVP matrix for projection
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix);

            // Track closest vertex (by depth)
            int closestVertexIndex = -1;
            float closestDepth = Float.POSITIVE_INFINITY;

            // Point size radius in pixels (half the point size)
            float radiusPixels = pointSize / 2.0f;

            // Test each vertex
            Vector4f worldPos = new Vector4f();
            for (int i = 0; i < vertexCount; i++) {
                int posIndex = i * 3;

                // Get vertex world position
                worldPos.set(
                    vertexPositions[posIndex + 0],
                    vertexPositions[posIndex + 1],
                    vertexPositions[posIndex + 2],
                    1.0f
                );

                // Project to clip space
                Vector4f clipPos = new Vector4f();
                mvpMatrix.transform(worldPos, clipPos);

                // Check if behind camera
                if (clipPos.w <= 0) {
                    continue;
                }

                // Convert to NDC
                float ndcX = clipPos.x / clipPos.w;
                float ndcY = clipPos.y / clipPos.w;
                float depth = clipPos.z / clipPos.w;

                // Convert NDC to screen space
                float screenX = (ndcX + 1.0f) * 0.5f * viewportWidth;
                float screenY = (1.0f - ndcY) * 0.5f * viewportHeight; // Flip Y

                // Calculate distance from mouse to projected vertex
                float dx = mouseX - screenX;
                float dy = mouseY - screenY;
                float distancePixels = (float) Math.sqrt(dx * dx + dy * dy);

                // Check if mouse is within point radius
                if (distancePixels <= radiusPixels && depth < closestDepth) {
                    closestDepth = depth;
                    closestVertexIndex = i;
                }
            }

            return closestVertexIndex;

        } catch (Exception e) {
            logger.error("Error detecting hovered vertex", e);
            return -1;
        }
    }
}
