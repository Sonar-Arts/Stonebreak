package com.stonebreak.world.generation.diffusion.biomes;

import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.diffusion.TerrainTile;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Beaches come from the TRUE ocean only. A lake is still water at sea level too, but the model
 * gives its columns the land biome around them (terrain_slm/world/ocean.py), and its shore — and
 * the river running into it — keeps that biome instead of a ring of sand.
 */
class BiomeManagerShoreTest {

    private static final int SEA = WorldConfiguration.SEA_LEVEL;
    private static final int N = 16;
    private static final short PLAINS = 1;
    private static final short OCEAN = 44;

    /** Rows 0-3 under water at sea level (ids {@code waterId}), dry plains at sea level beyond. */
    private static BiomeManager shore(short waterId) {
        short[] heights = new short[N * N];
        short[] biomes = new short[N * N];
        short[] water = new short[N * N];
        Arrays.fill(heights, (short) SEA);
        Arrays.fill(biomes, PLAINS);
        Arrays.fill(water, TerrainTile.NO_WATER);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < N; col++) {
                heights[row * N + col] = (short) (SEA - 3);
                biomes[row * N + col] = waterId;
                water[row * N + col] = (short) SEA;
            }
        }
        TerrainTile tile = new TerrainTile(0, 0, 0, 0, N, N, N, N, heights, biomes, water);
        return new BiomeManager((x, z) -> tile);
    }

    @Test
    void landBesideTheOceanIsBeach() {
        assertEquals(BiomeType.BEACH, shore(OCEAN).getBiome(4, 8));
        assertEquals(BiomeType.PLAINS, shore(OCEAN).getBiome(6, 8), "only the row touching the sea");
    }

    @Test
    void landBesideALakeKeepsItsBiome() {
        assertEquals(BiomeType.PLAINS, shore(PLAINS).getBiome(4, 8));
        assertEquals(BiomeType.PLAINS, shore(PLAINS).getBiome(2, 8), "the lake column itself keeps the land biome");
    }
}
