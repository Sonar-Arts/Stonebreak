package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standard Generation runs in its own 256-tall frame and is lifted by
 * {@link StandardTerrain#Y_OFFSET} into the taller world. Checks the lift lands where the rest
 * of the game expects: sea on the global sea level, solid rock under the old floor, and every
 * height the generator reports in the same Y the blocks were written at.
 */
class StandardTerrainLiftTest {

    private static final long SEED = 424242L;
    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    @Test
    void seaLevelLandsOnTheWorldSeaLevel() {
        assertEquals(WorldConfiguration.SEA_LEVEL, StandardTerrain.SEA_LEVEL + StandardTerrain.Y_OFFSET);
        assertEquals(0, StandardTerrain.Y_OFFSET % 16, "the lift must be whole storage sections");
    }

    @Test
    void chunkIsWrittenAboveAStoneBase() {
        TerrainGenerationSystem generator = new TerrainGenerationSystem(SEED);
        TerrainGenerator.TerrainResult result = generator.generateTerrainOnly(0, 0);
        Chunk chunk = result.chunk();

        for (int x = 0; x < CHUNK; x++) {
            for (int z = 0; z < CHUNK; z++) {
                assertEquals(BlockType.BEDROCK, chunk.getBlock(x, 0, z), "world floor @" + x + "," + z);
                for (int y = 1; y < StandardTerrain.Y_OFFSET; y++) {
                    BlockType block = chunk.getBlock(x, y, z);
                    // Stone, opened only by the deep cave layers.
                    assertTrue(block == BlockType.STONE || block == BlockType.AIR,
                            "base @" + x + "," + y + "," + z + " is " + block);
                }
                assertNotEquals(BlockType.BEDROCK, chunk.getBlock(x, StandardTerrain.Y_OFFSET, z),
                        "the frame's old bedrock floor is interior rock once lifted");
            }
        }
    }

    @Test
    void reportedHeightsAreWorldY() {
        TerrainGenerationSystem generator = new TerrainGenerationSystem(SEED);
        TerrainGenerator.TerrainResult result = generator.generateTerrainOnly(0, 0);
        ColumnProfile profile = result.profile();

        for (int x = 0; x < CHUNK; x++) {
            for (int z = 0; z < CHUNK; z++) {
                int idx = x * CHUNK + z;
                int height = profile.heights()[idx];
                assertEquals(generator.getFinalTerrainHeightAt(x, z), height, "profile vs API @" + x + "," + z);
                assertTrue(height > StandardTerrain.Y_OFFSET, "height " + height + " sits above the base");
                assertTrue(generator.carvedSurfaceHeight(x, z) <= height, "carving only lowers the surface");

                int water = profile.waterLevels()[idx];
                assertEquals(generator.getWaterLevelAt(x, z), water);
                assertTrue(water == WorldConfiguration.NO_WATER || water == WorldConfiguration.SEA_LEVEL,
                        "Standard's only water is the sea, got " + water);
                // Uncarved land keeps its surface block exactly at the reported height.
                if (generator.carvedSurfaceHeight(x, z) == height) {
                    assertNotEquals(BlockType.AIR, result.chunk().getBlock(x, height - 1, z),
                            "surface block @" + x + "," + z);
                    BlockType above = result.chunk().getBlock(x, height, z);
                    assertTrue(above == BlockType.AIR || above == BlockType.WATER,
                            "open above the surface @" + x + "," + z + ", got " + above);
                }
            }
        }
    }

    @Test
    void standardIsTheDefaultAndBuildsTheNoiseGenerator() {
        assertTrue(TerrainGeneratorType.STANDARD.create(SEED) instanceof TerrainGenerationSystem);
        assertEquals(TerrainGeneratorType.STANDARD,
                TerrainGeneratorType.parse("nonsense", TerrainGeneratorType.STANDARD));
        assertEquals(TerrainGeneratorType.DIFFUSION,
                TerrainGeneratorType.parse("diffusion", TerrainGeneratorType.STANDARD));
    }

    /**
     * The layers under the lifted frame keep main's caves going to the bottom of the world: every
     * 100-block band from bedrock up to the frame carries a real share of cave, not a solid slab.
     */
    @Test
    void cavesReachTheBottomOfTheWorld() {
        TerrainGenerationSystem generator = new TerrainGenerationSystem(SEED);
        int region = 8;
        int bandSize = DeepCaveLayers.LAYER_SPACING;
        int bands = StandardTerrain.Y_OFFSET / bandSize + 1;
        long[] air = new long[bands];
        long[] cells = new long[bands];
        int lowestAir = Integer.MAX_VALUE;
        long start = System.nanoTime();
        for (int cx = 0; cx < region; cx++) {
            for (int cz = 0; cz < region; cz++) {
                Chunk chunk = generator.generateTerrainOnly(cx, cz).chunk();
                for (int x = 0; x < CHUNK; x++) {
                    for (int z = 0; z < CHUNK; z++) {
                        for (int y = 1; y <= StandardTerrain.Y_OFFSET; y++) {
                            int band = y / bandSize;
                            cells[band]++;
                            if (chunk.getBlock(x, y, z) == BlockType.AIR) {
                                air[band]++;
                                lowestAir = Math.min(lowestAir, y);
                            }
                        }
                    }
                }
            }
        }
        long millisPerChunk = (System.nanoTime() - start) / 1_000_000L / (region * region);
        StringBuilder report = new StringBuilder("[deep caves] ms/chunk=" + millisPerChunk + " lowest air y=" + lowestAir);
        for (int b = 0; b < bands; b++) {
            report.append(String.format(" | y%d-%d %.2f%%", b * bandSize, b * bandSize + bandSize - 1,
                    100.0 * air[b] / Math.max(1, cells[b])));
        }
        System.out.println(report);
        assertTrue(lowestAir <= 16, "caves stop at y=" + lowestAir + ", well above the bottom of the world");
        for (int b = 0; b < bands - 1; b++) {
            assertTrue(air[b] * 100 >= cells[b], "band " + b + " is under 1% cave: " + report);
        }
    }
}
