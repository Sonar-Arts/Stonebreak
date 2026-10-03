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
    /** Warm flame tint, peak radiance, and a pulse locked to the ember clip. */
    public static final LightProfile PROFILE = new LightProfile(1.00f, 0.72f, 0.38f, 1.05f, CLIP_DURATION, 0.8f);

    private TorchLight() {}

    /** Intensity multiplier in {@code [0.8, 1.0]} for the given clip-elapsed time. */
    public static float intensity(float elapsed) {
        return PROFILE.intensity(elapsed);
    }
}
