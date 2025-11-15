package com.openmason.ui.viewport.coordinates;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Unified coordinate system utility for the Open Mason viewport.
 *
 * <p>This class provides centralized coordinate system conversions between:
 * <ul>
 *   <li><b>Screen Space (ImGui)</b>: Origin at top-left, Y increases downward, units in pixels</li>
 *   <li><b>NDC (Normalized Device Coordinates)</b>: Origin at center, range [-1, 1], Y increases upward</li>
 *   <li><b>Clip Space</b>: Homogeneous coordinates before perspective division</li>
 *   <li><b>View Space</b>: Camera-relative coordinates, camera looks down -Z</li>
 *   <li><b>World Space</b>: Absolute 3D coordinates, Y-up convention</li>
 * </ul>
 *
 * <p><b>Coordinate System Conventions:</b>
 * <pre>
 * Screen Space (ImGui):          NDC/Clip Space:           World/View Space:
 * (0,0)─────────► X              Y▲                        Y▲
 *   │                             │                         │
 *   │                       -1 ───┼─── +1 X                │
 *   │                             │                         │
 *   ▼ Y                          -1                         └──────► X
 *                                                           /
 *                                                          Z
 * </pre>
 *
 * <p><b>Design Principles:</b>
 * <ul>
 *   <li>SINGLE RESPONSIBILITY: Only coordinate system conversions</li>
 *   <li>IMMUTABLE: All methods return new vectors, never modify inputs</li>
 *   <li>EXPLICIT: Every conversion is named and documented</li>
 *   <li>SAFE: Validates inputs and handles edge cases</li>
 * </ul>
 *
 * @author Open Mason Team
 * @version 1.0
 */
public final class CoordinateSystem {

    // Private constructor - utility class
    private CoordinateSystem() {
        throw new AssertionError("CoordinateSystem is a utility class and should not be instantiated");
    }

    // ===========================================================================================
    // SCREEN SPACE ↔ NDC CONVERSIONS
    // ===========================================================================================

    /**
     * Converts screen-space coordinates to Normalized Device Coordinates (NDC).
     *
     * <p><b>Conversion:</b>
     * <ul>
     *   <li>Screen: (0,0) at top-left, (width,height) at bottom-right</li>
     *   <li>NDC: (-1,-1) at bottom-left, (1,1) at top-right</li>
     * </ul>
     *
     * @param screenX Screen X coordinate in pixels (0 = left edge)
     * @param screenY Screen Y coordinate in pixels (0 = top edge)
     * @param viewportWidth Viewport width in pixels (must be positive)
     * @param viewportHeight Viewport height in pixels (must be positive)
     * @return NDC coordinates in range [-1, 1]
     * @throws IllegalArgumentException if viewport dimensions are not positive
     */
    public static Vector2f screenToNDC(float screenX, float screenY, int viewportWidth, int viewportHeight) {
        validateViewportDimensions(viewportWidth, viewportHeight);

        // X: [0, width] → [-1, 1]
        float ndcX = (2.0f * screenX) / viewportWidth - 1.0f;

        // Y: [0, height] → [1, -1] (flip vertical, 0 at top becomes 1 at top in NDC)
        float ndcY = 1.0f - (2.0f * screenY) / viewportHeight;

        return new Vector2f(ndcX, ndcY);
    }

    /**
     * Converts NDC coordinates to screen-space coordinates.
     *
     * @param ndcX NDC X coordinate in range [-1, 1]
     * @param ndcY NDC Y coordinate in range [-1, 1]
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @return Screen coordinates in pixels
     * @throws IllegalArgumentException if viewport dimensions are not positive
     */
    public static Vector2f ndcToScreen(float ndcX, float ndcY, int viewportWidth, int viewportHeight) {
        validateViewportDimensions(viewportWidth, viewportHeight);

        // X: [-1, 1] → [0, width]
        float screenX = (ndcX + 1.0f) * viewportWidth / 2.0f;

        // Y: [1, -1] → [0, height] (flip vertical)
        float screenY = (1.0f - ndcY) * viewportHeight / 2.0f;

        return new Vector2f(screenX, screenY);
    }

    /**
     * Converts a screen-space delta (movement) to NDC delta.
     *
     * <p><b>Important:</b> Screen deltas need different handling than positions:
     * <ul>
     *   <li>X delta: Same direction, scaled to [-2, 2] range</li>
     *   <li>Y delta: NEGATED (screen down = NDC down, but screen Y+ down, NDC Y+ up)</li>
     * </ul>
     *
     * @param screenDeltaX Screen X movement in pixels
     * @param screenDeltaY Screen Y movement in pixels (positive = downward)
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @return NDC delta (positive Y = upward in NDC space)
     * @throws IllegalArgumentException if viewport dimensions are not positive
     */
    public static Vector2f screenDeltaToNDCDelta(float screenDeltaX, float screenDeltaY,
                                                  int viewportWidth, int viewportHeight) {
        validateViewportDimensions(viewportWidth, viewportHeight);

        // Scale to NDC range [-2, 2] (delta can span full viewport)
        float ndcDeltaX = (2.0f * screenDeltaX) / viewportWidth;

        // Negate Y to flip from screen space (down positive) to NDC space (up positive)
        float ndcDeltaY = -(2.0f * screenDeltaY) / viewportHeight;

        return new Vector2f(ndcDeltaX, ndcDeltaY);
    }

