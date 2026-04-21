package com.stonebreak.world.fastlod;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.TerrainGenerationSystem;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Builds a {@link FastLodChunkData} by sampling the deterministic terrain
 * system on a grid whose spacing matches the node's cell size. One sample
 * represents an entire cell ({@code cellSize × cellSize} blocks); the
 * representative point is the cell's geometric centre, rounded toward the
 * chunk origin so samples at {@link FastLodLevel#L0} match the classic LOD
 * behaviour exactly.
 */
public final class FastLodSampler {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final TerrainGenerationSystem terrain;

    public FastLodSampler(TerrainGenerationSystem terrain) {
        this.terrain = terrain;
    }

    public FastLodChunkData sample(FastLodKey key) {
        FastLodLevel level = key.level();
        int cellSize     = level.cellSize();
        int cellsPerAxis = level.cellsPerAxis();
        int stride       = level.stride();

        int baseX = key.chunkX() * CHUNK_SIZE;
        int baseZ = key.chunkZ() * CHUNK_SIZE;

        int[] heights = new int[level.heightCount()];
        for (int hx = 0; hx < stride; hx++) {
            int cellIx = hx - 1;                       // -1..cellsPerAxis
            int worldX = baseX + cellIx * cellSize + representativeOffset(cellSize);
            for (int hz = 0; hz < stride; hz++) {
                int cellIz = hz - 1;
                int worldZ = baseZ + cellIz * cellSize + representativeOffset(cellSize);
                heights[hx * stride + hz] = terrain.getFinalTerrainHeightAt(worldX, worldZ);
            }
        }

        BlockType[] surface = new BlockType[level.cellCount()];
        TreeSample[] trees  = level.emitsTrees() ? new TreeSample[level.cellCount()] : null;

        for (int ix = 0; ix < cellsPerAxis; ix++) {
            int worldX = baseX + ix * cellSize + representativeOffset(cellSize);
            for (int iz = 0; iz < cellsPerAxis; iz++) {
                int worldZ = baseZ + iz * cellSize + representativeOffset(cellSize);
                int idx = ix * cellsPerAxis + iz;
                surface[idx] = terrain.getSurfaceBlockAt(worldX, worldZ);
                if (trees != null) {
                    trees[idx] = terrain.getTreeAt(worldX, worldZ);
                }
            }
        }

        return new FastLodChunkData(key, heights, surface, trees);
    }

    /**
     * Centre offset of a cell. For cellSize==1 this collapses to 0 so L0 matches
     * the classic per-column sampler bit-for-bit.
     */
    private static int representativeOffset(int cellSize) {
        return (cellSize == 1) ? 0 : cellSize / 2;
    }
}
