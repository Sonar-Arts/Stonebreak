package com.stonebreak.blocks.waterSystem;

import com.openmason.engine.voxel.world.CubeWorld;
import com.stonebreak.blocks.BlockType;

/**
 * The world surface the water simulation runs against. {@link WaterSim}
 * contains only scheduling and vanilla flow rules; everything it observes or
 * mutates goes through this seam, so the algorithm is unit-testable over a
 * flat array (see FakeFlowWorld in tests) and the production adapter
 * ({@link WorldFlowWorld}) owns all cube/layer/replication plumbing.
 *
 * <p>Water values follow the {@link com.stonebreak.world.chunk.ChunkWaterLayer}
 * encoding: 0 = source/absent, 1..7 = flowing level, 8 = falling, RIVER + octant.
 * Coordinates are 64-bit block coordinates; cubes are 32^3 ({@code cx = x >> 5}).
 */
public interface FlowWorld {

    /** Block at world coords; AIR outside the world or in unloaded cubes. */
    BlockType getBlock(long x, long y, long z);

    /** Whether the position is inside the world and its cube is resident. */
    boolean isLoaded(long x, long y, long z);

    /** Writes a block (WATER when flowing in, AIR when drying up). */
    void setBlock(long x, long y, long z, BlockType type);

    /** Water-layer value at the position (game vocabulary); 0 when absent. */
    int getWater(long x, long y, long z);

    /** Writes a water-layer value; 0 makes the cell a source (or clean if not water). */
    void setWater(long x, long y, long z, int value);

    /** Whether the block at the position is solid (infinite-source support rule). */
    boolean isSolid(long x, long y, long z);

    /** Pops a fragile block (flower) as an item drop before water floods the cell. */
    void dropFragile(long x, long y, long z, BlockType type);

    /**
     * Notifies that the visible water state at a position changed (level flip,
     * falling flip, entry removed). Adapters use it for level replication;
     * {@code newValue} is the new layer value (0..8).
     */
    void markWaterChanged(long x, long y, long z, int newValue);

    /** Visitor for {@link #forEachWaterValue}: world coordinates + game water value. */
    @FunctionalInterface
    interface WaterCellVisitor {
        void accept(long x, long y, long z, int value);
    }

    /**
     * Every cell of cube (cx, cy, cz) whose water value is non-zero (flowing, falling, river) - the
     * load scan. Default: a per-cell sweep (tests); the production adapter scans the native layer.
     */
    default void forEachWaterValue(long cx, long cy, long cz, WaterCellVisitor visitor) {
        long x0 = cx << 5, y0 = cy << 5, z0 = cz << 5;
        for (int ly = 0; ly < 32; ly++) {
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    long x = x0 + lx, y = y0 + ly, z = z0 + lz;
                    if (getBlock(x, y, z) == BlockType.WATER) {
                        int v = getWater(x, y, z);
                        if (v != 0) {
                            visitor.accept(x, y, z, v);
                        }
                    }
                }
            }
        }
    }

    /**
     * Copies the block ids of cube (cx, cy, cz) into {@code out} (32768 entries,
     * {@link CubeWorld#cellIndex} order); false when the cube is not loaded. Default: a per-cell
     * sweep (tests); the production adapter reads the cube natively in one call.
     */
    default boolean readCubeIds(long cx, long cy, long cz, short[] out) {
        long x0 = cx << 5, y0 = cy << 5, z0 = cz << 5;
        if (!isLoaded(x0, y0, z0)) {
            return false;
        }
        for (int ly = 0; ly < 32; ly++) {
            for (int lz = 0; lz < 32; lz++) {
                for (int lx = 0; lx < 32; lx++) {
                    out[CubeWorld.cellIndex(lx, ly, lz)] = (short) getBlock(x0 + lx, y0 + ly, z0 + lz).getId();
                }
            }
        }
        return true;
    }

    /** Called once after each logical sim tick — adapters flush batched work here. */
    default void onTickComplete() {
    }

    /** Cube eviction hook so adapters can drop per-cube caches. */
    default void onCubeUnloaded(long cx, long cy, long cz) {
    }
}
