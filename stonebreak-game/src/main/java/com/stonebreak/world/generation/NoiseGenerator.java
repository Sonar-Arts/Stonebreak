package com.stonebreak.world.generation;

import com.stonebreak.world.generation.config.NoiseConfig;

import java.util.Random;

/**
 * Implements a Simplex Noise generator for terrain generation.
 * Now supports configurable noise parameters for different use cases.
 *
 * Phase 1 Enhancement: Accepts NoiseConfig to customize octaves, persistence, and lacunarity.
 * This allows different noise characteristics for continentalness, erosion, detail, etc.
 *
 * Implements INoiseGenerator for dependency inversion and testability.
 */
public class NoiseGenerator implements INoiseGenerator {

    private final int octaves;
    private final double persistence;
    private final double lacunarity;

    private final int[] permutation;
    @SuppressWarnings("unused")
    private final int seed;

    /**
     * Creates a noise generator with specified configuration.
     *
     * @param seed World seed for deterministic generation
     * @param config Noise parameters (octaves, persistence, lacunarity)
     */
    public NoiseGenerator(long seed, NoiseConfig config) {
        this.seed = (int) (seed % Integer.MAX_VALUE);
        this.octaves = config.getOctaves();
        this.persistence = config.getPersistence();
        this.lacunarity = config.getLacunarity();

        Random random = new Random(seed);
        permutation = new int[512];
        
        // Initialize permutation array
        for (int i = 0; i < 256; i++) {
            permutation[i] = i;
        }
        
        // Shuffle permutation array
        for (int i = 0; i < 256; i++) {
            int j = random.nextInt(256);
            int temp = permutation[i];
            permutation[i] = permutation[j];
            permutation[j] = temp;
        }
        
        // Duplicate permutation for faster lookup
        System.arraycopy(permutation, 0, permutation, 256, 256);
    }
      /**
     * Gets noise value in 2D space.
     * @return Noise value in the range [-1, 1]
     */
    @Override
    public float noise(float x, float y) {
        return (float) getFractalNoise(x, y);
    }

    /**
     * Sample 3D noise by layering 2D noise in different planes
     * This is a simplified 3D noise implementation using 2D simplex noise
     *
     * @param x X coordinate
     * @param y Y coordinate (height)
     * @param z Z coordinate
     * @return Noise value in range [-1, 1]
     */
    public float noise3D(float x, float y, float z) {
        // Layer three 2D noise samples from different planes
        // This creates pseudo-3D noise that's cheaper than true 3D simplex
        float xy = noise(x, y);
        float xz = noise(x, z);
        float yz = noise(y, z);

        // Blend the three planes together
        return (xy + xz + yz) / 3.0f;
    }
    
    /**
     * Combines multiple octaves of simplex noise for more natural terrain.
     * Uses configurable octaves, persistence, and lacunarity values.
     */
    private double getFractalNoise(float x, float y) {
        double total = 0;
        double frequency = 1;
        double amplitude = 1;
        double maxValue = 0;

        for (int i = 0; i < octaves; i++) {
            total += getSimplexNoise(x * frequency, y * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }

        // Normalize the result
        return total / maxValue;
    }    /**
     * Gets simplex noise in 2D space.
     * @return Noise value in the range [-1, 1]
     */
    private double getSimplexNoise(double x, double y) {
        try {
            // Noise contributions from the three corners
            double n0, n1, n2;
            
            // Skew the input space to determine which simplex cell we're in
            final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
            double s = (x + y) * F2; // Hairy factor for 2D
            int i = fastFloor(x + s);
            int j = fastFloor(y + s);
        
        final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
        double t = (i + j) * G2;
        double X0 = i - t; // Unskew the cell origin back to (x,y) space
        double Y0 = j - t;
        double x0 = x - X0; // The x,y distances from the cell origin
        double y0 = y - Y0;
        
        // For the 2D case, the simplex shape is an equilateral triangle.
        // Determine which simplex we are in.
        int i1, j1; // Offsets for second (middle) corner of simplex in (i,j) coords
        if (x0 > y0) {
            // Lower triangle, XY order: (0,0)->(1,0)->(1,1)
            i1 = 1;
            j1 = 0;
        } else {
            // Upper triangle, YX order: (0,0)->(0,1)->(1,1)
            i1 = 0;
            j1 = 1;
        }
        
        // A step of (1,0) in (i,j) means a step of (1-c,-c) in (x,y), and
        // a step of (0,1) in (i,j) means a step of (-c,1-c) in (x,y), where
        // c = (3-sqrt(3))/6
        double x1 = x0 - i1 + G2; // Offsets for middle corner in (x,y) unskewed coords
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2; // Offsets for last corner in (x,y) unskewed coords
        double y2 = y0 - 1.0 + 2.0 * G2;
        
        // Work out the hashed gradient indices of the three simplex corners
        int ii = i & 255;
        int jj = j & 255;
        int gi0 = permutation[ii + permutation[jj]] % 12;
        int gi1 = permutation[ii + i1 + permutation[jj + j1]] % 12;
        int gi2 = permutation[ii + 1 + permutation[jj + 1]] % 12;
          // Calculate the contribution from the three corners
        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if (t0 < 0) {
            n0 = 0.0;
        } else {
            t0 *= t0;
            n0 = t0 * t0 * dot(grad3[gi0], x0, y0); // (x,y) of grad3 used for 2D gradient
        }
        
        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if (t1 < 0) {
            n1 = 0.0;
        } else {
            t1 *= t1;
            n1 = t1 * t1 * dot(grad3[gi1], x1, y1);
        }
        
        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if (t2 < 0) {
            n2 = 0.0;
        } else {
            t2 *= t2;
            n2 = t2 * t2 * dot(grad3[gi2], x2, y2);
        }
              // Add contributions from each corner to get the final noise value.
            // The result is scaled to return values in the interval [-1,1].
            return 70.0 * (n0 + n1 + n2);
        } catch (Exception e) {
            System.err.println("Error generating simplex noise: " + e.getMessage());
            return 0.0;  // Return a safe default
        }
    }
    
    // Helper functions
    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }
      private static double dot(int[] g, double x, double y) {
        return g[0] * x + g[1] * y;
    }
    
    // Gradients for 2D. They approximate the directions to the
    // vertices of a hexagon from the center.
    private static final int[][] grad3 = {
        {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
        {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
        {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };
}
