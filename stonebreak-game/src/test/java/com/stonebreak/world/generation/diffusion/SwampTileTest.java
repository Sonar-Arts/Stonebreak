package com.stonebreak.world.generation.diffusion;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TerrainGenerator.TerrainResult;
import com.stonebreak.world.generation.biomes.BiomeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The model's swamp id (6, terrain_slm/biomes.py) reaches the world as SWAMP on swampy grass. */
class SwampTileTest {

    private static final long SEED = 42L;
    private static final int CHUNK = 16;
    private static final short SWAMP_ID = 6;

    /** {@link FakeTerrainTileSource}'s terrain, every column classified swamp. */
    private static final class SwampTileSource implements TerrainTileSource {
        private final FakeTerrainTileSource base = new FakeTerrainTileSource();

        @Override
        public TerrainTile getTile(int worldX, int worldZ) {
            TerrainTile t = base.getTile(worldX, worldZ);
            short[] biomes = new short[t.biomeIds().length];
            java.util.Arrays.fill(biomes, SWAMP_ID);
            return new TerrainTile(t.tileX(), t.tileZ(), t.worldI1(), t.worldJ1(), t.worldI2(), t.worldJ2(),
                    t.width(), t.height(), t.blockHeights(), biomes, t.waterLevels());
        }
    }

    @Test
    void swampTilesGenerateSwampyGrass() {
        DiffusionTerrainGenerator terrain = new DiffusionTerrainGenerator(SEED, new SwampTileSource());
        int swampColumns = 0;
        for (int[] c : new int[][] {{0, 0}, {1, 0}, {100, 100}, {-3, -5}}) {
            TerrainResult result = terrain.generateTerrainOnly(c[0], c[1]);
            Chunk chunk = result.chunk();
            for (int lx = 0; lx < CHUNK; lx++) {
                for (int lz = 0; lz < CHUNK; lz++) {
                    int i = lx * CHUNK + lz;
                    if (result.profile().biomes()[i] != BiomeType.SWAMP) {
                        continue;   // shoreline band: BEACH
                    }
                    swampColumns++;
                    int height = result.profile().heights()[i];
                    BlockType top = chunk.getBlock(lx, height - 1, lz);
                    if (top != BlockType.AIR && top != BlockType.WATER) {
                        assertEquals(BlockType.SWAMPY_GRASS, top, "surface at " + c[0] + "," + c[1] + " " + lx + "," + lz);
                    }
                }
            }
        }
        assertTrue(swampColumns > 0, "no column resolved to SWAMP");
    }
}
