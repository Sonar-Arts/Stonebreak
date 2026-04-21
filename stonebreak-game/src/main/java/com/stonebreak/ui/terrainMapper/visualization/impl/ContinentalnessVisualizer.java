package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** Ocean-to-inland gradient. Raw range is [-1, 1]. */
public final class ContinentalnessVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;

    public ContinentalnessVisualizer(NoiseRouter router) {
        this.router = router;
    }

    @Override public String displayName() { return "Continentalness"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return router.continentalness(worldX, worldZ);
    }

    @Override
    public float normalize(float raw) {
        return (raw + 1f) * 0.5f;
    }
}
