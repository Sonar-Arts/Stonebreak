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

    // Legacy calculator (deprecated, kept for A/B testing)
    @Deprecated(since = "Two-Tiered System", forRemoval = false)
    private final WaterLevelGrid legacyWaterGrid;

    private final WaterGenerationConfig config;

    // Feature flag for legacy mode (for testing/comparison)
    private final boolean useLegacyMode;

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
        this(noiseRouter, terrainGenerator, config, seed, false);
    }

    /**
     * Creates a new basin water filler with optional legacy mode.
     *
     * @param noiseRouter Noise router for parameter sampling
     * @param terrainGenerator Terrain generator for height calculation
     * @param config Water generation configuration
     * @param seed World seed for deterministic random
     * @param useLegacyMode If true, use legacy WaterLevelGrid (for A/B testing)
     */
    public BasinWaterFiller(NoiseRouter noiseRouter, TerrainGenerator terrainGenerator,
                           WaterGenerationConfig config, long seed, boolean useLegacyMode) {
        this.config = config;
        this.useLegacyMode = useLegacyMode;

        // Initialize two-tiered calculators
        this.seaLevelCalculator = new SeaLevelCalculator(config.seaLevel);
        this.basinWaterGrid = new BasinWaterLevelGrid(noiseRouter, terrainGenerator, config, seed);

        // Initialize legacy calculator (for comparison)
        this.legacyWaterGrid = new WaterLevelGrid(noiseRouter, terrainGenerator, config);
    }

    /**
     * Fills water bodies in the chunk using two-tiered system.
     *
     * <p>Uses cached terrain heights and noise parameters to avoid recalculation.
     * Places water in all air blocks up to water level.</p>
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

                // Get water level using two-tiered system (or legacy mode)
                int waterLevel;
                if (useLegacyMode) {
                    // Legacy mode - use old WaterLevelGrid
                    waterLevel = legacyWaterGrid.getWaterLevel(
                        worldX, worldZ, terrainHeight,
                        params.temperature, params.humidity
                    );
                } else {
                    // Two-tiered mode - try sea level first, then basin
                    waterLevel = calculateWaterLevelTwoTiered(
                        worldX, worldZ, terrainHeight,
                        params.temperature, params.humidity
                    );
                }

                // Skip if no water should generate
                if (waterLevel <= 0) {
                    continue;
                }

                // Fill water column from terrain height to water level
                // terrainHeight represents first air Y-coordinate, so start there (no +1 needed)
                for (int y = terrainHeight; y <= waterLevel; y++) {
                    // Place water in any air block
                    if (chunk.getBlock(x, y, z) == BlockType.AIR) {
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

    /**
     * Calculates water level using two-tiered system.
     *
     * <p><strong>Fast path:</strong> Try sea level first (O(1))</p>
     * <p><strong>Slow path:</strong> Try basin detection if no sea-level water</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param terrainHeight Terrain height at this column
     * @param temperature Temperature [0.0-1.0]
     * @param humidity Humidity [0.0-1.0]
     * @return Water level, or -1 if no water
     */
    private int calculateWaterLevelTwoTiered(int worldX, int worldZ, int terrainHeight,
                                             float temperature, float humidity) {
        // TIER 1: Sea level (fast path, O(1))
        int seaLevel = seaLevelCalculator.calculateSeaLevel(terrainHeight);
        if (seaLevel != -1) {
            return seaLevel; // Found sea-level water
        }

        // TIER 2: Basin detection (slower path, grid-based)
        int basinLevel = basinWaterGrid.getWaterLevel(
            worldX, worldZ, terrainHeight, temperature, humidity
        );
        return basinLevel; // May be -1 if no basin water
    }

    /**
     * Clears all water level caches.
     *
     * <p>Should be called when switching worlds or when memory pressure is high.</p>
     */
    public void clearCaches() {
        basinWaterGrid.clearCache();
        legacyWaterGrid.clearCache();
    }

    /**
     * Gets cache statistics for debugging.
     *
     * @return Cache statistics string
     */
    public String getCacheStats() {
        return String.format(
            "BasinWaterFiller Caches - Basin: %d points, Legacy: %d points",
            basinWaterGrid.getCacheSize(),
            legacyWaterGrid.getCacheSize()
        );
    }
}
