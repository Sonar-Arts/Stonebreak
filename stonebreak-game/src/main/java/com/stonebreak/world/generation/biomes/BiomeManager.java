package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.StandardTerrain;
import com.stonebreak.world.generation.heightmap.DistilledShape;
import com.stonebreak.world.generation.heightmap.HeightMapGenerator;
import com.stonebreak.world.generation.noise.MultiNoiseSample;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Coordinates biome queries: samples the noise router at the shaped terrain height
 * (so altitude chill applies) and delegates selection to {@link BiomeSelector}.
 *
 * Terrain shape is decided upstream by {@link HeightMapGenerator}; this class only
 * decides which biome "skins" that shape.
 */
public class BiomeManager {
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;

    private final NoiseRouter noise;
    private final HeightMapGenerator heightMap;
    private final BiomeSelector selector;

    public BiomeManager(NoiseRouter noise, HeightMapGenerator heightMap) {
        this.noise = noise;
        this.heightMap = heightMap;
        this.selector = new BiomeSelector();
    }

    public BiomeType getBiome(int x, int z) {
        DistilledShape.Column c = new DistilledShape.Column();
        heightMap.column(x, z, c);
        MultiNoiseSample s = NoiseRouter.sampleOf(c, noise.temperature(x, z, c.shapedHeight), noise.moisture(x, z));
        return selector.select(s, isSubmerged(c.height));
    }

    /**
     * Selection from an already-assembled sample (batched paths).
     *
     * @param height the column's final surface height (with detail), Standard frame
     */
    public BiomeType selectBiome(MultiNoiseSample sample, int height) {
        return selector.select(sample, isSubmerged(height));
    }

    /** The water rule {@link HeightMapGenerator#waterLevel} applies: a surface under sea level is sea. */
    private static boolean isSubmerged(int height) {
        return height < StandardTerrain.SEA_LEVEL;
    }

    public float getMoisture(int x, int z) {
        return noise.moisture(x, z);
    }

    public float getTemperature(int x, int z) {
        return noise.temperature(x, z, heightMap.shapedHeight(x, z));
    }

    /**
     * Fills a 16x16 biome grid for the given chunk using per-cell pre-computed heights
     * so altitude chill is applied consistently with the surface. Indexed [x*16+z].
     */
    public void populateChunkBiomes(int chunkX, int chunkZ, int[] heights, BiomeType[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        int cells = CHUNK_SIZE * CHUNK_SIZE;
        // Batch-fill the shape and the climate (one SIMD call per channel on the native
        // backend), then apply the same per-point transforms as getBiome.
        DistilledShape.Column[] cols = new DistilledShape.Column[cells];
        float[] tRaw = new float[cells];
        float[] mRaw = new float[cells];
        heightMap.fillColumns(baseX, baseZ, CHUNK_SIZE, CHUNK_SIZE, 1, cols);
        noise.fillClimateChannels(baseX, baseZ, CHUNK_SIZE, CHUNK_SIZE, 1, tRaw, mRaw);
        for (int idx = 0; idx < cells; idx++) {
            MultiNoiseSample s = NoiseRouter.sampleOf(cols[idx],
                NoiseRouter.temperatureFromRaw(tRaw[idx], heights[idx]),
                NoiseRouter.moistureFromRaw(mRaw[idx]));
            out[idx] = selector.select(s, isSubmerged(heights[idx]));
        }
    }
}
