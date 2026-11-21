package com.stonebreak.world.growth;

import java.util.Objects;

import com.openmason.engine.voxel.world.CubePos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;

/**
 * Production {@link SaplingWorld} over a {@link World}. Growth writes go through
 * {@link World#setBlockAt}, so water, leaf decay and physics all see the new tree, and then
 * to the integrated server's mutation sink so clients see it too (as {@code WorldLeafWorld}
 * does for decay). Progress lives in the cube's blob section; {@link #attach} registers the
 * world's unload-pending hook that writes it before a cube leaves.
 */
public final class WorldSaplingWorld implements SaplingWorld {

    private final World world;

    public WorldSaplingWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public void attach(SaplingGrowthSystem system) {
        // A save hook (not only unload-pending): progress reaches the blob at every save point too, so an
        // autosave followed by a crash keeps it. Save hooks also run right before a cube unloads.
        world.addSaveHook((w, cube) -> {
            if (w.isAuthoritative()) {
                system.onCubeUnloadPending(cube.pos());
            }
        });
    }

    @Override
    public void attach(BlueberryBushGrowthSystem system) {
        world.addSaveHook((w, cube) -> {
            if (w.isAuthoritative()) {
                system.onCubeUnloadPending(cube.pos());
            }
        });
    }

    @Override
    public String getState(long x, long y, long z) {
        return world.getBlockStateAt(x, y, z);
    }

    @Override
    public void setState(long x, long y, long z, String state) {
        world.setBlockStateAt(x, y, z, state);
    }

    @Override
    public byte[] readSection(CubePos cube, String section) {
        Cube c = world.getCube(cube.x(), cube.y(), cube.z());
        return c == null ? null : c.blobSection(section);
    }

    @Override
    public void writeSection(CubePos cube, String section, byte[] bytes) {
        Cube c = world.getCube(cube.x(), cube.y(), cube.z());
        if (c != null) {
            c.putBlobSection(section, bytes);
        }
    }

    @Override
    public BlockType getBlock(long x, long y, long z) {
        return world.getBlockAt(x, y, z);
    }

    @Override
    public void setBlock(long x, long y, long z, BlockType type) {
        if (!world.setBlockAt(x, y, z, type)) {
            return;
        }
        ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
        if (sink != null) {
            sink.onServerBlockChange(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z), type);
        }
    }

    @Override
    public boolean isAreaLoaded(long x, long y, long z, int radius, int height) {
        long y0 = Math.max(com.stonebreak.world.operations.WorldConfiguration.MIN_Y, y);
        long y1 = Math.min(com.stonebreak.world.operations.WorldConfiguration.MAX_Y - 1, y + height);
        for (long cx = (x - radius) >> 5; cx <= (x + radius) >> 5; cx++) {
            for (long cy = y0 >> 5; cy <= y1 >> 5; cy++) {
                for (long cz = (z - radius) >> 5; cz <= (z + radius) >> 5; cz++) {
                    if (world.hasCube(cx, cy, cz)) {
                        continue;
                    }
                    // A missing cube is fine only where it is known air: check the lowest cell of the box
                    // in it per column (known air = at or above the column's top).
                    long ylo = Math.max(y0, cy << 5);
                    for (long bx = Math.max(x - radius, cx << 5); bx <= Math.min(x + radius, (cx << 5) + 31); bx++) {
                        for (long bz = Math.max(z - radius, cz << 5); bz <= Math.min(z + radius, (cz << 5) + 31); bz++) {
                            if (!world.isKnown(bx, ylo, bz)) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    @Override
    public byte[] readSaplings(CubePos cube) {
        Cube c = world.getCube(cube.x(), cube.y(), cube.z());
        return c == null ? null : c.blobSection(SaplingGrowthSystem.BLOB_SECTION);
    }

    @Override
    public void writeSaplings(CubePos cube, byte[] section) {
        Cube c = world.getCube(cube.x(), cube.y(), cube.z());
        if (c != null) {
            c.putBlobSection(SaplingGrowthSystem.BLOB_SECTION, section);
        }
    }
}
