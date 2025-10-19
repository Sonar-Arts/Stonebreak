package com.openmason.ui.viewport.util;

import org.joml.Vector3f;

/**
 * Interpolation utilities for smooth camera animations.
 * Provides linear interpolation and angle-aware interpolation.
 */
public final class CameraInterpolator {

    public static final float INTERPOLATION_SPEED = 15.0f;
    private static final float EPSILON = 0.01f;
    private static final float ANGLE_EPSILON = 0.1f;

    private CameraInterpolator() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Linear interpolation between two values.
     *
     * @param from Start value
     * @param to End value
     * @param t Interpolation factor (clamped to [0, 1])
     * @return Interpolated value
     */
    public static float lerp(float from, float to, float t) {
        return from + (to - from) * Math.min(t, 1.0f);
    }

    /**
     * Angle-aware interpolation that handles wraparound correctly.
     * Ensures the shortest rotation path is taken.
     *
     * @param from Start angle in degrees
     * @param to End angle in degrees
     * @param t Interpolation factor (clamped to [0, 1])
     * @return Interpolated angle in normalized form
     */
    public static float lerpAngle(float from, float to, float t) {
        float difference = to - from;

        // Handle angle wraparound for smooth rotation
        if (difference > 180) {
            difference -= 360;
        } else if (difference < -180) {
            difference += 360;
        }

        return CameraMath.normalizeAngle(from + difference * Math.min(t, 1.0f));
    }

    /**
     * Checks if two float values are approximately equal.
     *
     * @param a First value
     * @param b Second value
     * @return true if values are within epsilon
     */
    public static boolean isApproximately(float a, float b) {
        return Math.abs(a - b) < EPSILON;
    }

    /**
     * Checks if two angles are approximately equal.
     *
     * @param a First angle
     * @param b Second angle
     * @return true if angles are within angle epsilon
     */
    public static boolean isAngleApproximately(float a, float b) {
        return Math.abs(a - b) < ANGLE_EPSILON;
    }

    /**
     * Checks if a vector has reached its target (all components within epsilon).
     *
     * @param current Current position
     * @param target Target position
     * @return true if position is approximately at target
     */
    public static boolean isAtTarget(Vector3f current, Vector3f target) {
        Vector3f delta = new Vector3f(target).sub(current);
        return delta.length() < EPSILON;
    }
}
