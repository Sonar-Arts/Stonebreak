package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the vanilla-Minecraft biome id -> Stonebreak {@link BiomeType} table
 * (plan.md Phase 4), including the height-band beach override and unknown-id
 * fallback.
 */
class DiffusionBiomeMapperTest {

    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;
    private static final int WELL_ABOVE_SHORE = SEA_LEVEL + 50;

    @Test
    void mapsCoreLandIds() {
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 3, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.DESERT, DiffusionBiomeMapper.map((short) 5, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.TAIGA, DiffusionBiomeMapper.map((short) 15, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.RED_SAND_DESERT, DiffusionBiomeMapper.map((short) 17, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.STONY_PEAKS, DiffusionBiomeMapper.map((short) 19, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.BADLANDS, DiffusionBiomeMapper.map((short) 26, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.MEADOW, DiffusionBiomeMapper.map((short) 29, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.TUNDRA, DiffusionBiomeMapper.map((short) 31, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 33, WELL_ABOVE_SHORE));
        assertEquals(BiomeType.STONY_PEAKS, DiffusionBiomeMapper.map((short) 35, WELL_ABOVE_SHORE));
    }

    @Test
    void mapsSparseAndAliasVariantsToTheirBaseBiome() {
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 8, WELL_ABOVE_SHORE));   // forest
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 23, WELL_ABOVE_SHORE));  // jungle
        assertEquals(BiomeType.MEADOW, DiffusionBiomeMapper.map((short) 6, WELL_ABOVE_SHORE));   // swamp
        assertEquals(BiomeType.MEADOW, DiffusionBiomeMapper.map((short) 108, WELL_ABOVE_SHORE)); // forest_sparse
        assertEquals(BiomeType.TAIGA, DiffusionBiomeMapper.map((short) 16, WELL_ABOVE_SHORE));   // snowy_taiga
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 115, WELL_ABOVE_SHORE)); // taiga_sparse
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 116, WELL_ABOVE_SHORE)); // snowy_taiga_sparse
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 32, WELL_ABOVE_SHORE));    // snowy_slopes
    }

    @Test
    void oceanIdsMapToSeafloorMaterialByTemperature() {
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 41, SEA_LEVEL - 20)); // warm_ocean
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 44, SEA_LEVEL - 20)); // ocean
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 46, SEA_LEVEL - 20)); // cold_ocean
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 48, SEA_LEVEL - 20)); // frozen_ocean
    }

    @Test
    void beachEligibleLandBiomesNearShoreBecomeBeach() {
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL));     // plains, at sea level
        assertEquals(BiomeType.BEACH, DiffusionBiomeMapper.map((short) 5, SEA_LEVEL + 2)); // desert, just above
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL + 3)); // just outside the band
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 1, SEA_LEVEL - 1)); // below sea level, not "near shore land"
    }

    @Test
    void beachIneligibleBiomesAreUnaffectedByShoreHeight() {
        assertEquals(BiomeType.STONY_PEAKS, DiffusionBiomeMapper.map((short) 35, SEA_LEVEL));
        assertEquals(BiomeType.BADLANDS, DiffusionBiomeMapper.map((short) 26, SEA_LEVEL));
        assertEquals(BiomeType.ICE_FIELDS, DiffusionBiomeMapper.map((short) 33, SEA_LEVEL));
        assertEquals(BiomeType.TAIGA, DiffusionBiomeMapper.map((short) 15, SEA_LEVEL));
        assertEquals(BiomeType.SNOWY_PLAINS, DiffusionBiomeMapper.map((short) 3, SEA_LEVEL));
        assertEquals(BiomeType.TUNDRA, DiffusionBiomeMapper.map((short) 31, SEA_LEVEL));
    }

    @Test
    void unrecognizedIdFailsSoftToPlains() {
        assertEquals(BiomeType.PLAINS, DiffusionBiomeMapper.map((short) 9999, WELL_ABOVE_SHORE));
    }
}
