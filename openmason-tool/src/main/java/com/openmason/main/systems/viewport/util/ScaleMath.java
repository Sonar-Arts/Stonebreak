package com.openmason.main.systems.viewport.util;

import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Pure static math for the modal scale tool (Blender-style S key).
 *
 * <p>The scale factor is the ratio of the mouse's current distance from the
 * projected pivot to its distance when the tool started ({@code d0}). Moving
 * away from the pivot grows the selection, moving toward it shrinks it.
 */
public final class ScaleMath {

    /**
     * Minimum reference distance in pixels. Prevents a divide-by-near-zero
     * (and wildly jumpy factors) when the tool starts with the mouse on top
     * of the pivot.
     */
    public static final float MIN_REFERENCE_DISTANCE_PX = 8.0f;

    private ScaleMath() {
        throw new AssertionError("ScaleMath is a utility class and should not be instantiated");
    }

    /**
     * Compute the reference distance d0 from the mouse to the projected pivot,
     * clamped to {@link #MIN_REFERENCE_DISTANCE_PX}.
     *
     * @param mouseX      Mouse X in viewport pixels
     * @param mouseY      Mouse Y in viewport pixels
     * @param pivotScreen Projected pivot position in viewport pixels
     * @return Clamped reference distance in pixels
     */
    public static float referenceDistance(float mouseX, float mouseY, Vector2f pivotScreen) {
        float dx = mouseX - pivotScreen.x;
        float dy = mouseY - pivotScreen.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return Math.max(dist, MIN_REFERENCE_DISTANCE_PX);
    }

    /**
     * Compute the current scale factor from the mouse position.
     *
     * @param mouseX      Mouse X in viewport pixels
     * @param mouseY      Mouse Y in viewport pixels
     * @param pivotScreen Projected pivot position in viewport pixels
     * @param d0          Reference distance from {@link #referenceDistance}
     * @return Scale factor (distance / d0), always >= 0
     */
    public static float factor(float mouseX, float mouseY, Vector2f pivotScreen, float d0) {
        float dx = mouseX - pivotScreen.x;
        float dy = mouseY - pivotScreen.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        return dist / d0;
    }

    /**
     * Uniformly scale a position about a pivot.
     *
     * @param original Original position (not modified)
     * @param pivot    Pivot position (not modified)
     * @param factor   Uniform scale factor
     * @return New position: pivot + (original - pivot) * factor
     */
    public static Vector3f scaleAboutPivot(Vector3f original, Vector3f pivot, float factor) {
        return new Vector3f(
            pivot.x + (original.x - pivot.x) * factor,
            pivot.y + (original.y - pivot.y) * factor,
            pivot.z + (original.z - pivot.z) * factor
        );
    }
}
