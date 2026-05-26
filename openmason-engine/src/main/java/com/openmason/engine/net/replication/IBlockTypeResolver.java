package com.openmason.engine.net.replication;

import com.openmason.engine.voxel.IBlockType;

/**
 * Resolves a numeric block id back to an {@link IBlockType}. Supplied by the game module
 * so engine codecs (e.g. {@link com.openmason.engine.net.protocol.codec.VoxelChunkCodec})
 * can reconstruct blocks without depending on a concrete block enum.
 */
@FunctionalInterface
public interface IBlockTypeResolver {

    /**
     * @param id numeric block id (as produced by {@link IBlockType#getId()})
     * @return the matching block type, or the air block for unknown ids (never {@code null})
     */
    IBlockType byId(int id);
}
