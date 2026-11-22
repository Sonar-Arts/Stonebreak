package com.stonebreak.rendering.gameWorld.clouds;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * The tileable noise the clouds are sculpted from, after the shape/detail
 * split of real-time cloud renderers (Schneider, "The Real-time Volumetric
 * Cloudscapes of Horizon Zero Dawn", SIGGRAPH 2015):
 * <ul>
 *   <li>{@link #shape}: Perlin-Worley — Perlin noise with Worley cells pressed
 *       into it. Worley's inverted cells are round and clumped, the billows of
 *       a cumulus; the Perlin keeps them connected into one cloud body. Two
 *       channels, the same recipe one octave apart: R = billows (4 cells per
 *       tile), G = turrets twice as large (2 cells). Real cumulus are fractal,
 *       a big cloud's lumps are big, so the shader mixes G in with a column's
 *       height: a storm tower is built of turrets, a fair-weather cumulus of
 *       billows.</li>
 *   <li>{@link #detail}: Worley fBm, fine enough to fray a cloud's edges into
 *       wisps at its base and cauliflower at its top.</li>
 *   <li>{@link #curl}: 2D curl noise (divergence-free), which bends the detail
 *       lookup so frayed edges swirl like turbulent air instead of pitting in
 *       round cells.</li>
 * </ul>
 * Each channel is stretched over the full byte range [0, 255] (the raw
 * noises bunch up), and repeats with period
 * {@code size} on every axis (lattices wrap), so a GL_REPEAT texture has no
 * seams. Pure arithmetic and deterministic, so it is testable and every
 * client sculpts identical clouds.
 */
final class CloudNoise {

    private CloudNoise() {}

    /** Channels of {@link #shape}. */
    static final int SHAPE_CHANNELS = 2;

    /**
     * Perlin-Worley shape noise, {@code size}³ texels of {@value #SHAPE_CHANNELS} bytes (R = billows, G = turrets),
     * x fastest then y then z.
     */
    static byte[] shape(int size) {
        byte[] billows = build(size, (x, y, z) -> perlinWorley(x, y, z, 4));
        byte[] turrets = build(size, (x, y, z) -> perlinWorley(x, y, z, 2));
        byte[] out = new byte[billows.length * SHAPE_CHANNELS];
        for (int i = 0; i < billows.length; i++) {
            out[i * SHAPE_CHANNELS] = billows[i];
            out[i * SHAPE_CHANNELS + 1] = turrets[i];
        }
        return out;
    }

    /**
     * Perlin-Worley with Worley cells {@code cells} per tile side: the Worley cells set the floor the Perlin rises
     * from, then the gaps of Worley twice as fine eat into it, so billows stay round instead of smeared.
     */
    static float perlinWorley(float x, float y, float z, int cells) {
        float perlin = 0.5f + 0.5f * (perlin(x, y, z, cells) * 0.5f
                + perlin(x, y, z, cells * 2) * 0.3f + perlin(x, y, z, cells * 4) * 0.2f);
        float pw = remap(Math.clamp(perlin, 0f, 1f), 0f, 1f, worleyFbm(x, y, z, cells), 1f);
        return pw - 0.4f * (1f - worleyFbm(x, y, z, cells * 2));
    }

    /** Worley fBm detail noise, {@code size}³ bytes. */
    static byte[] detail(int size) {
        return build(size, (x, y, z) -> worleyFbm(x, y, z, 4));
    }

    /**
     * 2D curl noise, {@code size}² texels of (x, z) pairs in [-1, 1] (x fastest): the curl of a tiling Perlin
     * potential (two octaves, 4 and 8 cells per tile), so it swirls without sources or sinks, scaled so its
     * largest magnitude is 1.
     */
    static float[] curl(int size) {
        float[] psi = new float[size * size];
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                float x = (i + 0.5f) / size, z = (j + 0.5f) / size;
                psi[j * size + i] = perlin(x, 0.37f, z, 4) + 0.5f * perlin(x, 0.71f, z, 8);
            }
        }
        float[] out = new float[size * size * 2];
        float max = 1e-6f;
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                float dx = psi[j * size + Math.floorMod(i + 1, size)] - psi[j * size + Math.floorMod(i - 1, size)];
                float dz = psi[Math.floorMod(j + 1, size) * size + i] - psi[Math.floorMod(j - 1, size) * size + i];
                // curl of a scalar potential in the plane: (d/dz, -d/dx)
                out[(j * size + i) * 2] = dz;
                out[(j * size + i) * 2 + 1] = -dx;
                max = Math.max(max, (float) Math.sqrt(dx * dx + dz * dz));
            }
        }
        for (int k = 0; k < out.length; k++) out[k] /= max;
        return out;
    }

    @FunctionalInterface
    private interface Field {
        /** Value at a point in [0, 1)³ of the tile. */
        float at(float x, float y, float z);
    }

    /** Share of texels clipped at each end when stretching a volume to the byte range. */
    private static final float CLIP = 0.01f;

    private static byte[] build(int size, Field field) {
        float[] values = new float[size * size * size];
        IntStream.range(0, size).parallel().forEach(z -> {
            float fz = (z + 0.5f) / size;
            for (int y = 0; y < size; y++) {
                float fy = (y + 0.5f) / size;
                int row = (z * size + y) * size;
                for (int x = 0; x < size; x++) {
                    values[row + x] = field.at((x + 0.5f) / size, fy, fz);
                }
            }
        });
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        float lo = sorted[(int) (CLIP * (sorted.length - 1))];
        float hi = sorted[(int) ((1f - CLIP) * (sorted.length - 1))];
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            float v = Math.clamp(remap(values[i], lo, hi, 0f, 1f), 0f, 1f);
            out[i] = (byte) Math.round(v * 255f);
        }
        return out;
    }

    /** Three octaves of inverted Worley noise, the coarsest with {@code cells} per side. */
    static float worleyFbm(float x, float y, float z, int cells) {
        return worley(x, y, z, cells) * 0.625f
                + worley(x, y, z, cells * 2) * 0.25f
                + worley(x, y, z, cells * 4) * 0.125f;
    }

    /**
     * Inverted F1 Worley noise on a lattice of {@code cells}³ cells wrapping over
     * the unit tile: 1 at a feature point, falling to 0 a cell away.
     */
    static float worley(float x, float y, float z, int cells) {
        float px = x * cells, py = y * cells, pz = z * cells;
        int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
        float best = Float.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int ix = cx + dx, iy = cy + dy, iz = cz + dz;
                    int h = hash(Math.floorMod(ix, cells), Math.floorMod(iy, cells), Math.floorMod(iz, cells), cells);
                    float fx = ix + unit(h) - px;
                    float fy = iy + unit(h * 0x27D4EB2F) - py;
                    float fz = iz + unit(h * 0x165667B1) - pz;
                    best = Math.min(best, fx * fx + fy * fy + fz * fz);
                }
            }
        }
        return 1f - Math.min((float) Math.sqrt(best), 1f);
    }

    /** Gradient noise in about [-1, 1] on a lattice of {@code period}³ cells wrapping over the unit tile. */
    static float perlin(float x, float y, float z, int period) {
        float px = x * period, py = y * period, pz = z * period;
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py), z0 = (int) Math.floor(pz);
        float fx = px - x0, fy = py - y0, fz = pz - z0;
        float u = fade(fx), v = fade(fy), w = fade(fz);
        float result = 0f;
        for (int c = 0; c < 8; c++) {
            int ox = c & 1, oy = (c >> 1) & 1, oz = (c >> 2) & 1;
            int h = hash(Math.floorMod(x0 + ox, period), Math.floorMod(y0 + oy, period),
                    Math.floorMod(z0 + oz, period), period);
            float g = grad(h, fx - ox, fy - oy, fz - oz);
            result += g * (ox == 1 ? u : 1f - u) * (oy == 1 ? v : 1f - v) * (oz == 1 ? w : 1f - w);
        }
        return result;
    }

    /** Dot with one of the 12 cube-edge gradients (Perlin's improved noise). */
    private static float grad(int hash, float x, float y, float z) {
        int h = (hash >>> 4) % 12;
        float a = h < 8 ? x : y;
        float b = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? a : -a) + ((h & 2) == 0 ? b : -b);
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    /** Lattice hash; {@code salt} keeps octaves of different periods uncorrelated. */
    private static int hash(int x, int y, int z, int salt) {
        int h = x * 0x8DA6B343 ^ y * 0xD8163841 ^ z * 0xCB1AB31F ^ salt * 0x61C88647;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return h;
    }

    /** A hash's top 24 bits as a float in [0, 1). */
    private static float unit(int h) {
        return (h >>> 8) * (1f / (1 << 24));
    }

    static float remap(float v, float lo, float hi, float newLo, float newHi) {
        return newLo + (v - lo) / (hi - lo) * (newHi - newLo);
    }
}
