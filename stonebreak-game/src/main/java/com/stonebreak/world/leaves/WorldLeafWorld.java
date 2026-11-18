package com.stonebreak.world.leaves;

import java.util.Objects;

import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.ServerMutationSinks;
import com.stonebreak.world.World;
import com.stonebreak.world.voxel.BlockIds;

/**
 * Production {@link LeafWorld} over a {@link World}: block reads from the native voxel world, decay writes
 * routed through {@link World#setBlockAt} — the same block-change funnel player edits use — so everything
 * watching that funnel observes the removal: the water simulation (settled water flows into the opened
 * cell), the animated-block registry, physics, and the leaf system's own cascade trigger. Replication to
 * clients goes through the integrated server's mutation sink, exactly like water-flow writes.
 */
public final class WorldLeafWorld implements LeafWorld {

    private final World world;
    /** Owner-thread scratch for {@link #cubeBlocks} (one cube of ids). */
    private short[] ids;

    public WorldLeafWorld(World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    @Override
    public BlockType getBlock(long x, long y, long z) {
        return world.getBlockAt(x, y, z);
    }

    /** Known content: resident cube, known air, or outside the world (air). */
    @Override
    public boolean isLoaded(long x, long y, long z) {
        return world.isKnown(x, y, z);
    }

    /**
     * Per cube of the box: resident, or - when missing - known air from the box's lowest y in every column
     * it covers (known air is "at or above the column's top", so the lowest cell decides the column).
     */
    @Override
    public boolean isRegionLoaded(long x0, long y0, long z0, long x1, long y1, long z1) {
        for (long cx = x0 >> 5; cx <= x1 >> 5; cx++) {
            for (long cy = y0 >> 5; cy <= y1 >> 5; cy++) {
                for (long cz = z0 >> 5; cz <= z1 >> 5; cz++) {
                    if (world.hasCube(cx, cy, cz)) {
                        continue;
                    }
                    long ylo = Math.max(y0, cy << 5);
                    for (long x = Math.max(x0, cx << 5); x <= Math.min(x1, (cx << 5) + 31); x++) {
                        for (long z = Math.max(z0, cz << 5); z <= Math.min(z1, (cz << 5) + 31); z++) {
                            if (!world.isKnown(x, ylo, z)) {
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
    public com.openmason.engine.physics.PhysicsWorld physics() {
        return com.stonebreak.world.physics.WorldPhysics.of(world).engine();
    }

    /** Uniform cubes (air, stone, water...) are skipped without reading cells. */
    @Override
    public BlockType[] cubeBlocks(CubePos pos) {
        CubeWorld voxels = world.voxels();
        int uniform = voxels.uniformId(pos.x(), pos.y(), pos.z());
        if (uniform == CubeWorld.MISSING) {
            return null;
        }
        int cells = CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE;
        BlockType[] out = new BlockType[cells];
        if (uniform != CubeWorld.NOT_UNIFORM) {
            BlockType b = BlockIds.of(uniform);
            if (!b.isLeaves() && !b.isLog()) {
                return null;
            }
            java.util.Arrays.fill(out, b);
            return out;
        }
        if (ids == null) {
            ids = new short[cells];
        }
        if (!voxels.readIds(pos.x(), pos.y(), pos.z(), ids)) {
            return null;
        }
        boolean any = false;
        for (int i = 0; i < cells; i++) {
            BlockType b = BlockIds.of(Short.toUnsignedInt(ids[i]));
            out[i] = b;
            any |= b.isLeaves() || b.isLog();
        }
        return any ? out : null;
    }

    @Override
    public void setBlock(long x, long y, long z, BlockType type) {
        BlockType previous = world.getBlockAt(x, y, z);
        if (previous == type || !world.isLoaded(x, y, z)) {
            return;
        }
        // Through the funnel: bypassing it left settled water beside a decayed leaf floating forever
        // (WaterSim only wakes on funnel notifications). Not a player modification, so no client intent.
        if (!world.setBlockAt(x, y, z, type)) {
            return;
        }
        ServerMutationSinks.BlockSink sink = world.serverSinks().blocks();
        if (sink != null) {
            sink.onServerBlockChange(Math.toIntExact(x), Math.toIntExact(y), Math.toIntExact(z), type);
        }
    }
}
