package com.stonebreak.ui.focusBattle.timed;

import com.stonebreak.rendering.UI.masonryUI.MColor;
import com.stonebreak.ui.startupIntro.tween.EasingFunctions;
import com.stonebreak.ui.startupIntro.tween.EasingType;

/**
 * The few timeline shapes the timed-input overlays share, composed from the game's one easing
 * table ({@link EasingFunctions}) so no overlay carries a curve of its own. Pure functions of a
 * normalised time; nothing here reads a clock.
 */
final class TimedMotion {

    private TimedMotion() {}

    /** {@code type} over a clamped 0..1 time (the easing table itself does not clamp). */
    static float ease(float t, EasingType type) {
        return EasingFunctions.apply(MColor.clamp01(t), type);
    }

    /** Fast start, soft landing: growth of bursts, pops settling, things sliding into place. */
    static float settle(float t) {
        return ease(t, EasingType.EaseOutCubic);
    }

    /** Soft at both ends: intensities easing in and out. */
    static float smooth(float t) {
        return ease(t, EasingType.EaseInOutQuad);
    }

    /** Opacity of something that holds until {@code from} (0..1 of its life), then fades out by 1. */
    static float holdThenFade(float t, float from) {
        float span = Math.max(1.0e-4f, 1f - from);
        return 1f - smooth((t - from) / span);
    }

    /** 0 → 1 → 0 over {@code t} 0..1 with a fast attack (first fifth) and an eased release. */
    static float flash(float t) {
        float k = MColor.clamp01(t);
        if (k <= 0f || k >= 1f) return 0f;
        float attack = 0.2f;
        return k < attack ? k / attack : 1f - smooth((k - attack) / (1f - attack));
    }

    /** A pop that lands {@code overshoot} oversized and settles to 1 over the first {@code span} of its life. */
    static float pop(float t, float overshoot, float span) {
        return 1f + overshoot * (1f - settle(t / Math.max(1.0e-4f, span)));
    }
}
