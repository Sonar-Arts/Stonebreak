package com.stonebreak.world.generation.water;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.generation.TerrainGenerator;
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
                // NOTE: Only applies to sea-level water; basin water trusts basin detection for containment
                if (!shouldPlaceWaterColumn(worldX, worldZ, terrainHeight, waterLevel,
                                           terrainHeights, x, z, isSeaLevel)) {
                    continue; // Skip this column - it's on a cliff edge
                }

                // Track water source type
                if (isSeaLevel) {
                    seaLevelColumns++;
                } else {
                    basinColumns++;
                }

                // NEW: Basin membership validation - check if column is actually inside the basin
                // This prevents water from filling columns outside the actual basin that get water
                // from grid interpolation artifacts (Phase 2 of water wall prevention)
                if (!isSeaLevel && config.enableBasinValidation) {
                    if (!isColumnInsideBasin(worldX, worldZ, waterLevel)) {
                        continue; // Column is outside basin - skip filling
                    }
                }

                // Fill water column from terrain height to water level
                // terrainHeight represents first air Y-coordinate, so start there (no +1 needed)
                // NEW: Per-block connectivity check to prevent floating water walls
                for (int y = terrainHeight; y <= waterLevel; y++) {
                    // Place water in any air block
                    if (chunk.getBlock(x, y, z) == BlockType.AIR) {
                        // NEW: Connectivity check - verify this water block has support
                        // Skip water placement if no support (land below or water/land adjacent)
                        if (config.enableWaterConnectivityCheck) {
                            if (!hasWaterSupport(chunk, worldX, worldZ, x, y, z, waterLevel, terrainHeights)) {
                                continue; // No support - leave as air (prevents floating water walls)
                            }
                        }

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
     * <p><strong>Basin Water Exception:</strong> Edge detection is SKIPPED for basin-detected water
     * because basin detection already validates containment via rim sampling. Only sea-level water
     * uses edge detection to prevent walls on ocean cliffs.</p>
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
     * @param isSeaLevel true if this is sea-level water, false if basin-detected water
     * @return true if water should be placed, false if it would create a water wall
     */
    private boolean shouldPlaceWaterColumn(int worldX, int worldZ, int terrainHeight,
                                          int waterLevel, int[][] terrainHeights,
                                          int localX, int localZ, boolean isSeaLevel) {
        // Early exit if edge detection disabled globally
        if (!config.enableEdgeDetection) {
            return true;
        }

        // REMOVED: Basin water bypass - edge detection now applies to BOTH sea-level AND basin water
        // This catches obvious cliff edges before the connectivity check

        // Sanity check - water level must be at or above terrain
        if (waterLevel < terrainHeight) {
            return false;
        }

        // Get ALL 8 neighboring terrain heights (N, S, E, W, NE, NW, SE, SW)
        int[] neighborHeights = getNeighborTerrainHeights8(worldX, worldZ, terrainHeights, localX, localZ);

        // Calculate maximum height drop to cardinal and diagonal neighbors
        int maxDrop = 0;
        int maxDiagonalDrop = 0;

        // Check cardinal neighbors (indices 0-3: N, S, E, W)
        for (int i = 0; i < 4; i++) {
            int drop = terrainHeight - neighborHeights[i]; // Positive if we're higher
            maxDrop = Math.max(maxDrop, drop);
        }

        // Check diagonal neighbors (indices 4-7: NE, NW, SE, SW)
        // Allow slightly larger drops on diagonals (1.5x threshold) since distance is sqrt(2) larger
        for (int i = 4; i < 8; i++) {
            int drop = terrainHeight - neighborHeights[i];
            maxDiagonalDrop = Math.max(maxDiagonalDrop, drop);
        }

        // Reject if any cardinal neighbor has steep drop
        if (maxDrop > config.maxTerrainDropForWater) {
            return false; // Water would create a "wall" on cliff face
        }

        // Reject if any diagonal neighbor has very steep drop (1.5x threshold)
        int diagonalThreshold = (int) Math.ceil(config.maxTerrainDropForWater * 1.5);
        if (maxDiagonalDrop > diagonalThreshold) {
            return false; // Diagonal cliff edge detected
        }

        // NEW: Depth-based rejection for basin water (prevents water walls from interpolation artifacts)
        // This filter catches cases where grid interpolation creates unrealistic water depth
        // by comparing actual depth to local terrain variation
        if (!isSeaLevel) {
            int depth = waterLevel - terrainHeight;

            // Calculate terrain variation (max - min of cardinal neighbors)
            int minNeighbor = Integer.MAX_VALUE;
            int maxNeighbor = Integer.MIN_VALUE;
            for (int i = 0; i < 4; i++) { // Cardinal neighbors only (indices 0-3: N, S, E, W)
                minNeighbor = Math.min(minNeighbor, neighborHeights[i]);
                maxNeighbor = Math.max(maxNeighbor, neighborHeights[i]);
            }
            int variation = maxNeighbor - minNeighbor;

            // Allow deeper water in high-variation basins (true basins have varied terrain)
            // Reject deep water in low-variation areas (likely interpolation artifacts)
            int maxAllowedDepth = (int) (config.maxWaterDepth + (variation * config.maxBasinDepthVariance));
            if (depth > maxAllowedDepth) {
                return false; // Likely interpolation artifact - water too deep for terrain variation
            }
        }

        return true; // All neighbors pass - water can be placed
    }

    /**
     * Gets terrain heights of ALL 8 neighboring columns (N, S, E, W, NE, NW, SE, SW).
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
     * @return Array of 8 neighbor heights [N, S, E, W, NE, NW, SE, SW]
     */
    private int[] getNeighborTerrainHeights8(int worldX, int worldZ, int[][] terrainHeights,
                                            int localX, int localZ) {
        int[] neighbors = new int[8];

        // Cardinal neighbors (N, S, E, W) - indices 0-3
        // North (z-1)
        if (localZ > 0) {
            neighbors[0] = terrainHeights[localX][localZ - 1];
        } else {
            neighbors[0] = calculateTerrainHeight(worldX, worldZ - 1);
        }

        // South (z+1)
        if (localZ < 15) {
            neighbors[1] = terrainHeights[localX][localZ + 1];
        } else {
            neighbors[1] = calculateTerrainHeight(worldX, worldZ + 1);
        }

        // East (x+1)
        if (localX < 15) {
            neighbors[2] = terrainHeights[localX + 1][localZ];
        } else {
            neighbors[2] = calculateTerrainHeight(worldX + 1, worldZ);
        }

        // West (x-1)
        if (localX > 0) {
            neighbors[3] = terrainHeights[localX - 1][localZ];
        } else {
            neighbors[3] = calculateTerrainHeight(worldX - 1, worldZ);
        }

        // Diagonal neighbors (NE, NW, SE, SW) - indices 4-7
        // NE (x+1, z-1)
        if (localX < 15 && localZ > 0) {
            neighbors[4] = terrainHeights[localX + 1][localZ - 1];
        } else {
            neighbors[4] = calculateTerrainHeight(worldX + 1, worldZ - 1);
        }

        // NW (x-1, z-1)
        if (localX > 0 && localZ > 0) {
            neighbors[5] = terrainHeights[localX - 1][localZ - 1];
        } else {
            neighbors[5] = calculateTerrainHeight(worldX - 1, worldZ - 1);
        }

        // SE (x+1, z+1)
        if (localX < 15 && localZ < 15) {
            neighbors[6] = terrainHeights[localX + 1][localZ + 1];
        } else {
            neighbors[6] = calculateTerrainHeight(worldX + 1, worldZ + 1);
        }

        // SW (x-1, z+1)
        if (localX > 0 && localZ < 15) {
            neighbors[7] = terrainHeights[localX - 1][localZ + 1];
        } else {
            neighbors[7] = calculateTerrainHeight(worldX - 1, worldZ + 1);
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
     * Validates if a column is actually inside a basin by sampling terrain around it.
     *
     * <p><strong>Basin Membership Validation Algorithm:</strong></p>
     * <ol>
     *     <li>Sample terrain heights in a ring around the column</li>
     *     <li>Count how many samples are at or above the water level (rim samples)</li>
     *     <li>Require majority (>= 50%) to be at/above water level</li>
     *     <li>Return true if containment validated, false if column is outside basin</li>
     * </ol>
     *
     * <p><strong>Purpose:</strong> Prevents water from filling columns outside the actual
     * basin that get water from grid interpolation artifacts. This is the second phase
     * of the water wall prevention system (after depth-based rejection).</p>
     *
     * <p><strong>Performance:</strong> Samples terrain at configurable radius/count
     * (default: 8 blocks, 8 samples = 8 terrain height calculations per call).
     * Only called for basin water columns that pass depth validation.</p>
     *
     * @param worldX World X coordinate of the column
     * @param worldZ World Z coordinate of the column
     * @param waterLevel Interpolated water level for this column
     * @return true if column is inside basin (majority of samples >= waterLevel), false otherwise
     */
    private boolean isColumnInsideBasin(int worldX, int worldZ, int waterLevel) {
        // NEW: Find which grid cell this column belongs to
        int gridX = Math.floorDiv(worldX, config.waterGridResolution);
        int gridZ = Math.floorDiv(worldZ, config.waterGridResolution);

        // NEW: Get basin metadata from grid cache for adaptive validation radius
        com.stonebreak.world.generation.water.basin.BasinDetectionResult metadata =
            basinWaterGrid.getBasinMetadata(gridX, gridZ);

        // Use adaptive radius if metadata available, otherwise fall back to config
        int radius;
        if (metadata.hasBasin()) {
            radius = metadata.getAdaptiveValidationRadius(); // Adaptive: scales 25-40% of detection radius
        } else {
            radius = config.basinValidationRadius; // Fallback to fixed 8 blocks
        }

        int sampleCount = config.basinValidationSampleCount;
        double angleStep = 2 * Math.PI / sampleCount;

        int rimSamples = 0;
        for (int i = 0; i < sampleCount; i++) {
            double angle = i * angleStep;
            int sampleX = worldX + (int) (radius * Math.cos(angle)); // Use adaptive radius
            int sampleZ = worldZ + (int) (radius * Math.sin(angle));

            int sampleHeight = calculateTerrainHeight(sampleX, sampleZ);
            if (sampleHeight >= waterLevel) {
                rimSamples++;
            }
        }

        // Require at least 50% of samples to be at/above water level (containment check)
        // This ensures the column is actually inside the basin, not just within grid interpolation area
        int requiredSamples = (int) Math.ceil(sampleCount * 0.5);
        return rimSamples >= requiredSamples;
    }

    /**
     * Checks if a water block at the given position would have support.
     * Water must either rest on land or connect to adjacent water/land.
     *
     * <p>This prevents "floating" water walls on cliff edges.</p>
     * <p>User suggestion: "if water at end of basin not touching water and/or land, leave as air"</p>
     *
     * <p><strong>Connectivity Algorithm:</strong></p>
     * <ol>
     *     <li><strong>Land Support:</strong> Check if block below is solid terrain</li>
     *     <li><strong>Horizontal Connectivity:</strong> Check 4 cardinal neighbors (N, S, E, W) for:
     *         <ul>
     *             <li>Existing water blocks (already placed)</li>
     *             <li>Land blocks at same Y level (basin floor support)</li>
     *             <li>Terrain below water level (neighbor will also have water)</li>
     *         </ul>
     *     </li>
     *     <li><strong>Cross-Chunk:</strong> Check neighbors in adjacent chunks via terrain height</li>
     * </ol>
     *
     * @param chunk The chunk being filled
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param localX Local chunk X coordinate (0-15)
     * @param localY Y coordinate to check
     * @param localZ Local chunk Z coordinate (0-15)
     * @param waterLevel The water level for this area
     * @param terrainHeights 2D array of terrain heights for this chunk
     * @return true if water has support (land below or water/land adjacent), false otherwise
     */
    private boolean hasWaterSupport(
            Chunk chunk,
            int worldX, int worldZ,
            int localX, int localY, int localZ,
            int waterLevel,
            int[][] terrainHeights
    ) {
        // Check 1: Land support (block below is solid terrain)
        // If this water block sits directly on terrain, it's supported
        if (localY > 0) {
            BlockType below = chunk.getBlock(localX, localY - 1, localZ);
            if (below != BlockType.AIR && below != BlockType.WATER) {
                return true; // Resting on solid land
            }
        } else {
            // At Y=0, check if terrain height indicates land below
            int terrainHeight = terrainHeights[localX][localZ];
            if (terrainHeight >= localY) {
                return true; // On terrain floor
            }
        }

        // Check 2: Horizontal connectivity (adjacent water or land at same Y level)
        // Check 4 cardinal neighbors (N, S, E, W)
        int[][] neighbors = {
            {0, -1},  // North (z-1)
            {0, 1},   // South (z+1)
            {-1, 0},  // West (x-1)
            {1, 0}    // East (x+1)
        };

        for (int[] offset : neighbors) {
            int nx = localX + offset[0];
            int nz = localZ + offset[1];

            // Neighbor within chunk bounds
            if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) {
                BlockType neighbor = chunk.getBlock(nx, localY, nz);

                // Connected to existing water
                if (neighbor == BlockType.WATER) {
                    return true;
                }

                // Connected to land at same Y level (basin floor support)
                if (neighbor != BlockType.AIR) {
                    return true;
                }

                // Check if neighbor position will get water (terrain below water level)
                int neighborTerrain = terrainHeights[nx][nz];
                if (neighborTerrain < waterLevel) {
                    return true; // Neighbor will also have water, this creates connectivity
                }
            } else {
                // Neighbor is in adjacent chunk - need to check cross-chunk
                // Calculate world coordinates for neighbor
                int nworldX = worldX + offset[0];
                int nworldZ = worldZ + offset[1];
                int neighborTerrain = calculateTerrainHeight(nworldX, nworldZ);

                if (neighborTerrain < waterLevel) {
                    return true; // Cross-chunk water connectivity
                }
            }
        }

        // No support found - this water block would be floating/unsupported
        return false;
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
