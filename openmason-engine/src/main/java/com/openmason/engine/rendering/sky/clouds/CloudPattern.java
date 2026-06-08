package com.openmason.engine.rendering.sky.clouds;

import java.util.Random;

/**
 * Generates a fixed, deterministic, seamlessly-tileable cloud coverage grid for
 * the voxel cloud layer.
 *
 * <p>The grid is a square {@code boolean[size][size]} where {@code true} marks a
 * cell occupied by a cloud cuboid. It is produced by smoothing a field of random
 * values with several toroidal (wrap-around) blur passes, then thresholding
 * against a target coverage — yielding rounded blob formations.</p>
 *
 * <p>All neighbour queries wrap modulo {@code size}, so the grid is a torus:
 * tiling the resulting mesh edge-to-edge produces an unbroken cloud layer with
 * no visible seams. The pattern is generated once and never changes — clouds
 * drift via the renderer's model matrix, not by regenerating this grid.</p>
 */
public final class CloudPattern {

    private final int size;
    private final boolean[][] cells;

    /**
     * Builds a seamlessly-tileable cloud coverage grid.
     *
     * @param size      grid width/height in cells
     * @param coverage  fraction of sky covered, roughly 0.0 (clear) to 1.0 (overcast)
     * @param seed      seed for deterministic generation
     */
    public CloudPattern(int size, float coverage, long seed) {
        this.size = size;
        this.cells = new boolean[size][size];
        generate(coverage, seed);
    }

    /** @return grid width/height in cells. */
    public int getSize() {
        return size;
    }

    /**
     * @return {@code true} if the cell is occupied by a cloud. Coordinates are
     *         wrapped modulo {@code size}, so the grid behaves as a torus and
     *         the mesh built from it tiles seamlessly.
     */
    public boolean isCloud(int x, int z) {
        return cells[Math.floorMod(x, size)][Math.floorMod(z, size)];
    }

    private void generate(float coverage, long seed) {
        Random random = new Random(seed);
        float[][] field = new float[size][size];
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                field[x][z] = random.nextFloat();
            }
        }

        // Toroidal box-blur passes turn the white noise into smooth blobs while
        // keeping the field perfectly tileable.
        for (int pass = 0; pass < 4; pass++) {
            field = blurToroidal(field);
        }

        // Choose the threshold from the smoothed field so that roughly the
        // requested fraction of cells end up occupied.
        float threshold = percentile(field, 1.0f - coverage);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                cells[x][z] = field[x][z] > threshold;
            }
        }
    }

    private float[][] blurToroidal(float[][] src) {
        float[][] dst = new float[size][size];
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                float sum = 0.0f;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int sx = Math.floorMod(x + dx, size);
                        int sz = Math.floorMod(z + dz, size);
                        sum += src[sx][sz];
                    }
                }
                dst[x][z] = sum / 9.0f;
            }
        }
        return dst;
    }

    /** Returns the value at the given fraction (0..1) of the sorted field. */
    private float percentile(float[][] field, float fraction) {
        float[] flat = new float[size * size];
        int i = 0;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                flat[i++] = field[x][z];
            }
        }
        java.util.Arrays.sort(flat);
        int index = Math.max(0, Math.min(flat.length - 1,
                Math.round(fraction * (flat.length - 1))));
        return flat[index];
    }
}
