package com.openmason.engine.format.oma;

import java.util.Locale;

/**
 * Interpolation curves applied between two adjacent keyframes.
 *
 * <p>Canonical easing math for the OMANIM animation system, shared by the engine
 * sampler ({@link AnimSampler}, which drives the Stonebreak game runtime) and the
 * Open Mason tool preview, so a clip eases identically in the editor and in game.
 *
 * <p>A keyframe's easing governs the segment leading <em>into the next</em>
 * keyframe. {@link #apply(float)} remaps a normalized segment parameter
 * {@code t} in {@code [0,1]} through the curve.
 */
public enum Easing {
    /** Constant velocity — straight linear blend. */
    LINEAR,
    /** Slow start, accelerating (cubic). */
    EASE_IN,
    /** Fast start, decelerating to a soft stop (cubic). */
    EASE_OUT,
    /** Slow start and soft stop, fast through the middle (cubic). */
    EASE_IN_OUT;

    /**
     * Remap a normalized segment parameter through this curve.
     *
     * @param t segment parameter, clamped to {@code [0,1]}
     * @return the eased parameter, also in {@code [0,1]}
     */
    public float apply(float t) {
        float x = t < 0f ? 0f : (t > 1f ? 1f : t);
        return switch (this) {
            case LINEAR -> x;
            case EASE_IN -> x * x * x;
            case EASE_OUT -> {
                float inv = 1f - x;
                yield 1f - inv * inv * inv;
            }
            case EASE_IN_OUT -> x < 0.5f
                    ? 4f * x * x * x
                    : 1f - cube(-2f * x + 2f) / 2f;
        };
    }

    private static float cube(float v) {
        return v * v * v;
    }

    /**
     * Parse an easing name case-insensitively, falling back to {@link #LINEAR}
     * for null/blank/unknown values.
     */
    public static Easing fromString(String name) {
        if (name == null || name.isBlank()) {
            return LINEAR;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return LINEAR;
        }
    }
}
