package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Final surface height in blocks. Grayscale: black = deep valleys,
 * white = mountain peaks. Mirrors what chunk generation actually builds.
 */
public final class HeightVisualizer implements NoiseVisualizer {

    private final HeightMapGenerator heightMap;

    public HeightVisualizer(HeightMapGenerator heightMap) {
        this.heightMap = heightMap;
    }

    @Override public String displayName() { return "Height"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return heightMap.generateHeight(worldX, worldZ);
    }

    @Override
    public float normalize(float raw) {
        return Math.max(0f, Math.min(1f, raw / (float) WorldConfiguration.WORLD_HEIGHT));
    }

    @Override
    public String formatValue(float raw) {
        return Math.round(raw) + " blocks";
    }
}
