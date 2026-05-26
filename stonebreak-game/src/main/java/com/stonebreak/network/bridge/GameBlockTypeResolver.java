package com.stonebreak.network.bridge;

import com.openmason.engine.net.replication.IBlockTypeResolver;
import com.openmason.engine.voxel.IBlockType;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.api.voxel.BlockTypeAdapter;

/**
 * Resolves a numeric block id to a game {@link BlockType}, wrapped as the engine's
 * {@link IBlockType} via the existing {@link BlockTypeAdapter}. Unknown ids resolve to
 * {@link BlockType#AIR} so a corrupt chunk payload can never yield {@code null}.
 *
 * <p>Stateless and thread-safe; share a single instance.
 */
public final class GameBlockTypeResolver implements IBlockTypeResolver {

    public static final GameBlockTypeResolver INSTANCE = new GameBlockTypeResolver();

    @Override
    public IBlockType byId(int id) {
        BlockType type = BlockType.getById(id);
        if (type == null) {
            type = BlockType.AIR;
        }
        return new BlockTypeAdapter(type);
    }
}
