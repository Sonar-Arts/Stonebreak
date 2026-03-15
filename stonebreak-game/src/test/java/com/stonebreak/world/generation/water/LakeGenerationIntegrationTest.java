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
 *     <li>HeightSamplingBasinDetector performs radial height sampling with ring coverage validation</li>
 *     <li>Single-chunk unit tests cannot properly test large-scale basin detection</li>
 * </ul>
 */
class LakeGenerationIntegrationTest {

    private static final long TEST_SEED = 12345L;
    private static final int CHUNK_SIZE = 16;

    private WaterGenerationConfig config;
    private BasinWaterFiller waterFiller;
    private MockNoiseRouter noiseRouter;

    @BeforeEach
    void setUp() {
        // Use simplified config with custom parameters for synthetic terrain testing
        config = new WaterGenerationConfig(
            64,   // seaLevel
            65,   // basinMinimumElevation
            64,   // basinSearchRadius
            4,    // lowestPointSampleStep
            32,   // singleRingRadius
            16,   // ringSampleCount
            2,    // minimumRimDepth
            32,   // initialRingRadius
            16,   // ringRadiusIncrement (UPDATED from 8 to 16 for 128-block range)
            128,  // maxRingRadius (UPDATED from 64 to 128 for larger basins)
            7,    // maxDetectionAttempts (UPDATED from 5 to 7 for 128-block range)
            0.20f, // minimumMoisture
            0.2f,  // freezeTemperature
            0.03f, // elevationDecayRate
            256,   // waterGridResolution
            10_000, // maxGridCacheSize
            30,    // maxWaterDepth
            3,     // maxTerrainDropForWater
            true,  // enableEdgeDetection
            2.0f,  // maxBasinDepthVariance
            8,     // basinValidationRadius
            8,     // basinValidationSampleCount
            true,  // enableBasinValidation
            true,  // enableValleyRejection
            true   // enableWaterConnectivityCheck (NEW parameter)
        );

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
            64, 74,    // Heights: center=64 (lower for depth), rim=74 (depth=10)
            6, 70      // Radii: inner=6 (very small flat center), outer=70 (gradual slope)
        );
        waterFiller = new BasinWaterFiller(noiseRouter, terrainGen, config, TEST_SEED);

        // Generate region of chunks around basin
        Chunk[][] chunks = generateChunkRegion(6, 6); // 96x96 blocks centered around (128, 128)
        int[][][][] terrainHeights = generateTerrainHeights(chunks, terrainGen);
        MultiNoiseParameters[][][][] climate = generateClimate(chunks, 0.5f, 0.5f);

        // Fill water in all chunks
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                waterFiller.fillWaterBodies(
                    chunks[cx][cz],
                    terrainHeights[cx][cz],
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
                for (int y = 64; y <= 74; y++) {
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

        boolean foundWater = hasWaterInChunk(testChunk, 64, 74);
        assertTrue(foundWater, "Lake should generate in multi-chunk basin center (found " + waterCount + " water, " + iceCount + " ice)");

        System.out.println("✓ Lake generates in multi-chunk basin");
    }

    @Test
    void testIceFormsInColdMultiChunkBasin() {
        // Given: Large basin in cold climate
        LargeBasinTerrainGenerator terrainGen = new LargeBasinTerrainGenerator(
            128, 128,  // Center at grid (0, 0)
            64, 74,    // Heights: center=64 (lower for depth), rim=74
            6, 70      // Radii: inner=6 (very small flat center), outer=70
        );
        waterFiller = new BasinWaterFiller(noiseRouter, terrainGen, config, TEST_SEED);

        // Generate region with cold climate
        Chunk[][] chunks = generateChunkRegion(6, 6);
        int[][][][] terrainHeights = generateTerrainHeights(chunks, terrainGen);
        MultiNoiseParameters[][][][] climate = generateClimate(chunks, 0.15f, 0.5f); // Cold!

        // Fill water
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                waterFiller.fillWaterBodies(
                    chunks[cx][cz],
                    terrainHeights[cx][cz],
                    climate[cx][cz]
                );
            }
        }

        // Then: Ice should form on water surface
        boolean foundIce = hasIceInChunk(chunks[3][3], 64, 74);
        assertTrue(foundIce, "Ice should form in cold climate");

