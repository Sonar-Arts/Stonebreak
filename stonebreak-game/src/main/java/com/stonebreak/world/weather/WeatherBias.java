package com.stonebreak.world.weather;

/**
 * Global offsets the weather applies to the raw cloud noise.
 *
 * @param coverage in [-1, 1]: -1 clears the sky, 1 overcasts it
 * @param wetness  added to each column's wetness noise, pushing types toward rain and storm
 */
public record WeatherBias(float coverage, float wetness) {

    /** Moves this bias toward {@code target} by {@code t} in [0, 1]. */
    public WeatherBias lerp(WeatherBias target, float t) {
        return new WeatherBias(
                coverage + (target.coverage - coverage) * t,
                wetness + (target.wetness - wetness) * t);
    }
}
