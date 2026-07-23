package com.openmason.engine.voxel.cco.core;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.data.CcoChunkMetadata;

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
     * @return Block type or null if out of bounds
     *
     * Thread-safety: Safe for concurrent reads
     * Performance: < 50ns
     */
    IBlockType getBlock(int x, int y, int z);

    /**
     * Optional zero-copy escape hatch: the backing block storage, when this
     * view wraps one. Native mesh kernels bulk-snapshot through it instead of
     * 65k virtual {@link #getBlock} calls. Null when the implementation has no
     * single backing storage; callers must fall back to per-cell access.
     */
    default com.openmason.engine.voxel.cco.data.CcoBlockStorage backingStorage() {
        return null;
    }

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

    /**
     * Get the renderable state-variant name at chunk-local coordinates, or
     * {@code null} when the block carries no non-default state. Examples:
     * {@code "Lit"} for a smelting furnace, {@code null} for an unlit one.
     *
     * <p>Implementations may need to project a richer per-block payload (e.g.
     * inventory contents) down to just the render-relevant state name —
     * callers in the mesh pipeline only need the variant to select.
     *
     * <p>Default returns {@code null} so existing data sources keep working.
     */
    default String getBlockState(int x, int y, int z) {
        return null;
    }

    /**
     * Highest Y containing a non-air block, or -1 if the chunk is all air.
     * Meshers use this to skip iterating empty air space above the terrain.
     *
     * <p>Default returns {@link Integer#MAX_VALUE} (no information — callers
     * clamp to world height), so existing data sources keep working.
     */
    default int getHighestNonAirY() {
        return Integer.MAX_VALUE;
    }
}
