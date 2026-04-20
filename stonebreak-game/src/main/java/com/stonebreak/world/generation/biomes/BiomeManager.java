package com.stonebreak.world.generation.biomes;

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
        return selector.select(noise.sample(x, z, heightMap.shapedHeight(x, z)));
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
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                MultiNoiseSample s = noise.sample(baseX + x, baseZ + z, heights[idx]);
                out[idx] = selector.select(s);
            }
        }
    }
}
