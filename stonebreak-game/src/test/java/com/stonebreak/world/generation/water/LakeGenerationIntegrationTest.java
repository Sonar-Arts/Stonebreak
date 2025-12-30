package com.stonebreak.world.generation.water;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.debug.HeightCalculationDebugInfo;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for lake generation system using multi-chunk world regions.
 *
 * <p>Unlike unit tests, these tests generate larger world regions (5x5 chunks = 80x80 blocks)
 * to properly test the grid-based water generation system at production scale.</p>
 *
 * <p><strong>Why Integration Tests?</strong></p>
 * <ul>
 *     <li>Grid-based system calculates water at 256-block resolution</li>
 *     <li>RimDetector samples 17x17 grid (289 points) at 15-block spacing</li>
 *     <li>Single-chunk unit tests cannot properly test large-scale basin detection</li>
 * </ul>
 */
class LakeGenerationIntegrationTest {

    private static final long TEST_SEED = 12345L;
    private static final int REGION_SIZE_CHUNKS = 5; // 5x5 chunks = 80x80 blocks
    private static final int CHUNK_SIZE = 16;

    private WaterGenerationConfig config;
    private BasinWaterFiller waterFiller;
    private MockNoiseRouter noiseRouter;

    @BeforeEach
    void setUp() {
        // Use default config (production settings)
        config = new WaterGenerationConfig();

        // Create mock noise router
        noiseRouter = new MockNoiseRouter(TEST_SEED);
    }

    // ==================== Integration Tests ====================

