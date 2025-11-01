package com.stonebreak.world.generation.heightmap;

/**
 * Terrain hint enum that categorizes terrain types based on noise parameters.
 *
 * Terrain hints are detected BEFORE biome selection by analyzing parameter combinations.
 * This allows terrain generation to apply appropriate logic (mesa terracing, peak sharpening, etc.)
 * without needing to know the final biome.
 *
 * The hints align naturally with biomes because both use the same underlying parameters,
 * creating a "soft link" between terrain shape and biome selection.
 *
 * Thread-safe (immutable enum).
 */
public enum TerrainHint {
    /**
     * Mesa terrain: Flat-topped plateaus with strong terracing.
     * Characteristics: Hot + Dry + High Weirdness
     * Typical biomes: Badlands
     * Terrain features: Large stepped layers (16-24 blocks), plateau flattening
     */
    MESA,

    /**
     * Sharp peaks: Jagged mountain spires with extreme heights.
     * Characteristics: Cold + Low Erosion (very mountainous) + High PV
     * Typical biomes: Stony Peaks
     * Terrain features: Amplified spikes, vertical emphasis, jagged surfaces
     */
    SHARP_PEAKS,

    /**
     * Gentle hills: Rolling terrain with subtle elevation changes.
     * Characteristics: High Erosion + Low PV
     * Typical biomes: Plains, Snowy Plains, some Tundra
     * Terrain features: Smooth undulations, reduced height variation
     */
    GENTLE_HILLS,

    /**
     * Flat plains: Very flat terrain with minimal variation.
     * Characteristics: Very High Erosion + Low PV
     * Typical biomes: Plains, Beaches, Ice Fields
     * Terrain features: Almost no height variation, very smooth
     */
    FLAT_PLAINS,

    /**
     * Normal terrain: Standard terrain generation without special features.
     * Default case when no specific pattern is detected.
     * Terrain features: Standard erosion/PV/weirdness application
     */
    NORMAL
}
