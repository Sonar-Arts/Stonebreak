package com.stonebreak.world.chunk.api.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelChunkData;
import com.stonebreak.world.chunk.Chunk;

/**
 * Adapts a game {@link Chunk} to the engine's {@link IVoxelChunkData} interface.
 */
public record ChunkDataAdapter(Chunk chunk) implements IVoxelChunkData {

    @Override
    public IBlockType getBlock(int x, int y, int z) {
        return new BlockTypeAdapter(chunk.getBlock(x, y, z));
    }

    @Override
    public int getChunkX() {
        return chunk.getChunkX();
    }

    @Override
    public int getChunkZ() {
        return chunk.getChunkZ();
    }
}
