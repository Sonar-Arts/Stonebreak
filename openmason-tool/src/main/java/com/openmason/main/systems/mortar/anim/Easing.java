package com.openmason.main.systems.mortar.anim;

/**
 * Pure easing functions over the unit interval. Each maps a progress
 * {@code t} in [0,1] to an eased value in [0,1] with {@code f(0)=0} and
 * {@code f(1)=1}. Inputs outside [0,1] are clamped.
 *
 * <p>These are intentionally minimal — MortarUI animation is built on
 * {@link Smoother} (rate-based), and these curves are used for one-off
 * transitions such as the hub view {@link Crossfade}.</p>
 */
public final class Easing {

    private Easing() {
    }

    /** Identity. */
    public static float linear(float t) {
        return clamp01(t);
    }

    /** Decelerating cubic — fast start, soft settle. Good for entrances. */
    public static float easeOutCubic(float t) {
        t = clamp01(t);
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv;
    }

    /** Accelerating cubic — soft start, fast end. */
    public static float easeInCubic(float t) {
        t = clamp01(t);
        return t * t * t;
    }

    /** Symmetric cubic — soft start and soft settle. */
    public static float easeInOutCubic(float t) {
        t = clamp01(t);
        if (t < 0.5f) {
            return 4.0f * t * t * t;
        }
        float f = (2.0f * t) - 2.0f;
        return 0.5f * f * f * f + 1.0f;
    }

    public static float clamp01(float v) {
        if (v < 0.0f) {
            return 0.0f;
        }
        return v > 1.0f ? 1.0f : v;
    }
}
