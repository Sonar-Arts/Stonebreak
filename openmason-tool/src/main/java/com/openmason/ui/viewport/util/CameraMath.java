package com.openmason.ui.viewport.util;

/**
 * Mathematical utility functions for camera calculations.
 * Provides constraint enforcement and angle normalization.
 */
public final class CameraMath {

    // Camera constraints
    public static final float MIN_DISTANCE = 2.0f;
    public static final float MAX_DISTANCE = 100.0f;
    public static final float MIN_PITCH = -89.0f;
    public static final float MAX_PITCH = 89.0f;
    public static final float ZOOM_SENSITIVITY = 1.08f;

    // FOV constraints
    public static final float MIN_FOV = 10.0f;
    public static final float MAX_FOV = 120.0f;

    // Sensitivity constraints
    public static final float MIN_SENSITIVITY = 0.1f;
    public static final float MAX_SENSITIVITY = 5.0f;

    private CameraMath() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Clamps a value between minimum and maximum bounds.
     *
     * @param value The value to clamp
     * @param min The minimum bound
     * @param max The maximum bound
     * @return The clamped value
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Normalizes an angle to the [0, 360) range.
     *
     * @param angle The angle in degrees
     * @return The normalized angle
     */
    public static float normalizeAngle(float angle) {
        while (angle < 0) angle += 360;
        while (angle >= 360) angle -= 360;
        return angle;
    }

    /**
     * Converts degrees to radians.
     *
     * @param degrees Angle in degrees
     * @return Angle in radians
     */
    public static float toRadians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    /**
     * Applies zoom with exponential scaling.
     *
     * @param currentDistance Current camera distance
     * @param scrollDelta Scroll wheel delta
     * @return New distance after zoom
     */
    public static float applyZoom(float currentDistance, float scrollDelta) {
        float zoomFactor = (float) Math.pow(ZOOM_SENSITIVITY, scrollDelta);
        return clamp(currentDistance * zoomFactor, MIN_DISTANCE, MAX_DISTANCE);
    }
}
