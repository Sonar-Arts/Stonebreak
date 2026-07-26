package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the vanilla-Minecraft biome id -> Stonebreak {@link BiomeType} table
 * (plan.md Phase 4), including the water-level-keyed beach override (Phase 9)
 * and unknown-id fallback.
 */
class DiffusionBiomeMapperTest {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final int WELL_ABOVE_SHORE = SEA_LEVEL + 50;
    private static final int NO_WATER = TerrainTile.NO_WATER;

    @Test
    void mapsCoreLandIds() {
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 3, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.DESERT, DiffusionBiomeMapper.map((short) 5, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.TAIGA, DiffusionBiomeMapper.map((short) 15, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.RED_SAND_DESERT, DiffusionBiomeMapper.map((short) 17, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.STONY_PEAKS, DiffusionBiomeMapper.map((short) 19, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.BADLANDS, DiffusionBiomeMapper.map((short) 26, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.MEADOW, DiffusionBiomeMapper.map((short) 29, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.TUNDRA, DiffusionBiomeMapper.map((short) 31, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 33, WELL_ABOVE_SHORE, NO_WATER));
        assertEquals(BiomeType.STONY_PEAKS, DiffusionBiomeMapper.map((short) 35, WELL_ABOVE_SHORE, NO_WATER));
    }

    @Test
    void mapsSparseAndAliasVariantsToTheirBaseBiome() {
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 8, WELL_ABOVE_SHORE, NO_WATER));   // forest
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 23, WELL_ABOVE_SHORE, NO_WATER));  // jungle
        assertEquals(BiomeType.MEADOW, DiffusionBiomeMapper.map((short) 6, WELL_ABOVE_SHORE, NO_WATER));   // swamp
        assertEquals(BiomeType.MEADOW, DiffusionBiomeMapper.map((short) 108, WELL_ABOVE_SHORE, NO_WATER)); // forest_sparse
        assertEquals(BiomeType.TAIGA, DiffusionBiomeMapper.map((short) 16, WELL_ABOVE_SHORE, NO_WATER));   // snowy_taiga
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 115, WELL_ABOVE_SHORE, NO_WATER)); // taiga_sparse
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 116, WELL_ABOVE_SHORE, NO_WATER)); // snowy_taiga_sparse
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 32, WELL_ABOVE_SHORE, NO_WATER));    // snowy_slopes
    }

    @Test
    void oceanIdsMapToSeafloorMaterialByTemperature() {
        // Ocean columns are submerged, so a real caller's nearbyWaterLevel is SEA_LEVEL here too.
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 41, SEA_LEVEL - 20, SEA_LEVEL)); // warm_ocean
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 44, SEA_LEVEL - 20, SEA_LEVEL)); // ocean
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 46, SEA_LEVEL - 20, SEA_LEVEL)); // cold_ocean
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 48, SEA_LEVEL - 20, SEA_LEVEL)); // frozen_ocean
    }

    @Test
    void beachEligibleLandBiomesNearShoreBecomeBeach() {
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL, SEA_LEVEL));     // plains, at sea level, sea nearby
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 5, SEA_LEVEL + 2, SEA_LEVEL)); // desert, just above
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL + 8, SEA_LEVEL)); // just outside the band
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL - 1, SEA_LEVEL)); // below sea level, not "near shore land"
    }

    @Test
    void noNearbyWaterMeansNoShoreOverrideRegardlessOfHeight() {
        // A dry column sitting at the old global sea-level band, far from any actual water
        // (e.g. a high plateau that happens to cross y=320), must not turn into a beach.
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL, NO_WATER));
        assertEquals(BiomeType.DESERT, DiffusionBiomeMapper.map((short) 5, SEA_LEVEL + 2, NO_WATER));
    }

    @Test
    void inlandLakeShoreGetsTheSameBeachTreatmentAsTheOcean() {
        int lakeLevel = SEA_LEVEL + 340; // an inland lake surface, well above sea level
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 1, lakeLevel, lakeLevel));
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 1, lakeLevel + 7, lakeLevel));
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, lakeLevel + 8, lakeLevel)); // outside the band
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL, lakeLevel)); // near sea level but the water nearby is the lake's, far above
    }

    @Test
    void beachIneligibleBiomesAreUnaffectedByShoreHeight() {
        assertEquals(BiomeType.STONY_PEAKS, DiffusionBiomeMapper.map((short) 35, SEA_LEVEL, SEA_LEVEL));
        assertEquals(BiomeType.BADLANDS, DiffusionBiomeMapper.map((short) 26, SEA_LEVEL, SEA_LEVEL));
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 33, SEA_LEVEL, SEA_LEVEL));
        assertEquals(BiomeType.TAIGA, DiffusionBiomeMapper.map((short) 15, SEA_LEVEL, SEA_LEVEL));
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 3, SEA_LEVEL, SEA_LEVEL));
        assertEquals(BiomeType.TUNDRA, DiffusionBiomeMapper.map((short) 31, SEA_LEVEL, SEA_LEVEL));
    }

    @Test
    void unrecognizedIdFailsSoftToPlains() {
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 9999, WELL_ABOVE_SHORE, NO_WATER));
    }
}
