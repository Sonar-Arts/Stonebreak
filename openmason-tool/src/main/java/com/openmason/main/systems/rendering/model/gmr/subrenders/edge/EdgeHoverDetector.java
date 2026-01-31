package com.openmason.main.systems.rendering.model.gmr.subrenders.edge;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for detecting edge hover interactions.
 * Mirrors VertexHoverDetector pattern but for line segments.
 */
public final class EdgeHoverDetector {

    private static final Logger logger = LoggerFactory.getLogger(EdgeHoverDetector.class);

    // Private constructor to prevent instantiation (utility class)
    private EdgeHoverDetector() {
        throw new AssertionError("EdgeHoverDetector is a utility class and should not be instantiated");
    }

    /**
     * Detects which edge (if any) is currently hovered by the mouse.
     * Uses screen-space point-to-line distance with line width as threshold.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix (transforms edges from model space to world space)
     * @param edgePositions Edge positions in MODEL SPACE (6 floats per edge: x1,y1,z1, x2,y2,z2)
     * @param edgeCount Number of edges
     * @param lineWidth Line width in pixels for hit testing
     * @return Index of hovered edge, or -1 if none
     */
    public static int detectHoveredEdge(float mouseX, float mouseY,
                                       int viewportWidth, int viewportHeight,
                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                       Matrix4f modelMatrix,
                                       float[] edgePositions, int edgeCount,
                                       float lineWidth) {
        // Validate inputs
        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            return -1;
        }

        if (edgePositions == null || edgeCount == 0) {
            return -1;
        }

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return -1;
        }

        if (lineWidth <= 0.0f) {
            return -1;
        }

        try {
            // Create MVP matrix for projection: projection * view * model
            // This transforms edges from model space → world space → view space → clip space
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            // Track closest edge (by distance)
            int closestEdgeIndex = -1;
            float closestDistance = Float.POSITIVE_INFINITY;
            float closestDepth = Float.POSITIVE_INFINITY;

            // Line width threshold (slightly larger for easier selection)
            float thresholdPixels = lineWidth * 2.0f;

            // Test each edge
            Vector4f modelPos1 = new Vector4f();
            Vector4f modelPos2 = new Vector4f();
            Vector2f screenPos1 = new Vector2f();
            Vector2f screenPos2 = new Vector2f();

            for (int i = 0; i < edgeCount; i++) {
                int posIndex = i * 6; // Each edge has 2 endpoints × 3 coords = 6 floats

                // Get edge endpoints in MODEL SPACE
                modelPos1.set(
                    edgePositions[posIndex + 0],
                    edgePositions[posIndex + 1],
                    edgePositions[posIndex + 2],
                    1.0f
                );
                modelPos2.set(
                    edgePositions[posIndex + 3],
                    edgePositions[posIndex + 4],
                    edgePositions[posIndex + 5],
                    1.0f
                );

                // Project both endpoints from model space to clip space
                Vector4f clipPos1 = new Vector4f();
                Vector4f clipPos2 = new Vector4f();
                mvpMatrix.transform(modelPos1, clipPos1);
                mvpMatrix.transform(modelPos2, clipPos2);

                // Check if either endpoint is behind camera
                if (clipPos1.w <= 0 || clipPos2.w <= 0) {
                    continue;
                }

                // Convert to NDC
                float ndcX1 = clipPos1.x / clipPos1.w;
                float ndcY1 = clipPos1.y / clipPos1.w;
                float depth1 = clipPos1.z / clipPos1.w;

                float ndcX2 = clipPos2.x / clipPos2.w;
                float ndcY2 = clipPos2.y / clipPos2.w;
                float depth2 = clipPos2.z / clipPos2.w;

                // Convert NDC to screen space
                screenPos1.x = (ndcX1 + 1.0f) * 0.5f * viewportWidth;
                screenPos1.y = (1.0f - ndcY1) * 0.5f * viewportHeight; // Flip Y

                screenPos2.x = (ndcX2 + 1.0f) * 0.5f * viewportWidth;
                screenPos2.y = (1.0f - ndcY2) * 0.5f * viewportHeight; // Flip Y

                // Calculate point-to-line-segment distance
                float distance = pointToLineSegmentDistance(
                    mouseX, mouseY,
                    screenPos1.x, screenPos1.y,
                    screenPos2.x, screenPos2.y
                );

                // Average depth of the edge
                float avgDepth = (depth1 + depth2) * 0.5f;

                // Check if mouse is within threshold and this edge is closer
                if (distance <= thresholdPixels) {
                    // Prefer closer edges when distances are similar
                    if (distance < closestDistance - 0.5f ||
                        (Math.abs(distance - closestDistance) < 0.5f && avgDepth < closestDepth)) {
                        closestDistance = distance;
                        closestDepth = avgDepth;
                        closestEdgeIndex = i;
                    }
                }
            }

            return closestEdgeIndex;

        } catch (Exception e) {
            logger.error("Error detecting hovered edge", e);
            return -1;
        }
    }

    /**
     * Calculate the shortest distance from a point to a line segment.
     * Uses parametric line equation to find closest point on segment.
     */
    private static float pointToLineSegmentDistance(float px, float py,
                                                   float x1, float y1,
                                                   float x2, float y2) {
        // Vector from start to end
        float dx = x2 - x1;
        float dy = y2 - y1;

        // Edge case: zero-length line segment (degenerate edge)
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < 0.0001f) {
            // Treat as point
            float dpx = px - x1;
            float dpy = py - y1;
            return (float) Math.sqrt(dpx * dpx + dpy * dpy);
        }

        // Parameter t (0 to 1) representing position along line segment
        // t = 0 means at start point, t = 1 means at end point
        float t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared;

        // Clamp t to [0, 1] to stay on line segment
        t = Math.max(0.0f, Math.min(1.0f, t));

        // Find closest point on line segment
        float closestX = x1 + t * dx;
        float closestY = y1 + t * dy;

        // Calculate distance from point to closest point on segment
        float distX = px - closestX;
        float distY = py - closestY;

        return (float) Math.sqrt(distX * distX + distY * distY);
    }
}
