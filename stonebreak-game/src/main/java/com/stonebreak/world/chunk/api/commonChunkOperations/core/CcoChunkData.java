package com.stonebreak.world.chunk.api.commonChunkOperations.core;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.commonChunkOperations.data.CcoChunkMetadata;

/**
 * CCO Chunk Data Interface - Read access to chunk block data
 *
 * Provides read-only access to chunk's block array and metadata.
 * Implementations must be thread-safe for concurrent reads.
 *
 * Design: Interface segregation - read operations only
 * Performance: < 50ns per block access
 */
public interface CcoChunkData {

    /**
     * Get block at chunk-local coordinates
     *
     * @param x Local X (0-15)
     * @param y Local Y (0-255)
     * @param z Local Z (0-15)
     * @return Block type or AIR if out of bounds
     *
     * Thread-safety: Safe for concurrent reads
     * Performance: < 50ns
     */
    BlockType getBlock(int x, int y, int z);

    /**
     * Check if coordinates are within chunk bounds
     *
     * @param x Local X
     * @param y Local Y
     * @param z Local Z
     * @return true if in bounds
     */
    boolean isInBounds(int x, int y, int z);

    /**
     * Get chunk metadata
     *
     * @return Immutable chunk metadata
     */
    CcoChunkMetadata getMetadata();

    /**
     * Get chunk X position
     *
     * @return Chunk X coordinate
     */
    default int getChunkX() {
        return getMetadata().getChunkX();
    }

    /**
     * Get chunk Z position
     *
     * @return Chunk Z coordinate
     */
    default int getChunkZ() {
        return getMetadata().getChunkZ();
    }
}
