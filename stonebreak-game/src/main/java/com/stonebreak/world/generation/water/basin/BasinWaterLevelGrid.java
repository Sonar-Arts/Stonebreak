package com.stonebreak.world.generation.water.basin;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.water.GridPosition;
import com.stonebreak.world.generation.utils.GridInterpolation;
import com.stonebreak.world.generation.utils.TerrainCalculations;

import java.util.concurrent.ConcurrentHashMap;
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
 *     <li>Permanent per-grid-cell cache (computed once, stored for world lifetime)</li>
 * </ul>
 *
 * <p><strong>Algorithm:</strong></p>
 * <pre>
 * For each column:
 *   1. Early exit if terrain &lt; 66 or bad climate
 *   2. Calculate grid cell coordinates
 *   3. Get/interpolate 4 corner grid values (permanently cached)
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

    // Permanent cache: grid cell → detection result (computed once, stored for world lifetime)
    private final ConcurrentHashMap<GridPosition, BasinDetectionResult> resultCache;
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
        // Permanent result cache: each grid cell is computed once and stored for the world's lifetime.
        // ConcurrentHashMap ensures thread-safe access from parallel chunk generation threads,
        // and computeIfAbsent guarantees the detection function runs at most once per key.
        this.resultCache = new ConcurrentHashMap<>();

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
     * <p><strong>Performance:</strong> O(1) after first access. First access to a grid cell
     * runs basin detection (O(N²) for rim sampling); result is then stored permanently —
     * all subsequent accesses for that cell are O(1) with no re-detection.</p>
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

        // Get 4 corner grid values (computed once per cell, permanently cached)
        int water00 = getOrCalculateResult(gridX,     gridZ    ).waterLevel();
        int water10 = getOrCalculateResult(gridX + 1, gridZ    ).waterLevel();
        int water01 = getOrCalculateResult(gridX,     gridZ + 1).waterLevel();
        int water11 = getOrCalculateResult(gridX + 1, gridZ + 1).waterLevel();

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
     * Gets the cached result for a grid cell, computing it exactly once if not yet cached.
     *
     * <p>Uses {@link ConcurrentHashMap#computeIfAbsent}, which is atomic — the detection
     * function runs at most once per key even under concurrent chunk generation. The result
     * is stored permanently for the lifetime of the world instance (no eviction).</p>
     *
     * @param gridX Grid X coordinate
     * @param gridZ Grid Z coordinate
     * @return Cached or freshly computed {@link BasinDetectionResult} for this grid cell
     */
    private BasinDetectionResult getOrCalculateResult(int gridX, int gridZ) {
        GridPosition key = new GridPosition(gridX, gridZ);
        return resultCache.computeIfAbsent(key, _ -> calculateGridResult(gridX, gridZ));
    }

    /**
     * Computes the basin detection result for a grid cell.
     *
     * <p>Called at most once per grid cell — the result is permanently stored in
     * {@code resultCache} by {@link #getOrCalculateResult}.</p>
     *
     * <p><strong>Algorithm (KISS):</strong></p>
     * <ol>
     *     <li>Apply elevation probability falloff (exponential decay)</li>
     *     <li>Detect basin using single-ring sampling with adaptive radius widening</li>
     *     <li>Return the full {@link BasinDetectionResult} (including {@code noBasin()} sentinels)</li>
     * </ol>
     *
     * @param gridX Grid X coordinate
     * @param gridZ Grid Z coordinate
     * @return Detection result — {@link BasinDetectionResult#noBasin()} if rejected, otherwise full metadata
     */
    private BasinDetectionResult calculateGridResult(int gridX, int gridZ) {
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
            return BasinDetectionResult.noBasin();
        }

        // Detect basin using adaptive ring radius widening
        BasinDetectionResult result = basinDetector.detectBasinWaterLevelWithAdaptiveRadius(centerX, centerZ);

        if (shouldLog) {
            System.out.printf("  Basin detection: %s%n", result.hasBasin() ?
                String.format("waterLevel=%d", result.waterLevel()) :
                "NO BASIN");
        }

        // No basin found
        if (!result.hasBasin()) {
            failedRim.incrementAndGet();
            if (shouldLog) {
                String failureReason = basinDetector.getLastFailureReason();
                System.out.printf("  REJECTED: %s%n", failureReason != null ? failureReason : "No basin found");
            }
            return BasinDetectionResult.noBasin();
        }

        // Reject valleys (aspect ratio based)
        if (result.isValley()) {
            failedRim.incrementAndGet();
            if (shouldLog) {
                System.out.printf("  REJECTED: Valley detected (depth=%d > width/3=%d)%n",
                    result.getBasinDepth(), (result.detectionRadius() * 2) / 3);
            }
            return BasinDetectionResult.noBasin();
        }

        succeeded.incrementAndGet();
        if (shouldLog) System.out.printf("  SUCCESS: Water level = %d (radius=%d, depth=%d)%n",
            result.waterLevel(), result.detectionRadius(), result.getBasinDepth());
        return result;
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
     * Gets cached basin detection metadata for a grid cell.
     *
     * <p>Used for adaptive validation radius calculation in BasinWaterFiller.
     * Returns metadata including detection radius, depth, and basin regularity.</p>
     *
     * @param gridX Grid X coordinate
     * @param gridZ Grid Z coordinate
     * @return BasinDetectionResult with metadata if cached, noBasin() if not found
     */
    public BasinDetectionResult getBasinMetadata(int gridX, int gridZ) {
        GridPosition key = new GridPosition(gridX, gridZ);
        return resultCache.getOrDefault(key, BasinDetectionResult.noBasin());
    }

    /**
     * Clears the result cache.
     *
     * <p>Should be called when switching worlds so the new world starts fresh.</p>
     */
    public void clearCache() {
        resultCache.clear();
    }

    /**
     * Gets the current cache size (number of cached grid points).
     *
     * @return Number of cached grid points
     */
    @SuppressWarnings("unused") // Useful for debugging/monitoring
    public int getCacheSize() {
        return resultCache.size();
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
