package com.stonebreak.rendering.gameWorld.clouds;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud noise repeats across the sky as a GL_REPEAT texture, so a seam in
 * it would draw a straight line through every cloud; and the shader erodes it
 * against coverage, so values bunched at one end would leave clouds either
 * solid blobs or nothing.
 */
class CloudNoiseTest {

    @Test
    void noiseTilesWithoutSeams() {
        for (int cells : new int[]{4, 8, 16}) {
            for (float[] p : new float[][]{{0.01f, 0.3f, 0.7f}, {0.42f, 0.99f, 0.05f}, {0.8f, 0.2f, 0.6f}}) {
                float x = p[0], y = p[1], z = p[2];
                assertEquals(CloudNoise.worley(x, y, z, cells), CloudNoise.worley(x + 1f, y, z, cells), 1e-4f);
                assertEquals(CloudNoise.worley(x, y, z, cells), CloudNoise.worley(x, y - 1f, z + 1f, cells), 1e-4f);
                assertEquals(CloudNoise.perlin(x, y, z, cells), CloudNoise.perlin(x + 1f, y + 1f, z, cells), 1e-4f);
                assertEquals(CloudNoise.perlin(x, y, z, cells), CloudNoise.perlin(x, y, z - 1f, cells), 1e-4f);
            }
        }
    }

    @Test
    void opposingFacesOfTheVolumeMeet() {
        // Neighbouring texels across the wrap must differ no more than neighbours inside it.
        int size = 32;
        byte[] v = CloudNoise.shape(size);
        float inner = 0, seam = 0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                int row = (z * size + y) * size;
                seam += Math.abs((v[row + size - 1] & 0xFF) - (v[row] & 0xFF));
                inner += Math.abs((v[row + size / 2 - 1] & 0xFF) - (v[row + size / 2] & 0xFF));
            }
        }
        assertTrue(seam < inner * 1.5f, "seam " + seam + " vs inner " + inner);
    }

    @Test
    void valuesSpreadOverTheRange() {
        for (byte[] v : new byte[][]{CloudNoise.shape(32), CloudNoise.detail(32)}) {
            int[] histogram = new int[4];
            for (byte b : v) histogram[(b & 0xFF) / 64]++;
            for (int quarter : histogram) {
                assertTrue(quarter > v.length / 50, "a quarter of the range is nearly empty: "
                        + java.util.Arrays.toString(histogram));
            }
        }
    }

    @Test
    void deterministic() {
        assertArrayEquals(CloudNoise.shape(16), CloudNoise.shape(16));
    }
}
