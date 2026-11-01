package com.openmason.engine.voxel;

/**
 * Read-only view of a chunk's block data.
 *
 * <p>Provides block access within a single chunk's local coordinate space.
 * Used by mesh generation systems to query block types without depending
 * on a specific chunk implementation.
 */
public interface IVoxelChunkData {

    /**
     * Get the block type at local chunk coordinates.
     *
     * @param x local X (0 to chunkSize-1)
     * @param y world Y (0 to worldHeight-1)
     * @param z local Z (0 to chunkSize-1)
     * @return the block type at that position
     */
    IBlockType getBlock(int x, int y, int z);

    /** Chunk X coordinate in chunk space. */
    int getChunkX();

    /** Chunk Z coordinate in chunk space. */
    int getChunkZ();
}
