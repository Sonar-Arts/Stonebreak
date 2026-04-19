package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Resolves biome from temperature and moisture noise.
 */
public class BiomeManager {
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final float MOISTURE_SCALE = 1f / 200f;
    private static final float TEMPERATURE_SCALE = 1f / 300f;

    private final NoiseGenerator moistureNoise;
    private final NoiseGenerator temperatureNoise;
    private final BiomeClassifier classifier;

    public BiomeManager(long seed) {
        this.moistureNoise = new NoiseGenerator(seed);
        this.temperatureNoise = new NoiseGenerator(seed + 1);
        this.classifier = new BiomeClassifier();
    }

    public BiomeType getBiome(int x, int z) {
        return classifier.classify(getTemperature(x, z), getMoisture(x, z));
    }

    public float getMoisture(int x, int z) {
        return moistureNoise.noise(x * MOISTURE_SCALE + 100, z * MOISTURE_SCALE + 100) * 0.5f + 0.5f;
    }

    public float getTemperature(int x, int z) {
        return temperatureNoise.noise(x * TEMPERATURE_SCALE - 50, z * TEMPERATURE_SCALE - 50) * 0.5f + 0.5f;
    }

    /**
     * Fills a 16x16 biome grid for the given chunk, indexed [x*16+z].
     */
    public void populateChunkBiomes(int chunkX, int chunkZ, BiomeType[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                out[x * CHUNK_SIZE + z] = getBiome(baseX + x, baseZ + z);
            }
        }
    }

}
