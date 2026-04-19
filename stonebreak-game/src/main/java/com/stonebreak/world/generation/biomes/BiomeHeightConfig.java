package com.stonebreak.world.generation.biomes;

/**
 * Per-biome noise parameters that modulate terrain height on top of the base
 * continentalness signal. Each biome gets a distinct character:
 * <ul>
 *   <li>PLAINS - gentle rolling hills</li>
 *   <li>DESERT - nearly flat with subtle dunes</li>
 *   <li>RED_SAND_DESERT - rolling volcanic hills</li>
 *   <li>SNOWY_PLAINS - moderate snowy hills</li>
 * </ul>
 */
public final class BiomeHeightConfig {

    /** Immutable per-biome noise configuration. */
    public static final class BiomeConfig {
        public final float noiseScale;
        public final float amplitude;

        public BiomeConfig(float noiseScale, float amplitude) {
            this.noiseScale = noiseScale;
            this.amplitude = amplitude;
        }
    }

    public static final BiomeConfig PLAINS           = new BiomeConfig(50.0f,  7.5f);
    public static final BiomeConfig DESERT           = new BiomeConfig(120.0f, 3.5f);
    public static final BiomeConfig RED_SAND_DESERT  = new BiomeConfig(90.0f, 10.0f);
    public static final BiomeConfig SNOWY_PLAINS     = new BiomeConfig(100.0f, 12.5f);

    private BiomeHeightConfig() {}

    public static BiomeConfig getConfig(BiomeType biome) {
        return switch (biome) {
            case PLAINS          -> PLAINS;
            case DESERT          -> DESERT;
            case RED_SAND_DESERT -> RED_SAND_DESERT;
            case SNOWY_PLAINS    -> SNOWY_PLAINS;
        };
    }
}
