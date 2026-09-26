package com.stonebreak.ui.terrainMapper.visualization;

/**
 * Every preview mode the terrain mapper knows. Which of them a generator offers, and in
 * what sidebar order, is {@link GeneratorPreview#modes()}.
 */
public enum VisualizerKind {
    HEIGHT("Height"),
    TOPOGRAPHY("Topography"),
    CONTINENTALNESS("Continentalness"),
    EROSION("Erosion"),
    PEAKS_VALLEYS("Peaks & Valleys"),
    TEMPERATURE("Temperature"),
    MOISTURE("Moisture"),
    BIOME("Biome"),
    WATER("Water");

    private final String displayName;

    VisualizerKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
