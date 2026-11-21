package com.stonebreak.world.leaves;

import com.openmason.engine.physics.PhysicsWorld;
import com.openmason.engine.voxel.world.CubePos;
import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The world surface the leaf-decay simulation runs against. {@link LeafDecaySystem}
 * contains only the scheduling and reachability rules; everything it observes or
 * mutates goes through this seam, so the algorithm is unit-testable over a flat
 * array (see FakeLeafWorld in tests) and the production adapter
 * ({@link WorldLeafWorld}) owns all cube/replication plumbing.
 *
 * <p>Block coordinates are {@code long} (cubic voxel runtime, #447).
 */
public interface LeafWorld {

    /** Block at world coords; AIR outside the world or in unloaded cells. */
    BlockType getBlock(long x, long y, long z);

    /**
     * Whether the cell's content is known: its cube is resident, or it is known air (above its
     * column's top) - a cell nothing unseen can hide a log in.
     */
    boolean isLoaded(long x, long y, long z);

    /**
     * Whether every cell of the box {@code [x0, x1] x [y0, y1] x [z0, z1]} (inclusive, already clamped to
     * {@link #minY()}..{@link #maxY()}-1) is {@link #isLoaded known}. The default probes one cell of each
     * cube the box touches (its clipped corner); adapters may answer more precisely.
     */
    default boolean isRegionLoaded(long x0, long y0, long z0, long x1, long y1, long z1) {
        for (long cx = x0 >> 5; cx <= x1 >> 5; cx++) {
            for (long cy = y0 >> 5; cy <= y1 >> 5; cy++) {
                for (long cz = z0 >> 5; cz <= z1 >> 5; cz++) {
                    if (!isLoaded(Math.max(x0, cx << 5), Math.max(y0, cy << 5), Math.max(z0, cz << 5))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Lowest block y the flood and the residency check may touch (inclusive). */
    default long minY() {
        return WorldConfiguration.MIN_Y;
    }

    /** One past the highest block y. */
    default long maxY() {
        return WorldConfiguration.MAX_Y;
    }

    /**
     * The physics world whose cells mirror this one, where the reachability flood runs: leaves carry
     * {@link com.stonebreak.world.physics.WorldPhysics#LEAVES}, logs
     * {@link com.stonebreak.world.physics.WorldPhysics#LOG}, unloaded cells are empty, and every
     * {@link #setBlock} reaches it (through the world's physics invalidation). Called on the sim tick
     * thread, which owns it.
     */
    PhysicsWorld physics();

    /**
     * Writes a block (AIR when a leaf decays). The production adapter routes
     * this through the world's block-change funnel, so co-simulations (water),
     * meshing and replication all observe the removal; called on the sim tick
     * thread.
     */
    void setBlock(long x, long y, long z, BlockType type);

    /**
     * Whether the leaf at these coords was placed by a player
     * ({@link com.stonebreak.blocks.leaves.LeafState#PLACED}). Such a leaf never
     * decays. It still carries support through itself like any leaf, but it is
     * not an anchor: only logs hold a canopy up.
     */
    default boolean isPersistent(long x, long y, long z) {
        return false;
    }

    /**
     * The blocks of one resident cube in {@link CubeWorld#cellIndex} order, for the install rescan, or null
     * when the cube is not resident or provably holds no leaf and no log (e.g. a uniform air or stone cube).
     * The default reads the cells one by one.
     */
    default BlockType[] cubeBlocks(CubePos pos) {
        long bx = pos.minBlockX(), by = pos.minBlockY(), bz = pos.minBlockZ();
        if (!isLoaded(bx, by, bz)) {
            return null;
        }
        BlockType[] out = new BlockType[CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE * CubeWorld.CUBE_EDGE];
        boolean any = false;
        for (int ly = 0; ly < 32; ly++) {
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    BlockType b = getBlock(bx + lx, by + ly, bz + lz);
                    out[CubeWorld.cellIndex(lx, ly, lz)] = b;
                    any |= b != null && (b.isLeaves() || b.isLog());
                }
            }
        }
        return any ? out : null;
    }

    /** Called once after each logical sim tick — adapters flush batched work here. */
    default void onTickComplete() {
    }

    /** Cube eviction hook so adapters can drop per-cube caches. */
    default void onCubeUnloaded(CubePos pos) {
    }
}
