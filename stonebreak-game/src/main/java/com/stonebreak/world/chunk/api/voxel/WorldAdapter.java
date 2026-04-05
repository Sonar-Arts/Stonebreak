package com.stonebreak.world.chunk.api.voxel;

import com.openmason.engine.voxel.IBlockType;
import com.openmason.engine.voxel.IVoxelWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;

/**
 * Adapts the game {@link World} to the engine's {@link IVoxelWorld} interface.
 */
public class WorldAdapter implements IVoxelWorld {

    private World world;

    public WorldAdapter(World world) {
        this.world = world;
    }

    /** Update the wrapped world (supports late binding). */
    public void setWorld(World world) {
        this.world = world;
    }

    @Override
    public IBlockType getBlockAt(int x, int y, int z) {
        if (world == null) return null;
        BlockType bt = world.getBlockAt(x, y, z);
        return bt != null ? new BlockTypeAdapter(bt) : null;
    }

    @Override
    public boolean hasChunkAt(int chunkX, int chunkZ) {
        return world != null && world.hasChunkAt(chunkX, chunkZ);
    }
}
