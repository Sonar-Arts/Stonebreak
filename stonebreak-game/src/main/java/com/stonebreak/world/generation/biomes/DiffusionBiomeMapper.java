package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Translates the vanilla-Minecraft biome ids baked into diffusion-bridge
 * tiles into Stonebreak's own {@link BiomeType} registry (plan.md Phase 4).
 *
 * <p>Those ids come from terrain-bridge's upstream classifier
 * ({@code minecraft_api.py}'s {@code _classify_biome()}, itself a Python
 * sibling of terrain-diffusion-mc's {@code BiomeClassifier.java}) — climate
 * classification already happened server-side before the tile ever reaches
 * Java, so this class is a lookup table, not a second classifier. Stonebreak
 * has 11 biomes against the classifier's ~20+, so the mapping is necessarily
 * lossy; choices below favor preserving each Stonebreak biome's distinctive
 * mechanic (TAIGA/SNOWY_PLAINS/MEADOW/PLAINS tree density in
 * {@code VegetationGenerator}, RED_SAND_DESERT's magma pockets in
 * {@code TerrainGenerationSystem.determineBlockType}) over strict climate
 * fidelity. Per plan.md: "terrain diversity far outpaces biome diversity" —
 * expect this table to get hand-tuned further once it's visible in-game.
 */
final class DiffusionBiomeMapper {

    /**
     * Land columns within this many blocks above sea level get a shoreline
     * override to BEACH. The classifier has no beach concept of its own
     * (its land/ocean split is a hard elevation threshold) — this mirrors
     * vanilla Minecraft's own approach of deriving beaches from height as a
     * post-process rather than from climate.
     */
    private static final int BEACH_BAND_BLOCKS = 3;

    private DiffusionBiomeMapper() {}

    /** Maps one tile column's (biome id, final block height) to a Stonebreak biome. */
    static BiomeType map(short vanillaBiomeId, int height) {
        BiomeType biome = landBiomeFor(vanillaBiomeId);
        if (isBeachEligible(biome) && isNearShore(height)) {
            return BiomeType.BEACH;
        }
        return biome;
    }

    private static boolean isNearShore(int height) {
        int seaLevel = WorldConfiguration.SEA_LEVEL;
        return height >= seaLevel && height < seaLevel + BEACH_BAND_BLOCKS;
    }

    private static boolean isBeachEligible(BiomeType biome) {
        return switch (biome) {
            case ICE_FIELDS, STONY_PEAKS, BADLANDS, TUNDRA, TAIGA, SNOWY_PLAINS -> false;
            default -> true;
        };
    }

    private static BiomeType landBiomeFor(short id) {
        return switch (id) {
            // Ocean ids. The classifier's land/ocean split (elev < 0m) lines up
            // exactly with our own height < SEA_LEVEL split — both reduce to the
            // same formula, see terrain-bridge/bridge/height_mapping.py — so these
            // ids reliably mean "this column is underwater"; the surface/subsurface
            // block resolved from the returned biome becomes the seafloor material.
            case 41, 44 -> BiomeType.BEACH;       // warm_ocean, ocean -> sandy seafloor
            case 46, 48 -> BiomeType.ICE_FIELDS;  // cold_ocean, frozen_ocean

            case 1, 8, 23 -> BiomeType.PLAINS;    // plains, forest, jungle (best tree density available)
            case 3, 115, 116 -> BiomeType.SNOWY_PLAINS; // snowy_plains, taiga_sparse, snowy_taiga_sparse
            case 5 -> BiomeType.DESERT;
            case 6, 29, 108 -> BiomeType.MEADOW;  // swamp, meadow, forest_sparse
            case 15, 16 -> BiomeType.TAIGA;       // taiga, snowy_taiga
            case 17 -> BiomeType.RED_SAND_DESERT; // savanna: warm dry grassland w/ reddish soil
            case 19, 35 -> BiomeType.STONY_PEAKS; // windswept_hills, stony_peaks
            case 26 -> BiomeType.BADLANDS;
            case 31 -> BiomeType.TUNDRA;          // grove: cold semi-arid steppe
            case 32, 33 -> BiomeType.ICE_FIELDS;  // snowy_slopes, frozen_peaks

            // Unrecognized id (e.g. a newer upstream classifier version) — fail
            // soft to the most climate-neutral biome rather than propagating an
            // unmapped id or throwing mid-chunk-generation.
            default -> BiomeType.PLAINS;
        };
    }
}
