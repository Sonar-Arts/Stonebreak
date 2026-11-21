package com.stonebreak.world.weather;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Where precipitation lands, read from the native surface tracker (never waits on terrain generation):
 * the height rain stops at, and whether it is snow.
 *
 * <p>Rain stops at the column's TOP ({@link World#topY}: first free y above the topmost non-air
 * block): on water surfaces rather than lake beds, on ice, on canopies and roofs. The tracker answers
 * for never-generated columns too (the generator's guess), so a column whose top cell is not
 * actually known yet ({@link World#isKnown}: its cube is neither loaded nor known air) is
 * {@link #UNKNOWN} — callers fall back to their own default rather than trusting a guess.
 */
public final class WeatherSurface {

    /** Returned by {@link #groundY} when the column's top cell isn't known. */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    private WeatherSurface() {}

    /**
     * First open block above the column's topmost non-air block (water, ice, leaves and snow layers
     * included), clamped into the world's [MIN_Y, MAX_Y], or {@link #UNKNOWN}.
     */
    public static int groundY(World world, long x, long z) {
        long ground = world.topY(x, z);
        if (ground <= WorldConfiguration.MIN_Y) {
            // Open to the floor (or below the world): nothing stops the rain inside the world.
            return world.isKnown(x, WorldConfiguration.MIN_Y, z) ? WorldConfiguration.MIN_Y : UNKNOWN;
        }
        if (!world.isKnown(x, ground - 1, z)) return UNKNOWN;
        return (int) Math.min(WorldConfiguration.MAX_Y, ground);
    }

    /** Snow falls on snow and ice: the top block, or a snow layer lying on it. */
    public static boolean isSnow(World world, long x, long z) {
        int ground = groundY(world, x, z);
        if (ground == UNKNOWN || ground <= WorldConfiguration.MIN_Y) return false;
        BlockType surface = world.getBlockAt(x, ground - 1, z);
        return surface == BlockType.SNOW || surface == BlockType.SNOWY_DIRT || surface == BlockType.ICE;
    }
}
