package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.heightmap.DistilledShape;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** Smoothness of the distilled shape, 1 - roughness: high = flat, eroded ground (label kept for the mapper UI). */
public final class ErosionVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;

    public ErosionVisualizer(NoiseRouter router) {
        this.router = router;
    }

    @Override public String displayName() { return "Erosion"; }

    @Override
    public float sample(int worldX, int worldZ) {
        DistilledShape.Column c = new DistilledShape.Column();
        router.shape().column(worldX, worldZ, c);
        return (float) (1.0 - c.rough);
    }

    @Override
    public float normalize(float raw) {
        return raw;
    }
}
