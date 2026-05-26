package com.openmason.engine.net.replication;

import com.openmason.engine.voxel.IBlockType;

/**
 * Write sink for decoded block data. The game module implements this over its chunk /
 * world so engine codecs can apply blocks without importing game classes.
 *
 * <p>Coordinates match {@link com.openmason.engine.voxel.IVoxelChunkData}: {@code x} and
 * {@code z} are chunk-local (0..chunkSize-1) and {@code y} is world height
 * (0..worldHeight-1).
 *
 * <p><b>Later seam:</b> an interest-management / area-of-interest layer can wrap a
 * {@code BlockSetter} to gate which writes are applied per client. Not used this milestone.
 */
@FunctionalInterface
public interface BlockSetter {

    void setBlock(int x, int y, int z, IBlockType type);
}
