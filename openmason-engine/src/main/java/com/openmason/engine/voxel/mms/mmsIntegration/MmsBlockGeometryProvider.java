package com.openmason.engine.voxel.mms.mmsIntegration;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.core.CcoChunkData;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;

/**
 * Pluggable geometry provider for block mesh generation.
 *
 * <p>Implementations generate mesh vertices/indices for specific block types.
 * The {@link MmsBlockGeometryDispatcher} routes blocks to the appropriate
 * provider during chunk mesh generation.
 *
 * <p>Providers replace the hardcoded if/else chain in the legacy adapter,
 * enabling arbitrary block geometry (cubes, crosses, SBO models, etc.).
 */
public interface MmsBlockGeometryProvider {

    /**
     * Add geometry for a block at the given local chunk position.
     *
     * @param builder    mesh builder to add vertices/indices to
     * @param blockType  the block type to generate geometry for
     * @param lx         local X coordinate (0 to chunkSize-1)
     * @param ly         local Y coordinate (0 to worldHeight-1)
     * @param lz         local Z coordinate (0 to chunkSize-1)
     * @param chunkX     chunk X coordinate
     * @param chunkZ     chunk Z coordinate
     * @param chunkData  chunk data for neighbor lookups
     */
    void addBlockGeometry(MmsMeshBuilder builder, IBlockType blockType,
                          int lx, int ly, int lz, int chunkX, int chunkZ,
                          CcoChunkData chunkData);

    /**
     * Whether this provider handles the given block type.
     *
     * @param blockType the block type to check
     * @return true if this provider should generate geometry for this block type
     */
    boolean handles(IBlockType blockType);
}
