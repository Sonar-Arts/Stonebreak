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

    /**
     * {@link #groundY}, or, where the column's top cell is not loaded, the surface tracker's estimate
     * ({@link World#topY}: what the column held when last seen, else the generator's guess). Used where an
     * unknown column must still count as covered: underground in a cave whose surface lies above the loaded
     * cubes (LOD only up there) the column is NOT open sky. {@link #UNKNOWN} only for a closed world.
     */
    public static int estimatedGroundY(World world, long x, long z) {
        int known = groundY(world, x, z);
        if (known != UNKNOWN || world.isClosed()) return known;
        return (int) Math.clamp(world.topY(x, z), WorldConfiguration.MIN_Y, WorldConfiguration.MAX_Y);
    }

    /**
     * Blocks of world above height {@code y} in column (x, z) by {@link #estimatedGroundY} (0 when the column
     * is open above {@code y}, or unknown): how deep under cover something at {@code y} is.
     */
    public static double coverAbove(World world, long x, long z, double y) {
        int ground = estimatedGroundY(world, x, z);
        return ground == UNKNOWN ? 0.0 : Math.max(0.0, ground - y);
    }

    /** Blocks between the centre and each ring sample of {@link #coverAround}. */
    public static final double COVER_SPREAD = 6.0;
    private static final double DIAGONAL = COVER_SPREAD / Math.sqrt(2.0);
    private static final double[] RING_X = {COVER_SPREAD, -COVER_SPREAD, 0, 0, DIAGONAL, DIAGONAL, -DIAGONAL, -DIAGONAL};
    private static final double[] RING_Z = {0, 0, COVER_SPREAD, -COVER_SPREAD, DIAGONAL, -DIAGONAL, DIAGONAL, -DIAGONAL};

    /**
     * How deep under ground height {@code y} is around (x, z): the MEDIAN {@link #coverAbove} of the column there and
     * eight around it ({@link #COVER_SPREAD} blocks out). One column alone lies in a big cave: under a skylight or a
     * carved opening the camera's own column is open, yet the cave around it is not. The median keeps a cave under a
     * hole buried and a spot under a single tree or overhang in the open.
     */
    public static double coverAround(World world, double x, double y, double z) {
        double[] covers = new double[RING_X.length + 1];
        covers[0] = coverAbove(world, (long) Math.floor(x), (long) Math.floor(z), y);
        for (int i = 0; i < RING_X.length; i++) {
            covers[i + 1] = coverAbove(world, (long) Math.floor(x + RING_X[i]), (long) Math.floor(z + RING_Z[i]), y);
        }
        java.util.Arrays.sort(covers);
        return covers[covers.length / 2];
    }

    /** Snow falls on snow and ice: the top block, or a snow layer lying on it. */
    public static boolean isSnow(World world, long x, long z) {
        int ground = groundY(world, x, z);
        if (ground == UNKNOWN || ground <= WorldConfiguration.MIN_Y) return false;
        BlockType surface = world.getBlockAt(x, ground - 1, z);
        return surface == BlockType.SNOW || surface == BlockType.SNOWY_DIRT || surface == BlockType.ICE;
    }
}
