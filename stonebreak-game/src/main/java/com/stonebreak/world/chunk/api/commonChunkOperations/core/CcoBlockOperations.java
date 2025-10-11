package com.stonebreak.world.chunk.api.commonChunkOperations.core;

import com.stonebreak.blocks.BlockType;

/**
 * CCO Block Operations Interface - Block read/write operations
 *
 * Provides block manipulation with automatic dirty tracking.
 * Implementations must validate bounds and handle edge cases.
 *
 * Design: Interface segregation for block operations
 * Performance: < 200ns per operation including dirty tracking
 */
public interface CcoBlockOperations extends CcoChunkData {

    /**
     * Set block at chunk-local coordinates
     *
     * @param x Local X (0-15)
     * @param y Local Y (0-255)
     * @param z Local Z (0-15)
     * @param block Block type to set
     * @return true if block changed
     *
     * Thread-safety: Must be thread-safe
     * Performance: < 200ns including dirty tracking
     */
    boolean setBlock(int x, int y, int z, BlockType block);

    /**
     * Fill region with block type
     *
     * @param x1 Start X (inclusive)
     * @param y1 Start Y (inclusive)
     * @param z1 Start Z (inclusive)
     * @param x2 End X (exclusive)
     * @param y2 End Y (exclusive)
     * @param z2 End Z (exclusive)
     * @param block Block type to fill
     * @return Number of blocks changed
     *
     * Thread-safety: Must be thread-safe
     */
    int fillRegion(int x1, int y1, int z1, int x2, int y2, int z2, BlockType block);

    /**
     * Replace all blocks of one type with another
     *
     * @param from Block type to replace
     * @param to Replacement block type
     * @return Number of blocks replaced
     *
     * Thread-safety: Must be thread-safe
     */
    int replaceAll(BlockType from, BlockType to);

    /**
     * Count blocks of specific type
     *
     * @param block Block type to count
     * @return Number of matching blocks
     */
    int countBlocks(BlockType block);

    /**
     * Check if chunk contains any non-air blocks
     *
     * @return true if chunk has solid blocks
     */
    boolean hasBlocks();
}
