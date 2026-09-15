package com.stonebreak.ui.terrainMapper.visualization;

/**
 * The per-column terrain values the preview draws from. Several visualizers render the same
 * channel (Height and Topography are both {@link #HEIGHT}), which is why the preview caches
 * channels rather than pictures: one sampled column serves every mode that reads it.
 */
public enum PreviewChannel {
    /** Final surface height, in blocks. */
    HEIGHT,
    /** Water level, in blocks, or {@code TerrainTile.NO_WATER} where the column is dry. */
    WATER,
    /** {@code BiomeType} ordinal. */
    BIOME;

    public static final int COUNT = values().length;
}
