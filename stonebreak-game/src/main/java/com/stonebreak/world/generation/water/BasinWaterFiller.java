package com.stonebreak.world.generation.water;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.noise.MultiNoiseParameters;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.water.basin.BasinWaterLevelGrid;
import com.stonebreak.world.generation.water.basin.SeaLevelCalculator;
import com.stonebreak.world.operations.WorldConfiguration;

/**
 * Fills water bodies in chunks using two-tiered water generation system.
 *
 * <p><strong>TWO-TIERED SYSTEM:</strong></p>
 * <ol>
 *     <li><strong>Sea Level (y &lt;= 64):</strong> Traditional ocean filling for terrain &lt; sea level</li>
 *     <li><strong>Basin Detection (y &gt;= 66):</strong> Elevated lakes/ponds via rim detection</li>
 * </ol>
 *
 * <p>Uses {@link SeaLevelCalculator} for fast ocean filling (O(1) per column).</p>
 * <p>Uses {@link BasinWaterLevelGrid} for sophisticated basin detection with:</p>
 * <ul>
 *     <li>Rim detection (finds lowest spillover point)</li>
 *     <li>Climate filtering (moisture, temperature)</li>
 *     <li>Elevation falloff (exponential decay for high basins)</li>
 *     <li>Grid-based calculation (256-block resolution)</li>
 *     <li>Bilinear interpolation for smooth water surfaces</li>
 * </ul>
 *
 * <p><strong>Algorithm:</strong></p>
 * <pre>
 * For each column in chunk:
 *   1. Try sea level calculator (fast path)
 *   2. If no sea-level water, try basin calculator (slower path)
 *   3. If water level &gt; 0, fill from terrain to water level
 *   4. Place WATER in air blocks, ICE on surface if temperature &lt; freezeTemperature
 * </pre>
 *
 * <p><strong>Design:</strong> Follows Single Responsibility Principle - only fills water blocks in chunks.</p>
 *
 * @see SeaLevelCalculator
 * @see BasinWaterLevelGrid
 */
public class BasinWaterFiller {

    // Two-tiered water calculators
    private final SeaLevelCalculator seaLevelCalculator;
    private final BasinWaterLevelGrid basinWaterGrid;

    private final WaterGenerationConfig config;
    private final TerrainGenerator terrainGenerator; // For edge detection height lookups

    /**
     * Creates a new basin water filler with two-tiered system.
     *
     * @param noiseRouter Noise router for parameter sampling
     * @param terrainGenerator Terrain generator for height calculation
     * @param config Water generation configuration
     * @param seed World seed for deterministic random
     */
    public BasinWaterFiller(NoiseRouter noiseRouter, TerrainGenerator terrainGenerator,
                           WaterGenerationConfig config, long seed) {
        this.config = config;
        this.terrainGenerator = terrainGenerator;

        // Initialize two-tiered calculators
        this.seaLevelCalculator = new SeaLevelCalculator(config.seaLevel);
        this.basinWaterGrid = new BasinWaterLevelGrid(noiseRouter, terrainGenerator, config, seed);
    }

    /**
     * Fills water bodies in the chunk using two-tiered system.
     *
     * <p>Uses cached terrain heights and noise parameters to avoid recalculation.
     * Places water in all air blocks up to water level.</p>
     *
     * @param chunk The chunk to fill with water
     * @param terrainHeights Cached terrain heights [16][16]
     * @param paramsCache Cached multi-noise parameters [16][16]
     */
    public void fillWaterBodies(Chunk chunk, int[][] terrainHeights,
                                MultiNoiseParameters[][] paramsCache) {
        int chunkSize = WorldConfiguration.CHUNK_SIZE;
        int chunkWorldX = chunk.getChunkX() * chunkSize;
        int chunkWorldZ = chunk.getChunkZ() * chunkSize;

        // Diagnostic counters for this chunk
        int waterBlocksPlaced = 0;
        int iceBlocksPlaced = 0;
        int seaLevelColumns = 0;
        int basinColumns = 0;

        // Process each column in chunk
        for (int x = 0; x < chunkSize; x++) {
            for (int z = 0; z < chunkSize; z++) {
                // Calculate world coordinates
                int worldX = chunkWorldX + x;
                int worldZ = chunkWorldZ + z;

                // Get cached data
                int terrainHeight = terrainHeights[x][z];
                MultiNoiseParameters params = paramsCache[x][z];

                // Get water level using two-tiered system: sea level (O(1)) + basin detection (grid-based)
                int waterLevel;
                boolean isSeaLevel = false;
                int seaLevelResult = seaLevelCalculator.calculateSeaLevel(terrainHeight);
                if (seaLevelResult != -1) {
                    waterLevel = seaLevelResult;
                    isSeaLevel = true;
                } else {
                    waterLevel = basinWaterGrid.getWaterLevel(
                        worldX, worldZ, terrainHeight,
                        params.temperature, params.humidity
                    );
                }

                // Skip if no water should generate
                if (waterLevel <= 0) {
                    continue;
                }

                // NEW: Edge detection - check if this column should have water
                // Prevents water walls on cliff edges by validating neighboring terrain
                if (!shouldPlaceWaterColumn(worldX, worldZ, terrainHeight, waterLevel,
                                           terrainHeights, x, z)) {
                    continue; // Skip this column - it's on a cliff edge
                }

                // Track water source type
                if (isSeaLevel) {
                    seaLevelColumns++;
                } else {
                    basinColumns++;
                }

                // Fill water column from terrain height to water level
                // terrainHeight represents first air Y-coordinate, so start there (no +1 needed)
                for (int y = terrainHeight; y <= waterLevel; y++) {
                    // Place water in any air block
                    if (chunk.getBlock(x, y, z) == BlockType.AIR) {
                        // Ice formation: freeze surface in cold biomes
                        if (y == waterLevel && params.temperature < config.freezeTemperature) {
                            chunk.setBlock(x, y, z, BlockType.ICE);
                            iceBlocksPlaced++;
                        } else {
                            chunk.setBlock(x, y, z, BlockType.WATER);
                            waterBlocksPlaced++;
                        }
                    }
                }
            }
        }

        // Log chunk summary if any water was placed
        if (waterBlocksPlaced > 0 || iceBlocksPlaced > 0) {
            System.out.printf("[LAKE-CHUNK] Chunk(%d,%d): %d water + %d ice blocks | %d sea-level + %d basin columns%n",
                chunk.getChunkX(), chunk.getChunkZ(),
                waterBlocksPlaced, iceBlocksPlaced,
                seaLevelColumns, basinColumns);
        }
    }

