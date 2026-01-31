package com.openmason.main.systems.menus.textureCreator.filters.noise;

import java.util.Random;

/**
 * Value noise generator - simpler than Perlin noise but still produces smooth patterns.
 * Uses random values at integer coordinates and interpolates between them.
 */
public class ValueNoiseGenerator implements NoiseGenerator {

    private final Random random;
    private final float[][] valueGrid;
    private final int gridSize;

    public ValueNoiseGenerator(long seed) {
        this.random = new Random(seed);
        this.gridSize = 256;
        this.valueGrid = generateValueGrid();
    }

    @Override
    public float generate(float x, float y) {
        // Find grid cell coordinates
        int x0 = ((int) Math.floor(x)) & (gridSize - 1);
        int y0 = ((int) Math.floor(y)) & (gridSize - 1);
        int x1 = (x0 + 1) & (gridSize - 1);
        int y1 = (y0 + 1) & (gridSize - 1);

        // Find relative position in cell
        float xf = x - (float) Math.floor(x);
        float yf = y - (float) Math.floor(y);

        // Apply smoothstep interpolation
        float u = smoothstep(xf);
        float v = smoothstep(yf);

        // Get corner values
        float v00 = valueGrid[x0][y0];
        float v10 = valueGrid[x1][y0];
        float v01 = valueGrid[x0][y1];
        float v11 = valueGrid[x1][y1];

        // Bilinear interpolation
        float v0 = lerp(v00, v10, u);
        float v1 = lerp(v01, v11, u);

        return lerp(v0, v1, v);
    }

    private float[][] generateValueGrid() {
        float[][] grid = new float[gridSize][gridSize];
        for (int x = 0; x < gridSize; x++) {
            for (int y = 0; y < gridSize; y++) {
                grid[x][y] = random.nextFloat();
            }
        }
        return grid;
    }

    private float smoothstep(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    @Override
    public String getName() {
        return "Value";
    }
}
