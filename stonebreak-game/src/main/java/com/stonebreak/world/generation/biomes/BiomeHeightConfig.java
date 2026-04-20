package com.stonebreak.world.generation.biomes;

/**
 * Per-biome noise parameters that modulate terrain height on top of the base
 * continentalness signal. Each biome gets a distinct character (scale = wavelength
 * in blocks, amplitude = vertical range). Density-carving intensities (0..1) opt
 * the biome into 3D cave / overhang carving via Density3D.
 */
public final class BiomeHeightConfig {

    /** Immutable per-biome noise configuration. */
    public static final class BiomeConfig {
        public final float noiseScale;
        public final float amplitude;
        public final float caveIntensity;
        public final float overhangIntensity;

        public BiomeConfig(float noiseScale, float amplitude,
                           float caveIntensity, float overhangIntensity) {
            this.noiseScale = noiseScale;
            this.amplitude = amplitude;
            this.caveIntensity = caveIntensity;
            this.overhangIntensity = overhangIntensity;
        }
    }

    public static final BiomeConfig PLAINS           = new BiomeConfig(50.0f,   7.5f, 0.22f, 0.10f);
    public static final BiomeConfig DESERT           = new BiomeConfig(120.0f,  3.5f, 0.00f, 0.00f);
    public static final BiomeConfig RED_SAND_DESERT  = new BiomeConfig(90.0f,  10.0f, 0.25f, 0.20f);
    public static final BiomeConfig SNOWY_PLAINS     = new BiomeConfig(100.0f, 12.5f, 0.18f, 0.08f);
    public static final BiomeConfig TUNDRA           = new BiomeConfig(150.0f,  6.0f, 0.22f, 0.18f);
    public static final BiomeConfig TAIGA            = new BiomeConfig(80.0f,  12.0f, 0.22f, 0.15f);
    public static final BiomeConfig STONY_PEAKS      = new BiomeConfig(40.0f,  20.0f, 0.30f, 0.35f);
    public static final BiomeConfig GRAVEL_BEACH     = new BiomeConfig(180.0f,  2.0f, 0.00f, 0.00f);
    public static final BiomeConfig ICE_FIELDS       = new BiomeConfig(120.0f,  8.0f, 0.00f, 0.00f);
    public static final BiomeConfig BADLANDS         = new BiomeConfig(60.0f,  15.0f, 0.28f, 0.32f);
    public static final BiomeConfig MEADOW           = new BiomeConfig(70.0f,   9.0f, 0.20f, 0.10f);

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
