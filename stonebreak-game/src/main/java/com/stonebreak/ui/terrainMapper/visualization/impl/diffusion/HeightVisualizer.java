package com.stonebreak.ui.terrainMapper.visualization.impl.diffusion;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.ui.terrainMapper.visualization.PreviewChannel;
import com.stonebreak.world.generation.diffusion.heightmap.HeightMapGenerator;

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

    @Override public PreviewChannel channel() { return PreviewChannel.HEIGHT; }

    @Override
    public float normalize(float raw) {
        return com.stonebreak.ui.terrainMapper.visualization.impl.HeightVisualizer.normalizeWorldY(raw);
    }

    @Override
    public String formatValue(float raw) {
        return Math.round(raw) + " blocks";
    }
}
