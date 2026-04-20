package com.stonebreak.ui.terrainMapper.visualization;

/**
 * Strategy for turning a world-space (x, z) sample into a preview pixel.
 * One instance per visualization mode; {@link VisualizerRegistry} binds them
 * to a seed so each query is cheap (no constructor work per pixel).
 *
 * Ranges vary per channel; {@link #normalize(float)} folds them into [0, 1]
 * so the grayscale default color function works uniformly. Biome-style
 * visualizers override {@link #colorFor(float)} for categorical palettes.
 */
public interface NoiseVisualizer {

    /** Short display label shown in the sidebar and footer tooltip. */
    String displayName();

    /** Raw sample at a world position. Cheap — must be safe to call per-pixel. */
    float sample(int worldX, int worldZ);

    /** Map a raw sample to [0, 1]; default assumes already in range. */
    default float normalize(float raw) {
        return Math.max(0f, Math.min(1f, raw));
    }

    /** Returns an ARGB color for a normalized value. Default is grayscale. */
    default int colorFor(float normalized) {
        int v = Math.round(Math.max(0f, Math.min(1f, normalized)) * 255f);
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    /** Human-readable value shown in the footer tooltip. */
    default String formatValue(float raw) {
        return String.format("%.3f", raw);
    }
}
