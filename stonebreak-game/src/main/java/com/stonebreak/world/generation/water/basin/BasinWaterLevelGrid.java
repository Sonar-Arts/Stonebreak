package com.stonebreak.world.generation.water.basin;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.config.WaterGenerationConfig;
import com.stonebreak.world.generation.noise.NoiseRouter;
import com.stonebreak.world.generation.water.GridPosition;
import com.stonebreak.world.generation.utils.GridInterpolation;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Grid-based basin water level calculator for elevated lakes/ponds.
 *
 * <p>Only operates on terrain >= basinMinimumElevation (66 default).
 * Uses rim detection to find lowest spillover point, ensuring water doesn't
 * unrealistically spill over basin edges.</p>
 *
 * <p><strong>Key Features:</strong></p>
 * <ul>
 *     <li>Rim detection (finds lowest spillover point via {@link RimDetector})</li>
 *     <li>Climate filtering (moisture >= 0.3, temp 0.1-0.95)</li>
 *     <li>Elevation falloff (exponential decay for high basins via {@link ElevationProbabilityCalculator})</li>
 *     <li>Grid-based calculation (256-block resolution default)</li>
 *     <li>Bilinear interpolation for smooth water surfaces</li>
 *     <li>Thread-safe caching (ConcurrentHashMap)</li>
 * </ul>
 *
 * <p><strong>Algorithm:</strong></p>
 * <pre>
 * For each column:
 *   1. Early exit if terrain &lt; 66 or bad climate
 *   2. Calculate grid cell coordinates
 *   3. Get/interpolate 4 corner grid values (cached)
 *   4. Each grid value calculated via:
 *      - Rim detection (find lowest spillover point)
 *      - Depth validation (>= 3 blocks)
 *      - Elevation probability (exponential decay)
 *   5. Bilinear interpolation -> final water level
 * </pre>
 *
 * <p><strong>Design:</strong> Follows Single Responsibility Principle - only calculates
 * basin water levels. Part of the two-tiered water generation system.</p>
 *
 * @see SeaLevelCalculator
 * @see RimDetector
 * @see ElevationProbabilityCalculator
 */
public class BasinWaterLevelGrid {

    private final ConcurrentHashMap<GridPosition, Integer> gridCache;
    private final RimDetector rimDetector;
    private final ElevationProbabilityCalculator probabilityCalculator;
    private final WaterGenerationConfig config;
    private final long seed;

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
        this.gridCache = new ConcurrentHashMap<>();
        this.config = config;
        this.seed = seed;

        // Initialize rim detector
        this.rimDetector = new RimDetector(
            noiseRouter,
            terrainGenerator,
            config.waterGridResolution,
            config.gridSampleResolution
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
        // Only operate on terrain >= basin minimum elevation
        if (terrainHeight < config.basinMinimumElevation) {
            return -1; // Too low for basin water (sea level handles this)
        }

        // Climate filters (early exit for performance)
        if (humidity < config.minimumMoisture) {
            return -1; // Too dry
        }

        if (temperature <= 0.1f || temperature >= 0.95f) {
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
        if (waterLevel <= terrainHeight) {
            return -1;
        }

        // Cap water depth
        int maxWaterLevel = terrainHeight + config.maxWaterDepth;
        return Math.min(waterLevel, maxWaterLevel);
    }

    /**
     * Gets a cached grid value or calculates it if not cached.
     *
     * <p>Thread-safe via {@link ConcurrentHashMap#computeIfAbsent(Object, java.util.function.Function)}.</p>
     *
     * @param gridX Grid X coordinate
     * @param gridZ Grid Z coordinate
     * @return Basin water level at grid point, or -1 if no water
     */
    private int getOrCalculateGridValue(int gridX, int gridZ) {
        GridPosition key = new GridPosition(gridX, gridZ);
        return gridCache.computeIfAbsent(key, k -> calculateGridWaterLevel(gridX, gridZ));
    }

    /**
     * Calculates basin water level at a grid point using rim detection.
     *
     * <p><strong>Algorithm:</strong></p>
     * <ol>
     *     <li>Detect basin rim (lowest spillover point)</li>
     *     <li>Check basin depth >= minimum threshold (3 blocks default)</li>
     *     <li>Check basin center >= minimum elevation (66 default)</li>
     *     <li>Apply elevation probability falloff (exponential decay)</li>
     *     <li>Return rim height as water level</li>
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

        // Detect basin rim
        RimDetector.BasinRimInfo rimInfo = rimDetector.detectRim(centerX, centerZ);

        // No rim found (open depression or flat area)
        if (rimInfo == null) {
            return -1;
        }

        // Check minimum basin depth
        if (!rimInfo.meetsMinimumDepth(config.basinMinimumDepth)) {
            return -1; // Too shallow
        }

        // Only operate on basins at or above minimum elevation
        if (rimInfo.centerHeight() < config.basinMinimumElevation) {
            return -1; // Basin center too low (sea level handles this)
        }

        // Apply elevation probability falloff
        int waterLevel = rimInfo.rimHeight(); // Fill to rim
        boolean shouldGenerate = probabilityCalculator.shouldGenerateWater(
            waterLevel, centerX, centerZ, seed
        );

        if (!shouldGenerate) {
            return -1; // Filtered by elevation probability
        }

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
    public int getCacheSize() {
        return gridCache.size();
    }
}
