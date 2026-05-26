package com.stonebreak.network.bridge;

import com.openmason.engine.net.replication.BlockSetter;
import com.openmason.engine.voxel.IBlockType;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.chunk.api.voxel.BlockTypeAdapter;

/**
 * {@link BlockSetter} that writes decoded blocks straight into a target {@link Chunk}.
 * Used by {@link com.openmason.engine.net.protocol.codec.VoxelChunkCodec#decodeInto} when
 * installing a network chunk on the client.
 *
 * <p>Writing into the chunk does not fire any local-change broadcast hook, so this is the
 * non-broadcasting apply path for chunk installs.
 */
public final class GameBlockSetter implements BlockSetter {

    private final Chunk chunk;

    public GameBlockSetter(Chunk chunk) {
        this.chunk = chunk;
    }

    @Override
    public void setBlock(int x, int y, int z, IBlockType type) {
        BlockType blockType = (type instanceof BlockTypeAdapter adapter)
            ? adapter.unwrap()
            : BlockType.getById(type.getId());
        if (blockType == null) {
            blockType = BlockType.AIR;
        }
        chunk.setBlock(x, y, z, blockType);
    }
}
