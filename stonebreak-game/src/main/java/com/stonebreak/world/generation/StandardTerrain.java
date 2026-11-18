package com.stonebreak.world.generation;

import com.stonebreak.world.operations.WorldConfiguration;

/**
 * The vertical frame Standard Generation ({@link TerrainGenerationSystem}) is tuned in, and
 * where that frame sits inside the taller world.
 *
 * <p>The noise stack, carvers and fused native kernel were built for a 256-block column with
 * the sea at 64. The world is now {@link WorldConfiguration#WORLD_HEIGHT} tall with the sea at
 * {@link WorldConfiguration#SEA_LEVEL}, so Standard terrain is generated in its own frame and
 * lifted by {@link #Y_OFFSET} on the way out: its sea lands on the global sea level, and the
 * column below the old floor is solid stone.
 */
public final class StandardTerrain {
    public static final int WORLD_HEIGHT = 256;
    public static final int SEA_LEVEL = 64;
    public static final int Y_OFFSET = WorldConfiguration.SEA_LEVEL - SEA_LEVEL;

    /** Whole 16-block storage sections below the frame. */
    public static final int SECTION_OFFSET = Y_OFFSET / 16;

    private StandardTerrain() {}

    /** A Standard-frame height (or water level) in world Y; {@code NO_WATER} passes through. */
    public static int lift(int standardY) {
        return standardY == WorldConfiguration.NO_WATER ? standardY : standardY + Y_OFFSET;
    }
}
