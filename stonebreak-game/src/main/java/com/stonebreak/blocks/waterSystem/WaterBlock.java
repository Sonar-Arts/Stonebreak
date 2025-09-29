package com.stonebreak.blocks.waterSystem;

/**
 * Immutable representation of a single water cell. Levels follow Minecraft's
 * convention where 0 represents a full source block and 7 is the thinnest
 * flowing layer. Falling water keeps its level but is marked with the falling
 * flag so it only spreads horizontally once it lands.
 */
public record WaterBlock(int level, boolean falling) {

    public static final int SOURCE_LEVEL = 0;
    public static final int MAX_LEVEL = 7;
    public static final int EMPTY_LEVEL = MAX_LEVEL + 1;

    public WaterBlock {
        if (level < SOURCE_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Water level must be between " + SOURCE_LEVEL + " and " + MAX_LEVEL + ": " + level);
        }
    }

    public static WaterBlock source() {
        return new WaterBlock(SOURCE_LEVEL, false);
    }

    public static WaterBlock flowing(int level) {
        if (level < 1 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Flowing water level must be between 1 and " + MAX_LEVEL + ": " + level);
        }
        return new WaterBlock(level, false);
    }

    public static WaterBlock falling(int level) {
        return new WaterBlock(level, true);
    }

    public boolean isSource() {
        return level == SOURCE_LEVEL && !falling;
    }

    public boolean isStrongerThan(WaterBlock other) {
        if (other == null) {
            return true;
        }
        if (isSource()) {
            return !other.isSource();
        }
        if (other.isSource()) {
            return false;
        }
        if (level != other.level) {
            return level < other.level;
        }
        return !falling && other.falling();
    }

    public WaterBlock asFalling() {
        return falling ? this : new WaterBlock(level, true);
    }

    public WaterBlock withoutFalling() {
        return falling ? new WaterBlock(level, false) : this;
    }
}
