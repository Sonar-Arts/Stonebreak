package com.stonebreak.world.generation.noise;

/**
 * One 3D world-generation noise channel. Contract mirrors {@link NoiseChannel2D}:
 * {@code sample(x, y, z)} returns the raw fbm value at that position, and the
 * two backends (native FastNoise2 / Java simplex) are each internally consistent
 * per backend — one implementation owns a world's noise.
 */
public interface NoiseChannel3D {

    /** Raw channel value at a position. */
    float sample(float x, float y, float z);
}
