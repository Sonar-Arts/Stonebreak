package com.openmason.ui.properties.utils;

import imgui.type.ImFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for validating and ensuring safe transform values.
 * Follows DRY principle by centralizing validation logic.
 */
public class TransformValidator {

    private static final Logger logger = LoggerFactory.getLogger(TransformValidator.class);

    /**
     * Ensure a float value is safe (not NaN, not infinite).
     *
     * @param value The value to check
     * @param defaultValue The default value to use if unsafe
     * @return The value if safe, otherwise the default value
     */
    public static float ensureSafe(float value, float defaultValue) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return defaultValue;
        }
        return value;
    }

    /**
     * Ensure an ImFloat value is safe (not NaN, not infinite).
     *
     * @param imFloat The ImFloat to check
     * @param defaultValue The default value to use if unsafe
     */
    public static void ensureSafe(ImFloat imFloat, float defaultValue) {
        float value = imFloat.get();
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            imFloat.set(defaultValue);
        }
    }

    /**
     * Ensure a scale value is safe (not NaN, not infinite, not zero or negative).
     *
     * @param imFloat The ImFloat scale value to check
     * @param defaultValue The default value to use if unsafe (typically 1.0f)
     */
    public static void ensureSafeScale(ImFloat imFloat, float defaultValue) {
        float value = imFloat.get();
        if (Float.isNaN(value) || Float.isInfinite(value) || value <= 0.0f) {
            imFloat.set(defaultValue);
        }
    }

    /**
     * Clamp a value between min and max.
     *
     * @param value The value to clamp
     * @param min Minimum allowed value
     * @param max Maximum allowed value
     * @return The clamped value
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Ensure all position values are safe.
     *
     * @param x Position X
     * @param y Position Y
     * @param z Position Z
     */
    public static void ensureSafePosition(ImFloat x, ImFloat y, ImFloat z) {
        ensureSafe(x, 0.0f);
        ensureSafe(y, 0.0f);
        ensureSafe(z, 0.0f);
    }

    /**
     * Ensure all rotation values are safe.
     *
     * @param x Rotation X
     * @param y Rotation Y
     * @param z Rotation Z
     */
    public static void ensureSafeRotation(ImFloat x, ImFloat y, ImFloat z) {
        ensureSafe(x, 0.0f);
        ensureSafe(y, 0.0f);
        ensureSafe(z, 0.0f);
    }

    /**
     * Ensure all scale values are safe.
     *
     * @param x Scale X
     * @param y Scale Y
     * @param z Scale Z
     */
    public static void ensureSafeScaleValues(ImFloat x, ImFloat y, ImFloat z) {
        ensureSafeScale(x, 1.0f);
        ensureSafeScale(y, 1.0f);
        ensureSafeScale(z, 1.0f);
    }

    /**
     * Ensure all transform values (position, rotation, scale) are safe.
     *
     * @param posX Position X
     * @param posY Position Y
     * @param posZ Position Z
     * @param rotX Rotation X
     * @param rotY Rotation Y
     * @param rotZ Rotation Z
     * @param sclX Scale X
     * @param sclY Scale Y
     * @param sclZ Scale Z
     */
    public static void ensureSafeTransform(ImFloat posX, ImFloat posY, ImFloat posZ,
                                           ImFloat rotX, ImFloat rotY, ImFloat rotZ,
                                           ImFloat sclX, ImFloat sclY, ImFloat sclZ) {
        ensureSafePosition(posX, posY, posZ);
        ensureSafeRotation(rotX, rotY, rotZ);
        ensureSafeScaleValues(sclX, sclY, sclZ);
    }
}
