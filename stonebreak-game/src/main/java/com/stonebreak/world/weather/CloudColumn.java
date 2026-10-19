package com.stonebreak.world.weather;

/**
 * The cloud over one column, resolved for the current weather: how dense it
 * is, what type it is and the block range it fills. {@link #CLEAR} when there
 * is no cloud.
 *
 * @param density in [0, 1]; 0 means clear sky
 * @param type    null when clear
 * @param baseY   first block of cloud (inclusive)
 * @param topY    block above the last block of cloud (exclusive)
 * @param build   0..1: how far the column has grown toward its type's full height
 * @param wet     wetness with the weather bias applied (what picked the type)
 */
public record CloudColumn(float density, CloudType type, int baseY, int topY, float build, float wet) {

    public static final CloudColumn CLEAR = new CloudColumn(0f, null, 0, 0, 0f, 0f);

    /**
     * Share of a fully grown column's rain that the smallest cloud drops: a
     * little shower falls lighter than the cloud mass a front brings.
     */
    public static final float SMALL_CLOUD_RAIN = 0.3f;

    public boolean isClear() {
        return density <= 0f;
    }

    /**
     * Rain or snow falling from this column, in [0, 1]: each type's
     * {@link CloudType#rain rate} by its share of the column (so rain fades in
     * across a type boundary instead of switching on), times the density, and
     * lighter for smaller clouds. Mirrored by {@code cloudPrecipitation} in
     * cloud_field.glsl.
     */
    public float precipitation() {
        if (isClear()) return 0f;
        float[] w = CloudType.weights(wet);
        float rate = 0f;
        for (CloudType t : CloudType.values()) rate += w[t.ordinal()] * t.rain;
        return density * rate * (SMALL_CLOUD_RAIN + (1f - SMALL_CLOUD_RAIN) * build);
    }
}
