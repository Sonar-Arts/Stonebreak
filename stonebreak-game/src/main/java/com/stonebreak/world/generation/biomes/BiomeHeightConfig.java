package com.stonebreak.world.generation.biomes;

/**
 * Configuration class for biome-specific height modifications.
 *
 * Each biome has its own noise parameters that modify the base terrain height,
 * creating distinct terrain characteristics (gentle plains, flat deserts, rolling hills, etc.).
 *
 * Parameters:
 * - noiseScale: Controls the frequency of terrain variation (larger = broader features)
 * - amplitude: Controls the magnitude of height variation (larger = more dramatic terrain)
 */
public class BiomeHeightConfig {

    /**
     * Configuration for a single biome's height modifier.
     */
    public static class BiomeConfig {
        public final float noiseScale;
        public final float amplitude;

        public BiomeConfig(float noiseScale, float amplitude) {
            this.noiseScale = noiseScale;
            this.amplitude = amplitude;
        }
    }

    // PLAINS: Gentle rolling hills
    // - 50-block scale creates medium-frequency variations
    // - 7.5 block amplitude (5-10 block range) for subtle elevation changes
    public static final BiomeConfig PLAINS = new BiomeConfig(50.0f, 7.5f);

    // DESERT: Flat desert floor with subtle dune-like undulations
    // - 120-block scale creates large, gentle curves
    // - 3.5 block amplitude (2-5 block range) for minimal variation
    public static final BiomeConfig DESERT = new BiomeConfig(120.0f, 3.5f);

    // RED_SAND_DESERT: Rolling volcanic hills with more dramatic terrain
    // - 90-block scale creates medium-large rolling features
    // - 10.0 block amplitude (8-12 block range) for volcanic landscape
    public static final BiomeConfig RED_SAND_DESERT = new BiomeConfig(90.0f, 10.0f);

    // SNOWY_PLAINS: Gentle snowy hills for tundra-like terrain
    // - 100-block scale creates moderate rolling terrain
    // - 12.5 block amplitude (10-15 block range) for distinct frozen hills
    public static final BiomeConfig SNOWY_PLAINS = new BiomeConfig(100.0f, 12.5f);

    /**
     * Gets the configuration for a specific biome type.
     *
     * @param biome The biome type
     * @return The height modifier configuration for this biome
     */
    public static BiomeConfig getConfig(BiomeType biome) {
        return switch (biome) {
            case PLAINS -> PLAINS;
            case DESERT -> DESERT;
            case RED_SAND_DESERT -> RED_SAND_DESERT;
            case SNOWY_PLAINS -> SNOWY_PLAINS;
        };
    }
}
