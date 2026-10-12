package com.openmason.main.systems.menus.textureCreator.filters.noise;

import java.util.Random;

/**
 * White noise generator - pure random noise with no correlation between pixels.
 * Produces grainy, static-like patterns.
 */
public class WhiteNoiseGenerator implements NoiseGenerator {

    private final long seed;
    private final Random random;

    public WhiteNoiseGenerator(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    @Override
    public float generate(float x, float y) {
        // Re-seed per cell so a coordinate always yields the same value, mixing in
        // the generator's own seed so different seeds give different patterns
        // (the constructor seed used to be overwritten here, so every seed
        // produced the identical pattern). y is masked so a negative y cannot
        // sign-extend over the x bits.
        long coordSeed = ((long) Math.floor(x) << 32) | ((long) Math.floor(y) & 0xFFFFFFFFL);
        random.setSeed(coordSeed ^ (seed * 0x9E3779B97F4A7C15L));
        return random.nextFloat();
    }

    @Override
    public String getName() {
        return "White";
    }
}
