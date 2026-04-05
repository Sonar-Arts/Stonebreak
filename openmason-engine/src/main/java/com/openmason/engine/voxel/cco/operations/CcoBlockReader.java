package com.openmason.engine.voxel.cco.operations;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.coordinates.CcoBounds;
import com.openmason.engine.voxel.cco.data.CcoBlockArray;

/**
 * Fast block reading operations for CCO chunks.
 * All operations are thread-safe for concurrent reads.
 * Performance: < 100ns per read (direct array access).
 */
public final class CcoBlockReader {

    private final CcoBlockArray blocks;

    /**
     * Creates a block reader for the given block array.
     *
     * @param blocks Block array to read from
     */
    public CcoBlockReader(CcoBlockArray blocks) {
        this.blocks = blocks;
    }

    /**
     * Gets block at local coordinates.
     *
     * @param x Local X coordinate
     * @param y Local Y coordinate
     * @param z Local Z coordinate
     * @return Block type, or AIR if out of bounds
     */
    public IBlockType get(int x, int y, int z) {
        return blocks.get(x, y, z);
    }

    /**
     * Gets block with bounds checking disabled (unsafe but fast).
     * Caller MUST ensure coordinates are valid.
     *
     * @param x Local X coordinate (must be valid)
     * @param y Local Y coordinate (must be valid)
     * @param z Local Z coordinate (must be valid)
     * @return Block type
     */
    public IBlockType getUnsafe(int x, int y, int z) {
        return blocks.getUnderlyingArray()[x][y][z];
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
     *
     * @return Number of non-AIR blocks
     */
    public int countNonAirBlocks() {
        return blocks.countNonAirBlocks();
    }

    /**
     * Counts unique block types in the chunk.
     *
     * @return Number of unique block types
     */
    public int countUniqueTypes() {
        return blocks.countUniqueBlockTypes();
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
     * Gets the underlying block array for advanced operations.
     * Use with caution - modifications will affect the chunk.
     *
     * @return Block array
     */
    public CcoBlockArray getBlockArray() {
        return blocks;
    }
}
