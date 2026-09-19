package com.stonebreak.rendering.UI.masonryUI;

/** ARGB colour arithmetic shared by widgets (fades, blends, value ramps). Pure functions. */
public final class MColor {

    private MColor() {}

    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** {@code color} with its alpha multiplied by {@code alpha} (0..1). */
    public static int fade(int color, float alpha) {
        int a = Math.round(((color >>> 24) & 0xFF) * clamp01(alpha));
        return (a << 24) | (color & 0xFFFFFF);
    }

    /** {@code color} with its alpha replaced by {@code alpha} (0..1). */
    public static int withAlpha(int color, float alpha) {
        return (Math.round(255f * clamp01(alpha)) << 24) | (color & 0xFFFFFF);
    }

    /** Component-wise blend, {@code t} = 0 gives {@code a}, 1 gives {@code b}. */
    public static int lerp(int a, int b, float t) {
        float k = clamp01(t);
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * k) << 24) | (Math.round(ar + (br - ar) * k) << 16)
                | (Math.round(ag + (bg - ag) * k) << 8) | Math.round(ab + (bb - ab) * k);
    }

    /**
     * Three-stop ramp for a 0..1 fraction: {@code low} at or below {@code lowAt}, {@code high} at or
     * above {@code highAt}, passing through {@code mid} halfway between. The health-bar colour rule.
     */
    public static int ramp(float fraction, int low, int mid, int high, float lowAt, float highAt) {
        float f = clamp01(fraction);
        if (f <= lowAt) return low;
        if (f >= highAt) return high;
        float k = (f - lowAt) / (highAt - lowAt);
        return k < 0.5f ? lerp(low, mid, k * 2f) : lerp(mid, high, (k - 0.5f) * 2f);
    }

    /** The shared vital ramp: {@link MStyle#VITAL_CRIT} → {@link MStyle#VITAL_WARN} → {@link MStyle#VITAL_OK}. */
    public static int vital(float fraction) {
        return ramp(fraction, MStyle.VITAL_CRIT, MStyle.VITAL_WARN, MStyle.VITAL_OK, 0.2f, 0.6f);
    }
}
