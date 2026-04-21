package com.stonebreak.world.fastlod;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Discrete LOD detail level. Each level packs a whole chunk footprint
 * ({@value WorldConfiguration#CHUNK_SIZE} × {@value WorldConfiguration#CHUNK_SIZE}
 * blocks) into a square grid of cells whose side length doubles per level.
 *
 * <ul>
 *   <li>L0: 1-block cells → 16×16 grid (parity with classic LOD)</li>
 *   <li>L1: 2-block cells → 8×8 grid</li>
 *   <li>L2: 4-block cells → 4×4 grid</li>
 *   <li>L3: 8-block cells → 2×2 grid</li>
 *   <li>L4: 16-block cells → 1×1 grid (one quad per chunk)</li>
 * </ul>
 *
 * The sampler always writes a one-cell margin around the active grid so the
 * mesher can emit seam-safe skirts without reading neighbour nodes.
 */
public enum FastLodLevel {
    L0(0, 1),
    L1(1, 2),
    L2(2, 4),
    L3(3, 8),
    L4(4, 16);

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final FastLodLevel[] BY_INDEX = values();

    private final int index;
    private final int cellSize;
    private final int cellsPerAxis;
    private final int stride;

    FastLodLevel(int index, int cellSize) {
        this.index = index;
        this.cellSize = cellSize;
        this.cellsPerAxis = CHUNK_SIZE / cellSize;
        this.stride = cellsPerAxis + 2;
    }

    public int index()        { return index; }
    public int cellSize()     { return cellSize; }
    public int cellsPerAxis() { return cellsPerAxis; }
    public int stride()       { return stride; }
    public int cellCount()    { return cellsPerAxis * cellsPerAxis; }
    public int heightCount()  { return stride * stride; }

    /** Trees are only worth silhouetting at the finest level; coarser levels skip them entirely. */
    public boolean emitsTrees() { return this == L0; }

    public static FastLodLevel finest() { return L0; }
    public static FastLodLevel coarsest() { return L4; }
    public static int count() { return BY_INDEX.length; }

    public static FastLodLevel byIndex(int i) {
        if (i < 0 || i >= BY_INDEX.length) {
            throw new IllegalArgumentException("LOD level index out of range: " + i);
        }
        return BY_INDEX[i];
    }
}
