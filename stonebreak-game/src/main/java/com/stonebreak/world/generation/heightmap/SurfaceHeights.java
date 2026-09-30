package com.stonebreak.world.generation.heightmap;

/**
 * What the cave stack (carvers, {@link Density3D}, {@link WaterGuard}, {@link CaveWaterTable})
 * reads from a terrain: a column's surface height and its water level. Implemented by the
 * noise {@link HeightMapGenerator} (Standard) and by the tile-backed height map of the
 * DaedalusTGM-Exp generator, so both worlds carve with this one package.
 */
public interface SurfaceHeights {

    /** Surface height of the column: the first y above the ground. */
    int generateHeight(int x, int z);

    /** First y that is not water in the column, or {@code WorldConfiguration.NO_WATER}. */
    int waterLevel(int x, int z);
}
