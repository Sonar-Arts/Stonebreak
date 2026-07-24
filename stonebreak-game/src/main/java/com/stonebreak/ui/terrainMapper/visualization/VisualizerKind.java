package com.stonebreak.ui.terrainMapper.visualization;

/**
 * Enumerates every preview mode available in the terrain mapper.
 * Order determines sidebar button order.
 */
public enum VisualizerKind {
    HEIGHT("Height"),
    TOPOGRAPHY("Topography"),
    BIOME("Biome");

    private final String displayName;

    VisualizerKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }
}
