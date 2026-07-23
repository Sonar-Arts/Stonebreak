package com.openmason.engine.cenda;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the FFM binding against the real native library. Skipped when
 * libcenda_kernels.so hasn't been built (cmake --preset release && cmake
 * --build --preset release in openmason-engine/cenda).
 */
class CendaKernelsTest {

    @Test
    void gridFillIsDeterministicAndSeedSensitive() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        long node = CendaKernels.createSimplexFbm(4, 2.0f, 0.5f, 1f / 100f);
        assertTrue(node != 0L);
        try {
            float[] a = new float[16 * 16];
            float[] b = new float[16 * 16];
            float[] c = new float[16 * 16];
            assertTrue(CendaKernels.fillGrid2D(node, a, 0f, 0f, 16, 16, 1f, 1f, 1337));
            assertTrue(CendaKernels.fillGrid2D(node, b, 0f, 0f, 16, 16, 1f, 1f, 1337));
            assertTrue(CendaKernels.fillGrid2D(node, c, 0f, 0f, 16, 16, 1f, 1f, 42));
            assertArrayEquals(a, b, "same seed must reproduce exactly");
            assertFalse(java.util.Arrays.equals(a, c), "different seed must differ");
        } finally {
            CendaKernels.destroy(node);
        }
    }

    @Test
    void singleSampleMatchesGridCellExactly() {
        // The parity invariant the game's FastLOD/chunk split depends on:
        // a 1x1 fill at integer position (x, y) equals cell (x, y) of a batch fill.
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        long node = CendaKernels.createSimplexFbm(5, 2.0f, 0.45f, 1f / 260f);
        assertTrue(node != 0L);
        try {
            int baseX = -1234, baseY = 5678;
            float[] grid = new float[8 * 8];
            assertTrue(CendaKernels.fillGrid2D(node, grid, baseX, baseY, 8, 8, 1f, 1f, 99));
            float[] one = new float[1];
            for (int j = 0; j < 8; j++) {
                for (int i = 0; i < 8; i++) {
                    assertTrue(CendaKernels.fillGrid2D(node, one, baseX + i, baseY + j, 1, 1, 1f, 1f, 99));
                    assertEquals(grid[j * 8 + i], one[0],
                        "grid cell (" + i + "," + j + ") must equal its 1x1 fill bit-for-bit");
                }
            }
        } finally {
            CendaKernels.destroy(node);
        }
    }

    @Test
    void volumeFillWorks() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        long node = CendaKernels.createSimplexFbm(2, 2.0f, 0.5f, 1f / 26f);
        assertTrue(node != 0L);
        try {
            float[] vol = new float[16 * 16 * 32];
            assertTrue(CendaKernels.fillGrid3D(node, vol, 0f, 0f, 14.4f, 16, 16, 32, 1f, 1f, 1.8f, 7));
            boolean varied = false;
            for (float v : vol) {
                assertTrue(Float.isFinite(v));
                varied |= (v != vol[0]);
            }
            assertTrue(varied, "volume must not be constant");
        } finally {
            CendaKernels.destroy(node);
        }
    }

    @Test
    void invalidInputsAreRejected() {
        assumeTrue(CendaKernels.isAvailable(), "Cenda kernels not built");
        assertEquals(0L, CendaKernels.createSimplexFbm(0, 2.0f, 0.5f, 0.01f), "octaves<1 must fail");
        assertEquals(0L, CendaKernels.createSimplexFbm(4, 2.0f, 0.5f, 0f), "frequency<=0 must fail");
        assertEquals(0L, CendaKernels.createFromEncodedTree("not-a-real-tree!!"));
        assertFalse(CendaKernels.fillGrid2D(0L, new float[4], 0f, 0f, 2, 2, 1f, 1f, 1));
        assertNotEquals("unavailable", CendaKernels.simdLevel());
    }
}
