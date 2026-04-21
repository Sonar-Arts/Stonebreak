package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.NoiseRouter;

/**
 * Temperature including altitude chill, matching the value the biome selector
 * receives. Raw range [0, 1].
 */
public final class TemperatureVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;
    private final HeightMapGenerator heightMap;

    public TemperatureVisualizer(NoiseRouter router, HeightMapGenerator heightMap) {
        this.router = router;
        this.heightMap = heightMap;
    }

    @Override public String displayName() { return "Temperature"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return router.temperature(worldX, worldZ, heightMap.shapedHeight(worldX, worldZ));
    }

    @Override
    public int colorFor(float normalized) {
        // Warm to cold: blue -> white -> red, so hot zones pop against frozen ones.
        float t = Math.max(0f, Math.min(1f, normalized));
        int r, g, b;
        if (t < 0.5f) {
            float k = t * 2f;
            r = Math.round(80f + 175f * k);
            g = Math.round(130f + 125f * k);
            b = Math.round(230f - 30f * k);
        } else {
            float k = (t - 0.5f) * 2f;
            r = 255;
            g = Math.round(255f - 140f * k);
            b = Math.round(200f - 180f * k);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
