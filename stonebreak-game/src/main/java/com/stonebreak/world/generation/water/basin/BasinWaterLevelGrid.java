package com.stonebreak.world.generation.water.basin;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.water.GridPosition;
import com.stonebreak.world.generation.utils.GridInterpolation;
import com.stonebreak.world.generation.utils.TerrainCalculations;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simplified grid-based basin water level calculator for elevated lakes/ponds.
 *
 * <p>Only operates on terrain >= basinMinimumElevation (66 default).
 * Uses simple single-ring sampling to detect basins.</p>
 *
 * <p><strong>Key Features (Simplified):</strong></p>
 * <ul>
 *     <li>Single-ring sampling (32-block radius, 16 samples) - 75% fewer samples than before</li>
 *     <li>Climate filtering (humidity and temperature checks)</li>
 *     <li>Elevation probability (exponential decay via {@link ElevationProbabilityCalculator})</li>
 *     <li>Grid-based calculation (256-block resolution default)</li>
 *     <li>Bilinear interpolation for smooth water surfaces</li>
 *     <li>LRU cache eviction (prevents unbounded growth)</li>
 * </ul>
 *
 * <p><strong>Algorithm:</strong></p>
 * <pre>
 * For each column:
 *   1. Early exit if terrain &lt; 66 or bad climate
 *   2. Calculate grid cell coordinates
 *   3. Get/interpolate 4 corner grid values (LRU cached)
 *   4. Each grid value calculated via:
 *      - Simple single-ring sampling (16 samples)
 *      - Depth validation (>= 2 blocks)
 *      - Elevation probability (exponential decay)
 *   5. Bilinear interpolation -> final water level
 * </pre>
 *
 * <p><strong>Design:</strong> Follows KISS and SOLID principles - removed over-engineered
 * pre-filtering and graduated ring coverage validation.</p>
 *
 * @see SeaLevelCalculator
 * @see SimpleBasinDetector
 * @see ElevationProbabilityCalculator
 */
public class BasinWaterLevelGrid {

    private final Map<GridPosition, Integer> gridCache;
    private final SimpleBasinDetector basinDetector;
    private final ElevationProbabilityCalculator probabilityCalculator;
    private final NoiseRouter noiseRouter;
    private final TerrainGenerator terrainGenerator;
    private final WaterGenerationConfig config;
    private final long seed;

    // Simplified debug statistics (removed pre-filter stats)
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger failedEarlyElevation = new AtomicInteger(0);
    private final AtomicInteger failedClimate = new AtomicInteger(0);
    private final AtomicInteger failedRim = new AtomicInteger(0);
    private final AtomicInteger failedProbability = new AtomicInteger(0);
    private final AtomicInteger failedTerrainValidation = new AtomicInteger(0);
    private final AtomicInteger failedMaxDepth = new AtomicInteger(0);
    private final AtomicInteger succeeded = new AtomicInteger(0);

