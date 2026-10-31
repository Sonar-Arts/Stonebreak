package com.stonebreak.world.generation.diffusion.biomes;

import com.stonebreak.world.generation.biomes.BiomeType;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Translates the vanilla-Minecraft biome ids baked into diffusion-bridge
 * tiles into Stonebreak's own {@link BiomeType} registry (plan.md Phase 4).
 *
 * <p>Those ids come from TGMPipe's vendored upstream classifier
 * ({@code terrain_slm/biomes.py}'s {@code _classify_biome()}, itself a Python
 * sibling of terrain-diffusion-mc's {@code BiomeClassifier.java}) — climate
 * classification already happened server-side before the tile ever reaches
 * Java, so this class is a lookup table, not a second classifier. Stonebreak
 * has fewer biomes than the classifier's ~20+, so the mapping is necessarily
 * lossy; choices below favor preserving each Stonebreak biome's distinctive
 * mechanic (TAIGA/SNOWY_PLAINS/MEADOW/PLAINS tree density in
 * {@code VegetationGenerator}, RED_SAND_DESERT's magma pockets in
 * {@code DiffusionTerrainGenerator.determineBlockType}) over strict climate
 * fidelity. Per plan.md: "terrain diversity far outpaces biome diversity" —
 * expect this table to get hand-tuned further once it's visible in-game.
 */
final class DiffusionBiomeMapper {

    /**
     * Land columns within this many blocks above the sea next to them get a
     * shoreline override to BEACH. The classifier has no beach concept of its
     * own — this mirrors vanilla Minecraft's own approach of deriving beaches
     * from height as a post-process rather than from climate. Only the TRUE
     * ocean makes beaches: the model gives ocean ids to the sea alone
     * ({@code terrain_slm/world/ocean.py}), and a lake — still water at sea
     * level in a basin off the sea — carries its land biome, so its shore and
     * the rivers running into it keep their grass.
     *
     * <p>Sized against the pre-Phase-5 15 m/block isotropy (3 blocks = 45 m of
     * shoreline). The Phase 5 curve's shoreline blend runs 4-12 m/block near
     * sea level (~6 m/block average, plan.md §10.6), so preserving the same
     * physical band width needs more blocks: {@code 45 / 6 ≈ 8}.
     */
    private static final int BEACH_BAND_BLOCKS = 8;

    private DiffusionBiomeMapper() {}

    /**
     * Maps one tile column's (biome id, final block height, nearby sea level)
     * to a Stonebreak biome.
     *
     * @param nearbySeaLevel the highest water level among this column's ocean
     *                       neighbours (columns whose biome id {@link #isOceanId is an
     *                       ocean id}), or {@link TerrainTile#NO_WATER} if none of them
     *                       is ocean. A lake or a river beside the column never counts.
     */
    static BiomeType map(short vanillaBiomeId, int height, int nearbySeaLevel) {
        BiomeType biome = landBiomeFor(vanillaBiomeId);
        if (isBeachEligible(biome) && isNearShore(height, nearbySeaLevel)) {
            return BiomeType.BEACH;
        }
        return biome;
    }

    /** True for the classifier's ocean ids, which the model assigns to the true ocean only. */
    static boolean isOceanId(short id) {
        return id == 41 || id == 44 || id == 46 || id == 48;
    }

    private static boolean isNearShore(int height, int nearbySeaLevel) {
        if (nearbySeaLevel == TerrainTile.NO_WATER || nearbySeaLevel > WorldConfiguration.SEA_LEVEL) {
            return false;
        }
        return height >= nearbySeaLevel && height < nearbySeaLevel + BEACH_BAND_BLOCKS;
    }

    private static boolean isBeachEligible(BiomeType biome) {
        return switch (biome) {
            case ICE_FIELDS, STONY_PEAKS, BADLANDS, TUNDRA, TAIGA, SNOWY_PLAINS -> false;
            default -> true;
        };
    }

    private static BiomeType landBiomeFor(short id) {
        return switch (id) {
            // Ocean ids: the TRUE ocean only (terrain_slm/world/ocean.py). Every such
            // column is underwater; a lake's columns are underwater too but carry the
            // land biome around them. The surface/subsurface block resolved from the
            // returned biome becomes the seafloor material.
            // Oceans are their own biome (2026-09-28): mapping them to BEACH painted every sea as a
            // giant beach in the terrain mapper. The shoreline band above still makes real beaches.
            case 41, 44, 46 -> BiomeType.OCEAN;   // warm_ocean, ocean, cold_ocean -> gravel sea floor
            case 48 -> BiomeType.ICE_FIELDS;      // frozen_ocean

            case 1, 8, 23 -> BiomeType.PLAINS;    // plains, forest, jungle (best tree density available)
            case 3, 116 -> BiomeType.SNOWY_PLAINS; // snowy_plains, snowy_taiga_sparse
            // taiga_sparse: cool, SNOW-FREE sparse conifer forest. Was SNOWY_PLAINS, which painted white strips
            // along every cool transition band (2026-09-28).
            case 115 -> BiomeType.MEADOW;
            case 5 -> BiomeType.DESERT;
            // red_desert (DaedalusTGM-Exp id 205): a WHOLE desert the model decided is red, one decision per
            // desert (terrain_slm generator.red_desert_cells), so red sand never comes in patches.
            case 205 -> BiomeType.RED_SAND_DESERT;
            // shade (DaedalusTGM-Exp id 206): patches of the Shade's own noise over dry temperate sparse forest and
            // steppe (biomes.py apply_shade, 2026-10-31), Standard's dry temperate woodland. Never the model's forest
            // or swamp: its moisture window ends far below theirs.
            case 206 -> BiomeType.SHADE;
            // swamp: patches overlaid on wet, mild, low-lying forest (biomes.py apply_swamps, 2026-10-18), never
            // near the snow line. Was MEADOW, which hid the model's swamps entirely (2026-10-11); SWAMP brings
            // swampy grass, pools and cypress.
            case 6 -> BiomeType.SWAMP;
            case 29, 108 -> BiomeType.MEADOW;     // meadow, forest_sparse
            case 15, 16 -> BiomeType.TAIGA;       // taiga, snowy_taiga
            // savanna: warm semi-arid grassland with sparse trees. Was RED_SAND_DESERT, which drew thin
            // orange cactus strips wherever a climate gradient passed through the savanna band (2026-09-28).
            case 17 -> BiomeType.PLAINS;
            case 19, 35 -> BiomeType.STONY_PEAKS; // windswept_hills, stony_peaks
            case 26 -> BiomeType.BADLANDS;
            case 31 -> BiomeType.TUNDRA;          // grove: cold barren ground only (v4 rule; semi-arid steppe is now plains)
            case 32, 33 -> BiomeType.ICE_FIELDS;  // snowy_slopes, frozen_peaks

            // Unrecognized id (e.g. a newer upstream classifier version) — fail
            // soft to the most climate-neutral biome rather than propagating an
            // unmapped id or throwing mid-chunk-generation.
            default -> BiomeType.PLAINS;
        };
    }
}
