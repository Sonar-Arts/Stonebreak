package com.stonebreak.ui.terrainMapper.visualization;

/**
 * Reads every {@link PreviewChannel} of one world column in a single call.
 *
 * <p>All channels come from the same terrain tile, so once the tile has been fetched for one of
 * them the rest cost only CPU. Reading them together means a later switch to another mode finds
 * its values already cached instead of going back to the tiles.
 */
@FunctionalInterface
public interface TerrainColumns {

    /** Writes the column's values into {@code out}, indexed by {@link PreviewChannel#ordinal()}. */
    void sample(int worldX, int worldZ, float[] out);
}
