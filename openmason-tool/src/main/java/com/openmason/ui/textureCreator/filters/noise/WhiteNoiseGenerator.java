package com.openmason.ui.textureCreator.filters.noise;

import java.util.Random;

/**
 * White noise generator - pure random noise with no correlation between pixels.
 * Produces grainy, static-like patterns.
 */
public class WhiteNoiseGenerator implements NoiseGenerator {

    private final Random random;

    public WhiteNoiseGenerator(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public float generate(float x, float y) {
        // Use coordinates to seed the random generator for consistent results
        long coordSeed = ((long) Math.floor(x) << 32) | (long) Math.floor(y);
        random.setSeed(coordSeed);
        return random.nextFloat();
    }

    @Override
    public String getName() {
        return "White";
    }
}
