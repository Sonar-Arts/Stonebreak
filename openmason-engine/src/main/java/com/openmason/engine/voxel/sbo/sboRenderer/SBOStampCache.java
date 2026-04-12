package com.openmason.engine.voxel.sbo.sboRenderer;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.sbo.SBOMeshProcessor.BlockStamp;

import java.util.HashMap;
import java.util.Map;

/**
 * Thread-safe cache for pre-computed SBO block stamps.
 *
 * <p>Stores {@link BlockStamp} records keyed by block type ID.
 * Stamps are computed once at startup by {@link com.openmason.engine.voxel.sbo.SBOMeshProcessor}
 * and looked up at chunk mesh generation time by {@link SBOStampEmitter}.
 *
 * <p>The cache is populated during initialization and read-only at runtime,
 * so no synchronization is needed after initialization completes.
 */
public class SBOStampCache {

    private final Map<Integer, BlockStamp> stamps = new HashMap<>();

    /**
     * Store a pre-computed block stamp.
     *
     * @param blockType the block type this stamp represents
     * @param stamp     the pre-computed stamp data
     */
    public void put(IBlockType blockType, BlockStamp stamp) {
        stamps.put(blockType.getId(), stamp);
    }

    /**
     * Retrieve the block stamp for a block type.
     *
     * @param blockType the block type
     * @return the stamp, or null if no SBO mesh exists for this type
     */
    public BlockStamp get(IBlockType blockType) {
        return stamps.get(blockType.getId());
    }

    /**
     * Check if a block type has a cached SBO stamp.
     *
     * @param blockType the block type
     * @return true if a stamp exists
     */
    public boolean has(IBlockType blockType) {
        return stamps.containsKey(blockType.getId());
    }

    /**
     * Get the number of cached stamps.
     */
    public int size() {
        return stamps.size();
    }
}
