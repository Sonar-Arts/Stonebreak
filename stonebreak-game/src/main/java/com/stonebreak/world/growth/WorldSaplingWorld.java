package com.stonebreak.world.growth;

import java.util.Objects;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Production {@link SaplingWorld} over a {@link World}. Growth writes go through
 * {@link World#setBlockAt}, so water, leaf decay and meshing all see the new tree, and then
 * to the integrated server's mutation sink so clients see it too (as {@code WorldLeafWorld}
 * does for decay).
 */
public final class WorldSaplingWorld implements SaplingWorld {

    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final World world;

    public WorldSaplingWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public BlockType getBlock(int x, int y, int z) {
        return world.getBlockAt(x, y, z);
    }

    @Override
    public void setBlock(int x, int y, int z, BlockType type) {
        if (!world.setBlockAt(x, y, z, type)) {
            return;
        }
        ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
        if (sink != null) {
            sink.onServerBlockChange(x, y, z, type);
        }
    }

    @Override
    public String getState(int x, int y, int z) {
        return world.getBlockStateAt(x, y, z);
    }

    @Override
    public void setState(int x, int y, int z, String state) {
        world.setBlockStateAt(x, y, z, state);
    }

    @Override
    public boolean isAreaLoaded(int x, int z, int radius) {
        for (int cx = Math.floorDiv(x - radius, CHUNK_SIZE); cx <= Math.floorDiv(x + radius, CHUNK_SIZE); cx++) {
            for (int cz = Math.floorDiv(z - radius, CHUNK_SIZE); cz <= Math.floorDiv(z + radius, CHUNK_SIZE); cz++) {
                if (world.getChunkIfLoaded(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
