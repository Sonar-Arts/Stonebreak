package com.stonebreak.world.generation.biomes;

import com.stonebreak.world.generation.NoiseGenerator;
import com.stonebreak.world.generation.climate.ClimateRegionManager;
import com.stonebreak.world.generation.climate.ClimateRegionType;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Resolves biome from temperature and moisture noise, constrained by large-scale
 * climate regions. Temperature drops with altitude above sea level so mountain
 * peaks cool into snowy/tundra biomes regardless of base climate.
 */
public class BiomeManager {
    private static final int CHUNK_SIZE = WorldConfiguration.CHUNK_SIZE;
    private static final int SEA_LEVEL = WorldConfiguration.SEA_LEVEL;

    private static final float MOISTURE_SCALE = 1f / 1500f;
    private static final float TEMPERATURE_SCALE = 1f / 2000f;
    /** Blocks above sea level for a full (1.0) drop in temperature. */
    private static final float ALTITUDE_CHILL_FACTOR = 200f;

    private final NoiseGenerator moistureNoise;
    private final NoiseGenerator temperatureNoise;
    private final BiomeClassifier classifier;
    private final ClimateRegionManager climateRegionManager;

    public BiomeManager(long seed) {
        this(seed, new ClimateRegionManager(seed));
    }

    public BiomeManager(long seed, ClimateRegionManager climateRegionManager) {
        this.moistureNoise = new NoiseGenerator(seed, 6, 0.45, 2.1);
        this.temperatureNoise = new NoiseGenerator(seed + 1, 6, 0.4, 2.0);
        this.classifier = new BiomeClassifier();
        this.climateRegionManager = climateRegionManager;
    }

    public BiomeType getBiome(int x, int z) {
        return getBiomeAt(x, z, SEA_LEVEL);
    }

    public BiomeType getBiomeAt(int x, int z, int height) {
        float moisture = getMoisture(x, z);
        float temperature = getTemperatureAt(x, z, height);
        ClimateRegionType region = climateRegionManager.getRegion(x, z, temperature, moisture);
        return classifier.classifyWithFilter(temperature, moisture, region.getAllowedBiomes());
    }

    public float getMoisture(int x, int z) {
        return moistureNoise.noise(x * MOISTURE_SCALE + 100, z * MOISTURE_SCALE + 100) * 0.5f + 0.5f;
    }

    public float getTemperature(int x, int z) {
        return getTemperatureAt(x, z, SEA_LEVEL);
    }

    public float getTemperatureAt(int x, int z, int height) {
        float base = temperatureNoise.noise(x * TEMPERATURE_SCALE - 50, z * TEMPERATURE_SCALE - 50) * 0.5f + 0.5f;
        if (height > SEA_LEVEL) {
            base -= (height - SEA_LEVEL) / ALTITUDE_CHILL_FACTOR;
        }
        return Math.max(0f, Math.min(1f, base));
    }

    /**
     * Fills a 16x16 biome grid for the given chunk using per-cell base heights
     * so altitude chill is applied. Indexed [x*16+z].
     */
    public void populateChunkBiomes(int chunkX, int chunkZ, int[] baseHeights, BiomeType[] out) {
        int baseX = chunkX * CHUNK_SIZE;
        int baseZ = chunkZ * CHUNK_SIZE;
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                int idx = x * CHUNK_SIZE + z;
                out[idx] = getBiomeAt(baseX + x, baseZ + z, baseHeights[idx]);
            }
        }
    }
}
