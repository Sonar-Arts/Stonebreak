package com.openmason.ui.properties.utils;

import imgui.type.ImFloat;

/**
 * Utility class for validating and ensuring safe transform values.
 */
public class TransformValidator {

    /**
     * Ensure an ImFloat value is safe (not NaN, not infinite).
     */
    public static void ensureSafe(ImFloat imFloat, float defaultValue) {
        float value = imFloat.get();
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            imFloat.set(defaultValue);
        }
    }

    /**
     * Ensure a scale value is safe (not NaN, not infinite, not zero or negative).
     */
    public static void ensureSafeScale(ImFloat imFloat, float defaultValue) {
        float value = imFloat.get();
        if (Float.isNaN(value) || Float.isInfinite(value) || value <= 0.0f) {
            imFloat.set(defaultValue);
        }
    }

    /**
     * Clamp a value between min and max.
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Ensure all position values are safe.
     */
    public static void ensureSafePosition(ImFloat x, ImFloat y, ImFloat z) {
        ensureSafe(x, 0.0f);
        ensureSafe(y, 0.0f);
        ensureSafe(z, 0.0f);
    }

    /**
     * Ensure all rotation values are safe.
     */
    public static void ensureSafeRotation(ImFloat x, ImFloat y, ImFloat z) {
        ensureSafe(x, 0.0f);
        ensureSafe(y, 0.0f);
        ensureSafe(z, 0.0f);
    }

    /**
     * Ensure all scale values are safe.
     */
    public static void ensureSafeScaleValues(ImFloat x, ImFloat y, ImFloat z) {
        ensureSafeScale(x, 1.0f);
        ensureSafeScale(y, 1.0f);
        ensureSafeScale(z, 1.0f);
    }

    /**
     * Ensure all transform values (position, rotation, scale) are safe.
     */
    public static void ensureSafeTransform(ImFloat posX, ImFloat posY, ImFloat posZ,
                                           ImFloat rotX, ImFloat rotY, ImFloat rotZ,
                                           ImFloat sclX, ImFloat sclY, ImFloat sclZ) {
        ensureSafePosition(posX, posY, posZ);
        ensureSafeRotation(rotX, rotY, rotZ);
        ensureSafeScaleValues(sclX, sclY, sclZ);
    }
}
