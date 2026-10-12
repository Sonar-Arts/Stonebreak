package com.stonebreak.world.generation;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.biomes.OceanFloor;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Standard Generation's sea is OCEAN, as DaedalusTGM-Exp's is: every submerged column is an
 * OCEAN (or, frozen, ICE_FIELDS) column, its floor carries {@link OceanFloor}'s mix, and the
 * per-point and FastLOD paths report the floor the chunk actually holds.
 */
class StandardOceanTest {

    private static final long SEED = 424242L;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    @Test
    void submergedColumnsAreOceanWithTheMixedFloor() {
        TerrainGenerationSystem terrain = new TerrainGenerationSystem(SEED);
        int[] chunk = findSeaChunk(terrain);
        assertNotNull(chunk, "no sea chunk in range — the seed's terrain moved");
        int baseX = chunk[0] * CHUNK_SIZE;
        int baseZ = chunk[1] * CHUNK_SIZE;

        TerrainGenerator.TerrainResult result = terrain.generateTerrainOnly(chunk[0], chunk[1]);
        Chunk blocks = result.chunk();
        ColumnProfile profile = result.profile();

        int[] lodHeights = new int[CHUNK_SIZE * CHUNK_SIZE];
        BlockType[] lodSurface = new BlockType[CHUNK_SIZE * CHUNK_SIZE];
        terrain.sampleRawColumns(baseX, baseZ, CHUNK_SIZE, 1, lodHeights, null, lodSurface);

        int ocean = 0;
        int mixed = 0;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                BiomeType biome = profile.biomes()[idx];
                boolean wet = profile.waterLevels()[idx] != WorldConfiguration.NO_WATER;
                String where = "column " + (baseX + x) + "," + (baseZ + z) + " is " + biome;
                if (wet) {
                    assertTrue(biome == BiomeType.OCEAN || biome == BiomeType.ICE_FIELDS, where + " under water");
                } else {
                    assertTrue(biome != BiomeType.OCEAN, where + " on dry land");
                }
                if (biome != BiomeType.OCEAN) {
                    continue;
                }
                ocean++;
                int wx = baseX + x;
                int wz = baseZ + z;
                BlockType expected = OceanFloor.block(wx, wz, SEED);
                assertEquals(expected, blocks.getBlock(x, profile.heights()[idx] - 1, z),
                    "sea floor at " + wx + "," + wz);
                assertEquals(expected, lodSurface[idx], "FastLOD surface at " + wx + "," + wz);
                assertEquals(expected, terrain.getSurfaceBlockAt(wx, wz), "per-point surface at " + wx + "," + wz);
                BiomeType perPoint = terrain.getBiomeAt(wx, wz);
                assertTrue(perPoint == BiomeType.OCEAN || perPoint == BiomeType.ICE_FIELDS,
                    "per-point biome at " + wx + "," + wz + " is " + perPoint);
                if (expected != BlockType.SAND) {
                    mixed++;
                }
            }
        }
        assertTrue(ocean > CHUNK_SIZE * CHUNK_SIZE / 2, "chunk should be mostly sea, got " + ocean);
        assertTrue(mixed > 0, "dirt or clay expected somewhere on " + ocean + " floor columns");
    }

    /** The nearest chunk whose centre and corners all sit under the sea. */
    private static int[] findSeaChunk(TerrainGenerationSystem terrain) {
        int sea = WorldConfiguration.SEA_LEVEL;
        for (int r = 0; r < 64; r++) {
            for (int cx = -r; cx <= r; cx++) {
                for (int cz = -r; cz <= r; cz++) {
                    if (Math.max(Math.abs(cx), Math.abs(cz)) != r) {
                        continue;
                    }
                    int bx = cx * CHUNK_SIZE;
                    int bz = cz * CHUNK_SIZE;
                    if (terrain.getFinalTerrainHeightAt(bx + 8, bz + 8) < sea - 4
                        && terrain.getFinalTerrainHeightAt(bx, bz) < sea - 4
                        && terrain.getFinalTerrainHeightAt(bx + 15, bz + 15) < sea - 4
                        && terrain.getFinalTerrainHeightAt(bx, bz + 15) < sea - 4
                        && terrain.getFinalTerrainHeightAt(bx + 15, bz) < sea - 4) {
                        return new int[]{cx, cz};
                    }
                }
            }
        }
        return null;
    }
}
