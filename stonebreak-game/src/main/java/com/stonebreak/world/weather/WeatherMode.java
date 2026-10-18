package com.stonebreak.world.weather;

/**
 * The world's weather setting, chosen with {@code /weather}. Fixed modes pin
 * the {@link WeatherBias}; {@link #AUTO} lets {@link CloudField#bias} wander it
 * over time so clear spells and storms come and go on their own.
 */
public enum WeatherMode {
    //       coverage  wetness
    CLEAR   (-1.00f,   -0.40f),
    CLOUDY  ( 0.25f,   -0.20f),
    RAIN    ( 0.55f,    0.05f),
    STORM   ( 0.75f,    0.30f),
    AUTO    ( 0.00f,    0.00f);

    private final WeatherBias fixedBias;

    WeatherMode(float coverage, float wetness) {
        this.fixedBias = new WeatherBias(coverage, wetness);
    }

    /** The bias this mode pins; meaningless for {@link #AUTO}. */
    WeatherBias fixedBias() {
        return fixedBias;
    }

    private static final WeatherMode[] VALUES = values();

    /** Wire lookup by ordinal; unknown ids (a newer peer) fall back to {@link #AUTO}. */
    public static WeatherMode byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : AUTO;
    }

    /** Case-insensitive lookup for commands and saves; null when unknown. */
    public static WeatherMode parse(String name) {
        if (name == null) return null;
        for (WeatherMode m : values()) {
            if (m.name().equalsIgnoreCase(name.trim())) return m;
        }
        return null;
    }
}
