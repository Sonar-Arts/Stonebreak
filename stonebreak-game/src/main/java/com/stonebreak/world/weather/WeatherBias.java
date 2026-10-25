package com.stonebreak.world.weather;

/**
 * Offsets the weather applies to the raw cloud noise at a place: the regional
 * weather, {@link WeatherZone}s and the terrain's own effect all add up to one.
 *
 * @param coverage in [-1, 1]: -1 clears the sky, 1 overcasts it
 * @param wetness  added to each column's wetness noise, pushing types toward rain and storm
 */
public record WeatherBias(float coverage, float wetness) {

    /** This bias with {@code dCoverage} and {@code dWetness} added. */
    public WeatherBias plus(float dCoverage, float dWetness) {
        return new WeatherBias(coverage + dCoverage, wetness + dWetness);
    }

    /** Moves this bias toward {@code target} by {@code t} in [0, 1]. */
    public WeatherBias lerp(WeatherBias target, float t) {
        return new WeatherBias(
                coverage + (target.coverage - coverage) * t,
                wetness + (target.wetness - wetness) * t);
    }
}
