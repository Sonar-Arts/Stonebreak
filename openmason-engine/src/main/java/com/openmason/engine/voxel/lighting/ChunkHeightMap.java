package com.openmason.engine.voxel.lighting;

/**
 * Per-chunk sky-shadow heightmap. Stores the Y at which sky light begins for
 * every (lx, lz) column — one greater than the topmost opaque block in that
 * column. A cell at world Y &ge; {@code heights[lz*width+lx]} is "under sky";
 * anything below is shaded by whatever's above it.
 *
 * <p><b>Maintenance:</b> callers hook this into their block-write pipeline and
 * invoke {@link #onBlockChanged} after every mutation. Worst case is one column
 * rescan on removal at the current height; all other cases are O(1).
 *
 * <p><b>Not synchronized.</b> Heightmaps are owned by their chunk and must be
 * mutated from the same thread as the chunk's block array (MMS meshes read
 * concurrently, but int reads are atomic).
 *
 * @since 1.0
 */
public final class ChunkHeightMap {

    /** Sentinel meaning "no opaque block anywhere in this column". */
    public static final int SKY_ALL_THE_WAY_DOWN = 0;

    private final int width;
    private final int height;
    private final int depth;
    /** 16-bit column heights — covers any Y up to 32767 in half the memory of int[]. */
    private final short[] heights;
    private volatile boolean populated = false;

    public ChunkHeightMap(int width, int height, int depth) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("ChunkHeightMap dimensions must be positive");
        }
        if (height > Short.MAX_VALUE) {
            throw new IllegalArgumentException("ChunkHeightMap height exceeds short range: " + height);
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.heights = new short[width * depth];
    }

    public int getWidth()  { return width;  }
    public int getHeight() { return height; }
    public int getDepth()  { return depth;  }

    public int getHeight(int lx, int lz) {
        return heights[lz * width + lx] & 0xFFFF;
    }

    /** True once {@link #recomputeAll} has run at least once. */
    public boolean isPopulated() {
        return populated;
    }

    /**
     * Rebuilds every column from scratch. Call after terrain generation or
     * deserialization so the first mesh build samples correct shadow data.
     */
    public void recomputeAll(ColumnOpacityProbe probe) {
        for (int lx = 0; lx < width; lx++) {
            for (int lz = 0; lz < depth; lz++) {
                heights[lz * width + lx] = (short) scanColumn(lx, lz, height - 1, probe);
            }
        }
        populated = true;
    }

    /**
     * Incremental update after a block at (lx, ly, lz) changed.
     *
     * <p>Four cases:
     * <ol>
     *   <li>Heightmap not yet populated → lazy full recompute.</li>
     *   <li>New block is opaque and sits at or above the current top → raise to {@code ly+1}.</li>
     *   <li>Old block was the current top and is now transparent → rescan column from {@code ly-1}.</li>
     *   <li>Otherwise → no heightmap effect (change is below the current top).</li>
     * </ol>
     */
    public void onBlockChanged(int lx, int ly, int lz,
                                boolean newOpaque, boolean oldOpaque,
                                ColumnOpacityProbe probe) {
        if (!populated) {
            recomputeAll(probe);
            return;
        }
        int idx = lz * width + lx;
        int current = heights[idx] & 0xFFFF;

        if (newOpaque && !oldOpaque) {
            if (ly + 1 > current) {
                heights[idx] = (short) (ly + 1);
            }
            return;
        }
        if (!newOpaque && oldOpaque) {
            if (ly + 1 == current) {
                heights[idx] = (short) scanColumn(lx, lz, ly - 1, probe);
            }
        }
        // Opacity unchanged, or change below current top — no-op.
    }

    /** Returns Y+1 of the first opaque block scanning down from {@code fromY}, or 0. */
    private int scanColumn(int lx, int lz, int fromY, ColumnOpacityProbe probe) {
        for (int y = fromY; y >= 0; y--) {
            if (probe.isOpaqueAt(lx, y, lz)) return y + 1;
        }
        return SKY_ALL_THE_WAY_DOWN;
    }

    public long getMemoryUsageBytes() {
        return (long) heights.length * Short.BYTES + 32L;
    }
}