    @Test
    void testLakeGeneratesInMultiChunkBasin() {
        // Given: Large basin spanning multiple chunks
        // - Basin center at world (128, 128) = grid (0, 0) center
        // - Basin radius: 50 blocks (spans ~6 chunks)
        // - Depth: 10 blocks (rim 75, center 65)
        // - Climate: Valid (temp=0.5, humidity=0.5)

        LargeBasinTerrainGenerator terrainGen = new LargeBasinTerrainGenerator(
            128, 128,  // Center at grid (0, 0)
            66, 76,    // Heights: center=66, rim=76 (depth=10)
            30, 50     // Radii: inner=30, outer=50
        );
        waterFiller = new BasinWaterFiller(noiseRouter, terrainGen, config, TEST_SEED);

        // Generate region of chunks around basin
        Chunk[][] chunks = generateChunkRegion(6, 6); // 96x96 blocks centered around (128, 128)
        int[][][][] terrainHeights = generateTerrainHeights(chunks, terrainGen);
        BiomeType[][][][] biomes = generateBiomes(chunks);
        MultiNoiseParameters[][][][] climate = generateClimate(chunks, 0.5f, 0.5f);

        // Fill water in all chunks
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                waterFiller.fillWaterBodies(
                    chunks[cx][cz],
                    terrainHeights[cx][cz],
                    biomes[cx][cz],
                    climate[cx][cz]
                );
            }
        }

        // Then: Water should be present in basin center chunks
        // Check chunks (3, 3) which is chunk (8, 8) = world (128-143, 128-143)
        Chunk testChunk = chunks[3][3];
        System.out.println("Testing chunk (" + testChunk.getChunkX() + ", " + testChunk.getChunkZ() + ")");
        System.out.println("  = world (" + (testChunk.getChunkX() * 16) + "-" + (testChunk.getChunkX() * 16 + 15) +
                           ", " + (testChunk.getChunkZ() * 16) + "-" + (testChunk.getChunkZ() * 16 + 15) + ")");

        // Debug: Print terrain heights and check for water
        int waterCount = 0;
        int iceCount = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 66; y <= 76; y++) {
                    BlockType block = testChunk.getBlock(x, y, z);
                    if (block == BlockType.WATER) waterCount++;
                    if (block == BlockType.ICE) iceCount++;
                }
            }
        }
        System.out.println("  Found " + waterCount + " water blocks, " + iceCount + " ice blocks");
        System.out.println("  Sample terrain heights at chunk:");
        System.out.print("  ");
        for (int x = 0; x < 16; x += 4) {
            for (int z = 0; z < 16; z += 4) {
                System.out.print(terrainHeights[3][3][x][z] + " ");
            }
        }
        System.out.println();

        boolean foundWater = hasWaterInChunk(testChunk, 66, 76);
        assertTrue(foundWater, "Lake should generate in multi-chunk basin center (found " + waterCount + " water, " + iceCount + " ice)");

        System.out.println("✓ Lake generates in multi-chunk basin");
    }

    @Test
    void testIceFormsInColdMultiChunkBasin() {
        // Given: Large basin in cold climate
        LargeBasinTerrainGenerator terrainGen = new LargeBasinTerrainGenerator(
            128, 128,  // Center at grid (0, 0)
            66, 76,    // Heights: center=66, rim=76
            30, 50     // Radii: inner=30, outer=50
        );
        waterFiller = new BasinWaterFiller(noiseRouter, terrainGen, config, TEST_SEED);

        // Generate region with cold climate
        Chunk[][] chunks = generateChunkRegion(6, 6);
        int[][][][] terrainHeights = generateTerrainHeights(chunks, terrainGen);
        BiomeType[][][][] biomes = generateBiomes(chunks);
        MultiNoiseParameters[][][][] climate = generateClimate(chunks, 0.15f, 0.5f); // Cold!

        // Fill water
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                waterFiller.fillWaterBodies(
                    chunks[cx][cz],
                    terrainHeights[cx][cz],
                    biomes[cx][cz],
                    climate[cx][cz]
                );
            }
        }

        // Then: Ice should form on water surface
        boolean foundIce = hasIceInChunk(chunks[3][3], 66, 76);
        assertTrue(foundIce, "Ice should form in cold climate");

        System.out.println("✓ Ice forms in cold multi-chunk basin");
    }

    // ==================== Helper Methods ====================

    private Chunk[][] generateChunkRegion(int width, int height) {
        Chunk[][] chunks = new Chunk[width][height];
        // Start at chunk (5, 5) to center around world (128, 128)
        // Chunk (5, 5) = world (80-95, 80-95)
        // Chunk (8, 8) = world (128-143, 128-143) ← contains grid center
        int startChunkX = 5;
        int startChunkZ = 5;

        for (int cx = 0; cx < width; cx++) {
            for (int cz = 0; cz < height; cz++) {
                chunks[cx][cz] = new Chunk(startChunkX + cx, startChunkZ + cz);
            }
        }
        return chunks;
    }

    private int[][][][] generateTerrainHeights(Chunk[][] chunks, TerrainGenerator terrainGen) {
        int width = chunks.length;
        int height = chunks[0].length;
        int[][][][] heights = new int[width][height][CHUNK_SIZE][CHUNK_SIZE];

        for (int cx = 0; cx < width; cx++) {
            for (int cz = 0; cz < height; cz++) {
                Chunk chunk = chunks[cx][cz];
                int chunkWorldX = chunk.getChunkX() * CHUNK_SIZE;
                int chunkWorldZ = chunk.getChunkZ() * CHUNK_SIZE;

                for (int x = 0; x < CHUNK_SIZE; x++) {
                    for (int z = 0; z < CHUNK_SIZE; z++) {
                        int worldX = chunkWorldX + x;
                        int worldZ = chunkWorldZ + z;

                        // Sample terrain generator
                        MultiNoiseParameters params = new MultiNoiseParameters(
                            0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f
                        );
                        int terrainHeight = terrainGen.generateHeight(worldX, worldZ, params);
                        heights[cx][cz][x][z] = terrainHeight;

                        // Set terrain blocks
                        for (int y = 0; y < terrainHeight; y++) {
                            chunk.setBlock(x, y, z, BlockType.STONE);
                        }
                    }
                }
            }
        }
        return heights;
    }

    private BiomeType[][][][] generateBiomes(Chunk[][] chunks) {
        int width = chunks.length;
        int height = chunks[0].length;
        BiomeType[][][][] biomes = new BiomeType[width][height][CHUNK_SIZE][CHUNK_SIZE];

        for (int cx = 0; cx < width; cx++) {
            for (int cz = 0; cz < height; cz++) {
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    for (int z = 0; z < CHUNK_SIZE; z++) {
                        biomes[cx][cz][x][z] = BiomeType.PLAINS;
                    }
                }
            }
        }
        return biomes;
    }

    private MultiNoiseParameters[][][][] generateClimate(Chunk[][] chunks, float temperature, float humidity) {
        int width = chunks.length;
        int height = chunks[0].length;
        MultiNoiseParameters[][][][] climate = new MultiNoiseParameters[width][height][CHUNK_SIZE][CHUNK_SIZE];

        MultiNoiseParameters params = new MultiNoiseParameters(
            0.5f, 0.0f, 0.0f, 0.0f, temperature, humidity
        );

        for (int cx = 0; cx < width; cx++) {
            for (int cz = 0; cz < height; cz++) {
                for (int x = 0; x < CHUNK_SIZE; x++) {
                    for (int z = 0; z < CHUNK_SIZE; z++) {
                        climate[cx][cz][x][z] = params;
                    }
                }
            }
        }
        return climate;
    }

    private boolean hasWaterInChunk(Chunk chunk, int minY, int maxY) {
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockType block = chunk.getBlock(x, y, z);
                    if (block == BlockType.WATER || block == BlockType.ICE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasIceInChunk(Chunk chunk, int minY, int maxY) {
        for (int x = 0; x < CHUNK_SIZE; x++) {
            for (int z = 0; z < CHUNK_SIZE; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (chunk.getBlock(x, y, z) == BlockType.ICE) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ==================== Mock Classes ====================

    /**
     * Terrain generator that creates a large basin for integration testing.
     */
    private static class LargeBasinTerrainGenerator implements TerrainGenerator {
        private final int centerX, centerZ;
        private final int centerHeight, rimHeight;
        private final int innerRadius, outerRadius;

        public LargeBasinTerrainGenerator(int centerX, int centerZ, int centerHeight,
                                          int rimHeight, int innerRadius, int outerRadius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.centerHeight = centerHeight;
            this.rimHeight = rimHeight;
            this.innerRadius = innerRadius;
            this.outerRadius = outerRadius;
        }

        @Override
        public int generateHeight(int x, int z, MultiNoiseParameters params) {
            int dx = x - centerX;
            int dz = z - centerZ;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist < innerRadius) {
                return centerHeight; // Basin center
            } else if (dist < outerRadius) {
                // Gradual slope to rim
                double factor = (dist - innerRadius) / (outerRadius - innerRadius);
                return (int)(centerHeight + (rimHeight - centerHeight) * factor);
            } else {
                return rimHeight; // Rim
            }
        }

        @Override public String getName() { return "LargeBasinMock"; }
        @Override public String getDescription() { return "Integration test basin"; }
        @Override public long getSeed() { return 0; }
        @Override public TerrainGeneratorType getType() { return TerrainGeneratorType.SPLINE; }
        @Override public HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z, MultiNoiseParameters params) { return null; }
    }

    /**
     * Mock noise router for integration tests.
     */
    private static class MockNoiseRouter extends NoiseRouter {
        public MockNoiseRouter(long seed) {
            super(seed, com.stonebreak.world.generation.config.TerrainGenerationConfig.defaultConfig());
        }

        @Override
        public MultiNoiseParameters sampleParameters(int x, int z, int y) {
            return new MultiNoiseParameters(0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f);
        }
    }
}
