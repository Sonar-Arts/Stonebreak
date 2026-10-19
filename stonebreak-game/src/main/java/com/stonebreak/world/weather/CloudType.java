package com.stonebreak.world.weather;

/**
 * The kinds of cloud the {@link CloudField} produces. Each type is a vertical
 * profile plus optics; the field picks one per column from its wetness.
 *
 * <p>{@code sigma} is the extinction per block at full density: a ray crossing
 * {@code d} blocks of cloud keeps {@code exp(-sigma * density * d)} of the light
 * behind it — the same Beer-Lambert rule the water shader uses, so thin cloud is
 * see-through and thick cloud goes opaque. The GPU reads these values as
 * uniforms (see {@code CloudUniforms}), so this enum is their only source.</p>
 *
 * <p>The ordinal is the type id the shaders index by; append new types at the end.</p>
 */
public enum CloudType {
    //            baseY  maxHeight  sigma   albedo  rain   storm
    CUMULUS      (176,   48,        0.060f, 1.00f,  0f,    false),
    STRATUS      (200,   8,         0.025f, 0.95f,  0f,    false),
    NIMBOSTRATUS (160,   48,        0.090f, 0.62f,  0.7f,  false),
    CUMULONIMBUS (152,   136,       0.140f, 0.52f,  1f,    true);

    /** Lowest block a cloud of this type occupies; a multiple of {@link CloudField#VOXEL}. */
    public final int baseY;
    /** Tallest a full-density column of this type grows above {@link #baseY}. */
    public final int maxHeight;
    public final float sigma;
    /** Reflectance in [0, 1]; storm clouds are dark from every side. */
    public final float albedo;
    /** Rain or snow a full-density, fully grown column of this type drops, 0..1. */
    public final float rain;
    public final boolean precipitates;
    public final boolean storm;

    CloudType(int baseY, int maxHeight, float sigma, float albedo, float rain, boolean storm) {
        this.baseY = baseY;
        this.maxHeight = maxHeight;
        this.sigma = sigma;
        this.albedo = albedo;
        this.rain = rain;
        this.precipitates = rain > 0f;
        this.storm = storm;
    }

    private static final CloudType[] VALUES = values();

    public static CloudType byId(int id) {
        return VALUES[id];
    }

    /** Wetness below this is stratus. Mirrored by {@code cloudTypeOf} in cloud_field.glsl via uniforms. */
    public static final float STRATUS_MAX_WETNESS = 0.15f;
    /** Wetness at or above this rains. */
    public static final float NIMBOSTRATUS_MIN_WETNESS = 0.66f;
    /** Wetness at or above this storms. */
    public static final float CUMULONIMBUS_MIN_WETNESS = 0.88f;

    /** Picks the type for a column whose wetness (noise plus weather bias) is {@code wetness}. */
    public static CloudType forWetness(float wetness) {
        if (wetness >= CUMULONIMBUS_MIN_WETNESS) return CUMULONIMBUS;
        if (wetness >= NIMBOSTRATUS_MIN_WETNESS) return NIMBOSTRATUS;
        if (wetness < STRATUS_MAX_WETNESS) return STRATUS;
        return CUMULUS;
    }

    /**
     * Wetness range over which one type fades into the next where a column is
     * drawn and rains (the column's {@link #forWetness type} still switches at
     * the split). Mirrored by {@code cloudTypeWeights} in cloud_field.glsl.
     */
    public static final float TYPE_BLEND = 0.04f;

    /**
     * How much of each type a column of {@code wetness} holds, indexed by
     * ordinal; sums to 1. The same cross-fades the clouds are drawn with.
     */
    public static float[] weights(float wetness) {
        float cu = CloudField.smoothstep(-TYPE_BLEND, TYPE_BLEND, wetness - STRATUS_MAX_WETNESS);
        float ns = CloudField.smoothstep(-TYPE_BLEND, TYPE_BLEND, wetness - NIMBOSTRATUS_MIN_WETNESS);
        float cb = CloudField.smoothstep(-TYPE_BLEND, TYPE_BLEND, wetness - CUMULONIMBUS_MIN_WETNESS);
        float[] w = new float[VALUES.length];
        w[CUMULUS.ordinal()] = cu * (1f - ns);
        w[STRATUS.ordinal()] = 1f - cu;
        w[NIMBOSTRATUS.ordinal()] = ns * (1f - cb);
        w[CUMULONIMBUS.ordinal()] = cb;
        return w;
    }

    /** Lowest base of any type: the bottom of the cloud slab. */
    public static int slabBottom() {
        int min = Integer.MAX_VALUE;
        for (CloudType t : VALUES) min = Math.min(min, t.baseY);
        return min;
    }

    /** Highest possible top of any type: the top of the cloud slab. */
    public static int slabTop() {
        int max = Integer.MIN_VALUE;
        for (CloudType t : VALUES) max = Math.max(max, t.baseY + t.maxHeight);
        return max;
    }
}