    /**
     * Checks if water should be placed at this column by validating neighboring terrain.
     * Prevents water walls on cliff edges by detecting steep terrain drops.
     *
     * <p><strong>Edge Detection Algorithm:</strong></p>
     * <ol>
     *     <li>Get terrain heights of 4 neighboring columns (N, S, E, W)</li>
     *     <li>Calculate maximum height difference to any neighbor</li>
     *     <li>Reject water if drop exceeds {@code config.maxTerrainDropForWater}</li>
     * </ol>
     *
     * <p><strong>Performance:</strong> 4 terrain height lookups per column.
     * Most lookups hit chunk-local array (fast). Chunk boundary lookups require terrain generator (slower).</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param terrainHeight Terrain height at this position
     * @param waterLevel Interpolated water level for this position
     * @param terrainHeights 2D array of terrain heights for chunk
     * @param localX Local X in chunk [0-15]
     * @param localZ Local Z in chunk [0-15]
     * @return true if water should be placed, false if it would create a water wall
     */
    private boolean shouldPlaceWaterColumn(int worldX, int worldZ, int terrainHeight,
                                          int waterLevel, int[][] terrainHeights,
                                          int localX, int localZ) {
        // Early exit if edge detection disabled
        if (!config.enableEdgeDetection) {
            return true;
        }

        // Sanity check - water level must be above terrain
        if (waterLevel < terrainHeight) {
            return false;
        }

        // Get 4 neighboring terrain heights (N, S, E, W)
        int[] neighborHeights = getNeighborTerrainHeights(worldX, worldZ, terrainHeights, localX, localZ);

        // Calculate maximum height drop to any neighbor
        int maxDrop = 0;
        for (int neighborHeight : neighborHeights) {
            int drop = terrainHeight - neighborHeight; // Positive if we're higher than neighbor
            maxDrop = Math.max(maxDrop, drop);
        }

        // Reject if terrain drops too steeply (cliff edge)
        return maxDrop <= config.maxTerrainDropForWater; // Water would create a "wall" on cliff face
    }

    /**
     * Gets terrain heights of 4 neighboring columns (N, S, E, W).
     * Handles chunk boundaries by sampling from terrain generator if needed.
     *
     * <p><strong>Optimization:</strong> Prioritizes chunk-local lookups (fast array access).
     * Only falls back to terrain generator for chunk boundaries (rare).</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param terrainHeights 2D array of terrain heights for current chunk [x][z]
     * @param localX Local X in chunk [0-15]
     * @param localZ Local Z in chunk [0-15]
     * @return Array of 4 neighbor heights [north, south, east, west]
     */
    private int[] getNeighborTerrainHeights(int worldX, int worldZ, int[][] terrainHeights,
                                           int localX, int localZ) {
        int[] neighbors = new int[4];

        // North (z-1)
        if (localZ > 0) {
            neighbors[0] = terrainHeights[localX][localZ - 1]; // Chunk-local
        } else {
            neighbors[0] = calculateTerrainHeight(worldX, worldZ - 1); // Chunk boundary
        }

        // South (z+1)
        if (localZ < 15) {
            neighbors[1] = terrainHeights[localX][localZ + 1]; // Chunk-local
        } else {
            neighbors[1] = calculateTerrainHeight(worldX, worldZ + 1); // Chunk boundary
        }

        // West (x-1)
        if (localX > 0) {
            neighbors[2] = terrainHeights[localX - 1][localZ]; // Chunk-local
        } else {
            neighbors[2] = calculateTerrainHeight(worldX - 1, worldZ); // Chunk boundary
        }

        // East (x+1)
        if (localX < 15) {
            neighbors[3] = terrainHeights[localX + 1][localZ]; // Chunk-local
        } else {
            neighbors[3] = calculateTerrainHeight(worldX + 1, worldZ); // Chunk boundary
        }

        return neighbors;
    }

    /**
     * Calculates terrain height at a world position using the terrain generator.
     *
     * <p>Used for chunk boundary lookups during edge detection. Assumes standard
     * noise parameters (0.5 continentalness, temperature, humidity).</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @return Terrain height (Y coordinate)
     */
    private int calculateTerrainHeight(int worldX, int worldZ) {
        com.stonebreak.world.generation.noise.MultiNoiseParameters params =
            new com.stonebreak.world.generation.noise.MultiNoiseParameters(
                0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f // Default parameters
            );
        return terrainGenerator.generateHeight(worldX, worldZ, params);
    }

    /**
     * Gets the basin water level grid for external systems (e.g., structure finding).
     *
     * @return The basin water level grid
     */
    public BasinWaterLevelGrid getBasinWaterLevelGrid() {
        return basinWaterGrid;
    }
}
