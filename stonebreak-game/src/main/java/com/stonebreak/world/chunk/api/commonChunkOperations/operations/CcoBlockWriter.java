package com.stonebreak.world.chunk.api.commonChunkOperations.operations;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.commonChunkOperations.coordinates.CcoBounds;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoBlockArray;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoDirtyTracker;

import java.util.Objects;

/**
 * Block writing operations with automatic dirty tracking for CCO chunks.
 * NOT thread-safe - caller must synchronize writes.
 *
 * Performance: < 100ns per write (array access + dirty flag set).
 */
public final class CcoBlockWriter {

    private final CcoBlockArray blocks;
    private final CcoBounds bounds;
    private final CcoDirtyTracker dirtyTracker;

    /**
     * Creates a block writer with dirty tracking.
     *
     * @param blocks Block array to write to
     * @param bounds Boundary validator
     * @param dirtyTracker Dirty tracker for automatic marking
     */
    public CcoBlockWriter(CcoBlockArray blocks, CcoBounds bounds, CcoDirtyTracker dirtyTracker) {
        this.blocks = Objects.requireNonNull(blocks, "blocks cannot be null");
        this.bounds = Objects.requireNonNull(bounds, "bounds cannot be null");
        this.dirtyTracker = Objects.requireNonNull(dirtyTracker, "dirtyTracker cannot be null");
    }

    /**
     * Creates a block writer with dirty tracking (simple constructor).
     *
     * @param blocks Block array to write to
     * @param dirtyTracker Dirty tracker for automatic marking
     */
    public CcoBlockWriter(CcoBlockArray blocks, CcoDirtyTracker dirtyTracker) {
        this(blocks, new CcoBounds(), dirtyTracker);
    }

    /**
     * Sets block at local coordinates with automatic dirty tracking.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @param block Block type to set
     * @return true if block was changed (and chunk marked dirty)
     */
    public boolean set(int x, int y, int z, BlockType block) {
        if (block == null) {
            block = BlockType.AIR;
        }

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
    public boolean setNoDirty(int x, int y, int z, BlockType block) {
        if (block == null) {
            block = BlockType.AIR;
        }

        return blocks.set(x, y, z, block);
    }

    /**
     * Sets block with unsafe direct array access (no bounds checking).
     * Caller MUST ensure coordinates are valid and handle dirty tracking.
     *
     * @param x Local X coordinate (must be valid)
     * @param y Local Y coordinate (must be valid)
     * @param z Local Z coordinate (must be valid)
     * @param block Block type to set
     */
    public void setUnsafe(int x, int y, int z, BlockType block) {
        if (block == null) {
            block = BlockType.AIR;
        }

        blocks.getUnderlyingArray()[x][y][z] = block;
    }

    /**
     * Removes block (sets to AIR) with dirty tracking.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return true if block was removed
     */
    public boolean remove(int x, int y, int z) {
        return set(x, y, z, BlockType.AIR);
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
    public boolean replace(int x, int y, int z, BlockType from, BlockType to) {
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
     * Gets the underlying block array.
     *
     * @return Block array
     */
    public CcoBlockArray getBlockArray() {
        return blocks;
    }
}
