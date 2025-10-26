package com.stonebreak.world.generation.heightmap;

import com.stonebreak.world.generation.config.NoiseConfig;
import com.stonebreak.world.operations.WorldConfiguration;

import java.util.Random;

/**
 * Generates 3D simplex noise for density-based terrain features.
 *
 * This noise type operates in 3D space (x, y, z) to determine if a block should
 * be solid or air, enabling overhangs, caves, arches, and floating terrain.
 *
 * Unlike 2D heightmap generation, 3D noise samples the density at every block position.
 * Positive density = solid block, negative density = air.
 *
 * Algorithm:
 * 1. Sample 3D simplex noise at world (x, y, z)
 * 2. Apply height bias (more solid below, more air above)
 * 3. Return density value (> 0.0 = solid, < 0.0 = air)
 *
 * Use Cases:
 * - Natural overhangs on cliffs
 * - Cave entrances and small caverns
 * - Natural arches and bridges
 * - Floating islands (rare)
 *
 * Performance Consideration:
 * 3D noise is expensive (~10x slower than 2D). Only sample in "transition zones"
 * near the surface (±10-20 blocks). Deep underground is always solid, high in
 * air is always air.
 *
 * Follows Single Responsibility Principle - only handles 3D noise generation.
 * Follows Dependency Inversion Principle - configuration injected via constructor.
 */
public class Noise3D {

    private final int octaves;
    private final double persistence;
    private final double lacunarity;
    private final int[] permutation;
    private final int seaLevel;

    /**
     * Creates a new 3D noise generator with the specified seed and configuration.
     *
     * @param seed   World seed for deterministic generation
     * @param config Noise configuration (octaves, persistence, lacunarity)
     */
    public Noise3D(long seed, NoiseConfig config) {
        this.octaves = config.getOctaves();
        this.persistence = config.getPersistence();
        this.lacunarity = config.getLacunarity();
        this.seaLevel = WorldConfiguration.SEA_LEVEL;

        // Initialize permutation table for simplex noise
        Random random = new Random(seed);
        permutation = new int[512];

        for (int i = 0; i < 256; i++) {
            permutation[i] = i;
        }

        // Shuffle
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256);
            int temp = permutation[i];
            permutation[i] = permutation[j];
            permutation[j] = temp;
        }

        // Duplicate for faster lookup
        System.arraycopy(permutation, 0, permutation, 256, 256);
    }

    /**
     * Gets the density value at a 3D world position.
     *
     * Density determines if a block should be solid or air:
     * - Positive density (> 0.0) → Solid block
     * - Negative density (< 0.0) → Air block
     *
     * Height bias is applied: blocks below sea level tend toward solid,
     * blocks above sea level tend toward air.
     *
     * @param x World X coordinate
     * @param y World Y coordinate
     * @param z World Z coordinate
     * @return Density value (> 0.0 = solid, < 0.0 = air)
     */
    public float getDensity(int x, int y, int z) {
        return getDensity(x, y, z, 60.0f);  // Default scale
    }

    /**
     * Gets the density value with custom noise scale.
     *
     * @param x     World X coordinate
     * @param y     World Y coordinate
     * @param z     World Z coordinate
     * @param scale Noise scale (larger = smoother features)
     * @return Density value (> 0.0 = solid, < 0.0 = air)
     */
    public float getDensity(int x, int y, int z, float scale) {
        // Sample 3D noise
        float density = (float) getFractalNoise3D(x / scale, y / scale, z / scale);

        // Apply height bias (more solid below sea level, more air above)
        float heightBias = (y - seaLevel) / 100.0f;
        density -= heightBias;

        return density;
    }

    /**
     * Combines multiple octaves of 3D simplex noise for natural terrain.
     */
    private double getFractalNoise3D(double x, double y, double z) {
        double total = 0;
        double frequency = 1;
        double amplitude = 1;
        double maxValue = 0;

        for (int i = 0; i < octaves; i++) {
            total += getSimplexNoise3D(x * frequency, y * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }

        return total / maxValue;
    }

    /**
     * Core 3D simplex noise implementation.
     *
     * Based on Ken Perlin's improved noise algorithm.
     * Returns values in range approximately [-1, 1].
     */
    private double getSimplexNoise3D(double x, double y, double z) {
        // Skew the input space to determine which simplex cell we're in
        final double F3 = 1.0 / 3.0;
        final double G3 = 1.0 / 6.0;

        double s = (x + y + z) * F3;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);
        int k = fastFloor(z + s);

        double t = (i + j + k) * G3;
        double X0 = i - t;
        double Y0 = j - t;
        double Z0 = k - t;

        double x0 = x - X0;
        double y0 = y - Y0;
        double z0 = z - Z0;

        // Determine which simplex we are in
        int i1, j1, k1;
        int i2, j2, k2;

        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1;
            }
        } else {
            if (y0 < z0) {
                i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1;
            } else if (x0 < z0) {
                i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1;
            } else {
                i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
            }
        }

        double x1 = x0 - i1 + G3;
        double y1 = y0 - j1 + G3;
        double z1 = z0 - k1 + G3;
        double x2 = x0 - i2 + 2.0 * G3;
        double y2 = y0 - j2 + 2.0 * G3;
        double z2 = z0 - k2 + 2.0 * G3;
        double x3 = x0 - 1.0 + 3.0 * G3;
        double y3 = y0 - 1.0 + 3.0 * G3;
        double z3 = z0 - 1.0 + 3.0 * G3;

        // Hash coordinates to get gradient indices
        int ii = i & 255;
        int jj = j & 255;
        int kk = k & 255;

        int gi0 = permutation[ii + permutation[jj + permutation[kk]]] % 12;
        int gi1 = permutation[ii + i1 + permutation[jj + j1 + permutation[kk + k1]]] % 12;
        int gi2 = permutation[ii + i2 + permutation[jj + j2 + permutation[kk + k2]]] % 12;
        int gi3 = permutation[ii + 1 + permutation[jj + 1 + permutation[kk + 1]]] % 12;

        // Calculate contributions from four corners
        double n0 = calculateCornerContribution(x0, y0, z0, gi0);
        double n1 = calculateCornerContribution(x1, y1, z1, gi1);
        double n2 = calculateCornerContribution(x2, y2, z2, gi2);
        double n3 = calculateCornerContribution(x3, y3, z3, gi3);

        // Sum contributions and scale
        return 32.0 * (n0 + n1 + n2 + n3);
    }

    private double calculateCornerContribution(double x, double y, double z, int gi) {
        double t = 0.6 - x * x - y * y - z * z;
        if (t < 0) {
            return 0.0;
        }
        t *= t;
        return t * t * dot(grad3[gi], x, y, z);
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }

    private static double dot(int[] g, double x, double y, double z) {
        return g[0] * x + g[1] * y + g[2] * z;
    }

    // Gradients for 3D simplex noise
    private static final int[][] grad3 = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };
}
