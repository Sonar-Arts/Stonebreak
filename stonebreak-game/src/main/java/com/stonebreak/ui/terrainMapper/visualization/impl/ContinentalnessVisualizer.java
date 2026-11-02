package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.heightmap.DistilledShape;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** Continent weight of the distilled shape: 0 = open ocean, 1 = continent (label kept for the mapper UI). */
public final class ContinentalnessVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;

    public ContinentalnessVisualizer(NoiseRouter router) {
        this.router = router;
    }

    @Override public String displayName() { return "Continentalness"; }

    @Override
    public float sample(int worldX, int worldZ) {
        DistilledShape.Column c = new DistilledShape.Column();
        router.shape().column(worldX, worldZ, c);
        return (float) c.land;
    }

    @Override
    public float normalize(float raw) {
        return raw;
    }
}
