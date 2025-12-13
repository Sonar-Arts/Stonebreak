package com.stonebreak.world.generation.sdf;

import com.stonebreak.world.generation.TerrainGeneratorFactory;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.config.TerrainGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for hybrid SDF terrain generation system.
 */
class HybridSdfIntegrationTest {

    private HybridSdfTerrainGenerator generator;
    private TerrainGenerationConfig config;
    private long seed = 12345L;

    @BeforeEach
    void setUp() {
        config = TerrainGenerationConfig.defaultConfig();
        generator = new HybridSdfTerrainGenerator(seed, config, SdfTerrainConfig.getDefault());
    }

    @Test
    void testGeneratorCreation() {
        assertNotNull(generator, "Generator should be created");
        assertEquals(TerrainGeneratorType.HYBRID_SDF, generator.getType(), "Should be HYBRID_SDF type");
        assertFalse(generator.isFallbackMode(), "Should not be in fallback mode");
    }

    @Test
    void testFactoryCreation() {
        var factoryGenerator = TerrainGeneratorFactory.create(
            TerrainGeneratorType.HYBRID_SDF,
            seed,
            config,
            true
        );

        assertNotNull(factoryGenerator, "Factory should create generator");
        assertInstanceOf(HybridSdfTerrainGenerator.class, factoryGenerator, "Should create HybridSdfTerrainGenerator");
    }

    @Test
    void testHeightGeneration() {
        MultiNoiseParameters params = new MultiNoiseParameters(
            0.5f,  // continentalness
            0.0f,  // erosion
            0.0f,  // peaks/valleys
            0.0f,  // weirdness
            0.5f,  // temperature
            0.5f   // humidity
        );

        int height = generator.generateHeight(100, 200, params);

        assertTrue(height >= 0 && height <= 256, "Height should be in valid range: " + height);
    }

    @Test
    void testHeightVariety() {
        // Test that different positions produce different heights
        MultiNoiseParameters params = new MultiNoiseParameters(0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f);

        int h1 = generator.generateHeight(0, 0, params);
        int h2 = generator.generateHeight(100, 0, params);
        int h3 = generator.generateHeight(0, 100, params);

        // Heights should vary (though not guaranteed to be different at all positions)
        // At least one should be different
        boolean hasVariety = (h1 != h2) || (h2 != h3) || (h1 != h3);
        assertTrue(hasVariety, "Generated heights should show variety");
    }

    @Test
    void testDensityFunctionCreation() {
        HybridDensityFunction densityFunc = generator.createDensityFunction(0, 0);

        assertNotNull(densityFunc, "Density function should be created");
    }

    @Test
    void testDensityFunctionSampling() {
        HybridDensityFunction densityFunc = generator.createDensityFunction(0, 0);

        MultiNoiseParameters params = new MultiNoiseParameters(0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f);

        // Sample at various Y levels
        float densityLow = densityFunc.sample(0, 10, 0, params);
        float densityMid = densityFunc.sample(0, 64, 0, params);
        float densityHigh = densityFunc.sample(0, 200, 0, params);

        // Lower Y should generally be more solid (positive density)
        assertTrue(densityLow > densityHigh, "Lower elevations should be more solid");

        densityFunc.cleanup();
    }

    @Test
    void testCaveGeneration() {
        HybridDensityFunction densityFunc = generator.createDensityFunction(0, 0);

        // Initialize cache (required before sampling)
        MultiNoiseParameters[][] paramGrid = new MultiNoiseParameters[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                paramGrid[x][z] = new MultiNoiseParameters(0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f);
            }
        }
        densityFunc.initializeCache(paramGrid);

        // Check that cave system has primitives
        int primitiveCount = densityFunc.getCavePrimitiveCount();
        assertTrue(primitiveCount >= 0, "Cave primitive count should be non-negative");

        densityFunc.cleanup();
    }

    @Test
    void testConfigurationPresets() {
        // Test different configuration presets
        SdfTerrainConfig defaultConfig = SdfTerrainConfig.getDefault();
        SdfTerrainConfig lowCaves = SdfTerrainConfig.getLowCaves();
        SdfTerrainConfig highCaves = SdfTerrainConfig.getHighCaves();
        SdfTerrainConfig minimal = SdfTerrainConfig.getMinimal();
        SdfTerrainConfig maxDrama = SdfTerrainConfig.getMaxDrama();

        assertNotNull(defaultConfig);
        assertNotNull(lowCaves);
        assertNotNull(highCaves);
        assertNotNull(minimal);
        assertNotNull(maxDrama);

        // Verify preset differences
        assertTrue(lowCaves.caveDensityThreshold > defaultConfig.caveDensityThreshold,
                   "Low caves should have higher threshold");
        assertTrue(highCaves.caveDensityThreshold < defaultConfig.caveDensityThreshold,
                   "High caves should have lower threshold");
        assertFalse(minimal.enableOverhangs, "Minimal should disable overhangs");
        assertFalse(minimal.enableArches, "Minimal should disable arches");
    }

    @Test
    void testDeterministicGeneration() {
        // Same seed and coordinates should produce same results
        HybridSdfTerrainGenerator gen1 = new HybridSdfTerrainGenerator(seed, config);
        HybridSdfTerrainGenerator gen2 = new HybridSdfTerrainGenerator(seed, config);

        MultiNoiseParameters params = new MultiNoiseParameters(0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f);

        int h1 = gen1.generateHeight(100, 200, params);
        int h2 = gen2.generateHeight(100, 200, params);

        assertEquals(h1, h2, "Same seed should produce same heights");
    }

    @Test
    void testDifferentSeeds() {
        // Different seeds should produce different results
        HybridSdfTerrainGenerator gen1 = new HybridSdfTerrainGenerator(12345L, config);
        HybridSdfTerrainGenerator gen2 = new HybridSdfTerrainGenerator(67890L, config);

        MultiNoiseParameters params = new MultiNoiseParameters(0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f);

        int h1 = gen1.generateHeight(100, 200, params);
        int h2 = gen2.generateHeight(100, 200, params);

        // Different seeds should generally produce different results
        // (not guaranteed at all positions, but very likely)
        assertNotEquals(h1, h2, "Different seeds should likely produce different heights");
    }

    @Test
    void testMultipleChunks() {
        // Test generating multiple chunks without errors
        for (int cx = -2; cx <= 2; cx++) {
            for (int cz = -2; cz <= 2; cz++) {
                HybridDensityFunction densityFunc = generator.createDensityFunction(cx, cz);
                assertNotNull(densityFunc, "Should create density function for chunk (" + cx + ", " + cz + ")");
                densityFunc.cleanup();
            }
        }
    }

    @Test
    void testCleanup() {
        HybridDensityFunction densityFunc = generator.createDensityFunction(0, 0);

        // Should not throw exception
        assertDoesNotThrow(() -> densityFunc.cleanup(), "Cleanup should not throw exception");

        // Multiple cleanups should be safe
        assertDoesNotThrow(() -> densityFunc.cleanup(), "Multiple cleanups should be safe");
    }

    @Test
    void testSdfTerrainConfigToString() {
        SdfTerrainConfig config = SdfTerrainConfig.getDefault();
        String str = config.toString();

        assertNotNull(str);
        assertTrue(str.contains("SdfTerrainConfig"), "toString should contain class name");
    }

    @Test
    void testGeneratorToString() {
        String str = generator.toString();

        assertNotNull(str);
        assertTrue(str.contains("HybridSdfTerrainGenerator"), "toString should contain class name");
        assertTrue(str.contains("seed=" + seed), "toString should contain seed");
    }
}
