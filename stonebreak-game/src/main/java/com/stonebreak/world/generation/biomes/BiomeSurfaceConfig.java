package com.stonebreak.world.generation.biomes;

/**
 * Per-biome 3D carving intensities for {@code Density3D}. Kept intentionally small:
 * height shaping lives in the noise router, not biomes.
 *
 * caveIntensity controls cave carving below the surface overhang zone; overhangIntensity
 * controls carving near the surface (cliffs, overhangs). Both in [0, 1].
 */
public final class BiomeSurfaceConfig {

    public static final class Entry {
        public final float caveIntensity;
        public final float overhangIntensity;
        /**
         * Which noise channel drives this biome's overhang band.
         *
         * <p>False (the default) reads the <em>cheese</em> channel, whose wavelength is chosen
         * for big underground chambers. True reads the dedicated <em>crag</em> channel, which
         * is roughly a quarter of that wavelength.
         *
         * <p>The distinction exists because the band is a silhouette decision, and silhouette
         * wants a different scale than chambers do. At the cheese wavelength an intensity that
         * should read as a broken cliff face instead lands as one 40-80 block bite out of the
         * hillside — the same fraction of blocks carved, arriving as one crater rather than as
         * many crags. STONY_PEAKS carries the highest overhangIntensity in the table, so it is
         * the biome where that difference stops being subtle, and it is the only biome that
         * opts in. Everything else keeps the cheese channel and is bit-for-bit unchanged.
         */
        public final boolean cragSurface;

        public Entry(float caveIntensity, float overhangIntensity) {
            this(caveIntensity, overhangIntensity, false);
        }

        public Entry(float caveIntensity, float overhangIntensity, boolean cragSurface) {
            this.caveIntensity = caveIntensity;
            this.overhangIntensity = overhangIntensity;
            this.cragSurface = cragSurface;
        }
    }

    private static final Entry PLAINS           = new Entry(0.22f, 0.10f);
    private static final Entry DESERT           = new Entry(0.00f, 0.00f);
    private static final Entry RED_SAND_DESERT  = new Entry(0.25f, 0.20f);
    private static final Entry SNOWY_PLAINS     = new Entry(0.18f, 0.08f);
    private static final Entry TUNDRA           = new Entry(0.22f, 0.18f);
    private static final Entry TAIGA            = new Entry(0.22f, 0.15f);
    private static final Entry STONY_PEAKS      = new Entry(0.30f, 0.35f, true);
    private static final Entry BEACH            = new Entry(0.00f, 0.00f);
    private static final Entry ICE_FIELDS       = new Entry(0.00f, 0.00f);
    private static final Entry BADLANDS         = new Entry(0.28f, 0.32f);
    private static final Entry MEADOW           = new Entry(0.20f, 0.10f);

    private BiomeSurfaceConfig() {}

    public static Entry get(BiomeType biome) {
        return switch (biome) {
            case PLAINS          -> PLAINS;
            case DESERT          -> DESERT;
            case RED_SAND_DESERT -> RED_SAND_DESERT;
            case SNOWY_PLAINS    -> SNOWY_PLAINS;
            case TUNDRA          -> TUNDRA;
            case TAIGA           -> TAIGA;
            case STONY_PEAKS     -> STONY_PEAKS;
            case BEACH           -> BEACH;
            case ICE_FIELDS      -> ICE_FIELDS;
            case BADLANDS        -> BADLANDS;
            case MEADOW          -> MEADOW;
        };
    }
}
