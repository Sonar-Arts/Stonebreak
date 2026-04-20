package com.stonebreak.world.lod;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;

/**
 * Builds a {@link LodChunk} by sampling the deterministic terrain heightmap at
 * each column plus a one-column margin, then probing tree placement on interior
 * columns. Avoids all cave / feature / entity work beyond the shared tree probe.
 */
public final class LodChunkGenerator {
    private final TerrainGenerationSystem terrain;

    public LodChunkGenerator(TerrainGenerationSystem terrain) {
        this.terrain = terrain;
    }

    public LodChunk generate(int chunkX, int chunkZ) {
        int[] heights18 = new int[LodChunk.STRIDE * LodChunk.STRIDE];
        BlockType[] surface = new BlockType[LodChunk.CHUNK_SIZE * LodChunk.CHUNK_SIZE];
        TreeSample[] trees = new TreeSample[LodChunk.CHUNK_SIZE * LodChunk.CHUNK_SIZE];

        int baseX = chunkX * LodChunk.CHUNK_SIZE;
        int baseZ = chunkZ * LodChunk.CHUNK_SIZE;

        for (int hx = 0; hx < LodChunk.STRIDE; hx++) {
            int worldX = baseX + hx - LodChunk.MARGIN;
            for (int hz = 0; hz < LodChunk.STRIDE; hz++) {
                int worldZ = baseZ + hz - LodChunk.MARGIN;
                heights18[hx * LodChunk.STRIDE + hz] = terrain.getFinalTerrainHeightAt(worldX, worldZ);
            }
        }

        for (int ix = 0; ix < LodChunk.CHUNK_SIZE; ix++) {
            int worldX = baseX + ix;
            for (int iz = 0; iz < LodChunk.CHUNK_SIZE; iz++) {
                int worldZ = baseZ + iz;
                int idx = ix * LodChunk.CHUNK_SIZE + iz;
                surface[idx] = terrain.getSurfaceBlockAt(worldX, worldZ);
                trees[idx] = terrain.getTreeAt(worldX, worldZ);
            }
        }

        return new LodChunk(chunkX, chunkZ, heights18, surface, trees);
    }
}
