package com.stonebreak.world.generation;

import java.util.Locale;

/**
 * How a DaedalusTGM-Exp (EXP-2) world lays out its climate: chosen once at world creation and saved with the world
 * ({@code WorldData#getClimateLayout}), part of its identity like the seed and the terrain scale. Each layout is its
 * own world in the TGMPipe handshake (see {@code TerrainScale#worldIndex}), so tiles and every cache built from them
 * are kept apart per layout. Standard (noise) worlds carry the value but ignore it.
 */
public enum ClimateLayout {

    /** Banded climate belts (the service default). */
    BELTS("belts", "Belts"),
    /** Patchwork climate zones. */
    ZONES("zones", "Zones"),
    /** Climate by latitude (cold towards the poles). */
    LATITUDE("latitude", "Latitude");

    /** The layout new worlds and old saves (no {@code climateLayout} in world.json) get. */
    public static final ClimateLayout DEFAULT = BELTS;

    private final String value;
    private final String label;

    ClimateLayout(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /** The service's spelling ({@code WorldConfig.climate_layout}): {@code belts}, {@code zones}, {@code latitude}. */
    public String value() {
        return value;
    }

    /** The name menus and logs show. */
    public String label() {
        return label;
    }

    /** {@code layout}, or {@link #DEFAULT} when null. */
    public static ClimateLayout orDefault(ClimateLayout layout) {
        return layout == null ? DEFAULT : layout;
    }

    /**
     * The service value ({@code zones}), the label or the constant name, any case; null for anything else (including
     * null/blank).
     */
    public static ClimateLayout parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        for (ClimateLayout l : values()) {
            if (l.value.equals(t) || l.label.toLowerCase(Locale.ROOT).equals(t)
                    || l.name().toLowerCase(Locale.ROOT).equals(t)) {
                return l;
            }
        }
        return null;
    }

    /** {@link #parse}, falling back to {@code fallback} when unrecognised. */
    public static ClimateLayout parse(String text, ClimateLayout fallback) {
        ClimateLayout l = parse(text);
        return l != null ? l : fallback;
    }

    /** The layout after {@code layout}, wrapping (for a single cycling control). */
    public static ClimateLayout next(ClimateLayout layout) {
        ClimateLayout[] all = values();
        return all[(orDefault(layout).ordinal() + 1) % all.length];
    }
}
