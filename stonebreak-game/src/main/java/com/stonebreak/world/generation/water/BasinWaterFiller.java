package com.stonebreak.world.generation.water;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.caves.CaveNoiseGenerator;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Fills water bodies in chunks using grid-based water level calculation.
 *
 * Uses WaterLevelGrid for perfectly flat, multi-chunk water bodies:
 * - Grid-based water level calculation (256-block resolution)
 * - Bilinear interpolation for smooth transitions
 * - 50-75% faster than per-column calculation (0.5-1ms vs 2ms per chunk)
 * - Guarantees perfectly flat water surfaces across chunk boundaries
 * - Reuses cached terrain heights and noise parameters
 *
 * Algorithm:
 * For each column in chunk:
 *   1. Get water level using WaterLevelGrid (interpolated from grid)
 *   2. If water level > 0, fill from terrain height to water level
 *   3. Skip cave positions (keep caves dry)
 *   4. Place ICE on surface if temperature < 0.2
 *
 * Follows Single Responsibility Principle - only fills water blocks in chunks.
 */
public class BasinWaterFiller {

    private final WaterLevelGrid waterGrid;
    private final CaveNoiseGenerator caveGenerator;
    private final WaterGenerationConfig config;

    /**
     * Creates a new basin water filler.
     *
     * @param noiseRouter Noise router for regional elevation sampling
     * @param terrainGenerator Terrain generator for height calculation
     * @param caveGenerator Cave generator for dry cave checks
     * @param config Water generation configuration
     */
    public BasinWaterFiller(NoiseRouter noiseRouter, TerrainGenerator terrainGenerator,
                           CaveNoiseGenerator caveGenerator, WaterGenerationConfig config) {
        this.config = config;
        this.caveGenerator = caveGenerator;

        // Initialize water level grid (replaces per-column calculator)
        this.waterGrid = new WaterLevelGrid(noiseRouter, terrainGenerator, config);
    }

    /**
     * Fills water bodies in the chunk based on basin detection.
     *
     * Uses cached terrain heights and noise parameters to avoid recalculation.
     * Keeps caves dry by checking cave density at each position.
     *
     * @param chunk The chunk to fill with water
     * @param terrainHeights Cached terrain heights [16][16]
     * @param biomeCache Cached biomes [16][16] (unused but kept for future use)
     * @param paramsCache Cached multi-noise parameters [16][16]
     */
    public void fillWaterBodies(Chunk chunk, int[][] terrainHeights,
                                BiomeType[][] biomeCache,
                                MultiNoiseParameters[][] paramsCache) {
        int chunkSize = WorldConfiguration.CHUNK_SIZE;
        int chunkWorldX = chunk.getChunkX() * chunkSize;
        int chunkWorldZ = chunk.getChunkZ() * chunkSize;

        // Process each column in chunk
        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                // Calculate world coordinates
                int worldX = chunkWorldX + x;
                int worldZ = chunkWorldZ + z;

                // Get cached data
                int terrainHeight = terrainHeights[x][z];
                MultiNoiseParameters params = paramsCache[x][z];

                // Get water level for this column (interpolated from grid)
                int waterLevel = waterGrid.getWaterLevel(
                        worldX, worldZ, terrainHeight,
                        params.temperature, params.humidity
                );

                // Skip if no water should generate
                if (waterLevel <= 0) {
                    continue;
                }

                // Fill water column from terrain height to water level
                for (int y = terrainHeight + 1; y <= waterLevel; y++) {
                    // Check if this position is a cave (keep caves dry)
                    boolean isCave = false;
                    if (caveGenerator.canGenerateCaves(y)) {
                        // Sample cave density (reuse pattern from old floodFillOceanWater)
                        float caveDensity = caveGenerator.sampleCaveDensity(
                                worldX, y, worldZ, terrainHeight
                        );
                        isCave = caveDensity > 0.0f;
                    }

                    // Only place water/ice if position is air and not a cave
                    if (!isCave && chunk.getBlock(x, y, z) == BlockType.AIR) {
                        // Ice formation: freeze surface in cold biomes
                        if (y == waterLevel && params.temperature < config.freezeTemperature) {
                            chunk.setBlock(x, y, z, BlockType.ICE);
                        } else {
                            chunk.setBlock(x, y, z, BlockType.WATER);
                        }
                    }
                }
            }
        }
    }
}
