package com.stonebreak.blocks.waterSystem;

import com.stonebreak.blocks.BlockType;

/**
 * The world surface the water simulation runs against. {@link WaterSim}
 * contains only scheduling and vanilla flow rules; everything it observes or
 * mutates goes through this seam, so the algorithm is unit-testable over a
 * flat array (see FakeFlowWorld in tests) and the production adapter
 * ({@link WorldFlowWorld}) owns all Chunk/CCO/replication plumbing.
 *
 * <p>Water values follow the {@link com.stonebreak.world.chunk.ChunkWaterLayer}
 * encoding: 0 = source/absent, 1..7 = flowing level, 8 = falling.
 */
public interface FlowWorld {

    /** Block at world coords; AIR outside the world or in unloaded chunks. */
    BlockType getBlock(int x, int y, int z);

    /** Whether the position is inside the world and its chunk is resident. */
    boolean isLoaded(int x, int y, int z);

    /** Writes a block (WATER when flowing in, AIR when drying up). */
    void setBlock(int x, int y, int z, BlockType type);

    /** Raw water-layer value at the position: 0 (absent), 1..7, or 8 (falling). */
    int getWater(int x, int y, int z);

    /** Writes a water-layer value; 0 removes the entry. */
    void setWater(int x, int y, int z, int value);

    /** Whether the block at the position is solid (infinite-source support rule). */
    boolean isSolid(int x, int y, int z);

    /** Pops a fragile block (flower) as an item drop before water floods the cell. */
    void dropFragile(int x, int y, int z, BlockType type);

    /**
     * Notifies that the visible water state at a position changed (level flip,
     * falling flip, entry removed). Adapters use it for mesh dirtying and
     * level replication; {@code newValue} is the new layer value (0..8).
     */
    void markWaterChanged(int x, int y, int z, int newValue);

    /** Called once after each logical sim tick — adapters flush batched work here. */
    default void onTickComplete() {
    }

    /** Chunk eviction hook so adapters can drop per-chunk caches. */
    default void onChunkUnloaded(int chunkX, int chunkZ) {
    }
}
