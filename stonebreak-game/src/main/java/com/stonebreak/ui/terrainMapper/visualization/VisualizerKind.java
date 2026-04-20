package com.stonebreak.ui.terrainMapper.visualization;

/**
 * Enumerates every noise-preview mode available in the terrain mapper.
 * Order determines sidebar button order.
 */
public enum VisualizerKind {
    HEIGHT("Height"),
    CONTINENTALNESS("Continentalness"),
    EROSION("Erosion"),
    PEAKS_VALLEYS("Peaks & Valleys"),
    TEMPERATURE("Temperature"),
    MOISTURE("Moisture"),
    BIOME("Biome");

    private final String displayName;

    VisualizerKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
