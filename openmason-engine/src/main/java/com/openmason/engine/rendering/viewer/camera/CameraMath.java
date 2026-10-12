package com.openmason.engine.rendering.viewer.camera;

/**
 * Mathematical utility functions for camera calculations.
 * Provides constraint enforcement and angle normalization.
 */
public final class CameraMath {

    // Camera constraints
    // Models are authored in block units (1 block = 1 unit, 1 pixel = 1/16), so the
    // orbit must reach well under a pixel to inspect detail up close.
    public static final float MIN_DISTANCE = 0.05f;
    public static final float MAX_DISTANCE = 100.0f;
    public static final float MIN_PITCH = -89.0f;
    public static final float MAX_PITCH = 89.0f;
    public static final float ZOOM_SENSITIVITY = 1.08f;

    // Near clip plane: a fixed fraction of the orbit distance, capped at the old fixed
    // 0.1 so normal zoom levels keep their depth precision.
    public static final float MAX_NEAR_PLANE = 0.1f;
    public static final float MIN_NEAR_PLANE = 0.001f;
    public static final float NEAR_PLANE_DISTANCE_RATIO = 0.05f;

    // FOV constraints
    public static final float MIN_FOV = 10.0f;
    public static final float MAX_FOV = 120.0f;

    // Sensitivity constraints
    public static final float MIN_SENSITIVITY = 0.1f;
    public static final float MAX_SENSITIVITY = 5.0f;

    // Pan sensitivity constraints
    public static final float MIN_PAN_SENSITIVITY = 0.1f;
    public static final float MAX_PAN_SENSITIVITY = 5.0f;
    public static final float DEFAULT_PAN_SENSITIVITY = 1.0f;

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
     * Near clip distance for an orbit camera at the given distance from its target.
     *
     * @param distance Orbit distance
     * @return Near plane distance, in [MIN_NEAR_PLANE, MAX_NEAR_PLANE]
     */
    public static float nearPlaneForDistance(float distance) {
        return clamp(distance * NEAR_PLANE_DISTANCE_RATIO, MIN_NEAR_PLANE, MAX_NEAR_PLANE);
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
