package com.stonebreak.world.generation.features;

/**
 * A column's terrain height in world Y — all the ore, limestone and decoration passes ask of
 * whichever generator is running, so they stay shared between Standard and Diffusion.
 */
@FunctionalInterface
public interface ColumnHeights {
    int generateHeight(int worldX, int worldZ);
}
