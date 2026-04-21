package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** Peak strength gate. Raw range [-1, 1]; high values flatten mountains. */
public final class ErosionVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;

    public ErosionVisualizer(NoiseRouter router) {
        this.router = router;
    }

    @Override public String displayName() { return "Erosion"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return router.erosion(worldX, worldZ);
    }

    @Override
    public float normalize(float raw) {
        return (raw + 1f) * 0.5f;
    }
}
