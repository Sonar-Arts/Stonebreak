package com.stonebreak.blocks.waterSystem;

/**
 * Immutable read view of a single water cell, following Minecraft's level
 * convention: 0 is a full source block, 7 the thinnest flowing layer. Falling
 * cells report level 0 (full strength) with the falling flag set.
 *
 * <p>This is the API value type returned by {@code World.getWaterStateAt};
 * the authoritative state lives in the chunk-owned
 * {@link com.stonebreak.world.chunk.ChunkWaterLayer}.
 */
public record WaterBlock(int level, boolean falling) {

    public static final int SOURCE_LEVEL = 0;
    public static final int MAX_LEVEL = 7;

    public WaterBlock {
        if (level < SOURCE_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Water level must be between " + SOURCE_LEVEL + " and " + MAX_LEVEL + ": " + level);
        }
    }

    public static WaterBlock source() {
        return new WaterBlock(SOURCE_LEVEL, false);
    }

    public static WaterBlock falling(int level) {
        return new WaterBlock(level, true);
    }

    public boolean isSource() {
        return level == SOURCE_LEVEL && !falling;
    }
}
