package com.openmason.engine.voxel.cco.operations;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;

/**
 * Fast block reading operations for CCO chunks.
 * All operations are thread-safe for concurrent reads.
 */
public final class CcoBlockReader {

    private final CcoBlockStorage blocks;

    /**
     * Creates a block reader for the given block storage.
     *
     * @param blocks Block storage to read from
     */
    public CcoBlockReader(CcoBlockStorage blocks) {
        this.blocks = blocks;
    }

    /**
     * Gets block at local coordinates.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return Block type, or null if out of bounds
     */
    public IBlockType get(int x, int y, int z) {
        return blocks.get(x, y, z);
    }

    /**
     * Checks if block is AIR.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return true if AIR or out of bounds
     */
    public boolean isAir(int x, int y, int z) {
        IBlockType block = get(x, y, z);
        return block == null || block.isAir();
    }

    /**
     * Checks if block is solid (not air and marked as solid).
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return true if solid block
     */
    public boolean isSolid(int x, int y, int z) {
        IBlockType block = get(x, y, z);
        return block != null && !block.isAir() && block.isSolid();
    }

    /**
     * Checks if block is opaque (blocks light/visibility).
     * A block is opaque if it is not air and not transparent.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return true if opaque
     */
    public boolean isOpaque(int x, int y, int z) {
        IBlockType block = get(x, y, z);
        return block != null && !block.isAir() && !block.isTransparent();
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
     * Counts non-AIR blocks in the chunk.
     * Cheap for paletted storage (sums per-section counters).
     *
     * @return Number of non-AIR blocks
     */
    public int countNonAirBlocks() {
        return blocks.countNonAirBlocks();
    }

    /**
     * Checks if chunk is completely empty (all AIR).
     *
     * @return true if all blocks are AIR
     */
    public boolean isEmpty() {
        return countNonAirBlocks() == 0;
    }

    /**
     * Gets the underlying block storage for advanced operations.
     *
     * @return Block storage
     */
    public CcoBlockStorage getStorage() {
        return blocks;
    }
}