        System.out.println("✓ Ice forms in cold multi-chunk basin");
    }

    @Test
    void testBasinWithSingleLowOutlierGeneratesLake() {
        // Given: Basin with 1 anomalous low rim sample (e.g., narrow gap in rim)
        // This tests the outlier filtering - the low sample should be filtered out
        // and the lake should generate at the consensus height (74)
        BasinWithOutlierTerrainGenerator terrainGen = new BasinWithOutlierTerrainGenerator(
            128, 128,  // Center
            64, 74,    // Heights: center=64, normal rim=74
            70,        // Low outlier height at one angle
            6, 50      // Radii
        );
        waterFiller = new BasinWaterFiller(noiseRouter, terrainGen, config, TEST_SEED);

        // Generate chunks
        Chunk[][] chunks = generateChunkRegion(6, 6);
        int[][][][] terrainHeights = generateTerrainHeights(chunks, terrainGen);
        MultiNoiseParameters[][][][] climate = generateClimate(chunks, 0.5f, 0.5f);

        // Fill water
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                waterFiller.fillWaterBodies(
                    chunks[cx][cz],
                    terrainHeights[cx][cz],
                    climate[cx][cz]
                );
            }
        }

        // Then: Water should generate despite the low outlier
        // The outlier filter should remove the single low sample
        boolean foundWater = hasWaterInChunk(chunks[3][3], 64, 74);
        assertTrue(foundWater, "Lake should generate when single low outlier is filtered");

        System.out.println("✓ Basin with single low outlier generates lake (outlier filtered)");
    }

    @Test
    void testIrregularTerrainRejectsBasin() {
        // Given: Irregular terrain with many outliers (e.g., canyon, mesa)
        // This should fail the 75% threshold check (< 12/16 samples remain)
        // IMPORTANT: Center must be above sea level (>=66) to test basin detection
        IrregularTerrainGenerator terrainGen = new IrregularTerrainGenerator(
            128, 128,  // Center
            70,        // Base height (above sea level 64 and basin minimum 66)
            6, 50      // Radii
        );
        waterFiller = new BasinWaterFiller(noiseRouter, terrainGen, config, TEST_SEED);

        // Generate chunks
        Chunk[][] chunks = generateChunkRegion(6, 6);
        int[][][][] terrainHeights = generateTerrainHeights(chunks, terrainGen);
        MultiNoiseParameters[][][][] climate = generateClimate(chunks, 0.5f, 0.5f);

        // Fill water
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                waterFiller.fillWaterBodies(
                    chunks[cx][cz],
                    terrainHeights[cx][cz],
                    climate[cx][cz]
                );
            }
        }

        // Then: No basin water should generate (too irregular)
        // Check specifically for basin water (y > 66) since sea-level water is expected below 64
        boolean foundBasinWater = hasWaterInChunk(chunks[3][3], 66, 95);
        assertFalse(foundBasinWater, "Irregular terrain should reject basin (too many outliers)");

        System.out.println("✓ Irregular terrain rejects basin (< 75% threshold)");
    }

    @Test
    void testWaterDoesNotGenerateOnCliffEdges() {
        // Given: Basin with steep cliff on north side (10-block sudden drop)
        CliffEdgeTerrainGenerator terrainGen = new CliffEdgeTerrainGenerator(
            128, 128,  // Center
            64, 74,    // Heights: basin=64, rim=74
            50,        // Basin radius
            10         // Cliff drop (10-block sudden drop on north side)
        );
        waterFiller = new BasinWaterFiller(noiseRouter, terrainGen, config, TEST_SEED);

        // Generate chunks
        Chunk[][] chunks = generateChunkRegion(6, 6);
        int[][][][] terrainHeights = generateTerrainHeights(chunks, terrainGen);
        MultiNoiseParameters[][][][] climate = generateClimate(chunks, 0.5f, 0.5f);

        // Fill water
        for (int cx = 0; cx < 6; cx++) {
            for (int cz = 0; cz < 6; cz++) {
                waterFiller.fillWaterBodies(
                    chunks[cx][cz],
                    terrainHeights[cx][cz],
                    climate[cx][cz]
                );
            }
        }

        // Then: Verify water behavior
        // 1. Water SHOULD generate in basin interior (south side, gentle slope)
        Chunk basinChunk = chunks[3][4]; // South of center
        boolean hasWaterInBasin = hasWaterInChunk(basinChunk, 64, 74);
        assertTrue(hasWaterInBasin, "Water should generate in basin interior");

        // 2. Count water blocks that would create "walls" on cliff edges
        // These are water blocks with a large drop to neighboring columns (> 3 blocks)
        // Check chunks near cliff edge (north of center)
        Chunk cliffChunk = chunks[3][2]; // North of center, near cliff
        int[][][][] cliffTerrainHeights = generateTerrainHeights(new Chunk[][]{new Chunk[]{cliffChunk}}, terrainGen);
        int waterWallBlocks = 0;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Check if this column has water above sea level (basin water)
                boolean hasBasinWater = false;
                for (int y = 66; y <= 74; y++) {
                    if (cliffChunk.getBlock(x, y, z) == BlockType.WATER) {
                        hasBasinWater = true;
                        break;
                    }
                }

                if (hasBasinWater) {
                    // Check if neighboring columns have large height drops (cliff edge)
                    int terrainHeight = cliffTerrainHeights[0][0][x][z];
                    boolean isCliffEdge = false;

                    // Check 4 directions (if in bounds)
                    if (x > 0 && Math.abs(terrainHeight - cliffTerrainHeights[0][0][x-1][z]) > 4) isCliffEdge = true;
                    if (x < 15 && Math.abs(terrainHeight - cliffTerrainHeights[0][0][x+1][z]) > 4) isCliffEdge = true;
                    if (z > 0 && Math.abs(terrainHeight - cliffTerrainHeights[0][0][x][z-1]) > 4) isCliffEdge = true;
                    if (z < 15 && Math.abs(terrainHeight - cliffTerrainHeights[0][0][x][z+1]) > 4) isCliffEdge = true;

                    if (isCliffEdge) {
                        waterWallBlocks++;
                    }
                }
            }
        }

        // Edge detection should prevent most water walls (allow a few due to interpolation)
        System.out.printf("  Water wall blocks detected: %d%n", waterWallBlocks);
        assertTrue(waterWallBlocks < 10,
            String.format("Too many water walls (%d blocks) - edge detection may not be working", waterWallBlocks));

        System.out.println("✓ Water does not generate on cliff edges (edge detection working)");
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
     * Terrain generator that creates a basin with one low outlier rim sample.
     * This tests outlier filtering - the low sample should be removed.
     */
    private static class BasinWithOutlierTerrainGenerator implements TerrainGenerator {
        private final int centerX, centerZ;
        private final int centerHeight, normalRimHeight, outlierHeight;
        private final int innerRadius, outerRadius;

        public BasinWithOutlierTerrainGenerator(int centerX, int centerZ, int centerHeight,
                                               int normalRimHeight, int outlierHeight,
                                               int innerRadius, int outerRadius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.centerHeight = centerHeight;
            this.normalRimHeight = normalRimHeight;
            this.outlierHeight = outlierHeight;
            this.innerRadius = innerRadius;
            this.outerRadius = outerRadius;
        }

        @Override
        public int generateHeight(int x, int z, MultiNoiseParameters params) {
            int dx = x - centerX;
            int dz = z - centerZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.atan2(dz, dx);

            if (dist < innerRadius) {
                return centerHeight; // Basin center
            } else if (dist < outerRadius) {
                // Gradual slope to rim
                double factor = (dist - innerRadius) / (outerRadius - innerRadius);
                int targetRimHeight = normalRimHeight;

                // Create single low outlier at angle = 0 radians (east direction)
                // This simulates a narrow gap in the rim
                if (Math.abs(angle) < 0.2) { // ~11 degrees on either side
                    targetRimHeight = outlierHeight;
                }

                return (int)(centerHeight + (targetRimHeight - centerHeight) * factor);
            } else {
                // Outside rim: normal height, except for narrow gap
                if (Math.abs(angle) < 0.2) {
                    return outlierHeight; // Low outlier
                }
                return normalRimHeight; // Normal rim
            }
        }

        @Override public String getName() { return "BasinWithOutlierMock"; }
        @Override public String getDescription() { return "Basin with single low outlier"; }
        @Override public long getSeed() { return 0; }
        @Override public TerrainGeneratorType getType() { return TerrainGeneratorType.SPLINE; }
        @Override public HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z, MultiNoiseParameters params) { return null; }
    }

    /**
     * Terrain generator that creates highly irregular terrain (canyon, mesa).
     * Should fail the 75% outlier threshold with extreme height variations.
     */
    private static class IrregularTerrainGenerator implements TerrainGenerator {
        private final int centerX, centerZ;
        private final int baseHeight;
        private final int innerRadius, outerRadius;

        public IrregularTerrainGenerator(int centerX, int centerZ, int baseHeight,
                                        int innerRadius, int outerRadius) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.baseHeight = baseHeight;
            this.innerRadius = innerRadius;
            this.outerRadius = outerRadius;
        }

        @Override
        public int generateHeight(int x, int z, MultiNoiseParameters params) {
            int dx = x - centerX;
            int dz = z - centerZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double angle = Math.atan2(dz, dx);

            if (dist < innerRadius) {
                return baseHeight; // Center (70 for above sea level testing)
            } else if (dist < outerRadius) {
                // Create EXTREMELY irregular rim to trigger outlier filtering
                // Heights vary from 65 to 95 (30 block range!)
                // This creates > 50% outliers, failing the 75% threshold
                double factor = (dist - innerRadius) / (outerRadius - innerRadius);
                double segments = 16.0; // Match sample count
                double segmentIndex = (angle + Math.PI) / (2 * Math.PI) * segments;
                int segment = (int) segmentIndex;

                // Extreme variations: half very low (65-68), half very high (90-95)
                // This creates a "canyon" or "mesa" pattern that's clearly not a basin
                int[] heights = {65, 95, 67, 93, 66, 94, 68, 92, 65, 95, 67, 93, 66, 94, 68, 92};
                int targetHeight = heights[segment % 16];

                return (int)(baseHeight + (targetHeight - baseHeight) * factor);
            } else {
                // Outside: continue extreme irregular pattern
                double segments = 16.0;
                double angle2 = Math.atan2(z - centerZ, x - centerX);
                double segmentIndex = (angle2 + Math.PI) / (2 * Math.PI) * segments;
                int segment = (int) segmentIndex;
                int[] heights = {65, 95, 67, 93, 66, 94, 68, 92, 65, 95, 67, 93, 66, 94, 68, 92};
                return heights[segment % 16];
            }
        }

        @Override public String getName() { return "IrregularTerrainMock"; }
        @Override public String getDescription() { return "Irregular terrain (canyon/mesa)"; }
        @Override public long getSeed() { return 0; }
        @Override public TerrainGeneratorType getType() { return TerrainGeneratorType.SPLINE; }
        @Override public HeightCalculationDebugInfo getHeightCalculationDebugInfo(int x, int z, MultiNoiseParameters params) { return null; }
    }

    /**
     * Terrain generator that creates a basin with a steep cliff on one side.
     * Tests edge detection - water should not generate on the cliff face.
     *
     * <p>Basin has flat interior (64-66) with gentle slopes, but steep cliff on north side.</p>
     */
    private static class CliffEdgeTerrainGenerator implements TerrainGenerator {
        private final int centerX, centerZ;
        private final int basinHeight, rimHeight;
        private final int basinRadius;
        private final int cliffDrop;

        public CliffEdgeTerrainGenerator(int centerX, int centerZ, int basinHeight,
                                        int rimHeight, int basinRadius, int cliffDrop) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.basinHeight = basinHeight;
            this.rimHeight = rimHeight;
            this.basinRadius = basinRadius;
            this.cliffDrop = cliffDrop;
        }

        @Override
        public int generateHeight(int x, int z, MultiNoiseParameters params) {
            int dx = x - centerX;
            int dz = z - centerZ;
            double dist = Math.sqrt(dx * dx + dz * dz);

            // Flat basin interior (gentle slopes only)
            if (dist < basinRadius * 0.7) {
                // Very gentle slope in interior (max 1-2 block difference between neighbors)
                return basinHeight + (int)(dist / (basinRadius * 0.7) * 2); // Rises 2 blocks max
            }

            // Transition zone - slopes toward rim
            if (dist < basinRadius) {
                double factor = (dist - basinRadius * 0.7) / (basinRadius * 0.3);
                int height = basinHeight + 2 + (int)((rimHeight - basinHeight - 2) * factor);

                // Create steep cliff on north side (z < centerZ - 20)
                if (z < centerZ - 20) {
                    return height - cliffDrop; // Sudden drop
                }

                return height;
            }

            // Outside basin: rim height
            // But with cliff on north side
            if (z < centerZ - 20) {
                return rimHeight - cliffDrop; // Cliff face
            }

            return rimHeight; // Normal rim
        }

        @Override public String getName() { return "CliffEdgeTestMock"; }
        @Override public String getDescription() { return "Basin with steep cliff"; }
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
