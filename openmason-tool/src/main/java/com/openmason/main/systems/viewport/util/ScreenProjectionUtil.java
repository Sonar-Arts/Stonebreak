package com.openmason.main.systems.viewport.util;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Utility class for projecting model-space positions to viewport screen coordinates.
 * Single home for the clip-space → NDC → screen math used by hover detection,
 * box select, and modal tools (DRY).
 *
 * <p>Screen coordinates are viewport-relative pixels with Y pointing down
 * (matching mouse coordinates from {@code InputContext}).
 */
public final class ScreenProjectionUtil {

    private ScreenProjectionUtil() {
        throw new AssertionError("ScreenProjectionUtil is a utility class and should not be instantiated");
    }

    /**
     * Project a model-space position to viewport screen coordinates.
     *
     * @param modelPos       Position in model space
     * @param mvp            Combined projection * view * model matrix
     * @param viewportWidth  Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @return Screen position in pixels, or null if the point is behind the camera (clip.w <= 0)
     */
    public static Vector2f projectToScreen(Vector3f modelPos, Matrix4f mvp,
                                           int viewportWidth, int viewportHeight) {
        if (modelPos == null) {
            return null;
        }
        Vector3f withDepth = projectToScreenWithDepth(
            modelPos.x, modelPos.y, modelPos.z, mvp, viewportWidth, viewportHeight);
        return withDepth != null ? new Vector2f(withDepth.x, withDepth.y) : null;
    }

    /**
     * Project a model-space position to viewport screen coordinates, keeping the NDC depth.
     * Used by hover detection for depth tie-breaking between overlapping candidates.
     *
     * @param x              Model-space X
     * @param y              Model-space Y
     * @param z              Model-space Z
     * @param mvp            Combined projection * view * model matrix
     * @param viewportWidth  Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @return (screenX, screenY, ndcDepth), or null if behind the camera (clip.w <= 0)
     */
    public static Vector3f projectToScreenWithDepth(float x, float y, float z, Matrix4f mvp,
                                                    int viewportWidth, int viewportHeight) {
        if (mvp == null) {
            return null;
        }

        // Transform to clip space
        Vector4f clipPos = mvp.transform(new Vector4f(x, y, z, 1.0f));

        // Behind camera
        if (clipPos.w <= 0) {
            return null;
        }

        // Perspective divide to NDC
        float ndcX = clipPos.x / clipPos.w;
        float ndcY = clipPos.y / clipPos.w;
        float depth = clipPos.z / clipPos.w;

        // NDC to screen space (Y flipped: screen Y grows downward)
        float screenX = (ndcX + 1.0f) * 0.5f * viewportWidth;
        float screenY = (1.0f - ndcY) * 0.5f * viewportHeight;

        return new Vector3f(screenX, screenY, depth);
    }
}
