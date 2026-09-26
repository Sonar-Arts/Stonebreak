package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** Moisture in [0, 1]. Dry → wet; drives biome humidity class. */
public final class MoistureVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;

    public MoistureVisualizer(NoiseRouter router) {
        this.router = router;
    }

    @Override public String displayName() { return "Moisture"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return router.moisture(worldX, worldZ);
    }

    @Override
    public int colorFor(float normalized) {
        float t = Math.max(0f, Math.min(1f, normalized));
        int r = Math.round(200f - 170f * t);
        int g = Math.round(170f - 40f * t);
        int b = Math.round(110f + 145f * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
