package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Final surface height in blocks. Grayscale over the whole world height
 * [{@link WorldConfiguration#MIN_Y}, {@link WorldConfiguration#MAX_Y}): black = the bedrock floor,
 * white = the build ceiling. Mirrors what the world generator actually builds (the native
 * Standard generator at the selected terrain scale, see
 * {@link com.stonebreak.ui.terrainMapper.visualization.NativeStandardColumns}).
 */
public final class HeightVisualizer implements NoiseVisualizer {

    /** Where the heights come from: the surface height of a column, in world Y. */
    @FunctionalInterface
    public interface Heights {
        float height(int worldX, int worldZ);
    }

    private final Heights heights;

    public HeightVisualizer(Heights heights) {
        this.heights = heights;
    }

    @Override public String displayName() { return "Height"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return heights.height(worldX, worldZ);
    }

    @Override
    public float normalize(float raw) {
        return normalizeWorldY(raw);
    }

    /** {@code y} as a fraction of the world height: 0 at MIN_Y, 1 at MAX_Y (clamped; NaN reads 0). */
    public static float normalizeWorldY(float y) {
        float t = (y - WorldConfiguration.MIN_Y) / (float) WorldConfiguration.HEIGHT;
        return t > 0f ? Math.min(1f, t) : 0f;
    }

    @Override
    public String formatValue(float raw) {
        return Math.round(raw) + " blocks";
    }
}
