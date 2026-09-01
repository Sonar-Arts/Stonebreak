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
 *   <li>{@code surface[cellsPerAxis²]} — representative surface block for each
 *   interior cell; for submerged cells (height below sea level) this is the
 *   real seabed block, submergence itself is height-derived.</li>
 *   <li>{@code trees[cellsPerAxis²]} — tree silhouettes, populated only at the
 *   finest level ({@link FastLodLevel#L0}); other levels leave this null.</li>
 *   <li>{@code openingFloor[cellsPerAxis²]} / {@code openingCoverage[cellsPerAxis²]} —
 *   cave mouths. Unlike every channel above, these are aggregated over the cell's whole
 *   footprint rather than probed at one representative point, because that is the only way
 *   an opening a few blocks across survives a 16-block cell. {@code openingFloor} is the
 *   lowest carved floor inside the cell (world Y) or {@link
 *   com.stonebreak.world.generation.TerrainGenerationSystem#NO_OPENING}; {@code
 *   openingCoverage} is the carved share of the footprint, 0..255, which sizes the notch.
 *   Both are null at {@link FastLodLevel#L0}, where a cell is one column and its carve is
 *   already in {@code heights}.</li>
 * </ul>
 */
public final class FastLodChunkData {

    private final FastLodKey key;
    private final int[] heights;
    private final BlockType[] surface;
    private final TreeSample[] trees;
    private final int[] openingFloor;
    private final byte[] openingCoverage;

    public FastLodChunkData(FastLodKey key, int[] heights, BlockType[] surface, TreeSample[] trees) {
        this(key, heights, surface, trees, null, null);
    }

    public FastLodChunkData(FastLodKey key, int[] heights, BlockType[] surface, TreeSample[] trees,
                            int[] openingFloor, byte[] openingCoverage) {
        if (key == null) throw new IllegalArgumentException("key");
        FastLodLevel level = key.level();
        if (heights.length != level.heightCount()) {
            throw new IllegalArgumentException("heights length " + heights.length
                    + " != expected " + level.heightCount() + " for " + level);
        }
        if (surface.length != level.cellCount()) {
            throw new IllegalArgumentException("surface length " + surface.length
                    + " != expected " + level.cellCount() + " for " + level);
        }
        if (trees != null && trees.length != level.cellCount()) {
            throw new IllegalArgumentException("trees length " + trees.length
                    + " != expected " + level.cellCount() + " for " + level);
        }
        if (openingFloor != null && openingFloor.length != level.cellCount()) {
            throw new IllegalArgumentException("openingFloor length " + openingFloor.length
                    + " != expected " + level.cellCount() + " for " + level);
        }
        if ((openingFloor == null) != (openingCoverage == null)) {
            throw new IllegalArgumentException("opening floor and coverage must be set together");
        }
        if (openingCoverage != null && openingCoverage.length != level.cellCount()) {
            throw new IllegalArgumentException("openingCoverage length " + openingCoverage.length
                    + " != expected " + level.cellCount() + " for " + level);
        }
        this.key = key;
        this.heights = heights;
        this.surface = surface;
        this.trees = trees;
        this.openingFloor = openingFloor;
        this.openingCoverage = openingCoverage;
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

    public BlockType surfaceAt(int ix, int iz) {
        return surface[ix * key.level().cellsPerAxis() + iz];
    }

    public TreeSample treeAt(int ix, int iz) {
        if (trees == null) return null;
        return trees[ix * key.level().cellsPerAxis() + iz];
    }

    /**
     * Lowest carved floor inside this cell (world Y), or {@link
     * com.stonebreak.world.generation.TerrainGenerationSystem#NO_OPENING} when the cell has
     * no cave mouth worth drawing — including at every level that carries no opening channel.
     */
    public int openingFloorAt(int ix, int iz) {
        if (openingFloor == null) {
            return com.stonebreak.world.generation.TerrainGenerationSystem.NO_OPENING;
        }
        return openingFloor[ix * key.level().cellsPerAxis() + iz];
    }

    /** Carved share of this cell's footprint, 0..255. Zero when there is no opening. */
    public int openingCoverageAt(int ix, int iz) {
        if (openingCoverage == null) return 0;
        return openingCoverage[ix * key.level().cellsPerAxis() + iz] & 0xFF;
    }

    /** True when any cell carries a cave mouth — lets the mesher skip the notch budget. */
    public boolean hasOpenings() {
        if (openingFloor == null) return false;
        for (int v : openingFloor) {
            if (v != com.stonebreak.world.generation.TerrainGenerationSystem.NO_OPENING) return true;
        }
        return false;
    }

    /** Direct access for the serializer; do not mutate. */
    public int[] rawHeights()          { return heights; }
    public BlockType[] rawSurface()    { return surface; }
    public TreeSample[] rawTrees()     { return trees; }
    public int[] rawOpeningFloor()     { return openingFloor; }
    public byte[] rawOpeningCoverage() { return openingCoverage; }
}
