package com.openmason.ui.textureCreator.filters.noise;

import java.util.Random;

/**
 * Simplex noise generator - Ken Perlin's improved noise algorithm.
 */
public class SimplexNoiseGenerator implements NoiseGenerator {

    private final int[] permutation;
    private final int[] perm;

    // Skewing and unskewing factors for 2D simplex grid
    private static final float F2 = 0.5f * ((float) Math.sqrt(3.0) - 1.0f);
    private static final float G2 = (3.0f - (float) Math.sqrt(3.0)) / 6.0f;

    public SimplexNoiseGenerator(long seed) {
        this.permutation = generatePermutation(seed);
        this.perm = new int[512];

        // Duplicate permutation array for wrapping
        for (int i = 0; i < 512; i++) {
            perm[i] = permutation[i & 255];
        }
    }

    @Override
    public float generate(float x, float y) {
        float n0, n1, n2; // Noise contributions from the three corners

        // Skew the input space to determine which simplex cell we're in
        float s = (x + y) * F2;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);

        float t = (i + j) * G2;
        float X0 = i - t; // Unskew the cell origin back to (x,y) space
        float Y0 = j - t;
        float x0 = x - X0; // The x,y distances from the cell origin
        float y0 = y - Y0;

        // Determine which simplex we are in
        int i1, j1; // Offsets for second (middle) corner of simplex in (i,j) coords
        if (x0 > y0) {
            i1 = 1;
            j1 = 0; // Lower triangle, XY order: (0,0)->(1,0)->(1,1)
        } else {
            i1 = 0;
            j1 = 1; // Upper triangle, YX order: (0,0)->(0,1)->(1,1)
        }

        // Offsets for middle corner in (x,y) unskewed coords
        float x1 = x0 - i1 + G2;
        float y1 = y0 - j1 + G2;
        // Offsets for last corner in (x,y) unskewed coords
        float x2 = x0 - 1.0f + 2.0f * G2;
        float y2 = y0 - 1.0f + 2.0f * G2;

        // Work out the hashed gradient indices of the three simplex corners
        int ii = i & 255;
        int jj = j & 255;
        int gi0 = perm[ii + perm[jj]] % 12;
        int gi1 = perm[ii + i1 + perm[jj + j1]] % 12;
        int gi2 = perm[ii + 1 + perm[jj + 1]] % 12;

        // Calculate the contribution from the three corners
        float t0 = 0.5f - x0 * x0 - y0 * y0;
        if (t0 < 0) {
            n0 = 0.0f;
        } else {
            t0 *= t0;
            n0 = t0 * t0 * dot(grad3[gi0], x0, y0);
        }

        float t1 = 0.5f - x1 * x1 - y1 * y1;
        if (t1 < 0) {
            n1 = 0.0f;
        } else {
            t1 *= t1;
            n1 = t1 * t1 * dot(grad3[gi1], x1, y1);
        }

        float t2 = 0.5f - x2 * x2 - y2 * y2;
        if (t2 < 0) {
            n2 = 0.0f;
        } else {
            t2 *= t2;
            n2 = t2 * t2 * dot(grad3[gi2], x2, y2);
        }

        // Add contributions from each corner and scale to [0, 1]
        // The result is in range [-1, 1], so we normalize it
        return (70.0f * (n0 + n1 + n2) + 1.0f) * 0.5f;
    }

    private int[] generatePermutation(long seed) {
        Random random = new Random(seed);
        int[] p = new int[256];

        // Initialize with sequential values
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }

        // Shuffle using Fisher-Yates algorithm
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = p[i];
            p[i] = p[j];
            p[j] = temp;
        }

        return p;
    }

    private int fastFloor(float x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    private float dot(int[] g, float x, float y) {
        return g[0] * x + g[1] * y;
    }

    // Gradient vectors for 3D (but we only use x,y components for 2D)
    // These 12 vectors point to the midpoints of the edges of a cube
    private static final int[][] grad3 = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };

    @Override
    public String getName() {
        return "Simplex";
    }
}
