package com.stonebreak.network.bridge;

import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.cco.data.CcoBlockStorage;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.voxel.BlockTypeAdapter;

/**
 * {@link BlockSetter} that decodes into a detached {@link CcoBlockStorage}
 * instead of a live chunk. Lets a network chunk payload be decoded off the
 * chunk's hot path and then installed with one cheap section-level copy —
 * no per-block dirty tracking, state-map removals, or incremental heightmap
 * updates (the caller recomputes the heightmap once afterwards).
 *
 * <p>AIR writes are skipped so above-terrain sections stay in their uniform
 * tier. Unwraps {@link BlockTypeAdapter} so the storage only ever holds
 * {@link BlockType} constants (the chunk's read path casts to it).
 */
public final class StorageBlockSetter implements BlockSetter {

    private final CcoBlockStorage storage;

    public StorageBlockSetter(CcoBlockStorage storage) {
        this.storage = storage;
    }

    @Override
    public void setBlock(int x, int y, int z, IBlockType type) {
        BlockType blockType = (type instanceof BlockTypeAdapter adapter)
            ? adapter.unwrap()
            : BlockType.getById(type.getId());
        if (blockType == null || blockType == BlockType.AIR) {
            return;
        }
        storage.set(x, y, z, blockType);
    }
}
