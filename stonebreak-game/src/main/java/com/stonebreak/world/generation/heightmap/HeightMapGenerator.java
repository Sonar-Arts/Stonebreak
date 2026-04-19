package com.stonebreak.world.generation.heightmap;

import com.stonebreak.util.SplineInterpolator;
import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Maps continentalness noise to terrain height via a spline.
 */
public class HeightMapGenerator {
    private static final int WORLD_HEIGHT = WorldConfiguration.WORLD_HEIGHT;
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final float CONTINENTALNESS_SCALE = 1f / 800f;

    private final NoiseGenerator continentalnessNoise;
    private final SplineInterpolator terrainSpline;

    public HeightMapGenerator(long seed) {
        this.continentalnessNoise = new NoiseGenerator(seed + 2);
        this.terrainSpline = new SplineInterpolator();
        terrainSpline.addPoint(-1.0, 70);
        terrainSpline.addPoint(-0.8, 20);
        terrainSpline.addPoint(-0.4, 60);
        terrainSpline.addPoint(-0.2, 70);
        terrainSpline.addPoint(0.1, 75);
        terrainSpline.addPoint(0.3, 120);
        terrainSpline.addPoint(0.7, 140);
        terrainSpline.addPoint(1.0, 200);
    }

    public int generateHeight(int x, int z) {
        int height = (int) terrainSpline.interpolate(getContinentalness(x, z));
        return Math.max(1, Math.min(height, WORLD_HEIGHT - 1));
    }

    public float getContinentalness(int x, int z) {
        return continentalnessNoise.noise(x * CONTINENTALNESS_SCALE, z * CONTINENTALNESS_SCALE);
    }

    /**
     * Fills a 16x16 height grid for the given chunk, indexed [x*16+z].
     */
    public void populateChunkHeights(int chunkX, int chunkZ, int[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                out[x * CHUNK_SIZE + z] = generateHeight(baseX + x, baseZ + z);
            }
        }
    }
}
