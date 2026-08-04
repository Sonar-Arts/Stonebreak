package com.openmason.engine.wayfind.voxel;

import com.openmason.engine.util.LongIntHashMap;

/**
 * Memoising view of a {@link NavVolume} for the life of one search.
 *
 * <p>A search probes the same cells repeatedly — every column is examined by up to eight neighbours,
 * each at several heights — and behind the volume sits a chunk lookup plus a paletted block read,
 * which is the single most expensive thing a voxel search does. Caching collapses those repeats to
 * one probe per cell, and it is why the search core can afford to read the world lazily instead of
 * copying a window of it up front.
 *
 * <p>Flags and surface height are packed into one {@code int} so a hit costs a single primitive map
 * lookup. Surface height is quantised to 1/1024 of a block — four orders of magnitude finer than
 * any decision made against it, and exact for the full-block case that dominates.
 *
 * <p>Not thread-safe, and deliberately not invalidated: one cache belongs to one search, and a
 * search is short enough that a block changing underneath it is indistinguishable from the change
 * landing a moment later.
 */
public final class NavCellCache implements NavVolume {

    private static final int FLAG_BITS = 8;
    private static final int FLAG_MASK = (1 << FLAG_BITS) - 1;
    private static final int SURFACE_QUANTUM = 1024;

    private static final int MISS = -1;

    private final NavVolume delegate;
    private final LongIntHashMap cells;

    public NavCellCache(NavVolume delegate) {
        this(delegate, 2048);
    }

    public NavCellCache(NavVolume delegate, int expectedCells) {
        this.delegate = delegate;
        this.cells = new LongIntHashMap(expectedCells);
    }

    @Override
    public int flags(int x, int y, int z) {
        return sample(x, y, z) & FLAG_MASK;
    }

    @Override
    public float topSurface(int x, int y, int z) {
        return (float) (sample(x, y, z) >>> FLAG_BITS) / SURFACE_QUANTUM;
    }

    /** Cells probed so far — a direct read of how much world one search touched. */
    public int probedCells() {
        return cells.size();
    }

    private int sample(int x, int y, int z) {
        if (!NavNodes.inRange(x, y, z)) {
            return NavCell.UNKNOWN;
        }
        long key = NavNodes.pack(x, y, z);
        int packed = cells.get(key, MISS);
        if (packed == MISS) {
            int flags = delegate.flags(x, y, z);
            float surface = NavCell.isSolid(flags) ? delegate.topSurface(x, y, z) : 0.0f;
            int quantised = Math.round(Math.min(1.0f, Math.max(0.0f, surface)) * SURFACE_QUANTUM);
            packed = (flags & FLAG_MASK) | (quantised << FLAG_BITS);
            cells.put(key, packed);
        }
        return packed;
    }
}
