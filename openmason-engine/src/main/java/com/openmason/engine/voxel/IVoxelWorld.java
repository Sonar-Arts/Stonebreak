package com.openmason.engine.voxel;

/**
 * World-level block queries for cross-chunk operations.
 *
 * <p>Used by face culling and neighbor lookups that need to read blocks
 * across chunk boundaries. Implementations wrap the game's world class.
 */
public interface IVoxelWorld {

    /**
     * Get the block type at world coordinates.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z
     * @return the block type, or null if out of bounds
     */
    IBlockType getBlockAt(int x, int y, int z);

    /**
     * Check if a chunk exists at the given chunk coordinates.
     * Used to avoid triggering chunk generation during neighbor lookups.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true if the chunk is loaded
     */
    boolean hasChunkAt(int chunkX, int chunkZ);
}
