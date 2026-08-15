package com.stonebreak.world;

import com.stonebreak.blocks.BlockType;

/**
 * Where a world reports authoritative simulation mutations so they can be replicated.
 *
 * <p>The integrated server installs these on the HEADLESS world only. On a client render world every
 * sink stays null — feeding one there would loop the server's own echoes back out. Each sink covers
 * a change that bypasses the normal edit funnel because it originates in simulation:</p>
 *
 * <ul>
 *   <li><b>blocks</b> — water flow and any future system writing via {@code chunk.setBlock} rather
 *       than {@code World.setBlockAt}</li>
 *   <li><b>snow</b> — layer changes ({@code layers == 0} means removed), replicated as
 *       {@code BlockMetaS2C}</li>
 *   <li><b>water</b> — flow levels (1..7 flowing, 8 falling, 0 = entry removed / became source),
 *       replicated as {@code BlockMetaS2C} KIND_WATER_LEVEL; fired from
 *       {@code WorldFlowWorld.markWaterChanged} on the server tick thread</li>
 * </ul>
 *
 * <p>All fields are volatile: sinks are installed on the main thread and read from the sim thread.</p>
 */
public final class ServerMutationSinks {

    @FunctionalInterface
    public interface BlockSink {
        void onServerBlockChange(int x, int y, int z, BlockType type);
    }

    @FunctionalInterface
    public interface SnowSink {
        void onServerSnowChange(int x, int y, int z, int layers);
    }

    @FunctionalInterface
    public interface WaterSink {
        void onServerWaterChange(int x, int y, int z, int value);
    }

    private volatile BlockSink blocks;
    private volatile SnowSink snow;
    private volatile WaterSink water;

    public void setBlockSink(BlockSink sink) {
        this.blocks = sink;
    }

    public void setSnowSink(SnowSink sink) {
        this.snow = sink;
    }

    public void setWaterSink(WaterSink sink) {
        this.water = sink;
    }

    /** The block sink, or null on client/render worlds. */
    public BlockSink blocks() {
        return blocks;
    }

    /** The snow sink, or null on client/render worlds. */
    public SnowSink snow() {
        return snow;
    }

    /** The water sink, or null on client/render worlds. */
    public WaterSink water() {
        return water;
    }
}
