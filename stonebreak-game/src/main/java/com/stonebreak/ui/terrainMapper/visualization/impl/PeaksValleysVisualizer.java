package com.stonebreak.ui.terrainMapper.visualization.impl;

import com.stonebreak.ui.terrainMapper.visualization.NoiseVisualizer;
import com.stonebreak.world.generation.noise.NoiseRouter;

/** Signed ridged mountain channel. Raw range [-1, 1]. */
public final class PeaksValleysVisualizer implements NoiseVisualizer {

    private final NoiseRouter router;

    public PeaksValleysVisualizer(NoiseRouter router) {
        this.router = router;
    }

    @Override public String displayName() { return "Peaks & Valleys"; }

    @Override
    public float sample(int worldX, int worldZ) {
        return router.peaksValleys(worldX, worldZ);
    }

    @Override
    public float normalize(float raw) {
        return (raw + 1f) * 0.5f;
    }
}
