package com.stonebreak.world.generation.heightmap;

/**
 * Utility class for smoothing noise values to create more natural terrain.
 *
 * Provides various smoothing and easing functions to reduce harsh transitions
 * in procedural noise, creating smoother, more realistic terrain features.
 *
 * Based on research from Minecraft's modern terrain generation (1.18+) which
 * uses spline curves and easing functions to smooth noise values.
 *
 * Key Techniques:
 * - Smoothstep: S-curve interpolation for smooth transitions
 * - Ease functions: Various interpolation curves
 * - Clamping: Ensures values stay within valid ranges
 *
 * Follows Single Responsibility Principle - only handles noise smoothing.
 * All methods are static utilities for performance.
 */
public class NoiseSmoothing {

    // Private constructor to prevent instantiation
    private NoiseSmoothing() {
        throw new AssertionError("NoiseSmoothing is a utility class and should not be instantiated");
    }

    /**
     * Applies smoothstep interpolation to a noise value.
     *
     * Smoothstep creates an S-curve that smooths transitions between -1 and 1.
     * The formula is: 3x² - 2x³
     *
     * This is the most commonly used smoothing function in terrain generation
     * as it creates natural-looking gradients.
     *
     * @param value Input noise value (typically -1 to 1)
     * @return Smoothed value with gentler transitions
     */
    public static float smoothstep(float value) {
        // Clamp to [-1, 1] range
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));

        // Map from [-1, 1] to [0, 1]
        float normalized = (clamped + 1.0f) / 2.0f;

        // Apply smoothstep: 3x² - 2x³
        float smoothed = normalized * normalized * (3.0f - 2.0f * normalized);

        // Map back to [-1, 1]
        return smoothed * 2.0f - 1.0f;
    }

    /**
     * Applies smootherstep interpolation (Ken Perlin's improved version).
     *
     * Formula: 6x⁵ - 15x⁴ + 10x³
     * Creates even smoother transitions than standard smoothstep.
     *
     * @param value Input noise value (typically -1 to 1)
     * @return Very smooth value with gentle transitions
     */
    public static float smootherstep(float value) {
        // Clamp to [-1, 1] range
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));

        // Map from [-1, 1] to [0, 1]
        float normalized = (clamped + 1.0f) / 2.0f;

        // Apply smootherstep: 6x⁵ - 15x⁴ + 10x³
        float smoothed = normalized * normalized * normalized *
                        (normalized * (normalized * 6.0f - 15.0f) + 10.0f);

        // Map back to [-1, 1]
        return smoothed * 2.0f - 1.0f;
    }

    /**
     * Applies ease-in-out cubic interpolation.
     *
     * Starts slow, speeds up in middle, slows at end.
     * Good for gradual terrain transitions.
     *
     * @param value Input noise value (typically -1 to 1)
     * @return Eased value
     */
    public static float easeInOutCubic(float value) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        float normalized = (clamped + 1.0f) / 2.0f;

        float eased;
        if (normalized < 0.5f) {
            eased = 4.0f * normalized * normalized * normalized;
        } else {
            float f = 2.0f * normalized - 2.0f;
            eased = 0.5f * f * f * f + 1.0f;
        }

        return eased * 2.0f - 1.0f;
    }

    /**
     * Applies fade curve (quintic polynomial).
     *
     * Standard fade function used in Perlin noise.
     * Formula: 6t⁵ - 15t⁴ + 10t³
     *
     * @param value Input value in range [0, 1]
     * @return Faded value in range [0, 1]
     */
    public static float fade(float value) {
        // Clamp to [0, 1]
        float t = Math.max(0.0f, Math.min(1.0f, value));

        // Apply fade curve
        return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f);
    }

    /**
     * Applies variable strength smoothing.
     *
     * Allows controlling how much smoothing is applied.
     *
     * @param value    Input noise value
     * @param strength Smoothing strength (0.0 = no smoothing, 1.0 = full smoothstep)
     * @return Smoothed value
     */
    public static float smoothWithStrength(float value, float strength) {
        if (strength <= 0.0f) {
            return value;
        }
        if (strength >= 1.0f) {
            return smoothstep(value);
        }

        // Interpolate between raw and smoothed
        float smoothed = smoothstep(value);
        return value * (1.0f - strength) + smoothed * strength;
    }

    /**
     * Reduces noise amplitude at low values (flattens valleys).
     *
     * Useful for creating flatter lowlands while preserving mountain peaks.
     *
     * @param value     Input noise value
     * @param threshold Values below this are compressed (typically 0.0 to 0.5)
     * @param factor    Compression factor (0.0 = full flatten, 1.0 = no change)
     * @return Modified noise value
     */
    public static float compressLowValues(float value, float threshold, float factor) {
        if (value < threshold) {
            // Compress values below threshold
            return value * factor;
        }
        return value;
    }

    /**
     * Reduces noise amplitude at high values (flattens peaks).
     *
     * Useful for creating plateau effects.
     *
     * @param value     Input noise value
     * @param threshold Values above this are compressed (typically 0.5 to 1.0)
     * @param factor    Compression factor (0.0 = full flatten, 1.0 = no change)
     * @return Modified noise value
     */
    public static float compressHighValues(float value, float threshold, float factor) {
        if (value > threshold) {
            // Compress values above threshold
            float excess = value - threshold;
            return threshold + excess * factor;
        }
        return value;
    }

    /**
     * Creates a terrace effect by quantizing noise values.
     *
     * Useful for mesa/badlands terrain.
     *
     * @param value  Input noise value
     * @param steps  Number of terrace levels
     * @param smooth Smoothing factor (0.0 = sharp steps, 1.0 = smooth blend)
     * @return Terraced noise value
     */
    public static float terrace(float value, int steps, float smooth) {
        // Map to [0, 1]
        float normalized = (value + 1.0f) / 2.0f;

        // Quantize
        float stepped = Math.round(normalized * (steps - 1)) / (float) (steps - 1);

        // Blend with original based on smooth factor
        float result = normalized * smooth + stepped * (1.0f - smooth);

        // Map back to [-1, 1]
        return result * 2.0f - 1.0f;
    }

    /**
     * Applies altitude-based smoothing fade.
     *
     * Reduces noise strength at low altitudes, increases at high altitudes.
     * Creates naturally flat lowlands and detailed mountains.
     *
     * @param value    Input noise value
     * @param altitude Height above sea level (0.0 = sea level, 1.0 = max height)
     * @return Altitude-scaled noise value
     */
    public static float altitudeFade(float value, float altitude) {
        // Clamp altitude to [0, 1]
        float alt = Math.max(0.0f, Math.min(1.0f, altitude));

        // Use cubic curve for smooth fade
        float fadeFactor = alt * alt * alt;

        return value * fadeFactor;
    }

    /**
     * Clamps a value between minimum and maximum.
     *
     * @param value Value to clamp
     * @param min   Minimum value
     * @param max   Maximum value
     * @return Clamped value
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * Linear interpolation between two values.
     *
     * @param a Start value
     * @param b End value
     * @param t Interpolation factor (0.0 = a, 1.0 = b)
     * @return Interpolated value
     */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp(t, 0.0f, 1.0f);
    }
}
