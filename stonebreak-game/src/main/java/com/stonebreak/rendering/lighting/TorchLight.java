package com.stonebreak.rendering.lighting;

/**
 * The torch's light: a warm radiance whose intensity follows the ember's
 * authored pulse (the {@code flicker} clip scales the ember 1.0 → 1.08 → 1.0
 * over 1.2 s with ease-in-out keys). The clip phase must be the
 * one the renderer plays — callers pass the same elapsed time
 * ({@code totalTime + AnimatedBlockRenderer.loopPhaseOffset(pos, duration)}).
 */
public final class TorchLight {

    /** Duration of the authored flicker clip, seconds. */
    public static final float CLIP_DURATION = 1.2f;
    /** Reach of a torch's light, blocks. */
    public static final float RADIUS = 9.5f;
    /** Warm flame tint at full intensity. */
    public static final float COLOR_R = 1.00f;
    public static final float COLOR_G = 0.72f;
    public static final float COLOR_B = 0.38f;
    /** Peak radiance (what a surface right at the ember receives). */
    public static final float PEAK = 1.05f;

    private TorchLight() {}

    /**
     * Intensity multiplier in {@code [0.8, 1.0]} for the given clip-elapsed
     * time: the ember's eased pulse (low at the loop ends, high at its
     * midpoint). Deliberately nothing faster than the clip itself — a
     * high-frequency shimmer reads as static, not fire.
     */
    public static float intensity(float elapsed) {
        float t = elapsed / CLIP_DURATION;
        t -= (float) Math.floor(t);
        float tri = t < 0.5f ? t * 2f : 2f - t * 2f;          // 0 → 1 → 0 across the loop
        float eased = tri * tri * (3f - 2f * tri);              // ease-in-out, like the keys
        return 0.8f + 0.2f * eased;
    }
}
