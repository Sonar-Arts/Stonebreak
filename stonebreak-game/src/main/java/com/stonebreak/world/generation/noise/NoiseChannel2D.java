package com.stonebreak.world.generation.noise;

/**
 * One 2D world-generation noise channel sampled in block coordinates,
 * returning raw fbm values in ~[-1, 1] (any post-transform, such as a
 * [0, 1] remap, belongs to the caller).
 *
 * Contract: {@code sample(x, z)} and any cell of {@link #fill} at the same
 * block coordinate return the SAME value. FastLOD relies on this — the chunk
 * pipeline samples via grids while FastLOD samples per point, and both must
 * agree bit-for-bit at L0.
 */
public interface NoiseChannel2D {

    /** Raw channel value at a block position. */
    float sample(int x, int z);

    /**
     * Fills {@code out[ix * countZ + iz]} (z fastest, matching the game's
     * {@code [x*16+z]} chunk-grid convention) with raw channel values at
     * block positions {@code (baseX + ix*stride, baseZ + iz*stride)}.
     */
    void fill(float[] out, int baseX, int baseZ, int countX, int countZ, int stride);
}
