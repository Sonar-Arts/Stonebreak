package com.stonebreak.world.generation.biomes;

/**
 * Per-biome noise parameters that modulate terrain height on top of the base
 * continentalness signal. Each biome gets a distinct character (scale = wavelength
 * in blocks, amplitude = vertical range).
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

    public static final BiomeConfig PLAINS           = new BiomeConfig(50.0f,   7.5f);
    public static final BiomeConfig DESERT           = new BiomeConfig(120.0f,  3.5f);
    public static final BiomeConfig RED_SAND_DESERT  = new BiomeConfig(90.0f,  10.0f);
    public static final BiomeConfig SNOWY_PLAINS     = new BiomeConfig(100.0f, 12.5f);
    public static final BiomeConfig TUNDRA           = new BiomeConfig(150.0f,  6.0f);
    public static final BiomeConfig TAIGA            = new BiomeConfig(80.0f,  12.0f);
    public static final BiomeConfig STONY_PEAKS      = new BiomeConfig(40.0f,  20.0f);
    public static final BiomeConfig GRAVEL_BEACH     = new BiomeConfig(180.0f,  2.0f);
    public static final BiomeConfig ICE_FIELDS       = new BiomeConfig(120.0f,  8.0f);
    public static final BiomeConfig BADLANDS         = new BiomeConfig(60.0f,  15.0f);
    public static final BiomeConfig MEADOW           = new BiomeConfig(70.0f,   9.0f);

    private BiomeHeightConfig() {}

    public static BiomeConfig getConfig(BiomeType biome) {
        return switch (biome) {
            case PLAINS          -> PLAINS;
            case DESERT          -> DESERT;
            case RED_SAND_DESERT -> RED_SAND_DESERT;
            case SNOWY_PLAINS    -> SNOWY_PLAINS;
            case TUNDRA          -> TUNDRA;
            case TAIGA           -> TAIGA;
            case STONY_PEAKS     -> STONY_PEAKS;
            case GRAVEL_BEACH    -> GRAVEL_BEACH;
            case ICE_FIELDS      -> ICE_FIELDS;
            case BADLANDS        -> BADLANDS;
            case MEADOW          -> MEADOW;
        };
    }
}
