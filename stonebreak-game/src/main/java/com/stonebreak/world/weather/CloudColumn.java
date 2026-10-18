package com.stonebreak.world.weather;

/**
 * One cloud-map cell resolved for the current weather: how dense the column is,
 * what type it is and the block range it fills. {@link #CLEAR} when the cell has
 * no cloud.
 *
 * @param density in [0, 1]; 0 means clear sky
 * @param type    null when clear
 * @param baseY   first block of cloud (inclusive)
 * @param topY    block above the last block of cloud (exclusive)
 */
public record CloudColumn(float density, CloudType type, int baseY, int topY) {

    public static final CloudColumn CLEAR = new CloudColumn(0f, null, 0, 0);

    public boolean isClear() {
        return density <= 0f;
    }

    /** Rain or snow falling from this column, in [0, 1]: 0 unless its type precipitates. */
    public float precipitation() {
        if (isClear() || !type.precipitates) return 0f;
        return density * (type.storm ? 1f : 0.7f);
    }
}
