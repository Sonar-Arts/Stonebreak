package com.stonebreak.rendering.lighting;

/**
 * How a point light glows: its tint, peak radiance and flicker pulse. Reach is not
 * part of a profile — every light shares {@link PointLightGlsl#RADIUS}, because the
 * point-shadow projection and the bounce volumes are sized to it.
 *
 * <p>The flicker is an eased pulse, low at the loop ends and high at its midpoint,
 * swinging between {@code floor} and {@code 1}. Deliberately nothing faster than the
 * pulse itself — a high-frequency shimmer reads as static, not fire.
 *
 * @param red    tint at full intensity
 * @param green  tint at full intensity
 * @param blue   tint at full intensity
 * @param peak   radiance a surface right at the emitter receives
 * @param period flicker loop length, seconds
 * @param floor  intensity multiplier at the dimmest point of the loop
 */
public record LightProfile(float red, float green, float blue, float peak, float period, float floor) {

    /** Intensity multiplier in {@code [floor, 1]} for the given loop-elapsed time. */
    public float intensity(float elapsed) {
        float t = elapsed / period;
        t -= (float) Math.floor(t);
        float tri = t < 0.5f ? t * 2f : 2f - t * 2f;          // 0 → 1 → 0 across the loop
        float eased = tri * tri * (3f - 2f * tri);              // ease-in-out
        return floor + (1f - floor) * eased;
    }
}