    // ===========================================================================================
    // WORLD SPACE ↔ SCREEN SPACE CONVERSIONS (via matrices)
    // ===========================================================================================

    /**
     * Projects a world-space position to screen-space coordinates.
     *
     * <p>Transformation pipeline: World → View → Clip → NDC → Screen
     *
     * @param worldPos World-space position
     * @param viewMatrix View matrix (world → view)
     * @param projectionMatrix Projection matrix (view → clip)
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @return Screen-space coordinates, or null if behind camera
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static Vector2f worldToScreen(Vector3f worldPos, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                        int viewportWidth, int viewportHeight) {
        validateMatrices(viewMatrix, projectionMatrix);
        validateViewportDimensions(viewportWidth, viewportHeight);

        // Transform to clip space
        Vector4f clipPos = worldToClip(worldPos, viewMatrix, projectionMatrix);

        // Check if behind camera (negative W after projection)
        if (clipPos.w <= 0.0f) {
            return null; // Behind camera
        }

        // Perspective divide to get NDC
        Vector3f ndc = new Vector3f(clipPos.x / clipPos.w, clipPos.y / clipPos.w, clipPos.z / clipPos.w);

        // Convert NDC to screen space
        return ndcToScreen(ndc.x, ndc.y, viewportWidth, viewportHeight);
    }

    /**
     * Projects a world-space direction to screen-space direction.
     *
     * <p><b>Note:</b> Directions are transformed differently than positions:
     * <ul>
     *   <li>No translation applied (only rotation/scale from matrices)</li>
     *   <li>No perspective divide (directions have W=0)</li>
     * </ul>
     *
     * @param worldDir World-space direction vector (will be normalized)
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @return Screen-space direction (2D vector, normalized)
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static Vector2f worldDirectionToScreenDirection(Vector3f worldDir, Matrix4f viewMatrix,
                                                           Matrix4f projectionMatrix) {
        validateMatrices(viewMatrix, projectionMatrix);

        // Transform direction to view space (rotation only, no translation)
        Vector3f viewDir = new Vector3f(worldDir).normalize();
        viewMatrix.transformDirection(viewDir);

        // Transform to clip space (w=0 for directions)
        Vector4f clipDir = new Vector4f(viewDir, 0.0f);
        projectionMatrix.transform(clipDir);

        // For directions, we just need XY components (already in clip space)
        // Normalize to get direction only
        Vector2f screenDir = new Vector2f(clipDir.x, clipDir.y);
        if (screenDir.lengthSquared() > 0.0001f) {
            screenDir.normalize();
        }

        return screenDir;
    }

    /**
     * Creates a ray from screen coordinates in world space.
     *
     * <p>The ray starts at the camera position and points toward the screen coordinate
     * in world space.
     *
     * @param screenX Screen X coordinate in pixels
     * @param screenY Screen Y coordinate in pixels
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @return Ray with origin and direction in world space
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static Ray createWorldRayFromScreen(float screenX, float screenY,
                                               int viewportWidth, int viewportHeight,
                                               Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        validateMatrices(viewMatrix, projectionMatrix);
        validateViewportDimensions(viewportWidth, viewportHeight);

        // Convert screen to NDC
        Vector2f ndc = screenToNDC(screenX, screenY, viewportWidth, viewportHeight);

        // Create clip-space points at near and far planes
        Vector4f clipNear = new Vector4f(ndc.x, ndc.y, -1.0f, 1.0f);
        Vector4f clipFar = new Vector4f(ndc.x, ndc.y, 1.0f, 1.0f);

        // Unproject to world space
        Vector3f worldNear = clipToWorld(clipNear, viewMatrix, projectionMatrix);
        Vector3f worldFar = clipToWorld(clipFar, viewMatrix, projectionMatrix);

        // Create ray
        Vector3f origin = worldNear;
        Vector3f direction = new Vector3f(worldFar).sub(worldNear).normalize();

        return new Ray(origin, direction);
    }

    // ===========================================================================================
    // COORDINATE SPACE TRANSFORMATIONS
    // ===========================================================================================

    /**
     * Transforms a world-space position to clip-space.
     *
     * @param worldPos World-space position
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @return Clip-space position (homogeneous coordinates, before perspective divide)
     */
    public static Vector4f worldToClip(Vector3f worldPos, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        validateMatrices(viewMatrix, projectionMatrix);

        // Transform to view space
        Vector4f viewPos = new Vector4f(worldPos, 1.0f);
        viewMatrix.transform(viewPos);

        // Transform to clip space
        projectionMatrix.transform(viewPos);

        return viewPos;
    }

