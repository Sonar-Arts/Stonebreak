package com.stonebreak.world.generation.heightmap;

import com.openmason.engine.cenda.CendaKernels;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The batched noise fill must land each value at the block it belongs to.
 *
 * <p>{@link Density3D#prepareChunk} fills a whole chunk's noise volume in one SIMD call per
 * channel, then indexes into it per block. That mapping is fiddly and easy to get subtly
 * wrong — FastNoise2 iterates X innermost, and the code deliberately feeds it
 * {@code fnX = worldZ, fnY = worldX, fnZ = squashed Y} so the output lands in the game's
 * {@code [x*16+z]} order with no reshuffle. An axis swap, a doubled squash, or an off-by-one
 * in the Y origin all produce caves that look perfectly plausible and are simply in the
 * wrong place.
 *
 * <p>This asserts the mapping against single-point fills through the same kernel, which is
 * the only comparison that isolates it.
 *
 * <p>Note what this deliberately does <em>not</em> test: agreement between the native and
 * Java backends. Those use different simplex implementations (FastNoise2 vs the Gustavson
 * one in {@code NoiseGenerator}) and produce different terrain by design —
 * {@code TerrainNoise} says so when it selects a backend. Asserting they match would be
 * asserting something the project has decided is false.
 */
public class Density3DBackendParityTest {

    private static final int CHUNK = WorldConfiguration.CHUNK_SIZE;

    /** Must mirror Density3D's spaghetti channel exactly. */
    private static final int CAVE_FLOOR = 8;
    private static final float SCALE = 1f / 34f;
    private static final float Y_SQUASH = 1.25f;
    private static final int OCTAVES = 2;

    @Test
    public void batchedFillLandsEachValueAtItsOwnBlock() {
        long node = TerrainNoise.native3DNode(OCTAVES, 0.5, 2.0, SCALE);
        Assumptions.assumeTrue(node != 0L,
                "native noise backend unavailable (" + TerrainNoise.backend()
                        + ") — the batched fill cannot be exercised here");

        int seed = TerrainNoise.nativeSeed(331L);
        int chunkX = 3;
        int chunkZ = -2;
        int yCount = 96;

        float[] volume = new float[yCount * CHUNK * CHUNK];
        boolean ok = CendaKernels.fillGrid3D(node, volume,
                (float) (chunkZ * CHUNK), (float) (chunkX * CHUNK), CAVE_FLOOR * Y_SQUASH,
                CHUNK, CHUNK, yCount,
                1f, 1f, Y_SQUASH,
                seed);
        assertEquals(true, ok, "grid fill failed");

        // Corners and interior, plus both ends of the Y range — an axis swap survives a
        // single centre sample but not a corner.
        int[] xs = {0, 1, 7, 15};
        int[] zs = {0, 3, 15};
        int[] yIdxs = {0, 1, 40, yCount - 1};

        float[] one = new float[1];
        for (int lx : xs) {
            for (int lz : zs) {
                for (int yIdx : yIdxs) {
                    int y = CAVE_FLOOR + yIdx;
                    boolean okOne = CendaKernels.fillGrid3D(node, one,
                            (float) (chunkZ * CHUNK + lz),
                            (float) (chunkX * CHUNK + lx),
                            y * Y_SQUASH,
                            1, 1, 1,
                            1f, 1f, Y_SQUASH,
                            seed);
                    assertEquals(true, okOne, "single-point fill failed");

                    float batched = volume[(yIdx * CHUNK + lx) * CHUNK + lz];
                    assertEquals(one[0], batched, 1e-6f, String.format(
                            "batched fill disagrees at local (%d,%d,%d) -> world (%d,%d,%d)",
                            lx, y, lz, chunkX * CHUNK + lx, y, chunkZ * CHUNK + lz));
                }
            }
        }
        System.out.printf("[caves] batched fill mapping verified at %d points, backend=%s%n",
                xs.length * zs.length * yIdxs.length, TerrainNoise.backend());
    }
}
