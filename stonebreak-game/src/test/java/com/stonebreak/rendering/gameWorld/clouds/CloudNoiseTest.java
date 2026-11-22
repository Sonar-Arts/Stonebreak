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
        // Neighbouring texels across the wrap must differ no more than neighbours inside it, in every channel.
        int size = 32;
        int n = CloudNoise.SHAPE_CHANNELS;
        byte[] v = CloudNoise.shape(size);
        for (int ch = 0; ch < n; ch++) {
            float inner = 0, seam = 0;
            for (int z = 0; z < size; z++) {
                for (int y = 0; y < size; y++) {
                    int row = (z * size + y) * size;
                    seam += Math.abs((v[(row + size - 1) * n + ch] & 0xFF) - (v[row * n + ch] & 0xFF));
                    inner += Math.abs((v[(row + size / 2 - 1) * n + ch] & 0xFF) - (v[(row + size / 2) * n + ch] & 0xFF));
                }
            }
            assertTrue(seam < inner * 1.5f, "channel " + ch + ": seam " + seam + " vs inner " + inner);
        }
    }

    @Test
    void valuesSpreadOverTheRange() {
        byte[] shape = CloudNoise.shape(32);
        int n = CloudNoise.SHAPE_CHANNELS;
        java.util.List<byte[]> channels = new java.util.ArrayList<>();
        for (int ch = 0; ch < n; ch++) {
            byte[] c = new byte[shape.length / n];
            for (int i = 0; i < c.length; i++) c[i] = shape[i * n + ch];
            channels.add(c);
        }
        channels.add(CloudNoise.detail(32));
        for (byte[] v : channels) {
            int[] histogram = new int[4];
            for (byte b : v) histogram[(b & 0xFF) / 64]++;
            for (int quarter : histogram) {
                assertTrue(quarter > v.length / 50, "a quarter of the range is nearly empty: "
                        + java.util.Arrays.toString(histogram));
            }
        }
    }

    @Test
    void turretsAreCoarserThanBillows() {
        // G is the same recipe an octave down: neighbouring texels differ less.
        int size = 32;
        byte[] v = CloudNoise.shape(size);
        float[] step = new float[2];
        for (int i = 0; i + 1 < size * size * size; i++) {
            if ((i + 1) % size == 0) continue;
            for (int ch = 0; ch < 2; ch++) {
                step[ch] += Math.abs((v[i * 2 + ch] & 0xFF) - (v[(i + 1) * 2 + ch] & 0xFF));
            }
        }
        assertTrue(step[1] < step[0] * 0.8f, "billows " + step[0] + " vs turrets " + step[1]);
    }

    @Test
    void curlTilesIsUnitBoundedAndDivergenceFree() {
        int size = 64;
        float[] c = CloudNoise.curl(size);
        float max = 0f, div = 0f, mag = 0f;
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                float x = c[(j * size + i) * 2], z = c[(j * size + i) * 2 + 1];
                max = Math.max(max, (float) Math.hypot(x, z));
                mag += Math.abs(x) + Math.abs(z);
                // central-difference divergence over the wrap (the field must tile seamlessly for this to hold at
                // the edges too)
                float dx = c[(j * size + Math.floorMod(i + 1, size)) * 2] - c[(j * size + Math.floorMod(i - 1, size)) * 2];
                float dz = c[(Math.floorMod(j + 1, size) * size + i) * 2 + 1]
                        - c[(Math.floorMod(j - 1, size) * size + i) * 2 + 1];
                div += Math.abs(dx + dz);
            }
        }
        assertEquals(1f, max, 1e-4f);
        assertTrue(div < mag * 0.05f, "divergence " + div + " vs magnitude " + mag);
    }

    @Test
    void deterministic() {
        assertArrayEquals(CloudNoise.shape(16), CloudNoise.shape(16));
    }
}
