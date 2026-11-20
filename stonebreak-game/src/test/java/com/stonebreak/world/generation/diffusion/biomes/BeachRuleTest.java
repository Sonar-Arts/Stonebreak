package com.stonebreak.world.generation.diffusion.biomes;

import com.stonebreak.world.generation.HeightCurveVersion;
import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Height-curve v2 worlds size beaches physically (agent BV, 2026-11-20): ~60 m wide, no higher than ~30 m above the
 * water, across tile edges. v1 worlds keep the legacy rule (one column, 8 blocks, no cross-tile reads).
 */
class BeachRuleTest {

    private static final int SEA = WorldConfiguration.SEA_LEVEL;
    private static final int N = 16;
    private static final short PLAINS = 1;
    private static final short TAIGA = 15;
    private static final short OCEAN = 44;

    @Test
    void v2RulesArePhysicalV1KeepsTheLegacyRule() {
        assertEquals(new BeachRule(1, 2, true), BeachRule.of(TerrainScalePreset.ONE_TO_FOUR, HeightCurveVersion.V2));
        assertEquals(new BeachRule(2, 4, true), BeachRule.of(TerrainScalePreset.ONE_TO_TWO, HeightCurveVersion.V2));
        assertEquals(new BeachRule(4, 8, true), BeachRule.of(TerrainScalePreset.ONE_TO_ONE, HeightCurveVersion.V2));
        assertEquals(BeachRule.of(TerrainScalePreset.ONE_TO_FOUR, HeightCurveVersion.CURRENT),
                BeachRule.of(TerrainScalePreset.ONE_TO_FOUR, null));
        for (TerrainScalePreset p : TerrainScalePreset.values()) {
            assertEquals(BeachRule.LEGACY, BeachRule.of(p, HeightCurveVersion.V1));
        }
    }

    /** A tile at (tileX, 0): rows (x) {@code [x0, x0 + seaRows)} sea at sea level, the rest dry land at {@code land}. */
    private static TerrainTile tile(int tileX, int seaRows, int land, short landId) {
        short[] heights = new short[N * N];
        short[] biomes = new short[N * N];
        short[] water = new short[N * N];
        Arrays.fill(heights, (short) land);
        Arrays.fill(biomes, landId);
        Arrays.fill(water, TerrainTile.NO_WATER);
        for (int row = 0; row < seaRows; row++) {
            for (int col = 0; col < N; col++) {
                heights[row * N + col] = (short) (SEA - 3);
                biomes[row * N + col] = OCEAN;
                water[row * N + col] = (short) SEA;
            }
        }
        int x0 = tileX * N;
        return new TerrainTile(tileX, 0, x0, 0, x0 + N, N, N, N, heights, biomes, water);
    }

    private static BiomeManager manager(TerrainTile t, BeachRule rule) {
        return new BiomeManager((x, z) -> t, rule);
    }

    @Test
    void v2BeachesStayLowLegacyClimbsEightBlocks() {
        BeachRule v2 = BeachRule.of(TerrainScalePreset.ONE_TO_FOUR, HeightCurveVersion.V2);
        assertEquals(BiomeType.BEACH, manager(tile(0, 4, SEA + 1, PLAINS), v2).getBiome(4, 8));
        assertEquals(BiomeType.PLAINS, manager(tile(0, 4, SEA + 2, PLAINS), v2).getBiome(4, 8), "a 46 m bluff");
        assertEquals(BiomeType.BEACH, manager(tile(0, 4, SEA + 7, PLAINS), BeachRule.LEGACY).getBiome(4, 8));
    }

    @Test
    void v2BeachesAreSixtyMetresWide() {
        BeachRule oneToOne = BeachRule.of(TerrainScalePreset.ONE_TO_ONE, HeightCurveVersion.V2);
        BiomeManager m = manager(tile(0, 4, SEA, PLAINS), oneToOne);
        for (int x = 4; x <= 7; x++) {
            assertEquals(BiomeType.BEACH, m.getBiome(x, 8), "row " + x);
        }
        assertEquals(BiomeType.PLAINS, m.getBiome(8, 8), "5 blocks (75 m) inland");
        assertEquals(BiomeType.PLAINS, manager(tile(0, 4, SEA, PLAINS), BeachRule.LEGACY).getBiome(5, 8));
        // cold coasts never get sand, whatever the rule
        assertEquals(BiomeType.TAIGA, manager(tile(0, 4, SEA, TAIGA), oneToOne).getBiome(4, 8));
    }

    @Test
    void v2ReadsTheOceanAcrossTileEdgesLegacyDoesNot() {
        TerrainTile sea = tile(0, N, SEA, PLAINS);           // all ocean, x 0..15
        TerrainTile land = tile(1, 0, SEA, PLAINS);          // all land at sea level, x 16..31
        com.stonebreak.world.generation.diffusion.TerrainTileSource src = (x, z) -> x < N ? sea : land;
        BeachRule v2 = BeachRule.of(TerrainScalePreset.ONE_TO_TWO, HeightCurveVersion.V2);   // reach 2
        BiomeManager m = new BiomeManager(src, v2);
        assertEquals(BiomeType.BEACH, m.getBiome(N, 8));
        assertEquals(BiomeType.BEACH, m.getBiome(N + 1, 8));
        assertEquals(BiomeType.PLAINS, m.getBiome(N + 2, 8));
        assertEquals(BiomeType.PLAINS, new BiomeManager(src, BeachRule.LEGACY).getBiome(N, 8), "the legacy notch");
    }
}