    /**
     * Transforms a clip-space position to world-space.
     *
     * @param clipPos Clip-space position (homogeneous)
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @return World-space position
     */
    public static Vector3f clipToWorld(Vector4f clipPos, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        validateMatrices(viewMatrix, projectionMatrix);

        // Inverse projection
        Matrix4f invProjection = new Matrix4f(projectionMatrix).invert();
        Vector4f viewPos = new Vector4f(clipPos);
        invProjection.transform(viewPos);

        // Perspective divide
        if (Math.abs(viewPos.w) > 0.0001f) {
            viewPos.div(viewPos.w);
        }

        // Inverse view
        Matrix4f invView = new Matrix4f(viewMatrix).invert();
        invView.transform(viewPos);

        return new Vector3f(viewPos.x, viewPos.y, viewPos.z);
    }

    // ===========================================================================================
    // AXIS PROJECTION UTILITIES
    // ===========================================================================================

    /**
     * Projects a screen-space delta onto a world-space axis.
     *
     * <p>This is used for gizmo dragging: converts mouse movement to movement along a specific axis.
     *
     * <p><b>Algorithm:</b>
     * <ol>
     *   <li>Project world axis to screen direction</li>
     *   <li>Compute dot product with screen delta direction</li>
     *   <li>Scale by screen delta magnitude and sensitivity</li>
     * </ol>
     *
     * @param screenDeltaX Screen X movement in pixels
     * @param screenDeltaY Screen Y movement in pixels (positive = downward)
     * @param worldAxis World-space axis to project onto (must be normalized)
     * @param viewMatrix View matrix
     * @param projectionMatrix Projection matrix
     * @param viewportWidth Viewport width
     * @param viewportHeight Viewport height
     * @param sensitivity Movement sensitivity multiplier (default: 0.01)
     * @return Projected movement along the world axis in world units
     * @throws IllegalArgumentException if parameters are invalid
     */
    public static float projectScreenDeltaOntoWorldAxis(float screenDeltaX, float screenDeltaY,
                                                       Vector3f worldAxis,
                                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                                       int viewportWidth, int viewportHeight,
                                                       float sensitivity) {
        validateMatrices(viewMatrix, projectionMatrix);
        validateViewportDimensions(viewportWidth, viewportHeight);

        // Check for zero movement
        float screenDeltaLength = (float) Math.sqrt(screenDeltaX * screenDeltaX + screenDeltaY * screenDeltaY);
        if (screenDeltaLength < 0.001f) {
            return 0.0f; // No movement
        }

        // Normalize screen delta
        Vector2f screenDeltaNorm = new Vector2f(screenDeltaX / screenDeltaLength, screenDeltaY / screenDeltaLength);

        // Project world axis to screen direction
        Vector2f axisScreenDir = worldDirectionToScreenDirection(worldAxis, viewMatrix, projectionMatrix);

        // Check if axis is perpendicular to screen
        if (axisScreenDir.lengthSquared() < 0.001f) {
            return 0.0f; // Axis perpendicular to view
        }

        axisScreenDir.normalize();

        // Compute projection: how much of the screen movement is along the axis direction?
        // Note: screen delta Y is already in screen space (down positive)
        // But NDC Y is up positive, so we need to negate
        float projection = screenDeltaNorm.x * axisScreenDir.x + (-screenDeltaNorm.y) * axisScreenDir.y;

        // Apply screen length and sensitivity
        return projection * screenDeltaLength * sensitivity;
    }

    /**
     * Projects a screen-space delta onto a world-space axis with default sensitivity.
     *
     * @see #projectScreenDeltaOntoWorldAxis(float, float, Vector3f, Matrix4f, Matrix4f, int, int, float)
     */
    public static float projectScreenDeltaOntoWorldAxis(float screenDeltaX, float screenDeltaY,
                                                       Vector3f worldAxis,
                                                       Matrix4f viewMatrix, Matrix4f projectionMatrix,
                                                       int viewportWidth, int viewportHeight) {
        return projectScreenDeltaOntoWorldAxis(screenDeltaX, screenDeltaY, worldAxis,
                                              viewMatrix, projectionMatrix,
                                              viewportWidth, viewportHeight, 0.01f);
    }

    // ===========================================================================================
    // RAY CLASS
    // ===========================================================================================

    /**
     * Represents a 3D ray with origin and direction in world space.
     */
    public static class Ray {
        public final Vector3f origin;
        public final Vector3f direction; // Normalized

        public Ray(Vector3f origin, Vector3f direction) {
            if (origin == null || direction == null) {
                throw new IllegalArgumentException("Ray parameters cannot be null");
            }
            this.origin = new Vector3f(origin);
            this.direction = new Vector3f(direction).normalize();
        }

        /**
         * Gets a point along the ray at a given distance.
         *
         * @param distance Distance along the ray
         * @return Point at that distance
         */
        public Vector3f getPoint(float distance) {
            return new Vector3f(origin).add(
                direction.x * distance,
                direction.y * distance,
                direction.z * distance
            );
        }
    }

    // ===========================================================================================
    // VALIDATION UTILITIES
    // ===========================================================================================

    private static void validateViewportDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                String.format("Viewport dimensions must be positive: width=%d, height=%d", width, height)
            );
        }
    }

    private static void validateMatrices(Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (viewMatrix == null || projectionMatrix == null) {
            throw new IllegalArgumentException("View and projection matrices cannot be null");
        }
    }
}
