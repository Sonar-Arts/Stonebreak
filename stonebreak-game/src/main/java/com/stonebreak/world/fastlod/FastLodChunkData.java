package com.stonebreak.world.fastlod;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.generation.features.VegetationGenerator.TreeSample;

/**
 * Immutable, level-aware coarse sample for one chunk footprint.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code heights[stride²]} — terrain height per cell, one-cell margin on
 *   each side so the mesher can emit skirts without reading neighbours.</li>
 *   <li>{@code waterLevels[cellsPerAxis²]} — per-cell water level, or {@link
 *   com.stonebreak.world.generation.diffusion.TerrainTile#NO_WATER}; a cell is
 *   submerged exactly when this exceeds its height, the same test native
 *   generation places water by. No margin: skirts only compare terrain
 *   heights, never water.</li>
 *   <li>{@code surface[cellsPerAxis²]} — representative surface block for each
 *   interior cell; for submerged cells this is the real seabed block.</li>
 *   <li>{@code trees[cellsPerAxis²]} — tree silhouettes, populated only at the
 *   finest level ({@link FastLodLevel#L0}); other levels leave this null.</li>
 * </ul>
 */
public final class FastLodChunkData {

    private final FastLodKey key;
    private final int[] heights;
    private final int[] waterLevels;
    private final BlockType[] surface;
    private final TreeSample[] trees;

    public FastLodChunkData(FastLodKey key, int[] heights, int[] waterLevels,
                            BlockType[] surface, TreeSample[] trees) {
        if (key == null) throw new IllegalArgumentException("key");
        FastLodLevel level = key.level();
        if (heights.length != level.heightCount()) {
            throw new IllegalArgumentException("heights length " + heights.length
                    + " != expected " + level.heightCount() + " for " + level);
        }
        if (waterLevels.length != level.cellCount()) {
            throw new IllegalArgumentException("waterLevels length " + waterLevels.length
                    + " != expected " + level.cellCount() + " for " + level);
        }
        if (surface.length != level.cellCount()) {
            throw new IllegalArgumentException("surface length " + surface.length
                    + " != expected " + level.cellCount() + " for " + level);
        }
        if (trees != null && trees.length != level.cellCount()) {
            throw new IllegalArgumentException("trees length " + trees.length
                    + " != expected " + level.cellCount() + " for " + level);
        }
        this.key = key;
        this.heights = heights;
        this.waterLevels = waterLevels;
        this.surface = surface;
        this.trees = trees;
    }

    public FastLodKey key()         { return key; }
    public FastLodLevel level()     { return key.level(); }
    public int chunkX()             { return key.chunkX(); }
    public int chunkZ()             { return key.chunkZ(); }

    /** Height at margin-extended cell coords (-1..cellsPerAxis, inclusive). */
    public int heightAt(int ix, int iz) {
        int stride = key.level().stride();
        return heights[(ix + 1) * stride + (iz + 1)];
    }

    /**
     * Water level at an interior cell, or {@link
     * com.stonebreak.world.generation.diffusion.TerrainTile#NO_WATER}. No
     * margin — unlike {@link #heightAt}, only {@code (ix, iz)} within
     * {@code [0, cellsPerAxis)} are valid.
     */
    public int waterLevelAt(int ix, int iz) {
        return waterLevels[ix * key.level().cellsPerAxis() + iz];
    }

    public BlockType surfaceAt(int ix, int iz) {
        return surface[ix * key.level().cellsPerAxis() + iz];
    }

    public TreeSample treeAt(int ix, int iz) {
        if (trees == null) return null;
        return trees[ix * key.level().cellsPerAxis() + iz];
    }

    /** Direct access for the serializer; do not mutate. */
    public int[] rawHeights()          { return heights; }
    public int[] rawWaterLevels()      { return waterLevels; }
    public BlockType[] rawSurface()    { return surface; }
    public TreeSample[] rawTrees()     { return trees; }
}