    /**
     * Creates basin water level grid.
     *
     * @param noiseRouter Noise router for parameter sampling
     * @param terrainGenerator Terrain generator for height calculation
     * @param config Water generation configuration
     * @param seed World seed for deterministic random
     */
    public BasinWaterLevelGrid(NoiseRouter noiseRouter, TerrainGenerator terrainGenerator,
                               WaterGenerationConfig config, long seed) {
        // Initialize LRU cache with size limit to prevent unbounded growth
        // access-order = true enables LRU behavior
        this.gridCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<GridPosition, Integer> eldest) {
                return size() > config.maxGridCacheSize;
            }
        };
        this.noiseRouter = noiseRouter;
        this.terrainGenerator = terrainGenerator;
        this.config = config;
        this.seed = seed;

        // Initialize simple basin detector (KISS approach - single ring sampling)
        this.basinDetector = new SimpleBasinDetector(
            noiseRouter,
            terrainGenerator,
            config
        );

        // Initialize elevation probability calculator
        this.probabilityCalculator = new ElevationProbabilityCalculator(
            config.elevationDecayRate,
            config.basinMinimumElevation
        );
    }

    /**
     * Gets basin water level for a column, or -1 if no water.
     *
     * <p>Only operates on terrain >= basinMinimumElevation (66 default).
     * Applies climate filtering, rim detection, and elevation falloff.</p>
     *
     * <p><strong>Performance:</strong> O(1) amortized due to grid caching.
     * First access to a grid cell is O(N²) for rim detection, subsequent
     * accesses within the same grid cell are O(1).</p>
     *
     * @param worldX World X coordinate
     * @param worldZ World Z coordinate
     * @param terrainHeight Actual terrain height at this position
     * @param temperature Temperature at this position [0.0-1.0]
     * @param humidity Humidity at this position [0.0-1.0]
     * @return Basin water level (Y coordinate), or -1 if no water
     */
    public int getWaterLevel(int worldX, int worldZ, int terrainHeight,
                            float temperature, float humidity) {
        totalRequests.incrementAndGet();

        // Only operate on terrain >= basin minimum elevation
        if (terrainHeight < config.basinMinimumElevation) {
            failedEarlyElevation.incrementAndGet();
            return -1; // Too low for basin water (sea level handles this)
        }

        // Climate filters (early exit for performance)
        if (humidity < config.minimumMoisture) {
            failedClimate.incrementAndGet();
            return -1; // Too dry
        }

        if (temperature <= 0.1f || temperature >= 0.95f) {
            failedClimate.incrementAndGet();
            return -1; // Too extreme
        }

        // Calculate grid cell coordinates
        int gridX = Math.floorDiv(worldX, config.waterGridResolution);
        int gridZ = Math.floorDiv(worldZ, config.waterGridResolution);

        // Local position within grid cell [0.0 - 1.0]
        float localX = (worldX - gridX * config.waterGridResolution) / (float)config.waterGridResolution;
        float localZ = (worldZ - gridZ * config.waterGridResolution) / (float)config.waterGridResolution;

        // Get 4 corner grid values (cached or calculated)
        int water00 = getOrCalculateGridValue(gridX, gridZ);
        int water10 = getOrCalculateGridValue(gridX + 1, gridZ);
        int water01 = getOrCalculateGridValue(gridX, gridZ + 1);
        int water11 = getOrCalculateGridValue(gridX + 1, gridZ + 1);

        // Bilinear interpolation
        int waterLevel = bilinearInterpolate(water00, water10, water01, water11, localX, localZ);

        // No water at this location
        if (waterLevel <= 0) {
            return -1;
        }

        // Verify water is above terrain (sanity check)
        // Allow waterLevel == terrainHeight on basin edges (water fills from terrainHeight)
        if (waterLevel < terrainHeight) {
            failedTerrainValidation.incrementAndGet();
            return -1;
        }

        // Validate interpolation didn't create unrealistic depth
        int actualDepth = waterLevel - terrainHeight;
        if (actualDepth > config.maxWaterDepth) {
            failedMaxDepth.incrementAndGet();
            return -1; // Interpolation artifact - reject
        }

        // Apply depth cap (safety fallback)
        int maxWaterLevel = terrainHeight + config.maxWaterDepth;
        return Math.min(waterLevel, maxWaterLevel);
    }

    /**
     * Gets a cached grid value or calculates it if not cached.
     *
     * <p>Uses LRU cache with access-order LinkedHashMap. NOT thread-safe - assumes
     * single-threaded access per world instance.</p>
     *
     * @param gridX Grid X coordinate
     * @param gridZ Grid Z coordinate
     * @return Basin water level at grid point, or -1 if no water
     */
    private int getOrCalculateGridValue(int gridX, int gridZ) {
        GridPosition key = new GridPosition(gridX, gridZ);
        return gridCache.computeIfAbsent(key, _ -> calculateGridWaterLevel(gridX, gridZ));
    }

    /**
     * Calculates basin water level at a grid point using simple single-ring sampling.
     *
     * <p><strong>Simplified Algorithm (KISS):</strong></p>
     * <ol>
     *     <li>Apply elevation probability falloff (exponential decay)</li>
     *     <li>Detect basin using simple single-ring sampling (16 samples)</li>
     *     <li>Return water level or -1 if no valid basin</li>
     * </ol>
     *
     * @param gridX Grid X coordinate
     * @param gridZ Grid Z coordinate
     * @return Basin water level at grid point, or -1 if no water
     */
    private int calculateGridWaterLevel(int gridX, int gridZ) {
        // World position at center of grid cell
        int centerX = gridX * config.waterGridResolution + config.waterGridResolution / 2;
        int centerZ = gridZ * config.waterGridResolution + config.waterGridResolution / 2;

        // DEBUG: Log first 20 grid calculations to diagnose lake issues
        boolean shouldLog = (failedRim.get() + succeeded.get()) < 20;
        if (shouldLog) {
            System.out.printf("[DEBUG] Grid (%d,%d) at world (%d,%d)%n", gridX, gridZ, centerX, centerZ);
        }

        // Apply elevation probability falloff BEFORE basin detection
        // Calculate terrain height at center to evaluate probability
        int centerTerrainHeight = TerrainCalculations.calculateTerrainHeight(
            centerX, centerZ, noiseRouter, terrainGenerator
        );

        boolean shouldGenerate = probabilityCalculator.shouldGenerateWater(
            centerTerrainHeight, centerX, centerZ, seed
        );

        if (!shouldGenerate) {
            failedProbability.incrementAndGet();
            if (shouldLog) System.out.printf("  REJECTED: Probability check failed at y=%d%n", centerTerrainHeight);
            return -1; // Filtered by elevation probability
        }

        // Detect basin using simple single-ring sampling
        int waterLevel = basinDetector.detectBasinWaterLevel(centerX, centerZ);

        if (shouldLog) {
            System.out.printf("  Basin detection: %s%n", waterLevel > 0 ?
                String.format("waterLevel=%d", waterLevel) :
                "NO BASIN");
        }

        // No basin found (too shallow depth check failed)
        if (waterLevel <= 0) {
            failedRim.incrementAndGet();
            if (shouldLog) {
                String failureReason = basinDetector.getLastFailureReason();
                System.out.printf("  REJECTED: %s%n", failureReason != null ? failureReason : "No basin found");
            }
            return -1;
        }

        succeeded.incrementAndGet();
        if (shouldLog) System.out.printf("  SUCCESS: Water level = %d%n", waterLevel);
        return waterLevel;
    }

    /**
     * Bilinear interpolation between 4 grid corner values.
     *
     * <p>Handles mixed water/no-water boundaries using nearest-neighbor.
     * Uses smooth interpolation for all-water regions.</p>
     *
     * <p><strong>Algorithm:</strong></p>
     * <ul>
     *     <li>If all corners are -1 (no water): return -1</li>
     *     <li>If mixed water/no-water: use nearest neighbor (preserves sharp edges)</li>
     *     <li>If all corners have water: standard bilinear interpolation (smooth water)</li>
     * </ul>
     *
     * @param v00 Grid value at (gridX, gridZ)
     * @param v10 Grid value at (gridX+1, gridZ)
     * @param v01 Grid value at (gridX, gridZ+1)
     * @param v11 Grid value at (gridX+1, gridZ+1)
     * @param tx Local X position [0.0-1.0]
     * @param tz Local Z position [0.0-1.0]
     * @return Interpolated water level, or -1 if no water
     */
    private int bilinearInterpolate(int v00, int v10, int v01, int v11, float tx, float tz) {
        return GridInterpolation.bilinearInterpolate(v00, v10, v01, v11, tx, tz);
    }

    /**
     * Clears the grid cache.
     *
     * <p>Should be called when switching worlds or when memory pressure is high.</p>
     */
    public void clearCache() {
        gridCache.clear();
    }

    /**
     * Gets the current cache size (number of cached grid points).
     *
     * @return Number of cached grid points
     */
    @SuppressWarnings("unused") // Useful for debugging/monitoring
    public int getCacheSize() {
        return gridCache.size();
    }

    /**
     * Logs simplified basin detection statistics.
     *
     * <p>Outputs rejection rates for each filter:</p>
     * <ul>
     *     <li>Climate Filter: humidity and temperature checks</li>
     *     <li>Probability Check: elevation-based probability falloff</li>
     *     <li>Basin Detection: single-ring sampling with depth check</li>
     *     <li>SUCCESS: basins that passed all filters</li>
     * </ul>
     */
    public void logStats() {
        int total = totalRequests.get();
        if (total == 0) {
            System.out.println("Basin Detection Stats: No requests yet");
            return;
        }

        // Calculate success rate for eligible terrain (excludes early elevation filter)
        int eligibleRequests = total - failedEarlyElevation.get();
        double successRate = eligibleRequests > 0 ?
            (100.0 * succeeded.get() / eligibleRequests) : 0.0;

        System.out.println("========================================");
        System.out.println("=== Simplified Basin Detection Stats ===");
        System.out.println("========================================");
        System.out.printf("Total Requests:      %,9d\n", total);
        System.out.printf("Eligible Terrain:    %,9d (y >= %d)\n", eligibleRequests, config.basinMinimumElevation);
        System.out.printf("Success Rate:        %9.2f%% of eligible terrain\n\n", successRate);

        System.out.println("--- Rejection Breakdown ---");
        System.out.printf("  Early Elevation:   %,9d (%5.1f%%) - terrain < y=%d\n",
            failedEarlyElevation.get(), 100.0 * failedEarlyElevation.get() / total, config.basinMinimumElevation);
        System.out.printf("  Climate Filter:    %,9d (%5.1f%%) - humidity < %.2f or temp extreme\n",
            failedClimate.get(), 100.0 * failedClimate.get() / total, config.minimumMoisture);
        System.out.printf("  Probability Check: %,9d (%5.1f%%) - elevation falloff (decay=%.3f)\n",
            failedProbability.get(), 100.0 * failedProbability.get() / total, config.elevationDecayRate);
        System.out.printf("  Basin Detection:   %,9d (%5.1f%%) - depth < %d blocks\n",
            failedRim.get(), 100.0 * failedRim.get() / total, config.minimumRimDepth);
        System.out.printf("  Terrain Validation:%,9d (%5.1f%%) - waterLevel < terrainHeight\n",
            failedTerrainValidation.get(), 100.0 * failedTerrainValidation.get() / total);
        System.out.printf("  Max Depth Exceeded:%,9d (%5.1f%%) - depth > %d blocks\n",
            failedMaxDepth.get(), 100.0 * failedMaxDepth.get() / total, config.maxWaterDepth);
        System.out.println();
        System.out.printf("  SUCCESS:           %,9d (%5.1f%%) - lakes generated!\n",
            succeeded.get(), 100.0 * succeeded.get() / total);

        System.out.println("\n--- Current Configuration ---");
        System.out.println(config);
        System.out.println("========================================");
    }

}
