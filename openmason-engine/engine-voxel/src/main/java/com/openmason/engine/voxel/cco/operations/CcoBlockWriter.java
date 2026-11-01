package com.openmason.engine.voxel.cco.operations;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.openmason.engine.voxel.cco.data.CcoDirtyTracker;

import java.util.Objects;

/**
 * Block writing operations with automatic dirty tracking for CCO chunks.
 * Single-cell writes are internally synchronized by the storage; callers
 * coordinating multi-cell invariants must still synchronize externally.
 */
public final class CcoBlockWriter {

    private final CcoBlockStorage blocks;
    private final CcoDirtyTracker dirtyTracker;

    /**
     * Creates a block writer with dirty tracking.
     *
     * @param blocks Block storage to write to
     * @param dirtyTracker Dirty tracker for automatic marking
     */
    public CcoBlockWriter(CcoBlockStorage blocks, CcoDirtyTracker dirtyTracker) {
        this.blocks = Objects.requireNonNull(blocks, "blocks cannot be null");
        this.dirtyTracker = Objects.requireNonNull(dirtyTracker, "dirtyTracker cannot be null");
    }

    /**
     * Sets block at local coordinates with automatic dirty tracking.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @param block Block type to set (null treated as air)
     * @return true if block was changed (and chunk marked dirty)
     */
    public boolean set(int x, int y, int z, IBlockType block) {
        boolean changed = blocks.set(x, y, z, block);

        if (changed) {
            // Mark both mesh and data as dirty
            dirtyTracker.markBlockChanged();
        }

        return changed;
    }

    /**
     * Sets block without dirty tracking (use with caution).
     * Useful for bulk operations where dirty tracking is handled externally.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @param block Block type to set
     * @return true if block was changed
     */
    public boolean setNoDirty(int x, int y, int z, IBlockType block) {
        return blocks.set(x, y, z, block);
    }

    /**
     * Removes block (sets to null/air) with dirty tracking.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return true if block was removed
     */
    public boolean remove(int x, int y, int z) {
        return set(x, y, z, null);
    }

    /**
     * Replaces one block type with another if present.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @param from Block type to replace
     * @param to Block type to replace with
     * @return true if block was replaced
     */
    public boolean replace(int x, int y, int z, IBlockType from, IBlockType to) {
        if (!CcoBounds.isInBounds(x, y, z)) {
            return false;
        }

        if (blocks.get(x, y, z) == from) {
            return set(x, y, z, to);
        }

        return false;
    }

    /**
     * Checks if coordinates are within bounds.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return true if within chunk bounds
     */
    public boolean isInBounds(int x, int y, int z) {
        return CcoBounds.isInBounds(x, y, z);
    }

    /**
     * Gets the dirty tracker for manual dirty flag manipulation.
     *
     * @return Dirty tracker
     */
    public CcoDirtyTracker getDirtyTracker() {
        return dirtyTracker;
    }

    /**
     * Gets the underlying block storage.
     *
     * @return Block storage
     */
    public CcoBlockStorage getStorage() {
        return blocks;
    }
}
