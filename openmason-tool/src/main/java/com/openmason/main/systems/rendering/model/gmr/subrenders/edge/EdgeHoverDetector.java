package com.openmason.main.systems.rendering.model.gmr.subrenders.edge;

import com.openmason.main.systems.viewport.util.ScreenProjectionUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for detecting edge hover interactions.
 * Mirrors VertexHoverDetector pattern but for line segments.
 */
public final class EdgeHoverDetector {

    private static final Logger logger = LoggerFactory.getLogger(EdgeHoverDetector.class);

    /**
     * Result of an edge hit test that includes the parametric position along the edge.
     *
     * @param edgeIndex Index of the hit edge, or -1 if no hit
     * @param t Parametric position along the edge (0 = start, 1 = end)
     * @param screenDistance Screen-space distance from mouse to edge in pixels
     */
    public record EdgeHitResult(int edgeIndex, float t, float screenDistance) {
        public static final EdgeHitResult NONE = new EdgeHitResult(-1, 0f, Float.MAX_VALUE);

        public boolean isHit() {
            return edgeIndex >= 0;
        }
    }

    /**
     * Internal result from point-to-line-segment distance calculation.
     */
    private record DistanceResult(float distance, float t) {}

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

            for (int i = 0; i < edgeCount; i++) {
                int posIndex = i * 6; // Each edge has 2 endpoints × 3 coords = 6 floats

                // Project both endpoints from MODEL SPACE to screen space (with NDC depth)
                Vector3f screenPos1 = ScreenProjectionUtil.projectToScreenWithDepth(
                    edgePositions[posIndex], edgePositions[posIndex + 1], edgePositions[posIndex + 2],
                    mvpMatrix, viewportWidth, viewportHeight);
                Vector3f screenPos2 = ScreenProjectionUtil.projectToScreenWithDepth(
                    edgePositions[posIndex + 3], edgePositions[posIndex + 4], edgePositions[posIndex + 5],
                    mvpMatrix, viewportWidth, viewportHeight);

                // Skip if either endpoint is behind camera
                if (screenPos1 == null || screenPos2 == null) {
                    continue;
                }

                // Calculate point-to-line-segment distance with parameter
                DistanceResult result = pointToLineSegmentWithParameter(
                    mouseX, mouseY,
                    screenPos1.x, screenPos1.y,
                    screenPos2.x, screenPos2.y
                );

                // Average depth of the edge
                float avgDepth = (screenPos1.z + screenPos2.z) * 0.5f;

                // Check if mouse is within threshold and this edge is closer
                if (result.distance() <= thresholdPixels) {
                    // Prefer closer edges when distances are similar
                    if (result.distance() < closestDistance - 0.5f ||
                        (Math.abs(result.distance() - closestDistance) < 0.5f && avgDepth < closestDepth)) {
                        closestDistance = result.distance();
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
     * Calculate the shortest distance from a point to a line segment,
     * returning both the distance and the parametric position along the segment.
     */
    private static DistanceResult pointToLineSegmentWithParameter(float px, float py,
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
            return new DistanceResult((float) Math.sqrt(dpx * dpx + dpy * dpy), 0f);
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

        return new DistanceResult((float) Math.sqrt(distX * distX + distY * distY), t);
    }

    /**
     * Detects which edge is hovered and returns the parametric position along it.
     * Same logic as {@link #detectHoveredEdge} but returns an {@link EdgeHitResult}
     * including the {@code t} parameter for precise cut placement.
     *
     * @param mouseX Mouse X coordinate in viewport space
     * @param mouseY Mouse Y coordinate in viewport space
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix Camera view matrix
     * @param projectionMatrix Camera projection matrix
     * @param modelMatrix Model transformation matrix
     * @param edgePositions Edge positions in MODEL SPACE (6 floats per edge)
     * @param edgeCount Number of edges
     * @param lineWidth Line width in pixels for hit testing
     * @return EdgeHitResult with edge index, t parameter, and screen distance
     */
    public static EdgeHitResult detectHoveredEdgeWithParameter(
            float mouseX, float mouseY,
            int viewportWidth, int viewportHeight,
            Matrix4f viewMatrix, Matrix4f projectionMatrix,
            Matrix4f modelMatrix,
            float[] edgePositions, int edgeCount,
            float lineWidth) {

        if (viewMatrix == null || projectionMatrix == null || modelMatrix == null) {
            return EdgeHitResult.NONE;
        }
        if (edgePositions == null || edgeCount == 0) {
            return EdgeHitResult.NONE;
        }
        if (viewportWidth <= 0 || viewportHeight <= 0 || lineWidth <= 0.0f) {
            return EdgeHitResult.NONE;
        }

        try {
            Matrix4f mvpMatrix = new Matrix4f(projectionMatrix).mul(viewMatrix).mul(modelMatrix);

            int closestEdgeIndex = -1;
            float closestDistance = Float.POSITIVE_INFINITY;
            float closestDepth = Float.POSITIVE_INFINITY;
            float closestT = 0f;

            float thresholdPixels = lineWidth * 2.0f;

            for (int i = 0; i < edgeCount; i++) {
                int posIndex = i * 6;

                Vector3f screenPos1 = ScreenProjectionUtil.projectToScreenWithDepth(
                    edgePositions[posIndex], edgePositions[posIndex + 1], edgePositions[posIndex + 2],
                    mvpMatrix, viewportWidth, viewportHeight);
                Vector3f screenPos2 = ScreenProjectionUtil.projectToScreenWithDepth(
                    edgePositions[posIndex + 3], edgePositions[posIndex + 4], edgePositions[posIndex + 5],
                    mvpMatrix, viewportWidth, viewportHeight);

                if (screenPos1 == null || screenPos2 == null) {
                    continue;
                }

                DistanceResult result = pointToLineSegmentWithParameter(
                    mouseX, mouseY,
                    screenPos1.x, screenPos1.y,
                    screenPos2.x, screenPos2.y
                );

                float avgDepth = (screenPos1.z + screenPos2.z) * 0.5f;

                if (result.distance() <= thresholdPixels) {
                    if (result.distance() < closestDistance - 0.5f ||
                        (Math.abs(result.distance() - closestDistance) < 0.5f && avgDepth < closestDepth)) {
                        closestDistance = result.distance();
                        closestDepth = avgDepth;
                        closestEdgeIndex = i;
                        closestT = result.t();
                    }
                }
            }

            if (closestEdgeIndex >= 0) {
                return new EdgeHitResult(closestEdgeIndex, closestT, closestDistance);
            }
            return EdgeHitResult.NONE;

        } catch (Exception e) {
            logger.error("Error detecting hovered edge with parameter", e);
            return EdgeHitResult.NONE;
        }
    }
}
