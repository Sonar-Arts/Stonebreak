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

        // One batched probe over the padded grid (margin ring included). The
        // interior of the same grid supplies the per-cell surface/tree data,
        // so the whole key costs six channel fills instead of thousands of
        // per-point samples. Values are bit-identical to the per-point API.
        int origin = -cellSize + representativeOffset(cellSize);
        int[] heights = new int[level.heightCount()];
        BlockType[] gridSurface = new BlockType[stride * stride];
        TreeSample[] gridTrees  = level.emitsTrees() ? new TreeSample[stride * stride] : null;
        if (key.carved()) {
            terrain.sampleColumns(baseX + origin, baseZ + origin, stride, cellSize,
                heights, gridSurface, gridTrees);
        } else {
            terrain.sampleRawColumns(baseX + origin, baseZ + origin, stride, cellSize,
                heights, gridSurface);
        }

        BlockType[] surface = new BlockType[level.cellCount()];
        TreeSample[] trees  = level.emitsTrees() ? new TreeSample[level.cellCount()] : null;
        for (int ix = 0; ix < cellsPerAxis; ix++) {
            for (int iz = 0; iz < cellsPerAxis; iz++) {
                int idx = ix * cellsPerAxis + iz;
                int gridIdx = (ix + 1) * stride + (iz + 1);
                surface[idx] = gridSurface[gridIdx];
                if (trees != null) {
                    trees[idx] = gridTrees[gridIdx];
                }
            }
        }

        // Cave mouths, aggregated over each cell's footprint rather than point-probed —
        // see TerrainGenerationSystem.sampleCellOpenings for why the heights cannot carry
        // this. Skipped at L0, where a cell is one column and its carve is already the
        // height, so the finest band pays nothing and draws exactly what it drew before.
        // Also skipped at the uncarved coarse levels: aggregating openings reads
        // every column's carve profile, the very cost those levels avoid.
        int[] openingFloor = null;
        byte[] openingCoverage = null;
        if (cellSize > 1 && key.carved()) {
            openingFloor = new int[level.cellCount()];
            openingCoverage = new byte[level.cellCount()];
            int[] cellHeights = new int[level.cellCount()];
            for (int ix = 0; ix < cellsPerAxis; ix++) {
                for (int iz = 0; iz < cellsPerAxis; iz++) {
                    cellHeights[ix * cellsPerAxis + iz] = heights[(ix + 1) * stride + (iz + 1)];
                }
            }
            terrain.sampleCellOpenings(baseX, baseZ, cellsPerAxis, cellSize,
                    cellHeights, openingFloor, openingCoverage);
        }

        // Trees past the finest band: every tree in the footprint, standing on its cell.
        int[] treeSpots = null;
        if (key.coarseTrees()) {
            int[] cellHeights = new int[level.cellCount()];
            for (int ix = 0; ix < cellsPerAxis; ix++) {
                for (int iz = 0; iz < cellsPerAxis; iz++) {
                    cellHeights[ix * cellsPerAxis + iz] = heights[(ix + 1) * stride + (iz + 1)];
                }
            }
            treeSpots = terrain.probeCellTrees(key.chunkX(), key.chunkZ(), cellsPerAxis, cellSize,
                    cellHeights, surface);
        }

        return new FastLodChunkData(key, heights, surface, trees,
                openingFloor, openingCoverage, treeSpots);
    }

    /**
     * Centre offset of a cell. For cellSize==1 this collapses to 0 so L0 matches
     * the classic per-column sampler bit-for-bit.
     */
    private static int representativeOffset(int cellSize) {
        return (cellSize == 1) ? 0 : cellSize / 2;
    }
}
