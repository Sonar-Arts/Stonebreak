package com.stonebreak.ui.startupIntro.tween;

public final class EasingFunctions {

    private EasingFunctions() {}

    public static float apply(float t, EasingType type) {
        return switch (type) {
            case Linear -> t;
            case EaseInQuad -> t * t;
            case EaseOutQuad -> t * (2 - t);
            case EaseInOutQuad -> t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t;
            case EaseInCubic -> t * t * t;
            case EaseOutCubic -> {
                float u = t - 1f;
                yield u * u * u + 1f;
            }
            case EaseInOutCubic -> t < 0.5f ? 4 * t * t * t : (t - 1f) * (2 * t - 2) * (2 * t - 2) + 1f;
            case EaseInQuart -> t * t * t * t;
            case EaseOutQuart -> {
                float u = t - 1f;
                yield 1f - u * u * u * u;
            }
            case EaseInOutQuart -> {
                if (t < 0.5f) yield 8 * t * t * t * t;
                float u = t - 1f;
                yield 1f - 8 * u * u * u * u;
            }
            case EaseInExpo -> t == 0f ? 0f : (float) Math.pow(2, 10 * (t - 1));
            case EaseOutExpo -> t == 1f ? 1f : 1f - (float) Math.pow(2, -10 * t);
            case EaseInOutExpo -> easeInOutExpo(t);
            case EaseInElastic -> easeInElastic(t);
            case EaseOutElastic -> easeOutElastic(t);
            case EaseInOutElastic -> easeInOutElastic(t);
            case EaseInBounce -> 1f - easeOutBounce(1f - t);
            case EaseOutBounce -> easeOutBounce(t);
            case EaseInOutBounce -> t < 0.5f
                    ? (1f - easeOutBounce(1f - 2f * t)) * 0.5f
                    : (1f + easeOutBounce(2f * t - 1f)) * 0.5f;
        };
    }

    private static float easeInOutExpo(float t) {
        if (t == 0f) return 0f;
        if (t == 1f) return 1f;
        if (t < 0.5f) return (float) Math.pow(2, 20 * t - 10) * 0.5f;
        return (2f - (float) Math.pow(2, -20 * t + 10)) * 0.5f;
    }

    private static float easeInElastic(float t) {
        if (t == 0f) return 0f;
        if (t == 1f) return 1f;
        return -(float) Math.pow(2, 10 * (t - 1)) * (float) Math.sin((t - 1.1f) * 5 * Math.PI);
    }

    private static float easeOutElastic(float t) {
        if (t == 0f) return 0f;
        if (t == 1f) return 1f;
        return (float) Math.pow(2, -10 * t) * (float) Math.sin((t - 0.1f) * 5 * Math.PI) + 1f;
    }

    private static float easeInOutElastic(float t) {
        if (t == 0f) return 0f;
        if (t == 1f) return 1f;
        float u = t * 2f;
        if (u < 1f) {
            return -0.5f * (float) Math.pow(2, 10 * (u - 1)) * (float) Math.sin((u - 1.1f) * 5 * Math.PI);
        }
        return (float) Math.pow(2, -10 * (u - 1)) * (float) Math.sin((u - 1.1f) * 5 * Math.PI) * 0.5f + 1f;
    }

    private static float easeOutBounce(float t) {
        if (t < 1f / 2.75f) return 7.5625f * t * t;
        if (t < 2f / 2.75f) {
            float u = t - 1.5f / 2.75f;
            return 7.5625f * u * u + 0.75f;
        }
        if (t < 2.5f / 2.75f) {
            float u = t - 2.25f / 2.75f;
            return 7.5625f * u * u + 0.9375f;
        }
        float u = t - 2.625f / 2.75f;
        return 7.5625f * u * u + 0.984375f;
    }
}
