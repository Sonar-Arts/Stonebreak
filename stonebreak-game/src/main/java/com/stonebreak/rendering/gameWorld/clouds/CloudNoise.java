package com.stonebreak.rendering.gameWorld.clouds;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * The tileable 3D noise the clouds are sculpted from, after the shape/detail
 * split of real-time cloud renderers (Schneider, "The Real-time Volumetric
 * Cloudscapes of Horizon Zero Dawn"):
 * <ul>
 *   <li>{@link #shape}: Perlin-Worley — Perlin noise with Worley cells pressed
 *       into it. Worley's inverted cells are round and clumped, the billows of
 *       a cumulus; the Perlin keeps them connected into one cloud body.</li>
 *   <li>{@link #detail}: Worley fBm, fine enough to fray a cloud's edges into
 *       wisps at its base and cauliflower at its top.</li>
 * </ul>
 * Each volume is stretched over the full byte range [0, 255] (the raw
 * noises bunch up), and repeats with period
 * {@code size} on every axis (lattices wrap), so a GL_REPEAT texture has no
 * seams. Pure arithmetic and deterministic, so it is testable and every
 * client sculpts identical clouds.
 */
final class CloudNoise {

    private CloudNoise() {}

    /** Perlin-Worley shape noise, {@code size}³ bytes, x fastest then y then z. */
    static byte[] shape(int size) {
        return build(size, (x, y, z) -> {
            float perlin = 0.5f + 0.5f * (perlin(x, y, z, 4) * 0.5f
                    + perlin(x, y, z, 8) * 0.3f + perlin(x, y, z, 16) * 0.2f);
            float cells = worleyFbm(x, y, z, 4);
            // Perlin-Worley: the Worley cells set the floor the Perlin rises from.
            float pw = remap(Math.clamp(perlin, 0f, 1f), 0f, 1f, cells, 1f);
            // Eaten into by the gaps of finer Worley, so billows stay round instead of smeared.
            return pw - 0.4f * (1f - worleyFbm(x, y, z, 8));
        });
    }

    /** Worley fBm detail noise, {@code size}³ bytes. */
    static byte[] detail(int size) {
        return build(size, (x, y, z) -> worleyFbm(x, y, z, 4));
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
