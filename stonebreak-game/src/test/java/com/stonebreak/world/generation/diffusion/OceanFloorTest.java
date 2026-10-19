package com.stonebreak.world.generation.diffusion;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.biomes.OceanFloor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OCEAN sea floor mixes 97% sand, 2% dirt and 1% clay, per column and deterministically; inland
 * water (lakes, rivers) lies on a sand-and-dirt lake bed instead of its biome's grass.
 */
class OceanFloorTest {

    @Test
    void seaFloorIsNinetySevenPercentSandTwoDirtOneClay() {
        int sand = 0, dirt = 0, clay = 0, n = 0;
        for (int x = -250; x < 250; x++) {
            for (int z = -200; z < 200; z++) {
                BlockType b = OceanFloor.block(x, z, 1234L);
                if (b == BlockType.SAND) sand++;
                else if (b == BlockType.DIRT) dirt++;
                else if (b == BlockType.CLAY) clay++;
                n++;
            }
        }
        assertEquals(n, sand + dirt + clay, "only sand, dirt and clay on the sea floor");
        assertEquals(0.97, sand / (double) n, 0.005);
        assertEquals(0.02, dirt / (double) n, 0.003);
        assertEquals(0.01, clay / (double) n, 0.003);
    }

    @Test
    void sameColumnAlwaysGetsTheSameBlockAndSeedsDiffer() {
        int differ = 0;
        for (int i = 0; i < 2000; i++) {
            int x = i * 37 - 5000, z = i * 91 + 17;
            assertEquals(OceanFloor.block(x, z, 7L), OceanFloor.block(x, z, 7L));
            if (OceanFloor.block(x, z, 7L) != OceanFloor.block(x, z, 8L)) differ++;
        }
        assertTrue(differ > 20, "different worlds should scatter dirt and clay differently");
    }

    @Test
    void inlandWaterLiesOnSandAndDirtNotGrass() {
        int shallowSand = 0, deepDirt = 0, n = 0;
        for (int x = 0; x < 100; x++) {
            for (int z = 0; z < 100; z++) {
                BlockType shallow = DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.PLAINS, x, z, 1L, 1);
                BlockType deep = DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.SWAMP, x, z, 1L, 4);
                assertTrue(shallow == BlockType.SAND || shallow == BlockType.DIRT, "shallow bed " + shallow);
                assertTrue(deep == BlockType.DIRT || deep == BlockType.SAND || deep == BlockType.CLAY, "deep bed " + deep);
                if (shallow == BlockType.SAND) shallowSand++;
                if (deep == BlockType.DIRT) deepDirt++;
                n++;
            }
        }
        assertTrue(shallowSand > n / 2, "shallow water lies mostly on sand");
        assertTrue(deepDirt > n / 2, "deeper water lies mostly on dirt");
        // Sandy and frozen biomes keep their own surface under water; dry ground keeps grass.
        assertEquals(BlockType.SAND, DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.DESERT, 3, 4, 1L, 3));
        assertEquals(BlockType.ICE, DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.ICE_FIELDS, 3, 4, 1L, 3));
        assertEquals(BlockType.GRASS, DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.PLAINS, 3, 4, 1L, 0));
        assertEquals(BlockType.GRASS, DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.PLAINS, 3, 4, 1L, -5));
        // The sea keeps its own floor at any depth.
        assertEquals(OceanFloor.block(3, 4, 1L), DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.OCEAN, 3, 4, 1L, 9));
    }

    @Test
    void onlyOceanColumnsUseTheMix() {
        assertEquals(BlockType.GRASS, DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.PLAINS, 3, 4, 1L));
        assertEquals(BlockType.SAND, DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.BEACH, 3, 4, 1L));
        BlockType ocean = DiffusionTerrainGenerator.surfaceBlockAt(BiomeType.OCEAN, 3, 4, 1L);
        assertEquals(OceanFloor.block(3, 4, 1L), ocean);
    }
}
