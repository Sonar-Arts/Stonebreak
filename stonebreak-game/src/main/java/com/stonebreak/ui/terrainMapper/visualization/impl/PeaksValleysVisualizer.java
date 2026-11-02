package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.heightmap.DistilledShape;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** Mountain-range body of the distilled shape, along-chain weighted, 0..1 (label kept for the mapper UI). */
public final class PeaksValleysVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;

    public PeaksValleysVisualizer(NoiseRouter router) {
        this.router = router;
    }

    @Override public String displayName() { return "Peaks & Valleys"; }

    @Override
    public float sample(int worldX, int worldZ) {
        DistilledShape.Column c = new DistilledShape.Column();
        router.shape().column(worldX, worldZ, c);
        return (float) c.mountain;
    }

    @Override
    public float normalize(float raw) {
        return raw;
    }
}
