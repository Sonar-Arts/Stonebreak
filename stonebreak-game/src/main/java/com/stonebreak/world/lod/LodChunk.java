package com.stonebreak.world.lod;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;

/**
 * Immutable coarse terrain sample for a single chunk footprint. Stores a surface
 * height grid with a one-column margin (18x18) so the mesher can emit seam-free
 * skirts against neighbours without needing neighbour chunk data, plus a parallel
 * 16x16 surface block and tree-sample grid for the interior columns.
 */
public final class LodChunk {
    public static final int CHUNK_SIZE = 16;
    public static final int MARGIN = 1;
    public static final int STRIDE = CHUNK_SIZE + 2 * MARGIN;

    private final int chunkX;
    private final int chunkZ;
    private final int[] heights18;
    private final BlockType[] surface;
    private final TreeSample[] trees;

    public LodChunk(int chunkX, int chunkZ, int[] heights18, BlockType[] surface, TreeSample[] trees) {
        if (heights18.length != STRIDE * STRIDE) {
            throw new IllegalArgumentException("heights18 must be " + (STRIDE * STRIDE) + " long");
        }
        if (surface.length != CHUNK_SIZE * CHUNK_SIZE) {
            throw new IllegalArgumentException("surface must be " + (CHUNK_SIZE * CHUNK_SIZE) + " long");
        }
        if (trees.length != CHUNK_SIZE * CHUNK_SIZE) {
            throw new IllegalArgumentException("trees must be " + (CHUNK_SIZE * CHUNK_SIZE) + " long");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.heights18 = heights18;
        this.surface = surface;
        this.trees = trees;
    }

    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }

    /** Height at margin-extended coords (-1..16 in both axes). */
    public int heightAt(int ix, int iz) {
        return heights18[(ix + MARGIN) * STRIDE + (iz + MARGIN)];
    }

    /** Surface block for interior column (0..15). */
    public BlockType surfaceAt(int ix, int iz) {
        return surface[ix * CHUNK_SIZE + iz];
    }

    /** Tree sample for interior column, or null if no tree at this column. */
    public TreeSample treeAt(int ix, int iz) {
        return trees[ix * CHUNK_SIZE + iz];
    }
}
